package me.rerere.rikkahub.ui.custom

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.UIBackgroundSection
import me.rerere.rikkahub.data.model.UIComponentStyle
import me.rerere.rikkahub.data.model.UIGlobalSection
import me.rerere.rikkahub.data.model.UIHorizontalAlignment
import me.rerere.rikkahub.data.model.UIMessageActionType
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIMessageNodeType
import me.rerere.rikkahub.data.model.UINodeAnimation
import me.rerere.rikkahub.data.model.UIPageSpec
import me.rerere.rikkahub.data.model.UIPressEffect
import me.rerere.rikkahub.data.model.UISwipeSpec
import me.rerere.rikkahub.data.model.UIZoomSpec
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.model.UITextStyleOverride
import me.rerere.rikkahub.data.model.UITransitionPair
import me.rerere.rikkahub.data.model.UITransitionSide
import me.rerere.rikkahub.data.model.UITransitionSpec
import me.rerere.rikkahub.data.model.UITypographySection
import me.rerere.rikkahub.data.model.UIVerticalAlignment

/** UI 代码编译失败（语法错误、运行时异常、超时、参数类型不对）。消息中含 `ui-code.js:行号`。 */
class UICodeCompileException(message: String, cause: Throwable? = null) : Exception(message, cause)

object CustomUICodeEngine {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * UI 代码引导脚本：定义 `ui.*` 与组件构造函数，产出与 [compileUICode] 解析一致的 JSON 规格。
     * 用户代码在其后以独立文件名 `ui-code.js` 执行，报错行号对应用户代码。
     */
    internal const val BOOTSTRAP_UI_DSL_JS = """
        globalThis.__ui_spec = {};
        globalThis.__ui_slot_fns = {};
        const __CALLBACK_KEYS = ["onClick", "onLongClick", "onDoubleClick", "onChange", "onSubmit", "onDismiss",
          "item", "onEndReached", "onReorder", "onRefresh", "onSwipe", "onCreate", "onRelease"];
        function __hasFns(x) {
          if (typeof x === "function") return true;
          if (Array.isArray(x)) return x.some(__hasFns);
          if (x != null && typeof x === "object") return Object.keys(x).some(k => __hasFns(x[k]));
          return false;
        }
        let __node_seq = 1;
        const __IS_NODE = Symbol.for("ui.isNode");
        const __AUTO_ID = Symbol.for("ui.autoId");
        const __CHILD_SLOTS = Symbol.for("ui.childSlots");
        function __nextId(prefix) {
          return (prefix || "n") + "_" + (__node_seq++);
        }
        function __checkProps(type, props) {
          for (const k of Object.keys(props)) {
            if (typeof props[k] === "function" && __CALLBACK_KEYS.indexOf(k) < 0) {
              throw new TypeError(type + " 的属性 " + k + " 是函数：只有 " + __CALLBACK_KEYS.join(" / ") + " 可以写成回调");
            }
          }
          return props;
        }
        function __normArgs(type, a, b) {
          if (a == null) return { props: {}, children: Array.isArray(b) ? b : [] };
          if (Array.isArray(a)) return { props: {}, children: a };
          if (typeof a === "object") {
            if (b != null && !Array.isArray(b)) throw new TypeError(type + " 的第二个参数必须是子节点数组");
            return { props: a, children: Array.isArray(b) ? b : (Array.isArray(a.children) ? a.children : []) };
          }
          throw new TypeError(type + " 的参数必须是属性对象或子节点数组，实际为 " + typeof a);
        }
        function __makeNode(type, a, b) {
          const { props, children } = __normArgs(type, a, b);
          __checkProps(type, props);
          const hasExplicitId = props.id != null && String(props.id) !== "";
          const node = Object.assign({}, props, { id: hasExplicitId ? props.id : __nextId(type), type: type });
          node[__IS_NODE] = true;
          if (!hasExplicitId) node[__AUTO_ID] = true;
          delete node.children;
          const list = [];
          const slots = [];
          for (let i = 0; i < children.length; i++) {
            const c = children[i];
            if (c == null || c === false) continue;
            if (typeof c !== "object" || Array.isArray(c) || typeof c.type !== "string") {
              throw new TypeError(type + " 的子节点必须是组件（如 Text(...)、Row(...)），实际为 " + JSON.stringify(c));
            }
            list.push(c);
            slots.push(i);
          }
          if (list.length > 0) {
            node.children = list;
            node[__CHILD_SLOTS] = slots;
          }
          return node;
        }
        function __leaf(type, key) {
          return (first, maybeProps) => {
            if (typeof first === "string" || typeof first === "number") {
              const props = Object.assign({}, maybeProps || {});
              props[key] = String(first);
              return __makeNode(type, props);
            }
            return __makeNode(type, first || {});
          };
        }
        function __merge(section, cfg) {
          if (cfg != null && (typeof cfg !== "object" || Array.isArray(cfg))) {
            throw new TypeError("ui." + section + " 的参数必须是对象");
          }
          __checkProps("ui." + section, cfg || {});
          // 多次调用合并；值是普通对象（如 colors、styles）时再合并一层
          const cur = globalThis.__ui_spec[section] || {};
          for (const k of Object.keys(cfg || {})) {
            const v = cfg[k], old = cur[k];
            const plain = (x) => x != null && typeof x === "object" && !Array.isArray(x) && typeof x.type !== "string";
            cur[k] = plain(v) && plain(old) ? Object.assign({}, old, v) : v;
          }
          globalThis.__ui_spec[section] = cur;
        }
        function __moved(old, now) {
          throw new TypeError(old + " 已改为 " + now);
        }
        function __node(name, n) {
          if (n == null || typeof n !== "object" || Array.isArray(n) || typeof n.type !== "string") {
            throw new TypeError(name + " 的参数必须是组件节点（如 Column(...)）");
          }
          return n;
        }
        globalThis.ui = {
          theme(cfg) { __merge("theme", cfg); },
          background(cfg) { __merge("theme", cfg); },
          drawer() { __moved("ui.drawer", "ui.slot(\"chat.drawer\", 节点树或函数)；宽度、底色用 ui.style(\"chat.drawer\", { width, bg })"); },
          font(cfg) { __merge("font", cfg); },
          page(route, cfg) {
            if (typeof route !== "string") throw new TypeError("ui.page 的第一个参数必须是页面名（如 \"Setting\"、\"*\"）");
            if (cfg == null || typeof cfg !== "object" || Array.isArray(cfg)) throw new TypeError("ui.page 的第二个参数必须是对象");
            if (cfg.background !== undefined) __node("ui.page 的 background", cfg.background);
            if (cfg.foreground !== undefined) __node("ui.page 的 foreground", cfg.foreground);
            cfg = Object.assign({}, cfg);
            // content：整页替换，等同 ui.slot("page:页面名", 节点树或函数)
            if (cfg.content !== undefined) {
              globalThis.ui.slot("page:" + route, cfg.content);
              delete cfg.content;
            }
            if (Object.keys(cfg).length === 0) return;
            const pages = globalThis.__ui_spec.pages || (globalThis.__ui_spec.pages = {});
            const cur = pages[route] || {};
            pages[route] = Object.assign(cur, cfg, cfg.colors ? { colors: Object.assign(cur.colors || {}, cfg.colors) } : {});
          },
          overlay(x) {
            globalThis.ui.slot("overlay", x);
          },
          invalidate() {
            throw new Error("ui.invalidate() 只能在 ui.slot / ui.page 的运行时回调或定时器里调用（顶层代码在保存时执行）");
          },
          transition(cfg) { __merge("transition", cfg); },
          style(name, cfg) {
            if (typeof name !== "string") throw new TypeError("ui.style 的第一个参数必须是组件名字符串");
            if (cfg == null || typeof cfg !== "object" || Array.isArray(cfg)) throw new TypeError("ui.style 的第二个参数必须是对象");
            __checkProps("ui.style", cfg);
            const styles = globalThis.__ui_spec.styles || (globalThis.__ui_spec.styles = {});
            styles[name] = Object.assign(styles[name] || {}, cfg);
          },
          slot(name, x) {
            if (typeof name !== "string") throw new TypeError("ui.slot 的第一个参数必须是位置名字符串");
            const spec = globalThis.__ui_spec;
            const slots = spec.slots || (spec.slots = {});
            const dyn = spec.dynamicSlots || (spec.dynamicSlots = []);
            const checks = spec.dynamicSlotChecks || (spec.dynamicSlotChecks = {});
            delete slots[name];
            delete checks[name];
            const i = dyn.indexOf(name);
            if (i >= 0) dyn.splice(i, 1);
            if (typeof x === "function") {
              globalThis.__ui_slot_fns[name] = x;
              dyn.push(name);
            } else {
              const n = __node("ui.slot 的第二个参数", x);
              if (__hasFns(n)) {
                // 含回调的固定节点树：运行时由主题实例渲染，同时生成一份擦除函数的快照供保存期做完整属性校验
                const toCheck = (v) => {
                  if (Array.isArray(v)) return v.filter(c => c != null && c !== false).map(toCheck);
                  if (v != null && typeof v === "object") {
                    const o = {};
                    for (const k of Object.keys(v)) {
                      const kv = v[k];
                      if (typeof kv === "function") o[k + "Fn"] = "__compile_check";
                      else if (k === "args" && Array.isArray(kv)) o.args = kv.map(a => typeof a === "function" ? { "\u0024cb": "__compile_check" } : toCheck(a));
                      else o[k] = toCheck(kv);
                    }
                    return o;
                  }
                  return v;
                };
                checks[name] = toCheck(n);
                globalThis.__ui_slot_fns[name] = () => n;
                dyn.push(name);
              } else {
                slots[name] = n;
              }
            }
          },
          template() { __moved("ui.template", "ui.slot(位置名, ...)：chat.reasoning.header / chat.tool.header / code_block.header"); },
          shapes(cfg) { __merge("shapes", cfg); },
          motion(cfg) { __merge("motion", typeof cfg === "string" ? { scheme: cfg } : cfg); },
          topBar() { __moved("ui.topBar", "ui.slot(\"chat.topbar\", ...)"); },
          inputBar() { __moved("ui.inputBar", "ui.slot(\"chat.input\", ...)"); },
          typography(cfg) { __merge("typography", cfg); },
          messages() { __moved("ui.messages", "ui.style(\"chat.messages\", { padding, spacing })"); },
          userMessage() { __moved("ui.userMessage", "ui.slot(\"chat.message.user\", ...)"); },
          assistantMessage() { __moved("ui.assistantMessage", "ui.slot(\"chat.message.assistant\", ...)"); },
        };
        globalThis.Column = (a, b) => __makeNode("column", a, b);
        globalThis.Row = (a, b) => __makeNode("row", a, b);
        globalThis.FlowRow = (a, b) => __makeNode("flow_row", a, b);
        globalThis.Box = (a, b) => __makeNode("box", a, b);
        globalThis.Popup = (a, b) => __makeNode("popup", a, b);
        globalThis.Surface = (a, b) => __makeNode("surface", a, b);
        globalThis.Card = (a, b) => __makeNode("surface", a, b);
        globalThis.Collapsible = (a, b) => __makeNode("collapsible", a, b);

        globalThis.Avatar = (p) => __makeNode("avatar", p);
        globalThis.AuthorName = (p) => __makeNode("author_name", p);
        globalThis.ModelIcon = (p) => __makeNode("model_icon", p);
        globalThis.ModelName = (p) => __makeNode("model_name", p);
        globalThis.Timestamp = (p) => __makeNode("timestamp", p);
        globalThis.BranchPager = (p) => __makeNode("branch_pager", p);
        globalThis.ReasoningBlock = (p) => __makeNode("reasoning_block", p);
        globalThis.MarkdownBody = (p) => __makeNode("markdown_body", p);
        globalThis.Translation = (p) => __makeNode("translation", p);
        globalThis.EditedFiles = (p) => __makeNode("edited_files", p);
        globalThis.NerdStats = (p) => __makeNode("nerd_stats", p);
        globalThis.Divider = (p) => __makeNode("divider", p);
        globalThis.Spacer = (p) => __makeNode("spacer", typeof p === "number" ? { weight: p } : p);
        globalThis.Canvas = (p) => __makeNode("canvas", p);
        globalThis.FolderBar = (p) => __makeNode("folder_bar", p);
        globalThis.ConversationList = (p) => __makeNode("conversation_list", p);
        globalThis.AssistantPicker = (p) => __makeNode("assistant_picker", p);
        globalThis.Switch = (a, b) => typeof a === "boolean"
          ? __makeNode("switch", Object.assign({}, b || {}, { checked: a }))
          : __makeNode("switch", a);
        globalThis.Slider = (a, b) => typeof a === "number"
          ? __makeNode("slider", Object.assign({}, b || {}, { sliderValue: a }))
          : __makeNode("slider", a);

        // 懒加载列表：LazyColumn({ count 或 keys, item: (index, ctx) => 节点 })，只为看得见的行调用 item
        globalThis.LazyColumn = (p) => __makeNode("lazy_column", p);
        globalThis.LazyRow = (p) => __makeNode("lazy_row", p);
        // 安卓原生视图：NativeView(类名, { onCreate: (view, ctx) => {...} })，宿主在主线程创建后把句柄交给 onCreate
        globalThis.NativeView = (cls, p) => {
          if (typeof cls !== "string" || !cls) throw new TypeError("NativeView 的第一个参数必须是安卓视图类名，如 \"android.webkit.WebView\"");
          if (p != null && (typeof p !== "object" || Array.isArray(p))) throw new TypeError("NativeView 的第二个参数必须是属性对象");
          return __makeNode("native_view", Object.assign({}, p || {}, { viewClass: cls }));
        };
        // 宿主界面函数：Compose(类名, 函数名, [按位置的参数], 属性?)；参数里的节点树作为界面内容，函数作为回调，null = 用默认值
        globalThis.Compose = (cls, fn, args, p) => {
          if (typeof cls !== "string" || !cls) throw new TypeError("Compose 的第一个参数必须是类名字符串（顶层函数所在的类是「文件名Kt」）");
          if (typeof fn !== "string" || !fn) throw new TypeError("Compose 的第二个参数必须是函数名字符串");
          if (!Array.isArray(args)) throw new TypeError("Compose 的第三个参数必须是参数数组（无参数写 []，null = 用函数默认值）");
          if (p != null && (typeof p !== "object" || Array.isArray(p))) throw new TypeError("Compose 的第四个参数必须是属性对象");
          if (p != null && p.children !== undefined) throw new TypeError("Compose 的界面内容参数请直接写在第三个参数数组里，不要在属性对象里写 children");
          const encTop = (v) => {
            if (v === undefined || v === null) return null;
            if (typeof v === "bigint") return { "\u0024long": v.toString() };
            if (globalThis.__java_internal && globalThis.__java_internal.isJ(v)) return v;
            if (Array.isArray(v)) return v.map(encTop);
            return v;
          };
          const children = [];
          const list = args.map((a) => {
            if (a != null && typeof a === "object" && a[__IS_NODE]) {
              children.push(a);
              return { "\u0024child": children.length - 1 };
            }
            return encTop(a);
          });
          return __makeNode("compose", Object.assign({}, p || {}, { composeClass: cls, composeFn: fn, args: list }), children);
        };

        globalThis.Text = __leaf("custom_text", "customText");
        globalThis.CustomText = globalThis.Text;
        globalThis.TextField = __leaf("text_field", "customText");
        globalThis.Button = __leaf("button", "customText");
        globalThis.Badge = __leaf("badge", "customText");
        globalThis.Icon = __leaf("icon", "iconName");
        globalThis.IconButton = __leaf("icon_button", "iconName");
        globalThis.Image = __leaf("image", "src");
        globalThis.Path = __leaf("path", "d");
        globalThis.Host = __leaf("host", "hostBlock");
        globalThis.Video = __leaf("video", "src");
        // 着色器：Shader(AGSL 源码, 属性?, 子节点?)；子节点画在着色器上面
        globalThis.Shader = (src, a, b) => {
          if (typeof src !== "string") throw new TypeError("Shader 的第一个参数必须是 AGSL 源码字符串");
          const { props, children } = __normArgs("shader", a, b);
          return __makeNode("shader", Object.assign({}, props, { shader: src }), children);
        };

        globalThis.ActionBar = (itemsOrProps, maybeProps) => {
          if (Array.isArray(itemsOrProps)) {
            return __makeNode("action_bar", Object.assign({}, maybeProps || {}, { actionItems: itemsOrProps }));
          }
          return __makeNode("action_bar", itemsOrProps);
        };

        globalThis.console = { log() {}, info() {}, warn() {}, error() {}, debug() {} };

        // 数据桥 java / host 只在运行时（ui.slot 的函数、回调里）可用；保存编译时只执行顶层代码，不连宿主
        (() => {
          const stub = (name) => new Proxy({}, { get(_, k) {
            if (typeof k === "symbol") return undefined;
            throw new Error(name + "." + String(k) + " 只能在 ui.slot / ui.page 的函数或回调里使用（顶层代码在保存时执行，那时不连宿主）");
          } });
          globalThis.java = stub("java");
          globalThis.host = stub("host");
          const timerErr = (fn) => () => { throw new Error(fn + " 只能在 ui.slot / ui.page 的运行时函数或回调里使用（顶层代码在保存时执行）"); };
          globalThis.setTimeout = timerErr("setTimeout");
          globalThis.setInterval = timerErr("setInterval");
          globalThis.clearTimeout = () => {};
          globalThis.clearInterval = () => {};
        })();

        // 颜色运算（宿主只提供运算，怎么推导配色由主题自己写）。颜色字符串 #RGB / #ARGB / #RRGGBB / #AARRGGBB，结果为 #AARRGGBB。
        globalThis.color = (() => {
          function parse(c) {
            if (typeof c !== "string") throw new TypeError("颜色必须是 #hex 字符串，实际为 " + JSON.stringify(c));
            let h = c.trim().replace(/^#/, "");
            if (!/^[0-9a-fA-F]+$/.test(h) || [3, 4, 6, 8].indexOf(h.length) < 0) throw new TypeError("不是合法颜色：" + c);
            if (h.length <= 4) h = h.split("").map(x => x + x).join("");
            if (h.length === 6) h = "FF" + h;
            const n = parseInt(h, 16);
            return { a: ((n >>> 24) & 255) / 255, r: ((n >>> 16) & 255) / 255, g: ((n >>> 8) & 255) / 255, b: (n & 255) / 255 };
          }
          function hex(c) {
            const to = v => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, "0").toUpperCase();
            return "#" + to(c.a) + to(c.r) + to(c.g) + to(c.b);
          }
          const lin = v => v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
          const gam = v => v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
          function toOklab(c) {
            const r = lin(c.r), g = lin(c.g), b = lin(c.b);
            const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
            const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
            const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
            return { L: 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, A: 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s, B: 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s, a: c.a };
          }
          function fromOklab(o) {
            const l = Math.pow(o.L + 0.3963377774 * o.A + 0.2158037573 * o.B, 3);
            const m = Math.pow(o.L - 0.1055613458 * o.A - 0.0638541728 * o.B, 3);
            const s = Math.pow(o.L - 0.0894841775 * o.A - 1.2914855480 * o.B, 3);
            return { r: gam(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s), g: gam(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s), b: gam(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s), a: o.a };
          }
          return {
            /** 按比例 t（0～1）从 a 过渡到 b，在 Oklab 空间插值（与 Compose 的 lerp 相同）。 */
            mix(a, b, t) {
              const x = toOklab(parse(a)), y = toOklab(parse(b));
              const k = v => x[v] + (y[v] - x[v]) * t;
              return hex(fromOklab({ L: k("L"), A: k("A"), B: k("B"), a: k("a") }));
            },
            /** 设置不透明度（0～1）。 */
            alpha(c, a) { const p = parse(c); p.a = a; return hex(p); },
            /** 相对亮度（0～1），与 Compose 的 luminance() 相同。 */
            luminance(c) { const p = parse(c); return 0.2126 * lin(p.r) + 0.7152 * lin(p.g) + 0.0722 * lin(p.b); },
            lighten(c, t) { return this.mix(c, "#FFFFFF", t); },
            darken(c, t) { return this.mix(c, "#000000", t); },
            hex(c) { return hex(parse(c)); },
          };
        })();
    """

