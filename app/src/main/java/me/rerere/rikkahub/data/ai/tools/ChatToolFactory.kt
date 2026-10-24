package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.buildAskKeyTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.service.ChatService
import me.rerere.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
        chatService: ChatService? = null,
        isSubConversation: Boolean = false,
    ): List<Tool> {
        val rawTools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            // 子代理不再挂载本地工具（JavaScript、时间、剪贴板、TTS、日历、屏幕时间、图表、ask_user 等）
            if (!isSubConversation) {
                addAll(localTools.getTools(assistant.localTools))
            }
            if (settings.enableSecretKeyAlias &&
                (me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.AskKey in assistant.localTools || assistant.enableAskKeyTool) &&
                !isSubConversation
            ) {
                add(buildAskKeyTool(settings))
            }

            if (assistant.enableRecentChatsReference ||
                (assistant.isSubAgentEnabled && !isSubConversation)
            ) {
                addAll(
                    createConversationTools(
                        conversationRepo = conversationRepository,
                        assistantId = assistant.id,
                        currentConversationId = conversationId,
                        chatService = chatService,
                        isSubConversation = isSubConversation,
                        enableSubAgent = assistant.isSubAgentEnabled,
                        boundSubAgentIds = assistant.effectiveSubAgentIds,
                        settings = settings,
                        assistant = assistant,
                        workspaceRepository = workspaceRepository,
                    )
                )
            }
            addAll(
                createWorkspaceToolsIfReady(
                    workspaceId = assistant.workspaceId?.toString(),
                    cwd = workspaceCwd,
                    disabledTools = assistant.disabledWorkspaceTools,
                )
            )
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }

        if (!isSubConversation) return rawTools

        // 子代理会话：工具是否需要审批完全由该子代理的「用户审批 (subAgentUserApprovalTools)」与「主代理审批 (subAgentAgentApprovalTools)」决定
        val mcpServerNameToId = settings.mcpServers.associate { it.commonOptions.name to it.id }
        return rawTools.map { tool ->
            val requiresSubApproval = assistant.requiresSubAgentUserApproval(tool.name, mcpServerNameToId) ||
                assistant.requiresSubAgentAgentApproval(tool.name, mcpServerNameToId)
            tool.copy(
                needsApproval = { input ->
                    if (!requiresSubApproval) {
                        false
                    } else if (tool.name == "workspace_terminal") {
                        // workspace_terminal 的只读操作（list_only 或无写指令仅读屏幕）免审批
                        val obj = runCatching { input.jsonObject }.getOrNull()
                        val listOnly = obj?.get("list_only")?.toString()?.toBooleanStrictOrNull() == true
                        val hasInput = !obj?.get("input")?.toString()?.trim('"').isNullOrEmpty() &&
                            obj?.get("input")?.toString() != "null"
                        val isMutatingSession = obj?.get("new_session")?.toString()?.toBooleanStrictOrNull() == true ||
                            obj?.get("kill")?.toString()?.toBooleanStrictOrNull() == true ||
                            obj?.get("kill_all")?.toString()?.toBooleanStrictOrNull() == true
                        !listOnly && (hasInput || isMutatingSession)
                    } else {
                        true
                    }
                }
            )
        }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String?,
        disabledTools: Set<String> = emptySet(),
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
            disabledTools = disabledTools,
        )
    }
}
