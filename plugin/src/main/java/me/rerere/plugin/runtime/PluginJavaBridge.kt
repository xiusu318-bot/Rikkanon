package me.rerere.plugin.runtime

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.lang.reflect.Array as JArray
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 插件 JS 与 Java/Kotlin 世界之间的反射桥（`rikka.java` / `rikka.hook` 的宿主实现）。
 *
 * - 每个插件运行时一份，持有「句柄表」：Java 对象以 `{"$j": id, "$c": 类名}` 形式传给 JS，
 *   JS 侧包装成 Proxy，调用方法时再按 id 取回对象。同一对象（按引用相等）复用同一个 id。
 * - 只在插件真正调用 `rikka.java.*` / `rikka.hook.*` 时才执行任何反射或加载 Hook 引擎；
 *   没有插件或插件不使用这些接口时，本类不会做任何事情。
 * - 插件停止时 [dispose] 会解除该插件的全部 Hook、释放全部句柄。
 */
class PluginJavaBridge(private val runtime: JavaBridgeHost) {
    private val lock = Any()
    private val handles = HashMap<Long, Any>()
    private val ids = IdentityHashMap<Any, Long>()
    private val seq = AtomicLong(0)

    /** hookId -> 解除函数列表（一个 hookId 可能对应多个重载）。 */
    private val hooks = ConcurrentHashMap<String, List<() -> Unit>>()
    private val hookSeq = AtomicLong(0)

    /** 正在等待 JS 回调的 Hook 调用帧（供 `callOriginal` 使用）。 */
    private val activeFrames = ConcurrentHashMap<String, HookFrame>()
    private val frameSeq = AtomicLong(0)

    @Volatile
    private var disposed = false

    /** 非 null 时，本桥新登记的句柄 id 都记到这里（主题运行时按一次渲染回收临时句柄）。 */
    @Volatile
    private var scopeCreated: MutableList<Long>? = null

    /**
     * 开始一个句柄作用域：此后新登记的句柄 id 都记下来，[endScope] 返回它们（调用方负责在合适的时候 [releaseScope]）。
     * 不可嵌套；同一时间只能有一个作用域（主题运行时在自己的单线程上使用）。
     */
    fun beginScope() {
        scopeCreated = java.util.Collections.synchronizedList(ArrayList())
    }

    fun endScope(): List<Long> {
        val list = scopeCreated ?: return emptyList()
        scopeCreated = null
        return synchronized(list) { list.toList() }
    }

    private val kept = ConcurrentHashMap.newKeySet<Long>()

    /** 句柄不再随作用域回收（主题的 `java.keep`）。 */
    fun keep(id: Long) {
        kept.add(id)
        scopeCreated?.remove(id)
    }

