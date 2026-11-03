package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptBlock
import me.rerere.rikkahub.data.model.PromptBlockPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.PromptMarkerType
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

/**
 * 提示词组装预设导入/导出器
 * - 导出：本应用原生 PromptPreset JSON
 * - 导入：支持本应用原生 PromptPreset JSON，以及 SillyTavern（酒馆）Chat Completion 预设 JSON
 */
object PromptPresetSerializer : ExportSerializer<PromptPreset> {
    override val type = "prompt_preset"

    override fun getExportFileName(data: PromptPreset): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptPreset): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptPreset> {
        return runCatching {
            val json = readUri(context, uri)
            val fileName = getUriFileName(context, uri)?.removeSuffix(".json")
            importFromString(json, fileName)
                ?: throw IllegalArgumentException("不支持的预设格式")
        }
    }

    fun importFromString(json: String, fileName: String? = null): PromptPreset? {
        return tryImportNative(json)
            ?: tryImportSillyTavernPreset(json, fileName)
    }

    private fun tryImportNative(json: String): PromptPreset? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            val decoded = ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptPreset>(exportData.data)
            decoded.copy(
                id = Uuid.random(),
                blocks = decoded.blocks.map { it.copy(id = Uuid.random()) },
            ).ensureRequiredMarkers()
        }.getOrNull()
    }

    /**
     * 导入酒馆（SillyTavern）预设：
     * - 按 prompt_order（优先 character_id=100001 全局组）排列顺序，并将未在 prompt_order 中出现但存在于 prompts[] 的块保留在末尾；
     * - 对齐 4 个内置不可删原点块（worldInfoBefore / charDescription / worldInfoAfter / chatHistory）；
     * - 其余非空壳提示词块全部保留（无论启用或禁用，有啥留啥）。
     */
    internal fun tryImportSillyTavernPreset(json: String, fileName: String?): PromptPreset? {
        return runCatching {
            val stPreset = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernPresetDto.serializer(),
                json
            )
            if (stPreset.prompts.isEmpty()) return null

            val promptsByKey = linkedMapOf<String, SillyTavernPromptItemDto>()
            stPreset.prompts.forEachIndexed { index, p ->
                val key = p.identifier?.takeIf { it.isNotBlank() } ?: "#$index"
                if (!promptsByKey.containsKey(key)) {
                    promptsByKey[key] = p
                }
            }

            val orderGroups = stPreset.promptOrder.filter { it.order.isNotEmpty() }
            val activeOrderList = (orderGroups.firstOrNull { it.characterId == 100001 }
                ?: orderGroups.lastOrNull())
                ?.order

            data class OrderedSlot(val key: String, val enabled: Boolean)

            val orderedSlots = mutableListOf<OrderedSlot>()
            val seenKeys = mutableSetOf<String>()

            if (activeOrderList != null) {
                activeOrderList.forEach { entry ->
                    val k = entry.identifier?.takeIf { it.isNotBlank() } ?: return@forEach
                    val item = promptsByKey[k] ?: return@forEach
                    if (seenKeys.add(k)) {
                        orderedSlots.add(OrderedSlot(k, entry.enabled ?: item.enabled ?: true))
                    }
                }
                // 把 prompts[] 中存在但未出现在 prompt_order 中的剩余条目也保留下来（默认关闭以免误注入）
                for ((k, item) in promptsByKey) {
                    if (seenKeys.add(k)) {
                        orderedSlots.add(OrderedSlot(k, item.enabled ?: false))
                    }
                }
            } else {
                for ((k, item) in promptsByKey) {
                    orderedSlots.add(OrderedSlot(k, item.enabled ?: true))
                }
            }

            val blocks = mutableListOf<PromptBlock>()
            // 已知的酒馆空壳 marker（当 marker=true 且无内容时，除 4 个核心原点外跳过）
            val ignoredEmptyMarkers = setOf(
                "charPersonality",
                "scenario",
                "personaDescription",
                "dialogueExamples",
                "enhanceDefinitions",
            )

            for ((key, isEnabled) in orderedSlots) {
                val p = promptsByKey[key] ?: continue
                val idStr = p.identifier.orEmpty()
                val markerType = when (idStr) {
                    "worldInfoBefore" -> PromptMarkerType.WORLD_INFO_BEFORE
                    "charDescription" -> PromptMarkerType.CHAR_SYSTEM_PROMPT
                    "worldInfoAfter" -> PromptMarkerType.WORLD_INFO_AFTER
                    "memories" -> PromptMarkerType.MEMORIES
                    "workspacePrompt" -> PromptMarkerType.WORKSPACE_PROMPT
                    "chatHistory" -> PromptMarkerType.CHAT_HISTORY
                    else -> null
                }

                if (markerType != null) {
                    val defaultMarkerName = when (markerType) {
                        PromptMarkerType.WORLD_INFO_BEFORE -> "World Info (before)"
                        PromptMarkerType.CHAR_SYSTEM_PROMPT -> "Char Description"
                        PromptMarkerType.WORLD_INFO_AFTER -> "World Info (after)"
                        PromptMarkerType.MEMORIES -> "记忆"
                        PromptMarkerType.WORKSPACE_PROMPT -> "工作区提示词"
                        PromptMarkerType.CHAT_HISTORY -> "Chat History"
                    }
                    val markerRole = when (p.role?.lowercase()) {
                        "user" -> MessageRole.USER
                        "assistant" -> MessageRole.ASSISTANT
                        else -> MessageRole.SYSTEM
                    }
                    val markerPos = if (markerType == PromptMarkerType.CHAT_HISTORY) {
                        PromptBlockPosition.RELATIVE
                    } else {
                        when (p.injectionPosition) {
                            1 -> PromptBlockPosition.IN_CHAT
                            2 -> PromptBlockPosition.ATTACH_TO_MESSAGE
                            else -> PromptBlockPosition.RELATIVE
                        }
                    }
                    blocks.add(
                        PromptBlock(
                            id = Uuid.random(),
                            identifier = idStr,
                            name = p.name?.takeIf { it.isNotBlank() } ?: defaultMarkerName,
                            enabled = isEnabled,
                            markerType = markerType,
                            role = markerRole,
                            content = p.content.orEmpty(),
                            inChatDepth = markerPos != PromptBlockPosition.RELATIVE,
                            position = markerPos,
                            depth = (p.injectionDepth ?: 4).coerceAtLeast(0),
                            order = p.injectionOrder ?: 100,
                        )
                    )
                    continue
                }

                // 其他 marker=true 且内容为空的酒馆内置占位符跳过
                val rawContent = p.content.orEmpty()
                val rawReasoning = p.reasoning?.takeIf { it.isNotBlank() } ?: p.thinking.orEmpty()
                val rawSignature = p.reasoningSignature?.takeIf { it.isNotBlank() }
                    ?: p.signature?.takeIf { it.isNotBlank() }
                    ?: p.thoughtSignature.orEmpty()
                if (p.marker && rawContent.isBlank() && rawReasoning.isBlank() && rawSignature.isBlank() && idStr in ignoredEmptyMarkers) {
                    continue
                }
                if (p.marker && rawContent.isBlank() && rawReasoning.isBlank() && rawSignature.isBlank()) {
                    continue
                }

                val roleLower = p.role?.trim()?.lowercase()
                val isBlankRole = roleLower == "?" || roleLower == "？" || roleLower == "blank"
                val role = when (roleLower) {
                    "user" -> MessageRole.USER
                    "assistant", "model" -> MessageRole.ASSISTANT
                    else -> MessageRole.SYSTEM
                }
                val blockPosition = when (p.injectionPosition) {
                    1 -> PromptBlockPosition.IN_CHAT
                    2 -> PromptBlockPosition.ATTACH_TO_MESSAGE
                    else -> PromptBlockPosition.RELATIVE
                }
                blocks.add(
                    PromptBlock(
                        id = Uuid.random(),
                        identifier = idStr,
                        name = p.name?.takeIf { it.isNotBlank() }
                            ?: idStr.ifBlank { "提示词块 ${blocks.size + 1}" },
                        enabled = isEnabled,
                        markerType = null,
                        role = role,
                        blankRole = isBlankRole,
                        content = rawContent,
                        reasoning = if (isBlankRole) "" else rawReasoning,
                        reasoningSignature = if (isBlankRole) "" else rawSignature,
                        inChatDepth = blockPosition != PromptBlockPosition.RELATIVE,
                        position = blockPosition,
                        depth = (p.injectionDepth ?: 4).coerceAtLeast(0),
                        order = p.injectionOrder ?: 100,
                    )
                )
            }

            if (blocks.isEmpty()) return null

            PromptPreset(
                id = Uuid.random(),
                name = fileName?.trim()?.takeIf { it.isNotEmpty() } ?: "导入预设",
                description = "共 ${blocks.size} 个提示词块",
                enabled = false,
                blocks = blocks,
            ).ensureRequiredMarkers()
        }.getOrNull()
    }
}

