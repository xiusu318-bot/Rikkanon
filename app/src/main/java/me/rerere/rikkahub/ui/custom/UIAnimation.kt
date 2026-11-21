package me.rerere.rikkahub.ui.custom

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UINodeAnimation
import me.rerere.rikkahub.data.model.UIPressEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 可做关键帧动画的数值属性（translate 单位 dp，rotate / gradientAngle 单位度）。 */
val UI_ANIM_FLOAT_PROPS = listOf(
    "alpha", "scale", "scaleX", "scaleY", "rotate", "rotateX", "rotateY", "translateX", "translateY", "gradientAngle",
)

/** 可做关键帧动画的颜色属性：bg（底色，含渐变起始色）、color（文字 / 图标 / 路径的颜色）。 */
val UI_ANIM_COLOR_PROPS = listOf("bg", "color")

private val NAMED_EASINGS: Map<String, Easing> = mapOf(
    "linear" to LinearEasing,
    "ease" to CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f),
    "easeIn" to CubicBezierEasing(0.42f, 0f, 1f, 1f),
    "easeOut" to CubicBezierEasing(0f, 0f, 0.58f, 1f),
    "easeInOut" to CubicBezierEasing(0.42f, 0f, 0.58f, 1f),
)

private val CUBIC_BEZIER = Regex("""^cubic-bezier\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)$""")

/** 缓动名 → 缓动曲线；不认识时返回 null。 */
fun parseUIEasing(name: String): Easing? {
    NAMED_EASINGS[name]?.let { return it }
    val m = CUBIC_BEZIER.matchEntire(name.trim()) ?: return null
    val (a, b, c, d) = m.destructured
    return CubicBezierEasing(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat())
}

val UI_EASING_NAMES: Set<String> get() = NAMED_EASINGS.keys

/** 动画在开始后 [elapsedMs] 毫秒时是否已播完（无限循环永远不会）。 */
fun UINodeAnimation.isFinished(elapsedMs: Long): Boolean =
    repeat >= 0 && elapsedMs >= delay.toLong() + duration.coerceAtLeast(1).toLong() * repeat

/** 开始后 [elapsedMs] 毫秒时在一次播放中的位置（0～1，已计入往返与延迟）。 */
fun UINodeAnimation.positionAt(elapsedMs: Long): Float {
    val t = elapsedMs - delay
    if (t <= 0L) return 0f
    val d = duration.coerceAtLeast(1).toLong()
    if (isFinished(elapsedMs)) {
        val lastIndex = repeat - 1
        return if (alternate && lastIndex % 2 == 1) 0f else 1f
    }
    val iteration = t / d
    val f = (t % d).toFloat() / d
    return if (alternate && iteration % 2L == 1L) 1f - f else f
}

/** 关键帧位置：写了 times 用 times，否则均匀分布。 */
private fun UINodeAnimation.keyTimes(count: Int): List<Float> =
    if (times.size == count) times else List(count) { i -> if (count == 1) 0f else i.toFloat() / (count - 1) }

/** 位置 [p] 落在哪一段：返回（段起点下标，段内已缓动的比例）。 */
private fun UINodeAnimation.segment(p: Float, count: Int, easing: Easing): Pair<Int, Float> {
    if (count <= 1) return 0 to 0f
    val ts = keyTimes(count)
    if (p <= ts.first()) return 0 to 0f
    if (p >= ts.last()) return count - 2 to 1f
    var i = 0
    while (i < count - 2 && p > ts[i + 1]) i++
    val span = (ts[i + 1] - ts[i]).takeIf { it > 0f } ?: return i to 1f
    return i to easing.transform(((p - ts[i]) / span).coerceIn(0f, 1f))
}

/** 数值属性在 [elapsedMs] 时的值。 */
fun UINodeAnimation.floatAt(elapsedMs: Long, easing: Easing = parseUIEasing(this.easing) ?: LinearEasing): Float {
    if (values.isEmpty()) return 0f
    if (values.size == 1) return values[0]
    val (i, f) = segment(positionAt(elapsedMs), values.size, easing)
    return values[i] + (values[i + 1] - values[i]) * f
}

/** 颜色属性在 [elapsedMs] 时的值（[resolved] 为已解析好的关键帧颜色）。 */
fun UINodeAnimation.colorAt(elapsedMs: Long, resolved: List<Color>, easing: Easing = parseUIEasing(this.easing) ?: LinearEasing): Color {
    if (resolved.isEmpty()) return Color.Unspecified
    if (resolved.size == 1) return resolved[0]
    val (i, f) = segment(positionAt(elapsedMs), resolved.size, easing)
    return lerp(resolved[i], resolved[i + 1], f)
}

/**
 * 一个节点的动画状态：时钟（毫秒）在每帧更新，各属性的值只在绘制阶段读取（只重画，不重组）。
 * 文字 / 图标颜色动画例外：图标的颜色在组合中读取，会逐帧重组该图标节点。
 */
