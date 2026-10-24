# Rikkanon 开发文档（基于 RikkaHub 二开）

本文档记录了 **Rikkanon** 中针对 **交互式 PTY 终端（支持多终端、自关闭、跟随工作区）**、**子代理独立模块与多助手模型绑定（Sub-Agent）**、**连续工具调用中途插话（Steer）**、**双层 Roll（整块与单步/单工具重跑）**、**工具调用内容直接编辑** 以及 **工作区终端 & 子代理主从跟随 UI** 的全部设计逻辑与代码改动定位，方便后续继续迭代。

---

## 一、 整体目录与构建产物速览

- **已签名安装包（当前版本 v1.1.0）**：
  - **Release 正式版（无开发模式水印，推荐日常主力使用）**：
    - ARM64 手机版：`Rikkanon_v1.1.0_Release_arm64-v8a.apk`（69MB）
    - 全架构通用版：`Rikkanon_v1.1.0_Release_universal.apk`（81MB）
  - **Debug 调试版**：
    - ARM64 手机版：`Rikkanon_v1.1.0_Debug_arm64-v8a.apk`（84MB）
    - 全架构通用版：`Rikkanon_v1.1.0_Debug_universal.apk`（97MB）
- **共存包名与签名配置**：
  - `applicationId`：`me.rerere.rikkahub.rikkanon`（与正式版 `me.rerere.rikkahub` 隔离，互不覆盖）
  - 桌面名称 & 关于页标题：`Rikkanon`（`versionName = "1.1.0"`）
  - 桌面图标：已替换为粉色兔耳千早爱音（Anon）自适应图标（`app/src/main/res/mipmap-*/ic_launcher*.png`，前景透明抠图 + 纯白背景层，完美适配圆形/圆角矩形桌面遮罩）

---

## 二、 四大核心改造模块与源码定位

### 模块 1：工作区持久化交互式 PTY 终端（`workspace_terminal`）

**核心思想**：
在原有一次性非交互命令执行器 `ProotShellRunner` 基础上，复用并扩展 App 的 Termux `TerminalSession` + `termux_pty.cpp` 伪终端池。终端**只按工作区（`workspace.root`）隔离，不按对话或助手隔离**，主对话、绑定了不同模型的子代理对话、新开对话以及人类用户的终端 UI 共享同一个终端池，支持同时开启多个终端标签页、按唯一数字 `id`（`tab.id`）操控，并支持通过命令 `exit` / `Ctrl+D` 或工具参数关闭终端本身。

#### 涉及文件与函数：
1. **`workspace/src/main/cpp/termux_pty.cpp`**
   - 新增 JNI 函数 `Java_com_termux_terminal_JNI_getForegroundProcessGroup(JNIEnv *, jclass, jint fd)`：调用 `tcgetpgrp(fd)` 获取 PTY 当前前台进程组。
2. **`app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSession.kt`**
   - `createWorkspaceTerminalSession(...)`：补齐了 `/upload` 和 `/tool_outputs` 两个 `-b` 挂载目录（与 `RepositoryModule.kt` 对齐）。
   - `WorkspaceTerminalSessionClient`：新增 `lastOutputAtMs: AtomicLong` 与 `outputEvents: MutableSharedFlow<Long>`，在 `onTextChanged` 和 `onSessionFinished` 时记录最后输出时间，供 AI 工具判断输出是否已稳定（Settle Detection）。
   - `inspectTerminalProcessTree(prootPid: Int): TerminalProcessTreeState`：
     - 维护 `cachedShellPids`（`prootPid -> shellPid`）缓存，常规轮询直接走 **O(1) 快速路径**（仅读取 `/proc/<prootPid>/stat`、`/proc/<shellPid>/stat` 及有前台任务时的 `/proc/<fgPgid>/stat` 共 2~3 个文件），避免遍历整个 `/proc` 目录；首次发现或边缘慢路径带 250ms 节流缓存。
     - 当顶层 Shell 存活且无活跃前台子进程（或 `tpgid == shellPgrp`）时，精确判定 `isAtPrompt = true`（`prompt_ready`），无需等待固定超时即可立即返回。
     - 当 `prootPid` 或顶层 Shell 进程已退出（如在终端内执行了 `exit`、`logout` 或发送了 `\u0004` `Ctrl+D`）时，立即判定 `processExited = true`。
3. **`app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSessionManager.kt`**
   - `createTabNow(...)`：在创建出 `TerminalSession` 后，若用户尚未点开过终端 UI 页面（`termSession.emulator == null`），立即调用 `termSession.updateSize(120, 40)` 初始化虚拟屏幕缓冲并启动后台读写线程；通过 `try ... finally` 确保 `isCreating` 状态永不卡死，支持连续开启任意多个终端标签页。
   - `listActiveTabs(root: String): List<WorkspaceTerminalTabInfo>`：返回当前工作区所有存活终端的 `id`、`number`、`title`、`shellPid`、`foregroundPgid`、`foregroundCommand`、`isAtPrompt` 以及屏幕尾部 6 行非空预览 `screenPreview`，并自动清理后台已退出的死标签页。
   - `interact(...)`：
     - 支持 `createNew = true`（开启新终端并支持传入 `title` 命名）、按 `tabId` 操控指定终端、管理增量游标 `tabCursors` 与 512 字符轻量尾部锚点 `tabTailAnchors`（常规走 `String.regionMatches` O(1) 快速路径，满 2000 行回滚淘汰时走多行尾锚点对齐 `extractIncrementalTranscript`）、等待输出稳定并返回 `incrementalOutput` 与 `screenSnapshot`（当前 40 行可视屏幕快照）。
     - 支持关闭终端本身：
       1. 传入 `kill = true` 关闭并终止指定 `id`（或当前活跃）终端标签页；传入 `killAll = true` 一键关闭该工作区所有终端。
       2. 在终端内发送 `exit` / `logout` / `\u0004` (`Ctrl+D`) 使顶层 Shell 退出时，读取完最后输出后自动调用 `closeTab(root, finalTab.id)` 移除该标签页，返回 `status = "exited_and_closed"`。
   - `readTabScreenSnapshot(...)` & `writeToTab(...)`：供聊天页顶栏「工作区终端实时小窗 (`WorkspaceTerminalsPeekSheet`)」直接读取终端屏幕快照与发送快捷指令。
4. **`app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt`**
   - `createTerminalTool(...)`：定义 `workspace_terminal` 工具（参数：`id`、`title`、`input`、`send_enter`、`wait_ms`、`new_session`、`kill`、`kill_all`、`list_only`）。只读操作（`list_only=true` 或不传 `input` 仅读屏幕）自动免除审批。
   - `normalizeRootfsAbsolutePath()`：对 `workspace_read_file`、`workspace_write_file`、`workspace_edit_file` 的路径消解 `.` 与 `..`，确保 `pathOutsideWritableRoots` 安全目录免审批校验不可被路径穿越绕过。
   - `WorkspaceToolDefaultApprovals`：注册 `"workspace_terminal" to true`。
5. **`app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt`**
   - `buildWorkspacePrompt(...)`：保持 `<workspace>` 系统提示词 **100% 静态**（绝不把动态变化的实时终端列表塞进系统提示词，严格保护多轮对话的 Prompt Cache / KV 缓存命中率）。AI 需要查看工作区当前开着哪些终端及各自状态时，主动通过工具 `workspace_terminal(list_only=true)` 获取。
6. **`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt` & `WorkspaceDetailPage.kt` & `WorkspaceEntity.kt`**
   - 新增 `TerminalToolUI` 渲染器并在 `ToolUIRegistry`（`ToolUI.kt`）注册，展示终端 `#id`、自定义 `[title]`、前台命令与输出预览。
   - 在 `WorkspaceDetailPage.kt` 中提供独立的 **「工具启用 (`WorkspaceToolEnabledCard`)」** 与 **「工具审批 (`WorkspaceToolApprovalCard`)」** 两套开关，支持对全部 5 个工作区工具（`workspace_read_file`、`workspace_write_file`、`workspace_edit_file`、`workspace_shell`、`workspace_terminal`）分别控制是否开启以及调用前是否需要审批；被关闭的工具自动从工具注册与 `<workspace>` 提示词中移除。

---

### 模块 2：子代理独立模块与多助手/多模型绑定（`AssistantSubAgentPage` + `ConversationTools` + `ChatService`）

