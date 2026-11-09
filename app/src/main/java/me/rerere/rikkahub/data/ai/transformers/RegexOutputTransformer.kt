package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegexPhase
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.plugin.PluginBridge
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 发出请求体正则转换器（「仅修改发出」requestOnly == true）
 *
 * 在组装请求体发送给 AI 前，对真实对话消息（非预设合成块）执行「仅修改发出」的正则替换，
 * 不改动数据库中的原始消息正文。
 */
object RegexInputTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        val pluginBridge = runCatching { get<PluginBridge>() }.getOrNull()
        val hasRequestRegex = assistant.regexes.any {
            it.enabled && it.findRegex.isNotEmpty() && it.matchesPhase(AssistantRegexPhase.REQUEST)
        } || pluginBridge?.hasPluginRegex(AssistantRegexPhase.REQUEST, AssistantAffectScope.USER) == true ||
            pluginBridge?.hasPluginRegex(AssistantRegexPhase.REQUEST, AssistantAffectScope.ASSISTANT) == true
        if (!hasRequestRegex) return messages

        // 深度（对齐酒馆）：只数真实聊天消息（USER / ASSISTANT 且非预设合成块），最后一条为 0，往上依次 +1
        val depths = computeRegexDepths(messages)
        return messages.mapIndexed { index, message ->
            val depth = depths[index] ?: return@mapIndexed message
            val scope = when (message.role) {
                MessageRole.USER -> AssistantAffectScope.USER
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@mapIndexed message
            }
            var changed = false
            val newParts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        val replaced = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = scope,
                            phase = AssistantRegexPhase.REQUEST,
                            depth = depth,
                        )
                        if (replaced != part.text) {
                            changed = true
                            part.copy(text = replaced)
                        } else {
                            part
                        }
                    }

                    else -> part
                }
            }
            if (changed) message.copy(parts = newParts) else message
        }
    }
}

/**
 * 计算每条消息的正则深度（对齐酒馆）：只数真实聊天消息（USER / ASSISTANT 且 !isSynthetic），
 * 最后一条为 0，往上依次 +1；系统消息与预设合成块为 null（不参与正则）。
 */
internal fun computeRegexDepths(messages: List<UIMessage>): Array<Int?> {
    val depths = arrayOfNulls<Int>(messages.size)
    var depth = 0
    for (i in messages.indices.reversed()) {
        val message = messages[i]
        if (message.isSynthetic) continue
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) continue
        depths[i] = depth++
    }
    return depths
}

/**
 * 助手输出落库正则转换器（「直接修改正文」!visualOnly && !requestOnly）
 *
 * 仅在当轮流式生成结束（onGenerationFinish）时，对最新生成的最后一条 ASSISTANT 消息正文执行一次替换，
 * 不在每个流式 chunk 重复执行，也不对历史已完成消息重复套娃替换。
 */
object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (messages.isEmpty()) return messages
        val assistant = ctx.assistant
        val pluginBridge = runCatching { get<PluginBridge>() }.getOrNull()
        val hasPersistRegex = assistant.regexes.any {
            it.enabled &&
                it.findRegex.isNotEmpty() &&
                AssistantAffectScope.ASSISTANT in it.affectingScope &&
                it.matchesPhase(AssistantRegexPhase.PERSIST)
        } || pluginBridge?.hasPluginRegex(AssistantRegexPhase.PERSIST, AssistantAffectScope.ASSISTANT) == true
        if (!hasPersistRegex) return messages

        val lastMessage = messages.last()
        if (lastMessage.role != MessageRole.ASSISTANT) return messages

        var changed = false
        val newParts = lastMessage.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val replaced = part.text.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                        phase = AssistantRegexPhase.PERSIST,
                        depth = 0, // 刚生成完的消息就是最后一条
                    )
                    if (replaced != part.text) {
                        changed = true
                        part.copy(text = replaced)
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
        if (!changed) return messages
        return messages.dropLast(1) + lastMessage.copy(parts = newParts)
    }
}
