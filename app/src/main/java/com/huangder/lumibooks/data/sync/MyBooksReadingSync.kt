package com.huangder.lumibooks.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.backup.*
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.*
import com.huangder.lumibooks.util.BookFileAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MyBooksReadingSync @Inject constructor(
    private val db: AppDatabase,
    private val identity: SyncIdentityStore,
    @ApplicationContext private val context: Context
) {
    private val client = MyBooksApiClient()
    private val dao get() = db.syncStateDao()
    private fun uuid(state: MyBooksSyncStateEntity) = runCatching { JSONObject(state.baseJson).optString("uuid") }.getOrDefault("")
    private suspend fun baseline(uuid: String): JSONObject = dao.getValue(BASE + uuid)?.let(::JSONObject)
        ?: JSONObject().put("revision", 0).put("data", emptyMyBooksReading())

    val pendingFingerprints = dao.observeReadingChangeSignal().map {
        withContext(Dispatchers.IO) { db.withTransaction {
            val result = mutableMapOf<String, String>()
            val bases = dao.getPrefix(BASE).mapNotNull { row -> runCatching {
                val data = JSONObject(row.value).getJSONObject("data"); validateMyBooksReading(data)
                row.key.removePrefix(BASE) to data
            }.getOrNull() }.toMap()
            val books = db.bookDao().getAllBooksSnapshot().associateBy { it.id }
            val rows = rows()
            for (state in db.myBooksSyncStateDao().getAll()) {
                if (state.remoteDeleted || uuid(state).isBlank()) continue
                val book = books[state.bookId] ?: continue
                val base = bases[uuid(state)] ?: emptyMyBooksReading()
                val local = capture(book, base, sourceRows = rows)
                if (readingCanonical(local) != readingCanonical(base)) result[state.bookId] =
                    MessageDigest.getInstance("SHA-256").digest(readingCanonical(local).toByteArray()).joinToString("") { "%02x".format(it) }
            }
            result.toMap()
        } }
    }.distinctUntilChanged()

    val issues = dao.observePrefix(CONFLICT).map { rows ->
        rows.mapNotNull { row ->
            val value = runCatching { JSONObject(row.value) }.getOrNull() ?: return@mapNotNull null
            val bookId = value.optString("bookId")
            val book = db.bookDao().getBookById(bookId) ?: return@mapNotNull null
            MyBooksSyncIssue(bookId, book.title, context.getString(R.string.mybooks_reading_conflict),
                canResolve = true, readingConflict = true)
        }
    }

    suspend fun hasPendingChanges(bookId: String): Boolean {
        val state = db.myBooksSyncStateDao().get(bookId) ?: return false
        return db.withTransaction {
            val book = db.bookDao().getBookById(bookId) ?: return@withTransaction false
            val base = baseline(uuid(state)).getJSONObject("data")
            readingCanonical(capture(book, base)) != readingCanonical(base) || dao.getValue(CONFLICT + uuid(state)) != null
        }
    }

    suspend fun acknowledgeRemoteDeletion(state: MyBooksSyncStateEntity) {
        dao.deleteState(CONFLICT + uuid(state))
        dao.deleteState(CHOICE + uuid(state))
    }

    suspend fun forgetBook(state: MyBooksSyncStateEntity) {
        acknowledgeRemoteDeletion(state)
        dao.deleteState(BASE + uuid(state))
    }

    suspend fun choose(bookId: String, useLocal: Boolean) {
        val state = db.myBooksSyncStateDao().get(bookId) ?: error("找不到同步书目")
        check(!state.remoteDeleted)
        dao.putState(SyncStateEntity(CHOICE + uuid(state), useLocal.toString()))
    }

    suspend fun sync(snapshot: MyBooksSyncSnapshot, excluded: Set<String> = emptySet(),
        allowUpload: Boolean = true, uploadOnly: Boolean = false, selectedBookIds: Set<String>? = null): List<Pair<String, Exception>> {
        if (!snapshot.supportsReadingSync) return emptyList()
        val heads = snapshot.reading.associateBy { it.id }
        val errors = mutableListOf<Pair<String, Exception>>()
        val pending = pendingFingerprints.first().keys
        val chosen = dao.getPrefix(CHOICE).map { it.key.removePrefix(CHOICE) }.toSet()
        val conflicts = dao.getPrefix(CONFLICT).map { it.key.removePrefix(CONFLICT) }.toSet()
        for (state in db.myBooksSyncStateDao().getAll()) {
            if (state.bookId in excluded || selectedBookIds != null && state.bookId !in selectedBookIds) continue
            if (uploadOnly && state.bookId !in pending && uuid(state) !in chosen && uuid(state) !in conflicts) continue
            if (state.remoteId !in heads && state.bookId !in pending && uuid(state) !in chosen && uuid(state) !in conflicts) continue
            if (state.remoteDeleted || uuid(state).isBlank() || db.bookDao().getBookById(state.bookId) == null) continue
            try {
                val base = baseline(uuid(state))
                val book = db.bookDao().getBookById(state.bookId) ?: continue
                val head = heads[state.remoteId]
                check(head == null || head.uuid == uuid(state)) { context.getString(R.string.mybooks_book_identity_changed) }
                val dirty = readingCanonical(capture(book, base.getJSONObject("data"))) != readingCanonical(base.getJSONObject("data"))
                if (dirty || head?.revision != null && head.revision != base.getLong("revision") ||
                    dao.getValue(CONFLICT + uuid(state)) != null || dao.getValue(CHOICE + uuid(state)) != null) {
                    syncBook(state, requireNotNull(snapshot.libraryId), allowUpload = allowUpload)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (classifyMyBooksSyncFailure(error) in setOf(MyBooksSyncFailure.AUTH, MyBooksSyncFailure.TRANSIENT)) throw error
                errors += state.bookId to error
            }
        }
        return errors
    }

    private fun checkedState(root: JSONObject, state: MyBooksSyncStateEntity): JSONObject {
        require(root.getLong("book_id") == state.remoteId && root.getString("book_uuid") == uuid(state))
        require(root.getLong("revision") >= 0)
        validateMyBooksReading(root.getJSONObject("data"))
        return root
    }

    suspend fun beforeOpen(bookId: String, library: String, timeoutMs: Long = 3_000) {
        val state = db.myBooksSyncStateDao().get(bookId) ?: return
        if (!state.remoteDeleted && uuid(state).isNotBlank()) syncBook(state, library, timeoutMs)
    }

    private suspend fun syncBook(state: MyBooksSyncStateEntity, library: String, timeoutMs: Long = 0, allowUpload: Boolean = true) {
        ensureHash(state.bookId)
        val base = baseline(uuid(state))
        var remote = checkedState(client.fetchReadingState(state.remoteId, timeoutMs), state)
        val choice = dao.getValue(CHOICE + uuid(state))?.toBooleanStrictOrNull()
        val attempts = if (timeoutMs > 0) 1 else 3
        repeat(attempts) { attempt ->
            check(remote.getLong("revision") >= base.getLong("revision")) { context.getString(R.string.mybooks_revision_rollback) }
            val local = db.withTransaction {
                capture(db.bookDao().getBookById(state.bookId) ?: error("本地书目已移除"),
                    base.getJSONObject("data"), remote.getJSONObject("data"))
            }
            val merged = mergeMyBooksReading(base.getJSONObject("data"), local, remote.getJSONObject("data"), choice)
            if (merged.conflicts.isNotEmpty() && choice == null) {
                dao.putState(SyncStateEntity(CONFLICT + uuid(state), JSONObject().put("bookId", state.bookId)
                    .put("fields", JSONArray(merged.conflicts)).toString()))
                throw MyBooksReadingConflict(context.getString(R.string.mybooks_reading_conflict))
            }
            val confirmed = try {
                if (!allowUpload || readingCanonical(merged.data) == readingCanonical(remote.getJSONObject("data"))) remote
                else checkedState(client.putReadingState(state.remoteId, library, uuid(state), remote.getLong("revision"), merged.data, timeoutMs), state)
            } catch (error: MyBooksHttpException) {
                val current = runCatching { JSONObject(error.body).optJSONObject("current") }.getOrNull()
                if (error.status != 409 || current == null || attempt == attempts - 1) throw error
                remote = checkedState(current, state)
                return@repeat
            }
            db.withTransaction {
                val shadowed = apply(state.bookId, base.getJSONObject("data"), local,
                    if (allowUpload) confirmed.getJSONObject("data") else merged.data)
                val latest = baseline(uuid(state))
                val accepted = readingItems(confirmed.getJSONObject("data"), "positions").associateBy { readingItemKey("positions", it) }
                val pending = jsonItems(latest.optJSONArray("pending_positions")).filter {
                    readingCanonical(it) != readingCanonical(accepted[readingItemKey("positions", it)])
                }
                val saved = JSONObject(confirmed.toString()).put("local_shadowed", JSONArray(shadowed.toList()))
                    .put("pending_positions", JSONArray(pending))
                dao.putState(SyncStateEntity(BASE + uuid(state), saved.toString()))
                dao.deleteState(CONFLICT + uuid(state)); dao.deleteState(CHOICE + uuid(state))
            }
            return
        }
    }

    suspend fun markDownloaded(bookId: String, filePath: String, format: String, sha256: String) = db.withTransaction {
        val book = db.bookDao().getBookById(bookId) ?: return@withTransaction
        val state = db.myBooksSyncStateDao().get(bookId)
        if (state != null && uuid(state).isNotBlank() && (fileHash(book) != sha256 || book.format != format)) {
            val root = baseline(uuid(state))
            val basePositions = readingItems(root.getJSONObject("data"), "positions").associateBy { readingItemKey("positions", it) }
            val local = capture(book, root.getJSONObject("data"))
            val pending = readingItems(local, "positions").filter {
                readingCanonical(it) != readingCanonical(basePositions[readingItemKey("positions", it)])
            }
            dao.putState(SyncStateEntity(BASE + uuid(state), root.put("book_id", state.remoteId).put("book_uuid", uuid(state))
                .put("pending_positions", JSONArray(pending)).toString()))
            db.bookDao().applyServerReadingPosition(bookId, 0f, null, 0L)
        }
        db.bookDao().markServerBookDownloaded(bookId, filePath, format, sha256)
    }

    suspend fun applyKnownProgress(bookId: String) {
        val state = db.myBooksSyncStateDao().get(bookId) ?: return
        db.withTransaction {
            val root = baseline(uuid(state))
            val data = root.getJSONObject("data")
            val book = db.bookDao().getBookById(bookId) ?: return@withTransaction
            val local = capture(book, data)
            val merged = mergeMyBooksReading(data, local, data).data
            val shadowed = apply(bookId, data, local, merged)
            dao.putState(SyncStateEntity(BASE + uuid(state), root.put("book_id", state.remoteId).put("book_uuid", uuid(state))
                .put("local_shadowed", JSONArray(shadowed.toList())).toString()))
        }
    }

    private suspend fun ensureHash(bookId: String) {
        val book = db.bookDao().getBookById(bookId) ?: return
        if (book.isCloudOnly || book.sourceSha256?.matches(Regex("[a-f0-9]{64}")) == true || book.filePath.isBlank()) return
        val hash = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            BookFileAccess.openInputStream(context, book.filePath).use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return
        db.bookDao().updateSourceSha256(bookId, hash)
    }

    private data class ReadingRows(
        val marks: Map<String, List<BookmarkEntity>>, val notes: Map<String, List<NoteEntity>>,
        val records: Map<String, List<ReadingRecordEntity>>, val tombstones: List<SyncTombstoneEntity>,
        val roots: Map<String, JSONObject>
    )
    private suspend fun rows() = ReadingRows(dao.getAllBookmarks().groupBy { it.bookId },
        dao.getAllNotes().groupBy { it.bookId }, dao.getAllReadingRecords().groupBy { it.bookId }, dao.getAllTombstones(),
        dao.getPrefix(BASE).mapNotNull { row -> runCatching { JSONObject(row.value) }.getOrNull()?.takeIf { it.opt("book_id") is Number && it.opt("data") is JSONObject } }
            .associateBy { MyBooksConfig.BOOK_ID_PREFIX + it.getLong("book_id") })

    private suspend fun capture(book: BookEntity, base: JSONObject, remote: JSONObject = emptyMyBooksReading(), sourceRows: ReadingRows? = null): JSONObject {
        val snapshot = sourceRows ?: rows()
        val device = identity.deviceId()
        val result = emptyMyBooksReading()
        val positions = readingItems(base, "positions").associateBy { readingItemKey("positions", it) }.toMutableMap()
        jsonItems(snapshot.roots[book.id]?.optJSONArray("pending_positions")).forEach { positions[readingItemKey("positions", it)] = it }
        if (book.lastReadTime > 0L) {
            val sha = fileHash(book)
            val p = JSONObject().put("format", book.format).put("fileSha256", sha).put("lastReadTime", book.lastReadTime)
                .put("readingProgress", book.readingProgress.toDouble()).put("locatorJson", book.locatorJson ?: JSONObject.NULL)
                .put("sourceDeviceId", device)
            val old = positions[readingItemKey("positions", p)]
            if (old != null && old.getLong("lastReadTime") == book.lastReadTime &&
                old.getDouble("readingProgress").toFloat() == book.readingProgress &&
                old.optTextOrNull("locatorJson") == book.locatorJson) p.put("sourceDeviceId", old.getString("sourceDeviceId"))
            positions[readingItemKey("positions", p)] = p
        }
        positions.values.forEach { result.getJSONArray("positions").put(it) }
        val marks = snapshot.marks[book.id].orEmpty()
        val notes = snapshot.notes[book.id].orEmpty()
        val shadowed = stringItems(snapshot.roots[book.id]?.optJSONArray("local_shadowed"))
        marks.forEach { result.getJSONArray("bookmarks").put(bindAnnotation(bookmarkToJson(it).apply { remove("bookId") }, book, base, "bookmarks", shadowed)) }
        notes.forEach { result.getJSONArray("notes").put(bindAnnotation(noteToJson(it).apply { remove("bookId") }, book, base, "notes", shadowed)) }
        val records = snapshot.records[book.id].orEmpty().map {
            readingRecordToJson(it).apply { remove("bookId"); if (optString("sourceDeviceId").isBlank()) put("sourceDeviceId", "legacy") }
        }.groupBy { readingItemKey("reading_records", it) }
        for ((_, items) in records) {
            val record = items.maxBy { it.getLong("duration") }
            val listening = items.maxOf { it.optLong("listeningDuration") }
            if (listening > 0) record.put("listeningDuration", listening)
            result.getJSONArray("reading_records").put(record)
        }
        for (t in snapshot.tombstones) {
            val section = when (t.namespace) { "bookmark" -> "bookmarks"; "note" -> "notes"; else -> continue }
            val known = (readingItems(base, section) + readingItems(remote, section)).map { it.getString("syncId") }.toSet() +
                (readingItems(base, "tombstones") + readingItems(remote, "tombstones"))
                    .filter { it.getString("namespace") == t.namespace }.map { it.getString("itemId") }
            val live = if (section == "bookmarks") marks.any { it.syncId == t.itemId } else notes.any { it.syncId == t.itemId }
            if (!live && t.itemId in known) result.getJSONArray("tombstones").put(tombstoneToJson(t))
        }
        return result
    }

    private fun jsonItems(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
    private fun stringItems(array: JSONArray?) = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()

    private fun annotationMatches(item: JSONObject, book: BookEntity): Boolean =
        item.optString("format", book.format) == book.format && item.optString("fileSha256") == fileHash(book)

    private fun bindAnnotation(item: JSONObject, book: BookEntity, base: JSONObject, section: String, shadowed: Set<String>): JSONObject {
        val old = readingItems(base, section).find { it.getString("syncId") == item.getString("syncId") }
        val fields = if (section == "bookmarks") listOf("chapterIndex", "position", "locatorJson")
            else listOf("chapterIndex", "startPosition", "endPosition", "startLocatorJson", "endLocatorJson")
        val moved = old != null && fields.any { readingCanonical(old.opt(it)) != readingCanonical(item.opt(it)) }
        val namespace = if (section == "bookmarks") "bookmark" else "note"
        if (old != null && !moved && old.has("format")) {
            item.put("format", old.getString("format")).put("fileSha256", old.optString("fileSha256"))
        } else item.put("format", book.format).put("fileSha256", fileHash(book))
        if (old != null && !moved && "$namespace:" + item.getString("syncId") in shadowed) {
            item.put("isStale", old.optBoolean("isStale"))
        }
        return item
    }

    private fun fileHash(book: BookEntity) = (book.sourceSha256 ?: book.remoteFileSha256)
        ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty()

    private fun annotationStates(data: JSONObject, section: String, namespace: String): Map<String, JSONObject> =
        readingItems(data, section).associateBy { it.getString("syncId") } +
            readingItems(data, "tombstones").filter { it.getString("namespace") == namespace }
                .associate { it.getString("itemId") to JSONObject(it.toString()).put("_deleted", true) }

    private suspend fun apply(bookId: String, base: JSONObject, captured: JSONObject, incoming: JSONObject): Set<String> {
        val book = db.bookDao().getBookById(bookId) ?: return emptySet()
        val shadowed = stringItems(rows().roots[bookId]?.optJSONArray("local_shadowed")).toMutableSet()
        val current = capture(book, base, incoming)
        applyPosition(book, captured, current, incoming)
        val allMarks = dao.getAllBookmarks().associateBy { it.syncId }
        val allNotes = dao.getAllNotes().associateBy { it.syncId }
        for ((section, namespace) in listOf("bookmarks" to "bookmark", "notes" to "note")) {
            val before = annotationStates(captured, section, namespace)
            val now = annotationStates(current, section, namespace)
            for ((id, item) in annotationStates(incoming, section, namespace)) {
                if (readingCanonical(before[id]) != readingCanonical(now[id])) continue
                val otherBook = if (section == "bookmarks") allMarks[id]?.bookId else allNotes[id]?.bookId
                check(otherBook == null || otherBook == bookId) { "标注身份与其他书籍冲突" }
                if (item.optBoolean("_deleted")) {
                    if (section == "bookmarks") dao.deleteBookmarksBySyncIds(listOf(id)) else dao.deleteNotesBySyncIds(listOf(id))
                    dao.upsertTombstones(listOf(tombstoneFromJson(item)))
                    shadowed.remove("$namespace:$id")
                } else {
                    val value = JSONObject(item.toString()).put("bookId", bookId)
                    if (!annotationMatches(item, book)) {
                        value.put("isStale", true); shadowed += "$namespace:$id"
                    } else shadowed.remove("$namespace:$id")
                    if (section == "bookmarks") dao.upsertBookmarks(listOf(bookmarkFromJson(value).copy(id = allMarks[id]?.id ?: 0)))
                    else dao.upsertNotes(listOf(noteFromJson(value).copy(id = allNotes[id]?.id ?: 0)))
                    dao.deleteTombstones(namespace, listOf(id))
                }
            }
        }
        val existing = dao.getAllReadingRecords().filter { it.bookId == bookId }
            .associateBy { it.date + ":" + it.sourceDeviceId.ifBlank { "legacy" } }
        val records = readingItems(incoming, "reading_records").map {
            val row = readingRecordFromJson(JSONObject(it.toString()).put("bookId", bookId))
            val old = existing[readingItemKey("reading_records", it)]
            row.copy(id = old?.id ?: 0, duration = maxOf(old?.duration ?: 0, row.duration),
                listeningDuration = maxOf(old?.listeningDuration ?: 0, row.listeningDuration),
                startTime = minOf(old?.startTime ?: row.startTime, row.startTime),
                endTime = maxOf(old?.endTime ?: 0, row.endTime), updatedAt = maxOf(old?.updatedAt ?: 0, row.updatedAt))
        }
        if (records.isNotEmpty()) dao.upsertReadingRecords(records)
        val labels = (dao.getAllBookmarks().filter { it.bookId == bookId }.map { it.tagsJson } +
            dao.getAllNotes().filter { it.bookId == bookId }.map { it.tagsJson }).flatMap { com.huangder.lumibooks.domain.model.AnnotationTags.decode(it) }
        db.annotationTagDao().ensureTags(labels.distinct().map { AnnotationTagEntity(it, 0L) })
        return shadowed
    }

    private suspend fun applyPosition(book: BookEntity, captured: JSONObject, current: JSONObject, incoming: JSONObject) {
        val hash = fileHash(book)
        val candidates = readingItems(incoming, "positions").filter { it.getString("format") == book.format }
        val exact = if (hash.isNotBlank()) candidates.filter { it.getString("fileSha256") == hash } else emptyList()
        val position = (if (hash.isNotBlank()) exact else candidates.filter { it.getString("fileSha256").isEmpty() }).maxByOrNull { it.getLong("lastReadTime") } ?: return
        val key = readingItemKey("positions", position)
        val before = readingItems(captured, "positions").find { readingItemKey("positions", it) == key }
        val now = readingItems(current, "positions").find { readingItemKey("positions", it) == key }
        if (readingCanonical(before) != readingCanonical(now)) return
        db.bookDao().applyServerReadingPosition(book.id, position.getDouble("readingProgress").toFloat(),
            position.optTextOrNull("locatorJson"), position.getLong("lastReadTime"))
    }

    companion object {
        internal const val BASE = "mybooks_reading_base:"
        internal const val CONFLICT = "mybooks_reading_conflict:"
        private const val CHOICE = "mybooks_reading_choice:"
    }
}
