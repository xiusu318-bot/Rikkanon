package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.allChildConversationIds
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.resolveLogicalSubAgentId
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withoutConversationFields
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    conversationId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = conversationId.toString(),
)

private val forkTitleSuffixRegex = Regex("""\((\d+)\)$""")

internal fun forkConversationTitle(sourceTitle: String, existingTitles: Set<String>): String {
    // 源标题已带 (N) 后缀时递增序号，避免多次 fork 后叠加成 xxx(1)(1)(1)
    val suffix = forkTitleSuffixRegex.find(sourceTitle)
    val baseTitle = suffix?.let { sourceTitle.removeRange(it.range) } ?: sourceTitle
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    return generateSequence(start) { it + 1 }
        .map { "$baseTitle($it)" }
        .first { it !in existingTitles }
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    title = forkConversationTitle(source.title, existingTitles),
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    config = source.config,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

/** 在 [afterNodeId] 节点之后插入压缩检查点；该节点已不存在时返回 null。 */
internal fun insertContextCheckpoint(
    conversation: Conversation,
    afterNodeId: Uuid,
    summary: String,
): Conversation? {
    val nodes = conversation.messageNodes
    val index = nodes.indexOfFirst { it.id == afterNodeId }
    if (index == -1) return null
    val checkpoint = UIMessage.user(summary).copy(isContextCheckpoint = true).toMessageNode()
    return conversation.copy(
        messageNodes = nodes.subList(0, index + 1) + checkpoint + nodes.subList(index + 1, nodes.size),
    )
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

enum class ToolEditAction {
    SAVE_ONLY,
    RERUN_TOOL_AND_CONTINUE,
    CONTINUE_FROM_EDITED_OUTPUT,
}

data class SubConversationStepSummary(
    val toolName: String,
    val inputPreview: String,
    val isExecuted: Boolean,
)

data class SubConversationSendResult(
    val conversationId: Uuid,
    val logicalId: String = conversationId.toString(),
    val title: String,
    val status: String, // "completed" | "running"
    val timedOut: Boolean,
    val reply: String?,
    val toolSteps: List<SubConversationStepSummary>,
    val processingStatus: String?,
    val assistantId: Uuid? = null,
    val assistantName: String? = null,
    val modelName: String? = null,
)

data class SubConversationMessageSnapshot(
    val position: Int,
    val role: String,
    val text: String,
    val toolSteps: List<SubConversationStepSummary> = emptyList(),
)

data class SubConversationReadResult(
    val conversationId: Uuid,
    val title: String,
    val status: String,
    val processingStatus: String?,
    val totalMessages: Int,
    val messages: List<SubConversationMessageSnapshot>,
    val assistantId: Uuid? = null,
    val assistantName: String? = null,
    val modelName: String? = null,
)

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationLoop: GenerationLoop,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
        onSessionRemoved = memoryConsolidationScheduler::onConversationLeft,
    )

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 子代理父子注册锁与并发限流池
    private val subAgentRegistrationMutex = Mutex()
    private val subAgentConcurrencyMutex = Mutex()
    private val subAgentActiveCounts = ConcurrentHashMap<Uuid, Int>()
    private val subAgentWaitQueues = ConcurrentHashMap<Uuid, ArrayDeque<CompletableDeferred<Unit>>>()
    private val subAgentSlotAcquiredDeferreds = ConcurrentHashMap<Uuid, CompletableDeferred<Unit>>()

    // 后台子代理完成自动通知状态机
    private val subAgentNotificationLock = Any()
    private val pendingBackgroundSubAgents = ConcurrentHashMap<Uuid, MutableSet<Uuid>>()
    private val unconsumedCompletedSubAgents = ConcurrentHashMap<Uuid, LinkedHashMap<Uuid, CompletedSubAgentReport>>()
    private val activeWaitingSubAgentReaders = ConcurrentHashMap<Uuid, AtomicInteger>()
    private val parentSubAgentNotifyJobs = ConcurrentHashMap<Uuid, Job>()

    private data class CompletedSubAgentReport(
        val childId: Uuid,
        val logicalId: String,
        val title: String,
        val assistantName: String?,
        val modelName: String?,
        val status: String,
        val reply: String,
        val toolSteps: List<SubConversationStepSummary>,
        val allowInspect: Boolean,
    )

    private suspend fun <T> withSubAgentConcurrencySlot(
        parentId: Uuid,
        childId: Uuid,
        getMaxConcurrency: suspend () -> Int,
        onWaitingQueue: (Int) -> Unit,
        onAcquired: () -> Unit,
        block: suspend () -> T,
    ): T {
        val waiter: CompletableDeferred<Unit>? = subAgentConcurrencyMutex.withLock {
            val limit = getMaxConcurrency().coerceAtLeast(1)
            val active = subAgentActiveCounts[parentId] ?: 0
            if (active < limit) {
                subAgentActiveCounts[parentId] = active + 1
                subAgentSlotAcquiredDeferreds[childId]?.complete(Unit)
                null
            } else {
                val deferred = CompletableDeferred<Unit>()
                val queue = subAgentWaitQueues.getOrPut(parentId) { ArrayDeque() }
                queue.addLast(deferred)
                onWaitingQueue(queue.size)
                deferred
            }
        }

        if (waiter != null) {
            try {
                waiter.await()
                subAgentSlotAcquiredDeferreds[childId]?.complete(Unit)
            } catch (e: Throwable) {
                subAgentConcurrencyMutex.withLock {
                    val queue = subAgentWaitQueues[parentId]
                    val removed = queue?.remove(waiter) == true
                    if (!removed && waiter.isCompleted) {
                        releaseSubAgentSlotLocked(parentId, getMaxConcurrency().coerceAtLeast(1))
                    }
                }
                throw e
            }
        }

        onAcquired()
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                val limit = runCatching { getMaxConcurrency().coerceAtLeast(1) }.getOrDefault(2)
                subAgentConcurrencyMutex.withLock {
                    releaseSubAgentSlotLocked(parentId, limit)
                }
            }
        }
    }

    private fun releaseSubAgentSlotLocked(parentId: Uuid, limit: Int) {
        val currentActive = (subAgentActiveCounts[parentId] ?: 1) - 1
        val queue = subAgentWaitQueues[parentId]
        var active = currentActive.coerceAtLeast(0)
        while (queue != null && queue.isNotEmpty() && active < limit) {
            val next = queue.removeFirst()
            if (next.complete(Unit)) {
                active++
            }
        }
        if (active <= 0 && (queue == null || queue.isEmpty())) {
            subAgentActiveCounts.remove(parentId)
            subAgentWaitQueues.remove(parentId)
        } else {
            subAgentActiveCounts[parentId] = active
        }
    }

    fun cleanup() = runCatching { sessionManager.cleanup() }

    private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
        if (cause != null) session.messageQueue.pause()
        if (session.state.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) {
            session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
        }
        appScope.launch { dispatchNextQueuedMessage(session.id) }
    }

    // 保留 UI/Web 的入口，生命周期和状态查询统一交给 SessionManager。
    fun addConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.release(conversationId)
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        sessionManager.getProcessingStatusFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        sessionManager.withSession(conversationId) { session ->
            ensureInitialized(session)
            settingsStore.selectAssistant(session.state.value.assistantId)
        }
    }

    private suspend fun ensureInitialized(session: ConversationSession) {
        session.initialize {
            loadConversation(session.id) ?: run {
                // 新建对话, 并添加预设消息
                val assistant = settingsStore.awaitLoaded().getCurrentAssistant()
                Conversation.ofId(
                    id = session.id,
                    assistantId = assistant.id,
                    newConversation = true
                ).updateCurrentMessages(assistant.presetMessages)
            }
        }
    }

    // 引入会话配置之前创建的会话没有固定配置，加载时按助手当前的值补上，之后不再随助手变化。
    private suspend fun loadConversation(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        // settingsFlow 在启动初期还是占位值，固定配置必须基于真实设置
        val bound = conversation.bindConfig(settingsStore.awaitLoaded())
        if (bound !== conversation) conversationRepo.updateConversationConfig(bound)
        return bound
    }

    /**
     * 安全获取会话（优先读内存中已初始化的活跃会话，否则从数据库加载），
     * 绝不触发 `getConversationFlow` 在找不到 ID 时自动向数据库插入默认助手空白会话的副作用。
     */
    suspend fun getOrLoadConversation(conversationId: Uuid): Conversation? {
        sessionManager.get(conversationId)?.state?.value
            ?.takeIf { it.messageNodes.isNotEmpty() || it.config != null || it.title.isNotBlank() }
            ?.let { return it }
        return loadConversation(conversationId)
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        sessionManager.getMessageQueueFlow(conversationId)

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessionManager.get(conversationId)?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessionManager.get(conversationId)?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessionManager.snapshot()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = sessionManager.getOrCreate(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessionManager.get(conversationId) ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.awaitLoaded()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    /**
     * 双层 Roll 之内层单步 Roll / 单工具重跑：
     * - 当 [rerunTool] 为 false 时：保留 [message] 中 `0..<partIndex` 的前置步骤（如已执行的工具和思考），
     *   在当前 [MessageNode] 下新建一个分支，截断后续步骤并让模型从此处重新生成下一步。
     * - 当 [rerunTool] 为 true 且 `parts[partIndex]` 是 [UIMessagePart.Tool] 时：
     *   保留 `0..partIndex`，将该工具的输出清空并标记为 `Approved`，让 `GenerationLoop` 原地重跑该工具并继续后续生成。
     */
    fun regenerateAtPart(
        conversationId: Uuid,
        message: UIMessage,
        partIndex: Int,
        rerunTool: Boolean = false,
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = true,
        ) {
            try {
                previousJob?.join()
                val conversation = session.state.value
                val node = conversation.getMessageNodeByMessageId(message.id) ?: return@launchGenerationJob
                val nodeIndex = conversation.messageNodes.indexOf(node)
                if (nodeIndex < 0) return@launchGenerationJob
                val targetMsg = node.messages.firstOrNull { it.id == message.id } ?: return@launchGenerationJob
                if (partIndex !in targetMsg.parts.indices) return@launchGenerationJob

                val prefixParts = if (rerunTool && targetMsg.parts[partIndex] is UIMessagePart.Tool) {
                    val toolPart = targetMsg.parts[partIndex] as UIMessagePart.Tool
                    targetMsg.parts.subList(0, partIndex) + toolPart.copy(
                        output = emptyList(),
                        approvalState = ToolApprovalState.Approved,
                    )
                } else {
                    targetMsg.parts.subList(0, partIndex)
                }

                if (prefixParts.isEmpty()) {
                    // 等价于整条助手消息重发
                    handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                } else {
                    val branchedMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = prefixParts,
                        modelId = targetMsg.modelId,
                        modelSnapshot = targetMsg.modelSnapshot,
                    )
                    val updatedNode = node.copy(
                        messages = node.messages + branchedMessage,
                        selectIndex = node.messages.size,
                    )
                    val truncatedNodes = conversation.messageNodes.subList(0, nodeIndex) + updatedNode
                    saveConversation(conversationId, conversation.copy(messageNodes = truncatedNodes))
                    handleMessageComplete(conversationId, messageRange = 0..nodeIndex)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    /**
     * 直接编辑助手消息中某个工具调用的 `input`（入参 JSON）与 `output`（返回文本），
     * 并可选从该工具处立即重跑工具或基于修改后的输出重新生成后续步骤。
     */
    fun editToolCallPart(
        conversationId: Uuid,
        messageId: Uuid,
        toolCallId: String,
        newInput: String,
        newOutputText: String?,
        actionAfterEdit: ToolEditAction = ToolEditAction.SAVE_ONLY,
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = actionAfterEdit != ToolEditAction.SAVE_ONLY,
        ) {
            try {
                previousJob?.join()
                val conversation = session.state.value
                val node = conversation.getMessageNodeByMessageId(messageId) ?: return@launchGenerationJob
                val nodeIndex = conversation.messageNodes.indexOf(node)
                val targetMsg = node.messages.firstOrNull { it.id == messageId } ?: return@launchGenerationJob
                val toolIndex = targetMsg.parts.indexOfFirst {
                    it is UIMessagePart.Tool && it.toolCallId == toolCallId
                }
                if (toolIndex < 0) return@launchGenerationJob
                val originalTool = targetMsg.parts[toolIndex] as UIMessagePart.Tool

                val updatedOutput = when {
                    actionAfterEdit == ToolEditAction.RERUN_TOOL_AND_CONTINUE -> emptyList()
                    newOutputText != null -> {
                        val nonTextParts = originalTool.output.filterNot { it is UIMessagePart.Text }
                        listOf(UIMessagePart.Text(newOutputText)) + nonTextParts
                    }
                    else -> originalTool.output
                }

                val updatedTool = originalTool.copy(
                    input = newInput,
                    output = updatedOutput,
                    approvalState = if (actionAfterEdit == ToolEditAction.RERUN_TOOL_AND_CONTINUE) {
                        ToolApprovalState.Approved
                    } else {
                        originalTool.approvalState
                    },
                )

                when (actionAfterEdit) {
                    ToolEditAction.SAVE_ONLY -> {
                        val newParts = targetMsg.parts.toMutableList().apply {
                            set(toolIndex, updatedTool)
                        }
                        val updatedNode = node.copy(
                            messages = node.messages.map { msg ->
                                if (msg.id == messageId) msg.copy(parts = newParts) else msg
                            }
                        )
                        val updatedNodes = conversation.messageNodes.toMutableList().apply {
                            set(nodeIndex, updatedNode)
                        }
                        saveConversation(conversationId, conversation.copy(messageNodes = updatedNodes))
                    }

                    ToolEditAction.RERUN_TOOL_AND_CONTINUE,
                    ToolEditAction.CONTINUE_FROM_EDITED_OUTPUT -> {
                        val truncatedParts = targetMsg.parts.subList(0, toolIndex) + updatedTool
                        val branchedMessage = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = truncatedParts,
                            modelId = targetMsg.modelId,
                            modelSnapshot = targetMsg.modelSnapshot,
                        )
                        val updatedNode = node.copy(
                            messages = node.messages + branchedMessage,
                            selectIndex = node.messages.size,
                        )
                        val truncatedNodes = conversation.messageNodes.subList(0, nodeIndex) + updatedNode
                        saveConversation(conversationId, conversation.copy(messageNodes = truncatedNodes))
                        handleMessageComplete(conversationId, messageRange = 0..nodeIndex)
                        _generationDoneFlow.emit(conversationId)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = "编辑工具调用失败")
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state
                    val isAskKeyAnswered = answer != null && conversation.currentMessages.any { message ->
                        message.getTools().any { it.toolCallId == toolCallId && it.toolName == "ask_key" }
                    }
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(
                        messageNodes = updatedNodes,
                        config = if (isAskKeyAnswered) {
                            conversation.config?.copy(enableSecretKeyAlias = true)
                        } else {
                            conversation.config
                        },
                    )
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.awaitLoaded()
        val initialConversation = getConversationFlow(conversationId).value
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(initialConversation)
        val model = settings.getChatModelOf(initialConversation)
            ?: throw IllegalStateException("No chat model selected")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools(assistant).isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            val isSubConversation = conversation.config?.autoApproveTools == true ||
                conversation.config?.parentConversationId != null
            val autoApproveTools = if (isSubConversation) assistant.subAgentAutoApproveTools else false

            val tools = try {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = conversation.workspaceCwd,
                    conversationId = conversationId,
                    chatService = this,
                    isSubConversation = isSubConversation,
                )
            } catch (error: InvalidMcpServerNamesException) {
                sessionManager.get(conversationId)?.messageQueue?.pause()
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        )
                    ),
                    conversationId = conversationId,
                )
                return
            }

            // start generating
            val session = sessionManager.getOrCreate(conversationId)
            val parentId = conversation.config?.parentConversationId
            val configuredMaxSteps = if (isSubConversation) {
                val overrideSteps = conversation.config?.subAgentMaxStepsOverride
                when {
                    overrideSteps != null -> if (overrideSteps <= 0) 256 else overrideSteps
                    assistant.subAgentMaxSteps > 0 -> assistant.subAgentMaxSteps
                    else -> 256
                }
            } else {
                256
            }

            val runGeneration: suspend () -> Unit = {
                generationLoop.generateText(
                    settings = settings,
                    model = model,
                    processingStatus = session.processingStatus,
                    messages = conversation.currentMessages.let {
                        if (messageRange != null) {
                            it.subList(messageRange.start, messageRange.endInclusive + 1)
                        } else {
                            it
                        }
                    },
                    assistant = assistant,
                    conversationId = conversationId,
                    conversationSystemPrompt = conversation.customSystemPrompt,
                    workspaceCwd = conversation.workspaceCwd,
                    memories = memoryRepository.getFiles(assistant.memoryId),
                    inputTransformers = buildList {
                        addAll(inputTransformers)
                        add(templateTransformer)
                        add(workspaceReminderTransformer)
                    },
                    outputTransformers = outputTransformers,
                    tools = tools,
                    maxSteps = configuredMaxSteps,
                    autoApproveTools = autoApproveTools,
                    pollQueuedUserMessages = {
                        val steered = mutableListOf<UIMessage>()
                        // 若该对话有后台运行完毕且尚未被 conversation_read 消费的子代理结果，在下一步直接通过 Steer 通道通知父代理
                        drainCompletedSubAgentNotificationText(conversationId)?.let { notifyText ->
                            steered += UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(UIMessagePart.Text(notifyText)),
                            )
                        }
                        while (true) {
                            val next = synchronized(session) {
                                session.messageQueue.takeNext()
                            } ?: break
                            if (next.answer) {
                                val processed = preprocessUserInputParts(next.parts, assistant)
                                steered += UIMessage(
                                    role = MessageRole.USER,
                                    parts = processed,
                                )
                            }
                        }
                        steered
                    },
                ).onCompletion {
                    // 可能被取消了，或者意外结束，兜底更新
                    val updatedConversation = session.finishGeneration { conversation ->
                        saveConversation(conversationId, conversation)
                    }

                    // 生成结束：取消 Live Update 通知，后台时发送完成通知
                    appEventBus.emit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            contentPreview = updatedConversation.currentMessages.lastOrNull()
                                ?.toText()?.take(50)?.trim() ?: "",
                        )
                    )
                }.collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            val updatedConversation = getConversationFlow(conversationId).value
                                .updateCurrentMessages(chunk.messages)
                            updateConversation(conversationId, updatedConversation)

                            // 通知等边缘副作用由 ChatNotificationManager 消费；
                            // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                            chunk.messages.lastOrNull()?.let { lastMessage ->
                                appEventBus.tryEmit(
                                    AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                                )
                            }
                        }
                    }
                }
            }

            if (isSubConversation && parentId != null) {
                withSubAgentConcurrencySlot(
                    parentId = parentId,
                    childId = conversationId,
                    getMaxConcurrency = {
                        val latestSettings = settingsStore.awaitLoaded()
                        val parentConv = getOrLoadConversation(parentId)
                        val parentAssistant = parentConv?.let { latestSettings.getStoredAssistantOf(it) }
                            ?: latestSettings.getCurrentAssistant()
                        parentAssistant.subAgentMaxConcurrency.coerceAtLeast(1)
                    },
                    onWaitingQueue = { queuePos ->
                        session.processingStatus.value = "排队等待并发执行 (#$queuePos)..."
                    },
                    onAcquired = {
                        if (session.processingStatus.value?.startsWith("排队等待") == true) {
                            session.processingStatus.value = null
                        }
                    },
                    block = runGeneration,
                )
            } else {
                runGeneration()
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            if (it is CancellationException) throw it
            sessionManager.get(conversationId)?.messageQueue?.pause()

            // 若是后台子代理请求失败且尚未产生有效助手回复，把错误详情记入子对话，绝不留空白空壳
            val failedSession = sessionManager.get(conversationId)
            val failedConv = failedSession?.state?.value
            if (failedConv?.config?.parentConversationId != null) {
                val lastNode = failedConv.messageNodes.lastOrNull()
                if (lastNode == null || lastNode.role == MessageRole.USER || lastNode.currentMessage.parts.isEmptyInputMessage()) {
                    val errMsg = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Text("⚠️ 子代理执行失败: ${it.message ?: it.javaClass.simpleName}")
                        ),
                        modelId = model.id,
                    )
                    val updatedNodes = if (lastNode?.role == MessageRole.ASSISTANT && lastNode.currentMessage.parts.isEmptyInputMessage()) {
                        failedConv.messageNodes.dropLast(1) + errMsg.toMessageNode()
                    } else {
                        failedConv.messageNodes + errMsg.toMessageNode()
                    }
                    runCatching {
                        saveConversation(conversationId, failedConv.copy(messageNodes = updatedNodes))
                    }
                }
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value

            sessionManager.launchWithSession(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            sessionManager.launchWithSession(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
            if (assistant.enableMemory && assistant.enableAutoMemoryConsolidation) memoryConsolidationScheduler.onTurnFinished(finalConversation)
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        val settings = settingsStore.awaitLoaded()
        // 关掉自动生成后只跳过对话结束时的自动调用，手动重新生成照常执行
        if (!force && !settings.enableAutoTitle) return@withContext

        runCatching {
            val model = settings.findModelById(settings.fastModelId)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_not_found))
            val provider = model.findProvider(settings.providers)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_provider_not_found))

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.awaitLoaded()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessionManager.get(conversationId)?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessionManager.get(conversationId)?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val session = sessionManager.getOrCreate(conversationId)
        // 生成循环按下标回写消息，期间插入节点会让回复写到错误的节点上。
        check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }

        val settings = settingsStore.awaitLoaded()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val nodes = conversation.messageNodes

        // 上一个检查点之前的消息已被它的摘要覆盖，只压缩它之后、保留区之前的部分。
        val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val cutIndex = nodes.size - keepRecentMessages.coerceAtLeast(0)
        if (cutIndex <= checkpointIndex + 1) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        // 上一份摘要一并交给模型，新摘要才能覆盖完整历史。
        val messagesToCompress = nodes.subList(checkpointIndex.coerceAtLeast(0), cutIndex)
            .map { it.currentMessage }
        // 生成循环只从最后一条消息恢复工具调用，待处理的工具被压到检查点之前就再也不会执行。
        check(messagesToCompress.none { message ->
            message.getTools().any { it.isPending || it.canResumeExecution }
        }) { context.getString(R.string.chat_page_compress_pending_tools) }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") {
                // 上一份摘要是更早历史的唯一来源，不能截断。
                it.summaryAsText(maxLength = if (it.isContextCheckpoint) Int.MAX_VALUE else 2000)
            }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, conversationId),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // 原消息原样保留，只在切点插入摘要。摘要生成期间对话可能已变化，
        // 因此按节点定位插入到最新状态，而不是用调用时的快照整体覆盖。
        val newConversation = synchronized(session) {
            check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }
            val updated = insertContextCheckpoint(
                conversation = session.state.value,
                afterNodeId = nodes[cutIndex - 1].id,
                summary = compressedSummaries.joinToString("\n\n"),
            ) ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
            updated.copy(chatSuggestions = emptyList()).also { updateConversation(conversationId, it) }
        }

        saveConversation(conversationId, newConversation)
    }

    // ---- 聊天页配置 ----

    /**
     * 聊天页对助手的修改：会话持有的字段只改当前会话，其余写回助手设置。
     *
     * @param update 接收会话视角下的助手，返回修改后的助手
     */
    suspend fun updateChatAssistant(conversationId: Uuid, update: (Assistant) -> Assistant) {
        sessionManager.withSession(conversationId) { session ->
            ensureInitialized(session)
            val settings = settingsStore.awaitLoaded()
            val conversation = session.state.value
            val stored = settings.getStoredAssistantOf(conversation)
            val updated = update(settings.getAssistantOf(conversation))
            val assistant = updated.withoutConversationFields(conversation, stored)
            session.updateMetadata(
                update = { it.withAssistantUpdate(updated, settings) },
                persist = conversationRepo::updateConversationConfig,
            )
            if (assistant != stored) {
                settingsStore.update { latest ->
                    latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) assistant else it })
                }
            }
        }
    }

    /**
     * 切换搜索方式，传 null 的一项保持不变。
     * 模型内置搜索在会话开始前是模型自身的开关，开始后固定在会话上。
     */
    suspend fun updateChatSearch(
        conversationId: Uuid,
        enableWebSearch: Boolean? = null,
        builtInSearch: Boolean? = null,
    ) {
        sessionManager.withSession(conversationId) { session ->
            ensureInitialized(session)
            val conversation = session.state.value
            settingsStore.update { settings ->
                val assistant = settings.getAssistantOf(conversation)
                val model = settings.getChatModelOf(conversation)
                settings.copy(
                    assistants = if (enableWebSearch == null) {
                        settings.assistants
                    } else {
                        settings.assistants.map {
                            if (it.id == assistant.id) it.copy(enableWebSearch = enableWebSearch) else it
                        }
                    },
                    providers = if (builtInSearch == null || model == null) {
                        settings.providers
                    } else {
                        settings.providers.map { provider ->
                            provider.editModel(
                                model.copy(
                                    tools = if (builtInSearch) {
                                        model.tools + BuiltInTools.Search
                                    } else {
                                        model.tools - BuiltInTools.Search
                                    }
                                )
                            )
                        }
                    },
                )
            }
        }
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = sessionManager.getOrCreate(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.updateConversation(conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        sessionManager.withSession(conversationId) { session ->
            session.initialize {
                loadConversation(conversationId)
                    ?: throw NotFoundException("Conversation not found")
            }
            session.updateMetadata(update, persist)
        }
    }

    suspend fun toggleConversationPinned(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
        )
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            // 文件夹属于助手，移动后清除原助手的文件夹归属。
            update = { it.copy(assistantId = assistantId, folderId = null) },
            persist = { conversationRepo.updateConversationAssistant(conversationId, it.assistantId) },
        )
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessionManager.get(conversationId) != null) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.snapshot().any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.snapshot()
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val session = sessionManager.get(newConversation.id)
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        // 会话落库即视为开始，此时把助手的配置固定到会话上
        val settings = settingsStore.awaitLoaded()
        val updatedConversation = conversation.bindConfig(settings).fillModelSnapshots(settings)
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }

        // 删除消息或切换分支也可能解除工具审批阻塞，保存成功后重新检查队列。
        // 调度器仍会检查当前生成任务、待审批工具、暂停状态及编辑占位。
        dispatchNextQueuedMessage(conversationId)
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.awaitLoaded()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.awaitLoaded()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            val original = node.messages.first { it.id == messageId }
            if (original.isContextCheckpoint) {
                // 摘要原地改写：新建分支会丢掉检查点标记，删除摘要时还会露出旧版本。
                return@map node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == messageId) message.copy(parts = processedParts) else message
                    }
                )
            }

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                    // 编辑的是原消息的内容，来源仍是生成它的模型
                    modelId = original.modelId,
                    modelSnapshot = original.modelSnapshot,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val existingTitles = conversationRepo
            .getConversationsOfAssistant(currentConversation.assistantId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val rawFork = createForkConversation(currentConversation, copiedNodes, existingTitles)

        // 若被分支的主对话存在关联子代理，为新分支深拷贝独立的子代理副本（将副本的 parentConversationId 指向新分支主对话），
        // 并在新分支主对话的 subAgentIdMap 中将原代号（如 "sub_1" 及历史旧ID）指向新克隆的子代理 UUID，
        // 做到既不改动一字历史上下文（100% 保住缓存），又让新分支拥有完全独立的子代理且点「返回主对话」准确回到新分支。
        val truncatedSource = currentConversation.copy(messageNodes = copiedNodes)
        val sourceChildIds = truncatedSource.allChildConversationIds.toList()
        val forkConversation = if (sourceChildIds.isNotEmpty()) {
            val oldMap = currentConversation.config?.subAgentIdMap.orEmpty()
            val newIdMap = mutableMapOf<String, Uuid>()
            val newChildIds = LinkedHashSet<Uuid>()
            var nextSubIdx = 1

            for (oldChildId in sourceChildIds) {
                val origChild = getOrLoadConversation(oldChildId) ?: continue
                val clonedChildId = Uuid.random()
                val clonedChildNodes = origChild.messageNodes.map { node ->
                    node.copy(
                        id = Uuid.random(),
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part -> part.copyWithForkedFileUrl() }
                            )
                        }
                    )
                }
                val clonedChildConfig = (origChild.config ?: me.rerere.rikkahub.data.model.ConversationConfig()).copy(
                    parentConversationId = rawFork.id,
                    childConversationIds = emptySet(),
                    deletedChildConversationIds = emptySet(),
                    subAgentIdMap = emptyMap(),
                )
                val clonedChild = origChild.copy(
                    id = clonedChildId,
                    messageNodes = clonedChildNodes,
                    config = clonedChildConfig,
                )
                saveConversation(clonedChildId, clonedChild)
                newChildIds.add(clonedChildId)

                val matchingKeys = oldMap.entries.filter { it.value == oldChildId }.map { it.key }
                var hasSubKey = false
                for (key in matchingKeys) {
                    newIdMap[key] = clonedChildId
                    if (key.startsWith("sub_")) {
                        hasSubKey = true
                        val idx = key.removePrefix("sub_").toIntOrNull()
                        if (idx != null && idx >= nextSubIdx) {
                            nextSubIdx = idx + 1
                        }
                    }
                }
                if (!hasSubKey) {
                    while ("sub_$nextSubIdx" in newIdMap) {
                        nextSubIdx++
                    }
                    newIdMap["sub_$nextSubIdx"] = clonedChildId
                    nextSubIdx++
                }
                // 同时将旧真实 UUID 字符串映射到新克隆的子代理 UUID，兼容历史消息中写了旧 UUID 的对话
                newIdMap[oldChildId.toString()] = clonedChildId
            }

            val baseConfig = rawFork.config ?: me.rerere.rikkahub.data.model.ConversationConfig()
            rawFork.copy(
                config = baseConfig.copy(
                    childConversationIds = newChildIds,
                    deletedChildConversationIds = emptySet(),
                    subAgentIdMap = newIdMap,
                )
            )
        } else {
            rawFork
        }

        saveConversation(forkConversation.id, forkConversation)
        memoryConsolidationScheduler.onConversationForked(forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        synchronized(subAgentNotificationLock) {
            parentSubAgentNotifyJobs.remove(conversationId)?.cancel()
            pendingBackgroundSubAgents.values.forEach { it.remove(conversationId) }
        }
        val session = sessionManager.get(conversationId) ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        finishInterruptedPendingTools(conversationId)
    }

    // ---- 子对话（Sub-Agent）操控与实时状态 ----

    fun getConversationRuntimeStatus(conversationId: Uuid): Pair<String, String?> {
        val session = sessionManager.get(conversationId) ?: return "idle" to null
        val isGen = session.isGenerating ||
            session.submittingMessage != null ||
            session.messageQueue.state.value.messages.isNotEmpty()
        val hasPendingApproval = session.state.value.currentMessages.any { msg ->
            msg.parts.any { it is UIMessagePart.Tool && it.isPending }
        }
        val status = when {
            isGen -> "running"
            hasPendingApproval -> "waiting_approval"
            session.messageQueue.state.value.paused -> "paused"
            else -> "idle"
        }
        return status to session.processingStatus.value
    }

    /**
     * 主对话通过 `conversation_send` 工具创建或向子对话发送一条普通用户指令（[MessageRole.USER]）。
     * 在子对话视角中，它只是一条标准对话，收到用户指令后正常调用工具并回复。
     */
    suspend fun sendToSubConversation(
        parentConversationId: Uuid,
        targetConversationId: Uuid? = null,
        preallocatedNewTargetId: Uuid? = null,
        targetAssistantId: Uuid? = null,
        title: String? = null,
        message: String,
        wait: Boolean = true,
        timeoutMs: Long = 60_000L,
        interrupt: Boolean = false,
        maxStepsOverride: Int? = null,
    ): SubConversationSendResult {
        require(message.isNotBlank()) { "message must not be blank" }
        require(targetConversationId != parentConversationId) {
            "Cannot send sub-agent message to the current parent conversation itself ($parentConversationId)"
        }

        val targetId = targetConversationId ?: preallocatedNewTargetId ?: Uuid.random()
        // 若是新建子代理，在进入任何挂起 I/O 前确保已在内存中同步分配 sub_N 编号，严格保证并发调用顺序
        val preassignedLogicalId = if (targetConversationId == null) {
            reserveChildConversationIdImmediate(parentConversationId, targetId)
        } else null

        val settings = settingsStore.awaitLoaded()
        val parentConversation = getConversationFlow(parentConversationId).value.bindConfig(settings)
        val parentStoredAssistant = settings.getStoredAssistantOf(parentConversation)
        val parentEffectiveAssistant = settings.getAssistantOf(parentConversation)

        // 若传入了 targetConversationId（追问已有子代理），强制校验其必须属于当前主对话派生的子代理，严禁触碰普通用户对话
        if (targetConversationId != null) {
            val existingTarget = getOrLoadConversation(targetConversationId)
                ?: error("Sub-agent conversation not found: $targetConversationId")
            val isOwnedSubAgent = existingTarget.config?.parentConversationId == parentConversationId ||
                (targetConversationId in parentConversation.config?.childConversationIds.orEmpty() && existingTarget.config?.parentConversationId != null)
            require(isOwnedSubAgent) {
                "Permission denied: conversation $targetConversationId is not a sub-agent of the current conversation"
            }
        }

        // 解析子代理绑定的目标助手（按传入的 assistant_id 匹配绑定的子代理助手）
        val resolvedSubAssistant = (targetAssistantId?.let { settings.getAssistantById(it) })
            ?: (parentStoredAssistant.effectiveSubAgentIds.firstOrNull { id -> settings.assistants.any { it.id == id } }
                ?.let { settings.getAssistantById(it) })
            ?: parentEffectiveAssistant
        val resolvedSubModel = settings.findModelById(resolvedSubAssistant.chatModelId ?: settings.chatModelId)
        val builtInSearch = resolvedSubModel != null && BuiltInTools.Search in resolvedSubModel.tools
        val parentWorkspaceId = parentConversation.config?.workspaceId ?: parentEffectiveAssistant.workspaceId
        val sharedWorkspaceId = if (parentStoredAssistant.subAgentShareWorkspace) {
            parentWorkspaceId ?: resolvedSubAssistant.workspaceId
        } else {
            resolvedSubAssistant.workspaceId
        }
        val sharedWorkspaceCwd = if (parentStoredAssistant.subAgentShareWorkspace && sharedWorkspaceId == parentWorkspaceId) {
            parentConversation.workspaceCwd
        } else {
            null
        }
        val subEnabledSkills = resolvedSubAssistant.enabledSkills

        val targetSession = sessionManager.getOrCreate(targetId)
        // 持有引用计数，防止 5 秒空闲定时器在排队或刚创建期间将子代理会话从内存卸载
        targetSession.acquire()

        try {
            // 将子对话 ID 持久化记录到主对话的 config.childConversationIds 与 subAgentIdMap 中，获取局部代号（如 "sub_1"）
            val logicalId = registerChildConversation(parentConversationId, targetId, preassignedLogicalId)

            // 初始化或创建子对话
            targetSession.initialize {
                loadConversation(targetId) ?: run {
                    val subConfig = me.rerere.rikkahub.data.model.ConversationConfig(
                        chatModelId = resolvedSubModel?.id ?: resolvedSubAssistant.chatModelId,
                        reasoningLevel = resolvedSubAssistant.reasoningLevel,
                        enableWebSearch = resolvedSubAssistant.enableWebSearch,
                        builtInSearch = builtInSearch,
                        mcpServers = resolvedSubAssistant.mcpServers,
                        workspaceId = sharedWorkspaceId,
                        enabledSkills = subEnabledSkills,
                        disabledWorkspaceTools = resolvedSubAssistant.disabledWorkspaceTools,
                        enableSecretKeyAlias = resolvedSubAssistant.enableSecretKeyAlias || parentEffectiveAssistant.enableSecretKeyAlias,
                        enableAskKeyTool = false,
                        childConversationIds = emptySet(),
                        parentConversationId = parentConversationId,
                        autoApproveTools = resolvedSubAssistant.subAgentAutoApproveTools,
                        subAgentMaxStepsOverride = maxStepsOverride,
                    )
                    val fallbackTitle = message.lineSequence().firstOrNull()?.trim()?.take(16)?.ifBlank { null }
                        ?: "子代理-$logicalId"
                    Conversation(
                        id = targetId,
                        assistantId = resolvedSubAssistant.id,
                        title = title?.trim()?.ifBlank { null } ?: fallbackTitle,
                        messageNodes = emptyList(),
                        customSystemPrompt = if (resolvedSubAssistant.id == parentConversation.assistantId) {
                            parentConversation.customSystemPrompt
                        } else {
                            null
                        },
                        modeInjectionIds = resolvedSubAssistant.modeInjectionIds,
                        lorebookIds = resolvedSubAssistant.lorebookIds,
                        config = subConfig,
                        workspaceCwd = sharedWorkspaceCwd,
                        folderId = if (resolvedSubAssistant.id == parentConversation.assistantId) {
                            parentConversation.folderId
                        } else {
                            null
                        },
                    )
                }
            }

            // 确保子对话配置了最新工具/免审批/步数限制状态，并立即落库防止成为未持久化空壳
            val existingChild = targetSession.state.value
            val updatedChildTitle = title?.trim()?.takeIf { it.isNotEmpty() }
                ?: existingChild.title.takeIf { it.isNotBlank() && !it.startsWith("Sub-", ignoreCase = true) }
                ?: "子代理-$logicalId"
            val updatedChildConfig = (existingChild.config ?: parentConversation.config)?.copy(
                parentConversationId = existingChild.config?.parentConversationId ?: parentConversationId,
                workspaceId = existingChild.config?.workspaceId ?: sharedWorkspaceId,
                disabledWorkspaceTools = resolvedSubAssistant.disabledWorkspaceTools,
                enableSecretKeyAlias = resolvedSubAssistant.enableSecretKeyAlias || parentEffectiveAssistant.enableSecretKeyAlias,
                mcpServers = resolvedSubAssistant.mcpServers,
                enabledSkills = subEnabledSkills,
                enableWebSearch = resolvedSubAssistant.enableWebSearch,
                autoApproveTools = resolvedSubAssistant.subAgentAutoApproveTools,
                subAgentMaxStepsOverride = maxStepsOverride,
            )
            val updatedChild = existingChild.copy(
                assistantId = if (targetConversationId == null) resolvedSubAssistant.id else existingChild.assistantId,
                title = updatedChildTitle,
                config = updatedChildConfig,
            )
            if (
                !conversationRepo.existsConversationById(targetId) ||
                updatedChild != existingChild
            ) {
                saveConversation(targetId, updatedChild)
            }

            val childAssistant = settings.getAssistantOf(targetSession.state.value)
            val childModel = settings.getChatModelOf(targetSession.state.value)
            val childAssistantName = childAssistant.name.ifBlank { context.getString(R.string.assistant_page_default_assistant) }
            val childModelName = childModel?.displayName

            // 开启新一轮消息前，清除该子代理上一轮可能残留的未消费后台通知
            markSubAgentResultConsumed(parentConversationId, targetId)

            if (interrupt && targetSession.isGenerating) {
                stopGeneration(targetId)
            }

            val slotAcquiredDeferred = CompletableDeferred<Unit>()
            subAgentSlotAcquiredDeferreds[targetId] = slotAcquiredDeferred
            val replyDeferred = CompletableDeferred<String?>()
            replyDeferred.invokeOnCompletion {
                subAgentSlotAcquiredDeferreds.remove(targetId)
                slotAcquiredDeferred.complete(Unit)
                targetSession.release()
                Unit
            }

            synchronized(targetSession) {
                targetSession.messageQueue.resume()
                targetSession.messageQueue.enqueue(
                    parts = listOf(UIMessagePart.Text(message)),
                    answer = true,
                    reply = replyDeferred,
                )
                dispatchNextQueuedMessage(targetId)
            }

            // 等待几毫秒确保后台协程已将 USER 消息写入会话并落库，杜绝异步返回瞬间 total_messages == 0
            withTimeoutOrNull(1_500L) {
                while (
                    targetSession.state.value.messageNodes.isEmpty() &&
                    !replyDeferred.isCompleted &&
                    (targetSession.submittingMessage != null || targetSession.messageQueue.state.value.messages.isNotEmpty() || targetSession.isGenerating)
                ) {
                    delay(15)
                }
            }

            if (!wait) {
                registerBackgroundSubAgentWatch(
                    parentConversationId = parentConversationId,
                    targetId = targetId,
                    logicalId = logicalId,
                    replyDeferred = replyDeferred,
                    allowInspect = parentEffectiveAssistant.subAgentAllowInspect,
                )
                val (status, procStatus) = getConversationRuntimeStatus(targetId)
                return SubConversationSendResult(
                    conversationId = targetId,
                    logicalId = logicalId,
                    title = targetSession.state.value.title,
                    status = if (status == "idle") "running" else status,
                    timedOut = false,
                    reply = null,
                    toolSteps = extractRecentToolSteps(targetSession.state.value),
                    processingStatus = procStatus,
                    assistantId = childAssistant.id,
                    assistantName = childAssistantName,
                    modelName = childModelName,
                )
            }

            // 当存在并发排队限制时，排队等待槽位的时间不计入子代理自身的执行超时时间
            while (!slotAcquiredDeferred.isCompleted && !replyDeferred.isCompleted) {
                delay(50)
            }

            val boundedTimeout = timeoutMs.coerceIn(1_000L, 600_000L)
            val completedReply = withTimeoutOrNull(boundedTimeout) {
                runCatching { replyDeferred.await() }.getOrElse { err ->
                    "Error: ${err.message}"
                }
            }

            val latestChild = targetSession.state.value
            val (status, procStatus) = getConversationRuntimeStatus(targetId)
            val timedOut = completedReply == null && (targetSession.isGenerating || status == "running")
            if (timedOut) {
                registerBackgroundSubAgentWatch(
                    parentConversationId = parentConversationId,
                    targetId = targetId,
                    logicalId = logicalId,
                    replyDeferred = replyDeferred,
                    allowInspect = parentEffectiveAssistant.subAgentAllowInspect,
                )
            }
            val finalReply = completedReply ?: latestChild.currentMessages
                .lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.toText()

            return SubConversationSendResult(
                conversationId = targetId,
                logicalId = logicalId,
                title = latestChild.title,
                status = if (timedOut) "running" else "completed",
                timedOut = timedOut,
                reply = finalReply,
                toolSteps = extractRecentToolSteps(latestChild),
                processingStatus = procStatus,
                assistantId = childAssistant.id,
                assistantName = childAssistantName,
                modelName = childModelName,
            )
        } catch (t: Throwable) {
            targetSession.release()
            throw t
        }
    }

    /**
     * 批量等待多个子代理对话完成（或其中任意一个完成），供 `conversation_read` 批量汇聚结果使用。
     * 等待期间标记 [activeWaitingSubAgentReaders]，避免后台子代理完成瞬间抢先发通知消息。
     */
    suspend fun waitForSubConversations(
        targetIds: Collection<Uuid>,
        waitMs: Long,
        waitAll: Boolean = true,
    ) {
        if (waitMs <= 0L || targetIds.isEmpty()) return
        targetIds.forEach { id ->
            activeWaitingSubAgentReaders.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
        }
        try {
            withTimeoutOrNull(waitMs.coerceIn(100L, 600_000L)) {
                while (true) {
                    val runningStates = targetIds.map { id ->
                        getConversationRuntimeStatus(id).first == "running"
                    }
                    val shouldContinue = if (waitAll) {
                        runningStates.any { it }
                    } else {
                        runningStates.all { it }
                    }
                    if (!shouldContinue) break
                    delay(100)
                }
            }
        } finally {
            targetIds.forEach { id ->
                val counter = activeWaitingSubAgentReaders[id]
                if (counter != null && counter.decrementAndGet() <= 0) {
                    activeWaitingSubAgentReaders.remove(id, counter)
                }
            }
        }
    }

    /**
     * 读取指定对话的实时状态、工具调用进度与消息内容；若正在运行且 [waitMs] > 0，可等待其完成或达到超时上限。
     */
    suspend fun readSubConversation(
        targetConversationId: Uuid,
        offset: Int = 0,
        limit: Int = 20,
        waitMs: Long = 0L,
    ): SubConversationReadResult {
        if (waitMs > 0L) {
            waitForSubConversations(listOf(targetConversationId), waitMs, waitAll = true)
        }

        val conversation = getOrLoadConversation(targetConversationId)
            ?: error("Conversation not found: $targetConversationId")

        val settings = settingsStore.awaitLoaded()
        val childAssistant = settings.getAssistantOf(conversation)
        val childModel = settings.getChatModelOf(conversation)

        val (status, procStatus) = getConversationRuntimeStatus(targetConversationId)
        val dialogue = conversation.currentMessages.filter {
            it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT
        }
        val slice = dialogue.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 50))
        val snapshots = slice.mapIndexed { idx, msg ->
            SubConversationMessageSnapshot(
                position = offset.coerceAtLeast(0) + idx,
                role = msg.role.name,
                text = msg.toText().take(12_000),
                toolSteps = msg.getTools().map { tool ->
                    SubConversationStepSummary(
                        toolName = tool.toolName,
                        inputPreview = tool.input.replace("\n", " ").take(120),
                        isExecuted = tool.isExecuted,
                    )
                },
            )
        }

        return SubConversationReadResult(
            conversationId = conversation.id,
            title = conversation.title.ifBlank { "Untitled" },
            status = status,
            processingStatus = procStatus,
            totalMessages = dialogue.size,
            messages = snapshots,
            assistantId = childAssistant.id,
            assistantName = childAssistant.name.ifBlank { context.getString(R.string.assistant_page_default_assistant) },
            modelName = childModel?.displayName,
        )
    }

    /**
     * 在内存中同步为新建子代理预订 `sub_N` 局部编号（无挂起点），
     * 配合 `GenerationLoop` 的 `CoroutineStart.UNDISPATCHED` 确保同一轮并发调用多个 `conversation_send` 时，
     * 100% 严格按照模型生成的工具调用顺序分配 `sub_1`、`sub_2`。
     */
    fun reserveChildConversationIdImmediate(parentId: Uuid, childId: Uuid): String? {
        if (parentId == childId) return null
        synchronized(subAgentNotificationLock) {
            val parentSession = sessionManager.get(parentId) ?: return null
            val parentConv = parentSession.state.value
            val settings = settingsStore.settingsFlow.value
            val boundParent = parentConv.bindConfig(settings)
            val oldConfig = boundParent.config ?: return null

            val existing = oldConfig.subAgentIdMap.entries
                .firstOrNull { (k, v) -> v == childId && k.startsWith("sub_") }
                ?.key
            if (existing != null) return existing

            val maxIdx = oldConfig.subAgentIdMap.keys
                .mapNotNull { k -> k.removePrefix("sub_").toIntOrNull() }
                .maxOrNull() ?: oldConfig.childConversationIds.size
            var candidateIdx = maxIdx + 1
            while ("sub_$candidateIdx" in oldConfig.subAgentIdMap) {
                candidateIdx++
            }
            val logicalId = "sub_$candidateIdx"
            val newConfig = oldConfig.copy(
                childConversationIds = oldConfig.childConversationIds + childId,
                deletedChildConversationIds = oldConfig.deletedChildConversationIds - childId,
                subAgentIdMap = oldConfig.subAgentIdMap + (logicalId to childId),
            )
            parentSession.updateConversation(boundParent.copy(config = newConfig))
            return logicalId
        }
    }

    private suspend fun registerChildConversation(
        parentId: Uuid,
        childId: Uuid,
        preassignedLogicalId: String? = null,
    ): String {
        if (parentId == childId) return childId.toString()
        return subAgentRegistrationMutex.withLock {
            val parentSession = sessionManager.get(parentId)
            val parentConv = parentSession?.state?.value ?: conversationRepo.getConversationById(parentId)
                ?: return@withLock preassignedLogicalId ?: childId.toString()
            val settings = settingsStore.awaitLoaded()
            val boundParent = parentConv.bindConfig(settings)
            val oldConfig = boundParent.config ?: return@withLock preassignedLogicalId ?: childId.toString()

            val existingLogicalId = oldConfig.subAgentIdMap.entries
                .firstOrNull { (k, v) -> v == childId && k.startsWith("sub_") }
                ?.key
                ?: preassignedLogicalId

            val logicalId = existingLogicalId ?: synchronized(subAgentNotificationLock) {
                val maxIdx = oldConfig.subAgentIdMap.keys
                    .mapNotNull { k -> k.removePrefix("sub_").toIntOrNull() }
                    .maxOrNull() ?: oldConfig.childConversationIds.size
                var candidateIdx = maxIdx + 1
                while ("sub_$candidateIdx" in oldConfig.subAgentIdMap) {
                    candidateIdx++
                }
                "sub_$candidateIdx"
            }

            val newConfig = synchronized(subAgentNotificationLock) {
                val latestConv = parentSession?.state?.value?.bindConfig(settings) ?: boundParent
                val latestCfg = latestConv.config ?: oldConfig
                latestCfg.copy(
                    childConversationIds = latestCfg.childConversationIds + childId,
                    deletedChildConversationIds = latestCfg.deletedChildConversationIds - childId,
                    subAgentIdMap = latestCfg.subAgentIdMap + (logicalId to childId),
                )
            }
            val updatedParent = (parentSession?.state?.value ?: boundParent).copy(config = newConfig)
            if (parentSession != null) {
                parentSession.updateConversation(updatedParent)
            }
            if (conversationRepo.existsConversationById(parentId)) {
                conversationRepo.updateConversationConfig(updatedParent)
            }
            logicalId
        }
    }

    /**
     * 标记某个后台子代理的完成结果已被主代理（通过 `conversation_read` 或新一轮 `conversation_send`）消费，
     * 撤回其待发送的后台完成通知，避免重复通知。
     */
    fun markSubAgentResultConsumed(parentId: Uuid, childId: Uuid) {
        synchronized(subAgentNotificationLock) {
            pendingBackgroundSubAgents[parentId]?.remove(childId)
            unconsumedCompletedSubAgents[parentId]?.remove(childId)
        }
    }

    private fun registerBackgroundSubAgentWatch(
        parentConversationId: Uuid,
        targetId: Uuid,
        logicalId: String,
        replyDeferred: CompletableDeferred<String?>,
        allowInspect: Boolean,
    ) {
        synchronized(subAgentNotificationLock) {
            pendingBackgroundSubAgents.getOrPut(parentConversationId) { linkedSetOf() }.add(targetId)
        }
        appScope.launch {
            val completedReply = try {
                replyDeferred.await()
            } catch (e: CancellationException) {
                synchronized(subAgentNotificationLock) {
                    pendingBackgroundSubAgents[parentConversationId]?.remove(targetId)
                }
                return@launch
            } catch (t: Throwable) {
                "⚠️ 子代理执行异常: ${t.message ?: t.javaClass.simpleName}"
            }

            val latestChild = getOrLoadConversation(targetId)
            val settings = settingsStore.awaitLoaded()
            val childAssistant = latestChild?.let { settings.getAssistantOf(it) }
            val childModel = latestChild?.let { settings.getChatModelOf(it) }
            val finalReply = completedReply?.takeIf { it.isNotBlank() }
                ?: latestChild?.currentMessages?.lastOrNull { it.role == MessageRole.ASSISTANT }?.toText()
                ?: "(无文本回复)"
            val currentParent = getOrLoadConversation(parentConversationId)
            val resolvedLogicalId = currentParent?.resolveLogicalSubAgentId(targetId) ?: logicalId

            val report = CompletedSubAgentReport(
                childId = targetId,
                logicalId = resolvedLogicalId,
                title = latestChild?.title?.ifBlank { null } ?: resolvedLogicalId,
                assistantName = childAssistant?.name?.ifBlank { null },
                modelName = childModel?.displayName,
                status = "completed",
                reply = finalReply,
                toolSteps = if (allowInspect && latestChild != null) extractRecentToolSteps(latestChild) else emptyList(),
                allowInspect = allowInspect,
            )

            // 原子地从 pendingBackgroundSubAgents 移入 unconsumedCompletedSubAgents；
            // 若在运行期间已被停止/删除或被主动清除，则不再投递
            val shouldSchedule = synchronized(subAgentNotificationLock) {
                val wasPending = pendingBackgroundSubAgents[parentConversationId]?.remove(targetId) == true
                if (wasPending) {
                    unconsumedCompletedSubAgents
                        .getOrPut(parentConversationId) { LinkedHashMap() }[targetId] = report
                    true
                } else {
                    false
                }
            }

            if (shouldSchedule) {
                scheduleParentSubAgentNotification(parentConversationId)
            }
        }
    }

    private fun drainCompletedSubAgentNotificationText(parentId: Uuid): String? {
        val reports: List<CompletedSubAgentReport> = synchronized(subAgentNotificationLock) {
            val map = unconsumedCompletedSubAgents[parentId] ?: return null
            if (map.isEmpty()) return null
            // 若其中有子代理正在被 waitForSubConversations 同步阻塞等待读取，暂不抢先消费
            val anyBeingWaitedByRead = map.keys.any { cid ->
                (activeWaitingSubAgentReaders[cid]?.get() ?: 0) > 0
            }
            if (anyBeingWaitedByRead) return null
            val list = map.values.toList()
            map.clear()
            list
        }
        if (reports.isEmpty()) return null

        return buildString {
            appendLine(SUB_AGENT_NOTIFICATION_HEADER)
            appendLine("The following background sub-agent task(s) spawned by this conversation have finished execution:")
            reports.forEach { r ->
                appendLine()
                append("### Sub-Agent `").append(r.logicalId).append("`: ").append(r.title)
                if (!r.assistantName.isNullOrBlank()) {
                    append(" (Assistant: ").append(r.assistantName)
                    if (r.allowInspect && !r.modelName.isNullOrBlank()) {
                        append(", Model: ").append(r.modelName)
                    }
                    append(")")
                }
                appendLine()
                appendLine("- Status: ${r.status}")
                if (r.allowInspect && r.toolSteps.isNotEmpty()) {
                    val toolSummary = r.toolSteps.joinToString(", ") { it.toolName }
                    appendLine("- Tool Steps (${r.toolSteps.size}): $toolSummary")
                }
                appendLine("- Final Reply:")
                appendLine(r.reply.trim())
            }
        }.trim()
    }

    private fun scheduleParentSubAgentNotification(parentId: Uuid) {
        synchronized(subAgentNotificationLock) {
            parentSubAgentNotifyJobs[parentId]?.cancel()
            parentSubAgentNotifyJobs[parentId] = appScope.launch {
                // 留出 350ms 窗口，供正在退出 waitForSubConversations 的 conversation_read 直接消费，并合并同时完成的并发子代理
                delay(350)

                while (true) {
                    val hasUnconsumed = synchronized(subAgentNotificationLock) {
                        !unconsumedCompletedSubAgents[parentId].isNullOrEmpty()
                    }
                    if (!hasUnconsumed) return@launch

                    val parentSession = sessionManager.get(parentId)
                    val isParentGenerating = parentSession?.isGenerating == true ||
                        parentSession?.submittingMessage != null

                    val remainingPendingIds = synchronized(subAgentNotificationLock) {
                        pendingBackgroundSubAgents[parentId]?.toList().orEmpty()
                    }
                    val anyOtherSubAgentStillRunning = remainingPendingIds.any { childId ->
                        getConversationRuntimeStatus(childId).first == "running"
                    }
                    if (!anyOtherSubAgentStillRunning && remainingPendingIds.isNotEmpty()) {
                        synchronized(subAgentNotificationLock) {
                            pendingBackgroundSubAgents[parentId]?.removeAll(remainingPendingIds.toSet())
                        }
                    }

                    val anyActiveReaderWaiting = synchronized(subAgentNotificationLock) {
                        unconsumedCompletedSubAgents[parentId]?.keys?.any { cid ->
                            (activeWaitingSubAgentReaders[cid]?.get() ?: 0) > 0
                        } == true
                    }

                    if (!isParentGenerating && !anyOtherSubAgentStillRunning && !anyActiveReaderWaiting) {
                        break
                    }
                    delay(250)
                }

                val parentConv = getOrLoadConversation(parentId) ?: return@launch
                val hasPendingApproval = parentConv.currentMessages.any { msg ->
                    msg.parts.any { it is UIMessagePart.Tool && it.isPending }
                }
                if (hasPendingApproval) return@launch

                val notificationText = drainCompletedSubAgentNotificationText(parentId) ?: return@launch
                sendMessage(
                    conversationId = parentId,
                    content = listOf(UIMessagePart.Text(notificationText)),
                    answer = true,
                )
            }
        }
    }

    /**
     * 彻底删除指定的子代理对话（供主代理 `conversation_delete` 工具及人类用户 UI 手动删除共用）：
     * 1. 若该子代理正在生成，先立即停止；
     * 2. 从主对话的 `childConversationIds` 与 `subAgentIdMap` 移除并记入 `deletedChildConversationIds`，使其从顶栏胶囊和侧边栏树立即消失；
     * 3. 从数据库删除该子对话。
     */
    suspend fun deleteSubConversation(
        parentConversationId: Uuid?,
        targetConversationId: Uuid,
    ) {
        require(targetConversationId != parentConversationId) {
            "Cannot delete the current parent conversation itself"
        }
        stopGeneration(targetConversationId)

        val targetConv = getOrLoadConversation(targetConversationId)
        val effectiveParentId = parentConversationId ?: targetConv?.config?.parentConversationId
        if (effectiveParentId != null) {
            markSubAgentResultConsumed(effectiveParentId, targetConversationId)
        }

        if (effectiveParentId != null && effectiveParentId != targetConversationId) {
            subAgentRegistrationMutex.withLock {
                val parentSession = sessionManager.get(effectiveParentId)
                val parentConv = parentSession?.state?.value ?: conversationRepo.getConversationById(effectiveParentId)
                if (parentConv != null) {
                    val settings = settingsStore.awaitLoaded()
                    val boundParent = parentConv.bindConfig(settings)
                    val oldConfig = boundParent.config
                    if (oldConfig != null) {
                        val cleanedIdMap = oldConfig.subAgentIdMap.filterValues { it != targetConversationId }
                        val newConfig = oldConfig.copy(
                            childConversationIds = oldConfig.childConversationIds - targetConversationId,
                            deletedChildConversationIds = oldConfig.deletedChildConversationIds + targetConversationId,
                            subAgentIdMap = cleanedIdMap,
                        )
                        val updatedParent = boundParent.copy(config = newConfig)
                        parentSession?.updateConversation(updatedParent)
                        if (conversationRepo.existsConversationById(effectiveParentId)) {
                            conversationRepo.updateConversationConfig(updatedParent)
                        }
                    }
                }
            }
        }

        if (targetConv != null) {
            conversationRepo.deleteConversation(targetConv)
        }
        sessionManager.get(targetConversationId)?.updateConversation(
            Conversation.ofId(targetConversationId, newConversation = true)
        )
    }

    private fun extractRecentToolSteps(conversation: Conversation): List<SubConversationStepSummary> {
        val lastAssistant = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return emptyList()
        return lastAssistant.getTools().map { tool ->
            SubConversationStepSummary(
                toolName = tool.toolName,
                inputPreview = tool.input.replace("\n", " ").take(120),
                isExecuted = tool.isExecuted,
            )
        }
    }
}

const val SUB_AGENT_NOTIFICATION_HEADER = "[System Notification: Background Sub-Agent Completed]"

