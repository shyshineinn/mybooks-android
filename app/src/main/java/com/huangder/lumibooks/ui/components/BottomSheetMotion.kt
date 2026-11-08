package com.huangder.lumibooks.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.animation.VisualMotion.spring
import com.huangder.lumibooks.ui.animation.VisualMotion.tween
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
import kotlin.math.min

suspend fun Animatable<Float, AnimationVector1D>.animateBottomSheetIn() {
    animateTo(
        targetValue = 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 500f,
            visibilityThreshold = 0.001f
        )
    )
}

suspend fun Animatable<Float, AnimationVector1D>.animateBottomSheetOut() {
    animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
    )
}

/**
 * 底部 Sheet 的进出动效。
 *
 * 规范 §7.1：Sheet 进入 `260ms` 或无过冲弹簧，退出 `200-220ms`。
 * 这里与本文件的抽屉族同源：入场 `spring(NoBouncy, 500)`，退场 `tween(220)`。
 * 对话框宿主里的底部弹窗也用这套，避免同一族 Sheet 出现两种手感。
 */
fun bottomSheetEnterTransition(): EnterTransition =
    scaleIn(
        initialScale = 0.86f,
        animationSpec = spring(stiffness = 500f),
        transformOrigin = TransformOrigin(0.5f, 1f)
    ) + slideInVertically(
        initialOffsetY = { it },
        animationSpec = spring(stiffness = 500f)
    ) + fadeIn(tween(120))

fun bottomSheetExitTransition(): ExitTransition =
    scaleOut(
        targetScale = 0.86f,
        animationSpec = tween(220),
        transformOrigin = TransformOrigin(0.5f, 1f)
    ) + slideOutVertically(
        targetOffsetY = { it },
        animationSpec = tween(220)
    ) + fadeOut(tween(200))

/** Applies the same predictive-back scale distances used by Material 3 ModalBottomSheet. */
@Composable
fun Modifier.materialBottomSheetMotion(
    entryOffset: Float,
    predictiveBackProgress: Float,
    alpha: Float = 1f,
    /** Tablet card sheets (see adaptiveSheetFrame); panels that stay docked pass false. */
    centeredCard: Boolean = usesCardSheets()
): Modifier {
    val isLiquidGlass = LocalAppTheme.current == "liquid_glass"
    val motionEnabled = LocalMotionEnabled.current
    return graphicsLayer {
        val progress = predictiveBackProgress.coerceIn(0f, 1f)
        if (centeredCard && motionEnabled) {
            // Centered cards rise a short distance and fade like hosted dialogs instead of
            // travelling their full height from the screen edge.
            val entry = entryOffset.coerceIn(0f, 1f)
            translationY = entry * size.height * 0.10f + min(24.dp.toPx(), size.height) * progress
            this.alpha = alpha.coerceIn(0f, 1f) * (1f - entry)
            val entryScale = 1f - entry * 0.10f
            scaleX = if (size.width > 0f) {
                entryScale * (1f - min(48.dp.toPx(), size.width) * progress / size.width)
            } else {
                entryScale
            }
            scaleY = if (size.height > 0f) {
                entryScale * (1f - min(24.dp.toPx(), size.height) * progress / size.height)
            } else {
                entryScale
            }
            transformOrigin = TransformOrigin.Center
            return@graphicsLayer
        }
        if (!motionEnabled) {
            translationY = 0f
            this.alpha = alpha.coerceIn(0f, 1f) *
                (1f - entryOffset.coerceIn(0f, 1f))
            scaleX = 1f
            scaleY = 1f
            transformOrigin = TransformOrigin(0.5f, 1f)
            return@graphicsLayer
        }
        if (!isLiquidGlass) {
            val entry = entryOffset.coerceIn(0f, 1f)
            val curvedEntry = 1f - FastOutSlowInEasing.transform(1f - entry)
            translationY = curvedEntry * size.height + min(24.dp.toPx(), size.height) * progress
            this.alpha = alpha.coerceIn(0f, 1f)
            scaleX = 1f
            scaleY = 1f
            transformOrigin = TransformOrigin(0.5f, 1f)
            return@graphicsLayer
        }

        val entry = entryOffset.coerceIn(-0.06f, 1.08f)
        val hiddenProgress = entryOffset.coerceIn(0f, 1f)
        translationY = entry * size.height
        this.alpha = alpha.coerceIn(0f, 1f) * (1f - hiddenProgress)

        // Entry overshoot is shared by translation and scale for floating glass sheets.
        val entryScaleX = 1f - entry * 0.14f
        val entryScaleY = 1f - entry * 0.14f
        scaleX = if (size.width > 0f) {
            entryScaleX * (1f - min(48.dp.toPx(), size.width) * progress / size.width)
        } else {
            entryScaleX
        }
        scaleY = if (size.height > 0f) {
            entryScaleY * (1f - min(24.dp.toPx(), size.height) * progress / size.height)
        } else {
            entryScaleY
        }
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}

/**
 * 底部抽屉外壳：容器 + 关闭请求收敛在这里，页面只负责内容。
 *
 * 与书架的标签弹窗（BookTagBottomSheet）同构：
 * 玻璃主题走 LiquidGlassDialog（对话框宿主层，因此浮在底部标签栏之上），
 * 其余主题走 ModalBottomSheet（真实窗口，同样在标签栏之上，且随键盘调整高度）。
 * 页内 inline 的写法会被 NavGraph 层后绘制的 FloatingTabBar 压住，不要用。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassBottomSheetScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestDismiss: () -> Boolean = { true },
    maxHeightFraction: Float = 0.82f,
    shape: Shape = AppRoundedCornerShape(AppRadius.capsule),
    fallbackColor: Color = AppColors.CardBg,
    contentPadding: PaddingValues = PaddingValues(horizontal = AppSpace.lg, vertical = AppSpace.md),
    glassContentScrimColor: Color = fallbackColor.copy(alpha = 0.82f),
    content: @Composable ColumnScope.(requestDismiss: () -> Unit) -> Unit
) {
    val isLiquidGlass = LocalAppTheme.current == "liquid_glass"
    // 返回 false 表示本次关闭请求被调用方拦下（例如有未保存的修改）。
    val dismiss = { if (onRequestDismiss()) onDismiss() }

    val sheetContent: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = if (maxHeight == Dp.Infinity) {
                LocalConfiguration.current.screenHeightDp.dp
            } else {
                maxHeight
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .heightIn(max = available * maxHeightFraction)
                    .navigationBarsPadding()
                    .padding(contentPadding),
                content = { content { dismiss() } }
            )
        }
    }

    if (isLiquidGlass) {
        LiquidGlassDialog(
            onDismissRequest = { dismiss() },
            modifier = modifier.imePadding(),
            alignment = Alignment.BottomCenter,
            shape = shape,
            contentScrimColor = glassContentScrimColor,
            backgroundScrimColor = Color.Black.copy(alpha = 0.12f),
            motion = LiquidGlassDialogMotion.BOTTOM_SHEET
        ) {
            sheetContent()
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { dismiss() },
            sheetState = sheetState,
            containerColor = fallbackColor,
            contentColor = AppColors.TextPrimary,
            scrimColor = Color.Black.copy(alpha = 0.12f),
            shape = AppRoundedCornerShape(AppRadius.capsule)
        ) {
            sheetContent()
        }
    }
}
