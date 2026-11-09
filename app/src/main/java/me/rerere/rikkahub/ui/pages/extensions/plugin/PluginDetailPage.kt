package me.rerere.rikkahub.ui.pages.extensions.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PERMISSION_LABELS = mapOf(
    "network" to "联网（rikka.fetch）",
    "conversation.read" to "读取对话",
    "conversation.write" to "修改对话",
    "subagent" to "操作子代理",
    "settings.read" to "读取设置",
    "settings.write" to "修改设置",
    "notification" to "发送通知",
    "device" to "剪贴板 / 朗读",
    "workspace" to "工作区文件、shell 与终端",
    "memory" to "读写助手记忆",
)

private val KIND_LABELS = mapOf(
    "tool" to "工具",
    "intercept" to "请求拦截",
    "stream" to "流式事件改写",
    "messages.beforeSend" to "发送前改写消息",
    "response.visual" to "流式显示改写",
    "response.finish" to "生成结束改写",
    "tool.before" to "工具执行前",
    "tool.after" to "工具执行后",
    "approval.decide" to "审批决策",
    "macro" to "宏",
    "ui" to "界面挂载",
    "expose" to "暴露方法",
    "chat.history" to "对话历史改写（仅真实历史）",
    "preset.beforeAssemble" to "预设块组装前改写",
    "prompt.assembled" to "预设组装后改写",
    "lorebook.activated" to "世界书激活改写",
    "promptBlock" to "自定义提示词块",
    "regex" to "正则替换",
    "conversation.beforeSave" to "对话保存前改写",
    "search.results" to "搜索结果改写",
    "tools.list" to "工具列表改写",
    "params.beforeSend" to "请求参数改写",
    "input.beforeSave" to "用户输入保存前改写",
    "attachment.parse" to "附件文档解析",
    "provider" to "AI 提供商实现",
    "search" to "搜索引擎实现",
    "tts" to "TTS 语音合成实现",
    "asr" to "ASR 语音识别实现",
    "mediagen" to "媒体生成实现",
    "share" to "系统分享处理",
    "schedule" to "定时任务",
    "web" to "Web 服务路由",
)

