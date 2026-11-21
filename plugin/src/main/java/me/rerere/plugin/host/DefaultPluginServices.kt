package me.rerere.plugin.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.plugin.http.PluginInterceptor
import me.rerere.plugin.http.headersToJson
import me.rerere.plugin.manifest.PluginPermission
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.plugin.runtime.PluginRuntimeServices
import me.rerere.plugin.store.PluginKvStorage
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** app 层提供的宿主 API（对话、设置等）。名称未知时必须抛异常。 */
interface PluginHostApi {
    suspend fun call(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement
    fun callSync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement =
        error("未知同步宿主 API: $name")
}

/**
 * 内置服务：log / storage / fetch / fetch.stream / ws / files，其余转交 [hostApi]（由 hostApi 按权限校验）。
 * [fetchClient] 为插件专用 OkHttp 客户端，不得挂载插件 intercept 拦截器。
 * [clientResolver] 可选：按单次请求的 `proxy` 参数返回绑定了对应代理或直连的 OkHttpClient。
 */
class DefaultPluginServices(
    private val host: PluginHost,
    private val fetchClient: OkHttpClient,
    private val hostApi: PluginHostApi = object : PluginHostApi {
        override suspend fun call(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement =
            error("未知宿主 API: $name")
    },
    private val clientResolver: (String?) -> OkHttpClient = { fetchClient },
) : PluginRuntimeServices {
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeWebSockets = ConcurrentHashMap<String, Pair<String, WebSocket>>()

    private fun storageOf(id: String): PluginKvStorage {
        host.installedPlugin(id) ?: error("插件 $id 未安装")
        return host.storage(id)
    }

    override fun onPluginStopped(pluginId: String) {
        activeWebSockets.entries.removeIf { (_, pair) ->
            if (pair.first == pluginId) {
                runCatching { pair.second.close(1000, "Plugin stopped") }
                true
            } else false
        }
        PluginInterceptor.closeExternalBodiesOf(pluginId)
    }

    override fun callSync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement {
        val id = runtime.id
        return when (name) {
            "log" -> {
                val o = args.jsonObject
                host.appendLog(id, o["level"]?.jsonPrimitive?.content ?: "info", o["text"]?.jsonPrimitive?.content ?: "")
                JsonNull
            }

            "storage.get" -> storageOf(id).get(args.jsonObject["key"]!!.jsonPrimitive.content) ?: JsonNull
            "storage.set" -> {
                val o = args.jsonObject
                storageOf(id).set(o["key"]!!.jsonPrimitive.content, o["value"] ?: JsonNull)
                host.bumpVersion()
                JsonNull
            }

            "storage.delete" -> {
                storageOf(id).delete(args.jsonObject["key"]!!.jsonPrimitive.content)
                host.bumpVersion()
                JsonNull
            }

            "storage.keys" -> JsonArray(storageOf(id).keys().map { JsonPrimitive(it) })
            else -> hostApi.callSync(runtime, name, args)
        }
    }

    override suspend fun callAsync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement =
        when (name) {
            "fetch" -> {
                runtime.requirePermission(PluginPermission.NETWORK)
                fetch(runtime.id, args.jsonObject, stream = false)
            }

            "fetch.stream" -> {
                runtime.requirePermission(PluginPermission.NETWORK)
                fetch(runtime.id, args.jsonObject, stream = true)
            }

            "ws.connect" -> {
                runtime.requirePermission(PluginPermission.NETWORK)
                wsConnect(runtime, args.jsonObject)
            }

            // 连接只能经 ws.connect（已校验 network 权限）建立
            "ws.send" -> wsSend(runtime, args.jsonObject)
            "ws.close" -> wsClose(runtime, args.jsonObject)

            "http.next" -> PluginInterceptor.handleNext(args)
            "http.body.read" -> PluginInterceptor.readLine(args, callerPluginId = runtime.id)
            "http.body.text" -> PluginInterceptor.readText(args, callerPluginId = runtime.id)
            "http.body.base64" -> PluginInterceptor.readBase64(args, callerPluginId = runtime.id)
            "http.body.close" -> PluginInterceptor.closeBody(args, callerPluginId = runtime.id)

            "files.read", "files.write", "files.readBase64", "files.writeBase64",
            "files.list", "files.delete", "files.exists" -> withContext(Dispatchers.IO) {
                pluginFile(runtime, name, args.jsonObject)
            }

            "tool.progress" -> {
                val o = args.jsonObject
                val token = o["token"]?.jsonPrimitive?.contentOrNull ?: error("ctx.progress 只能在工具 execute 中使用")
                val sink = PluginToolProgress.get(token) ?: error("工具调用已结束，不能再报告进度")
                sink(o["parts"] ?: JsonNull)
                JsonNull
            }

            "tool.askUser" -> {
                val o = args.jsonObject
                val handler = PluginToolAskUser.get(o.getValue("token").jsonPrimitive.content)
                    ?: error("工具调用已结束，不能再提问")
                val answer = handler(o.getValue("spec").jsonObject)
                JsonPrimitive(answer)
            }

            "plugins.call" -> {
                val o = args.jsonObject
                host.callExposed(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["args"] ?: JsonNull)
            }

            "ui.open" -> {
                val presentation = args.jsonObject["presentation"]?.jsonPrimitive?.content
                require(presentation in me.rerere.plugin.manifest.PluginUiSlot.PRESENTATIONS) { "ui.open: 未知 presentation $presentation" }
                hostApi.call(runtime, name, args)
            }

            else -> hostApi.call(runtime, name, args)
        }

    private fun pluginFile(runtime: PluginRuntime, name: String, o: JsonObject): JsonElement {
        val plugin = host.installedPlugin(runtime.id) ?: error("插件未安装")
        val root = plugin.dir.canonicalFile
        val rel = o["path"]?.jsonPrimitive?.content ?: ""
        val file = File(root, rel).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "路径超出插件目录: $rel" }
        if (name == "files.write" || name == "files.writeBase64" || name == "files.delete") {
            // manifest.json 决定插件权限、storage.json 由宿主管理（经 rikka.storage 读写）：插件不能自行改写或删除，
            // 否则可以给自己追加权限。删除包根目录同样会删掉它们。
            require(file != root) { "不能删除插件包根目录" }
            require(file.parentFile?.canonicalFile != root || file.name !in PROTECTED_FILES) {
                "不能修改宿主管理的文件: ${file.name}"
            }
        }
        return when (name) {
            "files.read" -> JsonPrimitive(file.readText())
            "files.write" -> {
                file.parentFile?.mkdirs()
                file.writeText(o["text"]!!.jsonPrimitive.content)
                JsonNull
            }
            "files.readBase64" -> JsonPrimitive(Base64.getEncoder().encodeToString(file.readBytes()))
            "files.writeBase64" -> {
                file.parentFile?.mkdirs()
                file.writeBytes(Base64.getDecoder().decode(o["base64"]!!.jsonPrimitive.content))
                JsonNull
            }
            "files.list" -> JsonArray(file.listFiles().orEmpty().sortedBy { it.name }.map { f ->
                buildJsonObject {
                    put("name", f.name)
                    put("isDirectory", f.isDirectory)
                    put("size", f.length())
                }
            })
            "files.delete" -> JsonPrimitive(file.deleteRecursively())
            "files.exists" -> JsonPrimitive(file.exists())
            else -> error(name)
        }
    }

    private fun buildHttpRequest(req: JsonObject): Pair<OkHttpClient, Request> {
        val url = req["url"]?.jsonPrimitive?.contentOrNull ?: error("fetch: url is required")
        val method = req["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val headers = (req["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val bodyBase64 = req["bodyBase64"]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
        val bodyText = when (val b = req["body"]) {
            null, JsonNull -> null
            is JsonPrimitive -> b.content
            else -> b.toString()
        }
        val contentType = headers.entries.find { it.key.equals("content-type", true) }?.value
            ?: if (req["body"] is JsonObject || req["body"] is JsonArray) "application/json"
            else if (bodyBase64 != null) "application/octet-stream"
            else "text/plain"
        val requestBody = when {
            bodyBase64 != null -> Base64.getDecoder().decode(bodyBase64).toRequestBody(contentType.toMediaTypeOrNull())
            bodyText != null -> bodyText.toRequestBody(contentType.toMediaTypeOrNull())
            method in setOf("POST", "PUT", "PATCH") -> "".toRequestBody(contentType.toMediaTypeOrNull())
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .method(method, requestBody)
            .build()
        val proxySpec = req["proxy"]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
        val base = clientResolver(proxySpec)
        val timeoutMs = req["timeoutMs"]?.let { if (it is JsonNull) null else it.jsonPrimitive.longOrNull }
        val client = if (timeoutMs != null && timeoutMs > 0) {
            base.newBuilder().readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        } else base
        return client to request
    }

    /**
     * fetch / fetch.stream：
     * - 非流式：`{ status, headers, body, bodyBase64? }`
     * - 流式（`stream: true`）：登记到 [PluginInterceptor.registerExternalBody]，返回 `{ status, headers, bodyId }`
     */
    private suspend fun fetch(pluginId: String, req: JsonObject, stream: Boolean): JsonElement = withContext(Dispatchers.IO) {
        val (client, request) = buildHttpRequest(req)
        val wantBase64 = req["base64"]?.jsonPrimitive?.booleanOrNull == true
        val res = client.newCall(request).execute()
        if (stream) {
            return@withContext PluginInterceptor.registerExternalBody(res, pluginId = pluginId)
        }
        res.use { response ->
            buildJsonObject {
                put("status", response.code)
                put("headers", headersToJson(response.headers))
                if (wantBase64) {
                    val b64 = Base64.getEncoder().encodeToString(response.body.bytes())
                    put("body", b64)
                    put("bodyBase64", b64)
                } else {
                    put("body", response.body.string())
                }
            }
        }
    }

    private suspend fun wsConnect(runtime: PluginRuntime, req: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val wsId = "${runtime.id}:${req["wsId"]!!.jsonPrimitive.content}"
        val jsWsId = req["wsId"]!!.jsonPrimitive.content
        val url = req["url"]!!.jsonPrimitive.content
        val headers = (req["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val proxySpec = req["proxy"]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
        val client = clientResolver(proxySpec)
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .build()
        val openDeferred = CompletableDeferred<Unit>()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openDeferred.complete(Unit)
                wsScope.launch {
                    deliverWsEvent(runtime) {
                        runtime.invokeInternal("ws", "__ws_event", buildJsonObject {
                            put("wsId", jsWsId)
                            put("event", "open")
                            put("status", response.code)
                        })
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                wsScope.launch {
                    deliverWsEvent(runtime) {
                        runtime.invokeInternal("ws", "__ws_event", buildJsonObject {
                            put("wsId", jsWsId)
                            put("event", "message")
                            put("data", text)
                            put("binary", false)
                        })
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                wsScope.launch {
                    deliverWsEvent(runtime) {
                        runtime.invokeInternal("ws", "__ws_event", buildJsonObject {
                            put("wsId", jsWsId)
                            put("event", "message")
                            put("data", bytes.base64())
                            put("binary", true)
                        })
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeWebSockets.remove(wsId)
                wsScope.launch {
                    deliverWsEvent(runtime) {
                        runtime.invokeInternal("ws", "__ws_event", buildJsonObject {
                            put("wsId", jsWsId)
                            put("event", "close")
                            put("code", code)
                            put("reason", reason)
                        })
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeWebSockets.remove(wsId)
                if (!openDeferred.isCompleted) {
                    openDeferred.completeExceptionally(t)
                } else {
                    wsScope.launch {
                        deliverWsEvent(runtime) {
                            runtime.invokeInternal("ws", "__ws_event", buildJsonObject {
                                put("wsId", jsWsId)
                                put("event", "error")
                                put("message", t.message ?: t.javaClass.name)
                            })
                        }
                    }
                }
            }
        })
        activeWebSockets[wsId] = runtime.id to ws
        try {
            openDeferred.await()
        } catch (e: Throwable) {
            activeWebSockets.remove(wsId)
            runCatching { ws.cancel() }
            throw e
        }
        JsonNull
    }

    /** 把 WebSocket 事件交给插件 JS；插件处理函数出错记入插件错误。 */
    private suspend fun deliverWsEvent(runtime: PluginRuntime, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            host.recordError(runtime.id, e)
        }
    }

    private fun wsSend(runtime: PluginRuntime, req: JsonObject): JsonElement {
        val wsId = "${runtime.id}:${req["wsId"]!!.jsonPrimitive.content}"
        val (_, ws) = activeWebSockets[wsId] ?: error("WebSocket 未连接或已关闭: ${req["wsId"]}")
        val b64 = req["base64"]?.jsonPrimitive?.contentOrNull
        val ok = if (b64 != null) {
            ws.send(Base64.getDecoder().decode(b64).toByteString())
        } else {
            ws.send(req.getValue("text").jsonPrimitive.content)
        }
        return JsonPrimitive(ok)
    }

    private fun wsClose(runtime: PluginRuntime, req: JsonObject): JsonElement {
        val wsId = "${runtime.id}:${req["wsId"]!!.jsonPrimitive.content}"
        val removed = activeWebSockets.remove(wsId)
        removed?.second?.close(req.getValue("code").jsonPrimitive.int, req.getValue("reason").jsonPrimitive.content)
        return JsonNull
    }
}

/** 工具执行中 ctx.progress(parts) 的回调登记（按调用 token）。 */
object PluginToolProgress {
    private val map = ConcurrentHashMap<String, (JsonElement) -> Unit>()
    fun register(sink: (JsonElement) -> Unit): String = UUID.randomUUID().toString().also { map[it] = sink }
    fun get(token: String) = map[token]
    fun remove(token: String) { map.remove(token) }
}

/** 工具执行中 ctx.askUser(spec) 的挂起回调登记（按调用 token）。 */
object PluginToolAskUser {
    private val map = ConcurrentHashMap<String, suspend (JsonObject) -> String>()
    fun register(handler: suspend (JsonObject) -> String): String = UUID.randomUUID().toString().also { map[it] = handler }
    fun get(token: String) = map[token]
    fun remove(token: String) { map.remove(token) }
}

/** 插件包根目录下由宿主管理、插件不能经 `rikka.files` 改写或删除的文件。 */
private val PROTECTED_FILES = setOf("manifest.json", "storage.json")
