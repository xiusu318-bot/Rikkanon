# Rikkanon 插件开发与注册接口文档 (Plugin API v1)

本文档面向插件开发者，详细说明 Rikkanon（`v1.2.5+`）插件系统的语言环境、打包规范、清单格式、全量注册接口（工具、Hook、流处理、HTTP 拦截、提示词/世界书/正则、服务提供者、UI 挂载与面板）、宿主主动调用 API，以及直接触达原生层的 `rikka.java`（反射调用任意 Java/Kotlin 代码）、`rikka.host`（宿主内部对象）与 `rikka.hook`（Hook 任意方法）。

> [!CAUTION]
> ### ⚠️ 插件安全与高自由度声明（必读）
> - **请务必完整了解插件全部源代码及作用**：在安装和启用任何插件前，请确保它是你自己编写的代码，或你已经完整审阅并 100% 明确知晓其全部源代码逻辑与实际作用。
> - **App 层面没有任何针对插件的额外限制**：出于作者个人需求（不喜欢过于繁琐、严苛的安全限制与能力阉割），**Rikkanon 在应用层面对插件完全开放底层能力，不设黑盒限制**。插件在声明对应权限后即可读写对话、修改全局设置与提示词预设、拦截改写底层 HTTP 请求与 SSE 响应流、发起网络请求以及操控本地工作区终端与子代理；声明 `native` 权限后还可通过 `rikka.java` 调用宿主与 Android 系统中的任意 Java/Kotlin 代码（含私有成员与隐藏 API）、通过 `rikka.host` 取得宿主内部任意服务，声明 `hook` 权限后可通过 `rikka.hook` 拦截/改写/替换宿主与系统的任意方法（见第六章），拥有与宿主应用同等的控制权限，使用者需自行对所加载的插件负责。

---

## 一、 快速概览与运行环境

1. **开发语言与运行时**：
   - 使用 **JavaScript (ES2020，ES Module 标准)** 编写，运行在内置的 **QuickJS (`quickjs-kt`)** 引擎中。
   - **支持 ES Module `import`**：入口文件 `main.js` 可以直接 `import { xxx } from './lib/helper.js'` 引用插件包内的其他 JS 文件。
   - **不支持 Node.js 原生模块 / npm 二进制包 / WASM**：JS 本身运行在 QuickJS 中，不起独立进程、不监听独立端口；但可通过 `rikka.java` 直接调用 Android / Java 的任意 API（见第六章）（若有重型 Node/Python 服务需求，可通过 `rikka.workspace` 在工作区终端启动服务后由 `rikka.fetch` 调用）。
2. **核心理念（动态挂靠，不开不生效）**：
   - 宿主本身只提供空白插槽与 Hook 管道，不预置任何插件。
   - 插件在启用时执行 `main.js`，通过全局对象 **`rikka`** 动态注册工具、Hook、服务或 UI 挂载项。**无需在 `manifest.json` 中声明注册了哪些 Hook 或工具**。
3. **错误处理原则（老实报错，绝不静默兜底）**：
   - 注册未知的 Hook 名、事件名、UI 挂载点，或调用未声明权限的 API，宿主会直接抛出 `[plugin:<id>] <hook>: ...` 错误，不会静默忽略。
   - 宿主没有预留 Hook 的地方，可用 `rikka.hook` 直接拦截对应的 Java/Kotlin 方法（见第六章）。

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
- **`permissions`**：插件主动调用宿主受控 API 时必须声明的权限数组（仅注册工具、Hook、宏、声明式资源、UI 挂载与更新、读写插件自身 `rikka.storage` / `rikka.files` 及调用 `rikka.attachments.save` **不需要任何权限**）。