**核心思想**：
1. **子代理本质上就是 RikkaHub 里的一条普通 `Conversation`（对话窗口）**：子代理完全不知道自己是子代理，主代理发来的话在它那只是普通的 `MessageRole.USER` 消息，它共享同一个工作区与终端池，正常调工具、正常输出 `MessageRole.ASSISTANT` 回复。
2. **借助 Rikka 自身的「助手 (`Assistant`)」体系实现子代理独立模块与多模型绑定**：
   - 将子代理功能从「本地工具」中独立出来，在「助手详情」页设立独立的 **「子代理 (Sub-Agent)」** 模块（`AssistantSubAgentPage.kt`）。
   - 采用 **复选框 (`Checkbox`) 支持单选或多选** 绑定 1 个或多个 Rikka 助手作为子代理（无“默认子代理”概念）。每个被绑定的子代理助手拥有自己独立的聊天模型（`chatModelId`）、系统提示词（`systemPrompt`）、思考预算（`reasoningLevel`）、MCP 与 Skills。
   - **提示词最小化、标签描述注入与缓存友好**：
     - 助手的「基础设定 -> 标签 (`tags`)」支持输入多行长文本（点击已添加标签也可直接弹窗编辑），直接充当子代理职责/能力描述（无需额外新建描述字段或存储）。
     - 在 `conversation_send` 工具定义中，自动注入已勾选绑定子代理的 **名称、`assistant_id` 以及标签描述 (`tags/description`)**（不包含模型名等易变细节，换模型不会破坏主代理缓存）；无论绑定了 1 个还是多个子代理，主代理创建子对话时统一根据名称与标签描述按规范传 `assistant_id` + `title` + `message` 调用。
   - **子代理独立工具管理面板 (`SubAgentToolsSheet`)**：
     - 点击子代理列表右侧的 `⚙` 按钮，直接弹出「子代理工具管理」面板（名称、标签、模型仍跟随助手基础设定），上方管理该子代理可调用的：**工具调用次数限制 (`subAgentMaxSteps`)**、**5 项工作区工具独立开关 (`disabledWorkspaceTools`)**、**MCP 工具服务 (`mcpServers`)**、**联网搜索 / 长期记忆** 以及 **Skills (`enabledSkills`)**（已移除子代理无需使用的本地工具）。
     - 下方针对每个已开启的工具独立配置 **「用户审批 (`subAgentUserApprovalTools`)」** 与 **「主代理审批 (`subAgentAgentApprovalTools`)」**（默认均不勾选即免审批直接执行；只开主代理审批时人类界面不弹审批窗，由主代理调用 `conversation_approve` 审批；只开用户审批时等待人类点击期间不打扰主代理；双开时支持幂等防冲突）。
   - **工具按需查看子代理状态与严格权限边界**：主代理想看已绑定子代理助手的具体状态（包括当前绑定的模型名称、供应商、思考预算、标签描述等）以及已派生子对话的运行状态时，直接调用 `conversation_read`（不传 `conversation_id` 即返回全局子代理助手模型状态与当前会话派生的活跃子会话概览）；所有带 `conversation_id` 的子代理操作均强制校验 `config.parentConversationId == currentConversationId`，与普通用户对话严格隔离。

#### 涉及文件与函数：
1. **`app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt` & `ConversationConfig.kt` & `Conversation.kt`**
   - `Assistant` 新增独立子代理配置字段：
     - `enableSubAgent: Boolean = false`：子代理独立模块总开关。
     - `subAgentIds: Set<Uuid> = emptySet()`：勾选绑定的子代理助手 ID 集合（支持单选或多选）。
     - `subAgentShareWorkspace: Boolean = true`：子代理是否自动继承并共享主对话绑定的工作区与交互式 PTY 终端池。
     - `subAgentMaxConcurrency: Int = 2`：同一主对话下同时并发运行的子代理上限（默认 2，最低 1，超出自动排队）。
     - `subAgentUserApprovalTools: Set<String> = emptySet()`：作为子代理运行时需要【人类用户】审批的工具/类别集合。
     - `subAgentAgentApprovalTools: Set<String> = emptySet()`：作为子代理运行时需要【主代理】审批的工具/类别集合。
     - `subAgentMaxSteps: Int = 0`：作为子代理运行时单轮默认最大工具调用步数（0 表示不设限）。
     - `isSubAgentEnabled: Boolean`：当开关开启且至少勾选绑定了 1 个子代理助手时生效。
   - `ConversationConfig` 新增字段：
     - `childConversationIds: Set<Uuid> = emptySet()`：主对话记录其关联的真实子对话 UUID。
     - `subAgentIdMap: Map<String, Uuid> = emptyMap()`：主对话局部子代理编号（如 `"sub_1"`、`"sub_2"` 及兼容旧历史 ID）到数据库真实子对话 `Uuid` 的映射表。
     - `nextSubAgentIndex: Int = 0`：单调递增的子代理编号计数器，删除子代理后不再复用已删的 `sub_N` 编号。
     - `parentConversationId: Uuid? = null`：若为子对话，记录创建它的主对话 ID。
     - `autoApproveTools: Boolean = false`：子对话在后台执行工具时自动免除审批弹窗。
     - `subAgentMaxStepsOverride: Int? = null`：主代理在 `conversation_send` 时显式传入的单轮最大工具步数（`null` 取子代理默认 `subAgentMaxSteps`，`0` 为不设限）。
   - `Conversation.resolveRealSubAgentId` / `resolveLogicalSubAgentId` / `allChildConversationIds`（`Conversation.kt`）：负责主对话局部代号（`sub_1`）与底层真实子对话 `Uuid` 的双向解析，以及合并当前主对话所属的全部真实子代理 ID（仅扫描配置集合与无报错 `conversation_send` 输出，不扫描不可信 `tool.input`）。
2. **`app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantSubAgentPage.kt` & `AssistantDetailPage.kt` & `AssistantDetailVM.kt` & `RouteActivity.kt` & `FilesPicker.kt`**
   - 新增 `Screen.AssistantSubAgent(id)` 路由与 `AssistantSubAgentPage` 页面。
   - 在 `AssistantDetailPage.kt`（助手详情）和 `FilesPicker.kt`（聊天页底部 `+` 面板）展示独立入口与已绑定子代理数量/名称摘要。
   - 在 `AssistantSubAgentPage.kt` 中支持多选勾选已有 Rikka 助手，并提供 `CreateSubAssistantDialog`（`vm.createAndBindSubAgentAssistant`）一键新建配置了其他模型的助手并自动勾选绑定。
3. **`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt` & `ConversationSession.kt`**
   - `sendToSubConversation(...)`：
     - 创建新子对话时，强制校验传入的 `targetAssistantId` 属于主助手的 `effectiveSubAgentIds`，并解析绑定的子代理助手 `resolvedSubAssistant` 及其模型 `resolvedSubModel`。
     - 若传入了已有 `targetConversationId`（多轮追问），强制校验其 `config.parentConversationId == parentConversationId`，严禁触碰或劫持普通用户对话。
     - 新建的 `Conversation` 绑定 `assistantId = resolvedSubAssistant.id`，其 `ConversationConfig` 记录 `parentConversationId = parentConversationId`，并在 `subAgentShareWorkspace = true` 时自动继承主对话的 `workspaceId` 与 `workspaceCwd`（`Assistant.withConversation` 优先应用 `config.workspaceId`）。
     - 支持 `wait = true`（同步等待，超时自动转后台不杀任务）、`wait = false`（异步后台并行）、`interrupt = true`（打断正在生成的子代理并立即改派新指令）。
     - 返回 `SubConversationSendResult`（含 `assistantId`、`assistantName`、`modelName`、`status`、`reply`、`toolSteps`）。
   - `approveSubConversationTool(...)` & `handleToolApproval(...)`：
     - 共用 `ConversationSession.approvalMutex` 串行化人机双轨审批，并在修改消息树前强制 `session.acquire()` + `session.initialize { loadConversation(targetConversationId) ?: targetConv }`，防止子代理空闲 5 秒卸载后空 Session 覆盖清空数据库记录。
   - `readSubConversation(...)`：读取目标子对话实时状态、所用助手与模型名称、工具步骤与消息列表，支持 `waitMs > 0` 等待完成。
