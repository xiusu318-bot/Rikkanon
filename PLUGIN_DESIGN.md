# Rikkanon 插件系统架构设计文档

> **适用版本**：`v1.2.5+`
> **开发文档**：完整的插件开发接口与示例请参阅 [**`PLUGIN_API_DOC.md`**](PLUGIN_API_DOC.md)

---

> [!CAUTION]
> ### ⚠️ 插件安全与高自由度免责声明（使用前必读）
> - **请务必完整审阅并了解插件全部源代码及作用**：在导入和启用任何插件前，请确保它是你自己编写的代码，或你已经完整阅读并 100% 理解其全部 JS 源码与行为逻辑。
> - **App 层面不对插件施加额外限制**：出于作者个人自用需求（不喜欢过多繁琐、严苛的沙盒阉割与安全限制），**Rikkanon 在应用层面对插件采取「能力完全开放、使用者自负」的设计原则，没有任何针对插件能力的阉割或额外限制**。插件在声明对应权限后即可读写对话、修改全局设置与提示词预设、拦截并改写底层 HTTP 请求与流式响应、发起网络请求以及操控工作区终端与子代理；声明 `native` / `hook` 权限后还可通过 `rikka.java` / `rikka.host` / `rikka.hook` 反射调用宿主与系统的任意代码、取得宿主内部任意服务、Hook 任意方法（见第二章第 5 节），拥有与宿主应用平级的完整控制权。

---

## 〇、核心设计原则

1. **轻量 JS 插件与高自由度开放**：插件由 `manifest.json` + 入口 `main.js`（ES2020 ES Module，支持 `import` 插件包内其他模块）组成，运行在内置 `quickjs-kt`（QuickJS）引擎中。出于个人使用对高自由度的需求，宿主不设繁琐的能力阉割，全面开放请求链路、对话、设置、预设、世界书、工作区与 UI 挂载点。
2. **不开不生效**：宿主默认不预置任何插件，已安装插件默认禁用，仅由用户手动开关。没有启用插件时，所有 Hook 链为空，执行路径与无插件时完全一致。
3. **显式权限校验与单项能力开关**：插件调用受控宿主 API 时必须在 `manifest.json` 中声明对应权限（未声明直接报错；权限声明只是让能力可见、可追溯，不弹窗、不限制已声明能力的用法）；`manifest.json` 与 `storage.json` 由宿主管理，插件不能经 `rikka.files` 改写或删除，从而无法在运行时给自己追加权限；插件实际注册的工具、Hook、宏、底层服务与 UI 挂载项全部在插件详情页自动列出，支持用户逐项单独开关。
4. **老实报错，绝不静默兜底**：插件抛错、超时或返回格式不合法时，当前操作直接报错并以 `[plugin:<id>] <hook>: ` 标明来源，不静默吞错、不偷偷降级。同时，`PluginInterceptor` 遇到上游网络断开等原生 `IOException` 时原样透传，保留宿主自动网络重试逻辑。
5. **原生全能力，不用不加载**：除宿主整理好的接口外，声明 `native` / `hook` 权限的插件可通过反射桥（`rikka.java`）、宿主对象（`rikka.host`）与方法 Hook（`rikka.hook`）直接触达原生层；这些能力在插件首次使用时才初始化（Hook 引擎首次 Hook 时才加载原生库），没有插件使用时宿主执行路径不变；插件停止时其 Hook 与 Java 句柄全部自动清理。
6. **数据自描述**：插件影响消息显示或语义的信息（如工具作为正文气泡渲染）直接写入消息部件 `metadata`，插件停用或卸载后历史消息依然照常显示。

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
| `network` | `rikka.fetch`（含流式 `stream: true`、二进制 `base64`、单次请求级 `proxy`）、`rikka.ws`（WebSocket 客户端） |
| `conversation.read` / `conversation.write` | `rikka.conversations` 读 / 写 |
| `subagent` | `rikka.subAgents` |
| `settings.read` / `settings.write` | `rikka.settings`、`rikka.presets`、`rikka.lorebooks`、`rikka.regexes` 读 / 写 |
| `memory` | `rikka.memory` 读 / 写 |
| `native` | `rikka.java`（反射桥）、`rikka.host`（宿主对象） |
| `hook` | `rikka.hook`（方法 Hook） |
| `workspace` | `rikka.workspace` 文件（含 `readBase64` / `writeBase64`）、命令与持久化终端 |
| `notification` | `rikka.notify` |
| `device` | `rikka.clipboard`、`rikka.speak` |

