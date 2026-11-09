package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.DISPLAY_ONLY_PART_KEY
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.mapRawJsonStrings
import me.rerere.ai.ui.rawJsonElementOrNull
import me.rerere.ai.ui.toMetadata
import me.rerere.plugin.error.PluginException
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.host.PluginToolProgress
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.plugin.registry.PluginRegistration
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegexPhase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.PromptBlock
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import kotlin.uuid.Uuid

/** 工具部件 metadata：插件 approval.decide 决定该工具由主代理审批时写入。 */
const val PLUGIN_MAIN_AGENT_APPROVAL_KEY = "rikka.mainAgentApproval"

/** 传给插件的统一上下文。 */
data class PluginCtx(
    val task: String = "chat",
    val assistantId: Uuid? = null,
    val conversationId: Uuid? = null,
    val isSubAgent: Boolean = false,
    val parentConversationId: Uuid? = null,
    val subAgentCode: String? = null,
    val stepIndex: Int? = null,
    val model: Model? = null,
    val workspaceId: Uuid? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("task", task)
        assistantId?.let { put("assistantId", it.toString()) }
        conversationId?.let { put("conversationId", it.toString()) }
        put("isSubAgent", isSubAgent)
        parentConversationId?.let { put("parentConversationId", it.toString()) }
        subAgentCode?.let { put("subAgentCode", it) }
        stepIndex?.let { put("stepIndex", it) }
        model?.let {
            put("model", buildJsonObject {
                put("id", it.id.toString())
                put("modelId", it.modelId)
                put("displayName", it.displayName)
            })
        }
        workspaceId?.let { put("workspaceId", it.toString()) }
    }
}

sealed interface ToolBeforeResult {
    data class Proceed(val args: JsonElement) : ToolBeforeResult
    data class Deny(val reason: String) : ToolBeforeResult
    data class Result(val parts: List<UIMessagePart>) : ToolBeforeResult
}

/** 后台任务 before 钩子结果：Proceed 为继续调用模型（可能改了输入），ShortCircuit 为直接给出输出。 */
sealed interface TaskBeforeResult<T> {
    data class Proceed<T>(val value: T) : TaskBeforeResult<T>
    data class ShortCircuit<T>(val output: String) : TaskBeforeResult<T>
}

/**
 * app 与插件注册表之间的桥：各挂点从这里取对应 hook 链并调用。
 * 插件只有全局开关；没有可用 hook 时所有方法都是零开销直通。
 */
