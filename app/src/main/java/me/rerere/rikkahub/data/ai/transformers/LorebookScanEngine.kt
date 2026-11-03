package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGreenLightMode
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.model.compileRegexWithOptionsCached
import me.rerere.rikkahub.data.model.parseSlashRegexKeyword
import me.rerere.rikkahub.data.model.parseWorldInfoContentDecorators
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/*
 * 世界书扫描引擎
 *
 * 绿灯激活状态始终由「当前对话历史」从头重放推算得出（无状态设计，不维护任何持久化激活标记）：
 * 编辑 / 删除 / 切换分支 / 重新生成 / 修改正则或世界书 / 上下文压缩后，结果都自然正确。
 *
 * 在保持与逐步重放算法逐条一致（激活集合、激活顺序、锚定位置完全相同，见 LorebookScanEquivalenceTest）的前提下：
 * 1. 每个条目只预处理一次（装饰器、去空关键字、关键字匹配器），每条消息的文本只提取一次；
 * 2. 全部条目都已激活后不再继续重放；递归无法再激活新条目时只扫描未激活条目；
 * 3. 不含换行的普通关键字不可能跨越「\n」拼接边界，因此「窗口 / 递归来源文本是否包含」等价于「其中某一段是否包含」：
 *    用 Aho-Corasick 多模式匹配对每条消息、每个条目正文只扫描一遍，得到其包含的关键字位图；
 *    窗口 / 递归来源的判定 = 各段位图按位或后查位。正则类关键字（及含换行的关键字）仍对完整拼接文本匹配；
 * 4. 不区分大小写匹配：String.contains(ignoreCase = true) 的字符相等关系等价于「逐字符 lowercase(uppercase(c)) 相等」，
 *    因此把文本与关键字各折叠一次后用原生 indexOf 匹配（关键字含代理对字符时保留原写法）；
 * 5. 仅存于内存的临时重放缓存 [LorebookScanCache]：按「世界书内容 + 对话前缀内容哈希」保存重放中间结果，
 *    下一次请求从最长的相同前缀处继续重放，只计算新增消息。缓存丢失 / 未命中时退化为从头重放，结果完全相同。
 */

/**
 * 扫描单本世界书在当前对话历史下的激活条目与锚定位置
 */
internal fun scanLorebookForConversation(
    book: Lorebook,
    chatHistory: List<UIMessage>,
): List<ActivatedLoreEntry> {
    if (book.entries.none { it.enabled }) return emptyList()

    val bookCache = LorebookScanCache.forBook(book)
    val session = LoreScanSession(
        book = book,
        bookCache = bookCache,
        texts = chatHistory.map { it.scanText() },
    )

    return when (book.greenLightMode) {
        LorebookGreenLightMode.DYNAMIC_NATIVE -> {
            // 酒馆原生绿灯：仅扫描当前最新窗口
            session.scanStep(
                candidates = bookCache.prepared,
                prefixLen = session.texts.size,
                rollProbability = true,
            ).map { ActivatedLoreEntry(entry = it.entry, cleanContent = it.cleanContent, anchoredChatInsertIndex = null) }
        }

        LorebookGreenLightMode.STICKY_FLOATING,
        LorebookGreenLightMode.STICKY_LOCKED_POSITION -> stickyScan(
            session = session,
            bookCache = bookCache,
            lockPosition = book.greenLightMode == LorebookGreenLightMode.STICKY_LOCKED_POSITION,
        )
    }
}

/**
 * 触发后持续至下次压缩（STICKY_LOCKED_POSITION 额外锁定首次触发时的注入位置）：
 * 依次在每个对话前缀 [0..step] 上执行一次完整扫描，记录每个条目首次被触发的步骤；
 * 仅最后一步（最新对话）执行触发百分比掷骰。
 */
