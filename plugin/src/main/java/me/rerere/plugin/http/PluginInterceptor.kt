package me.rerere.plugin.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.error.PluginException
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.plugin.registry.PluginRegistration
import me.rerere.plugin.runtime.PluginRuntime
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 插件错误在 OkHttp 链路中以 IOException 形式抛出（非 IOException 会使异步调用线程崩溃）。 */
class PluginIOException(val pluginError: PluginException) : IOException(pluginError.message, pluginError)

/** 请求所属的插件上下文：传给插件的 ctx 与按助手的插件过滤。 */
class PluginRequestContext(val ctx: JsonObject, val filter: (String) -> Boolean)

/**
 * 宿主在发请求前登记上下文，并通过请求头 [HEADER] 携带 token；拦截器取出后移除该请求头，不会发往上游。
 */
object PluginRequestContexts {
    const val HEADER = "X-Rikkanon-Plugin-Ctx"
    private val map = ConcurrentHashMap<String, PluginRequestContext>()

    fun register(context: PluginRequestContext): String =
        UUID.randomUUID().toString().also { map[it] = context }

    fun get(token: String): PluginRequestContext? = map[token]

    fun remove(token: String) {
        map.remove(token)
    }
}

/**
 * 插件 intercept 链的 OkHttp 应用层拦截器。没有已启用的 intercept 时只移除上下文请求头后直接放行。
 */
