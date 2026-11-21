<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Rikkanon Icon" width="110" />
  <h1>Rikkanon</h1>
  <p><b>基于 <a href="https://github.com/re-ovo/rikkahub">RikkaHub</a> 二次开发的个人向 Android AI Agent 客户端</b></p>
  <p>
    <a href="https://github.com/xiusu318-bot/Rikkanon/releases/latest"><img src="https://img.shields.io/github/v/release/xiusu318-bot/Rikkanon?label=Release&color=ff7eb6" alt="Release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-blue.svg" alt="License: AGPL-3.0" /></a>
    <a href="MOD_DOCS.md"><img src="https://img.shields.io/badge/Docs-MOD__DOCS.md-success" alt="MOD_DOCS" /></a>
    <a href="CUSTOM_UI_DOCS.md"><img src="https://img.shields.io/badge/UI-CUSTOM__UI__DOCS.md-00b4d8" alt="CUSTOM_UI_DOCS" /></a>
    <a href="ADVANCED_PROMPT_DOCS.md"><img src="https://img.shields.io/badge/Prompt-ADVANCED__PROMPT__DOCS.md-orange" alt="ADVANCED_PROMPT_DOCS" /></a>
    <a href="PLUGIN_API_DOC.md"><img src="https://img.shields.io/badge/Plugin-PLUGIN__API__DOC.md-purple" alt="PLUGIN_API_DOC" /></a>
  </p>
</div>

---

