# Rikkanon 插件开发与注册接口文档 (Plugin API v1)

本文档面向插件开发者，详细说明 Rikkanon（`v1.2.5+`）插件系统的语言环境、打包规范、清单格式、全量注册接口（工具、Hook、流处理、HTTP 拦截、提示词/世界书/正则、服务提供者、UI 挂载与面板）以及宿主主动调用 API。

> [!CAUTION]
> ### ⚠️ 插件安全与高自由度声明（必读）
> - **请务必完整了解插件全部源代码及作用**：在安装和启用任何插件前，请确保它是你自己编写的代码，或你已经完整审阅并 100% 明确知晓其全部源代码逻辑与实际作用。
> - **App 层面没有任何针对插件的额外限制**：出于作者个人需求（不喜欢过于繁琐、严苛的安全限制与能力阉割），**Rikkanon 在应用层面对插件完全开放底层能力，不设黑盒限制**。插件在声明对应权限后即可读写对话、修改全局设置与提示词预设、拦截改写底层 HTTP 请求与 SSE 响应流、发起网络请求以及操控本地工作区终端与子代理，拥有与宿主应用同等的控制权限，使用者需自行对所加载的插件负责。

---

## 一、 快速概览与运行环境

1. **开发语言与运行时**：
   - 使用 **JavaScript (ES2020，ES Module 标准)** 编写，运行在内置的 **QuickJS (`quickjs-kt`)** 引擎中。
   - **支持 ES Module `import`**：入口文件 `main.js` 可以直接 `import { xxx } from './lib/helper.js'` 引用插件包内的其他 JS 文件。
   - **不支持 Node.js 原生模块 / npm 二进制包 / WASM**：插件运行在轻量沙箱内，不起独立进程、不监听独立端口（若有重型 Node/Python 服务需求，可通过 `rikka.workspace` 在工作区终端启动服务后由 `rikka.fetch` 调用）。
2. **核心理念（动态挂靠，不开不生效）**：
   - 宿主本身只提供空白插槽与 Hook 管道，不预置任何插件。
   - 插件在启用时执行 `main.js`，通过全局对象 **`rikka`** 动态注册工具、Hook、服务或 UI 挂载项。**无需在 `manifest.json` 中声明注册了哪些 Hook 或工具**。
3. **错误处理原则（老实报错，绝不静默兜底）**：
   - 注册未知的 Hook 名、事件名、UI 挂载点，或调用未声明权限的 API，宿主会直接抛出 `[plugin:<id>] <hook>: ...` 错误，不会静默忽略。

---

## 二、 插件包结构与打包方式

插件以 **`.zip` 压缩包** 形式在「设置 -> 扩展 -> 插件 -> 右上角 `+`」导入安装。

### 1. 目录结构
```text
my-plugin.zip
├── manifest.json       # [必需] 插件身份与权限清单
├── main.js             # [必需] 入口 JS 文件（可在 manifest.main 中自定义路径）
├── lib/                # [可选] 子模块 JS 文件，可在 main.js 中 import
│   └── utils.js
└── panel.html          # [可选] 插件自定义 WebView 面板页面
```
> 打包提示：既可以直接在插件根目录下打包 `manifest.json` 和 `main.js`，也允许外层多套一层文件夹（如 `my-plugin/manifest.json`），宿主安装时会自动识别。

### 2. `manifest.json` 格式
```json
{
  "id": "com.example.my_plugin",
  "name": "我的示例插件",
  "version": "1.0.0",
  "apiVersion": 1,
  "main": "main.js",
  "description": "插件功能简述",
  "permissions": [
    "network",
    "settings.read"
  ]
}
```
- **`id`**：唯一标识，仅允许字母、数字、`_`、`.`、`-`，长度 1~64。
- **`apiVersion`**：固定为 `1`。
- **`permissions`**：插件主动调用宿主受控 API 时必须声明的权限数组（仅注册工具、Hook、宏、UI、读写插件自身 `rikka.storage` 与 `rikka.files` **不需要任何权限**）。

