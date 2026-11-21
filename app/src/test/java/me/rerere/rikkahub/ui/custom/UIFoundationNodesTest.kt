package me.rerere.rikkahub.ui.custom

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIMessageNodeType
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.model.UISwipeSpec
import me.rerere.rikkahub.data.model.UIZoomSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// ---- 供反射调用的界面函数（顶层函数所在的类是 UIFoundationNodesTestKt）----

@Composable
fun TestHostWidget(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 3,
    color: Color = Color.Unspecified,
    onPick: (String) -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    TestHostWidgetLog += "$text/$size/${color != Color.Unspecified}"
    onPick(text)
    content()
}

val TestHostWidgetLog = mutableListOf<String>()

private val LocalTestValue = compositionLocalOf { 0 }

/** 在嵌套的分组（remember、CompositionLocalProvider、key）里抛异常（无默认参数）。 */
@Composable
fun TestBoom(log: MutableList<String>, round: Int) {
    val x = remember { 1 }
    CompositionLocalProvider(LocalTestValue provides x + round) {
        key(round) {
            log += "boom-inner"
            if (log.isNotEmpty()) throw IllegalStateException("炸了")
        }
    }
}

@Composable
fun TestMark(log: MutableList<String>, tag: String) {
    log += tag
}

@Composable
fun TestPlainLong(ts: Long) {
    TestHostWidgetLog += "ts=$ts"
}

/** 带 Composer 参数但不是 @Composable 的普通函数，不应被误判为界面函数。 */
fun NotComposableHelper(@Suppress("UNUSED_PARAMETER") composer: Composer): Int = 0

private object UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

class UIFoundationNodesTest {
    private var rt: UIThemeRuntime? = null

    @After
    fun tearDown() {
        rt?.close()
    }

    private fun runtime(code: String): UIThemeRuntime {
        val r = UIThemeRuntime(code)
        rt = r
        runBlocking { withTimeout(10_000) { while (!r.ready.value && r.errors.value.isEmpty()) delay(10) } }
        assertEquals(emptyList<String>(), r.errors.value)
        return r
    }

    private fun compile(code: String): UIPreset = runBlocking { CustomUICodeEngine.compileUICode(code, UIPreset()) }

    private fun compileError(code: String): String = try {
        compile(code)
        fail("应当编译失败")
        ""
    } catch (e: UICodeCompileException) {
        e.message.orEmpty()
    }

    // ---------------------------------------------------------------- 编译与校验

    @Test
    fun `lazy list needs an item function and rejects runtime-only bypass keys`() {
        val p = compile("""ui.page("History", { content: (d) => LazyColumn({ count: 3, fillMaxSize: true, item: (i) => Text("行" + i) }) });""")
        assertEquals(listOf("page:History"), p.dynamicSlots)
        assertTrue(compileError("""ui.slot("x", LazyColumn({ count: 3 }));""").contains("item"))
        // 禁止在顶层直接手写运行时内部回调编号字段绕过检查
        assertTrue(compileError("""ui.slot("x", LazyColumn({ count: 3, itemFn: "cb1" }));""").contains("itemFn"))
        // LazyRow 不支持下拉刷新
        val r = UIThemeRuntime("""ui.slot("x", () => LazyRow({ count: 2, refreshing: true, item: (i) => Text("x") }));""")
        rt = r
        runBlocking { withTimeout(10_000) { while (!r.ready.value) delay(10) } }
        val res = r.renderAwait("x", "{}", r.version.value, 5_000)!!
        assertNull(res.tree)
        assertTrue(r.errors.value.any { it.contains("LazyRow") && it.contains("下拉刷新") })
    }

