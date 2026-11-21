// 原生能力自测插件：rikka.java / rikka.host / rikka.hook
// 用法：插件详情页点「▶ 运行全部测试」，或单独点某一组重跑。结果显示在各项右侧徽标与说明里，完整明细见插件日志。

const J = rikka.java;
const HOST_UTIL = "me.rerere.rikkahub.utils.StringUtilsKt"; // 宿主 utils/StringUtils.kt 的 JVM 类
const RUN_ID = "run";

const GROUPS = [
  { key: "java", title: "1. Java 反射", desc: "静态字段、构造、重载、变参、集合、超大 long、数组、异常" },
  { key: "hidden", title: "2. 隐藏 API", desc: "Android 隐藏类 SystemProperties / ActivityThread 及其私有字段" },
  { key: "host", title: "3. 宿主对象", desc: "Application、当前 Activity、Koin 取 PluginHost 并读私有字段、调用宿主方法" },
  { key: "proxy", title: "4. 接口代理与线程", desc: "JS 实现 Java 接口：后台线程、线程池、主线程回调，及同线程限制" },
  { key: "hook", title: "5. 方法 Hook（宿主方法）", desc: "Hook fileSizeToString：改参数、改返回值、跳过原方法、调原方法、解除" },
  { key: "ui", title: "6. 主线程 UI + Hook 系统方法", desc: "Hook Toast.makeText 后在主线程弹 Toast（应看到 [Hook] 前缀）" },
];

const mountIds = {};

// ---------------------------------------------------------------- 工具

const sleep = (ms) => J.use("java.lang.Thread").$async("sleep", ms);

function errText(e) {
  return e && e.message ? e.message : String(e);
}

async function check(results, name, fn, expect, note) {
  const t0 = Date.now();
  try {
    const v = await fn();
    let ok;
    if (expect === undefined) ok = !!v;
    else if (typeof expect === "function") ok = !!expect(v);
    else if (expect instanceof RegExp) ok = typeof v === "string" && expect.test(v);
    else ok = v === expect;
    const shown = typeof v === "bigint" ? v.toString() + "n" : (J.isJava(v) ? "<" + v.$type + ">" : JSON.stringify(v));
    results.push({ name, ok, detail: shown + (note ? "（" + note + "）" : ""), ms: Date.now() - t0 });
  } catch (e) {
    results.push({ name, ok: false, detail: "异常: " + errText(e), ms: Date.now() - t0 });
  }
}

// ---------------------------------------------------------------- 1. Java 反射

async function testJava(r) {
  const Build = J.use("android.os.Build");
  await check(r, "静态字段 Build.VERSION.SDK_INT", () => J.use("android.os.Build$VERSION").$get("SDK_INT"),
    (v) => typeof v === "number" && v >= 26);
  await check(r, "静态字段 Build.MODEL", () => Build.$get("MODEL"), (v) => typeof v === "string" && v.length > 0);
  await check(r, "变参 String.format", () => J.use("java.lang.String").format("%s-%d", "a", 5), "a-5");
  await check(r, "构造 + 链式调用 StringBuilder",
    () => J.new("java.lang.StringBuilder", "x").append(1).append(true).toString(), "x1true");
  await check(r, "重载选择 Math.max(int,int)", () => J.use("java.lang.Math").max(3, 7), 7);
  await check(r, "重载选择 Math.max(double,double)", () => J.use("java.lang.Math").max(1.5, 0.5), 1.5);
  await check(r, "集合 HashMap/ArrayList + $json", () => {
    const m = J.new("java.util.HashMap");
    m.put("a", 1);
    m.put("b", J.new("java.util.ArrayList"));
    m.get("b").add("z");
    const o = m.$json();
    return o.a === 1 && Array.isArray(o.b) && o.b[0] === "z";
  }, true);
  await check(r, "超大 long → BigInt", () => J.use("java.lang.Long").$get("MAX_VALUE"),
    (v) => typeof v === "bigint" && v === 9223372036854775807n);
  await check(r, "Java 数组 Arrays.toString(int[])", () => J.use("java.util.Arrays").toString(J.array("int", [3, 1, 2])), "[3, 1, 2]");
  await check(r, "Java 异常转为 JS 异常", () => {
    try {
      J.use("java.lang.Integer").parseInt("abc");
      return "未抛出";
    } catch (e) {
      return errText(e);
    }
  }, (v) => v.indexOf("NumberFormatException") >= 0);
}

// ---------------------------------------------------------------- 2. 隐藏 API

