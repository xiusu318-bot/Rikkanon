package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPhase
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.replaceLorebookRegexes
import me.rerere.rikkahub.data.model.replaceRegexes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 助手正则深度（对齐酒馆：0 = 最后一条消息）与「世界书」作用范围
 */
class AssistantRegexDepthTest {

    private fun regex(
        find: String = "x",
        replace: String = "y",
        scopes: Set<AssistantAffectScope> = setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT),
        minDepth: Int? = null,
        maxDepth: Int? = null,
        visualOnly: Boolean = true,
        requestOnly: Boolean = true,
    ) = AssistantRegex(
        id = Uuid.random(),
        findRegex = find,
        replaceString = replace,
        affectingScope = scopes,
        minDepth = minDepth,
        maxDepth = maxDepth,
        visualOnly = visualOnly,
        requestOnly = requestOnly,
    )

    @Test
    fun `new regex defaults to unlimited depth`() {
        val r = AssistantRegex(id = Uuid.random())
        assertNull(r.minDepth)
        assertNull(r.maxDepth)
        for (d in listOf(0, 1, 5, 9999)) assertTrue(r.matchesDepth(d))
        assertTrue(r.matchesDepth(null))
    }

    @Test
    fun `min and max depth follow SillyTavern semantics`() {
        val r = regex(minDepth = 2, maxDepth = 4)
        assertFalse(r.matchesDepth(0))
        assertFalse(r.matchesDepth(1))
        assertTrue(r.matchesDepth(2))
        assertTrue(r.matchesDepth(4))
        assertFalse(r.matchesDepth(5))
        // 没有深度概念的文本不做限制
        assertTrue(r.matchesDepth(null))

        val assistant = Assistant(regexes = listOf(r))
        for (phase in AssistantRegexPhase.entries) {
            if (!r.matchesPhase(phase)) continue
            assertEquals("x", "x".replaceRegexes(assistant, AssistantAffectScope.USER, phase, depth = 1))
            assertEquals("y", "x".replaceRegexes(assistant, AssistantAffectScope.USER, phase, depth = 3))
            assertEquals("y", "x".replaceRegexes(assistant, AssistantAffectScope.USER, phase))
            // 走缓存与不走缓存结果一致（深度过滤发生在缓存键计算之前）
            assertEquals("x", "x".replaceRegexes(assistant, AssistantAffectScope.USER, phase, useCache = false, depth = 5))
            assertEquals("x", "x".replaceRegexes(assistant, AssistantAffectScope.USER, phase, depth = 5))
        }
    }

    @Test
    fun `request regex depth counts only real chat messages from the end`() = runTest {
        val assistant = Assistant(regexes = listOf(regex(minDepth = 1, maxDepth = 1, visualOnly = false)))
        val ctx = TransformerContext(
            context = ContextWrapper(null),
            model = Model(modelId = "test", displayName = "Test"),
            assistant = assistant,
            settings = Settings(),
        )
        val messages = listOf(
            UIMessage.system("sys x"),
            UIMessage.user("u1 x"),                               // depth 3
            UIMessage.assistant("a1 x"),                          // depth 2
            UIMessage.user("u2 x"),                               // depth 1
            UIMessage.user("reminder x").copy(isSynthetic = true), // 合成块不计深度、不替换
            UIMessage.assistant("a2 x"),                          // depth 0
        )
        assertEquals(listOf(null, 3, 2, 1, null, 0), computeRegexDepths(messages).toList())

        val result = RegexInputTransformer.transform(ctx, messages)
        assertEquals(
            listOf("sys x", "u1 x", "a1 x", "u2 y", "reminder x", "a2 x"),
            result.map { it.toText() },
        )

        // 最小深度 2：最近两条保留原文
        val ctx2 = TransformerContext(
            context = ContextWrapper(null),
            model = Model(modelId = "test", displayName = "Test"),
            assistant = Assistant(regexes = listOf(regex(minDepth = 2, visualOnly = false))),
            settings = Settings(),
        )
        assertEquals(
            listOf("sys x", "u1 y", "a1 y", "u2 x", "reminder x", "a2 x"),
            RegexInputTransformer.transform(ctx2, messages).map { it.toText() },
        )
    }

    @Test
    fun `lorebook scope applies to request and persist rules but not visual only rules`() {
        val requestRule = Assistant(regexes = listOf(regex(scopes = setOf(AssistantAffectScope.LOREBOOK), visualOnly = false, requestOnly = true)))
        val persistRule = Assistant(regexes = listOf(regex(scopes = setOf(AssistantAffectScope.LOREBOOK), visualOnly = false, requestOnly = false)))
        val visualRule = Assistant(regexes = listOf(regex(scopes = setOf(AssistantAffectScope.LOREBOOK), visualOnly = true, requestOnly = false)))
        val defaultRule = Assistant(regexes = listOf(regex(scopes = setOf(AssistantAffectScope.LOREBOOK))))
        val chatOnlyRule = Assistant(regexes = listOf(regex(scopes = setOf(AssistantAffectScope.USER, AssistantAffectScope.ASSISTANT))))

        assertEquals("y", "x".replaceLorebookRegexes(requestRule, depth = null))
        assertEquals("y", "x".replaceLorebookRegexes(persistRule, depth = null))
        assertEquals("y", "x".replaceLorebookRegexes(defaultRule, depth = null))
        assertEquals("x", "x".replaceLorebookRegexes(visualRule, depth = null))
        assertEquals("x", "x".replaceLorebookRegexes(chatOnlyRule, depth = null))

        // 世界书范围的规则不作用于聊天消息
        assertEquals("x", "x".replaceRegexes(defaultRule, AssistantAffectScope.USER, AssistantRegexPhase.REQUEST, depth = 0))
    }

    @Test
    fun `lorebook regex uses entry at-depth and leaves non depth entries unrestricted`() {
        val loreId = Uuid.random()
        val lorebook = Lorebook(
            id = loreId,
            enabled = true,
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "before",
                    constantActive = true,
                    position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                    content = "[before x]",
                ),
                PromptInjection.RegexInjection(
                    name = "d1",
                    constantActive = true,
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 1,
                    role = MessageRole.SYSTEM,
                    content = "[d1 x]",
                ),
                PromptInjection.RegexInjection(
                    name = "d4",
                    constantActive = true,
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 4,
                    role = MessageRole.SYSTEM,
                    content = "[d4 x]",
                ),
            ),
        )
        assertEquals(1, lorebookRegexDepth(lorebook.entries[1]))
        assertNull(lorebookRegexDepth(lorebook.entries[0]))

        val assistant = Assistant(
            lorebookIds = setOf(loreId),
            regexes = listOf(regex(scopes = setOf(AssistantAffectScope.LOREBOOK), minDepth = 2)),
        )
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("u1 x"),
            UIMessage.assistant("a1 x"),
            UIMessage.user("u2 x"),
            UIMessage.assistant("a2 x"),
            UIMessage.user("u3 x"),
        )
        val text = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        ).joinToString("\n") { it.toText() }

        assertTrue(text, "[before y]" in text) // 非 @D 条目不受深度限制
        assertTrue(text, "[d1 x]" in text)     // @D 1 < 最小深度 2，跳过
        assertTrue(text, "[d4 y]" in text)     // @D 4 >= 2，替换
        // 聊天消息不受世界书范围规则影响
        assertTrue(text, "u1 x" in text && "u3 x" in text)
    }
}
