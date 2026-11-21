package me.rerere.rikkahub.ui.custom

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.model.UIMessageNode

const val UI_SHADER_MAX_HISTORY = 16

/** 宿主每帧提供的标量/向量 uniform：名字 → 分量数。另有数组/采样器内建项见 [UI_SHADER_ALL_BUILTIN_NAMES]。 */
val UI_SHADER_BUILTIN_UNIFORMS: Map<String, Int> = mapOf("iResolution" to 2, "iTime" to 1, "iTouch" to 3)

val UI_SHADER_ALL_BUILTIN_NAMES: Set<String> = setOf("iResolution", "iTime", "iTouch", "iClicks", "iTrail", "content")

data class ShaderUniformSpec(
    val type: String,
    /** 基础元素分量数（float/half=1 .. float4/half4=4；其余如 shader 为 -1）。 */
    val elementComponents: Int,
    /** 数组声明长度（如 `float4 iClicks[8]` 为 8）；非数组为 null；空括号等非法写法为 -1。 */
    val arraySize: Int?,
) {
    val isShader: Boolean get() = type == "shader"
    val themeAssignableComponents: Int get() = if (arraySize == null && !isShader) elementComponents else -1
}

/**
 * 触摸与点击历史环形缓冲（零对象分配）：
 * - [recordPointerEvent] 在外层根容器或单节点上只观察、不消费手势；
 * - 支持多指连击：任一手指按下瞬间（`!previousPressed && pressed`）立即记入 [clicks]（最新在 0 号位）；
 * - 按住移动时按最小步进记入 [trail]（最新在 0 号位）；
 * - [wakeSeq] 供开启了 `wakeDuration` 的着色器在空闲 0 FPS 休眠后瞬间唤醒。
 */
class UIShaderTouchState {
    var rootX by mutableFloatStateOf(0f)
    var rootY by mutableFloatStateOf(0f)
    var down by mutableFloatStateOf(0f)
    var wakeSeq by mutableIntStateOf(0)
    var lastActiveNanos: Long = 0L
    var hasRecorded: Boolean = false
    private var activeObserverCount by mutableIntStateOf(0)

    val hasActiveObservers: Boolean get() = activeObserverCount > 0

    fun retain() {
        activeObserverCount++
    }

    fun release() {
        activeObserverCount = (activeObserverCount - 1).coerceAtLeast(0)
    }

    private val clickX = FloatArray(UI_SHADER_MAX_HISTORY)
    private val clickY = FloatArray(UI_SHADER_MAX_HISTORY)
    private val clickNanos = LongArray(UI_SHADER_MAX_HISTORY) { Long.MIN_VALUE }
    private val clickSeed = FloatArray(UI_SHADER_MAX_HISTORY)
    private var clickHead = 0
    private var clickSeq = 1

    private val trailX = FloatArray(UI_SHADER_MAX_HISTORY)
    private val trailY = FloatArray(UI_SHADER_MAX_HISTORY)
    private val trailNanos = LongArray(UI_SHADER_MAX_HISTORY) { Long.MIN_VALUE }
    private val trailStrength = FloatArray(UI_SHADER_MAX_HISTORY)
    private var trailHead = 0
    private var lastTrailX = Float.NaN
    private var lastTrailY = Float.NaN
    private var lastTrailNanos = 0L

    fun recordDown(x: Float, y: Float, nowNanos: Long) {
        hasRecorded = true
        rootX = x
        rootY = y
        down = 1f
        lastActiveNanos = nowNanos
        clickHead = (clickHead - 1 + UI_SHADER_MAX_HISTORY) % UI_SHADER_MAX_HISTORY
        clickX[clickHead] = x
        clickY[clickHead] = y
        clickNanos[clickHead] = nowNanos
        // 黄金分割低差异序列：每次点击得到均匀分散在 0..1 的确定性随机种子
        val s = (clickSeq++ * 0.6180339887f)
        clickSeed[clickHead] = s - s.toInt()
        pushTrail(x, y, nowNanos, 1f)
        wakeSeq++
    }

