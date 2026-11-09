package me.rerere.plugin.registry

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 插件在加载期间（或运行中）通过 `rikka.*` 注册的一个条目。
 * [kind] 为 hook 名（见 PluginHookKind），事件为 `event:<name>`。
 */
data class PluginRegistration(
    val pluginId: String,
    val kind: String,
    val handlerId: String,
    val meta: JsonElement,
    /** 稳定标识（kind:名称#序号），用于在详情页单独开关某一项；重启后不变。 */
    val key: String = handlerId,
) {
    /** 工具注册的名称（仅 kind == tool 时有效）。插件写什么就是什么，宿主不加前缀。 */
    val toolName: String get() = meta.jsonObject["name"]!!.jsonPrimitive.content
}