免权限能力：`log`、`toast`、`storage`（插件私有 KV）、`files`（插件自身目录文本与 `readBase64`/`writeBase64` 二进制读写）、`attachments.save`（保存媒体/文档到宿主聊天附件存储）、注册工具 / Hook / 宏 / 声明式资源（Skill、世界书、快捷消息） / 服务实现 / 触发入口 / `onUnload`、UI 挂载与动态更新（`ui.update`）、打开自有面板、插件间互调。

### 3. 运行时与事件循环
- 每个已启用插件拥有独立的常驻 QuickJS 实例与专用单线程调度器（注意：JS 实际执行所在的线程并不固定，涉及线程判断的逻辑见第二章第 5 节「线程模型」）。
- 加载流程：读取 `manifest.json` → 创建实例 → 注入 `rikka` 全局对象 → 以 ES Module 模式执行 `main.js` → 收集注册结果并由 `PluginCapabilitySync` 同步能力。
- 常驻事件循环：插件加载后启动常驻 `evaluate` 事件循环，宿主通过 Channel 投递调用请求，JS 侧取出后并发交错分发异步 Handler，完成后经回调通道回传 JSON 结果。
- 超时与内存限制：可在插件详情页单独调整调用超时（默认 30s，`0` = 不限）与内存上限（默认 256MB）。工具执行中等待用户回答 `ctx.askUser` 的时间不计入调用超时（`PluginCallPause`）。
- 工具中间状态：`ctx.progress` / `ctx.askUser` 对消息的更新先写入通道，由生成流程自身的协程统一发出（`flow` 不允许从工具协程或插件运行时协程直接发射）。

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
- **注册工具 (`rikka.registerTool`)**：与内置/MCP 工具平级注入；支持动态 `parameters(ctx)` 与 `systemPrompt(ctx)` 函数求值、`display`（`as: "tool" | "text" | "hidden"`，`source: "input" | "output"`，且返回 `{ model, display }` 时 `display` 支持 `Text`/`Image`/`Audio`/`Video`/`Document`/`Reasoning` 任意部件）、`endTurn`（结束本轮生成）、`when(ctx)`（按上下文动态下发）、`ctx.progress(parts)`（实时推送工具执行中间进度）以及 **`await ctx.askUser(spec)`（在工具执行中途挂起并在工具步骤卡片内弹出单选/多选/文本交互提问，等待用户回答后无缝继续执行）**。
- **请求/响应改写 Hook**：
  - `tools.list`：过滤或动态覆盖工具列表定义（`description` / `parameters` / `systemPrompt` / `display` / `endTurn`）。
  - `params.beforeSend`：改写请求参数（`temperature` / `topP` / `maxTokens` / `reasoningLevel` / `headers` / `body`，其中 `body` 递归合并进最终 API 请求 JSON 顶层）。
  - `intercept`：OkHttp 原始层中间件，支持改写任意 HTTP 请求、流式 SSE 响应（配合 `rikka.sse.parse` / `rikka.sse.encode`）以及通过 **`next({ ...req, proxy })` 按单次请求动态切换代理节点或强制直连**。
  - `onStream`：统一 `StreamChunk` 事件流拦截与转换（支持按 `types` 按需订阅）。
  - `chat.history` / `preset.beforeAssemble` / `lorebook.activated` / `prompt.assembled` / `messages.beforeSend`：从纯对话历史、预设块结构、激活世界书到最终合流消息列表（自动携带并还原 `isSynthetic` 与 `rawJson` 边界标记）的分层改写。
  - `response.visual` / `response.finish`：流式视觉渲染变换与生成结束落库前变换。
  - `tool.before` / `tool.after` / `approval.decide`：工具执行前后拦截与动态审批策略（`auto` / `user` / `main_agent` / `deny`）。