    fun recordMoveOrUp(x: Float, y: Float, anyPressed: Boolean, nowNanos: Long) {
        hasRecorded = true
        rootX = x
        rootY = y
        val wasDown = down > 0.5f
        down = if (anyPressed) 1f else 0f
        lastActiveNanos = nowNanos
        if (anyPressed) {
            val dx = x - lastTrailX
            val dy = y - lastTrailY
            if (lastTrailX.isNaN() || dx * dx + dy * dy >= 16f || nowNanos - lastTrailNanos >= 12_000_000L) {
                pushTrail(x, y, nowNanos, 1f)
            }
        } else if (wasDown) {
            wakeSeq++
        }
    }

    private fun pushTrail(x: Float, y: Float, nowNanos: Long, strength: Float) {
        trailHead = (trailHead - 1 + UI_SHADER_MAX_HISTORY) % UI_SHADER_MAX_HISTORY
        trailX[trailHead] = x
        trailY[trailHead] = y
        trailNanos[trailHead] = nowNanos
        trailStrength[trailHead] = strength
        lastTrailX = x
        lastTrailY = y
        lastTrailNanos = nowNanos
    }

    /** 填充 `float4 iClicks[count]`（最新在下标 0，未使用槽位时间为 -10000 秒）。 */
    fun fillClicks(out: FloatArray, count: Int, offsetX: Float, offsetY: Float, startNanos: Long) {
        val n = count.coerceIn(0, UI_SHADER_MAX_HISTORY)
        for (i in 0 until n) {
            val idx = (clickHead + i) % UI_SHADER_MAX_HISTORY
            val base = i * 4
            val t = clickNanos[idx]
            if (t == Long.MIN_VALUE) {
                out[base] = 0f
                out[base + 1] = 0f
                out[base + 2] = -10000f
                out[base + 3] = 0f
            } else {
                out[base] = clickX[idx] - offsetX
                out[base + 1] = clickY[idx] - offsetY
                out[base + 2] = (t - startNanos) / 1_000_000_000f
                out[base + 3] = clickSeed[idx]
            }
        }
    }

    /** 填充 `float4 iTrail[count]`（最新在下标 0，未使用槽位时间为 -10000 秒）。 */
    fun fillTrail(out: FloatArray, count: Int, offsetX: Float, offsetY: Float, startNanos: Long) {
        val n = count.coerceIn(0, UI_SHADER_MAX_HISTORY)
        for (i in 0 until n) {
            val idx = (trailHead + i) % UI_SHADER_MAX_HISTORY
            val base = i * 4
            val t = trailNanos[idx]
            if (t == Long.MIN_VALUE) {
                out[base] = 0f
                out[base + 1] = 0f
                out[base + 2] = -10000f
                out[base + 3] = 0f
            } else {
                out[base] = trailX[idx] - offsetX
                out[base + 1] = trailY[idx] - offsetY
                out[base + 2] = (t - startNanos) / 1_000_000_000f
                out[base + 3] = trailStrength[idx]
            }
        }
    }
}

val LocalUIShaderTouch = compositionLocalOf<UIShaderTouchState?> { null }

/** 在外层容器上只观察、不消费触摸事件，供全局底层/顶层着色器读取 `iTouch`、`iClicks`、`iTrail`。无需要触摸的活跃着色器时不挂手势监听。 */
fun Modifier.observeUIShaderTouch(state: UIShaderTouchState?, requireActiveObserver: Boolean = false): Modifier =
    if (state == null || (requireActiveObserver && !state.hasActiveObservers)) this else this.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val changes = event.changes
                if (changes.isEmpty()) continue
                val now = System.nanoTime()
                var hadNewDown = false
                for (i in changes.indices) {
                    val c = changes[i]
                    if (!c.previousPressed && c.pressed) {
                        state.recordDown(c.position.x, c.position.y, now)
                        hadNewDown = true
                    }
                }
                if (!hadNewDown) {
                    val active = changes.firstOrNull { it.pressed } ?: changes.first()
                    val anyPressed = changes.any { it.pressed }
                    state.recordMoveOrUp(active.position.x, active.position.y, anyPressed, now)
                }
            }
        }
    }

private val UNIFORM_DECL = Regex("""\buniform\s+(?:layout\s*\([^)]*\)\s*)?(\w+)\s+(\w+)\s*(?:\[\s*(\d*)\s*\])?\s*;""")

