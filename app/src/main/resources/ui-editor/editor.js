// 界面自定义编辑页的「可视化编辑 → 改代码」工具：用 acorn 解析 UI 代码，把可视化修改改写成源码。
// 代码是唯一来源：值是直接写的字面量 → 改字面量；没写的属性 → 插入；子节点数组是直接写的 → 增删移动；
// 值来自变量 / 表达式、子节点不是直接写的 → 不改，返回所在行号让用户到代码里改。
// 所有接口返回 JSON 字符串：成功 { code } / { ok: true }，不能改 { error, line }。
(function () {
  "use strict";
  const LEAD = new Set(["Text", "CustomText", "TextField", "Button", "Badge", "Icon", "IconButton", "Image", "Path", "Host", "Video", "NativeView"]);
  const CONTAINER = new Set(["Column", "Row", "FlowRow", "Box", "Popup", "Surface", "Card", "Collapsible"]);
  const PROPS_ONLY = new Set([
    "Avatar", "AuthorName", "ModelIcon", "ModelName", "Timestamp", "BranchPager", "ReasoningBlock", "MarkdownBody",
    "Translation", "EditedFiles", "NerdStats", "Divider", "Spacer", "Canvas", "FolderBar", "ConversationList", "AssistantPicker",
    "Switch", "Slider", "LazyColumn", "LazyRow",
  ]);
  const SPECIAL = new Set(["Shader", "ActionBar", "Compose"]);
  const UI_FNS = new Set(["theme", "background", "font", "shapes", "motion", "transition", "typography", "style", "page", "slot", "overlay"]);

  const parse = (code) => acorn.parse(code, { ecmaVersion: 2022, sourceType: "script", locations: true });

  function walk(node, fn) {
    if (!node || typeof node.type !== "string") return;
    fn(node);
    for (const k of Object.keys(node)) {
      if (k === "loc") continue;
      const v = node[k];
      if (Array.isArray(v)) v.forEach((c) => walk(c, fn));
      else if (v && typeof v.type === "string") walk(v, fn);
    }
  }

  /** 调用的名字：构造函数（Text）或 ui.xxx。其余返回 null。 */
  function callName(n) {
    if (n.type !== "CallExpression") return null;
    const c = n.callee;
    if (c.type === "Identifier" && (LEAD.has(c.name) || CONTAINER.has(c.name) || PROPS_ONLY.has(c.name) || SPECIAL.has(c.name))) return c.name;
    if (c.type === "MemberExpression" && !c.computed && c.object.type === "Identifier" && c.object.name === "ui" &&
      c.property.type === "Identifier" && UI_FNS.has(c.property.name)) return "ui." + c.property.name;
    return null;
  }

  const lineOf = (n) => n.loc.start.line;

  function isLit(n) {
    if (!n) return false;
    switch (n.type) {
      case "Literal": return !(n.value instanceof RegExp);
      case "UnaryExpression":
        return (n.operator === "-" || n.operator === "+") && n.argument.type === "Literal" && typeof n.argument.value === "number";
      case "ArrayExpression": return n.elements.every((e) => e && isLit(e));
      case "TemplateLiteral": return n.expressions.length === 0;
      default: return false;
    }
  }

  function keyOf(p) {
    if (p.type !== "Property" || p.computed || p.kind !== "init") return null;
    if (p.key.type === "Identifier") return p.key.name;
    if (p.key.type === "Literal") return String(p.key.value);
    return null;
  }

  const IDENT = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
  const keySrc = (k) => (IDENT.test(k) ? k : JSON.stringify(k));

  /** path 的一段可以写成 "a|b"（别名）：查找时任一个都算，插入时用第一个。 */
  const alts = (seg) => seg.split("|");

  function findProp(obj, seg) {
    const names = alts(seg);
    let found = null;
    for (const p of obj.properties) {
      const k = keyOf(p);
      if (k !== null && names.indexOf(k) >= 0) found = p; // 同名多次时最后一个生效
    }
    return found;
  }

  function nested(path, valueSrc) {
    let s = valueSrc;
    for (let i = path.length - 1; i >= 0; i--) s = "{ " + keySrc(alts(path[i])[0]) + ": " + s + " }";
    return s;
  }

  // ---------------------------------------------------------------------------------------------
  // 文本修改（按原代码的位置，从后往前应用）
  // ---------------------------------------------------------------------------------------------
  function applyEdits(code, edits) {
    const sorted = edits.slice().sort((a, b) => b.start - a.start || b.end - a.end);
    let out = code;
    for (const e of sorted) out = out.slice(0, e.start) + e.text + out.slice(e.end);
    return out;
  }

  class Locked extends Error {
    constructor(message, line) { super(message); this.line = line; }
  }
  const locked = (msg, node) => { throw new Locked(msg, node ? lineOf(node) : null); };

  // ---------------------------------------------------------------------------------------------
  // 定位
  // ---------------------------------------------------------------------------------------------
  function findCallAt(ast, start) {
    let found = null;
    walk(ast, (n) => { if (!found && n.type === "CallExpression" && n.start === start) found = n; });
    if (!found) throw new Locked("代码已变化，找不到这个节点（请重新打开）", null);
    return found;
  }

  function uiCalls(ast, fn, key) {
    const list = [];
    walk(ast, (n) => {
      if (callName(n) !== "ui." + fn) return;
      if (key !== undefined && key !== null) {
        const a0 = n.arguments[0];
        if (!a0 || a0.type !== "Literal" || a0.value !== key) return;
      }
      list.push(n);
    });
    return list;
  }

  /** 节点调用的属性参数：{ node: ObjectExpression | 其他表达式 | null, insert: 没有属性参数时插入位置与写法 }。 */
  function propsArgOf(call) {
    const name = callName(call);
    const a = call.arguments;
    const closeParen = call.end - 1;
    if (name && name.startsWith("ui.")) {
      const idx = (name === "ui.style" || name === "ui.page") ? 1 : 0;
      const arg = a[idx];
      if (arg) return { node: arg };
      if (idx === 1 && a[0]) return { node: null, insertAt: a[0].end, prefix: ", " };
      return { node: null, insertAt: closeParen, prefix: "" };
    }
    if (LEAD.has(name)) {
      if (a[0] && a[0].type === "ObjectExpression") return { node: a[0] };
      if (a[1]) return { node: a[1] };
      if (a[0]) return { node: null, insertAt: a[0].end, prefix: ", " };
      return { node: null, insertAt: closeParen, prefix: "" };
    }
    if (CONTAINER.has(name)) {
      if (a[0] && a[0].type !== "ArrayExpression") return { node: a[0] };
      if (a[0]) return { node: null, insertAt: a[0].start, prefix: "", suffix: ", " };
      return { node: null, insertAt: closeParen, prefix: "" };
    }
    if (name === "Shader") {
      if (a[1] && a[1].type !== "ArrayExpression") return { node: a[1] };
      if (a[1]) return { node: null, insertAt: a[1].start, prefix: "", suffix: ", " };
      if (a[0]) return { node: null, insertAt: a[0].end, prefix: ", " };
      return { node: null, insertAt: closeParen, prefix: "" };
    }
    if (name === "Compose") {
      // Compose(类名, 函数名, [参数], 属性?)
      if (a[3]) return { node: a[3] };
      if (a[2]) return { node: null, insertAt: a[2].end, prefix: ", " };
      locked("Compose 需要先写前三个参数（类名, 函数名, [参数]）", call);
    }
    if (name === "ActionBar") {
      if (a[0] && a[0].type === "ArrayExpression") {
        if (a[1]) return { node: a[1] };
        return { node: null, insertAt: a[0].end, prefix: ", " };
      }
      if (a[0]) return { node: a[0] };
      return { node: null, insertAt: closeParen, prefix: "" };
    }
    if (name === "Switch" || name === "Slider") {
      if (a[0] && a[0].type === "ObjectExpression") return { node: a[0] };
      if (a[1]) return { node: a[1] };
      if (a[0]) return { node: null, insertAt: a[0].end, prefix: ", " };
      return { node: null, insertAt: closeParen, prefix: "" };
    }
    // 只有属性参数的组件；Spacer(1) 这种数字写法不能加属性
    if (a[0]) return { node: a[0] };
    return { node: null, insertAt: closeParen, prefix: "" };
  }

  // ---------------------------------------------------------------------------------------------
  // 属性：改 / 插入 / 删除
  // ---------------------------------------------------------------------------------------------
  function propEdits(code, call, path, valueSrc) {
    const pa = propsArgOf(call);
    if (pa.node === null) {
      return [{ start: pa.insertAt, end: pa.insertAt, text: (pa.prefix || "") + nested(path, valueSrc) + (pa.suffix || "") }];
    }
    let obj = pa.node;
    if (obj.type !== "ObjectExpression") locked("属性来自变量或表达式", obj);
    for (let i = 0; i < path.length; i++) {
      const p = findProp(obj, path[i]);
      const last = i === path.length - 1;
      if (!p) {
        const text = keySrc(alts(path[i])[0]) + ": " + (last ? valueSrc : nested(path.slice(i + 1), valueSrc));
        if (obj.properties.length === 0) return [{ start: obj.start, end: obj.end, text: "{ " + text + " }" }];
        const lastProp = obj.properties[obj.properties.length - 1];
        return [{ start: lastProp.end, end: lastProp.end, text: ", " + text }];
      }
      if (p.shorthand) locked("「" + keyOf(p) + "」的值来自同名变量", p);
      if (last) {
        if (!isLit(p.value)) locked("「" + keyOf(p) + "」的值来自变量或表达式", p.value);
        return [{ start: p.value.start, end: p.value.end, text: valueSrc }];
      }
      if (p.value.type !== "ObjectExpression") locked("「" + keyOf(p) + "」的值来自变量或表达式", p.value);
      obj = p.value;
    }
    throw new Locked("路径为空", null);
  }

  function removeListItem(list, i, container) {
    const e = list[i];
    if (list.length === 1) return { start: container.start + 1, end: container.end - 1, text: "" };
    if (i < list.length - 1) return { start: e.start, end: list[i + 1].start, text: "" };
    return { start: list[i - 1].end, end: e.end, text: "" };
  }

  function removePropEdits(call, path) {
    const pa = propsArgOf(call);
    if (pa.node === null) return [];
    let obj = pa.node;
    if (obj.type !== "ObjectExpression") locked("属性来自变量或表达式", obj);
    for (let i = 0; i < path.length; i++) {
      const p = findProp(obj, path[i]);
      if (!p) return [];
      if (i === path.length - 1) return [removeListItem(obj.properties, obj.properties.indexOf(p), obj)];
      if (p.value.type !== "ObjectExpression") locked("「" + keyOf(p) + "」的值来自变量或表达式", p.value);
      obj = p.value;
    }
    return [];
  }

  /** 找到要改的调用；ui.* 没有时返回 null（由调用方在末尾新增一句）。 */
  function resolveTarget(ast, target, path) {
    if (target.call !== undefined) return findCallAt(ast, target.call);
    const calls = uiCalls(ast, target.ui, target.key);
    if (calls.length === 0) return null;
    // 多次调用会合并：优先改已经写了这个属性的那一处，否则改最后一处
    for (let i = calls.length - 1; i >= 0; i--) {
      const pa = propsArgOf(calls[i]);
      if (pa.node && pa.node.type === "ObjectExpression" && findProp(pa.node, path[0])) return calls[i];
    }
    return calls[calls.length - 1];
  }

  function appendUiCall(code, target, path, valueSrc) {
    const args = (target.key !== undefined && target.key !== null ? JSON.stringify(target.key) + ", " : "") + nested(path, valueSrc);
    const sep = code.length === 0 || code.endsWith("\n") ? "" : "\n";
    return code + sep + "ui." + target.ui + "(" + args + ");\n";
  }

  function result(fn) {
    try {
      return JSON.stringify(fn());
    } catch (e) {
      if (e instanceof Locked) return JSON.stringify({ error: e.message, line: e.line });
      if (e instanceof SyntaxError) {
        const m = /\((\d+):(\d+)\)/.exec(e.message);
        return JSON.stringify({ error: "代码有语法错误：" + e.message, line: m ? Number(m[1]) : null });
      }
      return JSON.stringify({ error: String(e && e.message || e), line: null });
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 子节点
  // ---------------------------------------------------------------------------------------------
  function childrenArgOf(call) {
    const name = callName(call);
    const a = call.arguments;
    if (CONTAINER.has(name)) {
      if (a[0] && a[0].type === "ArrayExpression") return { node: a[0] };
      if (a[1]) return { node: a[1] };
      if (a[0]) return { node: null, insertAt: a[0].end, prefix: ", " };
      return { node: null, insertAt: call.end - 1, prefix: "" };
    }
    if (name === "Shader") {
      if (a[1] && a[1].type === "ArrayExpression") return { node: a[1] };
      if (a[2]) return { node: a[2] };
      const after = a[1] || a[0];
      return { node: null, insertAt: after.end, prefix: ", " };
    }
    locked("这个组件没有子节点", call);
  }

  function elementIndexOf(arr, childStart) {
    for (let i = 0; i < arr.elements.length; i++) {
      const e = arr.elements[i];
      if (e && e.start <= childStart && childStart < e.end) return i;
    }
    return -1;
  }

  function childArray(ast, parentStart) {
    const parent = findCallAt(ast, parentStart);
    const ca = childrenArgOf(parent);
    if (ca.node && ca.node.type !== "ArrayExpression") locked("子节点来自变量或表达式", ca.node);
    return { parent, ca };
  }

  // ---------------------------------------------------------------------------------------------
  // 对外接口
  // ---------------------------------------------------------------------------------------------
  globalThis.__ed_setProp = (code, targetJson, pathJson, valueSrc) => result(() => {
    const target = JSON.parse(targetJson), path = JSON.parse(pathJson);
    const ast = parse(code);
    const call = resolveTarget(ast, target, path);
    if (!call) return { code: appendUiCall(code, target, path, valueSrc) };
    return { code: applyEdits(code, propEdits(code, call, path, valueSrc)) };
  });

  /** 批量检查能否修改：返回与 checks 同序的 [{ ok } | { error, line }]。 */
  globalThis.__ed_check = (code, checksJson) => {
    let ast;
    try { ast = parse(code); } catch (e) { return result(() => { throw e; }); }
    return JSON.stringify(JSON.parse(checksJson).map((c) => {
      try {
        const call = resolveTarget(ast, c.target, c.path);
        if (call) propEdits(code, call, c.path, "0");
        return { ok: true };
      } catch (e) {
        return { error: e instanceof Locked ? e.message : String(e.message || e), line: e.line || null };
      }
    }));
  };

  globalThis.__ed_removeProp = (code, targetJson, pathJson) => result(() => {
    const target = JSON.parse(targetJson), path = JSON.parse(pathJson);
    const ast = parse(code);
    const call = resolveTarget(ast, target, path);
    if (!call) return { code: code };
    return { code: applyEdits(code, removePropEdits(call, path)) };
  });

  /** 改第一个参数（Text("…") 的文字、Icon("…") 的图标名……）。 */
  globalThis.__ed_setLead = (code, callStart, valueSrc) => result(() => {
    const call = findCallAt(parse(code), callStart);
    if (!LEAD.has(callName(call))) locked("这个组件没有文字 / 名称参数", call);
    const a0 = call.arguments[0];
    if (!a0 || a0.type === "ObjectExpression") {
      return { code: applyEdits(code, [{ start: call.callee.end + 1, end: call.callee.end + 1, text: valueSrc + (a0 ? ", " : "") }]) };
    }
    if (!isLit(a0)) locked("文字来自变量或表达式", a0);
    return { code: applyEdits(code, [{ start: a0.start, end: a0.end, text: valueSrc }]) };
  });

  globalThis.__ed_addChild = (code, parentStart, childSrc) => result(() => {
    const { ca } = childArray(parse(code), parentStart);
    if (ca.node === null) return { code: applyEdits(code, [{ start: ca.insertAt, end: ca.insertAt, text: ca.prefix + "[" + childSrc + "]" }]) };
    const els = ca.node.elements;
    if (els.length === 0) return { code: applyEdits(code, [{ start: ca.node.start, end: ca.node.end, text: "[" + childSrc + "]" }]) };
    return { code: applyEdits(code, [{ start: els[els.length - 1].end, end: els[els.length - 1].end, text: ", " + childSrc }]) };
  });

  globalThis.__ed_removeChild = (code, parentStart, childStart) => result(() => {
    const ast = parse(code);
    const { ca } = childArray(ast, parentStart);
    const child = findCallAt(ast, childStart);
    const i = ca.node ? elementIndexOf(ca.node, childStart) : -1;
    if (i < 0) locked("这个节点不是直接写在父节点的子节点数组里（由循环或函数生成）", child);
    return { code: applyEdits(code, [removeListItem(ca.node.elements, i, ca.node)]) };
  });

  globalThis.__ed_moveChild = (code, parentStart, childStart, delta) => result(() => {
    const ast = parse(code);
    const { ca } = childArray(ast, parentStart);
    const child = findCallAt(ast, childStart);
    const i = ca.node ? elementIndexOf(ca.node, childStart) : -1;
    if (i < 0) locked("这个节点不是直接写在父节点的子节点数组里（由循环或函数生成）", child);
    const j = i + delta;
    const els = ca.node.elements;
    if (j < 0 || j >= els.length) return { code: code };
    const a = els[i], b = els[j];
    return {
      code: applyEdits(code, [
        { start: a.start, end: a.end, text: code.slice(b.start, b.end) },
        { start: b.start, end: b.end, text: code.slice(a.start, a.end) },
      ]),
    };
  });

  /**
   * 插桩：每个组件调用包成 __src(起点, 调用)，执行后节点带上 __src，可视化编辑据此找到源码。
   * 只插入、不换行，行号不变。另外返回各 ui.slot 的位置与行号。
   */
  globalThis.__ed_instrument = (code) => result(() => {
    const ast = parse(code);
    const edits = [];
    const calls = {};
    const slots = {};
    walk(ast, (n) => {
      const name = callName(n);
      if (!name) return;
      calls[n.start] = { name: name, line: lineOf(n) };
      edits.push({ start: n.start, end: n.start, text: "__src(" + n.start + ", " }, { start: n.end, end: n.end, text: ")" });
      if (name === "ui.overlay") {
        slots["overlay"] = { start: n.start, line: lineOf(n) };
      } else if ((name === "ui.slot" || name === "ui.page") && n.arguments[0] && n.arguments[0].type === "Literal") {
        const arg0 = n.arguments[0].value;
        if (name === "ui.slot") {
          slots[arg0] = { start: n.start, line: lineOf(n) };
        } else if (n.arguments[1] && n.arguments[1].type === "ObjectExpression") {
          if (findProp(n.arguments[1], "content")) slots["page:" + arg0] = { start: n.start, line: lineOf(n) };
          if (findProp(n.arguments[1], "background")) slots["page:" + arg0 + " (background)"] = { start: n.start, line: lineOf(n) };
          if (findProp(n.arguments[1], "foreground")) slots["page:" + arg0 + " (foreground)"] = { start: n.start, line: lineOf(n) };
        }
      }
    });
    // 同一位置：先放后面的右括号，再放前面的 __src(（从后往前应用时顺序正确）
    edits.sort((a, b) => b.start - a.start || (a.text === ")" ? 1 : -1));
    let out = code;
    for (const e of edits) out = out.slice(0, e.start) + e.text + out.slice(e.end);
    return { code: out, calls: calls, slots: slots };
  });
})();
