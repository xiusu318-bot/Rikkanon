package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    // 上下文消息条数上限, 超出后阶梯式截断; 0 表示不限制
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableAutoMemoryConsolidation: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val enableSubAgent: Boolean = false,                // 子代理独立模块开关
    val subAgentIds: Set<Uuid> = emptySet(),            // 勾选绑定的子代理助手 ID 集合（支持单选或多选）
    val subAgentAssistantId: Uuid? = null,              // 兼容旧版单选字段
    val subAgentShareWorkspace: Boolean = true,         // 子代理是否自动继承并共享主对话的工作区与交互式终端池
    val subAgentMaxConcurrency: Int = 2,                // 子代理同时并发运行上限（默认 2，最低 1）
    val subAgentAutoApproveTools: Boolean = true,       // 兼容旧版序列化字段
    val subAgentUserApprovalTools: Set<String> = emptySet(),  // 作为子代理运行时需【用户】审批的工具/类别集合
    val subAgentAgentApprovalTools: Set<String> = emptySet(), // 作为子代理运行时需【主代理】审批的工具/类别集合
    val subAgentMaxSteps: Int = 0,                      // 作为子代理运行时单轮默认最大工具调用步数（0 表示不设限）
    // 主代理对子代理的 4 项进阶控制权限开关（最低基础权限为单次同步派发并仅拿最终回复）
    val subAgentAllowInspect: Boolean = true,           // 权限1：允许窥屏读取子代理状态/消息历史 (conversation_read) 及返回工具步骤 (tool_steps)
    val subAgentAllowMultiTurnAndAsync: Boolean = true, // 权限2：允许对已有子代理多轮追问 (conversation_id) 与后台异步并发 (wait=false)
    val subAgentAllowInterruptAndStop: Boolean = true,  // 权限3：允许中途打断改派 (interrupt=true) 与强制停止 (conversation_stop)
    val subAgentAllowDelete: Boolean = false,           // 权限4：最高权限，允许主代理彻底删除销毁子代理对话 (conversation_delete)
    // 主代理调用子代理相关操作前的审批开关
    val subAgentApproveSpawn: Boolean = false,          // 派发新子代理前需用户审批
    val subAgentApproveFollowUp: Boolean = false,       // 多轮追问子代理前需用户审批
    val subAgentApproveInspect: Boolean = false,        // 窥屏读取子代理前需用户审批
    val subAgentApproveInterruptAndStop: Boolean = false, // 打断或停止子代理前需用户审批
    val subAgentApproveDelete: Boolean = true,          // 删除销毁子代理前需用户审批（默认开启）
    val disabledWorkspaceTools: Set<String> = emptySet(), // 禁用的工作区工具名称集合（默认空集即全部开启）
    val enableSecretKeyAlias: Boolean = false,          // 启用密钥代名词自动替换（工具入参中的 keyName 动态替换为真实密钥）
    val enableAskKeyTool: Boolean = false,              // 启用 ask_key 询问密钥工具（在定义中告知可用代名词并支持弹窗请求/新建密钥）
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val timeReminderIntervalMinutes: Int = 60,          // 时间提醒间隔（分钟，至少 1 分钟）
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
) {
    val effectiveSubAgentIds: Set<Uuid>
        get() = when {
            subAgentIds.isNotEmpty() -> subAgentIds
            subAgentAssistantId != null -> setOf(subAgentAssistantId)
            else -> emptySet()
        }

    val isSubAgentEnabled: Boolean
        get() = (enableSubAgent || LocalToolOption.SubAgent in localTools) && effectiveSubAgentIds.isNotEmpty()

    fun requiresSubAgentUserApproval(
        toolName: String,
        mcpServerNameToId: Map<String, Uuid> = emptyMap(),
    ): Boolean {
        if (!subAgentAutoApproveTools && subAgentUserApprovalTools.isEmpty() && subAgentAgentApprovalTools.isEmpty()) {
            return true
        }
        return matchesSubAgentApprovalRule(toolName, subAgentUserApprovalTools, mcpServerNameToId)
    }

    fun requiresSubAgentAgentApproval(
        toolName: String,
        mcpServerNameToId: Map<String, Uuid> = emptyMap(),
    ): Boolean = matchesSubAgentApprovalRule(toolName, subAgentAgentApprovalTools, mcpServerNameToId)

    /** 工具是否开启了主代理审批（助手配置勾选或插件 approval.decide 返回 "main_agent"）。 */
    fun requiresSubAgentAgentApproval(
        tool: me.rerere.ai.ui.UIMessagePart.Tool,
        mcpServerNameToId: Map<String, Uuid> = emptyMap(),
    ): Boolean {
        if ((tool.metadata?.get(me.rerere.rikkahub.data.plugin.PLUGIN_MAIN_AGENT_APPROVAL_KEY) as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
            return true
        }
        return requiresSubAgentAgentApproval(tool.toolName, mcpServerNameToId)
    }
}

private fun matchesSubAgentApprovalRule(
    toolName: String,
    configuredRules: Set<String>,
    mcpServerNameToId: Map<String, Uuid>,
): Boolean {
    if (configuredRules.isEmpty()) return false
    if (toolName in configuredRules) return true
    if ((toolName == "search_web" || toolName == "scrape_web") && "web_search" in configuredRules) return true
    if (toolName.startsWith("memory_") && "memory" in configuredRules) return true
    if ((toolName == "use_skill" || toolName == "load_skill") && "skills" in configuredRules) return true
    if (toolName.startsWith("mcp__")) {
        val serverName = toolName.removePrefix("mcp__").substringBefore("__")
        if ("mcp__$serverName" in configuredRules) return true
        val serverId = mcpServerNameToId[serverName]
        if (serverId != null && "mcp:$serverId" in configuredRules) return true
    }
    return false
}

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
    LOREBOOK, // 世界书条目正文（仅在组装请求时生效：「仅修改发出」或「直接修改正文」规则）
}