/** 解析全部声明的 uniform 规格（含数组长度与 `shader` 类型）。 */
fun parseShaderUniformSpecs(src: String): Map<String, ShaderUniformSpec> =
    UNIFORM_DECL.findAll(src).associate { m ->
        val type = m.groupValues[1]
        val name = m.groupValues[2]
        val hasBracket = m.groups[3] != null
        val arrSize = if (!hasBracket) null else m.groupValues[3].toIntOrNull() ?: -1
        val elemComp = when (type) {
            "float", "half" -> 1
            "float2", "half2", "vec2" -> 2
            "float3", "half3", "vec3" -> 3
            "float4", "half4", "vec4" -> 4
            else -> -1
        }
        name to ShaderUniformSpec(type = type, elementComponents = elemComp, arraySize = arrSize)
    }

/**
 * AGSL 源码里声明的 uniform：名字 → 主题可直接赋值的分量数（1..4；数组、shader 等不可由主题直接赋值的类型为 -1）。
 */
fun parseShaderUniforms(src: String): Map<String, Int> =
    parseShaderUniformSpecs(src).mapValues { it.value.themeAssignableComponents }

/**
 * 着色器节点的绘制效果：
 * - 普通着色器：返回 [ShaderNodeModifiers.background]（`drawBehind` 画在子节点下面）；
 * - 声明了 `uniform shader content;` 的后处理滤镜着色器：返回 [ShaderNodeModifiers.contentEffect]
 *   （`RenderEffect.createRuntimeShaderEffect(shader, "content")` 对子节点整体画面做实时像素折射/色散/波纹滤镜）。
 * - Android 13 以下或着色器出错时返回 null（由调用处渲染 `fallback` 与正常子节点）。
 */
data class ShaderNodeModifiers(
    val background: Modifier? = null,
    val contentEffect: Modifier = Modifier,
)

