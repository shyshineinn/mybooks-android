package com.huangder.lumibooks.ui.bookshelf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.components.*
import com.huangder.lumibooks.ui.theme.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingTimelineDateDialogTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private val meta = ServerBookMeta.fromJson(JSONObject().put("title", "A very long book title")
        .put("read_count", 1).put("read_logs", JSONArray().put(JSONObject()
            .put("start", "2026-12-01").put("finish", "2026-12-17"))).toString(), 1)
    private val event = readingTimelineEvents(meta).first()

    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/timeline-date/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun mount(glass: Boolean = false, dark: Boolean = false, fontScale: Float = 1f,
        material3: Boolean = false, eInk: Boolean = false,
        content: @Composable () -> Unit) {
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                val theme = if (eInk) "lumi" else if (material3) "material3" else if (glass) "liquid_glass" else "lumi"
                EBookReaderTheme(appTheme = theme, darkTheme = dark, eInkMode = eInk,
                    dynamicColor = material3, motionPreference = MotionPreference.REDUCED) {
                    CompositionLocalProvider(LocalAppTheme provides theme,
                        LocalMotionEnabled provides false) {
                        LiquidGlassDialogHost(Modifier.fillMaxSize().background(AppColors.PageBg)) { content() }
                    }
                }
            }
        }
    }

    @Test fun cancelDoesNotPersistAndCalendarStartsOnTheOriginalDate() {
        val visible = mutableStateOf(true)
        var saved: LocalDate? = null
        mount {
            if (visible.value) ReadingTimelineDateDialog(event, false, true, null,
                { visible.value = false }, { saved = it })
        }
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled()
        compose.onNodeWithTag("timeline_date_value").performClick()
        compose.onNodeWithTag("calendar_date_2026-12-17").assertIsSelected()
        compose.onNodeWithTag("calendar_next").performClick()
        compose.onNodeWithTag("calendar_date_2027-01-02").performClick()
        compose.onNodeWithTag("calendar_month").assertDoesNotExist()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled()
        compose.onNodeWithTag("timeline_date_cancel").performClick()
        compose.runOnIdle { assertNull(saved); assertFalse(visible.value) }
    }

    // The API-31 highlight supports bitmap Canvas snapshots; modern shaders require a GPU.
    @Test @Config(sdk = [31]) fun glassDialogSelectsAcrossYearAndSavesOnlyAfterConfirmation() {
        val visible = mutableStateOf(true)
        var saved: LocalDate? = null
        mount(glass = true) {
            if (visible.value) ReadingTimelineDateDialog(event, false, true, null,
                { visible.value = false }, { saved = it; visible.value = false })
        }
        val collapsed = compose.onNodeWithTag("timeline_date_dialog", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("timeline_date_value").performClick()
        val expanded = compose.onNodeWithTag("timeline_date_dialog", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("The sheet stays anchored at the bottom", collapsed.bottom, expanded.bottom, 1f)
        assertTrue("The calendar expands upward", expanded.top < collapsed.top)
        assertTrue("The sheet is near the screen bottom", expanded.bottom > view.height * .8f)
        capture("glass-calendar")
        compose.onNodeWithTag("calendar_next").performClick()
        compose.onNodeWithTag("calendar_date_2027-01-02").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("timeline_date_save").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2027, 1, 2), saved) }
    }

    @Test @Config(sdk = [31]) fun failedSaveRetainsSelectionAndAllowsRetryInDarkLargeText() {
        val saving = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var attempts = 0
        var saved: LocalDate? = null
        mount(glass = true, dark = true, fontScale = 1.6f) {
            ReadingTimelineDateDialog(event, saving.value, true, error.value, {}, {
                saved = it; attempts++; saving.value = true
            })
        }
        compose.onNodeWithTag("timeline_date_value").performClick()
        capture("dark-large-calendar")
        compose.onNodeWithTag("calendar_date_2026-12-20").performClick()
        compose.onNodeWithTag("timeline_date_save").performScrollTo().performClick()
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Busy confirmation cannot submit twice", 1, attempts) }
        compose.runOnIdle { saving.value = false; error.value = "Try again" }
        compose.onNodeWithTag("timeline_date_error", useUnmergedTree = true).assertTextEquals("Try again")
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts); assertEquals(LocalDate.of(2026, 12, 20), saved) }
    }

    @Test fun detailsButtonsStayAtTheEndAndPreviewHasNoEditActions() {
        var selected: ReadingTimelineEvent? = null
        mount(dark = true, fontScale = 1.6f) {
            ReadingTimelineDetailsScreen("A long book title with many words", meta, readingTimelineEvents(meta), {},
                onEditEvent = { selected = it })
        }
        val button = compose.onNodeWithTag("timeline_edit_1_FINISH", useUnmergedTree = true)
        button.assertIsDisplayed()
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        val textBounds = compose.onNodeWithTag("timeline_event_text_1_FINISH", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(textBounds.right <= buttonBounds.left)
        capture("details-dark-large")
        button.performClick()
        compose.runOnIdle { assertEquals(event, selected) }
    }

    @Test fun previewRemainsReadOnly() {
        mount { ReadingRecordBlock(meta, readingTimelineEvents(meta), {}) }
        compose.onNodeWithTag("timeline_edit_1_FINISH", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("timeline_edit_1_START", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun material3PickerKeepsSelectionTemporary() {
        var saved = false
        mount(material3 = true) {
            ReadingTimelineDateDialog(event, false, true, null, {}, { saved = true })
        }
        compose.onNodeWithTag("timeline_date_value").performClick()
        compose.onNodeWithTag("calendar_date_2026-12-17").assertIsSelected()
        compose.onNodeWithTag("calendar_date_2026-12-18").performClick()
        compose.runOnIdle { assertFalse(saved) }
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled()
    }

    @Test fun unchangedConfirmationClosesWithoutSavingEvenWhileTheDraftIsLoading() {
        val visible = mutableStateOf(true)
        var writes = 0
        mount(glass = true) {
            if (visible.value) ReadingTimelineDateDialog(event, false, false, null,
                { visible.value = false }, { writes++ })
        }
        compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.confirm)).assertIsDisplayed()
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertFalse(visible.value); assertEquals(0, writes) }
    }

    @Test fun selectingTheOriginalDateAgainClosesWithoutWriting() {
        val visible = mutableStateOf(true)
        var writes = 0
        mount(glass = true) {
            if (visible.value) ReadingTimelineDateDialog(event, false, true, null,
                { visible.value = false }, { writes++ })
        }
        compose.onNodeWithTag("timeline_date_value").performClick()
        compose.onNodeWithTag("calendar_date_2026-12-18").performClick()
        compose.onNodeWithTag("timeline_date_value").performClick()
        compose.onNodeWithTag("calendar_date_2026-12-17").performClick()
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertFalse(visible.value); assertEquals(0, writes) }
    }

    @Test fun eInkPickerUsesTheSameSelectionFlowWithoutMotion() {
        mount(eInk = true) { ReadingTimelineDateDialog(event, false, true, null, {}, {}) }
        compose.onNodeWithTag("timeline_date_value").performClick()
        compose.onNodeWithTag("calendar_date_2026-12-17").assertIsSelected()
        compose.onNodeWithTag("calendar_previous").performClick()
        compose.onNodeWithTag("calendar_date_2026-11-20").performClick()
        compose.onNodeWithTag("timeline_date_save").assertIsEnabled()
    }

    @Test
    @Config(sdk = [31], qualifiers = "zh-rCN-w320dp-h800dp-xxhdpi")
    fun chineseDatesFitTheNarrowDialog() {
        mount(glass = true, fontScale = 1.3f) { ReadingTimelineDateDialog(event, false, true, null, {}, {}) }
        compose.onNodeWithText("2026年12月17日").assertIsDisplayed()
        capture("chinese-finish-row")
        assertDateRow("完成时间")
        compose.onNodeWithTag("timeline_date_value").performClick()
        compose.onNodeWithTag("calendar_date_2026-12-17").assertIsSelected()
        capture("chinese-narrow-calendar")
    }

    @Test
    @Config(sdk = [31], qualifiers = "zh-rCN-w320dp-h800dp-xxhdpi")
    fun startEventUsesStartTimeAtTheLeftAndItsDateAtTheRight() {
        val start = readingTimelineEvents(meta).single { it.type == ReadingTimelineEventType.START }
        mount(glass = true, fontScale = 1.6f) { ReadingTimelineDateDialog(start, false, true, null, {}, {}) }
        compose.onNodeWithText("2026年12月1日").assertIsDisplayed()
        capture("chinese-start-row")
        assertDateRow("开始时间")
    }

    private fun assertDateRow(label: String) {
        val labelNode = compose.onNodeWithTag("timeline_date_label", useUnmergedTree = true).assertTextEquals(label)
        val dateNode = compose.onNodeWithTag("timeline_date_text", useUnmergedTree = true)
        val row = compose.onNodeWithTag("timeline_date_value").fetchSemanticsNode().boundsInRoot
        val left = labelNode.fetchSemanticsNode().boundsInRoot
        val right = dateNode.fetchSemanticsNode().boundsInRoot
        assertEquals(row.left, left.left, .5f)
        assertEquals(row.right, right.right, .5f)
        assertEquals(left.center.y, right.center.y, .5f)
        assertTrue(left.right <= right.left)
        for (node in listOf(labelNode, dateNode)) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            val result = layouts.single()
            assertFalse(result.didOverflowHeight)
            // Match existing timeline layout checks: Android may round glyph bounds by half a pixel.
            assertTrue("Clipped text: ${result.layoutInput.text}",
                result.getLineLeft(0) >= -.5f && result.getLineRight(0) <= result.size.width + .5f)
        }
    }

    @Test fun statisticsModeHasNoDateSelectionAndKeepsNavigationLimit() {
        var previous = 0
        mount {
            Box {
                AppMonthCalendar(YearMonth.of(2026, 12), { previous++ }, {}, AppMonthCalendarMode.Statistics,
                    nextEnabled = false, dailyData = mapOf("2026-12-17" to 3600000L))
            }
        }
        compose.onNodeWithTag("calendar_next").assertIsNotEnabled()
        compose.onNodeWithTag("calendar_previous").performClick()
        compose.runOnIdle { assertEquals(1, previous) }
        compose.onNodeWithTag("calendar_date_2026-12-17").assertDoesNotExist()
        capture("statistics-calendar")
    }

    @Test fun statisticsModeOpensDayWhenCallbackProvided() {
        val today = LocalDate.now()
        var opened: LocalDate? = null
        mount {
            AppMonthCalendar(YearMonth.from(today), {}, {}, AppMonthCalendarMode.Statistics,
                onDateSelected = { opened = it })
        }
        compose.onNodeWithTag("calendar_date_$today").performClick()
        compose.runOnIdle { assertEquals(today, opened) }
    }
}
