package com.huangder.lumibooks.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.animation.VisualMotion.tween
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.icons.directionalIcon
import com.huangder.lumibooks.ui.theme.*
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields

internal enum class AppMonthCalendarMode { Statistics, DatePicker }

internal fun monthCalendarCells(month: YearMonth, firstDay: DayOfWeek): List<LocalDate?> {
    val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val count = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    return List(count) { index -> (index - offset + 1).takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay) }
}

/** Calendar layout shared by statistics and single-date editing; has no ViewModel dependency. */
@Composable
internal fun AppMonthCalendar(
    month: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    mode: AppMonthCalendarMode,
    modifier: Modifier = Modifier,
    nextEnabled: Boolean = true,
    previousEnabled: Boolean = true,
    dailyData: Map<String, Long> = emptyMap(),
    selectedDate: LocalDate? = null,
    onDateSelected: ((LocalDate) -> Unit)? = null,
    subtitle: @Composable () -> Unit = {}
) {
    val picker = mode == AppMonthCalendarMode.DatePicker
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = if (picker) WeekFields.of(locale).firstDayOfWeek else DayOfWeek.SUNDAY
    val cells = remember(month, firstDay) { monthCalendarCells(month, firstDay) }
    val today = LocalDate.now()
    val motion = LocalMotionEnabled.current && !LocalEInkMode.current
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val monthNames = listOf(R.string.month_january, R.string.month_february, R.string.month_march,
        R.string.month_april, R.string.month_may, R.string.month_june, R.string.month_july,
        R.string.month_august, R.string.month_september, R.string.month_october, R.string.month_november, R.string.month_december)
    val heading = if (picker) remember(month, locale) {
        month.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale))
    } else stringResource(R.string.year_format, month.year) + stringResource(monthNames[month.monthValue - 1])
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(heading, fontSize = if (picker) AppType.Body else AppType.Section, fontWeight = FontWeight.Bold,
                fontFamily = resolveAppFontFamily(KaiTi), color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f).testTag("calendar_month").semantics { heading() })
            Box(Modifier.size(if (picker) 44.dp else 28.dp).clip(AppRoundedCornerShape(6.dp))
                .clickable(enabled = previousEnabled, role = Role.Button, indication = null,
                    interactionSource = remember { MutableInteractionSource() }, onClick = onPreviousMonth)
                .testTag("calendar_previous"), contentAlignment = Alignment.Center) {
                Icon(directionalIcon(AppIcons.CaretLeft, AppIcons.CaretRight),
                    stringResource(R.string.calendar_previous_month),
                    tint = if (previousEnabled) AppColors.TextPrimary else AppColors.TextSecondary.copy(alpha = .3f),
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(if (picker) 44.dp else 28.dp).clip(AppRoundedCornerShape(6.dp))
                .clickable(enabled = nextEnabled, role = Role.Button, indication = null,
                    interactionSource = remember { MutableInteractionSource() }, onClick = onNextMonth)
                .testTag("calendar_next"), contentAlignment = Alignment.Center) {
                Icon(directionalIcon(AppIcons.CaretRight, AppIcons.CaretLeft),
                    stringResource(R.string.calendar_next_month),
                    tint = if (nextEnabled) AppColors.TextPrimary else AppColors.TextSecondary.copy(alpha = .3f),
                    modifier = Modifier.size(20.dp))
            }
        }
        subtitle()
        Spacer(Modifier.height(AppSpace.md))
        val statisticsWeekLabels = listOf(R.string.day_sunday, R.string.day_monday, R.string.day_tuesday,
            R.string.day_wednesday, R.string.day_thursday, R.string.day_friday, R.string.day_saturday)
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { index ->
                val label = if (picker) firstDay.plus(index.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale)
                    else stringResource(statisticsWeekLabels[index])
                Text(label, fontSize = 11.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(AppSpace.xs))
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) Spacer(Modifier.weight(1f)) else {
                        val minutes = ((dailyData[date.toString()] ?: 0L) / 1000 / 60).toInt()
                        val selected = picker && date == selectedDate
                        val isToday = date == today
                        val background = if (picker) {
                            if (selected) AppColors.Accent else Color.Transparent
                        } else when {
                            minutes == 0 -> AppColors.BgGray
                            minutes < 15 -> AppColors.Accent.copy(alpha = .2f)
                            minutes < 30 -> AppColors.Accent.copy(alpha = .4f)
                            minutes < 60 -> AppColors.Accent.copy(alpha = .7f)
                            else -> AppColors.Accent
                        }
                        val animated by animateColorAsState(background,
                            animationSpec = if (picker && motion) tween(140) else snap(), label = "calendarSelection")
                        val shape = AppRoundedCornerShape(4.dp)
                        Box(Modifier.weight(1f)
                            .then(if (picker) Modifier.heightIn(min = 44.dp) else Modifier.aspectRatio(1f))
                            .padding(2.dp).clip(shape).background(animated)
                            .then(if (isToday) Modifier.border(1.5.dp, AppColors.Accent, shape) else Modifier)
                            .then(if (picker || (onDateSelected != null && date <= today)) {
                                Modifier.testTag("calendar_date_$date")
                                    .semantics {
                                        if (picker) this.selected = selected
                                        contentDescription = date.format(
                                            DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)
                                        )
                                    }
                                    .clickable(role = Role.Button) { onDateSelected?.invoke(date) }
                            } else Modifier),
                            contentAlignment = Alignment.Center) {
                            Text(if (picker) numbers.format(date.dayOfMonth) else "${date.dayOfMonth}",
                                fontSize = if (picker) AppType.Caption else 10.sp,
                                color = if (picker && selected) AppColors.OnAccent else if (!picker && minutes >= 30) Color.White else AppColors.TextSecondary,
                                fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Normal,
                                textDecoration = if (picker && isToday) TextDecoration.Underline else null)
                        }
                    }
                }
            }
        }
    }
}