#### 可选权限列表 (`permissions`)
| 权限名 | 允许调用的宿主 API |
|---|---|
| `network` | `rikka.fetch` |
| `conversation.read` | `rikka.conversations.list / get / status / search` |
| `conversation.write` | `rikka.conversations.create / send / edit / deleteMessage / fork / regenerate / stop / approve / setTitle / delete` |
| `subagent` | `rikka.subAgents.send / read / wait / delete` |
| `settings.read` | `rikka.settings.get / assistants`、`rikka.presets.list`、`rikka.lorebooks.list`、`rikka.regexes.get` |
| `settings.write` | `rikka.settings.set / updateAssistant`、`rikka.presets.save`、`rikka.lorebooks.save`、`rikka.regexes.save` |
| `memory` | `rikka.memory.list / read / write / delete` |
| `workspace` | `rikka.workspace.list / files / read / write / delete / exec / terminal / terminals` |
| `notification` | `rikka.notify` |
| `device` | `rikka.clipboard.read / write`、`rikka.speak` |

---

## 三、 统一上下文对象 `ctx`

几乎所有工具与 Hook 回调的最后一个参数都是 `ctx`，结构如下：

```json
{
  "task": "chat",
  "assistantId": "uuid-string",
  "conversationId": "uuid-string",
  "isSubAgent": false,
  "parentConversationId": null,
  "subAgentCode": null,
  "stepIndex": 0,
  "model": {
    "id": "uuid-string",
    "modelId": "gemini-3.1-pro-preview",
    "displayName": "Gemini 3.1 Pro"
  },
  "workspaceId": "uuid-string"
}
```
- `ctx.task` 取值：`"chat"` | `"title"` | `"suggestion"` | `"compress"` | `"memory"` | `"translate"` | `"ocr"` | `"other"`。
- 插件可通过 `ctx.isSubAgent`、`ctx.assistantId`、`ctx.model.modelId` 等字段自行决定在哪些助手、主对话或模型下生效。

---

## 四、 核心注册接口清单

### 1. 注册 AI 工具：`rikka.registerTool(def)`

将自定义函数作为 Function Calling 工具暴露给大模型（与内置工具平级，工具名保持原样不加前缀）。

```js
rikka.registerTool({
  name: "my_tool",
  description: "工具功能描述（给模型看）",
  parameters: {
    type: "object",
    properties: {
      query: { type: "string", description: "查询词" }
    },
    required: ["query"]
  },
  systemPrompt: "可选：当启用该工具时自动注入到系统提示词中的说明",
  // 可选显示模式：
  // - as: "tool"（默认，显示工具折叠框）| "text"（直接作为普通正文气泡渲染）| "hidden"（完全隐藏）
  // - source: "input"（从工具入参 JSON 的 field 字段实时流式提取正文）| "output"（从执行结果提取正文）
  display: { as: "tool", source: "input", field: "content" },
  // 可选：当本步所有执行的工具都声明了 endTurn: true 且无用户插话时，直接结束本轮生成，不再请求下一轮
  endTurn: false,
  // 可选默认审批策略："auto"（免审批）| "user"（需用户审批）
  approval: "auto",
  // 可选按上下文动态启用：返回 false 时本次请求不下发该工具
  when: (ctx) => !ctx.isSubAgent,
  // 工具执行函数
  execute: async (args, ctx) => {
    // 可选：流式推送中间进度到工具卡片
    await ctx.progress([{ type: "text", text: "正在处理中..." }]);

    // 返回值支持：
    // 1. 字符串：直接作为给模型看的文本结果
    // 2. UIMessagePart 数组：[{ type: "text", text: "..." }]
    // 3. { model: "给模型看的结果", display: [{ type: "text", text: "仅在界面展示、不发给模型的内容" }] }
    return "执行成功: " + args.query;
  }
});
```

---

### 2. 请求/响应与工具链生命周期 Hook：`rikka.on(hookName, fn)`

通过 `rikka.on(hookName, fn)` 注册。返回 `null` 或 `undefined` 表示不修改、沿用原值。

