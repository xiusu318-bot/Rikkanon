package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.config, fork.config)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("Source conversation(1)", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `context checkpoint is inserted after the anchor node without touching other nodes`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = insertContextCheckpoint(source, afterNodeId = nodes[1].id, summary = "summary")!!

        assertEquals(nodes.subList(0, 2), result.messageNodes.subList(0, 2))
        assertEquals(nodes.subList(2, 4), result.messageNodes.subList(3, 5))
        val checkpoint = result.messageNodes[2].currentMessage
        assertTrue(checkpoint.isContextCheckpoint)
        assertEquals("summary", checkpoint.toText())
        // 只有检查点之后的消息会继续发送给模型
        assertEquals(result.currentMessages.subList(2, 5), result.currentMessages.limitContext(0))
    }

    @Test
    fun `context checkpoint is not inserted when the anchor node is gone`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("message").toMessageNode()),
        )

        assertNull(insertContextCheckpoint(source, afterNodeId = Uuid.random(), summary = "summary"))
    }

    @Test
    fun `fork title increments existing numeric suffix instead of stacking`() {
        assertEquals("Chat(2)", forkConversationTitle("Chat(1)", emptySet()))
        assertEquals("Chat(4)", forkConversationTitle("Chat(1)", setOf("Chat(2)", "Chat(3)")))
        assertEquals("Chat(1)", forkConversationTitle("Chat", emptySet()))
        assertEquals("Chat(2)", forkConversationTitle("Chat", setOf("Chat(1)")))
        assertEquals("Chat(abc)(1)", forkConversationTitle("Chat(abc)", emptySet()))
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model, conversationId)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
        assertEquals(conversationId.toString(), params.sessionId)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `sanitize invalid messages preserves completed multi-step tools instead of deleting whole message`() {
        val userNode = UIMessage.user("Refactor module").toMessageNode()
        val multiStepAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("Step 1 plan"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_read_file",
                    input = """{"path":"/workspace/a.kt"}""",
                    output = listOf(UIMessagePart.Text("file content A")),
                ),
                UIMessagePart.Text("Now checking second file"),
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "workspace_shell",
                    input = """{"command":"./gradlew test"}""",
                    output = emptyList(),
                    approvalState = ToolApprovalState.Pending,
                ),
                // Half-streamed broken SSE fragment at tail
                UIMessagePart.Tool(
                    toolCallId = "call-3",
                    toolName = "workspace_edit",
                    input = """{"path":"/work""",
                    output = emptyList(),
                    approvalState = ToolApprovalState.Auto,
                ),
            ),
        ).toMessageNode()
        val conv = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(userNode, multiStepAssistant),
        )

        val sanitized = sanitizeConversationInvalidMessages(conv)

        assertEquals(2, sanitized.messageNodes.size)
        val assistantMsg = sanitized.messageNodes[1].currentMessage
        val tools = assistantMsg.getTools()
        // Step 1 stays intact, Step 2 (complete call) is closed as cancelled, Step 3 (broken fragment) is stripped
        assertEquals(2, tools.size)
        assertEquals("call-1", tools[0].toolCallId)
        assertEquals("file content A", (tools[0].output.single() as UIMessagePart.Text).text)
        assertEquals("call-2", tools[1].toolCallId)
        assertTrue(tools[1].isExecuted)
        assertTrue((tools[1].output.single() as UIMessagePart.Text).text.contains("\"status\":\"cancelled\""))
    }

    @Test
    fun `sanitize invalid messages keeps resumable approved tool on active target node`() {
        val approvedTool = UIMessagePart.Tool(
            toolCallId = "call-approve",
            toolName = "workspace_shell",
            input = """{"command":"ls"}""",
            output = emptyList(),
            approvalState = ToolApprovalState.Approved,
        )
        val conv = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage.user("run ls").toMessageNode(),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(approvedTool)).toMessageNode(),
            ),
        )

        val sanitized = sanitizeConversationInvalidMessages(conv)

        val keptTool = sanitized.messageNodes[1].currentMessage.getTools().single()
        assertEquals(ToolApprovalState.Approved, keptTool.approvalState)
        assertFalse(keptTool.isExecuted)
    }

    @Test
    fun `cold load recovery closes interrupted auto tool from step checkpoint while keeping pending approvals`() {
        val runningCheckpointConv = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage.user("build").toMessageNode(),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "done-1",
                            toolName = "workspace_read_file",
                            input = """{"path":"/workspace/README.md"}""",
                            output = listOf(UIMessagePart.Text("ok")),
                        ),
                        UIMessagePart.Tool(
                            toolCallId = "running-2",
                            toolName = "workspace_shell",
                            input = """{"command":"sleep 60"}""",
                            output = emptyList(),
                            approvalState = ToolApprovalState.Auto,
                            startedAt = kotlin.time.Clock.System.now(),
                        ),
                        UIMessagePart.Tool(
                            toolCallId = "pending-3",
                            toolName = "ask_user",
                            input = """{"question":"continue?"}""",
                            output = emptyList(),
                            approvalState = ToolApprovalState.Pending,
                        ),
                    ),
                ).toMessageNode(),
            ),
        )

        val recovered = recoverColdLoadedConversation(runningCheckpointConv)

        val tools = recovered.messageNodes[1].currentMessage.getTools()
        assertEquals(3, tools.size)
        assertEquals("ok", (tools[0].output.single() as UIMessagePart.Text).text)
        assertTrue(tools[1].isExecuted)
        assertTrue((tools[1].output.single() as UIMessagePart.Text).text.contains("\"status\":\"interrupted\""))
        assertNotNull(tools[1].finishedAt)
        assertTrue(tools[2].isPending)
    }
}
