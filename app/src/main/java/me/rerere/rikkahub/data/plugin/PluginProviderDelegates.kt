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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
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
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * 解析插件声明或 `listModels()` 返回的模型项，支持完整 [Model] 能力元数据：
 * - 字符串简写：`"gpt-4o"`（默认赋予 `TOOL` + `REASONING` 能力与图文输入）
 * - 对象：`{ modelId/id, displayName/name, type?, abilities?, inputModalities?, outputModalities?, tools?, contextLength?, headers?, body? }`
 * 格式不合法或取值无法识别时抛 [PluginException]。
 */
internal fun parsePluginModel(
    pluginId: String,
    providerName: String,
    el: JsonElement,
    stableId: Boolean,
): Model {
    fun makeId(modelId: String): Uuid =
        if (stableId) PluginCapabilitySync.stableUuid("model", pluginId, "$providerName:$modelId") else Uuid.random()
    fun invalid(msg: String): Nothing = throw PluginException(pluginId, "provider.models", "$providerName: $msg")
    fun <T> names(key: String, parse: (String) -> T?): List<T>? = (el as JsonObject)[key]?.let { v ->
        (v as? JsonArray ?: invalid("$key 必须是数组")).map { item ->
            val raw = item.jsonPrimitive.content
            parse(raw) ?: invalid("$key 中的取值无法识别: $raw")
        }
    }

    return when (el) {
        is JsonPrimitive -> {
            val mid = el.content.trim()
            if (mid.isEmpty()) invalid("模型 id 不能为空")
            Model(
                id = makeId(mid),
                modelId = mid,
                displayName = mid,
                type = ModelType.CHAT,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            )
        }
        is JsonObject -> {
            val mid = el["modelId"]?.jsonPrimitive?.contentOrNull
                ?: el["id"]?.jsonPrimitive?.contentOrNull
                ?: invalid("模型对象缺少 modelId: $el")
            val displayName = el["displayName"]?.jsonPrimitive?.contentOrNull
                ?: el["name"]?.jsonPrimitive?.contentOrNull
                ?: mid
            val modelType = el["type"]?.jsonPrimitive?.content?.let { t ->
                ModelType.entries.find { it.name == t.uppercase() } ?: invalid("type 无法识别: $t")
            } ?: ModelType.CHAT
            val abilities = names("abilities") { a -> ModelAbility.entries.find { it.name == a.uppercase() } }
                ?: if (modelType == ModelType.CHAT) listOf(ModelAbility.TOOL, ModelAbility.REASONING) else emptyList()
            val inputModalities = names("inputModalities") { m -> Modality.entries.find { it.name == m.uppercase() } }
                ?: listOf(Modality.TEXT, Modality.IMAGE)
            val outputModalities = names("outputModalities") { m -> Modality.entries.find { it.name == m.uppercase() } }
                ?: if (modelType == ModelType.IMAGE) listOf(Modality.IMAGE) else listOf(Modality.TEXT)
            val builtInTools = names("tools") { t ->
                when (t.lowercase()) {
                    "search" -> BuiltInTools.Search
                    "url_context" -> BuiltInTools.UrlContext
                    "image_generation" -> BuiltInTools.ImageGeneration
                    else -> null
                }
            }?.toSet() ?: emptySet()
            val contextLength = el["contextLength"]?.jsonPrimitive?.intOrNull ?: 1_000_000
            val customHeaders = (el["headers"] as? JsonObject)?.entries?.map { (k, v) ->
                CustomHeader(k, (v as? JsonPrimitive)?.content ?: v.toString())
            } ?: emptyList()
            val customBodies = (el["body"] as? JsonObject)?.entries?.map { (k, v) ->
                CustomBody(k, v)
            } ?: emptyList()
            Model(
                id = makeId(mid),
                modelId = mid,
                displayName = displayName,
                type = modelType,
                inputModalities = inputModalities,
                outputModalities = outputModalities,
                abilities = abilities,
                tools = builtInTools,
                contextLength = contextLength,
                customHeaders = customHeaders,
                customBodies = customBodies,
            )
        }
        else -> invalid("模型项格式不合法: $el")
    }
}