> [!WARNING]
> ### ⚠️ 更新预警与项目声明（使用前必读）
> 1. **🚨 `v1.3.0` 慎重更新提示（未经充分测试，可能存在大量 Bug）**：
>    - 本次更新改动面较大，**未经充分测试，仅开发者个人日常自用，很多场景和边界下的 Bug 可能根本测试不到；目前可能存在大量未知问题，追求日常稳定使用的用户请慎重更新**（建议继续使用旧版；如要尝试更新，请务必提前备份聊天记录与配置）。
>    - **关于新增「主题美化（界面自定义）」功能的说明**：这个模块**目前不太稳定，也不太好用**（默认关闭）。因为个人爱好原因，宿主侧**只做了偏底层的底座能力**，没有提供省事的一键换皮模板或现成封装；如果只是简单改色还好，**想把界面彻底改成另一种风格基本需要手写代码覆盖很多插槽和页面，并不灵活方便**，唯一相对好一点的地方大概是限制少、相对自由一些。
> 2. **🚨 插件系统无限制与源码自审声明（重要）**：
>    - **请务必了解所安装插件的全部源代码及具体作用**：在导入和启用任何插件前，请确保它是你自己编写的代码，或你已完整审阅并明确知晓其全部源码与行为逻辑。
>    - **App 层面没有任何针对插件的限制**：出于个人需求（不喜欢繁琐的安全限制与沙盒阉割），**Rikkanon 在应用层面对插件完全开放底层控制能力**。插件在声明权限后即可读写对话、修改全局设置与提示词预设、拦截改写底层 HTTP 请求与 SSE 响应流、发起网络请求以及操控本地工作区终端与子代理；声明 `native` / `hook` 权限后还可通过 `rikka.java` / `rikka.host` / `rikka.hook` 调用宿主与系统的任意代码、Hook 任意方法，请使用者自行对所加载的插件代码负责。
> 3. **个人自用向二次开发 & 经典交互保留**：
>    - 本项目基于上游开源项目 [RikkaHub](https://github.com/re-ovo/rikkahub) 进行个人向改造，与上游官方无关；提示词预设编排、`@D` 深度注入、世界书与正则深度过滤等设计理念源自 [SillyTavern（酒馆）](https://github.com/SillyTavern/SillyTavern)，特此感谢原作者与社区。
>    - 因个人习惯保留了较多 2.5.6 版本的紧凑卡片与弹窗交互（如一体式卡片、提供商与 MCP 弹窗配置、聊天页单层 `+` 面板、会话配置实时跟随助手设定等）。若更喜欢现代化的 Material 3 Expressive 设计与稳定完整的官方体验，推荐前往下载 [**上游官方原版 RikkaHub**](https://github.com/re-ovo/rikkahub)。
>    - 支持与原版 RikkaHub 共存安装（独立包名 `me.rerere.rikkahub.rikkanon`）。记忆库模块中新增的「后台自动整理记忆」等开关平时较少自用，可能存在细节问题。

---

## 📚 文档导航

| 文档 | 说明 |
|---|---|
| [**`MOD_DOCS.md`**](MOD_DOCS.md) | 二开功能模块说明与版本更新记录 |
| [**`CUSTOM_UI_DOCS.md`**](CUSTOM_UI_DOCS.md) | 主题美化（`.ui.js`）接口说明与宿主字段速查字典 |
| [**`ADVANCED_PROMPT_DOCS.md`**](ADVANCED_PROMPT_DOCS.md) | 高级提示词组装、JSON 显式缓存编排、世界书与深度正则说明 |
| [**`PLUGIN_API_DOC.md`**](PLUGIN_API_DOC.md) | JS 插件开发与注册接口文档（含 `rikka.java` / `rikka.host` / `rikka.hook`） |
| [**`PLUGIN_DESIGN.md`**](PLUGIN_DESIGN.md) | JS 插件系统运行时与架构设计说明 |

---

## 📌 v1.3.0 更新内容（未经充分测试，请慎重更新）

> **再次提醒**：本次更新**未经充分测试，仅开发者个人自用，很多没用到的路径可能存在测试不到的 Bug，整体可能存在大量问题，请慎重更新**，升级前请做好数据备份。

1. **新增实验性「主题美化（界面自定义）」底座（[`CUSTOM_UI_DOCS.md`](CUSTOM_UI_DOCS.md)）**：
   - 入口位于「设置 -> 偏好设置 -> 界面自定义」，总开关默认关闭（关闭时走原版逻辑，异常启动时可通过安全模式关闭）。
   - **优缺点说明**：宿主只暴露了底层配色/字体/形状（含 `cut` 切角及超长卡片触摸修复）、共用组件样式覆写（`ui.style`）、插槽替换与聊天页骨架调整（`ui.slot` / `chat.layout`，含助手消息前置思考与正文中途工具按时间序拆分）、着色器/动画/视频节点（`Shader` / `animate` / `Video`），以及懒加载列表与宿主反射桥（`LazyColumn` / `Compose` / `NativeView` / `java.copy`）等底座能力。**目前不太稳定，也不太好用**——想彻底改掉界面外观需要手写代码覆盖很多组件和二级页面，并不灵活方便，只是相对自由一点。
2. **JS 插件系统（`:plugin`）能力扩充与宿主修复（[`PLUGIN_API_DOC.md`](PLUGIN_API_DOC.md)）**：
   - **原生全能力**：新增需显式声明 `native` / `hook` 权限的 `rikka.java`（反射调用任意 Java/Kotlin 代码、私有成员与隐藏 API、`java.copy` 数据类浅拷贝、接口代理与 `$async`/`$main` 线程切换）、`rikka.host`（取宿主内部服务）与 `rikka.hook`（基于 Pine 拦截/改写/替换方法），支持插件热重载与 `rikka.onUnload`；
   - **底层服务真流式与能力补齐**：Provider 支持 `async function*` 真流式输出，新增查余额 `getBalance`、向量 `generateEmbedding`、生图 `generateImage`、改图 `editImage` 及完整模型能力声明；TTS 支持流式音频分片播放；ASR 新增实时 PCM 流式语音识别（`startStream` / `pushAudio` / `stopStream`）；
   - **工具、网络与二进制文件**：`registerTool` 支持动态 `parameters`/`systemPrompt`、多媒体 `display` 及中途挂起提问 `await ctx.askUser`；`rikka.fetch` 与 `intercept` 支持单次请求动态指定 `proxy`、`rikka.fetch` 支持 `stream: true` 流式与 `base64` 二进制收发；新增 `rikka.ws` WebSocket、`rikka.attachments.save` 附件存储桥，以及 `rikka.files` / `rikka.workspace` 的 `readBase64` / `writeBase64` 二进制读写；插件宏支持在高级提示词预设与世界书组装阶段展开（含 JSON 块内递归展开）；
   - **UI 挂载与 WebView 增强**：新增 `chat.more`（`+` 面板）、`message.footer`（消息底栏）、`tool.card`（自定义工具卡片图标/标题/摘要/详情）、`conversation.menu`（会话长按菜单）、顶底栏 `menu` 下拉菜单及 `rikka.ui.update` 动态局部刷新；`form` 新增 `password` / `slider` / `segmented` / `model` 控件与实时变更回调；WebView 默认透明、自动注入宿主主题色 CSS 变量、移除高度上限支持 DOM 自适应伸缩，`floating` 悬浮窗支持最小化折叠为胶囊；
   - **宿主问题修复**：修复插件工具 `ctx.progress` / `ctx.askUser` 跨协程更新消息抛异常中断生成的问题；修复等待用户回答计入 30 秒调用超时的问题；修复 UI 挂载变化触发多余后台服务/WorkManager 同步的问题；禁止插件经 `rikka.files` 修改自身 `manifest.json`。
3. **密钥代名词出入站阀门与防拆字节脱敏（`SecretKeySanitizer`）**：
   - 保留外部与工作区执行工具的真实密钥展开与配置落盘，排除内部子代理通信（`conversation_*`）、记忆库（`memory_*`）与提问工具（`ask_*`）入参展开；
   - 工具返回前逐字段解包 JSON 脱敏（覆盖 `stdout`、`stderr`、插件 `onProgress` 与 `workspace_edit_file` 编辑 Diff），还原常规编码回显并拦截 `od`、`xxd`、`hexdump`、任意偏移三相位 `base64`、字符拼接与高熵切片等拆字节探测（附零泄密格式诊断）。
4. **提供商重写（`providerOverwrite` 子渠道）完整继承父渠道**：
   - 模型「提供商重写」留空的 `名称`、`API Key`、`API Base Url` 实时回退继承父渠道当前值（跨 `OpenAI / Google / Claude` 协议切换自动适配 `/v1` ⇄ `/v1beta` 后缀），并同步继承父渠道的 `customHeaders`（自定义请求头）、`balanceOption`（余额配置）、API 路径及 Vertex 服务账号字段；关闭重写启用开关时直接回退父渠道。
5. **连续工具单步增量落库、防连坐删消息与超长节点分片读取**：
   - 多步工具启动与完成边界单事务增量更新尾部节点（`checkpointConversation`）；
   - 修复网络断流或报错留下未执行完工具时直接把整条助手消息（连同前面已完成的所有工具步骤）整条删掉的问题，支持冷启动恢复（保留 `Pending` 待审批工具），在落库前自动兜底将残留 `data:` Base64 图片（含工具输出内图片）转存为本地文件防崩溃，并补充超 2MB `CursorWindow` 消息节点的 256K 分片流式读取。
6. **Markdown `<div style="...">` 盒模型样式渲染**：
   - 消息 Markdown 中写了 `padding` / `border` / `border-radius` / `background` 的 `<div style>` 支持渲染为整块圆角边框背景盒，不再只是逐字涂底色。

---

## 🚀 安装包下载

前往 [**GitHub Releases 页面**](https://github.com/xiusu318-bot/Rikkanon/releases/latest) 获取安装包（独立包名，可与原版 RikkaHub 共存；**更新前请先备份数据**）：

- 📱 **ARM64 版本（手机主力包）**：[`Rikkanon_v1.3.0_Release_arm64-v8a.apk`](https://github.com/xiusu318-bot/Rikkanon/releases/download/v1.3.0/Rikkanon_v1.3.0_Release_arm64-v8a.apk)

---

## ✨ 二开功能概览

> 详细说明请参阅 👉 [**`MOD_DOCS.md`**](MOD_DOCS.md)。

1. **🎨 主题美化 / 界面自定义（实验性底座）**：默认关闭；通过内置 QuickJS 脚本覆盖配色、字体、切角形状、组件样式、聊天骨架插槽或重写二级页面。仅提供底层底座能力，深度定制需覆盖大量界面，不够便捷且可能存在较多问题。
2. **🔌 JS 插件系统（`:plugin`）**：基于 QuickJS（ES2020 ES Module），支持自定义工具（含流式气泡渲染与 `ctx.askUser`）、分层请求/SSE 拦截改写、底层服务流式同步、配置表单与 WebView 面板，以及 `rikka.java` / `rikka.host` / `rikka.hook` 原生层访问（使用前请务必自审插件源码）。
3. **🧩 高级提示词模式、世界书与深度正则**：提供 6 个平权内置模块与对齐酒馆标准的 `@D` 深度注入、预设导入和双保存防误触；支持常规三段式思维链/签名回传与原生 JSON 数组元素直通（如自定义 `cache_control` 缓存断点）；内置蓝绿灯递归世界书与三阶段深度过滤正则。
4. **🌐 多代理分模块出站 & 本地沙盒透明出网**：支持多 `HTTP`/`HTTPS`/`SOCKS5` 代理节点管理与测速，可为 LLM、工作区沙盒、MCP 分别指定代理；本地沙盒通过 libc 层预加载实现无代理环境变量痕迹的远端 DoH 解析与 TCP 转发。
5. **🖥️ 工作区持久化交互式多终端（`workspace_terminal`）**：跨主对话、子代理与终端界面共享伪终端池（支持本地沙盒与远程 SSH），提供进程树状态检测、增量输出 + 屏幕快照、工作区工具「启用/审批」分离及沙箱常见兼容补丁。
6. **🤖 子代理（Sub-Agent）多助手绑定与主从跟随 UI**：支持绑定多个助手作为子代理、标签职责说明注入、`sub_N` 局部路由与主对话分支（Fork）克隆隔离、5 级控制权限、逐工具「用户/主代理」双轨审批、中断唤醒，以及顶栏窥屏小窗与抽屉树状缩进。
7. **⚡ 生成控制与消息编辑扩展**：支持同轮多工具并发、连续工具期间免暂停插话（Steer）、单步增量落库、单工具参数/结果编辑重跑与双层 Roll、整条消息可视化/JSON 双模式编辑器、密钥代名词脱敏与 `ask_key` 工具、提供商重写继承及模型多选批量删除。

---

## 🔄 同步上游 RikkaHub (`3.1.0` / `3.1.1`) 更新说明

本项目在保留个人交互习惯的基础上，同步合并了上游 [re-ovo/rikkahub](https://github.com/re-ovo/rikkahub) 的多项更新（感谢上游原作者）：
- **SSH 远程工作区 & `:terminal` 模块升级**：已与持久化多终端和顶栏窥屏小窗适配；
- **后台生成悬浮窗**：接入中途插话并过滤后台子代理会话；
- **APK 体积缩减与 PRoot 升级**：纯 Kotlin PDF 提取、CameraX + zxing 扫码、Compose 图片裁剪、`libsimple.so` 全文搜索与 Release R8 压缩；
- **其他功能**：工具调用耗时显示、内置 `models.dev` 模型目录、独立记忆整理模型、聊天 Markdown 工作区图片渲染与图片涂鸦画板等。

---

## 🛠️ 构建说明

```bash
./gradlew assembleDebug    # 构建 Debug 包
./gradlew assembleRelease  # 构建 Release 包
```

---

## 🙏 致谢与开源协议

- **感谢 [re-ovo/rikkahub](https://github.com/re-ovo/rikkahub)**：本项目基于 RikkaHub 二次开发，诚挚感谢原作者 [re-ovo](https://github.com/re-ovo) 及所有上游贡献者的开源分享。
- **感谢 [SillyTavern（酒馆）](https://github.com/SillyTavern/SillyTavern)**：提示词预设编排、`@D` 对话深度注入、世界书与正则深度过滤等设计理念源自 SillyTavern。
- **开源协议**：本项目遵循与上游一致的 [**GNU Affero General Public License v3.0 (AGPL-3.0)**](LICENSE) 开源协议。
