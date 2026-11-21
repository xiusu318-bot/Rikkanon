package me.rerere.plugin.runtime

import java.lang.reflect.Member

/**
 * 方法 Hook 引擎（基于 Pine：https://github.com/canyie/pine）。
 *
 * 只有插件第一次调用 `rikka.hook.*` 时才会触发 [ensureInitialized] 加载 Pine 原生库并初始化；
 * 没有插件使用 Hook 时，Pine 的任何类都不会被加载，对宿主运行没有任何影响。
 */
internal object PluginHookEngine {
    /** 对 Pine `CallFrame` 的抽象，便于桥接层与测试解耦。 */
    interface Frame {
        val thisObject: Any?
        val args: Array<Any?>
        val result: Any?
        val throwable: Throwable?
        fun setResult(value: Any?)
        fun setThrowable(t: Throwable)
        fun invokeOriginal(self: Any?, args: Array<Any?>): Any?
    }

    @Volatile
    private var initialized = false

    /** 是否成功关闭了 JIT 内联；失败时被内联的方法可能不触发 Hook。 */
    @Volatile
    var jitInlineDisabled = false
        private set

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            top.canyie.pine.PineConfig.debug = false
            top.canyie.pine.PineConfig.debuggable = isAppDebuggable()
            top.canyie.pine.PineConfig.disableHiddenApiPolicy = true
            top.canyie.pine.PineConfig.disableHiddenApiPolicyForPlatformDomain = true
            top.canyie.pine.Pine.ensureInitialized()
            // 关闭 JIT 内联，避免被 Hook 的方法被调用方内联后绕过 Hook
            jitInlineDisabled = top.canyie.pine.Pine.disableJitInline()
            initialized = true
        }
    }

    private fun isAppDebuggable(): Boolean {
        val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
            as android.app.Application
        return (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** Hook [member]，返回解除函数。 */
    fun hook(member: Member, before: ((Frame) -> Unit)?, after: ((Frame) -> Unit)?): () -> Unit {
        ensureInitialized()
        val callback = object : top.canyie.pine.callback.MethodHook() {
            override fun beforeCall(callFrame: top.canyie.pine.Pine.CallFrame) {
                before?.invoke(PineFrame(callFrame))
            }

            override fun afterCall(callFrame: top.canyie.pine.Pine.CallFrame) {
                after?.invoke(PineFrame(callFrame))
            }
        }
        val unhook = top.canyie.pine.Pine.hook(member, callback)
        return { unhook.unhook() }
    }

    /** 让方法回到解释执行（已被 AOT/JIT 内联到调用方的情况下，对调用方使用可让 Hook 生效）。 */
    fun deoptimize(member: Member): Boolean {
        ensureInitialized()
        return top.canyie.pine.Pine.decompile(member, true)
    }

    private class PineFrame(private val f: top.canyie.pine.Pine.CallFrame) : Frame {
        override val thisObject: Any? get() = f.thisObject
        override val args: Array<Any?>
            @Suppress("UNCHECKED_CAST")
            get() = (f.args ?: emptyArray<Any?>()) as Array<Any?>
        override val result: Any? get() = f.result
        override val throwable: Throwable? get() = f.throwable
        override fun setResult(value: Any?) = f.setResult(value)
        override fun setThrowable(t: Throwable) = f.setThrowable(t)
        override fun invokeOriginal(self: Any?, args: Array<Any?>): Any? = f.invokeOriginalMethod(self, *args)
    }
}
