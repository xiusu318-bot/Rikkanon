package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** rikka.conversations / rikka.subAgents 的实现，直接调用 ChatService 与 ConversationRepository。 */
class PluginConversationApi(
    private val chatService: ChatService,
    private val repo: ConversationRepository,
    private val settingsStore: SettingsStore,
) {
    private fun JsonObject.uuid(key: String): Uuid = Uuid.parse(str(key))

    private fun parts(element: JsonElement?): List<UIMessagePart> = when (element) {
        null, is JsonNull -> error("缺少参数 content")
        is JsonPrimitive -> listOf(UIMessagePart.Text(element.content))
        is JsonArray -> JsonInstant.decodeFromJsonElement(ListSerializer(UIMessagePart.serializer()), element)
        is JsonObject -> JsonInstant.decodeFromJsonElement(ListSerializer(UIMessagePart.serializer()), JsonArray(listOf(element)))
    }

    private fun summary(c: Conversation): JsonObject = buildJsonObject {
        put("id", c.id.toString())
        put("assistantId", c.assistantId.toString())
        put("title", c.title)
        put("isPinned", c.isPinned)
        put("createAt", c.createAt.toEpochMilli())
        put("updateAt", c.updateAt.toEpochMilli())
        put("messageCount", c.messageNodes.size)
        c.config?.parentConversationId?.let { put("parentConversationId", it.toString()) }
    }

    private suspend fun load(id: Uuid): Conversation =
        chatService.getOrLoadConversation(id) ?: error("对话不存在: $id")

    suspend fun call(name: String, o: JsonObject): JsonElement = when (name) {
        "conversations.list" -> {
            val assistantId = o.strOrNull("assistantId")?.let(Uuid::parse)
                ?: settingsStore.awaitLoaded().assistantId
            val limit = o.intOrNull("limit") ?: 50
            JsonArray(repo.getRecentConversations(assistantId, limit).map(::summary))
        }

        "conversations.search" -> {
            val keyword = o.str("keyword")
            val settings = settingsStore.awaitLoaded()
            val hits = repo.searchMessagesOfAssistants(keyword, settings.assistants.map { it.id }.toSet())
            JsonArray(hits.map { h ->
                buildJsonObject {
                    put("conversationId", h.conversationId)
                    put("messageId", h.messageId)
                    put("title", h.title)
                    put("snippet", h.snippet)
                    put("updateAt", h.updateAt.toEpochMilli())
                }
            })
        }

        "conversations.get" -> JsonInstant.encodeToJsonElement(Conversation.serializer(), load(o.uuid("id")))

        "conversations.status" -> {
            val (status, processing) = chatService.getConversationRuntimeStatus(o.uuid("id"))
            buildJsonObject {
                put("status", status)
                processing?.let { put("processingStatus", it) }
            }
        }

        "conversations.create" -> {
            val id = Uuid.random()
            val assistantId = o.strOrNull("assistantId")?.let(Uuid::parse)
                ?: settingsStore.awaitLoaded().assistantId
            val conversation = Conversation.ofId(id = id, assistantId = assistantId).copy(title = o.strOrNull("title").orEmpty())
            repo.insertConversation(conversation)
            JsonPrimitive(id.toString())
        }

        "conversations.send" -> {
            val id = o.uuid("id")
            chatService.initializeConversation(id)
            chatService.sendMessage(id, parts(o["content"]), answer = o.boolOr("answer", true))
            JsonNull
        }

        "conversations.edit" -> {
            chatService.editMessage(o.uuid("id"), o.uuid("messageId"), parts(o["content"]))
            JsonNull
        }

        "conversations.deleteMessage" -> {
            chatService.deleteMessage(o.uuid("id"), o.uuid("messageId"))
            JsonNull
        }

        "conversations.fork" -> JsonPrimitive(chatService.forkConversationAtMessage(o.uuid("id"), o.uuid("messageId")).id.toString())

        "conversations.regenerate" -> {
            val id = o.uuid("id")
            val messageId = o.uuid("messageId")
            val message = load(id).messageNodes.flatMap { it.messages }.find { it.id == messageId }
                ?: error("消息不存在: $messageId")
            chatService.regenerateAtMessage(id, message)
            JsonNull
        }

        "conversations.stop" -> {
            chatService.stopGeneration(o.uuid("id"))
            JsonNull
        }

        "conversations.approve" -> {
            chatService.handleToolApproval(
                conversationId = o.uuid("id"),
                toolCallId = o.str("toolCallId"),
                approved = o.boolOr("approved", false),
                reason = o.strOrNull("reason").orEmpty(),
                answer = o.strOrNull("answer"),
            )
            JsonNull
        }

        "conversations.setTitle" -> {
            val id = o.uuid("id")
            val conversation = load(id)
            chatService.saveConversation(id, conversation.copy(title = o.str("title")))
            JsonNull
        }

        "conversations.delete" -> {
            val conversation = load(o.uuid("id"))
            chatService.stopGeneration(conversation.id)
            repo.deleteConversation(conversation)
            JsonNull
        }

        else -> error("未知宿主 API: $name")
    }

    suspend fun callSubAgent(name: String, o: JsonObject): JsonElement = when (name) {
        "subAgents.send" -> {
            val result = chatService.sendToSubConversation(
                parentConversationId = o.uuid("parentId"),
                targetConversationId = o.strOrNull("targetId")?.let(Uuid::parse),
                targetAssistantId = o.strOrNull("assistantId")?.let(Uuid::parse),
                title = o.strOrNull("title"),
                message = o.str("message"),
                wait = o.boolOr("wait", true),
                timeoutMs = o.longOr("timeoutMs", 60_000L),
                interrupt = o.boolOr("interrupt", false),
                maxStepsOverride = o.intOrNull("maxSteps"),
            )
            buildJsonObject {
                put("conversationId", result.conversationId.toString())
                put("logicalId", result.logicalId)
                put("title", result.title)
                put("status", result.status)
                put("timedOut", result.timedOut)
                result.reply?.let { put("reply", it) }
                result.processingStatus?.let { put("processingStatus", it) }
            }
        }

        "subAgents.read" -> {
            val r = chatService.readSubConversation(
                targetConversationId = o.uuid("id"),
                offset = o.intOrNull("offset") ?: 0,
                limit = o.intOrNull("limit") ?: 20,
                waitMs = o.longOr("waitMs", 0L),
            )
            buildJsonObject {
                put("conversationId", r.conversationId.toString())
                put("title", r.title)
                put("status", r.status)
                r.processingStatus?.let { put("processingStatus", it) }
                put("totalMessages", r.totalMessages)
                put("messages", JsonArray(r.messages.map { m ->
                    buildJsonObject {
                        put("position", m.position)
                        put("role", m.role)
                        put("text", m.text)
                    }
                }))
            }
        }

        "subAgents.wait" -> {
            val ids = o["ids"]!!.jsonArray.map { Uuid.parse(it.jsonPrimitive.content) }
            chatService.waitForSubConversations(ids, o.longOr("waitMs", 60_000L), o.boolOr("waitAll", true))
            JsonArray(ids.map { id ->
                buildJsonObject {
                    put("id", id.toString())
                    put("status", chatService.getConversationRuntimeStatus(id).first)
                }
            })
        }

        "subAgents.delete" -> {
            chatService.deleteSubConversation(o.strOrNull("parentId")?.let(Uuid::parse), o.uuid("id"))
            JsonNull
        }

        else -> error("未知宿主 API: $name")
    }
}
