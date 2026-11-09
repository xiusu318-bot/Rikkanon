# Rikkanon 插件系统架构设计文档

> **适用版本**：`v1.2.5+`
> **开发文档**：完整的插件开发接口与示例请参阅 [**`PLUGIN_API_DOC.md`**](PLUGIN_API_DOC.md)

---

> [!CAUTION]
> ### ⚠️ 插件安全与高自由度免责声明（使用前必读）
> - **请务必完整审阅并了解插件全部源代码及作用**：在导入和启用任何插件前，请确保它是你自己编写的代码，或你已经完整阅读并 100% 理解其全部 JS 源码与行为逻辑。
> - **App 层面不对插件施加额外限制**：出于作者个人自用需求（不喜欢过多繁琐、严苛的沙盒阉割与安全限制），**Rikkanon 在应用层面对插件采取「能力完全开放、使用者自负」的设计原则，没有任何针对插件能力的阉割或额外限制**。插件在声明对应权限后即可读写对话、修改全局设置与提示词预设、拦截并改写底层 HTTP 请求与流式响应、发起网络请求以及操控工作区终端与子代理，拥有与宿主应用平级的完整控制权。

---

## 〇、核心设计原则

1. **轻量 JS 插件与高自由度开放**：插件由 `manifest.json` + 入口 `main.js`（ES2020 ES Module，支持 `import` 插件包内其他模块）组成，运行在内置 `quickjs-kt`（QuickJS）引擎中。出于个人使用对高自由度的需求，宿主不设繁琐的能力阉割，全面开放请求链路、对话、设置、预设、世界书、工作区与 UI 挂载点。
2. **不开不生效**：宿主默认不预置任何插件，已安装插件默认禁用，仅由用户手动开关。没有启用插件时，所有 Hook 链为空，执行路径与无插件时完全一致。
3. **显式权限校验与单项能力开关**：插件调用受控宿主 API 时必须在 `manifest.json` 中声明对应权限（未声明直接报错）；插件实际注册的工具、Hook、宏、底层服务与 UI 挂载项全部在插件详情页自动列出，支持用户逐项单独开关。
4. **老实报错，绝不静默兜底**：插件抛错、超时或返回格式不合法时，当前操作直接报错并以 `[plugin:<id>] <hook>: ` 标明来源，不静默吞错、不偷偷降级。同时，`PluginInterceptor` 遇到上游网络断开等原生 `IOException` 时原样透传，保留宿主自动网络重试逻辑。
5. **数据自描述**：插件影响消息显示或语义的信息（如工具作为正文气泡渲染）直接写入消息部件 `metadata`，插件停用或卸载后历史消息依然照常显示。

---

## 一、插件包与运行时

### 1. 包结构
```text
filesDir/plugins/<id>/
  manifest.json
  main.js
  ...            （可选）插件自带的任意文件，如子模块 JS、面板 HTML
  storage.json   （宿主维护的插件 KV 存储，由 PluginStore 单例管理）
```

### 2. 清单格式 (`manifest.json`)
```json
{
  "id": "com.example.demo",
  "name": "Demo",
  "version": "1.0.0",
  "apiVersion": 1,
  "main": "main.js",
  "description": "...",
  "permissions": ["network", "settings.read"]
}
```
- `manifest.json` 仅描述插件身份与所需宿主 API 权限；插件注册了哪些工具、Hook、服务或 UI 完全由运行时动态注册决定，无需在清单中静态声明。
- 注册未知的 Hook 名、事件名、UI 挂载点或不支持的 `spec.type` 会直接报错。

### 2.1 权限表
| 权限 | 对应能力 |
|---|---|
| `network` | `rikka.fetch` |
| `conversation.read` / `conversation.write` | `rikka.conversations` 读 / 写 |
| `subagent` | `rikka.subAgents` |
| `settings.read` / `settings.write` | `rikka.settings`、`rikka.presets`、`rikka.lorebooks`、`rikka.regexes` 读 / 写 |
| `memory` | `rikka.memory` 读 / 写 |
| `workspace` | `rikka.workspace` 文件、命令与持久化终端 |
| `notification` | `rikka.notify` |
| `device` | `rikka.clipboard`、`rikka.speak` |

免权限能力：`log`、`toast`、`storage`（插件私有 KV）、`files`（插件自身目录读写）、注册工具 / Hook / 宏 / 服务实现 / 触发入口、UI 挂载与打开自有面板、插件间互调。

### 3. 运行时与事件循环
- 每个已启用插件拥有独立的常驻 QuickJS 实例与专用单线程调度器。
- 加载流程：读取 `manifest.json` → 创建实例 → 注入 `rikka` 全局对象 → 以 ES Module 模式执行 `main.js` → 收集注册结果并由 `PluginCapabilitySync` 同步能力。
- 常驻事件循环：插件加载后启动常驻 `evaluate` 事件循环，宿主通过 Channel 投递调用请求，JS 侧取出后并发交错分发异步 Handler，完成后经回调通道回传 JSON 结果。
- 超时与内存限制：可在插件详情页单独调整调用超时（默认 30s，`0` = 不限）与内存上限（默认 256MB）。

