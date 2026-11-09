package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.DISPLAY_ONLY_PART_KEY
import me.rerere.ai.ui.TOOL_DISPLAY_KEY
import me.rerere.ai.ui.TOOL_DISPLAY_PARTS_KEY
import me.rerere.ai.ui.withDisplay
import me.rerere.ai.provider.CustomHeader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import me.rerere.plugin.http.PluginRequestContext
import me.rerere.plugin.http.PluginRequestContexts
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.rikkahub.data.plugin.ApprovalDecision
import me.rerere.rikkahub.data.plugin.PLUGIN_MAIN_AGENT_APPROVAL_KEY
import me.rerere.rikkahub.data.plugin.PluginBridge
import me.rerere.rikkahub.data.plugin.PluginCtx
import me.rerere.rikkahub.data.plugin.ToolBeforeResult
import me.rerere.rikkahub.data.plugin.isPluginError
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.redactSecretKeyValues
import me.rerere.rikkahub.data.datastore.replaceSecretKeyAliases
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")
private const val MAX_PROVIDER_NETWORK_RETRIES = 4
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_200L
private const val RATE_LIMIT_RETRY_DELAY_MS = 2_500L

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val pluginBridge: PluginBridge,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<MemoryFile>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        autoApproveTools: Boolean = false,
        pollQueuedUserMessages: (suspend () -> List<UIMessage>)? = null,
        isSubAgent: Boolean = false,
        parentConversationId: Uuid? = null,
        subAgentCode: String? = null,
        pluginTask: String = "chat",
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
        fun pluginCtx(step: Int?) = PluginCtx(
            task = pluginTask,
            assistantId = assistant.id,
            conversationId = conversationId,
            isSubAgent = isSubAgent,
            parentConversationId = parentConversationId,
            subAgentCode = subAgentCode,
            stepIndex = step,
            model = model,
            workspaceId = assistant.workspaceId,
        )

        var messages: List<UIMessage> = messages
        val hasCustomStepLimit = maxSteps > 0
        val priorCompletedToolRounds = if (hasCustomStepLimit && messages.lastOrNull()?.getTools()?.any { it.canResumeExecution } == true) {
            // 若是从工具审批恢复执行，统计当前最后一条助手消息中已完成的工具轮数，避免恢复后步数从零重算突破总预算
            val parts = messages.last().parts
            var rounds = 0
            var inToolGroup = false
            for (part in parts) {
                if (part is UIMessagePart.Tool && part.isExecuted && !part.canResumeExecution) {
                    if (!inToolGroup) {
                        rounds++
                        inToolGroup = true
                    }
                } else if (part !is UIMessagePart.Tool) {
                    inToolGroup = false
                }
            }
            rounds
        } else {
            0
        }
        val maxToolRounds = if (hasCustomStepLimit) {
            (maxSteps - priorCompletedToolRounds).coerceAtLeast(1)
        } else {
            256
        }
        val loopLimit = if (hasCustomStepLimit) maxToolRounds + 1 else maxToolRounds

        val eventBase = pluginCtx(null).toJson()
        pluginBridge.emit("generation.start", eventBase)
        try {
        for (stepIndex in 0 until loopLimit) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id}, maxToolRounds=$maxToolRounds)")
            pluginBridge.emit("generation.step", pluginCtx(stepIndex).toJson())

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            var toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                val curCtx = pluginCtx(stepIndex)
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                            pluginCtx = curCtx,
                        )
                        val visual = messages.visualTransforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                            pluginCtx = curCtx,
                        ).let { vm -> pluginBridge.responseVisual(vm, assistant, curCtx) }
                        emit(GenerationChunk.Messages(visual))
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = tools,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    workspaceCwd = workspaceCwd,
                    pluginCtx = curCtx,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    pluginCtx = curCtx,
                ).let { vm -> pluginBridge.responseVisual(vm, assistant, curCtx) }
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    pluginCtx = curCtx,
                )
                messages = pluginBridge.responseFinish(messages, assistant, curCtx)
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                messages.last().usage?.let { u ->
                    pluginBridge.emit("usage", JsonObject(curCtx.toJson() + ("usage" to buildJsonObject {
                        put("promptTokens", u.promptTokens)
                        put("completionTokens", u.completionTokens)
                        put("totalTokens", u.totalTokens)
                        put("cachedTokens", u.cachedTokens)
                    })))
                }
                emit(GenerationChunk.Messages(messages))

                val toolCalls = messages.last().getTools().filter { !it.isExecuted }
                if (toolCalls.isEmpty()) {
                    // Before breaking, check if user sent any steering messages while streaming
                    val steered = pollQueuedUserMessages?.invoke().orEmpty()
                    if (steered.isNotEmpty()) {
                        messages = messages + steered
                        emit(GenerationChunk.Messages(messages))
                        continue
                    }
                    // no tool calls, break
                    break
                }

                // 若设置了单轮最大工具调用步数（hasCustomStepLimit）且已用尽全部允许的工具步数，
                // 不再执行新的工具调用，剥离未执行的悬挂工具块以保持会话历史合法并结束本轮
                if (hasCustomStepLimit && stepIndex >= maxToolRounds) {
                    Log.i(TAG, "generateText: tool step limit ($maxToolRounds) reached, stopping further tool calls")
                    val lastMsg = messages.last()
                    val cleanedParts = lastMsg.parts.filterNot { it is UIMessagePart.Tool && !it.isExecuted }
                    val finalParts = if (cleanedParts.any { it is UIMessagePart.Text && it.text.isNotBlank() }) {
                        cleanedParts
                    } else {
                        cleanedParts + UIMessagePart.Text("（已达到单轮工具调用步数上限 $maxToolRounds 步，停止继续调用工具）")
                    }
                    messages = messages.dropLast(1) + lastMsg.copy(parts = finalParts)
                    emit(GenerationChunk.Messages(messages))
                    break
                }

                // Check for tools that need approval (bypass if autoApproveTools is enabled on sub-conversations,
                // except interactive HITL tools like ask_user / ask_key which always require user input)
                var hasPendingApproval = false
                // 插件审批决策（approval.decide）：优先于工具自身的审批设置
                val pluginApprovals = if (pluginBridge.hasHook(PluginHookKind.APPROVAL, assistant)) {
                    toolCalls.filter { it.approvalState is ToolApprovalState.Auto }
                        .mapNotNull { t -> pluginBridge.approvalDecide(t, assistant, pluginCtx(stepIndex))?.let { t.toolCallId to it } }
                        .toMap()
                } else emptyMap()
                val updatedTools = toolCalls.map { tool ->
                    val toolDef = tools.find { it.name == tool.toolName }
                    val isInteractiveHitl = tool.toolName == "ask_user" || tool.toolName == "ask_key"
                    val pluginDecision = pluginApprovals[tool.toolCallId]
                    when {
                        pluginDecision is ApprovalDecision.Deny -> tool.copy(approvalState = ToolApprovalState.Denied(pluginDecision.reason))
                        pluginDecision == ApprovalDecision.User -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        pluginDecision == ApprovalDecision.MainAgent -> {
                            hasPendingApproval = true
                            val meta = JsonObject((tool.metadata ?: JsonObject(emptyMap())) + (PLUGIN_MAIN_AGENT_APPROVAL_KEY to JsonPrimitive(true)))
                            tool.copy(approvalState = ToolApprovalState.Pending, metadata = meta)
                        }
                        pluginDecision == ApprovalDecision.Auto -> tool
                        (isInteractiveHitl || !autoApproveTools) &&
                            toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        (isInteractiveHitl || !autoApproveTools) && tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != toolCalls) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // 只给真正运行的工具计时；开始时先写一次，界面才能在执行中实时显示耗时
            val startNow = Clock.System.now()
            val startedTools = toolsToProcess.map { tool ->
                val runs = tool.approvalState == ToolApprovalState.Auto || tool.approvalState == ToolApprovalState.Approved
                if (runs) tool.copy(startedAt = startNow, finishedAt = null) else tool
            }
            if (startedTools != toolsToProcess) {
                toolsToProcess = startedTools
                val lastMessage = messages.last()
                val startedParts = lastMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool) {
                        startedTools.find { it.toolCallId == part.toolCallId } ?: part
                    } else {
                        part
                    }
                }
                messages = messages.dropLast(1) + lastMessage.copy(parts = startedParts)
                emit(GenerationChunk.Messages(messages))
            }

            // Handle tools concurrently (supports parallel sub-agents & parallel workspace tools)
            // Use CoroutineStart.UNDISPATCHED so concurrent tool calls (e.g. multiple conversation_send)
            // enter their initial synchronous registration/reservation block strictly in list order.
            val progressMutex = Mutex()
            val executedTools = coroutineScope {
                toolsToProcess.map { tool ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        when (tool.approvalState) {
                            is ToolApprovalState.Denied -> {
                                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                                tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put(
                                                        "error",
                                                        JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                                    )
                                                }
                                            )
                                        )
                                    )
                                )
                            }

                            is ToolApprovalState.Answered -> {
                                val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                                tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(answer)
                                    )
                                )
                            }

                            is ToolApprovalState.Pending -> null

                            else -> {
                                val useSecretAlias = settings.enableSecretKeyAlias
                                val toolScope = this
                                runCatching {
                                    val toolDef = tools.find { toolDef -> toolDef.name == tool.toolName }
                                        ?: error("Tool ${tool.toolName} not found")
                                    val rawArgs = runCatching {
                                        json.parseToJsonElement(tool.input.ifBlank { "{}" })
                                    }.getOrElse {
                                        error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                                    }
                                    val toolCtx = pluginCtx(stepIndex)
                                    // tool.before 看到的是代名词形式的参数，替换真实密钥在插件之后进行
                                    val before = pluginBridge.toolBefore(tool, rawArgs, assistant, toolCtx)
                                    Log.i(TAG, "generateText: executing tool ${toolDef.name}")
                                    val progressElement = PluginBridge.ToolProgressElement { progressParts ->
                                        toolScope.launch {
                                            progressMutex.withLock {
                                                val lastMsg = messages.lastOrNull() ?: return@withLock
                                                val newParts = lastMsg.parts.map { p ->
                                                    if (p is UIMessagePart.Tool && p.toolCallId == tool.toolCallId && p.finishedAt == null) {
                                                        p.copy(output = progressParts)
                                                    } else p
                                                }
                                                messages = messages.dropLast(1) + lastMsg.copy(parts = newParts)
                                                emit(GenerationChunk.Messages(messages))
                                            }
                                        }
                                    }
                                    val rawResult = when (before) {
                                        is ToolBeforeResult.Proceed -> withContext(progressElement) {
                                            toolDef.execute(before.args.replaceSecretKeyAliases(settings, useSecretAlias))
                                        }
                                        is ToolBeforeResult.Result -> before.parts
                                        is ToolBeforeResult.Deny -> listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(buildJsonObject {
                                                    put("error", JsonPrimitive("Tool execution denied by plugin. Reason: ${before.reason}"))
                                                })
                                            )
                                        )
                                    }
                                    val redacted = if (useSecretAlias) {
                                        rawResult.map { part ->
                                            if (part is UIMessagePart.Text) {
                                                part.copy(text = settings.redactSecretKeyValues(part.text, true))
                                            } else part
                                        }
                                    } else {
                                        rawResult
                                    }
                                    val afterHook = pluginBridge.toolAfter(tool, rawArgs, redacted, assistant, toolCtx)
                                    // 仅展示部件（插件 display / MCP _meta.rikka/display）移入 metadata，不发给模型
                                    val (displayOnly, result) = afterHook.partition {
                                        (it.metadata?.get(DISPLAY_ONLY_PART_KEY) as? JsonPrimitive)?.content == "true"
                                    }
                                    val toolMetadata = if (displayOnly.isEmpty()) tool.metadata else JsonObject(
                                        (tool.metadata ?: JsonObject(emptyMap())) + (TOOL_DISPLAY_PARTS_KEY to JsonArray(
                                            displayOnly.map { json.encodeToJsonElement(UIMessagePart.serializer(), it) }
                                        ))
                                    )
                                    val hasShellAccess = tools.any { it.name == "workspace_write_file" || it.name == "workspace_edit_file" }
                                    tool.copy(
                                        output = maybeTruncateToolOutput(tool, result.ifEmpty { listOf(UIMessagePart.Text("")) }, hasShellAccess),
                                        finishedAt = Clock.System.now(),
                                        metadata = toolMetadata,
                                    )
                                }.getOrElse {
                                    if (it is CancellationException) throw it
                                    it.printStackTrace()
                                    val rawErrText = buildString {
                                        append("[${it.javaClass.name}] ${it.message}")
                                        append("\n${it.stackTraceToString()}")
                                    }
                                    val safeErrText = settings.redactSecretKeyValues(rawErrText, useSecretAlias)
                                    tool.copy(
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(
                                                    buildJsonObject {
                                                        put("error", JsonPrimitive(safeErrText))
                                                    }
                                                )
                                            )
                                        ),
                                        finishedAt = Clock.System.now(),
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val reachedStepLimitNow = hasCustomStepLimit && (stepIndex + 1 >= maxToolRounds)
            val lastExecutedToolId = executedTools.lastOrNull()?.toolCallId
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    val executed = executedTools.find { it.toolCallId == part.toolCallId } ?: part
                    if (reachedStepLimitNow && executed.toolCallId == lastExecutedToolId) {
                        val limitNotice = UIMessagePart.Text(
                            "\n\n[System Notice: You have reached the maximum tool call step limit (${stepIndex + 1}/$maxToolRounds steps) for this turn. Do NOT call any more tools. Immediately summarize and output your final response based on the information gathered so far.]"
                        )
                        executed.copy(output = executed.output + limitNotice)
                    } else {
                        executed
                    }
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            messages = messages.transforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            )

            // Mid-turn user message steering: if the user sent a message during tool execution,
            // append it right after the completed tool step so the next LLM turn responds to it immediately
            // and starts a fresh assistant message block.
            val steeredMessages = pollQueuedUserMessages?.invoke().orEmpty()
            if (steeredMessages.isNotEmpty()) {
                pluginBridge.emit("steer.injected", JsonObject(eventBase + ("count" to JsonPrimitive(steeredMessages.size))))
                Log.i(TAG, "generateText: injecting ${steeredMessages.size} mid-turn steered user message(s)")
                messages = messages + steeredMessages
            }

            emit(GenerationChunk.Messages(messages))

            // 本步执行的工具全部声明 endTurn（插件 / MCP）且没有插话：结束本轮，不再请求模型
            val endTurn = steeredMessages.isEmpty() && executedTools.all { executed ->
                tools.find { it.name == executed.toolName }?.endTurn == true
            }
            if (endTurn) {
                Log.i(TAG, "generateText: all executed tools declared endTurn, finishing turn")
                break
            }
        }
        pluginBridge.emit("generation.finish", eventBase)
        } catch (e: CancellationException) {
            pluginBridge.emit("generation.stopped", eventBase)
            throw e
        } catch (e: Throwable) {
            pluginBridge.emit("generation.error", JsonObject(eventBase + ("error" to JsonPrimitive(e.message ?: e.javaClass.name))))
            throw e
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<MemoryFile>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        pluginCtx: PluginCtx = PluginCtx(),
    ) {
        val effectiveSystemPrompt =
            if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                conversationSystemPrompt
            } else {
                assistant.systemPrompt
            }
        val memoryPromptText = if (assistant.enableMemory) {
            buildMemoryPrompt(memories, autoConsolidate = assistant.enableAutoMemoryConsolidation).trim()
        } else {
            ""
        }
        val toolPromptText = tools.mapNotNull { tool ->
            tool.systemPrompt(model, messages).trim().takeIf { it.isNotEmpty() }
        }.joinToString("\n\n")

        val internalMessages = buildList {
            if (settings.enableAdvancedPrompt) {
                // 高级提示词模式下：Char Description 仅代表助手自身的系统提示词；
                // 记忆与工作区/工具附加提示词分别交由独立的内置 📌 模块（记忆 / 工作区提示词）按预设位置注入
                if (effectiveSystemPrompt.isNotBlank()) {
                    add(UIMessage.system(prompt = effectiveSystemPrompt).copy(isSynthetic = true))
                }
            } else {
                val system = buildString {
                    if (effectiveSystemPrompt.isNotBlank()) {
                        append(effectiveSystemPrompt)
                    }
                    if (memoryPromptText.isNotEmpty()) {
                        appendLine()
                        append(memoryPromptText)
                    }
                    if (toolPromptText.isNotEmpty()) {
                        appendLine()
                        append(toolPromptText)
                    }
                }
                if (system.isNotBlank()) {
                    add(UIMessage.system(prompt = system).copy(isSynthetic = true))
                }
            }
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
            memoryPrompt = memoryPromptText,
            toolSystemPrompt = toolPromptText,
            pluginCtx = pluginCtx,
        ).let { pluginBridge.beforeSend(it, assistant, pluginCtx) }

        // 插件请求上下文：经请求头携带到 PluginInterceptor，发往上游前移除
        val pluginToken = PluginRequestContexts.register(
            PluginRequestContext(pluginCtx.toJson()) { true }
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
                add(CustomHeader(PluginRequestContexts.HEADER, pluginToken))
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = (conversationId ?: Uuid.random()).toString(),
        ).let { pluginBridge.paramsBeforeSend(it, assistant, pluginCtx) }
        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                val responseBaseMessages =
                    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        messages
                    } else {
                        messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            modelId = model.id,
                        )
                    }
                var retryCount = 0

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    // 插件 onStream：每次请求（含重试）新建实例
                    val pluginStream = pluginBridge.openStream(assistant, pluginCtx)
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params
                        ).collect { chunk ->
                            try {
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                val chunks = pluginStream?.process(chunk) ?: listOf(chunk)
                                for (c in chunks) {
                                    attemptMessages = streamChunkHandler.handle(attemptMessages, c)
                                }
                                attemptMessages = applyToolDisplays(attemptMessages, tools)
                                onUpdateMessages(attemptMessages)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        pluginStream?.end()?.takeIf { it.isNotEmpty() }?.let { tail ->
                            for (c in tail) {
                                attemptMessages = streamChunkHandler.handle(attemptMessages, c)
                            }
                            attemptMessages = applyToolDisplays(attemptMessages, tools)
                            onUpdateMessages(attemptMessages)
                        }
                        messages = attemptMessages
                        break
                    } catch (error: Throwable) {
                        pluginStream?.abort()
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry,
                        )
                    }
                }
            } else {
                if (pluginBridge.hasHook(PluginHookKind.STREAM, assistant)) {
                    throw IllegalStateException("[plugin] onStream 暂不支持非流式输出，请在助手设置中开启流式输出")
                }
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    enabled = settings.networkSetting.enableAutoRetry,
                ) {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params,
                    )
                }
                messages = applyToolDisplays(messages.handleTextGenerationResult(result = result, model = model), tools)
                onUpdateMessages(messages)
            }
        } finally {
            processingStatus.value = null
            PluginRequestContexts.remove(pluginToken)
        }
    }

    /** 给声明了 display 的工具调用部件写入显示声明（流式期间持续补齐，provider 可能覆盖 metadata）。 */
    private fun applyToolDisplays(messages: List<UIMessage>, tools: List<Tool>): List<UIMessage> {
        val displays = tools.mapNotNull { t -> t.display?.let { t.name to it } }.toMap()
        if (displays.isEmpty()) return messages
        val last = messages.lastOrNull() ?: return messages
        var changed = false
        val parts = last.parts.map { part ->
            val display = (part as? UIMessagePart.Tool)?.let { displays[it.toolName] }
            if (part is UIMessagePart.Tool && display != null && part.metadata?.get(TOOL_DISPLAY_KEY) != display) {
                changed = true
                part.withDisplay(display)
            } else part
        }
        return if (changed) messages.dropLast(1) + last.copy(parts = parts) else messages
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        // 插件错误（含插件 intercept 抛出的 PluginIOException）不是网络波动，直接失败
        if (error.isPluginError()) throw error
        val isRateLimitOrOverload = isRateLimitOrOverloadError(error)
        if ((!enabled && !isRateLimitOrOverload) || (!isRateLimitOrOverload && error !is IOException) || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        val baseDelay = if (isRateLimitOrOverload) RATE_LIMIT_RETRY_DELAY_MS else INITIAL_PROVIDER_RETRY_DELAY_MS
        val retryDelay = baseDelay shl retryCount
        val errorLabel = if (error is IOException) {
            getNetworkErrorMessage(error)
        } else {
            "接口限流或繁忙"
        }
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            errorLabel,
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider request failed ($errorLabel), retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun isRateLimitOrOverloadError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is CancellationException) return false
            val msg = current.message?.lowercase().orEmpty()
            if (
                "429" in msg ||
                "502" in msg ||
                "503" in msg ||
                "504" in msg ||
                "529" in msg ||
                "rate limit" in msg ||
                "rate_limit" in msg ||
                "too many requests" in msg ||
                "resource_exhausted" in msg ||
                "overloaded" in msg ||
                "bad gateway" in msg ||
                "service unavailable" in msg ||
                "gateway timeout" in msg ||
                "temporarily unavailable" in msg
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun getNetworkErrorMessage(error: IOException): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    private fun maybeTruncateToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        if (tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return output

        val toolCallId = tool.toolCallId
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

}
