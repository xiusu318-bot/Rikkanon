package me.rerere.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.error.PluginException
import me.rerere.plugin.host.DefaultPluginServices
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.store.PluginStore
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class PluginRuntimeTest {
    private lateinit var host: PluginHost

    @Before
    fun setup() {
        val dir = Files.createTempDirectory("plugins").toFile()
        host = PluginHost(PluginStore(dir)) { h -> DefaultPluginServices(h, OkHttpClient()) }
    }

    @After
    fun teardown() = runBlocking { host.stopAll() }

    private fun manifest(id: String, permissions: String = "") = """
        {"id":"$id","name":"$id","version":"1.0.0","apiVersion":1,"permissions":[$permissions]}
    """.trimIndent()

    private suspend fun installAndEnable(id: String, js: String, permissions: String = "") {
        host.install(mapOf("manifest.json" to manifest(id, permissions).toByteArray(), "main.js" to js.toByteArray()))
        host.setEnabled(id, true)
    }

    @Test(timeout = 30_000)
    fun `disabled plugin has empty chain`() = runBlocking {
        host.install(mapOf("manifest.json" to manifest("a").toByteArray(), "main.js" to "rikka.registerTool({name:'x', execute: () => 1})".toByteArray()))
        assertTrue(host.chain("tool").isEmpty())
        host.setEnabled("a", true)
        assertEquals(1, host.chain("tool").size)
        host.setEnabled("a", false)
        assertTrue(host.chain("tool").isEmpty())
    }

    @Test(timeout = 30_000)
    fun `tool registration and concurrent async invocation`() = runBlocking {
        installAndEnable(
            "t", """
            rikka.storage.set("count", 0);
            rikka.registerTool({
              name: "echo",
              parameters: { type: "object", properties: { n: { type: "number" } } },
              display: { as: "text", source: "input", field: "content" },
              endTurn: true,
              execute: async (args, ctx) => {
                rikka.storage.set("count", rikka.storage.get("count") + 1);
                return { model: "n=" + (args.n * 2), task: ctx.task };
              }
            });
            """
        )
        val (rt, reg) = host.chain("tool").single()
        assertEquals("echo", reg.meta.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("text", reg.meta.jsonObject["display"]!!.jsonObject["as"]!!.jsonPrimitive.content)
        val results = (1..5).map { i ->
            async {
                rt.invoke("tool", reg.handlerId, listOf(buildJsonObject { put("n", i) }, buildJsonObject { put("task", "chat") }))
            }
        }.awaitAll()
        assertEquals((1..5).map { "n=${it * 2}" }, results.map { it.jsonObject["model"]!!.jsonPrimitive.content })
        assertEquals("chat", results[0].jsonObject["task"]!!.jsonPrimitive.content)
        // storage 由宿主落盘，可直接读取文件验证
        val storage = host.installedPlugin("t")!!.storageFile.readText()
        assertTrue(storage, storage.contains("\"count\":5"))
        Unit
    }

    @Test(timeout = 30_000)
    fun `errors are reported with plugin prefix`() = runBlocking {
        installAndEnable("e", "rikka.registerTool({name:'boom', execute: async () => { throw new Error('kaboom') }})")
        val (rt, reg) = host.chain("tool").single()
        try {
            rt.invoke("tool", reg.handlerId, listOf(JsonPrimitive(1)))
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.startsWith("[plugin:e] tool: "))
            assertTrue(e.message!!, e.message!!.contains("kaboom"))
        }
        // 出错后运行时仍可用
        assertEquals(1, host.chain("tool").size)
    }

    @Test(timeout = 30_000)
    fun `timeout is an error`() = runBlocking {
        installAndEnable("slow", "rikka.registerTool({name:'s', execute: () => new Promise(() => {})})")
        host.updateState("slow") { it.copy(callTimeoutMillis = 300) }
        val (rt, reg) = host.chain("tool").single()
        try {
            rt.invoke("tool", reg.handlerId, emptyList())
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("超时"))
        }
    }

    @Test(timeout = 30_000)
    fun `load error fails enable and keeps plugin disabled`() = runBlocking {
        host.install(mapOf("manifest.json" to manifest("u").toByteArray(), "main.js" to "rikka.on('no.such.hook', () => 1)".toByteArray()))
        try {
            host.setEnabled("u", true)
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.startsWith("[plugin:u] load: "))
            assertTrue(e.message!!, e.message!!.contains("no.such.hook"))
        }
        assertTrue(host.activeRuntimes.value.isEmpty())
        assertEquals(false, host.states.value["u"]?.enabled ?: false)
    }

    @Test(timeout = 30_000)
    fun `hooks need no declaration`() = runBlocking {
        installAndEnable(
            "h", """
            rikka.intercept(async (req, next) => next(req));
            rikka.on("tool.before", (t) => t);
            rikka.on("event:generation.finish", () => {});
            rikka.registerMacro("m", () => "x");
            rikka.ui.mount("chat.topbar", { type: "badge", text: "hi" }, () => {});
            """
        )
        assertEquals(1, host.chain("intercept").size)
        assertEquals(1, host.chain("tool.before").size)
        assertEquals(1, host.chain("event:generation.finish").size)
        assertEquals(1, host.chain("ui").size)
        assertEquals(1, host.chain("macro").size)
    }

    @Test(timeout = 30_000)
    fun `single registration can be disabled`() = runBlocking {
        installAndEnable("d", "rikka.registerTool({name:'a', execute: () => 1}); rikka.registerTool({name:'b', execute: () => 2});")
        assertEquals(listOf("a", "b"), host.tools().map { it.second.toolName })
        val keyA = host.tools().first().second.key
        host.updateState("d") { it.copy(disabled = setOf(keyA)) }
        assertEquals(listOf("b"), host.tools().map { it.second.toolName })
    }

    @Test(timeout = 30_000)
    fun `modules can import package files`() = runBlocking {
        host.install(
            mapOf(
                "manifest.json" to manifest("mod").toByteArray(),
                "main.js" to "import { name } from './lib/util.js'; rikka.registerTool({ name, execute: () => 1 });".toByteArray(),
                "lib/util.js" to "export const name = 'from_module';".toByteArray(),
            )
        )
        host.setEnabled("mod", true)
        assertEquals("from_module", host.tools().single().second.toolName)
    }

    @Test(timeout = 30_000)
    fun `plugin files stay inside plugin dir`() = runBlocking {
        installAndEnable(
            "f", """
            rikka.registerTool({ name: 'w', execute: async () => { await rikka.files.write('data/a.txt', 'hi'); return await rikka.files.read('data/a.txt'); } });
            rikka.registerTool({ name: 'esc', execute: async () => await rikka.files.read('../x') });
            """
        )
        val (rt, w) = host.tools().first()
        assertEquals("hi", rt.invoke("tool", w.handlerId, emptyList()).jsonPrimitive.content)
        val esc = host.tools()[1].second
        try {
            rt.invoke("tool", esc.handlerId, emptyList())
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("超出插件目录"))
        }
        Unit
    }

    @Test(timeout = 30_000)
    fun `unimplemented events cannot be registered`() = runBlocking {
        host.install(mapOf("manifest.json" to manifest("ev").toByteArray(), "main.js" to "rikka.on('event:app.nonexistent', () => {})".toByteArray()))
        try {
            host.setEnabled("ev", true)
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("app.nonexistent"))
        }
        Unit
    }

    @Test(timeout = 30_000)
    fun `unknown ui slot fails`() = runBlocking {
        host.install(mapOf("manifest.json" to manifest("ui").toByteArray(), "main.js" to "rikka.ui.mount('nowhere', {type:'badge'})".toByteArray()))
        try {
            host.setEnabled("ui", true)
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("nowhere"))
        }
        Unit
    }

    @Test(timeout = 30_000)
    fun `permission must be declared`() = runBlocking {
        installAndEnable("n", "rikka.registerTool({name:'f', execute: async () => await rikka.fetch('http://127.0.0.1:1/')})")
        val (rt, reg) = host.chain("tool").single()
        try {
            rt.invoke("tool", reg.handlerId, emptyList())
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("缺少权限 network"))
        }
        Unit
    }

    @Test(timeout = 30_000)
    fun `tool names are kept verbatim and conflicts fail`() = runBlocking {
        installAndEnable("a1", "rikka.registerTool({name:'reply', execute: () => 1})")
        assertEquals("reply", host.tools().single().second.toolName)
        installAndEnable("a2", "rikka.registerTool({name:'reply', execute: () => 2})")
        try {
            host.tools()
            fail("should throw")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("reply"))
            assertTrue(e.message!!, e.message!!.contains("a1") && e.message!!.contains("a2"))
        }
        Unit
    }

    @Test(timeout = 30_000)
    fun `stream instance keeps state per stream`() = runBlocking {
        installAndEnable(
            "s", """
            rikka.onStream((ctx) => {
              let n = 0;
              return {
                chunk(c) { n++; return [c, { type: "TextDelta", id: "x", text: String(n) }]; },
                end() { return [{ type: "TextDelta", id: "x", text: "end" + n }]; }
              };
            }, { types: ["TextDelta"] });
            """
        )
        val (rt, reg) = host.chain("stream").single()
        val sid = rt.invokeInternal("stream", "__stream_open", buildJsonObject { put("handler", reg.handlerId); put("ctx", buildJsonObject {}) }).jsonPrimitive.content
        val out1 = rt.invokeInternal("stream", "__stream_chunk", buildJsonObject { put("sid", sid); put("chunk", buildJsonObject { put("type", "TextDelta") }) })
        val out2 = rt.invokeInternal("stream", "__stream_chunk", buildJsonObject { put("sid", sid); put("chunk", buildJsonObject { put("type", "TextDelta") }) })
        val end = rt.invokeInternal("stream", "__stream_end", buildJsonObject { put("sid", sid) })
        assertEquals("1", (out1 as kotlinx.serialization.json.JsonArray)[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("2", (out2 as kotlinx.serialization.json.JsonArray)[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("end2", (end as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1, reg.meta.jsonObject["types"]!!.let { (it as kotlinx.serialization.json.JsonArray).size })
        Unit
    }

    @Test(timeout = 30_000)
    fun `priority orders chain`() = runBlocking {
        installAndEnable("p1", "rikka.registerTool({name:'a', execute: () => 1})")
        installAndEnable("p2", "rikka.registerTool({name:'b', execute: () => 2})")
        host.updateState("p1") { it.copy(priority = 10) }
        assertEquals(listOf("p2", "p1"), host.chain("tool").map { it.first.id })
        assertEquals(2, host.chain("tool").size)
        host.chain("tool").first().second.meta.jsonObject["name"]!!.jsonPrimitive.content.let { assertEquals("b", it) }
        Unit
    }

    @Test(timeout = 30_000)
    fun `host storage singleton stays in sync with js rikka storage`() = runBlocking {
        installAndEnable(
            "sync_test", """
            rikka.ui.mount("plugin.detail", {
              type: "form",
              title: "Settings",
              fields: [{ key: "flag", type: "switch", label: "Flag", default: true }]
            });
            rikka.registerTool({
              name: "check_flag",
              execute: async () => rikka.storage.get("flag") === false ? "off" : "on"
            });
            """
        )
        val (rt, reg) = host.chain("tool").single()
        assertEquals("on", rt.invoke("tool", reg.handlerId, emptyList()).jsonPrimitive.content)
        // 模拟宿主 UI 表单切换开关写入 host.storage
        host.storage("sync_test").set("flag", JsonPrimitive(false))
        assertEquals("off", rt.invoke("tool", reg.handlerId, emptyList()).jsonPrimitive.content)
    }
}