class PluginInterceptor(private val host: PluginHost) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = original.header(PluginRequestContexts.HEADER)
        val request = if (token != null) {
            original.newBuilder().removeHeader(PluginRequestContexts.HEADER).build()
        } else original
        val context = token?.let { PluginRequestContexts.get(it) }
        val ctx = context?.ctx ?: buildJsonObject { put("task", "other") }
        val handlers = host.chain(PluginHookKind.INTERCEPT, context?.filter ?: { true })
        if (handlers.isEmpty()) return chain.proceed(request)

        val session = InterceptSession(chain, handlers, ctx)
        try {
            val response = runBlocking { session.run(0, request) }
            return session.attachCleanup(response)
        } catch (e: Throwable) {
            val upstreamIo = session.matchUpstreamIoException(e)
            session.close()
            if (upstreamIo != null) throw upstreamIo
            throw when (e) {
                is IOException -> e
                is PluginException -> PluginIOException(e)
                else -> PluginIOException(PluginException("unknown", "intercept", "${e.javaClass.name}: ${e.message}", e))
            }
        }
    }

    companion object {
        private val sessions = ConcurrentHashMap<String, Pair<InterceptSession, Int>>()
        private val bodies = ConcurrentHashMap<String, InterceptSession.UpstreamBody>()

        /** JS `next(req)`：进入链上下一个插件或真正发出请求。 */
        suspend fun handleNext(args: JsonElement): JsonElement {
            val obj = args.jsonObject
            val token = obj["token"]!!.jsonPrimitive.content
            val (session, index) = sessions[token] ?: error("intercept 会话已结束，next() 不能在 intercept 返回后调用")
            return session.next(index, obj["req"]!!.jsonObject)
        }

        suspend fun readLine(args: JsonElement): JsonElement {
            val body = bodyOf(args)
            val line = try {
                withContext(Dispatchers.IO) { body.source.readUtf8Line() }
            } catch (e: IOException) {
                body.session.recordUpstreamIoException(e)
                throw e
            }
            return if (line == null) JsonNull else JsonPrimitive(line + "\n")
        }

        suspend fun readText(args: JsonElement): JsonElement {
            val body = bodyOf(args)
            val text = try {
                withContext(Dispatchers.IO) { body.source.readUtf8() }
            } catch (e: IOException) {
                body.session.recordUpstreamIoException(e)
                throw e
            }
            return JsonPrimitive(text)
        }

        private fun bodyOf(args: JsonElement): InterceptSession.UpstreamBody {
            val id = args.jsonObject["bodyId"]!!.jsonPrimitive.content
            return bodies[id] ?: error("响应体 $id 不存在或已关闭")
        }
    }

    private class InterceptSession(
        private val chain: Interceptor.Chain,
        private val handlers: List<Pair<PluginRuntime, PluginRegistration>>,
        private val ctx: JsonObject,
    ) {
        class UpstreamBody(val session: InterceptSession, val response: Response, val source: BufferedSource)

        private val id = UUID.randomUUID().toString()
        private val tokens = mutableListOf<String>()
        private val bodyIds = mutableListOf<String>()
        private val originalRequests = ConcurrentHashMap<Int, Request>()

        @Volatile
        private var lastUpstreamIoException: IOException? = null

        @Volatile
        private var closed = false

        fun recordUpstreamIoException(e: IOException) {
            if (e !is PluginIOException) {
                lastUpstreamIoException = e
            }
        }

        /**
         * 若异常本质上是上游网络抛出的原始 IOException（穿过 JS Promise 后被包装成 PluginException），
         * 还原为原始 IOException，确保宿主的自动网络重试机制正常工作。
         */
        fun matchUpstreamIoException(error: Throwable): IOException? {
            val io = lastUpstreamIoException ?: return null
            val msg = error.message.orEmpty()
            val ioName = io.javaClass.name
            val ioMsg = io.message.orEmpty()
            return if (ioName in msg || (ioMsg.isNotEmpty() && ioMsg in msg)) io else null
        }

        suspend fun run(index: Int, request: Request): Response {
            if (index >= handlers.size) {
                return try {
                    withContext(Dispatchers.IO) { chain.proceed(request) }
                } catch (e: IOException) {
                    recordUpstreamIoException(e)
                    throw e
                }
            }
            val (runtime, reg) = handlers[index]
            val token = "$id-$index"
            synchronized(tokens) { tokens += token }
            sessions[token] = this to index
            originalRequests[index] = request
            val payload = buildJsonObject {
                put("handler", reg.handlerId)
                put("token", token)
                put("req", requestToJson(request))
                put("ctx", ctx)
            }
            // intercept 包含等待上游的时间，不设插件超时
            val result = runtime.invokeInternal("intercept", "__intercept", payload, timeoutMillis = null)
            return toResponse(result.jsonObject, request, runtime)
        }

        suspend fun next(index: Int, req: JsonObject): JsonElement {
            val base = originalRequests[index] ?: error("intercept 会话状态丢失")
            val response = run(index + 1, jsonToRequest(req, base))
            val bodyId = UUID.randomUUID().toString()
            bodies[bodyId] = UpstreamBody(this, response, response.body.source())
            synchronized(bodyIds) { bodyIds += bodyId }
            return buildJsonObject {
                put("status", response.code)
                put("headers", headersToJson(response.headers))
                put("bodyId", bodyId)
            }
        }

        private fun toResponse(obj: JsonObject, request: Request, runtime: PluginRuntime): Response {
            val status = obj["status"]?.jsonPrimitive?.int ?: 200
            val headers = jsonToHeaders(obj["headers"])
            val bodyId = obj["bodyId"]?.jsonPrimitive?.contentOrNull
            val upstream = bodyId?.let { bodies[it] ?: error("响应体 $it 不存在") }
            val builder = Response.Builder()
                .request(request)
                .protocol(upstream?.response?.protocol ?: Protocol.HTTP_1_1)
                .code(status)
                .message(upstream?.response?.message ?: "")
            return when {
                upstream != null -> {
                    synchronized(bodyIds) { bodyIds.remove(bodyId) }
                    bodies.remove(bodyId)
                    builder.headers(headers).body(upstream.response.body).build()
                }

                obj["iterId"] != null -> {
                    val mediaType = headers["Content-Type"]?.toMediaTypeOrNull()
                    val source = JsIteratorSource(this, runtime, obj["iterId"]!!.jsonPrimitive.content).buffer()
                    builder.headers(headers.newBuilder().removeAll("Content-Length").build())
                        .body(StreamingBody(mediaType, source))
                        .build()
                }

                else -> {
                    val text = obj["text"]?.jsonPrimitive?.content ?: ""
                    val mediaType = headers["Content-Type"]?.toMediaTypeOrNull()
                    builder.headers(headers.newBuilder().removeAll("Content-Length").build())
                        .body(text.toResponseBody(mediaType))
                        .build()
                }
            }
        }

        /** 最终响应关闭时清理会话（流式 body 在 intercept 返回后仍会读取上游）。 */
        fun attachCleanup(response: Response): Response {
            val body = response.body
            val wrapped = object : ResponseBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun source(): BufferedSource = body.source()
                override fun close() {
                    try {
                        body.close()
                    } finally {
                        this@InterceptSession.close()
                    }
                }
            }
            return response.newBuilder().body(wrapped).build()
        }

        fun close() {
            if (closed) return
            closed = true
            synchronized(tokens) { tokens.forEach { sessions.remove(it) } }
            synchronized(bodyIds) {
                bodyIds.forEach { bodies.remove(it)?.response?.close() }
                bodyIds.clear()
            }
        }
    }

    private class StreamingBody(private val mediaType: MediaType?, private val source: BufferedSource) : ResponseBody() {
        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }

    /** 从插件返回的异步迭代器按需拉取 body 片段。 */
    private class JsIteratorSource(
        private val session: InterceptSession,
        private val runtime: PluginRuntime,
        private val iterId: String,
    ) : Source {
        private val buffer = Buffer()
        private var done = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (buffer.size == 0L && !done) {
                val next = try {
                    runBlocking {
                        runtime.invokeInternal(
                            "intercept", "__iter_next",
                            buildJsonObject { put("iterId", iterId) }, timeoutMillis = null,
                        )
                    }
                } catch (e: PluginException) {
                    done = true
                    session.matchUpstreamIoException(e)?.let { throw it }
                    throw PluginIOException(e)
                }
                if (next is JsonNull) done = true else buffer.writeUtf8(next.jsonPrimitive.content)
            }
            if (buffer.size == 0L) return -1
            return buffer.read(sink, byteCount)
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            if (done) return
            done = true
            runBlocking {
                runCatching {
                    runtime.invokeInternal("intercept", "__iter_close", buildJsonObject { put("iterId", iterId) })
                }
            }
        }
    }
}

