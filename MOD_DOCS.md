# Rikkanon 功能与架构说明文档（基于 RikkaHub 二开）

> **当前版本**：`v1.3.0`（更新未经充分测试，仅开发者自用，**可能存在大量测试不到的 Bug，建议慎重更新**）  
> **应用包名**：`me.rerere.rikkahub.rikkanon`（与原版 `me.rerere.rikkahub` 独立共存，互不覆盖）

本文档介绍 **Rikkanon** 相对上游的主要二开模块设计与版本记录，包括 **界面自定义（主题美化）底座**、**高自由度 JS 插件系统**、**高级提示词模式与世界书**、**多代理分模块出站与沙盒透明出网**、**工作区持久化多终端**、**子代理（Sub-Agent）协作与主从跟随 UI**，以及 **生成控制、消息编辑器、密钥代名词脱敏与渠道继承**。

| 专项文档 | 内容定位 |
|---|---|
| [**`CUSTOM_UI_DOCS.md`**](CUSTOM_UI_DOCS.md) | 界面自定义（`.ui.js`）语法说明、R8 反射注意事项与宿主字段/控件速查字典 |
| [**`PLUGIN_API_DOC.md`**](PLUGIN_API_DOC.md) | JS 插件开发接口文档（工具、Hook、流处理、服务同步、UI 挂载、`rikka.java`/`host`/`hook`） |
| [**`PLUGIN_DESIGN.md`**](PLUGIN_DESIGN.md) | 插件系统运行时、权限模型与原生桥架构说明 |
| [**`ADVANCED_PROMPT_DOCS.md`**](ADVANCED_PROMPT_DOCS.md) | 高级提示词组装、JSON 显式缓存编排、世界书引擎与深度正则说明 |

> [!WARNING]
> ### ⚠️ 稳定性与插件安全声明（必读）
> 1. **版本未充分测试与主题美化模块局限**：
>    - 本次更新改动较多且**未经充分测试，仅开发者个人自用，很多非自用路径下的 Bug 可能测试不到；目前可能存在大量问题，日常求稳请慎重更新**，使用前务必备份重要数据。
>    - 新增的「界面自定义（主题美化）」功能**目前不太稳定、也不太好用**（默认关闭）。出于个人偏好，宿主仅添加了偏底层的渲染、插槽与反射底座，未做傻瓜式模板封装；若想彻底改造界面外观，需要手写代码覆盖大量插槽与二级页面，并不灵活省事，仅在自由度上相对宽松一些。
> 2. **插件系统安全声明**：
>    - **请务必了解所安装插件的全部源代码及具体作用**：请仅导入和启用你自己编写、或已完整审阅全部源码并明确知晓其行为逻辑的插件。
>    - **App 层面无任何针对插件的额外限制**：出于个人需求，Rikkanon 在应用层面对插件完全开放底层控制权。插件声明对应权限后即可读写对话、修改全局设置与预设、拦截改写 HTTP 请求与流式响应、发起网络请求以及操控工作区终端与子代理；声明 `native` / `hook` 权限后还可通过 `rikka.java` / `rikka.host` / `rikka.hook` 调用宿主与系统任意代码、Hook 任意方法，请使用者自行对所加载的插件代码负责。

---

## 一、 核心功能模块

### 模块 1：界面自定义（UI 代码）底座（实验性，`ENGINE_VERSION = 14`）

> 详细接口与宿主字段字典请参阅 [**`CUSTOM_UI_DOCS.md`**](CUSTOM_UI_DOCS.md)。

**设计取向与局限**：
通过内置独立 QuickJS 运行 `.ui.js` 脚本拼装 Compose 节点。出于个人习惯，**宿主只负责提供底层底座能力，不内置高封装度的换肤开关**——关闭总开关时保持原版逻辑不变，开启后未覆盖的部分仍走原版；但这也意味着**如果想彻底改变客户端整体观感，必须自行编写较多代码逐一接管各处插槽乃至重写二级页面，操作繁琐、不够灵活方便，且复杂自定义下仍可能遇到兼容性或稳定性问题**（启动异常时可通过 `SafeModeActivity` 安全模式关闭）。

