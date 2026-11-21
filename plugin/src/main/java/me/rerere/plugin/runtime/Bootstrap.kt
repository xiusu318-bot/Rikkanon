package me.rerere.plugin.runtime

/**
 * 注入到每个插件 QuickJS 实例中的引导脚本，定义全局 `rikka` 对象。
 *
 * 约定：
 * - JS -> 宿主：`__host_sync(name, argsJson)`（同步）与 `__host_async(name, argsJson)`（异步，返回 Promise），
 *   参数与返回值均为 JSON 字符串。
 * - 宿主 -> JS：`__rikka_invoke(handlerId, argsJson)`，返回 Promise<string>（结果 JSON）。
 * - 注册类 API 将 JS 函数存入 `handlers`，并把元数据同步登记到宿主注册表。
 */
internal val PLUGIN_BOOTSTRAP_JS = """
(function () {
  const handlers = Object.create(null);
  let seq = 0;
  const sync = (name, args) => {
    const r = __host_sync(name, JSON.stringify(args === undefined ? null : args));
    return r === undefined || r === null ? null : JSON.parse(r);
  };
  const call = async (name, args) => {
    const r = await __host_async(name, JSON.stringify(args === undefined ? null : args));
    return r === undefined || r === null ? null : JSON.parse(r);
  };
  const fnType = (v, what) => {
    if (typeof v !== "function") throw new TypeError(what + " must be a function");
    return v;
  };
  const register = (kind, meta, fn, optional) => {
    const id = kind + "#" + (++seq);
    if (fn === undefined || fn === null) {
      if (!optional) throw new TypeError(kind + " handler must be a function");
    } else {
      handlers[id] = fnType(fn, kind + " handler");
    }
    sync("register", { kind: kind, id: id, meta: meta === undefined ? null : meta });
    return id;
  };
  // 多方法注册（provider / search / tts ...）：方法存为 handlers[id + ":" + name]
  const registerMethods = (kind, def, required, optionalNames) => {
    if (!def || typeof def.name !== "string" || def.name.length === 0) throw new TypeError(kind + ".name is required");
    const methods = [];
    required.forEach((m) => { fnType(def[m], kind + "." + m); methods.push(m); });
    (optionalNames || []).forEach((m) => { if (def[m] !== undefined) { fnType(def[m], kind + "." + m); methods.push(m); } });
    const meta = {};
    Object.keys(def).forEach((k) => { if (typeof def[k] !== "function") meta[k] = def[k]; });
    meta.methods = methods;
    const id = register(kind, meta, null, true);
    methods.forEach((m) => { handlers[id + ":" + m] = def[m]; });
    return id;
  };

  const fmt = (args) => Array.prototype.map.call(args, (a) => {
    if (typeof a === "string") return a;
    try { return JSON.stringify(a); } catch (e) { return String(a); }
  }).join(" ");
  const log = {
    info: function () { sync("log", { level: "info", text: fmt(arguments) }); },
    warn: function () { sync("log", { level: "warn", text: fmt(arguments) }); },
    error: function () { sync("log", { level: "error", text: fmt(arguments) }); },
  };
  globalThis.console = { log: log.info, info: log.info, warn: log.warn, error: log.error, debug: log.info };

  const rikka = {
    apiVersion: 1,
    log: log,
    registerTool(def) {
      if (!def || typeof def.name !== "string" || def.name.length === 0) throw new TypeError("tool.name is required");
      const dynParams = typeof def.parameters === "function";
      const dynSysPrompt = typeof def.systemPrompt === "function";
      for (const k of ["title", "summary", "icon"]) {
        if (def[k] != null && typeof def[k] !== "string") throw new TypeError("tool." + k + " 必须是字符串");
      }
      const meta = {
        name: def.name,
        description: def.description || "",
        parameters: dynParams ? { type: "object", properties: {} } : (def.parameters || { type: "object", properties: {} }),
        systemPrompt: dynSysPrompt ? "" : (def.systemPrompt || ""),
        display: def.display || null,
        endTurn: !!def.endTurn,
        approval: def.approval || "auto",
        hasWhen: typeof def.when === "function",
        hasDynamicParameters: dynParams,
        hasDynamicSystemPrompt: dynSysPrompt,
      };
      const id = register("tool", meta, def.execute);
      if (meta.hasWhen) handlers[id + ":when"] = def.when;
      if (dynParams) handlers[id + ":parameters"] = def.parameters;
      if (dynSysPrompt) handlers[id + ":systemPrompt"] = def.systemPrompt;
      return id;
    },
    intercept(fn) { return register("intercept", null, fn); },
    onStream(factory, options) {
      const meta = { types: (options && options.types) || null };
      return register("stream", meta, factory);
    },
    on(hook, fn) { return register("hook", { hook: hook }, fn); },
    onUnload(fn) { return register("unload", null, fn); },
    registerMacro(name, fn) { return register("macro", { name: name }, fn); },
    // 暴露方法给自己的面板（window.rikka.call）与其他插件调用
    expose(name, fn) {
      if (typeof name !== "string" || name.length === 0) throw new TypeError("expose: name is required");
      return register("expose", { name: name }, fn);
    },
    // ---- 提示词 / 世界书 / 正则 / 声明式资源 ----
    // 预设或任意提示词中写 {{plugin:<插件id>:<type>}}，组装时由 fn(arg, ctx) 生成内容
    registerPromptBlock(type, fn) {
      if (typeof type !== "string" || type.length === 0) throw new TypeError("registerPromptBlock: type is required");
      return register("promptBlock", { name: type }, fn);
    },
    // def: { name, phase: "request" | "visual" | "persist", scope?: ["user","assistant","lorebook"], replace(text, info) }
    registerRegex(def) {
      if (!def || typeof def.name !== "string") throw new TypeError("registerRegex: name is required");
      const phase = def.phase || "request";
      if (["request", "visual", "persist"].indexOf(phase) < 0) throw new TypeError("registerRegex: unknown phase " + phase);
      return register("regex", { name: def.name, phase: phase, scope: def.scope || ["assistant"] }, def.replace);
    },
    // 声明式注册 Agent Skill（启用即出现在可用技能列表，停用/卸载自动移除）
    // def: { name, description, content, compatibility? }
    registerSkill(def) {
      if (!def || typeof def.name !== "string" || !def.name) throw new TypeError("registerSkill: name is required");
      if (typeof def.description !== "string" || !def.description) throw new TypeError("registerSkill: description is required");
      if (typeof def.content !== "string" || !def.content) throw new TypeError("registerSkill: content is required");
      if (def.compatibility !== undefined && typeof def.compatibility !== "string") throw new TypeError("registerSkill: compatibility must be a string");
      return register("skill", {
        name: def.name,
        description: def.description,
        content: def.content,
        compatibility: def.compatibility || null,
      }, null, true);
    },
    // 声明式注册世界书（启用即同步到世界书列表，停用/卸载自动移除）
    // def: { name, description?, entries: [...] }
    registerLorebook(def) {
      if (!def || typeof def.name !== "string" || !def.name) throw new TypeError("registerLorebook: name is required");
      if (!Array.isArray(def.entries)) throw new TypeError("registerLorebook: entries array is required");
      return register("lorebook", {
        name: def.name,
        description: def.description || "",
        entries: def.entries,
      }, null, true);
    },
    // 声明式注册快捷消息（启用即同步到快捷消息列表，停用/卸载自动移除）
    // def: { name?, title, content, role? }
    registerQuickMessage(def) {
      const title = def && (def.title || def.name);
      if (typeof title !== "string" || !title) throw new TypeError("registerQuickMessage: title or name is required");
      if (typeof def.content !== "string") throw new TypeError("registerQuickMessage: content is required");
      return register("quickMessage", {
        name: def.name || title,
        title: title,
        content: def.content,
        role: def.role || "USER",
      }, null, true);
    },
    // ---- 插件提供实现 ----
    // { name, displayName?, models?, listModels?(cfg), streamText(req), generateText?(req), getBalance?(cfg), generateEmbedding?(req), generateImage?(req), editImage?(req) }
    registerProvider(def) {
      return registerMethods(
        "provider",
        def,
        ["streamText"],
        ["listModels", "generateText", "getBalance", "generateEmbedding", "generateImage", "editImage"]
      );
    },
    // { name, displayName?, search(params, opts) => { answer?, items:[{title,url,text}] }, scrape?(params) }
    registerSearch(def) { return registerMethods("search", def, ["search"], ["scrape"]); },
    // { name, displayName?, speak(text, opts) => { audio: base64, format: "mp3"|"wav"|"pcm"|..., sampleRate? } | async iterator }
    registerTTS(def) { return registerMethods("tts", def, ["speak"]); },
    // { name, displayName?, transcribe?({ audio: base64 wav, format, sampleRate, config }) => string, startStream?(cfg), pushAudio?(frame), stopStream?() }
    registerASR(def) {
      if (!def || (typeof def.transcribe !== "function" && typeof def.startStream !== "function")) {
        throw new TypeError("asr requires transcribe or startStream function");
      }
      if (typeof def.startStream === "function" && typeof def.pushAudio !== "function") {
        throw new TypeError("asr startStream requires pushAudio function");
      }
      return registerMethods("asr", def, [], ["transcribe", "startStream", "pushAudio", "stopStream"]);
    },
    // { name, displayName?, models?, kinds?: ["image","video"], create(req) => task, query?(taskId) => task }
    registerMediaGen(def) { return registerMethods("mediagen", def, ["create"], ["query"]); },
    // 兼容设计文档名称
    registerOcr(fn) { return register("hook", { hook: "ocr.replace" }, fn); },
    registerTranslator(fn) { return register("hook", { hook: "translate.replace" }, fn); },
    // ---- 触发入口 ----
    // 系统分享 / 选中文字：出现在分享处理页。def: { name, title, description? }，fn(text, ctx)
    registerShareHandler(def, fn) {
      if (!def || typeof def.name !== "string") throw new TypeError("registerShareHandler: name is required");
      return register("share", { name: def.name, title: def.title || def.name, description: def.description || "" }, fn);
    },
    // 定时任务（WorkManager，最短 15 分钟）。def: { name, intervalMinutes }
    schedule(def, fn) {
      if (!def || typeof def.name !== "string") throw new TypeError("schedule: name is required");
      const m = Number(def.intervalMinutes || 15);
      if (!(m >= 15)) throw new TypeError("schedule: intervalMinutes must be >= 15");
      return register("schedule", { name: def.name, intervalMinutes: m }, fn);
    },
    web: {
      // 内置 Web 服务路由 /plugin/<id>/<path>。fn(req) => { status?, headers?, body }
      route(path, fn) {
        if (typeof path !== "string") throw new TypeError("web.route: path is required");
        return register("web", { name: path.replace(/^\/+/, "") }, fn);
      },
    },
    ui: {
      // 挂载到宿主预留位置；挂载点与 spec.type 对应关系见文档，不支持的组合注册即报错。
      // handler 可选：点击、表单变更/按钮、渲染等回调。返回挂载 id，可用于 update / unmount。
      mount(slot, spec, handler) {
        if (typeof slot !== "string") throw new TypeError("ui.mount: slot must be a string");
        if (!spec || typeof spec.type !== "string") throw new TypeError("ui.mount: spec.type is required");
        return register("ui", { slot: slot, spec: spec, hasHandler: typeof handler === "function" }, handler, true);
      },
      // 动态局部更新已挂载项的 spec（如实时更新顶栏胶囊文字、状态灯颜色、badge 数字、菜单项、显隐等）
      update(id, partialSpec) {
        if (typeof id !== "string" || !id) throw new TypeError("ui.update: id is required");
        if (!partialSpec || typeof partialSpec !== "object") throw new TypeError("ui.update: partialSpec must be an object");
        sync("ui.update", { id: id, spec: partialSpec });
      },
      unmount(id) { delete handlers[id]; sync("unregister", { id: id }); },
      // 打开插件自带的 HTML 面板：presentation = page | sheet | dialog | floating
      open(path, options) {
        const opts = options || {};
        return call("ui.open", {
          path: path,
          presentation: opts.presentation || "page",
          args: opts.args === undefined ? null : opts.args,
          title: opts.title || null,
          width: opts.width || null,
          height: opts.height || null,
        });
      },
      close(path) { return call("ui.close", { path: path || null }); },
      // 向已打开的面板推送事件（面板内 window.rikka.on(event, fn) 接收；可选 opts.path 定向推送）
      emit(event, data, opts) {
        return call("ui.emit", {
          event: String(event),
          data: data === undefined ? null : data,
          path: (opts && opts.path) || null,
        });
      },
      // 通知宿主重新渲染本插件的挂载项
      refresh() { return call("ui.refresh", {}); },
    },
    storage: {
      get(key) { return sync("storage.get", { key: key }); },
      set(key, value) { sync("storage.set", { key: key, value: value === undefined ? null : value }); },
      delete(key) { sync("storage.delete", { key: key }); },
      keys() { return sync("storage.keys", null); },
    },
    // 插件自己目录下的文件（路径相对插件包根目录）
    files: {
      read(path) { return call("files.read", { path: path }); },
      write(path, text) { return call("files.write", { path: path, text: String(text) }); },
      readBase64(path) { return call("files.readBase64", { path: path }); },
      writeBase64(path, base64) { return call("files.writeBase64", { path: path, base64: String(base64) }); },
      list(path) { return call("files.list", { path: path || "" }); },
      delete(path) { return call("files.delete", { path: path }); },
      exists(path) { return call("files.exists", { path: path }); },
    },
    // 保存媒体/文档附件到宿主聊天附件存储，返回可供 UIMessagePart.Image/Audio/Video/Document 使用的本地 url/path
    attachments: {
      save(opts) { return call("attachments.save", opts || {}); },
    },
    host(name, args) { return call(name, args); },
    // ---- 宿主 API（需在 manifest.permissions 声明对应权限） ----
    conversations: {
      list(opts) { return call("conversations.list", opts || {}); },
      get(id) { return call("conversations.get", { id: id }); },
      create(opts) { return call("conversations.create", opts || {}); },
      send(id, content, opts) { return call("conversations.send", { id: id, content: content, answer: !(opts && opts.answer === false) }); },
      edit(id, messageId, content) { return call("conversations.edit", { id: id, messageId: messageId, content: content }); },
      deleteMessage(id, messageId) { return call("conversations.deleteMessage", { id: id, messageId: messageId }); },
      fork(id, messageId) { return call("conversations.fork", { id: id, messageId: messageId }); },
      regenerate(id, messageId) { return call("conversations.regenerate", { id: id, messageId: messageId }); },
      stop(id) { return call("conversations.stop", { id: id }); },
      approve(id, toolCallId, approved, reason, answer) { return call("conversations.approve", { id: id, toolCallId: toolCallId, approved: !!approved, reason: reason || "", answer: answer === undefined ? null : answer }); },
      status(id) { return call("conversations.status", { id: id }); },
      setTitle(id, title) { return call("conversations.setTitle", { id: id, title: title }); },
      delete(id) { return call("conversations.delete", { id: id }); },
      search(keyword) { return call("conversations.search", { keyword: keyword }); },
    },
    subAgents: {
      send(parentId, opts) { return call("subAgents.send", Object.assign({ parentId: parentId }, opts || {})); },
      read(id, opts) { return call("subAgents.read", Object.assign({ id: id }, opts || {})); },
      wait(ids, opts) { return call("subAgents.wait", Object.assign({ ids: ids }, opts || {})); },
      delete(parentId, id) { return call("subAgents.delete", { parentId: parentId, id: id }); },
    },
    settings: {
      get(path) { return call("settings.get", { path: path === undefined ? null : path }); },
      set(path, value) { return call("settings.set", { path: path, value: value === undefined ? null : value }); },
      assistants() { return call("settings.get", { path: "assistants" }); },
      updateAssistant(id, patch) { return call("settings.updateAssistant", { id: id, patch: patch }); },
    },
    presets: {
      list() { return call("settings.get", { path: "promptPresets" }); },
      save(list) { return call("settings.set", { path: "promptPresets", value: list }); },
    },
    lorebooks: {
      list() { return call("settings.get", { path: "lorebooks" }); },
      save(list) { return call("settings.set", { path: "lorebooks", value: list }); },
    },
    regexes: {
      get(assistantId) { return call("regexes.get", { assistantId: assistantId }); },
      save(assistantId, list) { return call("settings.updateAssistant", { id: assistantId, patch: { regexes: list } }); },
    },
    memory: {
      list(assistantId) { return call("memory.list", { assistantId: assistantId }); },
      read(assistantId, path) { return call("memory.read", { assistantId: assistantId, path: path }); },
      write(assistantId, path, content) { return call("memory.write", { assistantId: assistantId, path: path, content: content }); },
      delete(assistantId, path) { return call("memory.delete", { assistantId: assistantId, path: path }); },
    },
    workspace: {
      list() { return call("workspace.list", {}); },
      files(id, path, opts) { return call("workspace.files", Object.assign({ id: id, path: path || "" }, opts || {})); },
      read(id, path) { return call("workspace.read", { id: id, path: path }); },
      write(id, path, text, overwrite) { return call("workspace.write", { id: id, path: path, text: text, overwrite: overwrite !== false }); },
      readBase64(id, path) { return call("workspace.readBase64", { id: id, path: path }); },
      writeBase64(id, path, base64, overwrite) { return call("workspace.writeBase64", { id: id, path: path, base64: String(base64), overwrite: overwrite !== false }); },
      delete(id, path, recursive, opts) { return call("workspace.delete", Object.assign({ id: id, path: path, recursive: !!recursive }, opts || {})); },
      exec(id, command, opts) { return call("workspace.exec", Object.assign({ id: id, command: command }, opts || {})); },
      terminal(id, opts) { return call("workspace.terminal", Object.assign({ id: id }, opts || {})); },
      terminals(id) { return call("workspace.terminals", { id: id }); },
    },
    notify(title, text, opts) { return call("notify", Object.assign({ title: title, text: text || "" }, opts || {})); },
    clipboard: {
      read() { return call("clipboard.read", {}); },
      write(text) { return call("clipboard.write", { text: String(text) }); },
    },
    speak(text) { return call("speak", { text: String(text) }); },
    toast(text) { return call("toast", { text: String(text) }); },
    plugins: {
      call(id, name, args) { return call("plugins.call", { id: id, name: name, args: args === undefined ? null : args }); },
    },
  };

  // 流式 hook 实例：宿主为每条响应流调用工厂一次，得到 { chunk, end } 后按 instance id 调用
  const streams = Object.create(null);
  let streamSeq = 0;

  const entries = Object.create(null);

  globalThis.__rikka_invoke = async function (handlerId, argsJson) {
    const args = JSON.parse(argsJson);
    let result;
    if (handlerId === "__tool_when") {
      const w = handlers[args.handler + ":when"];
      if (!w) throw new Error("tool has no when()");
      result = !!(await w(args.ctx));
    } else if (handlerId === "__tool_params") {
      result = await handlers[args.handler + ":parameters"](args.ctx);
    } else if (handlerId === "__tool_sysprompt") {
      result = await handlers[args.handler + ":systemPrompt"](args.ctx);
      if (typeof result !== "string") throw new TypeError("tool systemPrompt(ctx) must return a string");
    } else if (handlerId === "__method") {
      const fn = handlers[args.handler + ":" + args.method];
      if (!fn) throw new Error("unknown method " + args.method);
      result = await fn.apply(null, args.args || []);
      if (result && typeof result[Symbol.asyncIterator] === "function") {
        const out = [];
        for await (const c of result) out.push(c);
        result = out;
      }
    } else if (handlerId === "__tool") {
      const fn = handlers[args.handler];
      if (!fn) throw new Error("unknown tool handler " + args.handler);
      const ctx = Object.assign({}, args.ctx, {
        progress: (parts) => call("tool.progress", { token: args.token, parts: parts }),
        askUser: (spec) => {
          const s = typeof spec === "string" ? { question: spec } : spec;
          if (!s || typeof s.question !== "string" || !s.question) throw new TypeError("ctx.askUser: question is required");
          if (s.options !== undefined && !(Array.isArray(s.options) && s.options.every((o) => typeof o === "string"))) {
            throw new TypeError("ctx.askUser: options must be an array of strings");
          }
          if (s.multiSelect !== undefined && typeof s.multiSelect !== "boolean") throw new TypeError("ctx.askUser: multiSelect must be a boolean");
          if (s.placeholder !== undefined && typeof s.placeholder !== "string") throw new TypeError("ctx.askUser: placeholder must be a string");
          return call("tool.askUser", { token: args.askToken, spec: s });
        },
      });
      result = await fn(args.args, ctx);
    } else if (entries[handlerId]) {
      result = await entries[handlerId](args);
    } else if (handlerId === "__stream_open") {
      const factory = handlers[args.handler];
      if (!factory) throw new Error("unknown stream handler " + args.handler);
      const inst = await factory(args.ctx);
      if (!inst || typeof inst.chunk !== "function") throw new TypeError("onStream factory must return { chunk(c), end() }");
      const sid = "s" + (++streamSeq);
      streams[sid] = inst;
      result = sid;
    } else if (handlerId === "__stream_chunk") {
      const inst = streams[args.sid];
      if (!inst) throw new Error("unknown stream instance " + args.sid);
      result = await inst.chunk(args.chunk);
    } else if (handlerId === "__stream_end") {
      const inst = streams[args.sid];
      if (!inst) throw new Error("unknown stream instance " + args.sid);
      delete streams[args.sid];
      result = typeof inst.end === "function" ? await inst.end() : [];
    } else if (handlerId === "__stream_abort") {
      delete streams[args.sid];
      result = null;
    } else {
      const fn = handlers[handlerId];
      if (!fn) throw new Error("unknown handler " + handlerId);
      result = await fn.apply(null, args);
    }
    return JSON.stringify(result === undefined ? null : result);
  };

  globalThis.rikka = rikka;
  globalThis.__rikka_internal = { handlers: handlers, entries: entries, call: call, sync: sync, rikka: rikka };
})();
""".trimIndent()


/** 在所有引导片段之后执行：冻结 rikka 对象并移除内部引用。 */
internal val PLUGIN_BOOTSTRAP_FINALIZE_JS = """
(function () {
  Object.freeze(globalThis.__rikka_internal.rikka);
  delete globalThis.__rikka_internal;
})();
""".trimIndent()