private fun stickyScan(
    session: LoreScanSession,
    bookCache: BookScanCache,
    lockPosition: Boolean,
): List<ActivatedLoreEntry> {
    val n = session.texts.size

    if (n == 0) {
        // 对话为空也执行一次空窗口扫描，以激活常驻蓝灯或空匹配正则
        val state = LinkedHashMap<Uuid, LoreActiveState>()
        session.applyStickyStep(state, prefixLen = 0, rollProbability = true, lockPosition = lockPosition)
        return session.finalizeSticky(state)
    }

    // hashes[k] 标识对话前 k 条消息的文本内容（前缀链式哈希）
    val hashes = LongArray(n + 1)
    hashes[0] = PREFIX_HASH_SEED
    for (i in 0 until n) {
        hashes[i + 1] = chainPrefixHash(hashes[i], session.texts[i])
    }

    // 快照语义：snapshot(k) = 已用「不掷骰」方式重放完步骤 0..k-1 后的激活状态
    val lastNonRollStep = n - 1
    val resume = bookCache.findSnapshot(maxStepCount = lastNonRollStep, hashes = hashes)
    val state = LinkedHashMap<Uuid, LoreActiveState>()
    var startStep = 0
    if (resume != null) {
        startStep = resume.stepCount
        resume.activated.forEach { state[it.prepared.id] = it }
    }
    LorebookScanCache.replayedSteps.addAndGet((n - startStep).toLong())

    for (step in startStep until lastNonRollStep) {
        session.applyStickyStep(state, prefixLen = step + 1, rollProbability = false, lockPosition = lockPosition)
        val done = step + 1
        if (done % LorebookScanCache.CHECKPOINT_INTERVAL == 0 && done < lastNonRollStep) {
            bookCache.putSnapshot(done, hashes[done], state.values.toList())
        }
    }
    if (lastNonRollStep > 0) {
        bookCache.putSnapshot(lastNonRollStep, hashes[lastNonRollStep], state.values.toList())
    }

    // 最后一步（最新对话）执行掷骰
    session.applyStickyStep(state, prefixLen = n, rollProbability = true, lockPosition = lockPosition)
    return session.finalizeSticky(state)
}

/**
 * 激活状态（仅在扫描内部与临时缓存中使用）
 */
internal class LoreActiveState(
    val prepared: PreparedLoreEntry,
    val anchoredChatInsertIndex: Int?,
)

// ==================== 匹配上下文 ====================

/**
 * 关键字匹配所面对的文本：可能是单段文本，也可能是若干段以「\n」拼接的文本（消息窗口 / 递归来源）。
 */
internal interface MatchContext {
    /** 不含换行的普通关键字是否出现在该文本中（可逐段判定） */
    fun containsPlain(matcher: KeywordMatcher.Plain): Boolean

    /** 完整拼接文本（正则类关键字使用） */
    fun fullText(): String
}

private class TextMatchContext(private val text: String) : MatchContext {
    override fun containsPlain(matcher: KeywordMatcher.Plain): Boolean = matcher.matchesText(text)
    override fun fullText(): String = text
}

/** 对话前缀中 [from, to) 区间的消息窗口 */
private class WindowMatchContext(
    private val session: LoreScanSession,
    private val from: Int,
    private val to: Int,
) : MatchContext {
    private var joined: String? = null
    private var presence: LongArray? = null

    override fun containsPlain(matcher: KeywordMatcher.Plain): Boolean {
        val bits = presence ?: session.windowPresence(from, to).also { presence = it }
        return bits.hasBit(matcher.id)
    }

    override fun fullText(): String = joined ?: session.joinTexts(from, to).also { joined = it }
}

/** 递归来源：若干个条目正文以「\n」拼接 */
private class SourcesMatchContext(
    private val sources: List<PreparedLoreEntry>,
    private val bookCache: BookScanCache,
) : MatchContext {
    private var joined: String? = null
    private var presence: LongArray? = null

    override fun containsPlain(matcher: KeywordMatcher.Plain): Boolean {
        val bits = presence ?: LongArray(bookCache.plainIndex.words).also { union ->
            for (source in sources) union.orWith(bookCache.contentPresence(source))
            presence = union
        }
        return bits.hasBit(matcher.id)
    }

    override fun fullText(): String = joined ?: sources.joinToString("\n") { it.cleanContent }.also { joined = it }
}

// ==================== 单次扫描会话 ====================

/**
 * 单次扫描会话：持有本次请求的消息文本与本书预处理条目
 */
