package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.RawJsonMessageMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.rawJsonElementOrNull
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.datastore.DEFAULT_PROMPT_PRESETS
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptBlock
import me.rerere.rikkahub.data.model.PromptBlockPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.PromptMarkerType
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.data.model.defaultPromptBlocks
import me.rerere.rikkahub.data.model.parseWorldInfoContentDecorators
import me.rerere.rikkahub.data.model.replaceLorebookRegexes
import me.rerere.rikkahub.data.plugin.PluginBridge
import me.rerere.plugin.manifest.PluginHookKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 高级提示词组装与世界书注入转换器
 *
 * 核心能力：
 * 1. 按预设轨道（PromptPreset.blocks）从上到下流式组装：
 *    - 支持 4 个内置不可删坐标原点（📌 World Info Before / Char System Prompt / World Info After / Chat History）
 *    - 支持围绕原点的任意自定义提示词块（📝 跟随轨道顺序 或 @D 按深度注入对话）
 * 2. 世界书（Lorebook）引擎：
 *    - 支持 🔵 蓝灯常驻（含可选排除过滤器）与 🟢 绿灯条件触发（主副关键词 + 4 种逻辑 + /regex/flags + 递归扫描）
 *    - 支持三档绿灯缓存策略：
 *      a) STICKY_LOCKED_POSITION（默认：触发后持续至下次压缩 + @D 注入位置锚定在首次触发处不变）
 *      b) STICKY_FLOATING（触发后持续至下次压缩 + @D 位置随最新消息浮动）
 *      c) DYNAMIC_NATIVE（酒馆原生绿灯：仅扫描窗口内命中当轮生效）
 *    - 排序策略：深度 + 顺序（先按 Depth 从大到小，Depth=9999 即对话开始前、Depth=0 即对话末尾；同位置/同深度按 Order 数字越小越靠上，默认 100）
 *    - 支持 ➡️ 锚点（Outlet）：通过 {{outlet::名称}} 在任意提示词块中展开
 * 3. 连续工具调用链保护：
 *    - 历史轮次已完成的工具调用留在 Chat History 中（且 @D 插入自动避开 USER -> ASSISTANT(含Tool) 之间）
 *    - 当前回合正在进行的连续工具链拆出并压在整个请求体的最底部（位于底部提示词之后）
 */
object PromptInjectionTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val pluginBridge = runCatching { get<PluginBridge>() }.getOrNull()
        if (!ctx.settings.enableAdvancedPrompt) {
            if (pluginBridge != null && pluginBridge.hasHook(PluginHookKind.CHAT_HISTORY, ctx.assistant)) {
                val firstSysIdx = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
                val leadingSys = if (firstSysIdx == 0) listOf(messages[0]) else emptyList()
                val nonSys = if (firstSysIdx == 0) messages.drop(1) else messages.filter { it.role != MessageRole.SYSTEM }
                val lastNonSys = nonSys.lastOrNull()
                val hasActiveTool = lastNonSys != null && lastNonSys.role == MessageRole.ASSISTANT && lastNonSys.getTools().isNotEmpty()
                val chatHist = if (hasActiveTool) nonSys.dropLast(1) else nonSys
                val activeTool = if (hasActiveTool) listOf(lastNonSys) else emptyList()
                val updatedHist = pluginBridge.chatHistory(chatHist, ctx.assistant, ctx.pluginCtx)
                return leadingSys + updatedHist + activeTool
            }
            return messages
        }
        val combinedWorkspacePrompt = listOfNotNull(
            ctx.toolSystemPrompt.trim().takeIf { it.isNotEmpty() },
            ctx.workspacePrompt.trim().takeIf { it.isNotEmpty() },
        ).joinToString("\n\n")
        val chatHistoryHook: ((List<UIMessage>) -> List<UIMessage>)? =
            if (pluginBridge != null && pluginBridge.hasHook(PluginHookKind.CHAT_HISTORY, ctx.assistant)) {
                { rawHistory ->
                    kotlinx.coroutines.runBlocking {
                        pluginBridge.chatHistory(rawHistory, ctx.assistant, ctx.pluginCtx)
                    }
                }
            } else null
        val presetHook: ((PromptPreset) -> PromptPreset)? =
            if (pluginBridge != null && pluginBridge.hasHook(PluginHookKind.PRESET_BEFORE_ASSEMBLE, ctx.assistant)) {
                { rawPreset ->
                    kotlinx.coroutines.runBlocking {
                        pluginBridge.presetBeforeAssemble(rawPreset, ctx.assistant, ctx.pluginCtx)
                    }
                }
            } else null
        val lorebookHook: ((List<ActivatedLoreEntry>) -> List<ActivatedLoreEntry>)? =
            if (pluginBridge != null && pluginBridge.hasHook(PluginHookKind.LOREBOOK_ACTIVATED, ctx.assistant)) {
                { rawEntries ->
                    val byId = rawEntries.associateBy { it.entry.id }
                    val updated = kotlinx.coroutines.runBlocking {
                        pluginBridge.lorebookActivated(rawEntries.map { it.entry }, ctx.assistant, ctx.pluginCtx)
                    }
                    updated.map { entry ->
                        val prev = byId[entry.id]
                        if (prev != null && prev.entry.content == entry.content) {
                            prev.copy(entry = entry)
                        } else {
                            val parsed = parseWorldInfoContentDecorators(entry.content)
                            ActivatedLoreEntry(
                                entry = entry,
                                cleanContent = parsed.cleanContent,
                                anchoredChatInsertIndex = prev?.anchoredChatInsertIndex,
                            )
                        }
                    }
                }
            } else null

        val assembled = transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            promptPresets = ctx.settings.promptPresets,
            enableAdvancedPrompt = ctx.settings.enableAdvancedPrompt,
            memoryPrompt = ctx.memoryPrompt,
            workspacePrompt = combinedWorkspacePrompt,
            chatHistoryTransform = chatHistoryHook,
            presetBeforeAssembleTransform = presetHook,
            lorebookActivatedTransform = lorebookHook,
        )
        return if (pluginBridge != null && pluginBridge.hasHook(PluginHookKind.PROMPT_ASSEMBLED, ctx.assistant)) {
            pluginBridge.promptAssembled(assembled, ctx.assistant, ctx.pluginCtx)
        } else {
            assembled
        }
    }
}

private val OUTLET_MACRO_REGEX = Regex("""\{\{\s*outlet::([^}]+)\}\}""", RegexOption.IGNORE_CASE)

