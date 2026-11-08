package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.BookEntity
import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import com.huangder.lumibooks.data.local.entity.MyBooksPendingDeleteEntity
import com.huangder.lumibooks.data.local.entity.TagEntity
import com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity
import com.huangder.lumibooks.data.repository.BookRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksSyncReliabilityTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var reading: MyBooksReadingSync
    private lateinit var store: MyBooksSyncStateStore
    private lateinit var manager: MyBooksSyncManager
    private lateinit var configState: MutableStateFlow<MyBooksConnection>
    private lateinit var oldConnection: MyBooksConnection
    private lateinit var oldToken: String
    private lateinit var repository: BookRepositoryImpl

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        // Isolate the test account in memory; never read or change encrypted device credentials.
        @Suppress("UNCHECKED_CAST")
        configState = MyBooksConfig::class.java.getDeclaredField("state").apply { isAccessible = true }
            .get(null) as MutableStateFlow<MyBooksConnection>
        oldConnection = configState.value
        val tokenField = MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }
        oldToken = tokenField.get(null) as String
        configState.value = MyBooksConnection(server.url("/").toString().trimEnd('/'), "test", true)
        tokenField.set(null, "test-session")
        repository = BookRepositoryImpl(db.bookDao(), db.syncStateDao(), SyncIdentityStore(db.syncStateDao()))
        reading = MyBooksReadingSync(db, SyncIdentityStore(db.syncStateDao()), context)
        store = MyBooksSyncStateStore(db.syncStateDao(), db.myBooksSyncStateDao(), repository, db.tagDao(), reading)
        manager = MyBooksSyncManager(repository, db.tagDao(), db.syncStateDao(), db.myBooksSyncStateDao(),
            store, MyBooksSyncScheduler(context, store, MyBooksSyncPreferencesStore(db.syncStateDao())),
            LocalNetworkAccessManager(context), reading, context, MyBooksSyncPreferencesStore(db.syncStateDao()),
            javax.inject.Provider { error("portable capability not declared by this fixture") },
            javax.inject.Provider { MyBooksBookTransfer(db, manager, context) }, db)
    }

    @After fun cleanup() {
        configState.value = oldConnection
        MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }.set(null, oldToken)
        server.shutdown()
        db.close()
    }

    private fun remote(title: String = "Remote", revision: Long = 1) = JSONObject().apply {
        put("id", 1); put("uuid", "book-one"); put("revision", revision)
        put("title", title); put("author", "Author"); put("updated_at", "2026-10-28T00:00:00Z")
        put("tags", JSONArray()); put("files", JSONArray())
    }
    private fun snapshot(vararg books: JSONObject) = JSONObject().apply {
        put("sync_version", 2); put("complete", true); put("library_id", "test-library")
        put("books", JSONArray(books.toList())); put("deleted", JSONArray())
    }.toString()
    private suspend fun localBook(id: String = "mybooks-1", title: String = "Remote", readAt: Long = 123L) {
        db.bookDao().insertBook(BookEntity(id, title, "Author", "/local/book.epub", null, "EPUB",
            readAt, .4f, "kept-position", 1L))
    }

    @Test fun discoveringACloudBookDoesNotReplaceTheLastActuallyReadBook() = runBlocking {
        localBook("read-book", "Actually read", 100)
        server.enqueue(MockResponse().setBody(snapshot(remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val imported = db.bookDao().getBookById("mybooks-1")!!
        assertEquals(0L, imported.lastReadTime)
        assertTrue(imported.isCloudOnly)
        assertEquals("read-book", repository.getAllBooks().first().maxBy { it.lastReadTime }.id)
        assertTrue(store.status.first().lastSuccessAt > 0)
    }

    @Test fun failedAttemptsAndReloadedStatusKeepTheLastCompleteSuccessTime() = runBlocking {
        server.enqueue(MockResponse().setBody(snapshot()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val successAt = store.status.first().lastSuccessAt
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"temporarily unavailable\"}"))
        val failed = manager.syncNow(scheduleRetry = false)
        assertFalse(failed.success)
        assertEquals(MyBooksSyncFailure.TRANSIENT, failed.failure)
        val reloaded = MyBooksSyncStateStore(db.syncStateDao(), db.myBooksSyncStateDao(), repository, db.tagDao(), reading).status.first()
        assertEquals(successAt, reloaded.lastSuccessAt)
        assertEquals(MyBooksSyncFailure.TRANSIENT, reloaded.failure)
        assertTrue(reloaded.message.contains("temporarily unavailable"))
        assertEquals(failed.attemptAt, reloaded.lastAttemptAt)
    }

    @Test fun authenticationFailureStopsAutomaticRetriesAndKeepsLocalChanges() = runBlocking {
        localBook(title = "Offline edit")
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString(),
            overridesJson = "{\"title\":\"Offline edit\"}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired session\"}"))
        val result = manager.syncNow(scheduleRetry = false)
        assertEquals(MyBooksSyncFailure.AUTH, result.failure)
        assertEquals(MyBooksWorkerDecision.STOP, myBooksWorkerDecision(result, 0))
        assertEquals(MyBooksSyncFailure.AUTH, store.status.first().failure)
        assertEquals(1, store.pendingCount.first())
        assertTrue(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson.contains("Offline edit"))
    }

    @Test fun newBookFailureRemainsVisibleEvenBeforeThereIsALocalBookState() = runBlocking {
        server.enqueue(MockResponse().setBody(snapshot(remote())))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"bad book detail\"}"))
        val result = manager.syncNow(scheduleRetry = false)
        assertFalse(result.success)
        assertEquals(1, result.failedCount)
        assertNull(db.myBooksSyncStateDao().get("mybooks-1"))
        assertTrue(store.status.first().message.contains("Remote"))
        assertTrue(store.status.first().message.contains("bad book detail"))
        assertEquals(0L, store.status.first().lastSuccessAt)
    }

    @Test fun anOfflineEditIsPushedWithItsOriginalRevisionAfterTheConnectionRecovers() = runBlocking {
        localBook(title = "Offline edit")
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1,
            baseJson = remote().toString(), overridesJson = "{\"title\":\"Offline edit\"}", editedAtJson = "{\"title\":2}"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(MyBooksSyncFailure.TRANSIENT, manager.syncNow(scheduleRetry = false).failure)
        assertEquals(1, store.pendingCount.first())
        server.enqueue(MockResponse().setBody(snapshot(remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        server.enqueue(MockResponse().setBody(remote("Offline edit", 2).toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val requests = (0 until 4).map { server.takeRequest() }
        val patch = requests.single { it.method == "PATCH" }
        val body = JSONObject(patch.body.readUtf8())
        assertEquals(1, body.getInt("expected_revision"))
        assertEquals("book-one", body.getString("expected_uuid"))
        assertEquals("Offline edit", body.getString("title"))
        assertEquals("{}", db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals(1, store.pendingCount.first()) // Reading data waits for a protocol-3 server.
        val book = db.bookDao().getBookById("mybooks-1")!!
        assertEquals("/local/book.epub", book.filePath)
        assertEquals("kept-position", book.locatorJson)
        assertEquals(.4f, book.readingProgress, 0f)
    }

    @Test fun pendingCountIncludesTagOnlyChangesAndDeduplicatesQueuedDeletions() = runBlocking {
        localBook()
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString()))
        val tag = TagEntity(id = "tag-one", name = "New tag", normalizedName = "new tag", createdAt = 1, updatedAt = 1)
        db.tagDao().insertTag(tag)
        db.tagDao().insertBookTagLink(BookTagCrossRefEntity("mybooks-1", tag.id))
        assertEquals(1, store.pendingCount.first())
        db.myBooksSyncStateDao().upsertPendingDelete(MyBooksPendingDeleteEntity(1, "mybooks-1", 1))
        assertEquals(1, store.pendingCount.first())
    }

    private fun delta(cursor: String, complete: Boolean, vararg books: JSONObject): String = JSONObject(snapshot(*books))
        .put("sync_version", 3).put("complete", complete).put("cursor", cursor)
        .put("reading_sync", false).put("reading", JSONArray()).toString()

    @Test fun anUnchangedServerDeltaStillPushesTheLocalOutboxAndAdvancesTheCursor() = runBlocking {
        server.enqueue(MockResponse().setBody(delta("cursor-1", true, remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val state = db.myBooksSyncStateDao().get("mybooks-1")!!
        db.bookDao().updateBookTitleAuthor("mybooks-1", "Offline edit", "Author", 2)
        db.myBooksSyncStateDao().upsert(state.copy(overridesJson = JSONObject().put("title", "Offline edit").toString()))
        server.enqueue(MockResponse().setBody(delta("cursor-2", false)))
        server.enqueue(MockResponse().setBody(remote().toString()))
        server.enqueue(MockResponse().setBody(remote("Offline edit", 2).toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val requests = (0 until 5).map { server.takeRequest() }
        assertEquals("/api/sync/changes?cursor=cursor-1", requests[2].path)
        assertEquals("PATCH", requests.last().method)
        assertEquals("cursor-2", db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
    }

    @Test fun aDeltaDeletionDoesNotFetchOrUploadPendingEditsForTheDeletedBook() = runBlocking {
        server.enqueue(MockResponse().setBody(delta("cursor-1", true, remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val state = db.myBooksSyncStateDao().get("mybooks-1")!!
        db.bookDao().updateBookTitleAuthor("mybooks-1", "Local retained", "Author", 2)
        db.bookDao().applyServerReadingPosition("mybooks-1", .6f, "kept", 100)
        db.myBooksSyncStateDao().upsert(state.copy(overridesJson = JSONObject().put("title", "Local retained").toString()))
        val deleted = JSONObject().put("id", 1).put("uuid", "book-one").put("revision", 2).put("deleted_at", "2026-10-28T00:00:00Z")
        server.enqueue(MockResponse().setBody(JSONObject(delta("cursor-2", false)).put("deleted", JSONArray().put(deleted)).toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertTrue(db.myBooksSyncStateDao().get("mybooks-1")!!.remoteDeleted)
        assertEquals("Local retained", db.bookDao().getBookById("mybooks-1")!!.title)
        assertEquals("kept", db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertEquals(3, server.requestCount)
    }

    @Test fun failedDeltaItemsDoNotAdvanceTheCursorPastUndeliveredChanges() = runBlocking {
        server.enqueue(MockResponse().setBody(delta("cursor-1", true)))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        server.enqueue(MockResponse().setBody(delta("cursor-2", false, remote())))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        assertFalse(manager.syncNow(scheduleRetry = false).success)
        assertEquals("cursor-1", db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
        server.enqueue(MockResponse().setBody(delta("cursor-2", false, remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertEquals("cursor-2", db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
    }

    @Test fun deletingALocalBookCleansOnlyItsOwnPausedDownloadArtifacts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = com.huangder.lumibooks.util.FileUtils.getBooksDirectory(context)
        val partial = java.io.File(directory, "mybooks-1.epub.part").apply { writeText("partial") }
        val metadata = java.io.File(directory, "mybooks-1.epub.part.resume.json").apply { writeText("metadata") }
        val other = java.io.File(directory, "mybooks-2.epub.part").apply { writeText("other") }
        manager.clearLocalBookData("mybooks-1")
        assertFalse(partial.exists()); assertFalse(metadata.exists()); assertTrue(other.exists())
        other.delete()
        Unit
    }

    @Test fun anOldWorkerCannotMarkANewerSuccessfulSyncAsExhausted() = runBlocking {
        store.started(10)
        store.completed(MyBooksSyncResult("offline", false, failure = MyBooksSyncFailure.TRANSIENT, attemptAt = 10))
        store.started(20)
        store.completed(MyBooksSyncResult("complete", true, attemptAt = 20))
        store.retryExhausted(10)
        assertFalse(store.status.first().retryStopped)
        assertNull(store.status.first().failure)
        assertTrue(store.status.first().lastSuccessAt > 0L)
    }

    @Test fun foregroundRequestsAreCoalescedButANewerEditKeepsOneSuccessor() {
        val running = androidx.work.WorkInfo.State.RUNNING
        val queued = androidx.work.WorkInfo.State.ENQUEUED
        val blocked = androidx.work.WorkInfo.State.BLOCKED
        assertTrue(canEnqueueMyBooksSync(emptyList(), false))
        assertFalse(canEnqueueMyBooksSync(listOf(running), false))
        assertTrue(canEnqueueMyBooksSync(listOf(running), true))
        assertFalse(canEnqueueMyBooksSync(listOf(running, blocked), true))
        assertFalse(canEnqueueMyBooksSync(listOf(queued), true))
    }

    @Test fun retryBudgetAndPermanentFailuresAreHandledSeparately() {
        val transient = MyBooksSyncResult("temporary", false, failure = MyBooksSyncFailure.TRANSIENT)
        assertEquals(MyBooksWorkerDecision.RETRY, myBooksWorkerDecision(transient, 3))
        assertEquals(MyBooksWorkerDecision.STOP, myBooksWorkerDecision(transient, 4))
        for (failure in listOf(MyBooksSyncFailure.AUTH, MyBooksSyncFailure.CONFLICT,
            MyBooksSyncFailure.PERMISSION, MyBooksSyncFailure.OTHER)) {
            assertEquals(MyBooksWorkerDecision.STOP,
                myBooksWorkerDecision(MyBooksSyncResult("blocked", false, failure = failure), 0))
        }
        assertEquals(MyBooksSyncFailure.TRANSIENT, classifyMyBooksSyncFailure(IOException("offline")))
        assertEquals(MyBooksSyncFailure.OTHER, classifyMyBooksSyncFailure(SSLHandshakeException("certificate")))
        assertEquals(MyBooksSyncFailure.CONFLICT, classifyMyBooksSyncFailure(MyBooksHttpException(409, "{}")))
    }

    @Test fun downloadingServerUpdatesNeverUploadsLocalEditsAndDoesNotConsumeTheFullSyncCursor() = runBlocking {
        localBook(title = "Offline title", readAt = 0)
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString(),
            overridesJson = "{\"title\":\"Offline title\"}"))
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity(MyBooksSyncManager.KEY_CURSOR, "before"))
        val changed = remote(revision = 2).put("author", "Updated author")
        server.enqueue(MockResponse().setBody(delta("after", false, changed)))
        server.enqueue(MockResponse().setBody(changed.toString()))
        val result = manager.syncNow(scheduleRetry = false, direction = MyBooksSyncDirection.DOWNLOAD)
        assertTrue(result.message, result.success)
        assertEquals("Offline title", db.bookDao().getBookById("mybooks-1")!!.title)
        assertEquals("Updated author", db.bookDao().getBookById("mybooks-1")!!.author)
        assertTrue(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson.contains("Offline title"))
        assertEquals("before", db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
    }

    @Test fun selectedUploadsLeaveOtherBooksPending() = runBlocking {
        for (id in 1..2) {
            localBook("mybooks-" + id, "Local " + id, 0)
            db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-" + id, id.toLong(),
                baseJson = remote().put("id", id).put("uuid", "book-" + id).toString(),
                overridesJson = JSONObject().put("title", "Local " + id).toString()))
        }
        server.enqueue(MockResponse().setBody(delta("not-consumed", true)))
        server.enqueue(MockResponse().setBody(remote().put("uuid", "book-1").toString()))
        server.enqueue(MockResponse().setBody(remote("Local 1", 2).put("uuid", "book-1").toString()))
        assertTrue(manager.syncNow(false, MyBooksSyncDirection.UPLOAD, setOf("mybooks-1")).success)
        assertEquals("{}", db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertTrue(db.myBooksSyncStateDao().get("mybooks-2")!!.overridesJson.contains("Local 2"))
        assertNull(db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
        assertEquals(3, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("/api/books/1", server.takeRequest().path)
        assertEquals("PATCH", server.takeRequest().method)
    }

    @Test fun serverManualModePersistsAndSkipsAutomaticReaderRefreshWithoutChangingWebdavState() = runBlocking {
        val preferences = MyBooksSyncPreferencesStore(db.syncStateDao())
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity("webdav_keep", "unchanged"))
        preferences.setAutomatic(false)
        assertFalse(MyBooksSyncPreferencesStore(db.syncStateDao()).automatic())
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity(MyBooksSyncManager.KEY_LIBRARY_ID, "test-library"))
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity(MyBooksSyncManager.KEY_READING_CAPABILITY, "true"))
        manager.syncReadingChanges()
        assertEquals(0, server.requestCount)
        assertEquals("unchanged", db.syncStateDao().getValue("webdav_keep"))
        server.enqueue(MockResponse().setBody(snapshot()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertEquals(1, server.requestCount)
    }

    @Test fun uploadedBookAssociationPreservesItsCopyAnnotationsStatisticsTagsAndFolder() = runBlocking {
        localBook("local", "Edited during upload", 999)
        val rows = db.syncStateDao()
        rows.upsertBookmarks(listOf(com.huangder.lumibooks.data.local.entity.BookmarkEntity(bookId = "local", chapterIndex = 0,
            position = .3f, title = "Saved", createdAt = 1, syncId = "mark")))
        rows.upsertNotes(listOf(com.huangder.lumibooks.data.local.entity.NoteEntity(bookId = "local", chapterIndex = 0,
            startPosition = 1, endPosition = 2, selectedText = "quote", note = "Kept", color = "yellow", createdAt = 1, syncId = "note")))
        rows.upsertReadingRecords(listOf(com.huangder.lumibooks.data.local.entity.ReadingRecordEntity(bookId = "local", date = "2026-10-28",
            duration = 123, startTime = 1, endTime = 124, sourceDeviceId = "device")))
        db.tagDao().createAndAssignTag("local", TagEntity("tag", "Tag", "tag", 1))
        db.folderDao().insertFolder(com.huangder.lumibooks.data.local.entity.FolderEntity("folder", "Folder", "folder", null, 1))
        db.openHelper.writableDatabase.execSQL("INSERT INTO book_folder_cross_refs(bookId,folderId,updatedAt) VALUES('local','folder',1)")
        val transfer = MyBooksBookTransfer(db, manager, ApplicationProvider.getApplicationContext())
        val initial = JSONObject().put("title", "Original upload").put("author", "Author").put("tags", JSONArray().put("Tag"))
        val id = transfer.adoptUploadedBook("local", remote("Original upload").put("tags", JSONArray().put("Tag")).toString(),
            "a".repeat(64), 100, initial, "pending")
        assertEquals("mybooks-1", id)
        assertNull(db.bookDao().getBookById("local"))
        val book = db.bookDao().getBookById(id)!!
        assertEquals("/local/book.epub", book.filePath)
        assertEquals("Edited during upload", book.title)
        assertEquals("kept-position", book.locatorJson)
        assertEquals(999L, book.lastReadTime)
        assertEquals(id, rows.getAllBookmarks().single().bookId)
        assertEquals(id, rows.getAllNotes().single().bookId)
        assertEquals(123L, rows.getAllReadingRecords().single().duration)
        assertEquals(id, rows.getAllReadingRecords().single().bookId)
        assertEquals("Tag", db.tagDao().getTagsForBook(id).single().name)
        db.openHelper.readableDatabase.query("SELECT bookId FROM book_folder_cross_refs").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(id, cursor.getString(0))
        }
        assertTrue(db.myBooksSyncStateDao().get(id)!!.overridesJson.contains("Edited during upload"))
    }

    @Test fun aTargetCollisionLeavesTheOriginalBookAndItsReadingDataIntact() = runBlocking {
        localBook("local", "Original", 999); localBook("mybooks-1", "Existing", 0)
        val transfer = MyBooksBookTransfer(db, manager, ApplicationProvider.getApplicationContext())
        try {
            transfer.adoptUploadedBook("local", remote().toString(), "a".repeat(64), 1,
                JSONObject().put("title", "Original").put("author", "Author").put("tags", JSONArray()), "pending")
            fail("must reject duplicate association")
        } catch (_: IllegalStateException) { }
        assertEquals("Original", db.bookDao().getBookById("local")!!.title)
        assertEquals(999L, db.bookDao().getBookById("local")!!.lastReadTime)
        assertEquals("Existing", db.bookDao().getBookById("mybooks-1")!!.title)
    }

    @Test fun retryingAnInterruptedLocalBookUploadReusesItsCreatedServerBook() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val body = "local book contents".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val file = java.io.File.createTempFile("mybooks-test-upload", ".txt", context.cacheDir).apply { writeBytes(body) }
        db.bookDao().insertBook(BookEntity("local", "Local book", "Author", file.path, null, "TXT", 0, 0f, createdAt = 1))
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity("book_import_metadata:local",
            JSONObject().put("fields", JSONObject().put("title", "Local book").put("author", "Author").put("isbn", "9781234567897").put("publisher", "Draft publisher").put("status", "reading")).toString()))
        var creates = 0; var uploads = 0; var published = false
        fun detail() = remote("Local book", if (published) 2 else 1).put("files", JSONArray().apply {
            if (published) put(JSONObject().put("format", "TXT").put("path", "1/book.txt").put("size", body.size)
                .put("download_url", "/api/books/1/files/1/content?uuid=book-one"))
        })
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/sync/changes") -> MockResponse().setBody(delta("upload-cursor", true, detail()))
                request.path == "/api/books" && request.method == "POST" -> {
                    val payload = JSONObject(request.body.readUtf8())
                    assertEquals("9781234567897", payload.getString("isbn")); assertEquals("Draft publisher", payload.getString("publisher")); assertEquals("reading", payload.getString("status"))
                    creates++; MockResponse().setBody(detail().toString())
                }
                request.path == "/api/books/1" -> MockResponse().setBody(detail().toString())
                request.path == "/api/books/1/file" -> {
                    uploads++
                    if (uploads == 1) MockResponse().setResponseCode(503).setBody("{}")
                    else { published = true; MockResponse().setBody("{}") }
                }
                request.method == "HEAD" -> MockResponse().setHeader("X-Content-SHA256", hash)
                else -> MockResponse().setResponseCode(404)
            }
        }
        try {
            val transfer = MyBooksBookTransfer(db, manager, context)
            val first = transfer.upload(setOf("local")) { }
            assertEquals(1, first.errors.size)
            assertEquals(1, store.pendingCount.first())
            assertTrue(manager.syncIssues.first().any { it.pendingUpload })
            assertNotNull(db.bookDao().getBookById("local"))
            assertEquals(1, creates)
            MyBooksSyncPreferencesStore(db.syncStateDao()).setContent(com.huangder.lumibooks.domain.model.SyncContent.BOOK_FILES, false)
            assertTrue(manager.syncNow(scheduleRetry = false).success)
            assertNull(db.bookDao().getBookById("mybooks-1"))
            MyBooksSyncPreferencesStore(db.syncStateDao()).setContent(com.huangder.lumibooks.domain.model.SyncContent.BOOK_FILES, true)
            val second = transfer.upload(setOf("local")) { }
            assertTrue(second.errors.joinToString(), second.errors.isEmpty())
            assertEquals(setOf("mybooks-1"), second.bookIds)
            assertEquals(0, store.pendingCount.first())
            assertTrue(manager.syncIssues.first().none { it.pendingUpload })
            assertEquals(1, creates); assertEquals(2, uploads)
            assertEquals(file.path, db.bookDao().getBookById("mybooks-1")!!.filePath)
            assertNull(db.bookDao().getBookById("local"))
            assertNull(db.syncStateDao().getValue("book_import_metadata:local"))
        } finally { file.delete() }
    }


    @Test fun anActivePendingImportProtectsServerIdentityButADeletedLocalBookCannotTrapTheConnection() = runBlocking {
        localBook("pending-local", "Pending", 0)
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity("mybooks_upload:library:pending-local",
            JSONObject().put("id", 12).put("localBookId", "pending-local").toString()))
        try { manager.checkServerChange("https://other.example"); fail("pending import must protect its server") }
        catch (_: IllegalStateException) { }
        db.bookDao().deleteBookById("pending-local")
        manager.checkServerChange("https://other.example")
    }

    @Test fun cloudDeletionUsesTheOriginalUuidAndRevisionAndDoesNotDeleteLocalDataOnFailure() = runBlocking {
        localBook()
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote(revision = 4).toString()))
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"changed on another device\"}"))
        val result = manager.deleteRemoteBooks(setOf("mybooks-1"))
        assertFalse(result.success)
        val request = server.takeRequest(); assertEquals("DELETE", request.method)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(4L, body.getLong("expected_revision")); assertEquals("book-one", body.getString("expected_uuid"))
        assertNotNull(db.bookDao().getBookById("mybooks-1"))
        assertEquals("kept-position", db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertNotNull(db.myBooksSyncStateDao().get("mybooks-1"))
    }
    @Test fun clearingOnlyTheDownloadedCopyKeepsServerAssociationAndReadingBaselines() = runBlocking {
        localBook()
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString()))
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity("mybooks_reading_base:book-one", "kept"))
        manager.clearLocalBookData("mybooks-1", keepServerAssociation = true)
        assertNotNull(db.myBooksSyncStateDao().get("mybooks-1"))
        assertEquals("kept", db.syncStateDao().getValue("mybooks_reading_base:book-one"))
        assertEquals("kept-position", db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertEquals(0, server.requestCount)
    }
    @Test fun disablingAllContentDoesNotMakeAnyNetworkRequest() = runBlocking {
        val preferences = MyBooksSyncPreferencesStore(db.syncStateDao())
        for (content in com.huangder.lumibooks.domain.model.SyncContent.entries) preferences.setContent(content, false)
        val result = manager.syncNow(scheduleRetry = false)
        assertFalse(result.success); assertEquals(0, server.requestCount)
    }

    @Test fun forcedCloudDeletionUsesItsQueueEvenWithNoLocalRowAndAllSyncContentDisabled() = runBlocking {
        localBook()
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote(revision = 7).toString()))
        assertTrue(manager.queuePendingDelete("mybooks-1"))
        db.bookDao().deleteBookById("mybooks-1")
        manager.clearLocalBookData("mybooks-1")
        val preferences = MyBooksSyncPreferencesStore(db.syncStateDao())
        for (content in com.huangder.lumibooks.domain.model.SyncContent.entries) preferences.setContent(content, false)
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        assertFalse(manager.deleteRemoteBooks(setOf("mybooks-1")).success)
        assertNotNull(db.myBooksSyncStateDao().getPendingDelete(1))
        val failed = JSONObject(requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) { "Expected cloud DELETE request" }.body.readUtf8())
        assertEquals(7L, failed.getLong("expected_revision")); assertEquals("book-one", failed.getString("expected_uuid"))
        server.enqueue(MockResponse().setBody("{}"))

        assertTrue(manager.deleteRemoteBooks(setOf("mybooks-1")).success)
        val retry = requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) { "Expected cloud DELETE request" }; assertEquals("DELETE", retry.method); assertEquals("/api/books/1", retry.path)
        assertNull(db.myBooksSyncStateDao().getPendingDelete(1))
        assertNull(db.bookDao().getBookById("mybooks-1"))
    }

    @Test fun automaticRetryAdoptsAnAlreadyUploadedRelativeUrlWithoutCreatingOrUploadingAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val body = "already uploaded book contents".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val file = java.io.File.createTempFile("mybooks-relative-upload", ".txt", context.cacheDir).apply { writeBytes(body) }
        db.bookDao().insertBook(BookEntity("local", "Imported book", "Author", file.path, null, "TXT", 123, .4f, "kept-position", 1))
        val key = "mybooks_upload:test-library:local"
        val initial = JSONObject().put("title", "Imported book").put("author", "Author").put("tags", JSONArray())
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity(key,
            JSONObject().put("id", 1).put("uuid", "book-one").put("revision", 1).put("localBookId", "local").put("initial", initial).toString()))
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity(
            "mybooks_upload_error:test-library:local", JSONObject().put("bookId", "local").put("message", "previous URL failure").toString()))
        val path = "/api/books/1/files/1/content?uuid=book-one"
        val detail = remote("Imported book", 2).put("files", JSONArray().put(JSONObject().put("format", "TXT")
            .put("path", "1/book.txt").put("size", body.size).put("download_url", path)))
        var writes = 0; var checks = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.method != "GET" && request.method != "HEAD") { writes++; return MockResponse().setResponseCode(500) }
                return when {
                    request.path!!.startsWith("/api/sync/changes") -> MockResponse().setBody(delta("recovered", true, detail))
                    request.path == "/api/books/1" -> MockResponse().setBody(detail.toString())
                    request.method == "HEAD" && request.path == path -> {
                        assertEquals("Bearer test-session", request.getHeader("Authorization"))
                        checks++; MockResponse().setHeader("X-Content-SHA256", hash)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        try {
            val result = manager.syncNow(scheduleRetry = false)
            assertTrue(result.message, result.success)
            assertEquals(0, writes); assertEquals(2, checks)
            assertNull(db.bookDao().getBookById("local"))
            val adopted = db.bookDao().getBookById("mybooks-1")!!
            assertEquals(file.path, adopted.filePath); assertEquals("kept-position", adopted.locatorJson)
            assertEquals(123L, adopted.lastReadTime); assertEquals(.4f, adopted.readingProgress, 0f)
            assertEquals(hash, adopted.sourceSha256)
            assertNull(db.syncStateDao().getValue(key))
            assertNull(db.syncStateDao().getValue("mybooks_upload_error:test-library:local"))
            assertTrue(manager.syncIssues.first().none { it.pendingUpload })
            assertTrue(reading.pendingFingerprints.first().containsKey("mybooks-1"))
        } finally { file.delete() }
    }

    @Test fun clearingServerConfigurationPreservesEveryBookCoverReadingRowAndPendingChange() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cover = java.io.File.createTempFile("mybooks-clear-cover", ".jpg", context.cacheDir).apply { writeText("local cover") }
        val body = java.io.File.createTempFile("mybooks-clear-body", ".txt", context.cacheDir).apply { writeText("local body") }
        val rows = db.syncStateDao()
        try {
            localBook()
            db.bookDao().insertBook(db.bookDao().getBookById("mybooks-1")!!.copy(filePath = body.path, coverPath = cover.path,
                sourceUri = MyBooksConfig.SOURCE_URI_PREFIX + "1", remoteLibraryKey = MyBooksConfig.LIBRARY_KEY))
            db.bookDao().insertBook(BookEntity("mybooks-2", "Cloud book", "Author", "", cover.path, "TXT", 0, 0f, createdAt = 1, isCloudOnly = true))
            db.bookDao().insertBook(BookEntity("native-local", "Local book", "Author", body.path, cover.path, "TXT", 0, 0f, createdAt = 1))
            db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString(),
                overridesJson = "{\"summary\":\"offline edit\"}", coverDirty = true))
            rows.upsertBookmarks(listOf(com.huangder.lumibooks.data.local.entity.BookmarkEntity(bookId = "mybooks-1", chapterIndex = 0,
                position = .3f, title = "Saved", createdAt = 1, syncId = "mark")))
            rows.upsertNotes(listOf(com.huangder.lumibooks.data.local.entity.NoteEntity(bookId = "mybooks-1", chapterIndex = 0,
                startPosition = 1, endPosition = 2, selectedText = "quote", note = "Kept", color = "yellow", createdAt = 1, syncId = "note")))
            rows.upsertReadingRecords(listOf(com.huangder.lumibooks.data.local.entity.ReadingRecordEntity(bookId = "mybooks-1", date = "2026-10-28",
                duration = 123, startTime = 1, endTime = 124, sourceDeviceId = "device")))
            db.tagDao().createAndAssignTag("mybooks-1", TagEntity("tag", "Tag", "tag", 1))
            db.folderDao().insertFolder(com.huangder.lumibooks.data.local.entity.FolderEntity("folder", "Folder", "folder", null, 1))
            rows.upsertBookFolderLinks(listOf(com.huangder.lumibooks.data.local.entity.BookFolderCrossRefEntity("mybooks-1", "folder", 1)))
            val pendingDelete = MyBooksPendingDeleteEntity(3, "mybooks-3", 1, expectedUuid = "book-three", expectedRevision = 4)
            db.myBooksSyncStateDao().upsertPendingDelete(pendingDelete)
            val keys = mapOf(MyBooksSyncManager.KEY_LIBRARY_ID to "test-library", MyBooksSyncManager.KEY_CURSOR to "saved-cursor",
                "mybooks_reading_keep" to "reading-baseline", "mybooks_portable_revision:test-library" to "7",
                "mybooks_upload:test-library:native-local" to JSONObject().put("id", 4).put("localBookId", "native-local").toString())
            rows.putStates(keys.map { com.huangder.lumibooks.data.local.entity.SyncStateEntity(it.key, it.value) })
            val books = db.bookDao().getAllBooksSnapshot().sortedBy { it.id }
            val states = db.myBooksSyncStateDao().getAll()
            val marks = rows.getAllBookmarks(); val notes = rows.getAllNotes(); val records = rows.getAllReadingRecords()
            val tags = rows.getAllBookTagLinks(); val folders = rows.getAllBookFolderLinks()
            server.enqueue(MockResponse().setBody("{}"))
            manager.clearConfiguration()
            assertEquals(books, db.bookDao().getAllBooksSnapshot().sortedBy { it.id })
            assertEquals(states, db.myBooksSyncStateDao().getAll()); assertEquals(pendingDelete, db.myBooksSyncStateDao().getPendingDelete(3))
            assertEquals(marks, rows.getAllBookmarks()); assertEquals(notes, rows.getAllNotes()); assertEquals(records, rows.getAllReadingRecords())
            assertEquals(tags, rows.getAllBookTagLinks()); assertEquals(folders, rows.getAllBookFolderLinks())
            keys.forEach { (key, value) -> assertEquals(value, rows.getValue(key)) }
            assertEquals("local cover", cover.readText()); assertEquals("local body", body.readText())
            assertFalse(MyBooksConfig.hasWriteToken()); assertEquals("", MyBooksConfig.SERVER_URL)
            assertEquals("", MyBooksConfig.connection.value.username)
            assertFalse(MyBooksSyncPreferencesStore(rows).current().enabled)
            assertFalse(manager.downloadBook("mybooks-2").success)
            assertEquals(1, server.requestCount)
            MyBooksConfig.initialize(context)
            assertFalse(MyBooksConfig.hasWriteToken()); assertEquals("", MyBooksConfig.SERVER_URL)
            manager.checkServerChange(server.url("/").toString())
            try { manager.checkServerChange("https://other.example"); fail("retained books must protect their original server") }
            catch (_: IllegalStateException) { }
        } finally { cover.delete(); body.delete() }
    }
    @Test fun reconnectingTheOriginalServerAfterClearingDoesNotCreateBooksOrUploadBodies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val body = java.io.File.createTempFile("mybooks-reconnect-body", ".txt", context.cacheDir).apply { writeText("local copy") }
        val cover = java.io.File.createTempFile("mybooks-reconnect-cover", ".jpg", context.cacheDir).apply { writeText("local cover") }
        try {
            localBook()
            db.bookDao().insertBook(db.bookDao().getBookById("mybooks-1")!!.copy(filePath = body.path, coverPath = cover.path,
                sourceUri = MyBooksConfig.SOURCE_URI_PREFIX + "1", remoteLibraryKey = MyBooksConfig.LIBRARY_KEY))
            val original = db.bookDao().getBookById("mybooks-1")!!
            db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString()))
            db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity(MyBooksSyncManager.KEY_LIBRARY_ID, "test-library"))
            var bookWrites = 0
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path == "/api/auth/logout" -> MockResponse().setBody("{}")
                    request.path!!.startsWith("/api/sync/changes") -> MockResponse().setBody(delta("reconnected", true, remote()))
                    request.path == "/api/books/1" && request.method == "GET" -> MockResponse().setBody(remote().toString())
                    request.path!!.startsWith("/api/books") && request.method != "GET" -> { bookWrites++; MockResponse().setResponseCode(409).setBody("{}") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            manager.clearConfiguration()
            assertEquals(original, db.bookDao().getBookById("mybooks-1"))
            val endpoint = server.url("/").toString().trimEnd('/')
            manager.checkServerChange(endpoint)
            configState.value = MyBooksConnection(endpoint, "test", true)
            MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }.set(null, "renewed-session")
            MyBooksSyncPreferencesStore(db.syncStateDao()).setEnabled(true)
            val result = manager.syncNow(scheduleRetry = false)
            assertTrue(result.message, result.success); assertEquals(0, bookWrites)
            assertEquals(listOf("mybooks-1"), db.bookDao().getAllBooksSnapshot().map { it.id })
            assertEquals(original, db.bookDao().getBookById("mybooks-1"))
            assertEquals("local cover", cover.readText()); assertEquals("local copy", body.readText())
        } finally { body.delete(); cover.delete() }
    }
    @Test fun aFailedRemoteLogoutStillClearsCredentialsWithoutDiscardingLocalBooks() = runBlocking {
        localBook()
        val original = db.bookDao().getBookById("mybooks-1")!!
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString()))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        manager.clearConfiguration()
        assertFalse(MyBooksConfig.hasWriteToken()); assertEquals("", MyBooksConfig.SERVER_URL)
        assertEquals(original, db.bookDao().getBookById("mybooks-1"))
        assertNotNull(db.myBooksSyncStateDao().get("mybooks-1"))
    }

    private fun deletionDelta(cursor: String, uuid: String = "book-one", revision: Long = 2): String =
        JSONObject(delta(cursor, false)).put("deleted", JSONArray().put(JSONObject()
            .put("id", 1).put("uuid", uuid).put("revision", revision).put("deleted_at", "2026-10-29T00:00:00Z"))).toString()

    @Test fun webDeletionRemovesAnUnmodifiedDownloadedCopyAndDoesNotRecreateIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val body = java.io.File(com.huangder.lumibooks.util.FileUtils.getBooksDirectory(context), "mybooks-1.epub").apply { writeText("body") }
        try {
            localBook(readAt = 0)
            db.bookDao().updateBook(db.bookDao().getBookById("mybooks-1")!!.copy(filePath = body.path, readingProgress = 0f, locatorJson = null))
            db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString()))
            server.enqueue(MockResponse().setBody(deletionDelta("deleted")))
            assertTrue(manager.syncNow(scheduleRetry = false).success)
            assertNull(db.bookDao().getBookById("mybooks-1")); assertFalse(body.exists())
            assertNull(db.myBooksSyncStateDao().get("mybooks-1"))
            assertEquals(0, store.pendingCount.first()); assertTrue(manager.syncIssues.first().isEmpty())
            server.enqueue(MockResponse().setBody(delta("empty", false)))
            assertTrue(manager.syncNow(scheduleRetry = false).success)
            assertNull(db.bookDao().getBookById("mybooks-1")); assertEquals(2, server.requestCount)
        } finally { body.delete() }
    }

    @Test fun webDeletionRetainsDirtyReadingAcrossRepeatedSyncButClearsObsoleteConflicts() = runBlocking {
        localBook()
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString(), conflictFieldsJson = "[\"title\"]", lastError = "old conflict"))
        db.syncStateDao().putState(com.huangder.lumibooks.data.local.entity.SyncStateEntity(
            MyBooksReadingSync.CONFLICT + "book-one", JSONObject().put("bookId", "mybooks-1").toString()))
        server.enqueue(MockResponse().setBody(deletionDelta("deleted")))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertNotNull(db.bookDao().getBookById("mybooks-1"))
        assertTrue(db.myBooksSyncStateDao().get("mybooks-1")!!.remoteDeleted)
        assertEquals("[]", db.myBooksSyncStateDao().get("mybooks-1")!!.conflictFieldsJson)
        assertNull(db.syncStateDao().getValue(MyBooksReadingSync.CONFLICT + "book-one"))
        assertTrue(manager.syncIssues.first().none { it.canResolve || it.readingConflict })
        assertEquals(0, store.pendingCount.first())
        server.enqueue(MockResponse().setBody(delta("empty", false)))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertEquals("kept-position", db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertFalse(manager.shouldKeepCloudPlaceholder("mybooks-1"))


        assertEquals(2, server.requestCount)
    }

    @Test fun aPreviouslyRetainedCloudPlaceholderCanBeRemovedWithoutAnotherDeletionEvent() = runBlocking {
        localBook(readAt = 0)
        db.bookDao().updateBook(db.bookDao().getBookById("mybooks-1")!!.copy(filePath = "", isCloudOnly = true))
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString(), remoteDeleted = true))
        assertFalse(manager.shouldKeepCloudPlaceholder("mybooks-1"))
        server.enqueue(MockResponse().setBody(delta("empty", false)))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertNull(db.bookDao().getBookById("mybooks-1"))
    }

    @Test fun deletionForAnotherIdentityNeverRemovesTheLocalCopy() = runBlocking {
        localBook(readAt = 0)
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString()))
        server.enqueue(MockResponse().setBody(deletionDelta("wrong-uuid", uuid = "another-book")))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertNotNull(db.bookDao().getBookById("mybooks-1"))
        assertFalse(db.myBooksSyncStateDao().get("mybooks-1")!!.remoteDeleted)
        assertTrue(manager.shouldKeepCloudPlaceholder("mybooks-1"))
    }

    @Test fun rereadStartsAtZeroWithANewDateAndFinishesWithoutErasingHistory() = runBlocking {
        localBook()
        val original = db.bookDao().getBookById("mybooks-1")!!
        db.bookDao().updateBook(original.copy(readingProgress = 1f, locatorJson = "old-last-page"))
        val first = JSONObject().put("start", "2026-10-27").put("finish", "2026-11-01")
        val base = remote().put("status", "done").put("read_count", 1)
            .put("read_logs", JSONArray().put(first))
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = base.toString()))
        manager.setReadingStatus("mybooks-1", com.huangder.lumibooks.domain.model.ReadingStatus.READING, startNewReading = true)
        val started = db.bookDao().getBookById("mybooks-1")!!
        assertEquals(0f, started.readingProgress)
        assertNull(started.locatorJson)
        assertEquals(original.title, started.title)
        assertEquals(original.filePath, started.filePath)
        assertTrue(started.lastReadTime > original.lastReadTime)
        val fields = JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals("reading", fields.getString("status"))
        val logs = fields.getJSONArray("read_logs")
        assertEquals(2, logs.length())
        assertEquals("2026-10-27", logs.getJSONObject(0).getString("start"))
        assertEquals("2026-11-01", logs.getJSONObject(0).getString("finish"))
        assertEquals(java.time.LocalDate.now().toString(), logs.getJSONObject(1).getString("start"))
        assertEquals("", logs.getJSONObject(1).getString("finish"))
        // Idempotence: a stale second tap cannot reset an in-progress reread again.
        db.bookDao().updateBook(started.copy(readingProgress = .25f, locatorJson = "second-position"))
        manager.setReadingStatus("mybooks-1", com.huangder.lumibooks.domain.model.ReadingStatus.READING, startNewReading = true)
        assertEquals(.25f, db.bookDao().getBookById("mybooks-1")!!.readingProgress)
        // The reader invokes this same method when progress first reaches 100%.
        manager.setReadStatus("mybooks-1", true)
        val finished = JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals("done", finished.getString("status"))
        assertEquals(2, finished.getJSONArray("read_logs").length())
        assertEquals(java.time.LocalDate.now().toString(), finished.getJSONArray("read_logs").getJSONObject(1).getString("finish"))
        assertEquals("2026-11-01", finished.getJSONArray("read_logs").getJSONObject(0).getString("finish"))
    }

    @Test fun abandoningAnOpenRereadKeepsTheCompletedCycle() = runBlocking {
        localBook()
        val logs = JSONArray().put(JSONObject().put("start", "2026-10-27").put("finish", "2026-11-01"))
            .put(JSONObject().put("start", "2026-11-04").put("finish", ""))
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1,
            baseJson = remote().put("status", "reading").put("read_logs", logs).toString()))
        manager.setReadingStatus("mybooks-1", com.huangder.lumibooks.domain.model.ReadingStatus.DONE, abandonCurrentReading = true)
        val result = JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals("done", result.getString("status"))
        assertEquals(1, result.getJSONArray("read_logs").length())
        assertEquals("2026-10-27", result.getJSONArray("read_logs").getJSONObject(0).getString("start"))
        assertEquals("2026-11-01", result.getJSONArray("read_logs").getJSONObject(0).getString("finish"))
    }

    @Test fun concurrentRereadRequestsCreateOnlyOnePendingRound() = runBlocking {
        localBook()
        val first = JSONObject().put("start", "2026-10-27").put("finish", "2026-11-01")
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1,
            baseJson = remote().put("status", "done").put("read_logs", JSONArray().put(first)).toString()))
        kotlinx.coroutines.withTimeout(10_000) {
            kotlinx.coroutines.coroutineScope {
                repeat(4) {
                    launch(kotlinx.coroutines.Dispatchers.Default) {
                        manager.setReadingStatus("mybooks-1", com.huangder.lumibooks.domain.model.ReadingStatus.READING, startNewReading = true)
                    }
                }
            }
        }
        val result = JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals("reading", result.getString("status"))
        assertEquals(2, result.getJSONArray("read_logs").length())
    }

    @Test fun legacyCompletedSummaryIsNotReplacedByAFakeFirstReading() = runBlocking {
        localBook()
        val base = remote().put("status", "done").put("read_count", 3)
            .put("start_date", "2026-10-27").put("finish_date", "2026-11-01")
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = base.toString()))
        manager.setReadingStatus("mybooks-1", com.huangder.lumibooks.domain.model.ReadingStatus.READING)
        val fields = JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals("reading", fields.getString("status"))
        assertFalse(fields.has("read_logs"))
        assertEquals(3, JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.baseJson).getInt("read_count"))
    }

    @Test fun dateOnlyEditPersistsOfflineAndPushesOnlyReadLogsWithRevision() = runBlocking {
        localBook()
        val log = MyBooksReadLog("2026-10-01", "2026-10-17")
        val logs = MyBooksReadStatus.logsToJson(listOf(log))
        val base = remote().put("status", "done").put("read_count", 1).put("read_logs", logs)
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = base.toString()))
        val result = manager.editReadLogDate("mybooks-1", readLogDateSource(log, listOf(log))!!,
            ReadLogDateField.FINISH, "2026-10-20")
        val pending = db.myBooksSyncStateDao().get("mybooks-1")!!
        val fields = JSONObject(pending.overridesJson)
        assertEquals(setOf("read_logs"), fields.keys().asSequence().toSet())
        assertEquals(1, JSONObject(result).getInt("read_count"))
        assertEquals("done", JSONObject(result).getString("status"))
        assertEquals("2026-10-01", fields.getJSONArray("read_logs").getJSONObject(0).getString("start"))
        assertEquals(1, store.pendingCount.first())
        assertEquals(0, server.requestCount)
        val updated = JSONObject(base.toString()).put("read_logs", fields.getJSONArray("read_logs")).put("revision", 2)
        server.enqueue(MockResponse().setBody(snapshot(base)))
        server.enqueue(MockResponse().setBody(base.toString()))
        server.enqueue(MockResponse().setBody(updated.toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val patch = (0 until 3).map { server.takeRequest() }.single { it.method == "PATCH" }
        val body = JSONObject(patch.body.readUtf8())
        assertTrue(body.has("read_logs"))
        assertEquals(1, body.getInt("expected_revision"))
        assertEquals("book-one", body.getString("expected_uuid"))
        assertFalse(body.has("status"))
        assertFalse(body.has("title"))
        assertEquals("2026-10-20", body.getJSONArray("read_logs").getJSONObject(0).getString("finish"))
        assertEquals("{}", db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
    }

    @Test fun sourceUpdateWhileWaitingForSyncLockRejectsDateEditWithoutOverwritingIt() = runBlocking {
        localBook()
        val log = MyBooksReadLog("2026-10-01", "2026-10-17")
        val base = remote().put("read_logs", MyBooksReadStatus.logsToJson(listOf(log)))
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = base.toString()))
        var failure: ReadLogDateFailure? = null
        lateinit var job: kotlinx.coroutines.Job
        manager.withBookTransfer {
            job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try {
                    manager.editReadLogDate("mybooks-1", readLogDateSource(log, listOf(log))!!,
                        ReadLogDateField.FINISH, "2026-10-20")
                    fail("Stale source must be rejected")
                } catch (error: ReadLogDateException) { failure = error.reason }
            }
            val latest = JSONObject(base.toString()).put("read_logs", MyBooksReadStatus.logsToJson(listOf(log.copy(start = "2026-10-02"))))
            db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = latest.toString()))
        }
        job.join()
        assertEquals(ReadLogDateFailure.CHANGED, failure)
        assertEquals("{}", db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
    }

    @Test fun dateOnlyEditPreservesUnrelatedPendingMetadataAndReportsServerLogConflict() = runBlocking {
        localBook()
        val log = MyBooksReadLog("2026-10-01", "2026-10-17")
        val base = remote().put("status", "done").put("read_logs", MyBooksReadStatus.logsToJson(listOf(log)))
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = base.toString(),
            overridesJson = "{\"summary\":\"Keep this\"}"))
        manager.editReadLogDate("mybooks-1", readLogDateSource(log, listOf(log))!!,
            ReadLogDateField.START, "2026-10-02")
        assertEquals("Keep this", JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson).getString("summary"))
        val changedRemote = JSONObject(base.toString()).put("read_logs", MyBooksReadStatus.logsToJson(listOf(log.copy(finish = "2026-10-18"))))
        server.enqueue(MockResponse().setBody(snapshot(changedRemote)))
        server.enqueue(MockResponse().setBody(changedRemote.toString()))
        assertFalse(manager.syncNow(scheduleRetry = false).success)
        assertEquals(2, server.requestCount)
        assertTrue(db.myBooksSyncStateDao().get("mybooks-1")!!.conflictFieldsJson.contains("read_logs"))
        assertTrue(JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson).has("read_logs"))
    }

    @Test fun unreadBookWithNullableDatesRecordsItsFirstReadingStart() = runBlocking {
        localBook()
        val base = remote().put("status", "wish").put("read_count", 0)
            .put("start_date", JSONObject.NULL).put("finish_date", JSONObject.NULL).put("read_logs", JSONArray())
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = base.toString()))
        manager.setReadingStatus("mybooks-1", com.huangder.lumibooks.domain.model.ReadingStatus.READING)
        val fields = JSONObject(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals("reading", fields.getString("status"))
        assertEquals(1, fields.getJSONArray("read_logs").length())
        assertEquals(java.time.LocalDate.now().toString(), fields.getJSONArray("read_logs").getJSONObject(0).getString("start"))
        assertEquals("", fields.getJSONArray("read_logs").getJSONObject(0).getString("finish"))
    }
}
