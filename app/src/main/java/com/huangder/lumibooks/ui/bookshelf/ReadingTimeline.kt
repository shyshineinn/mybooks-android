package com.huangder.lumibooks.ui.bookshelf

import android.icu.util.ULocale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.ui.components.InformationCard
import com.huangder.lumibooks.ui.components.InformationCardHeading
import com.huangder.lumibooks.ui.components.LiquidGlassIconButton
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.icons.directionalIcon
import com.huangder.lumibooks.ui.statistics.InsightsHue
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ReadingRecordBlock(
    meta: ServerBookMeta,
    events: List<ReadingTimelineEvent>,
    onClick: () -> Unit
) {
    val openLabel = stringResource(R.string.reading_timeline_open)
    InformationCard(
        modifier = Modifier.testTag("reading_timeline_preview")
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InformationCardHeading(
                stringResource(R.string.server_meta_read_logs),
                AppIcons.Timer,
                Modifier.weight(1f),
                iconTint = InsightsHue.Blue
            )
            readCountLabel(meta)?.let { Text(it, fontSize = AppType.Caption, color = AppColors.TextSecondary) }
        }
        val preview = events.take(3)
        if (preview.isEmpty()) {
            Text(stringResource(R.string.no_reading_records), fontSize = AppType.Caption, color = AppColors.TextSecondary)
        } else {
            Column {
                preview.forEachIndexed { index, event ->
                    ReadingTimelineItem(event, preview.getOrNull(index + 1), index == 0)
                }
            }
        }
    }
}

@Composable
internal fun ReadingTimelineDetailsScreen(
    bookTitle: String,
    meta: ServerBookMeta?,
    events: List<ReadingTimelineEvent>,
    onBack: () -> Unit,
    loading: Boolean = false,
    onEditEvent: ((ReadingTimelineEvent) -> Unit)? = null
) {
    Surface(Modifier.fillMaxSize().testTag("reading_timeline_details"), color = AppColors.PageBg) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = AppSpace.md)) {
            Row(Modifier.fillMaxWidth().padding(top = AppSpace.sm, bottom = AppSpace.md), verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassIconButton(
                    directionalIcon(AppIcons.ArrowLeft, AppIcons.ArrowRight),
                    stringResource(R.string.back), onBack,
                    modifier = Modifier.testTag("reading_timeline_back")
                )
                Spacer(Modifier.width(AppSpace.sm))
                val title = if (bookTitle.isBlank()) stringResource(R.string.server_meta_read_logs)
                    else stringResource(R.string.reading_timeline_title, bookTitle)
                val nameStart = if (bookTitle.isNotBlank()) title.lastIndexOf(bookTitle) else -1
                val headingText = if (nameStart >= 0) title.substring(0, nameStart) else title
                BoxWithConstraints(Modifier.weight(1f)) {
                    val headingWidth = if (nameStart >= 0) maxWidth * .65f else maxWidth
                    Row(
                        Modifier.fillMaxWidth().testTag("reading_timeline_title")
                            .semantics(mergeDescendants = true) { heading() },
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            headingText, fontSize = AppType.Title, lineHeight = AppType.Title,
                            fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = headingWidth)
                                .testTag("reading_timeline_heading").alignByBaseline()
                        )
                        if (nameStart >= 0) {
                            Text(
                                bookTitle, fontSize = AppType.Caption, lineHeight = AppType.Caption,
                                fontWeight = FontWeight.Normal, color = AppColors.TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).testTag("reading_timeline_book_title").alignByBaseline()
                            )
                        }
                    }
                }
            }
            InformationCard(
                modifier = Modifier.weight(1f).widthIn(max = 720.dp).align(Alignment.CenterHorizontally)
                    .testTag("reading_timeline_body"),
                contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top
            ) {
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppColors.Accent)
                    }
                } else if (events.isEmpty()) {
                    Column(Modifier.padding(AppSpace.md), verticalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
                        meta?.let { readCountLabel(it) }?.let {
                            Text(it, fontSize = AppType.Caption, color = AppColors.TextSecondary)
                        }
                        Text(stringResource(R.string.no_reading_records), fontSize = AppType.BodySmall, color = AppColors.TextSecondary)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("reading_timeline_list"), contentPadding = PaddingValues(AppSpace.md)) {
                        itemsIndexed(events, key = { _, event -> "${event.round}:${event.type}:${event.date}" }) { index, event ->
                            ReadingTimelineItem(event, events.getOrNull(index + 1), index == 0,
                                onEdit = if (event.source != null && onEditEvent != null) ({ onEditEvent(event) }) else null)
                        }
                    }
                }
            }
            Spacer(Modifier.height(AppSpace.md))
        }
    }
}

