package me.rerere.plugin.runtime

/**
 * intercept / SSE / 流式 fetch / WebSocket / 流式方法调用相关的 JS 运行时片段，在 [PLUGIN_BOOTSTRAP_JS] 之后执行。
 *
 * - `rikka.intercept(async (req, next, ctx) => Response)`：中间件（支持 `req.proxy` 动态指定代理）。
 * - `rikka.fetch(req)`：支持 `stream: true`（返回同构流式响应对象）、`proxy`、`bodyBase64`、`base64`、`timeoutMs`。
 * - `rikka.ws.connect(opts)`：WebSocket 客户端连接（支持 `proxy`、`headers` 与双向收发）。
 * - `rikka.sse.parse(body)` / `rikka.sse.encode(events)`：SSE 解析与编码（异步生成器）。
 */
internal val PLUGIN_BOOTSTRAP_HTTP_JS = """
(function () {
  const H = globalThis.__rikka_internal;
  const call = H.call;
  const iters = Object.create(null);
  let iterSeq = 0;

  function makeUpstream(r) {
    let consumed = false;
    const body = {
      [Symbol.asyncIterator]() {
        return {
          async next() {
            consumed = true;
            const c = await call("http.body.read", { bodyId: r.bodyId });
            return c === null ? { done: true, value: undefined } : { done: false, value: c };
          },
          async return() {
            await call("http.body.close", { bodyId: r.bodyId });
            return { done: true, value: undefined };
          },
        };
      },
    };
    return {
      status: r.status,
      headers: r.headers,
      body: body,
      async text() { consumed = true; return await call("http.body.text", { bodyId: r.bodyId }); },
      async base64() { consumed = true; return await call("http.body.base64", { bodyId: r.bodyId }); },
      async json() { return JSON.parse(await this.text()); },
      async close() { await call("http.body.close", { bodyId: r.bodyId }); },
      __upstream: { bodyId: r.bodyId, body: body, isConsumed: () => consumed },
    };
  }

  function normalizeRequest(req) {
    if (!req || typeof req.url !== "string") throw new TypeError("request.url is required");
    let body = req.body;
    if (body !== undefined && body !== null && typeof body !== "string") body = JSON.stringify(body);
    return {
      url: req.url,
      method: req.method || "GET",
      headers: req.headers || {},
      body: body === undefined ? null : body,
      bodyBase64: req.bodyBase64 === undefined || req.bodyBase64 === null ? null : String(req.bodyBase64),
      proxy: req.proxy === undefined || req.proxy === null ? null : String(req.proxy),
      timeoutMs: req.timeoutMs === undefined || req.timeoutMs === null ? null : Number(req.timeoutMs),
      base64: !!req.base64,
    };
  }

  function serializeResponse(res, upstream) {
    if (!res || typeof res !== "object") throw new TypeError("intercept must return a Response object");
    const status = res.status === undefined ? 200 : res.status;
    const headers = res.headers || {};
    const body = res.body;
    const up = res.__upstream || upstream;
    if (up && body === up.body) {
      if (up.isConsumed()) throw new Error("upstream body was already read; return a new body");
      return { status: status, headers: headers, bodyId: up.bodyId };
    }
    if (body === undefined || body === null) return { status: status, headers: headers, text: "" };
    if (typeof body === "string") return { status: status, headers: headers, text: body };
    if (typeof body[Symbol.asyncIterator] === "function") {
      const id = "it" + (++iterSeq);
      iters[id] = body[Symbol.asyncIterator]();
      return { status: status, headers: headers, iterId: id };
    }
    return { status: status, headers: headers, text: JSON.stringify(body) };
  }

  H.entries["__intercept"] = async function (args) {
    const fn = H.handlers[args.handler];
    if (!fn) throw new Error("unknown intercept handler " + args.handler);
    const req = Object.assign({}, args.req, {
      json() { return this.body === null ? null : JSON.parse(this.body); },
    });
    const next = async function (r) {
      const resp = await call("http.next", { token: args.token, req: normalizeRequest(r === undefined ? req : r) });
      return makeUpstream(resp);
    };
    const res = await fn(req, next, args.ctx);
    return serializeResponse(res, null);
  };

  H.entries["__iter_next"] = async function (args) {
    const it = iters[args.iterId];
    if (!it) throw new Error("unknown body iterator " + args.iterId);
    const r = await it.next();
    if (r.done) { delete iters[args.iterId]; return null; }
    const v = r.value;
    if (typeof v !== "string") throw new TypeError("streamed body chunks must be strings");
    return v;
  };

  H.entries["__iter_next_json"] = async function (args) {
    const it = iters[args.iterId];
    if (!it) throw new Error("unknown method stream iterator " + args.iterId);
    const r = await it.next();
    if (r.done) { delete iters[args.iterId]; return { done: true, value: null }; }
    return { done: false, value: r.value === undefined ? null : r.value };
  };

  H.entries["__iter_close"] = async function (args) {
    const it = iters[args.iterId];
    delete iters[args.iterId];
    if (it && typeof it.return === "function") await it.return();
    return null;
  };

  // 真流式方法调用入口：用于 registerProvider.streamText / generateImage / editImage 与 registerTTS.speak
  H.entries["__method_stream_open"] = async function (args) {
    const fn = H.handlers[args.handler + ":" + args.method];
    if (!fn) throw new Error("unknown method " + args.method);
    const res = await fn.apply(null, args.args || []);
    if (res && typeof res[Symbol.asyncIterator] === "function") {
      const id = "it" + (++iterSeq);
      iters[id] = res[Symbol.asyncIterator]();
      return { iterId: id };
    }
    return { value: res === undefined ? null : res };
  };

  // 增强版 fetch（支持 stream: true 流式读取、proxy 单次请求代理、bodyBase64 / base64 二进制）
  H.rikka.fetch = async function (rawReq) {
    const reqObj = typeof rawReq === "string" ? { url: rawReq } : (rawReq || {});
    const norm = normalizeRequest(reqObj);
    if (reqObj.stream) {
      const resp = await call("fetch.stream", norm);
      return makeUpstream(resp);
    }
    const resp = await call("fetch", norm);
    return Object.assign({}, resp, {
      text() { return Promise.resolve(this.body); },
      json() { return Promise.resolve(JSON.parse(this.body)); },
    });
  };

  // WebSocket 客户端支持
  const wsSessions = Object.create(null);
  let wsSeq = 0;

  H.entries["__ws_event"] = async function (args) {
    const s = wsSessions[args.wsId];
    if (!s) return null;
    try {
      if (args.event === "open" && typeof s.onOpen === "function") {
        await s.onOpen(args);
      } else if (args.event === "message" && typeof s.onMessage === "function") {
        await s.onMessage(args.data, args);
      } else if (args.event === "close") {
        delete wsSessions[args.wsId];
        if (typeof s.onClose === "function") await s.onClose(args.code, args.reason);
      } else if (args.event === "error") {
        delete wsSessions[args.wsId];
        if (typeof s.onError === "function") await s.onError(new Error(args.message));
      }
    } catch (e) {
      H.rikka.log.error("ws handler error:", e && e.stack ? e.stack : String(e));
    }
    return null;
  };

  H.rikka.ws = {
    async connect(opts) {
      if (!opts || typeof opts.url !== "string") throw new TypeError("ws.connect: url is required");
      const wsId = "ws" + (++wsSeq);
      wsSessions[wsId] = {
        onOpen: opts.onOpen,
        onMessage: opts.onMessage,
        onClose: opts.onClose,
        onError: opts.onError,
      };
      await call("ws.connect", {
        wsId: wsId,
        url: opts.url,
        headers: opts.headers || {},
        proxy: opts.proxy === undefined || opts.proxy === null ? null : String(opts.proxy),
      });
      return {
        id: wsId,
        send(data) {
          const text = typeof data === "string" ? data : JSON.stringify(data);
          return call("ws.send", { wsId: wsId, text: text });
        },
        sendBase64(b64) {
          return call("ws.send", { wsId: wsId, base64: String(b64) });
        },
        close(code, reason) {
          delete wsSessions[wsId];
          return call("ws.close", { wsId: wsId, code: code || 1000, reason: reason || "" });
        },
      };
    },
  };

  function parseEvent(block) {
    const ev = { event: "message", data: "" };
    const data = [];
    let comment = null;
    block.split("\n").forEach(function (line) {
      if (line === "") return;
      if (line[0] === ":") { comment = line.slice(1).trim(); return; }
      const i = line.indexOf(":");
      const field = i < 0 ? line : line.slice(0, i);
      let value = i < 0 ? "" : line.slice(i + 1);
      if (value[0] === " ") value = value.slice(1);
      if (field === "data") data.push(value);
      else if (field === "event") ev.event = value;
      else if (field === "id") ev.id = value;
      else if (field === "retry") ev.retry = Number(value);
    });
    ev.data = data.join("\n");
    if (comment !== null && data.length === 0) ev.comment = comment;
    return ev;
  }

  H.rikka.sse = {
    parse: async function* (body) {
      let buf = "";
      for await (const chunk of body) {
        buf += String(chunk).replace(/\r\n?/g, "\n");
        let idx;
        while ((idx = buf.indexOf("\n\n")) >= 0) {
          const block = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          if (block.trim() !== "") yield parseEvent(block);
        }
      }
      if (buf.trim() !== "") yield parseEvent(buf);
    },
    encode: async function* (events) {
      for await (const e of events) {
        if (typeof e === "string") { yield e; continue; }
        let out = "";
        if (e.comment !== undefined && (e.data === undefined || e.data === "")) out += ": " + e.comment + "\n";
        if (e.event && e.event !== "message") out += "event: " + e.event + "\n";
        if (e.id !== undefined) out += "id: " + e.id + "\n";
        if (e.retry !== undefined) out += "retry: " + e.retry + "\n";
        if (e.data !== undefined && (e.data !== "" || e.comment === undefined)) {
          const d = typeof e.data === "string" ? e.data : JSON.stringify(e.data);
          d.split("\n").forEach(function (l) { out += "data: " + l + "\n"; });
        }
        yield out + "\n";
      }
    },
  };
})();
""".trimIndent()