    /**
     * 引擎版本：解析规则变化时递增，已保存预设的 [UIPreset.compiledCodeHash] 随之失效，
     * 由 [UIPresetCompileSync] 在后台重新编译一次。
     */
    const val ENGINE_VERSION = 14

    /** 代码 + 引擎版本的指纹；与 [UIPreset.compiledCodeHash] 相同表示保存的编译结果是最新的。 */
    fun codeHash(code: String): String = "v$ENGINE_VERSION:" + code.trim().hashCode().toUInt().toString(16) + ":" + code.trim().length

    /**
     * 编译 UI 代码（或 JSON），返回填好各接管区的预设。在后台线程执行，不要在界面绘制过程中调用。
     * 代码出错时抛 [UICodeCompileException]。
     */
    suspend fun compileUICode(code: String, basePreset: UIPreset = UIPreset()): UIPreset {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return UIPreset(
                id = basePreset.id,
                name = basePreset.name,
                description = basePreset.description,
                enabled = basePreset.enabled,
                code = code,
                compiledCodeHash = codeHash(code),
                compileError = null,
            )
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val decoded = try {
                json.decodeFromString<UIPreset>(trimmed)
            } catch (e: Exception) {
                throw UICodeCompileException("无法解析预设 JSON：${e.message}", e)
            }
            val innerCode = decoded.code.trim()
            if (innerCode.isNotEmpty() && !innerCode.startsWith("{")) {
                return compileUICode(
                    innerCode,
                    decoded.copy(id = basePreset.id, enabled = basePreset.enabled),
                )
            }
            return decoded.copy(
                id = basePreset.id,
                enabled = basePreset.enabled,
                compiledCodeHash = null,
                compileError = null,
            )
        }

        val specJsonString: String = evaluateUiCode(trimmed)

        val root = json.parseToJsonElement(specJsonString).jsonObject
        // 代码是唯一来源：各接管区全部由这次编译产生（不沿用 basePreset 里旧的编译结果，否则代码里删掉的位置会残留）
        var result = UIPreset(
            id = basePreset.id,
            name = basePreset.name,
            description = basePreset.description,
            enabled = basePreset.enabled,
            code = code,
            compiledCodeHash = codeHash(code),
            compileError = null,
        )