#### 主要底座能力：
1. **全局外观与共用组件样式（`ui.style`）**：
   - 支持 `ui.theme`（种子色与 48 个颜色槽位）、`color.*`（Oklab 颜色运算）、`ui.font`（自定义字体与 30 级文字样式）、`ui.shapes`（圆角与 `cut` 斜切角）、`ui.motion`、`ui.typography`（聊天正文排版与引号/斜体染色）、`ui.transition`、`ui.page`（单页背景/前景/配色/整页替换）及 `ui.overlay`（顶层前景层）；
   - `ui.style` 支持调整顶栏、返回键、设置卡片组（含 `spacing` 分段）、底部弹窗（`bottom_sheet`）、分页切换条（`pager_tab`）、连接式按钮组（`connected_button`）、开关（`switch`）、附件方块（`attachment_tile`）、思考框、代码块等共用组件属性。
2. **插槽替换与聊天页骨架重排**：
   - `ui.slot` 支持静态节点树与动态渲染函数 `(data, ctx) => Node | null`，覆盖聊天顶栏、输入栏、`+` 扩展面板（`chat.more`）、抽屉及消息部件（含前置思考 `reasoning_block`、正文与中途工具穿插 `markdown_body`、全量时间序部件 `message_parts`）；
   - `chat.layout` 允许调整聊天页顶栏、消息列表、输入栏与抽屉的位置关系。
3. **动态效果、手势与页面重写底座**：
   - 支持基础关键帧动画 `animate`、按压 `press`、震动 `haptic`、滑动 `swipe`+`onSwipe`、缩放 `zoom`、AGSL 着色器 `Shader` 与 `Video` 节点（针对切角与不对称圆角容器补充了 `uiSafeClip` 绘制裁剪，避免超长消息卡片触发路径交集精度问题导致底部按钮失灵）；
   - 提供懒加载列表 `LazyColumn` / `LazyRow`、反射调用宿主界面函数的 `Compose(...)` 与原生视图 `NativeView(...)`，配合 `host.get`、`ctx.watch` 与基于 `-java-parameters` 的 `java.copy(obj, patch)` 读写宿主数据；编辑页支持通过内置 `acorn` 反向修改源码中的简单字面量。

---

### 模块 2：高自由度 JS 插件系统（`:plugin`）

> 详细开发接口与设计说明请参阅 [**`PLUGIN_API_DOC.md`**](PLUGIN_API_DOC.md) 与 [**`PLUGIN_DESIGN.md`**](PLUGIN_DESIGN.md)。

1. **运行时与权限机制**：每个已启用插件运行在独立常驻 QuickJS（ES2020 ES Module）实例中；采用 `manifest.json` 显式权限校验、详情页单项能力开关、热重载以及显式报错机制（禁止插件经 `rikka.files` 修改自身 `manifest.json`，并将纯 UI 挂载刷新与后台服务同步分离）。
2. **工具注册与挂起提问**：`rikka.registerTool` 支持动态 `parameters(ctx)` / `systemPrompt(ctx)`、`display` 多媒体或正文气泡渲染、`endTurn`、`ctx.progress` 进度更新，以及执行中途提问 `await ctx.askUser`（等待用户回答期间暂停调用超时计时）。
3. **分层请求/响应改写与网络接口**：提供 `chat.history`、`preset.beforeAssemble`、`lorebook.activated`、`prompt.assembled`、`messages.beforeSend`、`params.beforeSend`、`onStream` 与 `intercept` 原始 HTTP/SSE 拦截（支持单次请求指定 `proxy`）；支持流式/二进制 `rikka.fetch` 与 `rikka.ws` WebSocket。
4. **资源与底层服务流式同步**：支持 `registerSkill` / `registerLorebook` / `registerQuickMessage` 及 `registerProvider` / `registerTTS` / `registerASR` / `registerSearch` / `registerMediaGen` 自动同步至可用列表，支持 `async function*` 流式输出与实时 PCM 语音流。
5. **UI 挂载与原生层接口**：支持宿主多处挂载点、双向绑定 `form` 表单、`rikka.ui.update` 局部更新与自适应高度主题色 WebView 面板；声明 `native` / `hook` 权限后可按需使用 `rikka.java`、`rikka.host` 与 `rikka.hook`（基于 Pine，受 Android 版本/ROM 兼容性限制）。

---

### 模块 3：高级提示词模式（预设组装、JSON 显式缓存、世界书与深度正则）

> 详细说明请参阅 [**`ADVANCED_PROMPT_DOCS.md`**](ADVANCED_PROMPT_DOCS.md)。

