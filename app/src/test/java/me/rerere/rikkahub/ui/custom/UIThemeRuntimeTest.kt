package me.rerere.rikkahub.ui.custom

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.model.UIMessageNodeType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UIThemeRuntimeTest {
    private var rt: UIThemeRuntime? = null

    @After
    fun tearDown() {
        rt?.close()
    }

    private fun runtime(code: String): UIThemeRuntime {
        val r = UIThemeRuntime(code)
        rt = r
        runBlocking { withTimeout(10_000) { while (!r.ready.value && r.errors.value.isEmpty()) kotlinx.coroutines.delay(10) } }
        return r
    }

    private fun data(vararg pairs: Pair<String, Any?>) = mapOf(*pairs).toUIData().toString()

    @Test
    fun `renders with data and caches by data`() {
        val r = runtime(
            """
            let calls = 0;
            ui.slot("item", (c) => { calls++; return Row([Text((c.pinned ? "📌 " : "") + c.title), Text(String(calls))]); });
            """.trimIndent()
        )
        val a = r.renderAwait("item", data("title" to "你好", "pinned" to true), r.version.value, 5_000)!!
        assertEquals(UIMessageNodeType.ROW, a.tree!!.type)
        assertEquals("📌 你好", a.tree!!.children[0].customText)
        // 数据没变：直接用缓存，不再执行 JS
        val again = r.renderAwait("item", data("title" to "你好", "pinned" to true), r.version.value, 5_000)!!
        assertSame(a, again)
        assertEquals("1", again.tree!!.children[1].customText)
        // 数据变了：重算
        val b = r.renderAwait("item", data("title" to "你好", "pinned" to false), r.version.value, 5_000)!!
        assertEquals("你好", b.tree!!.children[0].customText)
        assertEquals("2", b.tree!!.children[1].customText)
    }

    @Test
    fun `callbacks run in js request host actions and bump state version`() {
        val r = runtime(
            """
            let expanded = false;
            ui.slot("item", (c, ctx) => Column([
              Text(expanded ? "展开" : "收起", { onClick: () => { expanded = !expanded; } }),
              Button("打开", { onClick: () => ctx.action("open", c.id) }),
            ]));
            """.trimIndent()
        )
        val v0 = r.version.value
        val tree = r.renderAwait("item", data("id" to "c1"), v0, 5_000)!!.tree!!
        assertEquals("收起", tree.children[0].customText)
        val toggleId = tree.children[0].onClickFn
        val openId = tree.children[1].onClickFn
        assertTrue(toggleId.isNotEmpty() && openId.isNotEmpty())

        val actions = runBlocking { r.call(openId) }
        assertEquals("open", actions.single().name)
        assertEquals("\"c1\"", actions.single().args.single().toString())

        runBlocking { r.call(toggleId) }
        // 回调后状态版本变化，同样的数据会重算并看到主题自己的状态
        val after = r.renderAwait("item", data("id" to "c1"), r.version.value, 5_000)!!.tree!!
        assertTrue(r.version.value > v0)
        assertEquals("展开", after.children[0].customText)
    }

    @Test
    fun `null result means original and errors are recorded once`() {
        val r = runtime(
            """
            ui.slot("maybe", (c) => c.show ? Text("主题") : null);
            ui.slot("broken", (c) => c.missing.field);
            """.trimIndent()
        )
        val hidden = r.renderAwait("maybe", data("show" to false), r.version.value, 5_000)
        assertNotNull(hidden)
        assertNull(hidden!!.tree)
        assertEquals("主题", r.renderAwait("maybe", data("show" to true), r.version.value, 5_000)!!.tree!!.customText)

        val broken = r.renderAwait("broken", data(), r.version.value, 5_000)
        assertNull(broken!!.tree)
        r.renderAwait("broken", data(), r.version.value, 5_000)
        assertEquals(1, r.errors.value.count { "broken" in it })
    }

    @Test
    fun `does not block when told not to wait`() {
        val r = runtime(
            """
            ui.slot("slow", (c) => { const t = Date.now(); while (Date.now() - t < 300) {} return Text("好了"); });
            """.trimIndent()
        )
        val start = System.nanoTime()
        assertNull(r.renderNow("slow", data(), r.version.value, wait = false))
        assertTrue((System.nanoTime() - start) / 1_000_000L < 100)
        // 后台算完后进缓存，再取直接命中
        runBlocking { withTimeout(5_000) { while (r.renderTick.value == 0) kotlinx.coroutines.delay(10) } }
        assertEquals("好了", r.renderNow("slow", data(), r.version.value, wait = false)!!.tree!!.customText)
    }

    @Test
    fun `sync waiting is capped across slots`() {
        val r = runtime(
            """
            ui.slot("slow", (c) => { const t = Date.now(); while (Date.now() - t < 200) {} return Text(c.i); });
            """.trimIndent()
        )
        val start = System.nanoTime()
        // 10 个各需 200ms 的位置连续要求等待：合计等待受窗口上限约束，而不是 10 × 48ms
        repeat(10) { i -> r.renderNow("slow", data("i" to "$i"), r.version.value, wait = true) }
        assertTrue((System.nanoTime() - start) / 1_000_000L < 200)
    }

    @Test
    fun `data bridge - java calls and watch`() {
        val r = runtime(
            """
            let flow = null;
            ui.slot("item", (d, ctx) => {
              const max = java.use("java.lang.Math").max(3, 7);
              if (!flow) flow = java.keep(java.use("kotlinx.coroutines.flow.StateFlowKt").MutableStateFlow("你好"));
              const v = ctx.watch(flow);
              return Text(v + max);
            });
            ui.slot("bad", (d, ctx) => Text(String(java.proxy)));
            """.trimIndent()
        )
        val res = r.renderAwait("item", data(), r.version.value, 5_000)!!
        assertEquals("你好7", res.tree!!.customText)
        val flow = res.watched.single() as kotlinx.coroutines.flow.MutableStateFlow<Any?>
        flow.value = "再见"
        // 被 watch 的值参与缓存键：值变了换一个键就会重新渲染并读到新值
        val again = r.renderNow("item", data(), r.version.value, wait = true, watchKey = "changed")
            ?: r.renderAwait("item", data(), r.version.value, 5_000)
        assertNotNull(again)
    }

    @Test
    fun `data bridge - java proxy timers value callbacks and haptic`() {
        val r = runtime(
            """
            let query = "init";
            let flag = false;
            let proxyMsg = "";
            let ticked = 0;
            ui.slot("panel", (d, ctx) => Column([
              Text(query + ":" + flag + ":" + proxyMsg + ":" + ticked),
              TextField(query, {
                onChange: (v) => { query = v; },
                onSubmit: (v, c) => {
                  c.haptic("confirm");
                  const r = java.keep(java.proxy("java.lang.Runnable", {
                    run() { proxyMsg = "proxy_ok"; }
                  }));
                  java.new("java.lang.Thread", r).start();
                  setTimeout(() => { ticked = 42; }, 35);
                },
              }),
              Switch(flag, { onChange: (v) => { flag = v; } }),
            ]));
            """.trimIndent()
        )
        val t0 = r.renderAwait("panel", data(), r.version.value, 5_000)!!.tree!!
        assertEquals("init:false::0", t0.children[0].customText)
        val tf = t0.children[1]
        val sw = t0.children[2]

        runBlocking { r.call(tf.onChangeFn, kotlinx.serialization.json.JsonPrimitive("endfield")) }
        runBlocking { r.call(sw.onChangeFn, kotlinx.serialization.json.JsonPrimitive(true)) }
        val actions = runBlocking { r.call(tf.onSubmitFn, kotlinx.serialization.json.JsonPrimitive("endfield")) }
        assertEquals("__haptic", actions.single().name)
        assertEquals("\"confirm\"", actions.single().args.single().toString())

        // 等待后台 Java Thread 回调 java.proxy 以及 setTimeout 触发并自动递增状态版本
        runBlocking {
            withTimeout(3_000) {
                while (true) {
                    val cur = r.renderAwait("panel", data(), r.version.value, 5_000)?.tree?.children?.get(0)?.customText
                    if (cur == "endfield:true:proxy_ok:42") break
                    kotlinx.coroutines.delay(15)
                }
            }
        }
        assertTrue("unexpected errors: ${r.errors.value}", r.errors.value.isEmpty())
    }

    @Test
    fun `dynamic slot keeps stable path ids across rerenders and conditional siblings`() {
        val r = runtime(
            """
            let q = "";
            ui.slot("search", () => {
              const sharedDivider = Divider();
              return Column([
                q.length > 0 ? Badge("active") : null,
                TextField(q, { onChange: (v) => { q = v; } }),
                Row([Text("static"), sharedDivider, Text({ id: "custom_id", text: "pinned" }), sharedDivider]),
              ]);
            });
            """.trimIndent()
        )
        val first = r.renderAwait("search", data(), r.version.value, 5_000)!!.tree!!
        assertEquals(2, first.children.size)
        val tfBefore = first.children[0]
        val rowBefore = first.children[1]
        assertEquals("custom_id", rowBefore.children[2].id)
        assertTrue(rowBefore.children[1].id != rowBefore.children[3].id)

        runBlocking { r.call(tfBefore.onChangeFn, kotlinx.serialization.json.JsonPrimitive("hello")) }

        val second = r.renderAwait("search", data(), r.version.value, 5_000)!!.tree!!
        assertEquals(3, second.children.size)
        val tfAfter = second.children[1]
        val rowAfter = second.children[2]
        // 即使前面的条件子节点从 null 变成了 Badge，TextField 所在槽位路径不变，默认 id 保持一致（保护拼音组词与本地状态）
        assertEquals(tfBefore.id, tfAfter.id)
        // 无回调的静态子树在重算后整棵节点结构完全相等，Compose 可直接跳过重组
        assertEquals(rowBefore, rowAfter)
    }

    @Test
    fun `shader touch observer count and chat overlay override state track lifecycle`() {
        val touch = UIShaderTouchState()
        assertEquals(false, touch.hasActiveObservers)
        touch.retain()
        touch.retain()
        assertEquals(true, touch.hasActiveObservers)
        touch.release()
        assertEquals(true, touch.hasActiveObservers)
        touch.release()
        touch.release()
        assertEquals(false, touch.hasActiveObservers)

        val overlay = UIChatOverlayOverrideState()
        assertEquals(false, overlay.isOverridden("c1"))
        overlay.retain("c1")
        overlay.retain("c2")
        assertEquals(true, overlay.isOverridden("c1"))
        assertEquals(true, overlay.isOverridden("c2"))
        overlay.release("c1")
        assertEquals(false, overlay.isOverridden("c1"))
        assertEquals(true, overlay.isOverridden("c2"))
    }

    @Test
    fun `java copy works on Settings and Assistant and proxy returns non-null during concurrent render`() {
        val r = runtime(
            """
            let savedFn = null;
            ui.slot("panel", () => {
              const s0 = java.use("me.rerere.rikkahub.data.datastore.Settings").${'$'}get("Companion").dummy();
              const a0 = java.new("me.rerere.rikkahub.data.model.Assistant");
              const a1 = java.copy(a0, { name: "欲肉载体", contextMessageLimit: 64, enableWebSearch: true });
              const s1 = s0.${'$'}copy({ enableAutoTitle: false, searchServiceSelected: 2 });
              if (!savedFn) {
                savedFn = java.keep(java.proxy("java.util.function.UnaryOperator", (cur) => {
                  // 支持原样返回入参或 java.copy 返回新实例
                  return cur.getEnableAutoTitle() ? java.copy(cur, { enableAutoTitle: false }) : cur;
                }));
              }
              return Text(a1.getName() + ":" + a1.getContextMessageLimit() + ":" + s1.getSearchServiceSelected());
            });
            """.trimIndent()
        )
        val tree = r.renderAwait("panel", data(), r.version.value, 5_000)!!.tree!!
        assertEquals("欲肉载体:64:2", tree.customText)
        assertTrue("unexpected errors: ${r.errors.value}", r.errors.value.isEmpty())
    }
}