class PluginBridge(val host: PluginHost) {
    /** 当前运行中的进度汇报协程上下文元素。 */
    class ToolProgressElement(val onProgress: (List<UIMessagePart>) -> Unit) :
        kotlin.coroutines.AbstractCoroutineContextElement(ToolProgressElement) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<ToolProgressElement>
    }

    @Suppress("UNUSED_PARAMETER")
    private fun chain(kind: String, assistant: Assistant? = null) = host.chain(kind)

    fun hasHook(kind: String, assistant: Assistant? = null): Boolean = chain(kind).isNotEmpty()

    // ---------- 工具 ----------

    /**
     * 插件注册的工具。[existingNames] 为内置 / MCP 工具名，重名直接报错。
     * 声明了 `when(ctx)` 的工具按当前上下文筛选。
     */
    suspend fun createTools(ctx: PluginCtx, existingNames: Set<String>): List<Tool> {
        val registered = host.tools()
        if (registered.isEmpty()) return emptyList()
        return registered.filter { (runtime, reg) ->
            if (reg.meta.jsonObject["hasWhen"]?.jsonPrimitive?.contentOrNull != "true") return@filter true
            val r = runtime.invokeInternal(
                "tool.when", "__tool_when",
                buildJsonObject {
                    put("handler", reg.handlerId)
                    put("ctx", ctx.toJson())
                },
            )
            r.jsonPrimitive.contentOrNull == "true"
        }.map { (runtime, reg) ->
            val meta = reg.meta.jsonObject
            val name = reg.toolName
            if (name in existingNames) {
                throw PluginException(runtime.id, "tool", "工具名 \"$name\" 与内置或 MCP 工具冲突")
            }
            val schema = meta["parameters"] as? JsonObject
            val approval = meta["approval"]?.jsonPrimitive?.contentOrNull ?: "auto"
            Tool(
                name = name,
                description = meta["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                parameters = { schema?.let(::toInputSchema) },
                systemPrompt = { _, _ -> meta["systemPrompt"]?.jsonPrimitive?.contentOrNull.orEmpty() },
                needsApproval = { approval == "user" },
                display = meta["display"] as? JsonObject,
                endTurn = meta["endTurn"]?.jsonPrimitive?.contentOrNull == "true",
                execute = { args ->
                    val progressElement = kotlinx.coroutines.currentCoroutineContext()[ToolProgressElement]
                    val token = PluginToolProgress.register { rawParts ->
                        progressElement?.onProgress?.invoke(partsOf(runtime, "tool.progress", rawParts))
                    }
                    try {
                        val result = runtime.invokeInternal(
                            "tool", "__tool",
                            buildJsonObject {
                                put("handler", reg.handlerId)
                                put("token", token)
                                put("args", args)
                                put("ctx", ctx.toJson())
                            },
                        )
                        toolResultToParts(runtime, result)
                    } finally {
                        PluginToolProgress.remove(token)
                    }
                },
            )
        }
    }

    /**
     * `tools.list`：组装完工具列表后，允许插件过滤、修改描述/参数或调整工具列表。
     * 插件不能凭空新增宿主里没有 execute 的工具，只允许保留/过滤已有工具并可覆盖 description / parameters / systemPrompt / endTurn / display。
     */
    suspend fun filterToolsList(tools: List<Tool>, ctx: PluginCtx): List<Tool> {
        val handlers = chain(PluginHookKind.TOOLS_LIST)
        if (handlers.isEmpty()) return tools
        var current = tools
        for ((runtime, reg) in handlers) {
            val encoded = JsonArray(current.map { t ->
                buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    t.parameters()?.let { put("parameters", JsonInstant.encodeToJsonElement(InputSchema.serializer(), it)) }
                    t.display?.let { put("display", it) }
                    put("endTurn", t.endTurn)
                }
            })
            val result = runtime.invoke(PluginHookKind.TOOLS_LIST, reg.handlerId, listOf(encoded, ctx.toJson()))
            if (result is JsonNull) continue
            val arr = result as? JsonArray
                ?: throw PluginException(runtime.id, PluginHookKind.TOOLS_LIST, "tools.list 必须返回工具数组或字符串数组")
            val byName = current.associateBy { it.name }
            current = arr.map { el ->
                when (el) {
                    is JsonPrimitive -> byName[el.content]
                        ?: throw PluginException(runtime.id, PluginHookKind.TOOLS_LIST, "未知工具名: ${el.content}")
                    is JsonObject -> {
                        val name = el["name"]?.jsonPrimitive?.contentOrNull
                            ?: throw PluginException(runtime.id, PluginHookKind.TOOLS_LIST, "工具对象缺少 name")
                        val base = byName[name]
                            ?: throw PluginException(runtime.id, PluginHookKind.TOOLS_LIST, "未知工具名: $name（新增工具请使用 rikka.registerTool）")
                        val newDesc = el["description"]?.jsonPrimitive?.contentOrNull ?: base.description
                        val newSchema = (el["parameters"] as? JsonObject)?.let(::toInputSchema)
                        val newDisplay = (el["display"] as? JsonObject) ?: base.display
                        val newEndTurn = el["endTurn"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: base.endTurn
                        val newSysPrompt = el["systemPrompt"]?.jsonPrimitive?.contentOrNull
                        base.copy(
                            description = newDesc,
                            parameters = if (newSchema != null) ({ newSchema }) else base.parameters,
                            systemPrompt = if (newSysPrompt != null) ({ _, _ -> newSysPrompt }) else base.systemPrompt,
                            display = newDisplay,
                            endTurn = newEndTurn,
                        )
                    }
                    else -> throw PluginException(runtime.id, PluginHookKind.TOOLS_LIST, "tools.list 数组项必须是对象或字符串")
                }
            }
        }
        return current
    }

    private fun toInputSchema(schema: JsonObject): InputSchema = InputSchema.Obj(
        properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap()),
        required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content },
    )

    /** 工具返回：字符串、Part 数组，或 `{ model, display }`；display 部件标记为仅展示。 */
    private fun toolResultToParts(runtime: PluginRuntime, result: JsonElement): List<UIMessagePart> {
        val (model, display) = when {
            result is JsonObject && ("model" in result || "display" in result) -> result["model"] to result["display"]
            else -> result to null
        }
        val modelParts = partsOf(runtime, "tool", model ?: JsonNull)
        val displayParts = display?.let { partsOf(runtime, "tool", it) }.orEmpty().map { part ->
            val metadata = JsonObject((part.metadata ?: JsonObject(emptyMap())) + (DISPLAY_ONLY_PART_KEY to JsonPrimitive(true)))
            when (part) {
                is UIMessagePart.Text -> part.copy(metadata = metadata)
                else -> throw PluginException(runtime.id, "tool", "display 目前只支持 text 部件")
            }
        }
        return modelParts + displayParts
    }

    private fun partsOf(runtime: PluginRuntime, hook: String, element: JsonElement): List<UIMessagePart> = when (element) {
        is JsonNull -> listOf(UIMessagePart.Text(""))
        is JsonPrimitive -> listOf(UIMessagePart.Text(element.content))
        is JsonArray -> decodeParts(runtime, hook, element)
        is JsonObject -> decodeParts(runtime, hook, JsonArray(listOf(element)))
    }

    private fun decodeParts(runtime: PluginRuntime, hook: String, element: JsonElement): List<UIMessagePart> =
        try {
            JsonInstant.decodeFromJsonElement(ListSerializer(UIMessagePart.serializer()), element)
        } catch (e: Exception) {
            throw PluginException(runtime.id, hook, "返回的部件格式不合法: ${e.message}", e)
        }

    // ---------- 消息与请求参数 ----------

    private suspend fun messageChain(kind: String, messages: List<UIMessage>, assistant: Assistant, ctx: PluginCtx): List<UIMessage> {
        val handlers = chain(kind, assistant)
        if (handlers.isEmpty()) return messages
        var current = messages
        for ((runtime, reg) in handlers) {
            val synById = current.associate { it.id.toString() to it.isSynthetic }
            val encoded = JsonArray(
                current.map { msg ->
                    val obj = JsonInstant.encodeToJsonElement(UIMessage.serializer(), msg).jsonObject
                    val extra = LinkedHashMap<String, JsonElement>(obj)
                    extra["isSynthetic"] = JsonPrimitive(msg.isSynthetic)
                    msg.rawJsonElementOrNull()?.let { extra["rawJson"] = it }
                    JsonObject(extra)
                }
            )
            val result = runtime.invoke(kind, reg.handlerId, listOf(encoded, ctx.toJson()))
            if (result is JsonNull) continue
            val arr = result as? JsonArray
                ?: throw PluginException(runtime.id, kind, "返回的消息必须是数组或 undefined")
            current = try {
                arr.map { el ->
                    val obj = el as? JsonObject
                        ?: throw IllegalArgumentException("消息项必须是对象")
                    val explicitSyn = (obj["isSynthetic"] as? JsonPrimitive)?.booleanOrNull
                    val explicitRawJson = obj["rawJson"]
                    val decoded = JsonInstant.decodeFromJsonElement(UIMessage.serializer(), obj)
                    val isSyn = explicitSyn ?: synById[decoded.id.toString()] ?: false
                    val withSyn = if (isSyn != decoded.isSynthetic) decoded.copy(isSynthetic = isSyn) else decoded
                    if (explicitRawJson != null && explicitRawJson !is JsonNull && explicitRawJson != withSyn.rawJsonElementOrNull()) {
                        val rawMeta = me.rerere.ai.ui.RawJsonMessageMetadata(rawJson = explicitRawJson).toMetadata()
                        val updatedParts = if (withSyn.parts.isEmpty()) {
                            listOf(UIMessagePart.Text(text = "", metadata = rawMeta))
                        } else {
                            withSyn.parts.mapIndexed { idx, p ->
                                if (idx == 0 && p is UIMessagePart.Text) {
                                    p.copy(metadata = JsonObject((p.metadata ?: JsonObject(emptyMap())) + rawMeta))
                                } else p
                            }
                        }
                        withSyn.copy(parts = updatedParts, isSynthetic = true)
                    } else {
                        withSyn
                    }
                }
            } catch (e: Exception) {
                throw PluginException(runtime.id, kind, "返回的消息格式不合法: ${e.message}", e)
            }
        }
        return current
    }

    suspend fun chatHistory(messages: List<UIMessage>, assistant: Assistant, ctx: PluginCtx): List<UIMessage> {
        return messageChain(PluginHookKind.CHAT_HISTORY, messages, assistant, ctx)
    }

    suspend fun presetBeforeAssemble(preset: PromptPreset, assistant: Assistant, ctx: PluginCtx): PromptPreset {
        val handlers = chain(PluginHookKind.PRESET_BEFORE_ASSEMBLE, assistant)
        if (handlers.isEmpty()) return preset
        var current = preset
        for ((runtime, reg) in handlers) {
            val encoded = JsonInstant.encodeToJsonElement(PromptPreset.serializer(), current)
            val result = runtime.invoke(PluginHookKind.PRESET_BEFORE_ASSEMBLE, reg.handlerId, listOf(encoded, ctx.toJson()))
            if (result is JsonNull) continue
            current = try {
                when (result) {
                    is JsonArray -> {
                        val blocks = JsonInstant.decodeFromJsonElement(ListSerializer(PromptBlock.serializer()), result)
                        current.copy(blocks = blocks)
                    }
                    is JsonObject -> {
                        JsonInstant.decodeFromJsonElement(PromptPreset.serializer(), result)
                    }
                    else -> throw IllegalArgumentException("返回值必须是预设对象或 blocks 数组")
                }
            } catch (e: Exception) {
                throw PluginException(runtime.id, PluginHookKind.PRESET_BEFORE_ASSEMBLE, "返回的预设格式不合法: ${e.message}", e)
            }
        }
        return current
    }

    suspend fun beforeSend(input: List<UIMessage>, assistant: Assistant, ctx: PluginCtx): List<UIMessage> {
        val hasMacros = host.chain(PluginHookKind.MACRO).isNotEmpty() || host.chain(PluginHookKind.PROMPT_BLOCK).isNotEmpty()
        val messages = if (!hasMacros) input else input.map { m ->
            val parts = m.parts.map { p ->
                if (p is UIMessagePart.Text && p.text.contains("{{")) p.copy(text = expandMacros(p.text, ctx)) else p
            }
            val textExpanded = if (parts == m.parts) m else m.copy(parts = parts)
            textExpanded.mapRawJsonStrings { str ->
                if (str.contains("{{")) kotlinx.coroutines.runBlocking { expandMacros(str, ctx) } else str
            }
        }
        return messageChain(PluginHookKind.MESSAGES_BEFORE_SEND, messages, assistant, ctx)
    }

    suspend fun responseVisual(messages: List<UIMessage>, assistant: Assistant, ctx: PluginCtx): List<UIMessage> {
        return messageChain(PluginHookKind.RESPONSE_VISUAL, messages, assistant, ctx)
    }

    suspend fun responseFinish(messages: List<UIMessage>, assistant: Assistant, ctx: PluginCtx): List<UIMessage> {
        return messageChain(PluginHookKind.RESPONSE_FINISH, messages, assistant, ctx)
    }

    suspend fun promptAssembled(messages: List<UIMessage>, assistant: Assistant, ctx: PluginCtx): List<UIMessage> {
        return messageChain(PluginHookKind.PROMPT_ASSEMBLED, messages, assistant, ctx)
    }

    suspend fun lorebookActivated(
        entries: List<PromptInjection.RegexInjection>,
        assistant: Assistant,
        ctx: PluginCtx,
    ): List<PromptInjection.RegexInjection> {
        val handlers = chain(PluginHookKind.LOREBOOK_ACTIVATED, assistant)
        if (handlers.isEmpty()) return entries
        var current = entries
        for ((runtime, reg) in handlers) {
            val encoded = JsonInstant.encodeToJsonElement(ListSerializer(PromptInjection.RegexInjection.serializer()), current)
            val result = runtime.invoke(PluginHookKind.LOREBOOK_ACTIVATED, reg.handlerId, listOf(encoded, ctx.toJson()))
            if (result is JsonNull) continue
            current = try {
                JsonInstant.decodeFromJsonElement(ListSerializer(PromptInjection.RegexInjection.serializer()), result)
            } catch (e: Exception) {
                throw PluginException(runtime.id, PluginHookKind.LOREBOOK_ACTIVATED, "返回的世界书条目格式不合法: ${e.message}", e)
            }
        }
        return current
    }

    /**
     * `params.beforeSend`：修改请求参数（temperature / topP / maxTokens / reasoningLevel / customHeaders / customBody）。
     */
    suspend fun paramsBeforeSend(
        params: me.rerere.ai.provider.TextGenerationParams,
        assistant: Assistant,
        ctx: PluginCtx,
    ): me.rerere.ai.provider.TextGenerationParams {
        val handlers = chain(PluginHookKind.PARAMS_BEFORE_SEND, assistant)
        if (handlers.isEmpty()) return params
        var current = params
        for ((runtime, reg) in handlers) {
            val payload = buildJsonObject {
                current.temperature?.let { put("temperature", it) }
                current.topP?.let { put("topP", it) }
                current.maxTokens?.let { put("maxTokens", it) }
                put("reasoningLevel", current.reasoningLevel.name)
                put("headers", buildJsonObject {
                    current.customHeaders
                        .filter { it.name != me.rerere.plugin.http.PluginRequestContexts.HEADER }
                        .forEach { put(it.name, it.value) }
                })
                put("body", buildJsonObject {
                    current.customBody.forEach { put(it.key, it.value) }
                })
            }
            val result = runtime.invoke(PluginHookKind.PARAMS_BEFORE_SEND, reg.handlerId, listOf(payload, ctx.toJson()))
            if (result is JsonNull) continue
            val obj = result as? JsonObject
                ?: throw PluginException(runtime.id, PluginHookKind.PARAMS_BEFORE_SEND, "返回值必须是对象或 undefined")
            val newTemp = if ("temperature" in obj) (obj["temperature"] as? JsonPrimitive)?.floatOrNull else current.temperature
            val newTopP = if ("topP" in obj) (obj["topP"] as? JsonPrimitive)?.floatOrNull else current.topP
            val newMaxTokens = if ("maxTokens" in obj) (obj["maxTokens"] as? JsonPrimitive)?.intOrNull else current.maxTokens
            val newReasoning = obj["reasoningLevel"]?.jsonPrimitive?.contentOrNull?.let { r ->
                runCatching { me.rerere.ai.core.ReasoningLevel.valueOf(r.uppercase()) }.getOrNull()
                    ?: throw PluginException(runtime.id, PluginHookKind.PARAMS_BEFORE_SEND, "未知 reasoningLevel: $r")
            } ?: current.reasoningLevel
            val pluginCtxHeader = current.customHeaders.find { it.name == me.rerere.plugin.http.PluginRequestContexts.HEADER }
            val newHeaders = (obj["headers"] as? JsonObject)?.let { hObj ->
                hObj.entries
                    .filter { it.key != me.rerere.plugin.http.PluginRequestContexts.HEADER }
                    .map { me.rerere.ai.provider.CustomHeader(it.key, (it.value as? JsonPrimitive)?.content ?: it.value.toString()) } +
                    listOfNotNull(pluginCtxHeader)
            } ?: current.customHeaders
            val newBody = (obj["body"] as? JsonObject)?.let { bObj ->
                bObj.entries.map { me.rerere.ai.provider.CustomBody(it.key, it.value) }
            } ?: current.customBody
            current = current.copy(
                temperature = newTemp,
                topP = newTopP,
                maxTokens = newMaxTokens,
                reasoningLevel = newReasoning,
                customHeaders = newHeaders,
                customBody = newBody,
            )
        }
        return current
    }

    // ---------- 正则 ----------

    fun hasPluginRegex(phase: AssistantRegexPhase, scope: AssistantAffectScope): Boolean {
        val phaseName = phase.name.lowercase()
        val scopeName = scope.name.lowercase()
        return host.chain(PluginHookKind.REGEX).any { (_, reg) ->
            val m = reg.meta.jsonObject
            val p = m["phase"]?.jsonPrimitive?.contentOrNull ?: "request"
            val scopes = (m["scope"] as? JsonArray)?.map { it.jsonPrimitive.content.lowercase() } ?: listOf("assistant")
            p == phaseName && scopeName in scopes
        }
    }

    suspend fun applyPluginRegexes(
        text: String,
        assistant: Assistant?,
        scope: AssistantAffectScope,
        phase: AssistantRegexPhase,
        depth: Int? = null,
    ): String {
        if (text.isEmpty()) return text
        val phaseName = phase.name.lowercase()
        val scopeName = scope.name.lowercase()
        val handlers = host.chain(PluginHookKind.REGEX).filter { (_, reg) ->
            val m = reg.meta.jsonObject
            val p = m["phase"]?.jsonPrimitive?.contentOrNull ?: "request"
            val scopes = (m["scope"] as? JsonArray)?.map { it.jsonPrimitive.content.lowercase() } ?: listOf("assistant")
            p == phaseName && scopeName in scopes
        }
        if (handlers.isEmpty()) return text
        var current = text
        val info = buildJsonObject {
            put("scope", scopeName)
            put("phase", phaseName)
            depth?.let { put("depth", it) }
            assistant?.id?.let { put("assistantId", it.toString()) }
        }
        for ((runtime, reg) in handlers) {
            val result = runtime.invoke(PluginHookKind.REGEX, reg.handlerId, listOf(JsonPrimitive(current), info))
            if (result is JsonNull) continue
            current = (result as? JsonPrimitive)?.content
                ?: throw PluginException(runtime.id, PluginHookKind.REGEX, "replace() 必须返回字符串或 undefined")
        }
        return current
    }

    fun applyPluginRegexesBlocking(
        text: String,
        assistant: Assistant?,
        scope: AssistantAffectScope,
        phase: AssistantRegexPhase,
        depth: Int? = null,
    ): String {
        if (text.isEmpty() || !hasPluginRegex(phase, scope)) return text
        return runBlocking { applyPluginRegexes(text, assistant, scope, phase, depth) }
    }

    // ---------- 数据钩子 ----------

    suspend fun conversationBeforeSave(conversation: Conversation, ctx: PluginCtx): Conversation {
        val handlers = chain(PluginHookKind.CONVERSATION_BEFORE_SAVE)
        if (handlers.isEmpty()) return conversation
        var current = conversation
        for ((runtime, reg) in handlers) {
            val encoded = JsonInstant.encodeToJsonElement(Conversation.serializer(), current)
            val result = runtime.invoke(PluginHookKind.CONVERSATION_BEFORE_SAVE, reg.handlerId, listOf(encoded, ctx.toJson()))
            if (result is JsonNull) continue
            val decoded = try {
                JsonInstant.decodeFromJsonElement(Conversation.serializer(), result)
            } catch (e: Exception) {
                throw PluginException(runtime.id, PluginHookKind.CONVERSATION_BEFORE_SAVE, "返回的对话格式不合法: ${e.message}", e)
            }
            // id 不可变
            current = decoded.copy(id = conversation.id)
        }
        return current
    }

    suspend fun searchResults(
        results: List<MessageSearchResult>,
        query: String,
        assistantId: Uuid?,
    ): List<MessageSearchResult> {
        val handlers = chain(PluginHookKind.SEARCH_RESULTS)
        if (handlers.isEmpty()) return results
        var current = results
        val ctx = PluginCtx(task = "other", assistantId = assistantId)
        for ((runtime, reg) in handlers) {
            val encoded = JsonArray(current.map { r ->
                buildJsonObject {
                    put("nodeId", r.nodeId)
                    put("messageId", r.messageId)
                    put("conversationId", r.conversationId)
                    put("title", r.title)
                    put("snippet", r.snippet)
                    put("updateAt", r.updateAt.toEpochMilli())
                }
            })
            val meta = buildJsonObject {
                put("query", query)
                assistantId?.let { put("assistantId", it.toString()) }
            }
            val result = runtime.invoke(PluginHookKind.SEARCH_RESULTS, reg.handlerId, listOf(encoded, meta, ctx.toJson()))
            if (result is JsonNull) continue
            val arr = result as? JsonArray
                ?: throw PluginException(runtime.id, PluginHookKind.SEARCH_RESULTS, "search.results 必须返回数组")
            current = arr.map { el ->
                val o = el as? JsonObject
                    ?: throw PluginException(runtime.id, PluginHookKind.SEARCH_RESULTS, "搜索结果项必须是对象")
                MessageSearchResult(
                    nodeId = o["nodeId"]?.jsonPrimitive?.contentOrNull ?: "",
                    messageId = o["messageId"]?.jsonPrimitive?.contentOrNull ?: "",
                    conversationId = o["conversationId"]?.jsonPrimitive?.contentOrNull
                        ?: throw PluginException(runtime.id, PluginHookKind.SEARCH_RESULTS, "搜索结果缺少 conversationId"),
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    snippet = o["snippet"]?.jsonPrimitive?.contentOrNull ?: "",
                    updateAt = Instant.ofEpochMilli(o["updateAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()),
                )
            }
        }
        return current
    }

    suspend fun inputBeforeSave(parts: List<UIMessagePart>, assistant: Assistant, ctx: PluginCtx): List<UIMessagePart> {
        val handlers = chain(PluginHookKind.INPUT_BEFORE_SAVE, assistant)
        if (handlers.isEmpty()) return parts
        var current = parts
        for ((runtime, reg) in handlers) {
            val encoded = JsonInstant.encodeToJsonElement(ListSerializer(UIMessagePart.serializer()), current)
            val result = runtime.invoke(PluginHookKind.INPUT_BEFORE_SAVE, reg.handlerId, listOf(encoded, ctx.toJson()))
            if (result is JsonNull) continue
            current = partsOf(runtime, PluginHookKind.INPUT_BEFORE_SAVE, result)
        }
        return current
    }

    /**
     * `attachment.parse`：在 DocumentAsPromptTransformer 解析文档前调用。
     * 返回非 null 字符串则替代默认解析结果；返回 null/undefined 则继续下一个插件或走默认解析。
     */
    suspend fun attachmentParse(document: UIMessagePart.Document, filePath: String?, ctx: PluginCtx): String? {
        val handlers = chain(PluginHookKind.ATTACHMENT_PARSE)
        if (handlers.isEmpty()) return null
        val payload = buildJsonObject {
            put("url", document.url)
            put("fileName", document.fileName)
            put("mime", document.mime)
            filePath?.let { put("path", it) }
        }
        for ((runtime, reg) in handlers) {
            val result = runtime.invoke(PluginHookKind.ATTACHMENT_PARSE, reg.handlerId, listOf(payload, ctx.toJson()))
            if (result is JsonNull) continue
            if (result is JsonPrimitive) return result.content
            if (result is JsonObject && "text" in result) return result["text"]!!.jsonPrimitive.content
            throw PluginException(runtime.id, PluginHookKind.ATTACHMENT_PARSE, "返回值必须是字符串或 { text } 或 undefined")
        }
        return null
    }

    // ---------- 后台任务钩子 (<task>.before / <task>.after / <task>.replace) ----------

    fun hasTaskReplace(task: String): Boolean = chain(PluginHookKind.task(task, "replace")).isNotEmpty()

    suspend fun taskReplace(task: String, input: JsonObject, ctx: PluginCtx): JsonElement? {
        val handlers = chain(PluginHookKind.task(task, "replace"))
        if (handlers.isEmpty()) return null
        for ((runtime, reg) in handlers) {
            val result = runtime.invoke(PluginHookKind.task(task, "replace"), reg.handlerId, listOf(input, ctx.toJson()))
            if (result !is JsonNull) return result
        }
        return null
    }

    suspend fun taskBeforePrompt(task: String, prompt: String, extra: JsonObject, ctx: PluginCtx): TaskBeforeResult<String> {
        val kind = PluginHookKind.task(task, "before")
        val handlers = chain(kind)
        if (handlers.isEmpty()) return TaskBeforeResult.Proceed(prompt)
        var current = prompt
        for ((runtime, reg) in handlers) {
            val arg = JsonObject(extra + ("prompt" to JsonPrimitive(current)))
            val result = runtime.invoke(kind, reg.handlerId, listOf(arg, ctx.toJson()))
            if (result is JsonNull) continue
            when {
                result is JsonPrimitive -> current = result.content
                result is JsonObject && "result" in result -> {
                    val r = result["result"]!!
                    val out = when (r) {
                        is JsonPrimitive -> r.content
                        is JsonArray -> r.joinToString("\n") { it.jsonPrimitive.content }
                        else -> r.toString()
                    }
                    return TaskBeforeResult.ShortCircuit(out)
                }
                result is JsonObject && "prompt" in result -> current = result["prompt"]!!.jsonPrimitive.content
                else -> throw PluginException(runtime.id, kind, "返回值必须是字符串、{ prompt } 或 { result }")
            }
        }
        return TaskBeforeResult.Proceed(current)
    }

    suspend fun taskAfterText(task: String, output: String, extra: JsonObject = JsonObject(emptyMap()), ctx: PluginCtx): String {
        val kind = PluginHookKind.task(task, "after")
        val handlers = chain(kind)
        if (handlers.isEmpty()) return output
        var current = output
        for ((runtime, reg) in handlers) {
            val result = runtime.invoke(kind, reg.handlerId, listOf(JsonPrimitive(current), extra, ctx.toJson()))
            if (result is JsonNull) continue
            current = when (result) {
                is JsonPrimitive -> result.content
                is JsonArray -> result.joinToString("\n") { it.jsonPrimitive.content }
                is JsonObject -> result["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw PluginException(runtime.id, kind, "返回对象需包含 text 字段")
                else -> throw PluginException(runtime.id, kind, "返回值格式不合法")
            }
        }
        return current
    }

    // ---------- 工具执行前后 / 审批 ----------

    private fun callJson(tool: UIMessagePart.Tool, args: JsonElement) = buildJsonObject {
        put("toolCallId", tool.toolCallId)
        put("name", tool.toolName)
        put("args", args)
    }

    suspend fun toolBefore(tool: UIMessagePart.Tool, args: JsonElement, assistant: Assistant, ctx: PluginCtx): ToolBeforeResult {
        var currentArgs = args
        for ((runtime, reg) in chain(PluginHookKind.TOOL_BEFORE, assistant)) {
            val result = runtime.invoke(PluginHookKind.TOOL_BEFORE, reg.handlerId, listOf(callJson(tool, currentArgs), ctx.toJson()))
            if (result is JsonNull) continue
            val obj = result as? JsonObject
                ?: throw PluginException(runtime.id, PluginHookKind.TOOL_BEFORE, "返回值必须是对象或 undefined")
            when {
                "deny" in obj -> return ToolBeforeResult.Deny(obj["deny"]!!.jsonPrimitive.content)
                "result" in obj -> return ToolBeforeResult.Result(partsOf(runtime, PluginHookKind.TOOL_BEFORE, obj["result"]!!))
                "args" in obj -> currentArgs = obj["args"]!!
                else -> throw PluginException(runtime.id, PluginHookKind.TOOL_BEFORE, "返回对象需包含 args / deny / result 之一")
            }
        }
        return ToolBeforeResult.Proceed(currentArgs)
    }

    suspend fun toolAfter(tool: UIMessagePart.Tool, args: JsonElement, output: List<UIMessagePart>, assistant: Assistant, ctx: PluginCtx): List<UIMessagePart> {
        var current = output
        for ((runtime, reg) in chain(PluginHookKind.TOOL_AFTER, assistant)) {
            val payload = JsonObject(callJson(tool, args) + ("output" to JsonInstant.encodeToJsonElement(ListSerializer(UIMessagePart.serializer()), current)))
            val result = runtime.invoke(PluginHookKind.TOOL_AFTER, reg.handlerId, listOf(payload, ctx.toJson()))
            if (result is JsonNull) continue
            current = partsOf(runtime, PluginHookKind.TOOL_AFTER, result)
        }
        return current
    }

    /** 返回 null 表示没有插件表态；"auto" / "user" / "main_agent" / Deny 原因。 */
    suspend fun approvalDecide(tool: UIMessagePart.Tool, assistant: Assistant, ctx: PluginCtx): ApprovalDecision? {
        for ((runtime, reg) in chain(PluginHookKind.APPROVAL, assistant)) {
            val result = runtime.invoke(PluginHookKind.APPROVAL, reg.handlerId, listOf(callJson(tool, tool.inputAsJson()), ctx.toJson()))
            if (result is JsonNull) continue
            return when {
                result is JsonPrimitive && result.content == "auto" -> ApprovalDecision.Auto
                result is JsonPrimitive && result.content == "user" -> ApprovalDecision.User
                result is JsonPrimitive && result.content == "main_agent" -> ApprovalDecision.MainAgent
                result is JsonPrimitive && result.content == "deny" -> ApprovalDecision.Deny("Denied by plugin ${runtime.id}")
                result is JsonObject && "deny" in result -> ApprovalDecision.Deny(result["deny"]!!.jsonPrimitive.content)
                else -> throw PluginException(runtime.id, PluginHookKind.APPROVAL, "未知审批结果: $result")
            }
        }
        return null
    }

    // ---------- 只读事件 ----------

    /** 派发事件（异步，不阻塞主流程）。没有订阅者时零开销。 */
    fun emit(name: String, payload: JsonObject) {
        host.emitEvent(name, payload, eventScope)
    }

    private val eventScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    // ---------- 宏与 PromptBlock ----------

    /** 展开插件注册的宏 `{{name}}` / `{{name:arg}}` 以及 `{{plugin:<id>:<type>}}` / `{{plugin:<id>:<type>:arg}}`；没有时原样返回。 */
    suspend fun expandMacros(text: String, ctx: PluginCtx): String {
        if (!text.contains("{{")) return text
        var result = text
        val promptBlocks = host.chain(PluginHookKind.PROMPT_BLOCK)
        if (promptBlocks.isNotEmpty()) {
            for ((runtime, reg) in promptBlocks) {
                val type = reg.meta.jsonObject["name"]!!.jsonPrimitive.content
                val regex = Regex(
                    "\\{\\{\\s*plugin:" + Regex.escape(runtime.id) + ":" + Regex.escape(type) + "(?::([^}]*))?\\}\\}",
                    RegexOption.IGNORE_CASE,
                )
                if (!regex.containsMatchIn(result)) continue
                val sb = StringBuilder()
                var last = 0
                for (m in regex.findAll(result)) {
                    sb.append(result, last, m.range.first)
                    val arg = m.groupValues.getOrNull(1)?.takeIf { m.groups[1] != null }
                    val value = runtime.invoke(
                        "promptBlock:$type", reg.handlerId,
                        listOf(arg?.let { JsonPrimitive(it) } ?: JsonNull, ctx.toJson()),
                    )
                    sb.append(if (value is JsonPrimitive) value.content else if (value is JsonNull) "" else value.toString())
                    last = m.range.last + 1
                }
                sb.append(result, last, result.length)
                result = sb.toString()
            }
        }
        val macros = host.chain(PluginHookKind.MACRO)
        if (macros.isEmpty()) return result
        for ((runtime, reg) in macros) {
            val name = reg.meta.jsonObject["name"]!!.jsonPrimitive.content
            val regex = Regex("\\{\\{" + Regex.escape(name) + "(?::([^}]*))?\\}\\}", RegexOption.IGNORE_CASE)
            if (!regex.containsMatchIn(result)) continue
            val sb = StringBuilder()
            var last = 0
            for (m in regex.findAll(result)) {
                sb.append(result, last, m.range.first)
                val arg = m.groupValues.getOrNull(1)?.takeIf { m.groups[1] != null }
                val value = runtime.invoke(
                    "macro:$name", reg.handlerId,
                    listOf(arg?.let { JsonPrimitive(it) } ?: JsonNull, ctx.toJson()),
                )
                sb.append(if (value is JsonPrimitive) value.content else value.toString())
                last = m.range.last + 1
            }
            sb.append(result, last, result.length)
            result = sb.toString()
        }
        return result
    }

    // ---------- 流式事件 ----------

    /** 每条响应流（含每次重试）调用一次；没有 onStream 时返回 null。 */
    suspend fun openStream(assistant: Assistant, ctx: PluginCtx): PluginStreamSession? {
        val handlers = chain(PluginHookKind.STREAM, assistant)
        if (handlers.isEmpty()) return null
        val stages = handlers.map { (runtime, reg) ->
            val sid = runtime.invokeInternal(
                PluginHookKind.STREAM, "__stream_open",
                buildJsonObject {
                    put("handler", reg.handlerId)
                    put("ctx", ctx.toJson())
                },
            ).jsonPrimitive.content
            val types = (reg.meta.jsonObject["types"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
            PluginStreamSession.Stage(runtime, reg, sid, types)
        }
        return PluginStreamSession(stages)
    }
}

enum class ApprovalKind { AUTO, USER, MAIN_AGENT, DENY }

sealed interface ApprovalDecision {
    data object Auto : ApprovalDecision
    data object User : ApprovalDecision
    data object MainAgent : ApprovalDecision
    data class Deny(val reason: String) : ApprovalDecision
}

/** 一条响应流上的插件处理管线：插件 i 的输出依次交给插件 i+1。 */
class PluginStreamSession(private val stages: List<Stage>) {
    class Stage(val runtime: PluginRuntime, val reg: PluginRegistration, val sid: String, val types: Set<String>?)

    suspend fun process(chunk: StreamChunk): List<StreamChunk> = runFrom(0, listOf(chunk))

    suspend fun end(): List<StreamChunk> {
        var pending = emptyList<StreamChunk>()
        val out = mutableListOf<StreamChunk>()
        for ((i, stage) in stages.withIndex()) {
            // 前面插件 end() 产出的事件先经过当前插件，再结束当前插件
            pending = runStage(stage, pending)
            val tail = stage.runtime.invokeInternal(PluginHookKind.STREAM, "__stream_end", buildJsonObject { put("sid", stage.sid) })
            pending = pending + decode(stage, tail)
            if (i == stages.lastIndex) out += pending
        }
        return out
    }

    suspend fun abort() {
        stages.forEach { stage ->
            runCatching {
                stage.runtime.invokeInternal(PluginHookKind.STREAM, "__stream_abort", buildJsonObject { put("sid", stage.sid) })
            }
        }
    }

    private suspend fun runFrom(index: Int, chunks: List<StreamChunk>): List<StreamChunk> {
        var current = chunks
        for (i in index until stages.size) current = runStage(stages[i], current)
        return current
    }

    private suspend fun runStage(stage: Stage, chunks: List<StreamChunk>): List<StreamChunk> {
        if (chunks.isEmpty()) return chunks
        val out = mutableListOf<StreamChunk>()
        for (chunk in chunks) {
            val encoded = JsonInstant.encodeToJsonElement(StreamChunk.serializer(), chunk).jsonObject
            val type = encoded["type"]?.jsonPrimitive?.content
            if (stage.types != null && type !in stage.types) {
                out += chunk
                continue
            }
            val result = stage.runtime.invokeInternal(
                PluginHookKind.STREAM, "__stream_chunk",
                buildJsonObject {
                    put("sid", stage.sid)
                    put("chunk", encoded)
                },
            )
            out += decode(stage, result)
        }
        return out
    }

    private fun decode(stage: Stage, result: JsonElement): List<StreamChunk> {
        if (result is JsonNull) return emptyList()
        val array = result as? JsonArray
            ?: throw PluginException(stage.runtime.id, PluginHookKind.STREAM, "chunk()/end() 必须返回事件数组")
        return array.map { el ->
            try {
                JsonInstant.decodeFromJsonElement(StreamChunk.serializer(), el)
            } catch (e: Exception) {
                throw PluginException(stage.runtime.id, PluginHookKind.STREAM, "非法事件 $el: ${e.message}", e)
            }
        }
    }
}

/** 错误链中是否包含插件错误（插件错误不参与网络重试）。 */
fun Throwable.isPluginError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is PluginException) return true
        if (current.message?.startsWith("[plugin:") == true) return true
        current = current.cause
    }
    return false
}
