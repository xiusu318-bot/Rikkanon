package me.rerere.rikkahub.ui.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.UIHorizontalAlignment
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.model.UIVerticalAlignment
import me.rerere.rikkahub.ui.components.ai.chatInputHaze
import me.rerere.rikkahub.ui.theme.CustomTheme

val LocalActiveUIPreset = compositionLocalOf<UIPreset?> { null }

val LocalChatHazeState = compositionLocalOf<HazeState?> { null }

/** 当前子树是否处于 `pointerEvents: "none"` 手势穿透状态。 */
val LocalUIPointerPassthrough = compositionLocalOf { false }

fun Settings.resolveActiveUIPreset(): UIPreset? {
    if (!enableCustomUi) return null
    // 只读保存的编译结果；编译在保存时与 UIPresetCompileSync 中完成，不在界面绘制过程中进行
    return uiPresets.firstOrNull { it.enabled }
}

fun applyUIPresetToColorScheme(
    baseScheme: ColorScheme,
    preset: UIPreset?,
    defaultDark: Boolean,
): Pair<ColorScheme, Boolean> {
    val bg = preset?.backgroundSection?.takeIf { it.enabled } ?: return baseScheme to defaultDark
    // 宿主只提供两种通用能力：种子色官方配色 + 逐槽位指定；如何推导配色由主题代码自己决定
    val dark = bg.dark
        ?: bg.colorOverrides["background"]?.let(::parseHexColorOrNull)?.let { it.luminance() < 0.5f }
        ?: defaultDark
    val seed = parseHexColorOrNull(bg.seedColorHex)
    var scheme = if (seed != null) {
        CustomTheme(primaryColorArgb = seed.toArgb().toLong() and 0xFFFFFFFFL).generateColorScheme(dark = dark)
    } else baseScheme
    scheme = scheme.withUIColorOverrides(bg.colorOverrides)
    return scheme to dark
}

fun resolveUIColor(
    spec: String,
    colorScheme: ColorScheme,
    defaultColor: Color = Color.Unspecified,
): Color {
    val trimmed = spec.trim()
    if (trimmed.isEmpty()) return defaultColor
    semanticUIColor(trimmed, colorScheme)?.let { return it }
    return parseHexColorOrNull(trimmed) ?: defaultColor
}

fun parseHexColorOrNull(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.isEmpty() || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return runCatching {
        when (hex.length) {
            3 -> {
                val r = hex[0].digitToInt(16) * 17
                val g = hex[1].digitToInt(16) * 17
                val b = hex[2].digitToInt(16) * 17
                Color(r, g, b, 255)
            }
            4 -> {
                val a = hex[0].digitToInt(16) * 17
                val r = hex[1].digitToInt(16) * 17
                val g = hex[2].digitToInt(16) * 17
                val b = hex[3].digitToInt(16) * 17
                Color(r, g, b, a)
            }
            6 -> {
                val rgb = hex.toLong(16).toInt()
                Color(
                    red = (rgb shr 16) and 0xFF,
                    green = (rgb shr 8) and 0xFF,
                    blue = rgb and 0xFF,
                    alpha = 0xFF,
                )
            }
            8 -> {
                val argb = hex.toLong(16)
                Color(
                    alpha = ((argb shr 24) and 0xFF).toInt(),
                    red = ((argb shr 16) and 0xFF).toInt(),
                    green = ((argb shr 8) and 0xFF).toInt(),
                    blue = (argb and 0xFF).toInt(),
                )
            }
            else -> null
        }
    }.getOrNull()
}

fun UIHorizontalAlignment.toComposeHorizontalAlignment(): Alignment.Horizontal = when (this) {
    UIHorizontalAlignment.START -> Alignment.Start
    UIHorizontalAlignment.CENTER -> Alignment.CenterHorizontally
    UIHorizontalAlignment.END -> Alignment.End
    UIHorizontalAlignment.SPACE_BETWEEN -> Alignment.Start
}