internal fun expandOutletMacros(text: String, outlets: Map<String, String>): String {
    if (!text.contains("{{", ignoreCase = true)) return text
    return OUTLET_MACRO_REGEX.replace(text) { matchResult ->
        val key = matchResult.groupValues[1].trim()
        outlets[key] ?: ""
    }
}

internal data class ActivatedLoreEntry(
    val entry: PromptInjection.RegexInjection,
    val cleanContent: String,
    /**
     * 当所属世界书开启 STICKY_LOCKED_POSITION 时，记录该条目首次触发那一刻计算出的对话历史插入下标；
     * null 表示按当前最新对话长度动态计算 (chatSize - depth)。
     */
    val anchoredChatInsertIndex: Int? = null,
)

/**
 * 核心组装与注入逻辑（可测试的纯函数）
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    promptPresets: List<PromptPreset> = emptyList(),
    enableAdvancedPrompt: Boolean = true,
    memoryPrompt: String = "",
    workspacePrompt: String = "",
    chatHistoryTransform: ((List<UIMessage>) -> List<UIMessage>)? = null,
    presetBeforeAssembleTransform: ((PromptPreset) -> PromptPreset)? = null,
    lorebookActivatedTransform: ((List<ActivatedLoreEntry>) -> List<ActivatedLoreEntry>)? = null,
): List<UIMessage> {
    if (!enableAdvancedPrompt) return messages
    // 1. 提取顶层系统消息（代表 📌 助手系统提示词）
    val firstSysIdx = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
    val charSystemMessage: UIMessage? = if (firstSysIdx == 0) messages[0] else null
    val nonSystemMessages: List<UIMessage> = if (firstSysIdx == 0) {
        messages.drop(1)
    } else {
        messages.filter { it.role != MessageRole.SYSTEM }
    }

    // 2. 分离「对话历史 (chatHistoryMessages)」与「当前回合正在执行的连续工具链 (activeToolChainMessage)」
    // 若最后一条消息是 ASSISTANT 且包含已执行/待续跑的工具调用（且尚未产出最终纯文本收尾，或正处于工具循环步中），
    // 将其拆出并在组装完底部提示词后压在请求最底部，保证连续工具链不断裂且 100% 命中前缀缓存。
    val lastNonSys = nonSystemMessages.lastOrNull()
    val isTrailingActiveToolChain = lastNonSys != null &&
        lastNonSys.role == MessageRole.ASSISTANT &&
        lastNonSys.getTools().isNotEmpty()
    val rawChatHistoryMessages: List<UIMessage> = if (isTrailingActiveToolChain) {
        nonSystemMessages.dropLast(1)
    } else {
        nonSystemMessages
    }
    val chatHistoryMessages: List<UIMessage> = chatHistoryTransform?.invoke(rawChatHistoryMessages) ?: rawChatHistoryMessages
    val activeToolChainMessage: UIMessage? = if (isTrailingActiveToolChain) lastNonSys else null

    // 3. 解析当前生效的提示词预设（PromptPreset）
    val resolvedPreset: PromptPreset? = resolveActivePromptPreset(
        assistant = assistant,
        promptPresets = promptPresets,
    )
    val activePreset: PromptPreset? = if (presetBeforeAssembleTransform != null) {
        presetBeforeAssembleTransform(resolvedPreset ?: PromptPreset(blocks = defaultPromptBlocks())).ensureRequiredMarkers()
    } else {
        resolvedPreset
    }

    // 4. 获取启用的世界书并执行扫描（支持三档绿灯模式、递归扫描、首次触发位置锚定）
    val enabledLorebooks = resolveEnabledLorebooks(assistant, lorebooks)
    val activatedLoreEntries = mutableListOf<ActivatedLoreEntry>()
    enabledLorebooks.forEach { book ->
        activatedLoreEntries.addAll(
            scanLorebookForConversation(
                book = book,
                chatHistory = chatHistoryMessages,
            )
        )
    }

    val effectiveLoreEntries = lorebookActivatedTransform?.invoke(activatedLoreEntries) ?: activatedLoreEntries

    // 4.1 世界书正则（作用范围勾选「世界书」）：激活后、汇聚锚点前对条目正文执行，不影响关键字扫描与递归（与酒馆一致）。
    // 深度取条目的 @D 深度，非 @D 条目（角色定义前/后、锚点）不做深度限制
    val regexedLoreEntries = applyLorebookRegexes(effectiveLoreEntries, assistant)

    // 5. 收集 ➡️ 锚点（OUTLET）条目，按 Order（priority）从小到大排序后汇聚，供 {{outlet::name}} 展开
    val outletMap: Map<String, String> = regexedLoreEntries
        .filter { it.entry.position == InjectionPosition.OUTLET && it.entry.outletName.isNotBlank() }
        .sortedBy { it.entry.priority }
        .groupBy { it.entry.outletName.trim() }
        .mapValues { (_, list) ->
            list.mapNotNull { it.cleanContent.takeIf { c -> c.isNotEmpty() } }.joinToString("\n")
        }

    // 对所有激活条目的内容也展开一次 {{outlet::name}}（支持锚点嵌套引用）
    val resolvedLoreEntries = if (outletMap.isEmpty()) {
        regexedLoreEntries
    } else {
        regexedLoreEntries.map { item ->
            item.copy(cleanContent = expandOutletMacros(item.cleanContent, outletMap))
        }
    }

    // 6. 兼容旧版 ModeInjection（若助手绑定了旧版 ModeInjection ID）
    val legacyModeInjections = modeInjections.filter {
        it.enabled && assistant.modeInjectionIds.contains(it.id)
    }

    // 如果既没有启用预设、也没有触发任何世界书或旧版注入、也没有待注入的记忆/工作区提示词，原样返回（若 chatHistoryTransform 已改写对话历史，则应用改写后的对话历史）
    val hasActivePresetBlocks = activePreset != null && activePreset.blocks.any {
        it.enabled && (!it.isMarker && it.hasAnyPayload)
    }
    val hasCustomMarkerOrder = activePreset != null && !isStandardDefaultMarkerOrder(activePreset)
    val hasMemoryOrWorkspace = memoryPrompt.isNotBlank() || workspacePrompt.isNotBlank()
    if (resolvedLoreEntries.isEmpty() && legacyModeInjections.isEmpty() && !hasActivePresetBlocks && !hasCustomMarkerOrder && !hasMemoryOrWorkspace) {
        if (chatHistoryTransform == null) return messages
        return listOfNotNull(charSystemMessage) + chatHistoryMessages + listOfNotNull(activeToolChainMessage)
    }

    val presetToUse = (activePreset ?: PromptPreset(blocks = defaultPromptBlocks())).ensureRequiredMarkers()

    // 7. 按预设轨道 + 世界书 + 深度注入 + 底部工具链组装最终消息列表
    return assembleWithPresetAndLorebooks(
        preset = presetToUse,
        charSystemMessage = charSystemMessage,
        memoryPrompt = memoryPrompt,
        workspacePrompt = workspacePrompt,
        chatHistoryMessages = chatHistoryMessages,
        activeToolChainMessage = activeToolChainMessage,
        loreEntries = resolvedLoreEntries,
        legacyModeInjections = legacyModeInjections,
        outletMap = outletMap,
    )
}

/**
 * 世界书条目的正则深度：@D 条目取设定的注入深度（对话最开头 = 9999，最新消息之前 = 1），其余位置为 null（不限制深度）
 */
