package me.rerere.rikkahub.data.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * rikka.settings / presets / lorebooks / regexes / memory 的实现。
 *
 * 设置以 JSON 形式整体读写（字段与备份中的 settings.json 一致），`path` 为点分路径，如
 * `assistants`、`displaySetting.userNickname`。密钥代名词（secretKeys）不开放：读取时移除，写入时拒绝。
 */
class PluginSettingsApi(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
) {
    private fun settingsJson(settings: Settings): JsonObject =
        JsonObject(JsonInstant.encodeToJsonElement(Settings.serializer(), settings).jsonObject - SECRET_KEY_FIELD)

    private fun pathOf(raw: String?): List<String> = raw?.split('.')?.filter { it.isNotEmpty() }.orEmpty()

    private fun JsonElement.at(path: List<String>): JsonElement {
        var cur: JsonElement = this
        for (seg in path) {
            cur = when (cur) {
                is JsonObject -> cur[seg] ?: error("设置路径不存在: ${path.joinToString(".")}")
                is JsonArray -> cur.getOrNull(seg.toIntOrNull() ?: error("数组下标不合法: $seg"))
                    ?: error("数组下标越界: $seg")
                else -> error("设置路径不存在: ${path.joinToString(".")}")
            }
        }
        return cur
    }

    private fun JsonElement.with(path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val seg = path.first()
        return when (this) {
            is JsonObject -> {
                val child = this[seg] ?: error("设置路径不存在: $seg")
                JsonObject(this + (seg to child.with(path.drop(1), value)))
            }

            is JsonArray -> {
                val i = seg.toIntOrNull() ?: error("数组下标不合法: $seg")
                require(i in indices) { "数组下标越界: $seg" }
                JsonArray(mapIndexed { idx, e -> if (idx == i) e.with(path.drop(1), value) else e })
            }

            else -> error("设置路径不存在: $seg")
        }
    }

    private suspend fun writeSettings(transform: (JsonObject) -> JsonElement) {
        val current = settingsStore.awaitLoaded()
        val updatedJson = transform(settingsJson(current)) as? JsonObject ?: error("设置必须是对象")
        // 密钥代名词保持原值，不经插件
        val merged = JsonObject(
            updatedJson + (SECRET_KEY_FIELD to JsonInstant.encodeToJsonElement(Settings.serializer(), current).jsonObject[SECRET_KEY_FIELD]!!)
        )
        val decoded = try {
            JsonInstant.decodeFromJsonElement(Settings.serializer(), merged)
        } catch (e: Exception) {
            error("设置格式不合法: ${e.message}")
        }
        settingsStore.update { decoded }
    }

    suspend fun call(name: String, o: JsonObject): JsonElement = when (name) {
        "settings.get" -> settingsJson(settingsStore.awaitLoaded()).at(pathOf(o.strOrNull("path")))

        "settings.set" -> {
            val path = pathOf(o.str("path"))
            require(path.isNotEmpty()) { "settings.set: 不能整体替换设置，请指定路径" }
            require(path.first() != SECRET_KEY_FIELD) { "密钥代名词不开放给插件" }
            writeSettings { it.with(path, o["value"] ?: JsonNull) }
            JsonNull
        }

        "settings.updateAssistant" -> {
            val id = o.str("id")
            val patch = o["patch"] as? JsonObject ?: error("patch 必须是对象")
            writeSettings { root ->
                val assistants = root["assistants"] as JsonArray
                val index = assistants.indexOfFirst { (it as JsonObject)["id"] == JsonPrimitive(id) }
                require(index >= 0) { "助手不存在: $id" }
                val updated = JsonObject((assistants[index] as JsonObject) + patch)
                JsonObject(root + ("assistants" to JsonArray(assistants.mapIndexed { i, e -> if (i == index) updated else e })))
            }
            JsonNull
        }

        "regexes.get" -> {
            val id = Uuid.parse(o.str("assistantId"))
            val assistant = settingsStore.awaitLoaded().assistants.find { it.id == id } ?: error("助手不存在: $id")
            settingsJson(settingsStore.awaitLoaded()).let { root ->
                (root["assistants"] as JsonArray).first { (it as JsonObject)["id"] == JsonPrimitive(assistant.id.toString()) }
                    .jsonObject["regexes"] ?: JsonArray(emptyList())
            }
        }

        else -> error("未知宿主 API: $name")
    }

    private suspend fun memoryIdOf(o: JsonObject): String {
        val id = Uuid.parse(o.str("assistantId"))
        val assistant = settingsStore.awaitLoaded().assistants.find { it.id == id } ?: error("助手不存在: $id")
        return assistant.memoryId
    }

    private fun result(r: MemoryWriteResult): JsonElement = when (r) {
        is MemoryWriteResult.Success -> JsonPrimitive(true)
        is MemoryWriteResult.Conflict -> error("记忆文件版本冲突")
        is MemoryWriteResult.Rejected -> error(r.reason)
    }

    suspend fun callMemory(name: String, o: JsonObject): JsonElement {
        val memoryId = memoryIdOf(o)
        return when (name) {
            "memory.list" -> JsonArray(memoryRepository.getFiles(memoryId).map { f ->
                buildJsonObject {
                    put("path", f.path)
                    put("updatedAt", f.updatedAt)
                    put("size", f.content.length)
                }
            })

            "memory.read" -> memoryRepository.getFile(memoryId, o.str("path"))?.let { JsonPrimitive(it.content) } ?: JsonNull
            "memory.write" -> result(memoryRepository.writeFile(memoryId, o.str("path"), o.str("content")))
            "memory.delete" -> result(memoryRepository.deleteFile(memoryId, o.str("path")))
            else -> error("未知宿主 API: $name")
        }
    }

    companion object {
        private const val SECRET_KEY_FIELD = "secretKeys"
    }
}