@Stable
class UINodeAnimator internal constructor(
    private val anims: List<UINodeAnimation>,
    private val easings: List<Easing>,
    private val colors: List<List<Color>>,
    val press: UIPressEffect?,
) {
    internal var elapsed by mutableLongStateOf(0L)
    internal var pressed by mutableStateOf(false)
    internal var pressProgress: State<Float>? = null

    private fun has(prop: String) = anims.any { it.prop == prop }

    val hasTransform: Boolean = press != null || anims.any { it.prop in TRANSFORM_PROPS }
    val hasBackground: Boolean = has("bg") || has("gradientAngle")
    val hasBgColor: Boolean = has("bg")
    val hasColor: Boolean = has("color")
    val finite: Boolean = anims.all { it.repeat >= 0 }

    fun allFinished(ms: Long): Boolean = anims.all { it.isFinished(ms) }

    /** 数值属性：同一属性写了多条时，alpha / scale 相乘，其余相加；没有动画时为 [default]。 */
    fun float(prop: String, default: Float): Float {
        var result = default
        var seen = false
        val ms = elapsed
        anims.forEachIndexed { i, a ->
            if (a.prop != prop) return@forEachIndexed
            val v = a.floatAt(ms, easings[i])
            result = if (!seen) v else if (prop == "alpha" || prop.startsWith("scale")) result * v else result + v
            seen = true
        }
        return result
    }

    /** 颜色属性：最后一条生效；没有动画时为 [default]。 */
    fun color(prop: String, default: Color): Color {
        val i = anims.indexOfLast { it.prop == prop }
        if (i < 0) return default
        return anims[i].colorAt(elapsed, colors[i], easings[i])
    }

    companion object {
        private val TRANSFORM_PROPS = setOf("alpha", "scale", "scaleX", "scaleY", "rotate", "rotateX", "rotateY", "translateX", "translateY")
    }
}

/** 节点没有 `animate` / `press` 时返回 null（不产生任何开销）。 */
@Composable
fun rememberUINodeAnimator(node: UIMessageNode, colorScheme: ColorScheme): UINodeAnimator? {
    if (node.animations.isEmpty() && node.press == null) return null
    val animator = remember(node.animations, node.press, colorScheme) {
        UINodeAnimator(
            anims = node.animations,
            easings = node.animations.map { parseUIEasing(it.easing) ?: LinearEasing },
            colors = node.animations.map { a -> a.colors.map { resolveUIColor(it, colorScheme, Color.Transparent) } },
            press = node.press,
        )
    }
    if (node.animations.isNotEmpty()) {
        LaunchedEffect(animator) {
            val start = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                animator.elapsed = now - start
                if (animator.finite && animator.allFinished(animator.elapsed)) break
            }
        }
    }
    node.press?.let { p ->
        animator.pressProgress = animateFloatAsState(
            targetValue = if (animator.pressed) 1f else 0f,
            animationSpec = tween(p.duration.coerceAtLeast(0)),
            label = "ui_press",
        )
    }
    return animator
}

/** 变换类动画与按压反馈（graphicsLayer，只重画）。[passthrough] 为 true 时不挂触摸监听以免阻挡底层点击。 */
fun Modifier.uiAnimatedLayer(animator: UINodeAnimator?, passthrough: Boolean = false): Modifier {
    if (animator == null || !animator.hasTransform) return this
    var mod = this
    if (animator.press != null && !passthrough) {
        mod = mod.pointerInput(animator) {
            awaitPointerEventScope {
                while (true) {
                    // 只观察、不消费，点击 / 长按照常由节点自己的手势处理
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    animator.pressed = event.changes.any { it.pressed }
                }
            }
        }
    }
    return mod.graphicsLayer {
        val press = animator.press
        val pp = animator.pressProgress?.value ?: 0f
        val pressScale = if (press != null) 1f + (press.scale - 1f) * pp else 1f
        val pressAlpha = if (press != null) 1f + (press.alpha - 1f) * pp else 1f
        val s = animator.float("scale", 1f)
        scaleX = s * animator.float("scaleX", 1f) * pressScale
        scaleY = s * animator.float("scaleY", 1f) * pressScale
        alpha = (animator.float("alpha", 1f) * pressAlpha).coerceIn(0f, 1f)
        rotationZ = animator.float("rotate", 0f)
        rotationX = animator.float("rotateX", 0f)
        rotationY = animator.float("rotateY", 0f)
        translationX = animator.float("translateX", 0f) * density
        translationY = animator.float("translateY", 0f) * density
    }
}

/**
 * 底色 / 渐变动画：代替静态底色绘制（在绘制阶段读取动画值）。
 * [baseColor] / [gradientEnd] / [baseAngle] 为节点的静态值（已乘过不透明度）；没有渐变时 [gradientEnd] 为 Unspecified。
 */
fun uiAnimatedBackground(
    animator: UINodeAnimator,
    shape: Shape,
    baseColor: Color,
    gradientEnd: Color,
    baseAngle: Float,
    alphaMultiplier: Float,
): Modifier = Modifier.drawBehind {
    val start = if (animator.hasBgColor) {
        animator.color("bg", baseColor).let { it.copy(alpha = it.alpha * alphaMultiplier) }
    } else baseColor
    if (start == Color.Unspecified) return@drawBehind
    val outline = shape.createOutline(size, layoutDirection, this)
    if (gradientEnd == Color.Unspecified) {
        drawOutline(outline, start)
    } else {
        val angle = animator.float("gradientAngle", baseAngle)
        drawOutline(outline, sizedLinearGradient(start, gradientEnd, angle, size.width, size.height))
    }
}

/** 按实际尺寸、角度（0 = 从左到右，90 = 从上到下）铺满的线性渐变。 */
fun sizedLinearGradient(start: Color, end: Color, angleDeg: Float, width: Float, height: Float): Brush {
    val rad = angleDeg * PI.toFloat() / 180f
    val dx = cos(rad)
    val dy = sin(rad)
    // 渐变线长度取能覆盖整个矩形的投影长度
    val half = (abs(width * dx) + abs(height * dy)) / 2f
    val c = Offset(width / 2f, height / 2f)
    return Brush.linearGradient(
        colors = listOf(start, end),
        start = Offset(c.x - dx * half, c.y - dy * half),
        end = Offset(c.x + dx * half, c.y + dy * half),
    )
}