@Serializable
enum class AssistantRegexPhase {
    VISUAL,  // 仅修改显示（面向人类渲染阶段）
    REQUEST, // 仅修改发出（组装请求体发给 AI 阶段）
    PERSIST, // 直接修改正文（两个开关均不勾选时，在发送/生成完成落库瞬间执行一次）
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式（支持 /pattern/flags 或普通正则）
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(AssistantAffectScope.ASSISTANT),
    val visualOnly: Boolean = true, // 仅修改显示（新建默认勾选）
    val requestOnly: Boolean = true, // 仅修改发出（新建默认勾选）
    // 深度范围（对齐酒馆：0 = 最后一条消息，往上依次 +1）；null = 无限（不限制）
    val minDepth: Int? = null, // 最小深度：depth < minDepth 时跳过
    val maxDepth: Int? = null, // 最大深度：depth > maxDepth 时跳过
) {
    fun matchesPhase(phase: AssistantRegexPhase): Boolean = when (phase) {
        AssistantRegexPhase.VISUAL -> visualOnly
        AssistantRegexPhase.REQUEST -> requestOnly
        AssistantRegexPhase.PERSIST -> !visualOnly && !requestOnly
    }

    /**
     * 深度过滤（对齐酒馆 getRegexedString）：[depth] 为 null 表示该文本没有深度概念（如非 @D 世界书条目），不做深度限制
     */
    fun matchesDepth(depth: Int?): Boolean {
        if (depth == null) return true
        if (minDepth != null && minDepth >= 0 && depth < minDepth) return false
        if (maxDepth != null && maxDepth >= 0 && depth > maxDepth) return false
        return true
    }
}

private data class RegexCompileCacheKey(
    val pattern: String,
    val optionsMask: Int,
)

internal data class ActiveRegexRuleKey(
    val findRegex: String,
    val replaceString: String,
)

internal data class RegexReplaceCacheKey(
    val text: String,
    val rules: List<ActiveRegexRuleKey>,
)

private const val MAX_CACHEABLE_TEXT_LENGTH = 65_536
private const val MAX_REGEX_RESULT_CACHE_CHARS = 2_000_000

// 统一缓存编译后的正则表达式（同时供助手正则与世界书关键词正则复用）；编译失败也缓存，避免反复构造异常
private val regexCache = SimpleCache.builder<RegexCompileCacheKey, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

/**
 * 正则替换结果 LRU 缓存，容量按字符数计（原文 + 与原文不同的替换结果），超出时淘汰最久未使用的条目
 */
internal class RegexResultCache(private val maxChars: Int) {
    private val map = LinkedHashMap<RegexReplaceCacheKey, String>(64, 0.75f, true)
    private var totalChars = 0L

    @Synchronized
    fun get(key: RegexReplaceCacheKey): String? = map[key]

    @Synchronized
    fun put(key: RegexReplaceCacheKey, value: String) {
        map.put(key, value)?.let { totalChars -= weight(key, it) }
        totalChars += weight(key, value)
        val iterator = map.entries.iterator()
        while (totalChars > maxChars && iterator.hasNext()) {
            val eldest = iterator.next()
            totalChars -= weight(eldest.key, eldest.value)
            iterator.remove()
        }
    }

    @Synchronized
    fun totalChars(): Long = totalChars

    @Synchronized
    fun size(): Int = map.size

    // 没有任何规则命中时替换结果就是原字符串本身，不重复计入
    private fun weight(key: RegexReplaceCacheKey, value: String): Long =
        key.text.length.toLong() + if (value === key.text) 0 else value.length
}

