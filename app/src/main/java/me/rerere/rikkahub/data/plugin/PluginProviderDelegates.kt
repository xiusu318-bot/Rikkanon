package me.rerere.rikkahub.data.plugin

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.plugin.error.PluginException
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * 将已启用插件通过 `registerProvider` / `registerSearch` / `registerTTS` / `registerASR` / `registerMediaGen`
 * 注册的能力实现自动同步到 Settings 中：
 * - 插件启用（且该注册项未被单独关闭）时自动出现在可用服务/提供商列表中（若 `registerProvider` 声明了静态 `models` 数组也自动填入）；
 * - 插件停用、卸载或单独关闭该能力项时自动移除；
 * - 用户无需也不应在设置界面手动新建 `Plugin` 渠道或手填 `pluginId` / `name`。
 */
object PluginCapabilitySync {
    private fun stableUuid(kind: String, pluginId: String, name: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes("rikkanon.plugin.$kind:$pluginId:$name".toByteArray()).toString())

    suspend fun sync(host: PluginHost, settingsStore: SettingsStore) {
        val activeProviders = host.chain(PluginHookKind.PROVIDER)
        val activeSearches = host.chain(PluginHookKind.SEARCH)
        val activeTts = host.chain(PluginHookKind.TTS)
        val activeAsr = host.chain(PluginHookKind.ASR)
        val activeMediaGen = host.chain(PluginHookKind.MEDIAGEN)

        settingsStore.update { settings ->
            // 1. AI Providers
            val existingPluginProviders = settings.providers.filterIsInstance<ProviderSetting.Plugin>()
                .associateBy { it.pluginId to it.providerName }
            val syncedPluginProviders = activeProviders.map { (rt, reg) ->
                val meta = reg.meta.jsonObject
                val name = meta["name"]!!.jsonPrimitive.content
                val displayName = meta["displayName"]?.jsonPrimitive?.contentOrNull ?: "${rt.manifest.name} ($name)"
                val staticModels = (meta["models"] as? JsonArray)?.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> Model(
                            id = stableUuid("model", rt.id, "$name:${el.content}"),
                            modelId = el.content,
                            displayName = el.content,
                        )
                        is JsonObject -> {
                            val mid = el["modelId"]?.jsonPrimitive?.contentOrNull
                                ?: el["id"]?.jsonPrimitive?.contentOrNull
                                ?: return@mapNotNull null
                            Model(
                                id = stableUuid("model", rt.id, "$name:$mid"),
                                modelId = mid,
                                displayName = el["displayName"]?.jsonPrimitive?.contentOrNull
                                    ?: el["name"]?.jsonPrimitive?.contentOrNull
                                    ?: mid,
                            )
                        }
                        else -> null
                    }
                }.orEmpty()
                val prev = existingPluginProviders[rt.id to name]
                if (prev != null) {
                    prev.copy(
                        models = if (prev.models.isEmpty() && staticModels.isNotEmpty()) staticModels else prev.models
                    )
                } else {
                    ProviderSetting.Plugin(
                        id = stableUuid(PluginHookKind.PROVIDER, rt.id, name),
                        name = displayName,
                        pluginId = rt.id,
                        providerName = name,
                        models = staticModels,
                    )
                }
            }
            val newProviders = settings.providers.filterNot { it is ProviderSetting.Plugin } + syncedPluginProviders