private class LoreScanSession(
    val book: Lorebook,
    val bookCache: BookScanCache,
    val texts: List<String>,
) {
    private val prepared = bookCache.prepared
    private val plainIndex = bookCache.plainIndex
    private val hasAnyRecursionSource = book.recursiveScan && prepared.any { it.isRecursionSource }

    // 每条消息包含的普通关键字位图（每条消息只扫描一遍）
    private val messagePresence = arrayOfNulls<LongArray>(texts.size)

    // 「递归来源条目序列 -> 各候选条目是否命中」，仅用于不掷骰的步骤（常驻蓝灯每步都产生相同的递归来源）
    private val recursionMemo = HashMap<List<Int>, ByteArray>()

    private fun presenceOf(index: Int): LongArray =
        messagePresence[index] ?: plainIndex.presence(texts[index]).also { messagePresence[index] = it }

    /** 消息 [from, to) 的关键字位图之并 */
    fun windowPresence(from: Int, to: Int): LongArray {
        if (to - from == 1) return presenceOf(from)
        val union = LongArray(plainIndex.words)
        for (i in from until to) union.orWith(presenceOf(i))
        return union
    }

    fun joinTexts(from: Int, to: Int): String {
        if (to - from == 1) return texts[from]
        return texts.subList(from, to).joinToString("\n")
    }

    /** 前 [prefixLen] 条消息中最近 [depth] 条构成的窗口（等价于 extractContextForMatching(prefix, depth)） */
    private fun window(prefixLen: Int, depth: Int): MatchContext {
        if (prefixLen <= 0 || depth <= 0) return EMPTY_CONTEXT
        return WindowMatchContext(this, (prefixLen - depth).coerceAtLeast(0), prefixLen)
    }

    /**
     * 在前 [prefixLen] 条消息构成的快照下执行一次完整的世界书扫描（含多轮递归扫描），
     * 返回按触发顺序排列的条目（与原 runSingleTurnLorebookScan 完全一致）。
     */
    fun scanStep(
        candidates: List<PreparedLoreEntry>,
        prefixLen: Int,
        rollProbability: Boolean,
    ): List<PreparedLoreEntry> {
        val activatedIds = HashSet<Uuid>()
        val activated = ArrayList<PreparedLoreEntry>()
        val contextByDepth = HashMap<Int, MatchContext>()

        // 第 0 轮：扫描聊天记录（delayUntilRecursion = true 的条目在首轮跳过）
        for (entry in candidates) {
            if (entry.skipInFirstRound) continue
            val chatContext = contextByDepth.getOrPut(entry.scanDepth) { window(prefixLen, entry.scanDepth) }
            if (entry.isTriggered(chatContext, rollProbability) && activatedIds.add(entry.id)) {
                activated.add(entry)
            }
        }

        // 递归扫描阶段
        if (book.recursiveScan && activated.isNotEmpty()) {
            val maxSteps = if (book.maxRecursionSteps <= 0) 10 else book.maxRecursionSteps
            var newlyActivated: List<PreparedLoreEntry> = activated.toList()
            var step = 0
            while (step < maxSteps && newlyActivated.isNotEmpty()) {
                // 递归来源 = 本轮新激活、未设置「防止进一步递归」且正文非空白的条目（与原实现拼接文本的过滤条件一致）
                val sources = newlyActivated.filter { it.isRecursionSource }
                if (sources.isEmpty()) break
                val sourceContext = SourcesMatchContext(sources, bookCache)

                val memo = if (rollProbability) {
                    null
                } else {
                    recursionMemo.getOrPut(sources.map { it.index }) { ByteArray(prepared.size) }
                }
                val nextBatch = ArrayList<PreparedLoreEntry>()
                for (candidate in candidates) {
                    if (candidate.id in activatedIds) continue
                    if (candidate.entry.excludeRecursion) continue
                    val hit = if (memo == null) {
                        candidate.isTriggered(sourceContext, rollProbability)
                    } else {
                        when (memo[candidate.index]) {
                            HIT -> true
                            MISS -> false
                            else -> candidate.isTriggered(sourceContext, false).also {
                                memo[candidate.index] = if (it) HIT else MISS
                            }
                        }
                    }
                    if (hit) {
                        activatedIds.add(candidate.id)
                        activated.add(candidate)
                        nextBatch.add(candidate)
                    }
                }
                newlyActivated = nextBatch
                step++
            }
        }

        return activated
    }

    /**
     * 执行持续绿灯的一步：在前 [prefixLen] 条消息下扫描，并把首次触发的条目追加进 [state]
     */
    fun applyStickyStep(
        state: LinkedHashMap<Uuid, LoreActiveState>,
        prefixLen: Int,
        rollProbability: Boolean,
        lockPosition: Boolean,
    ) {
        val remaining = prepared.filter { !state.containsKey(it.id) }
        // 全部条目都已激活：本步不可能再新增任何条目
        if (remaining.isEmpty()) return

        // 开启递归时，已激活条目仍需参与扫描以充当本步的递归来源；
        // 但若剩余未激活条目都不可被递归激活（或本书没有任何可作为递归来源的条目），递归不会带来新条目，只需扫描未激活条目
        val candidates = if (hasAnyRecursionSource && remaining.any { !it.entry.excludeRecursion }) {
            prepared
        } else {
            remaining
        }

        val triggered = scanStep(candidates, prefixLen, rollProbability)
        for (entry in triggered) {
            if (state.containsKey(entry.id)) continue
            val anchoredIdx = if (lockPosition) {
                computeDepthInsertIndex(
                    chatSize = prefixLen,
                    position = entry.entry.position,
                    depth = entry.entry.injectDepth,
                )
            } else {
                null
            }
            state[entry.id] = LoreActiveState(entry, anchoredIdx)
        }
    }

    /**
     * 最终复核：配置了 NOT_ANY / NOT_ALL 排除过滤器的条目，若当前最新扫描窗口命中排除词则本轮不激活
     */
    fun finalizeSticky(state: LinkedHashMap<Uuid, LoreActiveState>): List<ActivatedLoreEntry> {
        val n = texts.size
        val contextByDepth = HashMap<Int, MatchContext>()
        return state.values
            .filter { item ->
                val p = item.prepared
                if (!p.hasExclusionFilter) {
                    true
                } else {
                    val latestContext = contextByDepth.getOrPut(p.scanDepth) { window(n, p.scanDepth) }
                    !p.isExcludedBy(latestContext)
                }
            }
            .map {
                ActivatedLoreEntry(
                    entry = it.prepared.entry,
                    cleanContent = it.prepared.cleanContent,
                    anchoredChatInsertIndex = it.anchoredChatInsertIndex,
                )
            }
    }

    private companion object {
        val EMPTY_CONTEXT: MatchContext = TextMatchContext("")
    }
}

