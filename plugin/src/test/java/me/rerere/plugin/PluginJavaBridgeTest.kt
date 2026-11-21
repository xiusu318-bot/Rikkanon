package me.rerere.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.plugin.host.DefaultPluginServices
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.plugin.store.PluginStore
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Callable

/** 供反射桥测试使用的目标类。 */
class BridgeTarget(val name: String) {
    @Suppress("unused")
    private var secret: String = "s3cr3t"

    fun greet(who: String) = "hi $who from $name"
    fun add(a: Int, b: Int): Int = a + b
    fun add(a: Double, b: Double): Double = a + b + 0.5
    fun big(): Long = 9007199254740993L
    fun join(vararg parts: String): String = parts.joinToString("-")
    fun callBack(fn: java.util.function.Function<String, String>): String = fn.apply("x")

    companion object {
        @JvmStatic
        fun twice(x: Int) = x * 2

        @JvmField
        var counter = 0
    }
}

data class SampleConfig(
    val enabled: Boolean = false,
    val label: String = "init",
    val limit: Int = 16,
    val note: String? = "memo",
)

object ConfigBox {
    @JvmStatic
    fun applyTransform(initial: SampleConfig, fn: java.util.function.UnaryOperator<SampleConfig>): SampleConfig =
        fn.apply(initial)
}

class PluginJavaBridgeTest {
    private lateinit var host: PluginHost

    @Before
    fun setup() {
        val dir = Files.createTempDirectory("plugins").toFile()
        host = PluginHost(PluginStore(dir)) { h -> DefaultPluginServices(h, OkHttpClient()) }
    }

    @After
    fun teardown() = runBlocking { host.stopAll() }

    private suspend fun install(id: String, js: String, permissions: String = "\"native\"") {
        val manifest = """{"id":"$id","name":"$id","version":"1.0.0","apiVersion":1,"permissions":[$permissions]}"""
        host.install(mapOf("manifest.json" to manifest.toByteArray(), "main.js" to js.toByteArray()))
        host.setEnabled(id, true)
    }

    private fun runtimeOf(id: String): PluginRuntime = host.chain("expose").first { it.first.id == id }.first