internal fun lorebookRegexDepth(entry: PromptInjection.RegexInjection): Int? = when (entry.position) {
    InjectionPosition.AT_DEPTH -> entry.injectDepth.coerceAtLeast(0)
    InjectionPosition.TOP_OF_CHAT -> 9999
    InjectionPosition.BOTTOM_OF_CHAT -> 1
    else -> null
}

private fun applyLorebookRegexes(
    entries: List<ActivatedLoreEntry>,
    assistant: Assistant,
): List<ActivatedLoreEntry> {
    if (entries.isEmpty()) return entries
    val hasLorebookRegex = assistant.regexes.any {
        it.enabled && it.findRegex.isNotEmpty() && AssistantAffectScope.LOREBOOK in it.affectingScope
    } || runCatching {
        val bridge = org.koin.mp.KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<PluginBridge>()
        bridge?.hasPluginRegex(me.rerere.rikkahub.data.model.AssistantRegexPhase.REQUEST, AssistantAffectScope.LOREBOOK) == true ||
            bridge?.hasPluginRegex(me.rerere.rikkahub.data.model.AssistantRegexPhase.PERSIST, AssistantAffectScope.LOREBOOK) == true
    }.getOrDefault(false)
    if (!hasLorebookRegex) return entries
    return entries.map { item ->
        val replaced = item.cleanContent.replaceLorebookRegexes(
            assistant = assistant,
            depth = lorebookRegexDepth(item.entry),
        )
        if (replaced == item.cleanContent) item else item.copy(cleanContent = replaced)
    }
}

/**
 * 解析当前助手生效的提示词预设：
 * 仅当助手在扩展中明确勾选了某个 PromptPreset（存放在 assistant.modeInjectionIds 中）且该预设已启用（enabled = true）时生效；
 * 未勾选预设的助手（含子代理）默认不使用任何提示词预设。
 */
internal fun resolveActivePromptPreset(
    assistant: Assistant,
    promptPresets: List<PromptPreset>,
): PromptPreset? {
    if (promptPresets.isEmpty() || assistant.modeInjectionIds.isEmpty()) return null
    return promptPresets.firstOrNull { it.enabled && it.id in assistant.modeInjectionIds }
}

/**
 * 解析当前助手生效的世界书列表：
 * 仅当助手在扩展中显式勾选了 lorebookIds 且对应世界书已启用（enabled = true）时生效；
 * 未勾选世界书的助手（含子代理）默认不启用任何世界书。
 */
internal fun resolveEnabledLorebooks(
    assistant: Assistant,
    lorebooks: List<Lorebook>,
): List<Lorebook> {
    if (lorebooks.isEmpty() || assistant.lorebookIds.isEmpty()) return emptyList()
    return lorebooks.filter { it.enabled && it.id in assistant.lorebookIds }
}

private fun isStandardDefaultMarkerOrder(preset: PromptPreset): Boolean {
    val enabledBlocks = preset.blocks.filter { it.enabled }
    val markersOnly = enabledBlocks.mapNotNull { it.markerType }
    return (markersOnly == listOf(
        PromptMarkerType.WORLD_INFO_BEFORE,
        PromptMarkerType.CHAR_SYSTEM_PROMPT,
        PromptMarkerType.MEMORIES,
        PromptMarkerType.WORKSPACE_PROMPT,
        PromptMarkerType.WORLD_INFO_AFTER,
        PromptMarkerType.CHAT_HISTORY,
    ) || markersOnly == listOf(
        PromptMarkerType.WORLD_INFO_BEFORE,
        PromptMarkerType.CHAR_SYSTEM_PROMPT,
        PromptMarkerType.WORLD_INFO_AFTER,
        PromptMarkerType.CHAT_HISTORY,
    )) && enabledBlocks.all {
        (it.isMarker || !it.hasAnyPayload) &&
            !it.blankRole &&
            it.role == MessageRole.SYSTEM &&
            it.effectivePosition == PromptBlockPosition.RELATIVE
    }
}

/**
 * 计算对话历史内部的 @D 插入下标（范围 0..chatSize）：
 * - depth = 0 -> chatSize（最新消息之后）
 * - depth = 1 -> chatSize - 1（最新消息之前）
 * - depth >= chatSize（如 9999）-> 0（对话开始前，即第 1 条聊天消息之前）
 */
internal fun computeDepthInsertIndex(
    chatSize: Int,
    position: InjectionPosition,
    depth: Int,
): Int? {
    return when (position) {
        InjectionPosition.TOP_OF_CHAT -> 0
        InjectionPosition.BOTTOM_OF_CHAT -> (chatSize - 1).coerceAtLeast(0)
        InjectionPosition.AT_DEPTH -> (chatSize - depth.coerceAtLeast(0)).coerceIn(0, chatSize)
        else -> null
    }
}

private data class InChatDepthItem(
    val targetIndex: Int,
    val effectiveDepth: Int,
    val order: Int,
    val role: MessageRole,
    val blankRole: Boolean = false,
    val jsonMode: Boolean = false,
    val rawJson: String = "",
    val content: String,
    val reasoning: String = "",
    val reasoningSignature: String = "",
    val fromLorebook: Boolean = false,
    val attachToMessage: Boolean = false,
    // 「按预设发送工具调用」的块：targetIndex 基于「聊天历史 + 当前工具调用」计算
    val followsToolChain: Boolean = false,
)

