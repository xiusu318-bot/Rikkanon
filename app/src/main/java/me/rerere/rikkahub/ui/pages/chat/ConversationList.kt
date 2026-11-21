package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.ui.pages.extensions.plugin.uiSpec
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.custom.colorOr
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.allChildConversationIds
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.mirrorForRtl
import me.rerere.rikkahub.utils.toLocalString
import org.koin.compose.koinInject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String,
        val sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()
}

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {}
) {
    var hasScrolledToCurrent by remember(current.id) { mutableStateOf(false) }
    val chatService: ChatService = koinInject()
    val activeMainId = current.config?.parentConversationId ?: current.id
    val activeMainFlow = remember(activeMainId) { chatService.getConversationFlow(activeMainId) }
    val activeMainConv by activeMainFlow.collectAsStateWithLifecycle()
    val currentChildIds = remember(current, activeMainConv) {
        if (current.config?.parentConversationId != null) {
            activeMainConv.allChildConversationIds + current.id
        } else {
            current.allChildConversationIds
        }
    }
    var childrenExpanded by remember(activeMainId) { mutableStateOf(true) }

    LaunchedEffect(current.id, conversations.itemCount, hasScrolledToCurrent) {
        if (hasScrolledToCurrent) return@LaunchedEffect
        val currentIndex = conversations.itemSnapshotList.items.indexOfFirst {
            (it as? ConversationListItem.Item)?.conversation?.id == current.id
        }
        if (currentIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
            if (!isVisible) {
                listState.scrollToItem(currentIndex)
            }
            hasScrolledToCurrent = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversations.itemCount == 0) {
            item {
                // 界面自定义的接入点：ui.slot("conversation_empty", ...)；没写时绘制原版（样式可用 ui.style("conversation_empty")）
                me.rerere.rikkahub.ui.custom.UISlot(name = "conversation_empty") {
                val uiStyle = me.rerere.rikkahub.ui.custom.uiComponentStyle("conversation_empty")
                val scheme = MaterialTheme.colorScheme
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = (uiStyle?.marginHorizontalDp ?: 16f).dp,
                            vertical = (uiStyle?.marginVerticalDp ?: 16f).dp,
                        ),
                    shape = me.rerere.rikkahub.ui.custom.uiCornerShape((uiStyle?.cornerDp ?: 8f).dp),
                    color = uiStyle.colorOr({ it.background }, scheme, scheme.surfaceContainerLow),
                    border = uiStyle?.borderWidthDp?.takeIf { it > 0f }?.let {
                        androidx.compose.foundation.BorderStroke(
                            it.dp,
                            uiStyle.colorOr({ s -> s.borderColor }, scheme, scheme.outlineVariant),
                        )
                    },
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_page_no_conversations),
                        style = MaterialTheme.typography.bodyLarge.let { st ->
                            uiStyle?.fontSizeSp?.let { st.copy(fontSize = it.sp) } ?: st
                        },
                        color = uiStyle.colorOr({ it.color }, scheme, scheme.onSurface),
                        modifier = Modifier.padding(
                            horizontal = (uiStyle?.paddingHorizontalDp ?: 16f).dp,
                            vertical = (uiStyle?.paddingVerticalDp ?: 16f).dp,
                        )
                    )
                }
                }
            }
        }

        items(
            count = conversations.itemCount,
            key = conversations.itemKey { item ->
                when (item) {
                    // key 带上排序方式，避免切换排序后列表锚定到另一种排序下的同日期分组而发生跳动
                    is ConversationListItem.DateHeader -> "date_${item.sortOrder}_${item.date}"
                    is ConversationListItem.PinnedHeader -> "pinned_header"
                    is ConversationListItem.Item -> item.conversation.id.toString()
                }
            }
        ) { index ->
            when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> {
                    DateHeaderItem(
                        label = item.label,
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.PinnedHeader -> {
                    PinnedHeader(
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.Item -> {
                    val isSubAgentOrAttachedChild = item.conversation.config?.parentConversationId != null ||
                        (item.conversation.id in currentChildIds && item.conversation.id != activeMainId)
                    if (!isSubAgentOrAttachedChild) {
                        val isCurrentMain = item.conversation.id == activeMainId
                        val itemChildIds = if (isCurrentMain) {
                            currentChildIds
                        } else {
                            item.conversation.config?.childConversationIds.orEmpty() -
                                item.conversation.config?.deletedChildConversationIds.orEmpty()
                        }
                        var itemChildrenExpanded by remember(item.conversation.id, isCurrentMain) {
                            mutableStateOf(isCurrentMain)
                        }
                        val effectiveExpanded = if (isCurrentMain) childrenExpanded else itemChildrenExpanded
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConversationItem(
                                conversation = item.conversation,
                                selected = item.conversation.id == current.id,
                                loading = item.conversation.id in conversationJobs,
                                childCount = itemChildIds.size,
                                childrenExpanded = effectiveExpanded,
                                onToggleChildren = {
                                    if (isCurrentMain) {
                                        childrenExpanded = !childrenExpanded
                                    } else {
                                        itemChildrenExpanded = !itemChildrenExpanded
                                    }
                                },
                                onClick = onClick,
                                onDelete = onDelete,
                                onRegenerateTitle = onRegenerateTitle,
                                onPin = onPin,
                                onMoveToAssistant = onMoveToAssistant,
                                onMoveToFolder = onMoveToFolder,
                                modifier = Modifier.animateItem()
                            )
                            if (effectiveExpanded && itemChildIds.isNotEmpty()) {
                                itemChildIds.forEach { childId ->
                                    val childFlow = remember(childId) { chatService.getConversationFlow(childId) }
                                    val childConv by childFlow.collectAsStateWithLifecycle()
                                    SubConversationTreeItem(
                                        conversation = childConv,
                                        selected = childId == current.id,
                                        loading = childId in conversationJobs,
                                        onClick = { onClick(childConv) },
                                        onDelete = { onDelete(childConv) },
                                    )
                                }
                            }
                        }
                    }
                }

                null -> {
                    // Placeholder for loading state
                }
            }
        }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SubConversationTreeItem(
    conversation: Conversation,
    selected: Boolean = false,
    loading: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp)
                .clip(RoundedCornerShape(50f))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (onDelete != null) showMenu = true },
                )
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "├─ 🤖",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = conversation.title.trim().takeIf { it.isNotEmpty() && !it.startsWith("Sub-", ignoreCase = true) }
                    ?: "子代理-${conversation.id.toString().take(4)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(loading) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.extendColors.green6)
                        .size(5.dp)
                )
            }
        }
        if (onDelete != null) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    childCount: Int = 0,
    childrenExpanded: Boolean = true,
    onToggleChildren: () -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    // 界面自定义 ui.style("conversation_item")；未设置时为 null，各值与原版相同
    val uiStyle = me.rerere.rikkahub.ui.custom.uiComponentStyle("conversation_item")
    val scheme = MaterialTheme.colorScheme
    val itemShape = uiStyle?.cornerDp?.let { me.rerere.rikkahub.ui.custom.uiCornerShape(it.dp) }
        ?: if (me.rerere.rikkahub.ui.custom.hasUICustomShapes()) MaterialTheme.shapes.medium else RoundedCornerShape(50f)
    val backgroundColor = if (selected) {
        scheme.surfaceColorAtElevation(8.dp)
    } else {
        uiStyle.colorOr({ it.background }, scheme, Color.Transparent)
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    val newMessageTitle = stringResource(id = R.string.chat_page_new_message)
    // 界面自定义的接入点：主题可用 ui.slot("conversation_item", ...) 接管整个条目；没写时绘制下面的原版
    me.rerere.rikkahub.ui.custom.UISlot(
        name = "conversation_item",
        data = mapOf(
            "id" to conversation.id,
            "title" to conversation.title.ifBlank { newMessageTitle },
            "pinned" to conversation.isPinned,
            "selected" to selected,
            "loading" to loading,
            "updatedAt" to conversation.updateAt.toEpochMilli(),
            "children" to childCount,
            "childrenExpanded" to childrenExpanded,
        ),
        actions = mapOf(
            "open" to { onClick(conversation) },
            "menu" to {
                focusManager.clearFocus(force = true)
                showDropdownMenu = true
            },
            "toggle_children" to onToggleChildren,
        ),
        modifier = modifier,
        whenReplaced = {
            ConversationItemMenu(
                expanded = showDropdownMenu,
                onDismiss = { showDropdownMenu = false },
                conversation = conversation,
                onPin = onPin,
                onRegenerateTitle = onRegenerateTitle,
                onMoveToAssistant = onMoveToAssistant,
                onMoveToFolder = onMoveToFolder,
                onDelete = onDelete,
            )
        },
    ) {
        Box(
            modifier = modifier
                .clip(itemShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = { onClick(conversation) },
                    onLongClick = {
                        // Also clear chat input focus when the drawer is permanently visible.
                        focusManager.clearFocus(force = true)
                        showDropdownMenu = true
                    }
                )
                .background(backgroundColor),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = (uiStyle?.paddingHorizontalDp ?: 12f).dp,
                        vertical = (uiStyle?.paddingVerticalDp ?: 6f).dp,
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = uiStyle?.color?.let { me.rerere.rikkahub.ui.custom.resolveUIColor(it, scheme) } ?: Color.Unspecified,
                    fontSize = uiStyle?.fontSizeSp?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                )

                if (childCount > 0) {
                    ConversationChildrenToggle(
                        childCount = childCount,
                        expanded = childrenExpanded,
                        onToggle = onToggleChildren,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }

                // 置顶图标
                AnimatedVisibility(conversation.isPinned) {
                    Icon(
                        imageVector = HugeIcons.Pin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                AnimatedVisibility(loading) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.extendColors.green6)
                            .size(4.dp)
                            .semantics {
                                contentDescription = "Loading"
                            }
                    )
                }
                ConversationItemMenu(
                    expanded = showDropdownMenu,
                    onDismiss = { showDropdownMenu = false },
                    conversation = conversation,
                    onPin = onPin,
                    onRegenerateTitle = onRegenerateTitle,
                    onMoveToAssistant = onMoveToAssistant,
                    onMoveToFolder = onMoveToFolder,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun ConversationChildrenToggle(
    childCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable { onToggle() },
    ) {
        Text(
            text = "${if (expanded) "▾" else "▸"} $childCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ConversationItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    conversation: Conversation,
    onPin: (Conversation) -> Unit,
    onRegenerateTitle: (Conversation) -> Unit,
    onMoveToAssistant: (Conversation) -> Unit,
    onMoveToFolder: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                )
            },
            onClick = {
                onPin(conversation)
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                    null
                )
            }
        )

        DropdownMenuItem(
            text = {
                Text(stringResource(id = R.string.chat_page_regenerate_title))
            },
            onClick = {
                onRegenerateTitle(conversation)
                onDismiss()
            },
            leadingIcon = {
                Icon(HugeIcons.Refresh01, null)
            }
        )

        DropdownMenuItem(
            text = {
                Text(stringResource(R.string.chat_page_move_to_assistant))
            },
            onClick = {
                onMoveToAssistant(conversation)
                onDismiss()
            },
            leadingIcon = {
                Icon(HugeIcons.Forward02, null, modifier = Modifier.mirrorForRtl())
            }
        )

        DropdownMenuItem(
            text = {
                Text(stringResource(R.string.chat_page_move_to_folder))
            },
            onClick = {
                onMoveToFolder(conversation)
                onDismiss()
            },
            leadingIcon = {
                Icon(HugeIcons.Folder01, null)
            }
        )

        DropdownMenuItem(
            text = {
                Text(stringResource(id = R.string.chat_page_delete))
            },
            onClick = {
                onDelete(conversation)
                onDismiss()
            },
            leadingIcon = {
                Icon(HugeIcons.Delete01, null)
            }
        )

        val pluginHost = org.koin.compose.koinInject<me.rerere.plugin.host.PluginHost>()
        val convMenuMounts = me.rerere.rikkahub.ui.pages.extensions.plugin.rememberPluginUiMounts("conversation.menu")
        val pluginScope = androidx.compose.runtime.rememberCoroutineScope()
        val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
        convMenuMounts.forEach { (rt, reg) ->
            val spec = reg.uiSpec
            val title = (spec["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (spec["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: rt.manifest.name
            val iconName = (spec["icon"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val openPath = (spec["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            DropdownMenuItem(
                text = { Text(title) },
                leadingIcon = {
                    Icon(
                        imageVector = me.rerere.rikkahub.ui.pages.extensions.plugin.resolvePluginIcon(iconName),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    val payload = kotlinx.serialization.json.buildJsonObject {
                        put("conversationId", kotlinx.serialization.json.JsonPrimitive(conversation.id.toString()))
                        put("assistantId", kotlinx.serialization.json.JsonPrimitive(conversation.assistantId.toString()))
                        put("title", kotlinx.serialization.json.JsonPrimitive(conversation.title))
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
                                pluginHost.callUiHandler(rt.id, reg.handlerId, payload)
                            }.onFailure { e ->
                                pluginHost.recordError(rt.id, e)
                                toaster.show(e.message ?: e.javaClass.name, type = me.rerere.ui.toast.ToastType.Error)
                            }
                        }
                    }
                },
            )
        }
    }
}

