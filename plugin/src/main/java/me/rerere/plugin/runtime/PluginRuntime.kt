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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    /**
     * 注册表变化（register / unregister / ui.update）时回调。
     * 参数 uiOnly 为 true 表示只涉及界面挂载项（只需刷新界面）；为 false 表示涉及工具、服务、定时任务等能力。
     */
    private val onRegistrationsChanged: (uiOnly: Boolean) -> Unit = {},
) : JavaBridgeHost {
    override val id: String get() = manifest.id

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-${manifest.id}") }

    /**
     * 当前线程是否正处于「JS 同步调用宿主」之中（即 JS 正被这次调用占住）。
     * QuickJS 的执行线程并不固定，所以不按线程身份判断，而是在 `__host_sync` 执行期间给当前线程打标记。
     */
    private val inSyncHostCall = ThreadLocal<Boolean>()
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val seq = AtomicLong(0)
    private var js: QuickJs? = null
    private var loopJob: Job? = null

    /** `rikka.java` / `rikka.hook` 的宿主实现：首次使用时才创建，插件不用就不存在。 */
    private val javaBridgeLazy = lazy { PluginJavaBridge(this) }
    val javaBridge: PluginJavaBridge by javaBridgeLazy

    /** 当前线程上的 JS 是否正被一次同步宿主调用占住（此时无法再同步回调 JS，否则会等待自身）。 */
    override fun isJsThread(): Boolean = inSyncHostCall.get() == true

    /** 写入插件日志（详情页日志栏可见）；失败忽略。 */
    override fun hostLog(level: String, text: String) {
        runCatching {
            services.callSync(this, "log", buildJsonObject {
                put("level", level)
                put("text", text)
            })
        }
    }

    /**
     * Java 侧（接口代理、方法 Hook）回调 JS handler。
     * - [wait] 为 true 且当前不在插件 JS 线程：阻塞当前线程等待 JS 返回（最多 [timeoutMillis]）；
     * - 否则异步投递，立即返回 null（在 JS 线程上同步等待自身会死锁）。
     */
    override fun invokeFromJava(
        handlerId: String,
        args: List<JsonElement>,
        wait: Boolean,
        timeoutMillis: Long?,
    ): JsonElement? {
        if (!wait || isJsThread()) {
            scope.launch {
                try {
                    invoke("java.callback", handlerId, args, timeoutMillis)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hostLog("error", "Java 回调 JS 失败: ${e.message}")
                }
            }
            return null
        }
        return kotlinx.coroutines.runBlocking { invoke("java.callback", handlerId, args, timeoutMillis) }
    }

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
            val prev = inSyncHostCall.get()
            inSyncHostCall.set(true)
            try {
                hostSync(name, payload).toString()
            } finally {
                inSyncHostCall.set(prev)
            }
        }
        quickJs.asyncFunction("__host_async") { args ->
            val name = args[0] as String
            val payload = parseArgs(args.getOrNull(1))
            if (name.startsWith("java.main.") || name.startsWith("java.async.")) {
                requireJavaPermission(name)
                javaBridge.callAsync(name, payload).toString()
            }
            else services.callAsync(this, name, payload).toString()
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
            quickJs.evaluate<Any?>(PLUGIN_BOOTSTRAP_JAVA_JS, "rikka-java.js")
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
            put("a", JsonArray(args).toString())
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
            put("args", JsonArray(args))
        },
        timeoutMillis,
    )

    /**
     * 真流式调用多方法注册项（如 `provider.streamText`、`tts.speak`、`provider.generateImage`）。
     * 若 JS 函数返回 `AsyncIterator`，则逐项按需拉取并实时 emit；若返回普通值/数组，则单次 emit。
     */
    fun invokeMethodStream(
        hook: String,
        handlerId: String,
        method: String,
        args: List<JsonElement>,
        timeoutMillis: Long? = null,
    ): Flow<JsonElement> = flow {
        val opened = invokeInternal(
            "$hook.$method", "__method_stream_open",
            buildJsonObject {
                put("handler", handlerId)
                put("method", method)
                put("args", JsonArray(args))
            },
            timeoutMillis,
        ).jsonObject
        val iterId = opened["iterId"]?.jsonPrimitive?.contentOrNull
        if (iterId == null) {
            emit(opened["value"] ?: JsonNull)
            return@flow
        }
        try {
            while (true) {
                val step = invokeInternal(
                    "$hook.$method", "__iter_next_json",
                    buildJsonObject { put("iterId", iterId) },
                    timeoutMillis,
                ).jsonObject
                if (step["done"]?.jsonPrimitive?.booleanOrNull == true) break
                emit(step["value"] ?: JsonNull)
            }
        } finally {
            runCatching {
                withContext(NonCancellable) {
                    invokeInternal(
                        "$hook.$method", "__iter_close",
                        buildJsonObject { put("iterId", iterId) },
                        timeoutMillis = 3_000L,
                    )
                }
            }
        }
    }

    /** 调用内部协议入口（如流式 hook 的 open/chunk/end）。 */
    suspend fun invokeInternal(
        hook: String,
        entry: String,
        arg: JsonElement,
        timeoutMillis: Long? = callTimeoutMillis,
        pause: PluginCallPause? = null,
    ): JsonElement {
        val payload = buildJsonObject {
            put("h", entry)
            put("a", arg.toString())
        }
        return invokeRaw(hook, payload, timeoutMillis, pause)
    }

    /**
     * [timeoutMillis] 为 null 表示不设插件超时：用于 intercept 与流式 body 拉取，
     * 这类调用包含等待上游网络的时间，由网络超时与用户取消控制。
     * [pause] 不为 null 时，其处于暂停状态的时间（如等待用户回答 `ctx.askUser`）不计入超时。
     */
    private suspend fun invokeRaw(
        hook: String,
        payload: JsonObject,
        timeoutMillis: Long?,
        pause: PluginCallPause? = null,
    ): JsonElement {
        val effectiveTimeout = timeoutMillis?.takeIf { it > 0 }
        fatalError?.let { throw PluginException(id, hook, "插件运行时已终止: ${it.message}", it) }
        val runtime = js ?: throw PluginException(id, hook, "插件运行时未启动")
        if (runtime.isClosed) throw PluginException(id, hook, "插件运行时已关闭")
        val callId = seq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[callId] = deferred
        val message = JsonObject(payload + ("id" to JsonPrimitive(callId)))
        val startMillis = PluginCallPause.nowMillis()
        try {
            inbox.send(message.toString())
            val result = when {
                effectiveTimeout == null -> deferred.await()
                pause == null -> withTimeout(effectiveTimeout) { deferred.await() }
                else -> awaitExcludingPause(deferred, effectiveTimeout, startMillis, pause)
                    ?: throw PluginException(id, hook, "调用超时（${timeoutMillis}ms）")
            }
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

    /**
     * 等待调用结果，超时只计算未暂停的时间。超时返回 null。
     * 暂停期间每隔 [PAUSE_RECHECK_MS] 复查一次，恢复后按剩余时间继续等待。
     */
    private suspend fun awaitExcludingPause(
        deferred: CompletableDeferred<String>,
        timeoutMillis: Long,
        startMillis: Long,
        pause: PluginCallPause,
    ): String? {
        while (true) {
            val wait = if (pause.isPaused) {
                PAUSE_RECHECK_MS
            } else {
                val active = PluginCallPause.nowMillis() - startMillis - pause.pausedMillis()
                val remaining = timeoutMillis - active
                if (remaining <= 0) return null
                remaining
            }
            withTimeoutOrNull(wait) { deferred.await() }?.let { return it }
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
            val removed = _registrations.filter { it.handlerId == handlerId }
            _registrations.removeAll { it.handlerId == handlerId }
            onRegistrationsChanged(removed.all { it.kind == PluginHookKind.UI })
            JsonNull
        }

        "ui.update" -> {
            updateUiSpec(args.jsonObject)
            JsonNull
        }

        else -> if (name.startsWith("java.")) {
            requireJavaPermission(name)
            javaBridge.callSync(name, args)
        } else services.callSync(this, name, args)
    }

    private fun updateUiSpec(obj: JsonObject) {
        val handlerId = obj.getValue("id").jsonPrimitive.content
        val patch = obj.getValue("spec").jsonObject
        val idx = _registrations.indexOfFirst { it.handlerId == handlerId && it.kind == PluginHookKind.UI }
        require(idx >= 0) { "ui.update: 没有 id 为 $handlerId 的界面挂载项" }
        val current = _registrations[idx]
        val metaObj = current.meta.jsonObject
        val oldSpec = metaObj.getValue("spec").jsonObject
        val mergedSpec = JsonObject(oldSpec + patch)
        val newMeta = JsonObject(metaObj + ("spec" to mergedSpec))
        validateUi(newMeta)
        _registrations[idx] = current.copy(meta = newMeta)
        onRegistrationsChanged(true)
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
        onRegistrationsChanged(hookKind == PluginHookKind.UI)
    }

    private fun validateUi(meta: JsonObject) {
        val slot = meta["slot"]!!.jsonPrimitive.content
        val types = PluginUiSlot.TYPES[slot]
            ?: throw IllegalArgumentException("未知 UI 挂载点: $slot（可用: ${PluginUiSlot.ALL.joinToString()}）")
        val spec = meta["spec"] as? JsonObject ?: throw IllegalArgumentException("ui.mount: spec 必须是对象")
        val type = spec["type"].optString("spec.type")
        require(type in types) { "挂载点 $slot 不支持 spec.type=$type（支持: ${types.joinToString()}）" }

        // 界面绘制时直接读取的所有可选字段，在注册/更新入口统一校验类型，杜绝绘制期崩溃
        UI_OPTIONAL_STRING_KEYS.forEach { k -> spec[k]?.let { require(it.isString()) { "$k 必须是字符串" } } }
        UI_OPTIONAL_NUMBER_KEYS.forEach { k -> spec[k]?.let { require(it.isNumber()) { "$k 必须是数字" } } }
        UI_OPTIONAL_BOOL_KEYS.forEach { k -> spec[k]?.let { require(it.isBool()) { "$k 必须是布尔值" } } }
        spec["roles"]?.let { require(it.isStringArray()) { "roles 必须是字符串数组" } }

        when (type) {
            "form" -> {
                val fields = spec["fields"] as? JsonArray
                    ?: throw IllegalArgumentException("form 需要 fields 数组")
                fields.forEach { f ->
                    val o = f as? JsonObject ?: throw IllegalArgumentException("form 字段必须是对象")
                    val ft = o["type"].optString("form.field.type")
                    require(ft in PluginUiSlot.FORM_FIELD_TYPES) { "form 字段类型不支持: $ft" }
                    if (ft != "button") require(o["key"].isString()) { "form 字段需要字符串类型的 key" }
                    FORM_FIELD_STRING_KEYS.forEach { k -> o[k]?.let { require(it.isString()) { "form 字段 $k 必须是字符串" } } }
                    FORM_FIELD_NUMBER_KEYS.forEach { k -> o[k]?.let { require(it.isNumber()) { "form 字段 $k 必须是数字" } } }
                    o["options"]?.let { opts ->
                        require(opts is JsonArray) { "form 字段 options 必须是数组" }
                        opts.forEach { el ->
                            when (el) {
                                is JsonPrimitive -> Unit
                                is JsonObject -> {
                                    require(el["value"].isString()) { "form.options 对象项需要字符串 value" }
                                    el["label"]?.let { require(it.isString()) { "form.options.label 必须是字符串" } }
                                }
                                else -> throw IllegalArgumentException("form.options 元素必须是字符串或 { value, label } 对象")
                            }
                        }
                    }
                    o["visibleWhen"]?.let { vw ->
                        val vwo = vw as? JsonObject ?: throw IllegalArgumentException("form 字段 visibleWhen 必须是对象")
                        vwo["key"]?.let { require(it.isString()) { "visibleWhen.key 必须是字符串" } }
                        vwo["equals"]?.let { eq ->
                            require(eq is JsonPrimitive || (eq is JsonArray && eq.all { it is JsonPrimitive })) {
                                "visibleWhen.equals 必须是基本类型或基本类型数组"
                            }
                        }
                    }
                }
            }
            "html" -> require(spec["path"].isString() || spec["html"].isString()) { "html 需要字符串类型的 path 或 html" }
            "command" -> require(spec["name"].isString()) { "command 需要字符串类型的 name" }
            "renderer" -> when (slot) {
                "markdown.codeblock" -> require(spec["languages"].isStringArray()) { "codeblock renderer 需要 languages 字符串数组" }
                "tool.card" -> require(spec["toolNames"].isStringArray()) { "tool.card renderer 需要 toolNames 字符串数组" }
                "message.part" -> require(spec["rendererType"].isString()) { "message.part renderer 需要 rendererType 字符串" }
            }
        }
        spec["menu"]?.let { menu ->
            require(menu is JsonArray) { "menu 必须是数组" }
            menu.forEach { item ->
                val obj = item as? JsonObject ?: throw IllegalArgumentException("menu 项必须是对象")
                require(obj["id"].isString() && (obj["title"].isString() || obj["text"].isString())) {
                    "menu 的每一项需要字符串类型的 id 与 title"
                }
                MENU_ITEM_STRING_KEYS.forEach { k -> obj[k]?.let { require(it.isString()) { "menu.$k 必须是字符串" } } }
            }
        }
    }

    private fun JsonElement?.optString(what: String): String? = when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (isString) content else throw IllegalArgumentException("$what 必须是字符串")
        else -> throw IllegalArgumentException("$what 必须是字符串")
    }

    private fun JsonElement?.isString(): Boolean = this is JsonPrimitive && this.isString

    private fun JsonElement?.isNumber(): Boolean = this is JsonPrimitive && !this.isString && this.content.toDoubleOrNull() != null

    private fun JsonElement?.isBool(): Boolean = this is JsonPrimitive && !this.isString && this.booleanOrNull != null

    private fun JsonElement?.isStringArray(): Boolean = this is JsonArray && this.all { it.isString() }

    /**
     * `rikka.hook`（`java.hook` / `java.unhook` / `java.callOriginal` / `java.deoptimize`）需要 `hook` 权限，
     * 其余 `rikka.java` 调用需要 `native` 权限。在创建反射桥、加载 Hook 引擎之前检查。
     */
    private fun requireJavaPermission(name: String) {
        val op = name.removePrefix("java.main.").removePrefix("java.async.").removePrefix("java.")
        if (op in HOOK_OPS) requirePermission(me.rerere.plugin.manifest.PluginPermission.HOOK)
        else requirePermission(me.rerere.plugin.manifest.PluginPermission.NATIVE)
    }

    /** 宿主 API 实现调用：manifest 未声明权限则抛异常（最终报为 `[plugin:id] <hook>: 缺少权限 x`）。 */
    fun requirePermission(permission: String) {
        require(permission in manifest.permissions) { "缺少权限 $permission（需在 manifest.permissions 中声明）" }
    }

    /** 停止前执行插件注册的 onUnload 钩子；全部执行完后，若有出错则抛出第一个错误。 */
    suspend fun runUnloadHooks() {
        val errors = _registrations.filter { it.kind == PluginHookKind.UNLOAD }.mapNotNull { reg ->
            runCatching {
                invoke(PluginHookKind.UNLOAD, reg.handlerId, emptyList(), timeoutMillis = 3_000L)
            }.exceptionOrNull()
        }
        errors.firstOrNull()?.let { first ->
            errors.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    fun close() {
        // 先解除本插件的全部 Hook / 释放句柄，再关闭运行时
        if (javaBridgeLazy.isInitialized()) runCatching { javaBridge.dispose() }
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

        /** 调用处于暂停计时状态（等待用户操作）时，复查是否已恢复的间隔。 */
        private const val PAUSE_RECHECK_MS = 1_000L

        /** Java 代理 / Hook 同步回调 JS 的默认超时（超时后按未修改处理，原方法照常执行）。 */
        const val JAVA_CALLBACK_TIMEOUT_MS = 10_000L

        private val HOOK_OPS = setOf("hook", "unhook", "callOriginal", "deoptimize")
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

        private val UI_OPTIONAL_STRING_KEYS = setOf(
            "title", "text", "description", "icon", "badge", "trailingText", "path", "html",
            "status", "presentation", "switchKey", "name", "insertText", "summary", "role", "rendererType",
        )
        private val UI_OPTIONAL_NUMBER_KEYS = setOf("width", "height")
        private val UI_OPTIONAL_BOOL_KEYS = setOf("visible", "dismissOnClick", "hasCode", "hasImage", "hasTool")
        private val FORM_FIELD_STRING_KEYS = setOf("label", "title", "description", "placeholder", "action", "path", "presentation")
        private val FORM_FIELD_NUMBER_KEYS = setOf("min", "max", "step")
        private val MENU_ITEM_STRING_KEYS = setOf("title", "text", "icon", "path", "description", "presentation")
    }
}

/** 运行时向宿主请求服务（storage / log / fetch / 宿主 API）。 */
interface PluginRuntimeServices {
    fun callSync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement
    suspend fun callAsync(runtime: PluginRuntime, name: String, args: JsonElement): JsonElement
    fun onPluginStopped(pluginId: String) {}
}
