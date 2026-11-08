package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.backup.*
import com.huangder.lumibooks.data.local.entity.BookFolderCrossRefEntity
import com.huangder.lumibooks.data.local.entity.FolderEntity
import com.huangder.lumibooks.data.local.entity.TagGroupEntity
import com.huangder.lumibooks.domain.model.SyncContentSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksPortableStateSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var config: MutableStateFlow<MyBooksConnection>
    private lateinit var old: MyBooksConnection
    private lateinit var token: String
    private var remote: JSONObject? = null
    private var remoteRevision = 0L
    private val assets = mutableMapOf<String, ByteArray>()
    private var puts = 0
    private var forceConflict = false
    private var onPut: (() -> Unit)? = null
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun empty(device: String = "a") = PortableSyncRules.empty(device)
    private fun preference(value: String, time: Long = 1) = PortablePreference("nickname", "string", value, time, "a")
    private fun envelope(library: String = "library") = JSONObject().put("library_id", library).put("revision", remoteRevision).put("data", remote ?: JSONObject.NULL)
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        @Suppress("UNCHECKED_CAST")
        config = MyBooksConfig::class.java.getDeclaredField("state").apply { isAccessible = true }.get(null) as MutableStateFlow<MyBooksConnection>
        old = config.value
        val tokenField = MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }
        token = tokenField.get(null) as String
        config.value = MyBooksConnection(server.url("/").toString().trimEnd('/'), "test", true)
        tokenField.set(null, "test-session")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer test-session", request.getHeader("Authorization"))
                if (request.path!!.contains("/assets/")) {
                    val name = request.path!!.substringAfterLast('/')
                    if (request.method == "PUT") { assets[name] = request.body.readByteArray(); return MockResponse().setBody("{}") }
                    val bytes = assets[name] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(okio.Buffer().write(bytes)).setHeader("X-Content-SHA256", name.substringBefore('.'))
                }
                if (request.method == "GET") return MockResponse().setBody(envelope().toString())
                puts++
                if (forceConflict) {
                    forceConflict = false
                    remote = JSONObject(empty("b").copy(preferences = listOf(PortablePreference("theme", "string", "dark", 2, "b"))).toJson()).put("bookIdentities", JSONObject())
                    remoteRevision++
                    return MockResponse().setResponseCode(409).setBody("{}")
                }
                val input = JSONObject(request.body.readUtf8())
                assertEquals(remoteRevision, input.getLong("expected_revision"))
                val data = input.getJSONObject("data")
                // Mirror the production v1 validator instead of accepting the backup schema.
                if (data.has("tagGroups")) return MockResponse().setResponseCode(400)
                remote = data; remoteRevision++
                onPut?.invoke()
                return MockResponse().setBody(envelope().toString())
            }
        }
    }
    @After fun cleanup() {
        config.value = old
        MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }.set(null, token)
        server.shutdown()
    }
    private class Device(var state: PortableSnapshot) {
        var revision = 0L
        var applied = 0
        var sources = emptyMap<String, PortableAssetSource>()
        var received = emptyMap<String, ByteArray>()
    }
    private suspend fun sync(device: Device, selection: SyncContentSelection = SyncContentSelection(), direction: MyBooksSyncDirection = MyBooksSyncDirection.BOTH,
        active: Map<String, String> = emptyMap()) {
        MyBooksPortableStateEngine(context, MyBooksApiClient(),
            capture = { PortableSnapshotBundle(device.state, device.sources) },
            apply = { snapshot, files -> device.state = snapshot; device.received = files.mapValues { it.value.readBytes() }; device.applied++ },
            identities = { active }, revision = { device.revision }, rememberRevision = { _, value -> device.revision = value })
            .sync("library", selection, direction)
    }
    @Test fun twoDevicesMergeProfileAndFoldersWithoutReadingInjection() = runBlocking {
        val group = TagGroupEntity("g", "book", "Local group", "local group", 1)
        val folder = FolderEntity("f", "Books", "books", null, 1)
        val book = PortableBook("mybooks-1", "Book", "Author", "EPUB", 0, 0f, null, 1,
            false, true, 1, null, null, null, null, 0, null)
        val link = BookFolderCrossRefEntity(book.id, folder.id, 1)
        val active = mapOf(book.id to "uuid")
        val a = Device(empty().copy(preferences = listOf(preference("Reader")), folders = listOf(folder),
            books = listOf(book), bookFolderLinks = listOf(link), tagGroups = listOf(group)))
        sync(a, active = active)
        assertEquals(listOf(group), a.state.tagGroups)
        val b = Device(empty("b").copy(books = listOf(book)))
        sync(b, active = active)
        assertEquals("Reader", b.state.preferences.single().value)
        assertEquals(folder.id, b.state.folders.single().id)
        assertEquals(listOf(link), b.state.bookFolderLinks)
        assertEquals(1, puts); assertEquals(1L, b.revision)
        assertEquals(0, remote!!.getJSONArray("notes").length())
    }
    @Test fun disabledGroupsPreserveEachSideAndDownloadMakesNoPut() = runBlocking {
        val a = Device(empty().copy(preferences = listOf(preference("Remote")), folders = listOf(FolderEntity("f", "Remote folder", "remote folder", null, 1))))
        sync(a)
        val b = Device(empty("b").copy(preferences = listOf(preference("Local", 5))))
        sync(b, SyncContentSelection(profileAndSettings = false), MyBooksSyncDirection.DOWNLOAD)
        assertEquals("Local", b.state.preferences.single().value)
        assertEquals("f", b.state.folders.single().id)
        assertEquals("Remote", remote!!.getJSONArray("preferences").getJSONObject(0).getString("value"))
        assertEquals(1, puts)
    }
    @Test fun concurrentSnapshotUpdateRefetchesAndMergesBeforeRetry() = runBlocking {
        forceConflict = true
        val a = Device(empty().copy(preferences = listOf(preference("Reader"))))
        sync(a)
        assertEquals(setOf("nickname", "theme"), a.state.preferences.map { it.key }.toSet())
        assertEquals(2, puts); assertEquals(2L, a.revision)
    }
    @Test fun editsMadeWhileUploadingAreKeptLocallyForTheNextSync() = runBlocking {
        val a = Device(empty().copy(preferences = listOf(preference("First"))))
        onPut = { a.state = a.state.copy(preferences = listOf(preference("Edited during request", 9))) }
        sync(a)
        assertEquals("Edited during request", a.state.preferences.single().value)
        assertEquals("First", remote!!.getJSONArray("preferences").getJSONObject(0).getString("value"))
        onPut = null; sync(a)
        assertEquals("Edited during request", remote!!.getJSONArray("preferences").getJSONObject(0).getString("value"))
    }
    @Test fun profileResourceIsVerifiedUploadedAndDownloadedByAnotherDevice() = runBlocking {
        val bytes = "avatar bytes".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val asset = PortableAsset(sha, "avatar", "profile", "$sha.jpg", sha, bytes.size.toLong())
        val a = Device(empty().copy(preferences = listOf(PortablePreference("avatar_path", "string", "asset://$sha", 1, "a")), assets = listOf(asset)))
        a.sources = mapOf(sha to PortableAssetSource(asset) { ByteArrayInputStream(bytes) })
        sync(a)
        val b = Device(empty("b")); sync(b)
        assertArrayEquals(bytes, assets[asset.fileName]); assertArrayEquals(bytes, b.received[sha])
        assertEquals("asset://$sha", b.state.preferences.single().value)
    }
    @Test fun organizationRowsForBooksMissingFromTheLocalCaptureAreNotApplied() = runBlocking {
        val folder = FolderEntity("f", "Books", "books", null, 1)
        val tag = com.huangder.lumibooks.data.local.entity.TagEntity("t", "Tag", "tag", 1)
        val book = PortableBook("mybooks-1", "Remote only", "Author", "EPUB", 0, 0f, null, 1,
            false, true, 1, null, null, null, null, 0, null)
        remote = JSONObject(empty("b").copy(
            books = listOf(book), folders = listOf(folder), tags = listOf(tag),
            bookFolderLinks = listOf(com.huangder.lumibooks.data.local.entity.BookFolderCrossRefEntity("mybooks-1", "f", 1)),
            bookTagLinks = listOf(com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity("mybooks-1", "t", 1))
        ).toJson()).put("bookIdentities", JSONObject().put("mybooks-1", "uuid"))
        remoteRevision = 1L
        var applied: PortableSnapshot? = null
        MyBooksPortableStateEngine(context, MyBooksApiClient(),
            capture = { PortableSnapshotBundle(empty("a"), emptyMap()) },
            apply = { snapshot, _ -> applied = snapshot },
            identities = { mapOf("mybooks-1" to "uuid") }, revision = { 0L }, rememberRevision = { _, _ -> })
            .sync("library", SyncContentSelection(libraryOrganization = true), MyBooksSyncDirection.BOTH)
        val result = requireNotNull(applied)
        assertTrue(result.bookFolderLinks.isEmpty())
        assertTrue(result.bookTagLinks.isEmpty())
    }

    @Test fun rollbackDoesNotApplyOrAcknowledgeRemoteState() = runBlocking {
        val a = Device(empty()); a.revision = 2
        try { sync(a); fail("version rollback must stop sync") } catch (_: IllegalArgumentException) { }
        assertEquals(0, a.applied); assertEquals(2L, a.revision); assertEquals(0, puts)
    }

    @Test fun webTagReplacementIsNotRestoredByAnOlderOrganizationSnapshot() = runBlocking {
        val book = PortableBook("mybooks-1", "Book", "Author", "EPUB", 0, 0f, null, 1,
            false, true, 1, null, null, null, null, 0, null)
        val oldTag = com.huangder.lumibooks.data.local.entity.TagEntity("old", "Old", "old", 1)
        val newTag = com.huangder.lumibooks.data.local.entity.TagEntity("new", "New", "new", 2)
        val oldLink = com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity(book.id, oldTag.id, 1)
        val newLink = com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity(book.id, newTag.id, 2)
        remote = JSONObject(empty("b").copy(books = listOf(book), tags = listOf(oldTag), bookTagLinks = listOf(oldLink)).toJson())
            .put("bookIdentities", JSONObject().put(book.id, "uuid"))
        remoteRevision = 1
        val device = Device(empty().copy(books = listOf(book), tags = listOf(oldTag, newTag), bookTagLinks = listOf(newLink)))
        repeat(2) {
            sync(device, active = mapOf(book.id to "uuid"))
            val names = device.state.bookTagLinks.map { link -> device.state.tags.single { it.id == link.tagId }.name }
            assertEquals(listOf("New"), names)
            assertEquals(listOf("New"), MyBooksMerge.mergeTags(listOf("New"), names, listOf("New")))
        }
        assertEquals(0, remote!!.getJSONArray("bookTagLinks").length())
    }

    @Test fun clearingWebTagsDoesNotResurrectOldLinksEvenWhenOnlyDownloading() = runBlocking {
        val book = PortableBook("mybooks-1", "Book", "Author", "EPUB", 0, 0f, null, 1,
            false, true, 1, null, null, null, null, 0, null)
        val tag = com.huangder.lumibooks.data.local.entity.TagEntity("old", "Old", "old", 1)
        remote = JSONObject(empty("b").copy(books = listOf(book), tags = listOf(tag), bookTagLinks = listOf(
            com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity(book.id, tag.id, 1)
        )).toJson()).put("bookIdentities", JSONObject().put(book.id, "uuid"))
        remoteRevision = 1
        val device = Device(empty().copy(books = listOf(book), tags = listOf(tag)))
        sync(device, direction = MyBooksSyncDirection.DOWNLOAD, active = mapOf(book.id to "uuid"))
        assertTrue(device.state.bookTagLinks.isEmpty())
        assertEquals(0, puts)
    }
}