1. **平权预设轨道与双保存防误触**：提供模块总开关、预设独立开关、双保存防误触与上次打开预设记忆；内置 6 个 `📌` 模块（`World Info (before)`、`Char Description`、`记忆`、`工作区提示词`、`World Info (after)`、`Chat History`），对齐酒馆 `相对` / `聊天中 @D` / `附着到已有消息 @D` 位置，支持导入酒馆预设 JSON 及单块「按预设发送工具调用」开关。
2. **常规三段式思维链回传 ⇄ 原生 JSON 模式**：常规模式支持 `系统 / 用户 / AI / ？` 角色与正文/思维链/签名编辑；JSON 模式双向互通并原样并入底层 API 消息数组（可用于插入 `cache_control` 等自定义结构），同时隔离预设合成块与真实对话历史的合并及缓存断点统计边界。
3. **世界书引擎与深度正则**：支持常驻蓝灯、条件绿灯（正则、4 种次要逻辑、递归控制）、三档绿灯缓存策略（默认 `STICKY_LOCKED_POSITION` 固定首次 `@D` 注入位置）、6 种注入位置（含 `{{outlet::名称}}` 锚点）与链式哈希增量扫描；正则支持发出/显示/正文三阶段、`用户 / 助手 / 世界书` 范围与最小/最大深度过滤。

---

### 模块 4：多代理节点管理、分模块出站与本地沙盒透明出网

1. **代理管理与测速**：在「网络 -> 代理」中支持保存多个 `HTTP` / `HTTPS` / `SOCKS5` 代理节点，支持粘贴 URI 或四段式 `host:port:user:pass` 解析及连接延迟测试。
2. **分模块独立出站**：支持为 **LLM 本体与常规请求**、**工作区（本地沙盒）**、**MCP 服务** 分别指定不同代理或直连，同步覆盖 `McpManager`、`TTSManager`、WebDAV/S3 备份与 Rootfs 下载。
3. **本地沙盒 libc 层透明出网**：不向沙盒注入 `HTTP_PROXY` / `ALL_PROXY` 环境变量，通过预加载库在 libc 层接管域名解析与外网 TCP 连接，经宿主回环桥执行远端 DoH 解析（消除本机 TUN Fake-IP 干扰）并转发至工作区代理，支持已开终端热切换。

---

### 模块 5：工作区持久化交互式多终端（`workspace_terminal`）与沙箱兼容补丁

1. **共享持久化终端池**：支持本地沙盒与远程 SSH 工作区，主对话、子代理与终端 UI 共用同一终端池；支持多命名终端、`#id` 操控、`exit`/`Ctrl+D` 自动关页及 `kill`/`kill_all`。
2. **进程树状态检测与双视图输出**：检测顶层交互式 Shell 与前台活跃子进程，命令回到提示符后及时返回增量输出（`incrementalOutput`）与 40 行屏幕快照（`screenSnapshot`）。
3. **工具「启用/审批」分离与沙箱兼容补丁**：5 项工作区工具支持分别控制启用与审批；本地沙盒内置共享内存挂载、不可读系统状态节点兜底、Node.js 网卡枚举兼容、`apt`/`dpkg` 防锁死、轻量服务脚本及常用环境变量补丁。

---

### 模块 6：子代理（Sub-Agent）多助手协作与主从跟随 UI

1. **多助手绑定与职责注入**：支持勾选绑定多个助手作为子代理（各自独立模型、提示词、思考强度、MCP 与 Skills），助手多行标签直接作为职责说明注入派发工具定义。
2. **独立工具开关与双轨审批**：支持配置子代理单轮步数上限与可用工具，并对每个已开启工具分别提供 **`[用户]`** 与 **`[主代理]`**（`conversation_approve`）审批开关。
3. **分级控制、分支克隆隔离与主从 UI**：支持 0~4 级控制权限、并发限制池、全中断态通知唤醒、`sub_1` 局部编号路由及主对话分支（Fork）深拷贝隔离；聊天页顶栏提供终端与子代理紧凑胶囊及实时窥屏小窗，左侧抽屉以树状缩进展示关联子代理。

---

### 模块 7：生成链路控制、消息编辑器、密钥脱敏与渠道继承

