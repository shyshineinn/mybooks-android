package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.model.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfBookFiltersTest {
    @Test
    fun epubMobiIncludesEpubAndAllKindleBooks() {
        val books = BookFormat.entries.map { format -> book(format) }

        assertEquals(
            listOf(BookFormat.EPUB, BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3),
            books.filter(Book::isEpubMobi).map { it.format }
        )
    }

    @Test
    fun finishedBooksUseServerDoneForMyBooksAndLocalStateForImports() {
        val books = listOf(
            book(BookFormat.EPUB).copy(id = "local-finished", isManuallyMarkedRead = true),
            book(BookFormat.TXT).copy(id = "local-unfinished"),
            book(BookFormat.EPUB).copy(id = "mybooks-1"),
            book(BookFormat.EPUB).copy(id = "mybooks-2", readingProgress = 1f),
            book(BookFormat.EPUB).copy(id = "mybooks-3", isManuallyMarkedRead = true)
        )

        assertEquals(
            setOf("local-finished", "mybooks-1"),
            finishedBookIdsForLibrary(
                books,
                mapOf(
                    "mybooks-1" to ReadingStatus.DONE,
                    "mybooks-2" to ReadingStatus.READING
                )
            )
        )
    }

    private fun book(format: BookFormat) = Book(
        id = format.name,
        title = format.name,
        author = "Author",
        filePath = "/book.${format.name.lowercase()}",
        coverPath = null,
        format = format,
        lastReadTime = 0L,
        readingProgress = 0f,
        createdAt = 0L
    )
}