### 4. 统一上下文 `ctx`
```json
{
  "task": "chat | title | suggestion | compress | memory | translate | ocr | other",
  "conversationId": "...",
  "assistantId": "...",
  "isSubAgent": false,
  "parentConversationId": null,
  "subAgentCode": null,
  "stepIndex": 0,
  "model": { "id": "...", "modelId": "...", "displayName": "..." },
  "workspaceId": "..."
}
```

---

## 二、核心能力概览

### 1. 工具与请求/响应全链路改写
- **注册工具 (`rikka.registerTool`)**：与内置/MCP 工具平级注入；支持 `display`（`as: "tool" | "text" | "hidden"`，`source: "input" | "output"`，支持流式提取工具入参字段实时作为正文渲染）、`endTurn`（结束本轮生成）、`when(ctx)`（按上下文动态下发）以及 `ctx.progress(parts)`（实时推送工具执行中间进度）。
- **请求/响应改写 Hook**：
  - `tools.list`：过滤或动态覆盖工具列表定义（`description` / `parameters` / `systemPrompt` / `display` / `endTurn`）。
  - `params.beforeSend`：改写请求参数（`temperature` / `topP` / `maxTokens` / `reasoningLevel` / `headers` / `body`，其中 `body` 递归合并进最终 API 请求 JSON 顶层）。
  - `intercept`：OkHttp 原始层中间件，支持改写任意 HTTP 请求与流式 SSE 响应（配合 `rikka.sse.parse` / `rikka.sse.encode`）。
  - `onStream`：统一 `StreamChunk` 事件流拦截与转换（支持按 `types` 按需订阅）。
  - `chat.history` / `preset.beforeAssemble` / `lorebook.activated` / `prompt.assembled` / `messages.beforeSend`：从纯对话历史、预设块结构、激活世界书到最终合流消息列表（自动携带并还原 `isSynthetic` 与 `rawJson` 边界标记）的分层改写。
  - `response.visual` / `response.finish`：流式视觉渲染变换与生成结束落库前变换。
  - `tool.before` / `tool.after` / `approval.decide`：工具执行前后拦截与动态审批策略（`auto` / `user` / `main_agent` / `deny`）。

### 2. 宏、提示词块、正则与后台任务
- `registerMacro(name, fn)`：扩展 `{{name}}` / `{{name:arg}}` 宏（含 JSON 模式提示词块内递归展开）。
- `registerPromptBlock(type, fn)`：扩展 `{{plugin:<id>:<type>}}` 自定义提示词块。
- `registerRegex({ name, phase, scope, replace })`：参与发出（`request`）、显示（`visual`）、落库（`persist`）三阶段正则替换。
- 后台任务 Hook：支持 `title`、`suggestion`、`compress`、`memory`、`translate`、`ocr` 的 `.before` / `.after` / `.replace`。

### 3. 底层服务自动同步 (`PluginCapabilitySync`)
- 插件通过 `registerProvider`、`registerSearch`、`registerTTS`、`registerASR`、`registerMediaGen` 注册底层服务实现后，宿主在插件启用或能力开关变化时自动同步至对应可用列表，停用或卸载时自动清理，无需在设置页手动创建渠道。

### 4. UI 挂载点与自定义 WebView 面板
- **挂载点 (`rikka.ui.mount`)**：
  - 页面级（支持 `form` / `entry` / `html`，其中 `form` 双向实时绑定单例 `PluginKvStorage`）：`plugin.detail`（插件自身详情页内聚渲染）、`extensions.list`、`settings.group`、`assistant.detail`、`provider.detail`、`mcp.detail`、`workspace.detail`。
  - 聊天级：`chat.topbar`（`button` / `badge`）、`chat.input`（`button`）、`chat.slash`（`command` 斜杠补全）、`message.menu`（`menuItem` 消息菜单）。
  - 渲染级：`markdown.codeblock`（自定义代码块预览）、`message.part`（自定义消息部件渲染）。
- **自定义面板 (`rikka.ui.open`)**：支持以 `page` / `sheet` / `dialog` / `floating` 打开插件内 HTML 面板，并通过 `window.rikka.call` / `window.rikka.on` 与插件 QuickJS 实例双向通信。

---

## 三、模块代码结构

```text
:plugin（独立 Gradle 模块，依赖 :ai、:common）
  me.rerere.plugin
    manifest/   PluginManifest（清单解析与权限定义）
    runtime/    PluginRuntime（QuickJS 实例 + 常驻事件循环 + 单线程调度器 + 超时与内存限制）
                JsBridge（rikka 全局对象注入、JSON 交换、Promise/挂起函数桥接）
    registry/   PluginRegistry（工具 / Hook / 流处理器 / 拦截器 / 宏 / UI 挂载项注册表）
    host/       PluginHost（安装、启停、优先级、单项能力开关、按 Hook 取执行链）
    error/      PluginException（统一错误前缀包装與 IOException 透传）
    store/      PluginStore & PluginKvStorage（filesDir/plugins 与单例 KV 存储）

:app（宿主适配与 UI 层）
  app/.../data/plugin/
    PluginBridge、AppPluginHostApi、PluginCapabilitySync、PluginConversationApi、
    PluginSettingsApi、PluginWorkspaceApi、PluginProviderDelegates、PluginScheduleManager
  app/.../ui/pages/extensions/plugin/
    PluginsPage、PluginDetailPage、PluginPanelPage、PluginSlotMounts
```