    @Test
    fun `static trees carry zoom compose and native view`() {
        val p = compile(
            """
            ui.slot("a", Box({ zoom: { max: 6 } }, [
              Compose("me.rerere.x.FooKt", "Foo", ["文字", null, 3, Text("内容")], { width: 120, fallback: Text("不可用") }),
              NativeView("android.widget.ProgressBar", { width: 40, height: 40 }),
            ]));
            """.trimIndent()
        )
        val root = p.slots.getValue("a")
        assertEquals(UIZoomSpec(1f, 6f), root.zoom)
        val c = root.children[0]
        assertEquals(UIMessageNodeType.COMPOSE, c.type)
        assertEquals("Foo", c.composeFn)
        assertEquals(4, c.args!!.size)
        assertEquals(JsonNull, c.args!![1])
        assertEquals("0", c.args!![3].jsonObject.getValue("\$child").jsonPrimitive.content)
        assertEquals("内容", c.children.single().customText)
        assertEquals("不可用", c.fallback!!.customText)
        assertEquals("android.widget.ProgressBar", root.children[1].viewClass)
        // 生成的代码能编译回同样的界面树（即使 NativeView 意外挂了 children 也不生成非法参数）
        val dirtyPreset = p.copy(slots = mapOf("a" to root.copy(children = listOf(c, root.children[1].copy(children = listOf(UIMessageNode(customText = "孤立")))))))
        val again = compile(UICodeGenerator.generate(dirtyPreset)).slots.getValue("a")
        assertEquals(root.zoom, again.zoom)
        assertEquals(c.args, again.children[0].args)
        assertEquals(c.composeClass, again.children[0].composeClass)
        assertEquals("内容", again.children[0].children.single().customText)
        assertEquals(120f, again.children[0].widthDp)
        assertEquals("android.widget.ProgressBar", again.children[1].viewClass)
    }

    @Test
    fun `wrong values are reported at compile time`() {
        assertTrue(compileError("""ui.slot("a", Box({ swipe: "sideways", onSwipe: () => {} }));""").contains("left"))
        assertTrue(compileError("""ui.slot("a", Box({ swipe: "left" }));""").contains("onSwipe"))
        assertTrue(compileError("""ui.slot("a", Box({ swipe: true, onSwipe: () => {} }));""").contains("方向字符串"))
        assertTrue(compileError("""ui.slot("a", Box({ swipe: { direction: "left", speed: 1 }, onSwipe: () => {} }));""").contains("speed"))
        assertTrue(compileError("""ui.slot("a", Box({ zoom: { min: 3, max: 2 } }));""").contains("min"))
        assertTrue(compileError("""ui.slot("a", Box({ swipe: "left", onSwipe: "delete" }));""").contains("函数"))
        assertTrue(compileError("""ui.slot("a", Box({ padding: -4 }));""").contains("负数"))
        assertTrue(compileError("""ui.slot("a", Column({ spacing: -2 }));""").contains("负数"))
        assertTrue(compileError("""ui.slot("a", Compose("a.B", 3, []));""").contains("函数名"))
        assertTrue(compileError("""ui.slot("a", Compose("a.B", "c"));""").contains("参数数组"))
        assertTrue(compileError("""ui.slot("a", Compose("a.B", "c", "x"));""").contains("参数数组"))
        assertTrue(compileError("""ui.slot("a", Compose("a.B", "c", [], { children: [Text("x")] }));""").contains("children"))
        assertTrue(compileError("""ui.slot("a", NativeView({ width: 1 }));""").contains("类名"))
    }

    // ---------------------------------------------------------------- 运行时：按行渲染与回调

