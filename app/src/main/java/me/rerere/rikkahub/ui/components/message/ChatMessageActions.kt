package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.pages.extensions.plugin.uiSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import me.rerere.rikkahub.ui.custom.uiSegmentedItemBorder
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.UIMessageActionType
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.ui.components.RikkaConfirmDialog
import java.util.Locale

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    var isPendingDelete by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ChatMessageActionButton(
            icon = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.copy),
            onClick = { context.copyMessageToClipboard(message) },
        )

        ChatMessageActionButton(
            icon = HugeIcons.Refresh03,
            contentDescription = stringResource(R.string.regenerate),
            onClick = {
                if (message.role == MessageRole.USER) {
                    showRegenerateConfirm = true
                } else {
                    onRegenerate()
                }
            },
        )

        if (message.role == MessageRole.ASSISTANT) {
            val tts = LocalTTSState.current
            val isSpeaking by tts.isSpeaking.collectAsState()
            val isAvailable by tts.isAvailable.collectAsState()
            // 朗读中换成带底色的方角按钮
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconToggleButton(
                    checked = isSpeaking,
                    onCheckedChange = { speak ->
                        if (speak) {
                            val text = message.toText()
                            var textToSpeak = text
                            if (settings.displaySetting.ttsOnlyReadQuoted) {
                                textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                            }
                            if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                                textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                            }
                            tts.speak(textToSpeak)
                        } else {
                            tts.stop()
                        }
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
                    enabled = isAvailable,
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                        contentDescription = stringResource(R.string.tts),
                        modifier = Modifier.size(ActionIconSize),
                    )
                }
            }

            // Translation button
            if (onTranslate != null) {
                ChatMessageActionButton(
                    icon = HugeIcons.Translate,
                    contentDescription = stringResource(R.string.translate),
                    onClick = { showTranslateDialog = true },
                )
            }
        }

        ChatMessageActionButton(
            icon = HugeIcons.MoreVertical,
            contentDescription = stringResource(R.string.more_options),
            onClick = onOpenActionSheet,
        )

        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
        )

        if (settings.displaySetting.showDateTimeInMessage) {
            Text(
                text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    // Regenerate confirmation dialog
    RikkaConfirmDialog(
        show = showRegenerateConfirm,
        title = stringResource(R.string.regenerate),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRegenerateConfirm = false
            onRegenerate()
        },
        onDismiss = { showRegenerateConfirm = false },
        text = { Text(stringResource(R.string.regenerate_confirm_message)) }
    )
}

@Composable
fun CustomChatMessageActionButtons(
    actionItems: List<UIMessageActionType>,
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onOpenPartsEditor: () -> Unit,
    onFork: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onWebViewPreview: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        actionItems.forEach { item ->
            when (item) {
                UIMessageActionType.COPY -> {
                    ChatMessageActionButton(
                        icon = HugeIcons.Copy01,
                        contentDescription = stringResource(R.string.copy),
                        onClick = { context.copyMessageToClipboard(message) },
                    )
                }

                UIMessageActionType.REGENERATE -> {
                    ChatMessageActionButton(
                        icon = HugeIcons.Refresh03,
                        contentDescription = stringResource(R.string.regenerate),
                        onClick = {
                            if (message.role == MessageRole.USER) {
                                showRegenerateConfirm = true
                            } else {
                                onRegenerate()
                            }
                        },
                    )
                }

                UIMessageActionType.EDIT -> {
                    ChatMessageActionButton(
                        icon = HugeIcons.Edit01,
                        contentDescription = stringResource(R.string.edit),
                        onClick = onEdit,
                    )
                }

                UIMessageActionType.PARTS_EDITOR -> {
                    ChatMessageActionButton(
                        icon = HugeIcons.Settings03,
                        contentDescription = "深度部件编辑",
                        onClick = onOpenPartsEditor,
                    )
                }

                UIMessageActionType.TTS -> if (message.role == MessageRole.ASSISTANT) {
                    val tts = LocalTTSState.current
                    val isSpeaking by tts.isSpeaking.collectAsState()
                    val isAvailable by tts.isAvailable.collectAsState()
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        IconToggleButton(
                            checked = isSpeaking,
                            onCheckedChange = { speak ->
                                if (speak) {
                                    var textToSpeak = message.toText()
                                    if (settings.displaySetting.ttsOnlyReadQuoted) {
                                        textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                                    }
                                    if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                                        textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                                    }
                                    tts.speak(textToSpeak)
                                } else {
                                    tts.stop()
                                }
                            },
                            shapes = IconButtonDefaults.toggleableShapes(),
                            modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
                            enabled = isAvailable,
                            colors = IconButtonDefaults.iconToggleButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                                contentDescription = stringResource(R.string.tts),
                                modifier = Modifier.size(ActionIconSize),
                            )
                        }
                    }
                }

                UIMessageActionType.TRANSLATE -> if (message.role == MessageRole.ASSISTANT && onTranslate != null) {
                    ChatMessageActionButton(
                        icon = HugeIcons.Translate,
                        contentDescription = stringResource(R.string.translate),
                        onClick = { showTranslateDialog = true },
                    )
                }

                UIMessageActionType.FORK -> {
                    ChatMessageActionButton(
                        icon = HugeIcons.GitFork,
                        contentDescription = stringResource(R.string.create_fork),
                        onClick = onFork,
                    )
                }

                UIMessageActionType.FAVORITE -> if (onToggleFavorite != null) {
                    ChatMessageActionButton(
                        icon = HugeIcons.FavouriteCircle,
                        contentDescription = "收藏",
                        onClick = onToggleFavorite,
                    )
                }

                UIMessageActionType.WEBVIEW -> {
                    ChatMessageActionButton(
                        icon = HugeIcons.WebDesign01,
                        contentDescription = stringResource(R.string.render_with_webview),
                        onClick = onWebViewPreview,
                    )
                }

                UIMessageActionType.MORE -> {
                    ChatMessageActionButton(
                        icon = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.more_options),
                        onClick = onOpenActionSheet,
                    )
                }

                UIMessageActionType.BRANCH_SELECTOR -> {
                    ChatMessageBranchSelector(
                        node = node,
                        onUpdate = onUpdate,
                    )
                }

                UIMessageActionType.TIMESTAMP -> {
                    Text(
                        text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    RikkaConfirmDialog(
        show = showRegenerateConfirm,
        title = stringResource(R.string.regenerate),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRegenerateConfirm = false
            onRegenerate()
        },
        onDismiss = { showRegenerateConfirm = false },
        text = { Text(stringResource(R.string.regenerate_confirm_message)) }
    )
}

