package com.huangder.lumibooks.ui.bookshelf

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.domain.model.ActivityDuration
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.LiquidGlassCapability
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalLiquidGlassCapability
import com.huangder.lumibooks.ui.theme.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w1000dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDetailsMetricsTest {
    @get:Rule val compose = createComposeRule()
    private var width by mutableStateOf(320.dp)
    private var fontScale by mutableStateOf(1f)
    private var theme by mutableStateOf("lumi")
    private var dark by mutableStateOf(false)
    private var ink by mutableStateOf(false)
    private var longValues by mutableStateOf(false)
    private val book = Book(
        id = "metrics", title = "Test", author = "", filePath = "test.epub",
        coverPath = null, format = BookFormat.EPUB, lastReadTime = 0L,
        readingProgress = .42f, createdAt = 0L
    )

    private fun label(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private val coreLabels get() = listOf(
        label(R.string.book_detail_progress), label(R.string.insights_reading),
        label(R.string.insights_listening), label(R.string.book_detail_active_days)
    )
    private val coreValues get() = listOf(
        "42%", if (longValues) "2562047788015H 12M" else "1H 15M", "36M",
        if (longValues) Int.MAX_VALUE.toString() else "7"
    )
    private val auxiliaryValues get() = listOf(
        "${if (longValues) Int.MAX_VALUE else 11} ${label(R.string.book_detail_bookmarks)}",
        "${if (longValues) Int.MAX_VALUE else 23} ${label(R.string.book_detail_notes)}"
    )

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                EBookReaderTheme(
                    appTheme = theme, darkTheme = dark, dynamicColor = theme == "material3",
                    eInkMode = ink, motionPreference = MotionPreference.REDUCED
                ) {
                    // Exercise the glass card branch even on a software-only host.
                    CompositionLocalProvider(
                        LocalAppTheme provides if (ink) "lumi" else theme,
                        LocalLiquidGlassCapability provides LiquidGlassCapability(!ink, false)
                    ) {
                        Box(Modifier.width(width).testTag("metrics")) {
                            MetricGrid(
                                book, ActivityDuration(if (longValues) Long.MAX_VALUE else 4_500_000L, 2_160_000L),
                                if (longValues) Int.MAX_VALUE else 7,
                                if (longValues) Int.MAX_VALUE else 11,
                                if (longValues) Int.MAX_VALUE else 23
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun assertReadable() {
        val card = compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot
        val texts = listOf(label(R.string.book_detail_reading_statistics)) + coreLabels + coreValues + auxiliaryValues
        texts.forEach { text ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            // Compose can retain a wider paragraph than its wrap-content Text node.
            // Verify glyph bounds instead of treating that paragraph width as clipping.
            repeat(layout.lineCount) { line ->
                assertFalse("Text must not be ellipsized: $text", layout.isLineEllipsized(line))
                assertTrue("Text must fit horizontally: $text size=${layout.size} paragraph=${layout.multiParagraph.width} left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)}",
                    layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f)
                assertTrue("Text must fit vertically: $text",
                    layout.getLineBottom(line) <= layout.size.height + .5f)
            }
            val rect = bounds(text)
            assertTrue("Text must stay inside card: $text", rect.left >= card.left && rect.right <= card.right)
            assertTrue("Text must stay inside card: $text", rect.top >= card.top && rect.bottom <= card.bottom)
        }
        val rectangles = texts.map(::bounds)
        rectangles.forEachIndexed { index, rect ->
            rectangles.drop(index + 1).forEach { other ->
                assertFalse("Metric text must not overlap", rect.overlaps(other))
            }
        }
        coreValues.forEach { value ->
            val merged = compose.onNodeWithText(value).fetchSemanticsNode()
            assertEquals("TalkBack should read the value and its label together", 2,
                merged.config[SemanticsProperties.Text].size)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w1000dp-h1600dp-mdpi")
    fun standardWidthUsesFourEqualColumnsAndCompactAuxiliaryRow() {
        mount()
        assertReadable()
        val values = coreValues.map(::bounds)
        assertEquals(values[0].top, values[1].top, .5f)
        assertEquals(values[1].top, values[2].top, .5f)
        assertEquals(values[2].top, values[3].top, .5f)
        val columnSpacing = values[1].center.x - values[0].center.x
        values.zipWithNext().forEach { (left, right) ->
            assertEquals(columnSpacing, right.center.x - left.center.x, 1f)
        }
        val auxiliary = auxiliaryValues.map(::bounds)
        assertEquals(auxiliary[0].top, auxiliary[1].top, .5f)
        assertTrue(auxiliary[0].top > coreLabels.maxOf { bounds(it).bottom })
        assertTrue("Default card should be compact",
            compose.onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot.height < 180f)
    }

    @Test fun longerEnglishLabelsRemainInlineWhenThereIsEnoughWidth() {
        width = 400.dp
        mount()
        assertReadable()
        assertEquals(bounds(auxiliaryValues[0]).top, bounds(auxiliaryValues[1]).top, .5f)
        // A narrow English card can stack the auxiliary group without losing any text.
        compose.runOnIdle { width = 240.dp }
        assertReadable()
        assertTrue(bounds(auxiliaryValues[0]).bottom <= bounds(auxiliaryValues[1]).top)
    }

    @Test fun narrowLargeFontAndLongValuesRemainReadableAcrossThemes() {
        mount()
        listOf("lumi", "material3", "liquid_glass", "eink").forEach { appearance ->
            compose.runOnIdle {
                width = 180.dp
                fontScale = 2f
                longValues = true
                theme = appearance
                dark = appearance != "eink"
                ink = appearance == "eink"
            }
            assertReadable()
            val values = coreValues.map(::bounds)
            assertTrue(values[0].bottom <= values[1].top)
            assertTrue(values[1].bottom <= values[2].top)
            assertTrue(values[2].bottom <= values[3].top)
            assertTrue(bounds(auxiliaryValues[0]).bottom <= bounds(auxiliaryValues[1]).top)
        }
        // Recomposition must restore columns when the content and available space permit it.
        compose.runOnIdle { width = 480.dp; fontScale = 1f; longValues = false }
        assertReadable()
        assertEquals(bounds(coreValues[0]).top, bounds(coreValues[3]).top, .5f)
    }
}