            // 2. Search Services
            val existingPluginSearches = settings.searchServices.filterIsInstance<SearchServiceOptions.PluginOptions>()
                .associateBy { it.pluginId to it.serviceName }
            val syncedPluginSearches = activeSearches.map { (rt, reg) ->
                val meta = reg.meta.jsonObject
                val name = meta["name"]!!.jsonPrimitive.content
                val displayName = meta["displayName"]?.jsonPrimitive?.contentOrNull ?: "${rt.manifest.name} ($name)"
                val methods = (meta["methods"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val hasScrape = "scrape" in methods
                val prev = existingPluginSearches[rt.id to name]
                prev?.copy(enableScrape = hasScrape) ?: SearchServiceOptions.PluginOptions(
                    id = stableUuid(PluginHookKind.SEARCH, rt.id, name),
                    name = displayName,
                    pluginId = rt.id,
                    serviceName = name,
                    enableScrape = hasScrape,
                )
            }
            val newSearches = settings.searchServices.filterNot { it is SearchServiceOptions.PluginOptions } + syncedPluginSearches

            // 3. TTS Providers
            val existingPluginTts = settings.ttsProviders.filterIsInstance<TTSProviderSetting.Plugin>()
                .associateBy { it.pluginId to it.providerName }
            val syncedPluginTts = activeTts.map { (rt, reg) ->
                val meta = reg.meta.jsonObject
                val name = meta["name"]!!.jsonPrimitive.content
                val displayName = meta["displayName"]?.jsonPrimitive?.contentOrNull ?: "${rt.manifest.name} ($name)"
                existingPluginTts[rt.id to name] ?: TTSProviderSetting.Plugin(
                    id = stableUuid(PluginHookKind.TTS, rt.id, name),
                    name = displayName,
                    pluginId = rt.id,
                    providerName = name,
                )
            }
            val newTts = settings.ttsProviders.filterNot { it is TTSProviderSetting.Plugin } + syncedPluginTts

            // 4. ASR Providers
            val existingPluginAsr = settings.asrProviders.filterIsInstance<ASRProviderSetting.Plugin>()
                .associateBy { it.pluginId to it.providerName }
            val syncedPluginAsr = activeAsr.map { (rt, reg) ->
                val meta = reg.meta.jsonObject
                val name = meta["name"]!!.jsonPrimitive.content
                val displayName = meta["displayName"]?.jsonPrimitive?.contentOrNull ?: "${rt.manifest.name} ($name)"
                existingPluginAsr[rt.id to name] ?: ASRProviderSetting.Plugin(
                    id = stableUuid(PluginHookKind.ASR, rt.id, name),
                    name = displayName,
                    pluginId = rt.id,
                    providerName = name,
                )
            }
            val newAsr = settings.asrProviders.filterNot { it is ASRProviderSetting.Plugin } + syncedPluginAsr

            // 5. MediaGen Providers
            val existingPluginMediaGen = settings.mediaGenerationProviders.filterIsInstance<MediaGenerationProviderSetting.Plugin>()
                .associateBy { it.pluginId to it.providerName }
            val syncedPluginMediaGen = activeMediaGen.map { (rt, reg) ->
                val meta = reg.meta.jsonObject
                val name = meta["name"]!!.jsonPrimitive.content
                val displayName = meta["displayName"]?.jsonPrimitive?.contentOrNull ?: "${rt.manifest.name} ($name)"
                val staticModels = (meta["models"] as? JsonArray)?.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> MediaGenerationModel(
                            id = stableUuid("mediamodel", rt.id, "$name:${el.content}"),
                            modelId = el.content,
                            kind = MediaKind.IMAGE,
                        )
                        is JsonObject -> {
                            val mid = el["modelId"]?.jsonPrimitive?.contentOrNull
                                ?: el["id"]?.jsonPrimitive?.contentOrNull
                                ?: return@mapNotNull null
                            val kindStr = el["kind"]?.jsonPrimitive?.contentOrNull?.uppercase()
                            val kind = if (kindStr == "VIDEO") MediaKind.VIDEO else MediaKind.IMAGE
                            MediaGenerationModel(
                                id = stableUuid("mediamodel", rt.id, "$name:$mid"),
                                modelId = mid,
                                kind = kind,
                                displayName = el["displayName"]?.jsonPrimitive?.contentOrNull ?: mid,
                            )
                        }
                        else -> null
                    }
                }.orEmpty()
                val prev = existingPluginMediaGen[rt.id to name]
                if (prev != null) {
                    prev.copy(
                        models = if (prev.models.isEmpty() && staticModels.isNotEmpty()) staticModels else prev.models
                    )
                } else {
                    MediaGenerationProviderSetting.Plugin(
                        id = stableUuid(PluginHookKind.MEDIAGEN, rt.id, name),
                        name = displayName,
                        pluginId = rt.id,
                        providerName = name,
                        models = staticModels,
                    )
                }
            }
            val newMediaGen = settings.mediaGenerationProviders.filterNot { it is MediaGenerationProviderSetting.Plugin } + syncedPluginMediaGen

