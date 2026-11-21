package me.rerere.rikkahub.ui.custom

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/** 可视化修改要改的地方：某个组件调用（按源码起点），或某个 `ui.*` 调用（没有时在末尾新增一句）。 */
sealed interface UIEditTarget {
    data class Call(val start: Int) : UIEditTarget

    /** `ui.<fn>(...)`；[key] 为 `ui.style` / `ui.page` 的第一个参数。 */
    data class Ui(val fn: String, val key: String? = null) : UIEditTarget

    fun toJson(): JsonObject = when (this) {
        is Call -> buildJsonObject { put("call", start) }
        is Ui -> buildJsonObject {
            put("ui", fn)
            key?.let { put("key", it) }
        }
    }
}

/** 改写结果：成功时 [code] 为新代码；不能改时 [error] 说明原因，[line] 为该处所在行。 */
data class UIEditResult(val code: String?, val error: String?, val line: Int?) {
    val ok: Boolean get() = code != null

    /** 给用户看的说明（含行号）。 */
    val message: String
        get() = (error ?: "") + (line?.let { "，请到代码第 $it 行修改" } ?: "")
}

/** 一处能否修改。 */
data class UIEditCheck(val ok: Boolean, val error: String?, val line: Int?) {
    val message: String get() = (error ?: "") + (line?.let { "（第 $it 行）" } ?: "")
}

/**
 * 执行代码得到的界面树节点（保留代码里写的原始属性），[src] 为产生它的组件调用在源码里的起点；
 * 同一处调用被执行多次（循环、函数）时 [count] > 1，改它会同时影响这些节点。
 */
data class UIEditNode(
    val src: Int?,
    val type: String,
    val props: JsonObject,
    val children: List<UIEditNode>,
    val line: Int?,
    val count: Int,
) {
    /** 第一个参数写的值（Text 的文字、Icon 的图标名……）。 */
    val lead: String? get() = UI_LEAD_KEYS[type]?.let { (props[it] as? JsonPrimitive)?.contentOrNull }
}

data class UIEditSlot(
    val name: String,
    val line: Int?,
    /** 固定写法的节点树；按数据渲染（函数）的位置为 null。 */
    val tree: UIEditNode?,
)

data class UICodeAnalysis(
    val error: String?,
    val errorLine: Int?,
    val slots: List<UIEditSlot>,
)

/** 节点类型 → 第一个参数对应的属性名（与引导脚本一致）。 */
val UI_LEAD_KEYS: Map<String, String> = mapOf(
    "custom_text" to "customText", "text_field" to "customText", "button" to "customText", "badge" to "customText", "icon" to "iconName",
    "icon_button" to "iconName", "image" to "src", "path" to "d", "host" to "hostBlock", "video" to "src", "shader" to "shader",
)

/**
 * 编辑页的「可视化编辑 → 改代码」工具（acorn 解析 UI 代码）。只在编辑页使用：一个常驻 QuickJS 实例做解析与改写，
 * [analyze] 另起一次性实例执行插桩后的代码（用户代码的顶层 const 不能在同一实例里重复执行）。
 */
