package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptBlock
import me.rerere.rikkahub.data.model.PromptBlockPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.PromptMarkerType
import me.rerere.rikkahub.data.model.PromptPreset
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 「按预设发送工具调用」(PromptBlock.followPresetInToolCall)
 */
class PromptToolCallFollowPresetTest {

    private val toolMsg = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text("正在调用工具"),
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "workspace_shell",
                input = "{}",
                output = listOf(UIMessagePart.Text("ok")),
                approvalState = ToolApprovalState.Auto,
            ),
        ),
    )

    private val baseMarkers = listOf(
        PromptBlock(markerType = PromptMarkerType.WORLD_INFO_BEFORE),
        PromptBlock(markerType = PromptMarkerType.CHAR_SYSTEM_PROMPT),
        PromptBlock(markerType = PromptMarkerType.WORLD_INFO_AFTER),
        PromptBlock(markerType = PromptMarkerType.CHAT_HISTORY),
    )

    private fun run(
        blocks: List<PromptBlock>,
        messages: List<UIMessage>,
        lorebooks: List<Lorebook> = emptyList(),
    ): List<String> {
        val presetId = Uuid.random()
        val preset = PromptPreset(id = presetId, enabled = true, blocks = blocks)
        val assistant = Assistant(
            modeInjectionIds = setOf(presetId),
            lorebookIds = lorebooks.map { it.id }.toSet(),
        )
        return transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = lorebooks,
            promptPresets = listOf(preset),
        ).map { it.toText().trim() }
    }

    private val withTool = listOf(UIMessage.system("sys"), UIMessage.user("u1"), toolMsg)

    @Test
    fun `post history block follows preset and goes after the active tool call`() {
        val blocks = baseMarkers + listOf(
            PromptBlock(name = "照常", role = MessageRole.USER, content = "[照常]"),
            PromptBlock(name = "跟随", role = MessageRole.USER, content = "[跟随]", followPresetInToolCall = true),
        )
        assertEquals(
            listOf("sys", "u1", "[照常]", "正在调用工具", "[跟随]"),
            run(blocks, withTool),
        )
    }

    @Test
    fun `flag has no effect without an active tool call`() {
        val blocks = baseMarkers + listOf(
            PromptBlock(name = "照常", role = MessageRole.USER, content = "[照常]"),
            PromptBlock(name = "跟随", role = MessageRole.USER, content = "[跟随]", followPresetInToolCall = true),
        )
        val messages = listOf(UIMessage.system("sys"), UIMessage.user("u1"), UIMessage.assistant("a1"))
        assertEquals(listOf("sys", "u1", "a1", "[照常]", "[跟随]"), run(blocks, messages))
    }

    @Test
    fun `blocks above chat history are unaffected`() {
        val blocks = listOf(
            PromptBlock(name = "顶部", role = MessageRole.USER, content = "[顶部]", followPresetInToolCall = true),
        ) + baseMarkers
        // 「顶部」排在 📌 Char Description 之前，所以在 sys 之前；开关对 Chat History 上方的块不起作用
        assertEquals(listOf("[顶部]", "sys", "u1", "正在调用工具"), run(blocks, withTool))
    }

    @Test
    fun `in chat depth counts the active tool call only when following`() {
        fun d0(follow: Boolean) = PromptBlock(
            name = "d0",
            role = MessageRole.SYSTEM,
            content = if (follow) "[d0跟随]" else "[d0照常]",
            position = PromptBlockPosition.IN_CHAT,
            inChatDepth = true,
            depth = 0,
            followPresetInToolCall = follow,
        )
        // 照常：@D 0 落在聊天历史末尾（当前工具调用之前）
        assertEquals(
            listOf("sys", "u1", "[d0照常]", "正在调用工具"),
            run(baseMarkers + d0(false), withTool),
        )
        // 跟随：@D 0 落在当前工具调用之后
        assertEquals(
            listOf("sys", "u1", "正在调用工具", "[d0跟随]"),
            run(baseMarkers + d0(true), withTool),
        )
        // 两个同时存在
        assertEquals(
            listOf("sys", "u1", "[d0照常]", "正在调用工具", "[d0跟随]"),
            run(baseMarkers + d0(false) + d0(true), withTool),
        )
    }

    @Test
    fun `lorebook depth entries keep original placement when a following block exists`() {
        val loreId = Uuid.random()
        val lorebook = Lorebook(
            id = loreId,
            enabled = true,
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "lore_d0",
                    constantActive = true,
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 0,
                    role = MessageRole.SYSTEM,
                    content = "[世界书d0]",
                ),
            ),
        )
        val followD0 = PromptBlock(
            name = "d0",
            role = MessageRole.SYSTEM,
            content = "[d0跟随]",
            position = PromptBlockPosition.IN_CHAT,
            inChatDepth = true,
            depth = 0,
            followPresetInToolCall = true,
        )
        assertEquals(
            listOf("sys", "u1", "[世界书d0]", "正在调用工具", "[d0跟随]"),
            run(baseMarkers + followD0, withTool, listOf(lorebook)),
        )
        // 不开启时与原来一致
        assertEquals(
            listOf("sys", "u1", "[世界书d0]", "正在调用工具"),
            run(baseMarkers, withTool, listOf(lorebook)),
        )
    }
}