    @Test
    fun `lazy list renders only the requested rows and callbacks get plain arguments`() {
        val r = runtime(
            """
            const rows = ["甲", "乙", "丙", "丁"];
            let computed = [];
            let more = 0;
            ui.slot("page", (d, ctx) => LazyColumn({
              keys: rows,
              item: (i) => { computed.push(i); return Row([Text(rows[i]), Text(String(computed.length), { onClick: () => { more++; } })]); },
              onReorder: (from, to) => { rows.splice(to, 0, rows.splice(from, 1)[0]); },
              onEndReached: () => { rows.push("新" + rows.length); },
              swipe: "horizontal",
              onSwipe: (direction) => { rows.push(direction); },
            }));
            """.trimIndent()
        )
        val page = r.renderAwait("page", "{}", r.version.value, 5_000)!!
        val list = page.tree!!
        assertEquals(UIMessageNodeType.LAZY_COLUMN, list.type)
        assertEquals(4, list.count)
        assertEquals(listOf("甲", "乙", "丙", "丁"), list.keys)
        assertTrue(list.itemFn.isNotEmpty() && list.onReorderFn.isNotEmpty() && list.onEndReachedFn.isNotEmpty() && list.onSwipeFn.isNotEmpty())

        // 只算被要求的那一行；同一行再取直接用缓存
        val row2 = r.renderItemAwait(list.itemFn, 2, 5_000)!!
        assertEquals("丙", row2.tree!!.children[0].customText)
        assertEquals("1", row2.tree!!.children[1].customText)
        assertTrue(row2 === r.renderItemAwait(list.itemFn, 2, 5_000))
        val row0 = r.renderItemAwait(list.itemFn, 0, 5_000)!!
        assertEquals("甲", row0.tree!!.children[0].customText)
        assertEquals("2", row0.tree!!.children[1].customText)
        // 行里的节点编号带行号，不同的行不会重复
        assertFalse(row0.tree!!.id == row2.tree!!.id)

        // 拖拽结束：onReorder(from, to) 收到两个数字，状态版本 +1
        val v = r.version.value
        runBlocking { r.callArgs(list.onReorderFn, listOf(0, 2)) }
        assertEquals(v + 1, r.version.value)
        val list2 = r.renderAwait("page", "{}", r.version.value, 5_000)!!.tree!!
        assertEquals(listOf("乙", "丙", "甲", "丁"), list2.keys)
        // 滑动与滚到末尾
        runBlocking { r.callArgs(list2.onSwipeFn, listOf("left")) }
        runBlocking { r.callArgs(list2.onEndReachedFn, emptyList()) }
        val list3 = r.renderAwait("page", "{}", r.version.value, 5_000)!!.tree!!
        assertEquals(listOf("乙", "丙", "甲", "丁", "left", "新5"), list3.keys)
        assertEquals(emptyList<String>(), r.errors.value)
    }

    @Test
    fun `displayed results stay valid after being pushed out of the cache`() {
        val r = runtime(
            """
            let clicks = 0;
            ui.slot("p", (d) => Text(d.n + ":" + clicks, { onClick: () => { clicks++; } }));
            """.trimIndent()
        )
        val first = r.renderAwait("p", """{"n":0}""", r.version.value, 5_000)!!
        r.pin(first)
        // 把它挤出缓存（缓存 400 条）
        for (i in 1..450) r.renderAwait("p", """{"n":$i}""", r.version.value, 5_000)
        runBlocking { delay(100) }
        val before = r.version.value
        runBlocking { r.call(first.tree!!.onClickFn) }
        assertEquals("钉住的结果回调仍然有效", emptyList<String>(), r.errors.value)
        assertEquals(before + 1, r.version.value)
        assertEquals("0:1", r.renderAwait("p", """{"n":0}""", r.version.value, 5_000)!!.tree!!.customText)
        // 不再显示后才回收
        r.unpin(first)
        runBlocking { delay(100); r.call(first.tree!!.onClickFn) }
        assertTrue(r.errors.value.any { it.contains("回调已失效") })
    }

