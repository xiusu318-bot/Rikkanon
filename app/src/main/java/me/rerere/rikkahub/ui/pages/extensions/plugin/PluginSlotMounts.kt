package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.User
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.ViewOffSlash
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.plugin.registry.PluginRegistration
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.rikkahub.data.plugin.PluginUiEvent
import me.rerere.rikkahub.data.plugin.PluginUiEvents
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.ui.components.SelectTextField
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** 根据插件在 `spec.icon` 中声明的名称映射为宿主图标（未指定或未知时使用 [defaultIcon]）。 */
internal fun resolvePluginIcon(name: String?, defaultIcon: ImageVector = HugeIcons.Puzzle): ImageVector =
    when (name?.trim()?.lowercase()) {
        "puzzle", "plugin" -> HugeIcons.Puzzle
        "sparkles", "magic", "star" -> HugeIcons.Sparkles
        "ai", "wand" -> HugeIcons.AiMagic
        "brain", "model", "robot", "subagent" -> HugeIcons.Brain02
        "terminal", "console", "shell" -> HugeIcons.ComputerTerminal01
        "sandbox", "workspace" -> HugeIcons.Codesandbox
        "book", "lorebook", "preset", "prompt" -> HugeIcons.Book03
        "code", "script", "json" -> HugeIcons.Code
        "search", "find" -> HugeIcons.Search01
        "globe", "web", "network", "fetch" -> HugeIcons.GlobalSearch
        "webdesign", "html", "panel" -> HugeIcons.WebDesign01
        "settings", "config", "gear" -> HugeIcons.Settings03
        "tune", "sliders" -> HugeIcons.Settings02
        "key", "secret", "lock", "auth" -> HugeIcons.Key01
        "file", "doc", "document" -> HugeIcons.File02
        "folder", "dir" -> HugeIcons.Folder01
        "image", "photo", "picture" -> HugeIcons.Image02
        "video", "movie" -> HugeIcons.Video01
        "voice", "mic", "asr" -> HugeIcons.Voice
        "tts", "speaker", "audio" -> HugeIcons.Megaphone01
        "refresh", "reload", "sync" -> HugeIcons.Refresh03
        "delete", "trash", "remove" -> HugeIcons.Delete01
        "edit", "pen" -> HugeIcons.Edit01
        "copy" -> HugeIcons.Copy01
        "share" -> HugeIcons.Share04
        "download", "save" -> HugeIcons.Download04
        "check", "tick", "ok" -> HugeIcons.Tick01
        "warning", "alert", "error" -> HugeIcons.Alert01
        "info", "help", "idea" -> HugeIcons.Idea01
        "bolt", "zap", "quick", "fast" -> HugeIcons.Zap
        "tool", "tools", "wrench" -> HugeIcons.Tools
        "chat", "message" -> HugeIcons.Message02
        "database", "storage", "memory" -> HugeIcons.Database02
        "server", "proxy" -> HugeIcons.ServerStack01
        "mcp" -> HugeIcons.McpServer
        "package", "extension" -> HugeIcons.Package
        "translate", "language" -> HugeIcons.Translate
        "user", "account" -> HugeIcons.User
        "pin" -> HugeIcons.Pin
        "play", "run", "start" -> HugeIcons.Play
        "link", "url" -> HugeIcons.Link01
        "eye", "view" -> HugeIcons.Eye
        "history", "clock" -> HugeIcons.Clock01
        "time" -> HugeIcons.Time02
        "assistant", "persona" -> HugeIcons.LookTop
        "heart", "favorite", "favourite" -> HugeIcons.Favourite
        "chart", "stats" -> HugeIcons.ChartColumn
        "sort", "sorting" -> HugeIcons.Sorting01
        "add", "plus" -> HugeIcons.Add01
        "new_chat", "chat_add" -> HugeIcons.MessageAdd01
        "menu" -> HugeIcons.Menu03
        "more" -> HugeIcons.MoreVertical
        "close", "cancel" -> HugeIcons.Cancel01
        "back", "arrow_left" -> HugeIcons.ArrowLeft01
        "arrow_right", "forward" -> HugeIcons.ArrowRight01
        "arrow_up" -> HugeIcons.ArrowUp01
        "arrow_down", "expand" -> HugeIcons.ArrowDown01
        "sun", "light" -> HugeIcons.Sun01
        "moon", "dark" -> HugeIcons.Moon02
        "bell", "notification" -> HugeIcons.Notification01
        "camera" -> HugeIcons.Camera01
        "palette", "theme", "paint" -> HugeIcons.PaintBoard
        "layers" -> HugeIcons.Layers01
        else -> defaultIcon
    }

/** 该 UI 挂载项在注册时是否传入了 JS 回调函数。 */
internal fun PluginRegistration.hasUiHandler(): Boolean =
    meta.jsonObject.getValue("hasHandler").jsonPrimitive.boolean