        root["theme"]?.jsonObject?.let { t ->
            result = result.copy(
                backgroundSection = parseBackgroundSection(t, result.backgroundSection),
            )
        }

        val fontObj = root["font"]?.jsonObject
        val shapesObj = root["shapes"]?.jsonObject
        val motionObj = root["motion"]?.jsonObject
        if (fontObj != null || shapesObj != null || motionObj != null) {
            result = result.copy(
                globalSection = parseGlobalSection(fontObj, shapesObj, motionObj, result.globalSection),
            )
        }

        root["styles"]?.jsonObject?.let { st ->
            result = result.copy(
                componentStyles = result.componentStyles + st.mapValues { (name, v) -> parseComponentStyle(name, v) },
            )
        }

        root["slots"]?.jsonObject?.let { sl ->
            result = result.copy(slots = result.slots + sl.mapValues { (name, v) -> parseMessageNode(v.objectOrFail("ui.slot(\"$name\")")) })
        }
        root["dynamicSlotChecks"]?.jsonObject?.forEach { (name, v) ->
            parseMessageNode(v.objectOrFail("ui.slot(\"$name\")"), fromRuntime = true)
        }
        // ui.page(名, { content }) / ui.slot("page:名", ...)：整页替换，页面名按 Screen 子类名校验
        ((root["slots"] as? JsonObject)?.keys.orEmpty() + (root["dynamicSlots"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty())
            .filter { it.startsWith("page:") }
            .forEach { slot ->
                val route = slot.removePrefix("page:")
                val known = knownRouteNames()
                if (route != "*" && known != null && route !in known) {
                    fail("整页替换没有页面「$route」，可用：* / ${known.sorted().joinToString(" / ")}")
                }
            }
        root["dynamicSlots"]?.jsonArray?.let { arr ->
            val names = arr.map { it.jsonPrimitive.content }
            result = result.copy(
                dynamicSlots = (result.dynamicSlots + names).distinct(),
                slots = result.slots - names.toSet(),
            )
        }

        root["pages"]?.jsonObject?.let { pg ->
            result = result.copy(
                pages = result.pages + pg.mapValues { (route, v) -> parsePageSpec(route, v) },
            )
        }
        root["transition"]?.jsonObject?.let { tr ->
            result = result.copy(transitions = parseTransitionSpec(tr))
        }

        root["typography"]?.jsonObject?.let { tp ->
            result = result.copy(
                typographySection = parseTypographySection(tp, result.typographySection),
            )
        }

        return result
    }

    private fun parseBackgroundSection(obj: JsonObject, base: UIBackgroundSection): UIBackgroundSection {
        WALLPAPER_KEYS.firstOrNull { it in obj }?.let {
            fail("ui.theme 不再支持「$it」：聊天背景用 ui.slot(\"chat.background\", (bg) => ...) 自己画（数据里有助手壁纸 bg.wallpaper），模糊用节点的 blurRadius")
        }
        REMOVED_THEME_KEYS.firstOrNull { it in obj }?.let {
            fail("ui.theme 不再支持「$it」：用 seed（主色生成整套配色）与 colors（逐个指定颜色槽位）")
        }
        obj.keys.firstOrNull { it !in ALLOWED_THEME_KEYS }?.let {
            fail("ui.theme 不支持属性「$it」，可用：seed / dark / colors / enabled")
        }
        return base.copy(
            enabled = obj["enabled"]?.boolOrFail("ui.theme 的 enabled") ?: true,
            seedColorHex = obj["seed"]?.let { v ->
                if (v is JsonNull) ""
                else v.stringOrFail("ui.theme 的 seed").also { if (it.isNotBlank() && parseHexColorOrNull(it) == null) fail("ui.theme 的 seed 不是合法颜色：$it") }
            } ?: base.seedColorHex,
            dark = if ("dark" in obj) {
                val dv = obj["dark"]!!
                if (dv is JsonNull) null else dv.boolOrFail("ui.theme 的 dark")
            } else base.dark,
            colorOverrides = obj["colors"]?.let { base.colorOverrides + parseColorOverrides(it) } ?: base.colorOverrides,
        )
    }

    private val ALLOWED_THEME_KEYS = setOf("enabled", "seed", "dark", "colors")

    private val WALLPAPER_KEYS = listOf(
        "overrideWallpaperUrl", "wallpaperUrl", "wallpaper", "useMeshGradient", "meshGradient", "wallpaperOpacity",
        "wallpaperBlurRadiusDp", "wallpaperBlur", "scrimTopAlpha", "scrimBottomAlpha", "scrimColorHex", "scrimColor",
    )

    private val REMOVED_THEME_KEYS = listOf(
        "pageBackground", "pageBackgroundColorHex", "primary", "primaryColorHex", "accent", "text", "textColorHex",
        "secondaryText", "secondaryTextColorHex", "muted", "surface", "surfaceColorHex", "surfaceBright", "card",
        "surfaceContainerLow", "surfaceContainer", "surfaceContainerColorHex", "drawer", "primaryContainer",
        "primaryContainerColorHex", "accentSoft", "secondaryContainer", "secondaryContainerColorHex", "outline",
        "outlineColorHex", "border", "onBackground", "onSurface", "onSurfaceVariant", "background", "bg",
        "monochromeAvatar", "monochromeAvatarFallback",
    )

    private fun fail(message: String): Nothing = throw UICodeCompileException(message)

    private fun JsonElement.objectOrFail(what: String): JsonObject =
        this as? JsonObject ?: fail("$what 必须是对象")

    private fun JsonElement.numberOrFail(what: String): Float =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull ?: fail("$what 必须是数字，实际为 $this")

    private fun JsonElement.stringOrFail(what: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$what 必须是字符串，实际为 $this")

    private fun JsonElement.boolOrFail(what: String): Boolean =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail("$what 必须是 true / false，实际为 $this")

    private fun parseColorOverrides(el: JsonElement): Map<String, String> {
        val obj = el.objectOrFail("ui.theme 的 colors")
        return obj.mapValues { (name, value) ->
            if (name !in UI_COLOR_SLOTS) fail("ui.theme 的 colors 里没有颜色槽位「$name」，可用：${UI_COLOR_SLOTS.joinToString()}")
            val hex = value.stringOrFail("colors.$name")
            parseHexColorOrNull(hex) ?: fail("colors.$name 不是合法颜色：$hex（支持 #RGB / #ARGB / #RRGGBB / #AARRGGBB）")
            hex
        }
    }

    private fun parseFontFamilyValue(el: JsonElement, what: String): String {
        val v = el.stringOrFail(what)
        if (!isValidUIFontFamilySpec(v)) {
            fail("$what 不支持「$v」，可用：default / sans-serif / serif / monospace / cursive / chat / file:<文件路径>")
        }
        return v
    }

    private fun parseGlobalSection(
        font: JsonObject?,
        shapes: JsonObject?,
        motion: JsonObject?,
        base: UIGlobalSection,
    ): UIGlobalSection {
        var r = base.copy(enabled = true)
        font?.forEach { (k, v) ->
            r = when (k) {
                "family", "fontFamily" -> r.copy(fontFamily = parseFontFamilyValue(v, "ui.font 的 $k"))
                "scale", "fontScale" -> r.copy(fontScale = v.numberOrFail("ui.font 的 $k").also {
                    if (it <= 0f || it > 4f) fail("ui.font 的 $k 应在 0～4 之间，实际为 $it")
                })
                "letterSpacing", "letterSpacingEm" -> r.copy(letterSpacingEm = v.numberOrFail("ui.font 的 $k"))
                "styles" -> r.copy(textStyles = r.textStyles + v.objectOrFail("ui.font 的 styles").mapValues { (name, so) ->
                    if (name !in UI_TEXT_STYLES) fail("ui.font 的 styles 里没有文字样式「$name」，可用：${UI_TEXT_STYLES.joinToString()}")
                    parseTextStyleOverride(so.objectOrFail("styles.$name"), "styles.$name")
                })
                else -> fail("ui.font 不支持属性「$k」，可用：family / scale / letterSpacing / styles")
            }
        }
        shapes?.forEach { (k, v) ->
            r = when (k) {
                "style" -> r.copy(shapeStyle = v.stringOrFail("ui.shapes 的 style").also {
                    if (it != "rounded" && it != "cut") fail("ui.shapes 的 style 只能是 rounded 或 cut，实际为 $it")
                })
                in UI_SHAPE_KEYS -> r.copy(shapeCorners = r.shapeCorners + (k to v.numberOrFail("ui.shapes 的 $k").also {
                    if (it < 0f) fail("ui.shapes 的 $k 不能为负数")
                }))
                else -> fail("ui.shapes 不支持属性「$k」，可用：style / ${UI_SHAPE_KEYS.joinToString(" / ")}")
            }
        }
        motion?.forEach { (k, v) ->
            r = when (k) {
                "scheme" -> r.copy(motionScheme = v.stringOrFail("ui.motion 的 scheme").also {
                    if (it != "expressive" && it != "standard") fail("ui.motion 只能是 expressive 或 standard，实际为 $it")
                })
                else -> fail("ui.motion 不支持属性「$k」，可用：scheme")
            }
        }
        return r
    }

    private fun parseColorValue(el: JsonElement, what: String, allowEmpty: Boolean = false): String {
        val v = el.stringOrFail(what)
        if (allowEmpty && v.isBlank()) return ""
        if (parseHexColorOrNull(v) == null && !isSemanticUIColorName(v)) {
            fail("$what 不是合法颜色：$v（支持 #hex 或语义色名，如 primary、surfaceContainer）")
        }
        return v
    }

    private fun parsePageSpec(route: String, el: JsonElement): UIPageSpec {
        val known = knownRouteNames()
        if (route != "*" && known != null && route !in known) {
            fail("ui.page 没有页面「$route」，可用：* / ${known.sorted().joinToString(" / ")}")
        }
        var spec = UIPageSpec()
        el.objectOrFail("ui.page(\"$route\")").forEach { (k, v) ->
            spec = when (k) {
                "background" -> spec.copy(background = parseMessageNode(v.objectOrFail("ui.page(\"$route\").background")))
                "foreground" -> spec.copy(foreground = parseMessageNode(v.objectOrFail("ui.page(\"$route\").foreground")))
                "colors" -> spec.copy(colors = parseColorOverrides(v))
                else -> fail("ui.page(\"$route\") 不支持属性「$k」，可用：background / foreground / colors / content")
            }
        }
        return spec
    }

    /** 宿主全部页面名（`Screen` 的子类名）；拿不到（如混淆后丢失内部类信息）时返回 null，不校验。 */
    private fun knownRouteNames(): Set<String>? = runCatching {
        me.rerere.rikkahub.Screen::class.java.declaredClasses.map { it.simpleName }.filter { it.isNotEmpty() }.toSet()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun parseTransitionSpec(obj: JsonObject): UITransitionSpec {
        fun side(el: JsonElement, what: String): UITransitionSide {
            var s = UITransitionSide()
            el.objectOrFail(what).forEach { (k, v) ->
                s = when (k) {
                    "fade" -> s.copy(fade = v.boolOrFail("$what.fade"))
                    "slideX" -> s.copy(slideX = v.numberOrFail("$what.slideX"))
                    "slideY" -> s.copy(slideY = v.numberOrFail("$what.slideY"))
                    "scale" -> s.copy(scale = v.numberOrFail("$what.scale"))
                    "duration" -> s.copy(durationMs = v.numberOrFail("$what.duration").toInt().also { if (it < 0) fail("$what.duration 不能为负数") })
                    "delay" -> s.copy(delayMs = v.numberOrFail("$what.delay").toInt().also { if (it < 0) fail("$what.delay 不能为负数") })
                    else -> fail("$what 不支持属性「$k」，可用：fade / slideX / slideY / scale / duration / delay")
                }
            }
            return s
        }
        fun pair(el: JsonElement, what: String): UITransitionPair {
            var p = UITransitionPair()
            el.objectOrFail(what).forEach { (k, v) ->
                p = when (k) {
                    "enter" -> p.copy(enter = side(v, "$what.enter"))
                    "exit" -> p.copy(exit = side(v, "$what.exit"))
                    else -> fail("$what 不支持属性「$k」，可用：enter / exit")
                }
            }
            return p
        }
        var spec = UITransitionSpec()
        obj.forEach { (k, v) ->
            spec = when (k) {
                "push" -> spec.copy(push = pair(v, "ui.transition.push"))
                "pop" -> spec.copy(pop = pair(v, "ui.transition.pop"))
                else -> fail("ui.transition 不支持属性「$k」，可用：push / pop")
            }
        }
        return spec
    }

    private fun parseComponentStyle(name: String, el: JsonElement): UIComponentStyle {
        val what = "ui.style(\"$name\")"
        var o = UIComponentStyle()
        el.objectOrFail(what).forEach { (k, v) ->
            o = when (k) {
                "bg", "background" -> o.copy(background = parseColorValue(v, "$what.$k"))
                "color" -> o.copy(color = parseColorValue(v, "$what.$k"))
                "secondaryColor" -> o.copy(secondaryColor = parseColorValue(v, "$what.$k"))
                "corner", "corners", "cornerDp" -> o.copy(cornerDp = v.numberOrFail("$what.$k"))
                "border" -> {
                    val arr = v as? JsonArray ?: fail("$what.border 必须是 [宽度, 颜色]")
                    if (arr.size != 2) fail("$what.border 必须是 [宽度, 颜色]")
                    o.copy(borderWidthDp = arr[0].numberOrFail("$what.border[0]"), borderColor = parseColorValue(arr[1], "$what.border[1]"))
                }
                "padding", "margin" -> {
                    val (h, vv) = when (v) {
                        is JsonArray -> {
                            if (v.size != 2) fail("$what.$k 写一个数或 [水平, 垂直]")
                            v[0].numberOrFail("$what.$k[0]") to v[1].numberOrFail("$what.$k[1]")
                        }
                        else -> v.numberOrFail("$what.$k").let { it to it }
                    }
                    if (k == "padding") o.copy(paddingHorizontalDp = h, paddingVerticalDp = vv)
                    else o.copy(marginHorizontalDp = h, marginVerticalDp = vv)
                }
                "fontSize" -> o.copy(fontSizeSp = v.numberOrFail("$what.$k"))
                "width" -> o.copy(widthDp = v.numberOrFail("$what.$k"))
                "spacing" -> o.copy(spacingDp = v.numberOrFail("$what.$k"))
                "opacity" -> o.copy(opacity = v.numberOrFail("$what.$k").also { if (it !in 0f..1f) fail("$what.opacity 必须在 0～1 之间") })
                else -> fail("$what 不支持属性「$k」，可用：bg / color / secondaryColor / corner / border / padding / margin / fontSize / opacity / width / spacing")
            }
        }
        return o
    }

    private fun parseTextStyleOverride(obj: JsonObject, what: String): UITextStyleOverride {
        var o = UITextStyleOverride()
        obj.forEach { (k, v) ->
            o = when (k) {
                "size", "fontSize" -> o.copy(size = v.numberOrFail("$what.$k"))
                "lineHeight" -> o.copy(lineHeight = v.numberOrFail("$what.$k"))
                "weight", "fontWeight" -> o.copy(weight = v.numberOrFail("$what.$k").toInt().also {
                    if (it !in 1..1000) fail("$what.$k 应在 1～1000 之间，实际为 $it")
                })
                "letterSpacing", "letterSpacingEm" -> o.copy(letterSpacingEm = v.numberOrFail("$what.$k"))
                "family", "fontFamily" -> o.copy(fontFamily = parseFontFamilyValue(v, "$what.$k"))
                "italic" -> o.copy(italic = v.boolOrFail("$what.$k"))
                else -> fail("$what 不支持属性「$k」，可用：size / lineHeight / weight / letterSpacing / family / italic")
            }
        }
        return o
    }

    private val TYPOGRAPHY_MOVED_KEYS = mapOf(
        "codeBlock" to "代码块外观改用 ui.style(\"code_block\", { bg, corner, border })",
        "reasoning" to "思考框外观改用 ui.style(\"reasoning\", { bg, color（标题与图标）, secondaryColor（正文）, corner, border, opacity })",
    )

    private fun parseTypographySection(obj: JsonObject, base: UITypographySection): UITypographySection {
        val known = listOf(
            "enabled", "fontSizeMultiplier", "lineHeightMultiplier", "blockSpacingDp", "firstLineIndentSp",
            "bodyTextColor", "userBodyTextColor", "quoteHighlightEnabled", "quoteTextColor", "quoteFontWeightBold",
            "italicTextColor", "boldTextColor", "headingColor", "blockquoteBarColor", "blockquoteBgColor", "inlineCodeColor",
        )
        obj.keys.forEach { k ->
            TYPOGRAPHY_MOVED_KEYS.entries.firstOrNull { k.startsWith(it.key) }?.let { fail("ui.typography 不再支持「$k」：${it.value}") }
            if (k !in known && k.removeSuffix("Hex") !in known) fail("ui.typography 不支持属性「$k」，可用：${known.joinToString()}")
        }
        fun numOrNull(k: String): Float? = obj[k]?.let { if (it is JsonNull) null else it.numberOrFail("ui.typography.$k") }
        fun boolOrNull(k: String): Boolean? = obj[k]?.let { if (it is JsonNull) null else it.boolOrFail("ui.typography.$k") }
        fun colorKey(hexKey: String, shortKey: String, fallback: String): String {
            val el = obj[hexKey] ?: obj[shortKey] ?: return fallback
            if (el is JsonNull) return ""
            return parseColorValue(el, "ui.typography.$shortKey", allowEmpty = true)
        }
        return base.copy(
            enabled = boolOrNull("enabled") ?: true,
            fontSizeMultiplier = numOrNull("fontSizeMultiplier")?.also {
                if (it <= 0f || it > 4f) fail("ui.typography.fontSizeMultiplier 应在 0～4 之间，实际为 $it")
            } ?: base.fontSizeMultiplier,
            lineHeightMultiplier = if ("lineHeightMultiplier" in obj) numOrNull("lineHeightMultiplier")?.also {
                if (it <= 0f) fail("ui.typography.lineHeightMultiplier 必须大于 0")
            } else base.lineHeightMultiplier,
            blockSpacingDp = if ("blockSpacingDp" in obj) numOrNull("blockSpacingDp")?.also {
                if (it < 0f) fail("ui.typography.blockSpacingDp 不能为负数")
            } else base.blockSpacingDp,
            firstLineIndentSp = numOrNull("firstLineIndentSp")?.also {
                if (it < 0f) fail("ui.typography.firstLineIndentSp 不能为负数")
            } ?: base.firstLineIndentSp,
            bodyTextColorHex = colorKey("bodyTextColorHex", "bodyTextColor", base.bodyTextColorHex),
            userBodyTextColorHex = colorKey("userBodyTextColorHex", "userBodyTextColor", base.userBodyTextColorHex),
            quoteHighlightEnabled = if ("quoteHighlightEnabled" in obj) boolOrNull("quoteHighlightEnabled") else base.quoteHighlightEnabled,
            quoteTextColorHex = colorKey("quoteTextColorHex", "quoteTextColor", base.quoteTextColorHex),
            quoteFontWeightBold = boolOrNull("quoteFontWeightBold") ?: base.quoteFontWeightBold,
            italicTextColorHex = colorKey("italicTextColorHex", "italicTextColor", base.italicTextColorHex),
            boldTextColorHex = colorKey("boldTextColorHex", "boldTextColor", base.boldTextColorHex),
            headingColorHex = colorKey("headingColorHex", "headingColor", base.headingColorHex),
            blockquoteBarColorHex = colorKey("blockquoteBarColorHex", "blockquoteBarColor", base.blockquoteBarColorHex),
            blockquoteBgColorHex = colorKey("blockquoteBgColorHex", "blockquoteBgColor", base.blockquoteBgColorHex),
            inlineCodeColorHex = colorKey("inlineCodeColorHex", "inlineCodeColor", base.inlineCodeColorHex),
        )
    }

    private val ALLOWED_NODE_KEYS = setOf(
        "id", "name", "enabled", "type", "children", "visibleWhen",
        "horizontalAlignment", "align", "verticalAlignment", "vAlign",
        "spacingDp", "spacing", "crossSpacingDp", "crossSpacing", "weight", "fillMaxWidth", "fillMaxHeight", "fillMaxSize", "compact",
        "maxWidthFraction", "maxWidth", "width", "widthDp", "height", "heightDp",
        "padding", "paddingStartDp", "paddingTopDp", "paddingEndDp", "paddingBottomDp",
        "margin", "marginStartDp", "marginTopDp", "marginEndDp", "marginBottomDp",
        "corners", "corner", "cornerTopStartDp", "cornerTopEndDp", "cornerBottomEndDp", "cornerBottomStartDp",
        "border", "borderWidthDp", "borderColorHex", "borderColor",
        "backgroundColorHex", "bg", "background", "gradient", "gradientEndColorHex", "gradientAngleDeg",
        "opacity", "alpha", "shadowElevationDp", "shadow", "enableHazeBlur", "blur", "blurRadius", "blurRadiusDp",
        "zIndex", "clip", "blendMode", "pointerEvents", "scroll", "insets", "haptic",
        "textColorHex", "color", "textColor", "fontSizeSp", "fontSize", "fontWeightBold", "bold", "fontWeight",
        "italic", "fontFamily", "family", "maxLines", "letterSpacing", "letterSpacingEm", "lineHeight", "lineHeightSp", "textAlign",
        "offsetXDp", "offsetX", "offsetYDp", "offsetY", "scale", "rotationDeg", "rotate",
        "avatarSizeDp", "size", "avatarCornerDp", "avatarCorner", "iconSizeDp", "iconSize",
        "customText", "text", "label", "value", "placeholder", "hint", "singleLine",
        "checked", "sliderValue", "min", "minValue", "max", "maxValue", "steps", "modal",
        "iconName", "icon",
        "actionName", "onClick", "action", "longClickAction", "onLongClick", "doubleClickAction", "onDoubleClick",
        "onChange", "onSubmit", "onDismiss",
        "canvasDrawType", "drawType", "collapsibleTitle", "title", "collapsibleDefaultExpanded", "defaultExpanded",
        "actionItems", "src", "url", "imageSrc", "contentScale",
        "d", "path", "pathData", "viewBox", "viewBoxWidth", "viewBoxHeight", "strokeWidth", "strokeCap",
        "hostBlock", "animate", "animations", "press",
        "shader", "shaderSrc", "uniforms", "fallback", "wakeDuration", "loop", "muted",
        "count", "keys", "itemSize", "refreshing", "item", "onEndReached",
        "onReorder", "onRefresh", "swipe", "onSwipe", "zoom",
        "composeClass", "composeFn", "args", "viewClass", "onCreate", "onRelease",
    )

    /** 运行时由 UIThemeRuntime 编码回调后产生的内部编号字段（不允许在顶层用户代码或静态 JSON 里直接手写）。 */
    private val RUNTIME_CALLBACK_KEYS = setOf(
        "onClickFn", "onLongClickFn", "onDoubleClickFn", "onChangeFn", "onSubmitFn", "onDismissFn",
        "itemFn", "onEndReachedFn", "onReorderFn", "onRefreshFn", "onSwipeFn", "onCreateFn", "onReleaseFn",
    )

    /** 只能写成函数的回调（运行时由主题实例编号为 `xxxFn`）；出现在节点 JSON 里说明写的不是函数。 */
    private val FUNCTION_ONLY_KEYS = listOf("item", "onEndReached", "onReorder", "onRefresh", "onSwipe", "onCreate", "onRelease")

    private val UI_SWIPE_DIRECTIONS = listOf("left", "right", "horizontal", "up", "down", "vertical")

    private fun parseSwipe(el: JsonElement, what: String): UISwipeSpec? {
        fun dir(raw: String) = raw.lowercase().also {
            if (it !in UI_SWIPE_DIRECTIONS) fail("$what 的方向只能是 ${UI_SWIPE_DIRECTIONS.joinToString(" / ")}，实际为 $raw")
        }
        return when (el) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                el.booleanOrNull == false -> null
                el.isString -> UISwipeSpec(direction = dir(el.content))
                else -> fail("$what 写方向字符串或 { direction, follow, dismiss, threshold }")
            }
            is JsonObject -> {
                el.keys.firstOrNull { it !in setOf("direction", "follow", "dismiss", "threshold") }?.let {
                    fail("$what 不支持属性「$it」，可用：direction / follow / dismiss / threshold")
                }
                UISwipeSpec(
                    direction = el["direction"]?.let { dir(it.stringOrFail("$what.direction")) } ?: "horizontal",
                    follow = el["follow"]?.boolOrFail("$what.follow") ?: true,
                    dismiss = el["dismiss"]?.boolOrFail("$what.dismiss") ?: false,
                    threshold = el["threshold"]?.numberOrFail("$what.threshold")?.also {
                        if (it <= 0f || it > 1f) fail("$what.threshold 应在 0～1 之间（位移占节点宽 / 高的比例）")
                    } ?: 0.35f,
                )
            }
            else -> fail("$what 写方向字符串或 { direction, follow, dismiss, threshold }")
        }
    }

    private fun parseZoom(el: JsonElement, what: String): UIZoomSpec? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> when (el.booleanOrNull) {
            true -> UIZoomSpec()
            false -> null
            null -> fail("$what 写 true 或 { min, max }")
        }
        is JsonObject -> {
            el.keys.firstOrNull { it != "min" && it != "max" }?.let { fail("$what 不支持属性「$it」，可用：min / max") }
            val min = el["min"]?.numberOrFail("$what.min") ?: 1f
            val max = el["max"]?.numberOrFail("$what.max") ?: 4f
            if (min <= 0f || max < min) fail("$what 需要 0 < min <= max")
            UIZoomSpec(min, max)
        }
        else -> fail("$what 写 true 或 { min, max }")
    }