private const val HIT: Byte = 1
private const val MISS: Byte = 2

// ==================== 预处理条目与关键字匹配器 ====================

/**
 * 预处理后的世界书条目：装饰器、生效扫描深度、大小写 / 完整单词设置与关键字匹配器只计算一次。
 * 触发判定与 [me.rerere.rikkahub.data.model.isTriggered] / matchSingleKeyword 逐分支一致。
 */
internal class PreparedLoreEntry(
    val entry: PromptInjection.RegexInjection,
    val index: Int,
    book: Lorebook,
    registry: PlainMatcherRegistry,
) {
    val id: Uuid = entry.id
    private val decorators = parseWorldInfoContentDecorators(entry.content)
    val cleanContent: String = decorators.cleanContent

    val scanDepth: Int = (entry.overrideScanDepth ?: entry.scanDepth.takeIf { it > 0 } ?: book.defaultScanDepth)
        .coerceAtLeast(1)

    private val effectiveCaseSensitive = entry.overrideCaseSensitive ?: (entry.caseSensitive || book.caseSensitive)
    private val effectiveWholeWords = entry.matchWholeWords ?: book.matchWholeWords

    private val primary: List<KeywordMatcher> = entry.keywords
        .filter { it.isNotBlank() }
        .map { prepareKeywordMatcher(it, entry.useRegex, effectiveCaseSensitive, effectiveWholeWords, registry) }
    private val secondary: List<KeywordMatcher> = entry.secondaryKeys
        .filter { it.isNotBlank() }
        .map { prepareKeywordMatcher(it, entry.useRegex, effectiveCaseSensitive, effectiveWholeWords, registry) }

    val skipInFirstRound: Boolean = entry.delayUntilRecursion && !entry.constantActive
    val isRecursionSource: Boolean = !entry.preventRecursion && cleanContent.isNotBlank()
    val hasExclusionFilter: Boolean = entry.selective && secondary.isNotEmpty() &&
        (entry.selectiveLogic == SelectiveLogic.NOT_ANY || entry.selectiveLogic == SelectiveLogic.NOT_ALL)

    fun isTriggered(context: MatchContext, rollProbability: Boolean): Boolean {
        if (!entry.enabled) return false
        if (decorators.forceDontActivate) return false
        if (decorators.forceActivate) return true

        if (rollProbability && entry.probability in 0..99) {
            if (entry.probability <= 0) return false
            if (kotlin.random.Random.nextInt(100) >= entry.probability) return false
        }

        // 🔵 蓝灯常驻：默认直接激活；配置了排除逻辑（NOT_ANY / NOT_ALL）的次要关键字时依然生效
        if (entry.constantActive) {
            if (entry.selective && secondary.isNotEmpty()) {
                return when (entry.selectiveLogic) {
                    SelectiveLogic.NOT_ANY -> secondary.none { it.matches(context) }
                    SelectiveLogic.NOT_ALL -> !secondary.all { it.matches(context) }
                    else -> true
                }
            }
            return true
        }

        // 🟢 绿灯条件触发：必须先命中至少一个主关键字
        if (primary.isEmpty()) return false
        if (primary.none { it.matches(context) }) return false

        if (!entry.selective || secondary.isEmpty()) return true
        return when (entry.selectiveLogic) {
            SelectiveLogic.AND_ANY -> secondary.any { it.matches(context) }
            SelectiveLogic.AND_ALL -> secondary.all { it.matches(context) }
            SelectiveLogic.NOT_ANY -> secondary.none { it.matches(context) }
            SelectiveLogic.NOT_ALL -> !secondary.all { it.matches(context) }
        }
    }

    /** 当前窗口是否命中排除过滤器（与原 isExcludedByCurrentWindow 一致） */
    fun isExcludedBy(context: MatchContext): Boolean = when (entry.selectiveLogic) {
        SelectiveLogic.NOT_ANY -> secondary.any { it.matches(context) }
        SelectiveLogic.NOT_ALL -> secondary.all { it.matches(context) }
        else -> false
    }
}

