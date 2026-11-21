package me.rerere.rikkahub.ui.custom

import android.content.Context
import android.util.Log
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.UIGlobalSection
import me.rerere.rikkahub.data.model.UITextStyleOverride
import me.rerere.rikkahub.ui.theme.loadFilesDirFontFamily
import me.rerere.rikkahub.ui.theme.resolveChatFontFamily

private const val TAG = "UIGlobalStyle"

/**
 * 界面自定义设置的全局字体（`ui.font({ family })`）；未设置或总开关关闭时为 null。
 * 聊天字体设置为「默认」时，消息正文也跟随它。
 */
val LocalUIGlobalFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/** Material 3 全部颜色槽位名（`ui.theme({ colors })` 可用的键）。 */
val UI_COLOR_SLOTS: List<String> = listOf(
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
    "surfaceTint", "inverseSurface", "inverseOnSurface",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim",
    "surfaceBright", "surfaceDim", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    "surfaceContainerLow", "surfaceContainerLowest",
    "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
    "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
    "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
)

/** Material 3 全部文字样式名（`ui.font({ styles })` 可用的键）。 */
val UI_TEXT_STYLES: List<String> = listOf("display", "headline", "title", "body", "label").flatMap { group ->
    listOf("Large", "Medium", "Small").map { size -> group + size }
}.let { base -> base + base.map { it + "Emphasized" } }

/** Material 3 全部形状等级名（`ui.shapes` 可用的键）。 */
val UI_SHAPE_KEYS: List<String> = listOf(
    "extraSmall", "small", "medium", "large", "extraLarge",
    "largeIncreased", "extraLargeIncreased", "extraExtraLarge",
)

private val SIMPLE_FONT_FAMILIES = setOf("default", "sans-serif", "sans", "serif", "monospace", "mono", "cursive", "chat")

/** 字体取值是否合法（不检查 `file:` 指向的文件是否存在）。 */
fun isValidUIFontFamilySpec(spec: String): Boolean {
    val s = spec.trim()
    if (s.lowercase() in SIMPLE_FONT_FAMILIES) return true
    return s.startsWith("file:") && s.removePrefix("file:").isNotBlank()
}

/** 解析字体取值。`file:` 文件不存在或无法加载时写日志并回退系统默认字体。 */
fun resolveUIFontFamily(context: Context, spec: String, displaySetting: DisplaySetting): FontFamily {
    val s = spec.trim()
    return when (s.lowercase()) {
        "default" -> FontFamily.Default
        "sans-serif", "sans" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "monospace", "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        "chat" -> displaySetting.resolveChatFontFamily(context)
        else -> loadFilesDirFontFamily(context, s.removePrefix("file:").trim()) ?: run {
            Log.w(TAG, "font file not found or invalid: $s")
            FontFamily.Default
        }
    }
}

/** 按全局外观生成 Typography；[resolveFamily] 把字体取值转成 FontFamily。 */
fun buildUITypography(
    base: Typography,
    section: UIGlobalSection,
    resolveFamily: (String) -> FontFamily,
): Typography {
    val globalFamily = section.fontFamily.takeIf { it.isNotBlank() }?.let(resolveFamily)
    fun TextUnit.scaled(): TextUnit = if (isSpecified && section.fontScale != 1f) this * section.fontScale else this
    fun style(name: String, s: TextStyle): TextStyle {
        var r = s.copy(
            fontSize = s.fontSize.scaled(),
            lineHeight = s.lineHeight.scaled(),
            fontFamily = globalFamily ?: s.fontFamily,
            letterSpacing = section.letterSpacingEm?.em ?: s.letterSpacing,
        )
        val o: UITextStyleOverride = section.textStyles[name] ?: return r
        r = r.copy(
            fontSize = o.size?.sp ?: r.fontSize,
            lineHeight = o.lineHeight?.sp ?: r.lineHeight,
            fontWeight = o.weight?.let { FontWeight(it) } ?: r.fontWeight,
            letterSpacing = o.letterSpacingEm?.em ?: r.letterSpacing,
            fontFamily = o.fontFamily?.let(resolveFamily) ?: r.fontFamily,
            fontStyle = o.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: r.fontStyle,
        )
        return r
    }
    return base.copy(
        displayLarge = style("displayLarge", base.displayLarge),
        displayMedium = style("displayMedium", base.displayMedium),
        displaySmall = style("displaySmall", base.displaySmall),
        headlineLarge = style("headlineLarge", base.headlineLarge),
        headlineMedium = style("headlineMedium", base.headlineMedium),
        headlineSmall = style("headlineSmall", base.headlineSmall),
        titleLarge = style("titleLarge", base.titleLarge),
        titleMedium = style("titleMedium", base.titleMedium),
        titleSmall = style("titleSmall", base.titleSmall),
        bodyLarge = style("bodyLarge", base.bodyLarge),
        bodyMedium = style("bodyMedium", base.bodyMedium),
        bodySmall = style("bodySmall", base.bodySmall),
        labelLarge = style("labelLarge", base.labelLarge),
        labelMedium = style("labelMedium", base.labelMedium),
        labelSmall = style("labelSmall", base.labelSmall),
        displayLargeEmphasized = style("displayLargeEmphasized", base.displayLargeEmphasized),
        displayMediumEmphasized = style("displayMediumEmphasized", base.displayMediumEmphasized),
        displaySmallEmphasized = style("displaySmallEmphasized", base.displaySmallEmphasized),
        headlineLargeEmphasized = style("headlineLargeEmphasized", base.headlineLargeEmphasized),
        headlineMediumEmphasized = style("headlineMediumEmphasized", base.headlineMediumEmphasized),
        headlineSmallEmphasized = style("headlineSmallEmphasized", base.headlineSmallEmphasized),
        titleLargeEmphasized = style("titleLargeEmphasized", base.titleLargeEmphasized),
        titleMediumEmphasized = style("titleMediumEmphasized", base.titleMediumEmphasized),
        titleSmallEmphasized = style("titleSmallEmphasized", base.titleSmallEmphasized),
        bodyLargeEmphasized = style("bodyLargeEmphasized", base.bodyLargeEmphasized),
        bodyMediumEmphasized = style("bodyMediumEmphasized", base.bodyMediumEmphasized),
        bodySmallEmphasized = style("bodySmallEmphasized", base.bodySmallEmphasized),
        labelLargeEmphasized = style("labelLargeEmphasized", base.labelLargeEmphasized),
        labelMediumEmphasized = style("labelMediumEmphasized", base.labelMediumEmphasized),
        labelSmallEmphasized = style("labelSmallEmphasized", base.labelSmallEmphasized),
    )
}

