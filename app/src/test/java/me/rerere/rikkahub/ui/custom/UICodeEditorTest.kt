package me.rerere.rikkahub.ui.custom

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.model.UIPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UICodeEditorTest {
    private val editor = UICodeEditor()

    @After
    fun tearDown() = editor.close()

    private fun compile(code: String) = runBlocking { CustomUICodeEngine.compileUICode(code, UIPreset()) }

    private val code = """
        const c = { bg: "#FFFFFF" };
        ui.theme({ colors: { primary: "#D97757" } });
        ui.typography({ lineHeightMultiplier: 1.6, quoteTextColorHex: "#111111" });
        ui.slot("chat.topbar", Row({ padding: [4, 0], bg: c.bg }, [
          Text("标题", { fontSize: 15 }),
          Host("title"),
        ]));
        for (const n of [1, 2]) ui.slot("s" + n, Text("循环"));
        ui.slot("dyn", (d) => Column([Text(d.a)]));
    """.trimIndent()

    @Test
    fun `analyze gives trees with source positions`() = runBlocking {
        val a = editor.analyze(code)
        assertNull(a.error)
        val top = a.slots.single { it.name == "chat.topbar" }
        assertEquals(4, top.line)
        val row = top.tree!!
        assertEquals("row", row.type)
        assertEquals(4, row.line)
        val text = row.children[0]
        assertEquals("标题", text.lead)
        assertEquals(5, text.line)
        assertEquals(1, text.count)
        // 循环里同一处调用执行了两次
        assertEquals(2, a.slots.single { it.name == "s1" }.tree!!.count)
        // 按数据渲染的位置没有固定节点树
        assertNull(a.slots.single { it.name == "dyn" }.tree)
    }

    @Test
    fun `edits rewrite source and still compile`() = runBlocking {
        val a = editor.analyze(code)
        val row = a.slots.single { it.name == "chat.topbar" }.tree!!
        val text = row.children[0]

        val r1 = editor.setProp(code, UIEditTarget.Call(text.src!!), listOf("fontSize"), JsonPrimitive(18))
        assertTrue(r1.code!!.contains("Text(\"标题\", { fontSize: 18 })"))
        assertEquals(18f, compile(r1.code!!).slots["chat.topbar"]!!.children[0].fontSizeSp)

        val r2 = editor.setProp(code, UIEditTarget.Call(row.src!!), listOf("bg"), JsonPrimitive("#000000"))
        assertFalse(r2.ok)
        assertEquals(4, r2.line)

        val r3 = editor.setLead(code, text.src!!, "新\"标题\"")
        assertEquals("新\"标题\"", compile(r3.code!!).slots["chat.topbar"]!!.children[0].customText)

        val r4 = editor.setProp(code, UIEditTarget.Ui("typography"), listOf("quoteTextColor|quoteTextColorHex"), JsonPrimitive("#222222"))
        assertEquals("#222222", compile(r4.code!!).typographySection.quoteTextColorHex)

        val r5 = editor.setProp(code, UIEditTarget.Ui("theme"), listOf("colors", "background"), JsonPrimitive("#F4F3EE"))
        assertEquals("#F4F3EE", compile(r5.code!!).backgroundSection.colorOverrides["background"])

        val r6 = editor.setProp(code, UIEditTarget.Ui("style", "code_block"), listOf("corner"), JsonPrimitive(8))
        assertEquals(8f, compile(r6.code!!).componentStyles["code_block"]!!.cornerDp)

        val r7 = editor.addChild(code, row.src!!, "Text(\"新\")")
        assertEquals(3, compile(r7.code!!).slots["chat.topbar"]!!.children.size)

        val r8 = editor.removeChild(code, row.src!!, text.src!!)
        assertEquals(1, compile(r8.code!!).slots["chat.topbar"]!!.children.size)

        val r9 = editor.moveChild(code, row.src!!, text.src!!, 1)
        assertEquals("title", compile(r9.code!!).slots["chat.topbar"]!!.children[0].hostBlock)

        val r10 = editor.removeProp(code, UIEditTarget.Call(text.src!!), listOf("fontSize"))
        assertEquals(0f, compile(r10.code!!).slots["chat.topbar"]!!.children[0].fontSizeSp)

        val checks = editor.check(code, listOf(
            UIEditTarget.Call(row.src!!) to listOf("bg"),
            UIEditTarget.Call(row.src!!) to listOf("corners"),
            UIEditTarget.Ui("font") to listOf("family"),
        ))
        assertEquals(listOf(false, true, true), checks.map { it.ok })
    }

    @Test
    fun `page background foreground and overlay trees are captured for structure editor`() = runBlocking {
        val src = """
            ui.overlay(Box({ pointerEvents: "none" }, [Text("ov")]));
            ui.page("Setting", {
              background: Box({ bg: "#000000" }, [Text("bg")]),
              foreground: FlowRow([TextField("q"), Switch(true), Slider(0.5)]),
            });
        """.trimIndent()
        val a = editor.analyze(src)
        assertNull(a.error)
        assertEquals("box", a.slots.single { it.name == "overlay" }.tree!!.type)
        assertEquals("box", a.slots.single { it.name == "page:Setting (background)" }.tree!!.type)
        val fg = a.slots.single { it.name == "page:Setting (foreground)" }.tree!!
        assertEquals("flow_row", fg.type)
        val sw = fg.children[1]
        val edited = editor.setProp(src, UIEditTarget.Call(sw.src!!), listOf("haptic"), JsonPrimitive("tick"))
        assertTrue(edited.ok)
        assertEquals("tick", compile(edited.code!!).pages["Setting"]!!.foreground!!.children[1].haptic)
    }

    @Test
    fun `syntax errors report line`() = runBlocking {
        val a = editor.analyze("ui.slot(\"x\", Text(\"a\")\nconst = 1;")
        assertNotNull(a.error)
        val r = editor.setProp("ui.typography({ a: 1 \n", UIEditTarget.Ui("typography"), listOf("a"), JsonPrimitive(2))
        assertFalse(r.ok)
    }
}
