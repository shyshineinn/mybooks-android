package com.huangder.lumibooks.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightPaletteColorPickerTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun resetPreviewsTheSlotsDefaultAndOnlyConfirmSavesIt() {
        var saved: String? = null
        compose.setContent {
            EBookReaderTheme(appTheme = "lumi") {
                ThemeColorDialog(
                    initialColorHex = "#13579B",
                    dialogTitle = context.getString(R.string.highlight_palette_set_color),
                    resetText = context.getString(R.string.highlight_reset_default),
                    resetColorHex = "#D6C58D",
                    onDismiss = {},
                    onConfirm = { saved = it }
                )
            }
        }

        val hex = compose.onNode(hasSetTextAction())
        val confirm = compose.onNodeWithText(context.getString(R.string.confirm))
        hex.assertTextContains("#13579B")
        hex.performTextReplacement("123")
        confirm.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.highlight_reset_default))
            .performScrollTo().performClick()
        hex.assertTextContains("#D6C58D")
        compose.runOnIdle { assertNull(saved) }
        confirm.performScrollTo().performClick()
        compose.runOnIdle { assertEquals("#D6C58D", saved) }
    }
}