@Composable
fun PluginDetailPage(id: String) {
    val host = koinInject<PluginHost>()
    val installed by host.installed.collectAsStateWithLifecycle()
    val states by host.states.collectAsStateWithLifecycle()
    val active by host.activeRuntimes.collectAsStateWithLifecycle()
    val errors by host.errors.collectAsStateWithLifecycle()
    val logs by host.logs.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val plugin = installed.find { it.id == id }
    val runtime: PluginRuntime? = active[id]
    val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    var editLimit by remember { mutableStateOf<String?>(null) }
    editLimit?.let { kind ->
        LimitDialog(
            title = if (kind == "timeout") "调用超时（毫秒，0 = 不限）" else "内存上限（MB）",
            initial = if (kind == "timeout") (states[id]?.callTimeoutMillis ?: PluginRuntime.DEFAULT_CALL_TIMEOUT_MS).toString()
            else (states[id]?.memoryLimitMb ?: PluginRuntime.DEFAULT_MEMORY_LIMIT_MB).toString(),
            onDismiss = { editLimit = null },
            onConfirm = { text ->
                val value = text.trim().toLongOrNull()
                if (value == null || value < 0 || (kind == "memory" && value < 8)) {
                    toaster.show("数值不合法", type = ToastType.Error)
                } else {
                    scope.launch {
                        host.updateState(id) {
                            if (kind == "timeout") it.copy(callTimeoutMillis = value) else it.copy(memoryLimitMb = value.toInt())
                        }
                    }
                    editLimit = null
                }
            },
        )
    }

    val disabledKeys = states[id]?.disabled.orEmpty()
    val pageUiRegs = runtime?.registrations.orEmpty().filter { reg ->
        reg.kind == "ui" && reg.key !in disabledKeys &&
            ((reg.meta as? JsonObject)?.get("spec") as? JsonObject)
                ?.get("type")?.jsonPrimitive?.contentOrNull in setOf("entry", "form", "html")
    }
    val entries = pageUiRegs.filter { reg ->
        ((reg.meta as? JsonObject)?.get("spec") as? JsonObject)
            ?.get("type")?.jsonPrimitive?.contentOrNull == "entry"
    }
    val formsAndHtml = pageUiRegs.filter { reg ->
        ((reg.meta as? JsonObject)?.get("spec") as? JsonObject)
            ?.get("type")?.jsonPrimitive?.contentOrNull in setOf("form", "html")
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(plugin?.manifest?.name ?: id) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (plugin == null) {
            Text("插件 $id 未安装", modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text("基本信息") }) {
                    switchItem(
                        checked = states[id]?.enabled == true,
                        onCheckedChange = { checked ->
                            scope.launch {
                                runCatching { host.setEnabled(id, checked) }.onFailure {
                                    host.recordError(id, it)
                                    toaster.show(it.message ?: it.javaClass.name, type = ToastType.Error)
                                }
                            }
                        },
                        headlineContent = { Text("启用") },
                        supportingContent = {
                            val fatal = runtime?.fatalError
                            Text(
                                when {
                                    fatal != null -> "已终止：${fatal.message}"
                                    runtime != null -> "运行中"
                                    states[id]?.enabled == true -> "启用失败，见下方最近错误"
                                    else -> "未运行"
                                }
                            )
                        },
                    )
                    item(headlineContent = { Text("ID") }, supportingContent = { Text(plugin.id) })
                    item(headlineContent = { Text("版本") }, supportingContent = { Text(plugin.manifest.version) })
                    if (plugin.manifest.description.isNotBlank()) {
                        item(headlineContent = { Text("说明") }, supportingContent = { Text(plugin.manifest.description) })
                    }
                    item(
                        onClick = { editLimit = "timeout" },
                        headlineContent = { Text("调用超时") },
                        supportingContent = {
                            val t = states[id]?.callTimeoutMillis
                            Text(
                                (if (t == 0L) "不限" else "${t ?: PluginRuntime.DEFAULT_CALL_TIMEOUT_MS} ms") +
                                    "（intercept 与流式响应体不受此限制）"
                            )
                        },
                    )
                    item(
                        onClick = { editLimit = "memory" },
                        headlineContent = { Text("内存上限") },
                        supportingContent = { Text("${states[id]?.memoryLimitMb ?: PluginRuntime.DEFAULT_MEMORY_LIMIT_MB} MB（重新启用后生效）") },
                    )
                }
            }
            if (runtime == null || entries.isNotEmpty() || formsAndHtml.isEmpty()) {
                item {
                    CardGroup(title = { Text("插件界面") }) {
                        when {
                            runtime == null -> item(headlineContent = { Text("插件未运行，打开上方「启用」后显示") })
                            pageUiRegs.isEmpty() -> item(headlineContent = { Text("该插件没有提供界面") })
                        }
                        entries.forEach { reg ->
                            val spec = (reg.meta as JsonObject)["spec"] as JsonObject
                            val title = spec["title"]?.jsonPrimitive?.contentOrNull ?: "打开"
                            val openPath = spec["path"]?.jsonPrimitive?.contentOrNull
                            item(
                                onClick = {
                                    if (openPath != null) {
                                        me.rerere.rikkahub.data.plugin.PluginUiEvents.emit(
                                            me.rerere.rikkahub.data.plugin.PluginUiEvent.OpenPanel(
                                                pluginId = id,
                                                path = openPath,
                                                presentation = spec["presentation"]?.jsonPrimitive?.contentOrNull ?: "page",
                                                argsJson = "{}",
                                                title = title,
                                            )
                                        )
                                    } else {
                                        scope.launch {
                                            runCatching { host.callUiHandler(id, reg.handlerId, JsonObject(emptyMap())) }
                                                .onFailure { e ->
                                                    host.recordError(id, e)
                                                    toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                                                }
                                        }
                                    }
                                },
                                headlineContent = { Text(title) },
                                supportingContent = spec["description"]?.jsonPrimitive?.contentOrNull?.let { d -> { Text(d) } },
                            )
                        }
                    }
                }
            }
            if (runtime != null && formsAndHtml.isNotEmpty()) {
                items(formsAndHtml, key = { it.key }) { reg ->
                    val spec = (reg.meta as? JsonObject)?.get("spec") as? JsonObject ?: return@items
                    when (spec["type"]?.jsonPrimitive?.contentOrNull) {
                        "form" -> PluginFormCardGroup(
                            host = host,
                            runtime = runtime,
                            reg = reg,
                            spec = spec,
                            contextArgs = JsonObject(emptyMap()),
                            onInvoke = { targetPluginId, handlerId, payload ->
                                scope.launch {
                                    runCatching { host.callUiHandler(targetPluginId, handlerId, payload) }
                                        .onFailure { e ->
                                            host.recordError(targetPluginId, e)
                                            toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                                        }
                                }
                            },
                        )
                        "html" -> {
                            val path = spec["path"]?.jsonPrimitive?.contentOrNull ?: return@items
                            val title = spec["title"]?.jsonPrimitive?.contentOrNull ?: "插件面板"
                            val heightDp = (spec["height"]?.jsonPrimitive?.intOrNull ?: 220).coerceIn(80, 800)
                            CardGroup(title = { Text(title) }) {
                                formItem {
                                    PluginWebViewBox(
                                        pluginId = id,
                                        path = path,
                                        argsJson = "{}",
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
            item {
                CardGroup(title = { Text("权限（manifest 声明）") }) {
                    if (plugin.manifest.permissions.isEmpty()) {
                        item(headlineContent = { Text("不需要任何宿主权限") })
                    }
                    plugin.manifest.permissions.sorted().forEach { p ->
                        item(headlineContent = { Text(PERMISSION_LABELS[p] ?: p) }, supportingContent = { Text(p) })
                    }
                }
            }
            item {
                CardGroup(title = { Text("实际注册的能力（可单独关闭）") }) {
                    val regs = runtime?.registrations.orEmpty()
                    if (runtime == null) {
                        item(headlineContent = { Text("插件未运行，启用后显示") })
                    } else if (regs.isEmpty()) {
                        item(headlineContent = { Text("没有注册任何内容") })
                    }
                    regs.forEach { reg ->
                        val meta = reg.meta as? JsonObject
                        val detail = meta?.get("name")?.jsonPrimitive?.contentOrNull
                            ?: meta?.get("slot")?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val label = KIND_LABELS[reg.kind]
                            ?: if (reg.kind.startsWith("event:")) "事件 ${reg.kind.removePrefix("event:")}" else reg.kind
                        val disabled = reg.key in states[id]?.disabled.orEmpty()
                        switchItem(
                            checked = !disabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    host.updateState(id) { st ->
                                        st.copy(disabled = if (on) st.disabled - reg.key else st.disabled + reg.key)
                                    }
                                }
                            },
                            headlineContent = { Text(label) },
                            supportingContent = if (detail.isNotEmpty()) ({ Text(detail) }) else null,
                        )
                    }
                }
            }
            item {
                CardGroup(title = { Text("最近错误") }) {
                    val list = errors.filter { it.pluginId == id }.takeLast(20).reversed()
                    if (list.isEmpty()) item(headlineContent = { Text("无") })
                    list.forEach { e ->
                        item(
                            overlineContent = { Text(timeFormat.format(Date(e.at))) },
                            headlineContent = { Text(e.message, color = MaterialTheme.colorScheme.error) },
                        )
                    }
                }
            }
            item {
                CardGroup(title = { Text("日志") }) {
                    val list = logs.filter { it.pluginId == id }.takeLast(50).reversed()
                    if (list.isEmpty()) item(headlineContent = { Text("无") })
                    list.forEach { l ->
                        item(
                            overlineContent = { Text("${timeFormat.format(Date(l.at))}  ${l.level}") },
                            headlineContent = { Text(l.text) },
                        )
                    }
                }
            }
            item {
                CardGroup {
                    item(
                        onClick = {
                            scope.launch {
                                runCatching { host.uninstall(id) }
                                    .onSuccess { navController.popBackStack() }
                                    .onFailure { toaster.show(it.message ?: "卸载失败", type = ToastType.Error) }
                            }
                        },
                        headlineContent = { Text("卸载插件", color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text("删除插件文件与插件存储") },
                    )
                }
            }
        }
    }
}

@Composable
private fun LimitDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
