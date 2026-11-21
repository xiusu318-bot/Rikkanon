package me.rerere.rikkahub.ui.custom

import androidx.compose.runtime.Composer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JModifier

/** 找到的宿主界面函数：[paramTypes] 是它自己的参数（不含 Compose 编译器追加的那几个）。 */
internal class UIComposeTarget(
    val method: Method,
    val instance: Any?,
    val paramTypes: List<Class<*>>,
    val description: String,
) {
    // Compose 编译器在函数自己的参数后面追加：Composer、若干个 changed 整数（每个管 10 个参数）、
    // 有默认值时再加若干个 default 整数（每个管 31 个参数，某一位为 1 表示该参数用默认值）
    private val total = method.parameterTypes.size
    private val real = paramTypes.size
    private val changedCount = if (real == 0) 1 else (real + (if (instance != null) 1 else 0) + 9) / 10
    private val defaultCount = total - real - 1 - changedCount
    val hasDefaults: Boolean = defaultCount > 0
    /** 编译后方法名是否带内联类型后缀（如 `QRCode-jA1GFJw`）：只有带后缀的方法其 `long` 参数才可能是内联 `Color`。 */
    val hasInlineMangle: Boolean = method.name.contains('-')

    init {
        require(defaultCount == 0 || defaultCount == (real + 30) / 31) { "$description 的参数布局不是界面函数的形式" }
    }

    /**
     * 在 [composer] 里调用。[args] 与函数自己的参数一一对应；函数带默认参数时 null = 用该参数的默认值。
     */
    fun invoke(composer: Composer, args: Array<Any?>): Any? {
        if (!hasDefaults && args.size < real) {
            throw IllegalArgumentException("$description 没有默认参数，必须传满 $real 个参数（实际传了 ${args.size} 个）")
        }
        val full = arrayOfNulls<Any?>(total)
        for (i in 0 until real) {
            val v = args.getOrNull(i)
            if (v == null && !hasDefaults && paramTypes[i].isPrimitive) {
                throw IllegalArgumentException("$description 的第 ${i + 1} 个参数（${paramTypes[i].simpleName}）没有默认值，不能传 null")
            }
            full[i] = v ?: zeroOf(paramTypes[i])
        }
        full[real] = composer
        // changed 全 0 = 「不确定」，由函数自己比较参数决定是否跳过重绘
        for (i in 0 until changedCount) full[real + 1 + i] = 0
        for (d in 0 until defaultCount) {
            var mask = 0
            for (bit in 0 until 31) {
                val idx = d * 31 + bit
                if (idx < real && args.getOrNull(idx) == null) mask = mask or (1 shl bit)
            }
            full[real + 1 + changedCount + d] = mask
        }
        return method.invoke(instance, *full)
    }

    private fun zeroOf(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> 0.toChar()
        else -> null
    }
}

/**
 * `Compose(类名, 函数名, [参数])` 的查找与参数转换（纯反射，不涉及界面，便于单元测试）。
 *
 * 宿主不登记任何函数：按名字在类里找带 `Composer` 参数的方法。参数带颜色、尺寸等内联类型的函数，
 * 编译后名字会带后缀（如 `QRCode-jA1GFJw`），主题只写 `QRCode` 即可。
 */
internal object UIComposeResolver {
    private const val FUNCTION_PREFIX = "kotlin.jvm.functions.Function"

    fun describe(m: Method, real: List<Class<*>>): String =
        m.name.substringBefore('-') + real.joinToString(", ", "(", ")") { it.simpleName }

    private fun realParams(m: Method): List<Class<*>>? {
        val types = m.parameterTypes
        val i = types.lastIndexOf(Composer::class.java)
        if (i < 0 || i == types.lastIndex) return null
        if (!(i + 1 until types.size).all { types[it] == Int::class.javaPrimitiveType }) return null
        return types.take(i)
    }

    private fun methodHasDefaults(m: Method, real: Int): Boolean {
        val isStatic = JModifier.isStatic(m.modifiers)
        val changedCount = if (real == 0) 1 else (real + (if (isStatic) 0 else 1) + 9) / 10
        return m.parameterTypes.size - real - 1 - changedCount > 0
    }

    /** 不看具体值，只看这一项「可不可能」传给该类型（用来在重名函数里挑一个）。 */
    private fun compatible(el: JsonElement, type: Class<*>, hasDefaults: Boolean, hasInlineMangle: Boolean): Boolean = when {
        el is JsonNull -> hasDefaults || !type.isPrimitive
        el is JsonObject && "\$cb" in el -> type.isInterface
        el is JsonObject && "\$child" in el -> type.name.startsWith(FUNCTION_PREFIX)
        el is JsonObject && "\$j" in el -> !type.isPrimitive
        el is JsonObject && "\$long" in el -> type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java || type == Any::class.java
        el is JsonPrimitive && el.isString ->
            type == String::class.java || type == CharSequence::class.java || type == Any::class.java || type.isEnum ||
                (type == Long::class.javaPrimitiveType && hasInlineMangle) || type == Color::class.java ||
                type == Char::class.javaPrimitiveType
        el is JsonPrimitive && el.booleanOrNull != null ->
            type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java || type == Any::class.java
        el is JsonPrimitive -> (type.isPrimitive && type != Boolean::class.javaPrimitiveType && type != Char::class.javaPrimitiveType) ||
            Number::class.java.isAssignableFrom(type) || type == Dp::class.java || type == Any::class.java
        else -> !type.isPrimitive
    }

