package me.rerere.rikkahub.ui.custom

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.UIMessageNodeType
import me.rerere.rikkahub.data.model.UIPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CustomUICodeEngineTest {

    private fun compile(code: String): UIPreset = runBlocking { CustomUICodeEngine.compileUICode(code, UIPreset()) }

    private fun compileError(code: String): String {
        try {
            compile(code)
        } catch (e: UICodeCompileException) {
            return e.message ?: ""
        }
        fail("expected UICodeCompileException")
        error("unreachable")
    }

    @Test
    fun `real js - variables functions loops nested objects and trailing comments`() {
        val preset = compile(
            """
            const palette = { bg: "#101010", accent: { main: "#FF8800" } }; // 行尾注释 "带引号"
            let spacing = 4 * 2;
            function pill(label, i) {
              return Surface({ bg: palette.accent.main, corners: [12, 12, 12, 12], id: "pill_" + i }, [Text(label, { fontSize: 13 })]);
            }
            const items = [];
            for (let i = 0; i < 3; i++) items.push(pill('第 ' + (i + 1) + ' 个', i));
            ui.theme({ seed: palette.accent.main, colors: { background: palette.bg } });
            ui.slot("u", Column({ spacing }, [...items, Spacer(1), false && Text("hidden"), null]));
            """.trimIndent()
        )
        assertEquals("#101010", preset.backgroundSection.colorOverrides["background"])
        assertEquals("#FF8800", preset.backgroundSection.seedColorHex)
        val root = preset.slots["u"]!!
        assertEquals(UIMessageNodeType.COLUMN, root.type)
        assertEquals(8f, root.spacingDp)
        assertEquals(4, root.children.size)
        assertEquals("pill_2", root.children[2].id)
        assertEquals("第 3 个", root.children[2].children.single().customText)
        assertEquals(1f, root.children[3].weight)
        assertTrue(preset.compiledCodeHash == CustomUICodeEngine.codeHash(preset.code))
        assertNull(preset.compileError)
    }

    @Test
    fun `drawer second argument becomes root node and drawer components exist`() {
        val preset = compile(
            """
            ui.slot("chat.drawer", Column([
              Row([IconButton("search", { size: 32, onClick: "open_search" }), FolderBar()]),
              ConversationList({ weight: 1 }),
              AssistantPicker(),
            ]));
            """.trimIndent()
        )
        val root = preset.slots["chat.drawer"]
        assertEquals(UIMessageNodeType.COLUMN, root?.type)
        val types = root!!.children.map { it.type }
        // 旧写法的业务节点就是同名的宿主原样块
        assertEquals(listOf(UIMessageNodeType.ROW, UIMessageNodeType.HOST, UIMessageNodeType.HOST), types)
        assertEquals(listOf("", "conversation_list", "assistant_picker"), root.children.map { it.hostBlock })
        assertEquals("folder_bar", root.children[0].children[1].hostBlock)
        assertEquals(1f, root.children[1].weight)
        val iconButton = root.children[0].children[0]
        assertEquals(UIMessageNodeType.ICON_BUTTON, iconButton.type)
        assertEquals("search", iconButton.iconName)
    }

    @Test
    fun `errors report user code line numbers`() {
        val syntax = compileError("ui.theme({\n  primary: \"#fff\",\n  oops oops\n});")
        assertTrue(syntax, syntax.contains("ui-code.js"))
        val runtime = compileError("const a = 1;\nconst b = 2;\nui.theme(undefinedVariable);")
        assertTrue(runtime, runtime.contains("undefinedVariable"))
        assertTrue(runtime, runtime.contains("ui-code.js:3"))
    }

    @Test
    fun `wrong argument types and runtime callbacks are rejected`() {
        assertTrue(compileError("ui.theme(\"#fff\");").contains("ui.theme"))
        assertTrue(compileError("ui.slot(\"u\", Column([\"text\"]));").contains("子节点"))
        assertTrue(compileError("ui.page(\"*\", { background: Text(\"x\", { onClick: () => 1 }) });").contains("ui.slot"))
        assertTrue(compileError("ui.drawer({}, \"root\");").contains("ui.drawer"))
    }

    @Test
    fun `infinite loop times out`() {
        assertTrue(compileError("while (true) {}").contains("秒"))
    }

    @Test
    fun `exported json with dynamic slots preserves code on compile`() {
        val original = compile(
            """
            ui.theme({ seed: "#D97757", colors: { background: "#FAF9F5" } });
            ui.slot("conversation_item", (c, ctx) => Row([Text(c.title)]));
            """.trimIndent()
        )
        val jsonStr = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(UIPreset.serializer(), original)
        val reimported = compile(jsonStr)
        assertTrue("conversation_item" in reimported.dynamicSlots)
        assertTrue(reimported.code.contains("conversation_item"))
    }

    @Test
    fun `strict node validation rejects unknown keys and bad colors or alignments`() {
        assertTrue(compileError("ui.slot(\"u\", Box({ unknownKey: 1 }, []));").contains("unknownKey"))
        assertTrue(compileError("ui.slot(\"u\", Box({ bg: \"not_a_color\" }, []));").contains("不是合法颜色"))
        assertTrue(compileError("ui.slot(\"u\", Row({ align: \"diagonal\" }, []));").contains("align"))
        assertTrue(compileError("ui.slot(\"u\", Row({ vAlign: \"middle\" }, []));").contains("vAlign"))
        assertTrue(compileError("ui.slot(\"u\", Box({ border: \"1px\" }, []));").contains("border"))
        assertTrue(compileError("ui.slot(\"u\", Box({ gradient: [\"#fff\"] }, []));").contains("gradient"))
        assertTrue(compileError("ui.slot(\"u\", ActionBar([\"invalid_btn\"]));").contains("invalid_btn"))
    }

    @Test
    fun `presets needing compile`() {
        assertFalse(UIPreset(code = "").needsCompile())
        assertFalse(UIPreset(code = "{ \"name\": \"x\" }").needsCompile())
        assertTrue(UIPreset(code = "ui.theme({})").needsCompile())
        assertFalse(UIPreset(code = "ui.theme({})", compiledCodeHash = CustomUICodeEngine.codeHash("ui.theme({})")).needsCompile())
    }

    @Test
    fun `global appearance - font shapes motion and color slots`() {
        val preset = compile(
            """
            ui.theme({ seed: "#D97757", colors: { onPrimary: "#FFFFFF" } });
            ui.theme({ colors: { surfaceContainerHigh: "#EEEEEE" } });
            ui.font({ family: "serif", scale: 1.1, styles: { titleLarge: { size: 24, weight: 600 } } });
            ui.font({ styles: { bodyMedium: { italic: true, family: "monospace" } } });
            ui.shapes({ style: "cut", medium: 6, extraLarge: 20 });
            ui.motion("standard");
            """.trimIndent()
        )
        assertEquals(mapOf("onPrimary" to "#FFFFFF", "surfaceContainerHigh" to "#EEEEEE"), preset.backgroundSection.colorOverrides)
        val g = preset.globalSection
        assertTrue(g.enabled)
        assertEquals("serif", g.fontFamily)
        assertEquals(1.1f, g.fontScale)
        assertEquals(24f, g.textStyles["titleLarge"]?.size)
        assertEquals(600, g.textStyles["titleLarge"]?.weight)
        assertEquals(true, g.textStyles["bodyMedium"]?.italic)
        assertEquals("monospace", g.textStyles["bodyMedium"]?.fontFamily)
        assertEquals("cut", g.shapeStyle)
        assertEquals(mapOf("medium" to 6f, "extraLarge" to 20f), g.shapeCorners)
        assertEquals("standard", g.motionScheme)
        // 没写全局外观时保持关闭（宿主原样运行）
        assertFalse(compile("ui.theme({ seed: \"#000000\" });").globalSection.enabled)
    }

    @Test
    fun `global appearance - wrong names and values are rejected`() {
        assertTrue(compileError("ui.theme({ colors: { primry: \"#fff\" } });").contains("primry"))
        assertTrue(compileError("ui.theme({ colors: { primary: \"red\" } });").contains("不是合法颜色"))
        assertTrue(compileError("ui.font({ family: \"comic\" });").contains("comic"))
        assertTrue(compileError("ui.font({ styles: { titleHuge: { size: 30 } } });").contains("titleHuge"))
        assertTrue(compileError("ui.font({ styles: { titleLarge: { weight: 1200 } } });").contains("1～1000"))
        assertTrue(compileError("ui.font({ size: 14 });").contains("size"))
        assertTrue(compileError("ui.shapes({ huge: 3 });").contains("huge"))
        assertTrue(compileError("ui.shapes({ style: \"round\" });").contains("rounded"))
        assertTrue(compileError("ui.motion(\"fast\");").contains("fast"))
    }

    @Test
    fun `component styles`() {
        val preset = compile(
            """
            ui.style("conversation_item", { bg: "secondaryContainer", corner: 8, padding: [10, 4], width: 280, spacing: 6 });
            ui.style("conversation_item", { color: "#333333" });
            ui.style("conversation_empty", { border: [0.5, "outlineVariant"], margin: 8 });
            """.trimIndent()
        )
        val item = preset.componentStyles.getValue("conversation_item")
        assertEquals("secondaryContainer", item.background)
        assertEquals(280f, item.widthDp)
        assertEquals(6f, item.spacingDp)
        assertEquals(8f, item.cornerDp)
        assertEquals(10f, item.paddingHorizontalDp)
        assertEquals(4f, item.paddingVerticalDp)
        assertEquals("#333333", item.color)
        val empty = preset.componentStyles.getValue("conversation_empty")
        assertEquals(0.5f, empty.borderWidthDp)
        assertEquals(8f, empty.marginVerticalDp)
        // 名字不在宿主登记：任意名字都能写
        assertEquals("#FFFFFF", compile("ui.style(\"chat_bubble\", { bg: \"#FFFFFF\" });").componentStyles["chat_bubble"]!!.background)
        assertTrue(compileError("ui.style(\"conversation_item\", { bg: \"blue\" });").contains("blue"))
        assertTrue(compileError("ui.style(\"conversation_item\", { shadow: 2 });").contains("shadow"))
    }

    @Test
    fun `node text props image and path`() {
        val preset = compile(
            """
            ui.slot("u", Column([
              Text("标题", { fontFamily: "serif", fontWeight: 650, italic: true, maxLines: 2, letterSpacing: 0.02, lineHeight: 22, textAlign: "center", width: 120 }),
              Image("https://example.com/a.gif", { height: 80, contentScale: "crop" }),
              Path("M12 2 L22 22 H2 Z", { viewBox: [24, 24], size: 20, color: "primary", strokeWidth: 1.5 }),
            ]));
            """.trimIndent()
        )
        val (text, image, path) = preset.slots["u"]!!.children
        assertEquals("serif", text.fontFamily)
        assertEquals(650, text.fontWeight)
        assertTrue(text.italic)
        assertEquals(2, text.maxLines)
        assertEquals(0.02f, text.letterSpacingEm)
        assertEquals(22f, text.lineHeightSp)
        assertEquals("center", text.textAlign)
        assertEquals(120f, text.widthDp)
        assertEquals(UIMessageNodeType.IMAGE, image.type)
        assertEquals("https://example.com/a.gif", image.imageSrc)
        assertEquals(80f, image.heightDp)
        assertEquals("crop", image.contentScale)
        assertEquals(UIMessageNodeType.PATH, path.type)
        assertEquals("M12 2 L22 22 H2 Z", path.pathData)
        assertEquals(1.5f, path.strokeWidth)
    }

    @Test
    fun `node props are validated`() {
        assertTrue(compileError("ui.slot(\"u\", Text(\"x\", { fontWeight: 2000 }));").contains("1～1000"))
        assertTrue(compileError("ui.slot(\"u\", Text(\"x\", { textAlign: \"middle\" }));").contains("middle"))
        assertTrue(compileError("ui.slot(\"u\", Text(\"x\", { fontFamily: \"comic\" }));").contains("comic"))
        assertTrue(compileError("ui.slot(\"u\", Image({ height: 10 }));").contains("图片来源"))
        assertTrue(compileError("ui.slot(\"u\", Image(\"a.png\", { contentScale: \"zoom\" }));").contains("zoom"))
        assertTrue(compileError("ui.slot(\"u\", Path({}));").contains("SVG"))
        assertTrue(compileError("ui.slot(\"u\", Column({ padding: [1, 2, 3] }, []));").contains("3 个"))
        assertTrue(compileError("ui.slot(\"u\", Surface({ corners: [1, 2, 3] }, []));").contains("3 个"))
    }

    @Test
    fun `pages and transitions`() {
        val preset = compile(
            """
            ui.page("*", { background: Box({ fillMaxSize: true, gradient: ["#101828", "#1E1B4B", 135] }, [
              Image("file:themes/stars.webp", { fillMaxSize: true, contentScale: "crop", opacity: 0.6 }),
            ]) });
            ui.page("Setting", { colors: { surfaceContainerLow: "#22FFFFFF" } });
            ui.transition({
              push: { enter: { fade: true, slideX: 0.3, duration: 250 }, exit: { fade: true, scale: 0.95 } },
              pop: { enter: { fade: true }, exit: { fade: true, slideX: 0.3 } },
            });
            """.trimIndent()
        )
        val all = preset.pages.getValue("*")
        assertTrue(all.background!!.fillMaxWidth && all.background!!.fillMaxHeight)
        assertEquals(UIMessageNodeType.IMAGE, all.background!!.children.single().type)
        assertEquals(mapOf("surfaceContainerLow" to "#22FFFFFF"), preset.pages.getValue("Setting").colors)
        val push = preset.transitions!!.push!!
        assertEquals(0.3f, push.enter.slideX)
        assertEquals(250, push.enter.durationMs)
        assertEquals(0.95f, push.exit.scale)
        assertTrue(compileError("ui.page(\"Settings\", { colors: {} });").contains("Settings"))
        assertTrue(compileError("ui.page(\"Setting\", { background: \"#fff\" });").contains("background"))
        assertTrue(compileError("ui.transition({ push: { enter: { spin: 1 } } });").contains("spin"))
    }

    @Test
    fun `theme seed dark removed keys and color helpers`() {
        val p = compile("""
            const bg = color.mix("#FFFFFF", "#000000", 0.5);
            ui.theme({ seed: "#D97757", dark: true, colors: {
              background: bg,
              surface: color.alpha("#FF0000", 0.5),
              outline: color.luminance("#FFFFFF") > 0.99 ? "#111111" : "#222222",
              primary: color.hex("#abc"),
            } });
        """.trimIndent())
        val b = p.backgroundSection
        assertEquals("#D97757", b.seedColorHex)
        assertEquals(true, b.dark)
        assertEquals("#80FF0000", b.colorOverrides["surface"])
        assertEquals("#111111", b.colorOverrides["outline"])
        assertEquals("#FFAABBCC", b.colorOverrides["primary"])
        // Oklab 插值（与 Compose lerp 相同）：黑白中点为 #636363，而不是 sRGB 中点 #808080
        assertEquals("#FF636363", b.colorOverrides["background"])
        assertTrue(compileError("ui.theme({ pageBackground: \"#fff\" });").contains("pageBackground"))
        assertTrue(compileError("ui.theme({ seed: \"red\" });").contains("seed"))
        assertTrue(compileError("ui.theme({ colors: { primary: color.mix(\"#fff\", \"oops\", 0.5) } });").contains("oops"))
    }

    @Test
    fun `top bar and input bar via slots with host parts`() {
        val p = compile("""
            ui.slot("chat.topbar", Row({ compact: true }, [Host("drawer_button"), Host("title", { weight: 1 }), Text("{{model}}"), Host("new_chat_button")]));
            ui.slot("chat.input", Column([Host("text_field", { text: "说点什么" }), Host("send_button", { color: "#FF0000" })]));
        """.trimIndent())
        val top = p.slots["chat.topbar"]!!
        assertEquals(listOf("drawer_button", "title", "", "new_chat_button"), top.children.map { it.hostBlock })
        assertTrue(top.compact)
        assertEquals("说点什么", p.slots["chat.input"]!!.children[0].customText)
        // 旧写法给出改法提示
        assertTrue(compileError("ui.topBar({}, Row([]));").contains("chat.topbar"))
        assertTrue(compileError("ui.inputBar({}, Row([]));").contains("chat.input"))
        assertTrue(compileError("ui.drawer({}, Row([]));").contains("chat.drawer"))
        assertTrue(compileError("ui.messages({ spacing: 8 });").contains("chat.messages"))
        assertTrue(compileError("ui.template(\"tool_header\", Row([]));").contains("chat.tool.header"))
        assertTrue(compileError("ui.slot(\"u\", Host());").contains("块名"))
        assertTrue(compileError("ui.slot(\"x\", Row({ compact: 1 }, []));").contains("compact"))
        // 显示条件与动作是普通名字
        val q = compile("ui.slot(\"x\", Row({ visibleWhen: \"!loading\", onClick: \"open\", onLongClick: \"menu\", onDoubleClick: \"navigate:Setting\" }, []));").slots["x"]!!
        assertEquals("!loading", q.visibleWhen)
        assertEquals("open", q.actionName)
        assertEquals("menu", q.longClickAction)
        assertEquals("navigate:Setting", q.doubleClickAction)
        assertTrue(compileError("ui.slot(\"x\", Row({ visibleWhen: \"has reasoning\" }, []));").contains("has reasoning"))
        assertTrue(compileError("ui.theme({ wallpaperBlur: 6 });").contains("chat.background"))
    }

    @Test
    fun `message parts are host blocks`() {
        val p = compile("""
            ui.slot("a", Column([
              Avatar({ size: 24 }),
              Host("markdown_body"),
              ActionBar(["copy", "regenerate"], { visibleWhen: "not_loading_if_last" }),
              Host("action_bar", { actionItems: ["copy"] }),
            ]));
        """.trimIndent())
        val c = p.slots["a"]!!.children
        assertTrue(c.all { it.type == UIMessageNodeType.HOST })
        assertEquals(listOf("avatar", "markdown_body", "action_bar", "action_bar"), c.map { it.hostBlock })
        assertEquals(24f, c[0].avatarSizeDp)
        assertEquals(2, c[2].actionItems.size)
        assertEquals(1, c[3].actionItems.size)
    }

    @Test
    fun `slots - static dynamic and callbacks only inside slots`() {
        val p = compile("""
            ui.slot("conversation_empty", Text("还没有对话", { color: "outline" }));
            ui.slot("conversation_item", (c, ctx) => Row([Text(c.title)]));
            ui.slot("chat.badge", Row({ onClick: () => {} }, [Text("x")]));
        """.trimIndent())
        assertEquals("还没有对话", p.slots["conversation_empty"]!!.customText)
        // 函数、或含回调的固定节点树都由运行时渲染
        assertEquals(listOf("conversation_item", "chat.badge"), p.dynamicSlots)
        assertNull(p.slots["chat.badge"])
        // 回调写在 ui.slot 之外：报错而不是悄悄丢掉
        assertTrue(compileError("ui.topBar({}, Row({ onClick: () => {} }, []));").contains("ui.slot"))
        assertTrue(compileError("ui.slot(\"x\", Row({ bg: () => \"#fff\" }, []));").contains("bg"))
        assertTrue(compileError("ui.slot(\"u\", Text(\"x\", { visibleWhen: \"1abc\" }));").contains("1abc"))
    }

    @Test
    fun `reasoning and code block styles moved to ui style`() {
        val p = compile("""
            ui.typography({ bodyTextColor: "#111111" });
            ui.style("reasoning", { bg: "#EEEEEE", color: "primary", secondaryColor: "#666666", corner: 12, border: [1, "outline"], opacity: 0.8 });
            ui.style("code_block", { bg: "#F0F0F0", corner: 6 });
        """.trimIndent())
        // 没写的排版值保持 null（= 原版），不再套用宿主写死的行距 / 段距 / 引号高亮
        assertNull(p.typographySection.lineHeightMultiplier)
        assertNull(p.typographySection.blockSpacingDp)
        assertNull(p.typographySection.quoteHighlightEnabled)
        val r = p.componentStyles["reasoning"]!!
        assertEquals("#EEEEEE", r.background)
        assertEquals("#666666", r.secondaryColor)
        assertEquals(0.8f, r.opacity)
        assertEquals(1f, r.borderWidthDp)
        assertTrue(compileError("ui.style(\"code_block\", { headerBg: \"#E0E0E0\" });").contains("headerBg"))
        assertTrue(compileError("ui.typography({ reasoningCornerDp: 12 });").contains("ui.style(\"reasoning\""))
        assertTrue(compileError("ui.typography({ codeBlockContainerColor: \"#fff\" });").contains("code_block"))
        assertTrue(compileError("ui.typography({ lineHeight: 1.5 });").contains("lineHeight"))
        assertTrue(compileError("ui.style(\"reasoning\", { opacity: 2 });").contains("opacity"))
    }

    @Test
    fun `header slots`() {
        val p = compile("""
            ui.slot("chat.tool.header", Row({ fillMaxWidth: true }, [
              Text("{{tool}}", { weight: 1 }),
              Badge("待审批", { visibleWhen: "pending" }),
              Host("approval_buttons"),
            ]));
            ui.slot("code_block.header", (d) => Row([Text(d.language + " · " + d.lines + " 行")]));
        """.trimIndent())
        assertEquals("pending", p.slots["chat.tool.header"]!!.children[1].visibleWhen)
        assertEquals(listOf("code_block.header"), p.dynamicSlots)
    }

    @Test
    fun `dynamic effects - animate press shader video`() {
        val preset = compile(
            """
            const glow = `
              uniform float2 iResolution;
              uniform float iTime;
              uniform half4 tint;
              half4 main(float2 p) { return tint * half(0.5 + 0.5 * sin(iTime)); }
            `;
            ui.slot("u", Column([
              Text("呼吸", { animate: { prop: "alpha", values: [1, 0.4, 1], duration: 2000, repeat: "infinite" } }),
              Box({ press: { scale: 0.96, alpha: 0.8 }, animate: [
                { prop: "bg", from: "#FF0000", to: "primary", duration: 500, alternate: true, repeat: 4, easing: "cubic-bezier(0.2, 0, 0, 1)" },
                { prop: "translateY", values: [0, -6, 0], times: [0, 0.3, 1] },
              ] }, []),
              Shader(glow, { fillMaxSize: true, uniforms: { tint: "#80FF8800" }, fallback: Box({ bg: "#202020" }, []) }, [Text("上面")]),
              Video("file:bg.mp4", { fillMaxSize: true, contentScale: "crop", muted: false }),
            ]));
            """.trimIndent()
        )
        val (text, box, shader, video) = preset.slots["u"]!!.children
        val breath = text.animations.single()
        assertEquals("alpha", breath.prop)
        assertEquals(listOf(1f, 0.4f, 1f), breath.values)
        assertEquals(-1, breath.repeat)
        assertEquals(0.96f, box.press!!.scale)
        assertEquals(listOf("#FF0000", "primary"), box.animations[0].colors)
        assertTrue(box.animations[0].alternate)
        assertEquals(4, box.animations[0].repeat)
        assertEquals(listOf(0f, 0.3f, 1f), box.animations[1].times)
        assertEquals(UIMessageNodeType.SHADER, shader.type)
        assertTrue(shader.shaderSrc.contains("half4 main"))
        assertEquals(4, shader.uniforms["tint"]!!.size)
        assertEquals("#202020", shader.fallback!!.backgroundColorHex)
        assertEquals("上面", shader.children.single().customText)
        assertEquals(UIMessageNodeType.VIDEO, video.type)
        assertEquals("file:bg.mp4", video.imageSrc)
        assertFalse(video.muted)
        assertTrue(video.loop)
    }

    @Test
    fun `dynamic effects are validated`() {
        assertTrue(compileError("ui.slot(\"u\", Box({ animate: { prop: \"wobble\", from: 0, to: 1 } }, []));").contains("wobble"))
        assertTrue(compileError("ui.slot(\"u\", Box({ animate: { prop: \"alpha\" } }, []));").contains("from / to"))
        assertTrue(compileError("ui.slot(\"u\", Box({ animate: { prop: \"bg\", from: \"red\", to: \"#fff\" } }, []));").contains("red"))
        assertTrue(compileError("ui.slot(\"u\", Box({ animate: { prop: \"alpha\", from: 0, to: 1, easing: \"bounce\" } }, []));").contains("bounce"))
        assertTrue(compileError("ui.slot(\"u\", Box({ animate: { prop: \"alpha\", values: [0, 1], times: [0] } }, []));").contains("个数"))
        assertTrue(compileError("ui.slot(\"u\", Box({ animate: { prop: \"alpha\", from: 0, to: 1, repeat: 0 } }, []));").contains("至少"))
        assertTrue(compileError("ui.slot(\"u\", Box({ press: { scale: 0.9, bounce: 1 } }, []));").contains("bounce"))
        assertTrue(compileError("ui.slot(\"u\", Shader(\"uniform float iTime; half4 main(float2 p) { return half4(1); }\", { uniforms: { speed: 1 } }));").contains("speed"))
        assertTrue(compileError("ui.slot(\"u\", Shader(\"uniform float2 iTime; half4 main(float2 p) { return half4(1); }\"));").contains("iTime"))
        assertTrue(compileError("ui.slot(\"u\", Shader(\"uniform float3 c; half4 main(float2 p) { return half4(1); }\", { uniforms: { c: [1, 2] } }));").contains("3 个分量"))
        assertTrue(compileError("ui.slot(\"u\", Video(\"content://x\"));").contains("http"))
        assertTrue(compileError("ui.slot(\"u\", Video({}));").contains("视频来源"))
    }

    @Test
    fun `page replacement and data bridge at compile time`() {
        val preset = compile(
            """
            ui.page("Setting", { content: (d, ctx) => Column([Text(d.route), Host("original")]) });
            ui.page("SettingAbout", { content: Text("关于"), colors: { background: "#000000" } });
            ui.page("Favorite", { content: Text("收藏") });
            """.trimIndent()
        )
        assertTrue("page:Setting" in preset.dynamicSlots)
        assertEquals("关于", preset.slots["page:SettingAbout"]!!.customText)
        assertEquals("#000000", preset.pages["SettingAbout"]!!.colors["background"])
        assertFalse("Favorite" in preset.pages)
        assertTrue(compileError("ui.page(\"Nope\", { content: Text(\"x\") });").contains("Nope"))
        assertTrue(compileError("const s = host.get(\"x\");").contains("只能在"))
        assertTrue(compileError("java.use(\"java.lang.Math\");").contains("只能在"))
    }

    @Test
    fun `recompiling does not keep sections removed from code`() {
        val first = compile("ui.slot(\"a\", Text(\"x\")); ui.style(\"tag\", { corner: 4 }); ui.typography({ firstLineIndentSp: 28 });")
        val second = runBlocking { CustomUICodeEngine.compileUICode("ui.slot(\"b\", Text(\"y\"));", first) }
        assertEquals(setOf("b"), second.slots.keys)
        assertTrue(second.componentStyles.isEmpty())
        assertFalse(second.typographySection.enabled)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `overlay foreground shader click history content filter and full parity primitives`() {
        val preset = compile(
            """
            const burst = `
              uniform float2 iResolution;
              uniform float iTime;
              uniform float4 iClicks[8];
              uniform float4 iTrail[6];
              half4 main(float2 p) { return half4(0); }
            `;
            const refract = `
              uniform shader content;
              uniform float2 iResolution;
              uniform float iTime;
              uniform float4 iClicks[4];
              half4 main(float2 p) { return content.eval(p); }
            `;
            ui.overlay(Shader(burst, {
              fillMaxSize: true,
              pointerEvents: "none",
              blendMode: "plus",
              wakeDuration: 1.2,
            }));
            ui.page("Setting", {
              foreground: Box({ fillMaxSize: true, pointerEvents: "none", clip: false, zIndex: 2 }, [Text("HUD")]),
              content: (d, ctx) => Shader(refract, { fillMaxSize: true, wakeDuration: 0.9 }, [
                Column({ fillMaxSize: true, scroll: "vertical", insets: "status, nav, ime", spacing: 10 }, [
                  FlowRow({ spacing: 8, crossSpacing: 6 }, [
                    Badge("A", { haptic: "tick" }),
                    Badge("B", { haptic: "click" }),
                  ]),
                  TextField("初始值", { placeholder: "输入搜索词…", singleLine: true, onChange: (v) => {}, onSubmit: (v, c) => c.haptic("confirm") }),
                  Switch(true, { haptic: "tick", onChange: (v) => {} }),
                  Slider(0.4, { min: 0, max: 2, steps: 3, onChange: (v) => {} }),
                  Popup({ modal: false, align: "end", vAlign: "top", onDismiss: () => {} }, [Text("浮层")]),
                ]),
              ]),
            });
            """.trimIndent()
        )
        val overlay = preset.slots["overlay"]!!
        assertEquals(UIMessageNodeType.SHADER, overlay.type)
        assertEquals("none", overlay.pointerEvents)
        assertEquals("plus", overlay.blendMode)
        assertEquals(1.2f, overlay.wakeDuration, 1e-4f)
        val fg = preset.pages["Setting"]!!.foreground!!
        assertEquals(false, fg.clip)
        assertEquals(2f, fg.zIndex, 1e-4f)
        assertTrue("page:Setting" in preset.dynamicSlots)

        // 严格校验错误分支
        assertTrue(compileError("""ui.overlay(Shader("uniform float3 iClicks[4]; half4 main(float2 p){return half4(0);}"));""").contains("iClicks"))
        assertTrue(compileError("""ui.overlay(Shader("uniform float4 iClicks[20]; half4 main(float2 p){return half4(0);}"));""").contains("iClicks"))
        assertTrue(compileError("""ui.overlay(Shader("uniform float content; half4 main(float2 p){return half4(0);}"));""").contains("content"))
        assertTrue(compileError("""ui.overlay(Shader("uniform shader content; half4 main(float2 p){return content.eval(p);}", { uniforms: { content: 1 } }));""").contains("宿主提供"))
        assertTrue(compileError("""ui.slot("a", Box({ blendMode: "vivid" }, []));""").contains("blendMode"))
        assertTrue(compileError("""ui.slot("a", Box({ pointerEvents: "block" }, []));""").contains("pointerEvents"))
        assertTrue(compileError("""ui.slot("a", Column({ scroll: "diagonal" }, []));""").contains("scroll"))
        assertTrue(compileError("""ui.slot("a", Column({ insets: "top" }, []));""").contains("insets"))
        assertTrue(compileError("""ui.slot("a", Button("x", { haptic: "buzz" }));""").contains("haptic"))
        assertTrue(compileError("""ui.slot("a", Slider(0.5, { min: 5, max: 2 }));""").contains("max"))
        assertTrue(compileError("""setTimeout(() => {}, 10);""").contains("只能在"))
        assertTrue(compileError("""ui.invalidate();""").contains("只能在"))
    }

    @Test
    fun `chat layout slot compiles static and dynamic skeleton rearrangements`() = runBlocking {
        val staticPreset = CustomUICodeEngine.compileUICode(
            """
            ui.slot("chat.layout", Box({ fillMaxSize: true }, [
              Host("background"),
              Host("messages", { fillMaxSize: true, padding: [0, 56, 0, 96] }),
              Box({ fillMaxWidth: true, vAlign: "bottom", insets: "nav,ime" }, [
                Column({ fillMaxWidth: true }, [
                  Host("input"),
                  Host("topbar"),
                ]),
              ]),
              Popup({ visibleWhen: "drawerOpen", modal: true, align: "end", onDismiss: "close_drawer" }, [
                Host("drawer", { width: 300, fillMaxHeight: true, insets: "safe" }),
              ]),
            ]));
            """.trimIndent()
        )
        val root = staticPreset.slots["chat.layout"]!!
        assertEquals(UIMessageNodeType.BOX, root.type)
        val messagesNode = root.children[1]
        assertEquals(UIMessageNodeType.HOST, messagesNode.type)
        assertEquals("messages", messagesNode.hostBlock)
        assertEquals(56f, messagesNode.paddingTopDp, 1e-4f)
        assertEquals(96f, messagesNode.paddingBottomDp, 1e-4f)

        val dynamicPreset = CustomUICodeEngine.compileUICode(
            """
            let tab = "chat";
            ui.slot("chat.layout", (d, ctx) => Column({ fillMaxSize: true }, [
              tab === "chat" ? Host("messages", { weight: 1 }) : Host("page:Setting", { weight: 1 }),
              Row({ height: 52, insets: "nav" }, [
                Button("切换", { onClick: () => { tab = tab === "chat" ? "setting" : "chat"; } }),
              ]),
            ]));
            """.trimIndent()
        )
        assertTrue("chat.layout" in dynamicPreset.dynamicSlots)
    }
}

