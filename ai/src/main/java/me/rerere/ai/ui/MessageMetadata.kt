package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.util.json

/**
 * [UIMessagePart.metadata] 的类型安全 schema
 *
 * metadata 在序列化层仍然是 [JsonObject], 这里只是为读写提供编译期类型:
 * - 读: `part.metadataAs<ClaudeReasoningMetadata>()?.signature`
 * - 写: `part.metadata = ClaudeReasoningMetadata(signature = ...).toMetadata()`
 *
 * 所有字段必须可空且 key 与历史数据保持一致(必要时用 [SerialName]),
 * 否则旧会话中持久化的 metadata 将无法解析
 */
sealed interface PartMetadata

/**
 * Claude thinking block 的元数据, 回传时需要携带 signature
 */
@Serializable
data class ClaudeReasoningMetadata(
    val signature: String? = null,
) : PartMetadata

/**
 * OpenAI Responses API reasoning item 的元数据, 回传时需要携带 id 和 encrypted_content
 */
@Serializable
data class OpenAIReasoningMetadata(
    @SerialName("reasoning_id")
    val reasoningId: String? = null,
    @SerialName("encrypted_content")
    val encryptedContent: String? = null,
) : PartMetadata

/**
 * 服务端工具原始块所属的 wire protocol，与实际提供商品牌无关。
 */
@Serializable
enum class ServerToolProtocol {
    @SerialName("openai_responses")
    OPENAI_RESPONSES,

    @SerialName("anthropic_messages")
    ANTHROPIC_MESSAGES,

    @SerialName("google_generate_content")
    GOOGLE_GENERATE_CONTENT,

    @SerialName("google_interactions")
    GOOGLE_INTERACTIONS,
}

/**
 * Provider 服务端工具的原始协议块。
 *
 * 通用字段保存在 [UIMessagePart.ServerTool] 自身；这里额外保留 Provider 原始调用/结果，供多轮请求
 * 原样回传。OpenAI Responses API 通常只使用 [call]，Claude 则分别使用 [call] 和 [result]。
 */
@Serializable
data class ServerToolMetadata(
    @SerialName("server_tool_protocol")
    val protocol: ServerToolProtocol? = null,
    @SerialName("server_tool_call")
    val call: JsonObject? = null,
    @SerialName("server_tool_call_index")
    val callIndex: Int? = null,
    @SerialName("server_tool_result")
    val result: JsonObject? = null,
    @SerialName("server_tool_result_index")
    val resultIndex: Int? = null,
) : PartMetadata

/**
 * OpenRouter Chat Completions 的结构化 reasoning_details，工具调用续轮时需要原样回传。
 */
@Serializable
data class OpenRouterReasoningMetadata(
    @SerialName("openrouter_reasoning_details")
    val reasoningDetails: JsonArray? = null,
) : PartMetadata

/**
 * Google Gemini 部件的 thoughtSignature，回传时需要携带
 */
@Serializable
data class GoogleThoughtMetadata(
    val thoughtSignature: String? = null,
) : PartMetadata

/**
 * Google Interactions API 的 thought / function_call step 签名，无状态多轮时需要原样回传。
 *
 * 与 [GoogleThoughtMetadata] 使用不同的 key，避免在 generateContent 与 Interactions 之间切换时
 * 把一种协议的签名发给另一种协议。
 */
@Serializable
data class GoogleInteractionsMetadata(
    @SerialName("interactions_signature")
    val signature: String? = null,
) : PartMetadata

/**
 * 文件编辑类工具(如 workspace_edit_file)输出部件的元数据,
 * 携带 unified diff 文本供 UI 渲染 diff view, 不会发送给 API
 */
@Serializable
data class DiffMetadata(
    val diff: String? = null,
) : PartMetadata

/**
 * 提示词块 JSON 模式携带的原始 JSON 元素，作为数组元素直接并入 API 请求内
 */
@Serializable
data class RawJsonMessageMetadata(
    @SerialName("raw_json_element")
    val rawJson: JsonElement? = null,
) : PartMetadata

// 先判断是否含 raw_json_element 字段：不含时解析结果必然为 null，省去对每个带 metadata 的 part（如思维链签名）做完整反序列化
fun UIMessage.rawJsonElementOrNull(): JsonElement? = parts.firstNotNullOfOrNull { part ->
    if (part.metadata?.containsKey("raw_json_element") == true) part.metadataAs<RawJsonMessageMetadata>()?.rawJson else null
}

