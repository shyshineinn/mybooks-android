package com.huangder.lumibooks.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.local.dao.MyBooksSyncStateDao
import com.huangder.lumibooks.data.local.dao.SyncStateDao
import com.huangder.lumibooks.data.local.dao.TagDao
import com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity
import com.huangder.lumibooks.data.local.entity.MyBooksPendingDeleteEntity
import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import com.huangder.lumibooks.data.local.entity.TagEntity
import com.huangder.lumibooks.data.sync.BookDownloadState
import com.huangder.lumibooks.data.sync.CloudBookDownloadResult
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.domain.model.TagNameValidator
import com.huangder.lumibooks.domain.repository.BookRepository
import com.huangder.lumibooks.util.FileUtils
import com.huangder.lumibooks.util.parser.BookParserFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Provider
import com.huangder.lumibooks.util.BookFileAccess
import javax.inject.Inject
import javax.inject.Singleton

enum class MyBooksSyncDirection { BOTH, UPLOAD, DOWNLOAD }

data class MyBooksSyncIssue(val bookId: String, val title: String, val message: String,
    val canResolve: Boolean = false, val pendingDeletion: Boolean = false, val readingConflict: Boolean = false, val pendingUpload: Boolean = false)
internal class MyBooksMetadataConflict(val fields: List<String>, message: String) : Exception(message)

data class MyBooksSyncResult(
    val message: String,
    val success: Boolean,
    val failedCount: Int = 0,
    val failure: MyBooksSyncFailure? = null,
    val attemptAt: Long = 0
)

/**
 * 本地书名/作者是否算作"用户改过"、需要在基线里记为本地编辑。
 *
 * 空值不算：导入正文副本时书名/作者可能还是空的，那是流程的临时状态而非用户意图，
 * 记成本地编辑会在下一轮同步把空值推回服务器。
 */
internal fun isLocalTitleAuthorEdit(local: String, remote: String): Boolean =
    local.isNotBlank() && local != remote

/**
 * my-books 双向同步:
 * 服务器快照(base) ↔ 本地 overrides ↔ 服务器当前值(remote)。
 * 阅读页只读 MyBooksNotesStore 的本地副本;网络只在同步/推送时发生。
 */
