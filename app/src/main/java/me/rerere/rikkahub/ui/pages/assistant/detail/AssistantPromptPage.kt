package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.DefaultPlaceholderProvider
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.ChatFontProvider
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.insertAtCursor
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.components.Select
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableColumn
import kotlin.uuid.Uuid

@Composable
fun AssistantPromptPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_prompt))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantPromptContent(
            innerPadding = innerPadding,
            assistant = assistant,
            settings = settings,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantPromptContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val templateTransformer = koinInject<TemplateTransformer>()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            formItem {
                val systemPromptValue = rememberTextFieldState(
                    initialText = assistant.systemPrompt,
                )
                LaunchedEffect(Unit) {
                    snapshotFlow { systemPromptValue.text }.collect {
                        onUpdate(
                            assistant.copy(
                                systemPrompt = it.toString()
                            )
                        )
                    }
                }

                TextArea(
                    state = systemPromptValue,
                    label = stringResource(R.string.assistant_page_system_prompt),
                    minLines = 5,
                    maxLines = 10
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_available_variables),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        DefaultPlaceholderProvider.placeholders.forEach { (k, info) ->
                            Tag(
                                onClick = {
                                    systemPromptValue.insertAtCursor("{{$k}}")
                                }
                            ) {
                                info.displayName()
                                Text(": {{$k}}")
                            }
                        }
                    }
                }
            }
            switchItem(
                checked = assistant.allowConversationSystemPrompt,
                onCheckedChange = {
                    onUpdate(
                        assistant.copy(
                            allowConversationSystemPrompt = it
                        )
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_system_prompt_desc))
                },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_allow_conversation_system_prompt))
                },
            )
        }

        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_message_template)) },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_message_template_desc))
                    Text(buildAnnotatedString {
                        append(stringResource(R.string.assistant_page_template_variables_label))
                        append(" ")
                        append(stringResource(R.string.assistant_page_template_variable_role))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ role }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_message))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ message }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_time))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ time }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_date))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ date }}")
                        }
                    })
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            onUpdate(assistant.copy(messageTemplate = "{{ message }}"))
                        },
                        enabled = assistant.messageTemplate != "{{ message }}",
                    ) {
                        Icon(
                            imageVector = HugeIcons.Refresh03,
                            contentDescription = null,
                        )
                    }
                },
            ) {
                val missingMessage = "{{ message }}" !in assistant.messageTemplate
                OutlinedTextField(
                    value = assistant.messageTemplate,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                messageTemplate = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 15,
                    isError = missingMessage,
                    supportingText = if (missingMessage) {
                        { Text(stringResource(R.string.assistant_page_message_template_missing_message)) }
                    } else null,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp,
                        fontFamily = JetbrainsMono,
                        lineHeight = 16.sp
                    )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_template_preview),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val rawMessages = listOf(
                        UIMessage.user("你好啊"),
                        UIMessage.assistant("你好，有什么我可以帮你的吗？"),
                    )
                    val preview by produceState<UiState<List<UIMessage>>>(
                        UiState.Success(rawMessages),
                        assistant
                    ) {
                        value = runCatching {
                            UiState.Success(
                                templateTransformer.transform(
                                    ctx = TransformerContext(
                                        context = context,
                                        model = Model(modelId = "gpt-4o", displayName = "GPT-4o"),
                                        assistant = assistant,
                                        settings = settings
                                    ),
                                    messages = rawMessages
                                )
                            )
                        }.getOrElse {
                            UiState.Error(it)
                        }
                    }
                    preview.onError {
                        Text(
                            text = it.message ?: it.javaClass.name,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    preview.onSuccess {
                        ChatFontProvider(displaySetting = settings.displaySetting) {
                            it.fastForEachIndexed { previewIndex, message ->
                                ChatMessage(
                                    node = message.toMessageNode(),
                                    assistant = assistant,
                                    regexDepth = it.lastIndex - previewIndex,
                                    onFork = {},
                                    onRegenerate = {},
                                    onEdit = {},
                                    onShare = {},
                                    onDelete = {},
                                    onUpdate = {},
                                    lastMessage = false,
                                )
                            }
                        }
                    }
                }
            }
        }

        var expandedIds by remember { mutableStateOf(emptySet<Uuid>()) }
        SectionHeader(
            title = stringResource(R.string.assistant_page_regex_title),
            description = stringResource(R.string.assistant_page_regex_desc),
            action = {
                SectionAddButton(
                    onClick = {
                        val newRegex = AssistantRegex(
                            id = Uuid.random(),
                            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                            visualOnly = true,
                            requestOnly = true,
                        )
                        expandedIds = expandedIds + newRegex.id
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes + newRegex
                            )
                        )
                    }
                )
            },
        )
        ReorderableColumn(
            list = assistant.regexes,
            onSettle = { fromIndex, toIndex ->
                val regexes = assistant.regexes.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                onUpdate(assistant.copy(regexes = regexes))
            },
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) { index, regex, isDragging ->
            key(regex.id) {
                ReorderableItem(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val expanded = regex.id in expandedIds
                    AssistantRegexCard(
                        regex = regex,
                        onUpdate = onUpdate,
                        assistant = assistant,
                        index = index,
                        shape = cardGroupItemShape(index, assistant.regexes.size),
                        expanded = expanded,
                        onExpandedChange = {
                            expandedIds = if (it) expandedIds + regex.id else expandedIds - regex.id
                        },
                        // 展开后内部是输入框，长按拖拽会与文本选择冲突
                        modifier = longPressReorder(
                            isDragging = isDragging,
                            enabled = !expanded && assistant.regexes.size > 1,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantRegexCard(
    regex: AssistantRegex,
    onUpdate: (Assistant) -> Unit,
    assistant: Assistant,
    index: Int,
    shape: Shape,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onExpandedChange(!expanded) }
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val arrowRotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                )
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .rotate(arrowRotation)
                )
                Text(
                    text = regex.name.ifBlank { stringResource(R.string.extension_content_unnamed) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = regex.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(enabled = enabled)
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
                ItemActionMenu(
                    actions = listOf(
                        ItemAction(
                            text = stringResource(R.string.delete),
                            icon = HugeIcons.Delete01,
                            destructive = true,
                            onClick = { showDeleteDialog = true },
                        ),
                    )
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = regex.name,
                        onValueChange = { name ->
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(name = name)
                                        } else {
                                            reg
                                        }
                                    }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.assistant_page_regex_name)) }
                    )

                    OutlinedTextField(
                        value = regex.findRegex,
                        onValueChange = { findRegex ->
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(findRegex = findRegex)
                                        } else {
                                            reg
                                        }
                                    }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
                        placeholder = { Text("e.g., /<status>[\\s\\S]*?<\\/status>/gi") },
                    )

                    OutlinedTextField(
                        value = regex.replaceString,
                        onValueChange = { replaceString ->
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(replaceString = replaceString)
                                        } else {
                                            reg
                                        }
                                    }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
                        placeholder = { Text("e.g., $1 或留空删除") }
                    )

                    Text(
                        text = stringResource(R.string.assistant_page_regex_affecting_scopes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistantAffectScope.entries.forEach { scope ->
                            val selected = scope in regex.affectingScope
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val newScopes = if (selected) {
                                        regex.affectingScope - scope
                                    } else {
                                        regex.affectingScope + scope
                                    }
                                    onUpdate(
                                        assistant.copy(
                                            regexes = assistant.regexes.mapIndexed { i, reg ->
                                                if (i == index) {
                                                    reg.copy(affectingScope = newScopes)
                                                } else {
                                                    reg
                                                }
                                            }
                                        )
                                    )
                                },
                                label = {
                                    Text(
                                        when (scope) {
                                            AssistantAffectScope.USER -> "用户"
                                            AssistantAffectScope.ASSISTANT -> "助手"
                                            AssistantAffectScope.LOREBOOK -> "世界书"
                                        }
                                    )
                                },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = HugeIcons.Tick01,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }

                    // 深度范围（对齐酒馆：0 = 最后一条消息，往上依次 +1；留空 = 无限）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RegexDepthField(
                            label = "最小深度",
                            value = regex.minDepth,
                            onValueChange = { minDepth ->
                                onUpdate(
                                    assistant.copy(
                                        regexes = assistant.regexes.mapIndexed { i, reg ->
                                            if (i == index) reg.copy(minDepth = minDepth) else reg
                                        }
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        RegexDepthField(
                            label = "最大深度",
                            value = regex.maxDepth,
                            onValueChange = { maxDepth ->
                                onUpdate(
                                    assistant.copy(
                                        regexes = assistant.regexes.mapIndexed { i, reg ->
                                            if (i == index) reg.copy(maxDepth = maxDepth) else reg
                                        }
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "深度：0 = 最后一条消息，往上依次 +1，留空为无限。世界书按条目的 @D 深度计算，非 @D 条目不受深度限制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "仅修改发出",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = regex.requestOnly,
                            onCheckedChange = { requestOnly ->
                                onUpdate(
                                    assistant.copy(
                                        regexes = assistant.regexes.mapIndexed { i, reg ->
                                            if (i == index) {
                                                reg.copy(requestOnly = requestOnly)
                                            } else {
                                                reg
                                            }
                                        }
                                    )
                                )
                            }
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "仅修改显示",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = regex.visualOnly,
                            onCheckedChange = { visualOnly ->
                                onUpdate(
                                    assistant.copy(
                                        regexes = assistant.regexes.mapIndexed { i, reg ->
                                            if (i == index) {
                                                reg.copy(visualOnly = visualOnly)
                                            } else {
                                                reg
                                            }
                                        }
                                    )
                                )
                            }
                        )
                    }

                    val modeHint = when {
                        regex.requestOnly && regex.visualOnly ->
                            "保留原消息数据，同时修改人类显示与发出的请求体"
                        regex.requestOnly && !regex.visualOnly ->
                            "保留原消息数据，仅在发出请求体时替换（人类显示原文）"
                        !regex.requestOnly && regex.visualOnly ->
                            "保留原消息数据，仅在面向人类显示时替换（发出原文）"
                        else ->
                            "两个都不勾选：直接修改消息正文并写入数据库"
                    }.let { hint ->
                        if (AssistantAffectScope.LOREBOOK in regex.affectingScope) {
                            "$hint（世界书不显示、不落库：勾选「仅修改发出」或两个都不勾时，在组装请求时替换条目正文）"
                        } else {
                            hint
                        }
                    }
                    Text(
                        text = modeHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onUpdate(assistant.copy(regexes = assistant.regexes.filter { it.id != regex.id }))
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, regex.name))
    }
}

@Composable
private fun RegexDepthField(
    label: String,
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(5)
            onValueChange(digits.toIntOrNull())
        },
        modifier = modifier,
        label = { Text(label) },
        placeholder = { Text("无限") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
