package com.huangder.lumibooks.ui.statistics

import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.Bookmark
import com.huangder.lumibooks.domain.model.Note
import com.huangder.lumibooks.domain.model.ReadingRecord
import java.time.LocalDate

internal data class ReadingInsights(
    val dailyAverage: Long,
    val weekdayAverage: Long,
    val weekendAverage: Long,
    val bestDay: Pair<LocalDate, Long>?,
    val comparison: Pair<Long, Long>?
)

internal fun StatisticsPeriod.previousPeriod(tab: Int): StatisticsPeriod = when (tab) {
    0 -> StatisticsPeriod(start.minusWeeks(1), endInclusive.minusWeeks(1))
    1 -> start.minusMonths(1).let { StatisticsPeriod.month(it.year, it.monthValue - 1) }
    else -> StatisticsPeriod.year(start.year - 1)
}

internal fun readingInsights(
    period: StatisticsPeriod,
    previous: StatisticsPeriod,
    today: LocalDate,
    durationOn: (LocalDate) -> Long
): ReadingInsights {
    val elapsed = period.days.takeWhile { it <= today }
    val daily = elapsed.map { it to durationOn(it) }
    val (weekend, weekday) = daily.partition { it.first.dayOfWeek.value >= 6 }
    fun average(days: List<Pair<LocalDate, Long>>) =
        if (days.isEmpty()) 0L else days.sumOf { it.second } / days.size

    val comparison = if (period.endInclusive < today) {
        daily.sumOf { it.second } to previous.days.sumOf(durationOn)
    } else {
        // A day still in progress should not be compared with a completed day.
        val completed = daily.takeWhile { it.first < today }
        val comparable = minOf(completed.size, previous.days.size)
        if (comparable == 0) null else {
            completed.take(comparable).sumOf { it.second } to previous.days.take(comparable).sumOf(durationOn)
        }
    }
    return ReadingInsights(
        dailyAverage = average(daily),
        weekdayAverage = average(weekday),
        weekendAverage = average(weekend),
        bestDay = daily.maxByOrNull { it.second }?.takeIf { it.second > 0L },
        comparison = comparison
    )
}

internal data class ReadingDayBook(
    val book: Book,
    val duration: Long,
    val annotations: AnnotationPeriodSummary
)

internal data class ReadingDay(
    val date: LocalDate,
    val books: List<ReadingDayBook>? = null,
    val loadFailed: Boolean = false
)

internal fun readingDayBooks(
    records: List<ReadingRecord>,
    books: List<Book>,
    notes: List<Note>,
    bookmarks: List<Bookmark>
): List<ReadingDayBook> {
    val durations = records.groupBy { it.bookId }.mapValues { (_, rows) -> rows.sumOf { it.duration } }
    val notesByBook = notes.groupBy { it.bookId }
    val bookmarksByBook = bookmarks.groupBy { it.bookId }
    return books.mapNotNull { book ->
        val duration = durations[book.id] ?: 0L
        val annotations = summarizeAnnotations(notesByBook[book.id].orEmpty(), bookmarksByBook[book.id].orEmpty(), 0)
        if (duration <= 0L && annotations == AnnotationPeriodSummary()) null
        else ReadingDayBook(book, duration, annotations)
    }.sortedWith(compareByDescending<ReadingDayBook> { it.duration }.thenBy { it.book.title }.thenBy { it.book.id })
}
