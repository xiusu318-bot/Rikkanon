package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.AllWorkspaceToolNames
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun AssistantSubAgentPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val mcpServerConfigs by vm.mcpServerConfigs.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showCreateSubAssistantDialog by remember { mutableStateOf(false) }
    var showMainPermissionsSheet by remember { mutableStateOf(false) }
    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var editingToolsTargetId by remember { mutableStateOf<Uuid?>(null) }

    val tagMap = remember(tags) { tags.associateBy { it.id } }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("子代理设置")
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = { showCreateSubAssistantDialog = true }) {
                        Icon(HugeIcons.Add01, contentDescription = "新建子代理助手")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val selectedIds = assistant.effectiveSubAgentIds
        val switchEnabled = assistant.enableSubAgent || LocalToolOption.SubAgent in assistant.localTools
        val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)

        fun toggleSubAgent(targetId: Uuid, checked: Boolean) {
            val nextIds = if (checked) selectedIds + targetId else selectedIds - targetId
            vm.update(
                assistant.copy(
                    enableSubAgent = if (checked) true else switchEnabled,
                    subAgentIds = nextIds,
                    subAgentAssistantId = null,
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                switchItem(
                    checked = switchEnabled,
                    onCheckedChange = { checked ->
                        vm.update(
                            assistant.copy(
                                enableSubAgent = checked,
                                localTools = if (checked) assistant.localTools else assistant.localTools - LocalToolOption.SubAgent,
                            )
                        )
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Message02,
                            containerColor = if (switchEnabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (switchEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("启用子代理协作") },
                    supportingContent = {
                        Text("将下方勾选的助手作为子代理，主代理可根据各子代理的名称与标签分工派发任务")
                    },
                )

                switchItem(
                    checked = assistant.subAgentShareWorkspace,
                    enabled = switchEnabled,
                    onCheckedChange = { share ->
                        vm.update(assistant.copy(subAgentShareWorkspace = share))
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Codesandbox,
                            containerColor = if (switchEnabled && assistant.subAgentShareWorkspace) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (switchEnabled && assistant.subAgentShareWorkspace) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("共享工作区与终端") },
                    supportingContent = {
                        Text("子代理直接共用主对话的工作区目录与已开启的终端")
                    },
                )

                val enabledPerms = buildList {
                    if (assistant.subAgentAllowInspect) add("查看状态")
                    if (assistant.subAgentAllowMultiTurnAndAsync) add("多轮与并行")
                    if (assistant.subAgentAllowInterruptAndStop) add("打断停止")
                    if (assistant.subAgentAllowDelete) add("删除对话")
                }
                val enabledApprovals = buildList {
                    if (assistant.subAgentApproveSpawn) add("派发")
                    if (assistant.subAgentAllowMultiTurnAndAsync && assistant.subAgentApproveFollowUp) add("追问")
                    if (assistant.subAgentAllowInspect && assistant.subAgentApproveInspect) add("查看")
                    if (assistant.subAgentAllowInterruptAndStop && assistant.subAgentApproveInterruptAndStop) add("打断")
                    if (assistant.subAgentAllowDelete && assistant.subAgentApproveDelete) add("删除")
                }
                val permSummary = buildString {
                    if (enabledPerms.isEmpty()) {
                        append("基础模式（仅派发任务并获取最终回复）")
                    } else {
                        append("已开启: ${enabledPerms.joinToString("、")}")
                    }
                    if (enabledApprovals.isNotEmpty()) {
                        append(" · 需审批: ${enabledApprovals.joinToString("、")}")
                    }
                }

                item(
                    onClick = { showMainPermissionsSheet = true },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Settings03,
                            containerColor = if (switchEnabled) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (switchEnabled) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("主代理控制权限与审批") },
                    supportingContent = { Text(permSummary) },
                )

                if (switchEnabled) {
                    val currentConcurrency = assistant.subAgentMaxConcurrency.coerceAtLeast(1)
                    item(
                        onClick = { showConcurrencyDialog = true },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Clock01,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        headlineContent = { Text("子代理并发限制") },
                        supportingContent = {
                            Text("同时运行的子代理上限")
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (currentConcurrency > 1) {
                                            vm.update(assistant.copy(subAgentMaxConcurrency = currentConcurrency - 1))
                                        }
                                    },
                                    enabled = currentConcurrency > 1,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Text("-", fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    text = "$currentConcurrency",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                                FilledTonalIconButton(
                                    onClick = {
                                        vm.update(assistant.copy(subAgentMaxConcurrency = currentConcurrency + 1))
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Text("+", fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                    )
                }
            }

            CardGroup(
                title = {
                    Text("绑定子代理助手（已选 ${selectedIds.count { id -> settings.assistants.any { it.id == id } }} 个，点右侧图标配置工具）")
                }
            ) {
                settings.assistants.forEach { candidate ->
                    val isSelected = candidate.id in selectedIds
                    val isSelf = candidate.id == assistant.id
                    val candidateModel = settings.findModelById(candidate.chatModelId ?: settings.chatModelId)
                    val candidateProvider = candidateModel?.findProvider(settings.providers, checkOverwrite = false)
                    val candidateName = candidate.name.ifBlank { defaultAssistantName }
                    val candidateTagTexts = candidate.tags.mapNotNull { tagId ->
                        tagMap[tagId]?.name?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    val enabledWsCount = AllWorkspaceToolNames.count { it !in candidate.disabledWorkspaceTools }
                    val enabledMcpCount = mcpServerConfigs.count { it.commonOptions.enable && it.id in candidate.mcpServers }
                    val enabledLocalCount = candidate.localTools.count { it != LocalToolOption.SubAgent && it != LocalToolOption.AskUser }

                    item(
                        onClick = { toggleSubAgent(candidate.id, !isSelected) },
                        leadingContent = {
                            UIAvatar(
                                value = candidate.avatar,
                                name = candidateName,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = if (isSelf) "$candidateName (当前助手)" else candidateName,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val modelDesc = if (candidateModel != null) {
                                    "${candidateModel.displayName}${candidateProvider?.let { " (${it.name})" } ?: ""}"
                                } else {
                                    "未选择模型"
                                }
                                Text(
                                    text = "模型: $modelDesc",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (candidateTagTexts.isNotEmpty()) {
                                    Text(
                                        text = "标签: ${candidateTagTexts.joinToString(" | ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "工具: 工作区 $enabledWsCount/${AllWorkspaceToolNames.size} · MCP $enabledMcpCount · 本地 $enabledLocalCount" +
                                        (if (candidate.subAgentMaxSteps > 0) " · 限${candidate.subAgentMaxSteps}步" else "") +
                                        if (candidate.subAgentAutoApproveTools) " · 免审批" else " · 需审批",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        editingToolsTargetId = candidate.id
                                    }
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Settings03,
                                        contentDescription = "管理子代理可调用工具",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        toggleSubAgent(candidate.id, checked)
                                    },
                                )
                            }
                        },
                    )
                }

                item(
                    onClick = { showCreateSubAssistantDialog = true },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Add01,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                    headlineContent = { Text("新建子代理助手") },
                    supportingContent = { Text("快速创建新助手并自动绑定为子代理") },
                )
            }
        }
    }

    if (showCreateSubAssistantDialog) {
        CreateSubAssistantDialog(
            defaultModelId = assistant.chatModelId ?: settings.chatModelId,
            providers = providers,
            onDismiss = { showCreateSubAssistantDialog = false },
            onConfirm = { name, modelId, prompt ->
                vm.createAndBindSubAgentAssistant(
                    name = name,
                    chatModelId = modelId,
                    systemPrompt = prompt,
                )
                showCreateSubAssistantDialog = false
            },
        )
    }

    if (showMainPermissionsSheet) {
        MainAgentPermissionsSheet(
            assistant = assistant,
            onUpdateAssistant = { updated -> vm.update(updated) },
            onDismiss = { showMainPermissionsSheet = false },
        )
    }

    if (showConcurrencyDialog) {
        var inputText by remember(assistant.subAgentMaxConcurrency) {
            mutableStateOf(assistant.subAgentMaxConcurrency.coerceAtLeast(1).toString())
        }
        AlertDialog(
            onDismissRequest = { showConcurrencyDialog = false },
            title = { Text("设置子代理并发限制") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "设置同一时间最多允许多少个子代理并发请求模型（最低为 1，默认为 2）。超出此数量的子代理任务会自动排队等待。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { value ->
                            inputText = value.filter { it.isDigit() }
                        },
                        label = { Text("最大并发数（>= 1）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = inputText.toIntOrNull()?.coerceAtLeast(1) ?: 2
                        vm.update(assistant.copy(subAgentMaxConcurrency = parsed))
                        showConcurrencyDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConcurrencyDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    editingToolsTargetId?.let { targetId ->
        val targetAssistant = settings.assistants.find { it.id == targetId }
        if (targetAssistant != null) {
            SubAgentToolsSheet(
                targetAssistant = targetAssistant,
                mcpServers = mcpServerConfigs,
                skills = skills,
                onUpdateAssistant = { updated -> vm.update(updated) },
                onDismiss = { editingToolsTargetId = null },
            )
        } else {
            editingToolsTargetId = null
        }
    }
}

/**
 * 主代理对子代理的控制权限与操作审批设置面板：
 * - 最低权限（4 项进阶开关全关）：仅允许单次同步派发子代理并获取最终回复文本（隐藏工具步骤与内部状态）。
 * - 最高权限：支持窥屏查看工具轨迹、多轮追问与后台异步并发、中途打断与强制停止、以及彻底删除销毁子代理对话。
 * - 每项权限均支持独立配置「调用前是否需用户审批」。
 */
@Composable
private fun MainAgentPermissionsSheet(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "主代理控制权限与审批",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "设置主代理可对子代理执行的操作及审批要求。全部关闭时仅支持派发任务并获取最终回复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CardGroup(
                title = { Text("操作权限") }
            ) {
                switchItem(
                    checked = assistant.subAgentAllowInspect,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(assistant.copy(subAgentAllowInspect = enabled))
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.FileView,
                            containerColor = if (assistant.subAgentAllowInspect) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (assistant.subAgentAllowInspect) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("查看执行过程与状态") },
                    supportingContent = {
                        Text("允许查看子代理的实时状态、完整对话记录与工具调用过程；关闭后仅获取最终回复")
                    },
                )

                switchItem(
                    checked = assistant.subAgentAllowMultiTurnAndAsync,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(assistant.copy(subAgentAllowMultiTurnAndAsync = enabled))
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Message02,
                            containerColor = if (assistant.subAgentAllowMultiTurnAndAsync) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (assistant.subAgentAllowMultiTurnAndAsync) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("多轮追问与后台并行") },
                    supportingContent = {
                        Text("允许向已有子代理继续追问，或在后台同时并行运行多个子代理")
                    },
                )

                switchItem(
                    checked = assistant.subAgentAllowInterruptAndStop,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(assistant.copy(subAgentAllowInterruptAndStop = enabled))
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.ComputerTerminal01,
                            containerColor = if (assistant.subAgentAllowInterruptAndStop) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (assistant.subAgentAllowInterruptAndStop) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("中途打断与停止") },
                    supportingContent = {
                        Text("允许打断正在运行的子代理并改派新任务，或直接终止子代理运行")
                    },
                )

                switchItem(
                    checked = assistant.subAgentAllowDelete,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(assistant.copy(subAgentAllowDelete = enabled))
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Settings03,
                            containerColor = if (assistant.subAgentAllowDelete) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (assistant.subAgentAllowDelete) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("删除子代理对话") },
                    supportingContent = {
                        Text("允许彻底删除已创建的子代理对话记录")
                    },
                )
            }

            CardGroup(
                title = { Text("操作审批（开启后主代理执行前需你确认）") }
            ) {
                switchItem(
                    checked = assistant.subAgentApproveSpawn,
                    onCheckedChange = { needsApproval ->
                        onUpdateAssistant(assistant.copy(subAgentApproveSpawn = needsApproval))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                    headlineContent = { Text("派发新子代理需审批") },
                    supportingContent = { Text("创建新子代理并派发任务前需确认") },
                )

                switchItem(
                    checked = assistant.subAgentApproveFollowUp,
                    enabled = assistant.subAgentAllowMultiTurnAndAsync,
                    onCheckedChange = { needsApproval ->
                        onUpdateAssistant(assistant.copy(subAgentApproveFollowUp = needsApproval))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                    headlineContent = { Text("多轮追问子代理需审批") },
                    supportingContent = {
                        Text(
                            if (assistant.subAgentAllowMultiTurnAndAsync) {
                                "向已有子代理继续发送消息前需确认"
                            } else {
                                "未开启多轮追问权限"
                            }
                        )
                    },
                )

                switchItem(
                    checked = assistant.subAgentApproveInspect,
                    enabled = assistant.subAgentAllowInspect,
                    onCheckedChange = { needsApproval ->
                        onUpdateAssistant(assistant.copy(subAgentApproveInspect = needsApproval))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                    headlineContent = { Text("查看子代理状态需审批") },
                    supportingContent = {
                        Text(if (assistant.subAgentAllowInspect) "读取子代理对话记录与进度前需确认" else "未开启查看状态权限")
                    },
                )

                switchItem(
                    checked = assistant.subAgentApproveInterruptAndStop,
                    enabled = assistant.subAgentAllowInterruptAndStop,
                    onCheckedChange = { needsApproval ->
                        onUpdateAssistant(assistant.copy(subAgentApproveInterruptAndStop = needsApproval))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                    headlineContent = { Text("打断或停止子代理需审批") },
                    supportingContent = {
                        Text(
                            if (assistant.subAgentAllowInterruptAndStop) {
                                "中途打断或终止子代理前需确认"
                            } else {
                                "未开启打断与停止权限"
                            }
                        )
                    },
                )

                switchItem(
                    checked = assistant.subAgentApproveDelete,
                    enabled = assistant.subAgentAllowDelete,
                    onCheckedChange = { needsApproval ->
                        onUpdateAssistant(assistant.copy(subAgentApproveDelete = needsApproval))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                    headlineContent = { Text("删除子代理需审批") },
                    supportingContent = {
                        Text(if (assistant.subAgentAllowDelete) "彻底删除子代理对话前需确认" else "未开启删除对话权限")
                    },
                )
            }
        }
    }
}

/**
 * 子代理编辑面板：上方管理该子代理可调用的工具（工作区工具、MCP、联网与记忆、技能），
 * 下方针对每个已开启的工具独立配置「用户审批」与「主代理审批」。
 * 名称、标签描述与模型统一跟随助手自身的「基础设定」。
 */
@Composable
private fun SubAgentToolsSheet(
    targetAssistant: Assistant,
    mcpServers: List<McpServerConfig>,
    skills: List<SkillMetadata>,
    onUpdateAssistant: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultName = stringResource(R.string.assistant_page_default_assistant)
    val displayName = targetAssistant.name.ifBlank { defaultName }

    val workspaceToolItems = listOf(
        Triple("workspace_read_file", stringResource(R.string.workspace_detail_tool_read_file), HugeIcons.FileView),
        Triple("workspace_write_file", stringResource(R.string.workspace_detail_tool_write_file), HugeIcons.PencilEdit01),
        Triple("workspace_edit_file", stringResource(R.string.workspace_detail_tool_edit_file), HugeIcons.FileEdit),
        Triple("workspace_shell", stringResource(R.string.workspace_detail_tool_shell), HugeIcons.ComputerTerminal01),
        Triple("workspace_terminal", "交互式终端", HugeIcons.ComputerTerminal01),
    )

    val enabledMcpServers = remember(mcpServers) { mcpServers.filter { it.commonOptions.enable } }
    var showMaxStepsDialog by remember { mutableStateOf(false) }

    val activeApprovalItems = buildList {
        workspaceToolItems.forEach { (toolName, label, icon) ->
            if (toolName !in targetAssistant.disabledWorkspaceTools) {
                add(Triple(toolName, label, icon))
            }
        }
        enabledMcpServers.forEach { server ->
            if (server.id in targetAssistant.mcpServers) {
                add(Triple("mcp:${server.id}", "MCP: ${server.commonOptions.name}", HugeIcons.McpServer))
            }
        }
        if (targetAssistant.enableWebSearch) {
            add(Triple("web_search", "联网搜索", HugeIcons.GlobalSearch))
        }
        if (targetAssistant.enableMemory) {
            add(Triple("memory", "长期记忆", HugeIcons.Brain02))
        }
        if (targetAssistant.enabledSkills.isNotEmpty() && skills.any { it.name in targetAssistant.enabledSkills }) {
            add(Triple("skills", "技能调用", HugeIcons.Puzzle))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "子代理工具管理: $displayName",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "上方开启子代理可使用的工具，下方针对已开启的工具设置是否需要用户或主代理审批",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 1. 工具步数限制
            CardGroup(
                title = { Text("工具步数限制") }
            ) {
                val currentMaxSteps = targetAssistant.subAgentMaxSteps.coerceAtLeast(0)
                item(
                    onClick = { showMaxStepsDialog = true },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Clock01,
                            containerColor = if (currentMaxSteps > 0) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (currentMaxSteps > 0) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("工具调用次数限制") },
                    supportingContent = {
                        Text("主代理未填参时按此默认值执行")
                    },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    if (currentMaxSteps > 0) {
                                        onUpdateAssistant(targetAssistant.copy(subAgentMaxSteps = currentMaxSteps - 1))
                                    }
                                },
                                enabled = currentMaxSteps > 0,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Text("-", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = if (currentMaxSteps <= 0) "不设限" else "${currentMaxSteps}步",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            FilledTonalIconButton(
                                onClick = {
                                    onUpdateAssistant(targetAssistant.copy(subAgentMaxSteps = currentMaxSteps + 1))
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Text("+", fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                )
            }

            // 2. 工作区工具
            CardGroup(
                title = { Text("工作区工具") }
            ) {
                workspaceToolItems.forEach { (toolName, label, icon) ->
                    val enabled = toolName !in targetAssistant.disabledWorkspaceTools
                    switchItem(
                        checked = enabled,
                        onCheckedChange = { nextEnabled ->
                            val nextDisabled = if (nextEnabled) {
                                targetAssistant.disabledWorkspaceTools - toolName
                            } else {
                                targetAssistant.disabledWorkspaceTools + toolName
                            }
                            onUpdateAssistant(
                                targetAssistant.copy(
                                    disabledWorkspaceTools = nextDisabled,
                                    subAgentUserApprovalTools = if (nextEnabled) targetAssistant.subAgentUserApprovalTools else targetAssistant.subAgentUserApprovalTools - toolName,
                                    subAgentAgentApprovalTools = if (nextEnabled) targetAssistant.subAgentAgentApprovalTools else targetAssistant.subAgentAgentApprovalTools - toolName,
                                )
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = icon,
                                containerColor = if (enabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (enabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        headlineContent = { Text(label) },
                    )
                }
            }

            // 3. MCP 工具服务
            CardGroup(
                title = { Text("MCP 服务") }
            ) {
                if (enabledMcpServers.isEmpty()) {
                    item(
                        headlineContent = { Text("暂无已启用的全局 MCP 服务") },
                        supportingContent = { Text("可在应用设置 -> MCP 中添加并启用服务") },
                        leadingContent = { CardGroupIcon(HugeIcons.McpServer) },
                    )
                } else {
                    enabledMcpServers.forEach { server ->
                        val checked = server.id in targetAssistant.mcpServers
                        val toolCount = server.commonOptions.tools.count { it.enable }
                        val ruleKey = "mcp:${server.id}"
                        switchItem(
                            checked = checked,
                            onCheckedChange = { nextChecked ->
                                val nextServers = if (nextChecked) {
                                    targetAssistant.mcpServers + server.id
                                } else {
                                    targetAssistant.mcpServers - server.id
                                }
                                onUpdateAssistant(
                                    targetAssistant.copy(
                                        mcpServers = nextServers,
                                        subAgentUserApprovalTools = if (nextChecked) targetAssistant.subAgentUserApprovalTools else targetAssistant.subAgentUserApprovalTools - ruleKey,
                                        subAgentAgentApprovalTools = if (nextChecked) targetAssistant.subAgentAgentApprovalTools else targetAssistant.subAgentAgentApprovalTools - ruleKey,
                                    )
                                )
                            },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.McpServer,
                                    containerColor = if (checked) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (checked) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            headlineContent = { Text(server.commonOptions.name) },
                            supportingContent = { Text("已启用 $toolCount 个工具") },
                        )
                    }
                }
            }

            // 4. 联网与记忆（已移除子代理无需使用的本地工具）
            CardGroup(
                title = { Text("联网与记忆") }
            ) {
                switchItem(
                    checked = targetAssistant.enableWebSearch,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(
                            targetAssistant.copy(
                                enableWebSearch = enabled,
                                subAgentUserApprovalTools = if (enabled) targetAssistant.subAgentUserApprovalTools else targetAssistant.subAgentUserApprovalTools - "web_search",
                                subAgentAgentApprovalTools = if (enabled) targetAssistant.subAgentAgentApprovalTools else targetAssistant.subAgentAgentApprovalTools - "web_search",
                            )
                        )
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.GlobalSearch,
                            containerColor = if (targetAssistant.enableWebSearch) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (targetAssistant.enableWebSearch) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("联网搜索") },
                    supportingContent = { Text("允许子代理进行网页搜索与网页内容抓取") },
                )

                switchItem(
                    checked = targetAssistant.enableMemory,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(
                            targetAssistant.copy(
                                enableMemory = enabled,
                                subAgentUserApprovalTools = if (enabled) targetAssistant.subAgentUserApprovalTools else targetAssistant.subAgentUserApprovalTools - "memory",
                                subAgentAgentApprovalTools = if (enabled) targetAssistant.subAgentAgentApprovalTools else targetAssistant.subAgentAgentApprovalTools - "memory",
                            )
                        )
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Brain02,
                            containerColor = if (targetAssistant.enableMemory) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (targetAssistant.enableMemory) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text("长期记忆") },
                    supportingContent = { Text("允许子代理读取和更新长期记忆") },
                )
            }

            // 5. 技能 (Skills)
            if (skills.isNotEmpty()) {
                CardGroup(
                    title = { Text("技能") }
                ) {
                    skills.forEach { skill ->
                        val checked = skill.name in targetAssistant.enabledSkills
                        switchItem(
                            checked = checked,
                            onCheckedChange = { nextChecked ->
                                val nextSkills = if (nextChecked) {
                                    targetAssistant.enabledSkills + skill.name
                                } else {
                                    targetAssistant.enabledSkills - skill.name
                                }
                                onUpdateAssistant(
                                    targetAssistant.copy(
                                        enabledSkills = nextSkills,
                                        subAgentUserApprovalTools = if (nextSkills.isNotEmpty()) targetAssistant.subAgentUserApprovalTools else targetAssistant.subAgentUserApprovalTools - "skills",
                                        subAgentAgentApprovalTools = if (nextSkills.isNotEmpty()) targetAssistant.subAgentAgentApprovalTools else targetAssistant.subAgentAgentApprovalTools - "skills",
                                    )
                                )
                            },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.Puzzle,
                                    containerColor = if (checked) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (checked) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            headlineContent = { Text(skill.name) },
                            supportingContent = if (skill.description.isNotBlank()) {
                                {
                                    Text(
                                        text = skill.description,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }

            // 6. 已开启工具的调用审批设置（分为【用户审批】与【主代理审批】）
            CardGroup(
                title = { Text("工具调用审批（默认免审批直接执行）") }
            ) {
                if (activeApprovalItems.isEmpty()) {
                    item(
                        headlineContent = { Text("当前未开启任何工具") },
                        supportingContent = { Text("在上方开启工具后，可在此设置该工具是否需要用户或主代理审批") },
                        leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                    )
                } else {
                    activeApprovalItems.forEach { (ruleKey, label, icon) ->
                        val userApprove = ruleKey in targetAssistant.subAgentUserApprovalTools
                        val agentApprove = ruleKey in targetAssistant.subAgentAgentApprovalTools
                        val modeHint = when {
                            userApprove && agentApprove -> "用户或主代理均可审批"
                            userApprove -> "仅用户审批"
                            agentApprove -> "仅主代理审批（不弹用户窗）"
                            else -> "免审批直接执行"
                        }
                        item(
                            leadingContent = {
                                CardGroupIcon(
                                    icon = icon,
                                    containerColor = if (userApprove || agentApprove) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (userApprove || agentApprove) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            headlineContent = { Text(label) },
                            supportingContent = {
                                Text(
                                    text = modeHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (userApprove || agentApprove) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FilterChip(
                                        selected = userApprove,
                                        onClick = {
                                            val next = if (userApprove) {
                                                targetAssistant.subAgentUserApprovalTools - ruleKey
                                            } else {
                                                targetAssistant.subAgentUserApprovalTools + ruleKey
                                            }
                                            onUpdateAssistant(targetAssistant.copy(subAgentUserApprovalTools = next))
                                        },
                                        label = {
                                            Text("用户", style = MaterialTheme.typography.labelSmall)
                                        },
                                    )
                                    FilterChip(
                                        selected = agentApprove,
                                        onClick = {
                                            val next = if (agentApprove) {
                                                targetAssistant.subAgentAgentApprovalTools - ruleKey
                                            } else {
                                                targetAssistant.subAgentAgentApprovalTools + ruleKey
                                            }
                                            onUpdateAssistant(targetAssistant.copy(subAgentAgentApprovalTools = next))
                                        },
                                        label = {
                                            Text("主代理", style = MaterialTheme.typography.labelSmall)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showMaxStepsDialog) {
        var inputText by remember(targetAssistant.subAgentMaxSteps) {
            mutableStateOf(targetAssistant.subAgentMaxSteps.coerceAtLeast(0).toString())
        }
        AlertDialog(
            onDismissRequest = { showMaxStepsDialog = false },
            title = { Text("工具调用次数限制") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "设置该子代理单次任务默认最多连续调用工具的步数。填 0 表示不设限；当达到步数上限时，子代理会停止继续调用工具并直接输出最终回复。若主代理在调用时显式传入了步数参数，则以主代理参数为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { value ->
                            inputText = value.filter { it.isDigit() }
                        },
                        label = { Text("最大工具步数（0 = 不设限）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = inputText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        onUpdateAssistant(targetAssistant.copy(subAgentMaxSteps = parsed))
                        showMaxStepsDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMaxStepsDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun CreateSubAssistantDialog(
    defaultModelId: Uuid?,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, modelId: Uuid?, systemPrompt: String) -> Unit,
) {
    var name by remember { mutableStateOf("代码与任务子代理") }
    var selectedModelId by remember { mutableStateOf(defaultModelId) }
    var systemPrompt by remember { mutableStateOf("") }

    val modelListState = rememberModelListState(
        modelId = selectedModelId,
        providers = providers,
        type = ModelType.CHAT,
    )
    ModelListSheet(
        state = modelListState,
        onSelect = { selectedModelId = it.id },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建并绑定子代理助手") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("子代理助手名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "子代理使用的聊天模型",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    ModelField(
                        model = modelListState.currentModel,
                        onClick = { modelListState.open() },
                    )
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("子代理专属系统提示词 (可选)") },
                    placeholder = { Text("例如：你是一个专注于高效执行终端命令与编写代码的子代理…") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, selectedModelId, systemPrompt) },
            ) {
                Text("创建并绑定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