### 2. 宏、提示词块、正则、声明式资源与后台任务
- `registerMacro(name, fn)` / `registerPromptBlock(type, fn)`：在高级提示词预设与世界书组装阶段即参与展开（含 JSON 模式提示词块内递归展开）。
- `registerRegex({ name, phase, scope, replace })`：参与发出（`request`）、显示（`visual`）、落库（`persist`）三阶段正则替换。
- **声明式资源同步 (`registerSkill` / `registerLorebook` / `registerQuickMessage`)**：插件注册后自动同步到可用 Agent Skills、世界书、快捷消息列表中，插件停用或卸载时自动清理，不残留配置垃圾。
- `onUnload(fn)` 与详情页「重新加载插件」：支持停止/卸载前清理资源与运行时一键热重载。
- 后台任务 Hook：支持 `title`、`suggestion`、`compress`、`memory`、`translate`、`ocr` 的 `.before` / `.after` / `.replace`。

### 3. 底层服务自动同步与真流式通道 (`PluginCapabilitySync` & `callMethodStream`)
- 插件通过 `registerProvider`、`registerSearch`、`registerTTS`、`registerASR`、`registerMediaGen` 注册底层服务实现后，宿主在插件启用或能力开关变化时自动同步至对应可用列表，停用或卸载时自动清理，无需在设置页手动创建渠道。
- **真流式迭代协议 (`__method_stream_open` / `__iter_next_json`)**：`registerProvider.streamText`、`generateImage`、`editImage` 与 `registerTTS.speak` 支持 `async function*` 异步生成器逐块 `yield`，宿主通过 `Flow` 实时拉取消费，不再缓冲等待全部完成；`registerProvider` 完整支持模型能力元数据（`abilities`、`inputModalities`、`outputModalities`、`tools`、`contextLength`、`type`）及 `getBalance`、`generateEmbedding`、`generateImage`、`editImage`；`registerASR` 同时支持分段 WAV `transcribe` 与实时 PCM 流 `startStream` / `pushAudio` / `stopStream`。

### 4. UI 挂载点、动态更新与自适应主题 WebView 面板
- **挂载点 (`rikka.ui.mount`) 与动态更新 (`rikka.ui.update`)**：
  - 页面级（支持 `form` / `entry` / `html`，其中 `entry` 支持 `icon`、`badge` 与右侧独立开关 `switchKey`；`form` 支持 `switch`、`text`、`textarea`、`password`、`number`、`slider`、`select`、`segmented`、`model`、`multiselect`、`list`、`button` 12 类控件，双向绑定 `PluginKvStorage` 并在字段修改时实时触发 `handler({ type: "change", key, value })`）：`plugin.detail`、`extensions.list`、`settings.group`、`assistant.detail`、`provider.detail`、`mcp.detail`、`workspace.detail`。
  - 聊天与抽屉级：`chat.more`（聊天输入栏 `+` 扩展面板）、`chat.topbar`（`button` / `badge` / `menu`，支持状态圆点 `status`、图标 `icon` 与下拉菜单 `menu`）、`chat.input`（`button` / `badge` / `menu`）、`chat.slash`（`command` 斜杠补全）、`message.menu`（消息长按菜单，支持按角色/内容条件过滤）、`message.footer`（消息气泡底栏 `badge` / `button` / `html` / `renderer`）、`conversation.menu`（左侧抽屉会话长按菜单）。
  - 渲染级：`markdown.codeblock`（自定义代码块预览）、`message.part`（自定义消息部件渲染）、`tool.card`（按 `toolNames` 自定义工具步骤卡片图标/标题/内联摘要与详情预览）。
  - 动态更新：`rikka.ui.update(mountId, partialSpec)` 可在运行中随时局部更新任意已挂载项的文字、状态色、徽标、菜单或显隐（`visible`）。