// 「仅修改显示」与「仅修改发出」分开缓存：发请求时对整段历史的替换不会挤掉列表显示用的结果。
// 「直接修改正文」每条消息只执行一次，不缓存
private val visualRegexResultCache = RegexResultCache(MAX_REGEX_RESULT_CACHE_CHARS)
private val requestRegexResultCache = RegexResultCache(MAX_REGEX_RESULT_CACHE_CHARS)

private fun Set<RegexOption>.toOptionsMask(): Int {
    var mask = 0
    if (RegexOption.IGNORE_CASE in this) mask = mask or 1
    if (RegexOption.MULTILINE in this) mask = mask or 2
    if (RegexOption.DOT_MATCHES_ALL in this) mask = mask or 4
    return mask
}

internal fun compileRegexWithOptionsCached(
    pattern: String,
    options: Set<RegexOption> = emptySet(),
): Regex? {
    if (pattern.isEmpty()) return null
    val key = RegexCompileCacheKey(pattern, options.toOptionsMask())
    regexCache.getIfPresent(key)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern, options) }
    regexCache.put(key, result)
    return result.getOrNull()
}

private fun compileAssistantRegexCached(rawPattern: String): Regex? {
    if (rawPattern.isEmpty()) return null
    // 1. 优先识别 /pattern/flags 格式（如 /<status>[\s\S]*?<\/status>/gi 或 /.../gms）
    parseSlashRegexKeyword(rawPattern)?.let { return it }
    // 2. 普通正则默认启用 MULTILINE（使 ^ 和 $ 能匹配多行文本中的单行首尾）
    return compileRegexWithOptionsCached(rawPattern, setOf(RegexOption.MULTILINE))
}

/**
 * 安全展开正则替换字符串：
 * - 支持 $0、$&、{{match}} 表示完整匹配
 * - 支持 $1..$99 数字捕获组及 ${name} / $<name> 命名捕获组（越界时安全退化为空串，不抛异常）
 * - 支持 \n、\r、\t 换行/制表符转义，同时保留其它反斜杠序列（如 LaTeX 的 \frac、\( 等）
 * - 普通 $（如 LaTeX 的 $$$1$$ 或金额 $100）按字面量 '$' 输出，不会触发 Illegal group reference 崩溃
 */
internal fun expandRegexReplacement(template: String, match: MatchResult): String {
    if (template.isEmpty()) return ""
    if ('$' !in template && '\\' !in template && "{{" !in template) return template

    val sb = StringBuilder(template.length + 16)
    var i = 0
    val len = template.length
    val groupCount = match.groups.size - 1

    while (i < len) {
        val c = template[i]
        if (c == '{' && i + 9 <= len && template.regionMatches(i, "{{match}}", 0, 9, ignoreCase = true)) {
            sb.append(match.value)
            i += 9
            continue
        }
        if (c == '\\' && i + 1 < len) {
            when (val next = template[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                '\\', '$' -> sb.append(next)
                else -> sb.append('\\').append(next)
            }
            i += 2
            continue
        }
        if (c == '$' && i + 1 < len) {
            val next = template[i + 1]
            when {
                next == '&' -> {
                    sb.append(match.value)
                    i += 2
                    continue
                }
                next in '0'..'9' -> {
                    val d1 = next - '0'
                    if (i + 2 < len && template[i + 2] in '0'..'9') {
                        val d2 = d1 * 10 + (template[i + 2] - '0')
                        if (d2 <= groupCount) {
                            sb.append(match.groups[d2]?.value.orEmpty())
                            i += 3
                            continue
                        }
                    }
                    if (d1 <= groupCount) {
                        sb.append(match.groups[d1]?.value.orEmpty())
                    }
                    i += 2
                    continue
                }
                next == '{' || next == '<' -> {
                    val closeChar = if (next == '{') '}' else '>'
                    val endIdx = template.indexOf(closeChar, startIndex = i + 2)
                    if (endIdx > i + 2) {
                        val groupName = template.substring(i + 2, endIdx)
                        if (groupName.all { it.isLetterOrDigit() || it == '_' }) {
                            val namedVal = runCatching { match.groups[groupName]?.value }.getOrNull()
                            if (namedVal != null) {
                                sb.append(namedVal)
                            }
                            i = endIdx + 1
                            continue
                        }
                    }
                    sb.append('$')
                    i += 1
                    continue
                }
                else -> {
                    sb.append('$')
                    i += 1
                    continue
                }
            }
        }
        sb.append(c)
        i += 1
    }
    return sb.toString()
}

/**
 * @param useCache 是否使用结果缓存；正在流式生成的消息应传 false，避免每个 chunk 的中间文本挤占缓存
 * @param depth 文本所在深度（0 = 最后一条消息）；null 表示不做深度限制
 */
fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    phase: AssistantRegexPhase,
    useCache: Boolean = true,
    depth: Int? = null,
): String {
    if (this.isEmpty()) return this
    val afterBuiltin = if (assistant == null || assistant.regexes.isEmpty()) {
        this
    } else {
        val activeRegexes = assistant.regexes.filter { regex ->
            regex.enabled &&
                regex.findRegex.isNotEmpty() &&
                regex.affectingScope.contains(scope) &&
                regex.matchesPhase(phase) &&
                regex.matchesDepth(depth)
        }
        if (activeRegexes.isEmpty()) {
            this
        } else {
            val cache = when (phase) {
                AssistantRegexPhase.VISUAL -> visualRegexResultCache
                AssistantRegexPhase.REQUEST -> requestRegexResultCache
                AssistantRegexPhase.PERSIST -> null
            }
            if (cache == null || !useCache || this.length > MAX_CACHEABLE_TEXT_LENGTH) {
                applyRegexRules(activeRegexes)
            } else {
                val cacheKey = RegexReplaceCacheKey(this, activeRegexes.map { ActiveRegexRuleKey(it.findRegex, it.replaceString) })
                cache.get(cacheKey) ?: applyRegexRules(activeRegexes).also { cache.put(cacheKey, it) }
            }
        }
    }
    val bridge = runCatching {
        org.koin.mp.KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<me.rerere.rikkahub.data.plugin.PluginBridge>()
    }.getOrNull() ?: return afterBuiltin
    return bridge.applyPluginRegexesBlocking(afterBuiltin, assistant, scope, phase, depth)
}

