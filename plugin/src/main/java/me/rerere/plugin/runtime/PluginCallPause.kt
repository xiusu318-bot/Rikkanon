package me.rerere.plugin.runtime

/**
 * 一次插件调用的「暂停计时」控制。
 *
 * 插件调用的超时只应计算插件自己执行的时间；调用过程中等待用户操作（如工具里的 `ctx.askUser`）
 * 的时间不计入超时。等待开始时 [pause]，结束时 [resume]，可嵌套（多个并发等待）。
 */
class PluginCallPause {
    private var depth = 0
    private var pausedTotalMillis = 0L
    private var pausedSinceMillis = 0L

    @Synchronized
    fun pause() {
        if (depth++ == 0) pausedSinceMillis = nowMillis()
    }

    @Synchronized
    fun resume() {
        if (depth > 0 && --depth == 0) pausedTotalMillis += nowMillis() - pausedSinceMillis
    }

    val isPaused: Boolean
        @Synchronized get() = depth > 0

    /** 到目前为止累计暂停的时长（含正在进行的暂停）。 */
    @Synchronized
    fun pausedMillis(): Long =
        pausedTotalMillis + if (depth > 0) nowMillis() - pausedSinceMillis else 0L

    internal companion object {
        fun nowMillis(): Long = System.nanoTime() / 1_000_000
    }
}