    fun resolve(className: String, fnName: String, args: JsonArray, findClass: (String) -> Class<*>): UIComposeTarget {
        val cls = try {
            findClass(className)
        } catch (_: Throwable) {
            throw IllegalArgumentException("找不到类 $className（顶层函数所在的类是「包名.文件名Kt」）")
        }
        val named = cls.declaredMethods.filter { m ->
            !m.isSynthetic && !m.isBridge && (m.name == fnName || m.name.startsWith("$fnName-"))
        }
        val candidates = named.mapNotNull { m -> realParams(m)?.let { m to it } }
        if (candidates.isEmpty()) {
            val hint = if (named.isNotEmpty()) "它不是界面函数（没有 Composer 与 changed 参数）" else {
                val all = cls.declaredMethods.filter { !it.isSynthetic && realParams(it) != null }
                    .map { it.name.substringBefore('-') }.distinct().sorted()
                if (all.isEmpty()) "这个类里没有界面函数" else "这个类里的界面函数：${all.joinToString()}"
            }
            throw IllegalArgumentException("$className 里没有界面函数 $fnName：$hint")
        }
        val fits = candidates.filter { (m, real) ->
            val hasDef = methodHasDefaults(m, real.size)
            val mangle = m.name.contains('-')
            val sizeOk = if (hasDef) real.size >= args.size else real.size == args.size
            sizeOk && args.indices.all { compatible(args[it], real[it], hasDef, mangle) }
        }
        val (method, real) = fits.minByOrNull { it.second.size } ?: run {
            val signatures = candidates.joinToString("；") { (m, real) -> describe(m, real) }
            throw IllegalArgumentException("$fnName 的参数对不上（传了 ${args.size} 个）。可用的写法：$signatures")
        }
        val instance = if (JModifier.isStatic(method.modifiers)) null else {
            runCatching { cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) }.getOrNull()
                ?: throw IllegalArgumentException("$fnName 是类的成员函数，只支持顶层函数与 object 里的函数")
        }
        method.isAccessible = true
        return UIComposeTarget(method, instance, real, describe(method, real))
    }

    /** 数字 / 字符串 / 布尔按目标类型转换；[color] 把颜色字符串（#hex 或语义色名）转成 Compose 的颜色值。 */
    fun convertPrimitive(
        el: JsonPrimitive,
        type: Class<*>,
        what: String,
        allowInlineColor: Boolean = true,
        color: (String) -> Long?,
    ): Any? {
        fun mismatch(): Nothing = throw IllegalArgumentException("$what 需要 ${type.simpleName}，实际传了 $el")
        if (el.isString) {
            val text = el.content
            return when {
                type == String::class.java || type == CharSequence::class.java || type == Any::class.java -> text
                type.isEnum -> type.enumConstants!!.firstOrNull { (it as Enum<*>).name == text }
                    ?: throw IllegalArgumentException("$what 的取值只能是 ${type.enumConstants!!.joinToString(" / ") { (it as Enum<*>).name }}，实际为 $text")
                type == Color::class.java -> {
                    val bits = color(text) ?: throw IllegalArgumentException("$what 不是合法颜色：$text")
                    Color(bits.toULong())
                }
                // 内联颜色（Color）编译后是 long，方法名必带 '-' 后缀
                type == Long::class.javaPrimitiveType && allowInlineColor ->
                    color(text) ?: throw IllegalArgumentException("$what 不是合法颜色：$text")
                type == Char::class.javaPrimitiveType && text.length == 1 -> text[0]
                else -> mismatch()
            }
        }
        el.booleanOrNull?.let { b ->
            return if (type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java || type == Any::class.java) b else mismatch()
        }
        val d = el.doubleOrNull ?: mismatch()
        val whole = el.longOrNull
        return when (type) {
            Int::class.javaPrimitiveType, java.lang.Integer::class.java -> whole?.toInt() ?: mismatch()
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> whole ?: mismatch()
            Float::class.javaPrimitiveType, java.lang.Float::class.java -> d.toFloat()
            Dp::class.java -> Dp(d.toFloat())
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> d
            Short::class.javaPrimitiveType, java.lang.Short::class.java -> whole?.toShort() ?: mismatch()
            Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> whole?.toByte() ?: mismatch()
            Any::class.java, Number::class.java -> if (whole != null && whole in Int.MIN_VALUE..Int.MAX_VALUE) whole.toInt() else whole ?: d
            else -> mismatch()
        }
    }

    /** 界面内容参数（`@Composable (…) -> Unit`）除了编译器追加的两个参数外还带几个参数；不是函数类型时返回 null。 */
    fun contentArity(type: Class<*>): Int? =
        type.name.takeIf { it.startsWith(FUNCTION_PREFIX) }?.removePrefix(FUNCTION_PREFIX)?.toIntOrNull()?.minus(2)?.takeIf { it >= 0 }

    /** 参数的「形状」：回调与界面内容只记种类（编号每次渲染都会变，不应让宿主控件跟着重建）。 */
    fun signature(args: JsonArray): String = args.joinToString("\u0001") { el ->
        when {
            el is JsonObject && "\$cb" in el -> "cb"
            el is JsonObject && "\$child" in el -> "child:" + el["\$child"]
            else -> el.toString()
        }
    }
}