- **自定义面板 (`rikka.ui.open`) 与内嵌 WebView 增强**：
  - 支持以 `page` / `sheet` / `dialog` / `floating`（支持自定义宽高与一键最小化折叠为悬浮胶囊）打开插件内 HTML 面板；
  - 所有 WebView 默认透明背景，自动在 `:root` 注入宿主当前深浅色 Material 3 颜色变量（`--rikka-primary`、`--rikka-surface-container`、`--rikka-on-surface` 等）与 `window.rikka.theme`；
  - 内嵌 WebView 通过 `ResizeObserver` 自动随 DOM 内容高度伸缩（也支持 `window.rikka.resize(heightDp)`），并通过 `window.rikka.call` / `window.rikka.on` 与插件 QuickJS 实例双向通信。

### 5. 原生全能力：反射桥、宿主对象与方法 Hook

> 插件接口细节见 [`PLUGIN_API_DOC.md` 第六章](PLUGIN_API_DOC.md)；本节说明实现方式与约束。

- **权限**：`java.hook` / `java.unhook` / `java.callOriginal` / `java.deoptimize` 需要 `hook`，其余 `java.*`（含 `java.main.*` / `java.async.*`）需要 `native`，在 `PluginRuntime` 入口处、创建反射桥与加载 Pine 之前校验；`host.*` 由 `AppPluginHostApi.callSync` 校验 `native`。