/**
 * 将已启用插件通过 `registerProvider` / `registerSearch` / `registerTTS` / `registerASR` / `registerMediaGen` /
 * `registerLorebook` / `registerQuickMessage` 注册的能力实现自动同步到 Settings 中：
 * - 插件启用（且该注册项未被单独关闭）时自动出现在可用服务/列表中；
 * - 插件停用、卸载或单独关闭该能力项时自动移除；
 * - 用户无需也不应在设置界面手动新建 `Plugin` 渠道。
 */
object PluginCapabilitySync {
    private const val LOREBOOK_MAGIC_PREFIX = "524b504c-4c42-" // "RKPL-LB-"
    private const val QUICK_MSG_MAGIC_PREFIX = "524b504c-514d-" // "RKPL-QM-"

    internal fun stableUuid(kind: String, pluginId: String, name: String): Uuid {
        val raw = UUID.nameUUIDFromBytes("rikkanon.plugin.$kind:$pluginId:$name".toByteArray()).toString()
        val patched = when (kind) {
            PluginHookKind.LOREBOOK -> LOREBOOK_MAGIC_PREFIX + raw.substring(LOREBOOK_MAGIC_PREFIX.length)
            PluginHookKind.QUICK_MESSAGE -> QUICK_MSG_MAGIC_PREFIX + raw.substring(QUICK_MSG_MAGIC_PREFIX.length)
            else -> raw
        }
        return Uuid.parse(patched)
    }

    private fun isPluginManagedLorebook(id: Uuid): Boolean =
        id.toString().startsWith(LOREBOOK_MAGIC_PREFIX, ignoreCase = true)

    private fun isPluginManagedQuickMessage(id: Uuid): Boolean =
        id.toString().startsWith(QUICK_MSG_MAGIC_PREFIX, ignoreCase = true)

