package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.Settings
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // 会话开始后固定的配置，null 表示尚未开始、跟随助手
    val config: ConversationConfig? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 给还没有模型快照的消息补上快照，没有可补的消息时原样返回。
 *
 * 在保存会话时调用：新生成的消息由此带上快照，引入快照之前的旧消息也在会话下次保存时补齐。
 * 模型已经被删除的旧消息无从补起，保持原样。
 */
fun Conversation.fillModelSnapshots(settings: Settings): Conversation {
    val models = settings.providers.flatMap { it.models }.associateBy { it.id }
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { it.fillModelSnapshot(models) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.fillModelSnapshot(models: Map<Uuid, Model>): UIMessage {
    if (modelSnapshot != null) return this
    val model = modelId?.let(models::get) ?: return this
    return copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}

private val SUB_CONVERSATION_TOOL_NAMES = setOf("conversation_send")
private val CONVERSATION_ID_JSON_REGEX = Regex(""""conversation_id"\s*:\s*"([^"]+)"""")
private val SUB_AGENT_INDEX_REGEX = Regex("""^(?:sub[_-]?|#)?(\d+)$""", RegexOption.IGNORE_CASE)

/**
 * 将主代理传入的局部子代理代号（如 `"sub_1"`、`"#1"`、`"1"` 或 Fork 映射前的旧 UUID 字符串）
 * 解析为当前主对话名下对应的真实子对话 [Uuid]。
 */
fun Conversation.resolveRealSubAgentId(rawId: String): Uuid? {
    val token = rawId.trim()
    if (token.isEmpty()) return null
    val deleted = config?.deletedChildConversationIds.orEmpty()
    val idMap = config?.subAgentIdMap.orEmpty()

    // 1. 直接命中映射表（含 "sub_1" 或 Fork 后的旧 UUID 字符串映射）
    idMap[token]?.takeIf { it != id && it !in deleted }?.let { return it }

    // 2. 归一化匹配（例如传入 "1"、"#1"、"sub-1" 均可匹配 "sub_1"）
    val indexMatch = SUB_AGENT_INDEX_REGEX.matchEntire(token)
    if (indexMatch != null) {
        val normalizedKey = "sub_${indexMatch.groupValues[1]}"
        idMap[normalizedKey]?.takeIf { it != id && it !in deleted }?.let { return it }
        val oneBasedIdx = indexMatch.groupValues[1].toIntOrNull()
        if (oneBasedIdx != null && oneBasedIdx >= 1) {
            val ordered = allChildConversationIds.toList()
            if (oneBasedIdx - 1 in ordered.indices) {
                return ordered[oneBasedIdx - 1]
            }
        }
    }

    // 3. 兼容未映射的旧对话真实 UUID
    val parsedUuid = runCatching { Uuid.parse(token) }.getOrNull() ?: return null
    if (parsedUuid == id || parsedUuid in deleted) return null
    if (parsedUuid in allChildConversationIds) return parsedUuid
    return null
}

/**
 * 获取当前主对话下某个真实子对话 [realChildId] 对主代理暴露的局部代号（如 `"sub_1"`、`"sub_2"`）。
 */
fun Conversation.resolveLogicalSubAgentId(realChildId: Uuid): String {
    val idMap = config?.subAgentIdMap.orEmpty()
    idMap.entries.firstOrNull { (k, v) -> v == realChildId && k.startsWith("sub_") }?.key?.let { return it }
    val idx = allChildConversationIds.indexOf(realChildId)
    if (idx >= 0) return "sub_${idx + 1}"
    return realChildId.toString()
}

/**
 * 提取当前主对话所关联/操控的所有真实子对话 UUID：
 * - 包含 [ConversationConfig.childConversationIds] 与 [ConversationConfig.subAgentIdMap] 中的真实子对话 ID；
 * - 扫描消息树中 `conversation_send` 的 `conversation_id`：若在 `subAgentIdMap` 中有映射则取映射后的真实 UUID
 *   （避免 Fork 后把原主对话的旧子代理 UUID 混入新分支），否则兼容解析原始 UUID。
 */
val Conversation.allChildConversationIds: Set<Uuid>
    get() = buildSet {
        val idMap = config?.subAgentIdMap.orEmpty()
        config?.childConversationIds?.let(::addAll)
        addAll(idMap.values)
        messageNodes.forEach { node ->
            node.messages.forEach { message ->
                message.getTools().forEach { tool ->
                    if (tool.toolName in SUB_CONVERSATION_TOOL_NAMES) {
                        CONVERSATION_ID_JSON_REGEX.findAll(tool.input).forEach { match ->
                            val rawToken = match.groupValues[1].trim()
                            val mapped = idMap[rawToken]
                            if (mapped != null) {
                                add(mapped)
                            } else if (rawToken !in idMap.keys) {
                                runCatching { Uuid.parse(rawToken) }.getOrNull()?.let(::add)
                            }
                        }
                        tool.output.filterIsInstance<UIMessagePart.Text>().forEach { textPart ->
                            CONVERSATION_ID_JSON_REGEX.findAll(textPart.text).forEach { match ->
                                val rawToken = match.groupValues[1].trim()
                                val mapped = idMap[rawToken]
                                if (mapped != null) {
                                    add(mapped)
                                } else if (rawToken !in idMap.keys) {
                                    runCatching { Uuid.parse(rawToken) }.getOrNull()?.let(::add)
                                }
                            }
                        }
                    }
                }
            }
        }
        remove(id)
        config?.deletedChildConversationIds?.let(::removeAll)
    }

