package me.rerere.rikkahub.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeCompilerApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.UIMessageNode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.cancellation.CancellationException

/** 准备好的一次调用：[values] 与函数自己的参数一一对应（null = 用默认值）；[modifierIndex] 处由节点自己的修饰符填入。 */
internal class UIComposeCall(val target: UIComposeTarget, val values: Array<Any?>, val modifierIndex: Int)

/**
 * 界面函数执行到一半抛异常时，把 Compose 运行时内部的几个「进行中」记录恢复到调用之前。
 *
 * 公开接口 `endToMarker` 只回退界面树的分组；被调函数已经登记的重绘范围、CompositionLocal 的进出记录不在其内，
 * 不恢复的话外层那一块以后不再随状态变化重绘。这几项是运行时的私有字段，只能按名字反射
 * （正式包里靠 proguard-rules.pro 保留字段名）。取不到时（Compose 升级后改了内部实现）[snapshot] 返回 null，
 * 调用处就不接异常、照常抛出。
 */
internal object UIComposerRecovery {
    private class Fields(
        val invalidateStack: java.lang.reflect.Field,
        val providersInvalidStack: java.lang.reflect.Field,
        val providersInvalid: java.lang.reflect.Field,
        val providerCache: java.lang.reflect.Field,
        val intStackTop: java.lang.reflect.Field,
    )

    class Snapshot internal constructor(val scopes: Int, val providersTop: Int, val providersInvalid: Boolean)

    @Volatile
    private var cached: Pair<Class<*>, Fields?>? = null

    private fun fieldsOf(cls: Class<*>): Fields? {
        cached?.let { if (it.first === cls) return it.second }
        val fields = runCatching {
            fun field(owner: Class<*>, name: String) = generateSequence(owner) { it.superclass }
                .firstNotNullOf { c -> runCatching { c.getDeclaredField(name) }.getOrNull() }
                .apply { isAccessible = true }
            val invalidate = field(cls, "invalidateStack")
            val stack = field(cls, "providersInvalidStack")
            require(java.util.List::class.java.isAssignableFrom(invalidate.type))
            Fields(invalidate, stack, field(cls, "providersInvalid"), field(cls, "providerCache"), field(stack.type, "tos"))
        }.getOrNull()
        cached = cls to fields
        return fields
    }

    fun snapshot(composer: androidx.compose.runtime.Composer): Snapshot? {
        val f = fieldsOf(composer.javaClass) ?: return null
        return Snapshot(
            scopes = (f.invalidateStack.get(composer) as java.util.List<*>).size,
            providersTop = f.intStackTop.getInt(f.providersInvalidStack.get(composer)),
            providersInvalid = f.providersInvalid.getBoolean(composer),
        )
    }

    fun restore(composer: androidx.compose.runtime.Composer, snapshot: Snapshot) {
        val f = fieldsOf(composer.javaClass) ?: return
        @Suppress("UNCHECKED_CAST")
        val scopes = f.invalidateStack.get(composer) as MutableList<Any?>
        while (scopes.size > snapshot.scopes) scopes.removeAt(scopes.size - 1)
        f.intStackTop.setInt(f.providersInvalidStack.get(composer), snapshot.providersTop)
        f.providersInvalid.setBoolean(composer, snapshot.providersInvalid)
        f.providerCache.set(composer, null)
    }
}

/**
 * 在界面绘制过程中反射调用界面函数，并尽力接住它执行中抛出的异常：把界面树的记录回退到调用之前，交给 [onFailure]。
 * 调用之后才出现的错误（延后生成的内容、排版与绘制、控件自己的后台任务、点击处理）不在这里，接不住。
 */
@OptIn(ComposeCompilerApi::class)
@Composable
internal fun UIGuardedComposableCall(call: UIComposeCall, args: Array<Any?>, onFailure: (Throwable) -> Unit) {
    val composer = currentComposer
    val marker = composer.currentMarker
    val before = UIComposerRecovery.snapshot(composer)
    var caught: Throwable? = null
    try {
        call.target.invoke(composer, args)
    } catch (e: Throwable) {
        val cause = if (e is InvocationTargetException) e.targetException ?: e else e
        // 恢复不了运行时内部记录时不接：接了外层会停止重绘，不如照常抛出（崩溃后可在安全模式里关掉界面自定义）
        if (cause is CancellationException || before == null) throw cause
        composer.endToMarker(marker)
        UIComposerRecovery.restore(composer, before)
        caught = cause
    }
    val failed = caught
    if (failed != null) {
        // 绘制结束后再通知：在第一次绘制过程中直接改状态不会触发重绘（所在的重绘范围还没建好）
        SideEffect { onFailure(failed) }
    }
}