private val ActionIconSize = 18.dp

// 消息下方的小号图标按钮，按下时圆形收成方角
@Composable
internal fun ChatMessageActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 按钮排得密，不让 48dp 的最小触控尺寸把间距撑开
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(ActionIconSize),
            )
        }
    }
}

private class MessageSheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit
) {
    me.rerere.rikkahub.ui.custom.UIModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        val host = org.koin.compose.koinInject<me.rerere.plugin.host.PluginHost>()
        val menuMounts = me.rerere.rikkahub.ui.pages.extensions.plugin.rememberPluginUiMounts("message.menu")
        val pluginScope = androidx.compose.runtime.rememberCoroutineScope()
        val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
        // WebView Preview (only show if message has text content)
        val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isNotBlank() }
        val actions = buildList {
            add(MessageSheetAction(HugeIcons.TextSelection, stringResource(R.string.select_and_copy), onClick = onSelectAndCopy))
            if (hasTextContent) {
                add(MessageSheetAction(HugeIcons.WebDesign01, stringResource(R.string.render_with_webview), onClick = onWebViewPreview))
            }
            add(MessageSheetAction(HugeIcons.Edit01, stringResource(R.string.edit), onClick = onEdit))
            add(MessageSheetAction(HugeIcons.Share04, stringResource(R.string.share), onClick = onShare))
            add(MessageSheetAction(HugeIcons.GitFork, stringResource(R.string.create_fork), onClick = onFork))
            if (onToggleFavorite != null) {
                add(
                    MessageSheetAction(
                        icon = HugeIcons.FavouriteCircle,
                        label = stringResource(
                            if (isFavorite) R.string.chat_message_remove_favorite
                            else R.string.chat_message_add_favorite
                        ),
                        onClick = onToggleFavorite,
                    )
                )
            }
            menuMounts.forEach { (rt, reg) ->
                val spec = reg.uiSpec
                if (!me.rerere.rikkahub.ui.pages.extensions.plugin.matchesMessageFilter(spec, message)) return@forEach
                val title = (spec["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (spec["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: rt.manifest.name
                val iconName = (spec["icon"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val openPath = (spec["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                add(
                    MessageSheetAction(
                        icon = me.rerere.rikkahub.ui.pages.extensions.plugin.resolvePluginIcon(iconName, HugeIcons.WebDesign01),
                        label = title,
                        onClick = {
                            val payload = kotlinx.serialization.json.buildJsonObject {
                                put("messageId", kotlinx.serialization.json.JsonPrimitive(message.id.toString()))
                                put("role", kotlinx.serialization.json.JsonPrimitive(message.role.name.lowercase()))
                                put("text", kotlinx.serialization.json.JsonPrimitive(message.toText()))
                                put("message", me.rerere.rikkahub.utils.JsonInstant.encodeToJsonElement(UIMessage.serializer(), message))
                            }
                            if (!openPath.isNullOrBlank()) {
                                me.rerere.rikkahub.data.plugin.PluginUiEvents.emit(
                                    me.rerere.rikkahub.data.plugin.PluginUiEvent.OpenPanel(
                                        pluginId = rt.id,
                                        path = openPath,
                                        presentation = (spec["presentation"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "sheet",
                                        argsJson = payload.toString(),
                                        title = title,
                                    )
                                )
                            } else {
                                pluginScope.launch {
                                    runCatching {
                                        host.callUiHandler(rt.id, reg.handlerId, payload)
                                    }.onFailure { e ->
                                        host.recordError(rt.id, e)
                                        toaster.show(e.message ?: e.javaClass.name, type = me.rerere.ui.toast.ToastType.Error)
                                    }
                                }
                            }
                        },
                    )
                )
            }
            add(MessageSheetAction(HugeIcons.Delete01, stringResource(R.string.delete), destructive = true, onClick = onDelete))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(me.rerere.rikkahub.ui.custom.uiSegmentedListGap()),
            ) {
                actions.fastForEachIndexed { index, action ->
                    SegmentedListItem(
                        onClick = {
                            onDismissRequest()
                            action.onClick()
                        },
                        modifier = Modifier.uiSegmentedItemBorder(index, actions.size),
                        shapes = me.rerere.rikkahub.ui.custom.uiSegmentedListItemShapes(index = index, count = actions.size),
                        colors = me.rerere.rikkahub.ui.custom.uiSegmentedListItemColors(destructive = action.destructive),
                        leadingContent = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                            )
                        },
                    ) {
                        Text(action.label)
                    }
                }
            }

            // Message Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                    if (model != null) {
                        Text(model.displayName)
                    }
                }
            }
        }
    }
}
