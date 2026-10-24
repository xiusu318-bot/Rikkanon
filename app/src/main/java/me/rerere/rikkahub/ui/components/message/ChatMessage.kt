package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.service.ToolEditAction
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.writeClipboardText
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.font.FontFamily
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import me.rerere.ui.charts.ChartCard
import me.rerere.ui.charts.ChartSpec
import me.rerere.ui.webview.WebViewContentCache
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    isSubAgentConversation: Boolean = false,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onRegeneratePart: ((partIndex: Int, rerunTool: Boolean) -> Unit)? = null,
    onEditToolCall: ((toolCallId: String, newInput: String, newOutputText: String?, action: ToolEditAction) -> Unit)? = null,
) {
    val message = node.messages[node.selectIndex]
    if (message.isContextCheckpoint) {
        ChatMessageContextCheckpoint(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = modifier,
        )
        return
    }
    if (message.isSubAgentNotification) {
        ChatMessageSubAgentNotification(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onRegenerate = onRegenerate,
            modifier = modifier,
        )
        return
    }
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    var showPartsEditorSheet by remember { mutableStateOf(false) }
    val handleEditClick = {
        if (message.role == MessageRole.ASSISTANT) {
            showPartsEditorSheet = true
        } else {
            onEdit()
        }
    }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                assistant = assistant,
                isSubAgentConversation = isSubAgentConversation,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                onRegeneratePart = onRegeneratePart,
                onEditToolCall = onEditToolCall,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        val motionScheme = MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
                fadeIn(motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
                fadeOut(motionScheme.fastEffectsSpec())
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        EditedFilesList(
            parts = message.parts,
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = handleEditClick,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }

    if (showPartsEditorSheet) {
        MessagePartsEditorSheet(
            message = message,
            onDismissRequest = { showPartsEditorSheet = false },
            onSaveMessage = { updatedMessage, asNewBranch ->
                showPartsEditorSheet = false
                if (asNewBranch) {
                    val branched = updatedMessage.copy(id = kotlin.uuid.Uuid.random())
                    onUpdate(node.copy(messages = node.messages + branched, selectIndex = node.messages.size))
                } else {
                    onUpdate(
                        node.copy(
                            messages = node.messages.map { msg ->
                                if (msg.id == message.id) updatedMessage.copy(id = message.id) else msg
                            }
                        )
                    )
                }
            },
            onRegeneratePart = { partIndex, rerunTool ->
                showPartsEditorSheet = false
                onRegeneratePart?.invoke(partIndex, rerunTool)
            },
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    isSubAgentConversation: Boolean = false,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    onRegeneratePart: ((partIndex: Int, rerunTool: Boolean) -> Unit)? = null,
    onEditToolCall: ((toolCallId: String, newInput: String, newOutputText: String?, action: ToolEditAction) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        cardColors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                        ),
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    val mcpServerNameToId = remember(settings.mcpServers) {
                                        settings.mcpServers.associate { it.commonOptions.name to it.id }
                                    }
                                    val hideUserApprovalPopup = isSubAgentConversation &&
                                        assistant != null &&
                                        !assistant.requiresSubAgentUserApproval(step.tool.toolName, mcpServerNameToId) &&
                                        assistant.requiresSubAgentAgentApproval(step.tool.toolName, mcpServerNameToId)
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        assistant = assistant,
                                        onToolApproval = if (hideUserApprovalPopup) null else onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        onEditToolCall = onEditToolCall,
                                        onRegenerateFromHere = if (onRegeneratePart != null) {
                                            { onRegeneratePart(step.partIndex, false) }
                                        } else null,
                                        onRerunTool = if (onRegeneratePart != null) {
                                            { onRegeneratePart(step.partIndex, true) }
                                        } else null,
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ChartBlock -> key(block.index) {
                val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                spec?.let { ChartCard(spec = it) }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val textContent = @Composable {
                            if (role == MessageRole.USER) {
                                Surface(
                                    modifier = Modifier.animateContentSize(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    onClick = { onUserMessageClick?.invoke() },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        MarkdownBlock(
                                            content = part.text.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation
                                        )
                                    }
                                }
                            } else {
                                if (settings.displaySetting.showAssistantBubble) {
                                    Surface(
                                        modifier = Modifier.animateContentSize(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = part.text.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation,
                                            )
                                        }
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = part.text.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        modifier = Modifier
                                            .animateContentSize()
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.Video01,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Audio -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.MusicNote03,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.large)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        ChatMessageFileChip(
                            text = part.fileName,
                            onClick = { context.openLocalFile(part.url) },
                            icon = {
                                when (part.mime) {
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.docx),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    "application/pdf" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.pdf),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    else -> {
                                        Icon(
                                            imageVector = HugeIcons.File02,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                        )
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        ChatMessageCitations(annotations = annotations)
    }
}

private val BubbleCorner = 24.dp
private val BubbleTailCorner = 6.dp
private val BubblePressedCorner = 12.dp

/**
 * 消息气泡：大圆角，靠头像一侧的顶角收紧；可点击时按下其余圆角也跟着收紧
 */
@Composable
private fun ChatMessageBubble(
    role: MessageRole,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    val corner = lerp(BubbleCorner, BubblePressedCorner, pressProgress)
    val shape = if (role == MessageRole.USER) {
        RoundedCornerShape(topStart = corner, topEnd = BubbleTailCorner, bottomEnd = corner, bottomStart = corner)
    } else {
        RoundedCornerShape(topStart = BubbleTailCorner, topEnd = corner, bottomEnd = corner, bottomStart = corner)
    }
    // MarkdownBlock 自带 4dp 的水平内边距
    val contentModifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = color,
            interactionSource = interactionSource,
        ) {
            Box(modifier = contentModifier) { content() }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
        ) {
            Box(modifier = contentModifier) { content() }
        }
    }
}

@Composable
private fun ChatMessageCitations(annotations: List<UIMessageAnnotation>) {
    var expand by remember { mutableStateOf(false) }
    val urls = remember(annotations) {
        annotations.map { annotation ->
            when (annotation) {
                is UIMessageAnnotation.UrlCitation -> annotation.url
            }
        }
    }
    Column(
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChatMessageFileChip(
            text = stringResource(R.string.citations_count, annotations.size),
            onClick = { expand = !expand },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { FaviconRow(urls = urls, size = 18.dp) },
        )
        if (expand) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 助手消息深度结构化编辑器（融合可视化部件编辑与底层 Raw JSON 源码编辑）：
 * 1. 可视化部件模式：支持增删、上下移动、单步 Roll / 重跑工具，直接编辑 Reasoning / Tool (input, output, toolName, toolCallId, approvalState, metadata) / Text 等部件。
 * 2. 底层 JSON 源码模式：直接暴露整条 UIMessage 在数据库中的完整 JSON 结构，支持任意底层字段修改与语法校验。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessagePartsEditorSheet(
    message: UIMessage,
    onDismissRequest: () -> Unit,
    onSaveMessage: (UIMessage, asNewBranch: Boolean) -> Unit,
    onRegeneratePart: (partIndex: Int, rerunTool: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var baseMessage by remember(message) { mutableStateOf(message) }
    val editableParts = remember(message) {
        mutableStateListOf<UIMessagePart>().apply { addAll(message.parts) }
    }
    val expandedMetaIndices = remember(message) { mutableStateListOf<Int>() }
    var isRawJsonMode by remember { mutableStateOf(false) }
    var rawJsonText by remember(message) {
        mutableStateOf(
            runCatching {
                JsonInstantPretty.encodeToString(UIMessage.serializer(), message)
            }.getOrElse { "" }
        )
    }
    var jsonError by remember { mutableStateOf<String?>(null) }

    fun buildCurrentMessageFromParts(): UIMessage =
        baseMessage.copy(parts = editableParts.toList())

    fun syncPartsToRawJson() {
        rawJsonText = runCatching {
            JsonInstantPretty.encodeToString(UIMessage.serializer(), buildCurrentMessageFromParts())
        }.getOrElse { rawJsonText }
        jsonError = null
    }

    fun tryApplyRawJsonToParts(): UIMessage? {
        return runCatching {
            JsonInstantPretty.decodeFromString(UIMessage.serializer(), rawJsonText)
        }.fold(
            onSuccess = { parsed ->
                jsonError = null
                baseMessage = parsed
                editableParts.clear()
                editableParts.addAll(parsed.parts)
                parsed
            },
            onFailure = { err ->
                jsonError = err.message ?: "JSON 格式解析失败"
                null
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "编辑消息与底层存储",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = !isRawJsonMode,
                        onClick = {
                            if (isRawJsonMode) {
                                if (tryApplyRawJsonToParts() != null) {
                                    isRawJsonMode = false
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Edit01,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = { Text("可视化部件 (${editableParts.size} 步)") },
                    )
                    FilterChip(
                        selected = isRawJsonMode,
                        onClick = {
                            if (!isRawJsonMode) {
                                syncPartsToRawJson()
                                isRawJsonMode = true
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Code,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = { Text("底层 JSON 源码") },
                    )
                }
            }

            if (isRawJsonMode) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "UIMessage 原始 JSON 存储结构",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        val parsed = tryApplyRawJsonToParts()
                                        if (parsed != null) {
                                            rawJsonText = JsonInstantPretty.encodeToString(UIMessage.serializer(), parsed)
                                        }
                                    }
                                ) {
                                    Text("格式化校验", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = {
                                        context.writeClipboardText(rawJsonText)
                                    }
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Copy01,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "复制 JSON",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = rawJsonText,
                            onValueChange = {
                                rawJsonText = it
                                jsonError = null
                            },
                            label = { Text("完整消息 JSON (parts / metadata / usage / modelSnapshot 等)") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 10,
                            maxLines = 22,
                            isError = jsonError != null,
                        )

                        jsonError?.let { err ->
                            Text(
                                text = "JSON 解析错误: $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                editableParts.forEachIndexed { index, part ->
                    val showMeta = index in expandedMetaIndices
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                val partLabel = when (part) {
                                    is UIMessagePart.Reasoning -> "#${index + 1} 思考 (Reasoning)"
                                    is UIMessagePart.Tool -> "#${index + 1} 工具 (${part.toolName})"
                                    is UIMessagePart.Text -> "#${index + 1} 正文 (Text)"
                                    else -> "#${index + 1} ${part::class.simpleName}"
                                }
                                Text(
                                    text = partLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (part is UIMessagePart.Tool) {
                                        TextButton(onClick = { onRegeneratePart(index, true) }) {
                                            Text("重跑工具", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    TextButton(onClick = { onRegeneratePart(index, false) }) {
                                        Text("从此步 Roll", style = MaterialTheme.typography.labelSmall)
                                    }
                                    ChatMessageActionButton(
                                        icon = HugeIcons.Settings03,
                                        contentDescription = "展开/收起底层字段",
                                        onClick = {
                                            if (showMeta) expandedMetaIndices.remove(index)
                                            else expandedMetaIndices.add(index)
                                        },
                                    )
                                    if (index > 0) {
                                        ChatMessageActionButton(
                                            icon = HugeIcons.ArrowUp01,
                                            contentDescription = "上移此步",
                                            onClick = {
                                                val item = editableParts.removeAt(index)
                                                editableParts.add(index - 1, item)
                                            },
                                        )
                                    }
                                    if (index < editableParts.lastIndex) {
                                        ChatMessageActionButton(
                                            icon = HugeIcons.ArrowDown01,
                                            contentDescription = "下移此步",
                                            onClick = {
                                                val item = editableParts.removeAt(index)
                                                editableParts.add(index + 1, item)
                                            },
                                        )
                                    }
                                    if (editableParts.size > 1) {
                                        ChatMessageActionButton(
                                            icon = HugeIcons.Delete01,
                                            contentDescription = "删除此部件",
                                            onClick = { editableParts.removeAt(index) },
                                        )
                                    }
                                }
                            }

                            when (part) {
                                is UIMessagePart.Text -> {
                                    OutlinedTextField(
                                        value = part.text,
                                        onValueChange = { editableParts[index] = part.copy(text = it) },
                                        label = { Text("正文内容 (text)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 10,
                                    )
                                    if (showMeta) {
                                        PartMetadataEditorField(
                                            metadata = part.metadata,
                                            onMetadataChange = { newMeta ->
                                                editableParts[index] = part.copy(metadata = newMeta)
                                            },
                                        )
                                    }
                                }

                                is UIMessagePart.Reasoning -> {
                                    OutlinedTextField(
                                        value = part.reasoning,
                                        onValueChange = { editableParts[index] = part.copy(reasoning = it) },
                                        label = { Text("思考过程 (reasoning)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 8,
                                    )
                                    if (showMeta) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "思考类型:",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                            ReasoningType.entries.forEach { rType ->
                                                FilterChip(
                                                    selected = part.reasoningType == rType,
                                                    onClick = {
                                                        editableParts[index] = part.copy(reasoningType = rType)
                                                    },
                                                    label = { Text(rType.name, style = MaterialTheme.typography.labelSmall) },
                                                )
                                            }
                                        }
                                        PartMetadataEditorField(
                                            metadata = part.metadata,
                                            onMetadataChange = { newMeta ->
                                                editableParts[index] = part.copy(metadata = newMeta)
                                            },
                                        )
                                    }
                                }

                                is UIMessagePart.Tool -> {
                                    val outText = part.output.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                    if (showMeta) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            OutlinedTextField(
                                                value = part.toolName,
                                                onValueChange = { editableParts[index] = part.copy(toolName = it) },
                                                label = { Text("工具名 (toolName)") },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                modifier = Modifier.weight(1f),
                                            )
                                            OutlinedTextField(
                                                value = part.toolCallId,
                                                onValueChange = { editableParts[index] = part.copy(toolCallId = it) },
                                                label = { Text("调用 ID (toolCallId)") },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            itemVerticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("审批状态:", style = MaterialTheme.typography.labelSmall)
                                            val states = listOf(
                                                "Auto" to ToolApprovalState.Auto,
                                                "Approved" to ToolApprovalState.Approved,
                                                "Pending" to ToolApprovalState.Pending,
                                                "Denied" to ToolApprovalState.Denied("Denied"),
                                            )
                                            states.forEach { (label, stateObj) ->
                                                val selected = part.approvalState::class == stateObj::class
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = {
                                                        editableParts[index] = part.copy(approvalState = stateObj)
                                                    },
                                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                                )
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = part.input,
                                        onValueChange = { editableParts[index] = part.copy(input = it) },
                                        label = { Text("工具调用入参 (input JSON)") },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 8,
                                    )
                                    OutlinedTextField(
                                        value = outText,
                                        onValueChange = { newOut ->
                                            val nonText = part.output.filterNot { it is UIMessagePart.Text }
                                            val nextOutput = if (newOut.isEmpty() && nonText.isEmpty()) {
                                                emptyList()
                                            } else {
                                                listOf(UIMessagePart.Text(newOut)) + nonText
                                            }
                                            editableParts[index] = part.copy(output = nextOutput)
                                        },
                                        label = { Text("工具返回结果 (output，留空视为未执行)") },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 10,
                                    )
                                    if (showMeta) {
                                        PartMetadataEditorField(
                                            metadata = part.metadata,
                                            onMetadataChange = { newMeta ->
                                                editableParts[index] = part.copy(metadata = newMeta)
                                            },
                                        )
                                    }
                                }

                                is UIMessagePart.Image -> {
                                    OutlinedTextField(
                                        value = part.url,
                                        onValueChange = { editableParts[index] = part.copy(url = it) },
                                        label = { Text("图片地址 (url)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                is UIMessagePart.Document -> {
                                    OutlinedTextField(
                                        value = part.fileName,
                                        onValueChange = { editableParts[index] = part.copy(fileName = it) },
                                        label = { Text("文件名 (fileName)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = part.url,
                                        onValueChange = { editableParts[index] = part.copy(url = it) },
                                        label = { Text("文档地址 (url)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                else -> {}
                            }
                        }
                    }
                }

                // 底部快捷新增部件栏
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            editableParts.add(UIMessagePart.Text(text = ""))
                        }
                    ) {
                        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("新增正文 (Text)", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(
                        onClick = {
                            editableParts.add(UIMessagePart.Reasoning(reasoning = ""))
                        }
                    ) {
                        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("新增思考 (Reasoning)", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(
                        onClick = {
                            editableParts.add(
                                UIMessagePart.Tool(
                                    toolCallId = "call_${kotlin.uuid.Uuid.random().toString().take(8)}",
                                    toolName = "workspace_terminal",
                                    input = "{}",
                                    output = listOf(UIMessagePart.Text("{}")),
                                    approvalState = ToolApprovalState.Auto,
                                )
                            )
                        }
                    ) {
                        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("新增工具块 (Tool)", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        val finalMsg = if (isRawJsonMode) {
                            tryApplyRawJsonToParts() ?: return@TextButton
                        } else {
                            buildCurrentMessageFromParts()
                        }
                        onSaveMessage(finalMsg, false)
                    }
                ) {
                    Text("原地覆盖保存")
                }
                FilledTonalButton(
                    onClick = {
                        val finalMsg = if (isRawJsonMode) {
                            tryApplyRawJsonToParts() ?: return@FilledTonalButton
                        } else {
                            buildCurrentMessageFromParts()
                        }
                        onSaveMessage(finalMsg, true)
                    }
                ) {
                    Text("保存为新分支")
                }
            }
        }
    }
}

@Composable
private fun PartMetadataEditorField(
    metadata: JsonObject?,
    onMetadataChange: (JsonObject?) -> Unit,
) {
    var metaText by remember(metadata) {
        mutableStateOf(metadata?.let { JsonInstantPretty.encodeToString(JsonObject.serializer(), it) }.orEmpty())
    }
    var hasError by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = metaText,
        onValueChange = { next ->
            metaText = next
            if (next.isBlank()) {
                hasError = false
                onMetadataChange(null)
            } else {
                runCatching {
                    JsonInstantPretty.parseToJsonElement(next).jsonObject
                }.onSuccess { parsed ->
                    hasError = false
                    onMetadataChange(parsed)
                }.onFailure {
                    hasError = true
                }
            }
        },
        label = { Text("底层元数据 (metadata JSON，可留空)") },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 6,
        isError = hasError,
    )
}

