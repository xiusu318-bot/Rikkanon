package me.rerere.rikkahub.ui.custom

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import me.rerere.rikkahub.data.model.UIPageSpec
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.model.UITransitionPair
import me.rerere.rikkahub.data.model.UITransitionSide
import me.rerere.rikkahub.ui.context.LocalSettings

private val navEntryKeyField by lazy {
    runCatching {
        NavEntry::class.java.getDeclaredField("key").apply { isAccessible = true }
    }.getOrNull()
}

/** 取出 [NavEntry] 对应的原始路由对象（如 `Screen.AssistantDetail(id=...)`）。 */
private fun NavEntry<*>.resolveNavKey(backStack: () -> List<Any>): Any? {
    navEntryKeyField?.let { f -> runCatching { f.get(this) }.getOrNull()?.let { return it } }
    return backStack().firstOrNull { "${it}${it.javaClass.kotlin}" == contentKey.toString() || "$it:${it.javaClass.kotlin}" == contentKey.toString() }
}

/** 导航条目对应的页面名（`Screen` 子类名）。 */
internal fun routeNameOf(navKey: Any?, fallbackContentKey: Any): String {
    if (navKey != null) {
        val simple = navKey.javaClass.simpleName
        if (simple.isNotEmpty()) return simple
    }
    return fallbackContentKey.toString().substringBefore('(').substringAfterLast('.').substringAfterLast('$')
}

/** 记录当前是否有某个对话页正按助手独立预设接管顶层前景层（避免根窗口再叠加全局预设的 `overlay`）。 */
class UIChatOverlayOverrideState {
    private val counts = mutableStateMapOf<String, Int>()

    fun retain(conversationId: String) {
        counts[conversationId] = (counts[conversationId] ?: 0) + 1
    }

    fun release(conversationId: String) {
        val next = (counts[conversationId] ?: 0) - 1
        if (next <= 0) counts.remove(conversationId) else counts[conversationId] = next
    }

    fun isOverridden(conversationId: String): Boolean = (counts[conversationId] ?: 0) > 0
}

val LocalUIChatOverlayOverride = compositionLocalOf<UIChatOverlayOverrideState?> { null }

/** 全局顶层前景层（`ui.overlay(...)` / `ui.slot("overlay", ...)`）：未启用或未定义时完全不渲染。 */
@Composable
fun UIGlobalOverlay(topNavKey: Any?) {
    val preset = LocalActiveUIPreset.current ?: return
    if (!preset.hasSlot("overlay")) return
    val route = if (topNavKey is String) topNavKey else routeNameOf(topNavKey, topNavKey ?: "")
    UISlot(
        name = "overlay",
        data = mapOf("route" to route),
        modifier = Modifier.fillMaxSize(),
    ) {}
}

/**
 * 界面自定义的页面级接入点（导航处唯一的通用接入点，对全部页面生效）。
 * 没有为该页面设置 `ui.page`（或界面自定义关闭）时直接显示原页面。
 */
fun <T : Any> uiPageEntryDecorator(backStack: () -> List<Any>): NavEntryDecorator<T> = NavEntryDecorator(
    decorate = { entry ->
        val preset = LocalActiveUIPreset.current
        if (preset == null) {
            entry.Content()
        } else {
            val navKey = entry.resolveNavKey(backStack)
            val route = routeNameOf(navKey, entry.contentKey)
            val spec = preset.pages[route] ?: preset.pages["*"]?.takeIf { route != "Chat" }
            // 整页替换：ui.page(名, { content }) / ui.slot("page:名", ...)；"page:*" 作用于除聊天页外的全部页面
            val slotName = when {
                preset.hasSlot("page:$route") -> "page:$route"
                route != "Chat" && preset.hasSlot("page:*") -> "page:*"
                else -> null
            }
            if (spec == null && slotName == null) {
                entry.Content()
            } else {
                val content: @Composable () -> Unit = if (slotName == null) {
                    { entry.Content() }
                } else {
                    { UIPageSlot(slotName, route, navKey) { entry.Content() } }
                }
                if (spec == null) content() else UIPage(preset, route, spec, content)
            }
        }
    },
)

/**
 * 整页替换的接入点。数据：route（页面名）、params（页面参数，如 AssistantDetail 的 id）；
 * 部件 original（原页面）；动作 back（返回上一页），其余页面用 navigate。
 */