internal sealed class KeywordMatcher {
    abstract fun matches(context: MatchContext): Boolean

    object Never : KeywordMatcher() {
        override fun matches(context: MatchContext): Boolean = false
    }

    /** /pattern/flags 或 useRegex：对完整拼接文本匹配 */
    class RegexKeyword(private val regex: Regex) : KeywordMatcher() {
        override fun matches(context: MatchContext): Boolean =
            runCatching { regex.containsMatchIn(context.fullText()) }.getOrDefault(false)
    }

    /** 普通文本包含（context.contains(key, ignoreCase)） */
    class Plain(
        val key: String,
        val ignoreCase: Boolean,
        /** 在本书普通关键字中的编号；-1 表示含换行，不可逐段判定 */
        val id: Int,
    ) : KeywordMatcher() {
        // 不区分大小写时使用的折叠关键字；关键字含代理对字符时为 null（保留原写法，避免与平台的码点比较规则不一致）
        val foldedKey: String? = if (ignoreCase && key.none { it.isSurrogate() }) foldCase(key) else null

        /** 等价于 text.contains(key, ignoreCase) */
        fun matchesText(text: String): Boolean = matchesPrepared(text) { foldCase(text) }

        /** 等价于 text.contains(key, ignoreCase)；[folded] 提供 text 的大小写折叠结果（可缓存） */
        inline fun matchesPrepared(text: String, folded: () -> String): Boolean = when {
            !ignoreCase -> text.contains(key)
            foldedKey == null -> text.contains(key, ignoreCase = true)
            else -> folded().contains(foldedKey)
        }

        override fun matches(context: MatchContext): Boolean =
            if (id >= 0) context.containsPlain(this) else matchesText(context.fullText())
    }

