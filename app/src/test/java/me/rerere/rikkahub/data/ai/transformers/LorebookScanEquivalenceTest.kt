package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGreenLightMode
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * 世界书扫描优化等价性测试：
 * 用随机生成的世界书与对话，逐条对比优化后实现与原实现（LegacyLorebookScan.kt）的输出
 * （激活条目、顺序、cleanContent、锚定位置必须完全一致）。
 *
 * 触发百分比只使用 0 与 100（两者都是确定性的），以便逐条比较。
 */
class LorebookScanEquivalenceTest {

    private val vocab = listOf(
        "苹果", "香蕉", "Cat", "cat", "CAT", "dog", "Dog", "红色", "蓝色", "run", "running",
        "剑", "魔法", "龙", "dragon", "测试", "hello", "world", "x_1", "猫咪", "城堡",
    )

    private val keywordPool = listOf(
        "苹果", "cat", "Cat", "dog", "红色", "run", "剑", "魔法", "龙", "dragon", "测试", "hello", "x_1", "城堡",
        "/ca+t/i", "/^dog/m", "/魔.法/", "/dragon$/", "/(/", "/hello world/s", "c.t", "([", "  run  ", "不存在的词",
    )

    @Before
    fun setUp() {
        LorebookScanCache.clear()
    }

    private fun Random.word() = vocab[nextInt(vocab.size)]

    private fun Random.text(minWords: Int = 0, maxWords: Int = 6): String {
        val count = nextInt(minWords, maxWords + 1)
        val sep = listOf(" ", "", "\n")[nextInt(3)]
        return (0 until count).joinToString(sep) { word() }
    }

    private fun Random.message(): UIMessage {
        val role = if (nextBoolean()) MessageRole.USER else MessageRole.ASSISTANT
        val parts: List<UIMessagePart> = when (nextInt(6)) {
            0 -> listOf(UIMessagePart.Reasoning(reasoning = text()), UIMessagePart.Text(text()))
            1 -> listOf(UIMessagePart.Text(text()), UIMessagePart.Text(text()))
            2 -> emptyList()
            else -> listOf(UIMessagePart.Text(text()))
        }
        return UIMessage(role = role, parts = parts)
    }

    private fun Random.keywords(max: Int): List<String> =
        (0 until nextInt(0, max + 1)).map { keywordPool[nextInt(keywordPool.size)] } +
            if (nextInt(10) == 0) listOf("   ") else emptyList()

    private fun Random.entry(): PromptInjection.RegexInjection {
        val decorator = when (nextInt(12)) {
            0 -> "@@activate\n"
            1 -> "@@dont_activate\n"
            2 -> "@@@escaped\n"
            else -> ""
        }
        val content = decorator + when (nextInt(5)) {
            0 -> ""
            1 -> "   "
            else -> text(1, 4)
        }
        return PromptInjection.RegexInjection(
            name = "e${nextInt(1000)}",
            enabled = nextInt(8) != 0,
            priority = listOf(0, 50, 100, 100, 100, 200)[nextInt(6)],
            position = InjectionPosition.entries[nextInt(InjectionPosition.entries.size)],
            content = content,
            injectDepth = listOf(0, 1, 2, 4, 9999)[nextInt(5)],
            keywords = keywords(3),
            secondaryKeys = if (nextInt(3) == 0) keywords(3) else emptyList(),
            selective = nextInt(4) != 0,
            selectiveLogic = SelectiveLogic.entries[nextInt(SelectiveLogic.entries.size)],
            useRegex = nextInt(6) == 0,
            caseSensitive = nextInt(4) == 0,
            overrideCaseSensitive = listOf(null, null, true, false)[nextInt(4)],
            matchWholeWords = listOf(null, null, true, false)[nextInt(4)],
            scanDepth = listOf(0, 1, 2, 4, 10)[nextInt(5)],
            overrideScanDepth = listOf(null, null, 1, 3)[nextInt(4)],
            constantActive = nextInt(6) == 0,
            probability = if (nextInt(8) == 0) 0 else 100,
            excludeRecursion = nextInt(5) == 0,
            preventRecursion = nextInt(5) == 0,
            delayUntilRecursion = nextInt(6) == 0,
        )
    }