async function testHidden(r) {
  await check(r, "隐藏类 SystemProperties.get", () => J.use("android.os.SystemProperties").get("ro.build.version.release"),
    (v) => typeof v === "string" && v.length > 0);
  await check(r, "隐藏方法 ActivityThread.currentPackageName", () => J.use("android.app.ActivityThread").currentPackageName(),
    (v) => typeof v === "string" && v.indexOf("rikkahub") >= 0);
  await check(r, "隐藏私有字段 ActivityThread.mActivities", () => {
    const at = J.use("android.app.ActivityThread").currentActivityThread();
    return at.$get("mActivities").size();
  }, (v) => typeof v === "number" && v >= 1);
}

// ---------------------------------------------------------------- 3. 宿主对象

async function testHost(r) {
  await check(r, "rikka.host.context() 包名", () => rikka.host.context().getPackageName(),
    (v) => typeof v === "string" && v.indexOf("rikkahub") >= 0);
  await check(r, "rikka.host.activity() 当前界面", () => {
    const a = rikka.host.activity();
    return a ? a.$type : null;
  }, (v) => typeof v === "string" && v.length > 0);
  await check(r, "Koin 取 PluginHost + 读私有字段 _installed", () => {
    const h = rikka.host.get("me.rerere.plugin.host.PluginHost");
    return h.$get("_installed").getValue().size();
  }, (v) => typeof v === "number" && v >= 1);
  await check(r, "调用宿主 Kotlin 方法并读 data class", () => {
    const h = rikka.host.get("me.rerere.plugin.host.PluginHost");
    return h.installedPlugin("rikka.native.selftest").getManifest().getName();
  }, "原生能力自测");
}

// ---------------------------------------------------------------- 4. 接口代理与线程

async function testProxy(r) {
  const Collections = J.use("java.util.Collections");
  const listOf = (arr) => {
    const l = J.new("java.util.ArrayList");
    arr.forEach((x) => l.add(x));
    return l;
  };
  await check(r, "后台线程回调：$async + 比较器", async () => {
    const l = listOf([3, 1, 2]);
    await Collections.$async("sort", l, (a, b) => a - b);
    return JSON.stringify(l.$json());
  }, "[1,2,3]");
  await check(r, "线程池回调：Callable", async () => {
    const ex = J.use("java.util.concurrent.Executors").newSingleThreadExecutor();
    try {
      const f = ex.submit(J.proxy("java.util.concurrent.Callable", { call() { return "来自 JS"; } }));
      return await f.$async("get");
    } finally {
      ex.shutdown();
    }
  }, "来自 JS");
  await check(r, "主线程回调：Handler.post(Runnable)", async () => {
    let ran = false;
    const Looper = J.use("android.os.Looper");
    const handler = J.use("android.os.Handler").$new(Looper.getMainLooper());
    handler.post(J.proxy("java.lang.Runnable", { run() { ran = true; } }));
    for (let i = 0; i < 30 && !ran; i++) await sleep(100);
    return ran;
  }, true);
  await check(r, "同线程限制（预期）：同步调用拿不到回调返回值", () => {
    const l = listOf([3, 1, 2]);
    Collections.sort(l, (a, b) => a - b);
    return JSON.stringify(l.$json());
  }, "[3,1,2]", "比较器按默认值 0 处理，顺序不变；插件日志应有一条提示");
}

// ---------------------------------------------------------------- 5. 方法 Hook（宿主方法）

async function testHook(r) {
  const S = J.use(HOST_UTIL);
  await check(r, "原方法基线 fileSizeToString(512)", () => S.fileSizeToString(512), "512 B");

  let mode = "none";
  let fired = 0;
  let h = null;
  await check(r, "安装 Hook", () => {
    h = rikka.hook.method(HOST_UTIL, "fileSizeToString", ["long"], {
      before(p) {
        fired++;
        if (mode === "arg") p.args[0] = 2048;
        if (mode === "skip") p.setResult("跳过了原方法");
      },
      after(p) {
        if (mode === "result") p.result = p.result + " [hooked]";
        if (mode === "orig") p.result = p.callOriginal(1048576);
      },
    });
    return h.count;
  }, 1);
  if (!h) return;

  try {
    const call = () => S.$async("fileSizeToString", 512);
    mode = "arg";
    await check(r, "before 改参数 512→2048", call, /^2[.,]00 KB$/);
    mode = "result";
    await check(r, "after 改返回值", call, "512 B [hooked]");
    mode = "skip";
    await check(r, "before setResult 跳过原方法", call, "跳过了原方法");
    mode = "orig";
    await check(r, "after 中 callOriginal(1048576)", call, /^1[.,]00 MB$/);
    mode = "result";
    await check(r, "同线程限制（预期）：同步调用不触发 Hook", () => S.fileSizeToString(512), "512 B",
      "插件在自己线程上同步调用被自己 Hook 的方法时放行原方法");
    await check(r, "回调触发次数", () => fired, (v) => v >= 4);
  } finally {
    h.unhook();
  }
  await check(r, "解除 Hook 后恢复原样", () => S.$async("fileSizeToString", 512), "512 B");
}

