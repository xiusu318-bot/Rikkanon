package me.rerere.rikkahub.data.model

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A", tools = setOf(BuiltInTools.Search))
    private val modelB = Model(modelId = "model-b", displayName = "Model B")
    private val mcpServer = Uuid.random()
    private val workspace = Uuid.random()
    private val otherWorkspace = Uuid.random()
    private val injection = Uuid.random()
    private val lorebook = Uuid.random()

    private val assistant = Assistant(
        chatModelId = modelA.id,
        reasoningLevel = ReasoningLevel.HIGH,
        enableWebSearch = true,
        mcpServers = setOf(mcpServer),
        workspaceId = workspace,
        enabledSkills = setOf("skill"),
        modeInjectionIds = setOf(injection),
        lorebookIds = setOf(lorebook),
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = modelB.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB))),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = mcpServer)),
        modeInjections = listOf(PromptInjection.ModeInjection(id = injection)),
        lorebooks = listOf(Lorebook(id = lorebook)),
    )

    private fun conversation(assistant: Assistant = this.assistant) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
    )

    @Test
    fun `unstarted conversation follows the assistant`() {
        val conversation = conversation()

        assertSame(assistant, settings().getAssistantOf(conversation))
        assertEquals(modelA, settings().getChatModelOf(conversation))
    }

    @Test
    fun `bound conversation follows assistant updates in real time`() {
        val bound = conversation().bindConfig(settings())
        val changed = assistant.copy(
            chatModelId = modelB.id,
            reasoningLevel = ReasoningLevel.OFF,
            enableWebSearch = false,
            mcpServers = emptySet(),
            workspaceId = otherWorkspace,
            enabledSkills = setOf("skill-2"),
            systemPrompt = "changed",
        )

        val resolved = settings(changed).getAssistantOf(bound)

        assertEquals(changed, resolved)
        assertEquals(modelB, settings(changed).getChatModelOf(bound))
    }

    @Test
    fun `sub-agent conversation inherits shared workspace even if sub-assistant has its own workspace`() {
        val parentId = Uuid.random()
        val subConv = conversation().copy(
            config = ConversationConfig(
                parentConversationId = parentId,
                workspaceId = otherWorkspace,
            ),
        )

        val resolved = settings().getAssistantOf(subConv)
        assertEquals(otherWorkspace, resolved.workspaceId)
    }

    @Test
    fun `chat page update writes directly to assistant and resets cwd on workspace change`() {
        val bound = conversation().bindConfig(settings()).copy(workspaceCwd = "/workspace/project")
        val assistantView = settings().getAssistantOf(bound)

        val sameWorkspace = bound.withAssistantUpdate(assistantView.copy(reasoningLevel = ReasoningLevel.LOW), settings())
        val switchedWorkspace = bound.withAssistantUpdate(assistantView.copy(workspaceId = otherWorkspace), settings())

        assertEquals("/workspace/project", sameWorkspace.workspaceCwd)
        assertNull(switchedWorkspace.workspaceCwd)
    }
}