@Composable
private fun UIPageSlot(name: String, route: String, key: Any?, original: @Composable () -> Unit) {
    val nav = me.rerere.rikkahub.ui.context.LocalNavController.current
    val params = androidx.compose.runtime.remember(key) { screenParams(key) }
    UISlot(
        name = name,
        data = mapOf("route" to route, "params" to params),
        actions = mapOf("back" to { nav.popBackStack() }),
        modifier = Modifier.fillMaxSize(),
        original = original,
    )
}

/** 页面对象的参数（按 kotlinx 序列化的字段名）；没有参数或无法序列化时为空对象。 */
internal fun screenParams(key: Any?): kotlinx.serialization.json.JsonElement = if (key == null) kotlinx.serialization.json.JsonObject(emptyMap()) else try {
    @Suppress("UNCHECKED_CAST")
    val serializer = kotlinx.serialization.serializer(key.javaClass) as kotlinx.serialization.KSerializer<Any>
    kotlinx.serialization.json.Json.encodeToJsonElement(serializer, key) as? kotlinx.serialization.json.JsonObject
        ?: kotlinx.serialization.json.JsonObject(emptyMap())
} catch (_: Exception) {
    kotlinx.serialization.json.JsonObject(emptyMap())
}

val LocalUIPageHasBackground = compositionLocalOf { false }

@Composable
private fun UIPage(preset: UIPreset, route: String, spec: UIPageSpec, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    var scheme = base.withUIColorOverrides(spec.colors)
    val hasBg = spec.background != null
    // 有背景时页面底色透明，让背景透出来（Scaffold 用 background 或 CustomColors.topBarColors.containerColor 作底色均自动透出）
    if (hasBg && "background" !in spec.colors) scheme = scheme.copy(background = Color.Transparent)
    Box(modifier = Modifier.fillMaxSize()) {
        spec.background?.let { bg ->
            val host = rememberUINodeHost(
                displaySetting = LocalSettings.current.displaySetting,
                onError = { n, e -> UIRuntimeErrors.record(preset, "ui.page(\"$route\").background 的 ${n.type} 节点：${e.message}") },
            )
            RenderUINode(bg, host, Modifier.matchParentSize())
        }
        CompositionLocalProvider(LocalUIPageHasBackground provides (hasBg || LocalUIPageHasBackground.current)) {
            MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                content()
                spec.foreground?.let { fg ->
                    val fgHost = rememberUINodeHost(
                        displaySetting = LocalSettings.current.displaySetting,
                        onError = { n, e -> UIRuntimeErrors.record(preset, "ui.page(\"$route\").foreground 的 ${n.type} 节点：${e.message}") },
                    )
                    RenderUINode(fg, fgHost, Modifier.matchParentSize())
                }
            }
        }
    }
}

private fun UITransitionSide.enter(): EnterTransition {
    var t: EnterTransition = EnterTransition.None
    val spec = { tween<Float>(durationMillis = durationMs, delayMillis = delayMs) }
    if (fade) t += fadeIn(spec())
    if (slideX != 0f || slideY != 0f) {
        t += slideIn(tween(durationMillis = durationMs, delayMillis = delayMs)) { full ->
            IntOffset((full.width * slideX).toInt(), (full.height * slideY).toInt())
        }
    }
    if (scale != 1f) t += scaleIn(spec(), initialScale = scale)
    return t
}

private fun UITransitionSide.exit(): ExitTransition {
    var t: ExitTransition = ExitTransition.None
    val spec = { tween<Float>(durationMillis = durationMs, delayMillis = delayMs) }
    if (fade) t += fadeOut(spec())
    if (slideX != 0f || slideY != 0f) {
        t += slideOut(tween(durationMillis = durationMs, delayMillis = delayMs)) { full ->
            IntOffset((full.width * slideX).toInt(), (full.height * slideY).toInt())
        }
    }
    if (scale != 1f) t += scaleOut(spec(), targetScale = scale)
    return t
}

/** `ui.transition` 的 push / pop 转成 Compose 切换动画。 */
fun UITransitionPair.toContentTransform(): ContentTransform = enter.enter() togetherWith exit.exit()
