package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.ViewOffSlash
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ASK_KEY_TOOL_NAME
import me.rerere.rikkahub.data.datastore.SecretKeyEntry
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.ToolEditAction
import me.rerere.rikkahub.ui.components.message.tools.DefaultToolPreview
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ui.components.DotLoading
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val ASK_USER_TOOL_NAME = "ask_user"

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    assistant: Assistant? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onEditToolCall: ((toolCallId: String, newInput: String, newOutputText: String?, action: ToolEditAction) -> Unit)? = null,
    onRegenerateFromHere: (() -> Unit)? = null,
    onRerunTool: (() -> Unit)? = null,
) {
    // ask_user / ask_key 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }
    if (tool.toolName == ASK_KEY_TOOL_NAME) {
        AskKeyToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val outputJson = remember(tool) {
        if (tool.isExecuted) {
            runCatching {
                JsonInstant.parseToJsonElement(
                    tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                )
            }.getOrNull()
        } else {
            null
        }
    }
    // 输出不是 JSON (例如超长被截断后只剩文本预览) 时, 定制渲染器读不到任何字段, 详情改用默认渲染展示原文
    val outputUnparsable = tool.isExecuted && outputJson == null
    val context = remember(tool, loading, assistant) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) outputJson ?: JsonObject(emptyMap()) else null,
            loading = loading,
            assistant = assistant,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var editingInSheet by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.isPending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        onClick = {
            editingInSheet = false
            showResult = true
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 顶部操作栏：支持直接编辑工具入参/结果、单工具重跑、以及从此工具步骤处单步 Roll
                    if (onEditToolCall != null || onRegenerateFromHere != null || onRerunTool != null) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (onEditToolCall != null) {
                                FilterChip(
                                    selected = editingInSheet,
                                    onClick = { editingInSheet = !editingInSheet },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = HugeIcons.Edit01,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    label = { Text(if (editingInSheet) "返回预览" else "编辑工具调用/结果") },
                                )
                            }
                            if (onRerunTool != null && !loading) {
                                FilledTonalButton(
                                    onClick = {
                                        showResult = false
                                        onRerunTool()
                                    },
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Refresh03,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "重跑此工具",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            if (onRegenerateFromHere != null && !loading) {
                                TextButton(
                                    onClick = {
                                        showResult = false
                                        onRegenerateFromHere()
                                    },
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Refresh03,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "从此步 Roll",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (editingInSheet && onEditToolCall != null) {
                        ToolCallEditorContent(
                            tool = tool,
                            onSubmit = { newInput, newOutput, action ->
                                showResult = false
                                onEditToolCall(tool.toolCallId, newInput, newOutput, action)
                            },
                        )
                    } else if (outputUnparsable) {
                        DefaultToolPreview(context = context)
                    } else {
                        renderer.Preview(
                            context = context,
                            onDismissRequest = { showResult = false },
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCallEditorContent(
    tool: UIMessagePart.Tool,
    onSubmit: (newInput: String, newOutputText: String?, action: ToolEditAction) -> Unit,
) {
    val initialOutputText = remember(tool) {
        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }
    var inputText by remember(tool.toolCallId) { mutableStateOf(tool.input) }
    var outputText by remember(tool.toolCallId) { mutableStateOf(initialOutputText) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "编辑工具: ${tool.toolName}",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("工具调用入参 (input JSON)") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 10,
        )
        OutlinedTextField(
            value = outputText,
            onValueChange = { outputText = it },
            label = { Text("工具返回结果 (output 文本)") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 14,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    onSubmit(inputText, outputText, ToolEditAction.SAVE_ONLY)
                }
            ) {
                Text("仅保存修改")
            }
            FilledTonalButton(
                onClick = {
                    onSubmit(inputText, null, ToolEditAction.RERUN_TOOL_AND_CONTINUE)
                }
            ) {
                Text("按新入参重跑此工具并继续")
            }
            FilledTonalButton(
                onClick = {
                    onSubmit(inputText, outputText, ToolEditAction.CONTINUE_FROM_EDITED_OUTPUT)
                }
            ) {
                Text("按新结果重生成后续")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            if (q.options.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    q.options.forEach { option ->
                                        val selectedOptions = multiAnswers[q.id] ?: emptySet()
                                        FilterChip(
                                            selected = if (q.selectionType == "multi") {
                                                option in selectedOptions
                                            } else {
                                                answers[q.id] == option
                                            },
                                            onClick = {
                                                if (q.selectionType == "multi") {
                                                    multiAnswers[q.id] = if (option in selectedOptions) {
                                                        selectedOptions - option
                                                    } else {
                                                        selectedOptions + option
                                                    }
                                                } else {
                                                    answers[q.id] = option
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = option,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = answers[q.id] ?: "",
                                onValueChange = { answers[q.id] = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    FilledTonalButton(
                        onClick = {
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(
                                                (multiAnswers[q.id].orEmpty().toList() +
                                                    listOfNotNull(answers[q.id]?.takeIf { it.isNotBlank() }))
                                                    .joinToString(", ")
                                            ))
                                            else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                        }
                                    }
                                })
                            }
                            onToolAnswer(tool.toolCallId, answerPayload.toString())
                        },
                        enabled = questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty() || !answers[q.id].isNullOrBlank()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskKeyToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()

    val reason = remember(arguments) {
        runCatching {
            arguments.jsonObject["reason"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty().ifBlank { "请求密钥代名词" }
    }
    val suggestedKeyName = remember(arguments) {
        runCatching {
            arguments.jsonObject["suggested_key_name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty().trim()
    }

    val existingKeys = settings.secretKeys
    var selectedExistingKeyName by remember(existingKeys, suggestedKeyName) {
        mutableStateOf(
            existingKeys.find { it.keyName == suggestedKeyName }?.keyName
                ?: existingKeys.firstOrNull { it.enabled }?.keyName
        )
    }
    var createNewMode by remember(existingKeys.isEmpty()) {
        mutableStateOf(existingKeys.isEmpty())
    }
    var newKeyName by remember(suggestedKeyName) {
        mutableStateOf(suggestedKeyName.ifBlank { "SECRET_KEY" })
    }
    var newSecretValue by remember { mutableStateOf("") }
    var newDescription by remember(reason) { mutableStateOf(reason) }
    var showSecret by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Key01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = "请求密钥: $reason",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (isPending && onToolAnswer != null) {
                    if (existingKeys.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = !createNewMode,
                                onClick = { createNewMode = false },
                                label = { Text("选择已有密钥 (${existingKeys.size})", style = MaterialTheme.typography.labelSmall) },
                            )
                            FilterChip(
                                selected = createNewMode,
                                onClick = { createNewMode = true },
                                leadingIcon = {
                                    Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                label = { Text("新建密钥代名词", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }

                    if (!createNewMode && existingKeys.isNotEmpty()) {
                        Text(
                            text = "点选要提供给 AI 的密钥代名词（仅发送代名词，执行工具时自动替换为真实密钥）：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            existingKeys.forEach { entry ->
                                val label = if (entry.description.isNotBlank()) {
                                    "${entry.keyName} (${entry.description})"
                                } else {
                                    entry.keyName
                                }
                                FilterChip(
                                    selected = selectedExistingKeyName == entry.keyName,
                                    onClick = { selectedExistingKeyName = entry.keyName },
                                    label = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = newKeyName,
                            onValueChange = { newKeyName = it },
                            label = { Text("代名词 (key_name，发给 AI 的代号)") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newSecretValue,
                            onValueChange = { newSecretValue = it },
                            label = { Text("真实密钥值 (仅保存在宿主本地，AI 不可见)") },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            trailingIcon = {
                                FilledTonalIconButton(
                                    onClick = { showSecret = !showSecret },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = if (showSecret) HugeIcons.ViewOffSlash else HugeIcons.Eye,
                                        contentDescription = "显示/隐藏密钥",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newDescription,
                            onValueChange = { newDescription = it },
                            label = { Text("用途备注 (可选)") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    val canSubmit = if (!createNewMode && existingKeys.isNotEmpty()) {
                        !selectedExistingKeyName.isNullOrBlank()
                    } else {
                        newKeyName.isNotBlank() && newSecretValue.isNotEmpty()
                    }

                    FilledTonalButton(
                        onClick = {
                            val chosenKeyName = if (!createNewMode && existingKeys.isNotEmpty()) {
                                selectedExistingKeyName!!.trim()
                            } else {
                                newKeyName.trim()
                            }
                            scope.launch {
                                if (createNewMode || existingKeys.isEmpty()) {
                                    val newEntry = SecretKeyEntry(
                                        id = Uuid.random(),
                                        keyName = chosenKeyName,
                                        secretValue = newSecretValue,
                                        description = newDescription.trim(),
                                        enabled = true,
                                    )
                                    settingsStore.update { current ->
                                        val updatedList = current.secretKeys.filterNot {
                                            it.keyName == chosenKeyName
                                        } + newEntry
                                        current.copy(secretKeys = updatedList)
                                    }
                                } else {
                                    // 确保选中的已有密钥处于启用状态
                                    settingsStore.update { current ->
                                        current.copy(
                                            secretKeys = current.secretKeys.map {
                                                if (it.keyName == chosenKeyName) it.copy(enabled = true) else it
                                            }
                                        )
                                    }
                                }
                                val payload = buildJsonObject {
                                    put("key_name", JsonPrimitive(chosenKeyName))
                                    put(
                                        "instruction",
                                        JsonPrimitive("Use the exact alias `$chosenKeyName` in your tool arguments/commands. It will be automatically replaced with the real secret value at execution time.")
                                    )
                                }
                                onToolAnswer(tool.toolCallId, payload.toString())
                            }
                        },
                        enabled = canSubmit,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (!createNewMode && existingKeys.isNotEmpty()) {
                                "提供代名词: ${selectedExistingKeyName.orEmpty()}"
                            } else {
                                "保存密钥并提供代名词"
                            },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                } else if (isAnswered) {
                    val answeredState = tool.approvalState as ToolApprovalState.Answered
                    val answerJson = runCatching {
                        JsonInstant.parseToJsonElement(answeredState.answer)
                    }.getOrNull()
                    val providedKeyName = answerJson?.jsonObject?.get("key_name")?.jsonPrimitive?.contentOrNull
                        ?: answeredState.answer
                    Text(
                        text = "已提供密钥代名词: $providedKeyName（真实密钥仅保存在本地并在执行时自动替换）",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