1. **多工具并发与免暂停插话（Steer）**：同轮多工具并发执行；连续工具调用期间可直接发送消息插话并在下一轮并入上下文。
2. **单步增量落库与异常中断收尾**：工具步骤边界通过 `checkpointConversation` 单事务增量更新尾部节点；中断或冷启动恢复时仅对末尾未完成工具补写状态或剥离残缺碎片（避免整条已完成前置步骤的消息被连带删除），并对超 2MB 消息节点提供 256K 分片读取兜底。
3. **双层 Roll 与双模式消息编辑器**：支持单工具参数/结果修改重跑与从此步 Roll；整条助手消息提供可视化部件模式与原始 JSON 源码模式编辑。
4. **密钥代名词出入站阀门与防拆字节脱敏（`SecretKeySanitizer`）**：外部与工作区执行工具展开真实密钥，内部子代理通信（`conversation_*`）与记忆工具（`memory_*`）不展开入参；出站逐字段解包 JSON 脱敏，还原常见编码回显并拦截 `od`、`xxd`、`hexdump`、`base64`、字符拼接与高熵切片等拆字节输出（附格式诊断），配合 `ask_key` 工具支持聊天内点选或新建代名词。
5. **提供商重写父子继承、Markdown 盒模型与模型批量删除**：模型的 `providerOverwrite` 留空字段（`名称`、`API Key`、`API Base Url`、API 路径、Vertex 服务账号等）实时回退读取父渠道配置（跨协议自动适配 `/v1` ⇄ `/v1beta` 后缀），并同步继承父渠道的 `customHeaders` 与 `balanceOption`；消息 Markdown 中带 `padding` / `border` / `border-radius` / `background` 的 `<div style>` 支持渲染为整块圆角边框容器；渠道模型页长按右侧 `⋮` 进入多选批量删除；会话配置实时跟随所属助手设定。

---

## 二、 构建说明

- **环境要求**：OpenJDK 21、Android Compile SDK 37
- **构建命令**：
  ```bash
  ./gradlew assembleDebug    # 构建 Debug 包
  ./gradlew assembleRelease  # 构建 Release 包
  ./gradlew test             # 运行 JVM 单元测试
  ```

---

## 三、 版本更新记录 (Changelog)

### v1.3.0（实验性界面自定义底座、插件原生反射/Hook 与真流式扩展、密钥防拆字节脱敏、单步增量落库、子渠道继承与 Markdown 盒样式）
> **提示**：本次更新**未经充分测试，仅开发者个人自用，可能有较多 Bug 测试不到，建议慎重更新**；新增的界面自定义模块仅提供底层底座能力，深度改界面需覆盖大量页面与组件，目前不太稳定也不太好用（默认关闭）。
1. **新增实验性「界面自定义（UI 代码）」模块（`ENGINE_VERSION = 14`）**：提供全局配色/字体/切角形状、`ui.style` 共用组件样式覆写、`ui.slot` 插槽与 `chat.layout` 骨架调整（含助手消息前置思考与正文中途工具时间序拆分）、`Shader`/`animate`/`Video` 节点、切角长卡片 `uiSafeClip` 裁剪修复、懒加载列表 `LazyColumn`、`Compose`/`NativeView` 控件反射借用、`java.copy` 数据桥与 `SafeModeActivity` 安全模式兜底。
2. **JS 插件系统能力扩充与宿主修复**：
   - 新增需 `native`/`hook` 权限的 `rikka.java`（含 `java.copy`、隐藏 API 与 `$async`/`$main`）/ `rikka.host` / `rikka.hook`、插件热重载与 `rikka.onUnload`；
   - Provider 支持 `async function*` 真流式输出，新增 `getBalance`、`generateEmbedding`、`generateImage`、`editImage` 与完整模型能力声明；TTS 支持流式分片；ASR 新增实时 PCM 流式识别（`startStream` / `pushAudio` / `stopStream`）；
   - 工具新增动态 `parameters`/`systemPrompt`、多媒体 `display` 与 `await ctx.askUser` 挂起提问；`rikka.fetch` 与 `intercept` 支持单次请求动态 `proxy`、`rikka.fetch` 支持流式与 Base64 二进制；新增 `rikka.ws`、`rikka.attachments.save`、`rikka.files`/`workspace` 二进制读写、声明式资源注册（Skill/世界书/快捷消息），插件宏支持在预设与世界书组装阶段（含 JSON 块内）展开；
   - 新增 `chat.more`、`message.footer`、`tool.card`、`conversation.menu` 挂载点、顶底栏 `menu` 下拉菜单与 `rikka.ui.update`；`form` 新增 `password`/`slider`/`segmented`/`model` 与实时变更回调；WebView 默认透明、注入宿主主题 CSS 变量、DOM 高度自适应，悬浮窗支持折叠为胶囊；
   - 修复 `ctx.progress` / `ctx.askUser` 跨协程 `emit` 抛异常、等待用户回答计入 30s 超时、UI 挂载变化触发多余后台同步的问题，并禁止经 `rikka.files` 修改自身 `manifest.json`。