private fun String.applyRegexRules(activeRegexes: List<AssistantRegex>): String =
    activeRegexes.fold(this) { acc, regex ->
        val compiled = compileAssistantRegexCached(regex.findRegex) ?: return@fold acc
        try {
            compiled.replace(acc) { matchResult ->
                expandRegexReplacement(regex.replaceString, matchResult)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            acc
        }
    }

/**
 * 世界书条目正文正则：世界书不面向人类显示，「仅修改发出」与「直接修改正文」（两个开关都不勾）规则在组装请求时生效，
 * 「仅修改显示」规则不生效。[depth] 为条目的 @D 深度，非 @D 条目传 null（不做深度限制，与酒馆一致）。
 */
fun String.replaceLorebookRegexes(
    assistant: Assistant?,
    depth: Int?,
): String {
    if (this.isEmpty()) return this
    val afterBuiltin = if (assistant == null || assistant.regexes.isEmpty()) {
        this
    } else {
        val activeRegexes = assistant.regexes.filter { regex ->
            regex.enabled &&
                regex.findRegex.isNotEmpty() &&
                AssistantAffectScope.LOREBOOK in regex.affectingScope &&
                (regex.matchesPhase(AssistantRegexPhase.REQUEST) || regex.matchesPhase(AssistantRegexPhase.PERSIST)) &&
                regex.matchesDepth(depth)
        }
        if (activeRegexes.isEmpty()) this else applyRegexRules(activeRegexes)
    }
    val bridge = runCatching {
        org.koin.mp.KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<me.rerere.rikkahub.data.plugin.PluginBridge>()
    }.getOrNull() ?: return afterBuiltin
    val afterReq = bridge.applyPluginRegexesBlocking(afterBuiltin, assistant, AssistantAffectScope.LOREBOOK, AssistantRegexPhase.REQUEST, depth)
    return bridge.applyPluginRegexesBlocking(afterReq, assistant, AssistantAffectScope.LOREBOOK, AssistantRegexPhase.PERSIST, depth)
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false,
): String = replaceRegexes(
    assistant = assistant,
    scope = scope,
    phase = if (visual) AssistantRegexPhase.VISUAL else AssistantRegexPhase.PERSIST,
)

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 角色定义之前 (World Info Before)

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 角色定义之后 (World Info After)

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（等同于 AT_DEPTH depth=9999）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（等同于 AT_DEPTH depth=1）

    @SerialName("at_depth")
    AT_DEPTH,               // @D 在指定深度位置插入（从最新消息往前数）

    @SerialName("outlet")
    OUTLET,                 // ➡️ 锚点（通过 {{outlet::名称}} 引用展开）
}

/**
 * 世界书次要关键词（可选过滤器）匹配逻辑
 */
@Serializable
enum class SelectiveLogic {
    @SerialName("and_any")
    AND_ANY,  // 0: 与任意 (AND ANY)

    @SerialName("not_all")
    NOT_ALL,  // 1: 非全部 (NOT ALL)

    @SerialName("not_any")
    NOT_ANY,  // 2: 非任意 (NOT ANY)

    @SerialName("and_all")
    AND_ALL,  // 3: 与全部 (AND ALL)
}

/**
 * 世界书绿灯触发保持与缓存策略
 */
