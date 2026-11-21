package me.rerere.plugin.runtime

import kotlinx.serialization.json.JsonElement

/**
 * [PluginJavaBridge] 所依附的 JS 运行时（插件运行时、界面自定义的主题运行时）。
 */
interface JavaBridgeHost {
    /** 运行时标识（出现在日志与代理对象的 toString 里）。 */
    val id: String

    /** 写一条运行时日志。 */
    fun hostLog(level: String, text: String)

    /** 当前线程上的 JS 是否正被一次同步宿主调用占住（此时无法再同步回调 JS）。 */
    fun isJsThread(): Boolean

    /**
     * Java 侧（接口代理、方法 Hook）回调 JS handler。不支持回调的运行时抛 [UnsupportedOperationException]。
     */
    fun invokeFromJava(
        handlerId: String,
        args: List<JsonElement>,
        wait: Boolean,
        timeoutMillis: Long? = PluginRuntime.JAVA_CALLBACK_TIMEOUT_MS,
    ): JsonElement?
}