/**
 * 提取列表最开头（第一条非 SYSTEM 消息或 JSON 模式块之前）的所有连续普通 SYSTEM 消息；
 * 位于非首位（如预设底部、@D 深度或任意非 SYSTEM 块之后）的 SYSTEM 消息保留在消息数组原位置发出。
 */
fun List<UIMessage>.leadingSystemMessages(): List<UIMessage> {
    val firstNonSysIdx = indexOfFirst { it.role != MessageRole.SYSTEM || it.rawJsonElementOrNull() != null }
    return when {
        isEmpty() -> emptyList()
        firstNonSysIdx == -1 -> this
        firstNonSysIdx == 0 -> emptyList()
        else -> subList(0, firstNonSysIdx)
    }
}

/**
 * 仅当列表最开头（第 0 条）为非 JSON 模式的普通 SYSTEM 消息时，提取并合并开头所有连续 SYSTEM 消息；
 * 位于非首位（如预设底部、@D 深度或任意非 SYSTEM 块之后）的 SYSTEM 消息保留在消息数组原位置发出。
 */
fun List<UIMessage>.leadingSystemMessageOrNull(): UIMessage? {
    val leading = leadingSystemMessages()
    if (leading.isEmpty()) return null
    if (leading.size == 1) return leading.first()
    val reasoningParts = leading.flatMap { it.parts.filterIsInstance<UIMessagePart.Reasoning>() }
    val textParts = leading.flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
        .filter { it.text.isNotEmpty() }
    return leading.first().copy(parts = reasoningParts + textParts, isSynthetic = true)
}

/**
 * 跳过已被提取为顶层 systemInstruction / system / instructions 的最开头连续 SYSTEM 消息；
 * 其余所有位置的消息（含非首位的 SYSTEM 消息与 JSON 模式块）均保留在原位置。
 */
fun List<UIMessage>.withoutLeadingSystemMessages(): List<UIMessage> {
    val leadingCount = leadingSystemMessages().size
    return if (leadingCount > 0) drop(leadingCount) else this
}

/**
 * 递归对 [JsonElement] 中的所有字符串值应用转换函数（用于对 JSON 模式块中的字符串展开占位符与宏）
 */
fun JsonElement.mapJsonStrings(transform: (String) -> String): JsonElement = when (this) {
    is kotlinx.serialization.json.JsonPrimitive -> if (isString) kotlinx.serialization.json.JsonPrimitive(transform(content)) else this
    is JsonArray -> JsonArray(map { it.mapJsonStrings(transform) })
    is JsonObject -> JsonObject(mapValues { (_, v) -> v.mapJsonStrings(transform) })
}

fun UIMessage.mapRawJsonStrings(transform: (String) -> String): UIMessage {
    if (rawJsonElementOrNull() == null) return this
    var changed = false
    val newParts = parts.map { part ->
        val raw = if (part.metadata?.containsKey("raw_json_element") == true) {
            part.metadataAs<RawJsonMessageMetadata>()?.rawJson
        } else null
        if (raw != null) {
            val mapped = raw.mapJsonStrings(transform)
            if (mapped != raw) {
                changed = true
                val newMeta = JsonObject(
                    (part.metadata ?: JsonObject(emptyMap())) + ("raw_json_element" to mapped)
                )
                when (part) {
                    is UIMessagePart.Text -> part.copy(metadata = newMeta)
                    else -> part
                }
            } else part
        } else part
    }
    return if (changed) copy(parts = newParts) else this
}

/**
 * 将 metadata 解析为类型化的 [PartMetadata], 解析失败或 metadata 为 null 时返回 null
 *
 * 由于 json 配置了 ignoreUnknownKeys, 不同 provider 的 metadata 互不干扰
 * (例如切换 provider 后, OpenAI 写入的 reasoning 元数据不会影响 Claude 的解析)
 */
inline fun <reified T : PartMetadata> UIMessagePart.metadataAs(): T? = metadata?.let {
    runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull()
}

/**
 * 将类型化的 [PartMetadata] 编码为 metadata [JsonObject]
 *
 * 由于 json 配置了 explicitNulls = false, 值为 null 的字段不会写入
 */
inline fun <reified T : PartMetadata> T.toMetadata(): JsonObject =
    json.encodeToJsonElement(this).jsonObject