3. **密钥代名词脱敏加固（`SecretKeySanitizer`）**：保留外部与工作区执行工具展开，排除内部子代理/记忆/提问工具入参展开；出站逐字段解包 JSON（覆盖 `stdout`、`stderr`、插件 `onProgress` 与编辑 Diff），还原常规回显并拦截 `od`/`xxd`/`hexdump`/任意偏移三相位 `base64`/字符拼接/高熵切片等拆字节探测（附零泄密格式诊断）。
4. **提供商重写（`providerOverwrite`）完整继承父渠道**：留空的 `名称`、`API Key`、`API Base Url`（跨协议自动适配 `/v1` ⇄ `/v1beta`）、API 路径及 Vertex 服务账号字段实时回退读取父渠道，同步继承父渠道 `customHeaders` 与 `balanceOption`；关闭重写启用开关时直接回退父渠道。
5. **连续工具单步增量落库与防连坐删消息**：新增步骤边界 `checkpointConversation` 增量写入；修复异常中断或未完结工具导致整条助手消息被连坐删除的问题（`finalizeInterruptedParts` / 冷启动保留 `Pending` 待审批工具）；补充落库前残留 `data:` Base64 图片（含工具输出内图片）转存兜底及超 2MB `CursorWindow` 节点 256K 分片流式读取。
6. **Markdown `<div style="...">` 盒模型样式支持**：支持将含 `padding` / `border` / `border-radius` / `background` 的 `<div style>` 渲染为整块圆角边框背景容器。

### v1.2.5（JS 插件系统基础架构、扩展面板交互调整、多代理分模块出站与沙盒透明出网）
1. **新增 JS 插件系统（`:plugin`）**：支持 `.zip` 导入、QuickJS ES2020 运行时、权限校验与单项开关、分层请求/响应 Hook、服务自动同步与表单/WebView 面板。
2. **扩展面板交互与预设记忆**：预设/世界书/Skills/快捷消息改为「点击主体进编辑页 + 右侧独立开关」分离；提示词预设页自动停留在上次打开项。
3. **多代理管理与本地沙盒透明出网**：支持多 `HTTP`/`HTTPS`/`SOCKS5` 节点测速与 LLM/工作区/MCP 独立出站；本地沙盒通过 libc 层预加载实现无环境变量痕迹的远端 DoH 解析与 TCP 转发。
4. **Gemini 流式工具参数支持**：`GoogleStreamDecoder` 解析 `streamFunctionCallArguments`（`partialArgs`）。

### v1.2.0（高级提示词模式、JSON 显式缓存编排、世界书引擎与深度正则）
1. **高级提示词轨道架构**：细化 6 个 `📌` 内置模块，对齐酒馆 3 种注入位置并支持导入酒馆预设 JSON，提供双保存防误触与「按预设发送工具调用」开关。
2. **常规思维链回传 ⇄ 原生 JSON 模式**：支持回传正文/思维链/签名，JSON 模式块作为独立数组元素并入请求体（支持 `cache_control` 等自定义字段并隔离历史合并边界）。
3. **世界书与深度正则**：支持蓝绿灯、递归、三档绿灯缓存策略（`STICKY_LOCKED_POSITION`）、`{{outlet::}}` 锚点与链式哈希增量扫描；正则支持三阶段控制、`世界书` 范围与最小/最大深度过滤；移除生成时强制重测量自动滚动以修复首条消息 ANR。

### v1.1.0 及更早版本汇总
1. **同步上游 RikkaHub (`3.1.0` / `3.1.1`)**：SSH 远程工作区与 `:terminal` 升级、后台生成悬浮窗、APK 体积缩减、PRoot 升级、工具耗时显示、`models.dev` 目录与独立记忆整理模型。
2. **早期定制能力**：子代理多助手绑定、长标签职责注入、`sub_N` 路由与 Fork 克隆隔离、5 级权限与双轨审批、顶栏窥屏小窗与抽屉树状跟随；工作区持久化多终端与沙箱兼容补丁；多工具并发、Steer 插话、双层 Roll、双模式消息编辑器、密钥代名词与 `ask_key`、模型多选批量删除及列表上拉回跳修复。

---

## 四、 致谢与开源协议

- **感谢 [re-ovo/rikkahub](https://github.com/re-ovo/rikkahub)**：本项目基于 RikkaHub 二次开发，感谢原作者 [re-ovo](https://github.com/re-ovo) 及上游贡献者的开源工作。
- **感谢 [SillyTavern（酒馆）](https://github.com/SillyTavern/SillyTavern)**：提示词预设编排、`@D` 深度注入、世界书与正则深度过滤等设计理念源自酒馆。
- **开源协议**：本项目遵循与上游一致的 [**GNU Affero General Public License v3.0 (AGPL-3.0)**](LICENSE) 开源协议。