    private fun Random.book(mode: LorebookGreenLightMode, entryCount: Int = nextInt(0, 14)): Lorebook = Lorebook(
        name = "book",
        greenLightMode = mode,
        defaultScanDepth = listOf(1, 2, 4, 8)[nextInt(4)],
        caseSensitive = nextInt(4) == 0,
        matchWholeWords = nextInt(3) == 0,
        recursiveScan = nextInt(4) != 0,
        maxRecursionSteps = listOf(0, 1, 3, 5)[nextInt(4)],
        entries = (0 until entryCount).map { entry() },
    )

    private fun assertSame(book: Lorebook, history: List<UIMessage>, label: String) {
        val expected = legacyScanLorebookForConversation(book, history)
        val actual = scanLorebookForConversation(book, history)
        assertEquals(label, expected, actual)
    }

    @Test
    fun `random books and histories produce identical results in all green light modes`() {
        repeat(600) { seed ->
            val r = Random(seed)
            val mode = LorebookGreenLightMode.entries[seed % LorebookGreenLightMode.entries.size]
            val book = r.book(mode)
            val history = (0 until r.nextInt(0, 30)).map { r.message() }
            assertSame(book, history, "seed=$seed mode=$mode")
        }
    }

    @Test
    fun `dense random books with heavy recursion overlap produce identical results`() {
        // 极小词表：消息、条目正文、关键字高度重叠，递归链与「同一步共同触发」组合大量出现
        val tiny = listOf("甲", "乙", "丙", "丁", "戊", "Ab", "ab")
        fun Random.tinyText(maxWords: Int) = (0 until nextInt(0, maxWords + 1)).joinToString(" ") { tiny[nextInt(tiny.size)] }
        val tinyKeys = tiny + listOf("/甲.乙/", "/ab/i", "甲 乙")
        repeat(400) { seed ->
            LorebookScanCache.clear()
            val r = Random(30_000 + seed)
            val mode = LorebookGreenLightMode.entries[seed % LorebookGreenLightMode.entries.size]
            val entries = (0 until r.nextInt(2, 10)).map {
                r.entry().copy(
                    enabled = true,
                    keywords = (0 until r.nextInt(1, 3)).map { tinyKeys[r.nextInt(tinyKeys.size)] },
                    secondaryKeys = if (r.nextInt(2) == 0) (0 until r.nextInt(1, 3)).map { tinyKeys[r.nextInt(tinyKeys.size)] } else emptyList(),
                    content = r.tinyText(3),
                    constantActive = r.nextInt(8) == 0,
                    probability = 100,
                )
            }
            val book = r.book(mode, entryCount = 0).copy(
                entries = entries,
                defaultScanDepth = listOf(1, 2)[r.nextInt(2)],
                recursiveScan = r.nextInt(5) != 0,
            )
            val history = (0 until r.nextInt(1, 40)).map { UIMessage.user(r.tinyText(2)) }
            for (n in 0..history.size) {
                assertSame(book, history.subList(0, n), "seed=$seed mode=$mode n=$n")
            }
        }
    }

    @Test
    fun `fold table defines exactly the String regionMatches ignoreCase char equality`() {
        val r = Random(7)
        val samples = (0 until 300).map { r.nextInt(0, 0x10000).toChar() }.filter { !it.isSurrogate() } +
            "甲测試\nαΣσςİıiIkKsSſµμßẞǅǄǆ\u212A\u0130\u0131\u03C2".toList()
        for (c in samples) {
            val cs = c.toString()
            for (code in 0..0xFFFF) {
                val d = code.toChar()
                if (d.isSurrogate()) continue
                val expected = cs.regionMatches(0, d.toString(), 0, 1, ignoreCase = true)
                if (expected != (foldCaseChar(c) == foldCaseChar(d))) {
                    throw AssertionError("U+%04X vs U+%04X".format(c.code, code))
                }
            }
        }
    }

