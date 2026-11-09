package me.rerere.plugin.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.error.PluginException
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.plugin.manifest.PluginManifest
import me.rerere.plugin.manifest.PluginUiSlot
import me.rerere.plugin.registry.PluginRegistration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 单个插件的 QuickJS 运行时。
 *
 * 模型：插件加载后，一个常驻 `evaluate` 作为事件循环，宿主把调用投递进 [inbox]，
 * JS 侧并发处理（异步 handler 可交错执行），处理完通过 `__reply` 回传结果。
 * 所有 JS 执行都在本插件专属的单线程调度器上进行。
 */
class PluginRuntime(
    val manifest: PluginManifest,
    private val source: String,
    private val services: PluginRuntimeServices,
    /** 调用超时；null 表示不限。 */
    var callTimeoutMillis: Long? = DEFAULT_CALL_TIMEOUT_MS,
    /** 插件包目录：入口文件以 ES module 执行，可 `import` 包内其他文件。 */
    val baseDir: java.io.File? = null,
    private val memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT_BYTES,
    /** 注册表变化（运行中 mount / unmount 等）时回调，供界面刷新。 */
    private val onRegistrationsChanged: () -> Unit = {},
) {
    val id: String get() = manifest.id

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-${manifest.id}") }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val seq = AtomicLong(0)
    private var js: QuickJs? = null
    private var loopJob: Job? = null

    /** 插件已注册的条目（按注册顺序）。 */
    val registrations: List<PluginRegistration> get() = _registrations
    private val _registrations = CopyOnWriteArrayList<PluginRegistration>()

    /** 事件循环异常终止的原因；非 null 时所有调用直接失败。 */
    @Volatile
    var fatalError: Throwable? = null
        private set

    suspend fun start() {
        check(js == null) { "plugin runtime already started" }
        val quickJs = if (baseDir != null) QuickJs.create(dispatcher, moduleLoaderFor(baseDir)) else QuickJs.create(dispatcher)
        js = quickJs
        quickJs.memoryLimit = memoryLimitBytes
        quickJs.maxStackSize = MAX_STACK_BYTES
        quickJs.function("__host_sync") { args ->
            val name = args[0] as String
            val payload = parseArgs(args.getOrNull(1))
            hostSync(name, payload).toString()
        }
        quickJs.asyncFunction("__host_async") { args ->
            val name = args[0] as String
            val payload = parseArgs(args.getOrNull(1))
            services.callAsync(this, name, payload).toString()
        }
        quickJs.asyncFunction("__next") { inbox.receiveCatching().getOrNull() }
        quickJs.function("__reply") { args ->
            val callId = (args[0] as Number).toLong()
            val ok = args[1] as Boolean
            val payload = args.getOrNull(2) as? String ?: "null"
            val deferred = pending.remove(callId)
            if (deferred != null) {
                if (ok) deferred.complete(payload) else deferred.completeExceptionally(JsError(payload))
            }
            null
        }
        try {
            quickJs.evaluate<Any?>(PLUGIN_BOOTSTRAP_JS, "rikka-bootstrap.js")
            quickJs.evaluate<Any?>(PLUGIN_BOOTSTRAP_HTTP_JS, "rikka-http.js")
            quickJs.evaluate<Any?>(PLUGIN_BOOTSTRAP_FINALIZE_JS, "rikka-finalize.js")
            // 有包目录时按 ES module 执行，支持 import 包内文件
            quickJs.evaluate<Any?>(source, manifest.main, baseDir != null)
        } catch (e: Throwable) {
            close()
            throw PluginException.wrap(id, "load", e)
        }
        loopJob = scope.launch {
            try {
                quickJs.evaluate<Any?>(EVENT_LOOP_JS, "rikka-loop.js")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fatalError = e
                val err = PluginException.wrap(id, "runtime", e)
                pending.values.forEach { it.completeExceptionally(err) }
                pending.clear()
            }
        }
    }

    /**
     * 调用插件注册的 handler。[args] 为 JS 侧 `fn.apply(null, args)` 的参数数组。
     * 返回 handler 结果的 JSON。任何失败均抛出 [PluginException]。
     */
    suspend fun invoke(
        hook: String,
        handlerId: String,
        args: List<JsonElement>,
        timeoutMillis: Long? = callTimeoutMillis,
    ): JsonElement {
        val payload = buildJsonObject {
            put("h", handlerId)
            put("a", kotlinx.serialization.json.JsonArray(args).toString())
        }
        return invokeRaw(hook, payload, timeoutMillis)
    }

    /** 调用多方法注册项（provider / search / tts ...）的某个方法。 */
    suspend fun invokeMethod(
        hook: String,
        handlerId: String,
        method: String,
        args: List<JsonElement>,
        timeoutMillis: Long? = callTimeoutMillis,
    ): JsonElement = invokeInternal(
        "$hook.$method", "__method",
        buildJsonObject {
            put("handler", handlerId)
            put("method", method)
            put("args", kotlinx.serialization.json.JsonArray(args))
        },
        timeoutMillis,
    )

    /** 调用内部协议入口（如流式 hook 的 open/chunk/end）。 */
    suspend fun invokeInternal(
        hook: String,
        entry: String,
        arg: JsonElement,
        timeoutMillis: Long? = callTimeoutMillis,
    ): JsonElement {
        val payload = buildJsonObject {
            put("h", entry)
            put("a", arg.toString())
        }
        return invokeRaw(hook, payload, timeoutMillis)
    }

    /**
     * [timeoutMillis] 为 null 表示不设插件超时：用于 intercept 与流式 body 拉取，
     * 这类调用包含等待上游网络的时间，由网络超时与用户取消控制。
     */
    private suspend fun invokeRaw(hook: String, payload: JsonObject, timeoutMillis: Long?): JsonElement {
        val effectiveTimeout = timeoutMillis?.takeIf { it > 0 }
        fatalError?.let { throw PluginException(id, hook, "插件运行时已终止: ${it.message}", it) }
        val runtime = js ?: throw PluginException(id, hook, "插件运行时未启动")
        if (runtime.isClosed) throw PluginException(id, hook, "插件运行时已关闭")
        val callId = seq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[callId] = deferred
        val message = JsonObject(payload + ("id" to JsonPrimitive(callId)))
        try {
            inbox.send(message.toString())
            val result = if (effectiveTimeout == null) deferred.await() else withTimeout(effectiveTimeout) { deferred.await() }
            return PluginJson.parseToJsonElement(result)
        } catch (e: TimeoutCancellationException) {
            throw PluginException(id, hook, "调用超时（${timeoutMillis}ms）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: JsError) {
            throw PluginException(id, hook, e.message ?: "JS error")
        } catch (e: Throwable) {
            throw PluginException.wrap(id, hook, e)
        } finally {
            pending.remove(callId)
        }
    }

    private fun parseArgs(raw: Any?): JsonElement =
        (raw as? String)?.let { PluginJson.parseToJsonElement(it) } ?: JsonNull

    private fun hostSync(name: String, args: JsonElement): JsonElement = when (name) {
        "register" -> {
            register(args.jsonObject)
            JsonNull
        }

        "unregister" -> {
            val handlerId = args.jsonObject["id"]!!.jsonPrimitive.content
            _registrations.removeAll { it.handlerId == handlerId }
            onRegistrationsChanged()
            JsonNull
        }

        else -> services.callSync(this, name, args)
    }

    private fun register(obj: JsonObject) {
        val kind = obj["kind"]!!.jsonPrimitive.content
        val handlerId = obj["id"]!!.jsonPrimitive.content
        val meta = obj["meta"] ?: JsonNull
        val hookKind = when (kind) {
            "hook" -> meta.jsonObject["hook"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("rikka.on 缺少 hook 名")

            else -> kind
        }
        require(PluginHookKind.isKnown(hookKind)) { "未知 hook: $hookKind" }
        if (hookKind in PluginHookKind.NAMED_UNIQUE) {
            val name = (meta as? JsonObject)?.get("name")?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("$hookKind 缺少 name")
            require(_registrations.none { it.kind == hookKind && (it.meta as? JsonObject)?.get("name")?.jsonPrimitive?.content == name }) {
                "$hookKind \"$name\" 重复注册"
            }
        }
        if (hookKind == PluginHookKind.UI) validateUi(meta.jsonObject)
        val label = (meta as? JsonObject)?.let { m ->
            m["name"]?.jsonPrimitive?.content ?: m["slot"]?.jsonPrimitive?.content
        }.orEmpty()
        val base = "$hookKind:$label"
        val ordinal = _registrations.count { it.key.substringBeforeLast('#') == base }
        _registrations += PluginRegistration(
            pluginId = id,
            kind = hookKind,
            handlerId = handlerId,
            meta = meta,
            key = "$base#$ordinal",
        )
        onRegistrationsChanged()
    }

    private fun validateUi(meta: JsonObject) {
        val slot = meta["slot"]!!.jsonPrimitive.content
        val types = PluginUiSlot.TYPES[slot]
            ?: throw IllegalArgumentException("未知 UI 挂载点: $slot（可用: ${PluginUiSlot.ALL.joinToString()}）")
        val spec = meta["spec"] as? JsonObject ?: throw IllegalArgumentException("ui.mount: spec 必须是对象")
        val type = spec["type"]?.jsonPrimitive?.content
        require(type in types) { "挂载点 $slot 不支持 spec.type=$type（支持: ${types.joinToString()}）" }
        when (type) {
            "form" -> {
                val fields = spec["fields"] as? kotlinx.serialization.json.JsonArray
                    ?: throw IllegalArgumentException("form 需要 fields 数组")
                fields.forEach { f ->
                    val o = f as? JsonObject ?: throw IllegalArgumentException("form 字段必须是对象")
                    val ft = o["type"]?.jsonPrimitive?.content
                    require(ft in PluginUiSlot.FORM_FIELD_TYPES) { "form 字段类型不支持: $ft" }
                    if (ft != "button") require(o["key"] != null) { "form 字段缺少 key" }
                }
            }
            "html" -> require(spec["path"] != null) { "html 需要 path" }
            "command" -> require(spec["name"] != null) { "command 需要 name" }
            "renderer" -> if (slot == "markdown.codeblock") {
                require(spec["languages"] is kotlinx.serialization.json.JsonArray) { "codeblock renderer 需要 languages 数组" }
            } else {
                require(spec["rendererType"] != null) { "message.part renderer 需要 rendererType" }
            }
        }
    }

    /** 宿主 API 实现调用：manifest 未声明权限则抛异常（最终报为 `[plugin:id] <hook>: 缺少权限 x`）。 */
    fun requirePermission(permission: String) {
        require(permission in manifest.permissions) { "缺少权限 $permission（需在 manifest.permissions 中声明）" }
    }

    fun close() {
        inbox.close()
        pending.values.forEach { it.completeExceptionally(PluginException(id, "runtime", "插件已停止")) }
        pending.clear()
        js?.let { runCatching { it.interruptEvaluation() } }
        scope.cancel()
        js?.let { runCatching { it.close() } }
        js = null
        dispatcher.close()
    }

    private class JsError(message: String) : RuntimeException(message)

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 30_000L
        const val DEFAULT_MEMORY_LIMIT_MB = 256
        const val DEFAULT_MEMORY_LIMIT_BYTES = DEFAULT_MEMORY_LIMIT_MB * 1024L * 1024
        private const val MAX_STACK_BYTES = 4L * 1024 * 1024

        /** 模块解析：相对路径相对于导入方，绝对路径相对于包根；不能解析到包目录之外。 */
        private fun moduleLoaderFor(baseDir: java.io.File) = com.dokar.quickjs.moduleLoader {
            val root = baseDir.canonicalFile
            normalize { base, name ->
                val baseFile = java.io.File(root, base).parentFile ?: root
                val target = if (name.startsWith("/")) java.io.File(root, name.removePrefix("/"))
                else java.io.File(baseFile, name)
                val canonical = target.canonicalFile
                require(canonical.path.startsWith(root.path + java.io.File.separator)) { "import 路径超出插件目录: $name" }
                canonical.relativeTo(root).invariantSeparatorsPath
            }
            load { name ->
                val file = java.io.File(root, name)
                require(file.isFile) { "模块不存在: $name" }
                com.dokar.quickjs.ModuleContent.Source(file.readText())
            }
        }

        private val EVENT_LOOP_JS = """
            for (;;) {
              const raw = await __next();
              if (raw === null || raw === undefined) break;
              const m = JSON.parse(raw);
              __rikka_invoke(m.h, m.a).then(
                (r) => __reply(m.id, true, r),
                (e) => __reply(m.id, false, (e && e.stack) ? (String(e) + "\n" + e.stack) : String(e))
              );
            }
        """.trimIndent()
    }
}

/** 运行时向宿主请求服务（storage / log / fetch / 宿主 API）。 */
interface PluginRuntimeServices {
    fun callSync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement
    suspend fun callAsync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement
}