#### 可选权限列表 (`permissions`)
| 权限名 | 允许调用的宿主 API |
|---|---|
| `network` | `rikka.fetch`（含流式 `stream`、二进制 `base64`、单次请求级 `proxy`）、`rikka.ws.connect` |
| `conversation.read` | `rikka.conversations.list / get / status / search` |
| `conversation.write` | `rikka.conversations.create / send / edit / deleteMessage / fork / regenerate / stop / approve / setTitle / delete` |
| `subagent` | `rikka.subAgents.send / read / wait / delete` |
| `settings.read` | `rikka.settings.get / assistants`、`rikka.presets.list`、`rikka.lorebooks.list`、`rikka.regexes.get` |
| `settings.write` | `rikka.settings.set / updateAssistant`、`rikka.presets.save`、`rikka.lorebooks.save`、`rikka.regexes.save` |
| `memory` | `rikka.memory.list / read / write / delete` |
| `native` | `rikka.java.*`（反射调用任意 Java/Kotlin 代码、`copy` 数据类浅拷贝、接口代理、`$async` / `$main`）、`rikka.host.context / activity / koin / get`（见第六章） |
| `hook` | `rikka.hook.*`（拦截 / 改写 / 替换任意方法，含 `callOriginal`、`deoptimize`；见第六章） |
| `workspace` | `rikka.workspace.list / files / read / write / readBase64 / writeBase64 / delete / exec / terminal / terminals` |
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
  systemPrompt: "可选：字符串，或函数 (ctx) => string 动态返回系统提示词（函数必须返回字符串）",
  // parameters 支持静态 JSON Schema 对象，也支持函数 (ctx) => schema 动态返回（函数必须返回对象）
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

    // 可选：在工具执行过程中挂起并弹出交互卡片等待用户选择或输入回答（类似内置 ask_user）
    // 等待用户回答的时间不计入插件调用超时；工具结束（完成 / 出错 / 生成被停止）时仍未回答的提问会以错误结束；
    // 同一工具同时只能有一个未回答的提问，需要多个问题时依次 await；question 必填，也可直接传字符串 ctx.askUser("问题")
    // 可选 options（字符串数组）、multiSelect（布尔）、placeholder（字符串），类型不对直接报错
    // const answer = await ctx.askUser({ question: "请选择环境", options: ["staging", "prod"], multiSelect: false });

    // 返回值支持：
    // 1. 字符串：直接作为给模型看的文本结果
    // 2. UIMessagePart 数组：[{ type: "text", text: "..." }]
    // 3. { model: "给模型看的结果", display: [{ type: "image", url: "..." }] }（display 支持 Text/Image/Audio/Video/Document 等任意部件）
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
  // req: { url, method, headers, body (string|null), proxy?, json() }
  if (ctx.task === "chat" && req.url.endsWith("/chat/completions")) {
    const body = req.json();
    body.temperature = 0.7;
    // 调用 next(...) 发往下一个拦截器或真实上游；支持通过 proxy 字段为单次请求动态指定代理（如 "direct"、"workspace"、代理名或 "socks5://..."）
    const res = await next({ ...req, body, proxy: req.proxy });
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

### 5. 宏、自定义提示词块、正则与声明式资源（Skill / 世界书 / 快捷消息）注册

1. **注册宏 `{{macro_name}}` 或 `{{macro_name:arg}}`**（在预设与世界书组装阶段即展开）：
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
4. **声明式注册 Agent Skill / 世界书 / 快捷消息（启用即自动同步，停用或卸载自动移除）**：
   ```js
   rikka.registerSkill({ name: "my_skill", description: "技能简述", content: "# 技能正文说明", compatibility: "可选，字符串" });
   rikka.registerLorebook({ name: "扩展设定集", description: "说明", entries: [/* RegexInjection 条目 */] });
   rikka.registerQuickMessage({ name: "qm_1", title: "一键总结", content: "请总结以上对话要点。" });
   ```
5. **注册停用/卸载清理钩子**：
   ```js
   rikka.onUnload(async () => { /* 清理资源 */ });
   ```

---

### 6. 只读事件订阅：`rikka.on("event:<name>", fn)`

异步触发，不阻塞主流程：
- 生成事件：`event:generation.start`、`event:generation.step`、`event:generation.finish`、`event:generation.error`、`event:generation.stopped`
- 会话与消息事件：`event:conversation.create`、`event:conversation.fork`、`event:conversation.delete`、`event:message.send`、`event:steer.injected`、`event:context.compressed`
- 子代理与生命周期：`event:subagent.status`、`event:app.foreground`、`event:app.background`、`event:usage`

---

### 7. 提供底层服务实现（启用即自动同步到可用列表，支持真流式）

插件注册以下能力后，宿主的 `PluginCapabilitySync` 会**自动将其同步到对应的可用服务/模型列表中**（插件停用或卸载时自动移除，无需用户在设置页手动新建渠道）：

1. **AI Provider（支持 `async function*` 真流式逐块推送与完整模型能力声明）**：
   - `rikka.registerProvider({ name, displayName?, models?, listModels?(cfg), streamText(req), generateText?(req), getBalance?(cfg), generateEmbedding?(req), generateImage?(req), editImage?(req) })`
   - 模型对象支持：`{ modelId, displayName?, type?: "CHAT"|"IMAGE"|"EMBEDDING", abilities?: ["TOOL","REASONING"], inputModalities?: ["TEXT","IMAGE"], outputModalities?: ["TEXT","IMAGE"], tools?: ["search","url_context","image_generation"], contextLength? }`
2. **联网搜索服务**：`rikka.registerSearch({ name, displayName?, search(params, opts), scrape?(params) })`
3. **TTS 语音合成（支持单次返回或 `async function*` 真流式返回音频分片）**：
   - `rikka.registerTTS({ name, displayName?, speak(text, opts) => ({ audio: base64, format: "mp3", sampleRate? }) })`
4. **ASR 语音识别（支持分段 WAV `transcribe` 或实时 PCM 流 `startStream` / `pushAudio` / `stopStream`）**：
   - `rikka.registerASR({ name, displayName?, transcribe?({ audio: base64Wav, format, sampleRate }) => string, startStream?(cfg), pushAudio?(frame), stopStream?() })`
5. **媒体生成（生图/生视频）**：`rikka.registerMediaGen({ name, displayName?, models?, create(req) => task, query?(req) => task })`

- **返回值要求（不合法时直接报错，记入插件错误）**：
  - 模型声明：`type` / `abilities` / `inputModalities` / `outputModalities` / `tools` 只能使用上面列出的取值，拼错直接报错；`registerProvider` 的 `models` 不合法时该 Provider 保持原有配置不变。
  - `generateImage` / `editImage`：每项为 base64 字符串或 `{ data, mimeType?, partial?, partialImageIndex? }`。
  - `generateEmbedding`：返回 `{ embeddings: number[][], model? }`。
  - TTS `speak`：返回（或逐块 yield）`{ audio, format?, sampleRate?, isLast? }` 或其数组，`format` 必须是宿主支持的音频格式（默认 `mp3`）。
  - ASR：`transcribe` / `pushAudio` / `stopStream` 返回字符串、`{ text }` 或 `null`（本次无结果）；实现 `startStream` 时必须同时实现 `pushAudio`。流式识别出错时直接报错，不再自动改用分段识别。
  - `getBalance`：插件未启用或未实现时报错（余额处显示错误）。
  - `registerLorebook` 的条目不合法时该世界书保持原有内容不变。

---

### 8. 触发入口（系统分享、定时任务、Web 路由）

- **系统分享处理入口**：`rikka.registerShareHandler({ name, title, description? }, async (text, ctx) => { ... })`
- **周期定时任务（WorkManager，最小间隔 15 分钟）**：`rikka.schedule({ name, intervalMinutes: 15 }, async () => { ... })`
- **内置 Web 服务路由（`/plugin/<id>/<path>`）**：`rikka.web.route("hello", async (req) => ({ status: 200, headers: {}, body: "ok" }))`

---

### 9. UI 挂载与自定义 WebView 面板：`rikka.ui`

#### (1) 挂载到宿主预留位置：`rikka.ui.mount(slot, spec, handler?)` & `rikka.ui.update(mountId, partialSpec)`
| 挂载点 (`slot`) | 位置说明 | 支持的 `spec.type` |
|---|---|---|
| `plugin.detail` | **插件自身详情页**（最推荐放插件自己的配置表单） | `form`、`entry`、`html` |
| `extensions.list` | 「扩展」页列表 | `form`、`entry`、`html` |
| `settings.group` | 设置页分组 | `form`、`entry`、`html` |
| `assistant.detail` | 助手详情页 | `form`、`entry`、`html` |
| `provider.detail` / `mcp.detail` / `workspace.detail` | 对应详情页 | `form`、`entry`、`html` |
| `chat.more` | 聊天输入栏点击 `+` 弹出的扩展面板 | `entry`、`button`、`switch`、`form`、`html` |
| `chat.topbar` | 聊天页顶栏右侧胶囊区 | `button`、`badge`、`menu` |
| `chat.input` | 聊天输入栏快捷按钮/胶囊 | `button`、`badge`、`menu` |
| `chat.slash` | 聊天输入 `/` 斜杠命令补全 | `command` (`{ type: "command", name: "cmd", description: "..." }`) |
| `message.menu` | 消息长按操作菜单（支持 `roles` / `hasCode` / `hasImage` / `hasTool` 过滤） | `menuItem` (`{ type: "menuItem", title: "...", icon?: "..." }`) |
| `message.footer` | 消息气泡底部附加栏（支持 `roles` 过滤） | `badge`、`button`、`html`、`renderer` |
| `conversation.menu` | 左侧抽屉会话长按菜单 | `menuItem` |
| `markdown.codeblock` | Markdown 代码块自定义渲染预览 | `renderer` (`{ type: "renderer", languages: ["my_lang"] }`) |
| `message.part` | 消息部件自定义渲染（按 `metadata["rikka.renderer"]`） | `renderer` (`{ type: "renderer", rendererType: "my_part" }`) |
| `tool.card` | 工具调用卡片自定义摘要与详情渲染 | `renderer` (`{ type: "renderer", toolNames: ["my_tool"], title?, summary?, icon?, path? }`) |

- **通用视觉与动态更新属性**：
  - `icon`: 支持 `"sparkles"`、`"terminal"`、`"brain"`、`"book"`、`"code"`、`"search"`、`"globe"`、`"settings"`、`"key"`、`"file"`、`"folder"`、`"image"`、`"voice"`、`"tts"`、`"bolt"`、`"tools"`、`"chat"`、`"database"`、`"server"` 等宿主图标；
  - `status`: `"ready"`（绿点）| `"running"`（主题色点）| `"warning"`（橙黄点）| `"error"`（红点）；
  - `switchKey`: 在 `entry` 上指定后，右侧显示独立开关并双向绑定 `rikka.storage`；
  - `menu`: 在 `chat.topbar` / `chat.input` 上传入 `[{ id, title, description?, icon?, path? }]` 点击即可展开圆角下拉菜单；
  - `rikka.ui.update(mountId, { text: "新状态", status: "running", badge: "2", visible: true })`：运行中随时局部更新任意已挂载项（页面级 `entry` 的 `title` / `description` / `badge` / `icon` 在所有页面——包括插件自身详情页——都会立即刷新）。

- **声明式配置表单 (`spec.type = "form"`) 示例**（字段值自动双向绑定 `rikka.storage`，且修改字段时若传入了 `handler` 会实时触发 `{ type: "change", key, value }` 回调）：
  ```js
  rikka.ui.mount("plugin.detail", {
    type: "form",
    title: "插件配置",
    fields: [
      { key: "enabledFlag", type: "switch", label: "启用功能", description: "说明文字", default: true },
      { key: "mode", type: "segmented", label: "工作模式", default: "fast", options: [
        { value: "fast", label: "快速模式" },
        { value: "deep", label: "深度模式" }
      ], visibleWhen: { key: "enabledFlag", equals: true } },
      { key: "temp", type: "slider", label: "采样温度", min: 0, max: 2, step: 0.1, default: 0.7 },
      { key: "apiKey", type: "password", label: "API 密钥", default: "" },
      { key: "targetModel", type: "model", label: "绑定模型" },
      { key: "resetBtn", type: "button", label: "重置统计", action: "reset" }
    ]
  }, (payload) => {
    if (payload.type === "change") {
      rikka.log.info("字段变更:", payload.key, payload.value);
    } else if (payload.action === "reset") {
      rikka.storage.set("count", 0);
      rikka.toast("已重置");
    }
  });
  ```
  - 支持的字段 `type`：`switch`、`text`、`textarea`、`password`、`number`、`slider`、`select`、`segmented`、`model`、`multiselect`、`list`、`button`；所有字段均支持 `visibleWhen: { key, equals }` 条件显隐。

- **校验与渲染回调**：
  - `ui.mount` 与 `ui.update` 时校验：`menu` 的每一项必须有 `id` 与 `title`；`languages` / `toolNames` 必须是字符串数组；`rendererType` 必须是字符串；`visible` 必须是布尔值。`rikka.ui.update` 的 `partialSpec` 必须是对象，`mountId` 不存在时报错。
  - `renderer`（`message.part` / `message.footer` / `tool.card` / `markdown.codeblock`）未指定 `path` 时调用 handler 渲染：返回 HTML / 文本字符串、`{ html }` 或 `{ text }`，返回 `null` 表示不显示；出错时在原位置显示错误并记入插件错误。

#### (2) 打开自定义 HTML WebView 面板：`rikka.ui.open(path, options)`
- `rikka.ui.open("panel.html", { presentation: "page" | "sheet" | "dialog" | "floating", title: "面板标题", width?: 320, height?: 380, args: {} })`
- **自动注入宿主 Material 3 主题与自适应高度**：
  - 所有插件 WebView 默认透明背景，自动在 `:root` 上注入宿主当前深浅色主题的 CSS 变量（`--rikka-primary`、`--rikka-primary-container`、`--rikka-surface`、`--rikka-surface-container`、`--rikka-on-surface`、`--rikka-outline`、`--rikka-error` 等）以及 `window.rikka.theme = { isDark, colors }`；
  - 内嵌 WebView（`markdown.codeblock`、`message.part`、`message.footer`、`tool.card` 及未固定 `height` 的 `html`）通过 `ResizeObserver` 自动根据内部 DOM 高度伸缩，也可主动调用 `window.rikka.resize(heightDp)`；
  - `floating` 悬浮窗支持点击顶栏按钮最小化折叠为可拖拽悬浮胶囊。
- 在 `panel.html` 内自动注入 `window.rikka`：
  - `await window.rikka.call(exposedMethodName, args)`：调用插件在 `main.js` 中通过 `rikka.expose(name, fn)` 暴露的方法；
  - `window.rikka.on(event, fn)`：监听插件通过 `rikka.ui.emit(event, data, { path? })` 推送的事件；
  - `window.rikka.resize(heightDp)` / `window.rikka.close()`：调整面板高度 / 关闭当前面板。

---

## 五、 插件内置与宿主主动调用 API

### 1. 免权限基础 API
- **私有 KV 存储（`rikka.storage`，同步读写，立即落盘并与 UI `form` 实时同步）**：
  - `rikka.storage.get(key)`、`rikka.storage.set(key, value)`、`rikka.storage.delete(key)`、`rikka.storage.keys()`
- **插件包内文件读写（`rikka.files`，异步 Promise，限定在插件自身目录内，支持文本与 Base64 二进制；包根目录下的 `manifest.json`（权限来源）与 `storage.json`（由 `rikka.storage` 管理）不能写入或删除，也不能删除包根目录本身）**：
  - `await rikka.files.read(path)`、`await rikka.files.write(path, text)`、`await rikka.files.readBase64(path)`、`await rikka.files.writeBase64(path, base64)`、`await rikka.files.list(path)`、`await rikka.files.exists(path)`、`await rikka.files.delete(path)`
- **聊天附件存储桥接（`rikka.attachments`）**：
  - `await rikka.attachments.save({ base64?, text?, fileName?, mime? })` -> 返回 `{ id, fileName, mime, path, url }`（返回的 `url` 可直接用于构造 `UIMessagePart.Image` / `Audio` / `Video` / `Document`）
- **日志与提示**：
  - `console.log(...)` / `rikka.log.info(...)` / `rikka.log.warn(...)` / `rikka.log.error(...)`（在插件详情页日志栏可见）
  - `await rikka.toast("提示文本")`
- **插件间互调**：
  - 暴露方法：`rikka.expose("myMethod", async (args) => { ... })`
  - 调用其他已启用插件：`await rikka.plugins.call("other.plugin.id", "myMethod", args)`

### 2. 需声明权限的宿主 API（均为 `async` 返回 Promise）
- **网络请求与 WebSocket（需 `"network"` 权限，支持单次请求级 `proxy` 控制、SSE 流式读取与二进制）**：
  - 普通或二进制请求：`const res = await rikka.fetch({ url, method: "POST", headers: {}, body: {}, bodyBase64?, base64?: false, proxy?: "direct" | "llm" | "workspace" | "mcp" | "socks5://...", timeoutMs? })` -> 返回 `{ status, headers, body, bodyBase64?, text(), json() }`
  - 流式请求（`stream: true`）：`const res = await rikka.fetch({ url, stream: true, proxy? })`，直接通过 `for await (const ev of rikka.sse.parse(res.body))` 实时流式消费
  - WebSocket 长连接：`const ws = await rikka.ws.connect({ url, headers?, proxy?, onOpen?, onMessage?, onClose?, onError? })` -> 返回 `{ id, send(data), sendBase64(b64), close(code?, reason?) }`
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
  - `rikka.workspace.list()`、`files(id, path, { area? })`、`read(id, path)`、`write(id, path, text, overwrite?)`、`readBase64(id, path)`、`writeBase64(id, path, base64, overwrite?)`、`delete(id, path, recursive?, { area? })`
  - `rikka.workspace.exec(id, command, { cwd?, timeout? })`、`terminal(id, opts)`、`terminals(id)`
- **设备与通知（需 `"device"` / `"notification"`）**：
  - `rikka.clipboard.read()`、`rikka.clipboard.write(text)`、`rikka.speak(text)`
  - `rikka.notify(title, text)`

---

## 六、 原生全能力：`rikka.java` / `rikka.hook` / `rikka.host`

> 以上各章是宿主**预先整理好**的接口；本章是**不经宿主整理、直接触达原生层**的能力：插件可以按名字调用宿主与 Android 系统里的任意 Java/Kotlin 类和成员（含私有成员与隐藏 API）、拿到宿主内部任意服务，并 Hook（拦截/改写/替换）宿主里的任意方法。
>
> - **需要声明权限**：`rikka.java` / `rikka.host` 需要 `native`，`rikka.hook` 需要 `hook`（`manifest.json` 中 `"permissions": ["native", "hook"]`）。未声明时调用直接报 `缺少权限 native / hook`，且在创建反射桥、加载 Hook 引擎之前就拒绝。只声明 `hook` 时，回调里仍可修改基本类型 / 字符串形式的参数与返回值，但要调用回调中对象（如 `p.thisObject`）的方法需要同时声明 `native`。权限只能由插件包作者在 manifest 中声明，插件运行时无法经 `rikka.files` 改写自己的 manifest。
> - **不用就不存在**：只有插件第一次调用 `rikka.java.*` 时才创建反射桥；只有第一次调用 `rikka.hook.*` 时才加载 Hook 引擎（[Pine](https://github.com/canyie/pine)）的原生库。没有插件或插件不用这些接口时，宿主运行与之前完全一致。
> - **插件停止即还原**：停用/卸载/重载插件时，该插件的全部 Hook 自动解除，全部 Java 句柄自动释放。
> - **已在真机实测**：Android 16（SDK 36）上反射、隐藏 API、宿主对象、接口代理、宿主方法 Hook、系统方法 Hook 全部通过。仓库内 [`test-plugins/native-selftest`](test-plugins/native-selftest/main.js)（打包为 `test-plugins/native-selftest.zip`）是覆盖本章全部能力的自测插件，也可作为用法示例。
> - 为保证「按名字能找到、Hook 能生效」，正式版对宿主代码（`me.rerere.**`，不含图标库）与 `kotlin.uuid.**` 中**实际被使用的部分**保持原样：不改名、不改签名、不内联、不合并，且全模块开启 `-java-parameters` 保留方法参数名元数据（见 `app/proguard-rules.pro`）。**宿主自己从未使用的代码仍会在正式版中被删除**，插件无法调用这部分代码；第三方库照常裁剪，只保留宿主用到的部分。

### 1. 反射桥 `rikka.java`

#### (1) 拿到类与对象
```js
const Toast  = rikka.java.use("android.widget.Toast");          // 类（支持 "int"、"java.lang.String[]"、内部类 "a.B$C"）
const list   = rikka.java.new("java.util.ArrayList");           // 构造对象（自动按实参挑选构造函数重载）
const sb     = rikka.java.use("java.lang.StringBuilder").$new("a");
const ints   = rikka.java.array("int", [1, 2, 3]);              // Java 数组
const nextA  = rikka.java.copy(assistant, { name: "新载体", enableWebSearch: true }); // 无损浅拷贝 Kotlin data class
```

#### (2) 调用方法、读写字段与浅拷贝数据类
Java 对象在 JS 里是一个代理，**直接按方法名调用**即可：
```js
sb.append("b").append(1);       // 实例方法（含父类、接口默认方法、private 方法）
sb.toString();                  // "ab1"
Math.max(3, 7);                 // 类上的调用 = 静态方法；找不到同名静态方法时回退为 Class 对象自身的方法（如 getName）
obj.$get("secret");             // 读字段（含 private、父类字段）
obj.$set("secret", "x");        // 写字段（也可直接 obj.secret = "x"）；对类则读写静态字段
obj.$copy({ limit: 64 });       // 按编译期保留的 MethodParameters 参数名无损拷贝 data class（勿手写 getDeclaredFields 拼参，Android ART 会重排字段顺序）
obj.$call("foo(int,java.lang.String)", 1, "a");   // 显式指定重载签名
Cls.$newSig("int,java.lang.String", 1, "a");      // 显式指定构造函数签名
```

| 包装对象上的特殊成员 | 说明 |
|---|---|
| `$id` / `$type` / `$name` | 句柄 id / 运行时类名 / （类对象）所代表的类名 |
| `$call(name, ...args)` | 按名字调用方法（方法名与特殊成员冲突时使用） |
| `$get(f)` / `$set(f, v)` | 读写字段 |
| `$copy(patch)` | 无损浅拷贝 Kotlin `data class` 实例（等同于 `rikka.java.copy(obj, patch)`，支持一次修改多个字段） |
| `$new(...)` / `$newSig(sig, ...)` | （类对象）构造实例 |
| `$async(name, ...args)` | **在后台线程**调用方法，返回 Promise（见下方「线程模型」） |
| `$newAsync(...)` | 在后台线程构造实例，返回 Promise |
| `$main(name, ...args)` / `$mainGet(f)` / `$mainSet(f, v)` | **在主线程**调用方法 / 读写字段，返回 Promise（操作界面时使用） |
| `$json()` | 把集合 / 数组 / Map 深度转换为普通 JS 值 |
| `$is(cls)` | `instanceof` 判断 |
| `$members()` | 列出该类的全部方法、字段、构造函数签名（调试用） |
| `$release()` | 释放句柄 |

#### (3) 值的转换规则
| JS → Java | 说明 |
|---|---|
| `null` / `boolean` / `string` | 对应类型；`string` 也可转为 `char`（长度 1）、枚举（按名字）、`CharSequence`、`JSONObject`/`JSONArray` |
| `number` | 按目标参数类型转为 `int` / `long` / `double` / `float` / `short` / `byte`；重载时整数优先匹配 `int` |
| `BigInt` | `long`（超过 2^53 的长整数） |
| 数组 | Java 数组 / `List` / `Set` |
| 普通对象 | `Map` / `JSONObject` |
| Java 包装对象 | 原对象 |
| **JS 函数** | 当参数类型是接口时，自动生成该接口的实现（如 `Runnable`、`Comparator`、`Function`、各种 Listener、Kotlin 的 `FunctionN` lambda） |

| Java → JS | 说明 |
|---|---|
| `null` / `boolean` / `String` / `char` | 对应值 |
| 数字 | `number`；超过 2^53 的 `long` 为 `BigInt` |
| 其他一切对象（含集合、数组） | Java 包装对象（需要普通 JS 值时调用 `$json()`） |

#### (4) 实现 Java 接口：`rikka.java.proxy(interfaces, impl, opts?)`
```js
const r = rikka.java.proxy("java.lang.Runnable", { run() { console.log("run!"); } });
const l = rikka.java.proxy(["android.view.View$OnClickListener"], function (view) {
  // 传函数时：接口的任何方法都调用它，this.method 为被调用的方法名
});
```
- `opts.async: true`：回调不等待 JS 返回（适合高频、只需通知的监听器）。

#### (5) 线程模型（重要）
- 普通调用（`obj.foo()`）在**插件自己的 JS 线程**上同步执行。
- 如果这次 Java 调用**又同步回调了 JS**（比较器、`forEach` 的 lambda、被 Hook 的方法等），由于 JS 线程正被占用，**回调拿不到返回值**（按默认值 0/false/null 处理，插件日志会给出提示）。这种情况请用 **`await obj.$async(...)`** 发起调用：Java 在后台线程执行，期间 JS 线程空闲，回调可以正常同步返回。
  ```js
  await rikka.java.use("java.util.Collections").$async("sort", list, (a, b) => a - b);
  ```
- 必须在主线程执行的 API（操作 View、Toast 等）请用 `await obj.$main(...)`。
- 接口实现 / Hook 回调在其他线程被调用时，会**阻塞该线程**直到 JS 返回（默认最多 10 秒，超时按「不修改」处理）。在主线程上频繁触发的回调会直接拖慢界面，这是使用者自己的取舍。

#### (6) 句柄与内存
- Java 对象在宿主侧以句柄表保存，同一对象复用同一 id；JS 侧不再使用的对象可 `rikka.java.release(obj)` 释放，`rikka.java.stats()` 查看句柄与 Hook 数量。
- Hook / 接口回调传给 JS 的 `thisObject`、参数、返回值是**临时句柄**：在**返回值解码完成后**自动释放（因此回调里直接原样 `return arg` 是安全的）。需要在回调之外继续使用时，请在回调内把需要的值取出（例如 `toString()`、`$json()`），或用自己的 Java 调用重新获取。

### 2. 宿主对象 `rikka.host`
```js
const app      = rikka.host.context();     // Application
const activity = rikka.host.activity();    // 当前前台 Activity（首次调用时才开始跟踪）
const koin     = rikka.host.koin();        // Koin 容器
const settings = rikka.host.get("me.rerere.rikkahub.data.datastore.SettingsStore");  // 从 Koin 取任意已注册服务/单例
const repo     = rikka.host.get("me.rerere.rikkahub.data.repository.ConversationRepository");
```
- `rikka.host.get(cls, qualifier?)` 等价于宿主代码里的 `get<Cls>(named(qualifier))`；对 Koin 里的 `factory` / `viewModel` 定义会**新建实例**，不是界面上正在使用的那一个（需要界面里的实例时，可通过 Activity 的 ViewModelStore 或 Hook 获取）。
- `rikka.host(name, args)` 原有的通用宿主 API 调用方式保持不变。

### 3. 方法 Hook `rikka.hook`
```js
// Hook 某方法的全部重载
// 例：拦截宿主里所有 Toast 文本
const h = rikka.hook.method("android.widget.Toast", "makeText", ["android.content.Context", "java.lang.CharSequence", "int"], {
  before(p) {
    console.log("调用", p.method, "参数", p.args[1]);
    p.args[1] = "[插件] " + p.args[1];   // 修改参数
    // p.setResult(null);                // 直接给出返回值，跳过原方法
    // p.setThrowable("不允许");          // 让原方法抛出异常
  },
  after(p) {
    // p.result 为原返回值；给 p.result 赋值或调用 p.setResult(v) 即可替换返回值
  },
});
h.unhook();                          // 解除；插件停止时也会自动解除

rikka.hook.method(cls, "foo", ["int", "java.lang.String"], { before(p) {} });   // 指定重载
rikka.hook.constructor("android.widget.TextView", { after(p) {} });             // Hook 构造函数（全部重载）
```

| 回调参数 `p` | 说明 |
|---|---|
| `p.method` | 被调用方法的签名字符串 |
| `p.thisObject` | 调用对象（静态方法为 `null`） |
| `p.args` | 参数数组，可直接修改元素 |
| `p.result` / `p.throwable` | （`after`）原方法返回值 / 抛出的异常描述；`after` 中直接给 `p.result` 赋值即生效 |
| `p.setResult(v)` | `before` 中调用则跳过原方法；`after` 中调用则替换返回值 |
| `p.setThrowable(msg)` | 让本次调用抛出 `RuntimeException(msg)` |
| `p.callOriginal(...args?)` | 在回调中调用原方法（不传参数则用当前参数） |

| 选项（与 `before`/`after` 写在同一对象里） | 说明 |
|---|---|
| `async: true` | 不等待 JS 处理完（只观察，不能修改参数/返回值），不阻塞被 Hook 的线程 |
| `timeout: 毫秒` | 同步等待 JS 的超时，默认 10000；超时按「不修改」放行原方法 |

**限制（老实写清楚）**：
- Hook 引擎依赖 ART 内部实现，**不同 Android 版本 / ROM 可能不兼容**（初始化失败时 `rikka.hook.*` 直接抛错，不影响宿主其他功能）。
- 被 ART 编译器**内联进调用方**的方法，Hook 可能不触发。宿主自身代码已通过保留规则避免 R8 内联；仍不生效时，可对**调用方**使用 `rikka.hook.deoptimize(cls, name)` 强制其解释执行。
- 插件**自己在 JS 线程上同步调用**了被自己 Hook 的方法时，回调不会执行（会死锁），原方法照常运行并在日志提示；用 `$async` 调用即可。
- 被 Hook 方法在主线程上被调用时，主线程会等待 JS 回调完成。
