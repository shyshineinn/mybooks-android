package com.huangder.lumibooks.data.sync

import android.content.Context
import com.huangder.lumibooks.data.backup.*
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import com.huangder.lumibooks.domain.model.SyncContentSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MyBooksPortableStateSync @Inject constructor(
    @ApplicationContext context: Context,
    database: AppDatabase,
    snapshots: PortableSnapshotManager
) {
    private val engine = MyBooksPortableStateEngine(context, MyBooksApiClient(),
        capture = { snapshots.capture(includeBookFiles = false) },
        apply = { snapshot, files -> snapshots.apply(snapshot, files, replace = false, replaceAssetDirectories = false,
            preserveLocalBookIdentity = true, preserveNewerRows = true, preserveBookTags = true) },
        identities = { database.myBooksSyncStateDao().getAll().filterNot { it.remoteDeleted }.mapNotNull { state ->
            runCatching { state.bookId to JSONObject(state.baseJson).getString("uuid") }.getOrNull()
        }.toMap() },
        revision = { library -> database.syncStateDao().getValue("mybooks_portable_revision:" + library)?.toLong() ?: 0L },
        rememberRevision = { library, revision -> database.syncStateDao().putState(SyncStateEntity("mybooks_portable_revision:" + library, revision.toString())) }
    )
    suspend fun sync(library: String, content: SyncContentSelection, direction: MyBooksSyncDirection) = engine.sync(library, content, direction)
}