fun UIHorizontalAlignment.toHorizontalArrangement(spacing: Dp): Arrangement.Horizontal = when (this) {
    UIHorizontalAlignment.START -> Arrangement.spacedBy(spacing, Alignment.Start)
    UIHorizontalAlignment.CENTER -> Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
    UIHorizontalAlignment.END -> Arrangement.spacedBy(spacing, Alignment.End)
    UIHorizontalAlignment.SPACE_BETWEEN -> Arrangement.SpaceBetween
}

fun UIVerticalAlignment.toComposeVerticalAlignment(): Alignment.Vertical = when (this) {
    UIVerticalAlignment.TOP -> Alignment.Top
    UIVerticalAlignment.CENTER -> Alignment.CenterVertically
    UIVerticalAlignment.BOTTOM -> Alignment.Bottom
}

/** `Box` / `Shader` / `Popup` 层叠容器的 9 宫格内容对齐；未显式写 `align` / `vAlign` 时保持原版 `TopStart` 叠放，写了 `align: "center"` 等即按对应九宫格对齐。 */
fun UIMessageNode.toBoxAlignment(): Alignment {
    if (!hasExplicitBoxAlign) return Alignment.TopStart
    val h = horizontalAlignment
    val v = verticalAlignment
    return when (v) {
        UIVerticalAlignment.TOP -> when (h) {
            UIHorizontalAlignment.CENTER -> Alignment.TopCenter
            UIHorizontalAlignment.END -> Alignment.TopEnd
            else -> Alignment.TopStart
        }
        UIVerticalAlignment.CENTER -> when (h) {
            UIHorizontalAlignment.CENTER -> Alignment.Center
            UIHorizontalAlignment.END -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
        UIVerticalAlignment.BOTTOM -> when (h) {
            UIHorizontalAlignment.CENTER -> Alignment.BottomCenter
            UIHorizontalAlignment.END -> Alignment.BottomEnd
            else -> Alignment.BottomStart
        }
    }
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun isUICutCornerShape(): Boolean =
    LocalActiveUIPreset.current?.globalSection?.let { it.enabled && it.shapeStyle == "cut" } == true

fun uiCornerShape(
    topStart: Dp,
    topEnd: Dp = topStart,
    bottomEnd: Dp = topStart,
    bottomStart: Dp = topStart,
    cut: Boolean = false,
): CornerBasedShape = if (cut) {
    CutCornerShape(topStart = topStart, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = bottomStart)
} else {
    RoundedCornerShape(topStart = topStart, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = bottomStart)
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun uiCornerShape(corner: Dp): CornerBasedShape =
    uiCornerShape(topStart = corner, cut = isUICutCornerShape())

fun UIMessageNode.toCornerShape(cut: Boolean = false): CornerBasedShape = uiCornerShape(
    topStart = cornerTopStartDp.coerceAtLeast(0f).dp,
    topEnd = cornerTopEndDp.coerceAtLeast(0f).dp,
    bottomEnd = cornerBottomEndDp.coerceAtLeast(0f).dp,
    bottomStart = cornerBottomStartDp.coerceAtLeast(0f).dp,
    cut = cut,
)

fun UIMessageNode.toAvatarShape(cut: Boolean = false): Shape =
    if (avatarCornerDp < 0f) CircleShape else uiCornerShape(avatarCornerDp.dp, cut = cut)

/**
 * 安全形状裁剪：
 * - 对矩形与四角相等的 [RoundedCornerShape]（底层走 `setRectOutline` / `setRoundRectOutline` 解析式命中判断），保持原生 [Modifier.clip]；
 * - 对 [CutCornerShape] 与四角不等的 [RoundedCornerShape]（底层会转为 `Outline.Generic(Path)` 走 `ShapeContainingUtilKt.isInPath`），
 *   改用纯绘制阶段 `clipPath` 裁剪，避免容器局部坐标超过 `8192px` 时 Skia `Path.op(Intersect)` 对 `±0.005f` 探测框的浮点精度失效
 *   导致长消息底部操作按钮、长代码块或长表单下半截无法点击。
 */
fun Modifier.uiSafeClip(shape: Shape): Modifier {
    val usesHardwareSafeOutline = shape === RectangleShape ||
        (shape is RoundedCornerShape &&
            shape.topStart == shape.topEnd &&
            shape.topEnd == shape.bottomEnd &&
            shape.bottomEnd == shape.bottomStart)
    if (usesHardwareSafeOutline) {
        return this.clip(shape)
    }
    return this.drawWithCache {
        when (val outline = shape.createOutline(size, layoutDirection, this@drawWithCache)) {
            is Outline.Rectangle -> onDrawWithContent {
                clipRect(outline.rect.left, outline.rect.top, outline.rect.right, outline.rect.bottom) {
                    this@onDrawWithContent.drawContent()
                }
            }
            is Outline.Rounded -> {
                val path = Path().apply { addRoundRect(outline.roundRect) }
                onDrawWithContent {
                    clipPath(path) { this@onDrawWithContent.drawContent() }
                }
            }
            is Outline.Generic -> onDrawWithContent {
                clipPath(outline.path) { this@onDrawWithContent.drawContent() }
            }
        }
    }
}

private fun Modifier.applyUIWindowInsets(spec: String): Modifier {
    if (spec.isBlank()) return this
    var m = this
    val tokens = spec.trim().split(Regex("""[\s,+|]+""")).map { it.lowercase() }
    for (t in tokens) {
        m = when (t) {
            "status", "statusbars" -> m.statusBarsPadding()
            "nav", "navigationbars" -> m.navigationBarsPadding()
            "ime" -> m.imePadding()
            "safe", "systembars" -> m.systemBarsPadding()
            else -> m
        }
    }
    return m
}

@Composable
fun Modifier.applyUINodeVisuals(
    node: UIMessageNode,
    colorScheme: ColorScheme,
    hazeState: HazeState?,
    displaySetting: DisplaySetting,
    defaultBubbleAlpha: Float = 1f,
    /** 变换类动画（graphicsLayer），放在静态位移 / 缩放 / 旋转之后。 */
    layer: Modifier = Modifier,
    /** 代替静态底色绘制的背景（着色器、底色动画）；null = 按节点的静态底色。 */
    background: Modifier? = null,
    /** 对本节点及子内容施加的后处理滤镜（如声明了 `uniform shader content;` 的实时折射/色散着色器）。 */
    contentEffect: Modifier = Modifier,
    /** 子节点内边距之外额外叠加的安全区内边距（如顶栏根节点背景铺进状态栏，内容避开状态栏）。 */
    contentInsets: Modifier = Modifier,
    /** 是否把节点的 padding 直接加在外层 Modifier 上；列表类宿主块（如 Host("messages")）可关闭并改作 contentPadding。 */
    applyPadding: Boolean = true,
): Modifier {
    var mod = this

    if (node.offsetXDp != 0f || node.offsetYDp != 0f) {
        mod = mod.offset(x = node.offsetXDp.dp, y = node.offsetYDp.dp)
    }
    if (node.scale != 1.0f && node.scale >= 0f) {
        mod = mod.scale(node.scale)
    }
    if (node.rotationDeg != 0f) {
        mod = mod.rotate(node.rotationDeg)
    }
    mod = mod.then(layer)
    mod = mod.then(contentEffect)

    if (node.blendMode.isNotEmpty()) {
        val bm = parseUIBlendMode(node.blendMode)
        if (bm != BlendMode.SrcOver) {
            val blendPaint = remember(bm) { Paint().apply { blendMode = bm } }
            mod = mod.drawWithContent {
                if (size.width > 0f && size.height > 0f) {
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(Offset.Zero, size), blendPaint)
                        drawContent()
                        canvas.restore()
                    }
                } else {
                    drawContent()
                }
            }
        }
    }

    val shape = node.toCornerShape(cut = isUICutCornerShape())
    val hasCorner = node.cornerTopStartDp > 0f || node.cornerTopEndDp > 0f ||
        node.cornerBottomEndDp > 0f || node.cornerBottomStartDp > 0f

    if (node.shadowElevationDp > 0f) {
        mod = mod.shadow(elevation = node.shadowElevationDp.dp, shape = shape, clip = false)
    }

    val bgColorRaw = resolveUIColor(node.backgroundColorHex, colorScheme)
    val effectiveAlpha = (node.opacity.coerceIn(0f, 1f) * defaultBubbleAlpha.coerceIn(0.1f, 1f)).coerceIn(0f, 1f)
    val bgColor = if (bgColorRaw != Color.Unspecified) {
        bgColorRaw.copy(alpha = bgColorRaw.alpha * effectiveAlpha)
    } else Color.Unspecified
    val gradEndRaw = resolveUIColor(node.gradientEndColorHex, colorScheme)
    val gradEnd = if (gradEndRaw != Color.Unspecified) {
        gradEndRaw.copy(alpha = gradEndRaw.alpha * effectiveAlpha)
    } else Color.Unspecified

    val shouldClip = node.clip ?: (hasCorner || bgColor != Color.Unspecified || node.enableHazeBlur || background != null)
    if (shouldClip) {
        mod = mod.uiSafeClip(shape)
    }

    if (background != null) {
        mod = mod.then(background)
    } else if (node.enableHazeBlur && hazeState != null) {
        val hazeContainerColor = if (bgColor != Color.Unspecified) bgColor else colorScheme.surfaceContainerHigh
        mod = mod.chatInputHaze(
            hazeState = hazeState,
            displaySetting = displaySetting.copy(enableBlurEffect = true),
            containerColor = hazeContainerColor,
            shape = shape,
        )
    } else if (bgColor != Color.Unspecified && gradEnd != Color.Unspecified) {
        val angle = node.gradientAngleDeg
        mod = mod.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val brush = sizedLinearGradient(bgColor, gradEnd, angle, size.width, size.height)
            onDrawBehind {
                drawOutline(outline, brush)
            }
        }
    } else if (bgColor != Color.Unspecified) {
        mod = mod.background(color = bgColor, shape = shape)
    }

    if (node.borderWidthDp > 0f) {
        val borderColor = resolveUIColor(
            spec = node.borderColorHex,
            colorScheme = colorScheme,
            defaultColor = colorScheme.outlineVariant,
        )
        mod = mod.border(width = node.borderWidthDp.dp, color = borderColor, shape = shape)
    }

    if (node.blurRadiusDp > 0f) mod = mod.blur(node.blurRadiusDp.dp)
    if (node.alpha < 1f) mod = mod.alpha(node.alpha.coerceIn(0f, 1f))

    when (node.scroll.lowercase()) {
        "vertical", "y" -> mod = mod.verticalScroll(rememberScrollState())
        "horizontal", "x" -> mod = mod.horizontalScroll(rememberScrollState())
    }

    mod = mod.then(contentInsets)
    if (node.insets.isNotEmpty()) {
        mod = mod.applyUIWindowInsets(node.insets)
    }

    if (applyPadding && (node.paddingStartDp > 0f || node.paddingTopDp > 0f || node.paddingEndDp > 0f || node.paddingBottomDp > 0f)) {
        mod = mod.padding(
            start = node.paddingStartDp.coerceAtLeast(0f).dp,
            top = node.paddingTopDp.coerceAtLeast(0f).dp,
            end = node.paddingEndDp.coerceAtLeast(0f).dp,
            bottom = node.paddingBottomDp.coerceAtLeast(0f).dp,
        )
    }

    return mod
}