            if (newProviders == settings.providers &&
                newSearches == settings.searchServices &&
                newTts == settings.ttsProviders &&
                newAsr == settings.asrProviders &&
                newMediaGen == settings.mediaGenerationProviders
            ) {
                settings
            } else {
                settings.copy(
                    providers = newProviders,
                    searchServices = newSearches,
                    ttsProviders = newTts,
                    asrProviders = newAsr,
                    mediaGenerationProviders = newMediaGen,
                )
            }
        }
    }
}

/**
 * 插件通过 `rikka.registerProvider` 注册的 AI Provider 实现。
 */
class PluginAiProvider(private val host: PluginHost) : Provider<ProviderSetting.Plugin> {
    private fun configJson(setting: ProviderSetting.Plugin): JsonObject = buildJsonObject {
        put("id", setting.id.toString())
        put("name", setting.name)
        put("apiKey", setting.apiKey)
        put("baseUrl", setting.baseUrl)
    }

    private fun requestJson(
        setting: ProviderSetting.Plugin,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): JsonObject = buildJsonObject {
        put("config", configJson(setting))
        put("model", params.model.modelId)
        put("messages", JsonInstant.encodeToJsonElement(ListSerializer(UIMessage.serializer()), messages))
        params.temperature?.let { put("temperature", it) }
        params.topP?.let { put("topP", it) }
        params.maxTokens?.let { put("maxTokens", it) }
        put("reasoningLevel", params.reasoningLevel.name)
        put("tools", JsonArray(params.tools.map { t ->
            buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                t.parameters()?.let { put("parameters", JsonInstant.encodeToJsonElement(InputSchema.serializer(), it)) }
            }
        }))
        put("headers", buildJsonObject {
            (setting.customHeaders + params.customHeaders)
                .filter { it.name != me.rerere.plugin.http.PluginRequestContexts.HEADER }
                .forEach { put(it.name, it.value) }
        })
        put("body", buildJsonObject {
            params.customBody.forEach { put(it.key, it.value) }
        })
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Plugin): List<Model> {
        val result = host.callMethod(
            pluginId = providerSetting.pluginId,
            kind = PluginHookKind.PROVIDER,
            name = providerSetting.providerName,
            method = "listModels",
            args = listOf(configJson(providerSetting)),
        )
        if (result is JsonNull) return emptyList()
        val arr = result as? JsonArray
            ?: throw PluginException(providerSetting.pluginId, "provider.listModels", "listModels 必须返回数组")
        return arr.map { el ->
            when (el) {
                is JsonPrimitive -> Model(
                    modelId = el.content,
                    displayName = el.content,
                )
                is JsonObject -> {
                    val mid = el["modelId"]?.jsonPrimitive?.contentOrNull
                        ?: el["id"]?.jsonPrimitive?.contentOrNull
                        ?: throw PluginException(providerSetting.pluginId, "provider.listModels", "模型对象缺少 modelId/id")
                    Model(
                        modelId = mid,
                        displayName = el["displayName"]?.jsonPrimitive?.contentOrNull
                            ?: el["name"]?.jsonPrimitive?.contentOrNull
                            ?: mid,
                    )
                }
                else -> throw PluginException(providerSetting.pluginId, "provider.listModels", "模型项格式不合法")
            }
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Plugin,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val req = requestJson(providerSetting, messages, params)
        val result = host.callMethod(
            pluginId = providerSetting.pluginId,
            kind = PluginHookKind.PROVIDER,
            name = providerSetting.providerName,
            method = "streamText",
            args = listOf(req),
            timeoutMillis = null,
        )
        val chunks = parseChunks(providerSetting.pluginId, result)
        for (c in chunks) emit(c)
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Plugin,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val (rt, reg) = host.findNamed(PluginHookKind.PROVIDER, providerSetting.pluginId, providerSetting.providerName)
            ?: throw PluginException(providerSetting.pluginId, "provider", "插件未启用或没有注册 provider \"${providerSetting.providerName}\"")
        val methods = ((reg.meta as JsonObject)["methods"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val req = requestJson(providerSetting, messages, params)
        if ("generateText" in methods) {
            val result = rt.invokeMethod(PluginHookKind.PROVIDER, reg.handlerId, "generateText", listOf(req), timeoutMillis = null)
            return when (result) {
                is JsonPrimitive -> TextGenerationResult(
                    id = UUID.randomUUID().toString(),
                    model = params.model.modelId,
                    message = UIMessage.assistant(result.content),
                )
                is JsonObject -> {
                    if ("message" in result) {
                        JsonInstant.decodeFromJsonElement(TextGenerationResult.serializer(), result)
                    } else {
                        val text = result["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        TextGenerationResult(
                            id = result["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
                            model = result["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId,
                            message = UIMessage.assistant(text),
                            finishReason = result["finishReason"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
                else -> throw PluginException(providerSetting.pluginId, "provider.generateText", "返回值格式不合法")
            }
        }
        // 未提供 generateText 时自动退化为收集 streamText
        val handler = StreamChunkHandler(params.model)
        var acc = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id))
        streamText(providerSetting, messages, params).collect { chunk ->
            acc = handler.handle(acc, chunk)
        }
        val finalMsg = acc.last()
        return TextGenerationResult(
            id = UUID.randomUUID().toString(),
            model = params.model.modelId,
            message = finalMsg,
            usage = finalMsg.usage ?: TokenUsage(),
        )
    }

    private fun parseChunks(pluginId: String, result: JsonElement): List<StreamChunk> {
        return when (result) {
            is JsonNull -> emptyList()
            is JsonPrimitive -> listOf(
                StreamChunk.TextStart("t0"),
                StreamChunk.TextDelta("t0", result.content),
                StreamChunk.TextEnd("t0"),
                StreamChunk.Finish("stop"),
            )
            is JsonArray -> result.map { el ->
                when (el) {
                    is JsonPrimitive -> StreamChunk.TextDelta("t0", el.content)
                    is JsonObject -> try {
                        JsonInstant.decodeFromJsonElement(StreamChunk.serializer(), el)
                    } catch (e: Exception) {
                        throw PluginException(pluginId, "provider.streamText", "非法 StreamChunk $el: ${e.message}", e)
                    }
                    else -> throw PluginException(pluginId, "provider.streamText", "非法 StreamChunk: $el")
                }
            }
            is JsonObject -> listOf(
                try {
                    JsonInstant.decodeFromJsonElement(StreamChunk.serializer(), result)
                } catch (e: Exception) {
                    throw PluginException(pluginId, "provider.streamText", "非法 StreamChunk $result: ${e.message}", e)
                }
            )
        }
    }
}

/**
 * 插件通过 `rikka.registerTTS` 注册的 TTS 实现。
 */
class PluginTtsProvider(private val host: PluginHost) : TTSProvider<TTSProviderSetting.Plugin> {
    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Plugin,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        val opts = buildJsonObject {
            put("voice", providerSetting.voice)
        }
        val result = host.callMethod(
            pluginId = providerSetting.pluginId,
            kind = PluginHookKind.TTS,
            name = providerSetting.providerName,
            method = "speak",
            args = listOf(JsonPrimitive(request.text), opts),
        )
        val obj = result as? JsonObject
            ?: throw PluginException(providerSetting.pluginId, "tts.speak", "speak() 必须返回 { audio: base64, format?, sampleRate? }")
        val b64 = obj["audio"]?.jsonPrimitive?.contentOrNull
            ?: throw PluginException(providerSetting.pluginId, "tts.speak", "返回结果缺少 audio (base64)")
        val formatStr = obj["format"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "MP3"
        val format = runCatching { AudioFormat.valueOf(formatStr) }.getOrDefault(AudioFormat.MP3)
        val sampleRate = obj["sampleRate"]?.jsonPrimitive?.intOrNull
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        emit(
            AudioChunk(
                data = bytes,
                format = format,
                sampleRate = sampleRate,
                isLast = true,
            )
        )
    }
}

/**
 * 插件通过 `rikka.registerMediaGen` 注册的媒体生成实现。
 */
class PluginMediaGenProvider(private val host: PluginHost) : MediaGenerationProvider<MediaGenerationProviderSetting.Plugin> {
    override val id: String = "plugin"

    override suspend fun create(
        setting: MediaGenerationProviderSetting.Plugin,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        val arg = buildJsonObject {
            put("model", model.modelId)
            put("kind", model.kind.name.lowercase())
            put("apiKey", setting.apiKey)
            put("baseUrl", setting.baseUrl)
            put("request", JsonInstant.encodeToJsonElement(MediaGenerationRequest.serializer(), request))
        }
        val res = host.callMethod(
            pluginId = setting.pluginId,
            kind = PluginHookKind.MEDIAGEN,
            name = setting.providerName,
            method = "create",
            args = listOf(arg),
            timeoutMillis = null,
        )
        decodeTask(setting, model, res)
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.Plugin,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = runCatching {
        val arg = buildJsonObject {
            put("taskId", taskId)
            put("model", model.modelId)
            put("kind", model.kind.name.lowercase())
            put("apiKey", setting.apiKey)
            put("baseUrl", setting.baseUrl)
        }
        val res = host.callMethod(
            pluginId = setting.pluginId,
            kind = PluginHookKind.MEDIAGEN,
            name = setting.providerName,
            method = "query",
            args = listOf(arg),
            timeoutMillis = null,
        )
        decodeTask(setting, model, res)
    }

    private fun decodeTask(
        setting: MediaGenerationProviderSetting.Plugin,
        model: MediaGenerationModel,
        element: JsonElement,
    ): MediaGenerationTask {
        val obj = element as? JsonObject
            ?: throw PluginException(setting.pluginId, "mediagen", "返回值必须是任务对象")
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val statusName = obj["status"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "SUCCEEDED"
        val status = runCatching { MediaGenerationStatus.valueOf(statusName) }.getOrDefault(MediaGenerationStatus.SUCCEEDED)
        val defaultMime = if (model.kind == me.rerere.mediagen.model.MediaKind.VIDEO) "video/mp4" else "image/png"
        val outputs = (obj["outputs"] as? JsonArray)?.map { outEl ->
            when (outEl) {
                is JsonPrimitive -> MediaGenerationOutput(url = outEl.content, mimeType = defaultMime)
                is JsonObject -> {
                    val url = outEl["url"]?.jsonPrimitive?.contentOrNull
                    val b64 = outEl["base64"]?.jsonPrimitive?.contentOrNull ?: outEl["data"]?.jsonPrimitive?.contentOrNull
                    val mime = outEl["mimeType"]?.jsonPrimitive?.contentOrNull ?: defaultMime
                    MediaGenerationOutput(
                        url = url,
                        data = b64?.let { Base64.decode(it, Base64.DEFAULT) },
                        mimeType = mime,
                    )
                }
                else -> throw PluginException(setting.pluginId, "mediagen", "outputs 项格式不合法")
            }
        }.orEmpty()
        val error = (obj["error"] as? JsonObject)?.let { errObj ->
            MediaGenerationError(
                code = errObj["code"]?.jsonPrimitive?.contentOrNull,
                message = errObj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
            )
        } ?: (obj["error"] as? JsonPrimitive)?.contentOrNull?.let { MediaGenerationError(message = it) }
        return MediaGenerationTask(
            id = id,
            provider = setting.name.ifBlank { setting.providerName },
            model = model.modelId,
            status = status,
            outputs = outputs,
            error = error,
        )
    }
}