    @Test
    fun `plain keyword fast path matches Kotlin contains ignoreCase on tricky characters`() {
        // 含代理对字符：德瑟雷特字母 𐐀/𐐨（有大小写）与 emoji（无大小写）
        val alphabet = listOf(
            "a", "A", "b", "B", "k", "K", "\u212A", "ſ", "s", "S", "İ", "ı", "i", "I", "Σ", "σ", "ς", "µ", "μ",
            "ß", "ẞ", "ǅ", "Ǆ", "ǆ", "甲", "乙", "\n", " ", "1", "_", "\uD801\uDC00", "\uD801\uDC28", "😀", "\uD801",
        )
        val r = Random(99)
        repeat(40_000) {
            val text = (0 until r.nextInt(0, 12)).joinToString("") { alphabet[r.nextInt(alphabet.size)] }
            val key = (0 until r.nextInt(1, 4)).joinToString("") { alphabet[r.nextInt(alphabet.size)] }
            val ignoreCase = r.nextBoolean()
            val matcher = KeywordMatcher.Plain(key, ignoreCase, id = -1)
            assertEquals("text=$text key=$key ic=$ignoreCase", text.contains(key, ignoreCase), matcher.matchesText(text))
        }
    }

    @Test
    fun `multi pattern keyword index reports exactly the keywords contained in each text`() {
        val alphabet = listOf("a", "b", "c", "A", "B", "k", "K", "\u212A", "ſ", "s", "S", "İ", "i", "I", "甲", "乙", " ", "\uD801\uDC00", "\uD801\uDC28", "😀")
        val r = Random(2024)
        repeat(3_000) {
            val registry = PlainMatcherRegistry()
            val matchers = (0 until r.nextInt(1, 25)).map {
                val key = (0 until r.nextInt(1, 5)).joinToString("") { alphabet[r.nextInt(alphabet.size)] }
                registry.plain(key, ignoreCase = r.nextBoolean())
            }.distinct()
            val index = PlainKeywordIndex(registry.indexedMatchers())
            repeat(5) {
                val text = (0 until r.nextInt(0, 30)).joinToString("") { alphabet[r.nextInt(alphabet.size)] }
                val bits = index.presence(text)
                for (m in matchers) {
                    val expected = text.contains(m.key, m.ignoreCase)
                    val actual = (bits[m.id ushr 6] and (1L shl (m.id and 63))) != 0L
                    assertEquals("text=$text key=${m.key} ic=${m.ignoreCase}", expected, actual)
                }
            }
        }
    }

