package me.rerere.rikkahub.ui.components.ai

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.custom.LocalActiveUIPreset
import me.rerere.rikkahub.ui.custom.UIModalBottomSheet
import me.rerere.rikkahub.ui.custom.UISlot
import me.rerere.rikkahub.ui.custom.colorOr
import me.rerere.rikkahub.ui.custom.hasUICustomShapes
import me.rerere.rikkahub.ui.custom.uiComponentStyle
import me.rerere.rikkahub.ui.custom.uiCornerShape
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject

@Composable
internal fun FilesPickerSheet(
    sheetState: SheetState,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onSketch: () -> Unit,
    onStartVoiceMode: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())

    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    val terminalSessionManager: WorkspaceTerminalSessionManager = koinInject()
    val allTerminalStates by terminalSessionManager.allWorkspaceStates.collectAsState()
    val activeTerminalTabs = remember(boundWorkspace?.root, allTerminalStates) {
        boundWorkspace?.root?.let { root ->
            allTerminalStates[root]?.tabs?.filterNot { it.finished }.orEmpty()
        }.orEmpty()
    }
    val showCwd = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showCwdSheet by remember { mutableStateOf(false) }
    var showMcpSheet by remember { mutableStateOf(false) }
    var showExtensionSheet by remember { mutableStateOf(false) }

    val boundSubAssistants = remember(settings.assistants, assistant.effectiveSubAgentIds) {
        val ids = assistant.effectiveSubAgentIds
        settings.assistants.filter { it.id in ids }
    }
    val defaultName = stringResource(R.string.assistant_page_default_assistant)
    val subSupporting = when {
        !assistant.isSubAgentEnabled -> "未绑定子代理"
        else -> "已绑定 ${boundSubAssistants.size} 个: " + boundSubAssistants.joinToString(", ") {
            it.name.ifBlank { defaultName }
        }
    }
    val activeExtensionCount =
        assistant.quickMessageIds.size +
            assistant.modeInjectionIds.size +
            assistant.lorebookIds.size +
            assistant.enabledSkills.size
    val enabledMcpCount = settings.mcpServers.count { it.commonOptions.enable && it.id in assistant.mcpServers }
    val isGoogle = provider != null && provider is ProviderSetting.Google

    val attachmentActions = buildList {
        add(AttachmentAction(HugeIcons.Camera01, R.string.take_picture, onTakePic))
        add(AttachmentAction(HugeIcons.Image02, R.string.photo, onPickImage))
        add(AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, onSketch))
        if (isGoogle) {
            add(AttachmentAction(HugeIcons.Video01, R.string.video, onPickVideo))
            add(AttachmentAction(HugeIcons.MusicNote03, R.string.audio, onPickAudio))
        }
        add(AttachmentAction(HugeIcons.Files02, R.string.upload_file, onPickFile))
        onStartVoiceMode?.let { start ->
            add(AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, start))
        }
    }

    val renderAttachments: @Composable (Modifier) -> Unit = { mod ->
        AttachmentGrid(actions = attachmentActions, modifier = mod)
    }

    val renderOptions: @Composable (Modifier) -> Unit = { mod ->
        CardGroup(modifier = mod) {
            // 1. 工作区
            item(
                onClick = { showWorkspaceSheet = true },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Codesandbox,
                        contentDescription = null,
                    )
                },
                supportingContent = {
                    val baseName = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound)
                    val terminalInfo = if (activeTerminalTabs.isNotEmpty()) {
                        val ids = activeTerminalTabs.joinToString(", ") { "#${it.id}" }
                        "$baseName · 已开启 ${activeTerminalTabs.size} 个终端 ($ids)"
                    } else {
                        baseName
                    }
                    Text(
                        text = terminalInfo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (boundWorkspace != null) {
                            IconButton(onClick = {
                                onDismiss()
                                navController.navigate(Screen.WorkspaceDetail(boundWorkspace.id))
                            }) {
                                Icon(
                                    imageVector = HugeIcons.Settings02,
                                    contentDescription = stringResource(R.string.workspace_detail),
                                )
                            }
                            if (boundWorkspace.supportsTerminal()) {
                                IconButton(onClick = {
                                    onDismiss()
                                    navController.navigate(Screen.WorkspaceTerminal(boundWorkspace.id))
                                }) {
                                    Icon(
                                        imageVector = HugeIcons.ComputerTerminal01,
                                        contentDescription = stringResource(R.string.workspace_terminal),
                                    )
                                }
                            }
                        } else {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        }
                    }
                },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
            )

            // 2. 工作目录 (CWD)
            if (showCwd) {
                item(
                    onClick = { showCwdSheet = true },
                    leadingContent = {
                        Icon(
                            imageVector = HugeIcons.Folder01,
                            contentDescription = stringResource(R.string.workspace_cwd_select_directory),
                        )
                    },
                    trailingContent = {
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    },
                    headlineContent = {
                        Text(
                            text = conversation.workspaceCwd ?: boundWorkspace?.workspaceDir().orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    },
                )
            }

            // 3. 子代理
            item(
                onClick = {
                    onDismiss()
                    navController.navigate(Screen.AssistantSubAgent(assistant.id.toString()))
                },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Message02,
                        contentDescription = "子代理",
                    )
                },
                supportingContent = {
                    Text(
                        text = subSupporting,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    if (boundSubAssistants.isNotEmpty() && assistant.isSubAgentEnabled) {
                        CountBadge(count = boundSubAssistants.size)
                    } else {
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    }
                },
                headlineContent = {
                    Text("子代理")
                },
            )

            // 4. MCP
            if (settings.mcpServers.isNotEmpty()) {
                item(
                    onClick = { showMcpSheet = true },
                    leadingContent = {
                        Icon(
                            imageVector = HugeIcons.McpServer,
                            contentDescription = null,
                        )
                    },
                    trailingContent = {
                        if (enabledMcpCount > 0) {
                            CountBadge(count = enabledMcpCount)
                        } else {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        }
                    },
                    headlineContent = {
                        Text("MCP")
                    },
                )
            }

            // 5. 扩展管理
            item(
                onClick = { showExtensionSheet = true },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Package,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    if (activeExtensionCount > 0) {
                        CountBadge(count = activeExtensionCount)
                    } else {
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    }
                },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_tab_extensions))
                },
            )

            // 6. 压缩历史
            item(
                onClick = { onShowCompressDialogChange(true) },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Package01,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                headlineContent = {
                    Text(stringResource(R.string.chat_page_compress_context))
                },
            )
        }
    }

    val renderPlugins: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            me.rerere.rikkahub.ui.pages.extensions.plugin.PluginChatMoreMounts(
                conversationId = conversation.id.toString(),
                assistantId = assistant.id.toString(),
                onDismissSheet = onDismiss,
            )
        }
    }

    val renderContent: @Composable (Modifier) -> Unit = { mod ->
        Column(
            modifier = mod
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            renderAttachments(Modifier)
            renderOptions(Modifier)
            renderPlugins(Modifier)
        }
    }

    val renderSubSheets: @Composable () -> Unit = {
        if (showWorkspaceSheet) {
            WorkspaceSelectSheet(
                assistant = assistant,
                workspaces = workspaces,
                onSelect = { workspaceId ->
                    val newId = workspaceId?.let { Uuid.parse(it) }
                    if (newId != assistant.workspaceId) {
                        onUpdateAssistant(assistant.copy(workspaceId = newId))
                    }
                    showWorkspaceSheet = false
                },
                onManage = {
                    showWorkspaceSheet = false
                    onDismiss()
                    navController.navigate(Screen.Workspaces)
                },
                onDismiss = { showWorkspaceSheet = false },
            )
        }

        if (showMcpSheet) {
            UIModalBottomSheet(
                onDismissRequest = { showMcpSheet = false },
                sheetState = rememberBottomSheetState(
                    initialValue = SheetValue.Expanded,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
            ) {
                McpPickerPage(
                    assistant = assistant,
                    servers = settings.mcpServers,
                    mcpManager = mcpManager,
                    onUpdateAssistant = onUpdateAssistant,
                    onBack = { showMcpSheet = false },
                )
            }
        }

        if (showExtensionSheet) {
            UIModalBottomSheet(
                onDismissRequest = { showExtensionSheet = false },
                sheetState = rememberBottomSheetState(
                    initialValue = SheetValue.Expanded,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.75f)
                        .padding(horizontal = 16.dp),
                ) {
                    SheetHeader(
                        title = stringResource(R.string.assistant_page_tab_extensions),
                    )
                    ExtensionSelector(
                        assistant = assistant,
                        settings = settings,
                        onUpdate = onUpdateAssistant,
                        modifier = Modifier.weight(1f),
                        onNavigateToQuickMessages = { quickMessageId ->
                            showExtensionSheet = false
                            onDismiss()
                            navController.navigate(Screen.QuickMessages(quickMessageId = quickMessageId))
                        },
                        onNavigateToPrompts = { initialTab, presetId, lorebookId ->
                            showExtensionSheet = false
                            onDismiss()
                            navController.navigate(
                                Screen.Prompts(
                                    initialTab = initialTab,
                                    presetId = presetId,
                                    lorebookId = lorebookId,
                                )
                            )
                        },
                        onNavigateToSkills = { skillName ->
                            showExtensionSheet = false
                            onDismiss()
                            if (skillName != null) {
                                navController.navigate(Screen.SkillDetail(skillName))
                            } else {
                                navController.navigate(Screen.Skills)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (showCwdSheet && boundWorkspace != null) {
            WorkspaceCwdPickerSheet(
                workspaceId = boundWorkspace.id,
                workspaceDir = boundWorkspace.workspaceDir(),
                currentCwd = conversation.workspaceCwd,
                onSelectCwd = { newCwd ->
                    onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                },
                onDismiss = { showCwdSheet = false },
            )
        }

        if (showCompressDialog) {
            CompressContextDialog(
                onDismiss = {
                    onShowCompressDialogChange(false)
                    onDismiss()
                },
                onConfirm = { additionalPrompt, targetTokens, keepRecentMessages ->
                    onCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
                }
            )
        }
    }

    val slotActive = LocalActiveUIPreset.current?.hasSlot("chat.more") == true
    UISlot(
        name = "chat.more",
        data = if (slotActive) mapOf(
            "assistant" to assistant.name.ifBlank { defaultName },
            "assistantId" to assistant.id.toString(),
            "conversationId" to conversation.id.toString(),
            "model" to chatModel?.displayName.orEmpty(),
            "isGoogle" to isGoogle,
            "canVoice" to (onStartVoiceMode != null),
            "hasWorkspace" to (boundWorkspace != null),
            "workspace" to boundWorkspace?.name.orEmpty(),
            "workspaceId" to boundWorkspace?.id.orEmpty(),
            "canTerminal" to (boundWorkspace?.supportsTerminal() == true),
            "showCwd" to showCwd,
            "cwd" to (conversation.workspaceCwd ?: boundWorkspace?.workspaceDir().orEmpty()),
            "subAgentEnabled" to assistant.isSubAgentEnabled,
            "subAgentCount" to boundSubAssistants.size,
            "subAgentSummary" to subSupporting,
            "hasMcp" to settings.mcpServers.isNotEmpty(),
            "mcpCount" to enabledMcpCount,
            "extensionCount" to activeExtensionCount,
            "messageCount" to conversation.messageNodes.size,
        ) else emptyMap(),
        parts = if (slotActive) mapOf(
            "content" to { info -> renderContent(info.modifier) },
            "attachments" to { info -> renderAttachments(info.modifier) },
            "options" to { info -> renderOptions(info.modifier) },
            "plugins" to { info -> renderPlugins(info.modifier) },
        ) else emptyMap(),
        actions = if (slotActive) buildMap {
            put("dismiss", onDismiss)
            put("close", onDismiss)
            put("take_photo", onTakePic)
            put("camera", onTakePic)
            put("pick_image", onPickImage)
            put("photo", onPickImage)
            put("sketch", onSketch)
            if (isGoogle) {
                put("pick_video", onPickVideo)
                put("video", onPickVideo)
                put("pick_audio", onPickAudio)
                put("audio", onPickAudio)
            }
            put("pick_file", onPickFile)
            put("file", onPickFile)
            if (onStartVoiceMode != null) {
                put("voice", onStartVoiceMode)
            }
            put("open_workspace") { showWorkspaceSheet = true }
            if (boundWorkspace != null) {
                put("open_workspace_detail") {
                    onDismiss()
                    navController.navigate(Screen.WorkspaceDetail(boundWorkspace.id))
                }
                if (boundWorkspace.supportsTerminal()) {
                    put("open_terminal") {
                        onDismiss()
                        navController.navigate(Screen.WorkspaceTerminal(boundWorkspace.id))
                    }
                }
            }
            if (showCwd) {
                put("open_cwd") { showCwdSheet = true }
            }
            put("open_sub_agents") {
                onDismiss()
                navController.navigate(Screen.AssistantSubAgent(assistant.id.toString()))
            }
            if (settings.mcpServers.isNotEmpty()) {
                put("open_mcp") { showMcpSheet = true }
            }
            put("open_extensions") { showExtensionSheet = true }
            put("compress") { onShowCompressDialogChange(true) }
        } else emptyMap(),
        whenReplaced = renderSubSheets,
    ) {
        UIModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = onDismiss,
        ) {
            renderContent(Modifier)
        }
        renderSubSheets()
    }
}

private class AttachmentAction(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val onClick: () -> Unit,
)

@Composable
private fun AttachmentGrid(
    actions: List<AttachmentAction>,
    modifier: Modifier = Modifier,
) {
    val perRow = if (actions.size <= 4) actions.size else (actions.size + 1) / 2
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(perRow).fastForEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.fastForEach { action ->
                    AttachmentTile(
                        action = action,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
) {
    val tileStyle = uiComponentStyle("attachment_tile") ?: uiComponentStyle("card")
    val scheme = MaterialTheme.colorScheme
    val tileShape = tileStyle?.cornerDp?.let { uiCornerShape(it.dp) }
        ?: if (hasUICustomShapes()) MaterialTheme.shapes.large else RoundedCornerShape(16.dp)
    val tileBorder = tileStyle?.borderWidthDp?.takeIf { it > 0f }?.let {
        BorderStroke(it.dp, tileStyle.colorOr({ s -> s.borderColor }, scheme, scheme.outlineVariant))
    }
    Surface(
        onClick = action.onClick,
        shape = tileShape,
        color = tileStyle.colorOr({ it.background }, scheme, scheme.surfaceContainerLow),
        border = tileBorder,
        modifier = modifier.heightIn(min = 76.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = (tileStyle?.paddingHorizontalDp ?: 8f).dp,
                vertical = (tileStyle?.paddingVerticalDp ?: 14f).dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size((tileStyle?.widthDp ?: 24f).dp),
                tint = tileStyle.colorOr({ it.color }, scheme, scheme.primary),
            )
            Text(
                text = stringResource(action.label),
                style = MaterialTheme.typography.labelMedium,
                color = tileStyle.colorOr({ it.secondaryColor }, scheme, androidx.compose.ui.graphics.Color.Unspecified),
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 13.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CountBadge(count: Int) {
    val badgeStyle = uiComponentStyle("card_group.value") ?: uiComponentStyle("tag")
    if (badgeStyle == null && !hasUICustomShapes()) {
        Badge(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Text(count.toString())
        }
        return
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = badgeStyle?.cornerDp?.let { uiCornerShape(it.dp) } ?: MaterialTheme.shapes.extraSmall,
        color = badgeStyle.colorOr({ it.background }, scheme, scheme.primaryContainer),
        contentColor = badgeStyle.colorOr({ it.color }, scheme, scheme.onPrimaryContainer),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
