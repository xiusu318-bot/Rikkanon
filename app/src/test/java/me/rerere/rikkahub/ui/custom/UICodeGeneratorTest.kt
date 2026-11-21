package me.rerere.rikkahub.ui.custom

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.UIComponentStyle
import me.rerere.rikkahub.data.model.UIGlobalSection
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIMessageNodeType
import me.rerere.rikkahub.data.model.UIPageSpec
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.model.UITextStyleOverride
import me.rerere.rikkahub.data.model.UITransitionPair
import me.rerere.rikkahub.data.model.UITransitionSide
import me.rerere.rikkahub.data.model.UITransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class UICodeGeneratorTest {
    private fun roundTrip(p: UIPreset): UIPreset {
        val code = UICodeGenerator.generate(p.copy(code = ""))
        return try {
            runBlocking { CustomUICodeEngine.compileUICode(code, UIPreset(id = p.id, name = p.name)) }
        } catch (e: Exception) {
            throw AssertionError("generated code failed to compile: ${e.message}\n$code", e)
        }
    }

    private fun assertSections(expected: UIPreset, actual: UIPreset, code: String) {
        fun check(name: String, a: Any?, b: Any?) = assertEquals("$name\n$code", a, b)
        check("theme", expected.backgroundSection, actual.backgroundSection)
        check("typography", expected.typographySection, actual.typographySection)
        check("global", expected.globalSection, actual.globalSection)
        check("styles", expected.componentStyles, actual.componentStyles)
        check("pages", expected.pages, actual.pages)
        check("transitions", expected.transitions, actual.transitions)
        check("slots", expected.slots, actual.slots)
    }

    @Test
    fun `empty preset round trip stays empty`() {
        val p = UIPreset(name = "Empty")
        assertSections(p, roundTrip(p), UICodeGenerator.generate(p))
    }

    @Test
    fun `new features round trip`() {
        val tricky = UIMessageNode(
            id = "root", type = UIMessageNodeType.COLUMN, paddingStartDp = 1f, paddingTopDp = 2f, paddingEndDp = 3f, paddingBottomDp = 4f,
            cornerTopStartDp = 8f, cornerTopEndDp = 8f, cornerBottomEndDp = 2f, cornerBottomStartDp = 8f,
            backgroundColorHex = "#101010", gradientEndColorHex = "#202020", gradientAngleDeg = 45f,
            actionName = "edit_message", longClickAction = "open_action_sheet", visibleWhen = "!loading", alpha = 0.5f, blurRadiusDp = 4f,
            children = listOf(
                UIMessageNode(id = "t", type = UIMessageNodeType.CUSTOM_TEXT, customText = "a \"q\" \n b", fontWeight = 600, italic = true, maxLines = 2, letterSpacingEm = 0.1f, textAlign = "center"),
                UIMessageNode(id = "i", type = UIMessageNodeType.IMAGE, imageSrc = "file:a.gif", widthDp = 40f, heightDp = 30f, contentScale = "crop"),
                UIMessageNode(id = "p", type = UIMessageNodeType.PATH, pathData = "M0 0 L10 10", viewBoxWidth = 10f, viewBoxHeight = 20f, strokeWidth = 1f),
                UIMessageNode(id = "b", type = UIMessageNodeType.ICON_BUTTON, iconName = "search", actionName = "open_search", borderWidthDp = 1f, borderColorHex = "outline"),
                UIMessageNode(id = "e", type = UIMessageNodeType.ROW, fillMaxHeight = true, compact = true, children = emptyList()),
                UIMessageNode(id = "h", type = UIMessageNodeType.HOST, hostBlock = "send_button", textColorHex = "#FF0000"),
                UIMessageNode(
                    id = "fx", type = UIMessageNodeType.SHADER, shaderSrc = "uniform float iTime;\nuniform float2 c;\nhalf4 main(float2 p) { return half4(1); }",
                    uniforms = mapOf("c" to listOf(0.5f, 1f)), fillMaxWidth = true, heightDp = 80f,
                    fallback = UIMessageNode(id = "fb", type = UIMessageNodeType.BOX, backgroundColorHex = "#000000"),
                    press = me.rerere.rikkahub.data.model.UIPressEffect(scale = 0.9f),
                    animations = listOf(
                        me.rerere.rikkahub.data.model.UINodeAnimation(prop = "rotate", values = listOf(0f, 360f), duration = 3000, repeat = -1, easing = "linear"),
                        me.rerere.rikkahub.data.model.UINodeAnimation(prop = "bg", colors = listOf("#000000", "primary"), times = listOf(0f, 1f), alternate = true, repeat = 2, delay = 100),
                    ),
                    children = listOf(UIMessageNode(id = "fc", type = UIMessageNodeType.CUSTOM_TEXT, customText = "x")),
                ),
                UIMessageNode(id = "v", type = UIMessageNodeType.VIDEO, imageSrc = "https://e.com/a.mp4", loop = false, muted = false, contentScale = "crop"),
                UIMessageNode(
                    id = "fr", type = UIMessageNodeType.FLOW_ROW, spacingDp = 6f, crossSpacingDp = 8f, clip = false, blendMode = "plus", zIndex = 3f,
                    children = listOf(
                        UIMessageNode(id = "tf", type = UIMessageNodeType.TEXT_FIELD, customText = "init", placeholder = "hint", singleLine = false, haptic = "tick"),
                        UIMessageNode(id = "sw", type = UIMessageNodeType.SWITCH, checked = true),
                        UIMessageNode(id = "sl", type = UIMessageNodeType.SLIDER, sliderValue = 2.5f, minValue = 1f, maxValue = 5f, steps = 3),
                        UIMessageNode(id = "pp", type = UIMessageNodeType.POPUP, modal = false, hasExplicitBoxAlign = true, children = listOf(
                            UIMessageNode(id = "pt", type = UIMessageNodeType.CUSTOM_TEXT, customText = "pop"),
                        )),
                    ),
                ),
                UIMessageNode(id = "sc", type = UIMessageNodeType.COLUMN, scroll = "vertical", insets = "status, nav", pointerEvents = "none", children = emptyList()),
            ),
        )
        val p = UIPreset(
            globalSection = UIGlobalSection(enabled = true, fontFamily = "serif", fontScale = 1.1f, textStyles = mapOf("titleLarge" to UITextStyleOverride(size = 20f, weight = 600)), shapeStyle = "cut", shapeCorners = mapOf("medium" to 4f), motionScheme = "standard"),
            componentStyles = mapOf(
                "conversation_item" to UIComponentStyle(background = "secondaryContainer", cornerDp = 8f, paddingHorizontalDp = 10f, paddingVerticalDp = 4f, borderWidthDp = 0.5f, borderColor = "outline"),
                "reasoning" to UIComponentStyle(background = "#EEEEEE", color = "primary", secondaryColor = "#666666", opacity = 0.8f),
                "code_block" to UIComponentStyle(background = "#F0F0F0", cornerDp = 6f, widthDp = 300f, spacingDp = 4f),
            ),
            typographySection = me.rerere.rikkahub.data.model.UITypographySection(enabled = true, lineHeightMultiplier = 1.6f, quoteHighlightEnabled = false, bodyTextColorHex = "#111111"),
            pages = mapOf("*" to UIPageSpec(
                background = UIMessageNode(id = "bg", type = UIMessageNodeType.BOX, fillMaxWidth = true, fillMaxHeight = true),
                foreground = UIMessageNode(id = "fg", type = UIMessageNodeType.SHADER, shaderSrc = "uniform float iTime; uniform float4 iClicks[4]; half4 main(float2 p){return half4(0);}", wakeDuration = 1.2f, pointerEvents = "none"),
                colors = mapOf("surface" to "#FFFFFF"),
            )),
            transitions = UITransitionSpec(push = UITransitionPair(enter = UITransitionSide(fade = true, slideX = 0.3f, durationMs = 200))),
            slots = mapOf(
                "chat.message.user" to tricky,
                "chat.topbar" to UIMessageNode(id = "tb", type = UIMessageNodeType.ROW, compact = true, children = listOf(UIMessageNode(id = "t1", type = UIMessageNodeType.HOST, hostBlock = "title"))),
                "conversation_empty" to UIMessageNode(id = "ce", type = UIMessageNodeType.ROW, children = listOf(
                    UIMessageNode(id = "ct", type = UIMessageNodeType.CUSTOM_TEXT, customText = "空", textColorHex = "outline"),
                    UIMessageNode(id = "co", type = UIMessageNodeType.HOST, hostBlock = "original", weight = 1f),
                )),
            ),
        )
        assertSections(p, roundTrip(p), UICodeGenerator.generate(p))
    }
}