private fun resolveBlockContentText(
    block: PromptBlock,
    beforeCharContents: List<String>,
    charSystemMessage: UIMessage?,
    afterCharContents: List<String>,
    memoryPrompt: String,
    workspacePrompt: String,
    outletMap: Map<String, String>,
): String {
    val customText = expandOutletMacros(block.content, outletMap).trim()
    return when (block.markerType) {
        PromptMarkerType.WORLD_INFO_BEFORE -> {
            val base = beforeCharContents.joinToString("\n").trim()
            if (customText.isEmpty()) {
                base
            } else if (customText.contains("{0}")) {
                if (base.isNotEmpty()) customText.replace("{0}", base) else ""
            } else {
                listOfNotNull(base.takeIf { it.isNotEmpty() }, customText).joinToString("\n")
            }
        }

        PromptMarkerType.CHAR_SYSTEM_PROMPT -> {
            val base = charSystemMessage?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("") { expandOutletMacros(it.text, outletMap) }
                ?.trim()
                .orEmpty()
            if (customText.isEmpty()) {
                base
            } else if (customText.contains("{0}")) {
                customText.replace("{0}", base).trim()
            } else {
                listOfNotNull(base.takeIf { it.isNotEmpty() }, customText).joinToString("\n\n")
            }
        }

        PromptMarkerType.WORLD_INFO_AFTER -> {
            val base = afterCharContents.joinToString("\n").trim()
            if (customText.isEmpty()) {
                base
            } else if (customText.contains("{0}")) {
                if (base.isNotEmpty()) customText.replace("{0}", base) else ""
            } else {
                listOfNotNull(base.takeIf { it.isNotEmpty() }, customText).joinToString("\n")
            }
        }

        PromptMarkerType.MEMORIES -> {
            val base = expandOutletMacros(memoryPrompt, outletMap).trim()
            // 只有开启了记忆（base 非空）才注入；否则为空占位不注入
            if (base.isEmpty()) {
                ""
            } else if (customText.isEmpty()) {
                base
            } else if (customText.contains("{0}")) {
                customText.replace("{0}", base).trim()
            } else {
                "$base\n\n$customText"
            }
        }

        PromptMarkerType.WORKSPACE_PROMPT -> {
            val base = expandOutletMacros(workspacePrompt, outletMap).trim()
            // 只有启用了工作区/附加工具提示词（base 非空）才注入；否则为空占位不注入
            if (base.isEmpty()) {
                ""
            } else if (customText.isEmpty()) {
                base
            } else if (customText.contains("{0}")) {
                customText.replace("{0}", base).trim()
            } else {
                "$base\n\n$customText"
            }
        }

        PromptMarkerType.CHAT_HISTORY -> customText
        null -> customText
    }
}

private fun resolveBlockReasoningText(
    block: PromptBlock,
    outletMap: Map<String, String>,
): String {
    if (block.blankRole || block.isMarker) return ""
    return expandOutletMacros(block.reasoning, outletMap).trim()
}

private fun resolveBlockSignatureText(
    block: PromptBlock,
): String {
    if (block.blankRole || block.isMarker) return ""
    return block.reasoningSignature.trim()
}

private fun resolveBlockRawJsonText(
    block: PromptBlock,
    outletMap: Map<String, String>,
): String {
    if (block.isMarker || !block.jsonMode) return ""
    return expandOutletMacros(block.rawJson, outletMap).trim()
}

/**
 * 按预设轨道、世界书激活条目、深度注入及尾部活动工具链组装最终消息序列
 */