    /** 回收一个作用域登记的句柄（跳过 [keep] 过的）。 */
    fun releaseScope(ids: List<Long>) {
        ids.forEach { if (it !in kept) release(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // 句柄表
    // ---------------------------------------------------------------------------------------------

    /** 将任意 Java 对象登记进句柄表并返回 id（同一对象复用 id）。 */
    fun put(obj: Any): Long = synchronized(lock) {
        ids[obj]?.let { return it }
        val id = seq.incrementAndGet()
        handles[id] = obj
        ids[obj] = id
        id
    }

    private fun isRegistered(obj: Any): Boolean = synchronized(lock) { ids.containsKey(obj) }

    fun get(id: Long): Any = synchronized(lock) {
        handles[id] ?: throw IllegalStateException("Java 句柄 #$id 已释放或不存在")
    }

    fun release(id: Long) {
        kept.remove(id)
        synchronized(lock) {
            val obj = handles.remove(id) ?: return
            ids.remove(obj)
        }
    }

    fun handleCount(): Int = synchronized(lock) { handles.size }

    /** 插件停止：解除全部 Hook、释放全部句柄。 */
    fun dispose() {
        disposed = true
        hooks.values.forEach { list -> list.forEach { runCatching { it() } } }
        hooks.clear()
        activeFrames.clear()
        synchronized(lock) {
            handles.clear()
            ids.clear()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 编码：Java -> JSON（传给 JS）
    // ---------------------------------------------------------------------------------------------

    /**
     * 基本类型直接转 JSON；其余对象登记为句柄。
     * [created] 非空时，记录本次新创建的句柄 id（用于 Hook / 代理回调结束后自动回收临时句柄）。
     */
    fun encode(value: Any?, created: MutableList<Long>? = null): JsonElement = when (value) {
        null, Unit -> JsonNull
        is String -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value)
        is Byte -> JsonPrimitive(value)
        is Long -> if (value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) JsonPrimitive(value)
        else buildJsonObject { put("\$long", value.toString()) }

        is Float -> encodeDouble(value.toDouble())
        is Double -> encodeDouble(value)
        else -> {
            val existed = isRegistered(value)
            val id = put(value)
            if (!existed) {
                created?.add(id)
                scopeCreated?.add(id)
            }
            buildJsonObject {
                put("\$j", id)
                put("\$c", value.javaClass.name)
                if (value is Class<*>) put("\$cls", value.name)
            }
        }
    }

    private fun encodeDouble(d: Double): JsonElement =
        if (d.isNaN() || d.isInfinite()) buildJsonObject { put("\$d", d.toString()) } else JsonPrimitive(d)

    /** 深度转换为纯 JSON（集合 / 数组 / Map / 基本类型），无法转换的对象保留为句柄。 */
    fun toJson(value: Any?, depth: Int = 0): JsonElement {
        if (depth > MAX_JSON_DEPTH) return encode(value)
        return when (value) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v, depth + 1) })
            is Iterable<*> -> JsonArray(value.map { toJson(it, depth + 1) })
            is Sequence<*> -> JsonArray(value.map { toJson(it, depth + 1) }.toList())
            is Array<*> -> JsonArray(value.map { toJson(it, depth + 1) })
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is LongArray -> JsonArray(value.map { encode(it) })
            is DoubleArray -> JsonArray(value.map { encode(it) })
            is FloatArray -> JsonArray(value.map { encode(it) })
            is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
            is ShortArray -> JsonArray(value.map { JsonPrimitive(it) })
            is ByteArray -> JsonArray(value.map { JsonPrimitive(it) })
            is CharArray -> JsonPrimitive(String(value))
            is Enum<*> -> JsonPrimitive(value.name)
            is JsonElement -> value
            else -> encode(value)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 解码：JSON -> Java（按目标类型转换）
    // ---------------------------------------------------------------------------------------------

    /** 不知道目标类型时的自然解码。 */
    fun decode(e: JsonElement): Any? = when (e) {
        JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> {
                val l = e.longOrNull
                if (l != null && !e.content.contains('.') && !e.content.contains('e', ignoreCase = true)) {
                    if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
                } else e.doubleOrNull
            }
        }

        is JsonArray -> e.map { decode(it) }.toMutableList()
        is JsonObject -> when {
            e.containsKey("\$j") -> get(e["\$j"]!!.jsonPrimitive.content.toLong())
            e.containsKey("\$long") -> e["\$long"]!!.jsonPrimitive.content.toLong()
            e.containsKey("\$d") -> e["\$d"]!!.jsonPrimitive.content.toDouble()
            e.containsKey("\$fn") -> throw IllegalArgumentException("JS 函数只能传给接口类型的参数（需要知道要实现哪个接口）")
            else -> LinkedHashMap<String, Any?>().apply { e.forEach { (k, v) -> put(k, decode(v)) } }
        }
    }

    /** 按目标类型解码；无法转换时抛异常。 */
    fun decodeAs(e: JsonElement, type: Class<*>): Any? {
        if (e is JsonObject) {
            e["\$fn"]?.let { fn ->
                require(type.isInterface) { "JS 函数不能转换为 ${type.name}（只能传给接口类型参数）" }
                return newProxy(arrayOf(type), fn.jsonPrimitive.content, wait = true)
            }
        }
        if (e === JsonNull) {
            require(!type.isPrimitive) { "null 不能传给基本类型参数 ${type.name}" }
            return null
        }
        val raw = decode(e)
        return coerce(raw, type, e)
    }

    private fun coerce(raw: Any?, type: Class<*>, src: JsonElement): Any? {
        if (raw == null) return null
        val boxed = type.boxed()
        if (boxed.isInstance(raw) && !(raw is Number && boxed != raw.javaClass && Number::class.java.isAssignableFrom(boxed))) return raw
        when (raw) {
            is Number -> when (boxed) {
                java.lang.Integer::class.java -> return raw.toInt()
                java.lang.Long::class.java -> return raw.toLong()
                java.lang.Double::class.java -> return raw.toDouble()
                java.lang.Float::class.java -> return raw.toFloat()
                java.lang.Short::class.java -> return raw.toShort()
                java.lang.Byte::class.java -> return raw.toByte()
                java.lang.Character::class.java -> return raw.toInt().toChar()
                java.lang.Number::class.java, Any::class.java -> return raw
                java.lang.String::class.java, CharSequence::class.java -> return raw.toString()
            }

            is String -> {
                if (boxed == java.lang.Character::class.java && raw.length == 1) return raw[0]
                if (type.isEnum) return enumConstant(type, raw)
                    ?: throw IllegalArgumentException("${type.name} 没有枚举值 $raw")
                if (type == org.json.JSONObject::class.java) return org.json.JSONObject(raw)
                if (type == org.json.JSONArray::class.java) return org.json.JSONArray(raw)
            }

            is List<*> -> {
                if (type.isArray) {
                    val comp = type.componentType!!
                    val arr = JArray.newInstance(comp, raw.size)
                    val srcArr = src as? JsonArray
                    raw.forEachIndexed { i, v ->
                        JArray.set(arr, i, if (srcArr != null) decodeAs(srcArr[i], comp) else coerce(v, comp, JsonNull))
                    }
                    return arr
                }
                if (type.isAssignableFrom(ArrayList::class.java)) return ArrayList(raw)
                if (type.isAssignableFrom(LinkedHashSet::class.java)) return LinkedHashSet(raw)
                if (type == org.json.JSONArray::class.java) return org.json.JSONArray(src.toString())
            }

            is Map<*, *> -> {
                if (type.isAssignableFrom(LinkedHashMap::class.java)) return raw
                if (type == org.json.JSONObject::class.java) return org.json.JSONObject(src.toString())
                if (type == String::class.java) return src.toString()
            }
        }
        if (type == String::class.java || type == CharSequence::class.java) return raw.toString()
        throw IllegalArgumentException("无法把 ${raw.javaClass.name} 转换为 ${type.name}")
    }

    /** 参数匹配评分：-1 表示不兼容，越大越匹配。 */
    private fun score(type: Class<*>, e: JsonElement): Int {
        val boxed = type.boxed()
        return when (e) {
            JsonNull -> if (type.isPrimitive) -1 else 1
            is JsonPrimitive -> when {
                e.isString -> when {
                    boxed == String::class.java -> 5
                    boxed == CharSequence::class.java -> 4
                    boxed == java.lang.Character::class.java && e.content.length == 1 -> 3
                    type.isEnum -> if (enumConstant(type, e.content) != null) 3 else -1

                    type == org.json.JSONObject::class.java || type == org.json.JSONArray::class.java -> 1
                    boxed.isAssignableFrom(String::class.java) -> 2
                    else -> -1
                }

                e.booleanOrNull != null -> when {
                    boxed == java.lang.Boolean::class.java -> 5
                    boxed.isAssignableFrom(java.lang.Boolean::class.java) -> 2
                    else -> -1
                }

                else -> {
                    val integral = e.longOrNull != null && !e.content.contains('.')
                    when (boxed) {
                        java.lang.Integer::class.java -> if (integral && e.longOrNull!! in Int.MIN_VALUE..Int.MAX_VALUE) 5 else -1
                        java.lang.Long::class.java -> if (integral) 4 else -1
                        java.lang.Double::class.java -> if (integral) 3 else 5
                        java.lang.Float::class.java -> if (integral) 2 else 4
                        java.lang.Short::class.java -> if (integral && e.longOrNull!! in Short.MIN_VALUE..Short.MAX_VALUE) 2 else -1
                        java.lang.Byte::class.java -> if (integral && e.longOrNull!! in Byte.MIN_VALUE..Byte.MAX_VALUE) 2 else -1
                        java.lang.Character::class.java -> -1
                        java.lang.Number::class.java -> 2
                        Any::class.java -> 1
                        else -> if (boxed.isAssignableFrom(java.lang.Integer::class.java)) 1 else -1
                    }
                }
            }

            is JsonArray -> when {
                type.isArray -> 3
                type.isAssignableFrom(ArrayList::class.java) -> 3
                type.isAssignableFrom(LinkedHashSet::class.java) -> 2
                type == org.json.JSONArray::class.java -> 2
                else -> -1
            }

            is JsonObject -> when {
                e.containsKey("\$j") -> {
                    val obj = runCatching { get(e["\$j"]!!.jsonPrimitive.content.toLong()) }.getOrNull() ?: return -1
                    when {
                        obj.javaClass == boxed -> 6
                        boxed.isInstance(obj) -> 4
                        else -> -1
                    }
                }

                e.containsKey("\$long") -> when (boxed) {
                    java.lang.Long::class.java -> 5
                    java.lang.Number::class.java, Any::class.java -> 2
                    else -> -1
                }

                e.containsKey("\$d") -> when (boxed) {
                    java.lang.Double::class.java, java.lang.Float::class.java -> 4
                    java.lang.Number::class.java, Any::class.java -> 2
                    else -> -1
                }

                e.containsKey("\$fn") -> if (type.isInterface) 2 else -1
                else -> when {
                    type.isAssignableFrom(LinkedHashMap::class.java) -> 3
                    type == org.json.JSONObject::class.java -> 2
                    type == String::class.java -> 1
                    else -> -1
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 类、成员查找
    // ---------------------------------------------------------------------------------------------

    private val methodCache = ConcurrentHashMap<Pair<Class<*>, String>, List<Method>>()

    private fun ensureHiddenApiAccess() {
        if (hiddenApiDone) return
        synchronized(PluginJavaBridge::class.java) {
            if (hiddenApiDone) return
            hiddenApiDone = true
            // Android 9 以下没有隐藏 API 限制
            if (android.os.Build.VERSION.SDK_INT >= 28 &&
                !org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            ) {
                runtime.hostLog("warn", "放开隐藏 API 限制失败，访问系统隐藏成员可能报错")
            }
        }
    }

    fun findClass(name: String): Class<*> {
        ensureHiddenApiAccess()
        val n = name.trim()
        PRIMITIVES[n]?.let { return it }
        if (n.endsWith("[]")) {
            val comp = findClass(n.removeSuffix("[]"))
            return JArray.newInstance(comp, 0).javaClass
        }
        val loaders = listOfNotNull(
            PluginJavaBridge::class.java.classLoader,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
        )
        var last: Throwable? = null
        for (l in loaders) {
            try {
                return Class.forName(n, true, l)
            } catch (e: Throwable) {
                last = e
            }
        }
        throw ClassNotFoundException("找不到类 $n", last)
    }

    private fun hierarchy(cls: Class<*>): Sequence<Class<*>> = generateSequence(cls) { it.superclass }

    /** 收集类上所有同名方法（含父类、接口默认方法、非 public 方法），按签名去重并优先子类。 */
    private fun methodsNamed(cls: Class<*>, name: String): List<Method> = methodCache.getOrPut(cls to name) {
        val seen = HashSet<String>()
        val out = ArrayList<Method>()
        fun add(m: Method) {
            if (m.name != name || m.isBridge) return
            val sig = m.parameterTypes.joinToString(",") { it.name }
            if (seen.add(sig)) {
                runCatching { m.isAccessible = true }
                out += m
            }
        }
        hierarchy(cls).forEach { c -> c.declaredMethods.forEach(::add) }
        cls.methods.forEach(::add)
        allInterfaces(cls).forEach { i -> i.declaredMethods.forEach(::add) }
        out
    }

    private fun allInterfaces(cls: Class<*>): Set<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        fun walk(c: Class<*>) {
            c.interfaces.forEach { if (out.add(it)) walk(it) }
            c.superclass?.let(::walk)
        }
        walk(cls)
        return out
    }

    fun findField(cls: Class<*>, name: String): Field {
        hierarchy(cls).forEach { c ->
            c.declaredFields.firstOrNull { it.name == name }?.let {
                runCatching { it.isAccessible = true }
                return it
            }
        }
        allInterfaces(cls).forEach { i ->
            i.declaredFields.firstOrNull { it.name == name }?.let { return it }
        }
        throw NoSuchFieldException("${cls.name} 没有字段 $name")
    }

    /** 解析 `name(int,java.lang.String)` 形式的显式签名。 */
    private fun parseSignature(raw: String): Pair<String, List<Class<*>>?> {
        val p = raw.indexOf('(')
        if (p < 0 || !raw.endsWith(")")) return raw to null
        val name = raw.substring(0, p).trim()
        val inner = raw.substring(p + 1, raw.length - 1).trim()
        val types = if (inner.isEmpty()) emptyList() else inner.split(',').map { findClass(it.trim()) }
        return name to types
    }

    private fun enumConstant(type: Class<*>, name: String): Any? =
        type.enumConstants?.firstOrNull { (it as Enum<*>).name == name }

    private fun <T : Executable> pickBest(candidates: List<T>, args: List<JsonElement>, what: String): Pair<T, Array<Any?>> {
        var best: T? = null
        var bestScore = -1
        for (c in candidates) {
            val params = c.parameterTypes
            val s: Int = if (c.isVarArgs && args.size >= params.size - 1) {
                val fixed = params.size - 1
                var total = 0
                var ok = true
                for (i in 0 until fixed) {
                    val sc = score(params[i], args[i]); if (sc < 0) { ok = false; break }; total += sc
                }
                // 允许直接传数组，或展开传参
                if (ok && args.size == params.size) {
                    val direct = score(params.last(), args.last())
                    if (direct >= 0) total += direct else {
                        val sc = score(params.last().componentType!!, args.last()); if (sc < 0) ok = false else total += sc
                    }
                } else if (ok) {
                    for (i in fixed until args.size) {
                        val sc = score(params.last().componentType!!, args[i]); if (sc < 0) { ok = false; break }; total += sc
                    }
                }
                if (ok) total else -1
            } else if (params.size == args.size) {
                var total = 0
                for (i in params.indices) {
                    val sc = score(params[i], args[i]); if (sc < 0) { total = -1; break }; total += sc
                }
                total + 1 // 非变参优先
            } else -1
            if (s > bestScore) {
                best = c
                bestScore = s
            }
        }
        val chosen = best ?: throw NoSuchMethodException(
            "$what：没有参数匹配的重载（实参 ${args.size} 个）。候选：\n" +
                candidates.joinToString("\n") { "  " + describe(it) }
        )
        return chosen to buildArgs(chosen, args)
    }

    private fun buildArgs(exec: Executable, args: List<JsonElement>): Array<Any?> {
        val params = exec.parameterTypes
        if (exec.isVarArgs && !(args.size == params.size && score(params.last(), args.last()) >= 0)) {
            val fixed = params.size - 1
            val out = arrayOfNulls<Any?>(params.size)
            for (i in 0 until fixed) out[i] = decodeAs(args[i], params[i])
            val comp = params.last().componentType!!
            val rest = JArray.newInstance(comp, args.size - fixed)
            for (i in fixed until args.size) JArray.set(rest, i - fixed, decodeAs(args[i], comp))
            out[fixed] = rest
            return out
        }
        return Array(params.size) { decodeAs(args[it], params[it]) }
    }

    fun describe(m: Member): String = when (m) {
        is Method -> "${m.declaringClass.name}.${m.name}(${m.parameterTypes.joinToString(",") { it.typeName }}): ${m.returnType.typeName}"
        is Constructor<*> -> "${m.declaringClass.name}(${m.parameterTypes.joinToString(",") { it.typeName }})"
        else -> m.toString()
    }

    // ---------------------------------------------------------------------------------------------
    // 调用
    // ---------------------------------------------------------------------------------------------

    private fun unwrap(e: Throwable): Throwable = if (e is InvocationTargetException) e.targetException ?: e else e

    private fun <T> rethrowing(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val cause = unwrap(e)
        if (cause === e) throw e
        throw RuntimeException("${cause.javaClass.name}: ${cause.message}", cause)
    }

    /** 调用方法：[target] 为实例（或 isStatic 时为 Class）。 */
    fun invokeMethod(target: Any, isStatic: Boolean, rawName: String, args: List<JsonElement>): Any? = rethrowing {
        val (name, explicit) = parseSignature(rawName)
        val cls = if (isStatic) target as Class<*> else target.javaClass
        var candidates = methodsNamed(cls, name).filter { !isStatic || Modifier.isStatic(it.modifiers) }
        var receiver: Any? = if (isStatic) null else target
        if (isStatic && candidates.isEmpty()) {
            // 类对象本身的实例方法（如 getName / getDeclaredFields）
            candidates = methodsNamed(Class::class.java, name)
            receiver = target
        }
        if (explicit != null) candidates = candidates.filter { it.parameterTypes.toList() == explicit }
        require(candidates.isNotEmpty()) { "${cls.name} 没有方法 $rawName" }
        val (picked, jargs) = pickBest(candidates, args, "${cls.name}.$name")
        val m = accessibleVariant(picked)
        if (Modifier.isStatic(m.modifiers)) receiver = null
        m.invoke(receiver, *jargs)
    }

    /**
     * 方法本身是 public 但声明它的类不是 public（如 JDK / 框架内部实现类）时，
     * 改用公开父类或接口上的同签名方法调用，避免访问限制。
     */
    private fun accessibleVariant(m: Method): Method {
        if (!Modifier.isPublic(m.modifiers) || Modifier.isPublic(m.declaringClass.modifiers)) return m
        val supers = hierarchy(m.declaringClass).drop(1) + allInterfaces(m.declaringClass).asSequence()
        for (c in supers) {
            if (!Modifier.isPublic(c.modifiers)) continue
            val found = runCatching { c.getMethod(m.name, *m.parameterTypes) }.getOrNull()
            if (found != null && Modifier.isPublic(found.declaringClass.modifiers)) return found
        }
        return m
    }

    fun newInstance(cls: Class<*>, rawArgs: List<JsonElement>, explicit: List<Class<*>>? = null): Any = rethrowing {
        ensureHiddenApiAccess()
        var ctors = cls.declaredConstructors.toList()
        if (explicit != null) ctors = ctors.filter { it.parameterTypes.toList() == explicit }
        require(ctors.isNotEmpty()) { "${cls.name} 没有可用的构造函数" }
        val (c, jargs) = pickBest(ctors, rawArgs, "new ${cls.name}")
        runCatching { c.isAccessible = true }
        c.newInstance(*jargs)
    }

    fun getField(target: Any, isStatic: Boolean, name: String): Any? = rethrowing {
        val cls = if (isStatic) target as Class<*> else target.javaClass
        val f = findField(cls, name)
        f.get(if (Modifier.isStatic(f.modifiers)) null else target)
    }

    fun setField(target: Any, isStatic: Boolean, name: String, value: JsonElement) = rethrowing {
        val cls = if (isStatic) target as Class<*> else target.javaClass
        val f = findField(cls, name)
        if (Modifier.isFinal(f.modifiers)) {
            // 尝试去掉 final（部分 Android 版本允许直接写）
            runCatching {
                val mods = Field::class.java.getDeclaredField("accessFlags")
                mods.isAccessible = true
                mods.setInt(f, f.modifiers and Modifier.FINAL.inv())
            }
        }
        f.set(if (Modifier.isStatic(f.modifiers)) null else target, decodeAs(value, f.type))
    }

    private fun findInstanceFieldOrNull(cls: Class<*>, name: String): Field? {
        hierarchy(cls).forEach { c ->
            c.declaredFields.firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }?.let {
                runCatching { it.isAccessible = true }
                return it
            }
        }
        return null
    }

    private fun readPropertyOrField(cls: Class<*>, target: Any, name: String): Any? {
        findInstanceFieldOrNull(cls, name)?.let { return it.get(target) }
        val getterName = "get" + name.replaceFirstChar { it.uppercaseChar() }
        methodsNamed(cls, getterName).firstOrNull { it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
            ?.let { return it.invoke(target) }
        methodsNamed(cls, name).firstOrNull { it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
            ?.let { return it.invoke(target) }
        throw NoSuchFieldException("${cls.name} 无法读取属性 $name")
    }

    /**
     * 按字段名无损浅拷贝 Kotlin `data class` 实例（等价于源码里的 `instance.copy(field = newValue, ...)`）。
     * 通过编译期保留的 `MethodParameters` 元数据精确匹配主 `copy(...)` 参数顺序，不受 Android ART `getDeclaredFields()` 字段重排影响。
     */
    fun copyDataClass(target: Any, patch: JsonObject): Any = rethrowing {
        ensureHiddenApiAccess()
        val cls = target.javaClass
        require(cls != Class::class.java) { "java.copy 只能用于数据类实例，不能用于 Class 对象" }
        val candidates = cls.declaredMethods.filter {
            it.name == "copy" &&
                !Modifier.isStatic(it.modifiers) &&
                !it.isBridge &&
                !it.isSynthetic &&
                it.parameterCount > 0 &&
                it.returnType.isAssignableFrom(cls)
        }
        require(candidates.isNotEmpty()) { "${cls.name} 没有可用的 copy(...) 方法（非 Kotlin data class）" }
        val copyMethod = candidates.maxWithOrNull(
            compareBy<Method>({ m -> m.parameters.count { it.isNamePresent } }, { it.parameterCount })
        )!!
        runCatching { copyMethod.isAccessible = true }
        val params = copyMethod.parameters
        val paramNames: List<String> = if (params.isNotEmpty() && params.all { it.isNamePresent }) {
            params.map { it.name }
        } else {
            val instanceFields = hierarchy(cls).toList().asReversed()
                .flatMap { c -> c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.name.contains('$') } }
                .distinctBy { it.name }
            require(instanceFields.size == copyMethod.parameterCount) {
                "${cls.name}.copy 缺少 MethodParameters 元数据且实例字段数 (${instanceFields.size}) 与参数数 (${copyMethod.parameterCount}) 不一致"
            }
            instanceFields.map { it.name }
        }
        val paramNameSet = paramNames.toSet()
        val unknownKeys = patch.keys.filter { it !in paramNameSet }
        require(unknownKeys.isEmpty()) {
            "${cls.name}.copy 没有字段 ${unknownKeys.joinToString()}，可用：${paramNames.joinToString()}"
        }
        val paramTypes = copyMethod.parameterTypes
        val jargs = Array<Any?>(paramTypes.size) { i ->
            val pName = paramNames[i]
            val pType = paramTypes[i]
            if (patch.containsKey(pName)) {
                val raw = patch.getValue(pName)
                require(!(raw is JsonNull && pType.isPrimitive)) {
                    "${cls.name}.copy 字段 $pName (${pType.simpleName}) 为基本类型，不能传 null"
                }
                decodeAs(raw, pType)
            } else {
                readPropertyOrField(cls, target, pName)
            }
        }
        copyMethod.invoke(target, *jargs)!!
    }

    // ---------------------------------------------------------------------------------------------
    // 接口代理：Java 调用 -> JS 函数
    // ---------------------------------------------------------------------------------------------

    /**
     * 创建实现 [interfaces] 的 Java 动态代理，方法调用转发到 JS handler [handlerId]。
     * - 在其他线程上被调用时，默认同步等待 JS 返回（[wait] = true），以便返回值生效；
     * - 在插件自身 JS 线程上被调用时无法同步等待（会死锁），改为异步投递并返回默认值。
     */
    fun newProxy(interfaces: Array<Class<*>>, handlerId: String, wait: Boolean): Any {
        val loader = interfaces.firstOrNull()?.classLoader ?: PluginJavaBridge::class.java.classLoader
        val handler = InvocationHandler { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args?.getOrNull(0)
                method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
                method.declaringClass == Any::class.java && method.name == "toString" -> "JsProxy(${runtime.id}:$handlerId)@" +
                    Integer.toHexString(System.identityHashCode(proxy))

                else -> callJs(handlerId, method.name, args, method.returnType, wait)
            }
        }
        return Proxy.newProxyInstance(loader, interfaces, handler)
    }

    private val warnedSameThread = ConcurrentHashMap.newKeySet<String>()

    private fun warnSameThread(what: String) {
        if (warnedSameThread.add(what)) {
            runtime.hostLog(
                "warn",
                "$what 在插件 JS 线程上被同步触发（通常是 JS 用同步方式调用的 Java 代码又回调了 JS），" +
                    "此时无法等待 JS 返回，已按默认值处理。需要回调返回值时，请改用 obj.\$async(...) 发起这次 Java 调用。",
            )
        }
    }

    private fun callJs(handlerId: String, methodName: String, args: Array<out Any?>?, returnType: Class<*>, wait: Boolean): Any? {
        if (disposed) return defaultValue(returnType)
        if (wait && runtime.isJsThread()) warnSameThread("接口回调 $methodName")
        val created = ArrayList<Long>()
        return try {
            val encodedArgs = JsonArray((args ?: emptyArray()).map { encode(it, created) })
            val result = try {
                runtime.invokeFromJava(
                    handlerId,
                    listOf(JsonPrimitive(methodName), encodedArgs),
                    wait = wait,
                )
            } catch (e: Throwable) {
                runtime.hostLog("error", "Java 代理回调 $methodName 失败: ${e.message}")
                null
            }
            if (result == null || returnType == Void.TYPE) {
                defaultValue(returnType)
            } else {
                runCatching { decodeAs(result, returnType) }.getOrElse {
                    runtime.hostLog("error", "Java 代理回调 $methodName 返回值无法转换为 ${returnType.name}: ${it.message}")
                    defaultValue(returnType)
                }
            }
        } finally {
            created.forEach(::release)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 方法 Hook（Pine）
    // ---------------------------------------------------------------------------------------------

    class HookFrame(val member: Member, val frame: Any)

    fun hook(
        cls: Class<*>,
        rawName: String,
        paramTypes: List<Class<*>>?,
        handlerId: String,
        wantBefore: Boolean,
        wantAfter: Boolean,
        async: Boolean,
        timeoutMillis: Long?,
    ): JsonElement {
        val members: List<Member> = if (rawName == "<init>" || rawName == "constructor") {
            cls.declaredConstructors.filter { paramTypes == null || it.parameterTypes.toList() == paramTypes }
        } else {
            val (name, explicit) = parseSignature(rawName)
            val types = explicit ?: paramTypes
            hierarchy(cls).first().declaredMethods
                .filter { it.name == name && (types == null || it.parameterTypes.toList() == types) }
                .ifEmpty {
                    methodsNamed(cls, name).filter { types == null || it.parameterTypes.toList() == types }
                }
        }
        require(members.isNotEmpty()) { "找不到要 Hook 的成员 ${cls.name}.$rawName${paramTypes?.let { "(${it.joinToString(",") { t -> t.name }})" } ?: ""}" }
        val hookId = "hook#${hookSeq.incrementAndGet()}"
        val unhooks = members.map { member ->
            PluginHookEngine.hook(
                member = member,
                before = if (wantBefore) { frame -> dispatchHook("before", member, frame, handlerId, async, timeoutMillis) } else null,
                after = if (wantAfter) { frame -> dispatchHook("after", member, frame, handlerId, async, timeoutMillis) } else null,
            )
        }
        hooks[hookId] = unhooks
        if (!PluginHookEngine.jitInlineDisabled) {
            runtime.hostLog("warn", "未能关闭 JIT 内联：被内联进调用方的方法可能不触发 Hook，可对调用方使用 rikka.hook.deoptimize")
        }
        return buildJsonObject {
            put("id", hookId)
            put("count", members.size)
            put("members", JsonArray(members.map { JsonPrimitive(describe(it)) }))
        }
    }

    fun unhook(hookId: String): Boolean {
        val list = hooks.remove(hookId) ?: return false
        list.forEach { runCatching { it() } }
        return true
    }

    fun hookCount(): Int = hooks.size

    private fun dispatchHook(
        phase: String,
        member: Member,
        frame: PluginHookEngine.Frame,
        handlerId: String,
        async: Boolean,
        timeoutMillis: Long?,
    ) {
        if (disposed) return
        if (runtime.isJsThread()) {
            // 插件自己在 JS 线程里同步触发了被 Hook 的方法：无法同步回调自身，直接放行原方法
            warnSameThread("Hook ${describe(member)}")
            return
        }
        val created = ArrayList<Long>()
        val token = "f${frameSeq.incrementAndGet()}"
        val argTypes = (member as? Executable)?.parameterTypes ?: emptyArray()
        val payload = buildJsonObject {
            put("phase", phase)
            put("token", token)
            put("method", describe(member))
            put("self", encode(frame.thisObject, created))
            put("args", JsonArray(frame.args.map { encode(it, created) }))
            if (phase == "after") {
                put("result", encode(frame.result, created))
                put("throwable", frame.throwable?.let { JsonPrimitive("${it.javaClass.name}: ${it.message}") } ?: JsonNull)
            }
        }
        activeFrames[token] = HookFrame(member, frame)
        val out = try {
            runtime.invokeFromJava(handlerId, listOf(JsonPrimitive(phase), payload), wait = !async, timeoutMillis = timeoutMillis)
        } catch (e: Throwable) {
            runtime.hostLog("error", "Hook ${describe(member)} [$phase] 回调失败: ${e.message}")
            null
        } finally {
            activeFrames.remove(token)
        }
        try {
            val obj = out as? JsonObject ?: return
            (obj["args"] as? JsonArray)?.let { newArgs ->
                newArgs.forEachIndexed { i, a ->
                    if (i < frame.args.size) frame.args[i] = decodeAs(a, argTypes.getOrElse(i) { Any::class.java })
                }
            }
            obj["throwable"]?.jsonPrimitive?.contentOrNull?.let { msg ->
                frame.setThrowable(RuntimeException(msg))
                return
            }
            if (obj["hasResult"]?.jsonPrimitive?.booleanOrNull == true) {
                val rt = (member as? Method)?.returnType ?: Void.TYPE
                frame.setResult(if (rt == Void.TYPE) null else decodeAs(obj["result"] ?: JsonNull, rt))
            }
        } catch (e: Throwable) {
            runtime.hostLog("error", "Hook ${describe(member)} [$phase] 应用修改失败: ${e.message}")
        } finally {
            created.forEach(::release)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // JS 入口：同步与异步
    // ---------------------------------------------------------------------------------------------

    private fun JsonElement.obj() = jsonObject
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.toList() ?: emptyList()
    private fun JsonObject.target(): Any = this@PluginJavaBridge.get(long("h") ?: throw IllegalArgumentException("缺少句柄 h"))

    fun callSync(name: String, args: JsonElement): JsonElement {
        ensureHiddenApiAccess()
        val o = args.jsonObject
        return when (name) {
            "java.use" -> encode(findClass(o.str("name") ?: error("java.use: 缺少类名")))
            "java.call" -> encode(invokeMethod(o.target(), o.bool("static"), o.str("name")!!, o.arr("args")))
            "java.new" -> {
                val cls = o.target() as? Class<*> ?: throw IllegalArgumentException("java.new: 目标不是类")
                val sig = o.str("signature")?.let { s -> if (s.isBlank()) emptyList() else s.split(',').map { findClass(it.trim()) } }
                encode(newInstance(cls, o.arr("args"), sig))
            }

            "java.get" -> encode(getField(o.target(), o.bool("static"), o.str("field")!!))
            "java.set" -> {
                setField(o.target(), o.bool("static"), o.str("field")!!, o["value"] ?: JsonNull)
                JsonNull
            }
            "java.copy" -> encode(copyDataClass(o.target(), o["patch"]?.jsonObject ?: JsonObject(emptyMap())))

            "java.toString" -> JsonPrimitive(o.target().toString())
            "java.json" -> toJson(o.target())
            "java.release" -> {
                o.long("h")?.let(::release)
                JsonNull
            }

            "java.releaseAll" -> {
                synchronized(lock) {
                    handles.clear()
                    ids.clear()
                }
                JsonNull
            }

            "java.stats" -> buildJsonObject {
                put("handles", handleCount())
                put("hooks", hookCount())
            }

            "java.instanceOf" -> JsonPrimitive(findClass(o.str("cls")!!).isInstance(o.target()))
            "java.className" -> JsonPrimitive(o.target().javaClass.name)
            "java.array" -> {
                val comp = findClass(o.str("type")!!)
                val values = o.arr("values")
                val arr = JArray.newInstance(comp, values.size)
                values.forEachIndexed { i, v -> JArray.set(arr, i, decodeAs(v, comp)) }
                encode(arr)
            }

            "java.proxy" -> {
                val ifaces = o.arr("interfaces").map { findClass(it.jsonPrimitive.content) }.toTypedArray()
                require(ifaces.isNotEmpty() && ifaces.all { it.isInterface }) { "java.proxy: 只能实现接口" }
                encode(newProxy(ifaces, o.str("hid")!!, wait = !o.bool("async")))
            }

            "java.members" -> {
                val target = o.target()
                val cls = if (o.bool("static")) target as Class<*> else target.javaClass
                buildJsonObject {
                    put("class", cls.name)
                    put("methods", buildJsonArray {
                        hierarchy(cls).flatMap { it.declaredMethods.asSequence() }.map { describe(it) }.distinct()
                            .forEach { add(JsonPrimitive(it)) }
                    })
                    put("fields", buildJsonArray {
                        hierarchy(cls).flatMap { it.declaredFields.asSequence() }
                            .forEach { add(JsonPrimitive("${it.type.typeName} ${it.declaringClass.name}.${it.name}")) }
                    })
                    put("constructors", buildJsonArray {
                        cls.declaredConstructors.forEach { add(JsonPrimitive(describe(it))) }
                    })
                }
            }

            "java.hook" -> {
                val cls = findClass(o.str("cls")!!)
                val params = (o["params"] as? JsonArray)?.map { findClass(it.jsonPrimitive.content) }
                hook(
                    cls = cls,
                    rawName = o.str("name")!!,
                    paramTypes = params,
                    handlerId = o.str("hid")!!,
                    wantBefore = o.bool("before"),
                    wantAfter = o.bool("after"),
                    async = o.bool("async"),
                    timeoutMillis = o.long("timeoutMs"),
                )
            }

            "java.unhook" -> JsonPrimitive(unhook(o.str("id")!!))
            "java.callOriginal" -> {
                val token = o.str("token")!!
                val hf = activeFrames[token] ?: throw IllegalStateException("callOriginal 只能在 Hook 回调执行期间调用")
                val member = hf.member
                val types = (member as? Executable)?.parameterTypes ?: emptyArray()
                val frame = hf.frame as PluginHookEngine.Frame
                val self = if (o.containsKey("self")) decode(o["self"]!!) else frame.thisObject
                val callArgs = (o["args"] as? JsonArray)?.mapIndexed { i, a -> decodeAs(a, types.getOrElse(i) { Any::class.java }) }
                    ?.toTypedArray() ?: frame.args.copyOf()
                encode(rethrowing { frame.invokeOriginal(self, callArgs) })
            }

            "java.deoptimize" -> {
                val cls = findClass(o.str("cls")!!)
                val name = o.str("name")!!
                val targets: List<Member> = if (name == "<init>") cls.declaredConstructors.toList()
                else methodsNamed(cls, name)
                JsonPrimitive(targets.count { PluginHookEngine.deoptimize(it) })
            }

            else -> throw IllegalArgumentException("未知 Java 桥接口: $name")
        }
    }

    /**
     * 异步执行，JS 侧得到 Promise：
     * - `java.main.*`：切到主线程执行（操作界面 / 必须在主线程调用的 API）；
     * - `java.async.*`：在后台线程执行。执行期间插件 JS 线程是空闲的，所以 Java 代码同步回调 JS
     *   （比较器、监听器、Hook 等）都能正常拿到返回值。
     */
    suspend fun callAsync(name: String, args: JsonElement): JsonElement = when {
        name.startsWith("java.main.") -> runOnMain { callSync("java." + name.removePrefix("java.main."), args) }
        name.startsWith("java.async.") -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            callSync("java." + name.removePrefix("java.async."), args)
        }

        else -> throw IllegalArgumentException("未知 Java 异步接口: $name")
    }

    private suspend fun <T> runOnMain(block: () -> T): T {
        val mainLooper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == mainLooper) return block()
        return suspendCancellableCoroutine { cont ->
            android.os.Handler(mainLooper).post {
                try {
                    cont.resume(block())
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }
        }
    }

    companion object {
        private const val MAX_SAFE_INTEGER = 9007199254740991L
        private const val MAX_JSON_DEPTH = 12

        @Volatile
        private var hiddenApiDone = false

        private val PRIMITIVES = mapOf(
            "int" to Integer.TYPE, "long" to java.lang.Long.TYPE, "boolean" to java.lang.Boolean.TYPE,
            "double" to java.lang.Double.TYPE, "float" to java.lang.Float.TYPE, "short" to java.lang.Short.TYPE,
            "byte" to java.lang.Byte.TYPE, "char" to Character.TYPE, "void" to Void.TYPE,
        )

        fun defaultValue(type: Class<*>): Any? = when (type) {
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Boolean.TYPE -> false
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            Character.TYPE -> '\u0000'
            else -> null
        }

        private fun Class<*>.boxed(): Class<*> = if (!isPrimitive) this else when (this) {
            Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            Character.TYPE -> java.lang.Character::class.java
            else -> this
        }
    }
}