4. **`app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt` & `ChatToolFactory.kt` & `MessageFtsManager.kt`**
   - `conversation_send`：描述中仅注入已勾选绑定子代理的 `Name`、`assistant_id` 与 `tags/description`；新建子对话传 `assistant_id` + `title` + `message`，追问传 `conversation_id` + `message`（强制校验 `requireOwnedSubAgentConversation`）。
   - `conversation_read`：
     - 不传 `conversation_id` 时：返回 `bound_sub_agents`（所有已绑定子代理助手的 `assistant_id`、`name`、`tags/description`、当前实时 `model`、`provider`、`reasoning_level`、真实可用工具与工作区状态）与 `active_sub_conversations`（当前主对话已派生的所有子对话及其运行状态、所用模型）。
     - 传 `conversation_id` 时：在未开启 `enableRecentChatsReference` 时强制校验目标对话必须属于当前主对话派生的子代理（`requireOwnedSubAgentConversation`），返回该子对话的详细状态、所用模型、工具执行轨迹与消息内容。
   - `conversation_stop` / `conversation_delete`：强制校验 `requireOwnedSubAgentConversation` 后，立即停止或彻底删除当前主对话派生的指定 `conversation_id` 子代理对话。
   - `conversation_approve`：主代理对处于 `waiting_approval` 状态的当前主对话子代理执行工具批准或拒绝并自动恢复后台运行（支持幂等 `already_handled` 返回）。
   - `recent_chats` / `conversation_search`：仅在用户显式开启了「记忆 -> 参考最近对话 (`enableRecentChatsReference`)」时才注册暴露；`MessageFtsManager` 在写入索引时跳过子代理会话并在搜索 SQL 中直接过滤掉 `parentConversationId != null` 的记录。
   - 当 `isSubConversation == true` 时，自动隐藏本地工具、`ask_key`、`ask_user`、`conversation_send`、`conversation_stop`、`conversation_delete`。

---

### 模块 3：免暂停中途插话（Steer）、双层 Roll 重发、工具调用内容直接编辑

#### 1. 连续调用工具期间免按停插话（Steer） & 同轮多工具并发
- **文件**：`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt`、`ChatService.kt`
- **实现**：
  - 将 `GenerationLoop.kt` 中的 `toolsToProcess.forEach` 改为 `coroutineScope { toolsToProcess.map { tool -> async { ... } }.awaitAll() }`，使同一轮的多个工具调用或多个子代理派发真正并发执行。
  - 在 `GenerationLoop.generateText` 新增 `pollQueuedUserMessages: (suspend () -> List<UIMessage>)? = null` 参数。
  - 每一步工具执行完毕（以及流结束前），自动从 `session.messageQueue.takeNext()` 取出用户在生成期间发送的消息，作为 `UIMessage(role = MessageRole.USER)` 直接追加到 `messages` 末尾，并将该消息关联的 `reply: CompletableDeferred` 收集到 `steeredReplyDeferreds`，在流结束（`onCompletion`）时统一完成并释放引用计数。下一轮 `stepIndex` 循环时，`StreamChunkHandler` 看到末尾是 `USER` 消息，会自动在后面开启一个新的 `ASSISTANT` 块并立即响应用户的插话。

#### 2. 双层 Roll（外层整块 Roll vs 内层单步/单工具 Roll）
- **文件**：`ChatService.kt`（`regenerateAtPart`）、`ChatVM.kt`、`ChatList.kt`、`ChatMessageCot.kt`、`ChatMessage.kt`、`ChatMessageTools.kt`
- **实现**：
  - 在 `ChatMessageCot.kt` 的 `ThinkingStep` 中保留每个步骤在 `UIMessage.parts` 中的原始下标 `partIndex: Int`。
  - 在 `ChatService.kt` 新增 `regenerateAtPart(conversationId, message, partIndex, rerunTool)`：
    - **从此步 Roll（`rerunTool = false`）**：保留 `0..<partIndex` 的前置步骤，在当前 `MessageNode` 下新建一个分支，截断后续步骤并从此处让模型重新生成。
    - **重跑此工具（`rerunTool = true`）**：保留 `0..partIndex`，将该 `UIMessagePart.Tool` 的 `output` 清空并设为 `ToolApprovalState.Approved`，让 `GenerationLoop` 直接重新执行该工具并继续后续生成。

#### 3. 直接编辑工具调用内容（`input` / `output`）与全消息结构化编辑器
- **文件**：`ChatService.kt`（`editToolCallPart`）、`ChatMessageTools.kt`（`ToolCallEditorContent`）、`ChatMessage.kt`（`MessagePartsEditorSheet`）
- **实现**：
  - **单工具弹窗内编辑**：点开任意工具步骤 BottomSheet，顶部提供 **「编辑工具调用/结果」**、**「重跑此工具」**、**「从此步 Roll」** 三个按钮。切换至编辑态后可直接修改 `input`（JSON 入参）与 `output`（返回文本），并支持：
    1. `SAVE_ONLY`（仅保存修改）
    2. `RERUN_TOOL_AND_CONTINUE`（按新入参重跑此工具并继续生成后续）
    3. `CONTINUE_FROM_EDITED_OUTPUT`（保留新修改的输出文本，截断后续步骤并让模型基于新输出继续生成）
  - **整条助手消息双模式深度编辑器 (`MessagePartsEditorSheet`)**：
    - 统一收口于助手消息菜单的 **「编辑」** 入口（所有助手消息均进入此编辑器，无需区分普通编辑与深度编辑）。
    - **模式 1：可视化部件模式（默认）**：按 `#1`、`#2`、`#3` 展示所有 `Reasoning`、`Tool`、`Text`、`Image/Document` 卡片；支持单步 **「重跑工具」**、**「从此步 Roll」**、**「↑ 上移 / ↓ 下移」**、**「删除」** 以及底部 **「+ 新增正文 / + 新增思考 / + 新增工具块」**；点卡片右上角 `⚙` 可展开修改底层字段（如 `toolName`、`toolCallId`、`approvalState`、`reasoningType`、`metadata` JSON）。
    - **模式 2：底层 JSON 源码模式**：点击顶部 **「底层 JSON 源码」** Chip 即可直接查看与编辑整条 `UIMessage` 在数据库中的完整原始 JSON 结构（含 `parts`、`metadata`、`usage`、`modelSnapshot` 等），支持一键复制 JSON、格式化校验，并可选择「原地覆盖保存」或「保存为新分支」。

---

### 模块 4：工作区终端与子代理主从跟随 UI