/** 按全局外观生成 Shapes；没写角半径也没改风格时返回 null（不改宿主默认形状）。 */
fun buildUIShapes(base: Shapes, section: UIGlobalSection): Shapes? {
    val cut = section.shapeStyle == "cut"
    if (section.shapeCorners.isEmpty() && !cut) return null
    fun shape(name: String, original: CornerBasedShape): CornerBasedShape {
        val radius = section.shapeCorners[name]
        return when {
            radius != null && cut -> CutCornerShape(radius.dp)
            radius != null -> RoundedCornerShape(radius.dp)
            cut -> CutCornerShape(
                topStart = original.topStart,
                topEnd = original.topEnd,
                bottomEnd = original.bottomEnd,
                bottomStart = original.bottomStart,
            )
            else -> original
        }
    }
    return base.copy(
        extraSmall = shape("extraSmall", base.extraSmall),
        small = shape("small", base.small),
        medium = shape("medium", base.medium),
        large = shape("large", base.large),
        extraLarge = shape("extraLarge", base.extraLarge),
        largeIncreased = shape("largeIncreased", base.largeIncreased),
        extraLargeIncreased = shape("extraLargeIncreased", base.extraLargeIncreased),
        extraExtraLarge = shape("extraExtraLarge", base.extraExtraLarge),
    )
}