#### (1) 工具与请求参数 Hook
| Hook 名称 | 函数签名 | 说明 |
|---|---|---|
| `tools.list` | `(tools, ctx) => tools` | 工具列表组装完成后调用。可过滤工具数组（返回工具名字符串数组或修改后的工具对象数组），支持动态覆盖已有工具的 `description`、`parameters`、`systemPrompt`、`display`、`endTurn`。 |
| `params.beforeSend` | `(params, ctx) => params` | 发请求前修改生成参数：`{ temperature, topP, maxTokens, reasoningLevel, headers, body }`。其中 `body` 会递归合并进最终 API 请求 JSON 顶层（如注入 `toolConfig`）。 |
| `tool.before` | `(call, ctx) => result` | 工具执行前触发。`call` 为 `{ toolCallId, toolName, args }`。<br>• 返回 `{ args: newArgs }` 修改入参；<br>• 返回 `{ deny: "拒绝原因" }` 拦截拒绝执行；<br>• 返回 `{ result: "直接结果" \| Part[] }` 跳过真实执行并直接采用该返回值。 |
| `tool.after` | `(info, ctx) => parts` | 工具执行后触发。`info` 为 `{ toolCallId, toolName, args, output }`，可返回修改后的输出文本或 `UIMessagePart[]`。 |
| `approval.decide` | `(call, ctx) => string` | 动态决定某个工具调用的审批方式。返回 `"auto"` \| `"user"` \| `"main_agent"` \| `"deny"`。 |

#### (2) 消息、对话历史与高级提示词/世界书 Hook
| Hook 名称 | 函数签名 | 说明 |
|---|---|---|
| `chat.history` | `(historyMessages, ctx) => historyMessages` | **仅改写对话历史**：在世界书扫描与预设组装之前触发。入参 `historyMessages` **仅包含纯粹的真实对话历史 `UIMessage[]`**（不含助手系统提示词、预设块或当前未完结的工具链）。适合裁剪/总结历史、给历史消息加前后缀而不误伤预设块。 |
| `preset.beforeAssemble` | `(preset, ctx) => preset \| blocks` | **仅改写本次预设结构**：在预设轨道组装前触发。入参 `preset` 为当前生效的 `PromptPreset`（含 `blocks` 数组），可返回修改后的 `preset` 对象或直接返回新的 `blocks` 数组（仅对本次请求生效，不修改磁盘上的全局预设）。 |
| `lorebook.activated` | `(entries, ctx) => entries` | **仅改写本次激活世界书**：世界书扫描完成后触发，可过滤、修改或追加本次激活的世界书条目列表。 |
| `prompt.assembled` | `(messages, ctx) => messages` | **改写合流后的组装结果**：高级提示词预设、世界书与对话历史合流组装完成后触发。此时每条消息均携带 `isSynthetic: boolean`（`true` = 预设块/世界书合成消息，`false` = 真实对话历史）与可选 `rawJson`（JSON 模式块）。 |
| `messages.beforeSend` | `(messages, ctx) => messages` | **发往 Provider 前的最终 `UIMessage[]` 改写**：所有 InputMessageTransformer 与宏展开执行完毕后触发。同样携带并识别 `isSynthetic` 与 `rawJson`。 |
| `response.visual` | `(messages, ctx) => messages` | 流式生成期间仅用于 UI 视觉渲染的 `UIMessage[]` 变换（不影响实际存储）。 |
| `response.finish` | `(messages, ctx) => messages` | 单步生成结束时对 `UIMessage[]` 的最终后处理（会落库保存）。 |

