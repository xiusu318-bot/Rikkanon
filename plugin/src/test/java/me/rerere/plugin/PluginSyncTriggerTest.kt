package me.rerere.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import me.rerere.plugin.host.DefaultPluginServices
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.store.PluginStore
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * onChanged（定时任务同步 + 服务同步）的触发时机：
 * 只改界面挂载项不触发；加载过程中的注册不逐个触发；运行中增删能力类注册才触发。
 */
class PluginSyncTriggerTest {
    private lateinit var host: PluginHost
    private val changed = AtomicInteger(0)

    @Before
    fun setup() {
        val dir = Files.createTempDirectory("plugins").toFile()
        host = PluginHost(PluginStore(dir)) { h -> DefaultPluginServices(h, OkHttpClient()) }
        host.onChanged = { changed.incrementAndGet() }
    }

    @After
    fun teardown() = runBlocking { host.stopAll() }

    @Test(timeout = 30_000)
    fun `ui changes do not trigger sync but capability changes do`() = runBlocking {
        val manifest = """{"id":"s","name":"s","version":"1.0.0","apiVersion":1,"permissions":[]}"""
        val js = """
            rikka.registerQuickMessage({ name: "q1", title: "Q1", content: "c" });
            rikka.registerSkill({ name: "sk1", description: "d", content: "c" });
            const badge = rikka.ui.mount("chat.topbar", { type: "badge", text: "0" });
            let n = 0;
            rikka.expose("tick", () => { n++; rikka.ui.update(badge, { text: String(n) }); return n; });
            let extra = null;
            rikka.expose("mountUi", () => { extra = rikka.ui.mount("chat.topbar", { type: "badge", text: "x" }); return true; });
            rikka.expose("unmountUi", () => { rikka.ui.unmount(extra); return true; });
            let qm = null;
            rikka.expose("addQm", () => { qm = rikka.registerQuickMessage({ name: "q2", title: "Q2", content: "c" }); return true; });
            rikka.expose("removeQm", () => { rikka.ui.unmount(qm); return true; });
        """.trimIndent()
        host.install(mapOf("manifest.json" to manifest.toByteArray(), "main.js" to js.toByteArray()))

        changed.set(0)
        host.setEnabled("s", true)
        // 加载时 4 个注册不逐个触发；startLocked 结束 1 次 + setEnabled 写状态后 1 次
        val afterEnable = changed.get()
        assertTrue("afterEnable=$afterEnable", afterEnable in 1..2)

        val before = changed.get()
        repeat(5) { host.callExposed("s", "tick", JsonNull) }
        host.callExposed("s", "mountUi", JsonNull)
        host.callExposed("s", "unmountUi", JsonNull)
        assertEquals("ui.update / mount / unmount 不应触发同步", before, changed.get())

        host.callExposed("s", "addQm", JsonNull)
        assertEquals("运行中新增能力类注册应触发一次", before + 1, changed.get())
        host.callExposed("s", "removeQm", JsonNull)
        assertEquals("运行中移除能力类注册应触发一次", before + 2, changed.get())
    }
}
