package me.rerere.rikkahub.ui.custom

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.plugin.runtime.JavaBridgeHost
import me.rerere.plugin.runtime.PluginJavaBridge
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.plugin.PluginCurrentActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "UIThemeRuntime"

/**
 * 按预设记录运行时错误（包括静态 `ui.slot` / `ui.page` 的未知部件、未知动作、缺失字段、着色器编译失败，以及动态渲染与回调报错）。
 * 即使预设没有动态函数（未创建 [UIThemeRuntime]），错误也能在编辑页实时展示。
 */
object UIRuntimeErrors {
    private val map = ConcurrentHashMap<Uuid, MutableStateFlow<List<String>>>()
    private val lastHash = ConcurrentHashMap<Uuid, String?>()

    fun flowOf(presetId: Uuid): StateFlow<List<String>> =
        map.getOrPut(presetId) { MutableStateFlow(emptyList()) }.asStateFlow()

    fun record(preset: UIPreset?, message: String) {
        println("$TAG: $message")
        if (preset == null) return
        val prevHash = lastHash.put(preset.id, preset.compiledCodeHash)
        val flow = map.getOrPut(preset.id) { MutableStateFlow(emptyList()) }
        if (prevHash != null && prevHash != preset.compiledCodeHash) {
            flow.value = listOf(message)
        } else {
            flow.update { cur -> if (cur.lastOrNull() == message) cur else (cur + message).takeLast(50) }
        }
    }

    fun clear(presetId: Uuid) {
        map[presetId]?.value = emptyList()
    }
}

/** 主题回调里通过 `ctx.action(名字, ...参数)` 请求的宿主动作。 */
data class UIThemeAction(val name: String, val args: List<JsonElement>)

/**
 * 主题的运行时 JS 实例（与插件一样常驻），负责按数据渲染 `ui.slot(名, (data, ctx) => 节点树)` 与执行界面回调。
 *
 * - 同一份代码只加载一次；渲染结果按（位置名、数据、状态版本）缓存，数据没变就不重算。
 * - 回调、定时器或 `java.proxy` 接口回调执行后状态版本递增（或显式 `ui.invalidate()`），驱动按数据渲染的位置重算。
 * - 出错只影响出错的那一处（回退原版），错误记在 [errors] 与 [UIRuntimeErrors]。
 */
