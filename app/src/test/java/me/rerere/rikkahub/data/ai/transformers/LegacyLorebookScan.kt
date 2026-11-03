package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGreenLightMode
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.isTriggered
import me.rerere.rikkahub.data.model.matchSingleKeyword
import me.rerere.rikkahub.data.model.parseWorldInfoContentDecorators
import kotlin.uuid.Uuid

/*
 * 优化前的世界书扫描实现（原样保留，仅重命名），作为等价性测试的参照基准。
 */

/**
 * 扫描单本世界书在当前对话历史下的激活条目与锚定位置
 */
internal fun legacyScanLorebookForConversation(
    book: Lorebook,
    chatHistory: List<UIMessage>,
): List<ActivatedLoreEntry> {
    val enabledEntries = book.entries.filter { it.enabled }
    if (enabledEntries.isEmpty()) return emptyList()

    return when (book.greenLightMode) {
        LorebookGreenLightMode.DYNAMIC_NATIVE -> {
            // 酒馆原生绿灯：仅扫描当前最新窗口
            val triggered = legacyRunSingleTurnLorebookScan(
                book = book,
                entries = enabledEntries,
                visibleMessages = chatHistory,
                rollProbability = true,
            )
            triggered.map { entry ->
                val dec = parseWorldInfoContentDecorators(entry.content)
                ActivatedLoreEntry(
                    entry = entry,
                    cleanContent = dec.cleanContent,
                    anchoredChatInsertIndex = null,
                )
            }
        }

        LorebookGreenLightMode.STICKY_FLOATING,
        LorebookGreenLightMode.STICKY_LOCKED_POSITION -> {
            // 触发后持续至下次压缩（且 STICKY_LOCKED_POSITION 锁定首次触发时的注入位置）
            val lockPosition = book.greenLightMode == LorebookGreenLightMode.STICKY_LOCKED_POSITION
            val activatedById = LinkedHashMap<Uuid, ActivatedLoreEntry>()

            // 若对话为空（0 条消息），也执行一次空窗口扫描以激活常驻蓝灯或空匹配正则
            val totalSteps = chatHistory.size.coerceAtLeast(1)
            for (step in 0 until totalSteps) {
                val entriesForStep = if (book.recursiveScan) {
                    enabledEntries
                } else {
                    enabledEntries.filter { !activatedById.containsKey(it.id) }
                }
                if (entriesForStep.isEmpty()) break

                val prefixMessages = if (chatHistory.isEmpty()) {
                    emptyList()
                } else {
                    chatHistory.subList(0, step + 1)
                }
                val prefixLen = prefixMessages.size

                // 找出在 prefixMessages 下被触发的条目（开启递归时包含已激活条目以便参与递归链）
                val triggeredThisStep = legacyRunSingleTurnLorebookScan(
                    book = book,
                    entries = entriesForStep,
                    visibleMessages = prefixMessages,
                    rollProbability = step == totalSteps - 1,
                )

                for (entry in triggeredThisStep) {
                    if (!activatedById.containsKey(entry.id)) {
                        val dec = parseWorldInfoContentDecorators(entry.content)
                        val anchoredIdx = if (lockPosition) {
                            computeDepthInsertIndex(
                                chatSize = prefixLen,
                                position = entry.position,
                                depth = entry.injectDepth,
                            )
                        } else {
                            null
                        }
                        activatedById[entry.id] = ActivatedLoreEntry(
                            entry = entry,
                            cleanContent = dec.cleanContent,
                            anchoredChatInsertIndex = anchoredIdx,
                        )
                    }
                }
            }

            // 最终复核：对于配置了 NOT_ANY / NOT_ALL 排除过滤器的条目（如 "只要没出现『测试』就触发，出现『测试』就停止"），
            // 检查当前最新扫描窗口是否命中了排除词；若最新窗口命中了排除词，则本轮不激活。
            activatedById.values.filter { item ->
                !legacyIsExcludedByCurrentWindow(item.entry, book, chatHistory)
            }
        }
    }
}

/**
 * 检查带有 NOT_ANY / NOT_ALL 次要关键字排除过滤器的条目在当前最新窗口是否被排除
 */