class UICodeEditor : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ui-code-editor").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var js: QuickJs? = null

    private suspend fun <T> withJs(block: suspend (QuickJs) -> T): T = withContext(dispatcher) {
        mutex.withLock {
            val q = js ?: QuickJs.create(dispatcher).also { q ->
                q.memoryLimit = 64L * 1024 * 1024
                q.maxStackSize = 1L * 1024 * 1024
                q.evaluate<Any?>(resource("acorn.js"), "acorn.js")
                q.evaluate<Any?>(resource("editor.js"), "ui-editor.js")
                js = q
            }
            block(q)
        }
    }

    private suspend fun call(fn: String, vararg args: Any): JsonObject = withJs { q ->
        val argSrc = args.joinToString(", ") { a ->
            when (a) {
                is Int -> a.toString()
                is String -> JsonPrimitive(a).toString()
                is JsonElement -> JsonPrimitive(a.toString()).toString()
                else -> error("unsupported arg $a")
            }
        }
        Json.parseToJsonElement(q.evaluate<String>("$fn($argSrc)", "ui-editor-call.js")).jsonObject
    }

    private fun JsonObject.toResult() = UIEditResult(
        code = (this["code"] as? JsonPrimitive)?.contentOrNull,
        error = (this["error"] as? JsonPrimitive)?.contentOrNull,
        line = (this["line"] as? JsonPrimitive)?.intOrNull,
    )

    /** 设置属性（[path] 每段可写 "a|b" 表示别名）；[value] 为 JSON 值（字符串、数字、布尔、数组）。 */
    suspend fun setProp(code: String, target: UIEditTarget, path: List<String>, value: JsonElement): UIEditResult =
        call("__ed_setProp", code, target.toJson(), JsonArray(path.map(::JsonPrimitive)), value.toString()).toResult()

    suspend fun removeProp(code: String, target: UIEditTarget, path: List<String>): UIEditResult =
        call("__ed_removeProp", code, target.toJson(), JsonArray(path.map(::JsonPrimitive))).toResult()

    /** 改第一个参数（Text 的文字、Icon 的图标名……）。 */
    suspend fun setLead(code: String, callStart: Int, value: String): UIEditResult =
        call("__ed_setLead", code, callStart, JsonPrimitive(value).toString()).toResult()

    /** 在父节点的子节点数组末尾加一个子节点；[childSrc] 为组件代码（如 `Text("新文字")`）。 */
    suspend fun addChild(code: String, parentStart: Int, childSrc: String): UIEditResult =
        call("__ed_addChild", code, parentStart, childSrc).toResult()

    suspend fun removeChild(code: String, parentStart: Int, childStart: Int): UIEditResult =
        call("__ed_removeChild", code, parentStart, childStart).toResult()

    suspend fun moveChild(code: String, parentStart: Int, childStart: Int, delta: Int): UIEditResult =
        call("__ed_moveChild", code, parentStart, childStart, delta).toResult()

    /** 批量检查各处能否修改（与 [checks] 同序）。 */
    suspend fun check(code: String, checks: List<Pair<UIEditTarget, List<String>>>): List<UIEditCheck> = withJs { q ->
        val arr = buildJsonArray {
            checks.forEach { (t, p) ->
                add(buildJsonObject {
                    put("target", t.toJson())
                    put("path", JsonArray(p.map(::JsonPrimitive)))
                })
            }
        }
        val out = Json.parseToJsonElement(
            q.evaluate<String>("__ed_check(${JsonPrimitive(code)}, ${JsonPrimitive(arr.toString())})", "ui-editor-check.js")
        )
        if (out is JsonObject) {
            // 代码本身解析失败：全部不可改
            val r = out.toResult()
            return@withJs checks.map { UIEditCheck(false, r.error, r.line) }
        }
        out.jsonArray.map { e ->
            val o = e.jsonObject
            UIEditCheck(
                ok = o["ok"] != null,
                error = (o["error"] as? JsonPrimitive)?.contentOrNull,
                line = (o["line"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }

    /** 执行插桩后的代码，得到各位置的界面树（带源码位置）。 */
    suspend fun analyze(code: String): UICodeAnalysis {
        if (code.isBlank()) return UICodeAnalysis(null, null, emptyList())
        val ins = call("__ed_instrument", code)
        ins["error"]?.let { return UICodeAnalysis(it.jsonPrimitive.contentOrNull, (ins["line"] as? JsonPrimitive)?.intOrNull, emptyList()) }
        val instrumented = ins.getValue("code").jsonPrimitive.content
        val calls = ins.getValue("calls").jsonObject.mapKeys { it.key.toInt() }.mapValues { (it.value.jsonObject["line"] as JsonPrimitive).intOrNull }
        val slotLines = ins.getValue("slots").jsonObject.mapValues { (it.value.jsonObject["line"] as JsonPrimitive).intOrNull }
        val captured = try {
            withContext(Dispatchers.Default) {
                quickJs(Dispatchers.Default) {
                    memoryLimit = 64L * 1024 * 1024
                    maxStackSize = 1L * 1024 * 1024
                    evaluationTimeoutMillis = 5_000L
                    evaluate<Any?>(CustomUICodeEngine.BOOTSTRAP_UI_DSL_JS, "ui-bootstrap.js")
                    evaluate<Any?>(CAPTURE_JS, "ui-editor-capture.js")
                    evaluate<Any?>(instrumented, "ui-code.js")
                    evaluate<String>("JSON.stringify({ slots: __ed_slots, counts: __ed_counts }, (k, v) => typeof v === \"function\" ? \"ƒ\" : v)", "ui-editor-finish.js")
                }
            }
        } catch (_: QuickJsInterruptedException) {
            return UICodeAnalysis("UI 代码执行超过 5 秒（是否有死循环？）", null, emptyList())
        } catch (e: QuickJsException) {
            val line = Regex("""ui-code\.js:(\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            return UICodeAnalysis(e.message, line, emptyList())
        }
        val root = Json.parseToJsonElement(captured).jsonObject
        val counts = root.getValue("counts").jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.intOrNull ?: 1 }
        fun node(o: JsonObject): UIEditNode {
            val src = (o["__src"] as? JsonPrimitive)?.intOrNull
            return UIEditNode(
                src = src,
                type = o["type"]?.jsonPrimitive?.content.orEmpty(),
                props = JsonObject(o.filterKeys { it != "__src" && it != "type" && it != "children" && it != "id" }),
                children = (o["children"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::node) }.orEmpty(),
                line = src?.let { calls[it] },
                count = src?.let { counts[it] } ?: 1,
            )
        }
        val slots = root.getValue("slots").jsonObject.map { (name, v) ->
            val o = v.jsonObject
            UIEditSlot(name = name, line = slotLines[name], tree = (o["tree"] as? JsonObject)?.let(::node))
        }
        return UICodeAnalysis(null, null, slots)
    }

    override fun close() {
        scope.launch { mutex.withLock { js?.close(); js = null } }.invokeOnCompletion {
            scope.cancel()
            executor.shutdown()
        }
    }

    companion object {
        private fun resource(name: String): String =
            UICodeEditor::class.java.getResourceAsStream("/ui-editor/$name")?.use { it.readBytes().decodeToString() }
                ?: error("missing ui editor resource: $name")

        /** 插桩执行：记录每处组件调用执行的次数，把源码位置写进产生的节点，并记下每个 ui.slot / ui.page 的节点树。 */
        private const val CAPTURE_JS = """
            globalThis.__ed_counts = {};
            globalThis.__ed_slots = {};
            globalThis.__src = (s, v) => {
              __ed_counts[s] = (__ed_counts[s] || 0) + 1;
              if (v && typeof v === "object" && !Array.isArray(v) && typeof v.type === "string" && v.__src === undefined) v.__src = s;
              return v;
            };
            (() => {
              const origSlot = globalThis.ui.slot;
              globalThis.ui.slot = function (name, x) {
                __ed_slots[name] = typeof x === "function" ? {} : { tree: x };
                return origSlot.call(globalThis.ui, name, x);
              };
              const origPage = globalThis.ui.page;
              globalThis.ui.page = function (route, cfg) {
                if (cfg && typeof cfg === "object" && cfg.background && typeof cfg.background === "object") {
                  __ed_slots["page:" + route + " (background)"] = { tree: cfg.background };
                }
                if (cfg && typeof cfg === "object" && cfg.foreground && typeof cfg.foreground === "object") {
                  __ed_slots["page:" + route + " (foreground)"] = { tree: cfg.foreground };
                }
                return origPage.call(globalThis.ui, route, cfg);
              };
            })();
        """
    }
}
