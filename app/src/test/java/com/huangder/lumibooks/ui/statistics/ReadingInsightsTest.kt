package com.huangder.lumibooks.ui.statistics

import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.model.Bookmark
import com.huangder.lumibooks.domain.model.ReadingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReadingInsightsTest {
    @Test fun activeWeekIncludesTodayInAveragesButNotInComparison() {
        val today = LocalDate.of(2026, 11, 3)
        val week = StatisticsPeriod.week(today, 0)
        val previous = week.previousPeriod(0)
        val records = mapOf(
            week.start to 180L,
            today to 300L,
            today.plusDays(1) to 10_000L,
            previous.start to 60L,
            previous.start.plusDays(2) to 10_000L
        )

        val insights = readingInsights(week, previous, today) { records[it] ?: 0L }

        assertEquals(160L, insights.dailyAverage)
        assertEquals(150L, insights.weekdayAverage)
        assertEquals(180L, insights.weekendAverage)
        assertEquals(today to 300L, insights.bestDay)
        assertEquals(180L to 60L, insights.comparison)
        assertNull(readingInsights(week, previous, week.start) { 10L }.comparison)
    }

    @Test fun activeMonthCapsBothSidesToTheShorterPreviousMonth() {
        val march = StatisticsPeriod.month(2027, 2)
        val february = march.previousPeriod(1)
        val today = LocalDate.of(2027, 3, 31)
        val insights = readingInsights(march, february, today) { day ->
            if (day.monthValue == 3) 20L else 10L
        }
        assertEquals(560L to 280L, insights.comparison)

        val completed = readingInsights(march, february, today.plusDays(1)) { 10L }
        assertEquals(310L to 280L, completed.comparison)
    }

    @Test fun dayGroupsSessionsAcrossDevicesAndKeepsBooksWithOnlyMarks() {
        fun book(id: String) = Book(id, id, "", "", null, BookFormat.EPUB, 0L, 0f, createdAt = 0L)
        fun session(bookId: String, duration: Long, device: String) =
            ReadingRecord(bookId = bookId, date = "2026-11-03", duration = duration,
                startTime = 0L, endTime = duration, sourceDeviceId = device)
        val records = listOf(session("read", 100L, "a"), session("read", 200L, "b"), session("deleted", 400L, "a"))
        val bookmark = Bookmark(bookId = "marked", chapterIndex = 0, position = 0f, title = "", createdAt = 1L)

        val result = readingDayBooks(records, listOf(book("marked"), book("read"), book("inactive")),
            emptyList(), listOf(bookmark))

        assertEquals(listOf("read", "marked"), result.map { it.book.id })
        assertEquals(listOf(300L, 0L), result.map { it.duration })
        assertEquals(1, result.last().annotations.bookmarks)
    }
}