@Serializable
enum class LorebookGreenLightMode {
    @SerialName("sticky_locked_position")
    STICKY_LOCKED_POSITION, // 默认：触发后持续生效 + 注入位置锚定不变（直至下次压缩，保护缓存）

    @SerialName("sticky_floating")
    STICKY_FLOATING,        // 触发后持续生效 + 深度位置随最新消息浮动（直至下次压缩）

    @SerialName("dynamic_native")
    DYNAMIC_NATIVE,         // 酒馆原生绿灯（仅在最近扫描深度内命中时当轮生效）
}

/**
 * 预设内置不可删除的坐标原点块类型（📌）
 */
@Serializable
enum class PromptMarkerType {
    @SerialName("world_info_before")
    WORLD_INFO_BEFORE,      // 📌 World Info (before) / 角色定义之前世界书

    @SerialName("char_description")
    CHAR_SYSTEM_PROMPT,     // 📌 Char Description / 助手系统提示词（角色定义）

    @SerialName("world_info_after")
    WORLD_INFO_AFTER,       // 📌 World Info (after) / 角色定义之后世界书

    @SerialName("memories")
    MEMORIES,               // 📌 记忆 / 助手长期记忆（仅在开启记忆时注入）

    @SerialName("workspace_prompt")
    WORKSPACE_PROMPT,       // 📌 工作区提示词 / 工作区与工具附加系统提示词（仅在启用时注入）

    @SerialName("chat_history")
    CHAT_HISTORY,           // 📌 Chat History / 对话历史
}

/**
 * 提示词块的位置（对齐 SillyTavern Prompt Manager）
 */
@Serializable
enum class PromptBlockPosition {
    @SerialName("relative")
    RELATIVE,           // 相对（相对于提示管理器中的其他提示）

    @SerialName("in_chat")
    IN_CHAT,            // 聊天中（在聊天中 @ 深度）

    @SerialName("attach")
    ATTACH_TO_MESSAGE,  // 附着到已有消息
}

/**
 * 提示词组装预设中的单个提示词块
 * - markerType != null：内置不可删块（📌），可开关、可拖拽排序、可点击编辑
 * - markerType == null：用户自定义提示词块（📝），可编辑角色、内容、位置、可删除
 */
@Serializable
data class PromptBlock(
    val id: Uuid = Uuid.random(),
    val identifier: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val markerType: PromptMarkerType? = null,
    val role: MessageRole = MessageRole.SYSTEM,
    val blankRole: Boolean = false,   // true = 特殊空白角色 "?"（跟随最前一个 role 合并进入正文，仅支持正文）
    val jsonMode: Boolean = false,    // true = JSON 模式（作为数组元素直接并入 API 请求内）
    val rawJson: String = "",         // JSON 模式下的原始 JSON 文本
    val content: String = "",
    val reasoning: String = "",       // 思维链摘要 / 开源模型思维链明文
    val reasoningSignature: String = "", // 思维链签名 (Signature / Encrypted Content)
    val inChatDepth: Boolean = false, // 兼容字段：true = 聊天中 @D；false = 相对
    val position: PromptBlockPosition? = null, // 位置：相对 / 聊天中 / 附着到已有消息
    val depth: Int = 4,               // 当位置为「聊天中」或「附着到已有消息」时生效（0=对话末尾，9999=对话开始前）
    val order: Int = 100,             // 同深度的排序顺序（数字越小越靠上，默认 100）
    // 按预设发送工具调用：true = 当前正在进行的工具调用视为聊天历史的最后一条，本块按预设位置排（可排在它后面）；
    // false（默认）= 当前工具调用作为未完成整体压在请求最底部，本块照常在它上面。📌 Chat History 不使用此字段
    val followPresetInToolCall: Boolean = false,
) {
    val isMarker: Boolean get() = markerType != null

    val effectivePosition: PromptBlockPosition
        get() = position ?: if (inChatDepth) PromptBlockPosition.IN_CHAT else PromptBlockPosition.RELATIVE

    val hasReasoningPayload: Boolean
        get() = !jsonMode && !blankRole && !isMarker && (reasoning.isNotBlank() || reasoningSignature.isNotBlank())

    val hasRawJsonPayload: Boolean
        get() = !isMarker && jsonMode && rawJson.isNotBlank()

    val hasAnyPayload: Boolean
        get() = if (!isMarker && jsonMode) rawJson.isNotBlank() else (content.isNotBlank() || hasReasoningPayload)
}

/**
 * 提示词组装预设
 */
