package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.util.ImportBookMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalBookImportPreparationTest {
    private val isbn = "9780306406157"
    private fun book(number: String = isbn) = JSONObject().put("title", "Book").put("author", "Author")
        .put("isbn", number).put("doubanId", "123").put("translators", JSONArray().put("Translator"))
        .put("cover", "https://example.com/cover.jpg")
    private fun found(value: JSONObject = book()) = JSONObject().put("status", "found").put("book", value).toString()
    private fun results(vararg values: JSONObject) = JSONObject().put("results", JSONArray(values.toList())).toString()
    private val initial = MyBooksEditUiState(loading = false, loaded = true, title = "Book")

    @Test fun isbnLookupFillsAllMetadataWithoutTitleSearch() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata(isbns = listOf("0-306-40615-2")) },
            { assertEquals(isbn, it); found() }, { error("Must use ISBN lookup") })
        assertNull(result.reviewReason)
        assertEquals(isbn, result.state.isbn)
        assertEquals("Author", result.state.author)
        assertEquals("Translator", result.state.translators)
        assertEquals("https://example.com/cover.jpg", result.state.coverUrl)
        assertTrue(result.state.candidates.isEmpty())
    }
    @Test fun missingFileIsbnCanBeRecoveredFromUniqueExactTitleMatch() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata(title = "Book") },
            { error("No ISBN yet") }, { results(book()) })
        assertNull(result.reviewReason)
        assertEquals(isbn, result.state.isbn)
    }
    @Test fun multipleEditionsRequireSelectionAndKeepCandidates() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata() }, { found() }, { results(book(), book("9780131103627")) })
        assertEquals(ImportReviewReason.AMBIGUOUS, result.reviewReason)
        assertEquals(2, result.state.candidates.size)
    }
    @Test fun conflictingIsbnNeverOverwritesTheExtractedNumber() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata(isbns = listOf(isbn)) }, { found(book("9780131103627")) }, { results() })
        assertEquals(ImportReviewReason.CONFLICT, result.reviewReason)
        assertEquals(isbn, result.state.isbn)
    }
    @Test fun multipleFileIsbnsRequireReviewBeforeAnyLookup() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata(isbns = listOf(isbn, "9780131103627")) },
            { error("Ambiguous ISBN") }, { error("Ambiguous ISBN") })
        assertEquals(ImportReviewReason.MULTIPLE_ISBNS, result.reviewReason)
    }
    @Test fun openLibraryFallbackNeedsReviewForDoubanImport() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata(isbns = listOf(isbn)) },
            { found(book().put("doubanId", "").put("openlibraryId", "OL1M")) }, { results() })
        assertEquals(ImportReviewReason.INCOMPLETE, result.reviewReason)
        assertEquals(1, result.state.candidates.size)
    }
    @Test fun failedLookupKeepsFileTitleAuthorAndIsbnForEditing() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata("Actual title", "Actual author", listOf(isbn)) },
            { throw java.io.IOException("offline") }, { results() })
        assertEquals(ImportReviewReason.LOOKUP_FAILED, result.reviewReason)
        assertEquals("Actual title", result.state.title)
        assertEquals("Actual author", result.state.author)
        assertEquals(isbn, result.state.isbn)
    }
    @Test fun unreadableFileOpensReviewWithFilenameDraft() = runTest {
        val result = prepareLocalBookImport(initial, { throw java.io.IOException("unreadable") }, { found() }, { results() })
        assertEquals(ImportReviewReason.READ_FAILED, result.reviewReason)
        assertEquals(initial.title, result.state.title)
    }
    @Test fun noMatchRequiresReview() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata() }, { found() }, { results() })
        assertEquals(ImportReviewReason.NOT_FOUND, result.reviewReason)
    }
    @Test fun unrelatedSingleResultRequiresReview() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata() }, { found() }, { results(book().put("title", "Another book")) })
        assertEquals(ImportReviewReason.CONFLICT, result.reviewReason)
    }
    @Test fun cancellationIsPropagatedInsteadOfOpeningEditor() = runTest {
        try {
            prepareLocalBookImport(initial, { ImportBookMetadata(isbns = listOf(isbn)) }, { throw CancellationException("cancel") }, { results() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun blockedAndFailedResponsesAreNotReportedAsMissingBooks() = runTest {
        for ((status, reason) in listOf("blocked" to ImportReviewReason.BLOCKED, "error" to ImportReviewReason.LOOKUP_FAILED)) {
            val result = prepareLocalBookImport(initial, { ImportBookMetadata(isbns = listOf(isbn)) },
                { JSONObject().put("status", status).toString() }, { results() })
            assertEquals(reason, result.reviewReason)
            assertEquals(isbn, result.state.isbn)
        }
    }
    @Test fun sameTitleWithDifferentAuthorRequiresReview() = runTest {
        val result = prepareLocalBookImport(initial, { ImportBookMetadata(author = "Another author") },
            { found() }, { results(book()) })
        assertEquals(ImportReviewReason.CONFLICT, result.reviewReason)
    }
    @Test fun incompleteCandidatesRequireReview() = runTest {
        for (key in listOf("isbn", "author")) {
            val result = prepareLocalBookImport(initial, { ImportBookMetadata(isbns = listOf(isbn)) },
                { found(book().put(key, "")) }, { results() })
            assertEquals(ImportReviewReason.INCOMPLETE, result.reviewReason)
        }
    }

}
