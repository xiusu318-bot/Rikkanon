package me.rerere.rikkahub.data.model

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.custom.CustomUICodeEngine
import me.rerere.rikkahub.ui.custom.parseHexColorOrNull
import me.rerere.rikkahub.ui.custom.resolveActiveUIPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UIPresetTest {

    @Test
    fun `paper workbench theme compiles cleanly`() {
        val code = """
            ui.theme({
              seed: "#D97757",
              dark: false,
              colors: { background: "#FAF9F5" },
            });
            ui.slot("chat.topbar", Row({ fillMaxWidth: true, height: 52 }, [Host("drawer_button"), Host("title")]));
            ui.slot("chat.input", Column({ fillMaxWidth: true }, [Host("text_field"), Host("send_button")]));
            ui.slot("chat.drawer", Column({ fillMaxSize: true }, [Host("conversation_list")]));
            ui.slot("chat.message.user", Surface({ corners: 14 }, [Host("markdown_body")]));
            ui.slot("chat.message.assistant", (d, ctx) => Column({ fillMaxWidth: true }, [Host("markdown_body")]));
            ui.slot("conversation_item", (d, ctx) => Row({ fillMaxWidth: true }, [Text(d.title || "")]));
            ui.slot("avatar", (d, ctx) => Box({ width: 32, height: 32 }, [Text(d.initial || "")]));
        """.trimIndent()
        val compiled = runBlocking {
            CustomUICodeEngine.compileUICode(code, UIPreset(name = "Claude"))
        }
        assertNull(compiled.compileError)
        assertTrue(compiled.backgroundSection.enabled)
        assertEquals("#FAF9F5", compiled.backgroundSection.colorOverrides["background"])
        assertEquals("#D97757", compiled.backgroundSection.seedColorHex)
        assertTrue(compiled.hasSlot("chat.drawer"))
        assertTrue(compiled.hasSlot("chat.message.user") && compiled.hasSlot("chat.message.assistant"))
        assertTrue(compiled.hasSlot("chat.topbar") && compiled.hasSlot("chat.input"))
        assertTrue("conversation_item" in compiled.dynamicSlots && "avatar" in compiled.dynamicSlots)
    }

    @Test
    fun `resolveActiveUIPreset returns null when disabled or no preset enabled`() {
        val p1 = UIPreset(name = "A", enabled = false)
        val p2 = UIPreset(name = "B", enabled = true)
        assertNull(Settings(enableCustomUi = false, uiPresets = listOf(p2)).resolveActiveUIPreset())
        assertNull(Settings(enableCustomUi = true, uiPresets = listOf(p1)).resolveActiveUIPreset())
        assertEquals(p2.id, Settings(enableCustomUi = true, uiPresets = listOf(p1, p2)).resolveActiveUIPreset()?.id)
    }

    @Test
    fun `hex color parser supports 3, 4, 6 and 8 digit hex strings`() {
        assertEquals(Color(0xFFFFFFFF), parseHexColorOrNull("#FFF"))
        assertEquals(Color(0x88FF0000), parseHexColorOrNull("#8F00"))
        assertEquals(Color(0xFF00E5FF), parseHexColorOrNull("#00E5FF"))
        assertEquals(Color(0x80090D16), parseHexColorOrNull("#80090D16"))
        assertNull(parseHexColorOrNull("not_a_color"))
    }
}
