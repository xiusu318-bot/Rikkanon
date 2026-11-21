package me.rerere.rikkahub.ui.custom

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.UIPreset
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CustomUIDocsTest {
    @Test
    fun `every js block in CUSTOM_UI_DOCS compiles`() {
        val f = listOf(File("../CUSTOM_UI_DOCS.md"), File("CUSTOM_UI_DOCS.md")).first { it.exists() }
        val blocks = Regex("```js\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .map { it.groupValues[1] }
            .toList()
        val fails = mutableListOf<String>()
        blocks.forEachIndexed { i, b ->
            try {
                val compiled = runBlocking { CustomUICodeEngine.compileUICode(b, UIPreset()) }
                assertNull("block $i compileError: ${compiled.compileError}", compiled.compileError)
            } catch (e: Throwable) {
                fails += "block $i: ${e.message}\n${b.take(120)}"
            }
        }
        if (fails.isNotEmpty()) throw AssertionError(fails.joinToString("\n\n"))
    }
}