@Serializable
private data class SillyTavernPresetDto(
    val prompts: List<SillyTavernPromptItemDto> = emptyList(),
    @SerialName("prompt_order")
    val promptOrder: List<SillyTavernPromptOrderGroupDto> = emptyList(),
)

@Serializable
private data class SillyTavernPromptItemDto(
    val identifier: String? = null,
    val name: String? = null,
    val enabled: Boolean? = null,
    val role: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    val thinking: String? = null,
    @SerialName("reasoning_signature")
    val reasoningSignature: String? = null,
    val signature: String? = null,
    @SerialName("thought_signature")
    val thoughtSignature: String? = null,
    val marker: Boolean = false,
    @SerialName("injection_position")
    val injectionPosition: Int? = null,
    @SerialName("injection_depth")
    val injectionDepth: Int? = null,
    @SerialName("injection_order")
    val injectionOrder: Int? = null,
)

@Serializable
private data class SillyTavernPromptOrderGroupDto(
    @SerialName("character_id")
    val characterId: Int? = null,
    val order: List<SillyTavernPromptOrderItemDto> = emptyList(),
)

@Serializable
private data class SillyTavernPromptOrderItemDto(
    val identifier: String? = null,
    val enabled: Boolean? = null,
)

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            val json = readUri(context, uri)
            tryImportNative(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): PromptInjection.ModeInjection? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrNull()
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            val fileName = getUriFileName(context, uri)?.removeSuffix(".json")
            importFromString(json, fileName)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    fun importFromString(json: String, fileName: String? = null): Lorebook? {
        return tryImportNative(json)
            ?: tryImportSillyTavern(json, fileName)
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            val decoded = ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
            decoded.copy(
                id = Uuid.random(),
                entries = decoded.entries.map { it.copy(id = Uuid.random()) }
            )
        }.getOrNull()
    }

    internal fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val rootObj = ExportSerializer.DefaultJson.parseToJsonElement(json) as? JsonObject
                ?: return null
            val entriesEl = rootObj["entries"] ?: return null
            val rawEntries: List<SillyTavernEntry> = when (entriesEl) {
                is JsonObject -> entriesEl.values.map {
                    ExportSerializer.DefaultJson.decodeFromJsonElement<SillyTavernEntry>(it)
                }
                is JsonArray -> entriesEl.map {
                    ExportSerializer.DefaultJson.decodeFromJsonElement<SillyTavernEntry>(it)
                }
                else -> return null
            }

            Lorebook(
                id = Uuid.random(),
                name = fileName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: (rootObj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: LocalDateTime.now().toLocalString(),
                description = (rootObj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                enabled = false,
                entries = rawEntries.map { entry ->
                    val extObj = entry.extensions as? JsonObject
                    fun extBool(key: String): Boolean? =
                        (extObj?.get(key) as? JsonPrimitive)?.booleanOrNull
                    fun extInt(key: String): Int? =
                        (extObj?.get(key) as? JsonPrimitive)?.intOrNull
                    fun extString(key: String): String? =
                        (extObj?.get(key) as? JsonPrimitive)?.contentOrNull

                    val rawPosition = entry.position ?: extInt("position") ?: 0
                    val mappedPosition = mapSillyTavernPosition(rawPosition)
                    val mappedRole = parseSillyTavernRole(entry.role ?: extObj?.get("role"))
                    val mappedLogic = when (entry.selectiveLogic ?: extInt("selectiveLogic") ?: 0) {
                        1 -> SelectiveLogic.NOT_ALL
                        2 -> SelectiveLogic.NOT_ANY
                        3 -> SelectiveLogic.AND_ALL
                        else -> SelectiveLogic.AND_ANY
                    }
                    val delayRec = parseDelayUntilRecursion(
                        entry.delayUntilRecursion ?: extObj?.get("delay_until_recursion")
                    )
                    val prob = if (entry.useProbability == false) {
                        100
                    } else {
                        (entry.probability ?: extInt("probability") ?: 100).coerceIn(0, 100)
                    }
                    val customScanDepth = entry.scanDepth ?: extInt("scan_depth")
                    val customCaseSensitive = entry.caseSensitive ?: extBool("case_sensitive")
                    val customWholeWords = entry.matchWholeWords ?: extBool("match_whole_words")
                    val outlet = entry.outletName?.takeIf { it.isNotBlank() }
                        ?: extString("outlet_name").orEmpty()

                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = entry.comment.orEmpty().ifBlank { entry.key.firstOrNull().orEmpty() },
                        enabled = !entry.disable && (entry.enabled ?: true),
                        priority = entry.order,
                        position = mappedPosition,
                        injectDepth = entry.depth,
                        role = mappedRole,
                        content = entry.content,
                        keywords = entry.key,
                        secondaryKeys = entry.keysecondary,
                        selective = entry.selective,
                        selectiveLogic = mappedLogic,
                        useRegex = false,
                        caseSensitive = customCaseSensitive ?: false,
                        overrideCaseSensitive = customCaseSensitive,
                        matchWholeWords = customWholeWords,
                        scanDepth = customScanDepth ?: 4,
                        overrideScanDepth = customScanDepth,
                        constantActive = entry.constant,
                        probability = prob,
                        excludeRecursion = entry.excludeRecursion ?: extBool("exclude_recursion") ?: false,
                        preventRecursion = entry.preventRecursion ?: extBool("prevent_recursion") ?: false,
                        delayUntilRecursion = delayRec,
                        ignoreBudget = entry.ignoreBudget ?: extBool("ignore_budget") ?: false,
                        outletName = outlet,
                    )
                }
            )
        }.getOrNull()
    }

    /**
     * 酒馆 world_info_position 映射：
     * - 0 (Before Char) -> BEFORE_SYSTEM_PROMPT
     * - 1 (After Char) / 2 (ANTop) / 5 (EMTop) -> AFTER_SYSTEM_PROMPT
     * - 4 (@Depth) / 3 (ANBottom) / 6 (EMBottom) -> AT_DEPTH
     * - 7 (Outlet) -> OUTLET
     */
    private fun mapSillyTavernPosition(position: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1, 2, 5 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            3, 4, 6 -> InjectionPosition.AT_DEPTH
            7 -> InjectionPosition.OUTLET
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }

    /**
     * 酒馆世界书 role：0/null="system", 1="user", 2="assistant"
     */
    private fun parseSillyTavernRole(el: JsonElement?): MessageRole {
        val prim = el as? JsonPrimitive ?: return MessageRole.SYSTEM
        val content = prim.contentOrNull?.trim()?.lowercase() ?: return MessageRole.SYSTEM
        return when (content) {
            "1", "user" -> MessageRole.USER
            "2", "assistant", "model" -> MessageRole.ASSISTANT
            else -> MessageRole.SYSTEM
        }
    }

    private fun parseDelayUntilRecursion(el: JsonElement?): Boolean {
        val prim = el as? JsonPrimitive ?: return false
        prim.booleanOrNull?.let { return it }
        prim.intOrNull?.let { return it > 0 }
        return prim.contentOrNull?.equals("true", ignoreCase = true) == true
    }
}

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val keysecondary: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int? = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val enabled: Boolean? = null,
    val depth: Int = 4,
    val role: JsonElement? = null,
    val selective: Boolean = true,
    val selectiveLogic: Int? = 0,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val probability: Int? = 100,
    val useProbability: Boolean? = true,
    val excludeRecursion: Boolean? = null,
    val preventRecursion: Boolean? = null,
    val delayUntilRecursion: JsonElement? = null,
    val ignoreBudget: Boolean? = null,
    val outletName: String? = null,
    val extensions: JsonElement? = null,
)