private fun legacyIsExcludedByCurrentWindow(
    entry: PromptInjection.RegexInjection,
    book: Lorebook,
    chatHistory: List<UIMessage>,
): Boolean {
    if (!entry.selective || entry.secondaryKeys.isEmpty()) return false
    if (entry.selectiveLogic != SelectiveLogic.NOT_ANY && entry.selectiveLogic != SelectiveLogic.NOT_ALL) {
        return false
    }
    val cleanSecondary = entry.secondaryKeys.filter { it.isNotBlank() }
    if (cleanSecondary.isEmpty()) return false

    val effectiveScanDepth = (entry.overrideScanDepth ?: entry.scanDepth.takeIf { it > 0 } ?: book.defaultScanDepth)
        .coerceAtLeast(1)
    val latestContext = extractContextForMatching(chatHistory, effectiveScanDepth)
    val effectiveCase = entry.overrideCaseSensitive ?: (entry.caseSensitive || book.caseSensitive)
    val effectiveWhole = entry.matchWholeWords ?: book.matchWholeWords

    return when (entry.selectiveLogic) {
        SelectiveLogic.NOT_ANY -> cleanSecondary.any {
            matchSingleKeyword(it, latestContext, entry.useRegex, effectiveCase, effectiveWhole)
        }
        SelectiveLogic.NOT_ALL -> cleanSecondary.all {
            matchSingleKeyword(it, latestContext, entry.useRegex, effectiveCase, effectiveWhole)
        }
        else -> false
    }
}

/**
 * 在给定的消息列表快照下执行一次完整的世界书扫描（含多轮递归扫描）
 */
private fun legacyRunSingleTurnLorebookScan(
    book: Lorebook,
    entries: List<PromptInjection.RegexInjection>,
    visibleMessages: List<UIMessage>,
    rollProbability: Boolean,
): List<PromptInjection.RegexInjection> {
    val activatedIds = LinkedHashSet<Uuid>()
    val activatedEntries = mutableListOf<PromptInjection.RegexInjection>()
    val contextByDepth = HashMap<Int, String>()

    // 第 0 轮：扫描聊天记录（delayUntilRecursion = true 的条目在首轮跳过）
    for (entry in entries) {
        if (entry.delayUntilRecursion && !entry.constantActive) continue
        val effectiveScanDepth = (entry.overrideScanDepth ?: entry.scanDepth.takeIf { it > 0 } ?: book.defaultScanDepth)
            .coerceAtLeast(1)
        val chatContext = contextByDepth.getOrPut(effectiveScanDepth) {
            extractContextForMatching(visibleMessages, effectiveScanDepth)
        }
        if (entry.isTriggered(
                context = chatContext,
                bookCaseSensitive = book.caseSensitive,
                bookMatchWholeWords = book.matchWholeWords,
                rollProbability = rollProbability,
            )
        ) {
            if (activatedIds.add(entry.id)) {
                activatedEntries.add(entry)
            }
        }
    }

    // 递归扫描阶段
    if (book.recursiveScan && activatedEntries.isNotEmpty()) {
        val maxSteps = if (book.maxRecursionSteps <= 0) 10 else book.maxRecursionSteps
        var newlyActivated = activatedEntries.toList()
        var step = 0
        while (step < maxSteps && newlyActivated.isNotEmpty()) {
            val recursionSourceText = newlyActivated
                .filter { !it.preventRecursion }
                .map { parseWorldInfoContentDecorators(it.content).cleanContent }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (recursionSourceText.isBlank()) break

            val nextBatch = mutableListOf<PromptInjection.RegexInjection>()
            for (candidate in entries) {
                if (candidate.id in activatedIds) continue
                if (candidate.excludeRecursion) continue
                if (candidate.isTriggered(
                        context = recursionSourceText,
                        bookCaseSensitive = book.caseSensitive,
                        bookMatchWholeWords = book.matchWholeWords,
                        rollProbability = rollProbability,
                    )
                ) {
                    activatedIds.add(candidate.id)
                    activatedEntries.add(candidate)
                    nextBatch.add(candidate)
                }
            }
            newlyActivated = nextBatch
            step++
        }
    }

    return activatedEntries
}