> #### 💡 请求体改写分层指南（如何选择正确的 Hook？）
> Rikkanon 的架构将 **「对话历史 (Chat History)」**、**「提示词预设 (Prompt Preset)」** 与 **「最终 API 协议请求体 (Raw HTTP Body)」** 严格分离。插件改写请求时请按目标粒度选择对应层级：
>
> 1. **只想改「对话历史」（不动预设块、不破预设结构）** 👉 用 **`rikka.on("chat.history", (history, ctx) => ...)`**
>    - 这里拿到的 `history` 100% 都是真实聊天历史消息（不含系统提示词和预设块），改完之后宿主才会去跑世界书关键字扫描和预设 `@D` 深度注入。
> 2. **只想改「预设块」（动态增删改本次预设轨道，不写盘）** 👉 用 **`rikka.on("preset.beforeAssemble", (preset, ctx) => ...)`**
>    - 可直接操作 `preset.blocks`（如动态往预设底部塞一个块、动态开关某个 `markerType` 内置块、修改 `@D` 深度或角色），返回修改后的 `preset` 或 `blocks` 数组即可（若需永久修改磁盘预设，才用 `rikka.presets.save()`）。
> 3. **想同时看/改「合流后的预设 + 历史消息列表」** 👉 用 **`rikka.on("prompt.assembled")` 或 `rikka.on("messages.beforeSend")`**
>    - 此时预设块与对话历史已合流为单个 `UIMessage[]` 数组。
>    - **如何区分哪条是预设块、哪条是真实对话历史？** 每条消息对象上都带有 **`msg.isSynthetic`** 字段：
>      - `msg.isSynthetic === false`：真实对话历史消息（在 Claude / Gemini 下相邻同角色会自动合并，且参与 Claude 历史缓存断点计数與请求正则深度计数）；
>      - `msg.isSynthetic === true`：预设模块或世界书注入块（在所有 Provider 下保持独立数组元素绝不与相邻消息擅自合并，且若为 JSON 模式块还会携带 `msg.rawJson` 字段）。
>      - 插件在数组中增删改消息时，宿主会按每条消息的 `isSynthetic`（未显式指定时按消息 `id` 自动继承）还原边界属性；若插件自己新增了一条不希望被 Provider 合并的合成消息，只需在对象上设置 `isSynthetic: true`（或直接设置 `rawJson: { ... }`）。
> 4. **想改「完整 API 请求体顶层 JSON」（含 `generationConfig` / `thinkingConfig` / `toolConfig` / 最终 `contents` 或 `messages`）**：
>    - **仅追加/覆盖顶层配置字段**：用 **`rikka.on("params.beforeSend")`** 修改 `params.body`（宿主会执行递归 Deep Merge，例如写入 `{ generationConfig: { thinkingConfig: { includeThoughts: false } } }` 只会覆盖 `includeThoughts` 而保留 `thinkingLevel`）；
>    - **完全掌控/重写最终 HTTP 请求体 JSON**：用 **`rikka.intercept(async (req, next, ctx) => ...)`**，通过 `const body = req.json()` 直接读写 Provider 序列化完成后的完整 JSON 请求体。

#### (3) 数据与存储 Hook
| Hook 名称 | 函数签名 | 说明 |
|---|---|---|
| `input.beforeSave` | `(parts, ctx) => parts` | 用户点击发送消息、落库前改写用户输入的 `UIMessagePart[]`。 |
| `attachment.parse` | `({ path, fileName, mime }, ctx) => string` | 自定义文档附件解析器，返回提取出的文本内容（返回空或 `null` 则交回内置解析器）。 |
| `conversation.beforeSave` | `(conversation) => conversation` | 会话写入数据库前的最后一道改写。 |
| `search.results` | `(results, { query }) => results` | 本地全文搜索结果返回前的过滤或排序改写。 |

#### (4) 后台任务 Hook（`<task>.before` / `<task>.after` / `<task>.replace`）
支持任务名 `<task>`：**`title`**（标题生成）、**`suggestion`**（建议回复）、**`compress`**（上下文压缩）、**`memory`**（记忆整理）、**`translate`**（消息翻译）、**`ocr`**（图片文字识别）。
- `rikka.on("<task>.before", (input, ctx) => ...)`：修改任务输入，或返回 `{ output: "直接结果" }` 短路跳过模型调用。
- `rikka.on("<task>.after", (output, ctx) => ...)`：修改任务的模型输出结果。
- `rikka.on("<task>.replace", (input, ctx) => ...)`：完全接管该后台任务的实现，直接返回结果字符串（也可用别名 `rikka.registerOcr(fn)` / `rikka.registerTranslator(fn)`）。

---

### 3. 统一流式事件改写：`rikka.onStream(factory, options?)`

在 `GenerationLoop` 将流式分片交给 `StreamChunkHandler` 之前拦截、过滤或转换 `StreamChunk`。

