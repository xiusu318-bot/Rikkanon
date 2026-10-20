package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.allChildConversationIds
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.resolveLogicalSubAgentId
import me.rerere.rikkahub.data.model.resolveRealSubAgentId
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import me.rerere.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid

/**
 * Tools that let the main assistant query past conversations AND spawn/communicate with bound sub-agent
 * assistants.
 * - Only the bound sub-agent names and `assistant_id`s are included in the tool prompt so the model can
 *   distinguish and call them cleanly without exposing or depending on model names in the prompt.
 * - Full sub-agent assistant status (including current model, provider, tools, etc.) and sub-agent conversation
 *   runtime status can be inspected on demand via `conversation_read` or `recent_chats`.
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    currentConversationId: Uuid? = null,
    chatService: ChatService? = null,
    isSubConversation: Boolean = false,
    enableSubAgent: Boolean = false,
    boundSubAgentIds: Set<Uuid> = emptySet(),
    settings: Settings? = null,
    assistant: me.rerere.rikkahub.data.model.Assistant? = null,
    workspaceRepository: WorkspaceRepository? = null,
): List<Tool> = buildList {
    val boundSubAssistants = settings?.assistants?.filter { it.id in boundSubAgentIds }.orEmpty()
    val tagMap = settings?.assistantTags?.associateBy { it.id }.orEmpty()
    val enableRecentChats = assistant?.enableRecentChatsReference == true

    val allowInspect = assistant?.subAgentAllowInspect ?: true
    val allowMultiTurnAndAsync = assistant?.subAgentAllowMultiTurnAndAsync ?: true
    val allowInterruptAndStop = assistant?.subAgentAllowInterruptAndStop ?: true
    val allowDelete = assistant?.subAgentAllowDelete ?: false

    val approveSpawn = assistant?.subAgentApproveSpawn ?: false
    val approveFollowUp = assistant?.subAgentApproveFollowUp ?: false
    val approveInspect = assistant?.subAgentApproveInspect ?: false
    val approveInterruptAndStop = assistant?.subAgentApproveInterruptAndStop ?: false
    val approveDelete = assistant?.subAgentApproveDelete ?: true

    fun me.rerere.rikkahub.data.model.Assistant.resolveTagDescriptions(): List<String> =
        tags.mapNotNull { id -> tagMap[id]?.name?.trim()?.takeIf { it.isNotEmpty() } }

    suspend fun resolveTargetConversation(targetId: Uuid): Conversation? =
        chatService?.getOrLoadConversation(targetId)
            ?: conversationRepo.getConversationById(targetId)

    suspend fun getParentConversation(): Conversation? =
        currentConversationId?.let { cid -> resolveTargetConversation(cid) }

    suspend fun resolveSubAgentRealId(rawId: String): Uuid? =
        getParentConversation()?.resolveRealSubAgentId(rawId)

    suspend fun toLogicalSubAgentId(realId: Uuid): String =
        getParentConversation()?.resolveLogicalSubAgentId(realId) ?: realId.toString()

    suspend fun isOwnedSubAgentConversation(targetId: Uuid): Boolean {
        val parentId = currentConversationId ?: return false
        if (targetId == parentId) return false
        val targetConv = resolveTargetConversation(targetId) ?: return false
        if (targetConv.config?.parentConversationId == parentId) return true
        val parentConv = getParentConversation()
        return parentConv != null &&
            targetId in parentConv.allChildConversationIds &&
            targetConv.config?.parentConversationId != null
    }

    suspend fun requireOwnedSubAgentConversation(targetId: Uuid) {
        require(targetId != currentConversationId) { "Cannot target the current parent conversation itself" }
        require(isOwnedSubAgentConversation(targetId)) {
            "Permission denied: conversation $targetId is not a sub-agent of the current conversation"
        }
    }

    suspend fun buildBoundSubAgentsStatusJson() = buildJsonArray {
        val parentConv = getParentConversation()
        val shareWorkspace = assistant?.subAgentShareWorkspace ?: true
        val parentWorkspaceId = parentConv?.config?.workspaceId ?: assistant?.workspaceId

        boundSubAssistants.forEach { subAssistant ->
            val model = settings?.findModelById(subAssistant.chatModelId ?: settings.chatModelId)
            val provider = model?.let { m -> settings?.providers?.let { m.findProvider(it, checkOverwrite = false) } }
            val tagDescs = subAssistant.resolveTagDescriptions()
            val effectiveWorkspaceId = if (shareWorkspace) {
                parentWorkspaceId ?: subAssistant.workspaceId
            } else {
                subAssistant.workspaceId
            }
            val workspaceEntity = effectiveWorkspaceId?.toString()?.let { wid ->
                workspaceRepository?.getById(wid)
            }
            val workspaceReady = workspaceEntity != null &&
                workspaceEntity.shellStatus == WorkspaceShellStatus.READY.name
            val enabledOverrides = workspaceEntity?.toolEnabledOverrides().orEmpty()

            val availableTools = buildList {
                if (workspaceReady) {
                    AllWorkspaceToolNames.forEach { toolName ->
                        if (resolveWorkspaceToolEnabled(toolName, enabledOverrides) &&
                            toolName !in subAssistant.disabledWorkspaceTools
                        ) {
                            add(toolName)
                        }
                    }
                }
                if (subAssistant.enableWebSearch) {
                    add("search_web")
                    add("scrape_web")
                }
                if (subAssistant.enableMemory) {
                    add("memory_query")
                    add("memory_create")
                    add("memory_str_replace")
                    add("memory_append")
                    add("memory_delete")
                }
                subAssistant.localTools.forEach { opt ->
                    when (opt) {
                        LocalToolOption.JavascriptEngine -> add("eval_javascript")
                        LocalToolOption.TimeInfo -> add("get_time_info")
                        LocalToolOption.Clipboard -> add("clipboard_tool")
                        LocalToolOption.Tts -> add("text_to_speech")
                        LocalToolOption.ScreenTime -> add("get_screen_time")
                        LocalToolOption.Calendar -> {
                            add("query_calendar_events")
                            add("create_calendar_event")
                        }
                        LocalToolOption.ChartDisplay -> add("display_chart")
                        // 子对话后台运行时不弹出交互式提问卡片
                        else -> Unit
                    }
                }
                if (subAssistant.enabledSkills.isNotEmpty()) {
                    add("use_skill")
                }
                val activeMcpServers = settings?.mcpServers
                    ?.filter { it.commonOptions.enable && it.id in subAssistant.mcpServers }
                    .orEmpty()
                activeMcpServers.forEach { server ->
                    server.commonOptions.tools
                        .filter { it.enable }
                        .forEach { t -> add("mcp__${server.commonOptions.name}__${t.name}") }
                }
            }
            val activeMcpServerNames = settings?.mcpServers
                ?.filter { it.commonOptions.enable && it.id in subAssistant.mcpServers }
                ?.map { it.commonOptions.name }
                .orEmpty()

            add(buildJsonObject {
                put("assistant_id", subAssistant.id.toString())
                put("name", subAssistant.name.ifBlank { "Default Assistant" })
                if (tagDescs.isNotEmpty()) {
                    put("tags", buildJsonArray {
                        tagDescs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                    put("description", tagDescs.joinToString("; "))
                }
                put("model", model?.displayName ?: "unselected")
                if (provider != null) {
                    put("provider", provider.name)
                }
                put("reasoning_level", subAssistant.reasoningLevel.name)
                put("enable_web_search", subAssistant.enableWebSearch)
                put("default_max_steps", subAssistant.subAgentMaxSteps.coerceAtLeast(0))
                put("share_workspace", shareWorkspace && effectiveWorkspaceId != null)
                put("tools", buildJsonArray {
                    availableTools.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
                if (subAssistant.enabledSkills.isNotEmpty()) {
                    put("skills", buildJsonArray {
                        subAssistant.enabledSkills.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                }
                if (activeMcpServerNames.isNotEmpty()) {
                    put("mcp_servers", buildJsonArray {
                        activeMcpServerNames.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                }
            })
        }
    }

    // 仅当用户在「记忆 -> 参考最近对话」中显式开启了 enableRecentChatsReference 时，才暴露 recent_chats 与 conversation_search
    if (enableRecentChats) {
        add(
            Tool(
                name = "recent_chats",
                description = """
                    List recent user conversations of this assistant to recall past context.
                """.trimIndent().replace("\n", " "),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("limit", buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum number of recent conversations to return (default: 15, max: 30)"
                                )
                            })
                        }
                    )
                },
                execute = {
                    val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 30)
                    val childIds = currentConversationId?.let { cid ->
                        chatService?.getConversationFlow(cid)?.value?.allChildConversationIds
                    }.orEmpty()
                    val recent = conversationRepo.getRecentConversations(
                        assistantId = assistantId,
                        limit = limit,
                    )
                        .filter { conv ->
                            conv.id != currentConversationId &&
                                conv.id !in childIds &&
                                conversationRepo.getConversationById(conv.id)?.config?.parentConversationId == null
                        }
                        .take(limit)

                    val conversationsArray = buildJsonArray {
                        recent.forEach { conversation ->
                            add(buildJsonObject {
                                put("conversation_id", conversation.id.toString())
                                put("title", conversation.title.ifBlank { "Untitled" })
                                put("last_chat", conversation.updateAt.toLocalDate())
                            })
                        }
                    }
                    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(conversationsArray)))
                }
            )
        )

        add(conversationSearchTool { query -> conversationRepo.searchMessages(query, MessageSearchSort.RELEVANCE) })
    }

    // 仅在允许窥屏读取子代理状态 (enableSubAgent && allowInspect) 或开启了历史对话引用 (enableRecentChats) 时暴露 conversation_read
    if ((enableSubAgent && allowInspect && !isSubConversation) || enableRecentChats) {
        add(
            Tool(
                name = "conversation_read",
                description = if (enableRecentChats) {
                    """
                        Inspect sub-agent status or read a conversation/sub-agent by `conversation_id`.
                        - Omit `conversation_id` to inspect all bound sub-agent assistants' current status (including their active models) and all spawned sub-agent conversations of the current chat.
                        - Pass `conversation_id` to read the real-time status, bound assistant/model info, tool execution progress, and messages of that specific conversation or sub-agent (optionally with `wait_sec`).
                    """.trimIndent().replace("\n", " ")
                } else {
                    """
                        Inspect bound sub-agent assistants and sub-agent conversations spawned by the current chat.
                        - Omit `conversation_id` (and `conversation_ids`) to inspect all bound sub-agent assistants' current status and all spawned sub-agent conversations of the current chat (pass `wait_sec` > 0 to wait for all running sub-agents to finish).
                        - Pass `conversation_id` or `conversation_ids` of sub-agents belonging to the current chat to read their real-time status, bound assistant/model info, tool execution progress, and messages (optionally with `wait_sec` to wait for completion).
                    """.trimIndent().replace("\n", " ")
                },
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("conversation_id", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    if (enableRecentChats) {
                                        "ID of the target conversation or sub-agent. Omit to inspect bound sub-agent assistants status and active sub-agent conversations."
                                    } else {
                                        "ID of the target sub-agent conversation spawned by this chat. Omit to inspect bound sub-agent assistants status and active sub-agent conversations."
                                    }
                                )
                            })
                            put("conversation_ids", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                                put(
                                    "description",
                                    "Optional list of multiple sub-agent conversation IDs to inspect (and wait for when wait_sec > 0) in a single batch call."
                                )
                            })
                            put("wait_sec", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional seconds to wait if the target sub-agent conversation(s) are currently running (default: 0, max: 600)")
                            })
                            put("offset", buildJsonObject {
                                put("type", "integer")
                                put("description", "Position of the first message to return (default: 0)")
                            })
                            put("limit", buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum number of messages to return (default: $READ_DEFAULT_LIMIT, max: $READ_MAX_LIMIT)"
                                )
                            })
                        },
                    )
                },
                needsApproval = { enableSubAgent && approveInspect },
                execute = {
                val args = it.jsonObject
                val waitSec = (args["wait_sec"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 600)
                val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
                val rawId = args["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
                val rawIds = args["conversation_ids"]
                    ?.let { elem -> runCatching { elem.jsonArray }.getOrNull() }
                    ?.mapNotNull { elem -> elem.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .orEmpty()

                if (rawIds.isNotEmpty()) {
                    val targetIds = rawIds.map { idStr ->
                        resolveSubAgentRealId(idStr)
                            ?: error("Sub-agent '$idStr' not found in the current conversation")
                    }.distinct()
                    targetIds.forEach { tid -> requireOwnedSubAgentConversation(tid) }
                    if (chatService != null && waitSec > 0) {
                        chatService.waitForSubConversations(targetIds, waitSec * 1_000L, waitAll = true)
                    }
                    val parentConv = getParentConversation()
                    val batchPayload = buildJsonObject {
                        put("conversations", buildJsonArray {
                            for (tid in targetIds) {
                                if (chatService != null) {
                                    val result = chatService.readSubConversation(
                                        targetConversationId = tid,
                                        offset = offset,
                                        limit = limit,
                                        waitMs = 0L,
                                    )
                                    // 仅当读取结束时子代理确实已完成 (status != "running")，才标记为已消费，避免取消运行中子代理的完成通知
                                    if (result.status != "running" && currentConversationId != null) {
                                        chatService.markSubAgentResultConsumed(currentConversationId, tid)
                                    }
                                    val logicalId = parentConv?.resolveLogicalSubAgentId(result.conversationId)
                                        ?: result.conversationId.toString()
                                    add(buildJsonObject {
                                        put("conversation_id", logicalId)
                                        put("title", result.title)
                                        put("status", result.status)
                                        result.assistantId?.let { id -> put("assistant_id", id.toString()) }
                                        result.assistantName?.let { name -> put("assistant_name", name) }
                                        result.modelName?.let { model -> put("model", model) }
                                        if (!result.processingStatus.isNullOrBlank()) {
                                            put("processing_status", result.processingStatus)
                                        }
                                        put("total_messages", result.totalMessages)
                                        put("messages", buildJsonArray {
                                            result.messages.forEach { msg ->
                                                add(buildJsonObject {
                                                    put("position", msg.position)
                                                    put("role", msg.role)
                                                    put("text", msg.text)
                                                    if (msg.toolSteps.isNotEmpty()) {
                                                        put("tool_steps", buildJsonArray {
                                                            msg.toolSteps.forEach { step ->
                                                                add(buildJsonObject {
                                                                    put("tool", step.toolName)
                                                                    put("input", step.inputPreview)
                                                                    put("executed", step.isExecuted)
                                                                })
                                                            }
                                                        })
                                                    }
                                                })
                                            }
                                        })
                                    })
                                }
                            }
                        })
                    }
                    return@Tool listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(batchPayload)))
                }

                if (rawId.isNullOrBlank()) {
                    val parentConv = getParentConversation()
                    val childIds = parentConv?.allChildConversationIds.orEmpty()
                    if (chatService != null && waitSec > 0 && childIds.isNotEmpty()) {
                        chatService.waitForSubConversations(childIds, waitSec * 1_000L, waitAll = true)
                    }
                    val refreshedParent = getParentConversation() ?: parentConv
                    val childConversations = childIds.mapNotNull { childId ->
                        resolveTargetConversation(childId)?.takeIf { conv ->
                            conv.config?.parentConversationId == currentConversationId ||
                                conv.config?.parentConversationId != null
                        }
                    }
                    val overview = buildJsonObject {
                        put("bound_sub_agents", buildBoundSubAgentsStatusJson())
                        put("active_sub_conversations", buildJsonArray {
                            childConversations.forEach { conv ->
                                val (status, procStatus) = chatService?.getConversationRuntimeStatus(conv.id)
                                    ?: ("idle" to null)
                                val convAssistant = settings?.getAssistantOf(conv)
                                val convModel = settings?.getChatModelOf(conv)
                                val dialogue = conv.currentMessages.filter {
                                    it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT
                                }
                                val latestReply = dialogue.lastOrNull { it.role == MessageRole.ASSISTANT }
                                    ?.toText()
                                    ?.take(4_000)
                                // 仅当子代理已完成且返回了最终回复时，才标记完成通知已消费
                                if (status != "running" && !latestReply.isNullOrBlank() && currentConversationId != null) {
                                    chatService?.markSubAgentResultConsumed(currentConversationId, conv.id)
                                }
                                val logicalId = refreshedParent?.resolveLogicalSubAgentId(conv.id)
                                    ?: conv.id.toString()
                                add(buildJsonObject {
                                    put("conversation_id", logicalId)
                                    put("title", conv.title.ifBlank { "Untitled" })
                                    put("status", status)
                                    put("total_messages", dialogue.size)
                                    if (!procStatus.isNullOrBlank()) put("processing_status", procStatus)
                                    if (convAssistant != null) {
                                        put("assistant_id", convAssistant.id.toString())
                                        put("assistant_name", convAssistant.name.ifBlank { "Default Assistant" })
                                    }
                                    if (convModel != null) {
                                        put("model", convModel.displayName)
                                    }
                                    if (!latestReply.isNullOrBlank()) {
                                        put("latest_reply", latestReply)
                                    }
                                })
                            }
                        })
                    }
                    return@Tool listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(overview)))
                }

                val targetId = resolveSubAgentRealId(rawId)
                    ?: (if (enableRecentChats) runCatching { Uuid.parse(rawId) }.getOrNull() else null)
                    ?: error("Sub-agent or conversation '$rawId' not found in current conversation")
                val isSubAgentTarget = isOwnedSubAgentConversation(targetId)
                if (!enableRecentChats) {
                    // 未开启「参考最近对话」时，严格限制只能读取当前主对话派生的子代理
                    requireOwnedSubAgentConversation(targetId)
                } else {
                    // 即使开启了「参考最近对话」，也不允许跨主对话读取其他主对话派生的私有子代理
                    val targetConv = resolveTargetConversation(targetId)
                        ?: error("Conversation not found: $rawId")
                    if (targetConv.config?.parentConversationId != null && !isSubAgentTarget) {
                        error("Permission denied: conversation $rawId belongs to another parent conversation")
                    }
                }

                if (chatService != null && isSubAgentTarget) {
                    val result = chatService.readSubConversation(
                        targetConversationId = targetId,
                        offset = offset,
                        limit = limit,
                        waitMs = waitSec * 1_000L,
                    )
                    // 仅当读取结束时子代理已完成 (status != "running")，才标记为已消费
                    if (result.status != "running" && currentConversationId != null) {
                        chatService.markSubAgentResultConsumed(currentConversationId, targetId)
                    }
                    val logicalId = toLogicalSubAgentId(result.conversationId)
                    val payload = buildJsonObject {
                        put("conversation_id", logicalId)
                        put("title", result.title)
                        put("status", result.status)
                        result.assistantId?.let { id -> put("assistant_id", id.toString()) }
                        result.assistantName?.let { name -> put("assistant_name", name) }
                        result.modelName?.let { model -> put("model", model) }
                        if (!result.processingStatus.isNullOrBlank()) {
                            put("processing_status", result.processingStatus)
                        }
                        put("total_messages", result.totalMessages)
                        put("messages", buildJsonArray {
                            result.messages.forEach { msg ->
                                add(buildJsonObject {
                                    put("position", msg.position)
                                    put("role", msg.role)
                                    put("text", msg.text)
                                    if (msg.toolSteps.isNotEmpty()) {
                                        put("tool_steps", buildJsonArray {
                                            msg.toolSteps.forEach { step ->
                                                add(buildJsonObject {
                                                    put("tool", step.toolName)
                                                    put("input", step.inputPreview)
                                                    put("executed", step.isExecuted)
                                                })
                                            }
                                        })
                                    }
                                })
                            }
                        })
                    }
                    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
                } else {
                    val conversation = conversationRepo.getConversationById(targetId)
                        ?: error("conversation not found: $targetId")
                    val messages = conversation.dialogueMessages()
                    val payload = buildJsonObject {
                        put("conversation_id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("total_messages", messages.size)
                        put("messages", buildJsonArray {
                            messages.drop(offset).take(limit).forEachIndexed { index, message ->
                                add(buildJsonObject {
                                    put("position", offset + index)
                                    put("role", message.role.name)
                                    put("date", message.createdAt.date.toString())
                                    put("text", message.toText().take(READ_MESSAGE_LENGTH))
                                })
                            }
                        })
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
            }
        )
    )
    }

    // 主对话专属：仅在已启用且勾选绑定了至少一个子代理助手时暴露子代理控制工具
    // 根据主代理对子代理的 4 项权限开关动态裁剪参数、输出与工具列表
    if (enableSubAgent && boundSubAssistants.isNotEmpty() && !isSubConversation && chatService != null && currentConversationId != null) {
        val boundListPrompt = boundSubAssistants.joinToString("; ") { sub ->
            val name = sub.name.ifBlank { "Sub-Agent" }
            val tagDescs = sub.resolveTagDescriptions()
            if (tagDescs.isNotEmpty()) {
                val joinedTags = tagDescs.joinToString(" | ") { it.replace("\n", " ").trim() }
                "\"$name\" (assistant_id=\"${sub.id}\", tags/description=\"$joinedTags\")"
            } else {
                "\"$name\" (assistant_id=\"${sub.id}\")"
            }
        }

        add(
            Tool(
                name = "conversation_send",
                description = buildString {
                    append("Spawn a sub-agent conversation")
                    if (allowMultiTurnAndAsync) {
                        append(" or send a follow-up instruction to an existing sub-agent conversation")
                    }
                    append(". Bound sub-agents available to call: [$boundListPrompt]. ")
                    append("- Each bound sub-agent includes its name, `assistant_id`, and optional `tags/description` describing its role or capabilities. ")
                    append("- To create a new sub-agent conversation: pass `assistant_id` (chosen from the bound sub-agents list above), `title`, and `message`. ")
                    if (allowMultiTurnAndAsync) {
                        append("- To message an existing sub-agent conversation: pass its `conversation_id` and `message`. ")
                        append("- Set `wait=true` (default) to wait for the sub-agent's reply, or `wait=false` to run it asynchronously in the background and immediately get its `conversation_id`. ")
                    }
                    if (allowInterruptAndStop) {
                        append("- Set `interrupt=true` to cancel the sub-agent's currently running turn and immediately redirect it with your new `message`.")
                    }
                }.trim(),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("assistant_id", buildJsonObject {
                                put("type", "string")
                                put("description", "Bound sub-agent assistant ID (from [$boundListPrompt]). Pass this when creating a new sub-agent conversation.")
                            })
                            if (allowMultiTurnAndAsync) {
                                put("conversation_id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Existing sub-agent local ID to message (e.g. 'sub_1', 'sub_2'). Omit when creating a new sub-agent conversation.")
                                })
                            }
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "Name/title for the sub-agent conversation (e.g., '[Sub] Fix login bug').")
                            })
                            put("message", buildJsonObject {
                                put("type", "string")
                                put("description", "The user instruction, task description, or follow-up reply to send into the sub-agent conversation.")
                            })
                            if (allowMultiTurnAndAsync) {
                                put("wait", buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "Whether to wait for the sub-agent to finish its turn and return its reply directly. Defaults to true. Set false for async parallel execution.")
                                })
                                put("timeout_sec", buildJsonObject {
                                    put("type", "integer")
                                    put("description", "Max seconds to wait when wait=true (default: 60, max: 600).")
                                })
                            }
                            if (allowInterruptAndStop) {
                                put("interrupt", buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "When true and the target sub-agent is currently running, immediately stop its current turn and start a new turn with `message`. Defaults to false.")
                                })
                            }
                            put("max_steps", buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Optional max tool-calling steps allowed for the sub-agent in this turn (0 = unlimited, >0 = max tool steps before forcing final response; omit to use the sub-agent's configured default)."
                                )
                            })
                        },
                        required = listOf("message")
                    )
                },
                needsApproval = { args ->
                    val obj = runCatching { args.jsonObject }.getOrNull()
                    val hasTargetId = allowMultiTurnAndAsync &&
                        !obj?.get("conversation_id")?.jsonPrimitive?.contentOrNull?.trim().isNullOrEmpty()
                    val isInterrupt = allowInterruptAndStop &&
                        obj?.get("interrupt")?.jsonPrimitive?.booleanOrNull == true
                    when {
                        isInterrupt && approveInterruptAndStop -> true
                        hasTargetId -> approveFollowUp
                        else -> approveSpawn
                    }
                },
                execute = {
                    val args = it.jsonObject
                    val rawTargetId = if (allowMultiTurnAndAsync) {
                        args["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
                    } else null
                    // 当创建新子代理 (rawTargetId 为空) 时，在任何挂起操作前同步预订 sub_N 编号，
                    // 配合 GenerationLoop 的 CoroutineStart.UNDISPATCHED 确保同一批并发调用严格按顺序分配 sub_1, sub_2
                    val preallocatedNewTargetId = if (rawTargetId.isNullOrBlank()) {
                        Uuid.random().also { newChildId ->
                            chatService.reserveChildConversationIdImmediate(currentConversationId, newChildId)
                        }
                    } else null

                    val targetId = if (!rawTargetId.isNullOrBlank()) {
                        resolveSubAgentRealId(rawTargetId)
                            ?: error("Sub-agent '$rawTargetId' not found in the current conversation")
                    } else null
                    if (targetId != null) {
                        requireOwnedSubAgentConversation(targetId)
                    }
                    val rawAssistantId = args["assistant_id"]?.jsonPrimitive?.contentOrNull?.trim()
                    val targetAssistantId = if (!rawAssistantId.isNullOrBlank()) {
                        runCatching { Uuid.parse(rawAssistantId) }.getOrNull()
                            ?: error("Invalid assistant_id UUID: $rawAssistantId")
                    } else null
                    val title = args["title"]?.jsonPrimitive?.contentOrNull
                    val message = args["message"]?.jsonPrimitive?.contentOrNull
                        ?: error("message is required")
                    val wait = if (allowMultiTurnAndAsync) {
                        args["wait"]?.jsonPrimitive?.booleanOrNull ?: true
                    } else true
                    val timeoutSec = (args["timeout_sec"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
                    val interrupt = if (allowInterruptAndStop) {
                        args["interrupt"]?.jsonPrimitive?.booleanOrNull ?: false
                    } else false
                    val maxStepsOverride = args["max_steps"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0)

                    val result = chatService.sendToSubConversation(
                        parentConversationId = currentConversationId,
                        targetConversationId = targetId,
                        preallocatedNewTargetId = preallocatedNewTargetId,
                        targetAssistantId = targetAssistantId,
                        title = title,
                        message = message,
                        wait = wait,
                        timeoutMs = timeoutSec * 1_000L,
                        interrupt = interrupt,
                        maxStepsOverride = maxStepsOverride,
                    )

                    val payload = buildJsonObject {
                        put("conversation_id", result.logicalId)
                        put("title", result.title)
                        put("status", result.status)
                        put("timed_out", result.timedOut)
                        result.assistantId?.let { id -> put("assistant_id", id.toString()) }
                        result.assistantName?.let { name -> put("assistant_name", name) }
                        if (allowInspect) {
                            result.modelName?.let { model -> put("model", model) }
                            if (!result.processingStatus.isNullOrBlank()) {
                                put("processing_status", result.processingStatus)
                            }
                        }
                        if (result.reply != null) {
                            put("reply", result.reply)
                        }
                        // 仅当开启了「窥屏与查看工具轨迹」权限时才向主代理返回 tool_steps；最低权限下只返回最终 reply
                        if (allowInspect && result.toolSteps.isNotEmpty()) {
                            put("tool_steps", buildJsonArray {
                                result.toolSteps.forEach { step ->
                                    add(buildJsonObject {
                                        put("tool", step.toolName)
                                        put("input", step.inputPreview)
                                        put("executed", step.isExecuted)
                                    })
                                }
                            })
                        }
                        if (result.status == "running") {
                            put(
                                "hint",
                                "Sub-agent '${result.logicalId}' is running in the background. You will be automatically notified when it finishes, or you can call `conversation_read` with conversation_id='${result.logicalId}' (or wait_sec > 0) at any time."
                            )
                        }
                    }
                    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
                }
            )
        )

        if (allowInterruptAndStop) {
            add(
                Tool(
                    name = "conversation_stop",
                    description = """
                        Immediately stop/cancel a running sub-agent conversation by `conversation_id` (e.g. 'sub_1', 'sub_2').
                    """.trimIndent().replace("\n", " "),
                    parameters = {
                        InputSchema.Obj(
                            properties = buildJsonObject {
                                put("conversation_id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "ID of the running sub-agent conversation to stop (e.g. 'sub_1', 'sub_2')")
                                })
                            },
                            required = listOf("conversation_id")
                        )
                    },
                    needsApproval = { approveInterruptAndStop },
                    execute = {
                        val rawId = it.jsonObject["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?: error("conversation_id is required")
                        val targetId = resolveSubAgentRealId(rawId)
                            ?: error("Sub-agent '$rawId' not found in the current conversation")
                        requireOwnedSubAgentConversation(targetId)
                        val logicalId = toLogicalSubAgentId(targetId)
                        chatService.markSubAgentResultConsumed(currentConversationId, targetId)
                        chatService.stopGeneration(targetId)
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("conversation_id", logicalId)
                                    put("status", "stopped")
                                }.toString()
                            )
                        )
                    }
                )
            )
        }

        if (allowDelete) {
            add(
                Tool(
                    name = "conversation_delete",
                    description = """
                        Permanently delete and remove a spawned sub-agent conversation by `conversation_id` (e.g. 'sub_1', 'sub_2').
                        Only sub-agent conversations belonging to the current chat can be deleted.
                    """.trimIndent().replace("\n", " "),
                    parameters = {
                        InputSchema.Obj(
                            properties = buildJsonObject {
                                put("conversation_id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "ID of the sub-agent conversation to delete (e.g. 'sub_1', 'sub_2')")
                                })
                            },
                            required = listOf("conversation_id")
                        )
                    },
                    needsApproval = { approveDelete },
                    execute = {
                        val rawId = it.jsonObject["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?: error("conversation_id is required")
                        val targetId = resolveSubAgentRealId(rawId)
                            ?: error("Sub-agent '$rawId' not found in the current conversation")
                        requireOwnedSubAgentConversation(targetId)
                        val logicalId = toLogicalSubAgentId(targetId)

                        chatService.deleteSubConversation(
                            parentConversationId = currentConversationId,
                            targetConversationId = targetId,
                        )
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("conversation_id", logicalId)
                                    put("status", "deleted")
                                }.toString()
                            )
                        )
                    }
                )
            )
        }
    }
}

private fun conversationSearchTool(search: suspend (query: String) -> List<MessageSearchResult>) = Tool(
    name = "conversation_search",
    description = """
        Full-text search across the user's past conversations to recall specific information they mentioned before.
        Use focused keywords. Run multiple searches with different keywords if needed.
        Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Keywords to search for in past conversation messages")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum number of results to return (default: 15, max: 50)"
                    )
                })
            },
            required = listOf("query")
        )
    },
    execute = {
        val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
            ?: error("query is required")
        val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
        val results = search(query).take(limit)
        val payload = buildJsonArray {
            results.forEach { result ->
                add(buildJsonObject {
                    put("conversation_id", result.conversationId)
                    put("title", result.title.ifBlank { "Untitled" })
                    put("snippet", result.snippet)
                    put("date", result.updateAt.toLocalDate())
                })
            }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    }
)

private const val READ_DEFAULT_LIMIT = 20
private const val READ_MAX_LIMIT = 40
private const val READ_MESSAGE_LENGTH = 12_000

/**
 * 给后台记忆整理用的对话查询工具，只能看到 [assistantIds] 这些助手的对话：
 * 独立记忆库的助手不该读到别的助手的对话内容。
 */
