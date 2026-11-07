package com.huangder.lumibooks.ui.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.components.LiquidGlassBottomSheetScaffold
import com.huangder.lumibooks.ui.components.lumiCardSurface
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.cardOutline
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ReadingInsightsCard(data: ReadingInsights, onDay: (LocalDate) -> Unit) {
    val shape = AppRoundedCornerShape(AppRadius.lg)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = AppSpace.lg)
            .cardOutline(shape).clip(shape).lumiCardSurface(shape = shape).padding(AppSpace.md),
        verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
    ) {
        InsightHeading(stringResource(R.string.insights_habits))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
            InsightMetric(R.string.insights_daily_average, data.dailyAverage, Modifier.weight(1f))
            InsightMetric(R.string.insights_weekday, data.weekdayAverage, Modifier.weight(1f))
            InsightMetric(R.string.insights_weekend, data.weekendAverage, Modifier.weight(1f))
        }
        data.bestDay?.let { (date, duration) ->
            Text(
                stringResource(R.string.insights_best_day, insightDate(date), insightDuration(duration)),
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onDay(date) }
                    .padding(vertical = AppSpace.xs),
                color = AppColors.Accent,
                fontSize = AppType.BodySmall
            )
        }
        data.comparison?.let { (current, previous) ->
            Text(stringResource(R.string.insights_comparison), color = AppColors.TextSecondary, fontSize = AppType.Caption)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
                InsightMetric(R.string.insights_current_period, current, Modifier.weight(1f))
                InsightMetric(R.string.insights_previous_period, previous, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ReadingDaySheet(day: ReadingDay, onDismiss: () -> Unit, onBook: (String) -> Unit) {
    LiquidGlassBottomSheetScaffold(onDismiss = onDismiss) { requestDismiss ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { InsightHeading(insightDate(day.date)) }
            IconButton(onClick = requestDismiss) {
                Icon(AppIcons.X, stringResource(R.string.close), tint = AppColors.TextSecondary)
            }
        }
        val books = day.books
        if (books.isNullOrEmpty()) {
            val message = when {
                day.loadFailed -> R.string.insights_load_failed
                books == null -> R.string.loading
                else -> R.string.no_reading_records
            }
            Text(stringResource(message), Modifier.padding(vertical = AppSpace.md),
                color = AppColors.TextSecondary, fontSize = AppType.BodySmall)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(books, key = { it.book.id }) { entry ->
                    val marks = entry.annotations.run { highlights + underlines + notes + bookmarks }
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { requestDismiss(); onBook(entry.book.id) }
                        .padding(vertical = AppSpace.sm)) {
                        Text(entry.book.title, color = AppColors.TextPrimary, fontSize = AppType.Body,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entry.duration > 0L) Text(insightDuration(entry.duration),
                            color = AppColors.TextSecondary, fontSize = AppType.Caption)
                        if (marks > 0) Text(stringResource(R.string.insights_marks_count, marks),
                            color = AppColors.TextSecondary, fontSize = AppType.Caption)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightHeading(text: String) {
    Text(text, color = AppColors.TextPrimary, fontSize = AppType.Section, fontWeight = FontWeight.Bold)
}

@Composable
private fun InsightMetric(label: Int, duration: Long, modifier: Modifier) {
    Column(modifier) {
        Text(insightDuration(duration), color = AppColors.TextPrimary,
            fontSize = AppType.BodySmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(label), color = AppColors.TextSecondary, fontSize = AppType.Caption)
    }
}

@Composable
private fun insightDuration(duration: Long): String {
    val minutes = duration / 60_000L
    return stringResource(R.string.statistics_duration_value, (minutes / 60).toInt(), (minutes % 60).toInt())
}

@Composable
private fun insightDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    return date.format(formatter)
}