```js
rikka.onStream(
  (ctx) => {
    return {
      // 每收到一个匹配的 StreamChunk 调用，返回 StreamChunk 数组（返回 [] 即丢弃该分片，也可拆成多个或转成其他类型）
      chunk(c) {
        return [c];
      },
      // 流正常结束时调用，可返回收尾 StreamChunk 数组
      end() {
        return [];
      }
    };
  },
  // 可选性能优化：仅订阅指定 type 的事件，未订阅的事件直接在宿主放行不进 JS
  { types: ["text_delta", "tool_call_start", "tool_call_delta", "tool_call_end"] }
);
```
- **常用 `StreamChunk.type` 值（注意均为小写下划线）**：
  - 文本：`text_start` (`{ id, metadata? }`)、`text_delta` (`{ id, text, metadata? }`)、`text_end` (`{ id }`)
  - 思考：`reasoning_start`、`reasoning_delta` (`{ id, text, metadata? }`)、`reasoning_end` (`{ id, metadata? }`)
  - 工具调用：`tool_call_start` (`{ id, toolName, metadata? }`)、`tool_call_delta` (`{ id, toolNameDelta?, inputDelta, metadata? }`)、`tool_call_end` (`{ id }`)
  - 其他：`image_start`、`image_delta`、`image_end`、`annotations`、`usage`、`finish`

---

### 4. HTTP 原始层中间件与 SSE 工具：`rikka.intercept(fn)` & `rikka.sse`

挂载在 OkHttp 应用层拦截器上，可改写任意 Provider 发出的原始 HTTP 请求体、请求头或流式 SSE 响应体。

```js
rikka.intercept(async (req, next, ctx) => {
  // req: { url, method, headers, body (string|null), json() }
  if (ctx.task === "chat" && req.url.endsWith("/chat/completions")) {
    const body = req.json();
    body.temperature = 0.7;
    // 调用 next(...) 发往下一个拦截器或真实上游
    const res = await next({ ...req, body });
    return res;
  }
  return next(req);
});
```
- **改写流式 SSE 响应（配合 `rikka.sse.parse` / `rikka.sse.encode`）**：
  ```js
  rikka.intercept(async (req, next, ctx) => {
    const res = await next(req);
    async function* transformSse() {
      for await (const ev of rikka.sse.parse(res.body)) {
        // ev: { event, data, id?, retry?, comment? }
        yield ev;
      }
    }
    return {
      status: res.status,
      headers: res.headers,
      body: rikka.sse.encode(transformSse())
    };
  });
  ```

---

### 5. 宏、自定义提示词块与正则注册

1. **注册宏 `{{macro_name}}` 或 `{{macro_name:arg}}`**：
   ```js
   rikka.registerMacro("my_macro", (arg, ctx) => {
     return "展开内容: " + (arg || "无参数");
   });
   ```
2. **注册自定义提示词块 `{{plugin:<plugin_id>:<type>}}`**：
   ```js
   rikka.registerPromptBlock("status", (arg, ctx) => {
     return `[当前对话: ${ctx.conversationId}]`;
   });
   ```
3. **注册插件正则**：
   ```js
   rikka.registerRegex({
     name: "clean_tags",
     phase: "visual", // "request"（仅修改发出）| "visual"（仅修改显示）| "persist"（直接修改落库正文）
     scope: ["assistant"], // ["user", "assistant", "lorebook"]
     replace: (text, info) => text.replace(/<secret>[\s\S]*?<\/secret>/g, "")
   });
   ```

---

### 6. 只读事件订阅：`rikka.on("event:<name>", fn)`

异步触发，不阻塞主流程：
- 生成事件：`event:generation.start`、`event:generation.step`、`event:generation.finish`、`event:generation.error`、`event:generation.stopped`
- 会话与消息事件：`event:conversation.create`、`event:conversation.fork`、`event:conversation.delete`、`event:message.send`、`event:steer.injected`、`event:context.compressed`
- 子代理与生命周期：`event:subagent.status`、`event:app.foreground`、`event:app.background`、`event:usage`

---

### 7. 提供底层服务实现（启用即自动同步到可用列表）

