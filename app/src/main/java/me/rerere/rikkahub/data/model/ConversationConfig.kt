package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlin.uuid.Uuid

/**
 * 会话元数据配置：
 * 已彻底移除 3.0 的“会话开始后锁定模型/思考强度/搜索/工具快照”机制，所有对话（含主对话与子代理）
 * 均 100% 实时跟随其所属助手（[Conversation.assistantId]）的最新配置与模型。
 * 此处仅保留父子代理树状关联 ID 与主从共享工作区标记。
 */
@Serializable
data class ConversationConfig(
    val chatModelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val enableWebSearch: Boolean = false,
    val builtInSearch: Boolean = false,
    val mcpServers: Set<Uuid> = emptySet(),
    val workspaceId: Uuid? = null,
    val enabledSkills: Set<String> = emptySet(),
    val disabledWorkspaceTools: Set<String> = emptySet(),
    val enableSecretKeyAlias: Boolean = false,
    val enableAskKeyTool: Boolean = false,
    val subAgentAllowInspect: Boolean = true,
    val subAgentAllowMultiTurnAndAsync: Boolean = true,
    val subAgentAllowInterruptAndStop: Boolean = true,
    val subAgentAllowDelete: Boolean = false,
    val subAgentApproveSpawn: Boolean = false,
    val subAgentApproveFollowUp: Boolean = false,
    val subAgentApproveInspect: Boolean = false,
    val subAgentApproveInterruptAndStop: Boolean = false,
    val subAgentApproveDelete: Boolean = true,
    val childConversationIds: Set<Uuid> = emptySet(),
    val deletedChildConversationIds: Set<Uuid> = emptySet(),
    val subAgentIdMap: Map<String, Uuid> = emptyMap(), // 主对话局部子代理代号（如 "sub_1" 或旧对话历史ID） -> 数据库真实子对话 UUID
    val parentConversationId: Uuid? = null,
    val autoApproveTools: Boolean = false,
    val subAgentMaxStepsOverride: Int? = null, // 主代理调用时显式传入的单轮最大工具步数（null 则取子代理默认 subAgentMaxSteps，0 为不设限）
)

/** 是否为派生的子代理对话 */
val Conversation.isSubAgentConversation: Boolean
    get() = config?.parentConversationId != null

/**
 * 会话视角下的助手：
 * 始终 100% 实时使用助手自身的最新设置（不再用会话快照覆盖助手设置）；
 * 若为子代理会话且开启了共享主对话工作区，则自动继承主对话的 [ConversationConfig.workspaceId]。
 */
fun Assistant.withConversation(conversation: Conversation): Assistant {
    val config = conversation.config ?: return this
    return if (config.parentConversationId != null && config.workspaceId != null && workspaceId == null) {
        copy(workspaceId = config.workspaceId)
    } else {
        this
    }
}

/** 聊天页改动助手后留在会话上的部分：换工作区后重置会话级工作目录。 */
fun Conversation.withAssistantUpdate(updated: Assistant, settings: Settings): Conversation {
    val previous = settings.getAssistantOf(this)
    return if (updated.workspaceId != previous.workspaceId) copy(workspaceCwd = null) else this
}

/** 聊天页改动助手后写回助手设置的部分：所有改动一律直接写回助手本身。 */
fun Assistant.withoutConversationFields(conversation: Conversation, stored: Assistant): Assistant = this

/** 会话所属助手自身的设置；助手被删除后退回当前助手。 */
fun Settings.getStoredAssistantOf(conversation: Conversation): Assistant =
    getAssistantById(conversation.assistantId) ?: getCurrentAssistant()

/** 会话所属的助手（实时跟随助手最新设置）。 */
fun Settings.getAssistantOf(conversation: Conversation): Assistant =
    getStoredAssistantOf(conversation).withConversation(conversation)

/** 会话使用的聊天模型（实时跟随所属助手当前选中的模型，渠道切换立即生效）。 */
fun Settings.getChatModelOf(conversation: Conversation): Model? =
    findModelById(getStoredAssistantOf(conversation).chatModelId ?: chatModelId)

/** 确保会话拥有用于存储父子代理关联的 [ConversationConfig] 容器，不再锁定任何模型或工具快照。 */
fun Conversation.bindConfig(settings: Settings): Conversation {
    if (config != null) return this
    return copy(config = ConversationConfig())
}