    @Test
    fun `compose arguments keep java objects callbacks content and clean up temporary callback handles`() {
        val r = runtime(
            """
            let picked = "";
            let sb = null;
            let nestedRan = 0;
            ui.slot("p", (d, ctx) => {
              if (!sb) sb = java.keep(java.new("java.lang.StringBuilder", "abc"));
              return Compose("x.YKt", "Y", [
                sb,
                (v, w, col, dpVal) => { picked = v + "|" + w.toString() + "|" + col + "|" + dpVal; },
                Text(picked || "空"),
                [java.proxy("java.lang.Runnable", () => { nestedRan++; })],
                5,
                null,
                "#FF0000",
              ]);
            });
            ui.slot("badNested", () => Compose("x.YKt", "Y", [[() => {}]]));
            """.trimIndent()
        )
        val node = r.renderAwait("p", "{}", r.version.value, 5_000)!!.tree!!
        val args = node.args!!
        val sb = r.javaObject(args[0].jsonObject.getValue("\$j").jsonPrimitive.content.toLong())
        assertEquals("abc", sb.toString())
        val cb = args[1].jsonObject.getValue("\$cb").jsonPrimitive.content
        assertEquals("0", args[2].jsonObject.getValue("\$child").jsonPrimitive.content)
        // 数组里直接塞裸 JS 函数会明确报错提示使用 java.proxy，而包了 java.proxy 的对象可正常解码为 Runnable[]
        val runnables = r.decodeAs(args[3], Array<Runnable>::class.java) as Array<*>
        assertEquals(1, runnables.size)
        assertNull(r.renderAwait("badNested", "{}", r.version.value, 5_000)!!.tree)
        assertTrue(r.errors.value.any { it.contains("java.proxy") })
        r.close()

        val r2 = runtime(
            """
            let picked = "";
            ui.slot("p", () => Compose("x.YKt", "Y", [
              (v, w, col, dpVal) => { picked = v + "|" + w.toString() + "|" + col + "|" + dpVal; },
              Text(picked || "空"),
            ]));
            """.trimIndent()
        )
        val n1 = r2.renderAwait("p", "{}", r2.version.value, 5_000)!!.tree!!
        val cb2 = n1.args!![0].jsonObject.getValue("\$cb").jsonPrimitive.content

        // 回调：临时宿主对象回调结束后自动释放句柄（不泄漏），Color / Dp 自动拆箱为 #hex 与数值
        runBlocking {
            repeat(5) { i ->
                r2.callArgs(cb2, listOf("选中$i", StringBuilder("临时$i"), Color(0xFF123456), 24.5.dp))
            }
        }
        val node2 = r2.renderAwait("p", "{}", r2.version.value, 5_000)!!.tree!!
        assertEquals("选中4|临时4|#123456|24.5", node2.children.single().customText)
        assertEquals(UIComposeResolver.signature(n1.args!!), UIComposeResolver.signature(node2.args!!))
        assertEquals(emptyList<String>(), r2.errors.value)
        r2.close()

        // 10 次回调传入的 10 个临时 StringBuilder 已全部回收，只剩 java.keep 的那 1 个句柄
        val checkStats = runtime(
            """
            let kept = null;
            let lastHandles = 0;
            ui.slot("s", () => {
              if (!kept) kept = java.keep(java.new("java.lang.StringBuilder", "k"));
              lastHandles = java.stats().handles;
              return Compose("x.YKt", "Y", [(tmp) => {}], { name: String(lastHandles) });
            });
            """.trimIndent()
        )
        val s1 = checkStats.renderAwait("s", "{}", checkStats.version.value, 5_000)!!.tree!!
        val scb = s1.args!![0].jsonObject.getValue("\$cb").jsonPrimitive.content
        runBlocking { repeat(10) { checkStats.callArgs(scb, listOf(StringBuilder("ephemeral-$it"))) } }
        val s2 = checkStats.renderAwait("s", "{}", checkStats.version.value, 5_000)!!.tree!!
        // java.new("java.lang.StringBuilder", "k") 持有 Class 与实例共 2 个句柄；10 次临时回调参数已全部回收，句柄数零增长
        assertEquals(s1.name, s2.name)
        assertEquals("2", s2.name)
        assertEquals(emptyList<String>(), checkStats.errors.value)
    }

    // ---------------------------------------------------------------- 文档第九章的示例：编译并实际渲染

