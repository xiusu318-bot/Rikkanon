package me.rerere.plugin.runtime

/**
 * `rikka.java`（反射桥）、`rikka.hook`（方法 Hook）与 `rikka.host.*`（宿主对象）的 JS 侧实现。
 * 在 [PLUGIN_BOOTSTRAP_JS] 之后、[PLUGIN_BOOTSTRAP_FINALIZE_JS]（冻结 rikka）之前执行。
 *
 * 值传递约定（与 [PluginJavaBridge] 对应）：
 * - 基本类型（null / boolean / number / string）直接传递；
 * - Java 对象 <-> `{ $j: 句柄id, $c: 运行时类名, $cls?: 类对象所代表的类名 }`，JS 侧包装为 Proxy；
 * - 超出 2^53 的 long <-> `{ $long: "..." }`（JS 侧为 BigInt）；NaN / Infinity <-> `{ $d: "NaN" }`；
 * - JS 函数作为参数传给接口类型时 -> `{ $fn: handlerId }`，宿主自动生成该接口的动态代理。
 */
internal val PLUGIN_BOOTSTRAP_JAVA_JS = """
(function () {
  const I = globalThis.__rikka_internal;
  const sync = I.sync, call = I.call, handlers = I.handlers, rikka = I.rikka;
  let fseq = 0;
  const regFn = (fn) => { const id = "jfn#" + (++fseq); handlers[id] = fn; return id; };
  const TAG = Symbol("rikka.java");
  const isJ = (v) => !!v && (typeof v === "object" || typeof v === "function") && !!v[TAG];
  const clsName = (c) => isJ(c) ? (c[TAG].className || c[TAG].cls) : String(c);

  const enc = (v) => {
    if (v === undefined || v === null) return null;
    if (isJ(v)) return { ${'$'}j: v[TAG].id };
    const t = typeof v;
    if (t === "bigint") return { ${'$'}long: v.toString() };
    if (t === "number") return Number.isFinite(v) ? v : { ${'$'}d: String(v) };
    if (t === "string" || t === "boolean") return v;
    if (t === "function") {
      // 作为接口实现传给 Java：调用时 this.method 为被调用的接口方法名
      return { ${'$'}fn: regFn(async (method, args) => enc(await v.apply({ method: method }, dec(args) || []))) };
    }
    if (Array.isArray(v)) return v.map(enc);
    if (ArrayBuffer.isView(v)) return Array.from(v);
    const o = {};
    for (const k of Object.keys(v)) o[k] = enc(v[k]);
    return o;
  };

  const dec = (v) => {
    if (v === null || v === undefined) return null;
    if (Array.isArray(v)) return v.map(dec);
    if (typeof v === "object") {
      if (v.${'$'}j !== undefined) return wrap(v);
      if (v.${'$'}long !== undefined) return BigInt(v.${'$'}long);
      if (v.${'$'}d !== undefined) return Number(v.${'$'}d);
      const o = {};
      for (const k of Object.keys(v)) o[k] = dec(v[k]);
      return o;
    }
    return v;
  };

  const callJ = (t, name, args) =>
    dec(sync("java.call", { h: t.id, name: String(name), args: args.map(enc), static: t.isClass }));

  const wrap = (d) => {
    const t = { id: d.${'$'}j, cls: d.${'$'}c, isClass: d.${'$'}cls !== undefined, className: d.${'$'}cls || null };
    return new Proxy(t, {
      get(target, k) {
        if (k === TAG) return target;
        if (typeof k === "symbol") return undefined;
        switch (k) {
          // 让包装对象不被当成 Promise / 可被正常字符串化
          case "then": case "valueOf": case "constructor": return undefined;
          case "${'$'}id": return target.id;
          case "${'$'}type": return target.cls;
          case "${'$'}name": return target.className;
          case "toJSON": return () => ({ ${'$'}j: target.id, ${'$'}c: target.cls });
          // 无参且不是类对象：对象自身转字符串；带参或在类上（如 Arrays.toString(arr)）则按普通方法调用
          case "toString": return (...a) => (a.length || target.isClass) ? callJ(target, "toString", a) : sync("java.toString", { h: target.id });
          case "${'$'}call": return (name, ...a) => callJ(target, name, a);
          case "${'$'}get": return (f) => dec(sync("java.get", { h: target.id, field: String(f), static: target.isClass }));
          case "${'$'}set": return (f, v) => { sync("java.set", { h: target.id, field: String(f), value: enc(v), static: target.isClass }); };
          case "${'$'}copy": return (patch) => {
            if (target.isClass) throw new TypeError("${'$'}copy 只能用于数据类实例，不能用于类对象");
            if (!patch || typeof patch !== "object" || Array.isArray(patch)) throw new TypeError("${'$'}copy 参数必须是字段对象");
            const p = {};
            for (const k of Object.keys(patch)) { if (patch[k] !== undefined) p[k] = enc(patch[k]); }
            return dec(sync("java.copy", { h: target.id, patch: p }));
          };
          case "${'$'}new": return (...a) => dec(sync("java.new", { h: target.id, args: a.map(enc) }));
          case "${'$'}newSig": return (sig, ...a) => dec(sync("java.new", { h: target.id, signature: String(sig), args: a.map(enc) }));
          case "${'$'}async": return (name, ...a) => call("java.async.call", { h: target.id, name: String(name), args: a.map(enc), static: target.isClass }).then(dec);
          case "${'$'}newAsync": return (...a) => call("java.async.new", { h: target.id, args: a.map(enc) }).then(dec);
          case "${'$'}main": return (name, ...a) => call("java.main.call", { h: target.id, name: String(name), args: a.map(enc), static: target.isClass }).then(dec);
          case "${'$'}mainGet": return (f) => call("java.main.get", { h: target.id, field: String(f), static: target.isClass }).then(dec);
          case "${'$'}mainSet": return (f, v) => call("java.main.set", { h: target.id, field: String(f), value: enc(v), static: target.isClass });
          case "${'$'}json": return () => dec(sync("java.json", { h: target.id }));
          case "${'$'}release": return () => { sync("java.release", { h: target.id }); };
          case "${'$'}is": return (c) => sync("java.instanceOf", { h: target.id, cls: clsName(c) });
          case "${'$'}members": return () => sync("java.members", { h: target.id, static: target.isClass });
        }
        return (...a) => callJ(target, k, a);
      },
      set(target, k, v) {
        if (typeof k === "symbol") return false;
        sync("java.set", { h: target.id, field: String(k), value: enc(v), static: target.isClass });
        return true;
      },
      has(target, k) { return k === TAG; },
    });
  };

  const java = {
    use(name) { return dec(sync("java.use", { name: String(name) })); },
    type(name) { return java.use(name); },
    new(cls, ...a) { return (isJ(cls) ? cls : java.use(cls)).${'$'}new(...a); },
    copy(obj, patch) {
      if (!isJ(obj) || obj[TAG].isClass) throw new TypeError("java.copy: 第一个参数必须是 Java 数据类实例");
      return obj.${'$'}copy(patch);
    },
    // 实现一个或多个 Java 接口；impl 为对象（按方法名分派）或函数（所有方法都调用它，this.method 为方法名）
    proxy(interfaces, impl, opts) {
      const list = (Array.isArray(interfaces) ? interfaces : [interfaces]).map(clsName);
      if (typeof impl !== "function" && (!impl || typeof impl !== "object")) throw new TypeError("java.proxy: impl must be a function or an object");
      const hid = regFn(async (method, args) => {
        const fn = typeof impl === "function" ? impl : impl[method];
        if (typeof fn !== "function") return null;
        const self = typeof impl === "function" ? { method: method } : impl;
        return enc(await fn.apply(self, dec(args) || []));
      });
      return dec(sync("java.proxy", { interfaces: list, hid: hid, async: !!(opts && opts.async) }));
    },
    array(type, values) { return dec(sync("java.array", { type: clsName(type), values: (values || []).map(enc) })); },
    isJava(v) { return isJ(v); },
    release(v) { if (isJ(v)) sync("java.release", { h: v[TAG].id }); },
    releaseAll() { sync("java.releaseAll", {}); },
    stats() { return sync("java.stats", {}); },
    json(v) { return isJ(v) ? dec(sync("java.json", { h: v[TAG].id })) : v; },
    members(v) { return isJ(v) ? sync("java.members", { h: v[TAG].id, static: v[TAG].isClass }) : null; },
  };

  const hookImpl = (cls, name, params, cb) => {
    if (!cb || (typeof cb.before !== "function" && typeof cb.after !== "function")) {
      throw new TypeError("hook: callbacks need before(param) and/or after(param)");
    }
    const hid = regFn(async (phase, p) => {
      const fn = cb[phase];
      if (typeof fn !== "function") return null;
      const out = {};
      const origArgs = JSON.stringify(p.args);
      const origResult = phase === "after" ? JSON.stringify(p.result) : undefined;
      const param = {
        phase: phase,
        method: p.method,
        thisObject: dec(p.self),
        args: dec(p.args),
        result: phase === "after" ? dec(p.result) : undefined,
        throwable: p.throwable || null,
        setResult(v) { out.hasResult = true; out.result = enc(v); },
        setThrowable(msg) { out.throwable = String(msg); },
        callOriginal(...a) {
          return dec(sync("java.callOriginal", a.length ? { token: p.token, args: a.map(enc) } : { token: p.token }));
        },
      };
      await fn(param);
      const newArgs = enc(param.args);
      if (JSON.stringify(newArgs) !== origArgs) out.args = newArgs;
      if (phase === "after" && !out.hasResult && out.throwable === undefined) {
        const r = enc(param.result);
        if (JSON.stringify(r) !== origResult) { out.hasResult = true; out.result = r; }
      }
      return out;
    });
    const r = sync("java.hook", {
      cls: clsName(cls),
      name: String(name),
      params: params ? params.map(clsName) : null,
      hid: hid,
      before: typeof cb.before === "function",
      after: typeof cb.after === "function",
      async: !!cb.async,
      timeoutMs: cb.timeout || null,
    });
    return {
      id: r.id,
      count: r.count,
      members: r.members,
      unhook() { delete handlers[hid]; return sync("java.unhook", { id: r.id }); },
    };
  };

  const hook = {
    // method(cls, name, cb) Hook 全部同名重载；method(cls, name, ["int", "java.lang.String"], cb) 指定重载
    method(cls, name, a, b) {
      return (a === null || Array.isArray(a)) ? hookImpl(cls, name, a, b) : hookImpl(cls, name, null, a);
    },
    constructor(cls, a, b) {
      return (a === null || Array.isArray(a)) ? hookImpl(cls, "<init>", a, b) : hookImpl(cls, "<init>", null, a);
    },
    unhook(h) { return sync("java.unhook", { id: typeof h === "string" ? h : h.id }); },
    // 对已被 ART 内联/编译导致 Hook 不生效的调用方使用，强制其回到解释执行
    deoptimize(cls, name) { return sync("java.deoptimize", { cls: clsName(cls), name: String(name) }); },
  };
  hook.ctor = hook.constructor;

  // 宿主对象：Application、当前 Activity、Koin 依赖注入容器里的任意服务
  const host = rikka.host;
  host.context = () => dec(sync("host.context", null));
  host.activity = () => dec(sync("host.activity", null));
  host.koin = () => dec(sync("host.koin", null));
  host.get = (cls, qualifier) => dec(sync("host.get", { cls: clsName(cls), qualifier: qualifier || null }));

  rikka.java = java;
  rikka.hook = hook;
})();
""".trimIndent()