/**
 * 调用插件渲染回调（message.part / message.footer / tool.card / markdown.codeblock 的 handler 形式）。
 * 回调返回字符串、`{ html }` 或 `{ text }`；返回 null 表示不显示（结果为空串）。
 * 出错或返回格式不合法时记入插件错误，结果为 failure。计算完成前为 null。
 */
@Composable
fun rememberPluginRendered(
    pluginId: String,
    handlerId: String,
    payload: kotlinx.serialization.json.JsonElement,
    vararg keys: Any?,
): Result<String>? {
    val host = koinInject<PluginHost>()
    val result by produceState<Result<String>?>(null, pluginId, handlerId, *keys) {
        value = runCatching {
            when (val res = host.callUiHandler(pluginId, handlerId, payload)) {
                kotlinx.serialization.json.JsonNull -> ""
                is JsonPrimitive -> res.content
                is JsonObject -> (res["html"] ?: res["text"]
                    ?: throw IllegalArgumentException("渲染结果缺少 html 或 text")).jsonPrimitive.content
                is JsonArray -> throw IllegalArgumentException("渲染结果必须是字符串、{ html } 或 { text }")
            }
        }.onFailure { host.recordError(pluginId, it) }
    }
    return result
}

/** 插件渲染失败时在原位置显示的错误。 */
@Composable
fun PluginRenderError(error: Throwable, modifier: Modifier = Modifier) {
    Text(
        text = "插件渲染失败：${error.message ?: error.javaClass.name}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/** 界面挂载项（`ui.mount` 注册，宿主已校验 spec 为对象且含 type）的 spec。 */
val PluginRegistration.uiSpec: JsonObject
    get() = meta.jsonObject.getValue("spec").jsonObject

@Composable
private fun resolveStatusColor(status: String?): Color? = when (status?.trim()?.lowercase()) {
    "ready", "ok", "success", "online", "green" -> MaterialTheme.extendColors.green6
    "running", "loading", "active", "blue", "primary" -> MaterialTheme.colorScheme.primary
    "warning", "pending", "yellow", "orange" -> MaterialTheme.colorScheme.tertiary
    "error", "failed", "offline", "red" -> MaterialTheme.colorScheme.error
    else -> null
}

/** 顶层统一提供的插件 UI 挂载点分桶快照（避免聊天流中每条消息/代码块各自做 Koin 查找与 Flow 订阅）。 */
val LocalPluginUiMounts = compositionLocalOf<Map<String, List<Pair<PluginRuntime, PluginRegistration>>>?> { null }

/**
 * 取出当前某个 UI 挂载点上所有启用且可见（`spec.visible != false`）的注册项（随插件挂载变化自动重组）。
 */
@Composable
fun rememberPluginUiMounts(slot: String, pluginId: String? = null): List<Pair<PluginRuntime, PluginRegistration>> {
    val provided = LocalPluginUiMounts.current
    val mountsBySlot = if (provided != null) {
        provided
    } else {
        val host = koinInject<PluginHost>()
        val state by host.uiMountsBySlot.collectAsStateWithLifecycle()
        state
    }
    val list = mountsBySlot[slot].orEmpty()
    if (pluginId == null || list.isEmpty()) return list
    return remember(list, pluginId) { list.filter { it.first.id == pluginId } }
}

/**
 * 页面类挂载点渲染器（支持 `entry` / `form` / `html`）：
 * 适用于 `plugin.detail`、`extensions.list`、`settings.group`、`assistant.detail`、`provider.detail`、`mcp.detail`、`workspace.detail`。
 */
@Composable
fun PluginPageSlotMounts(
    slot: String,
    contextArgs: JsonObject = JsonObject(emptyMap()),
    pluginId: String? = null,
    modifier: Modifier = Modifier,
) {
    val host = koinInject<PluginHost>()
    val mounts = rememberPluginUiMounts(slot, pluginId)
    if (mounts.isEmpty()) return

    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    fun invokeHandler(targetPluginId: String, reg: PluginRegistration, payload: JsonObject = contextArgs) {
        if (!reg.hasUiHandler()) return
        scope.launch {
            runCatching { host.callUiHandler(targetPluginId, reg.handlerId, payload) }
                .onFailure { e ->
                    host.recordError(targetPluginId, e)
                    toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val entries = mounts.filter { (_, reg) ->
            reg.uiSpec.getValue("type").jsonPrimitive.content == "entry"
        }
        if (entries.isNotEmpty()) {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            var switchTick by remember { mutableIntStateOf(0) }
            CardGroup(title = { Text("插件") }) {
                entries.forEach { (rt, reg) ->
                    val spec = reg.uiSpec
                    val title = spec["title"]?.jsonPrimitive?.contentOrNull ?: rt.manifest.name
                    val desc = spec["description"]?.jsonPrimitive?.contentOrNull
                    val openPath = spec["path"]?.jsonPrimitive?.contentOrNull
                    val icon = resolvePluginIcon(spec["icon"]?.jsonPrimitive?.contentOrNull)
                    val badgeText = spec["badge"]?.jsonPrimitive?.contentOrNull
                        ?: spec["trailingText"]?.jsonPrimitive?.contentOrNull
                    val switchKey = spec["switchKey"]?.jsonPrimitive?.contentOrNull
                    val storage = host.storage(rt.id)
                    @Suppress("UNUSED_EXPRESSION")
                    switchTick
                    val switchChecked = if (switchKey == null) false
                    else (storage.get(switchKey) as? JsonPrimitive)?.booleanOrNull
                        ?: (spec["default"] as? JsonPrimitive)?.booleanOrNull
                        ?: false

                    item(
                        onClick = {
                            if (openPath != null) {
                                PluginUiEvents.emit(
                                    PluginUiEvent.OpenPanel(
                                        pluginId = rt.id,
                                        path = openPath,
                                        presentation = spec["presentation"]?.jsonPrimitive?.contentOrNull ?: "page",
                                        argsJson = contextArgs.toString(),
                                        title = title,
                                        widthDp = spec["width"]?.jsonPrimitive?.intOrNull,
                                        heightDp = spec["height"]?.jsonPrimitive?.intOrNull,
                                    )
                                )
                            } else if (reg.hasUiHandler()) {
                                invokeHandler(rt.id, reg, contextArgs)
                            } else if (switchKey != null) {
                                val next = !switchChecked
                                storage.set(switchKey, JsonPrimitive(next))
                                switchTick++
                                host.bumpVersion()
                            }
                        },
                        leadingContent = {
                            CardGroupIcon(icon, containerColor = containerColor, contentColor = contentColor)
                        },
                        headlineContent = { Text(title) },
                        supportingContent = if (desc != null) { { Text(desc) } } else null,
                        trailingContent = if (switchKey != null || !badgeText.isNullOrBlank()) {
                            {
                                when {
                                    switchKey != null -> me.rerere.rikkahub.ui.custom.UISwitch(
                                        checked = switchChecked,
                                        onCheckedChange = { next ->
                                            storage.set(switchKey, JsonPrimitive(next))
                                            switchTick++
                                            host.bumpVersion()
                                            invokeHandler(
                                                rt.id,
                                                reg,
                                                JsonObject(
                                                    contextArgs + mapOf(
                                                        "type" to JsonPrimitive("change"),
                                                        "key" to JsonPrimitive(switchKey),
                                                        "value" to JsonPrimitive(next),
                                                    )
                                                ),
                                            )
                                        },
                                    )
                                    !badgeText.isNullOrBlank() -> Badge(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ) {
                                        Text(badgeText, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                }
                            }
                        } else null,
                    )
                }
            }
        }

        mounts.forEach { (rt, reg) ->
            val spec = reg.uiSpec
            when (spec.getValue("type").jsonPrimitive.content) {
                "form" -> PluginFormCardGroup(
                    host = host,
                    runtime = rt,
                    reg = reg,
                    spec = spec,
                    contextArgs = contextArgs,
                    onInvoke = { _, _, payload -> invokeHandler(rt.id, reg, payload) },
                )

                "html" -> {
                    val path = spec["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val inlineHtml = spec["html"]?.jsonPrimitive?.contentOrNull
                    if (path.isEmpty() && inlineHtml == null) return@forEach
                    val title = spec["title"]?.jsonPrimitive?.contentOrNull
                    val explicitHeight = spec["height"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
                    CardGroup(title = title?.let { t -> { Text(t) } }) {
                        formItem {
                            PluginWebViewBox(
                                pluginId = rt.id,
                                path = path,
                                inlineHtml = inlineHtml,
                                argsJson = contextArgs.toString(),
                                autoHeight = explicitHeight == null,
                                initialHeightDp = explicitHeight ?: 200,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (explicitHeight != null) Modifier.height(explicitHeight.dp) else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginFormCardGroup(
    host: PluginHost,
    runtime: PluginRuntime,
    reg: PluginRegistration,
    spec: JsonObject,
    contextArgs: JsonObject,
    onInvoke: (String, String, JsonObject) -> Unit,
) {
    host.installedPlugin(runtime.id) ?: return
    val storage = remember(runtime.id) { host.storage(runtime.id) }
    val settings = LocalSettings.current
    val version by host.registrationsVersion.collectAsStateWithLifecycle()
    var storageTick by remember { mutableIntStateOf(0) }

    fun readKey(key: String, default: JsonElement?): JsonElement? {
        @Suppress("UNUSED_EXPRESSION")
        storageTick
        @Suppress("UNUSED_EXPRESSION")
        version
        return storage.get(key) ?: default
    }

    fun writeKey(key: String, value: JsonElement) {
        storage.set(key, value)
        storageTick++
        host.bumpVersion()
        if (reg.hasUiHandler()) {
            onInvoke(
                runtime.id,
                reg.handlerId,
                JsonObject(
                    contextArgs + mapOf(
                        "type" to JsonPrimitive("change"),
                        "key" to JsonPrimitive(key),
                        "value" to value,
                    )
                ),
            )
        }
    }

    val title = spec["title"]?.jsonPrimitive?.contentOrNull ?: runtime.manifest.name
    val fields = spec.getValue("fields").jsonArray.map { it.jsonObject }

    fun isVisible(field: JsonObject): Boolean {
        val cond = field["visibleWhen"] as? JsonObject ?: return true
        val condKey = cond["key"]?.jsonPrimitive?.contentOrNull ?: return true
        val expected = cond["equals"] ?: JsonPrimitive(true)
        val condFieldDefault = fields.find { it["key"]?.jsonPrimitive?.contentOrNull == condKey }?.get("default")
        val actual = readKey(condKey, condFieldDefault) ?: JsonNull
        if (expected is JsonArray && actual !is JsonArray) {
            val actualStr = (actual as? JsonPrimitive)?.contentOrNull ?: actual.toString()
            return expected.any { ((it as? JsonPrimitive)?.contentOrNull ?: it.toString()) == actualStr }
        }
        return actual.toString() == expected.toString() ||
            ((actual as? JsonPrimitive)?.contentOrNull == (expected as? JsonPrimitive)?.contentOrNull)
    }

    CardGroup(title = { Text(title) }) {
        fields.filter(::isVisible).forEach { field ->
            val type = field["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val key = field["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = field["label"]?.jsonPrimitive?.contentOrNull ?: field["title"]?.jsonPrimitive?.contentOrNull ?: key
            val desc = field["description"]?.jsonPrimitive?.contentOrNull
            val placeholder = field["placeholder"]?.jsonPrimitive?.contentOrNull
            val defaultVal = field["default"]

            when (type) {
                "switch" -> {
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.booleanOrNull ?: false
                    switchItem(
                        checked = cur,
                        onCheckedChange = { writeKey(key, JsonPrimitive(it)) },
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    )
                }

                "text", "textarea" -> {
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        OutlinedTextField(
                            value = cur,
                            onValueChange = { writeKey(key, JsonPrimitive(it)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = placeholder?.let { p -> { Text(p) } },
                            singleLine = type == "text",
                            minLines = if (type == "textarea") 3 else 1,
                            maxLines = if (type == "textarea") 8 else 1,
                        )
                    }
                }

                "password" -> {
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        var showPassword by remember(key) { mutableStateOf(false) }
                        OutlinedTextField(
                            value = cur,
                            onValueChange = { writeKey(key, JsonPrimitive(it)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = placeholder?.let { p -> { Text(p) } },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) HugeIcons.ViewOffSlash else HugeIcons.Eye,
                                        contentDescription = null,
                                    )
                                }
                            },
                        )
                    }
                }

                "number" -> {
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        OutlinedTextField(
                            value = cur,
                            onValueChange = { text ->
                                val num = text.toDoubleOrNull()
                                if (num != null) {
                                    writeKey(key, if (text.contains('.')) JsonPrimitive(num) else JsonPrimitive(num.toLong()))
                                } else if (text.isEmpty()) {
                                    writeKey(key, JsonPrimitive(0))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }

                "slider" -> {
                    val min = field["min"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val max = field["max"]?.jsonPrimitive?.floatOrNull ?: 100f
                    val step = field["step"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.floatOrNull ?: min
                    val stepsCount = if (step > 0f && max > min) {
                        (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)
                    } else 0
                    val isIntStep = step >= 1f && step % 1f == 0f && min % 1f == 0f
                    val displayValue = if (isIntStep) cur.roundToInt().toString() else String.format("%.2f", cur).trimEnd('0').trimEnd('.')
                    formItem(
                        headlineContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label)
                                Text(
                                    text = displayValue,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        Slider(
                            value = cur.coerceIn(min, max),
                            onValueChange = { v ->
                                val snapped = if (step > 0f) {
                                    (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max)
                                } else v
                                if (isIntStep) {
                                    writeKey(key, JsonPrimitive(snapped.roundToInt()))
                                } else {
                                    writeKey(key, JsonPrimitive((snapped * 1000.0).roundToInt() / 1000.0))
                                }
                            },
                            valueRange = min..max,
                            steps = stepsCount,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                "select" -> {
                    val options = (field["options"] as? JsonArray)?.mapNotNull { el ->
                        when (el) {
                            is JsonPrimitive -> el.content to el.content
                            is JsonObject -> (el["value"]?.jsonPrimitive?.contentOrNull ?: "") to
                                (el["label"]?.jsonPrimitive?.contentOrNull ?: el["value"]?.jsonPrimitive?.contentOrNull ?: "")
                            else -> null
                        }
                    }.orEmpty()
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.contentOrNull
                        ?: options.firstOrNull()?.first.orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        SelectTextField(
                            value = options.find { it.first == cur }?.second ?: cur,
                            options = options.map { it.first },
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            optionToString = { v -> options.find { it.first == v }?.second ?: v },
                            onOptionSelected = { v -> writeKey(key, JsonPrimitive(v)) },
                        )
                    }
                }

                "segmented" -> {
                    val options = (field["options"] as? JsonArray)?.mapNotNull { el ->
                        when (el) {
                            is JsonPrimitive -> el.content to el.content
                            is JsonObject -> (el["value"]?.jsonPrimitive?.contentOrNull ?: "") to
                                (el["label"]?.jsonPrimitive?.contentOrNull ?: el["value"]?.jsonPrimitive?.contentOrNull ?: "")
                            else -> null
                        }
                    }.orEmpty()
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.contentOrNull
                        ?: options.firstOrNull()?.first.orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { (valStr, labelStr) ->
                                FilterChip(
                                    selected = cur == valStr,
                                    onClick = { writeKey(key, JsonPrimitive(valStr)) },
                                    label = { Text(labelStr) },
                                )
                            }
                        }
                    }
                }

                "model" -> {
                    val modelOptions = settings.providers.filter { it.enabled }.flatMap { p ->
                        p.models.map { m -> m.modelId to "${m.displayName} (${p.name})" }
                    }
                    val cur = (readKey(key, defaultVal) as? JsonPrimitive)?.contentOrNull
                        ?: modelOptions.firstOrNull()?.first.orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        if (modelOptions.isNotEmpty()) {
                            SelectTextField(
                                value = modelOptions.find { it.first == cur }?.second ?: cur,
                                options = modelOptions.map { it.first },
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                optionToString = { v -> modelOptions.find { it.first == v }?.second ?: v },
                                onOptionSelected = { v -> writeKey(key, JsonPrimitive(v)) },
                            )
                        } else {
                            OutlinedTextField(
                                value = cur,
                                onValueChange = { writeKey(key, JsonPrimitive(it)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("输入模型 ID") },
                            )
                        }
                    }
                }

                "multiselect" -> {
                    val options = (field["options"] as? JsonArray)?.mapNotNull { el ->
                        when (el) {
                            is JsonPrimitive -> el.content to el.content
                            is JsonObject -> (el["value"]?.jsonPrimitive?.contentOrNull ?: "") to
                                (el["label"]?.jsonPrimitive?.contentOrNull ?: el["value"]?.jsonPrimitive?.contentOrNull ?: "")
                            else -> null
                        }
                    }.orEmpty()
                    val selected = (readKey(key, defaultVal) as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.toSet()
                        .orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { (valStr, labelStr) ->
                                val isSel = valStr in selected
                                FilterChip(
                                    selected = isSel,
                                    onClick = {
                                        val next = if (isSel) selected - valStr else selected + valStr
                                        writeKey(key, JsonArray(next.map(::JsonPrimitive)))
                                    },
                                    label = { Text(labelStr) },
                                )
                            }
                        }
                    }
                }

                "list" -> {
                    val items = (readKey(key, defaultVal) as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        var draft by remember(key) { mutableStateOf("") }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items.forEachIndexed { idx, itemText ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(itemText, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        val next = items.toMutableList().also { it.removeAt(idx) }
                                        writeKey(key, JsonArray(next.map(::JsonPrimitive)))
                                    }) {
                                        Icon(HugeIcons.Delete01, contentDescription = "删除")
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text(placeholder ?: "新增项") },
                                )
                                IconButton(
                                    onClick = {
                                        val trimmed = draft.trim()
                                        if (trimmed.isNotEmpty()) {
                                            writeKey(key, JsonArray((items + trimmed).map(::JsonPrimitive)))
                                            draft = ""
                                        }
                                    }
                                ) {
                                    Icon(HugeIcons.Add01, contentDescription = "添加")
                                }
                            }
                        }
                    }
                }

                "button" -> {
                    val action = field["action"]?.jsonPrimitive?.contentOrNull ?: key.ifEmpty { label }
                    val openPath = field["path"]?.jsonPrimitive?.contentOrNull
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        Button(
                            onClick = {
                                if (openPath != null) {
                                    PluginUiEvents.emit(
                                        PluginUiEvent.OpenPanel(
                                            pluginId = runtime.id,
                                            path = openPath,
                                            presentation = field["presentation"]?.jsonPrimitive?.contentOrNull ?: "page",
                                            argsJson = contextArgs.toString(),
                                            title = label,
                                        )
                                    )
                                } else {
                                    onInvoke(
                                        runtime.id,
                                        reg.handlerId,
                                        JsonObject(
                                            contextArgs + mapOf(
                                                "type" to JsonPrimitive("action"),
                                                "action" to JsonPrimitive(action),
                                                "key" to JsonPrimitive(key),
                                            )
                                        ),
                                    )
                                }
                            }
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 渲染单个顶栏/输入栏胶囊（支持图标、状态圆点 `status`、徽标 `badge`、点击打开面板 `path`、或点击展开下拉菜单 `menu`）。
 */
@Composable
private fun PluginPillMountItem(
    host: PluginHost,
    rt: PluginRuntime,
    reg: PluginRegistration,
    payload: JsonObject,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val spec = reg.uiSpec
    val text = spec["text"]?.jsonPrimitive?.contentOrNull
        ?: spec["title"]?.jsonPrimitive?.contentOrNull
        ?: rt.manifest.name
    val badge = spec["badge"]?.jsonPrimitive?.contentOrNull
    val iconName = spec["icon"]?.jsonPrimitive?.contentOrNull
    val statusColor = resolveStatusColor(spec["status"]?.jsonPrimitive?.contentOrNull)
    val openPath = spec["path"]?.jsonPrimitive?.contentOrNull
    val menuItems = spec["menu"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    var showMenu by remember { mutableStateOf(false) }

    fun triggerAction(extra: Map<String, JsonElement> = emptyMap()) {
        val merged = JsonObject(payload + extra)
        if (openPath != null && extra.isEmpty()) {
            PluginUiEvents.emit(
                PluginUiEvent.OpenPanel(
                    pluginId = rt.id,
                    path = openPath,
                    presentation = spec["presentation"]?.jsonPrimitive?.contentOrNull ?: "sheet",
                    argsJson = merged.toString(),
                    title = text,
                    widthDp = spec["width"]?.jsonPrimitive?.intOrNull,
                    heightDp = spec["height"]?.jsonPrimitive?.intOrNull,
                )
            )
        } else if (reg.hasUiHandler()) {
            scope.launch {
                runCatching { host.callUiHandler(rt.id, reg.handlerId, merged) }
                    .onFailure { e ->
                        host.recordError(rt.id, e)
                        toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                    }
            }
        }
    }

    Box {
        Surface(
            onClick = {
                if (menuItems.isNotEmpty()) {
                    showMenu = true
                } else {
                    triggerAction()
                }
            },
            shape = RoundedCornerShape(50),
            color = if (compact) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (compact) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (statusColor != null) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }
                if (!iconName.isNullOrBlank()) {
                    Icon(
                        imageVector = resolvePluginIcon(iconName),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                if (!badge.isNullOrBlank()) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }

        if (menuItems.isNotEmpty()) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
            ) {
                menuItems.forEach { item ->
                    // 挂载与更新时已校验每项都有 id 与 title
                    val itemTitle = (item["title"] ?: item.getValue("text")).jsonPrimitive.content
                    val itemAction = item.getValue("id").jsonPrimitive.content
                    val itemIcon = item["icon"]?.jsonPrimitive?.contentOrNull
                    val itemPath = item["path"]?.jsonPrimitive?.contentOrNull
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(itemTitle, style = MaterialTheme.typography.bodyMedium)
                                item["description"]?.jsonPrimitive?.contentOrNull?.let { d ->
                                    Text(
                                        text = d,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        leadingIcon = if (itemIcon != null) {
                            { Icon(resolvePluginIcon(itemIcon), contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        onClick = {
                            showMenu = false
                            if (itemPath != null) {
                                PluginUiEvents.emit(
                                    PluginUiEvent.OpenPanel(
                                        pluginId = rt.id,
                                        path = itemPath,
                                        presentation = item["presentation"]?.jsonPrimitive?.contentOrNull ?: "sheet",
                                        argsJson = payload.toString(),
                                        title = itemTitle,
                                    )
                                )
                            } else {
                                triggerAction(mapOf("action" to JsonPrimitive(itemAction)))
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 聊天页顶栏挂载项（`chat.topbar`：`button` / `badge` / `menu`）。
 */
@Composable
fun PluginChatTopBarMounts(conversationId: String?, assistantId: String?) {
    val host = koinInject<PluginHost>()
    val mounts = rememberPluginUiMounts("chat.topbar")
    if (mounts.isEmpty()) return
    val payload = remember(conversationId, assistantId) {
        buildJsonObject {
            conversationId?.let { put("conversationId", it) }
            assistantId?.let { put("assistantId", it) }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        mounts.forEach { (rt, reg) ->
            PluginPillMountItem(host = host, rt = rt, reg = reg, payload = payload, compact = false)
        }
    }
}

/**
 * 聊天输入栏挂载项（`chat.input`：`button` / `badge` / `menu`）。
 */
@Composable
fun PluginChatInputMounts(conversationId: String?, assistantId: String?) {
    val host = koinInject<PluginHost>()
    val mounts = rememberPluginUiMounts("chat.input")
    if (mounts.isEmpty()) return
    val payload = remember(conversationId, assistantId) {
        buildJsonObject {
            conversationId?.let { put("conversationId", it) }
            assistantId?.let { put("assistantId", it) }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        mounts.forEach { (rt, reg) ->
            PluginPillMountItem(host = host, rt = rt, reg = reg, payload = payload, compact = true)
        }
    }
}

/**
 * 聊天页 `+` 扩展面板挂载项（`chat.more`：`entry` / `button` / `switch` / `form` / `html`）。
 * 没有插件挂载到 `chat.more` 时不渲染任何内容。
 */
@Composable
fun PluginChatMoreMounts(
    conversationId: String,
    assistantId: String,
    onDismissSheet: () -> Unit,
) {
    val host = koinInject<PluginHost>()
    val mounts = rememberPluginUiMounts("chat.more")
    if (mounts.isEmpty()) return
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val contextArgs = remember(conversationId, assistantId) {
        buildJsonObject {
            put("conversationId", conversationId)
            put("assistantId", assistantId)
        }
    }

    fun invokeHandler(rt: PluginRuntime, reg: PluginRegistration, payload: JsonObject = contextArgs) {
        if (!reg.hasUiHandler()) return
        scope.launch {
            runCatching { host.callUiHandler(rt.id, reg.handlerId, payload) }
                .onFailure { e ->
                    host.recordError(rt.id, e)
                    toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                }
        }
    }

    val rowItems = mounts.filter { (_, reg) ->
        reg.uiSpec.getValue("type").jsonPrimitive.content in
            setOf("entry", "button", "switch")
    }
    if (rowItems.isNotEmpty()) {
        var switchTick by remember { mutableIntStateOf(0) }
        CardGroup {
            rowItems.forEach { (rt, reg) ->
                val spec = reg.uiSpec
                val type = spec.getValue("type").jsonPrimitive.content
                val title = spec["title"]?.jsonPrimitive?.contentOrNull
                    ?: spec["text"]?.jsonPrimitive?.contentOrNull
                    ?: rt.manifest.name
                val desc = spec["description"]?.jsonPrimitive?.contentOrNull
                val icon = resolvePluginIcon(spec["icon"]?.jsonPrimitive?.contentOrNull)
                val openPath = spec["path"]?.jsonPrimitive?.contentOrNull
                val switchKey = spec["switchKey"]?.jsonPrimitive?.contentOrNull
                val badgeText = spec["badge"]?.jsonPrimitive?.contentOrNull
                val storage = host.storage(rt.id)
                @Suppress("UNUSED_EXPRESSION")
                switchTick
                val switchChecked = if (switchKey == null) false
                else (storage.get(switchKey) as? JsonPrimitive)?.booleanOrNull
                    ?: (spec["default"] as? JsonPrimitive)?.booleanOrNull
                    ?: false

                item(
                    onClick = {
                        if (openPath != null) {
                            onDismissSheet()
                            PluginUiEvents.emit(
                                PluginUiEvent.OpenPanel(
                                    pluginId = rt.id,
                                    path = openPath,
                                    presentation = spec["presentation"]?.jsonPrimitive?.contentOrNull ?: "page",
                                    argsJson = contextArgs.toString(),
                                    title = title,
                                    widthDp = spec["width"]?.jsonPrimitive?.intOrNull,
                                    heightDp = spec["height"]?.jsonPrimitive?.intOrNull,
                                )
                            )
                        } else if (switchKey != null && !reg.hasUiHandler()) {
                            val next = !switchChecked
                            storage.set(switchKey, JsonPrimitive(next))
                            switchTick++
                            host.bumpVersion()
                        } else {
                            if (spec["dismissOnClick"]?.jsonPrimitive?.booleanOrNull != false && switchKey == null) {
                                onDismissSheet()
                            }
                            invokeHandler(rt, reg, contextArgs)
                        }
                    },
                    leadingContent = {
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    headlineContent = { Text(title) },
                    supportingContent = if (desc != null) {
                        { Text(desc, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else null,
                    trailingContent = {
                        when {
                            switchKey != null -> me.rerere.rikkahub.ui.custom.UISwitch(
                                checked = switchChecked,
                                onCheckedChange = { next ->
                                    storage.set(switchKey, JsonPrimitive(next))
                                    switchTick++
                                    host.bumpVersion()
                                    invokeHandler(
                                        rt,
                                        reg,
                                        JsonObject(
                                            contextArgs + mapOf(
                                                "type" to JsonPrimitive("change"),
                                                "key" to JsonPrimitive(switchKey),
                                                "value" to JsonPrimitive(next),
                                            )
                                        ),
                                    )
                                },
                            )
                            !badgeText.isNullOrBlank() -> Badge(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Text(badgeText)
                            }
                            else -> Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        }
                    },
                )
            }
        }
    }

    mounts.forEach { (rt, reg) ->
        val spec = reg.uiSpec
        when (spec.getValue("type").jsonPrimitive.content) {
            "form" -> PluginFormCardGroup(
                host = host,
                runtime = rt,
                reg = reg,
                spec = spec,
                contextArgs = contextArgs,
                onInvoke = { _, _, payload -> invokeHandler(rt, reg, payload) },
            )
            "html" -> {
                val path = spec["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val inlineHtml = spec["html"]?.jsonPrimitive?.contentOrNull
                if (path.isNotEmpty() || inlineHtml != null) {
                    val explicitHeight = spec["height"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
                    PluginWebViewBox(
                        pluginId = rt.id,
                        path = path,
                        inlineHtml = inlineHtml,
                        argsJson = contextArgs.toString(),
                        autoHeight = explicitHeight == null,
                        initialHeightDp = explicitHeight ?: 160,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (explicitHeight != null) Modifier.height(explicitHeight.dp) else Modifier),
                    )
                }
            }
        }
    }
}

/**
 * 判断消息是否满足挂载项 `spec` 中的可选过滤条件（`roles`、`hasCode`、`hasImage`、`hasTool`）。
 */
internal fun matchesMessageFilter(spec: JsonObject, message: UIMessage): Boolean {
    val roles = (spec["roles"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
    if (!roles.isNullOrEmpty() && message.role.name.lowercase() !in roles) return false
    val singleRole = spec["role"]?.jsonPrimitive?.contentOrNull?.lowercase()
    if (!singleRole.isNullOrEmpty() && message.role.name.lowercase() != singleRole) return false
    if (spec["hasCode"]?.jsonPrimitive?.booleanOrNull == true) {
        if (!message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("```") }) return false
    }
    if (spec["hasImage"]?.jsonPrimitive?.booleanOrNull == true) {
        if (!message.parts.any { it is UIMessagePart.Image }) return false
    }
    if (spec["hasTool"]?.jsonPrimitive?.booleanOrNull == true) {
        if (message.getTools().isEmpty()) return false
    }
    return true
}

/**
 * 消息底部附加栏挂载项（`message.footer`：`badge` / `button` / `html` / `renderer`）。
 * 没有匹配挂载项时不渲染任何内容。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluginMessageFooterMounts(
    message: UIMessage,
    conversationId: String?,
    assistantId: String?,
) {
    val host = koinInject<PluginHost>()
    val allMounts = rememberPluginUiMounts("message.footer")
    if (allMounts.isEmpty()) return
    val matched = remember(allMounts, message) {
        allMounts.filter { (_, reg) ->
            val spec = reg.uiSpec
            matchesMessageFilter(spec, message)
        }
    }
    if (matched.isEmpty()) return

    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val msgPayload = remember(message, conversationId, assistantId) {
        buildJsonObject {
            put("messageId", message.id.toString())
            put("role", message.role.name.lowercase())
            put("text", message.toText())
            conversationId?.let { put("conversationId", it) }
            assistantId?.let { put("assistantId", it) }
        }
    }

    val pills = matched.filter { (_, reg) ->
        reg.uiSpec.getValue("type").jsonPrimitive.content in
            setOf("badge", "button")
    }
    if (pills.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            pills.forEach { (rt, reg) ->
                PluginPillMountItem(host = host, rt = rt, reg = reg, payload = msgPayload, compact = true)
            }
        }
    }

    matched.forEach { (rt, reg) ->
        val spec = reg.uiSpec
        val type = spec.getValue("type").jsonPrimitive.content
        if (type == "html" || type == "renderer") {
            val path = spec["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val inlineHtml = spec["html"]?.jsonPrimitive?.contentOrNull
            val explicitHeight = spec["height"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            if (path.isNotEmpty() || inlineHtml != null) {
                PluginWebViewBox(
                    pluginId = rt.id,
                    path = path,
                    inlineHtml = inlineHtml,
                    argsJson = msgPayload.toString(),
                    autoHeight = explicitHeight == null,
                    initialHeightDp = explicitHeight ?: 120,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .then(if (explicitHeight != null) Modifier.height(explicitHeight.dp) else Modifier),
                )
            } else if (reg.hasUiHandler()) {
                val rendered = rememberPluginRendered(rt.id, reg.handlerId, msgPayload, message.id, message.parts)
                rendered?.exceptionOrNull()?.let { PluginRenderError(it, Modifier.padding(top = 4.dp)) }
                val content = rendered?.getOrNull()
                if (!content.isNullOrBlank()) {
                    if (content.trimStart().startsWith("<")) {
                        PluginWebViewBox(
                            pluginId = rt.id,
                            path = "",
                            inlineHtml = content,
                            argsJson = msgPayload.toString(),
                            autoHeight = explicitHeight == null,
                            initialHeightDp = explicitHeight ?: 120,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .then(if (explicitHeight != null) Modifier.height(explicitHeight.dp) else Modifier),
                        )
                    } else {
                        MarkdownBlock(content = content, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/**
 * 查找是否有插件通过 `rikka.ui.mount("tool.card", { type: "renderer", toolNames: ["..."] })` 接管该工具卡片。
 */
@Composable
fun rememberPluginToolCardMount(toolName: String): Pair<PluginRuntime, PluginRegistration>? {
    val mounts = rememberPluginUiMounts("tool.card")
    return remember(mounts, toolName) {
        mounts.find { (_, reg) ->
            val spec = reg.uiSpec
            val names = spec.getValue("toolNames").jsonArray.map { it.jsonPrimitive.content }
            toolName in names || "*" in names
        }
    }
}