    /** 完整单词：先做普通包含预筛，再用 \bkey\b 正则确认 */
    class WholeWord(private val prefilter: Plain, private val regex: Regex?) : KeywordMatcher() {
        override fun matches(context: MatchContext): Boolean {
            if (!prefilter.matches(context)) return false
            val compiled = regex ?: return false
            return runCatching { compiled.containsMatchIn(context.fullText()) }.getOrDefault(false)
        }
    }
}

/** 为本书的普通关键字分配编号（相同关键字 + 大小写设置共用一个编号与判定结果） */
internal class PlainMatcherRegistry {
    private val matchers = HashMap<Pair<String, Boolean>, KeywordMatcher.Plain>()

    /** 可逐段判定的普通关键字数量 */
    var size: Int = 0
        private set

    fun plain(key: String, ignoreCase: Boolean): KeywordMatcher.Plain = matchers.getOrPut(key to ignoreCase) {
        // 含换行的关键字可能跨越拼接边界，不可逐段判定
        val id = if ('\n' in key) -1 else size++
        KeywordMatcher.Plain(key, ignoreCase, id)
    }

    /** 可逐段判定的普通关键字（按编号排列） */
    fun indexedMatchers(): List<KeywordMatcher.Plain> = matchers.values.filter { it.id >= 0 }.sortedBy { it.id }
}

/**
 * 与 matchSingleKeyword 的分支一一对应，只是把与上下文无关的解析 / 编译提前到预处理阶段
 */
private fun prepareKeywordMatcher(
    rawKey: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    matchWholeWords: Boolean,
    registry: PlainMatcherRegistry,
): KeywordMatcher {
    val key = rawKey.trim()
    if (key.isEmpty()) return KeywordMatcher.Never

    // 1. /pattern/flags 语法
    parseSlashRegexKeyword(key)?.let { return KeywordMatcher.RegexKeyword(it) }

    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)

    // 2. 显式开启了 useRegex
    if (useRegex) {
        val compiled = compileRegexWithOptionsCached(key, options) ?: return KeywordMatcher.Never
        return KeywordMatcher.RegexKeyword(compiled)
    }

    // 3. 普通文本匹配（可选完整单词匹配）
    val plain = registry.plain(key, ignoreCase = !caseSensitive)
    if (matchWholeWords && key.all { it.isLetterOrDigit() || it == '_' }) {
        return KeywordMatcher.WholeWord(
            prefilter = plain,
            regex = compileRegexWithOptionsCached("\\b${Regex.escape(key)}\\b", options),
        )
    }
    return plain
}

/**
 * 大小写折叠表：FOLD[c] = lowercase(uppercase(c))。
 *
 * String.contains(key, ignoreCase = true) 最终调用 String.regionMatches(ignoreCase = true)，两个字符被视为相等当且仅当
 * c1 == c2 || upper(c1) == upper(c2) || lower(upper(c1)) == lower(upper(c2))，
 * 这恰好等价于 FOLD[c1] == FOLD[c2]，因此「折叠后区分大小写包含」与「不区分大小写包含」结果相同。
 */
private val FOLD: CharArray by lazy {
    CharArray(65536) { code -> code.toChar().uppercaseChar().lowercaseChar() }
}

internal fun foldCaseChar(c: Char): Char = FOLD[c.code]

/** 逐字符大小写折叠（长度不变）；没有字符需要改变时返回原字符串 */
internal fun foldCase(text: String): String {
    val fold = FOLD
    var i = 0
    while (i < text.length && fold[text[i].code] == text[i]) i++
    if (i == text.length) return text
    val chars = text.toCharArray()
    while (i < chars.size) {
        chars[i] = fold[chars[i].code]
        i++
    }
    return String(chars)
}

/** 与 [UIMessage.toText] 结果一致，单个文本块时直接复用原字符串 */
private fun UIMessage.scanText(): String {
    val single = parts.singleOrNull()
    if (single is UIMessagePart.Text) return single.text
    return toText()
}