/** 主题函数 → 宿主回调：按参数类型（接口）生成代理，被调用时回到主题执行；回调编号每次按节点当前的参数取。 */
private fun callbackProxy(type: Class<*>, index: Int, node: State<UIMessageNode>, host: UINodeHost): Any {
    val loader = type.classLoader ?: UIComposeCall::class.java.classLoader
    return Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, m, a ->
        when {
            m.name == "equals" && a?.size == 1 -> proxy === a[0]
            m.name == "hashCode" && a.isNullOrEmpty() -> System.identityHashCode(proxy)
            m.name == "toString" && a.isNullOrEmpty() -> "UIThemeCallback#$index"
            else -> {
                val id = node.value.args!![index].jsonObject.getValue("\$cb").jsonPrimitive.content
                host.callbackArgs(id, a?.toList().orEmpty())
                // 主题回调在主题线程异步执行，拿不到返回值：一律按「无返回值」处理
                when (m.returnType) {
                    Void.TYPE -> null
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    Short::class.javaPrimitiveType -> 0.toShort()
                    Byte::class.javaPrimitiveType -> 0.toByte()
                    Char::class.javaPrimitiveType -> 0.toChar()
                    Any::class.java -> Unit
                    else -> null
                }
            }
        }
    }
}

/** 主题节点树 → 界面内容参数：每次绘制读取节点当前的第 [childIndex] 个子节点。 */
private fun contentLambda(type: Class<*>, index: Int, childIndex: Int, node: State<UIMessageNode>, host: UINodeHost): Any {
    val body: @Composable () -> Unit = {
        RenderUINode(node.value.children[childIndex], host)
    }
    return when (UIComposeResolver.contentArity(type)) {
        0 -> body
        1 -> { val f: @Composable (Any?) -> Unit = { _ -> body() }; f }
        2 -> { val f: @Composable (Any?, Any?) -> Unit = { _, _ -> body() }; f }
        3 -> { val f: @Composable (Any?, Any?, Any?) -> Unit = { _, _, _ -> body() }; f }
        else -> throw IllegalArgumentException("第 ${index + 1} 个参数的类型是 ${type.simpleName}，不能传节点树（界面内容）")
    }
}

private fun buildComposeCall(
    target: UIComposeTarget,
    args: JsonArray,
    runtime: UIThemeRuntime?,
    colorScheme: ColorScheme,
    node: State<UIMessageNode>,
    host: UINodeHost,
): UIComposeCall {
    val values = arrayOfNulls<Any?>(target.paramTypes.size)
    args.forEachIndexed { i, el ->
        val type = target.paramTypes[i]
        val what = "${target.description} 的第 ${i + 1} 个参数"
        values[i] = when {
            el is JsonNull -> {
                if (!target.hasDefaults && type.isPrimitive) {
                    throw IllegalArgumentException("$what（${type.simpleName}）没有默认值，不能传 null")
                }
                null
            }
            el is JsonObject && "\$cb" in el -> callbackProxy(type, i, node, host)
            el is JsonObject && "\$child" in el -> contentLambda(type, i, el.getValue("\$child").jsonPrimitive.content.toInt(), node, host)
            el is JsonObject && "\$j" in el -> {
                val rt = runtime ?: throw IllegalStateException("$what 是 Java 对象，只能用在按数据渲染的位置")
                rt.javaObject(el.getValue("\$j").jsonPrimitive.content.toLong()).also { obj ->
                    require(type.isInstance(obj)) { "$what 需要 ${type.simpleName}，实际传了 ${obj.javaClass.name}" }
                }
            }
            el is JsonObject && "\$long" in el -> el.getValue("\$long").jsonPrimitive.content.toLong()
            el is JsonPrimitive -> UIComposeResolver.convertPrimitive(el, type, what, allowInlineColor = target.hasInlineMangle) { text ->
                resolveUIColor(text, colorScheme).takeIf { it != Color.Unspecified }?.value?.toLong()
            }
            else -> (runtime ?: throw IllegalStateException("$what 是数组或对象，只能用在按数据渲染的位置")).decodeAs(el, type)
        }
    }
    val modifierIndex = target.paramTypes.indices.firstOrNull { i ->
        target.paramTypes[i] == Modifier::class.java && values[i] == null
    } ?: -1
    return UIComposeCall(target, values, modifierIndex)
}

/**
 * `Compose(类名, 函数名, [参数], 属性?)`：把宿主的任意界面函数放到主题的页面里。
 * 找不到、参数对不上或函数执行中抛错时画 [UIMessageNode.fallback]（没有就不画）并记入运行时错误。
 */
