package me.rerere.rikkahub.ui.custom

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.model.UIGlobalSection
import me.rerere.rikkahub.data.model.UITextStyleOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UIGlobalStyleTest {
    private val families = mapOf("serif" to FontFamily.Serif, "monospace" to FontFamily.Monospace)

    @Test
    fun `typography applies family scale and per style overrides`() {
        val base = Typography()
        val t = buildUITypography(
            base,
            UIGlobalSection(
                enabled = true,
                fontFamily = "serif",
                fontScale = 2f,
                textStyles = mapOf("titleLarge" to UITextStyleOverride(size = 30f, weight = 700, italic = true, fontFamily = "monospace")),
            ),
        ) { families.getValue(it) }
        assertEquals(FontFamily.Serif, t.bodyMedium.fontFamily)
        assertEquals(base.bodyMedium.fontSize * 2f, t.bodyMedium.fontSize)
        assertEquals(base.bodyMedium.lineHeight * 2f, t.bodyMedium.lineHeight)
        assertEquals(FontFamily.Serif, t.labelSmallEmphasized.fontFamily)
        assertEquals(30.sp, t.titleLarge.fontSize)
        assertEquals(FontWeight(700), t.titleLarge.fontWeight)
        assertEquals(FontStyle.Italic, t.titleLarge.fontStyle)
        assertEquals(FontFamily.Monospace, t.titleLarge.fontFamily)
        assertEquals(30, UI_TEXT_STYLES.size)
    }

    @Test
    fun `shapes only change when configured`() {
        assertNull(buildUIShapes(Shapes(), UIGlobalSection(enabled = true)))
        val rounded = buildUIShapes(Shapes(), UIGlobalSection(enabled = true, shapeCorners = mapOf("medium" to 3f)))!!
        assertEquals(RoundedCornerShape(3.dp), rounded.medium)
        assertEquals(Shapes().small, rounded.small)
        val cut = buildUIShapes(Shapes(), UIGlobalSection(enabled = true, shapeStyle = "cut", shapeCorners = mapOf("large" to 8f)))!!
        assertEquals(CutCornerShape(8.dp), cut.large)
        assertTrue(cut.small is CutCornerShape)
    }

    @Test
    fun `color overrides set exact slots`() {
        val base = lightColorScheme()
        val c = base.withUIColorOverrides(mapOf("onPrimary" to "#FF0000", "tertiaryFixedDim" to "#00FF00"))
        assertEquals(Color(0xFFFF0000), c.onPrimary)
        assertEquals(Color(0xFF00FF00), c.tertiaryFixedDim)
        assertEquals(base.primary, c.primary)
        assertEquals(48, UI_COLOR_SLOTS.size)
    }
}