    @Test
    fun `tricky case characters, newline keywords and whole words produce identical results`() {
        val tiny = listOf("Kelvin", "\u212Aelvin", "kelvin", "ſtar", "Star", "İzmir", "izmir", "ΣΟΦΙΑ", "σοφια", "甲", "乙", "cat_1", "Cat_1")
        val keys = tiny + listOf("甲\n乙", "Kelvin\nStar", "/k.lvin/i", "CAT_1", "σοφια")
        repeat(300) { seed ->
            LorebookScanCache.clear()
            val r = Random(40_000 + seed)
            fun words(max: Int) = (0 until r.nextInt(0, max + 1)).joinToString(listOf(" ", "\n", "")[r.nextInt(3)]) { tiny[r.nextInt(tiny.size)] }
            val entries = (0 until r.nextInt(2, 9)).map {
                r.entry().copy(
                    enabled = true,
                    keywords = (0 until r.nextInt(1, 3)).map { keys[r.nextInt(keys.size)] },
                    secondaryKeys = if (r.nextBoolean()) listOf(keys[r.nextInt(keys.size)]) else emptyList(),
                    content = words(3),
                    probability = 100,
                )
            }
            val book = r.book(LorebookGreenLightMode.entries[seed % 3], entryCount = 0).copy(entries = entries)
            val history = (0 until r.nextInt(1, 25)).map {
                if (r.nextInt(4) == 0) {
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(words(2)), UIMessagePart.Text(words(2))))
                } else {
                    UIMessage.assistant(words(3))
                }
            }
            for (n in 0..history.size) assertSame(book, history.subList(0, n), "seed=$seed n=$n")
        }
    }

    @Test
    fun `incremental conversation growth through the replay cache matches full replay at every length`() {
        repeat(40) { seed ->
            LorebookScanCache.clear()
            val r = Random(10_000 + seed)
            val mode = if (seed % 2 == 0) {
                LorebookGreenLightMode.STICKY_LOCKED_POSITION
            } else {
                LorebookGreenLightMode.STICKY_FLOATING
            }
            val book = r.book(mode, entryCount = r.nextInt(3, 16))
            val history = (0 until 150).map { r.message() }
            for (n in 0..history.size) {
                assertSame(book, history.subList(0, n), "seed=$seed n=$n")
            }
        }
    }

    @Test
    fun `edits, branch switches, truncation and book changes never reuse stale cache entries`() {
        repeat(30) { seed ->
            LorebookScanCache.clear()
            val r = Random(20_000 + seed)
            val book = r.book(LorebookGreenLightMode.STICKY_LOCKED_POSITION, entryCount = r.nextInt(4, 16))
            val history = (0 until 200).map { r.message() }.toMutableList()

            assertSame(book, history, "seed=$seed initial")

            // 编辑中间某条消息
            val editIdx = r.nextInt(history.size)
            history[editIdx] = r.message()
            assertSame(book, history, "seed=$seed edit@$editIdx")

            // 切换分支：替换最后两条
            history[history.lastIndex] = r.message()
            history[history.lastIndex - 1] = r.message()
            assertSame(book, history, "seed=$seed branch")

            // 删除中间一条
            history.removeAt(r.nextInt(history.size))
            assertSame(book, history, "seed=$seed delete")

            // 回到更短的历史（重新生成更早的回复）
            assertSame(book, history.subList(0, history.size / 2), "seed=$seed shorter")

            // 上下文截断点前移（开头整体后移）
            assertSame(book, history.subList(37, history.size), "seed=$seed truncated")

            // 修改世界书（新内容）：必须使用新的缓存项
            val changedBook = book.copy(entries = book.entries.map { it.copy(keywords = it.keywords + "苹果") })
            assertSame(changedBook, history, "seed=$seed changed book")
            assertSame(book, history, "seed=$seed original book again")

            // 另一段对话使用同一本世界书
            val other = (0 until 120).map { r.message() }
            assertSame(book, other, "seed=$seed other conversation")
            assertSame(book, history, "seed=$seed back to first conversation")
        }
    }

    @Test
    fun `replay cache only replays newly appended messages`() {
        val r = Random(42)
        val book = r.book(LorebookGreenLightMode.STICKY_LOCKED_POSITION, entryCount = 10)
            .let { it.copy(recursiveScan = true, entries = it.entries.map { e -> e.copy(enabled = true) }) }
        val history = (0 until 300).map { r.message() }

        scanLorebookForConversation(book, history.subList(0, 298))
        val before = LorebookScanCache.replayedSteps.get()
        // 追加两条消息（AI 回复 + 新的用户消息）
        scanLorebookForConversation(book, history)
        val replayed = LorebookScanCache.replayedSteps.get() - before
        assertTrue("replayed=$replayed", replayed <= 3)

        // 工具循环中对话历史不变：只需重新计算最后一步
        val before2 = LorebookScanCache.replayedSteps.get()
        scanLorebookForConversation(book, history)
        assertEquals(1L, LorebookScanCache.replayedSteps.get() - before2)
    }

    @Test
    fun `recursion depending on entries triggered together in the same step is preserved`() {
        // B 需要 A 与 C 的正文同时出现（AND_ALL）才能被递归激活；A 与 C 只有在同一步同时被对话触发时才会一起作为递归来源
        val a = PromptInjection.RegexInjection(name = "A", keywords = listOf("钥匙A"), content = "线索甲")
        val c = PromptInjection.RegexInjection(name = "C", keywords = listOf("钥匙C"), content = "线索乙")
        val b = PromptInjection.RegexInjection(
            name = "B",
            keywords = listOf("线索甲"),
            secondaryKeys = listOf("线索乙"),
            selective = true,
            selectiveLogic = SelectiveLogic.AND_ALL,
            content = "隐藏设定",
        )
        val book = Lorebook(
            greenLightMode = LorebookGreenLightMode.STICKY_LOCKED_POSITION,
            defaultScanDepth = 1,
            recursiveScan = true,
            entries = listOf(a, b, c),
        )
        val histories = listOf(
            listOf(UIMessage.user("钥匙A"), UIMessage.assistant("..."), UIMessage.user("钥匙C")),
            listOf(UIMessage.user("钥匙A"), UIMessage.assistant("钥匙A 钥匙C"), UIMessage.user("无")),
        )
        for (h in histories) {
            for (n in 0..h.size) assertSame(book, h.subList(0, n), "n=$n")
        }
    }
}
