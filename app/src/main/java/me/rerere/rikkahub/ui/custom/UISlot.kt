package me.rerere.rikkahub.ui.custom

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * 界面自定义的通用接入点（与插件挂载点同理）。宿主每个可定制的位置包一行：
 *
 * ```
 * UISlot("位置名", data = mapOf(...), parts = mapOf(...), actions = mapOf(...)) { 原版 }
 * ```
 *
 * - 主题没写这个位置（或总开关关闭）：直接绘制原版，与没有接入点时完全相同。
 * - 主题写了 `ui.slot("位置名", 节点树)`：绘制节点树。`Host("original")` 放原版整体，`Host("部件名")` 放 [parts] 里的原版部件，
 *   节点的 `onClick: "动作名"` 调用 [actions]。
 * - 主题写了 `ui.slot("位置名", (data, ctx) => 节点树)`：主题 JS 按 [data] 渲染（数据没变不重算）；
 *   回调 `onClick: () => ctx.action("动作名")` 回到 JS 执行后调用 [actions]。返回 null 表示该处用原版。
 *
 * 固定节点树也能读数据：文字里的 `{{字段}}` 换成数据值，`visibleWhen: "字段"` / `"!字段"` 按数据真假显示。
 * 通用动作 `navigate:页面名`（或回调里 `ctx.action("navigate", "页面名", { 参数 })`）打开任意页面。
 *
 * 位置名、部件名、动作名、数据字段都不在宿主登记：主题写错时该处回退原版或不显示，错误记入运行时错误列表。
 *
 * @param modifier 被主题接管时作用在主题内容外层（原版自己处理 modifier）。
 * @param whenReplaced 被主题接管时仍需绘制的宿主部件（如长按菜单的弹出层，原版里它在原版布局内部），与主题内容放在同一层。
 */
@Composable
fun UISlot(
    name: String,
    data: Map<String, Any?> = emptyMap(),
    parts: Map<String, @Composable (UINodeRenderInfo) -> Unit> = emptyMap(),
    actions: Map<String, () -> Unit> = emptyMap(),
    actionsWithArgs: Map<String, (List<JsonElement>) -> Unit> = emptyMap(),
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    /** 作用在主题根节点背景之内、子节点之前的内边距（如状态栏避让，使顶栏背景自动铺进状态栏）。 */
    rootContentInsets: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    /** 被主题接管时内容的默认文字样式（如消息跟随聊天字体与字号）；null = 沿用外层。 */
    textStyle: androidx.compose.ui.text.TextStyle? = null,
    /** 被主题接管时通用节点的默认样式（如侧边栏文字单行、按钮用主色）。 */
    defaults: UINodeDefaults = UINodeDefaults(),
    whenReplaced: @Composable () -> Unit = {},
    original: @Composable () -> Unit,
) {
    val preset = LocalActiveUIPreset.current
    val staticTree = preset?.slots?.get(name)
    val dynamic = preset != null && name in preset.dynamicSlots
    if (staticTree == null && !dynamic) {
        original()
        return
    }
    val runtime = if (dynamic) remember(preset!!.id, preset.compiledCodeHash) { UIThemeRuntimes.get(preset) } else null
    val tree = if (runtime != null) rememberDynamicSlotTree(runtime, name, data) else staticTree
    if (tree == null) {
        original()
        return
    }

    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val view = androidx.compose.ui.platform.LocalView.current
    val latestActions by rememberUpdatedState(actions)
    val latestActionsWithArgs by rememberUpdatedState(actionsWithArgs)
    fun report(message: String) {
        if (runtime != null) runtime.recordError(message) else UIRuntimeErrors.record(preset, message)
    }
    fun runAction(actionName: String, args: List<JsonElement> = emptyList()) {
        if (actionName == "__haptic") {
            performUIHaptic(view, (args.firstOrNull() as? JsonPrimitive)?.content ?: "click")
            return
        }
        if (actionName == "navigate" || actionName.startsWith("navigate:")) {
            val route = actionName.substringAfter(":", "").ifEmpty { (args.firstOrNull() as? JsonPrimitive)?.content.orEmpty() }
            val params = (if (actionName == "navigate") args.getOrNull(1) else args.firstOrNull()) as? JsonObject ?: JsonObject(emptyMap())
            try {
                navController.navigate(screenOf(route, params))
            } catch (e: Exception) {
                report("ui.slot(\"$name\") 无法打开页面「$route」：${e.message}")
            }
            return
        }
        val argAction = latestActionsWithArgs[actionName]
        if (argAction != null) {
            argAction(args)
            return
        }
        val action = latestActions[actionName]
        if (action != null) action()
        else report("ui.slot(\"$name\") 没有动作「$actionName」，可用：${(latestActions.keys + latestActionsWithArgs.keys + "navigate").joinToString()}")
    }
    val host = rememberUINodeHost(
        displaySetting = LocalSettings.current.displaySetting,
        defaults = defaults,
        isVisible = { cond ->
            val negate = cond.startsWith("!")
            val key = cond.removePrefix("!")
            val value = data.lookup(key)
            if (value === MissingField) report("ui.slot(\"$name\") 的数据里没有字段「$key」，可用：${data.keys.joinToString()}")
            value.isTruthy() != negate
        },
        expandText = if (dynamic) {
            { it }
        } else {
            { raw ->
                TEXT_VARIABLE.replace(raw) { m ->
                    val key = m.groupValues[1]
                    val value = data.lookup(key)
                    if (value === MissingField) {
                        report("ui.slot(\"$name\") 的文字变量「{{$key}}」不存在，可用：${data.keys.joinToString()}")
                        m.value
                    } else {
                        value.toDisplayString()
                    }
                }
            }
        },
        onAction = { _, actionName -> runAction(actionName) },
        onCallbackWithArg = { id, arg ->
            val rt = runtime
            if (rt == null) report("ui.slot(\"$name\") 当前为静态节点，无法执行回调")
            else scope.launch { rt.call(id, arg).forEach { runAction(it.name, it.args) } }
        },
        onCallbackArgs = { id, args ->
            val rt = runtime
            if (rt == null) report("ui.slot(\"$name\") 当前为静态节点，无法执行回调")
            else scope.launch { rt.callArgs(id, args).forEach { runAction(it.name, it.args) } }
        },
        onRunAction = ::runAction,
        runtime = runtime,
        blocks = parts + ("original" to { info -> Box(info.modifier) { original() } }),
        onError = { n, e -> report("ui.slot(\"$name\") 的 ${n.type} 节点：${e.message}") },
    )
    Box(modifier) {
        if (textStyle != null) {
            androidx.compose.material3.ProvideTextStyle(textStyle) {
                RenderUINode(tree, host, rootContentInsets = rootContentInsets)
            }
        } else {
            RenderUINode(tree, host, rootContentInsets = rootContentInsets)
        }
        whenReplaced()
    }
}

