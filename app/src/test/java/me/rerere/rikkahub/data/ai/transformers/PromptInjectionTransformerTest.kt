package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.rawJsonElementOrNull
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGreenLightMode
import me.rerere.rikkahub.data.model.PromptBlock
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.PromptMarkerType
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.getTriggeredInjections
import me.rerere.rikkahub.data.model.isTriggered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {

    // ==================== isTriggered 测试 ====================

    @Test
    fun `disabled injection should never trigger`() {
        val injection = PromptInjection.RegexInjection(
            enabled = false,
            keywords = listOf("hello"),
            content = "injected"
        )
        assertFalse(injection.isTriggered("hello world"))
    }

    @Test
    fun `constantActive should always trigger when enabled unless excluded by secondary NOT filter`() {
        val injection = PromptInjection.RegexInjection(
            enabled = true,
            constantActive = true,
            keywords = emptyList(),
            content = "always active"
        )
        assertTrue(injection.isTriggered(""))
        assertTrue(injection.isTriggered("any content"))
    }

    @Test
    fun `uploaded test_json lorebook import and trigger behavior`() {
        val uploadedJson = """
            {"entries":{"0":{"addMemo":true,"automationId":"","caseSensitive":null,"characterFilterExclude":false,"characterFilterNames":[],"characterFilterTags":[],"comment":"测试","constant":false,"content":"我们正在测试，能看到吗？","cooldown":0,"delay":0,"delayUntilRecursion":false,"depth":4,"disable":false,"displayIndex":0,"excludeRecursion":false,"group":"","groupOverride":false,"groupWeight":100,"key":["/.*/s"],"keysecondary":["测试"],"matchCharacterDepthPrompt":false,"matchCharacterDescription":false,"matchCharacterPersonality":false,"matchCreatorNotes":false,"matchPersonaDescription":false,"matchScenario":false,"matchWholeWords":null,"order":100,"position":0,"preventRecursion":false,"probability":100,"role":null,"scanDepth":null,"selective":true,"selectiveLogic":2,"sticky":0,"uid":0,"useGroupScoring":null,"useProbability":true,"vectorized":false,"ignoreBudget":false,"outletName":"","triggers":[],"characterFilter":{"isExclude":false,"names":[],"tags":[]}},"1":{"addMemo":true,"automationId":"","caseSensitive":null,"characterFilterExclude":false,"characterFilterNames":[],"characterFilterTags":[],"comment":"测试","constant":true,"content":"我们正在测试，能看到吗？","cooldown":0,"delay":0,"delayUntilRecursion":false,"depth":4,"disable":false,"displayIndex":0,"excludeRecursion":false,"group":"","groupOverride":false,"groupWeight":100,"key":[],"keysecondary":["测试"],"matchCharacterDepthPrompt":false,"matchCharacterDescription":false,"matchCharacterPersonality":false,"matchCreatorNotes":false,"matchPersonaDescription":false,"matchScenario":false,"matchWholeWords":null,"order":100,"position":0,"preventRecursion":false,"probability":100,"role":null,"scanDepth":null,"selective":true,"selectiveLogic":2,"sticky":0,"uid":1,"useGroupScoring":null,"useProbability":true,"vectorized":false,"ignoreBudget":false,"outletName":"","triggers":[],"characterFilter":{"isExclude":false,"names":[],"tags":[]}}}}
        """.trimIndent()

        val book = LorebookSerializer.importFromString(uploadedJson, "测试")
        assertNotNull(book)
        assertEquals(2, book!!.entries.size)

        val entry0 = book.entries[0]
        val entry1 = book.entries[1]
        assertEquals(SelectiveLogic.NOT_ANY, entry0.selectiveLogic)
        assertEquals(SelectiveLogic.NOT_ANY, entry1.selectiveLogic)

        // 当上下文中不含"测试"时，/.*/s 命中且排除词未出现 -> 两个条目均触发
        assertTrue(entry0.isTriggered("你好\n世界"))
        assertTrue(entry1.isTriggered("你好\n世界"))

        // 当上下文中包含"测试"时，NOT_ANY 排除过滤器生效 -> 两个条目均不触发
        assertFalse(entry0.isTriggered("开始测试一下"))
        assertFalse(entry1.isTriggered("开始测试一下"))
    }

    // ==================== Order（数字越小越靠上）与 Depth=9999 测试 ====================

    @Test
    fun `getTriggeredInjections sorts by order ascending (smaller number on top)`() {
        val injections = listOf(
            PromptInjection.RegexInjection(
                name = "low_top",
                keywords = listOf("a"),
                priority = 200,
                content = "low"
            ),
            PromptInjection.RegexInjection(
                name = "high_top",
                keywords = listOf("a"),
                priority = 10,
                content = "high"
            ),
            PromptInjection.RegexInjection(
                name = "mid",
                keywords = listOf("a"),
                priority = 100,
                content = "mid"
            )
        )
        val triggered = getTriggeredInjections(injections, "a")
        assertEquals(listOf("high_top", "mid", "low_top"), triggered.map { it.name })
    }

    @Test
    fun `depth 9999 injects at start of chat history and same depth orders by smaller order first`() {
        val loreId = Uuid.random()
        val lorebook = Lorebook(
            id = loreId,
            enabled = true,
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "depth_9999_order_100",
                    constantActive = true,
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 9999,
                    priority = 100,
                    role = MessageRole.USER,
                    content = "[对话前-顺序100]"
                ),
                PromptInjection.RegexInjection(
                    name = "depth_9999_order_10",
                    constantActive = true,
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 9999,
                    priority = 10,
                    role = MessageRole.USER,
                    content = "[对话前-顺序10]"
                ),
            )
        )
        val assistant = Assistant(lorebookIds = setOf(loreId))
        val messages = listOf(
            UIMessage.system("系统提示词"),
            UIMessage.user("第1条消息"),
            UIMessage.assistant("第2条消息"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        // index 0 是系统提示词，index 1 是两条 user 世界书（顺序10在顺序100上面）与后方同角色 user("第1条消息") 合并后的正文
        assertEquals("系统提示词", result[0].toText())
        assertEquals("[对话前-顺序10]\n[对话前-顺序100]\n\n第1条消息", result[1].toText())
        assertEquals("第2条消息", result[2].toText())
    }

    // ==================== 绿灯 STICKY_LOCKED_POSITION 位置锚定测试 ====================

    @Test
    fun `green light STICKY_LOCKED_POSITION keeps injection locked between msg 4 and 5 after msg 6 arrives`() {
        val loreId = Uuid.random()
        val lorebook = Lorebook(
            id = loreId,
            enabled = true,
            greenLightMode = LorebookGreenLightMode.STICKY_LOCKED_POSITION,
            defaultScanDepth = 1,
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "locked_entry",
                    keywords = listOf("触发词"),
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 1, // 在触发时插在最新消息之前（即 4 和 5 之间）
                    priority = 100,
                    role = MessageRole.SYSTEM,
                    content = "[锚定注入]"
                )
            )
        )
        val assistant = Assistant(lorebookIds = setOf(loreId))

        // 当只有 1, 2, 3, 4, 5（第 5 条含触发词）时：注入在 4 和 5 之间
        val messagesAt5 = listOf(
            UIMessage.user("1"),
            UIMessage.assistant("2"),
            UIMessage.user("3"),
            UIMessage.assistant("4"),
            UIMessage.user("5 触发词"),
        )
        val resultAt5 = transformMessages(
            messages = messagesAt5,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
        assertEquals(listOf("1", "2", "3", "4", "[锚定注入]", "5 触发词"), resultAt5.map { it.toText() })

        // 继续发送第 6 条（不含触发词，且 defaultScanDepth=1 原本已滑出扫描窗口）：
        // 开启 STICKY_LOCKED_POSITION 后，不仅保持激活，而且位置依然焊死在 4 和 5 之间！
        val messagesAt6 = messagesAt5 + UIMessage.assistant("6")
        val resultAt6 = transformMessages(
            messages = messagesAt6,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
        assertEquals(listOf("1", "2", "3", "4", "[锚定注入]", "5 触发词", "6"), resultAt6.map { it.toText() })
    }

    // ==================== 连续工具链压底测试 ====================

    @Test
    fun `active trailing tool chain stays at the very bottom after preset post-history instructions`() {
        val presetId = Uuid.random()
        val preset = PromptPreset(
            id = presetId,
            name = "带底部提示词预设",
            enabled = true,
            blocks = listOf(
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_BEFORE),
                PromptBlock(markerType = PromptMarkerType.CHAR_SYSTEM_PROMPT),
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_AFTER),
                PromptBlock(markerType = PromptMarkerType.CHAT_HISTORY),
                PromptBlock(
                    name = "底部指令",
                    enabled = true,
                    role = MessageRole.USER,
                    content = "[底部后置指令]"
                ),
            )
        )
        val assistant = Assistant(modeInjectionIds = setOf(presetId))

        val activeToolMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("正在调用工具1..."),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "workspace_shell",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("ok")),
                    approvalState = ToolApprovalState.Auto,
                )
            )
        )
        val messages = listOf(
            UIMessage.system("系统提示词"),
            UIMessage.user("帮我查一下文件"),
            activeToolMsg,
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            promptPresets = listOf(preset),
        )

        // 顺序必须是：系统提示词 -> 用户消息(Chat History) -> [底部后置指令] -> activeToolMsg(压在最底部)
        assertEquals(4, result.size)
        assertEquals("系统提示词", result[0].toText())
        assertEquals("帮我查一下文件", result[1].toText())
        assertEquals("[底部后置指令]", result[2].toText())
        assertEquals(activeToolMsg, result[3])
    }

    // ==================== Outlet 锚点展开测试 ====================

    @Test
    fun `outlet lorebook entry expands inside preset custom block via macro`() {
        val presetId = Uuid.random()
        val loreId = Uuid.random()
        val preset = PromptPreset(
            id = presetId,
            enabled = true,
            blocks = listOf(
                PromptBlock(
                    name = "Main",
                    enabled = true,
                    role = MessageRole.SYSTEM,
                    content = "头部规则：\n{{outlet::状态栏}}"
                ),
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_BEFORE),
                PromptBlock(markerType = PromptMarkerType.CHAR_SYSTEM_PROMPT),
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_AFTER),
                PromptBlock(markerType = PromptMarkerType.CHAT_HISTORY),
            )
        )
        val lorebook = Lorebook(
            id = loreId,
            enabled = true,
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "status_outlet",
                    constantActive = true,
                    position = InjectionPosition.OUTLET,
                    outletName = "状态栏",
                    content = "[HP: 100/100]"
                )
            )
        )
        val assistant = Assistant(
            modeInjectionIds = setOf(presetId),
            lorebookIds = setOf(loreId),
        )
        val messages = listOf(
            UIMessage.system("助手设定"),
            UIMessage.user("你好"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            promptPresets = listOf(preset),
        )

        assertEquals("头部规则：\n[HP: 100/100]\n\n助手设定", result[0].toText())
        assertEquals("你好", result[1].toText())
    }

    // ==================== 思维链回传模块与空白角色 "?" 测试 ====================

    @Test
    fun `reasoning block emits reasoning part with multi-provider signature metadata and text`() {
        val presetId = Uuid.random()
        val preset = PromptPreset(
            id = presetId,
            enabled = true,
            blocks = listOf(
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_BEFORE),
                PromptBlock(markerType = PromptMarkerType.CHAR_SYSTEM_PROMPT),
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_AFTER),
                PromptBlock(markerType = PromptMarkerType.CHAT_HISTORY),
                PromptBlock(
                    name = "AI 预填思维链",
                    enabled = true,
                    role = MessageRole.ASSISTANT,
                    content = "这是预填正文",
                    reasoning = "这是思维链摘要或明文",
                    reasoningSignature = "sig_abc_123",
                ),
            )
        )
        val assistant = Assistant(modeInjectionIds = setOf(presetId))
        val messages = listOf(
            UIMessage.system("系统提示词"),
            UIMessage.user("你好"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            promptPresets = listOf(preset),
        )

        assertEquals(3, result.size)
        val aiMsg = result[2]
        assertEquals(MessageRole.ASSISTANT, aiMsg.role)
        val reasoningPart = aiMsg.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        val textPart = aiMsg.parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("这是思维链摘要或明文", reasoningPart.reasoning)
        assertEquals("这是预填正文", textPart.text)
        assertNotNull(reasoningPart.metadata)
        assertEquals("sig_abc_123", reasoningPart.metadata!!["signature"]?.toString()?.trim('"'))
        assertEquals("sig_abc_123", reasoningPart.metadata!!["thoughtSignature"]?.toString()?.trim('"'))
    }

    @Test
    fun `blank role merges into preceding block content without merging distinct assistant or user blocks`() {
        val presetId = Uuid.random()
        val preset = PromptPreset(
            id = presetId,
            enabled = true,
            blocks = listOf(
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_BEFORE),
                PromptBlock(markerType = PromptMarkerType.CHAR_SYSTEM_PROMPT),
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_AFTER),
                PromptBlock(markerType = PromptMarkerType.CHAT_HISTORY),
                PromptBlock(
                    name = "AI 块 1",
                    enabled = true,
                    role = MessageRole.ASSISTANT,
                    content = "第一段正文",
                    reasoning = "第一段思考",
                    reasoningSignature = "sig_1",
                ),
                PromptBlock(
                    name = "跟随空白块",
                    enabled = true,
                    role = MessageRole.SYSTEM,
                    blankRole = true,
                    content = "第二段跟随合并正文",
                ),
                PromptBlock(
                    name = "AI 块 2（独立不合并）",
                    enabled = true,
                    role = MessageRole.ASSISTANT,
                    content = "另一条独立AI正文",
                    reasoning = "第二段思考",
                    reasoningSignature = "sig_2",
                ),
            )
        )
        val assistant = Assistant(modeInjectionIds = setOf(presetId))
        val messages = listOf(
            UIMessage.system("系统提示词"),
            UIMessage.user("用户问题"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            promptPresets = listOf(preset),
        )

        // 系统提示词 + 用户问题 + AI块1(合并了空白块正文) + AI块2(独立不合并) = 4 条
        assertEquals(4, result.size)
        val aiMsg1 = result[2]
        assertEquals(MessageRole.ASSISTANT, aiMsg1.role)
        assertEquals("第一段思考", aiMsg1.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("第一段正文\n\n第二段跟随合并正文", aiMsg1.parts.filterIsInstance<UIMessagePart.Text>().single().text)

        val aiMsg2 = result[3]
        assertEquals(MessageRole.ASSISTANT, aiMsg2.role)
        assertEquals("第二段思考", aiMsg2.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("另一条独立AI正文", aiMsg2.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `json mode block injects raw json element directly into request messages`() {
        val presetId = Uuid.random()
        val cacheBlockJson = """
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": "静态设定知识库",
                        "cache_control": { "type": "ephemeral" }
                    }
                ]
            }
        """.trimIndent()
        val preset = PromptPreset(
            id = presetId,
            enabled = true,
            blocks = listOf(
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_BEFORE),
                PromptBlock(markerType = PromptMarkerType.CHAR_SYSTEM_PROMPT),
                PromptBlock(markerType = PromptMarkerType.WORLD_INFO_AFTER),
                PromptBlock(markerType = PromptMarkerType.CHAT_HISTORY),
                PromptBlock(
                    name = "Cache Control Block",
                    enabled = true,
                    jsonMode = true,
                    rawJson = cacheBlockJson,
                ),
            )
        )
        val assistant = Assistant(modeInjectionIds = setOf(presetId))
        val messages = listOf(
            UIMessage.system("系统提示词"),
            UIMessage.user("你好"),
        )

        val result = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = emptyList(),
            promptPresets = listOf(preset),
        )

        assertEquals(3, result.size)
        val rawElem = result[2].rawJsonElementOrNull() as? JsonObject
        assertNotNull(rawElem)
        assertEquals("user", rawElem!!["role"]?.jsonPrimitive?.content)
        assertTrue(result[2].isValidToUpload())
    }
}