private fun assembleWithPresetAndLorebooks(
    preset: PromptPreset,
    charSystemMessage: UIMessage?,
    memoryPrompt: String,
    workspacePrompt: String,
    chatHistoryMessages: List<UIMessage>,
    activeToolChainMessage: UIMessage?,
    loreEntries: List<ActivatedLoreEntry>,
    legacyModeInjections: List<PromptInjection.ModeInjection>,
    outletMap: Map<String, String>,
): List<UIMessage> {
    // 1. 准备 World Info (before) 与 World Info (after) 内容（按 Order 数字越小越靠上排序）
    val beforeCharContents = buildList {
        legacyModeInjections
            .filter { it.position == InjectionPosition.BEFORE_SYSTEM_PROMPT && it.content.isNotBlank() }
            .sortedBy { it.priority }
            .forEach { add(expandOutletMacros(it.content, outletMap)) }
        loreEntries
            .filter { it.entry.position == InjectionPosition.BEFORE_SYSTEM_PROMPT && it.cleanContent.isNotBlank() }
            .sortedBy { it.entry.priority }
            .forEach { add(it.cleanContent) }
    }

    val afterCharContents = buildList {
        legacyModeInjections
            .filter { it.position == InjectionPosition.AFTER_SYSTEM_PROMPT && it.content.isNotBlank() }
            .sortedBy { it.priority }
            .forEach { add(expandOutletMacros(it.content, outletMap)) }
        loreEntries
            .filter { it.entry.position == InjectionPosition.AFTER_SYSTEM_PROMPT && it.cleanContent.isNotBlank() }
            .sortedBy { it.entry.priority }
            .forEach { add(it.cleanContent) }
    }

    // 2. 准备 Chat History（包含所有「聊天中 @D」与「附着到已有消息」块 + 世界书 AT_DEPTH 条目 + 旧版 ModeInjection）
    val (assembledChatHistory, toolChainTail) = assembleChatHistoryWithDepthInjections(
        chatHistory = chatHistoryMessages,
        activeToolChainMessage = activeToolChainMessage,
        preset = preset,
        beforeCharContents = beforeCharContents,
        charSystemMessage = charSystemMessage,
        afterCharContents = afterCharContents,
        memoryPrompt = memoryPrompt,
        workspacePrompt = workspacePrompt,
        loreEntries = loreEntries,
        legacyModeInjections = legacyModeInjections,
        outletMap = outletMap,
    )

    // 3. 沿着预设轨道从上到下组装位置为「相对 (RELATIVE)」的消息列表
    val trackMessages = mutableListOf<UIMessage>()
    // 当前工具调用及其后方内容：工具调用本身 +「按预设发送工具调用」且落在它后面的 @D 块 + 排在 Chat History 下方的同类相对块
    val tailMessages = toolChainTail.toMutableList()
    var chatHistoryEmitted = false

    fun emitOrMergeTrackBlock(
        block: PromptBlock,
        text: String,
        reasoningText: String = "",
        signatureText: String = "",
        rawJsonText: String = "",
    ) {
        // 「按预设发送工具调用」且排在 Chat History 下方的块：放到当前工具调用后面（工具调用不存在时不区分）
        val target = if (
            chatHistoryEmitted && activeToolChainMessage != null &&
            block.followPresetInToolCall && block.markerType != PromptMarkerType.CHAT_HISTORY
        ) {
            tailMessages
        } else {
            trackMessages
        }
        if (!block.isMarker && block.jsonMode) {
            createRawJsonMessage(rawJsonText)?.let { target.add(it) }
            return
        }
        if (block.blankRole) {
            // 特殊空白 role ("?")：仅支持正文，跟随最前一个 role 合并进入其正文
            if (text.isNotEmpty()) {
                if (target.isNotEmpty()) {
                    target[target.lastIndex] = appendTextToMessage(target.last(), text)
                } else {
                    target.add(createRoleMessage(MessageRole.SYSTEM, text))
                }
            }
        } else {
            createBlockMessage(
                role = block.role,
                content = text,
                reasoning = reasoningText,
                reasoningSignature = signatureText,
            )?.let { target.add(it) }
        }
    }

    for (block in preset.blocks) {
        if (!block.enabled) continue
        if (block.markerType == PromptMarkerType.CHAT_HISTORY) {
            chatHistoryEmitted = true
            trackMessages.addAll(assembledChatHistory)
            val extraChatText = expandOutletMacros(block.content, outletMap).trim()
            if (extraChatText.isNotEmpty()) {
                emitOrMergeTrackBlock(block, extraChatText)
            }
            continue
        }
        // 非「相对」位置的块已在 assembleChatHistoryWithDepthInjections 中处理
        if (block.effectivePosition != PromptBlockPosition.RELATIVE) continue

        when (block.markerType) {
            PromptMarkerType.CHAR_SYSTEM_PROMPT -> {
                if (!block.blankRole && block.role == MessageRole.SYSTEM && block.content.isBlank() && charSystemMessage != null) {
                    val expandedParts = charSystemMessage.parts.map { part ->
                        if (part is UIMessagePart.Text) {
                            part.copy(text = expandOutletMacros(part.text, outletMap))
                        } else {
                            part
                        }
                    }
                    trackMessages.add(
                        charSystemMessage.copy(parts = expandedParts, isSynthetic = true)
                    )
                } else {
                    val text = resolveBlockContentText(
                        block = block,
                        beforeCharContents = beforeCharContents,
                        charSystemMessage = charSystemMessage,
                        afterCharContents = afterCharContents,
                        memoryPrompt = memoryPrompt,
                        workspacePrompt = workspacePrompt,
                        outletMap = outletMap,
                    )
                    emitOrMergeTrackBlock(block, text)
                }
            }

            PromptMarkerType.WORLD_INFO_BEFORE,
            PromptMarkerType.WORLD_INFO_AFTER,
            PromptMarkerType.MEMORIES,
            PromptMarkerType.WORKSPACE_PROMPT,
            null -> {
                val text = resolveBlockContentText(
                    block = block,
                    beforeCharContents = beforeCharContents,
                    charSystemMessage = charSystemMessage,
                    afterCharContents = afterCharContents,
                    memoryPrompt = memoryPrompt,
                    workspacePrompt = workspacePrompt,
                    outletMap = outletMap,
                )
                val reasoningText = resolveBlockReasoningText(block, outletMap)
                val signatureText = resolveBlockSignatureText(block)
                val rawJsonText = resolveBlockRawJsonText(block, outletMap)
                emitOrMergeTrackBlock(block, text, reasoningText, signatureText, rawJsonText)
            }

            PromptMarkerType.CHAT_HISTORY -> Unit
        }
    }

    // 4. 保留轨道上的每个 SYSTEM 块各自独立（各 Provider 按自身协议决定：
    //    - OpenAI Chat Completions: 原样发送多条独立 role="system" 消息
    //    - Claude Messages: 顶部连续 SYSTEM 块转为顶层 system: [{"type":"text",...}, ...] 多块数组
    //    - Google generateContent: 顶部连续 SYSTEM 块转为顶层 systemInstruction.parts: [{"text":...}, ...] 多部件数组
    //    - Google Interactions / OpenAI Responses: 仅支持单字符串，由 Provider 自动将顶部连续 SYSTEM 块用 "\n\n" 合并）
    val result = trackMessages.toMutableList()

    // 5. 若当前回合存在正在执行的连续工具链消息，将其压在底部提示词之后；
    // 开启「按预设发送工具调用」的块随后按预设顺序排在它后面
    result.addAll(tailMessages)

    return result
}

/**
 * 将所有 @D 深度注入项（预设 @D 块 + 世界书 @D 条目）按「深度 + 顺序（数字越小越靠上）」插入对话历史内部
 *
 * @return first = 组装后的聊天历史；second = 当前工具调用及插在它后面的内容（无工具调用时为空）。
 * 开启「按预设发送工具调用」的 @D / 附着块把当前工具调用当作历史最后一条计算深度，其余注入项行为不变。
 */