@Serializable
data class PromptPreset(
    val id: Uuid = Uuid.random(),
    val name: String = "默认预设",
    val description: String = "",
    val enabled: Boolean = false,
    val blocks: List<PromptBlock> = defaultPromptBlocks(),
) {
    /**
     * 确保 6 个内置不可删的 📌 模块始终存在（若导入或旧数据时缺失则自动补齐）
     */
    fun ensureRequiredMarkers(): PromptPreset {
        var hasBefore = false
        var hasChar = false
        var hasAfter = false
        var hasMemories = false
        var hasWorkspace = false
        var hasChat = false
        for (b in blocks) {
            when (b.markerType) {
                PromptMarkerType.WORLD_INFO_BEFORE -> hasBefore = true
                PromptMarkerType.CHAR_SYSTEM_PROMPT -> hasChar = true
                PromptMarkerType.WORLD_INFO_AFTER -> hasAfter = true
                PromptMarkerType.MEMORIES -> hasMemories = true
                PromptMarkerType.WORKSPACE_PROMPT -> hasWorkspace = true
                PromptMarkerType.CHAT_HISTORY -> hasChat = true
                null -> Unit
            }
        }
        if (hasBefore && hasChar && hasAfter && hasMemories && hasWorkspace && hasChat) return this

        val current = blocks.toMutableList()
        fun ensureMarker(type: PromptMarkerType, defaultName: String, idStr: String, insertAfter: PromptMarkerType? = null) {
            if (current.none { it.markerType == type }) {
                val block = PromptBlock(
                    id = Uuid.random(),
                    identifier = idStr,
                    name = defaultName,
                    enabled = true,
                    markerType = type,
                    role = MessageRole.SYSTEM,
                )
                val afterIdx = insertAfter?.let { target -> current.indexOfFirst { it.markerType == target } } ?: -1
                if (afterIdx >= 0) {
                    current.add(afterIdx + 1, block)
                } else {
                    current.add(block)
                }
            }
        }
        ensureMarker(PromptMarkerType.WORLD_INFO_BEFORE, "World Info (before)", "worldInfoBefore")
        ensureMarker(PromptMarkerType.CHAR_SYSTEM_PROMPT, "Char Description", "charDescription", PromptMarkerType.WORLD_INFO_BEFORE)
        ensureMarker(PromptMarkerType.MEMORIES, "记忆", "memories", PromptMarkerType.CHAR_SYSTEM_PROMPT)
        ensureMarker(PromptMarkerType.WORKSPACE_PROMPT, "工作区提示词", "workspacePrompt", PromptMarkerType.MEMORIES)
        ensureMarker(PromptMarkerType.WORLD_INFO_AFTER, "World Info (after)", "worldInfoAfter", PromptMarkerType.WORKSPACE_PROMPT)
        ensureMarker(PromptMarkerType.CHAT_HISTORY, "Chat History", "chatHistory", PromptMarkerType.WORLD_INFO_AFTER)
        return copy(blocks = current)
    }
}

fun defaultPromptBlocks(): List<PromptBlock> = listOf(
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
        identifier = "main",
        name = "Main Prompt",
        enabled = true,
        markerType = null,
        role = MessageRole.SYSTEM,
        content = "",
    ),
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000002"),
        identifier = "worldInfoBefore",
        name = "World Info (before)",
        enabled = true,
        markerType = PromptMarkerType.WORLD_INFO_BEFORE,
        role = MessageRole.SYSTEM,
    ),
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000003"),
        identifier = "charDescription",
        name = "Char Description",
        enabled = true,
        markerType = PromptMarkerType.CHAR_SYSTEM_PROMPT,
        role = MessageRole.SYSTEM,
    ),
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000007"),
        identifier = "memories",
        name = "记忆",
        enabled = true,
        markerType = PromptMarkerType.MEMORIES,
        role = MessageRole.SYSTEM,
    ),
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000008"),
        identifier = "workspacePrompt",
        name = "工作区提示词",
        enabled = true,
        markerType = PromptMarkerType.WORKSPACE_PROMPT,
        role = MessageRole.SYSTEM,
    ),
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000004"),
        identifier = "worldInfoAfter",
        name = "World Info (after)",
        enabled = true,
        markerType = PromptMarkerType.WORLD_INFO_AFTER,
        role = MessageRole.SYSTEM,
    ),
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000005"),
        identifier = "chatHistory",
        name = "Chat History",
        enabled = true,
        markerType = PromptMarkerType.CHAT_HISTORY,
        role = MessageRole.SYSTEM,
    ),
    PromptBlock(
        id = Uuid.parse("10000000-0000-0000-0000-000000000006"),
        identifier = "jailbreak",
        name = "Post-History Instructions",
        enabled = false,
        markerType = null,
        role = MessageRole.SYSTEM,
        content = "",
    ),
)