    suspend fun sync(host: PluginHost, settingsStore: SettingsStore) {
        val activeProviders = host.chain(PluginHookKind.PROVIDER)
        val activeSearches = host.chain(PluginHookKind.SEARCH)
        val activeTts = host.chain(PluginHookKind.TTS)
        val activeAsr = host.chain(PluginHookKind.ASR)
        val activeMediaGen = host.chain(PluginHookKind.MEDIAGEN)
        val activeLorebooks = host.chain(PluginHookKind.LOREBOOK)
        val activeQuickMessages = host.chain(PluginHookKind.QUICK_MESSAGE)

        settingsStore.update { settings ->
            // 1. AI Providers
            val existingPluginProviders = settings.providers.filterIsInstance<ProviderSetting.Plugin>()
                .associateBy { it.pluginId to it.providerName }
            val syncedPluginProviders = activeProviders.mapNotNull { (rt, reg) ->
                val meta = reg.meta.jsonObject
                val name = meta["name"]!!.jsonPrimitive.content
                val displayName = meta["displayName"]?.jsonPrimitive?.contentOrNull ?: "${rt.manifest.name} ($name)"
                val prev = existingPluginProviders[rt.id to name]
                // 模型声明不合法：记入插件错误，保留已有配置不变
                val staticModels = runCatching {
                    meta["models"]?.jsonArray?.map { el -> parsePluginModel(rt.id, name, el, stableId = true) }.orEmpty()
                }.getOrElse {
                    host.recordError(rt.id, it)
                    return@mapNotNull prev
                }
                if (prev != null) {
                    val prevById = prev.models.associateBy { it.modelId }
                    val mergedModels = if (staticModels.isNotEmpty()) {
                        staticModels.map { sm -> prevById[sm.modelId]?.let { pm -> sm.copy(id = pm.id) } ?: sm } +
                            prev.models.filter { pm -> staticModels.none { it.modelId == pm.modelId } }
                    } else prev.models
                    prev.copy(models = mergedModels)
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
                val methods = meta.getValue("methods").jsonArray.map { it.jsonPrimitive.content }
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

            // 6. Lorebooks (registerLorebook)
            val existingPluginLorebooks = settings.lorebooks.filter { isPluginManagedLorebook(it.id) }.associateBy { it.id }
            val syncedPluginLorebooks = activeLorebooks.mapNotNull { (rt, reg) ->
                val meta = reg.meta.jsonObject
                val name = meta.getValue("name").jsonPrimitive.content
                val lbId = stableUuid(PluginHookKind.LOREBOOK, rt.id, name)
                val prev = existingPluginLorebooks[lbId]
                // 条目不合法：记入插件错误，保留已有世界书不变
                val entries = runCatching {
                    meta.getValue("entries").jsonArray.mapIndexed { idx, el ->
                        val obj = el as? JsonObject
                            ?: throw PluginException(rt.id, "lorebook", "$name: 第 ${idx + 1} 个条目必须是对象")
                        val entryWithId = if ("id" in obj) obj else JsonObject(
                            obj + ("id" to JsonPrimitive(stableUuid("lb_entry", rt.id, "$name#$idx").toString()))
                        )
                        runCatching {
                            JsonInstant.decodeFromJsonElement(PromptInjection.RegexInjection.serializer(), entryWithId)
                        }.getOrElse { e ->
                            throw PluginException(rt.id, "lorebook", "$name: 第 ${idx + 1} 个条目格式不合法: ${e.message}")
                        }
                    }
                }.getOrElse {
                    host.recordError(rt.id, it)
                    return@mapNotNull prev
                }
                Lorebook(
                    id = lbId,
                    name = name,
                    description = meta.getValue("description").jsonPrimitive.content,
                    enabled = prev?.enabled ?: true,
                    entries = entries,
                )
            }
            val newLorebooks = settings.lorebooks.filterNot { isPluginManagedLorebook(it.id) } + syncedPluginLorebooks

            // 7. QuickMessages (registerQuickMessage)
            val syncedPluginQuickMessages = activeQuickMessages.map { (rt, reg) ->
                val meta = reg.meta.jsonObject
                QuickMessage(
                    id = stableUuid(PluginHookKind.QUICK_MESSAGE, rt.id, meta.getValue("name").jsonPrimitive.content),
                    title = meta.getValue("title").jsonPrimitive.content,
                    content = meta.getValue("content").jsonPrimitive.content,
                )
            }
            val newQuickMessages = settings.quickMessages.filterNot { isPluginManagedQuickMessage(it.id) } + syncedPluginQuickMessages

            if (newProviders == settings.providers &&
                newSearches == settings.searchServices &&
                newTts == settings.ttsProviders &&
                newAsr == settings.asrProviders &&
                newMediaGen == settings.mediaGenerationProviders &&
                newLorebooks == settings.lorebooks &&
                newQuickMessages == settings.quickMessages
            ) {
                settings
            } else {
                settings.copy(
                    providers = newProviders,
                    searchServices = newSearches,
                    ttsProviders = newTts,
                    asrProviders = newAsr,
                    mediaGenerationProviders = newMediaGen,
                    lorebooks = newLorebooks,
                    quickMessages = newQuickMessages,
                )
            }
        }
    }
}

/**
 * 插件通过 `rikka.registerProvider` 注册的 AI Provider 实现。
 * 支持真流式 `streamText`（AsyncIterator 逐块 yield）、`listModels`、`generateText`、`getBalance`、`generateEmbedding`、`generateImage`、`editImage`。
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
            parsePluginModel(providerSetting.pluginId, providerSetting.providerName, el, stableId = false)
        }
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.Plugin): String {
        val result = host.callMethod(
            pluginId = providerSetting.pluginId,
            kind = PluginHookKind.PROVIDER,
            name = providerSetting.providerName,
            method = "getBalance",
            args = listOf(configJson(providerSetting)),
        )
        return (result as? JsonPrimitive)?.contentOrNull ?: result.toString()
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Plugin,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val req = requestJson(providerSetting, messages, params)
        var autoTextStarted = false
        var sawFinish = false
        host.callMethodStream(
            pluginId = providerSetting.pluginId,
            kind = PluginHookKind.PROVIDER,
            name = providerSetting.providerName,
            method = "streamText",
            args = listOf(req),
            timeoutMillis = null,
        ).collect { element ->
            when (element) {
                is JsonNull -> Unit
                is JsonPrimitive -> {
                    if (!autoTextStarted) {
                        autoTextStarted = true
                        emit(StreamChunk.TextStart("t0"))
                    }
                    emit(StreamChunk.TextDelta("t0", element.content))
                }
                else -> {
                    val chunks = parseChunks(providerSetting.pluginId, element)
                    for (c in chunks) {
                        if (c is StreamChunk.Finish) sawFinish = true
                        emit(c)
                    }
                }
            }
        }
        if (autoTextStarted) {
            emit(StreamChunk.TextEnd("t0"))
        }
        if (autoTextStarted && !sawFinish) {
            emit(StreamChunk.Finish("stop"))
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Plugin,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val (rt, reg) = host.findNamed(PluginHookKind.PROVIDER, providerSetting.pluginId, providerSetting.providerName)
            ?: throw PluginException(providerSetting.pluginId, "provider", "插件未启用或没有注册 provider \"${providerSetting.providerName}\"")
        val req = requestJson(providerSetting, messages, params)
        if (host.hasMethod(providerSetting.pluginId, PluginHookKind.PROVIDER, providerSetting.providerName, "generateText")) {
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

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.Plugin,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        val req = buildJsonObject {
            put("config", configJson(providerSetting))
            put("model", params.model.modelId)
            put("input", JsonArray(params.input.map(::JsonPrimitive)))
            params.dimensions?.let { put("dimensions", it) }
        }
        val res = host.callMethod(
            pluginId = providerSetting.pluginId,
            kind = PluginHookKind.PROVIDER,
            name = providerSetting.providerName,
            method = "generateEmbedding",
            args = listOf(req),
            timeoutMillis = null,
        )
        val obj = res as? JsonObject
            ?: throw PluginException(providerSetting.pluginId, "provider.generateEmbedding", "返回值必须是对象")
        val embeddings = (obj["embeddings"] as? JsonArray
            ?: throw PluginException(providerSetting.pluginId, "provider.generateEmbedding", "返回值缺少 embeddings 数组"))
            .map { row ->
                (row as? JsonArray
                    ?: throw PluginException(providerSetting.pluginId, "provider.generateEmbedding", "embeddings 的每一项必须是数字数组"))
                    .map {
                        it.jsonPrimitive.floatOrNull
                            ?: throw PluginException(providerSetting.pluginId, "provider.generateEmbedding", "embeddings 中含有非数字: $it")
                    }
            }
        return EmbeddingGenerationResult(
            model = obj["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId,
            embeddings = embeddings,
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val ps = providerSetting as ProviderSetting.Plugin
        val req = buildJsonObject {
            put("config", configJson(ps))
            put("model", params.model.modelId)
            put("prompt", params.prompt)
            put("numOfImages", params.numOfImages)
            put("size", params.size)
        }
        host.callMethodStream(
            pluginId = ps.pluginId,
            kind = PluginHookKind.PROVIDER,
            name = ps.providerName,
            method = "generateImage",
            args = listOf(req),
            timeoutMillis = null,
        ).collect { el ->
            for (item in parseImageItems(ps.pluginId, el)) emit(item)
        }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = flow {
        val ps = providerSetting as ProviderSetting.Plugin
        val req = buildJsonObject {
            put("config", configJson(ps))
            put("model", params.model.modelId)
            put("prompt", params.prompt)
            put("images", JsonArray(params.images.map(::JsonPrimitive)))
            put("numOfImages", params.numOfImages)
            put("size", params.size)
        }
        host.callMethodStream(
            pluginId = ps.pluginId,
            kind = PluginHookKind.PROVIDER,
            name = ps.providerName,
            method = "editImage",
            args = listOf(req),
            timeoutMillis = null,
        ).collect { el ->
            for (item in parseImageItems(ps.pluginId, el)) emit(item)
        }
    }

    private fun parseImageItems(pluginId: String, el: JsonElement): List<ImageGenerationItem> {
        return when (el) {
            is JsonArray -> el.flatMap { child -> parseImageItems(pluginId, child) }
            is JsonObject -> {
                val data = el["data"]?.jsonPrimitive?.contentOrNull
                    ?: throw PluginException(pluginId, "provider.generateImage", "图片对象缺少 data: $el")
                listOf(
                    ImageGenerationItem(
                        data = data,
                        mimeType = el["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png",
                        partial = el["partial"]?.jsonPrimitive?.booleanOrNull ?: false,
                        partialImageIndex = el["partialImageIndex"]?.jsonPrimitive?.intOrNull,
                    )
                )
            }
            is JsonPrimitive -> if (el.isString) {
                listOf(ImageGenerationItem(data = el.content, mimeType = "image/png"))
            } else {
                throw PluginException(pluginId, "provider.generateImage", "图片结果格式不合法（应为 base64 字符串或 { data, mimeType? }）: $el")
            }
        }
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
 * 插件通过 `rikka.registerTTS` 注册的 TTS 实现（支持单次返回或 AsyncIterator 流式返回音频块）。
 */
class PluginTtsProvider(private val host: PluginHost) : TTSProvider<TTSProviderSetting.Plugin> {
    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Plugin,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        val opts = buildJsonObject {
            put("id", providerSetting.id.toString())
            put("name", providerSetting.name)
            put("voice", providerSetting.voice)
        }
        host.callMethodStream(
            pluginId = providerSetting.pluginId,
            kind = PluginHookKind.TTS,
            name = providerSetting.providerName,
            method = "speak",
            args = listOf(JsonPrimitive(request.text), opts),
            timeoutMillis = null,
        ).collect { element ->
            val items = when (element) {
                is JsonArray -> element.map {
                    it as? JsonObject
                        ?: throw PluginException(providerSetting.pluginId, "tts.speak", "speak() 返回的数组中每一项必须是 { audio, format?, sampleRate? }")
                }
                is JsonObject -> listOf(element)
                else -> throw PluginException(providerSetting.pluginId, "tts.speak", "speak() 必须返回 { audio: base64, format?, sampleRate? }")
            }
            for ((idx, obj) in items.withIndex()) {
                val b64 = obj["audio"]?.jsonPrimitive?.contentOrNull
                    ?: throw PluginException(providerSetting.pluginId, "tts.speak", "返回结果缺少 audio (base64)")
                val formatStr = obj["format"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "MP3"
                val format = AudioFormat.entries.find { it.name == formatStr }
                    ?: throw PluginException(providerSetting.pluginId, "tts.speak", "不支持的音频格式: $formatStr")
                val sampleRate = obj["sampleRate"]?.jsonPrimitive?.intOrNull
                val isLast = obj["isLast"]?.jsonPrimitive?.booleanOrNull ?: (idx == items.lastIndex)
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                emit(
                    AudioChunk(
                        data = bytes,
                        format = format,
                        sampleRate = sampleRate,
                        isLast = isLast,
                    )
                )
            }
        }
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
        val defaultMime = if (model.kind == MediaKind.VIDEO) "video/mp4" else "image/png"
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
