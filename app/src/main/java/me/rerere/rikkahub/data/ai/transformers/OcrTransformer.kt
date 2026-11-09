package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.http.PluginRequestContext
import me.rerere.plugin.http.PluginRequestContexts
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.plugin.PluginBridge
import me.rerere.rikkahub.data.plugin.PluginCtx
import me.rerere.rikkahub.data.plugin.TaskBeforeResult
import me.rerere.rikkahub.data.plugin.isPluginError
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part, ctx.pluginCtx))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image, baseCtx: PluginCtx = PluginCtx()): String {
        val pluginBridge = runCatching { get<PluginBridge>() }.getOrNull()
        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId)
        val ocrCtx = baseCtx.copy(task = "ocr", model = model ?: baseCtx.model)
        val extra = buildJsonObject { put("url", part.url) }

        return try {
            val content: String = if (pluginBridge != null && pluginBridge.hasTaskReplace("ocr")) {
                val replaced = pluginBridge.taskReplace("ocr", extra, ocrCtx)
                when (replaced) {
                    is JsonPrimitive -> replaced.content
                    null -> ""
                    else -> replaced.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: replaced.toString()
                }
            } else {
                cache.get(part.url)?.let { cachedResult ->
                    Log.i(TAG, "performOcr: Using cached result for ${part.url}")
                    return cachedResult
                }
                val before = pluginBridge?.taskBeforePrompt("ocr", settings.ocrPrompt, extra, ocrCtx)
                    ?: TaskBeforeResult.Proceed(settings.ocrPrompt)
                when (before) {
                    is TaskBeforeResult.ShortCircuit -> before.output
                    is TaskBeforeResult.Proceed -> {
                        val targetModel = model ?: return "[Image]"
                        val providerSetting = targetModel.findProvider(settings.providers) ?: return "[Image]"
                        val provider = get<ProviderManager>().getProviderByType(providerSetting)
                        val token = PluginRequestContexts.register(PluginRequestContext(ocrCtx.toJson()) { true })
                        try {
                            val result = provider.generateText(
                                providerSetting = providerSetting,
                                messages = listOf(
                                    UIMessage.system(before.value),
                                    UIMessage(
                                        role = MessageRole.USER,
                                        parts = listOf(UIMessagePart.Image(part.url))
                                    )
                                ),
                                params = TextGenerationParams(
                                    model = targetModel,
                                    customHeaders = targetModel.customHeaders + CustomHeader(PluginRequestContexts.HEADER, token),
                                    customBody = targetModel.customBodies,
                                ),
                            )
                            result.message.toText().ifBlank { "[ERROR, OCR failed]" }
                        } finally {
                            PluginRequestContexts.remove(token)
                        }
                    }
                }
            }

            val finalContent = pluginBridge?.taskAfterText("ocr", content, extra, ocrCtx) ?: content
            Log.i(TAG, "performOcr: $finalContent")
            val ocrResult = """
                <image_file_ocr>
                   $finalContent
                </image_file_ocr>
                * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
            """.trimIndent()

            cache.put(part.url, ocrResult)
            ocrResult
        } catch (e: Throwable) {
            if (e.isPluginError()) throw e
            "[ERROR, OCR failed: $e]"
        }
    }
}