internal class MyBooksPortableStateEngine(
    private val context: Context,
    private val api: MyBooksApiClient,
    private val capture: suspend () -> PortableSnapshotBundle,
    private val apply: suspend (PortableSnapshot, Map<String, File>) -> Unit,
    private val identities: suspend () -> Map<String, String>,
    private val revision: suspend (String) -> Long,
    private val rememberRevision: suspend (String, Long) -> Unit
) {
    suspend fun sync(library: String, selection: SyncContentSelection, direction: MyBooksSyncDirection) {
        if (!selection.profileAndSettings && !selection.libraryOrganization && !selection.readingData) return
        val active = identities()
        val localBundle = forServer(capture(), active, selection)
        val stateSelection = selection.copy(readingData = false)
        repeat(3) { attempt ->
            val envelope = api.fetchPortableState()
            require(envelope.getString("library_id") == library) { "书库身份已变化" }
            val remoteRevision = envelope.getLong("revision")
            require(remoteRevision >= revision(library)) { "服务器同步状态版本倒退" }
            val remote = readState(envelope, active, localBundle.snapshot.sourceDeviceId)
            val merged = PortableSnapshotMerger.merge(localBundle.snapshot, remote)
            var outgoing = PortableSyncRules.forRemote(localBundle.snapshot, remote, merged, stateSelection)
            outgoing = wire(outgoing.copy(annotationTags = if (selection.readingData) merged.annotationTags else remote.annotationTags))
            if (direction == MyBooksSyncDirection.DOWNLOAD) outgoing = remote
            val directory = File(context.cacheDir, "mybooks-state-" + UUID.randomUUID()).apply { mkdirs() }
            try {
                val files = mutableMapOf<String, File>()
                val existingFiles = remote.assets.mapTo(mutableSetOf()) { it.fileName }
                for (asset in outgoing.assets) {
                    currentCoroutineContext().ensureActive()
                    val source = localBundle.assetSources[asset.id]
                    if (source != null) {
                        val file = materialize(source, directory); files[asset.id] = file
                        if (direction != MyBooksSyncDirection.DOWNLOAD && asset.fileName !in existingFiles) api.uploadPortableAsset(file, asset.fileName)
                    }
                }
                var confirmed = envelope
                if (direction != MyBooksSyncDirection.DOWNLOAD && PortableSyncRules.fingerprint(outgoing) != PortableSyncRules.fingerprint(remote)) {
                    val json = JSONObject(outgoing.toJson()).put("bookIdentities", JSONObject(outgoing.books.associate { it.id to requireNotNull(active[it.id]) }))
                    try { confirmed = api.putPortableState(library, remoteRevision, json) }
                    catch (error: MyBooksHttpException) {
                        if (error.status == 409 && attempt < 2) return@repeat
                        throw error
                    }
                }
                currentCoroutineContext().ensureActive()
                require(confirmed.getString("library_id") == library && confirmed.getLong("revision") >= remoteRevision)
                val accepted = readState(confirmed, active, localBundle.snapshot.sourceDeviceId)
                val latestBundle = forServer(capture(), active, selection)
                val latest = latestBundle.snapshot
                val combined = PortableSnapshotMerger.merge(latest, accepted)
                var incoming = PortableSyncRules.forLocal(latest, combined, stateSelection)
                incoming = incoming.copy(annotationTags = if (selection.readingData) combined.annotationTags else latest.annotationTags)
                val currentBooks = latest.books.associateBy { it.id }
                incoming = incoming.copy(books = incoming.books.mapNotNull { item -> currentBooks[item.id]?.let { current ->
                    item.copy(title = current.title, author = current.author, format = current.format,
                        lastReadTime = current.lastReadTime, readingProgress = current.readingProgress, locatorJson = current.locatorJson,
                        metadataUpdatedAt = maxOf(item.metadataUpdatedAt, current.metadataUpdatedAt),
                        remoteLibraryKey = current.remoteLibraryKey, remoteFileName = current.remoteFileName,
                        remoteFileSize = current.remoteFileSize, remoteFileSha256 = current.remoteFileSha256,
                        isCloudOnly = current.isCloudOnly)
                } })
                // Books absent from the latest local capture must not leave book-scoped rows
                // behind; inserting one would violate the cross-reference foreign keys.
                val presentBookIds = currentBooks.keys
                incoming = incoming.copy(
                    bookFolderLinks = incoming.bookFolderLinks.filter { it.bookId in presentBookIds },
                    bookTagLinks = incoming.bookTagLinks.filter { it.bookId in presentBookIds },
                    readingRecords = incoming.readingRecords.filter { it.bookId in presentBookIds },
                    bookmarks = incoming.bookmarks.filter { it.bookId in presentBookIds },
                    notes = incoming.notes.filter { it.bookId in presentBookIds }
                )
                for (asset in incoming.assets.filter { PortableSyncRules.referenced(incoming, it.id) }) {
                    if (asset.id in files) continue
                    val source = latestBundle.assetSources[asset.id]
                    val file = if (source != null) materialize(source, directory) else File(directory, asset.fileName).also { target ->
                        val receipt = api.downloadPortableAsset(asset.fileName, target, asset.sizeBytes)
                        require(receipt.sha256 == asset.sha256 && receipt.size == asset.sizeBytes) { "同步资源校验失败" }
                    }
                    files[asset.id] = file
                }
                if (PortableSyncRules.fingerprint(incoming) != PortableSyncRules.fingerprint(latest)) apply(incoming, files)
                rememberRevision(library, confirmed.getLong("revision"))
                return
            } finally { directory.deleteRecursively() }
        }
        error("同步状态被其他设备连续更新，请重试")
    }
    private fun forServer(bundle: PortableSnapshotBundle, active: Map<String, String>, selection: SyncContentSelection): PortableSnapshotBundle {
        val selected = PortableSyncRules.filterBundle(bundle, selection)
        val ids = selected.snapshot.books.filter { it.id in active }.mapTo(mutableSetOf()) { it.id }
        val snapshot = selected.snapshot.copy(books = selected.snapshot.books.filter { it.id in ids },
            bookFolderLinks = selected.snapshot.bookFolderLinks.filter { it.bookId in ids },
            bookTagLinks = selected.snapshot.bookTagLinks.filter { it.bookId in ids },
            readingRecords = emptyList(), bookmarks = emptyList(), notes = emptyList(),
            tombstones = selected.snapshot.tombstones.filter { it.namespace in setOf("folder", "tag", "book_folder", "book_tag") },
            highlightRuleExclusions = emptyList(),
            assets = selected.snapshot.assets.filter { it.kind != "book_cover" || it.ownerId in ids })
        return selected.copy(snapshot = snapshot, assetSources = selected.assetSources.filterKeys { id -> snapshot.assets.any { it.id == id } })
    }
    private fun readState(envelope: JSONObject, active: Map<String, String>, device: String): PortableSnapshot {
        val raw = envelope.optJSONObject("data") ?: return PortableSyncRules.empty(device)
        val identities = raw.getJSONObject("bookIdentities")
        val state = PortableSyncRules.stripRuleLibrary(PortableSnapshot.fromJson(raw.toString()))
        val ids = state.books.filter { active[it.id] != null && identities.optString(it.id) == active[it.id] }.mapTo(mutableSetOf()) { it.id }
        val snapshot = state.copy(books = state.books.filter { it.id in ids }, bookFolderLinks = state.bookFolderLinks.filter { it.bookId in ids },
            // 服务器书籍的标签关联只由书目三方合并维护；旧快照不能重新添加或删除关联。
            bookTagLinks = emptyList(), readingRecords = emptyList(), bookmarks = emptyList(), notes = emptyList(),
            highlightRuleExclusions = emptyList(), tombstones = state.tombstones.filter { it.namespace in setOf("folder", "tag", "book_folder") },
            assets = state.assets.filter { it.kind != "book_cover" || it.ownerId in ids })
        return snapshot.copy(assets = snapshot.assets.filter { PortableSyncRules.referenced(snapshot, it.id) })
    }
    private fun wire(snapshot: PortableSnapshot) = snapshot.copy(books = snapshot.books.map { it.copy(
        lastReadTime = 0, readingProgress = 0f, locatorJson = null, bodyAssetId = null,
        remoteLibraryKey = null, remoteFileName = null, remoteFileSize = 0, remoteFileSha256 = null
    ) }, bookTagLinks = emptyList(), tombstones = snapshot.tombstones.filterNot { it.namespace == "book_tag" },
        // The MyBooks server v1 snapshot schema rejects unknown top-level fields.
        tagGroups = emptyList(), hasTagGroups = false,
        readingRecords = emptyList(), bookmarks = emptyList(), notes = emptyList())
    private fun materialize(source: PortableAssetSource, directory: File): File {
        val file = File(directory, source.asset.fileName)
        source.openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        require(file.length() == source.asset.sizeBytes && digest.digest().joinToString("") { "%02x".format(it) } == source.asset.sha256) { "本机同步资源已变化" }
        return file
    }
}