    @Test(timeout = 30_000)
    fun `reflection calls, overloads, fields, statics and conversions`() = runBlocking {
        install(
            "j1", """
            rikka.expose("t", () => {
              const T = rikka.java.use("me.rerere.plugin.BridgeTarget");
              const o = T.${'$'}new("bob");
              const out = {};
              out.greet = o.greet("amy");
              out.addInt = o.add(1, 2);
              out.addDouble = o.add(1.5, 2);
              out.secret = o.${'$'}get("secret");
              o.${'$'}set("secret", "changed");
              out.secret2 = o.${'$'}get("secret");
              out.twice = T.twice(21);
              T.counter = 5;
              out.counter = T.${'$'}get("counter");
              out.big = String(o.big());
              out.isBigInt = typeof o.big() === "bigint";
              out.name = o.getName();
              out.join = o.join("a", "b", "c");
              out.max = rikka.java.use("java.lang.Math").max(3, 7);
              out.longMax = String(rikka.java.use("java.lang.Long").${'$'}get("MAX_VALUE"));
              const sb = rikka.java.new("java.lang.StringBuilder", "a");
              sb.append("b").append(1);
              out.sb = sb.toString();
              const l = rikka.java.new("java.util.ArrayList");
              l.add("x"); l.add(2);
              out.list = l.${'$'}json();
              out.size = l.size();
              out.className = T.getName();
              out.isList = l.${'$'}is("java.util.List");
              out.type = l.${'$'}type;
              const arr = rikka.java.array("int", [1, 2, 3]);
              out.arrLen = rikka.java.use("java.lang.reflect.Array").getLength(arr);
              // 回归：类上调用带参 toString 应是静态方法 Arrays.toString(int[])
              out.arrStr = rikka.java.use("java.util.Arrays").toString(arr);
              // 回归：非 public 实现类上的 public 方法应经由公开接口调用
              const ex = rikka.java.use("java.util.concurrent.Executors").newSingleThreadExecutor();
              out.execClass = ex.${'$'}type;
              ex.shutdown();
              out.execShutdown = ex.isShutdown();
              try { o.noSuchMethod(); out.err = "none"; } catch (e) { out.err = String(e.message || e).indexOf("noSuchMethod") >= 0 ? "ok" : String(e); }
              return out;
            });
            """.trimIndent()
        )
        val r = host.callExposed("j1", "t", JsonNull).jsonObject
        fun s(k: String) = r[k]!!.jsonPrimitive.content
        assertEquals("hi amy from bob", s("greet"))
        assertEquals("3", s("addInt"))
        assertEquals("4", s("addDouble")) // 1.5 + 2 + 0.5：只有 double 重载能接收 1.5
        assertEquals("s3cr3t", s("secret"))
        assertEquals("changed", s("secret2"))
        assertEquals("42", s("twice"))
        assertEquals("5", s("counter"))
        assertEquals("9007199254740993", s("big"))
        assertEquals("true", s("isBigInt"))
        assertEquals("bob", s("name"))
        assertEquals("a-b-c", s("join"))
        assertEquals("7", s("max"))
        assertEquals(Long.MAX_VALUE.toString(), s("longMax"))
        assertEquals("ab1", s("sb"))
        assertEquals(listOf("x", "2"), r["list"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2", s("size"))
        assertEquals("me.rerere.plugin.BridgeTarget", s("className"))
        assertEquals("true", s("isList"))
        assertEquals("java.util.ArrayList", s("type"))
        assertEquals("3", s("arrLen"))
        assertEquals("ok", s("err"))
        assertEquals("[1, 2, 3]", s("arrStr"))
        assertEquals("true", s("execShutdown"))
        assertEquals(5, BridgeTarget.counter)
    }

    @Test(timeout = 30_000)
    fun `async calls let Java synchronously call back into JS`() = runBlocking {
        install(
            "j2", """
            rikka.expose("t", async () => {
              const l = rikka.java.new("java.util.ArrayList");
              [3, 1, 2].forEach((x) => l.add(x));
              await rikka.java.use("java.util.Collections").${'$'}async("sort", l, (a, b) => a - b);
              const o = rikka.java.new("me.rerere.plugin.BridgeTarget", "z");
              const cb = await o.${'$'}async("callBack", (s) => s + "!");
              return { sorted: l.${'$'}json(), cb: cb };
            });
            """.trimIndent()
        )
        val r = host.callExposed("j2", "t", JsonNull).jsonObject
        assertEquals(listOf(1L, 2L, 3L), r["sorted"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals("x!", r["cb"]!!.jsonPrimitive.content)
    }

    @Test(timeout = 30_000)
    fun `sync call that calls back into JS returns immediately with defaults`() = runBlocking {
        install(
            "j5", """
            rikka.expose("t", () => {
              const l = rikka.java.new("java.util.ArrayList");
              [3, 1, 2].forEach((x) => l.add(x));
              const t0 = Date.now();
              rikka.java.use("java.util.Collections").sort(l, (a, b) => a - b);
              return { order: l.${'$'}json(), ms: Date.now() - t0 };
            });
            """.trimIndent()
        )
        val r = host.callExposed("j5", "t", JsonNull).jsonObject
        // 同步调用中 Java 回调 JS：不能等待自身，比较器按默认值 0 处理，顺序不变，且必须立即返回
        assertEquals(listOf(3L, 1L, 2L), r["order"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertTrue("同步回调应立即返回，实际耗时 ${r["ms"]}ms", r["ms"]!!.jsonPrimitive.long < 2_000)
    }

    @Test(timeout = 30_000)
    fun `JS proxy implements a Java interface callable from other threads`() = runBlocking {
        install(
            "j3", """
            let calls = 0;
            rikka.expose("make", () => rikka.java.proxy("java.util.concurrent.Callable", {
              call() { calls++; return "from-js-" + calls; },
            }));
            """.trimIndent()
        )
        val handle = host.callExposed("j3", "make", JsonNull) as JsonObject
        val runtime = runtimeOf("j3")
        val id = handle["\$j"]!!.jsonPrimitive.long

        @Suppress("UNCHECKED_CAST")
        val callable = runtime.javaBridge.get(id) as Callable<String>
        assertEquals("from-js-1", callable.call())
        assertEquals("from-js-2", callable.call())
    }

    @Test(timeout = 30_000)
    fun `rikka java, host and hook require declared permissions`() = runBlocking {
        val js = """
            const err = (f) => { try { f(); return "allowed"; } catch (e) { return String(e && e.message || e); } };
            rikka.expose("t", () => ({
              java: err(() => rikka.java.use("java.lang.Math")),
              proxy: err(() => rikka.java.proxy("java.lang.Runnable", { run() {} })),
              host: err(() => rikka.host.context()),
              hook: err(() => rikka.hook.method("java.lang.Math", "abs", ["int"], { before() {} })),
            }));
        """.trimIndent()
        // 未声明任何权限：全部拒绝
        install("p0", js, permissions = "")
        val none = host.callExposed("p0", "t", JsonNull).jsonObject
        assertTrue(none["java"]!!.jsonPrimitive.content, none["java"]!!.jsonPrimitive.content.contains("缺少权限 native"))
        assertTrue(none["proxy"]!!.jsonPrimitive.content.contains("缺少权限 native"))
        assertTrue(none["hook"]!!.jsonPrimitive.content, none["hook"]!!.jsonPrimitive.content.contains("缺少权限 hook"))
        // host.* 由 app 层校验 native（此处 JVM 测试只确认没有被放行）
        assertTrue(none["host"]!!.jsonPrimitive.content != "allowed")
        // 只声明 native：反射可用，Hook 仍被拒绝（且在加载 Hook 引擎之前就拒绝）
        install("p1", js, permissions = "\"native\"")
        val nativeOnly = host.callExposed("p1", "t", JsonNull).jsonObject
        assertEquals("allowed", nativeOnly["java"]!!.jsonPrimitive.content)
        assertEquals("allowed", nativeOnly["proxy"]!!.jsonPrimitive.content)
        assertTrue(nativeOnly["hook"]!!.jsonPrimitive.content.contains("缺少权限 hook"))
    }

    @Test(timeout = 30_000)
    fun `plugin cannot rewrite or delete its manifest or host-managed storage`() = runBlocking {
        install(
            "pf", """
            const err = async (f) => { try { await f(); return "allowed"; } catch (e) { return String(e && e.message || e); } };
            rikka.expose("t", async () => ({
              manifest: await err(() => rikka.files.write("manifest.json", '{"id":"pf","name":"pf","version":"1","apiVersion":1,"permissions":["native","hook","network"]}')),
              manifestB64: await err(() => rikka.files.writeBase64("./manifest.json", "e30=")),
              manifestDel: await err(() => rikka.files.delete("manifest.json")),
              storage: await err(() => rikka.files.write("storage.json", "{}")),
              rootDel: await err(() => rikka.files.delete("")),
              normal: await err(() => rikka.files.write("data/manifest.json", "nested is fine")),
              readBack: await rikka.files.read("data/manifest.json"),
            }));
            """.trimIndent(),
            permissions = "",
        )
        val r = host.callExposed("pf", "t", JsonNull).jsonObject
        fun s(k: String) = r[k]!!.jsonPrimitive.content
        listOf("manifest", "manifestB64", "manifestDel", "storage").forEach { k ->
            assertTrue("$k: ${s(k)}", s(k).contains("不能修改宿主管理的文件"))
        }
        assertTrue(s("rootDel"), s("rootDel").contains("不能删除插件包根目录"))
        assertEquals("allowed", s("normal"))
        assertEquals("nested is fine", s("readBack"))
        // 磁盘上的 manifest 未被改动，权限仍为空
        val manifestFile = java.io.File(host.installedPlugin("pf")!!.dir, "manifest.json")
        assertTrue(manifestFile.readText().contains("\"permissions\":[]"))
    }

    @Test(timeout = 30_000)
    fun `handles are released when the plugin stops`() = runBlocking {
        install(
            "j4", """
            const keep = [];
            rikka.expose("t", () => { for (let i = 0; i < 5; i++) keep.push(rikka.java.new("java.lang.Object")); return rikka.java.stats(); });
            """.trimIndent()
        )
        val stats = host.callExposed("j4", "t", JsonNull).jsonObject
        assertTrue(stats["handles"]!!.jsonPrimitive.long >= 5)
        val runtime = runtimeOf("j4")
        host.setEnabled("j4", false)
        assertEquals(0, runtime.javaBridge.handleCount())
    }

    @Test(timeout = 30_000)
    fun `java copy updates data class fields by parameter name and proxy allows returning input handle`() = runBlocking {
        val cfgCls = SampleConfig::class.java.name
        val boxCls = ConfigBox::class.java.name
        install(
            "jcopy", """
            const Box = rikka.java.use("$boxCls");
            rikka.expose("t", async () => {
              const base = rikka.java.new("$cfgCls");
              const c1 = rikka.java.copy(base, { enabled: true, limit: 64, note: null, ignored: undefined });
              const c2 = c1.${'$'}copy({ label: "v2" });
              // 在异步线程上触发 UnaryOperator 回调并原样返回入参（验证回调临时句柄不会在解码返回值前被提前回收）
              const unchanged = await Box.${'$'}async("applyTransform", c2, (cur) => cur);
              const mutated = await Box.${'$'}async("applyTransform", c2, (cur) => rikka.java.copy(cur, { limit: 128 }));
              let badField = "";
              try { rikka.java.copy(base, { noSuchField: 1 }); } catch (e) { badField = String(e.message || e); }
              let badNull = "";
              try { rikka.java.copy(base, { enabled: null }); } catch (e) { badNull = String(e.message || e); }
              return {
                enabled: unchanged.getEnabled(),
                label: unchanged.getLabel(),
                limit1: unchanged.getLimit(),
                limit2: mutated.getLimit(),
                noteIsNull: unchanged.getNote() === null,
                badField,
                badNull,
              };
            });
            """.trimIndent(),
        )
        val r = host.callExposed("jcopy", "t", JsonNull).jsonObject
        assertEquals(true, r["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("v2", r["label"]!!.jsonPrimitive.content)
        assertEquals(64, r["limit1"]!!.jsonPrimitive.int)
        assertEquals(128, r["limit2"]!!.jsonPrimitive.int)
        assertEquals(true, r["noteIsNull"]!!.jsonPrimitive.boolean)
        assertTrue(r["badField"]!!.jsonPrimitive.content.contains("noSuchField"))
        assertTrue(r["badNull"]!!.jsonPrimitive.content.contains("enabled"))
    }
}