// ---------------------------------------------------------------- 6. 主线程 UI + Hook 系统方法

async function testUi(r) {
  let hooked = 0;
  let th = null;
  await check(r, "Hook Toast.makeText", () => {
    th = rikka.hook.method("android.widget.Toast", "makeText", ["android.content.Context", "java.lang.CharSequence", "int"], {
      before(p) {
        hooked++;
        p.args[1] = "[Hook] " + p.args[1];
      },
    });
    return th.count;
  }, 1);
  if (!th) return;
  try {
    let toast = null;
    await check(r, "$main 在主线程创建 Toast", async () => {
      const Toast = J.use("android.widget.Toast");
      toast = await Toast.$main("makeText", rikka.host.context(), "原生插件自测：这条前面应该有 [Hook]", 1);
      return toast.$type;
    }, "android.widget.Toast");
    await check(r, "$main 在主线程 show()", async () => {
      await toast.$main("show");
      return true;
    }, true, "请看屏幕上的 Toast 是否以 [Hook] 开头");
    await check(r, "系统方法 Hook 回调已触发", () => hooked, (v) => v >= 1);
  } finally {
    th.unhook();
  }
}

// ---------------------------------------------------------------- 运行与展示

const RUNNERS = { java: testJava, hidden: testHidden, host: testHost, proxy: testProxy, hook: testHook, ui: testUi };

async function runGroup(key) {
  const g = GROUPS.find((x) => x.key === key);
  rikka.ui.update(mountIds[key], { badge: "运行中…" });
  const results = [];
  try {
    await RUNNERS[key](results);
  } catch (e) {
    results.push({ name: "（组内未捕获异常）", ok: false, detail: errText(e), ms: 0 });
  }
  const passed = results.filter((x) => x.ok).length;
  const failed = results.filter((x) => !x.ok);
  rikka.ui.update(mountIds[key], {
    badge: (failed.length ? "❌ " : "✅ ") + passed + "/" + results.length,
    description: failed.length
      ? failed.map((x) => "✗ " + x.name + "：" + x.detail).join("\n")
      : g.desc + "\n全部通过",
  });
  const lines = results.map((x) => (x.ok ? "  ✓ " : "  ✗ ") + x.name + " = " + x.detail + " (" + x.ms + "ms)");
  (failed.length ? rikka.log.warn : rikka.log.info)("[" + g.title + "] " + passed + "/" + results.length + "\n" + lines.join("\n"));
  return { key, title: g.title, passed, total: results.length, results };
}

let running = false;

// 点击回调立即返回（宿主对回调有 30 秒超时），测试在后台继续执行；运行中重复点击直接忽略
function startInBackground(task) {
  if (running) {
    rikka.toast("测试正在运行，请稍候");
    return null;
  }
  running = true;
  task().catch((e) => rikka.log.error("测试中断: " + errText(e))).finally(() => { running = false; });
  return null;
}

async function runAll() {
  const t0 = Date.now();
  rikka.ui.update(mountIds[RUN_ID], { badge: "运行中…" });
  let passed = 0;
  let total = 0;
  const summary = [];
  for (const g of GROUPS) {
    const res = await runGroup(g.key);
    passed += res.passed;
    total += res.total;
    summary.push(g.title + "：" + res.passed + "/" + res.total);
  }
  const stats = J.stats();
  const text = "通过 " + passed + "/" + total + "，用时 " + (Date.now() - t0) + "ms；当前句柄 " + stats.handles + "、Hook " + stats.hooks;
  rikka.ui.update(mountIds[RUN_ID], {
    badge: passed === total ? "✅ 全部通过" : "❌ " + (total - passed) + " 项失败",
    description: text + "\n" + summary.join("\n"),
  });
  rikka.log.info("[汇总] " + text);
  return { passed, total };
}

mountIds[RUN_ID] = rikka.ui.mount("plugin.detail", {
  type: "entry",
  icon: "play",
  title: "▶ 运行全部测试",
  description: "依次运行下面 6 组；第 6 组会弹出一个 Toast。完整明细见插件日志。",
  badge: "未运行",
}, () => startInBackground(runAll));

for (const g of GROUPS) {
  mountIds[g.key] = rikka.ui.mount("plugin.detail", {
    type: "entry",
    icon: "code",
    title: g.title,
    description: g.desc + "\n（点此单独运行本组）",
    badge: "未运行",
  }, () => startInBackground(() => runGroup(g.key)));
}

// 供其他插件 / 自动化调用
rikka.expose("runAll", () => runAll());
rikka.expose("runGroup", (key) => runGroup(key));

rikka.onUnload(() => rikka.log.info("原生能力自测插件已卸载（其 Hook 与句柄由宿主自动清理）"));