@Singleton
class MyBooksSyncManager @Inject constructor(
    private val bookRepository: BookRepository,
    private val tagDao: TagDao,
    private val syncStateDao: SyncStateDao,
    private val myBooksSyncStateDao: MyBooksSyncStateDao,
    private val stateStore: MyBooksSyncStateStore,
    private val scheduler: MyBooksSyncScheduler,
    private val localNetworkAccessManager: LocalNetworkAccessManager,
    private val readingSync: MyBooksReadingSync,
    @ApplicationContext private val context: Context,
    private val preferences: MyBooksSyncPreferencesStore,
    private val portableState: Provider<MyBooksPortableStateSync>,
    private val bookTransfer: Provider<MyBooksBookTransfer>,
    private val database: AppDatabase
) {
    private val client = MyBooksApiClient()
    private val coverUploader = MyBooksCoverUploader(client)
    private val metaCache = MyBooksMetaCache(context)
    private val notesStore = MyBooksNotesStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val metadataSyncIssues = combine(myBooksSyncStateDao.observeAll(), myBooksSyncStateDao.observePendingDeletes(),
        bookRepository.getAllBooks()) { states, deletes, localBooks ->
        val titles = localBooks.associate { it.id to it.title }
        states.filter { it.lastError != null }.map { item ->
            MyBooksSyncIssue(item.bookId, titles[item.bookId].orEmpty(), item.lastError.orEmpty(),
                canResolve = item.conflictFieldsJson != "[]" && !item.remoteDeleted)
        } + deletes.filter { it.lastError != null }.map { item ->
            MyBooksSyncIssue(item.bookId, item.title.ifBlank { context.getString(R.string.mybooks_pending_delete_title) },
                item.lastError.orEmpty(), pendingDeletion = true)
        }
    }

    private val uploadIssues = combine(syncStateDao.observePrefix("mybooks_upload_error:"), bookRepository.getAllBooks()) { errors, books ->
        val titles = books.associate { it.id to it.title }
        errors.mapNotNull { row -> runCatching {
            val root = JSONObject(row.value); val id = root.getString("bookId")
            titles[id]?.let { MyBooksSyncIssue(id, it, root.getString("message"), pendingUpload = true) }
        }.getOrNull() }
    }
    val syncIssues = combine(metadataSyncIssues, readingSync.issues, uploadIssues) { metadata, reading, upload -> metadata + reading + upload }

    private val editMutex = Mutex()
    private val syncMutex = Mutex()
    val syncStatus = stateStore.status
    val pendingSyncCount = stateStore.pendingCount

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, BookDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, BookDownloadState>> = _downloadStates.asStateFlow()

    suspend fun <T> withBookTransfer(block: suspend () -> T): T = syncMutex.withLock {
        check(MyBooksConfig.hasWriteToken()) { context.getString(R.string.mybooks_server_login_required) }
        _isSyncing.value = true
        try { block() } finally { _isSyncing.value = false }
    }

    fun onLocalBooksImported() { scheduler.scheduleImportedBooks() }
    suspend fun cacheImportedCover(url: String, target: File) { client.downloadFile(url, target, 0) { _, _ -> } }

    suspend fun syncIfStale(maxAgeMs: Long = 10 * 60_000) {
        scheduler.scheduleIfNeeded(maxAgeMs)
    }

    suspend fun syncNow(scheduleRetry: Boolean = true, direction: MyBooksSyncDirection = MyBooksSyncDirection.BOTH,
        selectedBookIds: Set<String>? = null): MyBooksSyncResult = syncMutex.withLock {
        if (!preferences.current().enabled || !MyBooksConfig.hasWriteToken()) {
            return@withLock MyBooksSyncResult(context.getString(R.string.mybooks_server_login_required), false,
                failure = MyBooksSyncFailure.AUTH)
        }
        _isSyncing.value = true
        val attemptAt = System.currentTimeMillis()
        try {
            stateStore.started(attemptAt)
            val result = try {
                withContext(Dispatchers.IO) {
                    if (!localNetworkAccessManager.hasPermission() && localNetworkAccessManager.stateFor(MyBooksConfig.SERVER_URL) == LocalNetworkAccessState.PERMISSION_REQUIRED) {
                        throw MyBooksPermissionRequired(context.getString(R.string.mybooks_server_local_network_required))
                    }
                    runSyncNow(direction, selectedBookIds)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MyBooksSyncResult(error.message ?: context.getString(R.string.mybooks_connection_failed), false,
                    failure = classifyMyBooksSyncFailure(error))
            }.copy(attemptAt = attemptAt)
            stateStore.completed(result)
            if (scheduleRetry && result.failure == MyBooksSyncFailure.TRANSIENT) scheduler.scheduleRetry()
            result
        } finally {
            _isSyncing.value = false
        }
    }

    /** 当前使用单一活动服务器。已有书目时禁止换源，防止相同数字 id 混库。 */
    suspend fun checkServerChange(serverUrl: String) = syncMutex.withLock {
        checkServerChangeLocked(serverUrl)
    }
    private suspend fun boundServerUrl(): String {
        val configured = MyBooksConfig.SERVER_URL
        return if (configured.isNotBlank()) configured else syncStateDao.getValue(KEY_BOUND_SERVER_URL).orEmpty()
    }

    private suspend fun checkServerChangeLocked(serverUrl: String) {
        check(_downloadStates.value.values.none { it is BookDownloadState.Downloading }) {
            context.getString(R.string.mybooks_server_wait_download)
        }
        val changed = MyBooksEndpoint.normalize(serverUrl) != boundServerUrl()
        check(!changed || (bookRepository.getAllBooks().first().none(MyBooksConfig::isMyBooks) &&
            myBooksSyncStateDao.getAll().isEmpty() && myBooksSyncStateDao.getPendingDeletes().isEmpty() &&
            !hasPendingBookImports())) {
            context.getString(R.string.mybooks_server_switch_blocked)
        }
    }
    private suspend fun hasPendingBookImports(): Boolean = syncStateDao.getPrefix("mybooks_upload:").any { row ->
        val id = runCatching { JSONObject(row.value).getString("localBookId") }.getOrNull()
        id != null && bookRepository.getBookById(id) != null
    }
    suspend fun saveConnection(serverUrl: String, username: String, token: String) = syncMutex.withLock {
        checkServerChangeLocked(serverUrl)
        val changedServer = MyBooksEndpoint.normalize(serverUrl) != boundServerUrl()
        withContext(Dispatchers.IO) { MyBooksConfig.save(context, serverUrl, username, token) }
        syncStateDao.putState(SyncStateEntity(KEY_BOUND_SERVER_URL, MyBooksConfig.SERVER_URL))
        scheduler.cancel()
        preferences.setEnabled(true)
        stateStore.reset()
        if (changedServer) syncStateDao.putState(SyncStateEntity(KEY_LIBRARY_ID, ""))
        syncStateDao.putStates(listOf(SyncStateEntity(KEY_CURSOR, ""), SyncStateEntity(KEY_READING_CAPABILITY, ""), SyncStateEntity(KEY_PORTABLE_CAPABILITY, "")))
        scheduler.scheduleImmediate()
    }
    suspend fun logoutConnection(): Boolean = syncMutex.withLock {
        check(_downloadStates.value.values.none { it is BookDownloadState.Downloading }) {
            context.getString(R.string.mybooks_server_wait_download)
        }
        val auth = MyBooksAuthClient(MyBooksConfig.SERVER_URL)
        val revoked = try {
            auth.logout(MyBooksConfig.adminToken)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error is MyBooksHttpException && error.status in setOf(401, 403)
        }
        withContext(Dispatchers.IO) { MyBooksConfig.logout(context) }
        scheduler.cancel()
        revoked
    }

    /** Clear connection credentials, keeping local books and the identity needed to reconnect safely. */
    suspend fun clearConfiguration() = syncMutex.withLock {
        check(_downloadStates.value.values.none { it is BookDownloadState.Downloading }) { context.getString(R.string.mybooks_server_wait_download) }
        val endpoint = boundServerUrl()
        if (endpoint.isNotBlank()) syncStateDao.putState(SyncStateEntity(KEY_BOUND_SERVER_URL, endpoint))
        preferences.setEnabled(false)
        val token = MyBooksConfig.adminToken
        if (token.isNotBlank()) runCatching { MyBooksAuthClient(MyBooksConfig.SERVER_URL).logout(token) }
        withContext(Dispatchers.IO) { MyBooksConfig.clear(context) }
        preferences.clear()
        preferences.setEnabled(false)
        stateStore.reset()
    }

    /** 本地删除前调用:把服务器软删除放进队列,随后由同步器推送。 */
    suspend fun queuePendingDelete(bookId: String): Boolean {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return false
        val previous = myBooksSyncStateDao.getPendingDelete(remoteId)
        if (previous != null) return true // 不用空基线覆盖已经排队的删除条件。
        val state = myBooksSyncStateDao.get(bookId)
        val base = state?.baseJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        myBooksSyncStateDao.upsertPendingDelete(
            MyBooksPendingDeleteEntity(
                remoteId = remoteId,
                bookId = bookId,
                deletedAt = System.currentTimeMillis(),
                expectedRevision = base.optLong("revision", 0L).takeIf { it > 0 },
                expectedUuid = base.optTextOrEmpty("uuid"),
                expectedUpdatedAt = state?.serverUpdatedAt.orEmpty(),
                title = bookRepository.getBookById(bookId)?.title.orEmpty()
            )
        )
        return true
    }

    fun enqueuePendingDelete(bookId: String) {
        scope.launch {
            queuePendingDelete(bookId)
            if (preferences.automatic()) syncNow()
        }
    }

    /** Validate the source under both sync/edit locks, then reuse the normal local edit queue. */
    internal suspend fun editReadLogDate(
        bookId: String,
        source: ReadLogDateSource,
        field: ReadLogDateField,
        date: String
    ): String = syncMutex.withLock {
        editMutex.withLock {
            val state = myBooksSyncStateDao.get(bookId)
                ?: throw ReadLogDateException(ReadLogDateFailure.CHANGED)
            if (state.remoteDeleted) throw ReadLogDateException(ReadLogDateFailure.CHANGED)
            val base = JSONObject(state.baseJson)
            val overrides = JSONObject(state.overridesJson)
            val raw = if (overrides.has("read_logs")) overrides.opt("read_logs") else base.opt("read_logs")
            val logs = editReadLogDate(raw, source, field, date)
            recordLocalEditLocked(bookId, mapOf("read_logs" to logs))
            overrides.put("read_logs", logs)
            overrides.keys().forEach { key -> base.put(key, overrides.get(key)) }
            val dates = MyBooksReadStatus.parseLogs(logs)
            base.put("read_count", dates.count { it.finish.isNotBlank() })
            base.put("start_date", dates.map { it.start }.maxOrNull().orEmpty())
            base.put("finish_date", dates.map { it.finish }.maxOrNull().orEmpty())
            base.toString()
        }
    }

    /** 编辑页/标签操作后记录本地 field overrides,并 debounce 推送。 */
    suspend fun recordLocalEdit(
        bookId: String,
        fields: Map<String, Any?>,
        editedAt: Long = System.currentTimeMillis()
    ) = editMutex.withLock { recordLocalEditLocked(bookId, fields, editedAt) }

    private suspend fun recordLocalEditLocked(
        bookId: String,
        fields: Map<String, Any?>,
        editedAt: Long = System.currentTimeMillis()
    ) {
        if (fields.isEmpty()) return
        val state = myBooksSyncStateDao.get(bookId) ?: return
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        val edits = runCatching { JSONObject(state.editedAtJson) }.getOrDefault(JSONObject())
        fields.forEach { (key, value) ->
            overrides.put(key, value ?: "")
            edits.put(key, editedAt)
        }
        myBooksSyncStateDao.upsert(
            state.copy(
                overridesJson = overrides.toString(),
                editedAtJson = edits.toString(),
                lastError = null
            )
        )
        schedulePush()
    }

    /**
     * 快捷“标记已读/取消已读”同步到服务器：
     * 标记时补一条读完记录，取消时清掉最后一条 finish；两者与 status 一起进入现有合并通道。
     */
    suspend fun setReadStatus(bookId: String, finished: Boolean) = editMutex.withLock {
        val state = myBooksSyncStateDao.get(bookId) ?: return@withLock
        val base = runCatching { JSONObject(state.baseJson) }.getOrDefault(JSONObject())
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        val currentLogs = MyBooksReadStatus.parseLogs(
            if (overrides.has("read_logs")) overrides.opt("read_logs") else base.opt("read_logs")
        )
        val toggle = MyBooksReadStatus.toggle(
            currentLogs = currentLogs,
            markRead = finished,
            today = LocalDate.now().toString()
        )
        val logsChanged = MyBooksReadStatus.canonical(toggle.logs) !=
            MyBooksReadStatus.canonical(currentLogs)
        val currentStatus = (if (overrides.has("status")) {
            overrides.optString("status")
        } else {
            base.optString("status")
        }).ifBlank { "wish" }
        if (toggle.status == currentStatus && !logsChanged) return@withLock

        val fields = linkedMapOf<String, Any?>("status" to toggle.status)
        if (logsChanged) fields["read_logs"] = MyBooksReadStatus.logsToJson(toggle.logs)
        recordLocalEditLocked(bookId, fields)
    }

    /**
     * 详情页三态选择保留历史记录；显式开始重读同时清零当前位置，
     * 已读才按既有规则补一条完成记录，随后进入同一条离线 override 推送通道。
     */
    suspend fun setReadingStatus(
        bookId: String,
        target: ReadingStatus,
        startNewReading: Boolean = false,
        abandonCurrentReading: Boolean = false
    ) = editMutex.withLock {
        require(!startNewReading || !abandonCurrentReading)
        val state = myBooksSyncStateDao.get(bookId)
            ?: error(context.getString(R.string.mybooks_edit_save_failed))
        val base = runCatching { JSONObject(state.baseJson) }.getOrDefault(JSONObject())
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        val currentLogs = MyBooksReadStatus.parseLogs(
            if (overrides.has("read_logs")) overrides.opt("read_logs") else base.opt("read_logs")
        )
        val today = LocalDate.now().toString()
        val transition = if (startNewReading) {
            require(target == ReadingStatus.READING)
            MyBooksReadStatus.startNextReading(currentLogs, today)
        } else if (abandonCurrentReading) {
            MyBooksReadStatus.abandonCurrentReading(currentLogs)
        } else if (currentLogs.isEmpty() && !overrides.has("read_logs") &&
            (base.optInt("read_count", 0) > 0 || (!base.isNull("finish_date") && base.optString("finish_date").isNotBlank()))) {
            // A legacy summary has no complete per-round history to replace safely.
            MyBooksReadToggle(target.toServerStatus(), currentLogs)
        } else MyBooksReadStatus.transition(currentLogs, target, today)
        val logsChanged = MyBooksReadStatus.canonical(transition.logs) !=
            MyBooksReadStatus.canonical(currentLogs)
        val currentStatus = (if (overrides.has("status")) {
            overrides.optString("status")
        } else {
            base.optString("status")
        }).ifBlank { "wish" }
        if (transition.status == currentStatus && !logsChanged) return@withLock

        val fields = linkedMapOf<String, Any?>("status" to transition.status)
        if (logsChanged) fields["read_logs"] = MyBooksReadStatus.logsToJson(transition.logs)
        if (startNewReading && logsChanged) {
            database.withTransaction {
                val book = bookRepository.getBookById(bookId)
                    ?: error(context.getString(R.string.mybooks_edit_save_failed))
                // A null locator passed to updateReadingProgress preserves the old position.
                // Explicitly clear it, and publish a newer position for multi-device sync.
                bookRepository.updateBook(book.copy(
                    readingProgress = 0f,
                    locatorJson = null,
                    lastReadTime = maxOf(System.currentTimeMillis(), book.lastReadTime + 1)
                ))
                recordLocalEditLocked(bookId, fields)
            }
        } else recordLocalEditLocked(bookId, fields)
    }

    fun recordCoverChange(bookId: String, editedAt: Long = System.currentTimeMillis()) {
        scope.launch { editMutex.withLock {
            val state = myBooksSyncStateDao.get(bookId) ?: return@launch
            myBooksSyncStateDao.upsert(
                state.copy(coverDirty = true, coverEditedAt = maxOf(editedAt, state.coverEditedAt + 1), lastError = null)
            )
            schedulePush()
        } }
    }

    /**
     * 编辑 Sheet 保存:一次写入全部受管字段的 override。
     * [managedKeys] 中未出现在 [changed] 的键会被移除,避免已还原的字段继续推送。
     */
    suspend fun replaceLocalEdits(
        bookId: String,
        changed: Map<String, Any?>,
        managedKeys: Set<String>,
        editedAt: Long = System.currentTimeMillis()
    ) = editMutex.withLock {
        val state = myBooksSyncStateDao.get(bookId) ?: return@withLock
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        val edits = runCatching { JSONObject(state.editedAtJson) }.getOrDefault(JSONObject())
        managedKeys.forEach { key ->
            if (!changed.containsKey(key)) {
                overrides.remove(key)
                edits.remove(key)
            }
        }
        changed.forEach { (key, value) ->
            overrides.put(key, value ?: "")
            edits.put(key, editedAt)
        }
        myBooksSyncStateDao.upsert(
            state.copy(
                overridesJson = overrides.toString(),
                editedAtJson = edits.toString(),
                lastError = null
            )
        )
        schedulePush()
    }

    fun schedulePush() {
        scheduler.schedulePush()
    }

    suspend fun localNote(bookId: String): MyBooksLocalNote? {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return null
        return withContext(Dispatchers.IO) { notesStore.read(remoteId) }
    }

    /** 详情页“同步并阅读”:下载服务器 .md 到本地,成功后返回本地内容。 */
    suspend fun syncNoteToLocal(bookId: String): MyBooksLocalNote? {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return null
        val payload = withContext(Dispatchers.IO) { client.fetchNotes(remoteId) }
        if (!payload.hasNotes) return withContext(Dispatchers.IO) { notesStore.read(remoteId) }
        withContext(Dispatchers.IO) {
            notesStore.write(remoteId, payload.content, payload.updatedAt, payload.size)
            notesStore.read(remoteId)
        }
        return withContext(Dispatchers.IO) { notesStore.read(remoteId) }
    }

    suspend fun saveLocalNoteAfterUpload(bookId: String, content: String, payload: MyBooksNotesPayload) {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return
        withContext(Dispatchers.IO) {
            notesStore.write(remoteId, content, payload.updatedAt, payload.size)
        }
    }

    /** 移除自定义封面后,把服务器封面重新缓存到本地并清除 coverDirty。 */
    suspend fun restoreServerCover(bookId: String): Boolean {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return false
        return runCatching {
            val json = withContext(Dispatchers.IO) { client.fetchBookJson(remoteId) }
            val detail = parseRemoteBook(json, MyBooksConfig.SERVER_URL)
            val coverPath = cacheCover(remoteId, detail.coverUrl, force = true)
            val book = bookRepository.getBookById(bookId) ?: return@runCatching false
            bookRepository.updateBookMetadata(book.copy(coverPath = coverPath ?: book.coverPath))
            myBooksSyncStateDao.get(bookId)?.let { state ->
                myBooksSyncStateDao.upsert(state.copy(coverDirty = false, coverEditedAt = 0L))
            }
            true
        }.getOrDefault(false)
    }

    /** 本地删书后清理同步状态/详情缓存/心得副本;待处理删除记录由同步器保留。 */
    suspend fun deleteRemoteBooks(bookIds: Set<String>): MyBooksSyncResult {
        if (!syncMutex.tryLock()) return MyBooksSyncResult(context.getString(R.string.webdav_operation_in_progress), false)
        return try {
            if (!preferences.current().enabled || !MyBooksConfig.hasWriteToken()) return MyBooksSyncResult(context.getString(R.string.mybooks_server_login_required), false)
            withContext(Dispatchers.IO) {
                for (id in bookIds) {
                    val remoteId = requireNotNull(MyBooksConfig.remoteIdOf(id))

                    val pending = myBooksSyncStateDao.getPendingDelete(remoteId)
                    val base = myBooksSyncStateDao.get(id)?.baseJson?.let(::JSONObject)
                    val uuid = pending?.expectedUuid ?: base?.optString("uuid").orEmpty()
                    val revision = pending?.expectedRevision ?: base?.optLong("revision") ?: 0L
                    check(uuid.isNotBlank() && revision > 0) { context.getString(R.string.mybooks_delete_missing_baseline) }
                    client.deleteBook(remoteId, MyBooksConfig.adminToken, JSONObject().put("expected_uuid", uuid).put("expected_revision", revision))
                    myBooksSyncStateDao.deletePendingDelete(remoteId)
                }
                MyBooksSyncResult(context.getString(R.string.mybooks_cloud_delete_done), true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            withContext(Dispatchers.IO) {
                for (id in bookIds) MyBooksConfig.remoteIdOf(id)?.let { remoteId ->
                    myBooksSyncStateDao.getPendingDelete(remoteId)?.let { pending ->
                        myBooksSyncStateDao.upsertPendingDelete(pending.copy(lastAttemptAt = System.currentTimeMillis(), lastError = error.message.orEmpty()))
                    }
                }
            }
            MyBooksSyncResult(error.message.orEmpty(), false, failure = classifyMyBooksSyncFailure(error))
        }
        finally { syncMutex.unlock() }
    }

    /** A server-deleted book has no cloud placeholder to retain. This also works offline. */
    suspend fun shouldKeepCloudPlaceholder(bookId: String): Boolean =
        myBooksSyncStateDao.get(bookId)?.remoteDeleted != true

    suspend fun clearLocalBookData(bookId: String, keepServerAssociation: Boolean = false) {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return
        val download = downloadJobs[bookId]
        if (download != null && download != kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
            download.cancel(); download.join()
        }
        withContext(Dispatchers.IO) {
            if (!keepServerAssociation) {
                myBooksSyncStateDao.get(bookId)?.let { readingSync.forgetBook(it) }
                myBooksSyncStateDao.delete(bookId)
                metaCache.delete(remoteId)
                notesStore.delete(remoteId)
            }
            FileUtils.getBooksDirectory(context).listFiles()?.filter {
                it.name.startsWith("$bookId.") && (it.name.endsWith(".part") ||
                    it.name.endsWith(".part.resume.json") || it.name.endsWith(".part.resume.json.tmp"))
            }?.forEach { it.delete() }
            _downloadStates.value = _downloadStates.value - bookId
        }
    }

    /** 上传/替换 .md;成功后立即写入本地阅读副本。409 抛 [MyBooksHttpException]。 */
    suspend fun uploadNote(
        bookId: String,
        content: String,
        expectedUpdatedAt: String?
    ): MyBooksNotesPayload {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: error("不是服务器书籍")
        val token = MyBooksConfig.adminToken
        require(token.isNotBlank()) { context.getString(R.string.mybooks_readonly) }
        val payload = withContext(Dispatchers.IO) {
            client.saveNotes(remoteId, content, expectedUpdatedAt, token)
        }
        saveLocalNoteAfterUpload(bookId, content, payload)
        return payload
    }

    // ────────────────────────── core sync ──────────────────────────

    private suspend fun runSyncNow(direction: MyBooksSyncDirection, selectedBookIds: Set<String>?): MyBooksSyncResult {
        // Older builds could leave links behind after deleting a book, folder or tag. SQLite
        // rejects even an unrelated UPDATE of such a row, so repair them before moving any
        // local book to its server identity.
        syncStateDao.deleteDanglingBookFolderLinks()
        syncStateDao.deleteDanglingBookTagLinks()
        syncStateDao.detachDanglingFolderParents()
        val options = preferences.current().content
        if (!options.hasSelectedContent) return MyBooksSyncResult(context.getString(R.string.webdav_select_content_required), false)
        val automaticFileErrors = mutableListOf<String>()
        if (options.bookFiles && direction != MyBooksSyncDirection.DOWNLOAD && selectedBookIds == null) {
            val localIds = bookRepository.getAllBooks().first().filter { !MyBooksConfig.isMyBooks(it) && canUploadMyBooksBook(it) &&
                runCatching { BookFileAccess.openInputStream(context, it.filePath).use { } }.isSuccess }.mapTo(mutableSetOf()) { it.id }
            if (localIds.isNotEmpty()) automaticFileErrors += bookTransfer.get().uploadUnderSyncLock(localIds).errors
        }
        val token = MyBooksConfig.adminToken
        val snapshot = client.fetchSyncSnapshot(syncStateDao.getValue(KEY_CURSOR)?.takeIf { it.isNotBlank() })
        if (snapshot.libraryId != null) {
            val previousLibrary = syncStateDao.getValue(KEY_LIBRARY_ID).orEmpty()
            val hasBindings = myBooksSyncStateDao.getAll().isNotEmpty() ||
                myBooksSyncStateDao.getPendingDeletes().isNotEmpty() ||
                bookRepository.getAllBooks().first().any(MyBooksConfig::isMyBooks)
            check(previousLibrary.isBlank() || previousLibrary == snapshot.libraryId || !hasBindings) {
                context.getString(R.string.mybooks_library_changed)
            }
            syncStateDao.putState(SyncStateEntity(KEY_LIBRARY_ID, snapshot.libraryId))
        }
        syncStateDao.putStates(listOf(SyncStateEntity(KEY_READING_CAPABILITY, snapshot.supportsReadingSync.toString()),
            SyncStateEntity(KEY_PORTABLE_CAPABILITY, snapshot.supportsPortableSync.toString())))
        val deletedIds = snapshot.deleted.mapTo(mutableSetOf()) { it.id }
        val queued = myBooksSyncStateDao.getPendingDeletes().mapTo(mutableSetOf()) { it.remoteId }
        val failures = mutableListOf<MyBooksSyncFailure>()
        val failureDetails = automaticFileErrors.toMutableList()
        var failed = automaticFileErrors.size + if (direction != MyBooksSyncDirection.DOWNLOAD) pushPendingDeletes(token, snapshot, failures, failureDetails, selectedBookIds) else 0
        val failedBooks = mutableSetOf<String>()
        var added = 0
        var updated = 0
        // Pending imports keep their original local row until the verified file can be associated.
        val importPrefix = "mybooks_upload:" + snapshot.libraryId + ":"
        val reservedIds = mutableSetOf<Long>()
        for (row in syncStateDao.getPrefix(importPrefix)) {
            if (bookRepository.getBookById(row.key.removePrefix(importPrefix)) != null) {
                runCatching { JSONObject(row.value).getLong("id") }.getOrNull()?.let { reservedIds += it }
            }
        }
        val changes = (if (direction == MyBooksSyncDirection.UPLOAD) emptyList() else snapshot.books.filter { it.id !in reservedIds })
            .associateBy { it.id }.toMutableMap()
        val details = mutableMapOf<Long, String>()
        // A local outbox entry must be pushed even when the server delta has no book changes.
        for (state in myBooksSyncStateDao.getAll()) {
            if (!options.bookFiles || selectedBookIds != null && state.bookId !in selectedBookIds) continue
            if (state.remoteDeleted || state.remoteId in changes || state.remoteId in queued || state.remoteId in deletedIds) continue
            val tags = tagDao.getTagsForBook(state.bookId).map(TagEntity::name)
            if (hasPendingChanges(state, tags) || state.lastError != null) {
                try {
                    val raw = client.fetchBookJson(state.remoteId)
                    details[state.remoteId] = raw
                    changes[state.remoteId] = parseRemoteBook(raw, MyBooksConfig.SERVER_URL)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val category = classifyMyBooksSyncFailure(error)
                    if (category in setOf(MyBooksSyncFailure.AUTH, MyBooksSyncFailure.TRANSIENT)) throw error
                    failed++; failedBooks += state.bookId; failures += category
                    failureDetails += (bookRepository.getBookById(state.bookId)?.title.orEmpty() + ": " + error.message.orEmpty())
                    myBooksSyncStateDao.upsert(state.copy(lastError = error.message))
                }
            }
        }
        for (remote in changes.values) {
            if (remote.id in queued) continue
            try {
                when (syncOneBook(remote, token, details[remote.id], allowUpload = direction != MyBooksSyncDirection.DOWNLOAD && options.bookFiles, syncBookFiles = options.bookFiles)) { 1 -> added++; 2 -> updated++ }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                failedBooks += MyBooksConfig.BOOK_ID_PREFIX + remote.id
                val category = classifyMyBooksSyncFailure(error)
                failures += category
                failureDetails += "${remote.title}: ${error.message.orEmpty()}"
                editMutex.withLock { myBooksSyncStateDao.get(MyBooksConfig.BOOK_ID_PREFIX + remote.id)?.let { state ->
                    myBooksSyncStateDao.upsert(state.copy(
                        lastError = error.message ?: context.getString(R.string.mybooks_sync_failed),
                        conflictFieldsJson = if (error is MyBooksMetadataConflict) JSONArray(error.fields).toString()
                            else state.conflictFieldsJson
                    ))
                } }
                if (category == MyBooksSyncFailure.AUTH || category == MyBooksSyncFailure.TRANSIENT) throw error
            }
        }
        val marked = applyServerDeletions(snapshot.deleted)
        val readingErrors = if (options.readingData) readingSync.sync(snapshot, failedBooks + queued.map { MyBooksConfig.BOOK_ID_PREFIX + it },
            allowUpload = direction != MyBooksSyncDirection.DOWNLOAD, uploadOnly = direction == MyBooksSyncDirection.UPLOAD,
            selectedBookIds = selectedBookIds) else emptyList()
        for ((bookId, error) in readingErrors) {
            failed++
            failures += classifyMyBooksSyncFailure(error)
            failureDetails += (bookRepository.getBookById(bookId)?.title.orEmpty() + ": " + error.message.orEmpty())
        }
        if (failed == 0 && selectedBookIds == null && snapshot.supportsPortableSync) {
            portableState.get().sync(requireNotNull(snapshot.libraryId), options, direction)
        }
        if (failed == 0 && snapshot.cursor != null && direction == MyBooksSyncDirection.BOTH) syncStateDao.putState(SyncStateEntity(KEY_CURSOR, snapshot.cursor))
        val readingNotice = if (options.readingData && !snapshot.supportsReadingSync && readingSync.pendingFingerprints.first().isNotEmpty())
            "\n" + context.getString(R.string.mybooks_reading_upgrade_required) else ""
        return MyBooksSyncResult(
            if (failed == 0) context.getString(R.string.mybooks_sync_summary, added, updated, marked) + readingNotice + (if (!snapshot.supportsPortableSync && (options.profileAndSettings || options.libraryOrganization)) "\n" + context.getString(R.string.mybooks_portable_upgrade_required) else "")
            else context.getString(R.string.mybooks_sync_partial_failure, added, updated, failed) +
                failureDetails.take(2).joinToString(separator = "\n", prefix = "\n"),
            failed == 0, failed, failures.firstOrNull())
    }

    private suspend fun syncOneBook(remote: RemoteBook, token: String, detailHint: String? = null, allowUpload: Boolean = true, syncBookFiles: Boolean = true): Int {
        val bookId = MyBooksConfig.BOOK_ID_PREFIX + remote.id
        val existing = bookRepository.getBookById(bookId)
        var state = myBooksSyncStateDao.get(bookId)
        var localTags = existing?.let { tagDao.getTagsForBook(bookId).map(TagEntity::name) }.orEmpty()

        if (existing == null) {
            val detailJson = detailHint ?: client.fetchBookJson(remote.id)
            val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
        check(remote.uuid == null || remote.uuid == detail.uuid) { context.getString(R.string.mybooks_book_identity_changed) }
        check(remote.revision == null || (detail.revision != null && remote.revision <= detail.revision)) {
            context.getString(R.string.mybooks_revision_rollback)
        }
            val now = System.currentTimeMillis()
            val file = RemoteBookFormats.preferredFile(detail.files)
            val format = file?.format?.toBookFormatOrNull() ?: BookFormat.EPUB
            val coverPath = if (syncBookFiles) cacheCover(remote.id, detail.coverUrl, force = true) else null
            bookRepository.insertBook(
                Book(
                    id = bookId,
                    title = detail.title,
                    author = detail.author,
                    filePath = "",
                    coverPath = coverPath,
                    format = format,
                    lastReadTime = 0L,
                    readingProgress = 0f,
                    createdAt = now,
                    isCloudOnly = true,
                    remoteLibraryKey = MyBooksConfig.LIBRARY_KEY,
                    remoteFileName = null,
                    remoteFileSize = file?.size ?: 0L,
                    metadataUpdatedAt = parseEpoch(detail.updatedAt ?: remote.updatedAt) ?: now,
                    sourceUri = MyBooksConfig.SOURCE_URI_PREFIX + remote.id
                )
            )
            val created = bookRepository.getBookById(bookId) ?: return 0
            val newState = MyBooksSyncStateEntity(
                bookId = bookId,
                remoteId = remote.id,
                serverUpdatedAt = detail.updatedAt ?: remote.updatedAt.orEmpty(),
                baseJson = detailJson,
                baseTagsJson = JSONArray(detail.tags).toString(),
                lastSyncAt = now
            )
            myBooksSyncStateDao.upsert(newState)
            applyTags(bookId, detail.tags)
            syncNoteForDetail(detail)
            return 1
        }

        if (state == null) {
            val detailJson = detailHint ?: client.fetchBookJson(remote.id)
            val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
        check(remote.uuid == null || remote.uuid == detail.uuid) { context.getString(R.string.mybooks_book_identity_changed) }
        check(remote.revision == null || (detail.revision != null && remote.revision <= detail.revision)) {
            context.getString(R.string.mybooks_revision_rollback)
        }
            state = createBaselineState(existing, detailJson, detail, remote.updatedAt)
            myBooksSyncStateDao.upsert(state)
            // 新建服务器书后本地副本还没有标签；先以服务器为准补齐，
            // 否则三方合并会把服务器标签误判成“本地删除”并清空。
            if (localTags.isEmpty() && detail.tags.isNotEmpty()) {
                applyTags(bookId, detail.tags)
                localTags = detail.tags
            }
        }
        if (!syncBookFiles) {
            state?.let { checkBookIdentity(it, remote); myBooksSyncStateDao.upsert(it.copy(remoteDeleted = false, lastError = null)) }
            return 0
        }
        val currentState = state ?: return 0
        check(currentState.conflictFieldsJson == "[]") { context.getString(R.string.mybooks_conflict_requires_choice) }
        val base = JSONObject(currentState.baseJson)
        check(base.optTextOrEmpty("uuid").isBlank() || remote.uuid == null || base.optString("uuid") == remote.uuid) {
            context.getString(R.string.mybooks_book_identity_changed)
        }
        val baseRevision = base.optLong("revision", 0L)
        check(remote.revision == null || baseRevision <= remote.revision) { context.getString(R.string.mybooks_revision_rollback) }

        val remoteUnchanged = if (remote.revision != null) baseRevision > 0L && remote.revision == baseRevision
            else !remote.updatedAt.isNullOrBlank() && remote.updatedAt == currentState.serverUpdatedAt
        val pending = hasPendingChanges(currentState, localTags)
        val cachedDetail = parseRemoteBook(currentState.baseJson, MyBooksConfig.SERVER_URL)
        val needsNote = cachedDetail.hasNotes && !notesStore.isCurrent(cachedDetail.id, cachedDetail.notesUpdatedAt, cachedDetail.notesSize)
        if (remoteUnchanged && !pending && !currentState.remoteDeleted && !needsNote) return 0

        val detailJson = detailHint ?: client.fetchBookJson(remote.id)
        val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
        check(remote.uuid == null || remote.uuid == detail.uuid) { context.getString(R.string.mybooks_book_identity_changed) }
        check(remote.revision == null || (detail.revision != null && remote.revision <= detail.revision)) {
            context.getString(R.string.mybooks_revision_rollback)
        }
        val remoteUpdatedAt = detail.updatedAt ?: remote.updatedAt ?: currentState.serverUpdatedAt
        val merge = mergeState(existing, currentState, localTags, detailJson)

        if (token.isBlank() || !allowUpload) {
            applyRemote(
                existing,
                detail,
                merge.localWonFields,
                merge.coverAction == MyBooksCoverAction.DOWNLOAD_REMOTE
            )
            applyTags(bookId, merge.mergedTags)
            val updatedState = stateAfterRemoteOnly(currentState, detailJson, remoteUpdatedAt, detail.tags, merge)
            myBooksSyncStateDao.upsert(updatedState)
            syncNoteForDetail(detail)
            return 2
        }

        return pushAndApply(existing, currentState, detailJson, detail, remoteUpdatedAt, localTags, token)
    }

    private suspend fun pushAndApply(
        existing: Book,
        state: MyBooksSyncStateEntity,
        detailJson: String,
        detail: RemoteBook,
        remoteUpdatedAt: String,
        localTags: List<String>,
        token: String
    ): Int {
        var remoteJson = detailJson
        var remoteDetail = detail
        checkBookIdentity(state, remoteDetail)
        var expected = state.serverUpdatedAt
        var merge = mergeState(existing, state, localTags, remoteJson)
        throwIfConflict(merge)
        var uploadedCoverUrl: String? = null

        if (merge.coverAction == MyBooksCoverAction.UPLOAD_LOCAL) {
            val coverFile = existing.coverPath?.let(::File)
            check(coverFile != null && coverFile.isFile) { context.getString(R.string.mybooks_cover_missing) }
                uploadedCoverUrl = coverUploader.upload(coverFile, token)
        }

        repeat(2) { attempt ->
            val patch = buildPatch(merge, uploadedCoverUrl)
            if (patch.length() == 0) {
                applyRemote(
                    existing,
                    remoteDetail,
                    merge.localWonFields,
                    merge.coverAction == MyBooksCoverAction.DOWNLOAD_REMOTE
                )
                val latestTags = tagDao.getTagsForBook(existing.id).map(TagEntity::name)
                applyTags(existing.id, MyBooksMerge.mergeTags(localTags, latestTags, merge.mergedTags))
                editMutex.withLock { myBooksSyncStateDao.upsert(
                    stateAfterRemoteOnly(state, remoteJson, remoteUpdatedAt, remoteDetail.tags, merge)
                ) }
                syncNoteForDetail(remoteDetail)
                return 2
            }
            val version = JSONObject(remoteJson).optLong("revision", 0L)
            if (version > 0L) patch.put("expected_revision", version)
            else if (expected.isNotBlank()) patch.put("expected_updated_at", expected)
            JSONObject(remoteJson).optTextOrNull("uuid")?.let { patch.put("expected_uuid", it) }
            try {
                val response = client.patchBook(state.remoteId, patch, token)
                val responseDetail = parseRemoteBook(response, MyBooksConfig.SERVER_URL)
                editMutex.withLock {
                val next = stateAfterPush(
                    state = state,
                    patch = patch,
                    dropOverrides = merge.dropOverrides,
                    responseJson = response,
                    responseDetail = responseDetail,
                    clearCoverDirty = (merge.coverAction == MyBooksCoverAction.UPLOAD_LOCAL && uploadedCoverUrl != null) ||
                        merge.coverAction == MyBooksCoverAction.DOWNLOAD_REMOTE
                )
                applyRemote(existing, responseDetail, JSONObject(next.overridesJson).keys().asSequence().toSet(), false)
                val currentTags = tagDao.getTagsForBook(existing.id).map(TagEntity::name)
                applyTags(existing.id, MyBooksMerge.mergeTags(localTags, currentTags, responseDetail.tags))
                myBooksSyncStateDao.upsert(next)
                }
                syncNoteForDetail(responseDetail)
                return 2
            } catch (error: MyBooksHttpException) {
                if (error.status != 409 || attempt == 1) throw error
                val current = JSONObject(error.body).optJSONObject("current")
                    ?: throw error
                remoteJson = current.toString()
                remoteDetail = parseRemoteBook(remoteJson, MyBooksConfig.SERVER_URL)
                checkBookIdentity(state, remoteDetail)
                expected = current.optTextOrEmpty("updated_at")
                merge = mergeState(existing, state, localTags, remoteJson)
                throwIfConflict(merge)
                if (merge.coverAction != MyBooksCoverAction.UPLOAD_LOCAL) {
                    uploadedCoverUrl = null
                }
                if (merge.coverAction == MyBooksCoverAction.UPLOAD_LOCAL && uploadedCoverUrl == null) {
                    val coverFile = existing.coverPath?.let(::File)
                    check(coverFile != null && coverFile.isFile) { context.getString(R.string.mybooks_cover_missing) }
                uploadedCoverUrl = coverUploader.upload(coverFile, token)
                }
            }
        }
        return 2
    }

    private fun buildPatch(merge: MyBooksMergeResult, uploadedCoverUrl: String?): JSONObject {
        val patch = JSONObject(merge.patch.toString())
        if (merge.tagsDirty) patch.put("tags", JSONArray(merge.mergedTags))
        if (uploadedCoverUrl != null) patch.put("cover", uploadedCoverUrl)
        return patch
    }

    private fun mergeState(
        existing: Book,
        state: MyBooksSyncStateEntity,
        localTags: List<String>,
        remoteJson: String
    ): MyBooksMergeResult = MyBooksMerge.merge(
        book = existing,
        baseJson = state.baseJson,
        remoteJson = remoteJson,
        overridesJson = state.overridesJson,
        editedAtJson = state.editedAtJson,
        baseTags = parseTags(state.baseTagsJson),
        localTags = localTags,
        coverDirty = state.coverDirty,
        coverEditedAt = state.coverEditedAt
    )

    private suspend fun createBaselineState(
        existing: Book,
        detailJson: String,
        detail: RemoteBook,
        listUpdatedAt: String?
    ): MyBooksSyncStateEntity {
        val remoteEpoch = parseEpoch(detail.updatedAt ?: listUpdatedAt) ?: 0L
        val localEpoch = existing.metadataUpdatedAt
        val localWinsBaseline = true
        val overrides = JSONObject()
        val edits = JSONObject()
        if (localWinsBaseline) {
            // 本地书名/作者与服务端不一致才记为"本地编辑"。
            // 仅靠时间戳判断会把导入正文副本时按文件名写入的临时值当成用户编辑，
            // 再推回服务器，把豆瓣填好的书名作者覆盖掉。
            if (isLocalTitleAuthorEdit(existing.title, detail.title)) {
                overrides.put("title", existing.title)
                edits.put("title", localEpoch)
            }
            if (isLocalTitleAuthorEdit(existing.author, detail.author)) {
                overrides.put("author", existing.author)
                edits.put("author", localEpoch)
            }
        }
        val customCover = FileUtils.isCustomCover(existing.coverPath)
        return MyBooksSyncStateEntity(
            bookId = existing.id,
            remoteId = detail.id,
            serverUpdatedAt = detail.updatedAt ?: listUpdatedAt.orEmpty(),
            baseJson = detailJson,
            overridesJson = overrides.toString(),
            editedAtJson = edits.toString(),
            baseTagsJson = JSONArray(detail.tags).toString(),
            coverDirty = localWinsBaseline && customCover,
            coverEditedAt = if (localWinsBaseline && customCover) localEpoch else 0L,
            conflictFieldsJson = if (overrides.length() > 0 || customCover) "[\"baseline\"]" else "[]",
            lastError = if (overrides.length() > 0 || customCover) context.getString(R.string.mybooks_conflict_requires_choice) else null,
            lastSyncAt = System.currentTimeMillis()
        )
    }

    private suspend fun hasPendingChanges(state: MyBooksSyncStateEntity, localTags: List<String>): Boolean {
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        if (overrides.length() > 0 || state.coverDirty) return true
        val local = bookRepository.getBookById(state.bookId)
        val base = JSONObject(state.baseJson)
        if (local != null && (isLocalTitleAuthorEdit(local.title, base.optTextOrEmpty("title")) ||
            isLocalTitleAuthorEdit(local.author, normalizeRemoteAuthor(base.optTextOrEmpty("author"))))) return true
        return !tagSetEquals(localTags, parseTags(state.baseTagsJson))
    }

    private suspend fun applyRemote(
        existing: Book,
        detail: RemoteBook,
        localWonFields: Set<String>,
        downloadCover: Boolean,
        respectPendingEdits: Boolean = true
    ) {
        val current = bookRepository.getBookById(existing.id) ?: return
        val protectedFields = if (respectPendingEdits) {
            val latest = myBooksSyncStateDao.get(existing.id)
            localWonFields + JSONObject(latest?.overridesJson ?: "{}").keys().asSequence().toSet()
        } else localWonFields
        val preferred = RemoteBookFormats.preferredFile(detail.files)
        val format = if (current.isCloudOnly) {
            preferred?.format?.toBookFormatOrNull() ?: current.format
        } else {
            current.format
        }
        var coverPath = current.coverPath
        if (downloadCover && "cover" !in protectedFields) {
            coverPath = if (detail.coverUrl.isNullOrBlank()) null
                else cacheCover(detail.id, detail.coverUrl, force = true) ?: current.coverPath
        }
        val remoteEpoch = parseEpoch(detail.updatedAt) ?: existing.metadataUpdatedAt
        val updated = current.copy(
            title = if ("title" in protectedFields) current.title else detail.title,
            author = if ("author" in protectedFields) current.author else detail.author,
            coverPath = coverPath,
            format = format,
            remoteFileSize = preferred?.size ?: current.remoteFileSize,
            metadataUpdatedAt = remoteEpoch
        )
        bookRepository.applySyncedMetadata(updated, current)
    }

    private suspend fun stateAfterRemoteOnly(
        state: MyBooksSyncStateEntity,
        remoteJson: String,
        remoteUpdatedAt: String,
        remoteTags: List<String>,
        merge: MyBooksMergeResult
    ): MyBooksSyncStateEntity {
        val latest = myBooksSyncStateDao.get(state.bookId) ?: state
        val acknowledged = acknowledgedMyBooksKeys(state, latest, merge.dropOverrides)
        val overrides = removeOverrides(latest.overridesJson, acknowledged)
        val edits = removeEditedAt(latest.editedAtJson, acknowledged)
        return latest.copy(
            serverUpdatedAt = remoteUpdatedAt,
            baseJson = remoteJson,
            overridesJson = overrides.toString(),
            editedAtJson = edits.toString(),
            baseTagsJson = JSONArray(remoteTags).toString(),
            remoteDeleted = false,
            conflictFieldsJson = "[]",
            coverDirty = latest.coverDirty && (latest.coverEditedAt != state.coverEditedAt || merge.coverAction != MyBooksCoverAction.DOWNLOAD_REMOTE),
            lastSyncAt = System.currentTimeMillis(),
            lastError = null
        )
    }

    private suspend fun stateAfterPush(
        state: MyBooksSyncStateEntity,
        patch: JSONObject,
        dropOverrides: Set<String>,
        responseJson: String,
        responseDetail: RemoteBook,
        clearCoverDirty: Boolean
    ): MyBooksSyncStateEntity {
        val pushedKeys = linkedSetOf<String>()
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                "identifiers" -> patch.optJSONObject("identifiers")?.keys()?.forEach {
                    pushedKeys += MyBooksConfig.identifierOverrideKey(it)
                }
                "custom" -> patch.optJSONObject("custom")?.keys()?.forEach {
                    pushedKeys += MyBooksConfig.customOverrideKey(it)
                }
                else -> pushedKeys += key
            }
        }
        pushedKeys += dropOverrides
        // 同步期间可能有新的本地编辑落库;以最新 overrides 为准,只移除本次已推送/丢弃的键。
        val latest = myBooksSyncStateDao.get(state.bookId) ?: state
        val acknowledged = acknowledgedMyBooksKeys(state, latest, pushedKeys)
        val overrides = removeOverrides(latest.overridesJson, acknowledged)
        val edits = removeEditedAt(latest.editedAtJson, acknowledged)
        return latest.copy(
            serverUpdatedAt = responseDetail.updatedAt ?: state.serverUpdatedAt,
            baseJson = responseJson,
            overridesJson = overrides.toString(),
            editedAtJson = edits.toString(),
            baseTagsJson = JSONArray(responseDetail.tags).toString(),
            remoteDeleted = false,
            conflictFieldsJson = "[]",
            coverDirty = if (clearCoverDirty && latest.coverEditedAt == state.coverEditedAt) false else latest.coverDirty,
            lastSyncAt = System.currentTimeMillis(),
            lastError = null
        )
    }

    private fun removeOverrides(json: String, keys: Set<String>): JSONObject {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        keys.forEach { obj.remove(it) }
        return obj
    }

    private fun removeEditedAt(json: String, keys: Set<String>): JSONObject {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        keys.forEach { obj.remove(it) }
        return obj
    }

    private suspend fun applyTags(bookId: String, tagNames: List<String>) {
        val now = System.currentTimeMillis()
        val wanted = LinkedHashMap<String, String>()
        for (raw in tagNames) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val normalized = TagNameValidator.normalized(name)
            val existing = tagDao.getTagByNormalizedName(normalized)
            val tagId = existing?.id ?: run {
                val id = UUID.randomUUID().toString()
                val inserted = tagDao.insertTag(
                    TagEntity(
                        id = id,
                        name = name,
                        normalizedName = normalized,
                        createdAt = now,
                        parentId = null,
                        updatedAt = now
                    )
                )
                if (inserted != -1L) id else tagDao.getTagByNormalizedName(normalized)?.id
            } ?: continue
            wanted[name] = tagId
        }

        val links = wanted.values.distinct().map { tagId ->
            BookTagCrossRefEntity(bookId = bookId, tagId = tagId, updatedAt = now)
        }
        if (links.isNotEmpty()) syncStateDao.upsertBookTagLinks(links)
        val keep = wanted.values.toSet()
        tagDao.getBookTagLinks(bookId)
            .filter { it.tagId !in keep }
            .forEach { tagDao.deleteBookTagLink(bookId, it.tagId) }
    }

    private suspend fun applyServerDeletions(events: List<MyBooksDeletion>): Int {
        var marked = 0
        val candidates = myBooksSyncStateDao.getAll().filter { state ->
            state.remoteDeleted || events.any { it.id == state.remoteId && matchesMyBooksDeletion(state.baseJson, it) }
        }
        for (candidate in candidates) {
            clearLocalBookData(candidate.bookId, keepServerAssociation = true)
            editMutex.withLock {
                database.withTransaction {
                    val state = myBooksSyncStateDao.get(candidate.bookId) ?: return@withTransaction
                    val bookId = state.bookId
                    val tags = tagDao.getTagsForBook(bookId).map(TagEntity::name)
                    // Recheck reading rows under the write lock, including states marked
                    // deleted by an earlier build. Their outbox intentionally excludes them.
                    val pending = hasPendingChanges(state, tags) || readingSync.hasPendingChanges(bookId)
                    myBooksSyncStateDao.upsert(state.copy(remoteDeleted = true, conflictFieldsJson = "[]",
                        lastError = context.getString(if (pending) R.string.mybooks_deleted_pending else R.string.mybooks_deleted_retained)))
                    readingSync.acknowledgeRemoteDeletion(state)
                    if (!state.remoteDeleted) marked++
                    if (!pending) {
                        val book = bookRepository.getBookById(bookId)
                        if (book != null) {
                            val others = database.bookDao().getAllBooksSnapshot().filterNot { it.id == bookId }
                            if (book.filePath.isNotBlank() && others.none { it.filePath == book.filePath }) {
                                check(FileUtils.deleteAppManagedBookFile(context, book.filePath)) { "Unable to delete the local file for ${book.title}" }
                            }
                            bookRepository.deleteBook(book)
                            FileUtils.deleteTxtIndexCache(context, book.filePath)
                            book.coverPath?.takeIf { cover -> others.none { it.coverPath == cover } }?.let { FileUtils.deleteAppOwnedFile(context, it) }
                        }
                        clearLocalBookData(bookId)
                        myBooksSyncStateDao.deletePendingDelete(state.remoteId)
                    }
                }
            }
        }
        return marked
    }
    private suspend fun pushPendingDeletes(
        token: String, snapshot: MyBooksSyncSnapshot, failures: MutableList<MyBooksSyncFailure>,
        failureDetails: MutableList<String>,
        selectedBookIds: Set<String>? = null
    ): Int {
        var failed = 0
        for (item in myBooksSyncStateDao.getPendingDeletes()) {
            if (selectedBookIds != null && item.bookId !in selectedBookIds) continue
            try {
                check(snapshot.syncVersion >= 2) { context.getString(R.string.mybooks_delete_upgrade_required) }
                if (snapshot.deleted.any { it.id == item.remoteId && (item.expectedUuid.isBlank() || it.uuid == item.expectedUuid) }) {
                    myBooksSyncStateDao.deletePendingDelete(item.remoteId)
                    continue
                }
                check(item.expectedUuid.isNotBlank() && (item.expectedRevision != null || item.expectedUpdatedAt.isNotBlank())) {
                    context.getString(R.string.mybooks_delete_missing_baseline)
                }
                val expected = JSONObject().put("expected_uuid", item.expectedUuid)
                if (item.expectedRevision != null) expected.put("expected_revision", item.expectedRevision)
                else expected.put("expected_updated_at", item.expectedUpdatedAt)
                client.deleteBook(item.remoteId, token, expected)
                myBooksSyncStateDao.deletePendingDelete(item.remoteId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                myBooksSyncStateDao.upsertPendingDelete(item.copy(lastAttemptAt = System.currentTimeMillis(),
                    lastError = error.message ?: context.getString(R.string.mybooks_sync_failed)))
                val category = classifyMyBooksSyncFailure(error)
                failures += category
                failureDetails += "${item.title}: ${error.message.orEmpty()}"
                if (category == MyBooksSyncFailure.AUTH || category == MyBooksSyncFailure.TRANSIENT) throw error
            }
        }
        return failed
    }

    private fun throwIfConflict(merge: MyBooksMergeResult) {
        if (merge.conflicts.isNotEmpty()) throw MyBooksMetadataConflict(merge.conflicts,
            context.getString(R.string.mybooks_conflict_requires_choice))
    }

    private fun checkBookIdentity(state: MyBooksSyncStateEntity, detail: RemoteBook) {
        val base = JSONObject(state.baseJson)
        check(base.optTextOrEmpty("uuid").isBlank() || base.optString("uuid") == detail.uuid) {
            context.getString(R.string.mybooks_book_identity_changed)
        }
        check(detail.revision == null || base.optLong("revision", 0L) <= detail.revision) {
            context.getString(R.string.mybooks_revision_rollback)
        }
    }

    suspend fun syncReadingChanges() {
        val configuration = preferences.current()
        if (!configuration.enabled || !configuration.automatic || !configuration.content.readingData || !MyBooksConfig.hasWriteToken()) return
        if (localNetworkAccessManager.stateFor(MyBooksConfig.SERVER_URL) == LocalNetworkAccessState.PERMISSION_REQUIRED) return
        if (syncStateDao.getValue(KEY_READING_CAPABILITY) != "true" || !syncMutex.tryLock()) return
        try {
            val library = syncStateDao.getValue(KEY_LIBRARY_ID)?.takeIf { it.isNotBlank() } ?: return
            val snapshot = MyBooksSyncSnapshot(emptyList(), libraryId = library, syncVersion = 3, supportsReadingSync = true)
            val errors = withContext(Dispatchers.IO) { readingSync.sync(snapshot, uploadOnly = true) }
            errors.firstOrNull()?.second?.let { stateStore.readingFailure(it) }
            if (errors.isEmpty() && syncStateDao.getValue(KEY_PORTABLE_CAPABILITY) == "true") {
                withContext(Dispatchers.IO) { portableState.get().sync(library, configuration.content, MyBooksSyncDirection.BOTH) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { stateStore.readingFailure(error) }
        finally { syncMutex.unlock() }
    }

    suspend fun resolveReadingConflict(bookId: String, useLocal: Boolean): MyBooksSyncResult {
        syncMutex.withLock { readingSync.choose(bookId, useLocal) }
        return syncNow()
    }

    suspend fun cancelPendingDelete(bookId: String): MyBooksSyncResult {
        syncMutex.withLock {
            MyBooksConfig.remoteIdOf(bookId)?.let { myBooksSyncStateDao.deletePendingDelete(it) }
        }
        return syncNow()
    }

    suspend fun resolveConflict(bookId: String, useLocal: Boolean): MyBooksSyncResult {
        syncMutex.withLock {
            check(MyBooksConfig.hasWriteToken()) { context.getString(R.string.mybooks_server_login_required) }
            val state = myBooksSyncStateDao.get(bookId) ?: error(context.getString(R.string.mybooks_sync_failed))
            check(!state.remoteDeleted) { context.getString(R.string.mybooks_deleted_pending) }
            val raw = client.fetchBookJson(state.remoteId)
            val remote = parseRemoteBook(raw, MyBooksConfig.SERVER_URL)
            editMutex.withLock {
            val latest = myBooksSyncStateDao.get(bookId) ?: state
            val current = bookRepository.getBookById(bookId) ?: error(context.getString(R.string.mybooks_sync_failed))
            val oldBase = JSONObject(latest.baseJson)
            checkBookIdentity(latest, remote)
            check(oldBase.optTextOrEmpty("uuid").isBlank() || oldBase.optString("uuid") == remote.uuid) {
                context.getString(R.string.mybooks_book_identity_changed)
            }
            val tags = tagDao.getTagsForBook(bookId).map(TagEntity::name)
            val merge = mergeState(current, latest, tags, raw)
            val overrides = if (useLocal) JSONObject(latest.overridesJson) else JSONObject()
            if (useLocal) {
                if (isLocalTitleAuthorEdit(current.title, oldBase.optTextOrEmpty("title"))) overrides.put("title", current.title)
                if (isLocalTitleAuthorEdit(current.author, normalizeRemoteAuthor(oldBase.optTextOrEmpty("author")))) overrides.put("author", current.author)
            }
            applyRemote(current, remote, if (useLocal) merge.localWonFields else emptySet(),
                !useLocal || merge.coverAction == MyBooksCoverAction.DOWNLOAD_REMOTE, respectPendingEdits = useLocal)
            applyTags(bookId, if (useLocal) merge.mergedTags else remote.tags)
            myBooksSyncStateDao.upsert(latest.copy(baseJson = raw, serverUpdatedAt = remote.updatedAt.orEmpty(),
                overridesJson = overrides.toString(), editedAtJson = if (useLocal) latest.editedAtJson else "{}",
                baseTagsJson = JSONArray(remote.tags).toString(), coverDirty = useLocal && latest.coverDirty,
                conflictFieldsJson = "[]", lastError = null, remoteDeleted = false))
            }
        }
        return syncNow()
    }

    private suspend fun syncNoteForDetail(detail: RemoteBook) {
        if (!detail.hasNotes) return
        fun current(): Boolean = notesStore.isCurrent(detail.id, detail.notesUpdatedAt, detail.notesSize)
        if (withContext(Dispatchers.IO) { current() }) return
        run {
            val payload = client.fetchNotes(detail.id)
            if (payload.hasNotes) {
                withContext(Dispatchers.IO) {
                    notesStore.write(detail.id, payload.content, payload.updatedAt ?: detail.notesUpdatedAt, payload.size)
                }
            }
        }
    }

    private suspend fun cacheCover(remoteId: Long, coverUrl: String?, force: Boolean = false): String? {
        if (coverUrl.isNullOrBlank()) return null
        val hash = Integer.toHexString(coverUrl.hashCode())
        val covers = FileUtils.getCoversDirectory(context)
        val dest = File(covers, "mybooks_cover_${remoteId}_$hash.jpg")
        if (!force && dest.isFile && dest.length() > 0L) return dest.absolutePath
        val ok = client.fetchCover(coverUrl, dest)
        if (!ok) return dest.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        covers.listFiles()
            ?.filter { it.name.startsWith("mybooks_cover_${remoteId}_") && it.absolutePath != dest.absolutePath }
            ?.forEach { it.delete() }
        return dest.absolutePath
    }

    // ────────────────────────── download / notes API ──────────────────────────

    /** 下载服务器书正文到本地书库,进度写入 [downloadStates]。 */
    private val downloadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    suspend fun downloadBook(bookId: String): CloudBookDownloadResult {
        if (MyBooksConfig.SERVER_URL.isBlank()) return CloudBookDownloadResult(null, context.getString(R.string.mybooks_server_login_required), false)
        val lock = downloadLocks.getOrPut(bookId) { Mutex() }
        if (!lock.tryLock()) return CloudBookDownloadResult(null, context.getString(R.string.mybooks_download_running), false)
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { downloadJobs[bookId] = it }
        return try { runBookDownload(bookId) } finally { downloadJobs.remove(bookId); lock.unlock() }
    }

    private suspend fun runBookDownload(bookId: String): CloudBookDownloadResult {
        if (bookRepository.getBookById(bookId) == null) return CloudBookDownloadResult(null, context.getString(R.string.mybooks_no_download_source), false)
        val remoteId = MyBooksConfig.remoteIdOf(bookId)
            ?: return CloudBookDownloadResult(
                null,
                context.getString(R.string.mybooks_no_download_source),
                false
            )
        return try {
            val detailJson = client.fetchBookJson(remoteId)
            val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
            myBooksSyncStateDao.get(bookId)?.let { checkBookIdentity(it, detail) }
            metaCache.write(remoteId, detailJson)
            val file = RemoteBookFormats.preferredFile(detail.files)
                ?: return CloudBookDownloadResult(
                    null,
                    context.getString(R.string.mybooks_no_download_file),
                    false
                )
            val format = file.format.toBookFormatOrNull()
                ?: return CloudBookDownloadResult(
                    null,
                    context.getString(R.string.mybooks_unsupported_format, file.format),
                    false
                )
            val extension = format.extensionName()
            val booksDirectory = FileUtils.getBooksDirectory(context)
            val partial = File(booksDirectory, "$bookId.$extension.part")
            val destination = File(booksDirectory, "$bookId.$extension")
            if (!partial.exists() && destination.isFile && File(partial.path + ".resume.json").isFile &&
                bookRepository.getBookById(bookId)?.isCloudOnly == true) destination.renameTo(partial)
            val downloadUrl = resolveUrl(
                MyBooksConfig.SERVER_URL,
                file.downloadUrl ?: if (file.path.startsWith("http://") || file.path.startsWith("https://")) {
                    file.path
                } else {
                    "/books-files/${file.path.removePrefix("/")}"
                }
            )
            _downloadStates.value = _downloadStates.value +
                (bookId to BookDownloadState.Downloading(0L, file.size))
            val receipt = client.downloadFile(downloadUrl, partial, file.size) { bytesRead, totalBytes ->
                _downloadStates.value = _downloadStates.value +
                    (bookId to BookDownloadState.Downloading(bytesRead, totalBytes))
            }
            if (!partial.renameTo(destination)) {
                error(context.getString(R.string.mybooks_download_write_failed))
            }
            val readingFile = if (format == BookFormat.MARKDOWN) {
                com.huangder.lumibooks.util.markdown.MarkdownSnapshotStore.create(
                    context,
                    bookId,
                    destination.absolutePath
                )
            } else {
                destination
            }
            var coverPath = bookRepository.getBookById(bookId)?.coverPath
            if (coverPath.isNullOrBlank()) {
                coverPath = runCatching {
                    val parser = BookParserFactory.createParser(format, context)
                    try {
                        parser.extractCoverPath(readingFile.absolutePath)
                    } finally {
                        parser.close()
                    }
                }.getOrNull()
            }
            readingSync.markDownloaded(bookId, readingFile.absolutePath, format.name, receipt.sha256)
            readingSync.applyKnownProgress(bookId)
            File(partial.path + ".resume.json").delete()
            val updated = bookRepository.getBookById(bookId)
                ?: error(context.getString(R.string.mybooks_download_read_failed))
            _downloadStates.value = _downloadStates.value + (bookId to BookDownloadState.Completed)
            scope.launch {
                delay(1_500)
                if (_downloadStates.value[bookId] == BookDownloadState.Completed) {
                    _downloadStates.value = _downloadStates.value - bookId
                }
            }
            CloudBookDownloadResult(updated, context.getString(R.string.mybooks_download_done), true)
        } catch (cancelled: CancellationException) {
            _downloadStates.value = _downloadStates.value + (bookId to BookDownloadState.Failed(context.getString(R.string.mybooks_download_paused)))
            throw cancelled
        } catch (error: Exception) {
            // Keep a verified-validator partial file for the next attempt.
            _downloadStates.value = _downloadStates.value +
                (bookId to BookDownloadState.Failed(
                    error.message ?: context.getString(R.string.mybooks_download_failed)
                ))
            CloudBookDownloadResult(
                null,
                error.message ?: context.getString(R.string.mybooks_download_failed),
                false
            )
        }
    }

    private fun parseTags(json: String): List<String> = runCatching {
        parseRemoteTags(JSONArray(json))
    }.getOrElse { emptyList() }

    private fun tagSetEquals(a: List<String>, b: List<String>): Boolean =
        a.map { it.lowercase() }.toSet() == b.map { it.lowercase() }.toSet()

    private fun parseEpoch(value: String?): Long? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }

    private fun String.toBookFormatOrNull(): BookFormat? = when (uppercase()) {
        "EPUB" -> BookFormat.EPUB
        "PDF" -> BookFormat.PDF
        "TXT" -> BookFormat.TXT
        "MOBI" -> BookFormat.MOBI
        "AZW" -> BookFormat.AZW
        "AZW3" -> BookFormat.AZW3
        "CBZ" -> BookFormat.CBZ
        "MD", "MARKDOWN" -> BookFormat.MARKDOWN
        else -> null
    }

    private fun BookFormat.extensionName(): String = when (this) {
        BookFormat.MARKDOWN -> "md"
        else -> name.lowercase()
    }

    internal companion object {
        const val KEY_READING_CAPABILITY = "mybooks_reading_supported"
        const val KEY_PORTABLE_CAPABILITY = "mybooks_portable_supported"
        const val KEY_CURSOR = "mybooks_books_cursor"
        const val KEY_LAST_SYNC = "mybooks_last_sync_at"
        const val KEY_LIBRARY_ID = "mybooks_library_id"
        internal const val KEY_BOUND_SERVER_URL = "mybooks_bound_server_url"
    }
}
