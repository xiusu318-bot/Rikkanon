package me.rerere.plugin.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.manifest.PluginPermission
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.plugin.runtime.PluginRuntimeServices
import me.rerere.plugin.store.PluginKvStorage
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** app 层提供的宿主 API（对话、设置等）。名称未知时必须抛异常。 */
interface PluginHostApi {
    suspend fun call(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement
    fun callSync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement =
        error("未知同步宿主 API: $name")
}

/**
 * 内置服务：log / storage / fetch，其余转交 [hostApi]（由 hostApi 按权限校验）。
 * [fetchClient] 为插件专用 OkHttp 客户端，不得挂载插件 intercept 拦截器。
 */
class DefaultPluginServices(
    private val host: PluginHost,
    private val fetchClient: OkHttpClient,
    private val hostApi: PluginHostApi = object : PluginHostApi {
        override suspend fun call(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement =
            error("未知宿主 API: $name")
    },
) : PluginRuntimeServices {
    private fun storageOf(id: String): PluginKvStorage {
        host.installedPlugin(id) ?: error("插件 $id 未安装")
        return host.storage(id)
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
                fetch(args.jsonObject)
            }

            "http.next" -> me.rerere.plugin.http.PluginInterceptor.handleNext(args)
            "http.body.read" -> me.rerere.plugin.http.PluginInterceptor.readLine(args)
            "http.body.text" -> me.rerere.plugin.http.PluginInterceptor.readText(args)

            "files.read", "files.write", "files.list", "files.delete", "files.exists" -> withContext(Dispatchers.IO) {
                pluginFile(runtime, name, args.jsonObject)
            }

            "tool.progress" -> {
                val o = args.jsonObject
                val token = o["token"]?.jsonPrimitive?.contentOrNull ?: error("ctx.progress 只能在工具 execute 中使用")
                val sink = PluginToolProgress.get(token) ?: error("工具调用已结束，不能再报告进度")
                sink(o["parts"] ?: JsonNull)
                JsonNull
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
        return when (name) {
            "files.read" -> JsonPrimitive(file.readText())
            "files.write" -> {
                file.parentFile?.mkdirs()
                file.writeText(o["text"]!!.jsonPrimitive.content)
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

    /**
     * 非流式 fetch：`{ url, method?, headers?, body? }` -> `{ status, headers, body }`。
     * body 为字符串；对象会被序列化为 JSON。
     */
    private suspend fun fetch(req: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val url = req["url"]?.jsonPrimitive?.contentOrNull ?: error("fetch: url is required")
        val method = req["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val headers = (req["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val bodyText = when (val b = req["body"]) {
            null, JsonNull -> null
            is JsonPrimitive -> b.content
            else -> b.toString()
        }
        val contentType = headers.entries.find { it.key.equals("content-type", true) }?.value
            ?: if (req["body"] is JsonObject || req["body"] is JsonArray) "application/json" else "text/plain"
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .method(method, bodyText?.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        fetchClient.newCall(request).execute().use { res ->
            buildJsonObject {
                put("status", res.code)
                put("headers", JsonObject(res.headers.toMultimap().mapValues { JsonPrimitive(it.value.joinToString(", ")) }))
                put("body", res.body.string())
            }
        }
    }
}

/** 工具执行中 ctx.progress(parts) 的回调登记（按调用 token）。 */
object PluginToolProgress {
    private val map = ConcurrentHashMap<String, (JsonElement) -> Unit>()
    fun register(sink: (JsonElement) -> Unit): String = java.util.UUID.randomUUID().toString().also { map[it] = sink }
    fun get(token: String) = map[token]
    fun remove(token: String) { map.remove(token) }
}
