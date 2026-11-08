package com.huangder.lumibooks.data.local.database

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.data.local.entity.BookEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookArchiveDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun archivedBooksLeaveWidgetQueriesButStayInCompleteLibrary() = runBlocking {
        db.bookDao().insertBook(book("visible"))
        db.bookDao().insertBook(book("archived", archived = true, archiveUpdatedAt = 10))

        assertEquals("visible", db.bookDao().getContinueReadingWidgetData()?.bookId)
        assertEquals(
            setOf("visible", "archived"),
            db.bookDao().getAllBooksSnapshot().map { it.id }.toSet()
        )
    }

    @Test
    fun archiveUpdatesUseMonotonicTimestampsAndRestoreKeepsReadingData() = runBlocking {
        db.bookDao().insertBook(book("book"))

        assertEquals(1, db.bookDao().setArchived("book", true, 100))
        val archived = db.bookDao().getBookById("book")!!
        assertTrue(archived.isArchived)
        assertEquals(100L, archived.archiveUpdatedAt)

        assertEquals(0, db.bookDao().setArchived("book", true, 200))
        assertEquals(100L, db.bookDao().getBookById("book")!!.archiveUpdatedAt)

        assertEquals(1, db.bookDao().setArchived("book", false, 150))
        assertEquals(150L, db.bookDao().getBookById("book")!!.archiveUpdatedAt)
        assertEquals(1, db.bookDao().setArchived("book", true, 200))
        assertEquals(200L, db.bookDao().getBookById("book")!!.archiveUpdatedAt)

        db.bookDao().updateBookPreservingArchive(
            archived.copy(title = "Updated", lastReadTime = 9, isArchived = false, archiveUpdatedAt = 0)
        )
        val afterStaleUpdate = db.bookDao().getBookById("book")!!
        assertEquals("Updated", afterStaleUpdate.title)
        assertTrue(afterStaleUpdate.isArchived)
        assertEquals(200L, afterStaleUpdate.archiveUpdatedAt)

        db.bookDao().updateBookPreservingArchive(
            afterStaleUpdate.copy(isArchived = false, archiveUpdatedAt = 250)
        )
        assertFalse(db.bookDao().getBookById("book")!!.isArchived)
    }

    private fun book(
        id: String,
        archived: Boolean = false,
        archiveUpdatedAt: Long = 0L
    ) = BookEntity(
        id = id,
        title = id,
        author = "Author",
        filePath = "/books/$id.epub",
        coverPath = null,
        format = "EPUB",
        lastReadTime = 1,
        readingProgress = 0.4f,
        createdAt = 1,
        isArchived = archived,
        archiveUpdatedAt = archiveUpdatedAt
    )
}