    private fun docBlocks(): List<String> {
        val f = listOf(java.io.File("../CUSTOM_UI_DOCS.md"), java.io.File("CUSTOM_UI_DOCS.md")).first { it.exists() }
        val chapter = f.readText().substringAfter("## 九、整页重写的三项底座").substringBefore("\n## ")
        return Regex("```js\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(chapter).map { it.groupValues[1] }.toList()
    }

    private fun find(node: UIMessageNode, type: UIMessageNodeType): UIMessageNode? =
        if (node.type == type) node else node.children.firstNotNullOfOrNull { find(it, type) }

    @Test
    fun `chapter nine examples render at runtime`() {
        val blocks = docBlocks()
        assertEquals(4, blocks.size)
        val pageData = """{"route":"x","params":{}}"""

        // 懒加载列表：空列表 → 滚到末尾加载 50 行 → 渲染其中一行 → 左滑删除、拖拽排序
        var r = runtime(blocks[0])
        var list = find(r.renderAwait("page:Log", pageData, r.version.value, 5_000)!!.tree!!, UIMessageNodeType.LAZY_COLUMN)!!
        assertEquals(0, list.count)
        runBlocking { r.callArgs(list.onEndReachedFn, emptyList()) }
        list = find(r.renderAwait("page:Log", pageData, r.version.value, 5_000)!!.tree!!, UIMessageNodeType.LAZY_COLUMN)!!
        assertEquals(50, list.count)
        val row = r.renderItemAwait(list.itemFn, 3, 5_000)!!.tree!!
        assertEquals("第 4 条", row.children[0].customText)
        assertEquals(UISwipeSpec(direction = "left", dismiss = true), row.swipe)
        runBlocking { r.callArgs(row.onSwipeFn, listOf("left")) }
        runBlocking { r.callArgs(list.onReorderFn, listOf(0, 1)) }
        list = find(r.renderAwait("page:Log", pageData, r.version.value, 5_000)!!.tree!!, UIMessageNodeType.LAZY_COLUMN)!!
        assertEquals(listOf("row-1", "row-0", "row-2", "row-4"), list.keys.take(4))
        assertEquals(emptyList<String>(), r.errors.value)
        r.close()

        // 滑动切换分页 + 缩放
        r = runtime(blocks[1])
        var page = r.renderAwait("page:Stats", pageData, r.version.value, 5_000)!!.tree!!
        val pager = page.children[1]
        assertEquals(UISwipeSpec(direction = "horizontal", follow = false), pager.swipe)
        runBlocking { r.callArgs(pager.onSwipeFn, listOf("left")) }
        page = r.renderAwait("page:Stats", pageData, r.version.value, 5_000)!!.tree!!
        runBlocking { r.callArgs(page.children[1].onSwipeFn, listOf("left")) }
        page = r.renderAwait("page:Stats", pageData, r.version.value, 5_000)!!.tree!!
        assertEquals(UIZoomSpec(1f, 6f), find(page, UIMessageNodeType.IMAGE)!!.zoom)
        assertEquals(emptyList<String>(), r.errors.value)
        r.close()

        // 宿主界面函数：二维码 + 扫码弹窗（回调收到字符串后关闭）
        r = runtime(blocks[2])
        page = r.renderAwait("page:SettingAbout", pageData, r.version.value, 5_000)!!.tree!!
        val qr = find(page, UIMessageNodeType.COMPOSE)!!
        assertEquals("QRCode", qr.composeFn)
        assertEquals(180f, qr.widthDp)
        runBlocking { r.call(page.children.first { it.type == UIMessageNodeType.BUTTON }.onClickFn) }
        page = r.renderAwait("page:SettingAbout", pageData, r.version.value, 5_000)!!.tree!!
        val scanner = page.children.last { it.type == UIMessageNodeType.COMPOSE }
        assertEquals("QRCodeScannerDialog", scanner.composeFn)
        runBlocking { r.callArgs(scanner.args!![0].jsonObject.getValue("\$cb").jsonPrimitive.content, listOf("https://x")) }
        page = r.renderAwait("page:SettingAbout", pageData, r.version.value, 5_000)!!.tree!!
        assertTrue(page.children.any { it.customText == "扫到：https://x" })
        assertEquals(1, page.children.count { it.type == UIMessageNodeType.COMPOSE })
        assertEquals(emptyList<String>(), r.errors.value)
        r.close()

        // 原生视图
        r = runtime(blocks[3])
        page = r.renderAwait("page:SettingDonate", pageData, r.version.value, 5_000)!!.tree!!
        val web = find(page, UIMessageNodeType.NATIVE_VIEW)!!
        assertEquals("android.webkit.WebView", web.viewClass)
        assertTrue(web.onCreateFn.isNotEmpty() && web.onReleaseFn.isNotEmpty())
        assertEquals(emptyList<String>(), r.errors.value)
    }

    // ---------------------------------------------------------------- 反射查找与参数转换

    private val testClass = "me.rerere.rikkahub.ui.custom.UIFoundationNodesTestKt"
    private fun args(vararg e: kotlinx.serialization.json.JsonElement) = JsonArray(e.toList())
    private val findClass: (String) -> Class<*> = { Class.forName(it) }

    @Test
    fun `finds composable functions by plain name rejects missing required params and ignores non-composables`() {
        // 参数里有 Color，编译后的名字带后缀；写 TestHostWidget 就能找到
        val t = UIComposeResolver.resolve(testClass, "TestHostWidget", args(JsonPrimitive("a")), findClass)
        assertEquals(6, t.paramTypes.size)
        assertEquals(String::class.java, t.paramTypes[0])
        assertEquals(Modifier::class.java, t.paramTypes[1])
        assertEquals(Long::class.javaPrimitiveType, t.paramTypes[3])
        assertTrue(t.hasDefaults && t.hasInlineMangle)
        assertEquals(0, UIComposeResolver.contentArity(t.paramTypes[5]))

        fun error(block: () -> Unit): String = try { block(); fail("应当失败"); "" } catch (e: IllegalArgumentException) { e.message.orEmpty() }
        assertTrue(error { UIComposeResolver.resolve("no.such.ClassKt", "X", args(), findClass) }.contains("找不到类"))
        assertTrue(error { UIComposeResolver.resolve(testClass, "Nope", args(), findClass) }.contains("TestHostWidget"))
        // 普通函数即使带 Composer 参数也不得当作界面函数
        assertTrue(error { UIComposeResolver.resolve(testClass, "NotComposableHelper", args(), findClass) }.contains("不是界面函数"))
        // 无默认参数的函数（TestBoom）禁止少传参数或给基本类型传 null
        assertTrue(error { UIComposeResolver.resolve(testClass, "TestBoom", args(), findClass) }.contains("参数对不上"))
        assertTrue(error { UIComposeResolver.resolve(testClass, "TestBoom", args(JsonNull, JsonNull), findClass) }.contains("参数对不上"))
        // 普通 Long 参数（无内联后缀）不接受颜色字符串
        assertTrue(error { UIComposeResolver.resolve(testClass, "TestPlainLong", args(JsonPrimitive("#FF0000")), findClass) }.contains("参数对不上"))
        assertFalse(UIComposeResolver.resolve(testClass, "TestPlainLong", args(JsonPrimitive(123456L)), findClass).hasInlineMangle)
        // 第一个参数是字符串，传数字对不上：列出可用的写法
        assertTrue(error { UIComposeResolver.resolve(testClass, "TestHostWidget", args(JsonPrimitive(1)), findClass) }.contains("String"))
        assertTrue(error { UIComposeResolver.resolve(testClass, "TestMark", args(JsonNull, JsonNull, JsonNull), findClass) }.contains("3 个"))
    }

    @Test
    fun `converts plain values strictly by parameter type`() {
        val redBits = Color.Red.value.toLong()
        val color: (String) -> Long? = { if (it == "#FF0000") redBits else null }
        fun conv(v: JsonPrimitive, type: Class<*>, inlineColor: Boolean = true) =
            UIComposeResolver.convertPrimitive(v, type, "参数", allowInlineColor = inlineColor, color = color)
        assertEquals(3, conv(JsonPrimitive(3), Int::class.javaPrimitiveType!!))
        assertEquals(2.5f, conv(JsonPrimitive(2.5), Float::class.javaPrimitiveType!!))
        assertEquals(redBits, conv(JsonPrimitive("#FF0000"), Long::class.javaPrimitiveType!!))
        assertEquals(Color.Red, conv(JsonPrimitive("#FF0000"), Color::class.java))
        assertEquals(Dp(12.5f), conv(JsonPrimitive(12.5), Dp::class.java))
        assertEquals(UIMessageNodeType.ROW, conv(JsonPrimitive("ROW"), UIMessageNodeType::class.java))
        assertEquals(true, conv(JsonPrimitive(true), Boolean::class.javaPrimitiveType!!))
        fun error(block: () -> Unit): String = try { block(); fail("应当失败"); "" } catch (e: IllegalArgumentException) { e.message.orEmpty() }
        // 枚举不再忽略大小写兜底
        assertTrue(error { conv(JsonPrimitive("row"), UIMessageNodeType::class.java) }.contains("取值"))
        // 非内联的真 Long 参数传字符串报类型错误而非「不是合法颜色」
        assertTrue(error { conv(JsonPrimitive("#FF0000"), Long::class.javaPrimitiveType!!, inlineColor = false) }.contains("long"))
        assertTrue(error { conv(JsonPrimitive(2.5), Int::class.javaPrimitiveType!!) }.contains("int"))
        assertTrue(error { conv(JsonPrimitive("x"), Long::class.javaPrimitiveType!!) }.contains("颜色"))
        assertTrue(error { conv(JsonPrimitive("nope"), UIMessageNodeType::class.java) }.contains("取值"))
    }

    // ---------------------------------------------------------------- 界面函数执行中抛错：接住并继续

    private fun runComposition(block: suspend (compose: (@Composable () -> Unit) -> Unit, settle: suspend () -> Unit) -> Unit) {
        val clock = BroadcastFrameClock()
        runBlocking {
            withContext(clock) {
                val recomposer = Recomposer(coroutineContext)
                val job = launch { recomposer.runRecomposeAndApplyChanges() }
                // 先让重绘循环跑起来（它要登记对状态改动的监听），再开始绘制
                delay(20)
                val composition = Composition(UnitApplier, recomposer)
                try {
                    block({ content -> composition.setContent(content) }) {
                        repeat(10) {
                            Snapshot.sendApplyNotifications()
                            delay(5)
                            clock.sendFrame(System.nanoTime())
                            delay(5)
                        }
                    }
                } finally {
                    composition.dispose()
                    recomposer.cancel()
                    job.join()
                }
            }
        }
    }

    @Test
    fun `reflective call composes the function with defaults callbacks and content`() {
        TestHostWidgetLog.clear()
        val picked = mutableListOf<String>()
        val log = mutableListOf<String>()
        val target = UIComposeResolver.resolve(testClass, "TestHostWidget", args(JsonPrimitive("a")), findClass)
        val onPick: (String) -> Unit = { picked += it }
        val content: @Composable () -> Unit = { TestMark(log, "内容") }
        // 第 2、3、4 个参数留空：用函数自己的默认值
        val call = UIComposeCall(target, arrayOf("你好", null, null, null, onPick, content), -1)
        var failure: Throwable? = null
        runComposition { compose, _ ->
            compose { UIGuardedComposableCall(call, call.values) { failure = it } }
        }
        assertNull(failure)
        assertEquals(listOf("你好/3/false"), TestHostWidgetLog)
        assertEquals(listOf("你好"), picked)
        assertEquals(listOf("内容"), log)
    }

    @Test
    fun `control - test composition recomposes after a state change`() {
        val log = mutableListOf<String>()
        val round = mutableStateOf(0)
        runComposition { compose, settle ->
            compose { TestMark(log, "轮:" + round.value) }
            assertEquals(listOf("轮:0"), log)
            round.value = 1
            settle()
            assertEquals(listOf("轮:0", "轮:1"), log)
        }
    }

    @Test
    fun `an exception inside the function is caught and the rest of the screen keeps working`() {
        val log = mutableListOf<String>()
        val target = UIComposeResolver.resolve(
            testClass,
            "TestBoom",
            args(JsonObject(mapOf("\$j" to JsonPrimitive(1))), JsonPrimitive(0)),
            findClass,
        )
        val failure = mutableStateOf<Throwable?>(null)
        val round = mutableStateOf(0)
        runComposition { compose, settle ->
            compose {
                TestMark(log, "前")
                val n = round.value
                if (failure.value == null) {
                    UIGuardedComposableCall(UIComposeCall(target, arrayOf(log, n), -1), arrayOf(log, n)) { failure.value = it }
                } else {
                    TestMark(log, "替代")
                }
                TestMark(log, "后:$n")
            }
            // 同一遍绘制里：出错的函数之后的内容照常画出来
            assertEquals(listOf("前", "boom-inner", "后:0"), log)
            assertEquals("炸了", failure.value?.message)
            // 之后的重绘正常：换成替代内容
            log.clear()
            settle()
            assertTrue(log.toString(), log.contains("替代"))
            log.clear()
            round.value = 1
            settle()
            // 只有读了这个状态的部分重画（没变的部分被跳过），说明外层的重绘范围完好
            assertEquals(listOf("后:1"), log)
        }
    }
}
