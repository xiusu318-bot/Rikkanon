package me.rerere.plugin.runtime

/**
 * intercept / SSE 相关的 JS 运行时片段，在 [PLUGIN_BOOTSTRAP_JS] 之后执行。
 *
 * - `rikka.intercept(async (req, next, ctx) => Response)`：中间件。
 * - Response：`{ status, headers, body }`，`body` 为字符串或异步迭代器（流式）。
 *   上游响应对象提供 `text()` / `json()` / `body`（按行的异步迭代器，每项带换行）。
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
        };
      },
    };
    return {
      status: r.status,
      headers: r.headers,
      body: body,
      async text() { consumed = true; return await call("http.body.text", { bodyId: r.bodyId }); },
      async json() { return JSON.parse(await this.text()); },
      __upstream: { bodyId: r.bodyId, body: body, isConsumed: () => consumed },
    };
  }

  function normalizeRequest(req) {
    if (!req || typeof req.url !== "string") throw new TypeError("request.url is required");
    let body = req.body;
    if (body !== undefined && body !== null && typeof body !== "string") body = JSON.stringify(body);
    return { url: req.url, method: req.method || "GET", headers: req.headers || {}, body: body === undefined ? null : body };
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

  H.entries["__iter_close"] = async function (args) {
    const it = iters[args.iterId];
    delete iters[args.iterId];
    if (it && typeof it.return === "function") await it.return();
    return null;
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