// ==================== 多模式匹配索引 ====================

/**
 * 本书普通关键字的多模式匹配索引：对一段文本扫描一遍，得到其包含的关键字位图（bit = matcher.id）。
 * - 区分大小写的关键字：在原文上匹配；
 * - 不区分大小写的关键字：折叠后的关键字在折叠后的原文上匹配（与 contains(ignoreCase = true) 等价）；
 * - 不区分大小写且含代理对字符的关键字：直接用 contains(ignoreCase = true) 判定。
 * 结果与逐个调用 [KeywordMatcher.Plain.matchesText] 完全一致。
 */
internal class PlainKeywordIndex(matchers: List<KeywordMatcher.Plain>) {
    val words: Int = (matchers.size + 63) / 64

    private val exact: AhoCorasick?
    private val folded: AhoCorasick?
    private val direct: List<KeywordMatcher.Plain>

    init {
        val exactPatterns = ArrayList<Pair<String, Int>>()
        val foldedPatterns = ArrayList<Pair<String, Int>>()
        val directList = ArrayList<KeywordMatcher.Plain>()
        for (m in matchers) {
            when {
                !m.ignoreCase -> exactPatterns += m.key to m.id
                m.foldedKey != null -> foldedPatterns += m.foldedKey to m.id
                else -> directList += m
            }
        }
        exact = if (exactPatterns.isEmpty()) null else AhoCorasick(exactPatterns)
        folded = if (foldedPatterns.isEmpty()) null else AhoCorasick(foldedPatterns)
        direct = directList
    }

    fun presence(text: String): LongArray {
        val bits = LongArray(words)
        exact?.scan(text, bits)
        folded?.scan(foldCase(text), bits)
        for (m in direct) {
            if (m.matchesText(text)) bits.setBit(m.id)
        }
        return bits
    }
}

/**
 * Aho-Corasick 多模式子串匹配（报告所有出现在文本中的模式编号）
 */
private class AhoCorasick(patterns: List<Pair<String, Int>>) {
    private val children = ArrayList<HashMap<Char, Int>?>()
    private val fail: IntArray
    private val outputs = ArrayList<IntArray?>()
    // 沿失配链最近的「有输出」节点（-1 表示没有）
    private val dictLink: IntArray

    init {
        children.add(null)
        outputs.add(null)
        for ((pattern, id) in patterns) {
            var node = 0
            for (c in pattern) {
                val map = children[node] ?: HashMap<Char, Int>(4).also { children[node] = it }
                node = map.getOrPut(c) {
                    children.add(null)
                    outputs.add(null)
                    children.size - 1
                }
            }
            outputs[node] = (outputs[node] ?: IntArray(0)) + id
        }
        val size = children.size
        fail = IntArray(size)
        dictLink = IntArray(size) { -1 }
        val queue = ArrayDeque<Int>()
        children[0]?.values?.forEach { child ->
            fail[child] = 0
            queue.addLast(child)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            children[node]?.forEach { (c, child) ->
                var f = fail[node]
                while (f != 0 && children[f]?.get(c) == null) f = fail[f]
                val target = children[f]?.get(c)
                fail[child] = target ?: 0
                val fc = fail[child]
                dictLink[child] = if (outputs[fc] != null) fc else dictLink[fc]
                queue.addLast(child)
            }
        }
    }

    fun scan(text: String, bits: LongArray) {
        var state = 0
        for (c in text) {
            var next = children[state]?.get(c)
            while (next == null && state != 0) {
                state = fail[state]
                next = children[state]?.get(c)
            }
            state = next ?: 0
            var out = if (outputs[state] != null) state else dictLink[state]
            while (out > 0) {
                outputs[out]?.forEach { bits.setBit(it) }
                out = dictLink[out]
            }
        }
    }
}

private fun LongArray.setBit(i: Int) {
    this[i ushr 6] = this[i ushr 6] or (1L shl (i and 63))
}

private fun LongArray.hasBit(i: Int): Boolean = (this[i ushr 6] and (1L shl (i and 63))) != 0L