/** 把 `ui.theme({ colors })` 指定的槽位写入配色（编译时已校验槽位名与颜色值）。 */
fun ColorScheme.withUIColorOverrides(overrides: Map<String, String>): ColorScheme {
    if (overrides.isEmpty()) return this
    fun c(name: String, current: Color): Color = overrides[name]?.let(::parseHexColorOrNull) ?: current
    return copy(
        primary = c("primary", primary),
        onPrimary = c("onPrimary", onPrimary),
        primaryContainer = c("primaryContainer", primaryContainer),
        onPrimaryContainer = c("onPrimaryContainer", onPrimaryContainer),
        inversePrimary = c("inversePrimary", inversePrimary),
        secondary = c("secondary", secondary),
        onSecondary = c("onSecondary", onSecondary),
        secondaryContainer = c("secondaryContainer", secondaryContainer),
        onSecondaryContainer = c("onSecondaryContainer", onSecondaryContainer),
        tertiary = c("tertiary", tertiary),
        onTertiary = c("onTertiary", onTertiary),
        tertiaryContainer = c("tertiaryContainer", tertiaryContainer),
        onTertiaryContainer = c("onTertiaryContainer", onTertiaryContainer),
        background = c("background", background),
        onBackground = c("onBackground", onBackground),
        surface = c("surface", surface),
        onSurface = c("onSurface", onSurface),
        surfaceVariant = c("surfaceVariant", surfaceVariant),
        onSurfaceVariant = c("onSurfaceVariant", onSurfaceVariant),
        surfaceTint = c("surfaceTint", surfaceTint),
        inverseSurface = c("inverseSurface", inverseSurface),
        inverseOnSurface = c("inverseOnSurface", inverseOnSurface),
        error = c("error", error),
        onError = c("onError", onError),
        errorContainer = c("errorContainer", errorContainer),
        onErrorContainer = c("onErrorContainer", onErrorContainer),
        outline = c("outline", outline),
        outlineVariant = c("outlineVariant", outlineVariant),
        scrim = c("scrim", scrim),
        surfaceBright = c("surfaceBright", surfaceBright),
        surfaceDim = c("surfaceDim", surfaceDim),
        surfaceContainer = c("surfaceContainer", surfaceContainer),
        surfaceContainerHigh = c("surfaceContainerHigh", surfaceContainerHigh),
        surfaceContainerHighest = c("surfaceContainerHighest", surfaceContainerHighest),
        surfaceContainerLow = c("surfaceContainerLow", surfaceContainerLow),
        surfaceContainerLowest = c("surfaceContainerLowest", surfaceContainerLowest),
        primaryFixed = c("primaryFixed", primaryFixed),
        primaryFixedDim = c("primaryFixedDim", primaryFixedDim),
        onPrimaryFixed = c("onPrimaryFixed", onPrimaryFixed),
        onPrimaryFixedVariant = c("onPrimaryFixedVariant", onPrimaryFixedVariant),
        secondaryFixed = c("secondaryFixed", secondaryFixed),
        secondaryFixedDim = c("secondaryFixedDim", secondaryFixedDim),
        onSecondaryFixed = c("onSecondaryFixed", onSecondaryFixed),
        onSecondaryFixedVariant = c("onSecondaryFixedVariant", onSecondaryFixedVariant),
        tertiaryFixed = c("tertiaryFixed", tertiaryFixed),
        tertiaryFixedDim = c("tertiaryFixedDim", tertiaryFixedDim),
        onTertiaryFixed = c("onTertiaryFixed", onTertiaryFixed),
        onTertiaryFixedVariant = c("onTertiaryFixedVariant", onTertiaryFixedVariant),
    )
}

/** 语义色名（不区分大小写的 M3 槽位名，或 `transparent`）对应的颜色；不是语义色名时返回 null。 */
fun semanticUIColor(name: String, scheme: ColorScheme): Color? {
    val key = name.trim().lowercase()
    if (key == "transparent") return Color.Transparent
    val slot = UI_COLOR_SLOTS.firstOrNull { it.lowercase() == key } ?: return null
    return scheme.uiColorSlot(slot)
}

fun isSemanticUIColorName(name: String): Boolean {
    val key = name.trim().lowercase()
    return key == "transparent" || UI_COLOR_SLOTS.any { it.lowercase() == key }
}

private fun ColorScheme.uiColorSlot(slot: String): Color = when (slot) {
    "primary" -> primary
    "onPrimary" -> onPrimary
    "primaryContainer" -> primaryContainer
    "onPrimaryContainer" -> onPrimaryContainer
    "inversePrimary" -> inversePrimary
    "secondary" -> secondary
    "onSecondary" -> onSecondary
    "secondaryContainer" -> secondaryContainer
    "onSecondaryContainer" -> onSecondaryContainer
    "tertiary" -> tertiary
    "onTertiary" -> onTertiary
    "tertiaryContainer" -> tertiaryContainer
    "onTertiaryContainer" -> onTertiaryContainer
    "background" -> background
    "onBackground" -> onBackground
    "surface" -> surface
    "onSurface" -> onSurface
    "surfaceVariant" -> surfaceVariant
    "onSurfaceVariant" -> onSurfaceVariant
    "surfaceTint" -> surfaceTint
    "inverseSurface" -> inverseSurface
    "inverseOnSurface" -> inverseOnSurface
    "error" -> error
    "onError" -> onError
    "errorContainer" -> errorContainer
    "onErrorContainer" -> onErrorContainer
    "outline" -> outline
    "outlineVariant" -> outlineVariant
    "scrim" -> scrim
    "surfaceBright" -> surfaceBright
    "surfaceDim" -> surfaceDim
    "surfaceContainer" -> surfaceContainer
    "surfaceContainerHigh" -> surfaceContainerHigh
    "surfaceContainerHighest" -> surfaceContainerHighest
    "surfaceContainerLow" -> surfaceContainerLow
    "surfaceContainerLowest" -> surfaceContainerLowest
    "primaryFixed" -> primaryFixed
    "primaryFixedDim" -> primaryFixedDim
    "onPrimaryFixed" -> onPrimaryFixed
    "onPrimaryFixedVariant" -> onPrimaryFixedVariant
    "secondaryFixed" -> secondaryFixed
    "secondaryFixedDim" -> secondaryFixedDim
    "onSecondaryFixed" -> onSecondaryFixed
    "onSecondaryFixedVariant" -> onSecondaryFixedVariant
    "tertiaryFixed" -> tertiaryFixed
    "tertiaryFixedDim" -> tertiaryFixedDim
    "onTertiaryFixed" -> onTertiaryFixed
    "onTertiaryFixedVariant" -> onTertiaryFixedVariant
    else -> error("unknown color slot $slot")
}

