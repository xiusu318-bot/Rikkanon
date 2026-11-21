package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.custom.LocalUIGlobalFontFamily
import java.io.File

val LocalChatFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun ChatFontProvider(
    displaySetting: DisplaySetting,
    content: @Composable () -> Unit,
) {
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    CompositionLocalProvider(LocalChatFontFamily provides chatFontFamily) {
        content()
    }
}

@Composable
fun rememberChatFontFamily(displaySetting: DisplaySetting): FontFamily {
    val context = LocalContext.current
    // 聊天字体为「默认」时跟随界面自定义的全局字体（未设置 / 总开关关闭时为 null，行为不变）
    val uiGlobalFont = LocalUIGlobalFontFamily.current
    return remember(
        displaySetting.chatFontFamily,
        displaySetting.chatCustomFontPath,
        uiGlobalFont,
    ) {
        if (displaySetting.chatFontFamily == ChatFontFamily.DEFAULT && uiGlobalFont != null) {
            uiGlobalFont
        } else {
            displaySetting.resolveChatFontFamily(context)
        }
    }
}

fun DisplaySetting.resolveChatFontFamily(context: Context): FontFamily = when (chatFontFamily) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> loadFilesDirFontFamily(context, chatCustomFontPath) ?: FontFamily.Default
}

private data class CachedFontKey(val path: String, val lastModified: Long, val length: Long)

private val filesDirFontCache = object : LinkedHashMap<CachedFontKey, FontFamily>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CachedFontKey, FontFamily>): Boolean = size > 16
}

/** 加载 filesDir 内的字体文件（路径不能越出 filesDir）；不存在或无法加载时返回 null。 */
fun loadFilesDirFontFamily(context: Context, relativePath: String): FontFamily? {
    val file = resolveFilesDirFile(context, relativePath) ?: return null
    if (!file.isFile) return null
    val key = CachedFontKey(file.path, file.lastModified(), file.length())
    synchronized(filesDirFontCache) {
        filesDirFontCache[key]?.let { return it }
    }
    val loaded = runCatching {
        Typeface.createFromFile(file)
        FontFamily(Font(file))
    }.getOrNull() ?: return null
    synchronized(filesDirFontCache) {
        filesDirFontCache[key] = loaded
    }
    return loaded
}

private fun resolveFilesDirFile(context: Context, relativePath: String): File? {
    if (relativePath.isBlank()) return null
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it.path.startsWith("${filesDir.path}${File.separator}") }
}
