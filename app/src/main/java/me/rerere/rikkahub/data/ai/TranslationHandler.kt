package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.plugin.http.PluginRequestContext
import me.rerere.plugin.http.PluginRequestContexts
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.plugin.PluginBridge
import me.rerere.rikkahub.data.plugin.PluginCtx
import me.rerere.rikkahub.data.plugin.TaskBeforeResult
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

class TranslationHandler(
    private val providerManager: ProviderManager,
    private val pluginBridge: PluginBridge? = null,
) {
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        conversationId: Uuid? = null,
        onStreamUpdate: ((String) -> Unit)? = null,
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
        val ctx = PluginCtx(
            task = "translate",
            conversationId = conversationId,
            model = model,
        )
        val extra = buildJsonObject {
            put("text", sourceText)
            put("targetLanguage", targetLanguage.toLanguageTag())
            put("targetLanguageName", targetLanguage.displayName)
        }

        if (pluginBridge != null && pluginBridge.hasTaskReplace("translate")) {
            val replaced = pluginBridge.taskReplace("translate", extra, ctx)
            val rawOut = when (replaced) {
                is JsonPrimitive -> replaced.content
                null -> ""
                else -> replaced.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: replaced.toString()
            }
            val finalOut = pluginBridge.taskAfterText("translate", rawOut, extra, ctx)
            if (finalOut.isNotBlank()) {
                onStreamUpdate?.invoke(finalOut)
                emit(finalOut)
            }
            return@flow
        }

        val defaultPrompt = settings.translatePrompt.applyPlaceholders(
            "source_text" to sourceText,
            "target_lang" to targetLanguage.toString(),
        )
        val before = pluginBridge?.taskBeforePrompt("translate", defaultPrompt, extra, ctx)
            ?: TaskBeforeResult.Proceed(defaultPrompt)
        if (before is TaskBeforeResult.ShortCircuit) {
            val finalOut = pluginBridge?.taskAfterText("translate", before.output, extra, ctx) ?: before.output
            if (finalOut.isNotBlank()) {
                onStreamUpdate?.invoke(finalOut)
                emit(finalOut)
            }
            return@flow
        }
        val effectivePrompt = (before as TaskBeforeResult.Proceed).value

        val resolvedModel = model ?: error("Translation model not found")
        val provider = resolvedModel.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)
        val token = PluginRequestContexts.register(PluginRequestContext(ctx.toJson()) { true })

        try {
            var latestText = ""
            if (!ModelRegistry.QWEN_MT.match(resolvedModel.modelId)) {
                var messages = listOf(UIMessage.user(effectivePrompt))
                val streamChunkHandler = StreamChunkHandler(resolvedModel)

                providerHandler.streamText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = resolvedModel,
                        reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                        customHeaders = resolvedModel.customHeaders + CustomHeader(PluginRequestContexts.HEADER, token),
                        customBody = resolvedModel.customBodies,
                    ),
                ).collect { chunk ->
                    messages = streamChunkHandler.handle(messages, chunk)
                    val translatedText = messages.lastOrNull()?.toText() ?: ""
                    latestText = translatedText

                    if (translatedText.isNotBlank()) {
                        onStreamUpdate?.invoke(translatedText)
                        emit(translatedText)
                    }
                }
            } else {
                val messages = listOf(UIMessage.user(sourceText))
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = resolvedModel,
                        temperature = 0.3f,
                        topP = 0.95f,
                        customHeaders = resolvedModel.customHeaders + CustomHeader(PluginRequestContexts.HEADER, token),
                        customBody = resolvedModel.customBodies + CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH)),
                                )
                            },
                        ),
                    ),
                )
                latestText = result.message.toText()
                if (latestText.isNotBlank()) {
                    onStreamUpdate?.invoke(latestText)
                    emit(latestText)
                }
            }

            if (pluginBridge != null && latestText.isNotBlank()) {
                val after = pluginBridge.taskAfterText("translate", latestText, extra, ctx)
                if (after != latestText) {
                    onStreamUpdate?.invoke(after)
                    emit(after)
                }
            }
        } finally {
            PluginRequestContexts.remove(token)
        }
    }.flowOn(Dispatchers.IO)
}
