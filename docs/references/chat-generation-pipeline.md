# 消息生成链路文档

本文档描述从用户发送消息到 AI 回复与连续工具调用完成的完整数据流、核心类职责及扩展介入点。

## 核心类概览

| 类 | 职责 |
|---|---|
| `ChatService` | 编排入口，管理活跃会话、中途插话（Steer）、审批恢复、子代理唤醒与增量落库 |
| `ConversationSession` | 单个会话的运行时状态容器（引用计数、生成 `Job`、待注入插话队列、中断收尾） |
| `GenerationLoop` | 核心多步生成引擎（`Flow<GenerationChunk>`），驱动请求组装、流式解码与并发工具执行 |
| `PromptInjectionTransformer` / `LorebookScanEngine` | 高级提示词预设轨道编排、JSON 块直通、`@D` 深度注入与世界书增量扫描 |
| `SecretKeySanitizer` | 密钥代名词出入站安全阀门（外部工具明文展开、内部工具隔离、多编码与拆字节探测脱敏） |
| `PluginBridge` / `PluginInterceptor` | JS 插件工具桥接（含 `ctx.progress` / `ctx.askUser` 与暂停计时）及分层请求/响应 Hook |

---

## 完整生成链路

```text
用户发送消息（或 Steer 免暂停插话）
    │
    ▼
ChatService.sendMessage()
    ├── [若当前正在执行工具且支持 Steer] 入队待注入插话，下一 Step 自动合入上下文
    ├── 否则取消上一个 Job（cancel + join）并收尾未完成部件（finalizeInterruptedParts）
    ├── 用户文本预处理：input.beforeSave 插件 Hook + 助手正则（scope=USER）
    ├── 追加 UIMessage(USER) 到 Conversation.messageNodes 并保存初始检查点
    └── 启动 GenerationLoop.generateText()  ← Flow<GenerationChunk>
            │
            ├─ Step 循环（支持单轮步数上限与连续工具链）
            │
            ├─ 1. 组装请求消息与参数
            │       ├── chat.history 插件 Hook（纯真实对话历史）
            │       ├── limitContext() 上下文窗口裁剪
            │       ├── 世界书扫描（LorebookScanEngine 链式哈希增量扫描 + lorebook.activated Hook）
            │       ├── 高级提示词预设轨道组装（preset.beforeAssemble Hook + @D 注入 + JSON 块直通）
            │       ├── prompt.assembled 插件 Hook
            │       ├── InputMessageTransformer 管道（宏展开、文档转文本、OCR、工作区提示、请求期正则）
            │       └── messages.beforeSend & params.beforeSend 插件 Hook
            │
            ├─ 2. 调用 Provider（经 PluginInterceptor 原始 HTTP/SSE 中间件）
            │       ├── 流式分片 → onStream 插件流处理器 → StreamChunkHandler 合并进 UIMessage
            │       ├── OutputTransformers.transforms() + response.visual Hook + visualTransforms()
            │       └── 单步输出完毕 → onGenerationFinish() + response.finish Hook
            │
            ├─ 3. 检查待执行工具调用（ToolCall）
            │       ├── 无未执行工具且无 Steer 插话 → 结束本轮循环
            │       ├── 需人工或主代理审批（approval.decide Hook / 双轨审批策略）
            │       │       → 标记 Pending，发出 checkpoint = true 增量落库（若为子代理则通知主代理）并挂起等待审批
            │       └── 免审批或已批准 → 进入并发工具执行阶段
            │
            └─ 4. 并发工具执行与单步检查点落库
                    ├── Step 启动边界发出 GenerationChunk.Messages(checkpoint = true) 增量写入尾部节点
                    ├── 同批工具 async 并发执行（通过 Channel.CONFLATED liveUpdates 安全转发 ctx.progress / ctx.askUser）
                    │       ├── tool.before 插件 Hook
                    │       ├── SecretKeySanitizer 入站代名词展开（仅限外部/工作区工具，排除 conversation_* / memory_* / ask_*）
                    │       ├── 执行工具（等待 ctx.askUser 期间通过 PluginCallPause 暂停插件调用超时计时）
                    │       ├── SecretKeySanitizer 出站逐字段解包脱敏（含拆字节/编码探测抹除与零泄密格式诊断）
                    │       ├── 超 32KB 输出截断并转存 filesDir/tool_outputs/{toolCallId}.txt（search_web 除外）
                    │       └── tool.after 插件 Hook
                    ├── 写回工具结果（内联在 ASSISTANT 消息 parts 中）并发出 checkpoint = true 单事务增量更新尾部节点
                    ├── 若工具均声明 endTurn = true 且无 Steer 插话 → 结束本轮循环
                    └── 合入期间收到的 Steer 用户插话 → 继续下一 Step

    ▼
onCompletion（正常完成 / 手动停止 / 异常中断）
    ├── 清理未决的 PluginToolLiveAskRegistry 提问与后台通知
    ├── finalizeInterruptedParts()：仅对末尾未执行完的工具补写 cancelled/interrupted 或剥离流式残片（绝不连坐删除整条已完成前置步骤的消息）
    ├── 兜底转存残留 data: Base64 图片部件（避免落库校验失败）并执行最终 saveConversation()
    └── 按设置触发异步标题生成（titleModel）、建议词生成（suggestionModel）与可选记忆整理
```

