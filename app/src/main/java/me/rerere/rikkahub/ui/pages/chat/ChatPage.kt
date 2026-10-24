package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.allChildConversationIds
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.ui.components.DotLoading
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalTab
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalTabInfo
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(id, nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.scrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()

    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    var showFilesSheet by remember { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TopBar(
                        settings = setting,
                        assistant = assistant,
                        chatModel = currentChatModel,
                        conversation = conversation,
                        bigScreen = bigScreen,
                        drawerState = drawerState,
                        previewMode = previewMode,
                        onNewChat = {
                            navigateToChatPage(navController)
                        },
                        onClickMenu = {
                            previewMode = !previewMode
                        },
                        onUpdateTitle = {
                            vm.updateTitle(it)
                        }
                    )
                    SubAgentFollowerBar(
                        conversation = conversation,
                        assistant = assistant,
                        settings = setting,
                        workspaceRepository = workspaceRepository,
                        vm = vm,
                        navController = navController,
                    )
                }
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                ChatInput(
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    loading = loadingJob != null,
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    onUpdateSearchMode = { mode ->
                        vm.updateSearch(
                            enableWebSearch = mode == SearchMode.LOCAL,
                            builtInSearch = mode == SearchMode.BUILT_IN,
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                delay(100.milliseconds)
                                chatListState.scrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.scrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = vm::setChatModel,
                    onUpdateAssistant = vm::updateAssistant,
                    onUpdateSearchService = { index ->
                        vm.updateSettings { it.copy(searchServiceSelected = index) }
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.scrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            chatModel = chatModel,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = vm::updateAssistant,
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onSketch = attachmentPickerActions.onSketch,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    assistant: Assistant,
    chatModel: Model?,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val provider = chatModel?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chatModel != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${chatModel.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

/**
 * 聊天页顶栏下方的「工作区终端 & 子代理跟随栏」：
 * - 当当前对话绑定的工作区有活跃交互式 PTY 终端时，展示跟随工作区的「🖥 终端 (N)」快捷胶囊，点击一键直达终端页；
 * - 当当前对话是子代理时，展示返回主对话的快捷胶囊；
 * - 当当前对话拥有子代理（通过 `conversation_send` 创建或 Fork 继承）时，实时展示所有跟随的子代理状态胶囊，
 *   点击胶囊可弹出实时窥屏与交互小窗，或一键跳转到子代理全屏对话页。
 */
@Composable
private fun SubAgentFollowerBar(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    workspaceRepository: WorkspaceRepository,
    vm: ChatVM,
    navController: Navigator,
) {
    val parentId = conversation.config?.parentConversationId
    val childIds = remember(conversation) { conversation.allChildConversationIds.toList() }
    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(initialValue = emptyMap())
    var peekingChildId by remember(conversation.id) { mutableStateOf<Uuid?>(null) }
    var peekingTerminalTabId by remember(conversation.id) { mutableStateOf<Long?>(null) }

    val terminalSessionManager: WorkspaceTerminalSessionManager = koinInject()
    val workspacesFlow = remember(workspaceRepository) { workspaceRepository.listFlow() }
    val workspaces by workspacesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    val allTerminalStates by terminalSessionManager.allWorkspaceStates.collectAsStateWithLifecycle()
    val activeWorkspaceTabs = remember(boundWorkspace?.root, allTerminalStates) {
        boundWorkspace?.root?.let { root ->
            allTerminalStates[root]?.tabs?.filterNot { it.finished }.orEmpty()
        }.orEmpty()
    }

    if (parentId == null && childIds.isEmpty() && activeWorkspaceTabs.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (boundWorkspace != null && activeWorkspaceTabs.isNotEmpty()) {
            Text(
                text = "工作区终端 (${activeWorkspaceTabs.size}):",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            activeWorkspaceTabs.forEach { tab ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { peekingTerminalTabId = tab.id },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.extendColors.green6)
                        )
                        val tabTitle = (tab.customTitle ?: tab.title)?.takeIf { it.isNotBlank() }
                        val label = if (tabTitle != null) {
                            "🖥 #${tab.id} $tabTitle"
                        } else {
                            "🖥 终端 #${tab.id}"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (parentId != null) {
            val parentFlow = remember(parentId) { vm.getSubConversationFlow(parentId) }
            val parentConv by parentFlow.collectAsStateWithLifecycle()
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { navigateToChatPage(navController, chatId = parentId) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "⬅ 返回主对话: ${parentConv.title.ifBlank { "主对话" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (childIds.isNotEmpty()) {
            Text(
                text = "子代理 (${childIds.size}):",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            childIds.forEach { childId ->
                val childFlow = remember(childId) { vm.getSubConversationFlow(childId) }
                val childConv by childFlow.collectAsStateWithLifecycle()
                val isRunning = childId in conversationJobs
                val childAssistant = remember(settings, childConv.assistantId, childConv.config) {
                    settings.getAssistantOf(childConv)
                }
                val childModel = remember(settings, childConv.assistantId, childConv.config) {
                    settings.getChatModelOf(childConv)
                }
                val mcpServerNameToId = remember(settings.mcpServers) {
                    settings.mcpServers.associate { it.commonOptions.name to it.id }
                }
                val pendingUserApprovalCount = remember(childConv.messageNodes, childAssistant, mcpServerNameToId) {
                    childConv.currentMessages
                        .flatMap { it.parts }
                        .filterIsInstance<UIMessagePart.Tool>()
                        .count { tool ->
                            tool.isPending && (
                                childAssistant.requiresSubAgentUserApproval(tool.toolName, mcpServerNameToId) ||
                                    !childAssistant.requiresSubAgentAgentApproval(tool.toolName, mcpServerNameToId)
                                )
                        }
                }
                val hasCustomSubAssistant = childConv.assistantId != conversation.assistantId
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when {
                        pendingUserApprovalCount > 0 -> MaterialTheme.colorScheme.errorContainer
                        isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { peekingChildId = childId },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isRunning) {
                            DotLoading(size = 8.dp)
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (pendingUserApprovalCount > 0) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.extendColors.green6
                                        }
                                    )
                            )
                        }
                        val rawTitle = childConv.title.trim()
                        val baseTitle = when {
                            rawTitle.isBlank() || rawTitle.startsWith("Sub-", ignoreCase = true) ->
                                "子代理-${childId.toString().take(4)}"
                            else -> rawTitle
                        }
                        val badgeText = buildString {
                            if (hasCustomSubAssistant) {
                                val aName = childAssistant.name.ifBlank { "子代理" }
                                append("$baseTitle · $aName")
                            } else if (childModel != null) {
                                append("$baseTitle (${childModel.displayName})")
                            } else {
                                append(baseTitle)
                            }
                            if (pendingUserApprovalCount > 0) {
                                append(" · ⚠️待审批")
                            }
                        }
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pendingUserApprovalCount > 0) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    peekingChildId?.let { targetChildId ->
        SubAgentPeekSheet(
            childId = targetChildId,
            settings = settings,
            vm = vm,
            onDismiss = { peekingChildId = null },
            onOpenFullPage = {
                peekingChildId = null
                navigateToChatPage(navController, chatId = targetChildId)
            },
        )
    }

    if (peekingTerminalTabId != null && boundWorkspace != null) {
        WorkspaceTerminalsPeekSheet(
            workspaceName = boundWorkspace.name,
            workspaceRoot = boundWorkspace.root,
            shellCompatibilityMode = boundWorkspace.shellCompatibilityMode,
            initialTabId = peekingTerminalTabId!!,
            tabs = activeWorkspaceTabs,
            terminalSessionManager = terminalSessionManager,
            onDismiss = { peekingTerminalTabId = null },
            onOpenFullTerminalPage = {
                val selectedId = peekingTerminalTabId
                if (selectedId != null) {
                    terminalSessionManager.selectTab(boundWorkspace.root, selectedId)
                }
                peekingTerminalTabId = null
                navController.navigate(Screen.WorkspaceTerminal(boundWorkspace.id))
            },
        )
    }
}

@Composable
private fun WorkspaceTerminalsPeekSheet(
    workspaceName: String,
    workspaceRoot: String,
    shellCompatibilityMode: Boolean,
    initialTabId: Long,
    tabs: List<WorkspaceTerminalTab>,
    terminalSessionManager: WorkspaceTerminalSessionManager,
    onDismiss: () -> Unit,
    onOpenFullTerminalPage: () -> Unit,
) {
    var selectedTabId by remember(initialTabId) { mutableStateOf(initialTabId) }
    val currentTab = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.firstOrNull()
    var tabInfos by remember(workspaceRoot, tabs) {
        mutableStateOf(terminalSessionManager.listActiveTabs(workspaceRoot))
    }
    var screenSnapshot by remember(workspaceRoot, currentTab?.id) {
        mutableStateOf(
            currentTab?.let { terminalSessionManager.readTabScreenSnapshot(workspaceRoot, it.id) }.orEmpty()
        )
    }
    var commandInput by remember(currentTab?.id) { mutableStateOf("") }

    LaunchedEffect(workspaceRoot, currentTab?.id, tabs.size) {
        if (tabs.isEmpty()) {
            onDismiss()
            return@LaunchedEffect
        }
        if (currentTab != null && currentTab.id != selectedTabId) {
            selectedTabId = currentTab.id
        }
        while (true) {
            tabInfos = terminalSessionManager.listActiveTabs(workspaceRoot)
            val activeId = currentTab?.id
            if (activeId != null) {
                screenSnapshot = terminalSessionManager.readTabScreenSnapshot(workspaceRoot, activeId)
            }
            kotlinx.coroutines.delay(400)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        val currentInfo: WorkspaceTerminalTabInfo? = tabInfos.firstOrNull { it.id == currentTab?.id }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🖥 工作区「$workspaceName」活跃终端 (${tabs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (currentInfo != null) {
                        val statusText = when {
                            currentInfo.finished -> "已退出"
                            currentInfo.isAtPrompt -> "● 就绪 (PID ${currentInfo.shellPid})"
                            !currentInfo.foregroundCommand.isNullOrBlank() -> "◐ 运行中: ${currentInfo.foregroundCommand} (PGID ${currentInfo.foregroundPgid})"
                            else -> "◐ 运行中 / 等待输入 (PID ${currentInfo.shellPid})"
                        }
                        Text(
                            text = "终端 #${currentInfo.id} (${currentInfo.title ?: "shell"}) · $statusText",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (currentInfo.isAtPrompt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            terminalSessionManager.createTab(workspaceRoot, shellCompatibilityMode)
                        }
                    ) {
                        Text("+ 新建", style = MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(onClick = onOpenFullTerminalPage) {
                        Text("进入全屏终端 ↗", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 终端标签切换栏（支持直接查看多个终端、单独关闭某个终端）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val info = tabInfos.firstOrNull { it.id == tab.id }
                    val isSelected = tab.id == currentTab?.id
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { selectedTabId = tab.id },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val titleText = (tab.customTitle ?: tab.title)?.takeIf { it.isNotBlank() } ?: "终端 #${tab.number}"
                            val cmdSuffix = info?.foregroundCommand?.let { " ($it)" }.orEmpty()
                            Text(
                                text = "#${tab.id} $titleText$cmdSuffix",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        terminalSessionManager.closeTab(workspaceRoot, tab.id)
                                    }
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }

            // 终端实时屏幕快照预览
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF121212),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = screenSnapshot.ifBlank { "(终端屏幕暂无输出)" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = Color(0xFFE5E5E5),
                    )
                }
            }

            // 底部快捷输入与控制栏
            if (currentTab != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        placeholder = { Text("向终端 #${currentTab.id} 发送命令 (如 ls, exit)...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            terminalSessionManager.writeToTab(
                                root = workspaceRoot,
                                tabId = currentTab.id,
                                text = "\u0003",
                                appendEnter = false,
                            )
                        }
                    ) {
                        Text("Ctrl+C", style = MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(
                        enabled = commandInput.isNotBlank(),
                        onClick = {
                            val cmd = commandInput
                            commandInput = ""
                            terminalSessionManager.writeToTab(
                                root = workspaceRoot,
                                tabId = currentTab.id,
                                text = cmd,
                                appendEnter = true,
                            )
                        }
                    ) {
                        Text("发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun SubAgentPeekSheet(
    childId: Uuid,
    settings: Settings,
    vm: ChatVM,
    onDismiss: () -> Unit,
    onOpenFullPage: () -> Unit,
) {
    val childFlow = remember(childId) { vm.getSubConversationFlow(childId) }
    val childConv by childFlow.collectAsStateWithLifecycle()
    val statusFlow = remember(childId) { vm.getSubConversationProcessingStatusFlow(childId) }
    val procStatus by statusFlow.collectAsStateWithLifecycle()
    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val isRunning = childId in conversationJobs
    var followUpInput by remember(childId) { mutableStateOf("") }
    val childAssistant = remember(settings, childConv.assistantId, childConv.config) {
        settings.getAssistantOf(childConv)
    }
    val childModel = remember(settings, childConv.assistantId, childConv.config) {
        settings.getChatModelOf(childConv)
    }
    val mcpServerNameToId = remember(settings.mcpServers) {
        settings.mcpServers.associate { it.commonOptions.name to it.id }
    }
    // 仅筛选出允许【用户审批】的待审批工具（只开主代理审批的工具不对用户弹出审批卡片）
    val userApprovablePendingTools = remember(childConv.messageNodes, childAssistant, mcpServerNameToId) {
        childConv.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { tool ->
                tool.isPending && (
                    childAssistant.requiresSubAgentUserApproval(tool.toolName, mcpServerNameToId) ||
                        !childAssistant.requiresSubAgentAgentApproval(tool.toolName, mcpServerNameToId)
                    )
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
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = childConv.title.trim().takeIf { it.isNotEmpty() && !it.startsWith("Sub-", ignoreCase = true) }
                            ?: "子代理-${childId.toString().take(4)}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("助手: ${childAssistant.name.ifBlank { "默认助手" }}")
                            if (childModel != null) append(" · 模型: ${childModel.displayName}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(if (isRunning) "🟢 运行中" else "⚪ 已就绪/空闲")
                            if (!procStatus.isNullOrBlank()) append(" · $procStatus")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isRunning) {
                        TextButton(onClick = { vm.stopSubAgent(childId) }) {
                            Text("停止", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = {
                            onDismiss()
                            vm.deleteSubAgent(childId)
                        }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    FilledTonalButton(onClick = onOpenFullPage) {
                        Text("进入完整对话 ↗", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val messages = childConv.currentMessages
                if (messages.isEmpty()) {
                    Text(
                        text = "暂无消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    messages.forEach { msg ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (msg.role == MessageRole.USER) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = if (msg.role == MessageRole.USER) "主代理/用户指令" else "子代理回复",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val tools = msg.getTools()
                                if (tools.isNotEmpty()) {
                                    Text(
                                        text = "调用工具 (${tools.size}): " + tools.joinToString(", ") {
                                            "${it.toolName}${if (it.isExecuted) "✓" else "…"}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                val body = msg.toText().trim()
                                if (body.isNotEmpty()) {
                                    Text(
                                        text = body,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (userApprovablePendingTools.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "⚠️ 待你审批的工具调用 (${userApprovablePendingTools.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        userApprovablePendingTools.forEach { tool ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = tool.toolName,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        text = tool.input.take(300),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        ),
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                vm.handleSubAgentToolApproval(
                                                    targetConversationId = childId,
                                                    toolCallId = tool.toolCallId,
                                                    approved = false,
                                                    reason = "Denied by user",
                                                )
                                            }
                                        ) {
                                            Text("拒绝", color = MaterialTheme.colorScheme.error)
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                vm.handleSubAgentToolApproval(
                                                    targetConversationId = childId,
                                                    toolCallId = tool.toolCallId,
                                                    approved = true,
                                                )
                                            }
                                        ) {
                                            Text("批准执行")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = followUpInput,
                    onValueChange = { followUpInput = it },
                    placeholder = { Text("向该子代理发送补充指令...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                FilledTonalButton(
                    enabled = followUpInput.isNotBlank(),
                    onClick = {
                        val textToSend = followUpInput
                        followUpInput = ""
                        vm.sendToSubAgent(
                            targetConversationId = childId,
                            message = textToSend,
                            interrupt = isRunning,
                        )
                    }
                ) {
                    Text(if (isRunning) "打断并发送" else "发送")
                }
            }
        }
    }
}