private fun assembleChatHistoryWithDepthInjections(
    chatHistory: List<UIMessage>,
    activeToolChainMessage: UIMessage?,
    preset: PromptPreset,
    beforeCharContents: List<String>,
    charSystemMessage: UIMessage?,
    afterCharContents: List<String>,
    memoryPrompt: String,
    workspacePrompt: String,
    loreEntries: List<ActivatedLoreEntry>,
    legacyModeInjections: List<PromptInjection.ModeInjection>,
    outletMap: Map<String, String>,
): Pair<List<UIMessage>, List<UIMessage>> {
    val chatSize = chatHistory.size
    val depthItems = mutableListOf<InChatDepthItem>()
    val attachItems = mutableListOf<InChatDepthItem>()
    // 聊天历史 + 当前工具调用（供「按预设发送工具调用」的块计算位置）
    val extendedHistory = if (activeToolChainMessage != null) chatHistory + activeToolChainMessage else chatHistory
    val extendedSize = extendedHistory.size

    // a) 来自预设中位置为「聊天中 (IN_CHAT)」或「附着到已有消息 (ATTACH_TO_MESSAGE)」的块（含自定义块与编辑后的内置块）
    preset.blocks
        .filter { it.enabled && it.markerType != PromptMarkerType.CHAT_HISTORY && it.effectivePosition != PromptBlockPosition.RELATIVE }
        .forEach { block ->
            val text = resolveBlockContentText(
                block = block,
                beforeCharContents = beforeCharContents,
                charSystemMessage = charSystemMessage,
                afterCharContents = afterCharContents,
                memoryPrompt = memoryPrompt,
                workspacePrompt = workspacePrompt,
                outletMap = outletMap,
            )
            val reasoningText = resolveBlockReasoningText(block, outletMap)
            val signatureText = resolveBlockSignatureText(block)
            val rawJsonText = resolveBlockRawJsonText(block, outletMap)
            val hasPayload = if (!block.isMarker && block.jsonMode) {
                rawJsonText.isNotEmpty()
            } else {
                text.isNotEmpty() || reasoningText.isNotEmpty() || signatureText.isNotEmpty()
            }
            if (hasPayload) {
                val d = block.depth.coerceAtLeast(0)
                val follows = activeToolChainMessage != null && block.followPresetInToolCall
                val baseHistory = if (follows) extendedHistory else chatHistory
                val baseSize = if (follows) extendedSize else chatSize
                if (!block.jsonMode && block.effectivePosition == PromptBlockPosition.ATTACH_TO_MESSAGE && baseSize > 0) {
                    val attachIdx = (baseSize - 1 - d).coerceIn(0, baseSize - 1)
                    attachItems.add(
                        InChatDepthItem(
                            targetIndex = attachIdx,
                            effectiveDepth = d,
                            order = block.order,
                            role = block.role,
                            blankRole = block.blankRole,
                            content = text,
                            reasoning = reasoningText,
                            reasoningSignature = signatureText,
                            fromLorebook = false,
                            attachToMessage = true,
                            followsToolChain = follows,
                        )
                    )
                } else {
                    val rawIdx = (baseSize - d).coerceIn(0, baseSize)
                    depthItems.add(
                        InChatDepthItem(
                            targetIndex = findSafeInsertIndex(baseHistory, rawIdx),
                            followsToolChain = follows,
                            effectiveDepth = d,
                            order = block.order,
                            role = block.role,
                            blankRole = block.blankRole,
                            jsonMode = !block.isMarker && block.jsonMode,
                            rawJson = rawJsonText,
                            content = text,
                            reasoning = reasoningText,
                            reasoningSignature = signatureText,
                            fromLorebook = false,
                        )
                    )
                }
            }
        }

    // b) 来自旧版 ModeInjection 的对话内注入
    legacyModeInjections.forEach { modeInj ->
        val text = expandOutletMacros(modeInj.content, outletMap).trim()
        if (text.isEmpty()) return@forEach
        val effectiveDepth = when (modeInj.position) {
            InjectionPosition.TOP_OF_CHAT -> 9999
            InjectionPosition.BOTTOM_OF_CHAT -> 1
            InjectionPosition.AT_DEPTH -> modeInj.injectDepth.coerceAtLeast(0)
            else -> return@forEach
        }
        val rawIdx = computeDepthInsertIndex(chatSize, modeInj.position, effectiveDepth) ?: return@forEach
        depthItems.add(
            InChatDepthItem(
                targetIndex = findSafeInsertIndex(chatHistory, rawIdx),
                effectiveDepth = effectiveDepth,
                order = modeInj.priority,
                role = modeInj.role,
                content = text,
                fromLorebook = true,
            )
        )
    }

    // c) 来自世界书的 @D 深度注入条目（含 STICKY_LOCKED_POSITION 锚定位置）
    loreEntries.forEach { activated ->
        val entry = activated.entry
        val text = activated.cleanContent.trim()
        if (text.isEmpty()) return@forEach
        val effectiveDepth = when (entry.position) {
            InjectionPosition.TOP_OF_CHAT -> 9999
            InjectionPosition.BOTTOM_OF_CHAT -> 1
            InjectionPosition.AT_DEPTH -> entry.injectDepth.coerceAtLeast(0)
            else -> return@forEach
        }
        val rawIdx = activated.anchoredChatInsertIndex?.coerceIn(0, chatSize)
            ?: computeDepthInsertIndex(chatSize, entry.position, effectiveDepth)
            ?: return@forEach
        depthItems.add(
            InChatDepthItem(
                targetIndex = findSafeInsertIndex(chatHistory, rawIdx),
                effectiveDepth = effectiveDepth,
                order = entry.priority,
                role = entry.role,
                content = text,
                fromLorebook = true,
            )
        )
    }

    val toolChainTail = listOfNotNull(activeToolChainMessage)
    if (depthItems.isEmpty() && attachItems.isEmpty()) return chatHistory to toolChainTail

    // 有「按预设发送工具调用」的 @D / 附着块时，在「聊天历史 + 当前工具调用」上插入，最后再从工具调用处拆开；
    // 其余注入项的下标仍按原聊天历史计算，且不与当前工具调用合并，结果与原来一致
    val useExtended = activeToolChainMessage != null &&
        (depthItems.any { it.followsToolChain } || attachItems.any { it.followsToolChain })
    // 排序用深度：扩展模式下普通注入项的深度未计入当前工具调用，统一加 1 后与跟随块比较
    fun InChatDepthItem.sortDepth(): Int =
        if (useExtended && !followsToolChain) effectiveDepth + 1 else effectiveDepth

    val result = (if (useExtended) extendedHistory else chatHistory).toMutableList()

    fun splitResult(): Pair<List<UIMessage>, List<UIMessage>> {
        if (!useExtended) return result to toolChainTail
        val toolIdx = result.indexOfFirst { it.id == activeToolChainMessage!!.id }
        if (toolIdx < 0) return result to emptyList()
        return result.subList(0, toolIdx).toList() to result.subList(toolIdx, result.size).toList()
    }

    // 先处理「附着到已有消息 (ATTACH_TO_MESSAGE)」：不改变 result 下标，直接拼接到目标消息
    if (attachItems.isNotEmpty() && result.isNotEmpty()) {
        val byAttachIndex = attachItems.groupBy { it.targetIndex }
        byAttachIndex.forEach { (msgIdx, group) ->
            val targetMsg = result.getOrNull(msgIdx) ?: return@forEach
            val sortedAttach = group.sortedWith(
                compareByDescending<InChatDepthItem> { it.sortDepth() }
                    .thenBy { it.order }
            )
            val appendedText = sortedAttach
                .mapNotNull { it.content.takeIf(String::isNotBlank) }
                .joinToString("\n\n")
            val attachedReasoningParts = sortedAttach.mapNotNull { item ->
                if (item.blankRole) null else createReasoningPart(item.reasoning, item.reasoningSignature)
            }

            var updatedParts = targetMsg.parts
            if (appendedText.isNotEmpty()) {
                val lastTextIdx = updatedParts.indexOfLast { it is UIMessagePart.Text }
                updatedParts = if (lastTextIdx >= 0) {
                    updatedParts.mapIndexed { idx, part ->
                        if (idx == lastTextIdx && part is UIMessagePart.Text) {
                            val joined = if (part.text.isBlank()) appendedText else "${part.text}\n\n$appendedText"
                            part.copy(text = joined)
                        } else {
                            part
                        }
                    }
                } else {
                    updatedParts + UIMessagePart.Text(appendedText)
                }
            }
            if (attachedReasoningParts.isNotEmpty()) {
                val firstNonReasoningIdx = updatedParts.indexOfFirst { it !is UIMessagePart.Reasoning }
                updatedParts = if (firstNonReasoningIdx >= 0) {
                    updatedParts.subList(0, firstNonReasoningIdx) +
                        attachedReasoningParts +
                        updatedParts.subList(firstNonReasoningIdx, updatedParts.size)
                } else {
                    updatedParts + attachedReasoningParts
                }
            }
            result[msgIdx] = targetMsg.copy(parts = updatedParts, isSynthetic = true)
        }
    }

    if (depthItems.isEmpty()) return splitResult()

    val byTargetIndex = depthItems.groupBy { it.targetIndex }

    // 按 targetIndex 从大到小插入（从后往前插，不破坏前面元素的下标）
    byTargetIndex.keys.sortedDescending().forEach { insertIndex ->
        val group = byTargetIndex[insertIndex] ?: return@forEach
        // 核心排序规则：
        // 1. 先按 effectiveDepth 从大到小排（如同样落在对话开头的 depth=9999 排在 depth=10 上面）；
        // 2. 同深度下严格按 order 从小到大排（数字越小越靠上，默认 100）。
        val sortedGroup = group.sortedWith(
            compareByDescending<InChatDepthItem> { it.sortDepth() }
                .thenBy { it.order }
        )
        val curIdx = insertIndex.coerceIn(0, result.size)
        val groupMessages = buildDepthGroupMessages(
            items = sortedGroup,
            precedingChatMessage = if (curIdx > 0) result[curIdx - 1] else null,
            onUpdatePrecedingChatMessage = { updated ->
                if (curIdx > 0) result[curIdx - 1] = updated
            },
            // 当前工具调用不作为世界书条目的「后一条」合并对象（与原来一致：原来它不在聊天历史里）
            followingChatMessage = if (
                curIdx < result.size &&
                !(useExtended && result[curIdx].id == activeToolChainMessage!!.id)
            ) result[curIdx] else null,
            onUpdateFollowingChatMessage = { updated ->
                if (curIdx < result.size) result[curIdx] = updated
            },
        )
        var offset = 0
        groupMessages.forEach { msg ->
            result.add(curIdx + offset, msg)
            offset++
        }
    }

    return splitResult()
}

