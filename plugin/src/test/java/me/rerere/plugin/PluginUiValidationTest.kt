package me.rerere.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.plugin.host.DefaultPluginServices
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.store.PluginStore
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * 界面绘制时直接读取的插件数据，类型必须在注册 / 更新 / 调用入口拦下，
 * 否则绘制时抛异常会让整个 App 崩溃。
 */
class PluginUiValidationTest {
    private lateinit var host: PluginHost

    @Before
    fun setup() {
        val dir = Files.createTempDirectory("plugins").toFile()
        host = PluginHost(PluginStore(dir)) { h -> DefaultPluginServices(h, OkHttpClient()) }
    }

    @After
    fun teardown() = runBlocking { host.stopAll() }

    /** 在插件里执行 [body]，返回抛出的错误信息（没抛错返回 "ok"）。 */
    private suspend fun tryInPlugin(body: String): String {
        runCatching { host.uninstall("v") }
        val manifest = """{"id":"v","name":"v","version":"1.0.0","apiVersion":1,"permissions":[]}"""
        val js = """
            rikka.expose("run", async () => {
              try { $body; return "ok"; } catch (e) { return String(e && e.message || e); }
            });
        """.trimIndent()
        host.install(mapOf("manifest.json" to manifest.toByteArray(), "main.js" to js.toByteArray()))
        host.setEnabled("v", true)
        return host.callExposed("v", "run", JsonNull).jsonPrimitive.content
    }

    @Test(timeout = 30_000)
    fun `renderer lists must be string arrays`() = runBlocking {
        val msg = tryInPlugin("""rikka.ui.mount("tool.card", { type: "renderer", toolNames: [1] }, () => "x")""")
        assertEquals(true, msg.contains("toolNames"))
    }

    @Test(timeout = 30_000)
    fun `codeblock languages must be string arrays`() = runBlocking {
        val msg = tryInPlugin("""rikka.ui.mount("markdown.codeblock", { type: "renderer", languages: [{}] }, () => "x")""")
        assertEquals(true, msg.contains("languages"))
    }

    @Test(timeout = 30_000)
    fun `message part rendererType must be string`() = runBlocking {
        val msg = tryInPlugin("""rikka.ui.mount("message.part", { type: "renderer", rendererType: {} }, () => "x")""")
        assertEquals(true, msg.contains("rendererType"))
    }

    @Test(timeout = 30_000)
    fun `ui update cannot set invalid visible or menu`() = runBlocking {
        val msg1 = tryInPlugin(
            """const id = rikka.ui.mount("chat.topbar", { type: "badge", text: "0" }); rikka.ui.update(id, { visible: "no" })"""
        )
        assertEquals(true, msg1.contains("visible"))
    }

    @Test(timeout = 30_000)
    fun `ui update menu items need id and title`() = runBlocking {
        val msg = tryInPlugin(
            """const id = rikka.ui.mount("chat.topbar", { type: "menu", text: "m" }); rikka.ui.update(id, { menu: [{ title: "a" }] })"""
        )
        assertEquals(true, msg.contains("menu"))
    }

    @Test(timeout = 30_000)
    fun `skill compatibility must be string`() = runBlocking {
        val msg = tryInPlugin("""rikka.registerSkill({ name: "s", description: "d", content: "c", compatibility: {} })""")
        assertEquals(true, msg.contains("compatibility"))
    }

    @Test(timeout = 30_000)
    fun `entry optional title must be string`() = runBlocking {
        assertEquals(true, tryInPlugin("""rikka.ui.mount("extensions.list", { type: "entry", title: { bad: 1 } })""").contains("title"))
    }

    @Test(timeout = 30_000)
    fun `entry optional width must be number`() = runBlocking {
        assertEquals(true, tryInPlugin("""rikka.ui.mount("extensions.list", { type: "entry", width: "wide" })""").contains("width"))
    }

    @Test(timeout = 30_000)
    fun `form slider min must be number`() = runBlocking {
        assertEquals(true, tryInPlugin("""rikka.ui.mount("plugin.detail", { type: "form", fields: [{ type: "slider", key: "k", min: "zero" }] })""").contains("min"))
    }

    @Test(timeout = 30_000)
    fun `form visibleWhen equals must be primitives`() = runBlocking {
        assertEquals(true, tryInPlugin("""rikka.ui.mount("plugin.detail", { type: "form", fields: [{ type: "text", key: "k", visibleWhen: { key: "m", equals: [{}] } }] })""").contains("visibleWhen"))
    }

    @Test(timeout = 30_000)
    fun `registerTool optional title must be string`() = runBlocking {
        assertEquals(true, tryInPlugin("""rikka.registerTool({ name: "t", title: [], execute: async () => "ok" })""").contains("tool.title"))
    }
}