/** 当前生效的宿主组件样式覆盖；未设置或界面自定义关闭时为 null（组件按原版绘制）。 */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun uiComponentStyle(name: String): me.rerere.rikkahub.data.model.UIComponentStyle? =
    LocalActiveUIPreset.current?.componentStyles?.get(name)

/** 样式里的颜色；未写时返回 [default]。 */
fun me.rerere.rikkahub.data.model.UIComponentStyle?.colorOr(
    pick: (me.rerere.rikkahub.data.model.UIComponentStyle) -> String?,
    scheme: ColorScheme,
    default: Color,
): Color = this?.let(pick)?.let { resolveUIColor(it, scheme, default) } ?: default

val UI_TEXT_ALIGNS: List<String> = listOf("start", "center", "end", "justify")
val UI_CONTENT_SCALES: List<String> = listOf("crop", "fit", "fill", "fill_width", "fill_height", "none")
val UI_BLEND_MODES: List<String> = listOf("srcOver", "normal", "plus", "add", "screen", "multiply", "overlay", "lighten", "darken")
val UI_SCROLL_MODES: List<String> = listOf("vertical", "y", "horizontal", "x")
val UI_POINTER_EVENTS: List<String> = listOf("auto", "none")
val UI_INSET_TOKENS: Set<String> = setOf("status", "statusbars", "nav", "navigationbars", "ime", "safe", "systembars")
val UI_HAPTIC_TYPES: List<String> = listOf("tick", "clock_tick", "click", "heavy", "confirm", "reject", "long_press")

fun parseUIBlendMode(name: String): androidx.compose.ui.graphics.BlendMode = when (name.trim().lowercase()) {
    "plus", "add" -> androidx.compose.ui.graphics.BlendMode.Plus
    "screen" -> androidx.compose.ui.graphics.BlendMode.Screen
    "multiply" -> androidx.compose.ui.graphics.BlendMode.Multiply
    "overlay" -> androidx.compose.ui.graphics.BlendMode.Overlay
    "lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
    "darken" -> androidx.compose.ui.graphics.BlendMode.Darken
    else -> androidx.compose.ui.graphics.BlendMode.SrcOver
}

fun isValidUIInsetsSpec(spec: String): Boolean {
    val parts = spec.trim().split(Regex("""[\s,+|]+""")).filter { it.isNotEmpty() }
    return parts.isNotEmpty() && parts.all { it.lowercase() in UI_INSET_TOKENS }
}

fun performUIHaptic(view: android.view.View, kind: String) {
    val constant = when (kind.trim().lowercase()) {
        "tick", "clock_tick" -> android.view.HapticFeedbackConstants.CLOCK_TICK
        "click" -> android.view.HapticFeedbackConstants.CONTEXT_CLICK
        "heavy", "long_press" -> android.view.HapticFeedbackConstants.LONG_PRESS
        "confirm" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.HapticFeedbackConstants.CONFIRM
        } else android.view.HapticFeedbackConstants.CONTEXT_CLICK
        "reject" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.HapticFeedbackConstants.REJECT
        } else android.view.HapticFeedbackConstants.LONG_PRESS
        else -> return
    }
    runCatching { view.performHapticFeedback(constant) }
}

fun String.toContentScale(): androidx.compose.ui.layout.ContentScale = when (this) {
    "crop" -> androidx.compose.ui.layout.ContentScale.Crop
    "fill" -> androidx.compose.ui.layout.ContentScale.FillBounds
    "fill_width" -> androidx.compose.ui.layout.ContentScale.FillWidth
    "fill_height" -> androidx.compose.ui.layout.ContentScale.FillHeight
    "none" -> androidx.compose.ui.layout.ContentScale.None
    else -> androidx.compose.ui.layout.ContentScale.Fit
}

/** 图片来源：`file:<filesDir 相对路径>`（不能越出 filesDir）转成 File，其余（http(s)、data:）原样交给 Coil。 */
fun resolveUIImageModel(context: Context, src: String): Any {
    val s = src.trim()
    if (!s.startsWith("file:")) return s
    val filesDir = context.filesDir.canonicalFile
    val file = java.io.File(filesDir, s.removePrefix("file:").trim()).canonicalFile
    require(file.path.startsWith(filesDir.path + java.io.File.separator)) { "图片路径越出了 App 数据目录：$s" }
    return file
}
