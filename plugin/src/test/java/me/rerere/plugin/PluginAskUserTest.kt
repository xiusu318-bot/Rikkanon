package me.rerere.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.error.PluginException
import me.rerere.plugin.host.DefaultPluginServices
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.host.PluginToolAskUser
import me.rerere.plugin.runtime.PluginCallPause
import me.rerere.plugin.store.PluginStore
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * ctx.askUser：等待用户回答的时间不计入插件调用超时；
 * 以及宿主转发工具中间状态的方式（只在 flow 自身协程 emit）。
 */
class PluginAskUserTest {
    private lateinit var host: PluginHost

    @Before
    fun setup() {
        val dir = Files.createTempDirectory("plugins").toFile()
        host = PluginHost(PluginStore(dir)) { h -> DefaultPluginServices(h, OkHttpClient()) }
    }

    @After
    fun teardown() = runBlocking { host.stopAll() }

    private suspend fun installAskTool(js: String) {
        val manifest = """{"id":"ask","name":"ask","version":"1.0.0","apiVersion":1,"permissions":[]}"""
        host.install(mapOf("manifest.json" to manifest.toByteArray(), "main.js" to js.toByteArray()))
        host.setEnabled("ask", true)
        host.updateState("ask") { it.copy(callTimeoutMillis = 300) }
    }

    /** 与 PluginBridge 相同的方式执行插件工具：登记 askUser 回调，等待期间暂停计时。 */
    private suspend fun runTool(answerAfterMillis: Long, usePause: Boolean): String {
        val (rt, reg) = host.chain("tool").single()
        val pause = PluginCallPause()
        val askToken = PluginToolAskUser.register { spec: JsonObject ->
            if (usePause) pause.pause()
            try {
                delay(answerAfterMillis)
                "answer:" + spec["question"]!!.jsonPrimitive.content
            } finally {
                if (usePause) pause.resume()
            }
        }
        try {
            val result = rt.invokeInternal(
                "tool", "__tool",
                buildJsonObject {
                    put("handler", reg.handlerId)
                    put("token", "")
                    put("askToken", askToken)
                    put("args", buildJsonObject { })
                    put("ctx", buildJsonObject { put("task", "chat") })
                },
                pause = if (usePause) pause else null,
            )
            return result.jsonPrimitive.content
        } finally {
            PluginToolAskUser.remove(askToken)
        }
    }

    @Test(timeout = 30_000)
    fun `waiting for user answer does not count toward timeout`() = runBlocking {
        installAskTool(
            """
            rikka.registerTool({ name: "ask_env", execute: async (args, ctx) => {
              const a = await ctx.askUser({ question: "env" });
              return "got " + a;
            }});
            """
        )
        // 超时 300ms，用户 1.2 秒后才回答
        assertEquals("got answer:env", runTool(answerAfterMillis = 1_200, usePause = true))
    }

    @Test(timeout = 30_000)
    fun `without pause the same wait times out`() = runBlocking {
        installAskTool(
            """
            rikka.registerTool({ name: "ask_env", execute: async (args, ctx) => {
              return "got " + (await ctx.askUser({ question: "env" }));
            }});
            """
        )
        try {
            runTool(answerAfterMillis = 1_200, usePause = false)
            fail("should time out")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("超时"))
        }
    }

    @Test(timeout = 30_000)
    fun `plugin own work after answer still times out`() = runBlocking {
        installAskTool(
            """
            rikka.registerTool({ name: "ask_env", execute: async (args, ctx) => {
              await ctx.askUser({ question: "env" });
              await new Promise(() => {}); // 回答后插件自己卡死
            }});
            """
        )
        val start = System.currentTimeMillis()
        try {
            runTool(answerAfterMillis = 800, usePause = true)
            fail("should time out")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("超时"))
        }
        val elapsed = System.currentTimeMillis() - start
        // 等待 800ms 不计时，之后 300ms 超时；复查间隔 1s，总耗时应在 1.1s ~ 3s 之间
        assertTrue("elapsed=$elapsed", elapsed in 1_000..3_000)
    }

    /**
     * 与 GenerationLoop 相同结构：工具在 async 里执行，中间状态来自 launch 与外部协程（插件运行时），
     * 只写入 Channel，由 flow 自身协程用 select 转发。修改前的写法（直接在这些协程里 emit）会抛
     * "Flow invariant is violated"。
     */
    @Test(timeout = 30_000)
    fun `tool updates are forwarded from flow coroutine`() = runBlocking {
        val foreign = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = flow {
            val mutex = Mutex()
            val live = Channel<String>(Channel.CONFLATED)
            val results = coroutineScope {
                val jobs = listOf("a", "b").map { name ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        val toolScope = this
                        // ctx.progress：launch 里更新
                        toolScope.launch { mutex.withLock { live.trySend("$name:progress") } }
                        delay(100)
                        // ctx.askUser：插件运行时的协程里更新，并等待
                        foreign.async {
                            mutex.withLock { live.trySend("$name:ask") }
                            delay(100)
                        }.await()
                        "$name:done"
                    }
                }
                val all = async { jobs.awaitAll() }
                while (!all.isCompleted) {
                    select<Unit> {
                        all.onAwait { }
                        live.onReceive { emit(it) }
                    }
                }
                all.await()
            }
            results.forEach { emit(it) }
        }
        val out = f.toList()
        assertTrue(out.toString(), out.takeLast(2) == listOf("a:done", "b:done"))
        assertTrue(out.toString(), out.any { it.endsWith(":ask") })
    }
}