@Composable
private fun rememberDynamicSlotTree(runtime: UIThemeRuntime, name: String, data: Map<String, Any?>): UIMessageNode? {
    val ready by runtime.ready.collectAsState()
    val version by runtime.version.collectAsState()
    // 必须在调用 renderNow 之前无条件订阅 renderTick：否则 wait=false 时若后台 QuickJS 线程在亚毫秒内先算完并递增了 renderTick，
    // 再进入 else 分支才首次 collectAsState 就会漏掉这次完成信号，导致按钮点击改了状态后界面卡在旧树不刷新！
    val tick by runtime.renderTick.collectAsState()
    val dataJson = remember(data) { data.toUIData().toString() }
    var last by remember(runtime, name) { mutableStateOf<UISlotResult?>(null) }
    // 上次渲染里 ctx.watch 的宿主状态：它们的值变了就重新渲染（值参与缓存键，不传给主题）
    val watchKey = watchKeyOf(last?.watched.orEmpty())
    // 已有上一次的结果时不在界面线程等待：先显示上一次的，后台算完再换；还没有任何结果时才短暂等待（避免先闪原版）
    val now = if (ready && tick >= 0) runtime.renderNow(name, dataJson, version, wait = last == null, watchKey = watchKey) else null
    val shown = if (now != null) {
        SideEffect { last = now }
        now
    } else {
        last
    }
    UIPinResult(runtime, shown)
    return shown?.tree
}

/** 界面正在显示 [result] 期间钉住它：即使被挤出缓存，它的回调与 Java 句柄也保持有效，直到不再显示。 */
@Composable
internal fun UIPinResult(runtime: UIThemeRuntime, result: UISlotResult?) {
    if (result != null) {
        androidx.compose.runtime.DisposableEffect(runtime, result) {
            runtime.pin(result)
            onDispose { runtime.unpin(result) }
        }
    }
}

/** 订阅被 watch 的对象，返回它们当前值的指纹。 */
@Composable
internal fun watchKeyOf(watched: List<Any>): String {
    if (watched.isEmpty()) return ""
    val sb = StringBuilder()
    for (obj in watched) {
        val v = androidx.compose.runtime.key(System.identityHashCode(obj)) {
            when (obj) {
                is kotlinx.coroutines.flow.StateFlow<*> -> obj.collectAsState().value
                is kotlinx.coroutines.flow.Flow<*> -> obj.collectAsState(null).value
                is androidx.compose.runtime.State<*> -> obj.value
                else -> null
            }
        }
        sb.append(System.identityHashCode(obj)).append('=').append(v?.hashCode() ?: 0).append(';')
    }
    return sb.toString()
}

// Android 的正则引擎（ICU）不接受未转义的 `}`（JVM 接受），写错会在类初始化时抛异常、进聊天页即崩。
private val TEXT_VARIABLE = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*\}\}""")

private object MissingField

/** 按 `a.b.c` 取数据字段；没有时返回 [MissingField]。 */
private fun Map<String, Any?>.lookup(path: String): Any? {
    var cur: Any? = this
    for (part in path.split('.')) {
        val map = cur as? Map<*, *> ?: return MissingField
        if (!map.containsKey(part)) return MissingField
        cur = map[part]
    }
    return cur
}

private fun Any?.isTruthy(): Boolean = when (this) {
    null, MissingField -> false
    is Boolean -> this
    is Number -> toDouble() != 0.0
    is String -> isNotEmpty()
    is Collection<*> -> isNotEmpty()
    is Map<*, *> -> isNotEmpty()
    else -> true
}

private fun Any?.toDisplayString(): String = when (this) {
    null -> ""
    is Enum<*> -> name
    else -> toString()
}

/** 页面名（`Screen` 子类名）+ 参数 → 页面，参数按该页面的字段名（如 AssistantDetail 的 id）。 */
private fun screenOf(route: String, params: JsonObject): me.rerere.rikkahub.Screen {
    require(route.isNotBlank()) { "没有写页面名" }
    val cls = try {
        Class.forName("me.rerere.rikkahub.Screen\$$route")
    } catch (_: ClassNotFoundException) {
        throw IllegalArgumentException("没有页面「$route」")
    }
    @Suppress("UNCHECKED_CAST")
    val serializer = kotlinx.serialization.serializer(cls) as kotlinx.serialization.KSerializer<Any>
    return kotlinx.serialization.json.Json.decodeFromJsonElement(serializer, params) as me.rerere.rikkahub.Screen
}
