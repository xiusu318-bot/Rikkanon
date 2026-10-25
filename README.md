<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Rikkanon Icon" width="110" />
  <h1>Rikkanon</h1>
  <p><b>基于 <a href="https://github.com/re-ovo/rikkahub">RikkaHub</a> 二次开发的个人向 Android AI Agent 客户端</b></p>
  <p>
    <a href="https://github.com/xiusu318-bot/Rikkanon/releases/latest"><img src="https://img.shields.io/github/v/release/xiusu318-bot/Rikkanon?label=Release&color=ff7eb6" alt="Release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-blue.svg" alt="License: AGPL-3.0" /></a>
    <a href="MOD_DOCS.md"><img src="https://img.shields.io/badge/Docs-MOD__DOCS.md-success" alt="MOD_DOCS" /></a>
  </p>
</div>

---

> [!WARNING]
> ### ⚠️ 项目声明（使用前必读）
> 1. **个人自用向二次开发**：本项目出于个人日常使用需求与操作习惯，基于上游优秀开源项目 [RikkaHub](https://github.com/re-ovo/rikkahub) 进行二次开发与定制改造，与上游官方无关。非常感谢原作者及社区贡献者提供的坚实基础框架与持续更新。
> 2. **保留 2.5.6 经典布局与交互习惯**：因个人日常操作习惯原因，本项目保留了较多 2.5.6 版本的紧凑卡片布局与弹窗交互方式（如一体式圆角卡片、提供商与 MCP 弹窗配置、聊天页单层 `+` 面板，以及会话配置实时跟随助手设定等）。如果您更喜欢现代化的 Material 3 Expressive 视觉设计与完整官方体验，推荐前往下载使用 [**上游官方原版 RikkaHub**](https://github.com/re-ovo/rikkahub)。
> 3. **未充分测试提示**：本项目针对个人使用场景改动较多，**尚未经过大规模充分测试，可能存在未知 Bug**。支持与原版 RikkaHub 共存安装（独立包名 `me.rerere.rikkahub.rikkanon`），重要配置与聊天记录请定期备份。
> 4. **记忆库功能说明**：因本人日常较少使用记忆库功能，记忆模块中额外添加的开关按钮（如「后台自动整理记忆」等）及相关交互逻辑未经深入实测，可能存在细节问题，主要用于按需手动控制后台自动整理时机以节省 Token 消耗。

---

## 🚀 安装包下载

前往 [**GitHub Releases 页面**](https://github.com/xiusu318-bot/Rikkanon/releases/latest) 直接下载已签名安装包（可与原版 RikkaHub 共存，互不覆盖）：

- 📱 **ARM64 手机主力版（推荐）**：[`Rikkanon_v1.1.0_Release_arm64-v8a.apk`](https://github.com/xiusu318-bot/Rikkanon/releases/download/v1.1.0/Rikkanon_v1.1.0_Release_arm64-v8a.apk)
- 📦 **全架构通用版（含模拟器/x86_64）**：[`Rikkanon_v1.1.0_Release_universal.apk`](https://github.com/xiusu318-bot/Rikkanon/releases/download/v1.1.0/Rikkanon_v1.1.0_Release_universal.apk)

> 注：自 `v1.1.0` 起脱离原跟随上游的 `v2.5.6-rikkanon` 改为独立版本号（因此数字比旧版小），可直接覆盖安装旧版。

---

## ✨ 二开定制功能概览

详细的技术架构、设计逻辑与源码改动定位请参阅 👉 [**`MOD_DOCS.md`（Rikkanon 开发文档）**](MOD_DOCS.md)。

### 1. 🖥️ 工作区持久化交互式 PTY 多终端 (`workspace_terminal`)、顶栏窥屏小窗 & 沙箱兼容补丁
- **持久化交互式伪终端工具 (`workspace_terminal`)**：在原有一次性命令执行器基础上，结合 `:terminal` 终端会话池扩展出供 AI 持续交互的伪终端工具（同时支持本地 PRoot 与远程 SSH 工作区）。终端按工作区（`workspace.root`）共享，主对话、子代理、新开对话与人类终端 UI 共用同一终端池。
- **多终端管理与自关闭**：支持 `new_session=true` + `title` 开启多个命名终端、按数字 `#id` 操控；支持在终端内输入 `exit` / `Ctrl+D` 退出顶层 Shell 后自动关闭标签页，或通过 `kill` / `kill_all` 参数关闭终端。
- **PRoot 进程树状态感知 (`inspectTerminalProcessTree`)**：通过 `prootPid -> shellPid` O(1) 快速路径缓存识别顶层交互式 Shell 与前台活跃子进程（如 `python3`、`node`、`ssh`），在命令结束回到提示符时及时返回，无需等待固定超时或全盘遍历 `/proc`。
- **聊天页顶栏终端实时窥屏小窗 (`WorkspaceTerminalsPeekSheet`)**：在聊天页（含空白新对话）顶栏下方实时展示工作区活跃终端胶囊，点击即可在不离开聊天页的情况下查看 40 行实时终端屏幕、发送命令或 `Ctrl+C`、新建或关闭终端。
- **工作区工具「启用」与「审批」分离控制**：在工作区详情页可对 5 项工作区工具（`read_file`、`write_file`、`edit_file`、`shell`、`terminal`）分别独立控制是否启用以及调用前是否需要审批；同时保持 `<workspace>` 系统提示词静态以保护多轮 Prompt Cache。
- **PRoot 沙箱 6 项运行环境兼容补丁 (`RootfsPatcher.kt`)**：
  1. 绑定挂载 `/dev/shm` 共享内存（修复 Python `multiprocessing` / PyTorch / Chromium 报错）；
  2. 不可读 `/proc` 节点（`stat` / `loadavg` / `uptime` / `vmstat`）自动兜底（修复 `htop` / `free` / `psutil` / `pm2` 权限报错）；
  3. 注入 Node.js 网卡枚举预加载补丁与 `ip` 包装（解决 Android 11+ SELinux 限制 Netlink 导致 Vite / Next.js / PM2 报错退出）；
  4. `apt` / `dpkg` 防锁死与自动清缓存（`policy-rc.d` 阻断服务自启卡住、`setcap` 软降级、装包后自动清理 `.deb`）；
  5. 轻量级 `systemctl` 兼容脚本（支持在无 systemd 的 PRoot 下执行基础的 `systemctl start/stop/restart/status`）；
  6. 默认注入 ARM64 JVM 兼容参数（`-XX:-UseCompressedOops`）与浏览器免沙箱环境变量。

### 2. 🤖 子代理（Sub-Agent）独立模块、多模型绑定与分级权限控制
- **独立模块与多助手/多模型绑定 (`AssistantSubAgentPage.kt`)**：
  - 子代理本质上是一条独立的普通 `Conversation` 对话，可共享同一工作区与终端池。
  - 支持通过复选框绑定 **1 个或多个助手**作为子代理（每个子代理拥有独立的模型、系统提示词、思考强度、MCP 与 Skills），支持在页面内快速新建子代理助手。
  - **标签长文本描述注入**：助手的「标签 (`Tags`)」支持多行长文本输入与点击编辑，自动注入 `conversation_send` 工具定义中充当子代理职责说明（不注入易变模型名，避免换模型影响主代理缓存）。
- **子代理独立工具管理与双轨审批面板 (`SubAgentToolsSheet`)**：
  - 点击子代理右侧 `⚙` 可配置该子代理的单轮工具调用次数限制 (`subAgentMaxSteps`)、5 项工作区工具独立开关（如配置只读子代理）、MCP、联网与记忆及 Skills。
  - 下方针对每个已开启的工具独立配置 **「用户审批」** 与 **「主代理审批」**（默认免审批直接执行；只开主代理审批时由主代理通过 `conversation_approve` 处理；只开用户审批时由人类在 UI 确认；双开时具备幂等防冲突保护）。
- **子代理全中断态通知（强插 Steer + 闲时唤醒）**：除正常完成（`completed`）外，当子代理触发待主代理审批（`waiting_approval`）、被用户手动停止（`interrupted_by_user`）或因接口异常中断（`failed`）时，均自动生成折叠通知条通知主代理；请求层按协议规范处理相邻 `user` 消息块（Claude 合并相邻同角色块，Gemini 保持 `functionResponse` 独立成块）。
- **子代理并发池、单轮步数限制、局部编号路由 (`sub_1`) 与主对话分支 (Fork) 自动克隆隔离**：
  - 支持配置同一主对话下的**子代理并发限制 (`subAgentMaxConcurrency`)** 与**单轮工具调用步数限制 (`subAgentMaxSteps` / `max_steps`)**；支持 `conversation_read` 批量等待 (`conversation_ids`) 与 HTTP 429/503 指数退避重试。
  - 主代理统一使用局部代号（`sub_1`、`sub_2`）路由子代理，校验 `parentConversationId` 归属边界；对主对话执行分支（Fork）时自动深拷贝独立子代理副本并重新映射 `sub_1`，兼顾历史上下文 Prompt Cache 与分支隔离。
- **主代理对子代理的 5 级控制权限 (`MainAgentPermissionsSheet`)**：
  - **0 级（基础必选）**：单次派发任务并同步等待最终 `reply` 回复；
  - **1 级（查看工具轨迹）**：控制是否向主代理暴露子代理消息流与工具执行步骤；
  - **2 级（多轮追问与异步并发）**：控制是否允许向已有子代理多轮追问及 `wait=false` 后台并行派发；
  - **3 级（中途打断与停止）**：控制是否允许打断正在生成的子代理改派新指令或停止运行；
  - **4 级（删除销毁子代理）**：允许主代理销毁已派生的子代理对话（默认需审批），用户亦可在顶栏窥屏小窗或侧边栏树状列表中手动删除。
- **顶栏胶囊与左侧抽屉树状主从跟随 UI**：顶栏下方实时展示子代理运行胶囊（点击弹出 `SubAgentPeekSheet` 查看对话流、补充指令或跳转），左侧抽屉以树状缩进（`├─ 🤖`）挂载在主对话下方。

### 3. ⚡ 流式控制、双层 Roll、全消息双模式编辑器、密钥代名词 & 模型多选删除
- **同轮多工具并发 & 免暂停中途插话（Steer）**：同一轮多个工具调用或多个子代理派发支持 `async` 并发执行；在模型连续调用工具期间，用户无需暂停即可直接发送消息插话，下一轮步骤自动追加并响应。
- **双层 Roll 与单工具直接编辑**：点开任意工具步骤弹窗，支持直接修改工具 `input`（入参 JSON）与 `output`（返回文本），并提供「仅保存」、「重跑此工具并继续」、「从此步 Roll」三种操作。
- **整条助手消息双模式编辑器 (`MessagePartsEditorSheet`)**：
  - **模式 1（可视化部件模式）**：卡片化展示并编辑所有 `Reasoning`、`Tool`、`Text` 部件，支持单步 Roll/重跑、上下移动、增删部件及修改底层字段（`toolName`、`toolCallId`、`approvalState`、`metadata` 等）。
  - **模式 2（底层 JSON 源码模式）**：查看与编辑整条 `UIMessage` 在数据库中的原始 JSON 结构，支持原地覆盖或保存为新分支。
- **密钥代名词自动替换 & `ask_key` 询问密钥工具**：
  - 在「设置 -> 扩展 -> 密钥代名词」开启后，工具调用中包含已启用代名词 `key_name` 时，执行层自动替换为真实密钥，且工具输出中若出现真实密钥会自动反向脱敏还原为 `key_name`，避免私钥进入模型上下文。
  - 开启本地工具「询问密钥 (`ask_key`)」后，模型可在聊天流中弹出交互卡片供用户选择已有密钥或新建密钥。
- **更新渠道编辑能力 & 渠道模型多选批量删除**：优化提供商渠道编辑管理能力；在提供商详情「模型」页长按模型卡片右侧 `⋮` 即可进入多选并勾选该模型（单击 `⋮` 仍为原删除菜单，卡片其余区域长按仍为拖拽排序），支持全选与一键批量删除。

### 4. 🎨 2.5.6 经典交互保留、记忆自动整理开关 & 滚动修复
- **会话配置实时跟随助手设定**：主对话与子代理对话实时跟随所属助手的最新模型、思考强度、搜索开关、MCP、Skills 与工具配置；切换助手模型后已有对话可直接按新模型继续运行。
- **保留 2.5.6 紧凑卡片与弹窗交互**：保留提供商 `+` 弹窗添加与经典详情页、MCP 底部弹窗配置、聊天页单层 `+` 面板以及一体式紧凑圆角卡片（`CardGroup`）。
- **记忆新增「后台自动整理记忆」独立开关（默认关闭）**：在「助手详情 -> 记忆 -> 设置」中可独立控制是否开启后台自动整理，方便仅在对话中按需调用记忆工具或手动点击「立即整理」。
- **长对话上拉回跳修复**：通过编译期字节码补丁（`ComposeLazyScrollBackFixVisitorFactory`）修复进入长对话后首次上拉偶发跳回页面底部的 Compose `LazyList` 测量差值累加问题。

---

## 🔄 同步上游 RikkaHub (`3.1.0` / `3.1.1`) 更新与适配说明

以下特性与优化**源自上游 [re-ovo/rikkahub](https://github.com/re-ovo/rikkahub) 主分支（`3.1.0` / `3.1.1`）的优秀更新成果**，本项目在保留上述个人定制体验的基础上，同步引入了上游代码并完成了与二开模块的桥接适配（特此感谢上游原作者的开发工作）：

- **上游 SSH 远程工作区 (`SshWorkspaceBackend`) & `:terminal` 模块底层升级**：
  - 上游新增了「SSH 远程工作区」（基于 JSch，支持密码/私钥认证、主机指纹确认、Android Keystore 加密存储、空闲自动断连与重连）以及重构后的 `:terminal` 传输层（`LocalPtyTransport` / `SshTerminalTransport`）与本地会话孤儿进程清理机制。
  - **本项目适配**：将二开的 `workspace_terminal` 持久化多终端、进程树状态检测及聊天页顶栏「工作区终端窥屏小窗 (`WorkspaceTerminalsPeekSheet`)」与上游 `:terminal` / SSH 工作区打通，使多终端与实时窥屏同时支持本地 PRoot 与远程 SSH 服务器。
- **上游后台生成悬浮窗 (`ChatFloatingWindows`)**：
  - 上游在「设置 -> 通知设置」中新增了「后台生成时显示悬浮窗」功能，支持切后台时悬浮查看生成进度、审批工具调用或发送消息。
  - **本项目适配**：替换了千早爱音动画图标，接入 Steer 免暂停插话，并在 `ChatFloatingTracker` 中增加了后台子代理过滤（防止后台子代理并发运行时抢占主对话悬浮窗）。
- **上游 APK 体积大幅瘦身方案（Release ARM64 从 69MB 降至 21MB）**：
  - 同步引入上游 `3.1.1` 的全套体积优化：纯 Kotlin PDF 提取器替换 `libmupdf_java.so`、`CameraX + zxing` 扫码替换 ML Kit、Compose `ImageCropDialog` 替换 `uCrop`、全文搜索改用无结巴 `libsimple.so` + 单字 `NEAR` 查询（本项目同步保留了子代理 FTS 过滤）、`web-ui` 换用 `highlight.js` + `woff2` 及自实现 HS256 JWT，并开启 Release R8 优化。
- **上游内置 PRoot 引擎升级 (`5.1.107.96`)**：同步上游 `3.1.0` 的 `libproot_exec.so` / `libproot_loader.so` 更新，修复 Ubuntu 26.04 等新版 Rootfs 中 `rust-coreutils` 在 `--link2symlink` 模式下报 `Security violation` 的问题。
- **上游工具耗时显示、模型能力目录与多项体验增强/修复**：同步引入上游的工具调用右侧实时执行耗时显示、内置 `models.dev` 模型能力目录与参数量后缀识别优化、独立「记忆整理模型」设置、聊天 Markdown 直接渲染 `/workspace` 工作区图片、输入图片涂鸦画板，以及上游针对 LaTeX 公式、超长代码块、TTS 朗读等场景的多项修复。

---

## 🛠️ 构建说明

```bash
# 构建 Debug 测试包
./gradlew assembleDebug

# 构建 Release 正式包（需在根目录 local.properties 中配置签名证书参数）
./gradlew assembleRelease
```

更多技术细节与源码改动定位详见 [**`MOD_DOCS.md`**](MOD_DOCS.md)。

---

## 🙏 致谢与开源协议

- 本项目基于 [**re-ovo/rikkahub**](https://github.com/re-ovo/rikkahub) 二次开发，诚挚感谢原作者 [re-ovo](https://github.com/re-ovo) 及所有上游贡献者付出的心血与开源分享。
- 本项目严格遵循与上游一致的 [**GNU Affero General Public License v3.0 (AGPL-3.0)**](LICENSE) 开源协议开放全部修改源码与说明文档。