private fun LongArray.orWith(other: LongArray) {
    for (i in indices) this[i] = this[i] or other[i]
}

// ==================== 前缀哈希 ====================

private const val PREFIX_HASH_SEED = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

private fun chainPrefixHash(previous: Long, text: String): Long {
    // 64 位 FNV-1a 文本哈希 + 长度，再与前缀哈希做 splitmix64 混合
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (i in text.indices) {
        h = (h xor text[i].code.toLong()) * 0x100000001b3L
    }
    return mix64(previous * -0x61c8864680b583ebL + mix64(h xor (text.length.toLong() shl 32)))
}

private fun mix64(value: Long): Long {
    var z = value
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

// ==================== 临时缓存 ====================

/**
 * 单本世界书的临时缓存（仅存内存，丢失只会退化为重新计算，不影响结果）：
 * 预处理条目、条目正文对普通关键字的判定结果、重放快照。
 */
internal class BookScanCache(book: Lorebook) {
    private val registry = PlainMatcherRegistry()

    val prepared: List<PreparedLoreEntry> = book.entries
        .filter { it.enabled }
        .mapIndexed { index, entry -> PreparedLoreEntry(entry, index, book, registry) }

    /** 本书全部可逐段判定的普通关键字的多模式匹配索引 */
    val plainIndex = PlainKeywordIndex(registry.indexedMatchers())

    // 条目正文（递归来源）包含的关键字位图；正文固定不变，可跨请求复用。并发写入的都是相同的确定性结果，无需加锁
    private val contentPresence = arrayOfNulls<LongArray>(prepared.size)

    fun contentPresence(entry: PreparedLoreEntry): LongArray =
        contentPresence[entry.index] ?: plainIndex.presence(entry.cleanContent).also { contentPresence[entry.index] = it }

    class Snapshot(
        val stepCount: Int,
        val prefixHash: Long,
        val activated: List<LoreActiveState>,
    )

    private data class SnapshotKey(val stepCount: Int, val prefixHash: Long)

    private val snapshots = object : LinkedHashMap<SnapshotKey, Snapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SnapshotKey, Snapshot>?): Boolean {
            return size > LorebookScanCache.MAX_SNAPSHOTS_PER_BOOK
        }
    }

    /** 找到与当前对话前缀内容一致、且步骤数不超过 [maxStepCount] 的最长快照 */
    fun findSnapshot(maxStepCount: Int, hashes: LongArray): Snapshot? = synchronized(snapshots) {
        var best: Snapshot? = null
        for (snap in snapshots.values) {
            if (snap.stepCount > maxStepCount) continue
            if (hashes[snap.stepCount] != snap.prefixHash) continue
            if (best == null || snap.stepCount > best.stepCount) best = snap
        }
        // 刷新访问顺序
        best?.let { snapshots[SnapshotKey(it.stepCount, it.prefixHash)] }
        best
    }

    fun putSnapshot(stepCount: Int, prefixHash: Long, activated: List<LoreActiveState>) {
        synchronized(snapshots) {
            snapshots[SnapshotKey(stepCount, prefixHash)] = Snapshot(stepCount, prefixHash, activated)
        }
    }
}

/**
 * 全局世界书扫描临时缓存：以世界书内容（Lorebook 数据类整体）为键，修改世界书后自然使用新的缓存项
 */
internal object LorebookScanCache {
    const val CHECKPOINT_INTERVAL = 64
    const val MAX_SNAPSHOTS_PER_BOOK = 48
    private const val MAX_BOOKS = 16

    /** 累计重放的步骤数（用于测试校验缓存是否生效） */
    val replayedSteps = AtomicLong(0)

    private val books = object : LinkedHashMap<Lorebook, BookScanCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Lorebook, BookScanCache>?): Boolean {
            return size > MAX_BOOKS
        }
    }

    fun forBook(book: Lorebook): BookScanCache {
        synchronized(books) {
            books[book]?.let { return it }
        }
        val created = BookScanCache(book)
        synchronized(books) {
            return books.getOrPut(book) { created }
        }
    }

    fun clear() {
        synchronized(books) { books.clear() }
        replayedSteps.set(0)
    }
}
