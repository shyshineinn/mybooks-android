package com.huangder.lumibooks.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.huangder.lumibooks.domain.model.AppUiIconStyle
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.icons.ControlIcon
import com.huangder.lumibooks.ui.icons.LocalAppUiIconStyle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalCardOutlinesEnabled
import com.huangder.lumibooks.ui.theme.LocalEInkMode
import com.huangder.lumibooks.ui.theme.vectorCardOutline
import com.huangder.lumibooks.ui.theme.LocalIsDarkTheme
import com.huangder.lumibooks.ui.theme.LocalLiquidGlassCapability

/**
 * Shared container for grouped information and metrics.
 * The outline preference controls the extra edge; glass retains its optical rim.
 * Pass zero content padding when child rows already own their touch-area padding.
 */
@Composable
fun InformationCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(AppSpace.md),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AppSpace.sm),
    titleIcon: ImageVector? = null,
    titleIconTint: Color = AppColors.Accent,
    content: @Composable ColumnScope.() -> Unit
) {
    val eInk = LocalEInkMode.current
    val dark = LocalIsDarkTheme.current
    val glass = !eInk && LocalAppTheme.current == "liquid_glass" &&
        LocalLiquidGlassCapability.current.supported && LocalLiquidGlassLayer.current < 3
    val shape = AppRoundedCornerShape(if (eInk) AppRadius.sm else AppRadius.lg)
    val highContrast = LocalCardOutlinesEnabled.current
    val borderColor = when {
        eInk -> Color.Black
        dark -> Color.White
        else -> Color.Black
    }
    // Keep the vector edge outside elevation/glass layers so scrolling does not
    // resample the high-contrast stroke together with a cached shadow texture.
    val decoration = Modifier
        .then(
            if (eInk || highContrast) {
                Modifier.vectorCardOutline(if (glass) 0.5.dp else 1.dp, borderColor, shape)
            } else Modifier
        )
        .then(
            if (eInk) Modifier else Modifier.shadow(
                elevation = 4.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.18f else 0.06f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.24f else 0.08f)
            )
        )

    // This card owns the optional outline, avoiding a second global border.
    // Restore the preference for children, which may include their own controls.
    CompositionLocalProvider(LocalCardOutlinesEnabled provides false) {
        LiquidGlassSurface(
            shape = shape,
            fallbackColor = AppColors.CardBg,
            modifier = modifier.fillMaxWidth(),
            decorationModifier = decoration,
            contentAlignment = Alignment.TopStart
        ) {
            CompositionLocalProvider(LocalCardOutlinesEnabled provides highContrast) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(shape)
                ) {
                    if (!title.isNullOrBlank()) {
                        InformationCardHeading(
                            title, titleIcon,
                            Modifier.padding(horizontal = AppSpace.md).padding(top = AppSpace.md),
                            iconTint = titleIconTint
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(contentPadding),
                        verticalArrangement = verticalArrangement,
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
internal fun InformationCardHeading(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    iconTint: Color = AppColors.Accent
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) {
            val style = LocalAppUiIconStyle.current
            val glyphStyle = if (style == AppUiIconStyle.FILLED_COLORFUL) AppUiIconStyle.FILLED_MONOCHROME else style
            ControlIcon(AppIcons.styled(icon, glyphStyle), null, Modifier.size(17.dp),
                tint = if (style.isColorful && !LocalEInkMode.current) iconTint else AppColors.TextPrimary)
        }
        Text(title, Modifier.semantics { heading() }, fontSize = AppType.Body,
            fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
    }
}