    /** `Compose` 的参数数组：校验节点树与回调只能作为顶层参数。 */
    private fun validateComposeArgs(arr: JsonArray, childCount: Int, what: String) {
        fun check(el: JsonElement, top: Boolean) {
            when (el) {
                is JsonArray -> el.forEach { check(it, false) }
                is JsonObject -> {
                    val child = el["\$child"]
                    if (child != null) {
                        if (!top) fail("$what 里的界面内容（节点树）只能直接作为一个参数，不能放在数组或对象里")
                        val i = (child as? JsonPrimitive)?.content?.toIntOrNull()
                        if (i == null || i !in 0 until childCount) fail("$what 引用了不存在的子节点 $child")
                    } else if ("\$cb" in el) {
                        if (!top) fail("$what 里的回调函数只能直接作为一个参数；若需放在数组或对象里，请用 java.proxy(接口名, fn) 指定接口")
                    } else if ("\$j" !in el) {
                        el.values.forEach { check(it, false) }
                    }
                }
                else -> Unit
            }
        }
        arr.forEach { check(it, true) }
    }

    private fun parseMessageNode(obj: JsonObject, fromRuntime: Boolean = false): UIMessageNode {
        val rawType = obj["type"]?.stringOrFail("节点.type")?.lowercase() ?: fail("节点缺少 type")
        val what = "$rawType(${(obj["id"] as? JsonPrimitive)?.contentOrNull ?: ""})"
        obj.keys.firstOrNull { it !in ALLOWED_NODE_KEYS && (!fromRuntime || it !in RUNTIME_CALLBACK_KEYS) }?.let { unknown ->
            fail("$what 不支持属性「$unknown」")
        }

        // 旧写法的业务节点（Avatar()、MarkdownBody()、ConversationList()……）就是同名的宿主原样块
        val legacyBlock = rawType.takeIf { it in LEGACY_BLOCK_TYPES }
        val type = if (legacyBlock != null) UIMessageNodeType.HOST else when (rawType) {
            "column" -> UIMessageNodeType.COLUMN
            "row" -> UIMessageNodeType.ROW
            "flow_row" -> UIMessageNodeType.FLOW_ROW
            "box" -> UIMessageNodeType.BOX
            "popup" -> UIMessageNodeType.POPUP
            "surface", "card" -> UIMessageNodeType.SURFACE
            "collapsible" -> UIMessageNodeType.COLLAPSIBLE
            "avatar" -> UIMessageNodeType.AVATAR
            "author_name" -> UIMessageNodeType.AUTHOR_NAME
            "model_icon" -> UIMessageNodeType.MODEL_ICON
            "model_name" -> UIMessageNodeType.MODEL_NAME
            "timestamp" -> UIMessageNodeType.TIMESTAMP
            "branch_pager" -> UIMessageNodeType.BRANCH_PAGER
            "reasoning_block" -> UIMessageNodeType.REASONING_BLOCK
            "markdown_body" -> UIMessageNodeType.MARKDOWN_BODY
            "translation" -> UIMessageNodeType.TRANSLATION
            "edited_files" -> UIMessageNodeType.EDITED_FILES
            "nerd_stats" -> UIMessageNodeType.NERD_STATS
            "action_bar" -> UIMessageNodeType.ACTION_BAR
            "custom_text", "text" -> UIMessageNodeType.CUSTOM_TEXT
            "text_field" -> UIMessageNodeType.TEXT_FIELD
            "switch" -> UIMessageNodeType.SWITCH
            "slider" -> UIMessageNodeType.SLIDER
            "icon" -> UIMessageNodeType.ICON
            "button" -> UIMessageNodeType.BUTTON
            "icon_button" -> UIMessageNodeType.ICON_BUTTON
            "badge" -> UIMessageNodeType.BADGE
            "canvas" -> UIMessageNodeType.CANVAS
            "folder_bar" -> UIMessageNodeType.FOLDER_BAR
            "conversation_list" -> UIMessageNodeType.CONVERSATION_LIST
            "assistant_picker" -> UIMessageNodeType.ASSISTANT_PICKER
            "divider" -> UIMessageNodeType.DIVIDER
            "spacer" -> UIMessageNodeType.SPACER
            "image" -> UIMessageNodeType.IMAGE
            "path" -> UIMessageNodeType.PATH
            "host" -> UIMessageNodeType.HOST
            "shader" -> UIMessageNodeType.SHADER
            "video" -> UIMessageNodeType.VIDEO
            "lazy_column" -> UIMessageNodeType.LAZY_COLUMN
            "lazy_row" -> UIMessageNodeType.LAZY_ROW
            "compose" -> UIMessageNodeType.COMPOSE
            "native_view" -> UIMessageNodeType.NATIVE_VIEW
            else -> fail("未知的组件类型「$rawType」")
        }

        fun optStr(vararg keys: String): String? {
            for (k in keys) {
                val el = obj[k] ?: continue
                if (el is JsonNull) continue
                return el.stringOrFail("$what.$k")
            }
            return null
        }

        fun optNum(vararg keys: String): Float? {
            for (k in keys) {
                val el = obj[k] ?: continue
                if (el is JsonNull) continue
                return el.numberOrFail("$what.$k")
            }
            return null
        }

        fun optBool(vararg keys: String): Boolean? {
            for (k in keys) {
                val el = obj[k] ?: continue
                if (el is JsonNull) continue
                return el.boolOrFail("$what.$k")
            }
            return null
        }

        fun optColor(vararg keys: String): String? {
            for (k in keys) {
                val el = obj[k] ?: continue
                if (el is JsonNull) continue
                return parseColorValue(el, "$what.$k", allowEmpty = true)
            }
            return null
        }

        // 条件是接入点数据的字段名（可加 ! 取反），不在宿主登记
        val visibleWhen = optStr("visibleWhen")?.trim()?.also {
            if (it.isNotEmpty() && !VISIBLE_WHEN_PATTERN.matches(it)) {
                fail("$what.visibleWhen 应为数据字段名（可加 ! 取反、用 . 取子字段），如 \"pinned\"、\"!loading\"，实际为 $it")
            }
        } ?: ""

        val hAlign = optStr("horizontalAlignment", "align")?.let { raw ->
            when (raw.lowercase()) {
                "start", "left" -> UIHorizontalAlignment.START
                "center" -> UIHorizontalAlignment.CENTER
                "end", "right" -> UIHorizontalAlignment.END
                "space_between", "between" -> UIHorizontalAlignment.SPACE_BETWEEN
                else -> fail("$what.align 只能是 start / center / end / space_between，实际为 $raw")
            }
        } ?: UIHorizontalAlignment.START

        val vAlign = optStr("verticalAlignment", "vAlign")?.let { raw ->
            when (raw.lowercase()) {
                "top" -> UIVerticalAlignment.TOP
                "center" -> UIVerticalAlignment.CENTER
                "bottom" -> UIVerticalAlignment.BOTTOM
                else -> fail("$what.vAlign 只能是 top / center / bottom，实际为 $raw")
            }
        } ?: UIVerticalAlignment.CENTER

        val (padS, padT, padE, padB) = obj.quadNumbers(
            what = what,
            arrayKeys = listOf("padding"),
            sKey = "paddingStartDp",
            tKey = "paddingTopDp",
            eKey = "paddingEndDp",
            bKey = "paddingBottomDp",
        )
        val (marS, marT, marE, marB) = obj.quadNumbers(
            what = what,
            arrayKeys = listOf("margin"),
            sKey = "marginStartDp",
            tKey = "marginTopDp",
            eKey = "marginEndDp",
            bKey = "marginBottomDp",
        )
        val (corTS, corTE, corBE, corBS) = obj.quadNumbers(
            what = what,
            arrayKeys = listOf("corners", "corner"),
            sKey = "cornerTopStartDp",
            tKey = "cornerTopEndDp",
            eKey = "cornerBottomEndDp",
            bKey = "cornerBottomStartDp",
        )

        var borderWidth = optNum("borderWidthDp") ?: 0f
        var borderColor = optColor("borderColorHex", "borderColor") ?: ""
        obj["border"]?.let { b ->
            val arr = b as? JsonArray ?: fail("$what.border 必须是 [宽度, 颜色]")
            if (arr.size != 2) fail("$what.border 必须是 [宽度, 颜色]")
            borderWidth = arr[0].numberOrFail("$what.border[0]")
            borderColor = parseColorValue(arr[1], "$what.border[1]", allowEmpty = true)
        }

        var bgColor = optColor("backgroundColorHex", "bg", "background") ?: ""
        var gradEnd = optColor("gradientEndColorHex") ?: ""
        var gradAngle = optNum("gradientAngleDeg") ?: 0f
        obj["gradient"]?.let { g ->
            val arr = g as? JsonArray ?: fail("$what.gradient 必须是 [起始色, 结束色] 或 [起始色, 结束色, 角度]")
            if (arr.size !in 2..3) fail("$what.gradient 写 [起始色, 结束色] 或 [起始色, 结束色, 角度]")
            bgColor = parseColorValue(arr[0], "$what.gradient[0]")
            gradEnd = parseColorValue(arr[1], "$what.gradient[1]")
            if (arr.size >= 3) gradAngle = arr[2].numberOrFail("$what.gradient[2]")
        }

        val actionItems = obj["actionItems"]?.let { ai ->
            val arr = ai as? JsonArray ?: fail("$what.actionItems 必须是字符串数组")
            arr.mapIndexed { idx, el ->
                val raw = el.stringOrFail("$what.actionItems[$idx]").lowercase()
                when (raw) {
                    "copy" -> UIMessageActionType.COPY
                    "regenerate" -> UIMessageActionType.REGENERATE
                    "edit" -> UIMessageActionType.EDIT
                    "parts_editor" -> UIMessageActionType.PARTS_EDITOR
                    "tts" -> UIMessageActionType.TTS
                    "translate" -> UIMessageActionType.TRANSLATE
                    "fork" -> UIMessageActionType.FORK
                    "favorite" -> UIMessageActionType.FAVORITE
                    "webview" -> UIMessageActionType.WEBVIEW
                    "more" -> UIMessageActionType.MORE
                    "branch_selector" -> UIMessageActionType.BRANCH_SELECTOR
                    "timestamp" -> UIMessageActionType.TIMESTAMP
                    else -> fail("$what.actionItems 不支持「$raw」，可用：copy / regenerate / edit / parts_editor / tts / translate / fork / favorite / webview / more / branch_selector / timestamp")
                }
            }
        } ?: listOf(
            UIMessageActionType.COPY,
            UIMessageActionType.REGENERATE,
            UIMessageActionType.TTS,
            UIMessageActionType.TRANSLATE,
            UIMessageActionType.MORE,
            UIMessageActionType.BRANCH_SELECTOR,
        )

        val children = obj["children"]?.let { ch ->
            val arr = ch as? JsonArray ?: fail("$what.children 必须是数组")
            arr.mapIndexed { idx, el -> parseMessageNode(el.objectOrFail("$what.children[$idx]"), fromRuntime) }
        } ?: emptyList()

        val viewBox: Pair<Float, Float>? = obj["viewBox"]?.let { vb ->
            when (vb) {
                is JsonArray -> {
                    if (vb.size != 2) fail("$what.viewBox 写一个数或 [宽, 高]")
                    vb[0].numberOrFail("$what.viewBox[0]") to vb[1].numberOrFail("$what.viewBox[1]")
                }
                else -> vb.numberOrFail("$what.viewBox").let { it to it }
            }
        }?.also { (w, h) -> if (w <= 0f || h <= 0f) fail("$what.viewBox 必须大于 0") }

        val shaderSrc = optStr("shader", "shaderSrc")?.also {
            if (it.isBlank()) fail("$what 的着色器源码不能为空")
        } ?: if (type == UIMessageNodeType.SHADER) fail("Shader 必须指定 AGSL 源码，如 Shader(\"half4 main(float2 p) { return half4(1); }\")") else ""
        val uniforms = obj["uniforms"]?.let { parseUniforms(it, "$what.uniforms") } ?: emptyMap()
        if (shaderSrc.isNotEmpty()) validateShaderUniforms(shaderSrc, uniforms, what)

        val sliderVal = if (type == UIMessageNodeType.SLIDER) {
            optNum("sliderValue", "value") ?: 0f
        } else {
            optNum("sliderValue") ?: 0f
        }
        val minVal = optNum("minValue", "min") ?: 0f
        val maxVal = optNum("maxValue", "max") ?: 1f
        if (type == UIMessageNodeType.SLIDER && maxVal <= minVal) {
            fail("$what 的 max（$maxVal）必须大于 min（$minVal）")
        }
        val customTextResolved = if (type == UIMessageNodeType.SLIDER) {
            optStr("customText", "text", "label") ?: ""
        } else {
            optStr("customText", "text", "label", "value") ?: ""
        }
        val blendMode = optStr("blendMode")?.also {
            if (it.lowercase() !in UI_BLEND_MODES.map { m -> m.lowercase() }) {
                fail("$what.blendMode 不支持「$it」，可用：${UI_BLEND_MODES.joinToString(" / ")}")
            }
        } ?: ""
        val pointerEvents = optStr("pointerEvents")?.lowercase()?.also {
            if (it !in UI_POINTER_EVENTS) fail("$what.pointerEvents 只能是 auto 或 none，实际为 $it")
        }?.takeIf { it != "auto" } ?: ""
        val scroll = optStr("scroll")?.lowercase()?.also {
            if (it !in UI_SCROLL_MODES) fail("$what.scroll 只能是 vertical / horizontal，实际为 $it")
        }?.let { if (it == "y") "vertical" else if (it == "x") "horizontal" else it } ?: ""
        val insets = optStr("insets")?.trim()?.also {
            if (it.isNotEmpty() && !isValidUIInsetsSpec(it)) {
                fail("$what.insets 不支持「$it」，可用组合：status / nav / ime / safe")
            }
        } ?: ""
        val haptic = optStr("haptic")?.lowercase()?.also {
            if (it !in UI_HAPTIC_TYPES) fail("$what.haptic 不支持「$it」，可用：${UI_HAPTIC_TYPES.joinToString(" / ")}")
        } ?: ""

        FUNCTION_ONLY_KEYS.firstOrNull { it in obj && obj[it] !is JsonNull }?.let {
            fail("$what.$it 必须是函数（如 $it: (…, ctx) => { ... }），并且只能写在 ui.slot / ui.page 里")
        }
        val isLazy = type == UIMessageNodeType.LAZY_COLUMN || type == UIMessageNodeType.LAZY_ROW
        val keys = obj["keys"]?.let { k ->
            val arr = k as? JsonArray ?: fail("$what.keys 必须是字符串或数字的数组")
            arr.mapIndexed { i, el ->
                (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: fail("$what.keys[$i] 必须是字符串或数字")
            }.also { list ->
                if (list.toSet().size != list.size) {
                    val dup = list.groupingBy { it }.eachCount().entries.first { it.value > 1 }.key
                    fail("$what.keys 里有重复的值「$dup」（每行的标识必须不同）")
                }
            }
        } ?: emptyList()
        val count = optNum("count")?.let {
            if (it < 0f || it != it.toInt().toFloat()) fail("$what.count 必须是不小于 0 的整数")
            it.toInt()
        } ?: 0
        if (isLazy) {
            if (optStr("itemFn").isNullOrEmpty()) {
                fail("${if (type == UIMessageNodeType.LAZY_ROW) "LazyRow" else "LazyColumn"} 需要 item: (index, ctx) => 节点（只为看得见的行调用）")
            }
            if (keys.isNotEmpty() && obj["count"] != null && count != keys.size) {
                fail("$what 的 count（$count）与 keys 的个数（${keys.size}）不一致；写了 keys 可以不写 count")
            }
            if (type == UIMessageNodeType.LAZY_ROW && (!optStr("onRefreshFn").isNullOrEmpty() || obj["refreshing"] != null)) {
                fail("LazyRow 不支持下拉刷新 onRefresh / refreshing（仅 LazyColumn 支持）")
            }
        }
        val swipeSpec = obj["swipe"]?.let { parseSwipe(it, "$what.swipe") }
        val onSwipeFn = optStr("onSwipeFn") ?: ""
        if (swipeSpec != null && onSwipeFn.isEmpty()) {
            fail("$what 写了 swipe 也要写 onSwipe: (direction, ctx) => { ... }（滑动手势只能在 ui.slot / ui.page 中配合回调使用）")
        }
        if (onSwipeFn.isNotEmpty() && swipeSpec == null) {
            fail("$what 写了 onSwipe 也要写 swipe（指定滑动方向）")
        }
        val composeArgs = obj["args"]?.let { a ->
            if (a is JsonNull) null else a as? JsonArray ?: fail("$what.args 必须是数组")
        }
        if (type == UIMessageNodeType.COMPOSE) {
            validateComposeArgs(composeArgs ?: JsonArray(emptyList()), children.size, "Compose 的参数")
        }

        return UIMessageNode(
            id = optStr("id") ?: "node",
            name = optStr("name") ?: "",
            enabled = optBool("enabled") ?: true,
            type = type,
            visibleWhen = visibleWhen,
            horizontalAlignment = hAlign,
            verticalAlignment = vAlign,
            hasExplicitBoxAlign = obj.containsKey("align") || obj.containsKey("horizontalAlignment") ||
                obj.containsKey("vAlign") || obj.containsKey("verticalAlignment"),
            spacingDp = (optNum("spacingDp", "spacing") ?: 4f).also {
                if (it < 0f) fail("$what.spacing 不能为负数")
            },
            crossSpacingDp = optNum("crossSpacingDp", "crossSpacing")?.also {
                if (it < 0f) fail("$what.crossSpacing 不能为负数")
            } ?: 0f,
            weight = optNum("weight") ?: 0f,
            fillMaxWidth = optBool("fillMaxWidth", "fillMaxSize") ?: false,
            fillMaxHeight = optBool("fillMaxHeight", "fillMaxSize") ?: false,
            compact = optBool("compact") ?: false,
            maxWidthFraction = optNum("maxWidthFraction", "maxWidth") ?: 1f,
            paddingStartDp = padS,
            paddingTopDp = padT,
            paddingEndDp = padE,
            paddingBottomDp = padB,
            marginStartDp = marS,
            marginTopDp = marT,
            marginEndDp = marE,
            marginBottomDp = marB,
            cornerTopStartDp = corTS,
            cornerTopEndDp = corTE,
            cornerBottomEndDp = corBE,
            cornerBottomStartDp = corBS,
            backgroundColorHex = bgColor,
            gradientEndColorHex = gradEnd,
            gradientAngleDeg = gradAngle,
            opacity = optNum("opacity")?.also { if (it !in 0f..1f) fail("$what.opacity 必须在 0～1 之间") } ?: 1f,
            borderWidthDp = borderWidth,
            borderColorHex = borderColor,
            shadowElevationDp = optNum("shadowElevationDp", "shadow") ?: 0f,
            enableHazeBlur = optBool("enableHazeBlur", "blur") ?: false,
            textColorHex = optColor("textColorHex", "color", "textColor") ?: "",
            fontSizeSp = optNum("fontSizeSp", "fontSize") ?: 0f,
            fontWeightBold = optBool("fontWeightBold", "bold") ?: false,
            offsetXDp = optNum("offsetXDp", "offsetX") ?: 0f,
            offsetYDp = optNum("offsetYDp", "offsetY") ?: 0f,
            scale = optNum("scale") ?: 1f,
            rotationDeg = optNum("rotationDeg", "rotate") ?: 0f,
            avatarSizeDp = optNum("avatarSizeDp", "size") ?: 28f,
            avatarCornerDp = optNum("avatarCornerDp", "avatarCorner") ?: -1f,
            iconSizeDp = optNum("iconSizeDp", "iconSize") ?: 18f,
            customText = customTextResolved,
            placeholder = optStr("placeholder", "hint") ?: "",
            singleLine = optBool("singleLine") ?: true,
            checked = optBool("checked") ?: false,
            sliderValue = sliderVal,
            minValue = minVal,
            maxValue = maxVal,
            steps = optNum("steps")?.toInt()?.also { if (it < 0) fail("$what.steps 不能为负数") } ?: 0,
            modal = optBool("modal") ?: true,
            zIndex = optNum("zIndex") ?: 0f,
            clip = optBool("clip"),
            blendMode = blendMode,
            pointerEvents = pointerEvents,
            scroll = scroll,
            insets = insets,
            haptic = haptic,
            wakeDuration = optNum("wakeDuration")?.also { if (it < 0f) fail("$what.wakeDuration 不能为负数") } ?: 0f,
            iconName = optStr("iconName", "icon") ?: "",
            // 单击 / 长按 / 双击 / 提交 / 关闭请求的动作名（接入点提供的动作，或 navigate:页面名）
            actionName = optStr("actionName", "onClick", "action", "onSubmit", "onDismiss") ?: "",
            longClickAction = optStr("longClickAction", "onLongClick") ?: "",
            doubleClickAction = optStr("doubleClickAction", "onDoubleClick") ?: "",
            canvasDrawType = (optStr("canvasDrawType", "drawType") ?: "circle").also {
                if (it !in listOf("circle", "ring")) fail("$what.drawType 只能是 circle / ring（其他图形请用 Path 绘制），实际为 $it")
            },
            collapsibleTitle = optStr("collapsibleTitle", "title") ?: "详情",
            collapsibleDefaultExpanded = optBool("collapsibleDefaultExpanded", "defaultExpanded") ?: false,
            actionItems = actionItems,
            alpha = optNum("alpha")?.also { if (it !in 0f..1f) fail("$what.alpha 必须在 0～1 之间") } ?: 1f,
            blurRadiusDp = optNum("blurRadius", "blurRadiusDp") ?: 0f,
            widthDp = optNum("width", "widthDp") ?: 0f,
            heightDp = optNum("height", "heightDp") ?: 0f,
            fontFamily = (obj["fontFamily"] ?: obj["family"])?.let { parseFontFamilyValue(it, "$what.fontFamily") } ?: "",
            fontWeight = optNum("fontWeight")?.toInt()?.also {
                if (it !in 1..1000) fail("$what.fontWeight 应在 1～1000 之间，实际为 $it")
            } ?: 0,
            italic = optBool("italic") ?: false,
            maxLines = optNum("maxLines")?.toInt()?.also {
                if (it < 0) fail("$what.maxLines 不能为负数")
            } ?: 0,
            letterSpacingEm = optNum("letterSpacing", "letterSpacingEm"),
            lineHeightSp = optNum("lineHeight", "lineHeightSp") ?: 0f,
            textAlign = optStr("textAlign")?.also {
                if (it !in UI_TEXT_ALIGNS) fail("$what.textAlign 只能是 ${UI_TEXT_ALIGNS.joinToString(" / ")}，实际为 $it")
            } ?: "",
            imageSrc = optStr("src", "url", "imageSrc")?.also {
                if (it.isBlank()) fail("$what.src 不能为空")
            }?.also { if (type == UIMessageNodeType.VIDEO) validateVideoSrc(it, what) }
                ?: when (type) {
                    UIMessageNodeType.IMAGE -> fail("Image 必须指定图片来源，如 Image(\"https://...\")")
                    UIMessageNodeType.VIDEO -> fail("Video 必须指定视频来源，如 Video(\"file:bg.mp4\")")
                    else -> ""
                },
            contentScale = optStr("contentScale")?.also {
                if (it !in UI_CONTENT_SCALES) fail("$what.contentScale 只能是 ${UI_CONTENT_SCALES.joinToString(" / ")}，实际为 $it")
            } ?: "fit",
            pathData = optStr("d", "path", "pathData")?.also { validateSvgPath(it, what) }
                ?: if (type == UIMessageNodeType.PATH) fail("Path 必须指定 SVG 路径数据，如 Path(\"M12 2 L22 22 H2 Z\")") else "",
            viewBoxWidth = viewBox?.first ?: optNum("viewBoxWidth") ?: 24f,
            viewBoxHeight = viewBox?.second ?: optNum("viewBoxHeight") ?: 24f,
            strokeWidth = optNum("strokeWidth") ?: 0f,
            onClickFn = optStr("onClickFn") ?: "",
            onLongClickFn = optStr("onLongClickFn") ?: "",
            onDoubleClickFn = optStr("onDoubleClickFn") ?: "",
            onChangeFn = optStr("onChangeFn") ?: "",
            onSubmitFn = optStr("onSubmitFn") ?: "",
            onDismissFn = optStr("onDismissFn") ?: "",
            // 块名不在宿主登记：写错时该处不绘制，并记入运行时错误
            hostBlock = legacyBlock ?: optStr("hostBlock")?.also {
                if (it.isBlank()) fail("Host 的块名不能为空")
            } ?: if (type == UIMessageNodeType.HOST) fail("Host 必须指定块名，如 Host(\"send_button\")") else "",
            strokeCap = optStr("strokeCap")?.also {
                if (it !in listOf("butt", "round", "square")) fail("$what.strokeCap 只能是 butt / round / square，实际为 $it")
            } ?: "butt",
            animations = (obj["animate"] ?: obj["animations"])?.let { parseAnimations(it, "$what.animate") } ?: emptyList(),
            press = obj["press"]?.let { parsePress(it, "$what.press") },
            shaderSrc = shaderSrc,
            uniforms = uniforms,
            fallback = obj["fallback"]?.let { parseMessageNode(it.objectOrFail("$what.fallback"), fromRuntime) },
            loop = optBool("loop") ?: true,
            muted = optBool("muted") ?: true,
            count = if (keys.isNotEmpty()) keys.size else count,
            keys = keys,
            itemSize = optNum("itemSize")?.also { if (it <= 0f) fail("$what.itemSize 必须大于 0") } ?: 56f,
            refreshing = optBool("refreshing") ?: false,
            itemFn = optStr("itemFn") ?: "",
            onEndReachedFn = optStr("onEndReachedFn") ?: "",
            onReorderFn = optStr("onReorderFn") ?: "",
            onRefreshFn = optStr("onRefreshFn") ?: "",
            swipe = swipeSpec,
            onSwipeFn = onSwipeFn,
            zoom = obj["zoom"]?.let { parseZoom(it, "$what.zoom") },
            composeClass = optStr("composeClass")?.also {
                if (it.isBlank()) fail("Compose 的类名不能为空")
            } ?: if (type == UIMessageNodeType.COMPOSE) fail("Compose 必须指定类名与函数名，如 Compose(\"包名.文件名Kt\", \"函数名\", [参数])") else "",
            composeFn = optStr("composeFn")?.also {
                if (it.isBlank()) fail("Compose 的函数名不能为空")
            } ?: if (type == UIMessageNodeType.COMPOSE) fail("Compose 必须指定函数名") else "",
            args = if (type == UIMessageNodeType.COMPOSE) (composeArgs ?: JsonArray(emptyList())) else composeArgs,
            viewClass = optStr("viewClass")?.also {
                if (it.isBlank()) fail("NativeView 的类名不能为空")
            } ?: if (type == UIMessageNodeType.NATIVE_VIEW) fail("NativeView 必须指定安卓视图类名，如 NativeView(\"android.webkit.WebView\")") else "",
            onCreateFn = optStr("onCreateFn") ?: "",
            onReleaseFn = optStr("onReleaseFn") ?: "",
            children = children,
        )
    }

    private fun parseAnimations(el: JsonElement, what: String): List<UINodeAnimation> = when (el) {
        is JsonArray -> el.mapIndexed { i, a -> parseAnimation(a, "$what[$i]") }
        else -> listOf(parseAnimation(el, what))
    }

    private val ANIMATION_KEYS = listOf("prop", "values", "from", "to", "times", "duration", "delay", "repeat", "alternate", "easing")

    private fun parseAnimation(el: JsonElement, what: String): UINodeAnimation {
        val o = el.objectOrFail(what)
        o.keys.firstOrNull { it !in ANIMATION_KEYS && it != "colors" }?.let {
            fail("$what 不支持属性「$it」，可用：${ANIMATION_KEYS.joinToString(" / ")}")
        }
        val prop = o["prop"]?.stringOrFail("$what.prop") ?: fail("$what 必须写 prop（要动的属性）")
        val isColor = prop in UI_ANIM_COLOR_PROPS
        if (!isColor && prop !in UI_ANIM_FLOAT_PROPS) {
            fail("$what.prop 不支持「$prop」，可用：${(UI_ANIM_FLOAT_PROPS + UI_ANIM_COLOR_PROPS).joinToString(" / ")}")
        }
        val raw: List<JsonElement> = when {
            "values" in o || "colors" in o -> {
                val v = o["values"] ?: o["colors"]!!
                (v as? JsonArray)?.toList() ?: fail("$what.values 必须是数组（各关键帧的值）")
            }
            "from" in o || "to" in o -> listOf(
                o["from"] ?: fail("$what 写了 to 也要写 from"),
                o["to"] ?: fail("$what 写了 from 也要写 to"),
            )
            else -> fail("$what 要写 values: [...] 或 from / to")
        }
        if (raw.isEmpty()) fail("$what.values 不能为空")
        val values = if (isColor) emptyList() else raw.mapIndexed { i, v -> v.numberOrFail("$what.values[$i]") }
        val colors = if (isColor) raw.mapIndexed { i, v -> parseColorValue(v, "$what.values[$i]") } else emptyList()
        val times = o["times"]?.let { t ->
            val arr = (t as? JsonArray) ?: fail("$what.times 必须是数组")
            if (arr.size != raw.size) fail("$what.times 的个数（${arr.size}）要和关键帧个数（${raw.size}）相同")
            arr.mapIndexed { i, v -> v.numberOrFail("$what.times[$i]") }.also { ts ->
                if (ts.any { it !in 0f..1f }) fail("$what.times 每个值都要在 0～1 之间")
                if (ts.zipWithNext().any { (a, b) -> b < a }) fail("$what.times 要从小到大排列")
            }
        } ?: emptyList()
        val repeat = when (val r = o["repeat"]) {
            null -> 1
            is JsonPrimitive -> if (r.isString) {
                if (r.content == "infinite") -1 else fail("$what.repeat 写次数或 \"infinite\"，实际为 ${r.content}")
            } else r.numberOrFail("$what.repeat").toInt().also { if (it < 1) fail("$what.repeat 至少为 1") }
            else -> fail("$what.repeat 写次数或 \"infinite\"")
        }
        val easing = o["easing"]?.stringOrFail("$what.easing")?.also {
            if (parseUIEasing(it) == null) fail("$what.easing 不支持「$it」，可用：${UI_EASING_NAMES.joinToString(" / ")} / cubic-bezier(x1, y1, x2, y2)")
        } ?: "easeInOut"
        return UINodeAnimation(
            prop = prop,
            values = values,
            colors = colors,
            times = times,
            duration = o["duration"]?.numberOrFail("$what.duration")?.toInt()?.also { if (it <= 0) fail("$what.duration 必须大于 0") } ?: 1000,
            delay = o["delay"]?.numberOrFail("$what.delay")?.toInt()?.also { if (it < 0) fail("$what.delay 不能为负数") } ?: 0,
            repeat = repeat,
            alternate = o["alternate"]?.boolOrFail("$what.alternate") ?: false,
            easing = easing,
        )
    }

    private fun parsePress(el: JsonElement, what: String): UIPressEffect {
        var p = UIPressEffect()
        el.objectOrFail(what).forEach { (k, v) ->
            p = when (k) {
                "scale" -> p.copy(scale = v.numberOrFail("$what.scale").also { if (it <= 0f) fail("$what.scale 必须大于 0") })
                "alpha" -> p.copy(alpha = v.numberOrFail("$what.alpha").also { if (it !in 0f..1f) fail("$what.alpha 必须在 0～1 之间") })
                "duration" -> p.copy(duration = v.numberOrFail("$what.duration").toInt().also { if (it < 0) fail("$what.duration 不能为负数") })
                else -> fail("$what 不支持属性「$k」，可用：scale / alpha / duration")
            }
        }
        return p
    }

    /** 主题 uniform：数字、[数字…]（1～4 个）或颜色（转成 r, g, b, a）。 */
    private fun parseUniforms(el: JsonElement, what: String): Map<String, List<Float>> =
        el.objectOrFail(what).mapValues { (name, v) ->
            when {
                v is JsonArray -> v.mapIndexed { i, x -> x.numberOrFail("$what.$name[$i]") }.also {
                    if (it.size !in 1..4) fail("$what.$name 写 1～4 个数")
                }
                v is JsonPrimitive && v.isString -> {
                    val c = parseHexColorOrNull(v.content) ?: fail("$what.$name 不是合法颜色：${v.content}（uniform 的颜色只支持 #hex）")
                    listOf(c.red, c.green, c.blue, c.alpha)
                }
                else -> listOf(v.numberOrFail("$what.$name"))
            }
        }

    /** 不在宿主编译着色器（只有 Android 13+ 真机能编译），但先核对 uniform 的声明与取值个数，免得绘制时才出错。 */
    private fun validateShaderUniforms(src: String, uniforms: Map<String, List<Float>>, what: String) {
        val specs = parseShaderUniformSpecs(src)
        UI_SHADER_BUILTIN_UNIFORMS.forEach { (name, n) ->
            val sp = specs[name] ?: return@forEach
            if (sp.themeAssignableComponents != n) {
                fail("$what 的着色器里 $name 要声明为 ${if (n == 1) "float" else "float$n"}（宿主每帧提供）")
            }
        }
        listOf("iClicks", "iTrail").forEach { arrName ->
            specs[arrName]?.let { sp ->
                val len = sp.arraySize
                if (sp.elementComponents != 4 || len == null || len !in 1..UI_SHADER_MAX_HISTORY) {
                    fail("$what 的着色器里 $arrName 要声明为 float4 $arrName[1..$UI_SHADER_MAX_HISTORY]（宿主提供）")
                }
            }
        }
        specs["content"]?.let { sp ->
            if (!sp.isShader || sp.arraySize != null) {
                fail("$what 的着色器里 content 要声明为 `uniform shader content;`（宿主提供子内容画面采样）")
            }
        }
        uniforms.forEach { (name, v) ->
            if (name in UI_SHADER_ALL_BUILTIN_NAMES) fail("$what.uniforms 里的 $name 由宿主提供，不能自己设置")
            val sp = specs[name] ?: fail("$what 的着色器里没有声明 uniform「$name」")
            val d = sp.themeAssignableComponents
            if (d < 0) fail("$what 的 uniform「$name」类型不能由主题赋值（只支持 float / float2 / float3 / float4 及 half 系列）")
            if (d != v.size) fail("$what.uniforms.$name 写了 ${v.size} 个数，着色器里声明的是 $d 个分量")
        }
    }

    private fun validateVideoSrc(src: String, what: String) {
        val s = src.trim()
        if (!(s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file:"))) {
            fail("$what.src 只支持 http(s) 地址或 file:<文件路径>，实际为 $src")
        }
    }

    private fun validateSvgPath(d: String, what: String) {
        if (d.isBlank()) fail("$what 的 SVG 路径数据不能为空")
        try {
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(d)
        } catch (e: Exception) {
            fail("$what 的 SVG 路径数据无法解析：${e.message}")
        }
    }

    /** 预设的代码；没有代码（旧的纯积木 / JSON 预设）时由编译结果生成。 */
    fun generateCodeFromPreset(preset: UIPreset): String =
        preset.code.ifBlank { UICodeGenerator.generate(preset) }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    private fun JsonObject.quadNumbers(
        what: String,
        arrayKeys: List<String>,
        sKey: String,
        tKey: String,
        eKey: String,
        bKey: String,
    ): Quad {
        fun nonNeg(v: Float, label: String): Float {
            if (v < 0f) fail("$label 不能为负数，实际为 $v")
            return v
        }
        val matchedKey = arrayKeys.firstOrNull { it in this }
        if (matchedKey != null) {
            return when (val el = this[matchedKey]!!) {
                is JsonPrimitive -> {
                    val v = nonNeg(el.numberOrFail("$what.$matchedKey"), "$what.$matchedKey")
                    Quad(v, v, v, v)
                }
                is JsonArray -> when (el.size) {
                    1 -> {
                        val v = nonNeg(el[0].numberOrFail("$what.$matchedKey[0]"), "$what.$matchedKey[0]")
                        Quad(v, v, v, v)
                    }
                    2 -> {
                        val h = nonNeg(el[0].numberOrFail("$what.$matchedKey[0]"), "$what.$matchedKey[0]")
                        val v = nonNeg(el[1].numberOrFail("$what.$matchedKey[1]"), "$what.$matchedKey[1]")
                        Quad(h, v, h, v)
                    }
                    4 -> Quad(
                        nonNeg(el[0].numberOrFail("$what.$matchedKey[0]"), "$what.$matchedKey[0]"),
                        nonNeg(el[1].numberOrFail("$what.$matchedKey[1]"), "$what.$matchedKey[1]"),
                        nonNeg(el[2].numberOrFail("$what.$matchedKey[2]"), "$what.$matchedKey[2]"),
                        nonNeg(el[3].numberOrFail("$what.$matchedKey[3]"), "$what.$matchedKey[3]"),
                    )
                    else -> fail("$what.$matchedKey 写一个数、[水平, 垂直] 或 4 个数，实际写了 ${el.size} 个")
                }
                else -> fail("$what.$matchedKey 写一个数、[水平, 垂直] 或 4 个数，实际为 $el")
            }
        }
        fun num(k: String): Float = this[k]?.let { nonNeg(it.numberOrFail("$what.$k"), "$what.$k") } ?: 0f
        return Quad(num(sKey), num(tKey), num(eKey), num(bKey))
    }

    /** 在 QuickJS 中执行 UI 代码，返回 `__ui_spec` 的 JSON。出错时抛 [UICodeCompileException]（含用户代码行号）。 */
    private suspend fun evaluateUiCode(code: String): String = withContext(Dispatchers.Default) {
        try {
            quickJs(Dispatchers.Default) {
                memoryLimit = UI_JS_MEMORY_LIMIT_BYTES
                maxStackSize = UI_JS_MAX_STACK_BYTES
                evaluationTimeoutMillis = UI_JS_TIMEOUT_MS
                evaluate<Any?>(BOOTSTRAP_UI_DSL_JS, "ui-bootstrap.js")
                evaluate<Any?>(code, UI_CODE_FILE_NAME)
                evaluate<String>(FINALIZE_JS, "ui-finalize.js")
            }
        } catch (_: QuickJsInterruptedException) {
            throw UICodeCompileException("UI 代码执行超过 ${UI_JS_TIMEOUT_MS / 1000} 秒（是否有死循环？）")
        } catch (e: QuickJsException) {
            throw UICodeCompileException(e.message ?: "UI 代码执行失败", e)
        }
    }

    /** 编译结束：回调只能写在 ui.slot 里（其余位置在保存时就固定成界面树，回调无处执行），写在别处直接报错而不是悄悄丢掉。 */
    private const val FINALIZE_JS = """
        (() => {
          const spec = globalThis.__ui_spec;
          for (const k of Object.keys(spec)) {
            if (k === "slots" || k === "dynamicSlots" || k === "dynamicSlotChecks") continue;
            if (__hasFns(spec[k])) throw new TypeError("ui." + k + " 里写了回调函数：回调只能写在 ui.slot(...) 里");
          }
          return JSON.stringify(spec);
        })()
    """

    /** 解析运行时主题实例返回的节点 JSON（允许携带运行时回调编号 `*Fn`）。 */
    internal fun parseNodeJson(text: String): UIMessageNode = parseMessageNode(json.parseToJsonElement(text).jsonObject, fromRuntime = true)

    internal fun parseNodeJson(obj: JsonObject): UIMessageNode = parseMessageNode(obj, fromRuntime = true)

    private val VISIBLE_WHEN_PATTERN = Regex("^!?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

    /** 旧写法的构造函数 Avatar()、MarkdownBody()、ConversationList()…… 就是同名原样块 Host("…") 的简写。 */
    private val LEGACY_BLOCK_TYPES = setOf(
        "avatar", "author_name", "model_icon", "model_name", "timestamp", "branch_pager", "reasoning_block",
        "markdown_body", "translation", "edited_files", "nerd_stats", "action_bar", "folder_bar", "conversation_list",
        "assistant_picker",
    )

    private const val UI_CODE_FILE_NAME = "ui-code.js"
    private const val UI_JS_TIMEOUT_MS = 5_000L
    private const val UI_JS_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
    private const val UI_JS_MAX_STACK_BYTES = 1L * 1024 * 1024
}
