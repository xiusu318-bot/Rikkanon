package me.rerere.plugin.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PLUGIN_API_VERSION = 1

/**
 * 插件清单。只描述"插件是谁"与"插件会用到哪些宿主能力"（[permissions]）。
 * 插件注册了哪些工具 / hook / UI 由运行时实际注册决定，不在清单中声明。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val main: String = "main.js",
    val description: String = "",
    val permissions: Set<String> = emptySet(),
) {
    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9_.-]{1,64}$")
        private val json = Json { ignoreUnknownKeys = false }

        /** 解析并校验 manifest；不合法直接抛异常。 */
        fun parse(text: String): PluginManifest {
            val manifest = json.decodeFromString(serializer(), text)
            require(ID_REGEX.matches(manifest.id)) { "manifest.id 不合法: ${manifest.id}" }
            require(manifest.name.isNotBlank()) { "manifest.name 不能为空" }
            require(manifest.apiVersion == PLUGIN_API_VERSION) {
                "不支持的 apiVersion ${manifest.apiVersion}，当前宿主为 $PLUGIN_API_VERSION"
            }
            require(!manifest.main.contains("..")) { "manifest.main 不合法: ${manifest.main}" }
            manifest.permissions.forEach { require(it in PluginPermission.ALL) { "未知权限: $it" } }
            return manifest
        }
    }
}

/** 宿主已知的注册类型。注册未知类型直接报错（否则会静默永不触发）。 */
object PluginHookKind {
    const val TOOL = "tool"
    const val INTERCEPT = "intercept"
    const val STREAM = "stream"
    const val MESSAGES_BEFORE_SEND = "messages.beforeSend"
    const val RESPONSE_VISUAL = "response.visual"
    const val RESPONSE_FINISH = "response.finish"
    const val TOOL_BEFORE = "tool.before"
    const val TOOL_AFTER = "tool.after"
    const val APPROVAL = "approval.decide"
    const val MACRO = "macro"
    const val UI = "ui"
    const val EXPOSE = "expose"

    // 提示词 / 世界书 / 正则
    const val CHAT_HISTORY = "chat.history"
    const val PRESET_BEFORE_ASSEMBLE = "preset.beforeAssemble"
    const val PROMPT_ASSEMBLED = "prompt.assembled"
    const val LOREBOOK_ACTIVATED = "lorebook.activated"
    const val PROMPT_BLOCK = "promptBlock"
    const val REGEX = "regex"

    // 数据
    const val CONVERSATION_BEFORE_SAVE = "conversation.beforeSave"
    const val SEARCH_RESULTS = "search.results"
    const val TOOLS_LIST = "tools.list"
    const val PARAMS_BEFORE_SEND = "params.beforeSend"
    const val INPUT_BEFORE_SAVE = "input.beforeSave"
    const val ATTACHMENT_PARSE = "attachment.parse"

    // 插件提供实现
    const val PROVIDER = "provider"
    const val SEARCH = "search"
    const val TTS = "tts"
    const val ASR = "asr"
    const val MEDIAGEN = "mediagen"

    // 触发入口
    const val SHARE = "share"
    const val SCHEDULE = "schedule"
    const val WEB = "web"

    const val EVENT_PREFIX = "event:"

    /** 后台任务：<task>.before / <task>.after / <task>.replace */
    val TASKS = setOf("title", "suggestion", "compress", "memory", "translate", "ocr")
    val TASK_STAGES = setOf("before", "after", "replace")
    fun task(task: String, stage: String) = "$task.$stage"

    val ALL: Set<String> = setOf(
        TOOL, INTERCEPT, STREAM, MESSAGES_BEFORE_SEND, RESPONSE_VISUAL, RESPONSE_FINISH,
        TOOL_BEFORE, TOOL_AFTER, APPROVAL, MACRO, UI, EXPOSE,
        CHAT_HISTORY, PRESET_BEFORE_ASSEMBLE, PROMPT_ASSEMBLED, LOREBOOK_ACTIVATED, PROMPT_BLOCK, REGEX,
        CONVERSATION_BEFORE_SAVE, SEARCH_RESULTS, TOOLS_LIST, PARAMS_BEFORE_SEND, INPUT_BEFORE_SAVE, ATTACHMENT_PARSE,
        PROVIDER, SEARCH, TTS, ASR, MEDIAGEN, SHARE, SCHEDULE, WEB,
    ) + TASKS.flatMap { t -> TASK_STAGES.map { task(t, it) } }