private val TEXT_TYPES = setOf("json", "text", "xml", "x-www-form-urlencoded", "javascript", "event-stream")

private fun isTextual(type: MediaType?): Boolean =
    type == null || type.type == "text" || TEXT_TYPES.any { type.subtype.contains(it) }

private fun headersToJson(headers: Headers): JsonObject =
    JsonObject(headers.toMultimap().mapValues { JsonPrimitive(it.value.joinToString(", ")) })

private fun jsonToHeaders(element: JsonElement?): Headers {
    val obj = element as? JsonObject ?: return Headers.Builder().build()
    return Headers.Builder().apply {
        obj.forEach { (k, v) -> add(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
    }.build()
}

private fun requestToJson(request: Request): JsonObject = buildJsonObject {
    put("url", request.url.toString())
    put("method", request.method)
    put("headers", headersToJson(request.headers))
    val body = request.body
    when {
        body == null -> put("body", JsonNull)
        isTextual(body.contentType()) -> {
            val buffer = Buffer()
            body.writeTo(buffer)
            put("body", buffer.readUtf8())
        }
        // 二进制 / multipart 不转为文本，插件返回 body 为 null 时沿用原始 body
        else -> {
            put("body", JsonNull)
            put("binaryBody", true)
        }
    }
}

private fun jsonToRequest(obj: JsonObject, base: Request): Request {
    val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: base.method
    val headers = jsonToHeaders(obj["headers"])
    val bodyText = obj["body"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    val mediaType = headers["Content-Type"]?.toMediaTypeOrNull() ?: base.body?.contentType()
    val body = when {
        bodyText != null -> bodyText.toRequestBody(mediaType)
        base.body != null && !isTextual(base.body?.contentType()) -> base.body
        else -> null
    }
    return base.newBuilder()
        .url(obj["url"]?.jsonPrimitive?.contentOrNull ?: base.url.toString())
        .headers(headers)
        .method(method, body)
        .build()
}
