package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.plugin.registry.PluginRegistration
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.components.SelectTextField
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

/**
 * 取出当前某个 UI 挂载点上所有启用的注册项（随 registrationsVersion 重组）。
 */
@Composable
fun rememberPluginUiMounts(slot: String, pluginId: String? = null): List<Pair<PluginRuntime, PluginRegistration>> {
    val host = koinInject<PluginHost>()
    val version by host.registrationsVersion.collectAsStateWithLifecycle()
    return remember(slot, pluginId, version) {
        host.chain(PluginHookKind.UI).filter { (rt, reg) ->
            (pluginId == null || rt.id == pluginId) &&
                (reg.meta as? JsonObject)?.get("slot")?.jsonPrimitive?.contentOrNull == slot
        }
    }
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

    fun invokeHandler(targetPluginId: String, handlerId: String, payload: JsonObject = contextArgs) {
        scope.launch {
            runCatching { host.callUiHandler(targetPluginId, handlerId, payload) }
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
            ((reg.meta as? JsonObject)?.get("spec") as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "entry"
        }
        if (entries.isNotEmpty()) {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup(title = { Text("插件") }) {
                entries.forEach { (rt, reg) ->
                    val spec = (reg.meta as JsonObject)["spec"] as JsonObject
                    val title = spec["title"]?.jsonPrimitive?.contentOrNull ?: rt.manifest.name
                    val desc = spec["description"]?.jsonPrimitive?.contentOrNull
                    val openPath = spec["path"]?.jsonPrimitive?.contentOrNull
                    item(
                        onClick = {
                            if (openPath != null) {
                                me.rerere.rikkahub.data.plugin.PluginUiEvents.emit(
                                    me.rerere.rikkahub.data.plugin.PluginUiEvent.OpenPanel(
                                        pluginId = rt.id,
                                        path = openPath,
                                        presentation = spec["presentation"]?.jsonPrimitive?.contentOrNull ?: "page",
                                        argsJson = contextArgs.toString(),
                                        title = title,
                                    )
                                )
                            } else {
                                invokeHandler(rt.id, reg.handlerId)
                            }
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Puzzle, containerColor = containerColor, contentColor = contentColor)
                        },
                        headlineContent = { Text(title) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    )
                }
            }
        }

        mounts.forEach { (rt, reg) ->
            val spec = (reg.meta as? JsonObject)?.get("spec") as? JsonObject ?: return@forEach
            when (spec["type"]?.jsonPrimitive?.contentOrNull) {
                "form" -> PluginFormCardGroup(
                    host = host,
                    runtime = rt,
                    reg = reg,
                    spec = spec,
                    contextArgs = contextArgs,
                    onInvoke = ::invokeHandler,
                )

                "html" -> {
                    val path = spec["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val title = spec["title"]?.jsonPrimitive?.contentOrNull
                    val heightDp = (spec["height"]?.jsonPrimitive?.intOrNull ?: 220).coerceIn(80, 800)
                    CardGroup(title = title?.let { t -> { Text(t) } }) {
                        formItem {
                            PluginWebViewBox(
                                pluginId = rt.id,
                                path = path,
                                argsJson = contextArgs.toString(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(heightDp.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

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
    }

    val title = spec["title"]?.jsonPrimitive?.contentOrNull ?: runtime.manifest.name
    val fields = (spec["fields"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun isVisible(field: JsonObject): Boolean {
        val cond = field["visibleWhen"] as? JsonObject ?: return true
        val condKey = cond["key"]?.jsonPrimitive?.contentOrNull ?: return true
        val expected = cond["equals"] ?: JsonPrimitive(true)
        val actual = readKey(condKey, null) ?: JsonNull
        return actual.toString() == expected.toString() ||
            ((actual as? JsonPrimitive)?.contentOrNull == (expected as? JsonPrimitive)?.contentOrNull)
    }

    CardGroup(title = { Text(title) }) {
        fields.filter(::isVisible).forEach { field ->
            val type = field["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val key = field["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = field["label"]?.jsonPrimitive?.contentOrNull ?: field["title"]?.jsonPrimitive?.contentOrNull ?: key
            val desc = field["description"]?.jsonPrimitive?.contentOrNull
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
                            singleLine = type == "text",
                            minLines = if (type == "textarea") 3 else 1,
                            maxLines = if (type == "textarea") 8 else 1,
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
                                    placeholder = { Text("新增项") },
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
                    formItem(
                        headlineContent = { Text(label) },
                        supportingContent = desc?.let { d -> { Text(d) } },
                    ) {
                        Button(
                            onClick = {
                                onInvoke(
                                    runtime.id,
                                    reg.handlerId,
                                    JsonObject(contextArgs + ("action" to JsonPrimitive(action))),
                                )
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
 * 聊天页顶栏挂载项（`chat.topbar`：`button` / `badge`）。
 */
@Composable
fun PluginChatTopBarMounts(conversationId: String?, assistantId: String?) {
    val host = koinInject<PluginHost>()
    val mounts = rememberPluginUiMounts("chat.topbar")
    if (mounts.isEmpty()) return
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
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
            val spec = (reg.meta as? JsonObject)?.get("spec") as? JsonObject ?: return@forEach
            val text = spec["text"]?.jsonPrimitive?.contentOrNull
                ?: spec["title"]?.jsonPrimitive?.contentOrNull
                ?: rt.manifest.name
            Surface(
                onClick = {
                    scope.launch {
                        runCatching { host.callUiHandler(rt.id, reg.handlerId, payload) }
                            .onFailure { e ->
                                host.recordError(rt.id, e)
                                toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                            }
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * 聊天输入栏挂载项（`chat.input`：`button`）。
 */
@Composable
fun PluginChatInputMounts(conversationId: String?, assistantId: String?) {
    val host = koinInject<PluginHost>()
    val mounts = rememberPluginUiMounts("chat.input")
    if (mounts.isEmpty()) return
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val payload = remember(conversationId, assistantId) {
        buildJsonObject {
            conversationId?.let { put("conversationId", it) }
            assistantId?.let { put("assistantId", it) }
        }
    }

    mounts.forEach { (rt, reg) ->
        val spec = (reg.meta as? JsonObject)?.get("spec") as? JsonObject ?: return@forEach
        val label = spec["title"]?.jsonPrimitive?.contentOrNull
            ?: spec["text"]?.jsonPrimitive?.contentOrNull
            ?: rt.manifest.name
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching { host.callUiHandler(rt.id, reg.handlerId, payload) }
                        .onFailure { e ->
                            host.recordError(rt.id, e)
                            toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                        }
                }
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