@Composable
private fun ReadingTimelineItem(
    event: ReadingTimelineEvent,
    older: ReadingTimelineEvent?,
    highlighted: Boolean,
    onEdit: (() -> Unit)? = null
) {
    val locale = LocalConfiguration.current.locales[0]
    val round = readingRoundLabel(event.round)
    val title = stringResource(
        if (event.type == ReadingTimelineEventType.START) R.string.reading_timeline_started else R.string.reading_timeline_finished,
        round
    )
    val dateFormatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    val lineColor = AppColors.Divider
    Column(Modifier.fillMaxWidth().drawBehind {
        if (older != null) {
            val x = if (layoutDirection == LayoutDirection.Ltr) 12.dp.toPx() else size.width - 12.dp.toPx()
            drawLine(lineColor, Offset(x, 24.dp.toPx()), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = if (onEdit == null) Alignment.Top else Alignment.CenterVertically) {
            Icon(
                if (event.type == ReadingTimelineEventType.START) AppIcons.BookOpen else AppIcons.CheckCircle,
                contentDescription = null,
                tint = if (highlighted) AppColors.Accent else AppColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            val dateColor = AppColors.TextSecondary
            Text(
                buildAnnotatedString {
                    append(title)
                    withStyle(SpanStyle(fontSize = AppType.Caption, color = dateColor, fontWeight = FontWeight.Normal)) {
                        append('·')
                        append(event.date.format(dateFormatter))
                    }
                },
                fontSize = AppType.Body, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary, modifier = Modifier.weight(1f)
                    .testTag("timeline_event_text_${event.round}_${event.type}")
            )
            if (onEdit != null) IconButton(onClick = onEdit,
                modifier = Modifier.size(44.dp).testTag("timeline_edit_${event.round}_${event.type}")) {
                Icon(AppIcons.PencilSimple, stringResource(R.string.reading_timeline_edit_time),
                    tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        if (older != null) {
            val days = readingTimelineGapDays(event, older)
            val interval = if (days == 0) stringResource(R.string.reading_timeline_same_day) else {
                val text = when {
                    event.round == older.round && event.type == ReadingTimelineEventType.FINISH && older.type == ReadingTimelineEventType.START -> R.plurals.reading_timeline_elapsed
                    event.round > older.round && event.type == ReadingTimelineEventType.START && older.type == ReadingTimelineEventType.FINISH -> R.plurals.reading_timeline_reread_gap
                    else -> R.plurals.reading_timeline_gap
                }
                pluralStringResource(text, days, days)
            }
            Text(interval, fontSize = AppType.Caption, lineHeight = 18.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(start = 36.dp, top = AppSpace.sm, bottom = AppSpace.md))
        }
    }
}

@Composable
internal fun readingRoundLabel(round: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(round, locale) {
        if (locale.language == "zh") {
            val numbering = if (locale.country in setOf("TW", "HK", "MO")) "hant" else "hans"
            android.icu.text.NumberFormat.getInstance(ULocale.forLocale(locale).setKeywordValue("numbers", numbering))
                .format(round.toLong())
        } else NumberFormat.getIntegerInstance(locale).format(round)
    }
}
