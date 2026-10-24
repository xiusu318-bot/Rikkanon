<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Rikkanon Icon" width="110" />
  <h1>Rikkanon</h1>
  <p><b>基于 <a href="https://github.com/re-ovo/rikkahub">RikkaHub</a> 深度定制的个人向 Android AI Agent 终端客户端</b></p>
  <p>
    <a href="https://github.com/xiusu318-bot/Rikkanon/releases/latest"><img src="https://img.shields.io/github/v/release/xiusu318-bot/Rikkanon?label=Release&color=ff7eb6" alt="Release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-blue.svg" alt="License: AGPL-3.0" /></a>
    <a href="MOD_DOCS.md"><img src="https://img.shields.io/badge/Docs-MOD__DOCS.md-success" alt="MOD_DOCS" /></a>
  </p>
</div>

---

> [!WARNING]
> ### ⚠️ 项目声明（使用前必读）
> 1. **纯粹个人喜好二改**：本项目纯粹出于个人日常使用需求与个人喜好，基于上游开源项目 [RikkaHub](https://github.com/re-ovo/rikkahub) 进行二次开发，与上游官方无关。
> 2. **个人审美回退 2.5.6 设计**：因个人审美与操作习惯原因，本项目**回退了大量 2.5.6 版本的经典紧凑设计**（包括但不限于：移除 3.0 割裂的大圆角香肠式分段卡片与彩色圆圈底托、移除提供商与 MCP 新建即塞空白项再跳二级菜单 + 大圆角悬浮保存按钮的繁琐交互、移除聊天页 `+` 面板内部翻页、连根移除 3.0 会话配置快照锁定机制等），恢复为一体式紧凑圆角卡片与经典的弹窗/底部面板交互。
> 3. **未充分测试提示**：本项目改动幅度较大，**尚未经过大规模充分测试，可能存在未知 Bug**。支持与原版 RikkaHub 共存安装（独立包名 `me.rerere.rikkahub.rikkanon`），重要配置与聊天记录请定期备份。
> 4. **记忆库功能说明**：因本人日常完全不使用记忆库功能，记忆模块中顺手添加的开关按钮（如「后台自动整理记忆」等）及相关交互逻辑未经深入实测，可能存在交互或逻辑问题，仅用于默认拦截后台自动整理以防偷跑 Token。

---

## 🚀 安装包下载

前往 [**GitHub Releases 页面**](https://github.com/xiusu318-bot/Rikkanon/releases/latest) 直接下载已签名安装包（可与原版 RikkaHub 共存，互不覆盖）：

- 📱 **ARM64 手机主力版（推荐）**：[`Rikkanon_v1.1.0_Release_arm64-v8a.apk`](https://github.com/xiusu318-bot/Rikkanon/releases/download/v1.1.0/Rikkanon_v1.1.0_Release_arm64-v8a.apk)
- 📦 **全架构通用版（含模拟器/x86_64）**：[`Rikkanon_v1.1.0_Release_universal.apk`](https://github.com/xiusu318-bot/Rikkanon/releases/download/v1.1.0/Rikkanon_v1.1.0_Release_universal.apk)

> 注：自 `v1.1.0` 起脱离原跟随上游的 `v2.5.6-rikkanon` 改为独立版本号（因此数字比旧版小），可直接覆盖安装旧版。

---

## ✨ 二开核心特性概览

详细的技术架构、设计逻辑与源码改动定位请参阅 👉 [**`MOD_DOCS.md`（Rikkanon 开发文档）**](MOD_DOCS.md)。

### 1. 🖥️ 工作区持久化交互式 PTY 多终端 (`workspace_terminal`) & 沙箱增强
- **真正持久化的交互式伪终端池**：摒弃一次性非交互命令执行器，复用并扩展 Termux `TerminalSession` + `termux_pty.cpp`。终端按工作区（`workspace.root`）隔离，主对话、子代理、新开对话与人类终端 UI 共享同一终端池。
- **多终端管理与自关闭**：支持 `new_session=true` + `title` 开启多个命名终端、按数字 `#id` 操控；支持在终端内直接输入 `exit` / `Ctrl+D` 退出顶层 Shell 后自动关闭标签页，或通过 `kill` / `kill_all` 关闭终端。
- **PRoot 进程树精确感知 (`inspectTerminalProcessTree`)**：通过 `prootPid -> shellPid` O(1) 快速路径缓存精准识别顶层交互式 Shell 与前台活跃子进程（如 `python3`、`node`、`ssh`），在回到提示符时立即返回，无需傻等超时或全盘遍历 `/proc`。
- **升级内置 PRoot 引擎 (`5.1.107.96`)**：根据上游主分支 `3.1.0` 同步升级 `libproot_exec.so` / `libproot_loader.so`，修复 Ubuntu 26.04 等新版 Rootfs 中 `rust-coreutils` 在 `--link2symlink` 模式下报 `Security violation` 的问题。
- **聊天页顶栏终端实时窥屏小窗 (`WorkspaceTerminalsPeekSheet`)**：在聊天页（含空白新对话）顶栏下方实时展示工作区活跃终端胶囊，点击即可在不离开聊天页的情况下查看 40 行实时终端屏幕、发送命令或 `Ctrl+C`、新建或关闭终端。
- **工作区工具「启用」与「审批」分离控制**：在工作区详情页可对 5 项工作区工具（`read_file`、`write_file`、`edit_file`、`shell`、`terminal`）分别独立控制是否启用以及调用前是否需要审批；同时严格保持 `<workspace>` 系统提示词 100% 静态以保护多轮 Prompt Cache。
- **PRoot 沙箱 6 项“类 VPS”防崩补丁 (`RootfsPatcher.kt`)**：
  1. 绑定挂载 `/dev/shm` 共享内存（修复 Python `multiprocessing` / PyTorch / Chromium 报错）；
  2. 不可读 `/proc` 节点（`stat` / `loadavg` / `uptime` / `vmstat`）自动兜底（修复 `htop` / `free` / `psutil` / `pm2` 权限崩溃）；
  3. 注入 Node.js 网卡枚举预加载补丁与 `ip` 包装（解决 Android 11+ SELinux 禁用 Netlink 导致 Vite / Next.js / PM2 直接崩溃）；
  4. `apt` / `dpkg` 防锁死与自动清缓存（`policy-rc.d` 阻断服务自启卡死、`setcap` 软降级、装包后自动清理 `.deb`）；
  5. 轻量级 `systemctl` 兼容执行器（支持在无 systemd 的 PRoot 下 `systemctl start/stop/restart/status`）；
  6. 默认注入 ARM64 JVM 防崩参数（`-XX:-UseCompressedOops`）与浏览器免沙箱环境变量。

### 2. 🤖 子代理（Sub-Agent）独立模块、多模型绑定与 5 级控制权限
- **独立模块与多助手/多模型绑定 (`AssistantSubAgentPage.kt`)**：
  - 子代理本质上就是一条独立的普通 `Conversation` 对话，共享同一工作区与终端池。
  - 支持通过复选框绑定 **1 个或多个 Rikka 助手**作为子代理（每个子代理拥有独立的模型、系统提示词、思考强度、MCP 与 Skills），支持一键新建子代理助手。
  - **标签长文本描述注入**：助手的「标签 (`Tags`)」支持多行长文本输入与点击编辑，自动注入 `conversation_send` 工具定义中充当子代理职责说明（不注入易变模型名，换模型不破坏主代理缓存）。
- **子代理独立工具管理与双轨审批面板 (`SubAgentToolsSheet`)**：
  - 点击子代理右侧 `⚙` 即可在上方配置该子代理的单轮工具调用次数限制 (`subAgentMaxSteps`)、5 项工作区工具独立开关（如打造只读审查子代理）、MCP、联网与记忆及 Skills（已精简移除子代理无需使用的本地工具）。
  - 下方针对每个已开启的工具独立配置 **「用户审批」** 与 **「主代理审批」**（默认免审批直接执行；只开主代理审批时不弹用户审批窗，子代理暂停并通过强插/唤醒通知主代理调用 `conversation_approve` 审批；只开用户审批时等待人类点击期间不打扰主代理；双开时具备幂等防冲突保护）。
- **子代理全中断态通知（强插 Steer + 闲时唤醒）**：除正常跑完（`completed`）外，当子代理触发待主代理审批（`waiting_approval`）、被用户手动停止（`interrupted_by_user`）或因 API/网络异常中断（`failed`）时，均自动生成折叠通知条通知主代理；Claude / Gemini 请求层自动合并相邻 `user` 消息块以严守交替规范。
- **子代理并发池、单轮步数限制、局部编号路由 (`sub_1`) 与主对话分支 (Fork) 自动克隆隔离**：
  - 支持配置同一主对话下的**子代理并发限制 (`subAgentMaxConcurrency`)**（超出自动平滑排队，排队时间不计入超时）与**单轮工具调用步数限制 (`subAgentMaxSteps` / `max_steps`)**；支持 `conversation_read` 批量等待 (`conversation_ids`) 与 HTTP 429/503 指数退避重试。
  - 主代理统一使用局部代号（`sub_1`、`sub_2`）路由子代理，严格校验 `parentConversationId` 归属边界；对主对话执行分支（Fork）时自动深拷贝独立子代理副本并重新映射 `sub_1`，既 100% 保护历史上下文 Prompt Cache，又确保原对话与分支的子代理完全隔离。
- **主代理对子代理的 5 级控制权限与独立审批 (`MainAgentPermissionsSheet`)**：
  - **0 级（基础必选）**：单次黑盒派发任务并同步等待最终 `reply` 回复；
  - **1 级（窥屏与查看工具轨迹）**：控制是否暴露子代理消息流与工具执行步骤；
  - **2 级（多轮追问与异步并发）**：控制是否允许向已有子代理多轮追问及 `wait=false` 后台并行派发；
  - **3 级（中途打断与强制停止）**：控制是否允许打断正在生成的子代理改派新指令或强制停止；
  - **4 级（删除销毁子代理）**：允许主代理销毁已派生的子代理对话（默认需审批），人类用户亦可在顶栏窥屏小窗或侧边栏树状列表中手动删除。
- **顶栏胶囊与左侧抽屉树状主从跟随 UI**：顶栏下方实时展示子代理运行胶囊（点击弹出 `SubAgentPeekSheet` 实时查看对话流、补充指令、打断或跳转），左侧抽屉以树状缩进（`├─ 🤖`）挂载在主对话下方。

### 3. ⚡ 流式控制、双层 Roll、全消息双模式深度编辑器 & 密钥代名词
- **同轮多工具并发 & 免暂停中途插话（Steer）**：同一轮多个工具调用或多个子代理派发真正 `async` 并发执行；在模型连续调用工具期间，用户无需按暂停即可直接发消息插话，下一步循环立即追加并响应。
- **双层 Roll 与单工具直接编辑**：点开任意工具步骤弹窗，支持直接修改工具 `input`（入参 JSON）与 `output`（返回文本），并提供「仅保存」、「重跑此工具并继续」、「从此步 Roll」三种操作。
- **整条助手消息双模式深度编辑器 (`MessagePartsEditorSheet`)**：
  - **模式 1（可视化部件模式）**：卡片化展示并编辑所有 `Reasoning`、`Tool`、`Text` 部件，支持单步 Roll/重跑、上下移动、增删部件及修改底层字段（`toolName`、`toolCallId`、`approvalState`、`metadata` 等）。
  - **模式 2（底层 JSON 源码模式）**：直接查看与编辑整条 `UIMessage` 在数据库中的完整原始 JSON 结构，支持原地覆盖或保存为新分支。
- **密钥代名词自动替换 & `ask_key` 询问密钥工具**：
  - 在「设置 -> 扩展 -> 密钥代名词」开启后，AI 发出的任何工具调用中包含已启用代名词 `key_name` 时，底层执行瞬间自动替换为真实密钥，且工具输出中若出现真实密钥会自动反向脱敏还原为 `key_name`，防止私钥泄露进模型上下文。
  - 开启本地工具「询问密钥 (`ask_key`)」后，模型可在聊天流中弹出交互卡片供用户点选已有密钥或当场新建密钥。

### 4. 🎨 审美与交互回归（回退 2.5.6 经典设计 & 记忆防偷跑）
- **连根移除 3.0 会话快照锁定**：所有主对话与子代理对话 **100% 实时跟随所属助手的最新模型、思考强度、搜索开关、MCP、Skills 与工具配置**；当某个 API 渠道失效时，修改助手模型即可使已有对话当场原地切换至新渠道继续运行。
- **回归 2.5.6 经典交互与紧凑卡片**：恢复提供商 `+` 弹窗添加与经典详情页、恢复 MCP 底部弹窗配置（`McpServerConfigModal`）、去除聊天页 `+` 面板二级翻页、将全局割裂的 20dp+ 分段胶囊恢复为一体式紧凑圆角卡片（`CardGroup`）。
- **记忆新增「后台自动整理记忆」独立开关（默认关闭）**：在「助手详情 -> 记忆 -> 设置」中默认关闭后台自动整理，彻底屏蔽对话暂停/满 6 轮/切后台时偷偷调用主模型消耗 Token，仅在对话中按需调用记忆工具或手动点击「立即整理」时运行。
- **移除官方更新检测**：禁用后台向原项目更新服务器发起的检测请求。
- **修复长对话上拉回跳底部缺陷**：通过编译期字节码补丁（`ComposeLazyScrollBackFixVisitorFactory`）彻底修复进入长对话后手指上拉突然闪回页面底部的 Compose `LazyList` 回跳 Bug。

### 5. 🗂️ 渠道模型多选批量删除
- **长按 `⋮` 进入多选**：在提供商详情「模型」页长按模型卡片右侧 `⋮` 即进入多选并勾选该模型（单击 `⋮` 仍为原删除菜单；卡片其余区域长按仍为拖拽排序，`⋮` 不参与拖拽）。
- **批量删除**：多选时卡片右侧显示复选框，底部工具栏切换为「取消 / 已选 N 个 / 全选 / 删除」，确认后一次性删除所选模型；按返回键退出多选。

---

## 🛠️ 构建说明

完整编译与打包命令详见 [**`MOD_DOCS.md` 第三节**](MOD_DOCS.md)。

---

## 🙏 致谢与开源协议

- 本项目基于 [**re-ovo/rikkahub**](https://github.com/re-ovo/rikkahub) 二次开发，感谢原作者及所有贡献者提供的优秀基础框架。
- 本项目严格遵循与上游一致的 [**GNU Affero General Public License v3.0 (AGPL-3.0)**](LICENSE) 开源协议开放全部修改源码与说明文档。