@Composable
internal fun UIComposeNode(node: UIMessageNode, host: UINodeHost, modifier: Modifier) {
    val runtime = host.runtime
    val args = requireNotNull(node.args) { "Compose 节点缺少 args 参数数组" }
    val colorScheme = MaterialTheme.colorScheme
    val current = rememberUpdatedState(node)
    val signature = remember(args) { UIComposeResolver.signature(args) }
    val prepared = remember(node.composeClass, node.composeFn, signature, runtime, colorScheme) {
        runCatching {
            val target = UIComposeResolver.resolve(node.composeClass, node.composeFn, args) { name ->
                try {
                    Class.forName(name, true, UIComposeCall::class.java.classLoader)
                } catch (e: ClassNotFoundException) {
                    runtime?.findClass(name) ?: throw e
                }
            }
            buildComposeCall(target, args, runtime, colorScheme, current, host)
        }
    }
    var failure by remember(prepared) { mutableStateOf(prepared.exceptionOrNull()) }
    val error = failure
    if (error != null) {
        LaunchedEffect(error) {
            host.onError(node, IllegalStateException("Compose(\"${node.composeClass}\", \"${node.composeFn}\")：${error.message ?: error.javaClass.name}"))
        }
        val fallback = node.fallback
        if (fallback != null) RenderUINode(fallback, host, modifier)
    } else {
        val call = prepared.getOrThrow()
        if (call.modifierIndex >= 0) {
            val values = call.values.copyOf()
            values[call.modifierIndex] = modifier
            UIGuardedComposableCall(call, values) { failure = it }
        } else {
            Box(modifier) {
                UIGuardedComposableCall(call, call.values) { failure = it }
            }
        }
    }
}

private fun newView(cls: Class<*>, context: Context): View {
    val ctor = runCatching { cls.getDeclaredConstructor(Context::class.java) }.getOrNull()
    if (ctor != null) {
        ctor.isAccessible = true
        return ctor.newInstance(context) as View
    }
    val ctor2 = runCatching { cls.getDeclaredConstructor(Context::class.java, AttributeSet::class.java) }.getOrNull()
        ?: throw IllegalArgumentException("${cls.name} 没有 (Context) 或 (Context, AttributeSet) 构造函数")
    ctor2.isAccessible = true
    return ctor2.newInstance(context, null) as View
}

/**
 * `NativeView(类名, { onCreate, onRelease })`：宿主在主线程创建安卓视图并放到界面上，
 * 创建后把视图的 Java 句柄交给主题的 `onCreate(view, ctx)`（在里面用 `view.$main("方法", ...)` 配置），
 * 离开界面时调用 `onRelease(view, ctx)` 并回收句柄。
 */
@Composable
internal fun UINativeView(node: UIMessageNode, host: UINodeHost, modifier: Modifier) {
    val context = LocalContext.current
    val runtime = host.runtime
    val current by rememberUpdatedState(node)
    val latestHost by rememberUpdatedState(host)
    val created = remember(node.viewClass, context) {
        runCatching {
            val cls = try {
                Class.forName(node.viewClass, true, UIComposeCall::class.java.classLoader)
            } catch (e: ClassNotFoundException) {
                runtime?.findClass(node.viewClass) ?: throw e
            }
            require(View::class.java.isAssignableFrom(cls)) { "${node.viewClass} 不是安卓视图（android.view.View 的子类）" }
            try {
                newView(cls, context)
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }
    val error = created.exceptionOrNull()
    if (error != null) {
        LaunchedEffect(error) {
            host.onError(node, IllegalStateException("NativeView(\"${node.viewClass}\") 创建失败：${error.message ?: error.javaClass.name}"))
        }
        val fallback = node.fallback
        if (fallback != null) RenderUINode(fallback, host, modifier)
    } else {
        val view = created.getOrThrow()
        DisposableEffect(view, runtime) {
            val hasCallbacks = current.onCreateFn.isNotEmpty() || current.onReleaseFn.isNotEmpty()
            val handleId = if (hasCallbacks && runtime != null) runtime.retainHandle(view) else -1L
            if (current.onCreateFn.isNotEmpty()) {
                if (runtime != null) {
                    runtime.callArgsDetached(
                        callbackId = current.onCreateFn,
                        args = listOf(view),
                        onActions = { actions -> actions.forEach { latestHost.runAction(it.name, it.args) } },
                    )
                } else {
                    latestHost.onError(current, IllegalStateException("NativeView 的 onCreate 只能用在按数据渲染的位置"))
                }
            }
            onDispose {
                val releaseFn = current.onReleaseFn
                if (runtime != null && handleId >= 0L) {
                    if (releaseFn.isNotEmpty()) {
                        runtime.callArgsDetached(releaseFn, listOf(view)) { runtime.releaseHandleId(handleId) }
                    } else {
                        runtime.releaseHandleId(handleId)
                    }
                }
            }
        }
        AndroidView(factory = { view }, modifier = modifier)
    }
}