插件注册以下能力后，宿主的 `PluginCapabilitySync` 会**自动将其同步到对应的可用服务/模型列表中**（插件停用或卸载时自动移除，无需用户在设置页手动新建渠道）：

1. **AI Provider**：`rikka.registerProvider({ name, displayName?, models?, listModels?(cfg), streamText(req), generateText?(req) })`
2. **联网搜索服务**：`rikka.registerSearch({ name, displayName?, search(params, opts), scrape?(params) })`
3. **TTS 语音合成**：`rikka.registerTTS({ name, displayName?, speak(text, opts) => ({ audio: base64, format: "mp3", sampleRate? }) })`
4. **ASR 语音识别**：`rikka.registerASR({ name, displayName?, transcribe({ audio: base64Wav, format, sampleRate }) => string })`
5. **媒体生成（生图/生视频）**：`rikka.registerMediaGen({ name, displayName?, models?, create(req) => task, query?(req) => task })`

---

### 8. 触发入口（系统分享、定时任务、Web 路由）

- **系统分享处理入口**：`rikka.registerShareHandler({ name, title, description? }, async (text, ctx) => { ... })`
- **周期定时任务（WorkManager，最小间隔 15 分钟）**：`rikka.schedule({ name, intervalMinutes: 15 }, async () => { ... })`
- **内置 Web 服务路由（`/plugin/<id>/<path>`）**：`rikka.web.route("hello", async (req) => ({ status: 200, headers: {}, body: "ok" }))`

---

### 9. UI 挂载与自定义 WebView 面板：`rikka.ui`

#### (1) 挂载到宿主预留位置：`rikka.ui.mount(slot, spec, handler?)`
| 挂载点 (`slot`) | 位置说明 | 支持的 `spec.type` |
|---|---|---|
| `plugin.detail` | **插件自身详情页**（最推荐放插件自己的配置表单） | `form`、`entry`、`html` |
| `extensions.list` | 「扩展」页列表 | `form`、`entry`、`html` |
| `settings.group` | 设置页分组 | `form`、`entry`、`html` |
| `assistant.detail` | 助手详情页 | `form`、`entry`、`html` |
| `provider.detail` / `mcp.detail` / `workspace.detail` | 对应详情页 | `form`、`entry`、`html` |
| `chat.topbar` | 聊天页顶栏右侧胶囊区 | `button`、`badge` |
| `chat.input` | 聊天输入栏快捷按钮 | `button` |
| `chat.slash` | 聊天输入 `/` 斜杠命令补全 | `command` (`{ type: "command", name: "cmd", description: "..." }`) |
| `message.menu` | 消息长按操作菜单 | `menuItem` (`{ type: "menuItem", title: "..." }`) |
| `markdown.codeblock` | Markdown 代码块自定义渲染预览 | `renderer` (`{ type: "renderer", languages: ["my_lang"] }`) |
| `message.part` | 消息部件自定义渲染（按 `metadata["rikka.renderer"]`） | `renderer` (`{ type: "renderer", rendererType: "my_part" }`) |

- **声明式配置表单 (`spec.type = "form"`) 示例**（字段值自动双向绑定 `rikka.storage`）：
  ```js
  rikka.ui.mount("plugin.detail", {
    type: "form",
    title: "插件配置",
    fields: [
      { key: "enabledFlag", type: "switch", label: "启用功能", description: "说明文字", default: true },
      { key: "mode", type: "select", label: "工作模式", default: "fast", options: [
        { value: "fast", label: "快速模式" },
        { value: "deep", label: "深度模式" }
      ], visibleWhen: { key: "enabledFlag", equals: true } },
      { key: "apiKey", type: "text", label: "自定义参数", default: "" },
      { key: "resetBtn", type: "button", label: "重置统计", action: "reset" }
    ]
  }, (payload) => {
    if (payload.action === "reset") {
      rikka.storage.set("count", 0);
      rikka.toast("已重置");
    }
  });
  ```
  - 支持的字段 `type`：`switch`、`text`、`textarea`、`number`、`select`、`multiselect`、`list`、`button`；所有字段均支持 `visibleWhen: { key, equals }` 条件显隐。

