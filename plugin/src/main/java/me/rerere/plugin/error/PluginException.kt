package me.rerere.plugin.error

/**
 * 插件导致的所有失败统一包装为此异常，消息以 `[plugin:<id>] <hook>: ` 开头。
 * 宿主捕获后直接让当前操作失败，不重试、不降级。
 */
class PluginException(
    val pluginId: String,
    val hook: String,
    detail: String,
    cause: Throwable? = null,
) : RuntimeException("[plugin:$pluginId] $hook: $detail", cause) {
    companion object {
        fun wrap(pluginId: String, hook: String, error: Throwable): PluginException =
            error as? PluginException
                ?: PluginException(pluginId, hook, "${error.javaClass.name}: ${error.message}", error)
    }
}