    /** 同一插件内按 meta.name 不能重复的注册类型。 */
    val NAMED_UNIQUE = setOf(TOOL, EXPOSE, PROVIDER, SEARCH, TTS, ASR, MEDIAGEN, PROMPT_BLOCK, SHARE, SCHEDULE, WEB, MACRO, REGEX)

    /** 可订阅事件（rikka.on("event:<name>")）。只列出宿主实际会派发的事件。 */
    val EVENTS = setOf(
        "generation.start", "generation.step", "generation.finish", "generation.error", "generation.stopped",
        "conversation.create", "conversation.fork", "conversation.delete",
        "message.send", "steer.injected", "subagent.status", "context.compressed",
        "app.foreground", "app.background", "usage",
    )

    fun isKnown(kind: String): Boolean =
        kind in ALL || (kind.startsWith(EVENT_PREFIX) && kind.removePrefix(EVENT_PREFIX) in EVENTS)
}

/**
 * 宿主能力权限。插件调用对应宿主 API 时，manifest 未声明则直接报错。
 * 详情页展示的权限列表即插件真实可用的能力。
 * log / storage / UI 挂载 / 注册 hook 与工具不需要权限。
 */
object PluginPermission {
    const val NETWORK = "network"
    const val CONVERSATION_READ = "conversation.read"
    const val CONVERSATION_WRITE = "conversation.write"
    const val SUBAGENT = "subagent"
    const val SETTINGS_READ = "settings.read"
    const val SETTINGS_WRITE = "settings.write"
    const val NOTIFICATION = "notification"
    const val DEVICE = "device"
    const val WORKSPACE = "workspace"
    const val MEMORY = "memory"
    val ALL = setOf(
        NETWORK, CONVERSATION_READ, CONVERSATION_WRITE, SUBAGENT,
        SETTINGS_READ, SETTINGS_WRITE, NOTIFICATION, DEVICE, WORKSPACE, MEMORY,
    )
}

/**
 * 宿主预留的 UI 挂载点及各挂载点支持的 spec.type。插件运行时 `rikka.ui.mount(slot, spec)` 自行决定挂在哪里；
 * 未预留的位置、或挂载点不支持的类型，注册即报错。
 */
object PluginUiSlot {
    private val PAGE_TYPES = setOf("entry", "form", "html")

    val TYPES: Map<String, Set<String>> = mapOf(
        "plugin.detail" to PAGE_TYPES,        // 插件自身详情页（「插件界面」区域）
        "extensions.list" to PAGE_TYPES,      // 「扩展」页列表
        "settings.group" to PAGE_TYPES,       // 设置页分组
        "assistant.detail" to PAGE_TYPES,     // 助手详情分类列表
        "provider.detail" to PAGE_TYPES,      // 提供商详情
        "mcp.detail" to PAGE_TYPES,           // MCP 详情
        "workspace.detail" to PAGE_TYPES,     // 工作区详情
        "chat.topbar" to setOf("button", "badge"), // 聊天页顶栏
        "chat.input" to setOf("button"),      // 输入栏按钮
        "chat.slash" to setOf("command"),     // 斜杠命令
        "message.menu" to setOf("menuItem"),  // 消息长按菜单
        "markdown.codeblock" to setOf("renderer"), // 代码块渲染（spec.languages）
        "message.part" to setOf("renderer"),  // 消息部件渲染（spec.rendererType 对应 metadata["rikka.renderer"]）
    )

    val ALL: Set<String> get() = TYPES.keys

    /** 插件自有面板（HTML）的打开方式，由插件决定。 */
    val PRESENTATIONS = setOf("page", "sheet", "dialog", "floating")

    /** form 字段类型 */
    val FORM_FIELD_TYPES = setOf("switch", "text", "textarea", "number", "select", "multiselect", "list", "button")
}