fun createMemoryConversationTools(
    conversationRepo: ConversationRepository,
    assistantIds: Set<Uuid>,
): List<Tool> = listOf(
    conversationSearchTool { query -> conversationRepo.searchMessagesOfAssistants(query, assistantIds) },
    Tool(
        name = "conversation_read",
        description = """
            Read the messages of one conversation in full, by position.
            Use it when a message you were given was cut off, or a search snippet needs its surrounding context.
            Positions are the #numbers shown next to each message.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Id of the conversation")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Position of the first message to return (default: 0)")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of messages to return (default: $READ_DEFAULT_LIMIT, max: $READ_MAX_LIMIT)"
                        )
                    })
                },
                required = listOf("conversation_id")
            )
        },
        execute = {
            val args = it.jsonObject
            val conversation = args["conversation_id"]?.jsonPrimitive?.contentOrNull
                ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
                ?.let { id -> conversationRepo.getConversationById(id) }
                ?.takeIf { conversation -> conversation.assistantId in assistantIds }
                ?: error("conversation not found")
            val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
            val messages = conversation.dialogueMessages()
            val payload = buildJsonObject {
                put("title", conversation.title.ifBlank { "Untitled" })
                put("total", messages.size)
                put("messages", buildJsonArray {
                    messages.drop(offset).take(limit).forEachIndexed { index, message ->
                        add(buildJsonObject {
                            put("position", offset + index)
                            put("role", message.role.name)
                            put("date", message.createdAt.date.toString())
                            put("text", message.toText().take(READ_MESSAGE_LENGTH))
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)

/** 当前分支上用户和助手的消息，位置编号以这个列表为准 */
internal fun Conversation.dialogueMessages(): List<UIMessage> =
    currentMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
