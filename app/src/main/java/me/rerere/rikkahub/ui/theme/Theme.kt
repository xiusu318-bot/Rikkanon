package me.rerere.rikkahub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.ui.custom.LocalActiveUIPreset
import me.rerere.rikkahub.ui.custom.LocalUIGlobalFontFamily
import me.rerere.rikkahub.ui.custom.applyUIPresetToColorScheme
import me.rerere.rikkahub.ui.custom.buildUIShapes
import me.rerere.rikkahub.ui.custom.buildUITypography
import me.rerere.rikkahub.ui.custom.resolveActiveUIPreset
import me.rerere.rikkahub.ui.custom.resolveUIFontFamily
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import me.rerere.rikkahub.utils.getActivity

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }
val LocalHostBaseColorScheme = compositionLocalOf<ColorScheme?> { null }
val LocalHostBaseDarkMode = compositionLocalOf { false }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    ignoreCustomUi: Boolean = false,
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val hostBaseScheme = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val activeUIPreset = remember(settings, ignoreCustomUi) {
        if (ignoreCustomUi) null else settings.resolveActiveUIPreset()
    }
    CompositionLocalProvider(
        LocalHostBaseColorScheme provides hostBaseScheme,
        LocalHostBaseDarkMode provides darkTheme,
    ) {
        UIPresetThemeOverlay(
            preset = activeUIPreset,
            displaySetting = settings.displaySetting,
            updateSystemBars = true,
            baseScheme = hostBaseScheme,
            baseDark = darkTheme,
            content = content,
        )
    }
}

/**
 * 将指定 [UIPreset] 的全局外观（配色、深浅色、全局字体、圆角形状、动效）叠加在宿主基底主题上。
 * - 当 [preset] 为 null（或界面自定义关闭）时，直接使用原版 [baseScheme] / [baseDark] / [Typography] / [MotionScheme.expressive]；
 * - 聊天页切换了绑定独立预设的助手，或编辑页实时预览沙盒时，调用此组件即可让预设的完整外观生效。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UIPresetThemeOverlay(
    preset: UIPreset?,
    displaySetting: DisplaySetting,
    updateSystemBars: Boolean = false,
    baseScheme: ColorScheme = LocalHostBaseColorScheme.current ?: androidx.compose.material3.MaterialTheme.colorScheme,
    baseDark: Boolean = LocalHostBaseDarkMode.current,
    content: @Composable () -> Unit,
) {
    val (colorSchemeConverted, effectiveDark) = remember(baseScheme, baseDark, preset) {
        applyUIPresetToColorScheme(baseScheme, preset, baseDark)
    }
    val extendColors = if (effectiveDark) ExtendDarkColors else ExtendLightColors

    if (updateSystemBars) {
        val view = LocalView.current
        val activity = view.context.getActivity()
        if (!view.isInEditMode && activity != null) {
            DisposableEffect(view, activity, effectiveDark) {
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, view)
                val previousLightStatusBars = controller.isAppearanceLightStatusBars
                val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
                controller.apply {
                    isAppearanceLightStatusBars = !effectiveDark
                    isAppearanceLightNavigationBars = !effectiveDark
                }
                onDispose {
                    controller.isAppearanceLightStatusBars = previousLightStatusBars
                    controller.isAppearanceLightNavigationBars = previousLightNavigationBars
                }
            }
        }
    }

    val globalSection = preset?.globalSection?.takeIf { it.enabled }
    val context = LocalContext.current
    val uiGlobalFont = remember(globalSection?.fontFamily, displaySetting.chatFontFamily, displaySetting.chatCustomFontPath) {
        globalSection?.fontFamily?.takeIf { it.isNotBlank() }?.let { resolveUIFontFamily(context, it, displaySetting) }
    }
    val typography = remember(globalSection, displaySetting.chatFontFamily, displaySetting.chatCustomFontPath) {
        if (globalSection == null) Typography
        else buildUITypography(Typography, globalSection) { resolveUIFontFamily(context, it, displaySetting) }
    }
    val uiShapes = remember(globalSection) { globalSection?.let { buildUIShapes(Shapes(), it) } }
    val motionScheme = if (globalSection?.motionScheme == "standard") MotionScheme.standard() else MotionScheme.expressive()

    CompositionLocalProvider(
        LocalDarkMode provides effectiveDark,
        LocalExtendColors provides extendColors,
        LocalActiveUIPreset provides preset,
        LocalUIGlobalFontFamily provides uiGlobalFont,
        me.rerere.ui.components.LocalCustomShapesEnabled provides (uiShapes != null),
    ) {
        if (uiShapes == null) {
            MaterialExpressiveTheme(
                colorScheme = colorSchemeConverted,
                typography = typography,
                content = content,
                motionScheme = motionScheme,
            )
        } else {
            MaterialExpressiveTheme(
                colorScheme = colorSchemeConverted,
                typography = typography,
                shapes = uiShapes,
                content = content,
                motionScheme = motionScheme,
            )
        }
    }
}

val androidx.compose.material3.MaterialTheme.extendColors
    @Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalExtendColors.current