#### (2) 打开自定义 HTML WebView 面板：`rikka.ui.open(path, options)`
- `rikka.ui.open("panel.html", { presentation: "page" | "sheet" | "dialog" | "floating", title: "面板标题", args: {} })`
- 在 `panel.html` 内自动注入 `window.rikka`：
  - `await window.rikka.call(exposedMethodName, args)`：调用插件在 `main.js` 中通过 `rikka.expose(name, fn)` 暴露的方法；
  - `window.rikka.on(event, fn)`：监听插件通过 `rikka.ui.emit(event, data)` 推送的事件；
  - `window.rikka.close()`：关闭当前面板。

---

## 五、 插件内置与宿主主动调用 API

### 1. 免权限基础 API
- **私有 KV 存储（`rikka.storage`，同步读写，立即落盘并与 UI `form` 实时同步）**：
  - `rikka.storage.get(key)`、`rikka.storage.set(key, value)`、`rikka.storage.delete(key)`、`rikka.storage.keys()`
- **插件包内文件读写（`rikka.files`，异步 Promise，限定在插件自身目录内）**：
  - `await rikka.files.read(path)`、`await rikka.files.write(path, text)`、`await rikka.files.list(path)`、`await rikka.files.exists(path)`、`await rikka.files.delete(path)`
- **日志与提示**：
  - `console.log(...)` / `rikka.log.info(...)` / `rikka.log.warn(...)` / `rikka.log.error(...)`（在插件详情页日志栏可见）
  - `await rikka.toast("提示文本")`
- **插件间互调**：
  - 暴露方法：`rikka.expose("myMethod", async (args) => { ... })`
  - 调用其他已启用插件：`await rikka.plugins.call("other.plugin.id", "myMethod", args)`

### 2. 需声明权限的宿主 API（均为 `async` 返回 Promise）
- **网络请求（需 `"network"` 权限，独立客户端不经过 `intercept`）**：
  - `const res = await rikka.fetch({ url, method: "POST", headers: {}, body: {} })` -> 返回 `{ status, headers, body }`
- **对话管理（需 `"conversation.read"` / `"conversation.write"`）**：
  - `rikka.conversations.list({ assistantId?, limit? })`、`get(id)`、`status(id)`、`search(keyword)`
  - `rikka.conversations.create({ assistantId?, title? })`、`send(id, content, { answer: true })`、`edit(id, messageId, content)`、`deleteMessage(id, messageId)`、`fork(id, messageId)`、`regenerate(id, messageId)`、`stop(id)`、`approve(id, toolCallId, approved, reason?, answer?)`、`setTitle(id, title)`、`delete(id)`
- **子代理控制（需 `"subagent"`）**：
  - `rikka.subAgents.send(parentId, { targetAssistantId, task, subAgentId?, wait? })`、`read(id)`、`wait(ids)`、`delete(parentId, id)`
- **设置、预设、世界书与正则读写（需 `"settings.read"` / `"settings.write"`）**：
  - `rikka.settings.get(dotPath?)`、`rikka.settings.set(dotPath, value)`、`assistants()`、`updateAssistant(id, patch)`
  - `rikka.presets.list()` / `save(list)`、`rikka.lorebooks.list()` / `save(list)`、`rikka.regexes.get(assistantId)` / `save(assistantId, list)`
- **长期记忆（需 `"memory"`）**：
  - `rikka.memory.list(assistantId)`、`read(assistantId, path)`、`write(assistantId, path, content)`、`delete(assistantId, path)`
- **工作区文件、命令与持久化终端（需 `"workspace"`）**：
  - `rikka.workspace.list()`、`files(id, path)`、`read(id, path)`、`write(id, path, text, overwrite?)`、`delete(id, path, recursive?)`
  - `rikka.workspace.exec(id, command, { cwd?, timeout? })`、`terminal(id, opts)`、`terminals(id)`
- **设备与通知（需 `"device"` / `"notification"`）**：
  - `rikka.clipboard.read()`、`rikka.clipboard.write(text)`、`rikka.speak(text)`
  - `rikka.notify(title, text)`
