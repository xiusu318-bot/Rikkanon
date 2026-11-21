package me.rerere.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.host.DefaultPluginServices
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.http.PluginIOException
import me.rerere.plugin.http.PluginInterceptor
import me.rerere.plugin.http.PluginRequestContext
import me.rerere.plugin.http.PluginRequestContexts
import me.rerere.plugin.store.PluginStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class PluginInterceptTest {
    private lateinit var host: PluginHost
    private val seen = mutableListOf<Request>()
    private var upstreamBody = ""

    /** 代替网络：记录收到的请求并返回固定 SSE 响应。 */
    private val fakeUpstream = Interceptor { chain ->
        val req = chain.request()
        seen += req
        Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "text/event-stream")
            .body(upstreamBody.toResponseBody("text/event-stream".toMediaType()))
            .build()
    }

    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        val dir = Files.createTempDirectory("plugins").toFile()
        host = PluginHost(PluginStore(dir)) { h -> DefaultPluginServices(h, OkHttpClient()) }
        client = OkHttpClient.Builder()
            .addInterceptor(PluginInterceptor(host))
            .addInterceptor(fakeUpstream)
            .build()
    }

    @After
    fun teardown() = runBlocking { host.stopAll() }

    private suspend fun enable(id: String, js: String) {
        host.install(
            mapOf(
                "manifest.json" to """{"id":"$id","name":"$id","version":"1","apiVersion":1}""".toByteArray(),
                "main.js" to js.toByteArray(),
            )
        )
        host.setEnabled(id, true)
    }

    private fun post(body: String, token: String? = null): Response {
        val builder = Request.Builder().url("https://api.example.com/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
        token?.let { builder.header(PluginRequestContexts.HEADER, it) }
        return client.newCall(builder.build()).execute()
    }

    @Test(timeout = 30_000)
    fun `no plugin passes through and strips context header`() {
        upstreamBody = "data: hi\n\n"
        val token = PluginRequestContexts.register(PluginRequestContext(buildJsonObject { put("task", "chat") }) { true })
        val res = post("{}", token)
        assertEquals("data: hi\n\n", res.body.string())
        assertNull(seen.single().header(PluginRequestContexts.HEADER))
    }

    @Test(timeout = 30_000)
    fun `rewrite request and transform sse stream`() = runBlocking {
        upstreamBody = "data: {\"t\":\"a\"}\n\ndata: {\"t\":\"b\"}\n\ndata: [DONE]\n\n"
        enable(
            "proxy", """
            rikka.intercept(async (req, next, ctx) => {
              const body = req.json();
              body.tool_choice = "required";
              body.task = ctx.task;
              const res = await next({ ...req, url: req.url.replace("api.example.com", "proxy.local"), body });
              async function* transform() {
                for await (const ev of rikka.sse.parse(res.body)) {
                  if (ev.data === "[DONE]") { yield ev; continue; }
                  const d = JSON.parse(ev.data);
                  yield { data: { t: d.t.toUpperCase() } };
                  yield { data: { t: d.t + "!" } };
                }
              }
              return { status: res.status, headers: res.headers, body: rikka.sse.encode(transform()) };
            });
            """
        )
        val token = PluginRequestContexts.register(PluginRequestContext(buildJsonObject { put("task", "chat") }) { true })
        val res = withIo { post("""{"model":"x"}""", token) }
        val text = withIo { res.body.string() }
        assertEquals(
            "data: {\"t\":\"A\"}\n\ndata: {\"t\":\"a!\"}\n\ndata: {\"t\":\"B\"}\n\ndata: {\"t\":\"b!\"}\n\ndata: [DONE]\n\n",
            text,
        )
        val upstreamReq = seen.single()
        assertEquals("proxy.local", upstreamReq.url.host)
        val sentBody = okio.Buffer().also { upstreamReq.body!!.writeTo(it) }.readUtf8()
        assertTrue(sentBody, sentBody.contains("\"tool_choice\":\"required\"") && sentBody.contains("\"task\":\"chat\""))
        assertNull(upstreamReq.header(PluginRequestContexts.HEADER))
    }

    @Test(timeout = 30_000)
    fun `short circuit and chain order`() = runBlocking {
        enable("a", "rikka.intercept(async (req, next) => { const r = await next(req); return { status: 200, body: 'A(' + await r.text() + ')' }; });")
        enable("b", "rikka.intercept(async (req, next) => ({ status: 201, headers: {'Content-Type':'text/plain'}, body: 'mock' }));")
        val res = withIo { post("{}") }
        assertEquals(200, res.code)
        assertEquals("A(mock)", withIo { res.body.string() })
        assertTrue("upstream must not be called", seen.isEmpty())
    }

    @Test(timeout = 30_000)
    fun `pass through upstream response object`() = runBlocking {
        upstreamBody = "data: x\n\n"
        enable("p", "rikka.intercept(async (req, next) => next(req));")
        val res = withIo { post("{}") }
        assertEquals("data: x\n\n", withIo { res.body.string() })
    }

    @Test(timeout = 30_000)
    fun `plugin error surfaces as PluginIOException`() = runBlocking {
        enable("bad", "rikka.intercept(async () => { throw new Error('nope'); });")
        try {
            withIo { post("{}") }
            fail("should throw")
        } catch (e: PluginIOException) {
            assertTrue(e.message!!, e.message!!.startsWith("[plugin:bad] intercept: ") && e.message!!.contains("nope"))
        }
        Unit
    }

    @Test(timeout = 30_000)
    fun `upstream IOException is preserved instead of wrapped as PluginIOException`() = runBlocking {
        val failingClient = OkHttpClient.Builder()
            .addInterceptor(PluginInterceptor(host))
            .addInterceptor { throw java.net.SocketException("Software caused connection abort") }
            .build()
        enable("pass", "rikka.intercept(async (req, next) => next(req));")
        try {
            withIo {
                failingClient.newCall(
                    Request.Builder().url("https://api.example.com/v1/chat/completions")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()
            }
            fail("should throw")
        } catch (e: java.net.SocketException) {
            assertEquals("Software caused connection abort", e.message)
        }
        Unit
    }

    private suspend fun <T> withIo(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    @Test
    fun `external stream body is isolated per plugin and closed when plugin stops`() = runBlocking {
        var bodyClosed = false
        val rawBody = object : okhttp3.ResponseBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength(): Long = -1L
            override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8("line1\nline2\n")
            override fun close() {
                bodyClosed = true
                super.close()
            }
        }
        val dummyReq = Request.Builder().url("https://api.example.com/sse").build()
        val dummyResp = okhttp3.Response.Builder()
            .request(dummyReq)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(rawBody)
            .build()

        val regJson = PluginInterceptor.registerExternalBody(dummyResp, pluginId = "p.owner")
        val bodyId = regJson["bodyId"]!!.jsonPrimitive.content
        val arg = buildJsonObject { put("bodyId", bodyId) }

        val crossRead = runCatching { PluginInterceptor.readLine(arg, callerPluginId = "p.other") }
        assertTrue(crossRead.isFailure)
        val crossClose = runCatching { PluginInterceptor.closeBody(arg, callerPluginId = "p.other") }
        assertTrue(crossClose.isFailure)
        assertEquals(false, bodyClosed)

        assertEquals("line1\n", PluginInterceptor.readLine(arg, callerPluginId = "p.owner").jsonPrimitive.content)
        assertEquals(false, bodyClosed)
        PluginInterceptor.closeExternalBodiesOf("p.owner")
        assertEquals(true, bodyClosed)
    }

    @Test
    fun `uiMountsBySlot tracks visible mounts and clears on stop`() = runBlocking {
        assertTrue(host.uiMountsBySlot.value.isEmpty())
        enable(
            "ui.p",
            """
            const m1 = rikka.ui.mount("message.footer", { type: "button", title: "A" }, () => {});
            const m2 = rikka.ui.mount("message.footer", { type: "button", title: "B", visible: false }, () => {});
            rikka.expose("show", () => rikka.ui.update(m2, { visible: true }));
            """.trimIndent(),
        )
        assertEquals(1, host.uiMountsBySlot.value["message.footer"]?.size)
        host.callExposed("ui.p", "show", JsonNull)
        assertEquals(2, host.uiMountsBySlot.value["message.footer"]?.size)
        host.setEnabled("ui.p", false)
        assertTrue(host.uiMountsBySlot.value.isEmpty())
    }
}
