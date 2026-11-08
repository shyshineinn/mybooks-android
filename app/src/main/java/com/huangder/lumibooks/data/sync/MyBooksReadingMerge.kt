package com.huangder.lumibooks.data.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal val readingSections = listOf("positions", "bookmarks", "notes", "reading_records", "tombstones")
internal fun emptyMyBooksReading() = JSONObject().apply { readingSections.forEach { put(it, JSONArray()) } }
internal fun readingItems(data: JSONObject, section: String): List<JSONObject> {
    val array = data.getJSONArray(section)
    return (0 until array.length()).map { array.getJSONObject(it) }
}
internal fun readingItemKey(section: String, item: JSONObject): String = when (section) {
    "positions" -> item.getString("format") + ":" + item.getString("fileSha256")
    "reading_records" -> item.getString("date") + ":" + item.getString("sourceDeviceId")
    "tombstones" -> item.getString("namespace") + ":" + item.getString("itemId")
    else -> item.getString("syncId")
}

/** Entity IDs and item modification clocks are not user content. Float coordinates round-trip through Room. */
internal fun readingCanonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().filterNot {
        // Older servers omit zero listening time, while the portable serializer writes it.
        it == "updatedAt" || it == "listeningDuration" && value.optLong(it, -1L) == 0L
    }.sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + readingCanonical(value.get(it))
    }
    is JSONArray -> (0 until value.length()).map { readingCanonical(value.get(it)) }.sorted().joinToString(",", "[", "]")
    is Float, is Double -> String.format(Locale.ROOT, "%.7f", (value as Number).toDouble()).trimEnd('0').trimEnd('.')
    is Number, is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
}

internal fun validateMyBooksReading(data: JSONObject) {
    require(data.keys().asSequence().toSet() == readingSections.toSet())
    readingSections.forEach { section ->
        val items = readingItems(data, section)
        require(items.size <= 10000 && items.map { readingItemKey(section, it) }.distinct().size == items.size)
        items.forEach { require(readingItemKey(section, it).isNotBlank()) }
    }
    readingItems(data, "reading_records").forEach {
        if (it.has("listeningDuration")) require(it.getLong("listeningDuration") >= 0)
    }
    readingItems(data, "positions").forEach {
        require(it.getDouble("readingProgress").isFinite() && it.getDouble("readingProgress") in 0.0..1.0)
        require(it.getLong("lastReadTime") >= 0 && it.getString("format") in setOf("EPUB","PDF","TXT","MOBI","AZW","AZW3","CBZ","MARKDOWN"))
        require(it.getString("fileSha256").isEmpty() || it.getString("fileSha256").matches(Regex("[a-f0-9]{64}")))
    }
    for ((section, namespace) in listOf("bookmarks" to "bookmark", "notes" to "note")) {
        val live = readingItems(data, section).map { it.getString("syncId") }.toSet()
        require(readingItems(data, "tombstones").none { it.getString("namespace") == namespace && it.getString("itemId") in live })
    }
}

internal data class MyBooksReadingMerge(val data: JSONObject, val conflicts: List<String>)
internal class MyBooksReadingConflict(message: String) : Exception(message)

/** Three-way item merge. Concurrent edits/deletions require a choice instead of comparing device clocks. */
internal fun mergeMyBooksReading(base: JSONObject, local: JSONObject, remote: JSONObject, useLocal: Boolean? = null): MyBooksReadingMerge {
    listOf(base, local, remote).forEach(::validateMyBooksReading)
    val result = emptyMyBooksReading()
    val conflicts = mutableListOf<String>()
    fun choose(key: String, b: JSONObject?, l: JSONObject?, r: JSONObject?): JSONObject? {
        val lc = readingCanonical(l); val rc = readingCanonical(r); val bc = readingCanonical(b)
        return when {
            lc == rc -> l ?: r
            lc == bc -> r
            rc == bc -> l
            l?.optBoolean("_deleted") == true && r?.optBoolean("_deleted") == true ->
                if (l.optLong("deletedAt") >= r.optLong("deletedAt")) l else r
            else -> { conflicts += key; if (useLocal == false) r else l }
        }
    }
    val basePos = readingItems(base, "positions").associateBy { readingItemKey("positions", it) }
    val localPos = readingItems(local, "positions").associateBy { readingItemKey("positions", it) }
    val remotePos = readingItems(remote, "positions").associateBy { readingItemKey("positions", it) }
    for (key in (basePos.keys + localPos.keys + remotePos.keys).sorted()) {
        choose("positions:$key", basePos[key], localPos[key] ?: basePos[key], remotePos[key] ?: basePos[key])
            ?.let { result.getJSONArray("positions").put(it) }
    }
    for ((section, namespace) in listOf("bookmarks" to "bookmark", "notes" to "note")) {
        fun states(data: JSONObject): Map<String, JSONObject> {
            val items = readingItems(data, section).associateBy { it.getString("syncId") }.toMutableMap()
            readingItems(data, "tombstones").filter { it.getString("namespace") == namespace }.forEach {
                items[it.getString("itemId")] = JSONObject(it.toString()).put("_deleted", true)
            }
            return items
        }
        val b = states(base); val l = states(local); val r = states(remote)
        for (key in (b.keys + l.keys + r.keys).sorted()) {
            val picked = choose("$section:$key", b[key], l[key] ?: b[key], r[key] ?: b[key]) ?: continue
            if (picked.optBoolean("_deleted")) {
                result.getJSONArray("tombstones").put(JSONObject(picked.toString()).apply { remove("_deleted") })
            } else result.getJSONArray(section).put(picked)
        }
    }
    val records = (readingItems(base, "reading_records") + readingItems(local, "reading_records") + readingItems(remote, "reading_records"))
        .groupBy { readingItemKey("reading_records", it) }
    for ((_, values) in records.toSortedMap()) {
        val newest = values.maxBy { it.optLong("duration") }
        result.getJSONArray("reading_records").put(JSONObject(newest.toString()).apply {
            put("duration", values.maxOf { it.getLong("duration") })
            val listening = values.maxOf { it.optLong("listeningDuration") }
            if (listening > 0) put("listeningDuration", listening) else remove("listeningDuration")
            put("startTime", values.minOf { it.getLong("startTime") })
            put("endTime", values.maxOf { it.getLong("endTime") })
            put("updatedAt", values.maxOf { it.getLong("updatedAt") })
        })
    }
    return MyBooksReadingMerge(result, conflicts)
}