@Composable
fun rememberUIShaderModifiers(
    node: UIMessageNode,
    passthrough: Boolean,
    onError: (Throwable) -> Unit,
): ShaderNodeModifiers? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return rememberRuntimeShaderModifiers(node, passthrough, onError)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberRuntimeShaderModifiers(
    node: UIMessageNode,
    passthrough: Boolean,
    onError: (Throwable) -> Unit,
): ShaderNodeModifiers? {
    var failed by remember(node.shaderSrc, node.uniforms) { mutableStateOf(false) }
    val shader = remember(node.shaderSrc) {
        try {
            RuntimeShader(node.shaderSrc)
        } catch (e: Exception) {
            onError(IllegalArgumentException("着色器编译失败：${e.message}", e))
            null
        }
    }
    if (shader == null || failed) return null

    val specs = remember(node.shaderSrc) { parseShaderUniformSpecs(node.shaderSrc) }
    val hasTime = "iTime" in specs
    val hasTouch = "iTouch" in specs
    val hasResolution = "iResolution" in specs
    val clicksCount = specs["iClicks"]?.arraySize?.coerceIn(1, UI_SHADER_MAX_HISTORY) ?: 0
    val trailCount = specs["iTrail"]?.arraySize?.coerceIn(1, UI_SHADER_MAX_HISTORY) ?: 0
    val isContentFilter = specs["content"]?.isShader == true
    val needsPointer = hasTouch || clicksCount > 0 || trailCount > 0 || node.wakeDuration > 0f

    val startNanos = remember(shader) { System.nanoTime() }
    var elapsedNanos by remember(shader) { mutableLongStateOf(0L) }
    val localTouch = remember(shader) { UIShaderTouchState() }
    val globalTouch = LocalUIShaderTouch.current
    var nodeRootOffset by remember(shader) { mutableStateOf(Offset.Zero) }

    val clicksBuf = remember(shader, clicksCount) { if (clicksCount > 0) FloatArray(clicksCount * 4) else FloatArray(0) }
    val trailBuf = remember(shader, trailCount) { if (trailCount > 0) FloatArray(trailCount * 4) else FloatArray(0) }
    val themeUniforms = remember(node.uniforms) { node.uniforms.mapValues { it.value.toFloatArray() } }
    val shaderBrush = remember(shader, isContentFilter) { if (isContentFilter) null else ShaderBrush(shader) }

    DisposableEffect(globalTouch, needsPointer) {
        if (needsPointer && globalTouch != null) {
            globalTouch.retain()
            onDispose { globalTouch.release() }
        } else {
            onDispose {}
        }
    }

    if (hasTime) {
        val wakeNanos = (node.wakeDuration.coerceAtLeast(0f) * 1_000_000_000f).toLong()
        LaunchedEffect(shader, globalTouch, wakeNanos) {
            while (true) {
                val now = withFrameMillis { System.nanoTime() }
                elapsedNanos = now - startNanos
                if (wakeNanos > 0L) {
                    val lastActive = maxOf(localTouch.lastActiveNanos, globalTouch?.lastActiveNanos ?: 0L)
                    val isDown = localTouch.down > 0.5f || (globalTouch?.down ?: 0f) > 0.5f
                    if (!isDown && (lastActive == 0L || now - lastActive > wakeNanos)) {
                        // 已绘制完消散后的静止帧，挂起协程进入 0 FPS 休眠，直到下次触摸瞬间唤醒
                        val seqNow = (globalTouch?.wakeSeq ?: 0) + localTouch.wakeSeq
                        snapshotFlow { (globalTouch?.wakeSeq ?: 0) + localTouch.wakeSeq }
                            .first { it != seqNow }
                    }
                }
            }
        }
    }

    var trackMod: Modifier = Modifier
    if (needsPointer) {
        trackMod = trackMod.onGloballyPositioned { coords -> nodeRootOffset = coords.positionInRoot() }
        // 全局触摸已由根容器观察时不再给节点自身挂 pointerInput，确保前景层 100% 不拦截底层点击与列表滑动
        if (globalTouch == null && !passthrough) {
            trackMod = trackMod.observeUIShaderTouch(localTouch)
        }
    }

    fun bindUniforms(width: Float, height: Float) {
        if (hasResolution) shader.setFloatUniform("iResolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        if (hasTime) shader.setFloatUniform("iTime", elapsedNanos / 1_000_000_000f)
        val src = if (localTouch.hasRecorded && (localTouch.down > 0.5f || globalTouch == null)) localTouch else globalTouch
        val ox = if (src === globalTouch) nodeRootOffset.x else 0f
        val oy = if (src === globalTouch) nodeRootOffset.y else 0f
        // 读取 wakeSeq 保证未声明 iTime 的纯交互着色器在点击/松手时也会触发重绘
        val touchSeq = src?.wakeSeq ?: 0
        if (touchSeq < 0) return
        if (hasTouch) {
            if (src != null) {
                shader.setFloatUniform("iTouch", src.rootX - ox, src.rootY - oy, src.down)
            } else {
                shader.setFloatUniform("iTouch", 0f, 0f, 0f)
            }
        }
        if (clicksCount > 0) {
            if (src != null) {
                src.fillClicks(clicksBuf, clicksCount, ox, oy, startNanos)
            } else {
                for (i in 0 until clicksCount) {
                    clicksBuf[i * 4 + 2] = -10000f
                }
            }
            shader.setFloatUniform("iClicks", clicksBuf)
        }
        if (trailCount > 0) {
            if (src != null) {
                src.fillTrail(trailBuf, trailCount, ox, oy, startNanos)
            } else {
                for (i in 0 until trailCount) {
                    trailBuf[i * 4 + 2] = -10000f
                }
            }
            shader.setFloatUniform("iTrail", trailBuf)
        }
        themeUniforms.forEach { (name, v) -> shader.setFloatUniform(name, v) }
    }

    return if (isContentFilter) {
        val effectMod = trackMod.graphicsLayer {
            val w = size.width
            val h = size.height
            if (w > 0f && h > 0f) {
                try {
                    bindUniforms(w, h)
                    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                } catch (e: Exception) {
                    onError(IllegalArgumentException("后处理着色器执行失败：${e.message}", e))
                    failed = true
                }
            }
        }
        ShaderNodeModifiers(background = null, contentEffect = effectMod)
    } else {
        val bgMod = trackMod.drawBehind {
            try {
                bindUniforms(size.width, size.height)
                drawRect(shaderBrush!!)
            } catch (e: Exception) {
                onError(IllegalArgumentException("着色器绘制失败：${e.message}", e))
                failed = true
            }
        }
        ShaderNodeModifiers(background = bgMod, contentEffect = Modifier)
    }
}
