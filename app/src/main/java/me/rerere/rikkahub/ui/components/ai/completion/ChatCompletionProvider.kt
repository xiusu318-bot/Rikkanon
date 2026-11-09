package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

data class ChatCompletionContext(
    val text: String,
    val selection: TextRange,
) {
    val cursor: Int = selection.max
    val hasSelection: Boolean = selection.min != selection.max
}

data class ChatCompletionList(
    val providerId: String,
    val replacementRange: TextRange,
    val items: List<ChatCompletionItem>,
)

data class ChatCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val sortScore: Int = 0,
    val onSelect: ((ChatInputState) -> Unit)? = null,
)

interface ChatCompletionProvider {
    val id: String

    suspend fun complete(context: ChatCompletionContext): ChatCompletionList?
}

@Composable
fun rememberPluginSlashCompletionProvider(
    conversationId: String?,
    assistantId: String?,
): ChatCompletionProvider {
    val host = koinInject<PluginHost>()
    val version by host.registrationsVersion.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val mounts = remember(version) {
        host.chain(PluginHookKind.UI).filter { (_, reg) ->
            (reg.meta as? JsonObject)?.get("slot")?.jsonPrimitive?.contentOrNull == "chat.slash"
        }
    }
    return remember(mounts, conversationId, assistantId) {
        object : ChatCompletionProvider {
            override val id: String = "plugin.slash"

            override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
                if (mounts.isEmpty() || context.hasSelection) return null
                val cursor = context.cursor
                val beforeCursor = context.text.substring(0, cursor)
                val slashIdx = beforeCursor.lastIndexOf('/')
                if (slashIdx < 0) return null
                if (slashIdx > 0 && !beforeCursor[slashIdx - 1].isWhitespace()) return null
                val query = beforeCursor.substring(slashIdx + 1)
                if (query.any { it.isWhitespace() }) return null

                val items = mounts.mapNotNull { (rt, reg) ->
                    val spec = (reg.meta as? JsonObject)?.get("spec") as? JsonObject ?: return@mapNotNull null
                    val name = spec["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("/") ?: return@mapNotNull null
                    val desc = spec["description"]?.jsonPrimitive?.contentOrNull
                        ?: spec["title"]?.jsonPrimitive?.contentOrNull
                        ?: rt.manifest.name
                    val insertText = spec["insertText"]?.jsonPrimitive?.contentOrNull
                    if (query.isNotEmpty() && !name.contains(query, ignoreCase = true) && !desc.contains(query, ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    ChatCompletionItem(
                        label = "/$name",
                        insertText = insertText ?: "",
                        detail = desc,
                        icon = HugeIcons.Puzzle,
                        onSelect = if (insertText == null) {
                            { inputState ->
                                scope.launch {
                                    runCatching {
                                        val res = host.callUiHandler(
                                            rt.id,
                                            reg.handlerId,
                                            buildJsonObject {
                                                put("command", name)
                                                put("query", query)
                                                conversationId?.let { put("conversationId", it) }
                                                assistantId?.let { put("assistantId", it) }
                                            },
                                        )
                                        val rep = (res as? JsonPrimitive)?.contentOrNull
                                            ?: (res as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                                        if (!rep.isNullOrEmpty()) {
                                            inputState.setMessageText(rep)
                                        }
                                    }.onFailure { e ->
                                        host.recordError(rt.id, e)
                                        toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                                    }
                                }
                            }
                        } else null,
                    )
                }
                if (items.isEmpty()) return null
                return ChatCompletionList(
                    providerId = id,
                    replacementRange = TextRange(slashIdx, cursor),
                    items = items,
                )
            }
        }
    }
}
