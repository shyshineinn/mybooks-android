package com.huangder.lumibooks.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.domain.model.ReaderBackgroundPreset
import com.huangder.lumibooks.domain.model.ReaderBackgroundPresetCodec
import com.huangder.lumibooks.domain.model.ReaderBackgroundType
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.BookEntity
import com.huangder.lumibooks.data.local.entity.AnnotationTagEntity
import com.huangder.lumibooks.domain.model.AnnotationTags
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.edition.EditionActivityObserver
import com.huangder.lumibooks.mineru.MineruTokenStore
import com.huangder.lumibooks.tts.ExternalTtsTokenStore
import com.huangder.lumibooks.util.BookFileAccess
import com.huangder.lumibooks.util.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PortableAssetSource(
    val asset: PortableAsset,
    val openStream: () -> InputStream
)

data class PortableSnapshotBundle(
    val snapshot: PortableSnapshot,
    val assetSources: Map<String, PortableAssetSource>
)

@Singleton
class PortableSnapshotManager internal constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val dataStoreManager: DataStoreManager,
    private val syncIdentityStore: SyncIdentityStore,
    private val mineruTokenStore: MineruTokenStore,
    private val externalTtsTokenStore: ExternalTtsTokenStore,
    private val translationTokenStore: com.huangder.lumibooks.translation.TranslationTokenStore,
    private val beforeApply: suspend () -> Unit = {}
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        database: AppDatabase,
        dataStoreManager: DataStoreManager,
        syncIdentityStore: SyncIdentityStore,
        mineruTokenStore: MineruTokenStore,
        externalTtsTokenStore: ExternalTtsTokenStore,
        translationTokenStore: com.huangder.lumibooks.translation.TranslationTokenStore,
        activityObserver: EditionActivityObserver
    ) : this(context, database, dataStoreManager, syncIdentityStore, mineruTokenStore,
        externalTtsTokenStore, translationTokenStore, beforeApply = activityObserver::prepare)

    suspend fun capture(includeBookFiles: Boolean = true): PortableSnapshotBundle = withContext(Dispatchers.IO) {
        val deviceId = syncIdentityStore.deviceId()
        val bookDao = database.bookDao()
        val syncDao = database.syncStateDao()
        val books = bookDao.getAllBooksSnapshot()
        val folders = syncDao.getAllFolders()
        val sources = linkedMapOf<String, PortableAssetSource>()
        val pathAssets = mutableMapOf<String, String>()

        fun addSource(kind: String, ownerId: String, displayName: String, dedupeByContent: Boolean = false, opener: () -> InputStream): String? {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val valid = runCatching {
                opener().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        size += count
                    }
                }
            }.isSuccess
            if (!valid || size <= 0L) return null
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val extension = displayName.substringAfterLast('.', "bin")
                .lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "bin"
            // 背景图按内容去重：同一张图不同路径只上传一份。
            val logicalId = if (dedupeByContent) sha else "$sha-${stableSuffix("$kind\u0000$ownerId")}"
            val asset = PortableAsset(logicalId, kind, ownerId, "$sha.$extension", sha, size)
            sources.putIfAbsent(logicalId, PortableAssetSource(asset, opener))
            return logicalId
        }

        fun addFile(kind: String, ownerId: String, file: File, dedupeByContent: Boolean = false): String? {
            if (!file.isFile || file.length() <= 0L) return null
            val id = addSource(kind, ownerId, file.name, dedupeByContent) { file.inputStream().buffered() }
            if (id != null) pathAssets[file.absolutePath] = id
            return id
        }

        val portableBooks = books.map { book ->
            val bodyId = if (includeBookFiles && !book.isCloudOnly && book.filePath.isNotBlank()) {
                addSource(
                    "book",
                    book.id,
                    if (book.format == "MARKDOWN") "${book.id}.mdpack" else BookFileAccess.displayName(context, book.filePath) ?: "${book.id}.${book.format.lowercase()}"
                ) { if (book.format == "MARKDOWN") com.huangder.lumibooks.util.markdown.MarkdownSnapshotStore.packageFile(File(book.filePath)).inputStream().buffered()
                    else BookFileAccess.openInputStream(context, book.filePath) }
            } else null
            val coverId = book.coverPath?.let(::File)?.let { addFile("book_cover", book.id, it) }
            book.toPortable(bodyId, coverId)
        }

        val portableFolders = folders.map { folder ->
            val assetId = folder.coverPath?.let(::File)?.let { addFile("folder_cover", folder.id, it) }
            folder.copy(coverPath = assetId?.let { "asset://$it" })
        }

        val avatar = File(context.filesDir, "avatars/avatar.jpg")
        addFile("avatar", "profile", avatar)
        val fontRoot = File(context.filesDir, "fonts")
        fontRoot.walkTopDown()
            .filter { it.isFile }
            .forEach { file -> addFile("font", file.relativeTo(fontRoot).path, file) }

        // 自定义阅读背景是唯一随快照同步的本地文件；按内容去重并限制体积与数量。
        val backgroundRoot = File(context.filesDir, "reader_backgrounds")
        val backgroundIds = linkedSetOf<String>()
        backgroundRoot.walkTopDown()
            .filter { it.isFile && !it.name.contains(".blurred-") }
            .sortedBy { it.relativeTo(backgroundRoot).path }
            .forEach { file ->
                if (file.length() > MAX_READER_BACKGROUND_BYTES) return@forEach
                val id = addFile(READER_BACKGROUND_KIND, file.relativeTo(backgroundRoot).path, file, dedupeByContent = true)
                if (id != null) backgroundIds += id
            }
        backgroundIds.drop(MAX_READER_BACKGROUND_ASSETS).forEach { id ->
            sources.remove(id)
            pathAssets.entries.removeAll { it.value == id }
        }
        val includedPaths = pathAssets.keys.toSet()

        val preferences = rewriteReaderBackgroundPreferences(
            dataStoreManager.exportPortablePreferences(deviceId),
            keep = { preset ->
                preset.type != ReaderBackgroundType.IMAGE || File(preset.value).absolutePath in includedPaths
            },
            transform = ::stripDerivedBackgroundPaths
        ).map { preference ->
            if (preference.type != PortablePreference.TYPE_STRING) return@map preference
            preference.copy(
                value = replacePathsInPortableString(
                    preference.value,
                    pathAssets.mapValues { "asset://${it.value}" }
                )
            )
        }

        PortableSnapshotBundle(
            snapshot = PortableSnapshot(
                createdAt = System.currentTimeMillis(),
                sourceDeviceId = deviceId,
                preferences = preferences,
                books = portableBooks,
                folders = portableFolders,
                bookFolderLinks = syncDao.getAllBookFolderLinks(),
                tags = syncDao.getAllTags(),
                tagGroups = database.tagGroupDao().getAllGroups(),
                annotationTags = database.annotationTagDao().getAllTags(),
                bookTagLinks = syncDao.getAllBookTagLinks(),
                readingRecords = syncDao.getAllReadingRecords(),
                bookmarks = syncDao.getAllBookmarks(),
                notes = syncDao.getAllNotes(),
                tombstones = syncDao.getAllTombstones(),
                assets = sources.values.map { it.asset },
                highlightRules = database.highlightRuleDao().getRules(),
                bookHighlightRuleStates = database.highlightRuleDao().getAllBookStates(),
                bookHighlightSettings = database.highlightRuleDao().getAllSettings(),
                highlightRuleExclusions = database.highlightRuleDao().getAllExclusions()
            ),
            assetSources = sources
        )
    }

    suspend fun apply(
        incomingSnapshot: PortableSnapshot,
        assetFiles: Map<String, File>,
        replace: Boolean,
        replaceAssetDirectories: Boolean = replace,
        preserveLocalBookIdentity: Boolean = false,
        preserveNewerRows: Boolean = false,
        // My Books 的标签关联来自书目同步，组织快照只补充目录与层级信息。
        preserveBookTags: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val snapshot = incomingSnapshot.flattenLegacyTagHierarchy()
        val deviceId = syncIdentityStore.deviceId()
        validateSnapshot(snapshot, assetFiles)
        // Freeze this installation's pre-existing history before restore/sync can replace it.
        beforeApply()
        val oldPreferences = dataStoreManager.exportPortablePreferences(deviceId)
        val preparedAssets = prepareAssets(snapshot, assetFiles, replaceAssetDirectories)
        val assetPaths = preparedAssets.paths
        val preferences = rewriteReaderBackgroundPreferences(
            snapshot.preferences,
            keep = { preset ->
                if (preset.type != ReaderBackgroundType.IMAGE) true
                else if (preset.value.startsWith("asset://")) {
                    preset.value.removePrefix("asset://") in assetPaths
                } else File(preset.value).isFile
            },
            transform = ::stripDerivedBackgroundPaths
        ).map { preference ->
            if (preference.type != PortablePreference.TYPE_STRING) return@map preference
            preference.copy(
                value = replacePathsInPortableString(
                    preference.value,
                    assetPaths.mapKeys { "asset://${it.key}" }
                )
            )
        }
        try {
            preparedAssets.commit()
            if (replace) dataStoreManager.replacePortablePreferences(preferences, deviceId)
            else dataStoreManager.applyPortablePreferences(preferences)
            if (!mineruTokenStore.hasToken()) dataStoreManager.saveMineruMode("disabled")
            if (!externalTtsTokenStore.hasToken()) dataStoreManager.disableExternalTts()
            val translationSettings = dataStoreManager.translationSettings.first()
            if (!translationTokenStore.hasToken(translationSettings.credentialScope)) {
                dataStoreManager.saveTranslationSettings(translationSettings.copy(enabled = false))
            }

            database.withTransaction {
                val bookDao = database.bookDao()
                val syncDao = database.syncStateDao()
                syncDao.deleteDanglingBookFolderLinks()
                syncDao.deleteDanglingBookTagLinks()
                syncDao.detachDanglingFolderParents()
                val existingBooks = bookDao.getAllBooksSnapshot().associateBy { it.id }
                val existingFolders = syncDao.getAllFolders()
                val existingTags = syncDao.getAllTags().associateBy { it.id }
                val existingFolderLinks = syncDao.getAllBookFolderLinks().associateBy { it.bookId }
                val existingTagLinks = syncDao.getAllBookTagLinks().associateBy { it.bookId + ":" + it.tagId }
                val protectedTagIds = if (preserveBookTags && !replace) existingTagLinks.values.mapTo(mutableSetOf()) { it.tagId } else emptySet()
                val protectedTagNames = protectedTagIds.mapNotNull { existingTags[it]?.normalizedName }.toSet()
                val existingRecords = syncDao.getAllReadingRecords().associateBy {
                    "${it.bookId}\u0000${it.date}\u0000${it.sourceDeviceId}"
                }
                val existingBookmarks = syncDao.getAllBookmarks().associateBy { it.syncId }
                val existingNotes = syncDao.getAllNotes().associateBy { it.syncId }
                val restoredBooks = snapshot.books.map { book ->
                    val existing = existingBooks[book.id]
                    book.toEntity(
                        filePath = book.bodyAssetId?.let(assetPaths::get)?.let { path ->
                            if (book.format == "MARKDOWN") com.huangder.lumibooks.util.markdown.MarkdownSnapshotStore.unpack(context, book.id, File(path)).absolutePath else path
                        } ?: existing?.filePath.orEmpty().takeIf { !replaceAssetDirectories }.orEmpty(),
                        coverPath = book.coverAssetId?.let(assetPaths::get)
                            ?: existing?.coverPath?.takeIf { !replaceAssetDirectories },
                        // 状态同步不携带书本体（replaceAssetDirectories=false）时，与 filePath/
                        // coverPath 同理，保留本机真实的“已下载/仅云端”状态，避免 bodyless
                        // 快照用推导结果把已下载书籍翻回仅云端。
                        existingIsCloudOnly = existing?.isCloudOnly
                            ?.takeIf { !replaceAssetDirectories && book.bodyAssetId == null },
                        existingIsArchived = existing?.isArchived ?: false
                    ).let { restored ->
                        val incomingArchiveTime = book.archiveUpdatedAt ?: book.metadataUpdatedAt
                        val retainArchive = existing != null && (book.isArchived == null ||
                            (!replace && (existing.archiveUpdatedAt > incomingArchiveTime ||
                                (existing.archiveUpdatedAt == incomingArchiveTime && existing.isArchived))))
                        val withArchive = if (retainArchive && existing != null) {
                            restored.copy(isArchived = existing.isArchived, archiveUpdatedAt = existing.archiveUpdatedAt)
                        } else restored
                        val withSource = if ((preserveLocalBookIdentity || book.format == "MARKDOWN") && !replaceAssetDirectories && existing != null) {
                            withArchive.copy(sourceUri = existing.sourceUri, sourceDocumentKey = existing.sourceDocumentKey,
                                sourceParentUri = existing.sourceParentUri, sourceSha256 = existing.sourceSha256,
                                sourceDisplayName = existing.sourceDisplayName, sourceLastModified = existing.sourceLastModified,
                                isMissing = existing.isMissing,
                                lastReadTime = if (preserveLocalBookIdentity) existing.lastReadTime else restored.lastReadTime,
                                readingProgress = if (preserveLocalBookIdentity) existing.readingProgress else restored.readingProgress,
                                locatorJson = if (preserveLocalBookIdentity) existing.locatorJson else restored.locatorJson)
                        } else withArchive
                        if (preserveNewerRows && !replace && existing != null && existing.metadataUpdatedAt > book.metadataUpdatedAt) {
                            withSource.copy(title = existing.title, author = existing.author, isFavorite = existing.isFavorite,
                                isPinned = existing.isPinned, coverPath = existing.coverPath, metadataUpdatedAt = existing.metadataUpdatedAt)
                        } else withSource
                    }
                }
                if (replace) {
                    syncDao.clearBookFolderLinks()
                    syncDao.clearBookTagLinks()
                    syncDao.clearBookmarks()
                    syncDao.clearNotes()
                    syncDao.clearReadingRecords()
                    syncDao.clearFolders()
                    syncDao.clearTags()
                    database.annotationTagDao().clearTags()
                    database.tagGroupDao().deleteAll()
                    bookDao.clearBooks()
                    syncDao.clearTombstones()
                    database.highlightRuleDao().clearBookStates()
                    database.highlightRuleDao().clearSettings()
                    database.highlightRuleDao().clearAllExclusions()
                    database.highlightRuleDao().clearRules()
                }
                if (restoredBooks.isNotEmpty()) bookDao.upsertBooks(restoredBooks)
                // A server snapshot can outlive a local edit that removed a book, folder or tag.
                // Never write a cross reference whose parent is absent: SQLite would abort the
                // whole transaction with FOREIGN KEY constraint failed (787).
                val availableBookIds = if (replace) restoredBooks.mapTo(mutableSetOf()) { it.id }
                    else existingBooks.keys + restoredBooks.map { it.id }
                val availableFolderIds = if (replace) snapshot.folders.mapTo(mutableSetOf()) { it.id }
                    else existingFolders.mapTo(mutableSetOf()) { it.id } + snapshot.folders.map { it.id }
                val tagMerge = mergePortableTags(
                    (if (replace) emptyList() else existingTags.values.toList()) + snapshot.tags.map { incoming ->
                        val current = existingTags[incoming.id]
                        val selected = current?.takeIf { preserveNewerRows && !replace && it.updatedAt > incoming.updatedAt } ?: incoming
                        if (current != null && current.id in protectedTagIds) selected.copy(name = current.name, normalizedName = current.normalizedName)
                        else selected
                    },
                    (if (replace) emptyList() else existingTagLinks.values.toList()) +
                        (if (preserveBookTags && !replace) emptyList() else snapshot.bookTagLinks)
                )
                val protectedCanonicalTagIds = tagMerge.tags.filter { it.normalizedName in protectedTagNames }.mapTo(mutableSetOf()) { it.id }
                val effectiveTombstones = snapshot.tombstones.filterNot { tombstone -> preserveBookTags && !replace &&
                    (tombstone.namespace == "book_tag" || tombstone.namespace == "tag" &&
                        (tombstone.itemId in protectedTagIds || tombstone.itemId in protectedCanonicalTagIds)) }
                val availableTagIds = tagMerge.tags.mapTo(mutableSetOf()) { it.id }
                val folderLinks = snapshot.bookFolderLinks.filter {
                    it.bookId in availableBookIds && it.folderId in availableFolderIds
                }
                val tagLinks = tagMerge.links.filter {
                    it.bookId in availableBookIds && it.tagId in availableTagIds
                }
                val readingRecords = snapshot.readingRecords.filter { it.bookId in availableBookIds }
                val bookmarks = snapshot.bookmarks.filter { it.bookId in availableBookIds }
                val notes = snapshot.notes.filter { it.bookId in availableBookIds }
                val knownFolderIds = if (replace) emptySet() else existingFolders.mapTo(mutableSetOf()) { it.id }
                val existingFoldersById = existingFolders.associateBy { it.id }
                val restoredFolders = sortFoldersParentFirst(snapshot.folders, knownFolderIds).map { folder ->
                    val restoredCover = folder.coverPath
                        ?.takeIf { it.startsWith("asset://") }
                        ?.removePrefix("asset://")
                        ?.let(assetPaths::get)
                    val existing = existingFoldersById[folder.id]
                    if (preserveNewerRows && !replace && existing != null && existing.updatedAt > folder.updatedAt) existing else folder.copy(
                        coverPath = restoredCover
                            ?: existingFoldersById[folder.id]?.coverPath?.takeIf { !replaceAssetDirectories }
                    )
                }
                if (restoredFolders.isNotEmpty()) syncDao.upsertFolders(restoredFolders)
                // Recheck group deletions and assignments while holding the database write lock.
                val tagCatalog = PortableSnapshotMerger.merge(
                    snapshot.copy(
                        tags = if (replace) emptyList() else syncDao.getAllTags(),
                        annotationTags = if (replace) emptyList() else database.annotationTagDao().getAllTags(),
                        tagGroups = if (replace) emptyList() else database.tagGroupDao().getAllGroups(),
                        hasTagGroups = true,
                        bookTagsWithoutGroupId = emptySet(),
                        annotationTagsWithoutGroupId = emptySet()
                    ), snapshot.copy(tombstones = effectiveTombstones)
                )
                database.tagGroupDao().upsertGroups(tagCatalog.tagGroups)
                val groupAssignments = tagCatalog.tags.associateBy { it.id }
                // Rebuild the catalog and its remapped links together to avoid a unique-name collision.
                syncDao.clearBookTagLinks()
                syncDao.clearTags()
                if (tagMerge.tags.isNotEmpty()) syncDao.upsertTags(tagMerge.tags.map { tag ->
                    tag.copy(groupId = groupAssignments[tag.id]?.groupId)
                })
                if (folderLinks.isNotEmpty()) syncDao.upsertBookFolderLinks(folderLinks.map { incoming ->
                    existingFolderLinks[incoming.bookId]?.takeIf { preserveNewerRows && !replace && it.updatedAt > incoming.updatedAt }
                        ?: incoming
                })
                if (tagLinks.isNotEmpty()) syncDao.upsertBookTagLinks(tagLinks)
                if (readingRecords.isNotEmpty()) syncDao.upsertReadingRecords(readingRecords.map {
                    val key = "${it.bookId}\u0000${it.date}\u0000${it.sourceDeviceId}"
                    val existing = existingRecords[key]?.takeUnless { replace }
                    if (existing == null) it.copy(id = 0)
                    else it.copy(
                        id = existing.id,
                        duration = maxOf(existing.duration, it.duration),
                        listeningDuration = maxOf(existing.listeningDuration, it.listeningDuration),
                        startTime = minOf(existing.startTime, it.startTime),
                        endTime = maxOf(existing.endTime, it.endTime),
                        updatedAt = maxOf(existing.updatedAt, it.updatedAt)
                    )
                })
                if (bookmarks.isNotEmpty()) syncDao.upsertBookmarks(bookmarks.map {
                    val existing = existingBookmarks[it.syncId]
                    // A sync payload may have been captured before the user edited tags
                    // while network requests were in flight. Recheck under the write lock.
                    if (!replace && existing != null && existing.updatedAt > it.updatedAt) existing
                    else it.copy(id = if (replace) 0 else existing?.id ?: 0)
                })
                if (notes.isNotEmpty()) syncDao.upsertNotes(notes.map {
                    val existing = existingNotes[it.syncId]
                    if (!replace && existing != null && existing.updatedAt > it.updatedAt) existing
                    else it.copy(id = if (replace) 0 else existing?.id ?: 0)
                })
                val tagDao = database.annotationTagDao()
                // Backfill labels from older backups that have no independent catalog.
                tagDao.ensureTags((snapshot.notes.flatMap { note ->
                    AnnotationTags.decode(note.tagsJson).map { AnnotationTagEntity(it, note.updatedAt) }
                } + snapshot.bookmarks.flatMap { bookmark ->
                    AnnotationTags.decode(bookmark.tagsJson).map { AnnotationTagEntity(it, bookmark.updatedAt) }
                }).distinctBy { it.name })
                tagDao.upsertTags(tagCatalog.annotationTags)
                if (effectiveTombstones.isNotEmpty()) syncDao.upsertTombstones(effectiveTombstones)
                if (snapshot.highlightRules.isNotEmpty()) {
                    database.highlightRuleDao().upsertRules(snapshot.highlightRules)
                }
                if (snapshot.bookHighlightRuleStates.isNotEmpty()) {
                    database.highlightRuleDao().upsertBookStates(snapshot.bookHighlightRuleStates)
                }
                if (snapshot.bookHighlightSettings.isNotEmpty()) {
                    database.highlightRuleDao().upsertAllSettings(snapshot.bookHighlightSettings)
                }
                if (snapshot.highlightRuleExclusions.isNotEmpty()) {
                    database.highlightRuleDao().upsertExclusions(snapshot.highlightRuleExclusions)
                }
                val tombstoneSnapshot = snapshot.copy(tombstones = effectiveTombstones)
                applyTombstones(if (preserveNewerRows && !replace) tombstoneSnapshot.copy(
                    books = emptyList(), folders = syncDao.getAllFolders(), tags = syncDao.getAllTags(),
                    bookFolderLinks = syncDao.getAllBookFolderLinks(), bookTagLinks = syncDao.getAllBookTagLinks(),
                    bookmarks = syncDao.getAllBookmarks(), notes = syncDao.getAllNotes()
                ) else tombstoneSnapshot)
            }
            preparedAssets.finish()
        } catch (error: Throwable) {
            runCatching { dataStoreManager.replacePortablePreferences(oldPreferences, deviceId) }
            preparedAssets.rollback()
            throw error
        } finally {
            preparedAssets.cleanup()
        }
    }

    private suspend fun applyTombstones(snapshot: PortableSnapshot) {
        val dao = database.syncStateDao()
        val grouped = snapshot.tombstones.groupBy { it.namespace }
        val bookmarkTimes = snapshot.bookmarks.associate { it.syncId to it.updatedAt }
        val noteTimes = snapshot.notes.associate { it.syncId to it.updatedAt }
        val folderTimes = snapshot.folders.associate { it.id to it.updatedAt }
        val tagTimes = snapshot.tags.associate { it.id to it.updatedAt }
        val folderLinkTimes = snapshot.bookFolderLinks.associate { it.bookId to it.updatedAt }
        val tagLinkTimes = snapshot.bookTagLinks.associate { "${it.bookId}:${it.tagId}" to it.updatedAt }

        grouped["bookmark"]?.effectiveDeletes(bookmarkTimes)?.takeIf { it.isNotEmpty() }
            ?.let { dao.deleteBookmarksBySyncIds(it) }
        grouped["note"]?.effectiveDeletes(noteTimes)?.takeIf { it.isNotEmpty() }
            ?.let { dao.deleteNotesBySyncIds(it) }
        grouped["book_folder"]?.effectiveDeletes(folderLinkTimes)?.takeIf { it.isNotEmpty() }
            ?.let { dao.deleteBookFolderLinksByBookIds(it) }
        grouped["book_tag"]?.effectiveDeletes(tagLinkTimes)?.takeIf { it.isNotEmpty() }
            ?.let { dao.deleteBookTagLinksByIds(it) }
        grouped["folder"]?.effectiveDeletes(folderTimes)?.takeIf { it.isNotEmpty() }
            ?.let { dao.deleteFoldersByIds(it) }
        grouped["tag"]?.effectiveDeletes(tagTimes)?.takeIf { it.isNotEmpty() }
            ?.let { dao.deleteTagsByIds(it) }
    }

    private fun List<com.huangder.lumibooks.data.local.entity.SyncTombstoneEntity>.effectiveDeletes(
        liveUpdatedAt: Map<String, Long>
    ): List<String> = mapNotNull { tombstone ->
        tombstone.itemId.takeIf { tombstone.deletedAt >= (liveUpdatedAt[it] ?: Long.MIN_VALUE) }
    }

    private fun prepareAssets(
        snapshot: PortableSnapshot,
        files: Map<String, File>,
        replaceDirectories: Boolean
    ): PreparedAssetInstall {
        val token = UUID.randomUUID().toString()
        val roots = assetRoots()
        val stagingRoots = roots.mapValues { (_, target) -> File(target.parentFile, ".${target.name}.restore-$token") }
        val required = referencedAssetIds(snapshot)
        val strictRequired = required.filterTo(mutableSetOf()) { id ->
            snapshot.assets.firstOrNull { it.id == id }?.kind != READER_BACKGROUND_KIND
        }
        val paths = mutableMapOf<String, String>()
        val stagedFiles = linkedMapOf<File, File>()
        try {
            for (asset in snapshot.assets) {
                val source = files[asset.id] ?: continue
                val root = roots[asset.kind] ?: continue
                val stagingRoot = stagingRoots.getValue(asset.kind)
                val target = assetTarget(root, asset)
                val staged = assetTarget(stagingRoot, asset)
                val bestEffort = asset.kind == READER_BACKGROUND_KIND
                val installed = runCatching {
                    staged.parentFile?.mkdirs()
                    source.copyTo(staged, overwrite = true)
                    require(staged.length() == asset.sizeBytes && staged.sha256Portable() == asset.sha256) {
                        "Backup asset is damaged: ${asset.fileName}"
                    }
                }
                if (installed.isFailure) {
                    staged.delete()
                    if (bestEffort) continue
                    throw installed.exceptionOrNull()!!
                }
                paths[asset.id] = target.absolutePath
                stagedFiles[target] = staged
            }
            require(strictRequired.all { it in paths }) { "Backup is missing a referenced asset" }
        } catch (error: Throwable) {
            stagingRoots.values.distinct().forEach { it.deleteRecursively() }
            throw error
        }
        val directoryPlans = roots.entries
            .associate { (_, target) -> target.canonicalFile to File(target.parentFile, ".${target.name}.restore-$token") }
            .map { (target, staging) -> DirectoryPlan(target, staging, File(target.parentFile, ".${target.name}.previous-$token")) }
        return PreparedAssetInstall(directoryPlans, stagedFiles, paths, replaceDirectories, token)
    }

    private fun validateSnapshot(snapshot: PortableSnapshot, files: Map<String, File>) {
        require(snapshot.schemaVersion == PortableSnapshot.CURRENT_VERSION) { "Unsupported snapshot version" }
        require(snapshot.assets.map { it.id }.distinct().size == snapshot.assets.size) { "Duplicate asset IDs" }
        require(snapshot.books.map { it.id }.distinct().size == snapshot.books.size) { "Duplicate book IDs" }
        require(snapshot.folders.map { it.id }.distinct().size == snapshot.folders.size) { "Duplicate folder IDs" }
        require(snapshot.tags.map { it.id }.distinct().size == snapshot.tags.size) { "Duplicate tag IDs" }
        require(snapshot.annotationTags.map { it.name }.distinct().size == snapshot.annotationTags.size) { "Duplicate annotation tags" }
        require(snapshot.annotationTags.all { it.name.isNotBlank() && it.name == it.name.trim() }) { "Invalid annotation tag" }
        require(snapshot.bookmarks.all { it.syncId.isNotBlank() }) { "Bookmark without stable ID" }
        require(snapshot.bookmarks.map { it.syncId }.distinct().size == snapshot.bookmarks.size) {
            "Duplicate bookmark stable IDs"
        }
        require(snapshot.notes.all { it.syncId.isNotBlank() }) { "Note without stable ID" }
        require(snapshot.notes.map { it.syncId }.distinct().size == snapshot.notes.size) { "Duplicate note stable IDs" }
        require(snapshot.readingRecords.all { it.sourceDeviceId.isNotBlank() }) { "Reading record without device ID" }
        require(
            snapshot.readingRecords.distinctBy { "${it.bookId}\u0000${it.date}\u0000${it.sourceDeviceId}" }.size ==
                snapshot.readingRecords.size
        ) { "Duplicate reading device contributions" }

        val assets = snapshot.assets.associateBy { it.id }
        for (asset in snapshot.assets) {
            require(asset.id.matches(Regex("[a-f0-9]{64}(?:-[a-f0-9]{16})?"))) { "Invalid asset ID" }
            require(asset.sha256.matches(Regex("[a-f0-9]{64}")) && asset.sizeBytes > 0L) { "Invalid asset metadata" }
            require(asset.fileName == File(asset.fileName).name && '/' !in asset.fileName && '\\' !in asset.fileName) {
                "Invalid asset filename"
            }
        }
        for (assetId in referencedAssetIds(snapshot)) {
            val asset = assets[assetId]
            require(asset != null) { "Referenced asset is not declared: $assetId" }
            // 背景图是可选内容：文件缺失或损坏时回退默认背景，不阻断整次恢复。
            if (asset.kind == READER_BACKGROUND_KIND) continue
            val source = files[assetId]
            require(source?.isFile == true) { "Referenced asset is missing: $assetId" }
        }
    }

    private fun referencedAssetIds(snapshot: PortableSnapshot): Set<String> = buildSet {
        snapshot.books.forEach { book ->
            book.bodyAssetId?.let(::add)
            book.coverAssetId?.let(::add)
        }
        snapshot.folders.forEach { folder ->
            folder.coverPath?.takeIf { it.startsWith("asset://") }?.removePrefix("asset://")?.let(::add)
        }
        val pattern = Regex("asset://([a-f0-9]{64}(?:-[a-f0-9]{16})?)")
        snapshot.preferences.forEach { preference ->
            if (preference.type == PortablePreference.TYPE_STRING) {
                pattern.findAll(preference.value).forEach { add(it.groupValues[1]) }
            }
        }
    }

    private fun assetRoots(): Map<String, File> {
        val books = FileUtils.getBooksDirectory(context)
        val covers = FileUtils.getCoversDirectory(context)
        return mapOf(
            "book" to books,
            "book_cover" to covers,
            "folder_cover" to covers,
            "avatar" to File(context.filesDir, "avatars"),
            "font" to File(context.filesDir, "fonts"),
            "reader_background" to File(context.filesDir, "reader_backgrounds")
        )
    }

    private fun assetTarget(root: File, asset: PortableAsset): File {
        val owner = asset.ownerId.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) }
            ?: stableSuffix(asset.ownerId)
        val extension = asset.fileName.substringAfterLast('.')
        return when (asset.kind) {
            "avatar" -> File(root, "avatar.jpg")
            "folder_cover" -> File(root, "folder_custom_${owner}_${asset.sha256}.$extension")
            "book_cover" -> File(root, "custom_${owner}_${asset.sha256}.$extension")
            else -> File(root, asset.fileName)
        }
    }

    private fun replacePathsInPortableString(value: String, replacements: Map<String, String>): String {
        fun replaceText(text: String): String = replacements.entries.fold(text) { current, (old, new) ->
            current.replace(old, new).replace(old.replace('\\', '/'), new)
        }

        fun transform(value: Any?): Any? = when (value) {
            is JSONObject -> value.apply {
                keys().asSequence().toList().forEach { key -> put(key, transform(opt(key))) }
            }
            is JSONArray -> value.apply {
                for (index in 0 until length()) put(index, transform(opt(index)))
            }
            is String -> replaceText(value)
            else -> value
        }

        val trimmed = value.trimStart()
        return when {
            trimmed.startsWith('{') -> runCatching { transform(JSONObject(value)).toString() }.getOrElse {
                replaceText(value)
            }
            trimmed.startsWith('[') -> runCatching { transform(JSONArray(value)).toString() }.getOrElse {
                replaceText(value)
            }
            else -> replaceText(value)
        }
    }

    private fun sortFoldersParentFirst(
        folders: List<com.huangder.lumibooks.data.local.entity.FolderEntity>,
        existingIds: Set<String>
    ): List<com.huangder.lumibooks.data.local.entity.FolderEntity> {
        val remaining = folders.associateByTo(linkedMapOf()) { it.id }
        val known = existingIds.toMutableSet()
        val result = mutableListOf<com.huangder.lumibooks.data.local.entity.FolderEntity>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { it.parentId == null || it.parentId in known }
            require(ready.isNotEmpty()) { "Folder hierarchy contains a cycle or missing parent" }
            ready.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach {
                result += it
                known += it.id
                remaining.remove(it.id)
            }
        }
        return result
    }

    private data class DirectoryPlan(val target: File, val staging: File, val backup: File)

    private class PreparedAssetInstall(
        private val directories: List<DirectoryPlan>,
        private val stagedFiles: Map<File, File>,
        val paths: Map<String, String>,
        private val replaceDirectories: Boolean,
        private val token: String
    ) {
        private val committedDirectories = mutableListOf<DirectoryPlan>()
        private val committedFiles = mutableListOf<Pair<File, File?>>()

        fun commit() {
            if (replaceDirectories) {
                directories.forEach { plan ->
                    plan.staging.mkdirs()
                    plan.backup.deleteRecursively()
                    val hadTarget = plan.target.exists()
                    if (hadTarget) require(plan.target.renameTo(plan.backup)) { "Unable to stage existing resources" }
                    if (!plan.staging.renameTo(plan.target)) {
                        if (hadTarget) plan.backup.renameTo(plan.target)
                        error("Unable to install restored resources")
                    }
                    committedDirectories += plan
                }
            } else {
                stagedFiles.forEach { (target, staged) ->
                    target.parentFile?.mkdirs()
                    val backup = target.takeIf { it.exists() }?.let {
                        File(target.parentFile, ".${target.name}.previous-$token").also { backup ->
                            backup.delete()
                            require(target.renameTo(backup)) { "Unable to stage existing resource" }
                        }
                    }
                    if (!staged.renameTo(target)) {
                        backup?.renameTo(target)
                        error("Unable to install restored resource")
                    }
                    committedFiles += target to backup
                }
            }
        }

        fun rollback() {
            committedFiles.asReversed().forEach { (target, backup) ->
                target.delete()
                backup?.renameTo(target)
            }
            committedDirectories.asReversed().forEach { plan ->
                plan.target.deleteRecursively()
                if (plan.backup.exists()) plan.backup.renameTo(plan.target)
            }
        }

        fun finish() {
            committedFiles.forEach { (_, backup) -> backup?.delete() }
            committedDirectories.forEach { it.backup.deleteRecursively() }
            committedFiles.clear()
            committedDirectories.clear()
        }

        fun cleanup() {
            directories.forEach {
                it.staging.deleteRecursively()
                if (it.backup.exists() && !it.target.exists()) it.backup.renameTo(it.target)
            }
        }
    }

    private fun stripDerivedBackgroundPaths(preset: ReaderBackgroundPreset): ReaderBackgroundPreset =
        if (preset.type == ReaderBackgroundType.IMAGE) {
            preset.copy(processedValue = null, processedBlurDp = null)
        } else preset

    /**
     * 背景图是可选的：未入包或还原失败的图片对应 preset 直接剔除，
     * 并把指向它的选中项回退默认背景，避免快照携带跨设备无效路径。
     */
    private fun rewriteReaderBackgroundPreferences(
        preferences: List<PortablePreference>,
        keep: (ReaderBackgroundPreset) -> Boolean,
        transform: (ReaderBackgroundPreset) -> ReaderBackgroundPreset
    ): List<PortablePreference> {
        val stored = preferences.firstOrNull {
            it.key == READER_BACKGROUNDS_PREFERENCE_KEY && it.type == PortablePreference.TYPE_STRING
        } ?: return preferences
        val presets = ReaderBackgroundPresetCodec.decode(stored.value)
        if (presets.isEmpty()) return preferences
        val kept = presets.filter(keep).map(transform)
        if (kept == presets) return preferences
        val droppedKeys = presets.filterNot(keep).map { it.selectionKey }
        return preferences.map { preference ->
            when {
                preference.key == READER_BACKGROUNDS_PREFERENCE_KEY ->
                    preference.copy(value = ReaderBackgroundPresetCodec.encode(kept))
                preference.type == PortablePreference.TYPE_STRING && droppedKeys.any { it in preference.value } ->
                    preference.copy(
                        value = droppedKeys.fold(preference.value) { text, key ->
                            text.replace(key, DEFAULT_BACKGROUND_SELECTION)
                        }
                    )
                else -> preference
            }
        }
    }

    private fun BookEntity.toPortable(bodyAssetId: String?, coverAssetId: String?) = PortableBook(
        id, title, author, format, lastReadTime, readingProgress, locatorJson, createdAt, isFavorite,
        isCloudOnly, metadataUpdatedAt, bodyAssetId, coverAssetId, remoteLibraryKey, remoteFileName,
        remoteFileSize, remoteFileSha256, isPinned, isManuallyMarkedRead, isHidden, isArchived,
        archiveUpdatedAt
    )

    private companion object {
        const val READER_BACKGROUND_KIND = "reader_background"
        const val READER_BACKGROUNDS_PREFERENCE_KEY = "custom_reader_backgrounds"
        const val MAX_READER_BACKGROUND_BYTES = 20L * 1024 * 1024
        const val MAX_READER_BACKGROUND_ASSETS = 30
        const val DEFAULT_BACKGROUND_SELECTION = "day"
    }

}

internal fun PortableBook.toEntity(
    filePath: String,
    coverPath: String?,
    existingIsCloudOnly: Boolean? = null,
    existingIsArchived: Boolean = false
) = BookEntity(
    id = id, title = title, author = author, filePath = filePath, coverPath = coverPath,
    format = format, lastReadTime = lastReadTime, readingProgress = readingProgress,
    locatorJson = locatorJson, createdAt = createdAt, isFavorite = isFavorite,
    isCloudOnly = existingIsCloudOnly
        ?: (bodyAssetId == null && (isCloudOnly || filePath.isBlank())),
    remoteLibraryKey = remoteLibraryKey, remoteFileName = remoteFileName,
    remoteFileSize = remoteFileSize, remoteFileSha256 = remoteFileSha256,
    metadataUpdatedAt = metadataUpdatedAt, isPinned = isPinned,
    isManuallyMarkedRead = isManuallyMarkedRead, isHidden = isHidden,
    isArchived = isArchived ?: existingIsArchived,
    archiveUpdatedAt = archiveUpdatedAt ?: 0L
)

private fun stableSuffix(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .take(8)
    .joinToString("") { "%02x".format(it) }

private fun File.sha256Portable(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
