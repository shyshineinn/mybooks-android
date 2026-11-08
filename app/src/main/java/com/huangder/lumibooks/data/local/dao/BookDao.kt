package com.huangder.lumibooks.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Update
import com.huangder.lumibooks.data.local.entity.BookEntity
import com.huangder.lumibooks.data.local.model.ContinueReadingWidgetData
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadTime DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY lastReadTime DESC")
    suspend fun getAllBooksSnapshot(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("UPDATE books SET coverPath = :newPath WHERE id = :bookId AND ((coverPath IS NULL AND :oldPath IS NULL) OR coverPath = :oldPath)")
    suspend fun updateCoverIfUnchanged(bookId: String, oldPath: String?, newPath: String): Int

    @Query("SELECT * FROM books WHERE sourceDocumentKey = :key LIMIT 1")
    suspend fun getBookBySourceDocumentKey(key: String): BookEntity?

    @Query("SELECT * FROM books WHERE sourceUri = :uri LIMIT 1")
    suspend fun getBookBySourceUri(uri: String): BookEntity?

    @Query("SELECT * FROM books WHERE sourceSha256 = :sha256")
    suspend fun getBooksBySourceSha256(sha256: String): List<BookEntity>

    @Query(
        "SELECT id AS bookId, title, author, coverPath, readingProgress " +
            "FROM books WHERE isArchived = 0 ORDER BY lastReadTime DESC LIMIT 1"
    )
    suspend fun getContinueReadingWidgetData(): ContinueReadingWidgetData?

    @Query(
        "SELECT id AS bookId, title, author, coverPath, readingProgress " +
            "FROM books WHERE isArchived = 0 ORDER BY lastReadTime DESC LIMIT 1"
    )
    fun observeContinueReadingWidgetData(): Flow<ContinueReadingWidgetData?>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Upsert
    suspend fun insertBook(book: BookEntity)

    @Upsert
    suspend fun upsertBooks(books: List<BookEntity>)

    @Query("DELETE FROM books")
    suspend fun clearBooks()

    @Update
    suspend fun updateBook(book: BookEntity)

    @Transaction
    suspend fun updateBookPreservingArchive(book: BookEntity) {
        val existing = getBookById(book.id) ?: return
        val incomingArchiveWins = book.archiveUpdatedAt > existing.archiveUpdatedAt ||
            (book.archiveUpdatedAt == existing.archiveUpdatedAt && book.isArchived && !existing.isArchived)
        updateBook(book.copy(
            isArchived = if (incomingArchiveWins) book.isArchived else existing.isArchived,
            archiveUpdatedAt = maxOf(book.archiveUpdatedAt, existing.archiveUpdatedAt)
        ))
    }

    @Query("""UPDATE books SET isArchived = :archived,
        archiveUpdatedAt = MAX(:updatedAt, archiveUpdatedAt + 1)
        WHERE id = :bookId AND isArchived != :archived""")
    suspend fun setArchived(bookId: String, archived: Boolean, updatedAt: Long): Int

    @Query("""UPDATE books SET
        title=CASE WHEN title=:expectedTitle THEN :title ELSE title END,
        author=CASE WHEN author=:expectedAuthor THEN :author ELSE author END,
        coverPath=CASE WHEN coverPath IS :expectedCover THEN :coverPath ELSE coverPath END,
        format=CASE WHEN isCloudOnly=1 THEN :format ELSE format END,
        remoteFileSize=:remoteFileSize, metadataUpdatedAt=:metadataUpdatedAt WHERE id=:bookId""")
    suspend fun applySyncedMetadata(bookId: String, title: String, author: String, coverPath: String?,
        format: String, remoteFileSize: Long, metadataUpdatedAt: Long,
        expectedTitle: String, expectedAuthor: String, expectedCover: String?)

    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :bookId")
    suspend fun updateBookCoverPath(bookId: String, coverPath: String)

    @Query("UPDATE books SET sourceSha256 = :sha256 WHERE id = :bookId")
    suspend fun updateSourceSha256(bookId: String, sha256: String)

    @Query("UPDATE books SET title = :title, author = :author, metadataUpdatedAt = :metadataUpdatedAt WHERE id = :bookId")
    suspend fun updateBookTitleAuthor(
        bookId: String,
        title: String,
        author: String,
        metadataUpdatedAt: Long
    )

    @Query("UPDATE books SET filePath = '', isCloudOnly = 1 WHERE id = :bookId")
    suspend fun markBookCloudOnly(bookId: String)

    @Query("UPDATE books SET readingProgress=:progress,locatorJson=:locator,lastReadTime=:readAt WHERE id=:bookId")
    suspend fun applyServerReadingPosition(bookId: String, progress: Float, locator: String?, readAt: Long)

    @Query("UPDATE books SET filePath=:filePath,isCloudOnly=0,format=:format,remoteFileSha256=:sha256,sourceSha256=:sha256 WHERE id=:bookId")
    suspend fun markServerBookDownloaded(bookId: String, filePath: String, format: String, sha256: String)

    @Query("UPDATE books SET filePath = :filePath, isCloudOnly = 0 WHERE id = :bookId")
    suspend fun markBookDownloaded(bookId: String, filePath: String)

    @Query(
        "UPDATE books SET isCloudOnly = 0, remoteLibraryKey = NULL, remoteFileName = NULL, " +
            "remoteFileSize = 0, remoteFileSha256 = NULL WHERE id = :bookId"
    )
    suspend fun clearRemoteAssociation(bookId: String)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksByBookId(bookId: String)

    @Query("DELETE FROM notes WHERE bookId = :bookId")
    suspend fun deleteNotesByBookId(bookId: String)

    @Query("DELETE FROM reading_records WHERE bookId = :bookId")
    suspend fun deleteReadingRecordsByBookId(bookId: String)

    @Query("DELETE FROM book_tag_cross_refs WHERE bookId = :bookId")
    suspend fun deleteTagLinksByBookId(bookId: String)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)

    @Transaction
    suspend fun deleteBookWithRelatedData(bookId: String) {
        deleteBookmarksByBookId(bookId)
        deleteNotesByBookId(bookId)
        deleteReadingRecordsByBookId(bookId)
        deleteTagLinksByBookId(bookId)
        deleteBookById(bookId)
    }

    @Query("UPDATE books SET lastReadTime = :timestamp WHERE id = :bookId")
    suspend fun updateLastReadTime(bookId: String, timestamp: Long)

    @Query("UPDATE books SET readingProgress = :progress, locatorJson = COALESCE(:locatorJson, locatorJson) WHERE id = :bookId")
    suspend fun updateReadingProgress(bookId: String, progress: Float, locatorJson: String?)
}