- **反射桥（`PluginJavaBridge`，每个插件运行时一份，首次使用时创建）**
  - **句柄表**：Java 对象以 `{ $j: id, $c: 类名 }` 传给 JS，JS 侧包装为 Proxy（按名字调用方法、读写字段）；同一对象（引用相等）复用同一 id。基本类型直接传递，超过 2^53 的 `long` 为 BigInt，JS 函数传给接口参数时自动生成 `java.lang.reflect.Proxy`。
  - **成员解析**：遍历类层级与全部接口，包含非 public 成员与接口默认方法；按实参逐个评分选择重载（支持变参与显式签名）；声明类非 public 时改用公开父类型上的同签名方法调用。
  - **隐藏 API**：首次调用时通过 [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 放开 Android 9+ 的隐藏 API 反射限制。
- **宿主对象（app 层 `AppPluginHostApi.callSync`）**：`host.context`（Application）、`host.activity`（前台 Activity；`PluginCurrentActivity` 首次请求时才注册生命周期回调）、`host.koin` / `host.get`（从 Koin 取任意服务）。
- **方法 Hook（`PluginHookEngine`，基于 [Pine](https://github.com/canyie/pine)）**：首次 Hook 时才初始化 Pine 并关闭 JIT 内联；回调把 `this` / 参数 / 返回值编码后交给 JS，JS 修改后写回调用帧（改参数、`setResult` 跳过原方法、改返回值、`setThrowable`、`callOriginal`）。回调传给 JS 的对象是临时句柄，回调结束即释放。
- **线程模型**
  - JS 同步调用 Java（`__host_sync`）期间，该线程上的 JS 被占住：若 Java 此时又同步回调 JS（接口代理、Hook），无法等待自身，按默认值立即返回并写一条日志提示。判断依据是「当前线程正处于同步宿主调用中」的线程标记，而不是线程身份（QuickJS 的执行线程并不固定）。
  - `$async(...)` 在后台线程执行 Java、`$main(...)` 在主线程执行，期间 JS 空闲，Java 同步回调 JS 可正常拿到返回值。
  - 其他线程上的回调会阻塞该线程等待 JS（默认最多 10 秒，超时按「不修改」处理）。
- **清理**：插件停止（停用 / 卸载 / 重载）时 `PluginRuntime.close()` 先解除该插件全部 Hook、释放全部句柄。
- **构建配置（正式版 R8）**
  - 全模块启用 Kotlin `-java-parameters`（`MethodParameters` 保留），配合 `rikka.java.copy(obj, patch)` / `obj.$copy(patch)` 按参数名无损浅拷贝任意宿主 `data class`（不受 Android ART `getDeclaredFields()` 字段重排影响）。
  - `app/proguard-rules.pro`：对宿主代码（`me.rerere.**`，不含外部图标库 `me.rerere.hugeicons`）与 `kotlin.uuid.**` 使用 `-keep,allowshrinking`：实际被使用的代码保持原样（不改名、不改签名、不内联、不合并），保证反射按名字可达、Hook 能生效；宿主从未使用的代码仍会被删除。第三方库照常裁剪。
  - `app/build.gradle.kts` 中 `optimization.keepRules.ignoreFrom("top.canyie.pine:core")`：Pine 自带的 consumer 规则含 `-keep class * { native <methods>; }`，会保留整个 App 的全部类（APK 约 +9MB），已忽略并在 `proguard-rules.pro` 中按原意补写。
  - 体积：Release ARM64 约 20.0MB（加入前 18.2MB）。
- **限制**：Hook 依赖 ART 内部实现，不同 Android 版本 / ROM 可能不兼容（失败时仅 `rikka.hook` 报错）；被 ART 内联进调用方的方法可能不触发 Hook（可对调用方 `rikka.hook.deoptimize`）；宿主从未使用的代码在正式版中不存在。
- **验证**：`:plugin` 单元测试 `PluginJavaBridgeTest`（JVM，覆盖反射 / 重载 / 字段 / 代理 / `$async` 回调 / 同线程回调 / 句柄清理 / 未声明权限被拒 / 不能改写自身 manifest）；真机自测插件 `test-plugins/native-selftest`（34 项，Android 16 实测全部通过）。

---

## 三、模块代码结构

```text
:plugin（独立 Gradle 模块，依赖 :ai、:common）
  me.rerere.plugin
    manifest/   PluginManifest（清单解析与权限定义）
    runtime/    PluginRuntime（QuickJS 实例 + 常驻事件循环 + 单线程调度器 + 超时与内存限制）
                JsBridge（rikka 全局对象注入、JSON 交换、Promise/挂起函数桥接）
                PluginJavaBridge（rikka.java 反射桥：句柄表、类型转换、重载选择、接口代理、Hook 回调分派）
                PluginHookEngine（rikka.hook：Pine 封装，首次使用时初始化）
                BootstrapJava（rikka.java / rikka.hook / rikka.host.* 的 JS 侧实现）
    registry/   PluginRegistry（工具 / Hook / 流处理器 / 拦截器 / 宏 / UI 挂载项注册表）
    host/       PluginHost（安装、启停、优先级、单项能力开关、按 Hook 取执行链）
    error/      PluginException（统一错误前缀包装與 IOException 透传）
    store/      PluginStore & PluginKvStorage（filesDir/plugins 与单例 KV 存储）

:app（宿主适配与 UI 层）
  app/.../data/plugin/
    PluginBridge、AppPluginHostApi（含 rikka.host.* 宿主对象）、PluginHostObjects（PluginCurrentActivity）、
    PluginCapabilitySync、PluginConversationApi、
    PluginSettingsApi、PluginWorkspaceApi、PluginProviderDelegates、PluginScheduleManager
  app/.../ui/pages/extensions/plugin/
    PluginsPage、PluginDetailPage、PluginPanelPage、PluginSlotMounts
```
