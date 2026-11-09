package me.rerere.rikkahub.data.plugin

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.plugin.host.PluginHostApi
import me.rerere.plugin.manifest.PluginPermission
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

sealed interface PluginUiEvent {
    data class OpenPanel(
        val pluginId: String,
        val path: String,
        val presentation: String = "page",
        val argsJson: String = "null",
        val title: String? = null,
    ) : PluginUiEvent
    data class ClosePanel(val pluginId: String, val path: String? = null) : PluginUiEvent
    data class EmitToPanel(val pluginId: String, val event: String, val dataJson: String) : PluginUiEvent
}

/** 插件发起的界面事件，由 RouteActivity 与打开的面板消费。 */
object PluginUiEvents {
    private val _events = MutableSharedFlow<PluginUiEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PluginUiEvent> = _events.asSharedFlow()

    fun emit(event: PluginUiEvent) {
        check(_events.tryEmit(event)) { "插件界面事件队列已满" }
    }
}

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("参数必须是对象")
internal fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: error("缺少参数 $key")
internal fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default
internal fun JsonObject.longOr(key: String, default: Long): Long =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: default
internal fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/**
 * app 层宿主 API 分发。权限按命名空间强制校验；未知接口直接报错。
 * 依赖通过 Koin 延迟获取，避免与 ChatService 形成构造循环。
 */
class AppPluginHostApi(private val context: Context) : PluginHostApi, KoinComponent {
    private val conversations by lazy { PluginConversationApi(get(), get(), get()) }
    private val settings by lazy { PluginSettingsApi(get(), get()) }
    private val workspace by lazy { PluginWorkspaceApi(get(), get()) }

    override suspend fun call(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement {
        val ns = name.substringBefore('.')
        return when (ns) {
            "conversations" -> {
                val write = name.substringAfter('.') !in setOf("list", "get", "status", "search")
                runtime.requirePermission(if (write) PluginPermission.CONVERSATION_WRITE else PluginPermission.CONVERSATION_READ)
                conversations.call(name, args.obj())
            }

            "subAgents" -> {
                runtime.requirePermission(PluginPermission.SUBAGENT)
                conversations.callSubAgent(name, args.obj())
            }

            "settings", "regexes" -> {
                val write = name == "settings.set" || name == "settings.updateAssistant"
                runtime.requirePermission(if (write) PluginPermission.SETTINGS_WRITE else PluginPermission.SETTINGS_READ)
                settings.call(name, args.obj())
            }

            "memory" -> {
                runtime.requirePermission(PluginPermission.MEMORY)
                settings.callMemory(name, args.obj())
            }

            "workspace" -> {
                runtime.requirePermission(PluginPermission.WORKSPACE)
                workspace.call(name, args.obj())
            }

            "notify" -> {
                runtime.requirePermission(PluginPermission.NOTIFICATION)
                notify(runtime, args.obj())
            }

            "clipboard", "speak" -> {
                runtime.requirePermission(PluginPermission.DEVICE)
                device(name, args.obj())
            }

            "toast" -> withContext(Dispatchers.Main) {
                Toast.makeText(context, args.obj().str("text"), Toast.LENGTH_SHORT).show()
                JsonNull
            }

            "ui" -> ui(runtime, name, args.obj())
            else -> error("未知宿主 API: $name")
        }
    }

    private fun ui(runtime: PluginRuntime, name: String, obj: JsonObject): JsonElement = when (name) {
        "ui.open" -> {
            val path = obj.str("path")
            require(!path.contains("..")) { "ui.open: 路径不合法 $path" }
            val presentation = obj.strOrNull("presentation") ?: "page"
            require(presentation in me.rerere.plugin.manifest.PluginUiSlot.PRESENTATIONS) {
                "ui.open: 未知 presentation \"$presentation\""
            }
            PluginUiEvents.emit(
                PluginUiEvent.OpenPanel(
                    pluginId = runtime.id,
                    path = path,
                    presentation = presentation,
                    argsJson = (obj["args"] ?: JsonNull).toString(),
                    title = obj.strOrNull("title"),
                )
            )
            JsonNull
        }

        "ui.close" -> {
            PluginUiEvents.emit(PluginUiEvent.ClosePanel(runtime.id, obj.strOrNull("path")))
            JsonNull
        }

        "ui.emit" -> {
            PluginUiEvents.emit(
                PluginUiEvent.EmitToPanel(
                    pluginId = runtime.id,
                    event = obj.str("event"),
                    dataJson = (obj["data"] ?: JsonNull).toString(),
                )
            )
            JsonNull
        }

        "ui.refresh" -> {
            get<me.rerere.plugin.host.PluginHost>().bumpVersion()
            JsonNull
        }

        else -> error("未知宿主 API: $name")
    }

    private fun notify(runtime: PluginRuntime, obj: JsonObject): JsonElement {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                android.os.Build.VERSION.SDK_INT < 33
        ) { "应用没有通知权限" }
        val id = obj.intOrNull("id") ?: (runtime.id.hashCode() and 0x7fffffff)
        val notification = NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(obj.str("title"))
            .setContentText(obj.strOrNull("text").orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(obj.strOrNull("text").orEmpty()))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify("plugin:${runtime.id}", id, notification)
        return JsonPrimitive(id)
    }

    private suspend fun device(name: String, obj: JsonObject): JsonElement = when (name) {
        "clipboard.read" -> withContext(Dispatchers.Main) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = cm.primaryClip ?: return@withContext JsonNull
            if (clip.itemCount == 0) JsonNull else JsonPrimitive(clip.getItemAt(0).coerceToText(context).toString())
        }

        "clipboard.write" -> withContext(Dispatchers.Main) {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("plugin", obj.str("text")))
            JsonNull
        }

        "speak" -> {
            get<AppEventBus>().emit(AppEvent.Speak(obj.str("text")))
            JsonNull
        }

        else -> error("未知宿主 API: $name")
    }
}
