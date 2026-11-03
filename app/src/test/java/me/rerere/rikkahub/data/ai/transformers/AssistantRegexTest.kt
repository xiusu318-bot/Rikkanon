package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ActiveRegexRuleKey
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPhase
import me.rerere.rikkahub.data.model.RegexReplaceCacheKey
import me.rerere.rikkahub.data.model.RegexResultCache
import me.rerere.rikkahub.data.model.replaceRegexes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantRegexTest {

    @Test
    fun `default regex checks both requestOnly and visualOnly and preserves raw text`() = runTest {
        val regex = AssistantRegex(
            id = Uuid.random(),
            findRegex = "/<status>.*?<\\/status>\\n?/gs",
            replaceString = "",
        )
        // 默认勾选 ASSISTANT、visualOnly = true、requestOnly = true
        assertEquals(setOf(AssistantAffectScope.ASSISTANT), regex.affectingScope)
        assertEquals(true, regex.visualOnly)
        assertEquals(true, regex.requestOnly)

        val assistant = Assistant(regexes = listOf(regex))
        val rawText = "<status>\nHP: 100\nMP: 50\n</status>\n你好，旅行者！"

        // 1. 面向人类显示 (VISUAL) 生效
        assertEquals(
            "你好，旅行者！",
            rawText.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.VISUAL)
        )
        // 2. 发出请求体 (REQUEST) 生效
        assertEquals(
            "你好，旅行者！",
            rawText.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.REQUEST)
        )
        // 3. 落库阶段 (PERSIST) 不改动原消息
        assertEquals(
            rawText,
            rawText.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.PERSIST)
        )
    }

    @Test
    fun `requestOnly and visualOnly independent switches and persist mode`() = runTest {
        val raw = "前缀 [隐藏内容] 后缀"

        // 仅修改发出
        val requestOnlyAssistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "\\[隐藏内容\\]\\s*",
                    replaceString = "",
                    requestOnly = true,
                    visualOnly = false,
                )
            )
        )
        assertEquals(raw, raw.replaceRegexes(requestOnlyAssistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.VISUAL))
        assertEquals("前缀 后缀", raw.replaceRegexes(requestOnlyAssistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.REQUEST))
        assertEquals(raw, raw.replaceRegexes(requestOnlyAssistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.PERSIST))

        // 仅修改显示
        val visualOnlyAssistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "\\[隐藏内容\\]\\s*",
                    replaceString = "",
                    requestOnly = false,
                    visualOnly = true,
                )
            )
        )
        assertEquals("前缀 后缀", raw.replaceRegexes(visualOnlyAssistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.VISUAL))
        assertEquals(raw, raw.replaceRegexes(visualOnlyAssistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.REQUEST))
        assertEquals(raw, raw.replaceRegexes(visualOnlyAssistant, AssistantAffectScope.ASSISTANT, AssistantRegexPhase.PERSIST))

        // 两个都不勾选 -> 直接修改正文 (PERSIST)
        val persistAssistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "abc",
                    replaceString = "[abc]",
                    requestOnly = false,
                    visualOnly = false,
                )
            )
        )
        val ctx = TransformerContext(
            context = ContextWrapper(null),
            model = Model(modelId = "test", displayName = "Test"),
            assistant = persistAssistant,
            settings = Settings(),
        )
        // 历史已完成消息 + 当前新生成消息：onGenerationFinish 只改最后一条，不对历史消息重复套娃
        val historyMsg = UIMessage.assistant("[abc]")
        val latestMsg = UIMessage.assistant("hello abc")
        val finished = RegexOutputTransformer.onGenerationFinish(ctx, listOf(historyMsg, latestMsg))
        assertEquals("[abc]", (finished[0].parts[0] as UIMessagePart.Text).text)
        assertEquals("hello [abc]", (finished[1].parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `supports latex dollar replacement, slash flags, newline and empty pattern safety`() {
        val assistant = Assistant(
            regexes = listOf(
                // 空正则安全跳过
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "",
                    replaceString = "BROKEN",
                ),
                // LaTeX $$$1$$ 与 \frac 反斜杠保留
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = """\\\[(.*?)\\\]""",
                    replaceString = "$$$1$$",
                ),
                // {{match}} 与 \n 转义
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "/(标题)/i",
                    replaceString = "【{{match}}】\\n",
                ),
            )
        )
        val input = "标题：\\[\\frac{a}{b}\\]"
        val output = input.replaceRegexes(
            assistant = assistant,
            scope = AssistantAffectScope.ASSISTANT,
            phase = AssistantRegexPhase.VISUAL,
        )
        assertEquals("【标题】\n：$$\\frac{a}{b}$$", output)
    }

    @Test
    fun `cached and uncached regex replacement give identical results in every phase`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "status",
                    findRegex = "/<status>[\\s\\S]*?<\\/status>/gi",
                    replaceString = "[状态]",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT, AssistantAffectScope.USER),
                ),
                AssistantRegex(
                    id = Uuid.random(),
                    name = "dollar",
                    findRegex = "(\\d+)元",
                    replaceString = "$$1",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            )
        )
        val texts = listOf("", "无匹配", "价格 100元", "<status>HP:10</status>后文 20元", "<STATUS>x</STATUS>")
        for (phase in AssistantRegexPhase.entries) {
            for (scope in AssistantAffectScope.entries) {
                for (text in texts) {
                    val uncached = text.replaceRegexes(assistant, scope, phase, useCache = false)
                    val first = text.replaceRegexes(assistant, scope, phase)
                    val second = text.replaceRegexes(assistant, scope, phase)
                    assertEquals("$phase $scope $text", uncached, first)
                    assertEquals("$phase $scope $text", uncached, second)
                }
            }
        }
    }

    @Test
    fun `regex result cache evicts least recently used entries by character budget`() {
        val cache = RegexResultCache(maxChars = 100)
        val rules = listOf(ActiveRegexRuleKey("a", "b"))
        val keys = "abcde".associateWith { RegexReplaceCacheKey(it.toString().repeat(30), rules) }

        // 未命中规则时替换结果就是原字符串本身（同一实例），只计 30 字
        "abc".forEach { cache.put(keys.getValue(it), keys.getValue(it).text) }
        assertEquals(90L, cache.totalChars())

        // 访问 a 使其变为最近使用，再写入 d：超出 100 字，淘汰最久未使用的 b
        assertEquals(keys.getValue('a').text, cache.get(keys.getValue('a')))
        cache.put(keys.getValue('d'), keys.getValue('d').text)
        assertEquals(null, cache.get(keys.getValue('b')))
        assertEquals(3, cache.size())
        assertEquals(90L, cache.totalChars())

        // 替换结果与原文不同时，原文与结果都计入（30 + 40）
        cache.put(keys.getValue('e'), "x".repeat(40))
        assertTrue(cache.totalChars() <= 100)
        assertEquals("x".repeat(40), cache.get(keys.getValue('e')))
    }
}
