package com.huangder.lumibooks.data.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 电子书文件条目，对应 my-books `book_files` 表。 */
data class RemoteBookFile(
    val format: String,
    val path: String,
    val size: Long,
    val downloadUrl: String? = null
)

/**
 * my-books `/api/books` 返回的单本书（只取 Android 端需要的字段）。
 * 阅读进度、笔记等全部留在本地，不参与任何回传。
 */
data class RemoteBook(
    val id: Long,
    val title: String,
    val author: String,
    val status: String,
    val summary: String,
    val tags: List<String>,
    val coverUrl: String?,
    val rating: Double?,
    val seriesLabel: String?,
    val files: List<RemoteBookFile>,
    val updatedAt: String? = null,
    val createdAt: String? = null,
    val hasNotes: Boolean = false,
    val notesSize: Long = 0L,
    val notesUpdatedAt: String? = null,
    val uuid: String? = null,
    val revision: Long? = null
) {
    fun fileFor(format: String): RemoteBookFile? =
        files.firstOrNull { it.format.equals(format, ignoreCase = true) }
}

object RemoteBookFormats {
    /** Lumi 的阅读器支持这些格式；AZW3 暂不支持。 */
    val supported = setOf("EPUB", "PDF", "TXT", "MOBI", "AZW", "AZW3", "CBZ", "MD", "MARKDOWN")

    private val preferredOrder = listOf("EPUB", "PDF", "MOBI", "AZW", "AZW3", "TXT", "CBZ", "MD")

    fun isSupported(format: String): Boolean =
        format.uppercase() in supported

    fun preferredFile(files: List<RemoteBookFile>): RemoteBookFile? =
        files.filter { isSupported(it.format) }
            .minByOrNull { preferredOrderIndex(it.format) }

    internal fun preferredOrderIndex(format: String): Int =
        preferredOrder.indexOf(format.uppercase()).takeIf { it >= 0 } ?: preferredOrder.size

    fun displayFormat(format: String): String =
        if (format.equals("MARKDOWN", true) || format.equals("MD", true)) "MD" else format.uppercase()
}

/**
 * 服务器书作者的缺省值。
 *
 * 服务端允许作者为空，这里统一成"未知作者"：本地行、合并基线、服务器详情
 * 三处都用同一个值，否则空作者会被当成"本地改动"推回服务器。
 */
internal const val UNKNOWN_REMOTE_AUTHOR = "未知作者"

/** 服务端作者为空时补上缺省值，与 [parseRemoteBook] 保持一致。 */
internal fun normalizeRemoteAuthor(raw: String?): String =
    raw?.trim().orEmpty().ifBlank { UNKNOWN_REMOTE_AUTHOR }

internal fun parseRemoteBooks(json: String, baseUrl: String): List<RemoteBook> {
    val array = JSONArray(json)
    val books = ArrayList<RemoteBook>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        books += item.toRemoteBook(baseUrl)
    }
    return books
}

/** 解析 `GET /api/books/{id}` 的单本详情 JSON。 */
internal fun parseRemoteBook(json: String, baseUrl: String): RemoteBook =
    JSONObject(json).toRemoteBook(baseUrl)

private fun JSONObject.toRemoteBook(baseUrl: String): RemoteBook {
    val files = parseRemoteFiles(this)

    val summary = optTextOrEmpty("summary")
    val rawCover = optTextOrNull("cover")
    val rating = optDouble("rating", Double.NaN).takeUnless { it.isNaN() }

    return RemoteBook(
        id = optLong("id", 0L),
        title = optTextOrEmpty("title").ifBlank { "未命名" },
        author = normalizeRemoteAuthor(optTextOrEmpty("author")),
        status = optTextOrEmpty("status").ifBlank { "wish" },
        summary = summary,
        tags = parseRemoteTags(opt("tags")),
        coverUrl = rawCover?.let { resolveUrl(baseUrl, it) },
        rating = rating,
        seriesLabel = optTextOrNull("series_label"),
        files = files,
        updatedAt = optTextOrNull("updated_at"),
        createdAt = optTextOrNull("created_at"),
        hasNotes = optBoolean("has_notes", false),
        notesSize = optLong("notes_size", 0L),
        notesUpdatedAt = optTextOrNull("notes_updated_at"),
        uuid = optTextOrNull("uuid"),
        revision = optLong("revision", 0L).takeIf { it > 0 }
    )
}

internal fun parseRemoteFiles(obj: JSONObject): List<RemoteBookFile> =
    obj.optJSONArray("files")?.let { array ->
        buildList {
            for (i in 0 until array.length()) {
                val file = array.optJSONObject(i) ?: continue
                val format = file.optString("format").uppercase()
                val path = file.optString("path")
                if (format.isBlank() || path.isBlank()) continue
                add(RemoteBookFile(format = format, path = path, size = file.optLong("size", 0L),
                    downloadUrl = file.optTextOrNull("download_url")))
            }
        }
    }.orEmpty()

/**
 * 服务端把 tags 以 JSON 字符串返回（`"[\"中国\",\"经济\"]"`）。先按 JSON 解码；
 * 旧数据才退回逗号切分，并且只清理 JSON 语法残留，保留标签内部的引号/逗号等符号。
 */
internal fun parseRemoteTags(value: Any?): List<String> {
    val raw = when (value) {
        is JSONArray -> value.tagStrings()
        is String -> value.toTagStrings()
        else -> emptyList()
    }
    val seen = HashSet<String>()
    return raw.asSequence()
        .map(::cleanServerTagName)
        .filter { it.isNotEmpty() }
        .filter { seen.add(it.lowercase(Locale.ROOT)) }
        .toList()
}

private fun JSONArray.tagStrings(): List<String> =
    (0 until length()).mapNotNull { index ->
        optString(index).takeIf { it.isNotBlank() }
    }

private fun String.toTagStrings(): List<String> {
    val text = trim().removePrefix("\uFEFF").trim()
    if (text.isEmpty()) return emptyList()
    text.asJsonTagArray()?.let { return it }
    // 兼容历史上被二次编码的 JSON 字符串。
    val unescaped = text.replace("\\\"", "\"").replace("\\\\", "\\")
    if (unescaped != text) unescaped.asJsonTagArray()?.let { return it }
    return splitLegacyTagString(unescaped)
}

private fun String.asJsonTagArray(): List<String>? =
    runCatching { JSONArray(this) }.getOrNull()?.tagStrings()

private fun splitLegacyTagString(text: String): List<String> {
    val body = text.trim().removePrefix("[").removeSuffix("]")
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    for (char in body) {
        if (escaped) {
            current.append(char)
            escaped = false
            continue
        }
        if (char == '\\') {
            escaped = true
            continue
        }
        if (quote != null) {
            if (char == quote) quote = null
            current.append(char)
            continue
        }
        when (char) {
            '"', '\'' -> {
                quote = char
                current.append(char)
            }
            ',', '，', '、' -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
    }
    parts += current.toString()
    return parts.map { part ->
        part.trim()
            .removePrefix("[").removeSuffix("]")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }
}

private fun cleanServerTagName(raw: String): String =
    raw.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun resolveUrl(baseUrl: String, path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = baseUrl.trim().trimEnd('/')
    return if (path.startsWith('/')) "$base$path" else "$base/$path"
}