class UIThemeRuntime(
    val code: String,
    private val presetId: Uuid? = null,
    private val codeHash: String? = null,
) : AutoCloseable, JavaBridgeHost {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ui-theme-js").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var js: QuickJs? = null

    /** 主线程当前是否正在 `renderNow` 中同步等待本实例完成渲染（此时不可双向阻塞互等）。 */
    @Volatile
    private var mainThreadWaiting = false

    /** JS 线程当前是否正通过 `$main` / `$async` 同步阻塞等待外部线程完成（此时若外部线程又同步回调 `java.proxy`，需异步排队防互锁）。 */
    @Volatile
    private var jsWaitingExternal = false

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** 状态版本：回调执行后递增，按数据渲染的位置随之重算。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** 后台渲染完成的次数：没拿到结果（未等待或等待超时）的位置据此重新取结果。 */
    private val _renderTick = MutableStateFlow(0)
    val renderTick: StateFlow<Int> = _renderTick

    private val _errors = MutableStateFlow<List<String>>(emptyList())
    /** 运行时错误（最近 50 条），编辑页展示。 */
    val errors: StateFlow<List<String>> = _errors

    private val timers = ConcurrentHashMap<Int, Job>()
    private val timerSeq = AtomicInteger(1)

    private val cache = object : LinkedHashMap<String, UISlotResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UISlotResult>): Boolean {
            val evict = size > CACHE_SIZE
            if (evict) evicted(eldest.value)
            return evict
        }
    }

    /** 懒加载列表各行的渲染结果（与各位置的结果分开存放，滚动长列表不会把页面自身的结果挤出去）。 */
    private val itemCache = object : LinkedHashMap<String, UISlotResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UISlotResult>): Boolean {
            val evict = size > ITEM_CACHE_SIZE
            if (evict) evicted(eldest.value)
            return evict
        }
    }

    /** 结果被挤出缓存：界面上还在显示它（[pin]）就留着；若尚未完成首帧挂载，给 3 秒宽限期再复查，防止高并发插槽把刚算完尚未 pin 的回调误删。 */
    private fun evicted(result: UISlotResult) {
        val scheduleGrace = synchronized(result) {
            result.evicted = true
            result.pins <= 0 && !result.released
        }
        if (scheduleGrace) releaseAfterGrace(result)
    }

    /** 界面开始显示某次渲染结果：显示期间它的回调与 Java 句柄保持有效。 */
    fun pin(result: UISlotResult) {
        synchronized(result) { result.pins++ }
    }

    fun unpin(result: UISlotResult) {
        val releaseNow = synchronized(result) {
            result.pins--
            if (result.pins <= 0 && result.evicted && !result.released) {
                result.released = true
                true
            } else false
        }
        if (releaseNow) releaseLater(result)
    }

    private fun releaseAfterGrace(result: UISlotResult) {
        scope.launch {
            kotlinx.coroutines.delay(3_000L)
            val shouldRelease = synchronized(result) {
                if (result.pins <= 0 && result.evicted && !result.released) {
                    result.released = true
                    true
                } else false
            }
            if (shouldRelease) releaseLater(result)
        }
    }
    private val inFlight = HashMap<String, Deferred<UISlotResult?>>()

    // ---- 数据桥（java / host）：主题第一次用到时才创建；没有用到时不做任何反射 ----
    override val id: String = "ui-theme@" + Integer.toHexString(System.identityHashCode(this))
    private val bridgeLazy = lazy { PluginJavaBridge(this) }
    private val bridge: PluginJavaBridge by bridgeLazy
    @Volatile
    private var jsThread: Thread? = null

    /** 句柄编号 → Java 对象（`Compose` 参数里的 `{"$j": 编号}`）。 */
    internal fun javaObject(id: Long): Any = bridge.get(id)

    /** 登记持久化视图句柄（与组件存活期一致，离开界面时用 [releaseHandleId] 回收）。 */
    internal fun retainHandle(obj: Any): Long = bridge.put(obj)

    internal fun releaseHandleId(id: Long) {
        if (bridgeLazy.isInitialized()) bridge.release(id)
    }

    internal fun findClass(name: String): Class<*> = bridge.findClass(name)

    /** 按目标类型转换参数（数组、对象等基本类型以外的值）。 */
    internal fun decodeAs(e: JsonElement, type: Class<*>): Any? = bridge.decodeAs(e, type)

    override fun hostLog(level: String, text: String) {
        if (level == "error" || level == "warn") recordError(text) else println("$TAG: $text")
    }

    override fun isJsThread(): Boolean = Thread.currentThread() === jsThread

    /**
     * Java 接口代理（`java.proxy`）回调 JS。
     * - 若在 JS 线程自身、主线程正处于同步首帧等待中或显式 `async: true`，异步投递执行；
     * - 否则同步等待 JS 返回结果（超时上限 [timeoutMillis] 默认 2 秒）。
     */
    override fun invokeFromJava(
        handlerId: String,
        args: List<JsonElement>,
        wait: Boolean,
        timeoutMillis: Long?,
    ): JsonElement? {
        val methodName = (args.getOrNull(0) as? JsonPrimitive)?.content ?: ""
        val encodedArgs = (args.getOrNull(1) as? JsonArray) ?: JsonArray(emptyList())
        val onMainLooper = android.os.Looper.getMainLooper()?.let { android.os.Looper.myLooper() == it } == true
        if (!wait || isJsThread() || jsWaitingExternal || (onMainLooper && mainThreadWaiting)) {
            scope.launch {
                invokeJavaHandlerLocked(handlerId, methodName, encodedArgs)
            }
            return null
        }
        return runBlocking {
            withTimeoutOrNull(timeoutMillis ?: 2_000L) {
                scope.async {
                    invokeJavaHandlerLocked(handlerId, methodName, encodedArgs)
                }.await()
            }
        }
    }

    private suspend fun invokeJavaHandlerLocked(
        handlerId: String,
        methodName: String,
        encodedArgs: JsonArray,
    ): JsonElement? {
        var shouldInvalidate = false
        val res = mutex.withLock {
            val q = js ?: return@withLock null
            try {
                val out = q.evaluate<String>(
                    "__ui_java_cb(${JsonPrimitive(handlerId)}, ${JsonPrimitive(methodName)}, ${JsonPrimitive(encodedArgs.toString())})",
                    "ui-java-cb.js",
                )
                val obj = Json.parseToJsonElement(out).jsonObject
                shouldInvalidate = (obj["invalidate"] as? JsonPrimitive)?.booleanOrNull == true
                obj["result"]
            } catch (e: Throwable) {
                recordError("java.proxy 回调 $methodName 失败：${e.message}")
                null
            }
        }
        if (shouldInvalidate) _version.value++
        return res
    }

    /** JS 的 `__ui_java(名, 参数 JSON)`：反射桥、宿主对象、watch 取值与定时器调度。在主题 JS 线程上执行。 */
    private fun javaCall(name: String, payload: JsonElement): JsonElement = when {
        name == "host.context" -> bridge.encode(appContext())
        name == "host.activity" -> bridge.encode(PluginCurrentActivity.get(appContext() as android.app.Application))
        name == "host.koin" -> bridge.encode(org.koin.core.context.GlobalContext.get())
        name == "host.get" -> {
            val o = payload.jsonObject
            val cls = bridge.findClass(o.getValue("cls").jsonPrimitive.content)
            val qualifier = (o["qualifier"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { org.koin.core.qualifier.named(it) }
            bridge.encode(org.koin.core.context.GlobalContext.get().get<Any>(cls.kotlin, qualifier))
        }
        name == "java.keep" -> {
            bridge.keep(payload.jsonObject.getValue("h").jsonPrimitive.content.toLong())
            JsonNull
        }
        name == "ui.watchValue" -> {
            val obj = bridge.get(payload.jsonObject.getValue("h").jsonPrimitive.content.toLong())
            bridge.encode(watchValueOf(obj))
        }
        name == "ui.invalidate" -> {
            _version.value++
            JsonNull
        }
        name == "ui.timer.set" -> {
            val o = payload.jsonObject
            val delayMs = (o["ms"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(1L)
            val repeat = o["repeat"]?.jsonPrimitive?.booleanOrNull == true
            val tid = timerSeq.getAndIncrement()
            timers[tid] = scope.launch {
                try {
                    if (repeat) {
                        while (true) {
                            delay(delayMs)
                            if (!timers.containsKey(tid)) break
                            runTimerTick(tid, repeat = true)
                        }
                    } else {
                        delay(delayMs)
                        if (timers.remove(tid) != null) {
                            runTimerTick(tid, repeat = false)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                }
            }
            JsonPrimitive(tid)
        }
        name == "ui.timer.clear" -> {
            val tid = payload.jsonObject["id"]?.jsonPrimitive?.intOrNull
            if (tid != null) timers.remove(tid)?.cancel()
            JsonNull
        }
        name in UNSUPPORTED_JAVA_OPS -> throw UnsupportedOperationException("主题里不支持 $name（ART 方法 Hook 请写插件）")
        name.startsWith("java.main.") -> {
            if (mainThreadWaiting) {
                throw IllegalStateException("不能在渲染函数同步首帧等待期间调用 \$main(...)（主线程正在等待本次渲染，请在点击回调里调用或直接读取状态）")
            }
            val prev = jsWaitingExternal
            jsWaitingExternal = true
            try {
                runBlocking { bridge.callAsync(name, payload) }
            } finally {
                jsWaitingExternal = prev
            }
        }
        name.startsWith("java.async.") -> {
            val prev = jsWaitingExternal
            jsWaitingExternal = true
            try {
                runBlocking { bridge.callAsync(name, payload) }
            } finally {
                jsWaitingExternal = prev
            }
        }
        name.startsWith("java.") -> bridge.callSync(name, payload)
        else -> throw IllegalArgumentException("未知接口 $name")
    }

    private suspend fun runTimerTick(tid: Int, repeat: Boolean) {
        val invalidate = mutex.withLock {
            val q = js ?: return@withLock false
            try {
                q.evaluate<Boolean>("__ui_timer_fire($tid, $repeat)", "ui-timer.js")
            } catch (e: Throwable) {
                recordError("定时器回调失败：${e.message}")
                false
            }
        }
        if (invalidate) _version.value++
    }

    private fun appContext(): android.content.Context = org.koin.core.context.GlobalContext.get().get<android.content.Context>()

    init {
        scope.launch {
            try {
                mutex.withLock {
                    val q = QuickJs.create(dispatcher)
                    q.memoryLimit = MEMORY_LIMIT_BYTES
                    q.maxStackSize = MAX_STACK_BYTES
                    jsThread = Thread.currentThread()
                    q.function("__ui_java") { args ->
                        javaCall(args[0] as String, Json.parseToJsonElement(args.getOrNull(1) as? String ?: "null")).toString()
                    }
                    q.evaluate<Any?>(CustomUICodeEngine.BOOTSTRAP_UI_DSL_JS, "ui-bootstrap.js")
                    q.evaluate<Any?>(RUNTIME_JS, "ui-runtime.js")
                    q.evaluate<Any?>(THEME_JAVA_JS, "ui-java.js")
                    q.evaluate<Any?>(code, "ui-code.js")
                    js = q
                }
                _ready.value = true
            } catch (e: Throwable) {
                recordError("加载主题代码失败：${e.message}")
            }
        }
    }

    private fun key(name: String, dataJson: String, version: Int) = "$version\u0000$name\u0000$dataJson"

    fun renderNow(name: String, dataJson: String, version: Int, wait: Boolean, watchKey: String = ""): UISlotResult? {
        if (!_ready.value) return null
        val k = key(name, dataJson, version) + "\u0000" + watchKey
        synchronized(cache) { cache[k]?.let { return it } }
        val job = startRender(cache, k, "ui.slot(\"$name\")") { "__ui_render(${JsonPrimitive(name)}, ${JsonPrimitive(dataJson)})" }
        return awaitWithinBudget(job, wait)
    }

    /**
     * 懒加载列表的一行：调用主题的 `item(index, ctx)`（[itemFn] 是它在本次页面渲染里的回调编号）。
     * 与 [renderNow] 共用等待预算：单次最多等 48ms，任意 250ms 内合计最多等 48ms，超出的行先占位、后台算完再显示。
     */
    fun renderItemNow(itemFn: String, index: Int, wait: Boolean, watchKey: String = ""): UISlotResult? {
        if (!_ready.value) return null
        val k = itemFn + "\u0000" + index + "\u0000" + watchKey
        synchronized(itemCache) { itemCache[k]?.let { return it } }
        val job = startRender(itemCache, k, "列表第 $index 行") { "__ui_render_item(${JsonPrimitive(itemFn)}, $index)" }
        return awaitWithinBudget(job, wait)
    }

    private fun awaitWithinBudget(job: Deferred<UISlotResult?>, wait: Boolean): UISlotResult? {
        if (!wait) return null
        val budget = takeSyncBudget()
        if (budget <= 0L) return null
        val mainLooper = android.os.Looper.getMainLooper()
        val isOnMain = mainLooper != null && android.os.Looper.myLooper() == mainLooper
        val start = System.nanoTime()
        if (isOnMain) mainThreadWaiting = true
        val result = try {
            runBlocking { withTimeoutOrNull(budget) { job.await() } }
        } finally {
            if (isOnMain) mainThreadWaiting = false
        }
        spendSyncBudget((System.nanoTime() - start) / 1_000_000L)
        return result
    }

    private fun startRender(
        store: LinkedHashMap<String, UISlotResult>,
        k: String,
        what: String,
        expr: () -> String,
    ): Deferred<UISlotResult?> = synchronized(inFlight) {
        val flightKey = (if (store === itemCache) "i:" else "s:") + k
        inFlight.getOrPut(flightKey) {
            scope.async {
                val r = renderInternal(what, expr())
                synchronized(store) {
                    if (r != null) store.put(k, r)?.let { old -> if (old !== r) evicted(old) }
                }
                synchronized(inFlight) { inFlight.remove(flightKey) }
                _renderTick.value++
                r
            }
        }
    }

    private var syncWindowStart = 0L
    private var syncWindowSpent = 0L

    @Synchronized
    private fun takeSyncBudget(): Long {
        val now = System.nanoTime() / 1_000_000L
        if (now - syncWindowStart >= SYNC_WINDOW_MS) {
            syncWindowStart = now
            syncWindowSpent = 0L
        }
        return minOf(SYNC_WAIT_MS, SYNC_WINDOW_BUDGET_MS - syncWindowSpent)
    }

    @Synchronized
    private fun spendSyncBudget(ms: Long) {
        syncWindowSpent += ms
    }

    private suspend fun renderInternal(what: String, expr: String): UISlotResult? = mutex.withLock {
        val q = js ?: return@withLock null
        var created: List<Long> = emptyList()
        try {
            val tracking = bridgeLazy.isInitialized()
            if (tracking) bridge.beginScope()
            val out = try {
                q.evaluate<String>(expr, "ui-render.js")
            } finally {
                if (tracking) created = bridge.endScope()
            }
            val obj = Json.parseToJsonElement(out).jsonObject
            val ids = obj["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val watched = obj["watch"]?.jsonArray?.takeIf { it.isNotEmpty() }?.map { bridge.get(it.jsonPrimitive.content.toLong()) }.orEmpty()
            val treeEl = obj["tree"]
            val tree = if (treeEl == null || treeEl is JsonNull) null else CustomUICodeEngine.parseNodeJson(treeEl.jsonObject)
            UISlotResult(tree, ids, created, watched.distinctBy { System.identityHashCode(it) })
        } catch (e: Throwable) {
            if (created.isNotEmpty()) bridge.releaseScope(created)
            recordError("$what 渲染失败：${e.message}")
            UISlotResult(null, emptyList())
        }
    }

    /** 执行界面回调（可选传入表单值 [arg]），返回回调里请求的宿主动作。执行后状态版本 +1。 */
    suspend fun call(callbackId: String, arg: JsonElement? = null): List<UIThemeAction> {
        val result = scope.async {
            mutex.withLock {
                val q = js ?: return@withLock emptyList()
                try {
                    val callExpr = if (arg == null) {
                        "__ui_call(${JsonPrimitive(callbackId)}, false, \"null\")"
                    } else {
                        "__ui_call(${JsonPrimitive(callbackId)}, true, ${JsonPrimitive(arg.toString())})"
                    }
                    val out = q.evaluate<String>(callExpr, "ui-call.js")
                    Json.parseToJsonElement(out).jsonArray.map { el ->
                        val o = el.jsonObject
                        UIThemeAction(o["name"]!!.jsonPrimitive.content, (o["args"] as? JsonArray).orEmpty())
                    }
                } catch (e: Throwable) {
                    recordError("界面回调执行失败：${e.message}")
                    emptyList()
                }
            }
        }.await()
        _version.value++
        return result
    }

    /**
     * 执行带多个参数的界面回调（`onReorder(from, to, ctx)`、`onSwipe(方向, ctx)`、`onCreate(view, ctx)`、
     * 传给宿主界面函数的回调）。参数里的 Java 对象以临时句柄交给主题（回调结束后自动回收，除非主题显式 `java.keep`）；
     * 内联值类 `Color` / `Dp` 自动拆箱为 `#hex` 颜色串或 dp 数值。执行后状态版本 +1。
     */
    suspend fun callArgs(callbackId: String, args: List<Any?>): List<UIThemeAction> {
        val tempHandles = ArrayList<Long>()
        try {
            val encoded = JsonArray(args.map { a ->
                when (a) {
                    null, Unit -> JsonNull
                    is JsonElement -> a
                    is androidx.compose.ui.graphics.Color -> {
                        val argb = androidx.compose.ui.graphics.Color(a.value).let { c ->
                            val al = (c.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val r = (c.red * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val g = (c.green * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val b = (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
                            if (al == 255) String.format("#%02X%02X%02X", r, g, b)
                            else String.format("#%02X%02X%02X%02X", al, r, g, b)
                        }
                        JsonPrimitive(argb)
                    }
                    is androidx.compose.ui.unit.Dp -> JsonPrimitive(a.value)
                    is String -> JsonPrimitive(a)
                    is Boolean -> JsonPrimitive(a)
                    is Int, is Short, is Byte -> JsonPrimitive((a as Number).toInt())
                    is Long -> if (a in -9007199254740991L..9007199254740991L) JsonPrimitive(a) else JsonObject(mapOf("\$long" to JsonPrimitive(a.toString())))
                    is Float -> JsonPrimitive(a.toDouble())
                    is Double -> JsonPrimitive(a)
                    is Char -> JsonPrimitive(a.toString())
                    else -> bridge.encode(a, tempHandles)
                }
            })
            val result = scope.async {
                mutex.withLock {
                    val q = js ?: return@withLock emptyList()
                    try {
                        val out = q.evaluate<String>("__ui_call_args(${JsonPrimitive(callbackId)}, ${JsonPrimitive(encoded.toString())})", "ui-call.js")
                        Json.parseToJsonElement(out).jsonArray.map { el ->
                            val o = el.jsonObject
                            UIThemeAction(o["name"]!!.jsonPrimitive.content, (o["args"] as? JsonArray).orEmpty())
                        }
                    } catch (e: Throwable) {
                        recordError("界面回调执行失败：${e.message}")
                        emptyList()
                    }
                }
            }.await()
            _version.value++
            return result
        } finally {
            if (tempHandles.isNotEmpty()) bridge.releaseScope(tempHandles)
        }
    }

    /**
     * 在主题实例自身协程上串行执行回调（不随界面离开而被取消；`NativeView` 的 `onCreate` / `onRelease` 共用同一队列保证严格保序）。
     * [onActions] 处理回调里请求的宿主动作（界面已离开时传 null 忽略）；[after] 在回调结束后必定执行。
     */
    internal fun callArgsDetached(
        callbackId: String,
        args: List<Any?>,
        onActions: ((List<UIThemeAction>) -> Unit)? = null,
        after: () -> Unit = {},
    ) {
        scope.launch {
            try {
                if (callbackId.isNotEmpty()) {
                    val actions = callArgs(callbackId, args)
                    onActions?.invoke(actions)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                recordError("界面回调执行失败：${e.message}")
            } finally {
                after()
            }
        }
    }

    private fun releaseLater(result: UISlotResult) {
        if (result.javaHandles.isNotEmpty()) bridge.releaseScope(result.javaHandles)
        val ids = result.callbackIds
        if (ids.isEmpty()) return
        scope.launch {
            mutex.withLock {
                runCatching { js?.evaluate<Any?>("__ui_release(${JsonPrimitive(JsonArray(ids.map(::JsonPrimitive)).toString())})", "ui-release.js") }
            }
        }
    }

    fun recordError(message: String) {
        _errors.update { cur -> if (cur.lastOrNull() == message) cur else (cur + message).takeLast(50) }
        if (presetId != null) {
            UIRuntimeErrors.record(UIPreset(id = presetId, compiledCodeHash = codeHash), message)
        } else {
            println("$TAG: $message")
        }
    }

    override fun close() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        if (bridgeLazy.isInitialized()) runCatching { bridge.dispose() }
        scope.launch { mutex.withLock { js?.close(); js = null } }.invokeOnCompletion {
            scope.cancel()
            executor.shutdown()
        }
    }

    companion object {
        private val UNSUPPORTED_JAVA_OPS = setOf("java.hook", "java.unhook", "java.callOriginal", "java.deoptimize")

        /** `ctx.watch` 的当前值：StateFlow / Compose State 取 value，其余 Flow 为 null（等它发出值后重新渲染）。 */
        internal fun watchValueOf(obj: Any): Any? = when (obj) {
            is kotlinx.coroutines.flow.StateFlow<*> -> obj.value
            is androidx.compose.runtime.State<*> -> obj.value
            is kotlinx.coroutines.flow.Flow<*> -> null
            else -> throw IllegalArgumentException("ctx.watch 只能用于 StateFlow / Flow / Compose State，实际为 ${obj.javaClass.name}")
        }

        /**
         * 主题里的数据桥：`java`（反射调用任意 Java / Kotlin 代码 + `java.proxy` 实现任意 Java 接口）
         * 与 `host`（Application、当前 Activity、Koin 容器里的任意服务），以及 `setTimeout` / `setInterval` / `ui.invalidate()`。
         */
        private val THEME_JAVA_JS = """
            (function () {
              const sync = (name, payload) => JSON.parse(__ui_java(name, JSON.stringify(payload === undefined ? null : payload)));
              const TAG = Symbol("ui.java");
              const isJ = (v) => !!v && (typeof v === "object" || typeof v === "function") && !!v[TAG];
              const clsName = (c) => isJ(c) ? (c[TAG].className || c[TAG].cls) : String(c);
              let __jfn_seq = 0;
              const __jhandlers = new Map();
              const regJFn = (fn, autoInvalidate) => {
                const id = "ujfn#" + (++__jfn_seq);
                __jhandlers.set(id, { fn, autoInvalidate: autoInvalidate !== false });
                return id;
              };
              const enc = (v) => {
                if (v === undefined || v === null) return null;
                if (isJ(v)) return { "${'$'}j": v[TAG].id };
                const t = typeof v;
                if (t === "bigint") return { "${'$'}long": v.toString() };
                if (t === "number") return Number.isFinite(v) ? v : { "${'$'}d": String(v) };
                if (t === "string" || t === "boolean") return v;
                if (t === "function") {
                  return { "${'$'}fn": regJFn((method, args) => enc(v.apply({ method }, dec(args) || [])), true) };
                }
                if (Array.isArray(v)) return v.map(enc);
                if (ArrayBuffer.isView(v)) return Array.from(v);
                const o = {};
                for (const k of Object.keys(v)) o[k] = enc(v[k]);
                return o;
              };
              const dec = (v) => {
                if (v === null || v === undefined) return null;
                if (Array.isArray(v)) return v.map(dec);
                if (typeof v === "object") {
                  if (v["${'$'}j"] !== undefined) return wrap(v);
                  if (v["${'$'}long"] !== undefined) return BigInt(v["${'$'}long"]);
                  if (v["${'$'}d"] !== undefined) return Number(v["${'$'}d"]);
                  const o = {};
                  for (const k of Object.keys(v)) o[k] = dec(v[k]);
                  return o;
                }
                return v;
              };
              const callJ = (t, prefix, name, args) =>
                dec(sync(prefix + "call", { h: t.id, name: String(name), args: args.map(enc), static: t.isClass }));
              const wrap = (d) => {
                const t = { id: d["${'$'}j"], cls: d["${'$'}c"], isClass: d["${'$'}cls"] !== undefined, className: d["${'$'}cls"] || null };
                return new Proxy(t, {
                  get(target, k) {
                    if (k === TAG) return target;
                    if (typeof k === "symbol") return undefined;
                    switch (k) {
                      case "then": case "valueOf": case "constructor": return undefined;
                      case "${'$'}id": return target.id;
                      case "${'$'}type": return target.cls;
                      case "${'$'}name": return target.className;
                      case "toJSON": return () => ({ "${'$'}j": target.id, "${'$'}c": target.cls });
                      case "toString": return (...a) => (a.length || target.isClass) ? callJ(target, "java.", "toString", a) : sync("java.toString", { h: target.id });
                      case "${'$'}call": return (name, ...a) => callJ(target, "java.", name, a);
                      case "${'$'}get": return (f) => dec(sync("java.get", { h: target.id, field: String(f), static: target.isClass }));
                      case "${'$'}set": return (f, v) => { sync("java.set", { h: target.id, field: String(f), value: enc(v), static: target.isClass }); };
                      case "${'$'}copy": return (patch) => {
                        if (target.isClass) throw new TypeError("${'$'}copy 只能用于数据类实例，不能用于类对象");
                        if (!patch || typeof patch !== "object" || Array.isArray(patch)) throw new TypeError("${'$'}copy 参数必须是字段对象");
                        const p = {};
                        for (const k of Object.keys(patch)) { if (patch[k] !== undefined) p[k] = enc(patch[k]); }
                        return dec(sync("java.copy", { h: target.id, patch: p }));
                      };
                      case "${'$'}new": return (...a) => dec(sync("java.new", { h: target.id, args: a.map(enc) }));
                      case "${'$'}newSig": return (sig, ...a) => dec(sync("java.new", { h: target.id, signature: String(sig), args: a.map(enc) }));
                      case "${'$'}main": return (name, ...a) => callJ(target, "java.main.", name, a);
                      case "${'$'}async": return (name, ...a) => callJ(target, "java.async.", name, a);
                      case "${'$'}mainGet": return (f) => dec(sync("java.main.get", { h: target.id, field: String(f), static: target.isClass }));
                      case "${'$'}mainSet": return (f, v) => { sync("java.main.set", { h: target.id, field: String(f), value: enc(v), static: target.isClass }); };
                      case "${'$'}json": return () => dec(sync("java.json", { h: target.id }));
                      case "${'$'}release": return () => { sync("java.release", { h: target.id }); };
                      case "${'$'}is": return (c) => sync("java.instanceOf", { h: target.id, cls: clsName(c) });
                      case "${'$'}members": return () => sync("java.members", { h: target.id, static: target.isClass });
                    }
                    return (...a) => callJ(target, "java.", k, a);
                  },
                  set(target, k, v) {
                    if (typeof k === "symbol") return false;
                    sync("java.set", { h: target.id, field: String(k), value: enc(v), static: target.isClass });
                    return true;
                  },
                  has(target, k) { return k === TAG; },
                });
              };
              globalThis.__ui_java_cb = (hid, method, argsJson) => {
                const entry = __jhandlers.get(hid);
                if (!entry) return JSON.stringify({ result: null, invalidate: false });
                const r = entry.fn(method, JSON.parse(argsJson));
                return JSON.stringify({ result: r === undefined ? null : r, invalidate: entry.autoInvalidate });
              };
              globalThis.java = {
                use(name) { return dec(sync("java.use", { name: String(name) })); },
                type(name) { return this.use(name); },
                new(cls, ...a) { return (isJ(cls) ? cls : this.use(cls))["${'$'}new"](...a); },
                copy(obj, patch) {
                  if (!isJ(obj) || obj[TAG].isClass) throw new TypeError("java.copy 第一个参数必须是 Java 数据类实例");
                  return obj["${'$'}copy"](patch);
                },
                proxy(interfaces, impl, opts) {
                  const list = (Array.isArray(interfaces) ? interfaces : [interfaces]).map(clsName);
                  if (typeof impl !== "function" && (!impl || typeof impl !== "object")) {
                    throw new TypeError("java.proxy 的第二个参数必须是函数或方法对象");
                  }
                  const autoInv = !opts || opts.autoInvalidate !== false;
                  const hid = regJFn((method, args) => {
                    const fn = typeof impl === "function" ? impl : impl[method];
                    if (typeof fn !== "function") return null;
                    const self = typeof impl === "function" ? { method } : impl;
                    return enc(fn.apply(self, dec(args) || []));
                  }, autoInv);
                  return dec(sync("java.proxy", { interfaces: list, hid, async: !!(opts && opts.async) }));
                },
                array(type, values) { return dec(sync("java.array", { type: clsName(type), values: (values || []).map(enc) })); },
                isJava(v) { return isJ(v); },
                json(v) { return isJ(v) ? dec(sync("java.json", { h: v[TAG].id })) : v; },
                members(v) { return isJ(v) ? sync("java.members", { h: v[TAG].id, static: v[TAG].isClass }) : null; },
                keep(v) { if (isJ(v)) sync("java.keep", { h: v[TAG].id }); return v; },
                release(v) { if (isJ(v)) sync("java.release", { h: v[TAG].id }); },
                stats() { return sync("java.stats", {}); },
              };
              globalThis.host = {
                context() { return dec(sync("host.context", null)); },
                activity() { return dec(sync("host.activity", null)); },
                koin() { return dec(sync("host.koin", null)); },
                get(cls, qualifier) { return dec(sync("host.get", { cls: clsName(cls), qualifier: qualifier || null })); },
              };
              if (globalThis.ui) {
                globalThis.ui.invalidate = () => { sync("ui.invalidate", null); };
              }
              const __timers = new Map();
              globalThis.setTimeout = (fn, ms, ...args) => {
                if (typeof fn !== "function") throw new TypeError("setTimeout 第一个参数必须是函数");
                const id = sync("ui.timer.set", { ms: Number(ms) || 0, repeat: false });
                __timers.set(id, () => fn(...args));
                return id;
              };
              globalThis.setInterval = (fn, ms, ...args) => {
                if (typeof fn !== "function") throw new TypeError("setInterval 第一个参数必须是函数");
                const id = sync("ui.timer.set", { ms: Math.max(16, Number(ms) || 16), repeat: true });
                __timers.set(id, () => fn(...args));
                return id;
              };
              globalThis.clearTimeout = (id) => {
                __timers.delete(id);
                if (typeof id === "number") sync("ui.timer.clear", { id });
              };
              globalThis.clearInterval = globalThis.clearTimeout;
              globalThis.__ui_timer_fire = (id, repeat) => {
                const fn = __timers.get(id);
                if (!repeat) __timers.delete(id);
                if (!fn) return false;
                const r = fn();
                return r !== false;
              };
              globalThis.__java_internal = { isJ, idOf: (v) => v[TAG].id, enc, dec, sync };
            })();
        """

        private const val CACHE_SIZE = 2048
        private const val ITEM_CACHE_SIZE = 2048
        private const val SYNC_WAIT_MS = 48L
        private const val SYNC_WINDOW_MS = 250L
        private const val SYNC_WINDOW_BUDGET_MS = 48L
        private const val MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
        private const val MAX_STACK_BYTES = 1L * 1024 * 1024

        /** 运行时附加脚本：渲染、回调编号、动作收集。 */
        private const val RUNTIME_JS = """
            let __cb_seq = 1;
            const __cbs = new Map();
            let __render_ids = [];
            let __watch_ids = [];
            let __actions = [];
            const __ctx = {
              action(name, ...args) {
                if (typeof name !== "string") throw new TypeError("ctx.action 的第一个参数必须是动作名字符串");
                __actions.push({ name, args });
              },
              haptic(kind) {
                __actions.push({ name: "__haptic", args: [String(kind || "click")] });
              },
              watch(x) {
                const J = globalThis.__java_internal;
                if (!J.isJ(x)) throw new TypeError("ctx.watch 的参数必须是 Java 对象（StateFlow / Flow / Compose State）");
                const id = J.idOf(x);
                if (__watch_ids.indexOf(id) < 0) __watch_ids.push(id);
                return J.dec(J.sync("ui.watchValue", { h: id }));
              },
            };
            // Compose(类名, 函数名, [参数]) 的参数：Java 对象 → 句柄编号，顶层函数 → 回调编号
            function __encArg(a, top) {
              if (a == null) return null;
              const J = globalThis.__java_internal;
              if (J.isJ(a)) return { "\u0024j": J.idOf(a) };
              if (typeof a === "function") {
                if (!top) throw new TypeError("Compose 参数里的回调函数只能直接作为顶层参数；若需放在数组或对象里，请用 java.proxy(接口名, fn) 指定接口");
                const id = "cb" + (__cb_seq++);
                __cbs.set(id, a);
                __render_ids.push(id);
                return { "\u0024cb": id };
              }
              if (typeof a === "bigint") return { "\u0024long": a.toString() };
              if (Array.isArray(a)) return a.map(v => __encArg(v, false));
              if (typeof a === "object") {
                const o = {};
                for (const k of Object.keys(a)) o[k] = __encArg(a[k], false);
                return o;
              }
              return a;
            }
            function __encode(x, path) {
              if (Array.isArray(x)) return x.filter(c => c != null && c !== false).map(c => __encode(c));
              if (x != null && typeof x === "object") {
                const isNode = !!x[__IS_NODE];
                const slots = isNode ? x[__CHILD_SLOTS] : null;
                const o = {};
                for (const k of Object.keys(x)) {
                  const v = x[k];
                  if (isNode && k === "id" && x[__AUTO_ID] && path != null) {
                    o.id = x.type + "_" + path;
                  } else if (isNode && k === "children" && Array.isArray(v)) {
                    const base = path != null ? path : "0";
                    o.children = v.map((c, idx) => __encode(c, base + "_" + (slots && slots[idx] !== undefined ? slots[idx] : idx)));
                  } else if (isNode && k === "fallback" && v != null && typeof v === "object") {
                    o.fallback = __encode(v, (path != null ? path : "0") + "_fb");
                  } else if (isNode && k === "args" && Array.isArray(v)) {
                    o.args = v.map(a => __encArg(a, true));
                  } else if (typeof v === "function") {
                    const id = "cb" + (__cb_seq++);
                    __cbs.set(id, v);
                    __render_ids.push(id);
                    o[k + "Fn"] = id;
                  } else {
                    o[k] = __encode(v);
                  }
                }
                return o;
              }
              return x;
            }
            globalThis.__ui_render = (name, dataJson) => {
              const fn = globalThis.__ui_slot_fns[name];
              if (typeof fn !== "function") throw new Error("主题没有按数据渲染的位置「" + name + "」");
              __render_ids = [];
              __watch_ids = [];
              const node = fn(JSON.parse(dataJson), __ctx);
              if (node == null || node === false) return JSON.stringify({ tree: null, ids: __render_ids, watch: __watch_ids });
              if (typeof node !== "object" || Array.isArray(node) || typeof node.type !== "string") {
                throw new TypeError("ui.slot(\"" + name + "\") 的函数必须返回组件节点（如 Row(...)），或 null 表示用原版");
              }
              const tree = __encode(node, "0");
              return JSON.stringify({ tree, ids: __render_ids, watch: __watch_ids });
            };
            // 懒加载列表的一行：item(index, ctx)
            globalThis.__ui_render_item = (id, index) => {
              const fn = __cbs.get(id);
              if (typeof fn !== "function") throw new Error("列表已重新渲染");
              __render_ids = [];
              __watch_ids = [];
              const node = fn(index, __ctx);
              if (node == null || node === false) return JSON.stringify({ tree: null, ids: __render_ids, watch: __watch_ids });
              if (typeof node !== "object" || Array.isArray(node) || typeof node.type !== "string") {
                throw new TypeError("item 函数必须返回组件节点（如 Row(...)），或 null 表示这一行不显示");
              }
              const tree = __encode(node, "i" + index);
              return JSON.stringify({ tree, ids: __render_ids, watch: __watch_ids });
            };
            // 多个参数的回调：参数里的 Java 句柄还原成对象
            globalThis.__ui_call_args = (id, argsJson) => {
              const f = __cbs.get(id);
              if (typeof f !== "function") throw new Error("回调已失效（界面已重新渲染）");
              __actions = [];
              const args = JSON.parse(argsJson).map(globalThis.__java_internal.dec);
              f(...args, __ctx);
              return JSON.stringify(__actions);
            };
            globalThis.__ui_call = (id, hasArg, argJson) => {
              const f = __cbs.get(id);
              if (typeof f !== "function") throw new Error("回调已失效（界面已重新渲染）");
              __actions = [];
              if (hasArg) f(JSON.parse(argJson), __ctx);
              else f(__ctx);
              return JSON.stringify(__actions);
            };
            globalThis.__ui_release = (idsJson) => { for (const id of JSON.parse(idsJson)) __cbs.delete(id); };
        """
    }
}

/** 一次渲染结果：[tree] 为 null 表示该处用原版（主题返回 null 或出错）。 */
class UISlotResult(
    val tree: UIMessageNode?,
    internal val callbackIds: List<String>,
    internal val javaHandles: List<Long> = emptyList(),
    val watched: List<Any> = emptyList(),
) {
    /** 界面上正在显示它的地方的个数（[UIThemeRuntime.pin]）；大于 0 时被挤出缓存也不回收。 */
    internal var pins = 0
    internal var evicted = false
    internal var released = false
}

/** 按预设（代码 + 编译指纹）共享运行时实例，最多保留 3 个（助手可各自绑定主题）；预设没有按数据渲染的位置时不创建。 */
object UIThemeRuntimes {
    private val runtimes = object : LinkedHashMap<String, UIThemeRuntime>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UIThemeRuntime>): Boolean =
            (size > 3).also { if (it) eldest.value.close() }
    }

    @Synchronized
    fun get(preset: UIPreset): UIThemeRuntime? {
        if (preset.dynamicSlots.isEmpty()) return null
        val key = "${preset.id}:${preset.compiledCodeHash}"
        runtimes[key]?.let { return it }
        runtimes.keys.filter { it.startsWith("${preset.id}:") }.forEach { runtimes.remove(it)?.close() }
        return UIThemeRuntime(preset.code, preset.id, preset.compiledCodeHash).also { runtimes[key] = it }
    }
}

/** 接入点数据（Kotlin 值）转 JSON：字符串、数字、布尔、null、Map、List、枚举（名字），其余 toString()。 */
fun Any?.toUIData(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(name)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toUIData() })
    is Iterable<*> -> JsonArray(map { it.toUIData() })
    is Array<*> -> JsonArray(map { it.toUIData() })
    else -> JsonPrimitive(toString())
}