/**
 * 提示词注入
 *
 * - ModeInjection: 保留旧版序列化兼容
 * - RegexInjection: 世界书条目（支持常驻/绿灯、主副关键词4逻辑、/regex/flags、深度+顺序、锚点等）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置（0=末尾，9999=对话开始前）
    abstract val role: MessageRole  // 注入角色：SYSTEM、USER 或 ASSISTANT

    /**
     * 模式注入 - 保留兼容
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 100,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.SYSTEM,
    ) : PromptInjection()

    /**
     * 世界书条目
     * - priority 即 Order（顺序）：同位置/同深度下数字越小越靠上，默认 100
     * - injectDepth 即 Depth（深度）：在对话内从最新消息往前数（0=对话末尾，9999=对话开始前）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 100,
        override val position: InjectionPosition = InjectionPosition.BEFORE_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.SYSTEM,
        val keywords: List<String> = emptyList(),        // 主要关键字（支持普通词或 /pattern/flags）
        val secondaryKeys: List<String> = emptyList(),   // 可选过滤器（次要关键字）
        val selective: Boolean = true,                   // 是否启用次要关键字过滤
        val selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        val useRegex: Boolean = false,                   // 是否强制全部按正则匹配
        val caseSensitive: Boolean = false,              // 大小写敏感
        val overrideCaseSensitive: Boolean? = null,      // null=使用世界书全局，true/false=单条目覆盖
        val matchWholeWords: Boolean? = null,            // null=使用世界书全局，true/false=单条目覆盖
        val scanDepth: Int = 4,                          // 扫描最近 N 条消息
        val overrideScanDepth: Int? = null,              // null=使用世界书全局扫描深度
        val constantActive: Boolean = false,             // 🔵 true=常驻蓝灯，🟢 false=绿灯条件触发
        val probability: Int = 100,                      // 触发百分比 (0..100)
        val excludeRecursion: Boolean = false,           // 不可递归（不会被其他条目激活）
        val preventRecursion: Boolean = false,           // 防止进一步递归
        val delayUntilRecursion: Boolean = false,        // 延迟到递归（首轮扫对话不触发，仅递归阶段触发）
        val ignoreBudget: Boolean = false,               // 无视回复限额
        val outletName: String = "",                     // ➡️ 锚点名称（当 position == OUTLET 时通过 {{outlet::name}} 引用）
    ) : PromptInjection()
}

/**
 * Lorebook - 世界书
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val greenLightMode: LorebookGreenLightMode = LorebookGreenLightMode.STICKY_LOCKED_POSITION,
    val defaultScanDepth: Int = 4,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val recursiveScan: Boolean = true,
    val maxRecursionSteps: Int = 3,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 解析世界书条目开头的 @@activate / @@dont_activate 装饰器，并返回剥离装饰器行后的正文
 */
data class ParsedWorldInfoDecorators(
    val forceActivate: Boolean = false,
    val forceDontActivate: Boolean = false,
    val cleanContent: String = "",
)

fun parseWorldInfoContentDecorators(rawContent: String): ParsedWorldInfoDecorators {
    if (!rawContent.startsWith("@@")) {
        return ParsedWorldInfoDecorators(cleanContent = rawContent)
    }
    var forceActivate = false
    var forceDontActivate = false
    val lines = rawContent.lines()
    var firstContentLineIdx = 0
    for ((idx, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("@@") || trimmed.startsWith("@@@")) {
            firstContentLineIdx = idx
            break
        }
        val directive = trimmed.removePrefix("@@").trim().lowercase()
        when (directive) {
            "activate" -> forceActivate = true
            "dont_activate", "dontactivate" -> forceDontActivate = true
        }
        firstContentLineIdx = idx + 1
    }
    val remaining = lines.drop(firstContentLineIdx).joinToString("\n") { line ->
        if (line.startsWith("@@@")) line.removePrefix("@") else line
    }
    return ParsedWorldInfoDecorators(
        forceActivate = forceActivate,
        forceDontActivate = forceDontActivate,
        cleanContent = remaining,
    )
}

// 识别酒馆 /pattern/flags 形式的正则关键字（复用全局编译缓存）
internal fun parseSlashRegexKeyword(rawKey: String): Regex? {
    val trimmed = rawKey.trim()
    if (trimmed.length < 2 || !trimmed.startsWith("/")) return null
    val lastSlash = trimmed.lastIndexOf('/')
    if (lastSlash <= 0) return null
    val pattern = trimmed.substring(1, lastSlash)
    val flagsStr = trimmed.substring(lastSlash + 1)
    if (pattern.isEmpty()) return null
    val validFlags = setOf('i', 'm', 's', 'u', 'g', 'y', 'd')
    if (flagsStr.any { it !in validFlags }) return null

    val options = mutableSetOf<RegexOption>()
    if ('i' in flagsStr) options.add(RegexOption.IGNORE_CASE)
    if ('m' in flagsStr) options.add(RegexOption.MULTILINE)
    if ('s' in flagsStr) options.add(RegexOption.DOT_MATCHES_ALL)
    return compileRegexWithOptionsCached(pattern, options)
}

