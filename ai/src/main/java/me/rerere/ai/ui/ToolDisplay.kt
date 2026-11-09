package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.json

/** 工具调用部件 metadata 中的显示声明（由声明了 display 的工具在调用出现时写入）。 */
const val TOOL_DISPLAY_KEY = "rikka.display"

/** 工具执行后仅用于展示、不发给 API 的部件（JSON 数组），存于工具调用部件 metadata。 */
const val TOOL_DISPLAY_PARTS_KEY = "rikka.displayParts"

/** 工具 execute 返回的部件若带此 metadata 标记，则移入 [TOOL_DISPLAY_PARTS_KEY] 而不进入 output。 */
const val DISPLAY_ONLY_PART_KEY = "rikka.displayOnly"

@Serializable
data class ToolDisplay(
    @SerialName("as") val mode: String = "tool",
    val source: String = "input",
    val field: String = "content",
) {
    val isText: Boolean get() = mode == "text"
    val isHidden: Boolean get() = mode == "hidden"
}

fun UIMessagePart.Tool.toolDisplay(): ToolDisplay? {
    val element = metadata?.get(TOOL_DISPLAY_KEY) as? JsonObject ?: return null
    return runCatching { json.decodeFromJsonElement(ToolDisplay.serializer(), element) }.getOrNull()
}

/**
 * 以正文显示的工具调用的文本；非正文显示返回 null。
 * - source=input：从参数 JSON（可能尚在流式拼接）中容错提取 field 字段。
 * - source=output：优先取 displayParts 中的文本，否则取 output 文本。
 */
fun UIMessagePart.Tool.displayText(): String? {
    val display = toolDisplay() ?: return null
    if (!display.isText) return null
    return when (display.source) {
        "output" -> {
            val displayParts = metadata?.get(TOOL_DISPLAY_PARTS_KEY) as? JsonArray
            val fromDisplay = displayParts?.mapNotNull { el ->
                runCatching { el.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }?.joinToString("\n")
            fromDisplay ?: output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        }

        else -> extractPartialJsonString(input, display.field) ?: ""
    }
}

/**
 * 从可能不完整的 JSON 对象文本中提取顶层字符串字段 [field] 的已到达部分。
 * 仅用于流式显示；字段尚未出现时返回 null。
 */
fun extractPartialJsonString(text: String, field: String): String? {
    val key = "\"" + field + "\""
    var searchFrom = 0
    while (true) {
        val k = text.indexOf(key, searchFrom)
        if (k < 0) return null
        var i = k + key.length
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        if (text[i] != ':') {
            searchFrom = k + key.length
            continue
        }
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return ""
        if (text[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i + 1 >= text.length) return sb.toString()
                    when (val e = text[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '/' -> sb.append('/')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'u' -> {
                            if (i + 6 > text.length) return sb.toString()
                            val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return sb.toString()
                            sb.append(code.toChar())
                            i += 4
                        }

                        else -> sb.append(e)
                    }
                    i += 2
                    continue
                }

                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}

/** 给工具调用部件补上显示声明（保留已有 metadata，例如 Gemini thoughtSignature）。 */
fun UIMessagePart.Tool.withDisplay(display: JsonObject): UIMessagePart.Tool {
    if (metadata?.get(TOOL_DISPLAY_KEY) == display) return this
    val merged = JsonObject((metadata ?: JsonObject(emptyMap())) + (TOOL_DISPLAY_KEY to display))
    return copy(metadata = merged)
}