---

## 阶段一：InputMessage 变换与高级提示词组装

1. **高级提示词预设与世界书（`PromptInjectionTransformer` & `LorebookScanEngine`）**：
   - 开启高级提示词模式时，系统提示词、长期记忆、工作区/工具提示、世界书（前后置、`@D` 深度、`{{outlet::}}` 锚点）与对话历史作为平权模块按预设轨道排列；
   - 自定义 JSON 模式块保持 `rawJson` 与 `isSynthetic = true` 独立边界，Provider 层仅合并真实对话历史的相邻同角色消息，保护显式 `cache_control` 缓存断点。
2. **常规 InputTransformers**：
   - `TimeReminderTransformer`、`PlaceholderTransformer`（含插件 `registerMacro` / `registerPromptBlock` 展开）、`DocumentAsPromptTransformer`、`OcrTransformer`、`TemplateTransformer`、`WorkspaceReminderTransformer`，以及「仅修改发出」阶段的深度过滤正则。

---

## 阶段二：OutputMessage 变换管道

| 时机 | 方法 | 说明 |
|---|---|---|
| 流式分片到达时（存储态） | `transforms()` | 实时变换内部消息（如请求/输出正则处理） |
| 流式分片到达时（显示态） | `visualTransforms()` | 仅影响 UI 渲染、不改数据库原文（如 `<think>` 提取、「仅修改显示」正则、插件 `response.visual`） |
| 单步生成结束时 | `onGenerationFinish()` | 最终落盘处理（如 `Base64ImageToLocalFileTransformer`、「直接修改正文」单次落库正则、插件 `response.finish`） |

---

## 阶段三：工具系统与双轨审批

### 工具来源（`ChatToolFactory` & `PluginBridge`）
1. **联网搜索工具**（`SearchTools`：`search_web`、`scrape_web`）
2. **本地工具**（`LocalTools`：JS 计算、时间、剪贴板、TTS、`ask_user`、`ask_key` 密钥询问、屏幕时间等）
3. **对话与子代理工具**（`ConversationTools`：历史对话检索，以及子代理 0~4 级派发/查看/追问/打断/审批/销毁工具 `conversation_*`）
4. **工作区工具**（`WorkspaceTools`：`read_file`、`write_file`、`edit_file`、`shell`、持久化交互式多终端 `terminal`，支持「启用」与「审批」分离配置）
5. **Agent Skills 工具**（`SkillsTools`，含内置、本地与插件声明的 Skills）
6. **MCP 工具**（`mcp__{serverName}__{toolName}`）
7. **插件注册工具**（`PluginBridge.createTools`，支持动态 `parameters(ctx)` / `systemPrompt(ctx)`、`display` 媒体/气泡渲染、`endTurn` 与中途挂起提问 `ctx.askUser`）
8. **长期记忆工具**（`MemoryTools`）

### 双轨审批与增量持久化
- 主对话工具支持免审批或人类用户 UI 审批；子代理工具支持逐工具配置 **`[用户]`** 与 **`[主代理]`** 双轨审批（主代理可通过 `conversation_approve` 审批子代理挂起工具）。
- **单步增量落库（`ConversationRepository.checkpointConversation`）**：每次工具启动与完成时在单事务内仅 `REPLACE` 尾部脏 `MessageNode` 并更新会话 `update_at`；配合 `MessageNodeDAO` 针对超 2MB 节点的 256K `substr` 分片流式读取，确保超长多步工具链既不触发 `SQLiteBlobTooBigException`，也不会因进程被杀丢失已跑完的步骤。

---

## 关键文件路径

```text
app/src/main/java/me/rerere/rikkahub/
├── service/
│   ├── ChatService.kt                    # 会话编排、Steer 插话、子代理联动与保存入口
│   └── ConversationSession.kt            # 会话状态容器与中断收尾
├── data/
│   ├── ai/
│   │   ├── GenerationLoop.kt             # 多步生成主循环与并发工具执行
│   │   ├── transformers/
│   │   │   ├── Transformer.kt
│   │   │   ├── PromptInjectionTransformer.kt
│   │   │   ├── LorebookScanEngine.kt
│   │   │   └── ...
│   │   └── tools/
│   │       ├── ChatToolFactory.kt
│   │       ├── ConversationTools.kt
│   │       ├── WorkspaceTools.kt
│   │       ├── SearchTools.kt
│   │       ├── SkillsTools.kt
│   │       ├── MemoryTools.kt
│   │       └── local/
│   ├── secret/
│   │   └── SecretKeySanitizer.kt         # 密钥代名词出入站阀门与防拆字节脱敏
│   ├── plugin/
│   │   └── PluginBridge.kt               # 插件工具、Hook 与流处理器桥接
│   └── repository/
│       └── ConversationRepository.kt     # 单步 checkpoint 落库、冷启动恢复与超 2MB 分片读取
```
