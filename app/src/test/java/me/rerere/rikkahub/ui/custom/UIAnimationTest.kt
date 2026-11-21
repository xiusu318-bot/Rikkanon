package me.rerere.rikkahub.ui.custom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.data.model.UINodeAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UIAnimationTest {
    private val eps = 1e-3f

    @Test
    fun `position with delay repeat and alternate`() {
        val a = UINodeAnimation(prop = "alpha", values = listOf(0f, 1f), duration = 100, delay = 50, repeat = 3, alternate = true)
        assertEquals(0f, a.positionAt(0), eps)
        assertEquals(0f, a.positionAt(50), eps)
        assertEquals(0.5f, a.positionAt(100), eps)
        assertEquals(0.25f, a.positionAt(225), eps) // 第 2 次倒放：走了 3/4，位置回到 1/4
        assertEquals(0.25f, a.positionAt(275), eps)
        assertFalse(a.isFinished(349))
        assertTrue(a.isFinished(350))
        assertEquals(1f, a.positionAt(10_000), eps) // 3 次：最后一次正放，停在终点
        val even = a.copy(repeat = 2)
        assertEquals(0f, even.positionAt(10_000), eps) // 2 次往返：停在起点
        val infinite = a.copy(repeat = -1)
        assertFalse(infinite.isFinished(Long.MAX_VALUE / 2))
    }

    @Test
    fun `keyframes with times and easing per segment`() {
        val a = UINodeAnimation(prop = "translateY", values = listOf(0f, -10f, 0f), times = listOf(0f, 0.2f, 1f), duration = 1000, easing = "linear")
        assertEquals(0f, a.floatAt(0, LinearEasing), eps)
        assertEquals(-5f, a.floatAt(100, LinearEasing), eps)
        assertEquals(-10f, a.floatAt(200, LinearEasing), eps)
        assertEquals(-5f, a.floatAt(600, LinearEasing), eps)
        val eased = a.copy(easing = "easeIn")
        // easeIn 开头慢：段中点的值小于线性插值
        assertTrue(kotlin.math.abs(eased.floatAt(100)) < 5f)
    }

    @Test
    fun `colors interpolate`() {
        val a = UINodeAnimation(prop = "bg", colors = listOf("#000000", "#FFFFFF"), duration = 100, easing = "linear")
        val mid = a.colorAt(50, listOf(Color.Black, Color.White))
        assertTrue(mid.red > 0.2f && mid.red < 0.8f)
        assertEquals(Color.White, a.colorAt(100, listOf(Color.Black, Color.White)))
    }

    @Test
    fun `easing names`() {
        assertNotNull(parseUIEasing("easeInOut"))
        assertNotNull(parseUIEasing("cubic-bezier(0.2, 0, 0, 1)"))
        assertNull(parseUIEasing("bounce"))
    }

    @Test
    fun `shader uniform declarations`() {
        val u = parseShaderUniforms(
            """
            uniform float2 iResolution;
            uniform float iTime;
            layout(color) uniform half4 tint;
            uniform shader image;
            uniform float arr[4];
            """.trimIndent()
        )
        assertEquals(mapOf("iResolution" to 2, "iTime" to 1, "tint" to 4, "image" to -1, "arr" to -1), u)

        val specs = parseShaderUniformSpecs(
            """
            uniform shader content;
            uniform float4 iClicks[8];
            uniform half4 iTrail[6];
            """.trimIndent()
        )
        assertTrue(specs["content"]!!.isShader)
        assertEquals(8, specs["iClicks"]!!.arraySize)
        assertEquals(4, specs["iClicks"]!!.elementComponents)
        assertEquals(6, specs["iTrail"]!!.arraySize)
    }

    @Test
    fun `touch click and trail ring buffers order newest first and offset locally`() {
        val state = UIShaderTouchState()
        val t0 = 1_000_000_000L
        state.recordDown(100f, 200f, t0 + 500_000_000L) // +0.5s
        state.recordMoveOrUp(120f, 230f, anyPressed = true, nowNanos = t0 + 600_000_000L) // +0.6s
        state.recordDown(300f, 400f, t0 + 1_500_000_000L) // +1.5s

        val clicks = FloatArray(4 * 4)
        state.fillClicks(clicks, count = 4, offsetX = 10f, offsetY = 20f, startNanos = t0)
        // 最新点击在下标 0
        assertEquals(290f, clicks[0], eps)
        assertEquals(380f, clicks[1], eps)
        assertEquals(1.5f, clicks[2], eps)
        assertTrue(clicks[3] in 0f..1f)
        // 上一次点击在下标 1
        assertEquals(90f, clicks[4], eps)
        assertEquals(180f, clicks[5], eps)
        assertEquals(0.5f, clicks[6], eps)
        // 未使用槽位时间为 -10000 秒
        assertEquals(-10000f, clicks[10], eps)

        val trail = FloatArray(4 * 4)
        state.fillTrail(trail, count = 4, offsetX = 0f, offsetY = 0f, startNanos = t0)
        assertEquals(300f, trail[0], eps)
        assertEquals(1.5f, trail[2], eps)
        assertEquals(120f, trail[4], eps)
        assertEquals(0.6f, trail[6], eps)
    }
}