private data class PendingDepthNode(
    var message: UIMessage,
    val fromLorebook: Boolean,
)

/**
 * 构建单个插入位置的深度注入消息列表：
 * - 预设模块（!item.fromLorebook）：不自动合并相邻的助手或用户模块；仅当角色为特殊空白角色 "?" (blankRole = true) 时，跟随最前一个消息合并进入正文。
 * - 世界书条目（item.fromLorebook）：世界书只有正文，同角色时直接与所在位置（前一条或后一条同角色消息）的正文合并。
 */
private fun buildDepthGroupMessages(
    items: List<InChatDepthItem>,
    precedingChatMessage: UIMessage?,
    onUpdatePrecedingChatMessage: (UIMessage) -> Unit,
    followingChatMessage: UIMessage?,
    onUpdateFollowingChatMessage: (UIMessage) -> Unit,
): List<UIMessage> {
    if (items.isEmpty()) return emptyList()
    var currentPreceding = precedingChatMessage
    var currentFollowing = followingChatMessage
    val nodes = mutableListOf<PendingDepthNode>()

    for (item in items) {
        if (item.jsonMode) {
            createRawJsonMessage(item.rawJson)?.let { msg ->
                nodes.add(PendingDepthNode(msg, fromLorebook = false))
            }
            continue
        }
        if (item.blankRole) {
            val cleanText = item.content.trim()
            if (cleanText.isNotEmpty()) {
                if (nodes.isNotEmpty()) {
                    val last = nodes.last()
                    nodes[nodes.lastIndex] = last.copy(message = appendTextToMessage(last.message, cleanText))
                } else if (currentPreceding != null) {
                    val updated = appendTextToMessage(currentPreceding, cleanText, preserveSynthetic = true)
                    currentPreceding = updated
                    onUpdatePrecedingChatMessage(updated)
                } else {
                    nodes.add(PendingDepthNode(createRoleMessage(MessageRole.SYSTEM, cleanText), fromLorebook = false))
                }
            }
            continue
        }

        if (item.fromLorebook) {
            val cleanText = item.content.trim()
            if (cleanText.isEmpty()) continue
            if (nodes.isNotEmpty() && nodes.last().message.role == item.role && nodes.last().message.rawJsonElementOrNull() == null) {
                // 与组内前一条同角色消息合并正文
                val last = nodes.last()
                val sep = if (last.fromLorebook) "\n" else "\n\n"
                nodes[nodes.lastIndex] = last.copy(message = appendTextToMessage(last.message, cleanText, separator = sep))
            } else if (nodes.isEmpty() && currentPreceding != null && currentPreceding.role == item.role && currentPreceding.rawJsonElementOrNull() == null) {
                // 与插入点前一条同角色聊天消息合并正文（例如 user -> 世界书(user) -> assistant）
                val updated = appendTextToMessage(currentPreceding, cleanText, separator = "\n\n", preserveSynthetic = true)
                currentPreceding = updated
                onUpdatePrecedingChatMessage(updated)
            } else {
                nodes.add(PendingDepthNode(createRoleMessage(item.role, cleanText), fromLorebook = true))
            }
        } else {
            createBlockMessage(
                role = item.role,
                content = item.content,
                reasoning = item.reasoning,
                reasoningSignature = item.reasoningSignature,
            )?.let { msg ->
                // 若前一个节点是同角色的世界书条目，将世界书正文中置合并进当前同角色预设块开头
                if (nodes.isNotEmpty() && nodes.last().fromLorebook && nodes.last().message.role == msg.role) {
                    val loreText = nodes.removeAt(nodes.lastIndex).message.toText()
                    nodes.add(PendingDepthNode(prependTextToMessage(msg, loreText, separator = "\n\n"), fromLorebook = false))
                } else {
                    nodes.add(PendingDepthNode(msg, fromLorebook = false))
                }
            }
        }
    }

    // 检查尾部的世界书条目是否与插入点后一条聊天消息同角色（例如 assistant -> 世界书(user) -> user）
    if (nodes.isNotEmpty() && nodes.last().fromLorebook && currentFollowing != null &&
        currentFollowing.role == nodes.last().message.role && currentFollowing.rawJsonElementOrNull() == null
    ) {
        val loreText = nodes.removeAt(nodes.lastIndex).message.toText()
        val updated = prependTextToMessage(currentFollowing, loreText, separator = "\n\n", preserveSynthetic = true)
        currentFollowing = updated
        onUpdateFollowingChatMessage(updated)
    }

    return nodes.map { it.message }
}