/**
 * 单个关键词匹配（支持 /pattern/flags、普通正则、大小写敏感、完整单词匹配，全程走正则编译缓存）
 */
internal fun matchSingleKeyword(
    rawKey: String,
    context: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    matchWholeWords: Boolean,
): Boolean {
    val key = rawKey.trim()
    if (key.isEmpty()) return false

    // 1. 优先识别 /pattern/flags 语法
    val slashRegex = parseSlashRegexKeyword(key)
    if (slashRegex != null) {
        return runCatching { slashRegex.containsMatchIn(context) }.getOrDefault(false)
    }

    // 2. 显式开启了 useRegex
    if (useRegex) {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val compiled = compileRegexWithOptionsCached(key, options) ?: return false
        return runCatching { compiled.containsMatchIn(context) }.getOrDefault(false)
    }

    // 3. 普通文本匹配（可选完整单词匹配）
    if (matchWholeWords && key.all { it.isLetterOrDigit() || it == '_' }) {
        // 先用 O(N) 子串快路径预筛，不含该词时直接跳过正则匹配
        if (!context.contains(key, ignoreCase = !caseSensitive)) return false
        val escaped = Regex.escape(key)
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val compiled = compileRegexWithOptionsCached("\\b$escaped\\b", options) ?: return false
        return runCatching { compiled.containsMatchIn(context) }.getOrDefault(false)
    }

    return context.contains(key, ignoreCase = !caseSensitive)
}

/**
 * 检查世界书条目在给定文本下是否满足触发条件
 */
fun PromptInjection.RegexInjection.isTriggered(
    context: String,
    bookCaseSensitive: Boolean = false,
    bookMatchWholeWords: Boolean = false,
    rollProbability: Boolean = false,
): Boolean {
    if (!enabled) return false

    val decorators = parseWorldInfoContentDecorators(content)
    if (decorators.forceDontActivate) return false
    if (decorators.forceActivate) return true

    if (rollProbability && probability in 0..99) {
        if (probability <= 0) return false
        if (kotlin.random.Random.nextInt(100) >= probability) return false
    }

    val effectiveCaseSensitive = overrideCaseSensitive ?: (caseSensitive || bookCaseSensitive)
    val effectiveWholeWords = matchWholeWords ?: bookMatchWholeWords

    // 🔵 蓝灯常驻（constantActive = true）：
    // 默认直接激活；但如果配置了次要关键字且逻辑为排除（NOT_ANY / NOT_ALL），依然让排除过滤器生效！
    if (constantActive) {
        if (selective && secondaryKeys.isNotEmpty()) {
            val cleanSecondary = secondaryKeys.filter { it.isNotBlank() }
            if (cleanSecondary.isNotEmpty()) {
                return when (selectiveLogic) {
                    SelectiveLogic.NOT_ANY -> cleanSecondary.none {
                        matchSingleKeyword(it, context, useRegex, effectiveCaseSensitive, effectiveWholeWords)
                    }
                    SelectiveLogic.NOT_ALL -> !cleanSecondary.all {
                        matchSingleKeyword(it, context, useRegex, effectiveCaseSensitive, effectiveWholeWords)
                    }
                    else -> true
                }
            }
        }
        return true
    }

    // 🟢 绿灯条件触发：必须先命中至少一个主关键字
    val cleanPrimary = keywords.filter { it.isNotBlank() }
    if (cleanPrimary.isEmpty()) return false
    val anyPrimaryMatched = cleanPrimary.any {
        matchSingleKeyword(it, context, useRegex, effectiveCaseSensitive, effectiveWholeWords)
    }
    if (!anyPrimaryMatched) return false

    // 检查可选过滤器（次要关键字）
    val cleanSecondary = secondaryKeys.filter { it.isNotBlank() }
    if (!selective || cleanSecondary.isEmpty()) return true

    return when (selectiveLogic) {
        SelectiveLogic.AND_ANY -> cleanSecondary.any {
            matchSingleKeyword(it, context, useRegex, effectiveCaseSensitive, effectiveWholeWords)
        }
        SelectiveLogic.AND_ALL -> cleanSecondary.all {
            matchSingleKeyword(it, context, useRegex, effectiveCaseSensitive, effectiveWholeWords)
        }
        SelectiveLogic.NOT_ANY -> cleanSecondary.none {
            matchSingleKeyword(it, context, useRegex, effectiveCaseSensitive, effectiveWholeWords)
        }
        SelectiveLogic.NOT_ALL -> !cleanSecondary.all {
            matchSingleKeyword(it, context, useRegex, effectiveCaseSensitive, effectiveWholeWords)
        }
    }
}

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    if (scanDepth <= 0) return ""
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
}

/**
 * 获取所有被触发的注入，按顺序（Order 数字越小越靠上）排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedBy { it.priority }
}
