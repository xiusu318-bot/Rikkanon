package me.rerere.rikkahub.data.plugin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 为 `rikka.host.activity()` 提供「当前前台 Activity」。
 *
 * 惰性启用：只有插件第一次请求时才注册生命周期回调；在此之前不做任何事。
 * 首次请求时如果回调还没捕获到 Activity，会通过 ActivityThread 反射找出当前未暂停的 Activity。
 */
internal object PluginCurrentActivity {
    @Volatile
    private var current: WeakReference<Activity>? = null

    @Volatile
    private var registered = false

    fun get(app: Application): Activity? {
        ensureRegistered(app)
        current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { return it }
        return findViaActivityThread()?.also { current = WeakReference(it) }
    }

    private fun ensureRegistered(app: Application) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    current = WeakReference(activity)
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (current?.get() === activity) current = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            })
            registered = true
        }
    }

    private fun findViaActivityThread(): Activity? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getMethod("currentActivityThread").invoke(null)
        val field = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
        val records = (field.get(at) as Map<*, *>).values
        var fallback: Activity? = null
        for (record in records) {
            if (record == null) continue
            val rc = record.javaClass
            val activity = rc.getDeclaredField("activity").apply { isAccessible = true }.get(record) as? Activity ?: continue
            val paused = runCatching {
                rc.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
            }.getOrDefault(true)
            if (!paused) return@runCatching activity
            fallback = activity
        }
        fallback
    }.getOrNull()
}