private val RawBlockJson = Json {
    ignoreUnknownKeys = true
}

internal fun createRawJsonMessage(
    rawJsonString: String,
): UIMessage? {
    val clean = rawJsonString.trim()
    if (clean.isEmpty()) return null
    val parsedElement: JsonElement = try {
        RawBlockJson.parseToJsonElement(clean)
    } catch (e: Exception) {
        throw IllegalArgumentException("提示词预设 JSON 模式块解析失败: ${e.message}", e)
    }
    val meta = RawJsonMessageMetadata(rawJson = parsedElement).toMetadata()
    return UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text = "", metadata = meta)),
        isSynthetic = true,
    )
}

internal fun createReasoningPart(
    reasoningText: String,
    signatureText: String,
): UIMessagePart.Reasoning? {
    val cleanReasoning = reasoningText.trim()
    val cleanSig = signatureText.trim()
    if (cleanReasoning.isEmpty() && cleanSig.isEmpty()) return null
    val meta = if (cleanSig.isNotEmpty()) {
        buildJsonObject {
            put("signature", cleanSig)
            put("thoughtSignature", cleanSig)
            put("interactions_signature", cleanSig)
            put("encrypted_content", cleanSig)
        }
    } else {
        null
    }
    return UIMessagePart.Reasoning(
        reasoning = cleanReasoning,
        metadata = meta,
        reasoningType = if (cleanSig.isNotEmpty()) ReasoningType.SUMMARY_TEXT else ReasoningType.REASONING_TEXT,
    )
}

private fun createBlockMessage(
    role: MessageRole,
    content: String,
    reasoning: String = "",
    reasoningSignature: String = "",
): UIMessage? {
    val cleanContent = content.trim()
    val reasoningPart = createReasoningPart(reasoning, reasoningSignature)
    if (cleanContent.isEmpty() && reasoningPart == null) return null
    val parts = buildList {
        if (reasoningPart != null) add(reasoningPart)
        if (cleanContent.isNotEmpty()) add(UIMessagePart.Text(cleanContent))
    }
    return UIMessage(
        role = role,
        parts = parts,
        isSynthetic = true,
    )
}

private fun appendTextToMessage(
    target: UIMessage,
    additionalText: String,
    separator: String = "\n\n",
    preserveSynthetic: Boolean = false,
): UIMessage {
    val cleanAdd = additionalText.trim()
    if (cleanAdd.isEmpty()) return target
    val lastTextIdx = target.parts.indexOfLast { it is UIMessagePart.Text }
    val newParts = if (lastTextIdx >= 0) {
        target.parts.mapIndexed { idx, part ->
            if (idx == lastTextIdx && part is UIMessagePart.Text) {
                val joined = if (part.text.isBlank()) cleanAdd else "${part.text}$separator$cleanAdd"
                part.copy(text = joined)
            } else {
                part
            }
        }
    } else {
        target.parts + UIMessagePart.Text(cleanAdd)
    }
    return target.copy(parts = newParts, isSynthetic = if (preserveSynthetic) target.isSynthetic else true)
}

private fun prependTextToMessage(
    target: UIMessage,
    prefixText: String,
    separator: String = "\n\n",
    preserveSynthetic: Boolean = false,
): UIMessage {
    val cleanPrefix = prefixText.trim()
    if (cleanPrefix.isEmpty()) return target
    val firstTextIdx = target.parts.indexOfFirst { it is UIMessagePart.Text }
    val newParts = if (firstTextIdx >= 0) {
        target.parts.mapIndexed { idx, part ->
            if (idx == firstTextIdx && part is UIMessagePart.Text) {
                val joined = if (part.text.isBlank()) cleanPrefix else "$cleanPrefix$separator${part.text}"
                part.copy(text = joined)
            } else {
                part
            }
        }
    } else {
        target.parts + UIMessagePart.Text(cleanPrefix)
    }
    return target.copy(parts = newParts, isSynthetic = if (preserveSynthetic) target.isSynthetic else true)
}

private fun createRoleMessage(role: MessageRole, content: String): UIMessage {
    return when (role) {
        MessageRole.ASSISTANT -> UIMessage.assistant(content)
        MessageRole.SYSTEM -> UIMessage.system(content)
        else -> UIMessage.user(content)
    }.copy(isSynthetic = true)
}

/**
 * 合并位于列表最开头的连续普通 SYSTEM 消息为单条顶层 SYSTEM 消息（用 "\n\n" 连接），
 * 位于第一条非 SYSTEM 消息（或 JSON 模式块）之后的 SYSTEM 消息保持原位独立。
 */
private fun mergeLeadingSystemMessages(messages: List<UIMessage>): List<UIMessage> {
    if (messages.size <= 1) return messages
    val firstNonSysIdx = messages.indexOfFirst { it.role != MessageRole.SYSTEM || it.rawJsonElementOrNull() != null }
    val leadingCount = if (firstNonSysIdx == -1) messages.size else firstNonSysIdx
    if (leadingCount <= 1) return messages

    val leadingSysMessages = messages.subList(0, leadingCount)
    val leadingReasoningParts = leadingSysMessages.flatMap { msg ->
        msg.parts.filterIsInstance<UIMessagePart.Reasoning>()
    }
    val combinedText = leadingSysMessages
        .mapNotNull { msg ->
            msg.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
                .takeIf { it.isNotEmpty() }
        }
        .joinToString("\n\n")

    val mergedParts = buildList {
        addAll(leadingReasoningParts)
        if (combinedText.isNotEmpty() || isEmpty()) {
            add(UIMessagePart.Text(combinedText))
        }
    }
    val mergedSys = leadingSysMessages.first().copy(
        parts = mergedParts,
        isSynthetic = true,
    )
    return listOf(mergedSys) + messages.subList(leadingCount, messages.size)
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些提供商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