1. **聊天页顶栏下方「工作区终端 & 子代理跟随胶囊栏 (`SubAgentFollowerBar`)」**
   - **文件**：`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
   - **工作区活跃终端跟随（新旧对话通用）**：
     - 只要当前对话绑定的工作区有开启的交互式 PTY 终端（哪怕刚点开空白新对话），顶栏下方即实时展示每个终端的胶囊（如 `工作区终端 (2): [🖥 #1 dev-server] [🖥 #2 test]`）。
     - 单击任意终端胶囊弹出 **`WorkspaceTerminalsPeekSheet`（工作区终端实时查看与快控小窗）**：无需离开聊天页即可查看所有已开启终端的状态（`Prompt Ready` 或正在运行的前台命令名）、实时刷新 40 行屏幕输出、直接发送命令或 `Ctrl+C`、点 `×` 单独关闭某个终端、点 `+ 新建` 开启新终端、或一键进入全屏终端页。
   - **子代理胶囊与实时窥屏小窗 (`SubAgentPeekSheet`)**：
     - 当主对话存在关联子代理时，在顶栏下方展示所有子代理胶囊（显示子代理标题、所绑定的子代理助手名称与运行动画）。
     - 单击胶囊弹出 `SubAgentPeekSheet`：展示子代理所属助手与模型名称、实时运行状态与消息流，支持在底部输入框直接给子代理发补充指令、打断并重派指令、停止子代理或一键跳转至子代理完整对话页。
     - 当处于子代理对话页时，顶栏下方显示 **「⬅ 返回主对话」** 胶囊。
2. **左侧抽屉树状缩进跟随 (`SubConversationTreeItem`)**
   - **文件**：`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt`
   - 属于当前主对话的所有子代理（包括绑定在其他助手名下的子代理）自动以树状缩进（`├─ 🤖 子代理标题` + 运行绿点）挂载在主对话条目正下方；即使点进子代理全屏页，侧边栏依然保持主对话树状展开并高亮当前选中的子代理。
3. **消息流内的子代理通信卡片**
   - **文件**：`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`、`ToolUI.kt`
   - 新增 `ConversationSendToolUI`（显示运行状态、绑定的子代理助手与模型名、工具步数与回复摘要）、`ConversationReadToolUI`、`ConversationStopToolUI`。

---

## 三、 构建说明

### 1. 环境要求
- **JDK**：OpenJDK 21
- **Android SDK**：Compile SDK 37

### 2. 编译命令
```bash
# 构建 Debug 测试包
./gradlew assembleDebug

# 构建 Release 正式包（需在根目录 local.properties 中配置签名证书信息：storeFile / storePassword / keyAlias / keyPassword）
./gradlew assembleRelease

# 运行单元测试
./gradlew test
```

---

## 四、 最新迭代更新记录 (Changelog)

### 2026-10-17 迭代更新
1. **子代理独立为专属模块 & 支持多选绑定助手 (`AssistantSubAgentPage.kt`)**：
   - 将子代理从「本地工具」移出，在「助手详情」页及聊天页底部 `+` 面板中设立独立的 **「子代理 (Sub-Agent)」** 模块。
   - 支持用复选框（`Checkbox`）勾选绑定 1 个或多个 Rikka 助手作为子代理（`subAgentIds: Set<Uuid>`），并支持在页面内直接快速新建配置了其他模型的子代理助手并自动勾选。
   - 取消“默认子代理”概念：提示词/工具定义（`conversation_send`）仅注入已绑定子代理的名称与 `assistant_id`（不注入模型名，避免换模型破坏缓存），无论绑定 1 个还是多个均统一按规范传 `assistant_id` 调用。
   - 支持通过工具 `conversation_read`（不传 `conversation_id`）或 `recent_chats` 按需查看所有已绑定子代理助手的状态（含当前实时模型与提供商）及活跃子对话状态。
2. **交互式 PTY 终端多会话、自关闭、缓存保护与 UI 实时窥屏**：
   - **PRoot 进程树精确检测 (`inspectTerminalProcessTree`)**：通过 `/proc/<pid>/stat` 会话 ID (`sid == prootPid`) 识别 PRoot 内部顶层 Shell 与前台子进程名（`foregroundCommand`），解决 PRoot 包装下提示符就绪检测与退出的精确性问题。
   - **多终端与自关闭支持**：支持 `new_session=true` + `title` 开启多个命名终端；支持在终端内直接输入 `exit` / `logout` / `Ctrl+D` (`\u0004`) 退出顶层 Shell 后自动关闭并移除该终端标签页，或通过 `kill=true` / `kill_all=true` 关闭终端。
   - **严格保护 Prompt Cache**：移除 `WorkspaceReminderTransformer` 中的动态终端列表注入，保持 `<workspace>` 系统提示词 100% 静态；AI 通过 `workspace_terminal(list_only=true)` 免审批主动获取当前工作区所有开启的终端及屏幕尾部预览。
   - **聊天页工作区终端跟随小窗 (`WorkspaceTerminalsPeekSheet`)**：在聊天页（含空白新对话）顶栏下方实时显示当前工作区已开启的终端胶囊，点击即可弹出实时屏幕预览、切换终端标签、发送命令/`Ctrl+C`、关闭或新建终端。

### 2026-10-18 迭代更新
1. **工作区工具「启用开关」与「审批开关」分离控制（`WorkspaceDetailPage.kt` / `WorkspaceEntity.kt` / `WorkspaceTools.kt` / `WorkspaceReminderTransformer.kt`）**：
   - 在工作区详情页新增独立的 **「工具启用 (`WorkspaceToolEnabledCard`)」** 卡片，支持对全部 5 个工作区工具（`workspace_read_file`、`workspace_write_file`、`workspace_edit_file`、`workspace_shell`、`workspace_terminal`）分别控制开启或关闭；已启用工具可在下方 **「工具审批」** 卡片独立控制调用前是否需要用户确认。
   - 复用 `WorkspaceEntity.toolApprovals` JSON（以 `__enabled__<toolName>` 键值存储启用状态），无需数据库 Schema 迁移。
   - 被关闭的工具自动从 `createWorkspaceTools` 及 `<workspace>` 系统提示词中移除，避免模型看到或尝试调用已禁用的工具。
2. **子代理专属「可调用工具管理面板 (`SubAgentToolsSheet`)」（`AssistantSubAgentPage.kt` / `Assistant.kt` / `ConversationConfig.kt` / `ChatService.kt`）**：
   - 子代理的名称、标签与模型完全跟随助手自身的「基础设定」，不在子代理页重复维护。
   - 点击子代理列表右侧的 `⚙` 按钮，改为直接弹出 **「子代理工具管理 (`SubAgentToolsSheet`)」** 面板，支持针对每个子代理独立管理：
     - **执行审批策略**：`子代理调用工具自动免审批 (subAgentAutoApproveTools)`（默认开启，关闭则遵循工作区/MCP 审批规则）。
     - **工作区工具细粒度开关 (`disabledWorkspaceTools`)**：独立勾选该子代理可调用的 5 项工作区工具（如配置只读子代理）。
     - **MCP 工具服务 (`mcpServers`)**、**联网搜索 / 长期记忆 / 本地工具 (`localTools`)** 以及 **技能 (`enabledSkills`)**。
3. **利用助手「标签 (`Tags`)」支持长文本描述并自动注入子代理工具定义（`ConversationTools.kt` / `TagList.kt` / `AssistantDetailPage.kt`）**：
   - 将 `TagList.kt`（`TagsInput`）的新增标签输入框改为多行文本框（`2~8` 行），并支持**直接点击已添加的标签 Chip 弹窗编辑内容**，方便直接填入长段职责说明作为子代理描述。
   - 在 `conversation_send` 工具定义中，除子代理名称和 `assistant_id` 外，自动注入该子代理的标签描述（`tags/description="..."`），引导主代理根据名称与标签描述选择最合适的子代理；`conversation_read` 与 `recent_chats` 返回的 `bound_sub_agents` 状态及子代理列表 UI 同步展示标签描述。
4. **移除聊天正文外显「从此步重发」按钮 & 升级助手消息双模式深度编辑器（`ChatMessage.kt`）**：
   - 移除助手正文块下方外显的 `🔄 从此步重发` 按钮，保持聊天界面干净整洁，单步 Roll 与重跑工具统一收口至工具详情弹窗和消息编辑面板。
   - 将助手消息菜单的 **「编辑」** 升级为统一的双模式深度编辑器（`MessagePartsEditorSheet`）：
     - **模式 1：可视化部件模式**：支持对任意 `Reasoning`、`Tool`、`Text`、`Image/Document` 部件进行内容修改、单步 `重跑工具`、`从此步 Roll`、`↑ 上移 / ↓ 下移`、`删除`、底部 `+ 新增正文 / + 新增思考 / + 新增工具块`，以及点 `⚙` 展开修改底层字段（`toolName`、`toolCallId`、`approvalState`、`reasoningType`、`metadata` JSON）。
     - **模式 2：底层 JSON 源码模式**：直接暴露整条 `UIMessage` 在数据库中的完整 Pretty JSON 结构，支持复制、格式化校验与任意底层字段修改，并可选择「原地覆盖保存」或「保存为新分支」。
5. **替换 `RikkaHub Test` 桌面图标（`app/src/main/res/mipmap-*/ic_launcher*.png`）**：
   - 将默认黑兔图标替换为粉色兔耳角色自适应图标（前景透明抠图 + 纯白背景层，适配全部 `mdpi` ~ `xxxhdpi` 分辨率与桌面遮罩）。
6. **密钥代名词自动替换与 `ask_key` 询问密钥工具（`SecretKeyManagerUI.kt` / `ExtensionsPage.kt` / `AssistantLocalToolPage.kt` / `AskKeyTool.kt` / `Settings.kt` / `GenerationLoop.kt` / `ChatMessageTools.kt`）**：
   - **入口与布局**：
     - **全局管理页（`设置 -> 扩展 -> 密钥代名词`）**：点击进入独立二级页面 `SecretKeysPage`，顶部提供 **「启用密钥代名词替换 (`settings.enableSecretKeyAlias`)」** 总开关，下方管理密钥代名词列表（支持增删改、启停、查看/隐藏真实密钥）。
     - **助手本地工具开关（`助手详情 -> 本地工具 -> 询问密钥`）**：仅在扩展中开启了「启用密钥代名词替换」后，本地工具列表才会显示 **「询问密钥 (`ask_key`)」** 开关（简介：*告知模型已有密钥名称，并允许模型询问或新建密钥*）。
   - **运行机制**：
     - 只要开启了「启用密钥代名词替换」，即使不开启或未调用 `ask_key` 工具，AI 发出的任何工具命令（如 `workspace_terminal`、`workspace_shell`、`workspace_write_file`、MCP 等）中包含已启用代名词 `key_name` 时，底层执行瞬间自动替换为真实密钥 `secretValue`，且工具输出中若出现真实密钥会自动反向还原为 `key_name`。
     - 当助手同时开启了本地工具「询问密钥 (`ask_key`)」时，工具定义中会告知模型已有密钥代名词名称，且模型可调用 `ask_key` 在聊天流中弹出交互卡片（`AskKeyToolStep`）供用户点选已有密钥或当场新建密钥。
7. **工作区 PRoot 沙箱 6 项“类 VPS”防崩增强补丁（`RootfsPatcher.kt` / `ProotShellRunner.kt` / `WorkspaceTerminalSession.kt`）**：
   - **挂载 `/dev/shm` 共享内存**：将工作区临时共享内存目录绑定挂载至 `/dev/shm`，解决 Python `multiprocessing`、`torch.DataLoader`、PostgreSQL、Chromium 等因缺少 `/dev/shm` 报错退出的问题。
   - **不可读 `/proc` 节点自动兜底（`stat` / `loadavg` / `uptime` / `vmstat`）**：当检测到宿主机对应 `/proc` 节点被 Android 权限锁死（`!canRead()`）时，自动挂载可读兜底节点，解决 `htop`、`top`、`free`、`uptime`、`psutil`、`pm2` 因 `Permission denied` 崩溃的问题。
   - **Node.js 网卡枚举与 `ip` 命令防崩（`/etc/rikkahub-node-preload.js` & `/usr/local/sbin/ip`）**：拦截 Android 11+ SELinux 禁用 Netlink `RTM_GETLINK` 引发的 `EACCES` 异常，当系统禁止枚举网卡时自动返回兜底 `lo` 与 `eth0` 网卡信息，彻底解决 Vite / Next.js / Webpack / Nuxt / PM2 调用 `os.networkInterfaces()` 直接崩溃退出的问题。
   - **`apt` / `dpkg` 防锁死与自动清缓存**：注入 `/usr/sbin/policy-rc.d`（`exit 101` 阻止装包期间调 init 启动服务卡死）、`/usr/local/sbin/setcap` 软降级包装（解决 `iputils-ping` 等因无权写扩展属性导致 `dpkg` 报错中断），以及 `/etc/apt/apt.conf.d/99rikkahub-vps`（修复 `_apt` 沙盒警告并在装包后自动清理 `.deb` 缓存防止工作区膨胀）。
   - **轻量级 `systemctl` 兼容执行器（`/usr/local/sbin/systemctl`）**：支持在无 systemd PID 1 的 PRoot 环境下执行 `systemctl start/stop/restart/status <service>`，自动调用 `/etc/init.d/` 脚本或解析 `.service` 文件的 `ExecStart` 在后台拉起守护进程。
   - **全局运行时防崩环境变量（`/etc/profile.d/rikkahub-vps.sh`）**：默认注入 ARM64 JVM 免崩参数（`JAVA_TOOL_OPTIONS="-XX:-UseCompressedOops -XX:-UseCompressedClassPointers"`）与 Chromium / Electron / Firefox 免沙箱参数。
8. **主代理对子代理的 5 级控制权限与独立审批面板（`AssistantSubAgentPage.kt` / `ConversationTools.kt` / `ChatService.kt` / `ChatPage.kt` / `ConversationList.kt`）**：
   - 在「子代理 (Sub-Agent)」页面顶部卡片新增 **「主代理对子代理的权限与审批 (`MainAgentPermissionsSheet`)」** 面板，将主代理的控制力划分为「基础必选 + 4 项可选进阶权限」，每项均支持独立的 **「启用开关」** 与 **「调用前需审批开关」**：
     0. **基础派发与获取最终回复（最低权限）**：当 4 项进阶权限全关时，主代理退化为标准黑盒单次派发模式（仅能传 `assistant_id` + `title` + `message` 同步等待完成，且只返回最终 `reply` 文本，不返回 `tool_steps`）。
     1. **窥屏与查看工具轨迹 (`subAgentAllowInspect` / `subAgentApproveInspect`)**：控制是否向主代理暴露 `conversation_read` 工具及 `conversation_send` 返回结果中的 `tool_steps` 工具执行轨迹。
     2. **多轮追问与异步并发 (`subAgentAllowMultiTurnAndAsync` / `subAgentApproveFollowUp`)**：控制是否开放 `conversation_id`（向已有子代理多轮追问）与 `wait=false`（后台异步并行）。
     3. **中途打断与强制停止 (`subAgentAllowInterruptAndStop` / `subAgentApproveInterruptAndStop`)**：控制是否开放 `interrupt=true`（打断正在生成的子代理并改派新指令）与 `conversation_stop` 工具。
     4. **删除销毁子代理对话 (`subAgentAllowDelete` / `subAgentApproveDelete`，最高权限)**：新增 `conversation_delete` 工具（默认需用户审批），允许主代理将当前主对话派生的子代理对话从数据库和关联树中彻底删除。
   - **人类手动删除子代理增强**：在聊天页顶栏「子代理窥屏小窗 (`SubAgentPeekSheet`)」右上角新增 **「删除」** 按钮，并在左侧抽屉子代理树条目（`├─ 🤖`）支持长按弹出 **「删除」** 菜单；删除后自动同步写入 `deletedChildConversationIds`，顶栏胶囊与侧边栏树状条目立即消失。
9. **UI 文案精简中文化与移除官方更新检测（`AssistantSubAgentPage.kt` / `AssistantDetailPage.kt` / `FilesPicker.kt` / `UpdateChecker.kt` / `ChatDrawer.kt` / `SettingPreferencesNotificationPage.kt`）**：
   - 将页面标题与入口统一改为 **「子代理设置」**，聊天页底部 `+` 面板改为 **「子代理」**，移除 `(Sub-Agent)`、`PTY` 等多余英文及内部工具名/参数名（如 `conversation_send`、`conversation_read`、`tool_steps` 等），采用简明中文直接描述功能。
   - 彻底禁用 `UpdateChecker` 对 `https://updates.rikka-ai.com/` 的后台更新检测请求，移除侧边栏 `UpdateCard` 卡片与通知设置页中的「显示更新提醒」选项。
10. **会话配置实时跟随助手设定 & 回退 2.5.6 经典交互与紧凑卡片样式（`ConversationConfig.kt` / `ChatService.kt` / `SettingProviderPage.kt` / `SettingMcpPage.kt` / `FilesPicker.kt` / `CardGroup.kt`）**：
   - **会话配置实时跟随助手设定**：所有对话（无论主对话还是子代理对话）均 **100% 实时跟随其所属助手（`conversation.assistantId`）的最新模型、思考强度、搜索开关、MCP、Skills 与工具权限**；当某个渠道失效时，修改对应助手的模型即可使已有主对话和子代理对话当场原地切换至新渠道继续运行。
   - **提供商与 MCP 页面回退 2.5.6**：恢复点 `+` 一键弹窗添加提供商、经典提供商详情页以及 MCP 底部弹窗配置。
   - **聊天页 `+` 面板与全局 `CardGroup` 紧凑化**：保留单层 `+` 面板与一体式紧凑圆角卡片样式。
11. **记忆设置新增「后台自动整理记忆」独立开关（`AssistantMemoryPage.kt` / `Assistant.kt` / `MemoryConsolidator.kt` / `MemoryPrompts.kt` / `GenerationLoop.kt` / `ChatService.kt`）**：
   - 在「助手详情 -> 记忆 -> 设置」的「全局记忆」下方新增 **「后台自动整理记忆 (`enableAutoMemoryConsolidation`)」** 开关（需开启「记忆」总开关后可操作，**默认关闭 `false`**）。
   - 关闭时：不自动触发对话暂停、满 6 轮、离开对话或切后台时的后台归纳请求（方便按需控制后台 Token 消耗），并动态调整 `<memory_system>` 提示词让主对话模型在对话中按需直接调用记忆工具记录重要事实，同时保留手动点击「立即整理」按钮的能力。
   - 开启时：恢复后台自动归纳整理机制。
12. **正式定名 `Rikkanon` 与 Release 正式版构建（`app/build.gradle.kts` / `SettingAboutPage.kt` / `strings.xml`）**：
   - 应用名与关于页标题统一为 **`Rikkanon`**，独立共存包名定为 **`me.rerere.rikkahub.rikkanon`**，同时产出无开发模式水印的 Release 正式版与 Debug 版安装包。

### 2026-10-19 迭代更新
1. **终端进程树状态检测 O(1) 快速路径优化（`WorkspaceTerminalSession.kt`）**：
   - 缓存每个终端会话 `prootPid -> shellPid` 映射，在常规轮询中直接按已知 PID 精确读取 `/proc/<prootPid>/stat`、`/proc/<shellPid>/stat` 与 `/proc/<fgPgid>/stat`（每次仅读 2~3 个文件），消除轮询期间反复调用 `File("/proc").listFiles()` 全盘遍历 `/proc` 目录的性能开销；对首次发现与边缘慢路径增加 250ms 节流缓存。
2. **工作区 Rootfs 补丁与 `/proc` 可读性探测幂等缓存（`RootfsPatcher.kt` / `ProotShellRunner.kt`）**：
   - 在 `RootfsPatcher` 中增加基于工作区路径与配置参数的内存幂等缓存（结合关键补丁文件存在性校验），并静态缓存宿主机不可读 `/proc` 节点列表与附加用户组 ID，避免每次调用 `workspace_shell` 启动 PRoot 前重复对十几个配置文件执行同步磁盘 I/O 检查。
3. **聊天气泡 UI 回退 2.5.6 经典样式（`ChatMessage.kt`）**：
   - 移除上游 3.0 新增的用户气泡强制左侧缩进（`.padding(start = 48.dp)`）与非对称圆角（`24.dp` / `6.dp`）及过宽内边距（`12.dp`），恢复为 2.5.6 经典的 `RoundedCornerShape(16.dp)` 四角对称圆角与 `8.dp` 紧凑内边距。
4. **严格收紧子代理与「参考最近对话」的权限边界（`ChatToolFactory.kt` / `ConversationTools.kt` / `ChatService.kt` / `Conversation.kt`）**：
   - **隔离 `recent_chats` 与 `conversation_search`**：移除工作区触发对话工具的冗余条件，仅当用户在「记忆 -> 参考最近对话 (`enableRecentChatsReference`)」中显式开启时才暴露 `recent_chats` 与 `conversation_search`（且 `recent_chats` 自动过滤掉 `parentConversationId != null` 的子代理会话）。
   - **基于 `config.parentConversationId` 的子代理强制归属校验 (`requireOwnedSubAgentConversation`)**：在 `conversation_read`（未开启参考最近对话时）、`conversation_send`（追问已有子代理时）、`conversation_stop`、`conversation_delete` 以及 `ChatService.sendToSubConversation` 中，强制验证目标 `conversation_id` 的 `config.parentConversationId == currentConversationId`，严禁主代理越权读取、停止、删除或向任何普通用户对话及其他主对话的子代理发送消息。
   - **收敛 `Conversation.allChildConversationIds`**：仅扫描 `conversation_send` 实际创建/派发的子代理 ID，不再将 `conversation_read` 读取过的普通会话误纳入子代理集合。
5. **修复侧边栏历史对话标题半行截断 `…` 与历史子代理条目外溢（`ConversationList.kt` / `ConversationDAO.kt` / `ConversationRepository.kt`）**：
   - 移除 `ConversationItem` 中多余的 `Spacer(Modifier.weight(1f))`，让标题 `Text(Modifier.weight(1f))` 占满整行宽度，解决所有历史对话标题在 6 个汉字处被平分宽度截断为 `…` 的问题。
   - 在分页摘要实体 `LightConversationEntity` 中补齐 `config` 字段解析，使侧边栏列表中的历史子代理（`config.parentConversationId != null`）始终严格归属在对应父对话树下折叠/展开，不再平铺混入正常历史列表。
6. **同步上游主分支 `3.1.0` 升级工作区内置 PRoot 引擎至 `5.1.107.96`（`workspace/src/main/jniLibs/*/libproot_*.so`）**：
   - 根据上游 RikkaHub 主分支 `3.1.0`（commit `bf8ff5a`）同步更新，将 `arm64-v8a` 与 `x86_64` 的 `libproot_exec.so` 及 `libproot_loader.so` 从 `5.1.107.92` 升级至 `5.1.107.96`，修复 Ubuntu 26.04 等新版 Rootfs 中 `rust-coreutils` 在 `--link2symlink` (L2S) 模式下因 `AT_EXECFN` 路径不匹配报 `Security violation` 导致基础命令不可用的问题。
7. **子代理并发限制池 (`subAgentMaxConcurrency`)、单轮工具调用步数限制 (`subAgentMaxSteps` / `max_steps`)、批量等待与防空壳加固（`AssistantSubAgentPage.kt` / `Assistant.kt` / `ConversationConfig.kt` / `ChatService.kt` / `ConversationTools.kt` / `GenerationLoop.kt`）**：
   - **子代理并发限制（默认 `2`，最低 `1`）**：在「子代理设置」顶部总面板（开启子代理功能后显示）新增 **「子代理并发限制 (`subAgentMaxConcurrency`)」** 设置；底层通过动态并发池控制同一主对话下同时请求模型的子代理上限，超出上限的子代理任务自动在队列中平滑等待（排队等待时间不计入 `wait=true` 的执行超时），避免高并发瞬间击穿上游接口限流。
   - **单轮工具调用次数限制（`0` = 不设限）**：
     - 在每个子代理的「子代理工具管理 (`SubAgentToolsSheet`)」面板中新增 **「工具调用次数限制 (`subAgentMaxSteps`)」**，作为该子代理未传参时的默认单轮步数上限（`0` 表示不设限）。
     - 在 `conversation_send` 工具中新增可选整数参数 `max_steps`（`0` 表示不设限，`>0` 表示单轮最多连续调用工具步数）：若主代理调用时显式传参，则以主代理参数为准；若未传参，则取该子代理工具管理面板中的默认设置。每次追加新消息按单轮独立重新计数，到达步数上限时自动停止继续调用工具并强制输出最终回复交卷。
   - **消灭异步空壳会话与并发竞态 + 429/503 限流退避重试**：
     - 子代理创建瞬间立即持久化落库并持有会话引用计数，杜绝 5 秒空闲内存卸载后触发默认助手空白会话的 Bug；子代理请求失败时自动将错误详情写入子对话消息记录；`registerChildConversation` 增加 `Mutex` 锁保护。
     - `GenerationLoop` 将 HTTP `429`、`502`、`503`、`504`、`529` 等限流/过载错误纳入指数退避自动重试。
   - **`conversation_read` 支持批量等待 (`conversation_ids`)**：支持一次传入多个子代理 ID（或不传 ID 配合 `wait_sec > 0` 等待全部运行中的子代理完成），1 次调用即可收齐多个异步子代理的状态与回复。
8. **子代理局部编号路由 (`sub_1` / `sub_2`) 与主对话分支 (Fork) 自动克隆独立子代理（`ConversationConfig.kt` / `Conversation.kt` / `ChatService.kt` / `ConversationTools.kt`）**：
   - **主代理全链路统一 `sub_N` 编号与严格并发顺序分配**：`conversation_send`、`conversation_read`、`conversation_stop`、`conversation_delete` 的入参与返回值全程统一使用当前主对话内的局部编号（如 `sub_1`、`sub_2`）；通过 `CoroutineStart.UNDISPATCHED` 与 `reserveChildConversationIdImmediate` 在内存同步预订编号，确保同一轮并发派发多个子代理时 `sub_1`、`sub_2` 严格按模型调用顺序分配。
   - **主对话分支（Fork）自动深拷贝独立子代理**：当对含有子代理的主对话执行分支（Fork）时，自动将关联子代理深拷贝一份全新的独立子对话副本（将其 `parentConversationId` 指向新分支主对话），并在新分支主对话的 `subAgentIdMap` 中将 `sub_1`（及兼容旧历史 ID）映射到新克隆的子代理副本。既无需改动历史消息上下文（100% 保护 Prompt Cache），又保证原主对话与分支主对话的子代理完全隔离，且从分支子代理点击「⬅ 返回主对话」100% 准确返回该分支主对话。
9. **`bound_sub_agents` 工具与工作区能力摘要 & 后台子代理完成自动通知唤醒（`ConversationTools.kt` / `ChatService.kt` / `ChatMessageContextCheckpoint.kt` / `ChatMessage.kt`）**：
   - **`bound_sub_agents` 补充能力摘要**：`conversation_read()` 返回的 `bound_sub_agents` 列表中新增 `share_workspace`、`tools`（根据工作区启用状态与该子代理 `disabledWorkspaceTools` 动态计算出的真实可用工具名称列表）、`skills` 与 `mcp_servers`，方便主代理直接按工具能力挑选合适的子代理。
   - **后台子代理完成自动通知与唤醒状态机**：
     - 当子代理因 `wait=false` 或 `wait=true` 超时转入后台运行时，自动挂载后台完成监听；若主代理中途调 `conversation_read` 时子代理仍在运行（`status == "running"`），保留待通知状态；仅当读取到已完成状态（`status != "running"`）时才标记结果已消费。
     - 当后台子代理执行完毕且未被主动消费时：若主代理正在运行工具循环，通过 `pollQueuedUserMessages`（Steer 通道）在下一步立即注入完成报告；若主代理已停下等待，待当前所有运行中的后台子代理全部完成后合并发送一条完成通知自动唤醒主代理（UI 渲染为紧凑可折叠的 `ChatMessageSubAgentNotification` 卡片）。

### 2026-10-22 迭代更新
1. **子代理工具管理面板重构：移除本地工具 + 逐工具双轨审批（`AssistantSubAgentPage.kt` / `Assistant.kt` / `ChatToolFactory.kt`）**：
   - **移除子代理本地工具**：在 `SubAgentToolsSheet` 与 `ChatToolFactory.createTools`（`isSubConversation == true`）中彻底移除子代理无需使用的本地工具（JavaScript、时间、剪贴板、TTS、屏幕时间、日历、图表等）。
   - **将统一免审批开关细化为上方开启工具 + 下方逐工具配置「用户审批」与「主代理审批」**：
     - `Assistant` 新增 `subAgentUserApprovalTools: Set<String>` 与 `subAgentAgentApprovalTools: Set<String>`。
     - 上方开启任意工具（工作区 5 项工具、MCP 服务、联网搜索、长期记忆、技能）后，下方「工具调用审批」列表自动展示对应项，每一项独立提供 **`[用户]`** 与 **`[主代理]`** 两个审批开关（默认均不勾选即免审批直接执行）。
     - **只开「主代理审批」**：人类在 UI（含子代理完整页、聊天页顶栏胶囊与窥屏小窗）完全看不到审批弹窗打扰，子代理触发工具时进入 `waiting_approval` 并立即通过强插/唤醒通知主代理审批。
     - **只开「用户审批」**：仅人类可在 UI（含顶栏窥屏小窗 `SubAgentPeekSheet` 内置的快捷批准/拒绝卡片或子代理完整对话页）点击审批，等待人类审批期间不发送中断通知打扰主代理，待人类审批后子代理继续跑完再通知主代理。
     - **「用户审批」+「主代理审批」双开**：双方均可审批，内置幂等防冲突保护。
2. **新增主代理审批工具 `conversation_approve`（幂等防冲突）与全中断态通知机制（`ConversationTools.kt` / `ChatService.kt` / `ChatMessageContextCheckpoint.kt` / `BuiltinToolUIs.kt`）**：
   - **新增 `conversation_approve` 工具**：允许主代理对处于 `waiting_approval` 的子代理工具执行批准或拒绝并自动恢复子代理运行；若双开模式下人类用户已在 UI 抢先点击批准，主代理调用时返回 `status = "already_handled"` 幂等成功结果而不报错。
   - **子代理全中断态强插 / 闲时唤醒通知**：
     - **`waiting_approval`（待主代理审批中断）**：附带 `tool_call_id`、`tool`、`input` 详情立即通知主代理（闲时无需等待其他运行中的子代理，立即唤醒主代理处理）。
     - **`interrupted_by_user`（被人类用户手动停止中断）**：区分主代理主动调用 `conversation_stop` 与人类用户在 UI 点击「停止」；人类手动停止时不再静默丢弃监听，而是携带停止前已产出的半截回复与工具轨迹通知主代理。
     - **`failed`（API / 网络异常中断）**：修复 `sendQueuedMessage` 覆盖真实异常的问题，透传真实报错原因与报错前步骤并以 `status = "failed"` 通知主代理。
   - **折叠通知条 UI 状态自适应**：`ChatMessageSubAgentNotification` 根据通知状态自动切换标题与配色（`子代理请求工具审批` / `子代理后台任务已中断/异常` / `子代理后台任务已完成`）。
3. **Claude 与 Gemini Provider 相邻 `user` 消息自动合并（`ClaudeProvider.kt` / `GoogleProvider.kt`）**：
   - 在 UI 与数据库层保持每条 `UIMessage(role = MessageRole.USER)`（含强插的子代理折叠通知条）为独立节点；在构造发往 Claude / Gemini API 的请求数组时，自动合并相邻同角色（如 `tool_result` / `functionResponse` 后紧跟 Steer 注入的子代理通知或连续两条用户消息）的内容块，严守 API 角色交替规范。

### 2026-10-23 迭代更新（全链路静态审计加固与零开销性能优化）
1. **修复子代理待审批期间空闲回收后审批覆盖空会话 & 双轨审批并发锁保护（`ChatService.kt` / `ConversationSession.kt`）**：
   - 在 `ConversationSession` 实例内部新增 `internal val approvalMutex = Mutex()`（随 Session 空闲卸载自动 GC 回收，不在 `ChatService` 留全局 Map）。
   - 在主代理审批入口 `approveSubConversationTool` 与人类审批入口 `handleToolApproval` 中统一通过 `session.approvalMutex.withLock` 串行化，并在修改消息树前强制调用 `session.acquire()` + `session.initialize { loadConversation(targetConversationId) ?: targetConv }`，彻底杜绝子代理因 5 秒空闲卸载后重建空 Session 覆盖数据库历史记录的问题，并消除人机同时审批或同轮并发 `conversation_approve` 的状态覆盖竞态。
2. **修复后台监听 `registerBackgroundSubAgentWatch` 取消异常导致的协程忙循环（`ChatService.kt`）**：
   - 在 `watchJob` 内部 `replyDeferred.await()` 与 `activeJob.join()` 的 `catch (CancellationException)` 分支及循环入口显式调用 `currentCoroutineContext().ensureActive()`，区分“被监听的子代理任务取消”与“watcher 自身被 `markSubAgentResultConsumed` 取消”，防止 watcher 取消后在 `Dispatchers.Main` 上形成非挂起死循环。
3. **补齐 Steer 插话消费队列消息后的 `reply` Deferred 完成回调与引用计数释放（`ChatService.kt`）**：
   - 在 `handleMessageComplete` 的 `pollQueuedUserMessages` 中收集所有被 Steer 取走的 `QueuedMessage.reply` 至 `steeredReplyDeferreds`，并在当前生成流 `onCompletion` 结束或异常时统一触发 `complete(latestReply)` / `completeExceptionally(cause)`（对 `answer = false` 的队列消息同步写入会话并立即完成），确保子代理追问或语音队列被 Steer 合并后调用方能正常收到回复并释放 `targetSession.release()` 引用计数。
4. **收紧子代理归属校验、绑定助手执行层校验与 FTS 全文检索隔离提速（`ConversationTools.kt` / `Conversation.kt` / `ChatService.kt` / `MessageFtsManager.kt`）**：
   - 移除 `Conversation.allChildConversationIds` 对不可信 `tool.input` 的扫描（仅保留 `config.childConversationIds`、`subAgentIdMap` 及无报错的 `conversation_send` 输出），并在 `isOwnedSubAgentConversation` 中直接校验配置集合，防止通过工具入参伪造 UUID 越权访问其他主对话的子代理。
   - 在 `ChatService.sendToSubConversation` 新增执行层校验，强制验证新建子对话传入的 `targetAssistantId` 必须属于主助手已勾选绑定的 `effectiveSubAgentIds`。
   - **FTS 写入跳过子代理 + 查询单条 SQL 过滤（`MessageFtsManager.kt`）**：在 `indexConversation` 中跳过 `conversation.config?.parentConversationId != null` 的子代理临时会话（省去子代理高频工具调用时的结巴分词与 SQLite FTS 索引写入开销），并在 `search` 的 `EXISTS` 子查询中直接过滤掉 `parentConversationId` 非空的记录，避免 N+1 查询并防止 `conversation_search` 泄露其他主对话的子代理片段。
5. **修复技能审批规则工具名匹配、主对话审批按钮误隐藏与能力摘要同步（`Assistant.kt` / `ChatMessage.kt` / `ChatList.kt` / `ConversationTools.kt` / `ConversationConfig.kt` / `ConversationConfigTest.kt`）**：
   - `Assistant.matchesSubAgentApprovalRule` 同时匹配 `use_skill` 与 `load_skill`，并兼容旧版 `subAgentAutoApproveTools = false` 配置。
   - `ChatMessage` 新增 `isSubAgentConversation` 参数，仅在子代理会话中才按「仅主代理审批」规则隐藏人类审批按钮，防止同一助手用于普通主对话时误隐藏批准/拒绝入口。
   - `Assistant.withConversation` 在子代理配置了 `config.workspaceId`（含共享主对话工作区）时无条件优先应用该工作区，消除子助手自带默认工作区时的优先级不一致；同时移除 `buildBoundSubAgentsStatusJson` 中已废弃的本地工具能力列表，并同步更新 `ConversationConfigTest.kt` 单元测试断言。
   - `ConversationConfig` 新增单调递增 `nextSubAgentIndex` 计数器并移除 `resolveRealSubAgentId` 的列表下标兜底，防止删除子代理后复用旧 `sub_N` 编号或发生序号漂移。
   - `GenerationLoop` 放开 `maxSteps > 255` 的自定义步数上限，并在工具审批恢复执行时扣除当前消息已完成的工具轮数。
6. **密钥代名词异常路径脱敏、Rootfs 写入路径规范化与终端满缓冲 O(1) 尾锚点匹配（`GenerationLoop.kt` / `ChatService.kt` / `WorkspaceTools.kt` / `WorkspaceTerminalSessionManager.kt`）**：
   - 在 `GenerationLoop` 工具执行异常分支及 `ChatService` 生成失败分支中统一接入 `settings.redactSecretKeyValues(...)`，防止命令非零退出回显异常泄露真实密钥。
   - 在 `WorkspaceTools.kt` 新增 `normalizeRootfsAbsolutePath()` 消解 `.` 与 `..`，阻断通过 `/workspace/../etc` 绕过可写安全区免审批检查。
   - 在 `WorkspaceTerminalSessionManager` 新增 512 字符轻量尾部锚点 `tabTailAnchors` 与 `extractIncrementalTranscript`：常规未淘汰路径走 `String.regionMatches` O(1) 快速校验（零大字符串常驻、零行切分），仅在满 2000 行滚动淘汰旧行时回退至多行尾锚点查找，解决满缓冲后增量输出丢失的问题。
7. **彻底修复进入长对话后手指上拉突然跳回底部的回跳缺陷（`app/build.gradle.kts` `ComposeLazyScrollBackFixVisitorFactory`）**：
   - **根因定位**：底层依赖 `androidx.compose.foundation:foundation-android:1.13.0-alpha01` 的 `LazyList`（及同源的 `LazyGrid`、`LazyStaggeredGrid`）在进入长对话首次定位到底部 `ScrollBottomKey`（5dp Spacer）时，会产生一次性的测量回滚补偿 `scrollBackAmount`。而后续手指拖动走免测量快路径 `copyWithScrollDeltaWithoutRemeasure` 时，错误地原样保留了旧的 `scrollBackAmount`，并在 `applyMeasureResult` 中每帧重复调用 `updateScrollDeltaForApproach` 将其累加到 `_lazyLayoutScrollDeltaBetweenPasses` 中。当拖动约 446px 使尾部 Spacer 离开视口、首次触发完整重测量（`forceRemeasure`）时，Approach 布局瞬间消费累加的巨额负向差值退回页面底部，再经由 spring 动画追回当前位置。
   - **三层正交防御补丁（AGP `AsmClassVisitorFactory` 编译期无分支字节码织入）**：
     1. **防线 A（数据源头净化）**：在 `LazyListMeasureResult` / `LazyGridMeasureResult` / `LazyStaggeredGridMeasureResult` 的 `copyWithScrollDeltaWithoutRemeasure(int, boolean)` 中，将复制读取的 `this.scrollBackAmount` 替换为 `0.0f`（`POP` + `FCONST_0`），保证免测量平移结果不携带历史回滚脉冲。
     2. **防线 B（消费端免测量守卫）**：在 `LazyListState` / `LazyGridState` / `LazyStaggeredGridState` 的 `applyMeasureResult$foundation` 读取 `result.getScrollBackAmount()` 后，无分支织入 `scrollBackAmount * (1 - visibleItemsStayedTheSame).toFloat()`，在 `visibleItemsStayedTheSame == true` 的快路径中强制将传入 `updateScrollDeltaForApproach` 的增量归零，同时完整保留真实重测量时的合法高度动画补偿。
     3. **防线 C（瞬移生命周期清理）**：在 `snapToItemIndexInternal$foundation` / `snapToItemInternal$foundation` 因位置变化调用 `itemAnimator.reset()` 时，同步调用 `_lazyLayoutScrollDeltaBetweenPasses.stop$foundation()` 清理瞬移前残留的两阶段滚动差值动画。

### 2026-10-25 迭代更新（v1.1.0）
1. **渠道模型多选批量删除（`SettingProviderDetailPage.kt` / `ItemActionMenu.kt` / `ReorderableDrag.kt`）**：
   - **进入方式**：在提供商详情「模型」页，**长按模型卡片右侧 `⋮`** 进入多选并默认勾选该模型（`combinedClickable` 自带长按震动）；单击 `⋮` 仍弹出原「删除」菜单。
   - **`⋮` 排除在拖拽范围外，卡片其余区域仍可长按拖拽**：`ModelCard` 外层包一层 `Box`，`OutlinedCard` 作为下层承载长按拖拽手势（`cardModifier = longPressReorderHandle(...)`），`⋮` / 复选框作为兄弟节点叠放在上层（`Alignment.CenterEnd` + `padding(end = 16.dp)`，卡片 `Row` 末尾用 48dp `Spacer` 占位）。Compose 重叠兄弟节点默认不共享指针事件（`sharePointerInputWithSiblings() = false`），因此按在 `⋮` 上时下层卡片收不到事件，不会同时触发拖拽；拖拽缩放 `draggingScale` 加在外层 `Box` 上，`⋮` 随卡片一起缩放。
   - **多选态**：`⋮` 位置替换为 `Checkbox`；点卡片或复选框切换勾选，选中卡片显示 2dp `primary` 边框；拖拽关闭（`longPressReorderHandle(enabled = !selectionMode)`）；底部 `HorizontalFloatingToolbar` 内容由 `AddModelButton`（拉取模型 + 添加新模型）整体切换为 `✕ | 已选 N 个 | 全选/取消全选 | 🗑 删除`；系统返回键退出多选（`BackHandler`）。
   - **删除**：点删除弹出 `RikkaConfirmDialog`（「确定删除选中的 N 个模型吗？」），确认后通过一次 `copyProvider(models = models.filterNot { it.id in selectedIds })` 一次性写回（不可循环调用 `delModel`：每次都基于同一份旧 `providerSetting`，只会删掉最后一个），随后退出多选。
   - **公共组件改动（向后兼容）**：
     - `ItemActionMenu` 新增可选参数 `onLongClick: (() -> Unit)? = null`；不传时仍为原 `IconButton`，其余 18 处调用不受影响；传入时改用 `minimumInteractiveComponentSize().size(40.dp).clip(CircleShape).combinedClickable(...)`（Material3 1.5.0-alpha29 的 `IconButton` 无长按参数）。
     - `ReorderableDrag.kt` 新增 `ReorderableCollectionItemScope.longPressReorderHandle(enabled)`（仅长按拖拽手势，不带缩放），并将 `Modifier.draggingScale(isDragging)` 改为公开，用于缩放与手势需放在不同节点的场景；原 `longPressReorder` 未改动。
2. **版本号改为 `1.1.0`（`app/build.gradle.kts`）**：
   - 版本号由原跟随上游的 `2.5.6-rikkanon` 改为独立版本号 `1.1.0`（因此数字比旧版小，可直接覆盖安装旧版）。
   - 安装包文件名改为带版本号的 `Rikkanon_v1.1.0_Release_*.apk` / `Rikkanon_v1.1.0_Debug_*.apk`。

---

## 五、 历史疑难问题排查与解决归档

### 聊天页进入长对话后首次连续上拉跳回底部（已于 2026-10-23 彻底修复）
- **现象**：切换进入有长历史的对话后，从底部用手指按住往上拉，当拖动使底部 `ScrollBottomKey` Spacer 离开可视区域时，画面会突然闪回刚进入页面时的最底部位置，随后又弹回手指所在位置。
- **解决方案**：已通过 `app/build.gradle.kts` 中的 `ComposeLazyScrollBackFixVisitorFactory` 编译期字节码三层防御补丁彻底解决（详见上文 `2026-10-23 迭代更新` 第 7 条）。











