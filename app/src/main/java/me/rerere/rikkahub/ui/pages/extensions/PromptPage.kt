package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.PencilEdit02
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.PromptPresetSerializer
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGreenLightMode
import me.rerere.rikkahub.data.model.PromptBlock
import me.rerere.rikkahub.data.model.PromptBlockPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.PromptMarkerType
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.model.defaultPromptBlocks
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberExporter
import me.rerere.rikkahub.ui.hooks.rememberImporter
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.FormItem
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.components.Select
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

private enum class PromptTab(
    override val icon: ImageVector,
    private val titleText: String,
) : PagerTab {
    PRESET_COMPOSER(HugeIcons.MagicWand01, "提示词组装"),
    LOREBOOKS(HugeIcons.Book01, "世界书");

    override val label: String
        @Composable get() = titleText
}

private const val LAST_SELECTED_PRESET_PREF_KEY = "last_selected_prompt_preset_id"

@Composable
fun PromptPage(
    initialTab: Int = 0,
    initialPresetId: String? = null,
    initialLorebookId: String? = null,
    vm: PromptVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val safeInitialTab = initialTab.coerceIn(0, PromptTab.entries.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitialTab) { PromptTab.entries.size }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    // 标题槽在展开（大标题）与收起（顶栏小标题）两种状态下都会渲染；
                    // 开关只在顶部展开时显示，往下滚动开始收起即淡出，收起后不可点击
                    val collapsedFraction = scrollBehavior.state.collapsedFraction
                    val switchVisible = collapsedFraction < 0.5f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "高级提示词模式",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Switch(
                            checked = settings.enableAdvancedPrompt,
                            onCheckedChange = if (switchVisible) {
                                { enabled ->
                                    vm.updateSettings { latest ->
                                        latest.copy(enableAdvancedPrompt = enabled)
                                    }
                                }
                            } else null,
                            modifier = Modifier
                                .padding(start = 12.dp, end = 12.dp)
                                .graphicsLayer {
                                    alpha = (1f - collapsedFraction * 2f).coerceIn(0f, 1f)
                                },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        // 等待真实设置从磁盘加载完成再渲染列表，防止首帧先用 Settings.dummy() 默认顺序渲染再跳变动画
        if (settings.init) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 总开关关闭时，标签页、预设与世界书内容整体隐藏（数据不变，重新开启后原样显示）
            AnimatedVisibility(
                visible = settings.enableAdvancedPrompt,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PagerTabs(
                        pagerState = pagerState,
                        tabs = PromptTab.entries,
                        modifier = Modifier.padding(bottom = 4.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    )

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) { page ->
                        when (PromptTab.entries[page]) {
                            PromptTab.PRESET_COMPOSER -> PresetComposerTab(
                                presets = settings.promptPresets,
                                initialPresetId = initialPresetId,
                                onUpdate = { updated ->
                                    vm.updateSettings { latest -> latest.copy(promptPresets = updated) }
                                }
                            )

                            PromptTab.LOREBOOKS -> LorebookTab(
                                lorebooks = settings.lorebooks,
                                initialLorebookId = initialLorebookId,
                                onUpdate = { updated ->
                                    vm.updateSettings { latest -> latest.copy(lorebooks = updated) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== Tab 1: 提示词组装 (Preset Composer) ====================

@Composable
private fun PresetComposerTab(
    presets: List<PromptPreset>,
    initialPresetId: String? = null,
    onUpdate: (List<PromptPreset>) -> Unit,
) {
    val toaster = LocalToaster.current
    val currentPresets by rememberUpdatedState(presets)

    var lastSavedPresetIdStr by rememberSharedPreferenceString(
        keyForString = LAST_SELECTED_PRESET_PREF_KEY,
        defaultValue = null,
    )
    var selectedPresetIdStr by rememberSaveable {
        mutableStateOf(
            initialPresetId
                ?: lastSavedPresetIdStr
                ?: presets.firstOrNull()?.id?.toString().orEmpty()
        )
    }
    var consumedInitialPreset by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialPresetId) {
        if (!consumedInitialPreset && !initialPresetId.isNullOrBlank()) {
            consumedInitialPreset = true
            selectedPresetIdStr = initialPresetId
            lastSavedPresetIdStr = initialPresetId
        }
    }

    val activePreset = presets.find { it.id.toString() == selectedPresetIdStr }
        ?: presets.find { it.id.toString() == lastSavedPresetIdStr }
        ?: presets.firstOrNull()
        ?: PromptPreset(name = "默认预设", blocks = defaultPromptBlocks())

    LaunchedEffect(activePreset.id) {
        val idStr = activePreset.id.toString()
        if (selectedPresetIdStr != idStr) {
            selectedPresetIdStr = idStr
        }
        if (lastSavedPresetIdStr != idStr) {
            lastSavedPresetIdStr = idStr
        }
    }

    // 本地维护当前预设的草稿状态（双保存机制：弹窗内保存/拖拽/开关仅修改本地草稿，点外部「保存预设」才真正写盘）
    var localBlocks by remember(activePreset.id) { mutableStateOf(activePreset.blocks) }
    var localPresetEnabled by remember(activePreset.id) { mutableStateOf(activePreset.enabled) }
    var lastSyncedPreset by remember(activePreset.id) { mutableStateOf(activePreset) }

    if (activePreset != lastSyncedPreset) {
        lastSyncedPreset = activePreset
        localBlocks = activePreset.blocks
        localPresetEnabled = activePreset.enabled
    }

    val hasUnsavedChanges = localBlocks != activePreset.blocks || localPresetEnabled != activePreset.enabled

    fun savePresetDraft() {
        val next = activePreset.copy(
            enabled = localPresetEnabled,
            blocks = localBlocks,
        ).ensureRequiredMarkers()
        localBlocks = next.blocks
        localPresetEnabled = next.enabled
        lastSyncedPreset = next
        val list = if (currentPresets.any { it.id == activePreset.id }) {
            currentPresets.map { if (it.id == activePreset.id) next else it }
        } else {
            currentPresets + next
        }
        onUpdate(list)
        toaster.show("已保存预设「${activePreset.name}」")
    }

    fun discardPresetDraft() {
        localBlocks = activePreset.blocks
        localPresetEnabled = activePreset.enabled
        toaster.show("已放弃未保存的修改")
    }

    fun persistActivePresetMetadata(transform: (PromptPreset) -> PromptPreset) {
        val next = transform(activePreset.copy(enabled = localPresetEnabled, blocks = localBlocks)).ensureRequiredMarkers()
        localBlocks = next.blocks
        localPresetEnabled = next.enabled
        lastSyncedPreset = next
        val list = if (currentPresets.any { it.id == activePreset.id }) {
            currentPresets.map { if (it.id == activePreset.id) next else it }
        } else {
            currentPresets + next
        }
        onUpdate(list)
    }

    var editingBlock by remember { mutableStateOf<PromptBlock?>(null) }

    val importer = rememberImporter(PromptPresetSerializer) { result ->
        result.onSuccess { imported ->
            val updated = currentPresets + imported
            onUpdate(updated)
            selectedPresetIdStr = imported.id.toString()
            lastSavedPresetIdStr = imported.id.toString()
            toaster.show("已导入预设「${imported.name}」（共 ${imported.blocks.size} 个块）")
        }.onFailure {
            it.printStackTrace()
            toaster.show("导入预设失败：${it.message ?: "格式不支持"}")
        }
    }

    val exporter = rememberExporter(
        activePreset.copy(enabled = localPresetEnabled, blocks = localBlocks),
        PromptPresetSerializer
    )
    var showExportDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showNewPresetDialog by remember { mutableStateOf(false) }
    var showDeletePresetDialog by remember { mutableStateOf(false) }

    // 每个预设使用独立的列表状态：切换预设时列表直接按该预设保存的顺序显示，不再从上一个预设的位置做移动动画
    val lazyListState = remember(activePreset.id) { LazyListState() }
    var toolbarExpanded by rememberSaveable { mutableStateOf(true) }

    // 拖拽仅修改本地草稿 localBlocks，不自动写盘，需点外部「保存预设」才持久化
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (from.index in localBlocks.indices && to.index in localBlocks.indices) {
            localBlocks = localBlocks.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部预设控制栏独立在 LazyColumn 外面，避免被卷入列表滚动与拖拽计算
            PresetHeaderBar(
                presets = presets,
                activePreset = activePreset.copy(enabled = localPresetEnabled, blocks = localBlocks),
                onSelectPreset = {
                    val idStr = it.id.toString()
                    selectedPresetIdStr = idStr
                    lastSavedPresetIdStr = idStr
                },
                onTogglePresetEnabled = { enabled ->
                    localPresetEnabled = enabled
                },
                onNewPreset = { showNewPresetDialog = true },
                onRenamePreset = { showRenameDialog = true },
                onExportPreset = { showExportDialog = true },
                onDeletePreset = if (presets.size > 1) {
                    { showDeletePresetDialog = true }
                } else null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            // 外部预设保存栏（当有未保存改动时出现，防止手滑误触自动保存）
            AnimatedVisibility(visible = hasUnsavedChanges) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "当前预设存在未保存的修改",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { discardPresetDraft() }) {
                            Text("放弃")
                        }
                        Button(onClick = { savePresetDraft() }) {
                            Text("保存预设")
                        }
                    }
                }
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .floatingToolbarVerticalNestedScroll(
                        expanded = toolbarExpanded,
                        onExpand = { toolbarExpanded = true },
                        onCollapse = { toolbarExpanded = false },
                    ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp) + PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    items = localBlocks,
                    key = { _, block -> block.id },
                ) { index, block ->
                    ReorderableItem(reorderableState, key = block.id) { isDragging ->
                        PromptBlockRowItem(
                            block = block,
                            shape = cardGroupItemShape(index, localBlocks.size),
                            onClick = {
                                editingBlock = block
                            },
                            onToggleEnabled = { enabled ->
                                localBlocks = localBlocks.map {
                                    if (it.id == block.id) it.copy(enabled = enabled) else it
                                }
                            },
                            modifier = longPressReorder(
                                isDragging = isDragging,
                                enabled = localBlocks.size > 1,
                            ),
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = toolbarExpanded,
            colors = CustomColors.floatingToolbarColors,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, contentDescription = "导入预设")
                }
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        editingBlock = PromptBlock(
                            name = "自定义提示词",
                            enabled = true,
                            markerType = null,
                            role = MessageRole.SYSTEM,
                            content = "",
                        )
                    }
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    AnimatedVisibility(visible = toolbarExpanded) {
                        Text("添加提示词块", modifier = Modifier.padding(start = 8.dp))
                    }
                }

                if (hasUnsavedChanges) {
                    Button(
                        onClick = { savePresetDraft() }
                    ) {
                        Text("保存预设")
                    }
                }
            }
        }
    }

    // 第一层保存：编辑具体栏目后点「保存」仅更新当前预设草稿 localBlocks，再由外层「保存预设」落盘
    editingBlock?.let { target ->
        val isExisting = localBlocks.any { it.id == target.id }
        PromptBlockEditDialog(
            initialBlock = target,
            onDismiss = { editingBlock = null },
            onSave = { saved ->
                editingBlock = null
                localBlocks = if (isExisting) {
                    localBlocks.map { if (it.id == saved.id) saved else it }
                } else {
                    val chatHistIdx = localBlocks.indexOfFirst { it.markerType == PromptMarkerType.CHAT_HISTORY }
                    val newBlocks = localBlocks.toMutableList()
                    if (chatHistIdx >= 0) {
                        newBlocks.add(chatHistIdx, saved)
                    } else {
                        newBlocks.add(saved)
                    }
                    newBlocks
                }
            },
            onDelete = if (!target.isMarker && isExisting) {
                {
                    val delId = target.id
                    editingBlock = null
                    localBlocks = localBlocks.filterNot { it.id == delId }
                }
            } else null,
        )
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false },
        )
    }

    if (showRenameDialog) {
        PresetNameDialog(
            title = "重命名预设",
            initialName = activePreset.name,
            initialDesc = activePreset.description,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName, newDesc ->
                persistActivePresetMetadata { it.copy(name = newName.ifBlank { "未命名预设" }, description = newDesc) }
                showRenameDialog = false
            }
        )
    }

    if (showNewPresetDialog) {
        PresetNameDialog(
            title = "新建提示词预设",
            initialName = "新建预设 ${presets.size + 1}",
            initialDesc = "",
            onDismiss = { showNewPresetDialog = false },
            onConfirm = { newName, newDesc ->
                val created = PromptPreset(
                    id = Uuid.random(),
                    name = newName.ifBlank { "新建预设" },
                    description = newDesc,
                    enabled = false,
                    blocks = defaultPromptBlocks(),
                )
                onUpdate(currentPresets + created)
                selectedPresetIdStr = created.id.toString()
                lastSavedPresetIdStr = created.id.toString()
                showNewPresetDialog = false
            }
        )
    }

    RikkaConfirmDialog(
        show = showDeletePresetDialog,
        title = "删除预设",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            val remaining = currentPresets.filterNot { it.id == activePreset.id }
            onUpdate(remaining)
            val fallbackIdStr = remaining.firstOrNull()?.id?.toString().orEmpty()
            selectedPresetIdStr = fallbackIdStr
            lastSavedPresetIdStr = fallbackIdStr
            showDeletePresetDialog = false
        },
        onDismiss = { showDeletePresetDialog = false },
    ) {
        Text("确定要删除预设「${activePreset.name}」吗？")
    }
}

@Composable
private fun PresetHeaderBar(
    presets: List<PromptPreset>,
    activePreset: PromptPreset,
    onSelectPreset: (PromptPreset) -> Unit,
    onTogglePresetEnabled: (Boolean) -> Unit,
    onNewPreset: () -> Unit,
    onRenamePreset: () -> Unit,
    onExportPreset: () -> Unit,
    onDeletePreset: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Select(
                options = presets.ifEmpty { listOf(activePreset) },
                selectedOption = presets.find { it.id == activePreset.id } ?: activePreset,
                onOptionSelected = onSelectPreset,
                optionToString = {
                    val blocks = if (it.id == activePreset.id) activePreset.blocks else it.blocks
                    "${it.name} (${blocks.count { b -> b.enabled }}/${blocks.size})"
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Switch(
            checked = activePreset.enabled,
            onCheckedChange = onTogglePresetEnabled,
        )

        ItemActionMenu(
            actions = buildList {
                add(
                    ItemAction(
                        text = "新建空白预设",
                        icon = HugeIcons.Add01,
                        onClick = onNewPreset,
                    )
                )
                add(
                    ItemAction(
                        text = "重命名预设",
                        icon = HugeIcons.PencilEdit02,
                        onClick = onRenamePreset,
                    )
                )
                add(
                    ItemAction(
                        text = "导出预设",
                        icon = HugeIcons.Share03,
                        onClick = onExportPreset,
                    )
                )
                if (onDeletePreset != null) {
                    add(
                        ItemAction(
                            text = "删除当前预设",
                            icon = HugeIcons.Delete01,
                            destructive = true,
                            onClick = onDeletePreset,
                        )
                    )
                }
            }
        )
    }
}

@Composable
private fun PromptBlockRowItem(
    block: PromptBlock,
    shape: Shape,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMarker = block.isMarker
    val containerColor = if (isMarker) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (isMarker) "📌" else "📝",
                style = MaterialTheme.typography.titleMedium,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val pos = block.effectivePosition
                val posLabel = when (pos) {
                    PromptBlockPosition.RELATIVE -> "相对"
                    PromptBlockPosition.IN_CHAT -> "聊天中 @D:${block.depth} 序:${block.order}"
                    PromptBlockPosition.ATTACH_TO_MESSAGE -> "附着 @D:${block.depth} 序:${block.order}"
                }
                val roleBadge = getShortBlockRoleBadge(block)
                val cotBadge = when {
                    block.jsonMode || block.blankRole || isMarker -> ""
                    block.reasoning.isNotBlank() && block.reasoningSignature.isNotBlank() -> " · 🧠思维链+签名"
                    block.reasoning.isNotBlank() -> " · 🧠思维链"
                    block.reasoningSignature.isNotBlank() -> " · 🔑签名"
                    else -> ""
                }
                val badgeSuffix = if (isMarker) {
                    if (block.markerType == PromptMarkerType.CHAT_HISTORY) {
                        " [内置]"
                    } else if (pos == PromptBlockPosition.RELATIVE && !block.blankRole && block.role == MessageRole.SYSTEM) {
                        " [内置 · 相对]"
                    } else {
                        " [内置 · $roleBadge · $posLabel]"
                    }
                } else {
                    " [$roleBadge · $posLabel$cotBadge]"
                }

                Text(
                    text = block.name.ifBlank { block.identifier.ifBlank { "未命名块" } } + badgeSuffix +
                        if (block.followPresetInToolCall && block.markerType != PromptMarkerType.CHAT_HISTORY) " 🔧" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isMarker) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val extraPreview = block.content.lineSequence().firstOrNull()?.trim().orEmpty()
                val reasoningPreview = if (!block.jsonMode && !block.blankRole && !isMarker) {
                    block.reasoning.lineSequence().firstOrNull()?.trim().orEmpty()
                } else {
                    ""
                }
                // 整段 JSON 按行压成一行预览：仅在 JSON 内容变化时重新计算（拖拽排序等重组时直接复用）
                val rawJsonPreview = remember(block.rawJson, block.jsonMode, isMarker) {
                    if (block.jsonMode && !isMarker) {
                        block.rawJson.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                    } else {
                        ""
                    }
                }
                val subtitle = when (block.markerType) {
                    PromptMarkerType.WORLD_INFO_BEFORE ->
                        if (extraPreview.isNotEmpty()) "世界书（角色定义之前） · $extraPreview" else "世界书（角色定义之前）"
                    PromptMarkerType.CHAR_SYSTEM_PROMPT ->
                        if (extraPreview.isNotEmpty()) "助手系统提示词（角色定义） · $extraPreview" else "助手系统提示词（角色定义）"
                    PromptMarkerType.WORLD_INFO_AFTER ->
                        if (extraPreview.isNotEmpty()) "世界书（角色定义之后） · $extraPreview" else "世界书（角色定义之后）"
                    PromptMarkerType.MEMORIES ->
                        if (extraPreview.isNotEmpty()) "长期记忆（开启记忆时注入） · $extraPreview" else "长期记忆（仅在助手开启记忆时注入，否则为空占位）"
                    PromptMarkerType.WORKSPACE_PROMPT ->
                        if (extraPreview.isNotEmpty()) "工作区提示词（启用工作区/技能时注入） · $extraPreview" else "工作区与工具提示词（仅在启用时注入，否则为空占位）"
                    PromptMarkerType.CHAT_HISTORY ->
                        if (extraPreview.isNotEmpty()) "对话历史 · $extraPreview" else "对话历史（承载所有聊天中 @D 注入）"
                    null -> when {
                        block.jsonMode -> rawJsonPreview.ifEmpty { "（点击编辑 JSON）" }
                        extraPreview.isNotEmpty() -> extraPreview
                        reasoningPreview.isNotEmpty() -> "[思维链] $reasoningPreview"
                        !block.blankRole && block.reasoningSignature.isNotBlank() -> "[仅回传思维链签名]"
                        else -> "（点击编辑内容）"
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Switch(
                checked = block.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
    }
}

private fun getShortBlockRoleBadge(block: PromptBlock): String {
    if (!block.isMarker && block.jsonMode) return "JSON"
    if (block.blankRole) return "？"
    return getShortRoleBadge(block.role)
}

private fun getShortRoleBadge(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> "系统"
    MessageRole.USER -> "用户"
    MessageRole.ASSISTANT -> "AI"
    else -> role.name
}

private enum class PromptBlockRoleOption {
    SYSTEM,
    USER,
    ASSISTANT,
    BLANK,
}

private enum class PromptBlockPayloadTab {
    CONTENT,
    REASONING,
    SIGNATURE,
}

private val PrettyBlockJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * 常规模式 -> JSON 模式：将当前的角色、正文、思维链摘要、思维链签名同步到 JSON 文本中
 * （若已有合法 JsonObject，保留用户自定义的额外顶层字段如 prefix / name / cache_control 等）
 */
private fun syncNormalFieldsToRawJson(
    roleOption: PromptBlockRoleOption,
    content: String,
    reasoning: String,
    reasoningSignature: String,
    existingRawJson: String,
): String {
    val existingObj = runCatching {
        if (existingRawJson.isBlank()) null else PrettyBlockJson.parseToJsonElement(existingRawJson) as? JsonObject
    }.getOrNull()

    // 若现有 JSON 是带 "parts" 数组的风格，按 parts 结构同步更新
    if (existingObj != null && existingObj["parts"] is JsonArray) {
        val roleStr = when (roleOption) {
            PromptBlockRoleOption.USER -> "user"
            PromptBlockRoleOption.ASSISTANT -> "model"
            PromptBlockRoleOption.SYSTEM -> "system"
            PromptBlockRoleOption.BLANK -> (existingObj["role"] as? JsonPrimitive)?.contentOrNull ?: "user"
        }
        val newParts = buildJsonArray {
            if (roleOption != PromptBlockRoleOption.BLANK && (reasoning.isNotBlank() || reasoningSignature.isNotBlank())) {
                add(buildJsonObject {
                    put("thought", true)
                    put("text", reasoning)
                    if (reasoningSignature.isNotBlank()) {
                        put("thoughtSignature", reasoningSignature.trim())
                    }
                })
            }
            if (content.isNotBlank() || (reasoning.isBlank() && reasoningSignature.isBlank())) {
                add(buildJsonObject {
                    put("text", content)
                })
            }
        }
        val merged = LinkedHashMap<String, JsonElement>(existingObj)
        merged["role"] = JsonPrimitive(roleStr)
        merged["parts"] = newParts
        return PrettyBlockJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    val roleStr = when (roleOption) {
        PromptBlockRoleOption.SYSTEM -> "system"
        PromptBlockRoleOption.USER -> "user"
        PromptBlockRoleOption.ASSISTANT -> "assistant"
        PromptBlockRoleOption.BLANK -> "?"
    }
    val merged = LinkedHashMap<String, JsonElement>()
    merged["role"] = JsonPrimitive(roleStr)
    merged["content"] = JsonPrimitive(content)
    if (roleOption != PromptBlockRoleOption.BLANK) {
        if (reasoning.isNotBlank()) {
            val reasoningKey = when {
                existingObj?.containsKey("thinking") == true -> "thinking"
                existingObj?.containsKey("reasoning") == true -> "reasoning"
                else -> "reasoning_content"
            }
            merged[reasoningKey] = JsonPrimitive(reasoning)
        }
        if (reasoningSignature.isNotBlank()) {
            val sigKey = when {
                existingObj?.containsKey("thoughtSignature") == true -> "thoughtSignature"
                existingObj?.containsKey("encrypted_content") == true -> "encrypted_content"
                else -> "signature"
            }
            merged[sigKey] = JsonPrimitive(reasoningSignature.trim())
        }
    }
    // 保留用户此前在 JSON 中添加的其他自定义字段（如 "prefix": true, "name": "...", "cache_control": {...}）
    val managedKeys = setOf(
        "role", "content", "reasoning_content", "reasoning", "thinking",
        "signature", "thoughtSignature", "encrypted_content"
    )
    existingObj?.forEach { (k, v) ->
        if (k !in managedKeys) {
            merged[k] = v
        }
    }
    return PrettyBlockJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

private data class ParsedBlockFieldsFromJson(
    val roleOption: PromptBlockRoleOption?,
    val content: String?,
    val reasoning: String?,
    val reasoningSignature: String?,
)

/**
 * JSON 模式 -> 常规模式：尽量从 JSON 中识别并提取 role、正文、思维链摘要、思维链签名回填到常规模式
 */
private fun tryParseNormalFieldsFromRawJson(rawJson: String): ParsedBlockFieldsFromJson? {
    if (rawJson.isBlank()) return null
    val root = runCatching { PrettyBlockJson.parseToJsonElement(rawJson) }.getOrNull() ?: return null
    val obj: JsonObject = when (root) {
        is JsonObject -> root
        is JsonArray -> root.firstNotNullOfOrNull { it as? JsonObject } ?: return null
        else -> return null
    }

    val roleStr = (obj["role"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
    val parsedRole = when (roleStr) {
        "system", "developer" -> PromptBlockRoleOption.SYSTEM
        "user" -> PromptBlockRoleOption.USER
        "assistant", "model" -> PromptBlockRoleOption.ASSISTANT
        "?", "？", "blank" -> PromptBlockRoleOption.BLANK
        else -> null
    }

    var extractedContent: String? = null
    var extractedReasoning: String? = null
    var extractedSignature: String? = null

    // 1. 顶层字符串字段提取
    (obj["content"] as? JsonPrimitive)?.contentOrNull?.let { extractedContent = it }
    val topReasoning = (obj["reasoning_content"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["reasoning"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["thinking"] as? JsonPrimitive)?.contentOrNull
    if (topReasoning != null) extractedReasoning = topReasoning

    val topSig = (obj["signature"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["thoughtSignature"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["encrypted_content"] as? JsonPrimitive)?.contentOrNull
    if (topSig != null) extractedSignature = topSig

    // 2. "content": [ ... ] 数组结构提取
    (obj["content"] as? JsonArray)?.let { arr ->
        val textList = mutableListOf<String>()
        val thinkList = mutableListOf<String>()
        arr.forEach { el ->
            val item = el as? JsonObject ?: return@forEach
            val type = (item["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            if (type == "thinking" || type == "reasoning" || type == "summary_text" || type == "reasoning_text") {
                val rText = (item["thinking"] as? JsonPrimitive)?.contentOrNull
                    ?: (item["text"] as? JsonPrimitive)?.contentOrNull
                if (!rText.isNullOrEmpty()) thinkList.add(rText)
                (item["signature"] as? JsonPrimitive)?.contentOrNull?.let { extractedSignature = it }
            } else {
                (item["text"] as? JsonPrimitive)?.contentOrNull?.let { textList.add(it) }
            }
        }
        if (textList.isNotEmpty() || extractedContent == null) {
            extractedContent = textList.joinToString("\n")
        }
        if (thinkList.isNotEmpty()) {
            extractedReasoning = thinkList.joinToString("\n")
        }
    }

    // 3. "parts": [ ... ] 数组结构提取
    (obj["parts"] as? JsonArray)?.let { arr ->
        val textList = mutableListOf<String>()
        val thoughtList = mutableListOf<String>()
        arr.forEach { el ->
            val item = el as? JsonObject ?: return@forEach
            val isThought = (item["thought"] as? JsonPrimitive)?.booleanOrNull == true
            val partText = (item["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (isThought) {
                thoughtList.add(partText)
            } else if (partText.isNotEmpty()) {
                textList.add(partText)
            }
            (item["thoughtSignature"] as? JsonPrimitive)?.contentOrNull?.let { extractedSignature = it }
            (item["signature"] as? JsonPrimitive)?.contentOrNull?.let { extractedSignature = it }
        }
        extractedContent = textList.joinToString("\n")
        if (thoughtList.isNotEmpty()) {
            extractedReasoning = thoughtList.joinToString("\n")
        }
    }

    return ParsedBlockFieldsFromJson(
        roleOption = parsedRole,
        content = extractedContent,
        reasoning = extractedReasoning,
        reasoningSignature = extractedSignature,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptBlockEditDialog(
    initialBlock: PromptBlock,
    onDismiss: () -> Unit,
    onSave: (PromptBlock) -> Unit,
    onDelete: (() -> Unit)?,
) {
    // 全部使用本地状态，打字只重绘当前输入框，避免整页重组卡顿
    var name by remember(initialBlock.id) { mutableStateOf(initialBlock.name) }
    var enabled by remember(initialBlock.id) { mutableStateOf(initialBlock.enabled) }
    var jsonMode by remember(initialBlock.id) { mutableStateOf(!initialBlock.isMarker && initialBlock.jsonMode) }
    var rawJson by remember(initialBlock.id) { mutableStateOf(initialBlock.rawJson) }
    var jsonError by remember(initialBlock.id) { mutableStateOf<String?>(null) }
    var roleOption by remember(initialBlock.id) {
        mutableStateOf(
            when {
                initialBlock.blankRole -> PromptBlockRoleOption.BLANK
                initialBlock.role == MessageRole.USER -> PromptBlockRoleOption.USER
                initialBlock.role == MessageRole.ASSISTANT -> PromptBlockRoleOption.ASSISTANT
                else -> PromptBlockRoleOption.SYSTEM
            }
        )
    }
    var position by remember(initialBlock.id) { mutableStateOf(initialBlock.effectivePosition) }
    var followPresetInToolCall by remember(initialBlock.id) { mutableStateOf(initialBlock.followPresetInToolCall) }
    var depthText by remember(initialBlock.id) { mutableStateOf(initialBlock.depth.toString()) }
    var orderText by remember(initialBlock.id) { mutableStateOf(initialBlock.order.toString()) }
    var content by remember(initialBlock.id) { mutableStateOf(initialBlock.content) }
    var reasoning by remember(initialBlock.id) { mutableStateOf(initialBlock.reasoning) }
    var reasoningSignature by remember(initialBlock.id) { mutableStateOf(initialBlock.reasoningSignature) }
    var payloadTab by remember(initialBlock.id) {
        mutableStateOf(
            when {
                initialBlock.content.isNotBlank() -> PromptBlockPayloadTab.CONTENT
                initialBlock.reasoning.isNotBlank() -> PromptBlockPayloadTab.REASONING
                initialBlock.reasoningSignature.isNotBlank() -> PromptBlockPayloadTab.SIGNATURE
                else -> PromptBlockPayloadTab.CONTENT
            }
        )
    }

    val isChatHistoryMarker = initialBlock.markerType == PromptMarkerType.CHAT_HISTORY
    val isBlankRole = roleOption == PromptBlockRoleOption.BLANK

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialBlock.isMarker) "编辑内置模块（不可删除）" else "编辑提示词块")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }

                // 独立的模式切换栏（与消息编辑器保持一致的 FilterChip 风格，支持双向互通）
                if (!initialBlock.isMarker) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = !jsonMode,
                            onClick = {
                                if (jsonMode) {
                                    tryParseNormalFieldsFromRawJson(rawJson)?.let { parsed ->
                                        parsed.roleOption?.let { roleOption = it }
                                        parsed.content?.let { content = it }
                                        parsed.reasoning?.let { reasoning = it }
                                        parsed.reasoningSignature?.let { reasoningSignature = it }
                                        payloadTab = when {
                                            roleOption == PromptBlockRoleOption.BLANK -> PromptBlockPayloadTab.CONTENT
                                            content.isNotBlank() -> PromptBlockPayloadTab.CONTENT
                                            reasoning.isNotBlank() -> PromptBlockPayloadTab.REASONING
                                            reasoningSignature.isNotBlank() -> PromptBlockPayloadTab.SIGNATURE
                                            else -> PromptBlockPayloadTab.CONTENT
                                        }
                                    }
                                    jsonMode = false
                                    jsonError = null
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.PencilEdit02,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            label = { Text("常规模式") },
                        )
                        FilterChip(
                            selected = jsonMode,
                            onClick = {
                                if (!jsonMode) {
                                    rawJson = syncNormalFieldsToRawJson(
                                        roleOption = roleOption,
                                        content = content,
                                        reasoning = reasoning,
                                        reasoningSignature = reasoningSignature,
                                        existingRawJson = rawJson,
                                    )
                                    jsonMode = true
                                    jsonError = null
                                    if (position == PromptBlockPosition.ATTACH_TO_MESSAGE) {
                                        position = PromptBlockPosition.RELATIVE
                                    }
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            label = { Text("JSON 模式") },
                        )
                    }
                }

                if (!isChatHistoryMarker) {
                    if (!jsonMode) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("角色 (Role)", style = MaterialTheme.typography.labelLarge)
                            ConnectedChoiceButtons(
                                options = PromptBlockRoleOption.entries,
                                selected = roleOption,
                                onSelect = {
                                    roleOption = it
                                    if (it == PromptBlockRoleOption.BLANK) {
                                        payloadTab = PromptBlockPayloadTab.CONTENT
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceBright,
                                growSelected = false,
                            ) { option, selected ->
                                val label = when (option) {
                                    PromptBlockRoleOption.SYSTEM -> "⚙️系统"
                                    PromptBlockRoleOption.USER -> "👤用户"
                                    PromptBlockRoleOption.ASSISTANT -> "🤖AI"
                                    PromptBlockRoleOption.BLANK -> "？"
                                }
                                ConnectedChoiceLabel(text = label, selected = selected)
                            }
                            if (isBlankRole) {
                                Text(
                                    text = "？(空白角色)：跟随最前一个模块合并进入其正文，仅支持正文编辑。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("位置", style = MaterialTheme.typography.labelLarge)
                        val positionOptions = if (jsonMode) {
                            listOf(PromptBlockPosition.RELATIVE, PromptBlockPosition.IN_CHAT)
                        } else {
                            PromptBlockPosition.entries
                        }
                        ConnectedChoiceButtons(
                            options = positionOptions,
                            selected = if (position in positionOptions) position else PromptBlockPosition.RELATIVE,
                            onSelect = { position = it },
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                            growSelected = false,
                        ) { posOption, selected ->
                            val label = when (posOption) {
                                PromptBlockPosition.RELATIVE -> "相对"
                                PromptBlockPosition.IN_CHAT -> "聊天中"
                                PromptBlockPosition.ATTACH_TO_MESSAGE -> "附着到已有消息"
                            }
                            ConnectedChoiceLabel(
                                text = label,
                                selected = selected,
                            )
                        }
                        val positionHelpText = when (position) {
                            PromptBlockPosition.RELATIVE -> "相对：相对于提示管理器中的其他提示，按列表上下顺序发送。"
                            PromptBlockPosition.IN_CHAT -> "聊天中：按指定的 @ 深度与顺序，作为独立消息插入聊天历史中。"
                            PromptBlockPosition.ATTACH_TO_MESSAGE -> "附着到已有消息：按指定的 @ 深度与顺序，直接拼接到已有的聊天消息末尾。"
                        }
                        Text(
                            text = positionHelpText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (position != PromptBlockPosition.RELATIVE) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = depthText,
                                    onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) depthText = it },
                                    label = { Text("深度(0=末,9999=前)") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = orderText,
                                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() || c == '-' }) orderText = it },
                                    label = { Text("顺序(越小越上)") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "按预设发送工具调用",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = followPresetInToolCall,
                                onCheckedChange = { followPresetInToolCall = it },
                            )
                        }
                        Text(
                            text = if (followPresetInToolCall) {
                                "按照预设位置发送每一次请求，工具调用仅视为普通聊天历史。"
                            } else {
                                "工具调用视为未完成的助手回复，在请求体底部发出。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!initialBlock.isMarker) {
                    if (jsonMode) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "作为数组元素并入 API 请求内。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        if (rawJson.isNotBlank()) {
                                            runCatching {
                                                val parsed = PrettyBlockJson.parseToJsonElement(rawJson)
                                                rawJson = PrettyBlockJson.encodeToString(JsonElement.serializer(), parsed)
                                                jsonError = null
                                            }.onFailure { err ->
                                                jsonError = err.message ?: "JSON 格式有误"
                                            }
                                        }
                                    }
                                ) {
                                    Text("格式化校验", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("{{char}}", "{{user}}", "{{cur_date}}", "{{outlet::锚点名}}").forEach { token ->
                                    Tag(onClick = { rawJson = if (rawJson.isEmpty()) token else "$rawJson$token" }) {
                                        Text(token)
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = rawJson,
                                onValueChange = {
                                    rawJson = it
                                    jsonError = null
                                },
                                label = { Text("JSON (${rawJson.length})") },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 6,
                                maxLines = 12,
                                isError = jsonError != null,
                            )
                            if (jsonError != null) {
                                Text(
                                    text = jsonError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!isBlankRole) {
                                Text("模块内容与思维链回传", style = MaterialTheme.typography.labelLarge)
                                ConnectedChoiceButtons(
                                    options = PromptBlockPayloadTab.entries,
                                    selected = payloadTab,
                                    onSelect = { payloadTab = it },
                                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                                    growSelected = false,
                                ) { tab, selected ->
                                    val label = when (tab) {
                                        PromptBlockPayloadTab.CONTENT ->
                                            if (content.isNotBlank()) "正文 •" else "正文"
                                        PromptBlockPayloadTab.REASONING ->
                                            if (reasoning.isNotBlank()) "思维链摘要 •" else "思维链摘要"
                                        PromptBlockPayloadTab.SIGNATURE ->
                                            if (reasoningSignature.isNotBlank()) "思维链签名 •" else "思维链签名"
                                    }
                                    ConnectedChoiceLabel(text = label, selected = selected)
                                }
                            } else {
                                Text("正文内容", style = MaterialTheme.typography.labelLarge)
                            }

                            val activeTab = if (isBlankRole) PromptBlockPayloadTab.CONTENT else payloadTab

                            when (activeTab) {
                                PromptBlockPayloadTab.CONTENT -> {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        listOf("{{char}}", "{{user}}", "{{cur_date}}", "{{outlet::锚点名}}").forEach { token ->
                                            Tag(onClick = { content = if (content.isEmpty()) token else "$content$token" }) {
                                                Text(token)
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value = content,
                                        onValueChange = { content = it },
                                        label = { Text("正文 (${content.length})") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 5,
                                        maxLines = 9,
                                    )
                                }

                                PromptBlockPayloadTab.REASONING -> {
                                    Text(
                                        text = "思维链摘要：回传为模型思考过程（部分开源模型实际回传为思维链明文 reasoning_content）。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        listOf("{{char}}", "{{user}}", "{{cur_date}}", "{{outlet::锚点名}}").forEach { token ->
                                            Tag(onClick = { reasoning = if (reasoning.isEmpty()) token else "$reasoning$token" }) {
                                                Text(token)
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value = reasoning,
                                        onValueChange = { reasoning = it },
                                        label = { Text("思维链摘要 / 明文 (${reasoning.length})") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 5,
                                        maxLines = 9,
                                    )
                                }

                                PromptBlockPayloadTab.SIGNATURE -> {
                                    Text(
                                        text = "思维链签名：回传为加密思考签名（自动适配 Claude / Gemini / OpenAI，开源模型可留空）。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    OutlinedTextField(
                                        value = reasoningSignature,
                                        onValueChange = { reasoningSignature = it },
                                        label = { Text("思维链签名 (${reasoningSignature.length})") },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 4,
                                        maxLines = 8,
                                    )
                                }
                            }
                        }
                    }
                }

                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除此提示词块", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    var savedRoleOption = roleOption
                    var savedContent = content
                    var savedReasoning = reasoning
                    var savedSignature = reasoningSignature
                    if (!initialBlock.isMarker && jsonMode && rawJson.isNotBlank()) {
                        val valid = runCatching {
                            PrettyBlockJson.parseToJsonElement(rawJson)
                        }.isSuccess
                        if (!valid) {
                            jsonError = "JSON 格式解析失败，请检查括号或引号"
                            return@TextButton
                        }
                        tryParseNormalFieldsFromRawJson(rawJson)?.let { parsed ->
                            parsed.roleOption?.let { savedRoleOption = it }
                            parsed.content?.let { savedContent = it }
                            parsed.reasoning?.let { savedReasoning = it }
                            parsed.reasoningSignature?.let { savedSignature = it }
                        }
                    }
                    val finalPos = when {
                        isChatHistoryMarker -> PromptBlockPosition.RELATIVE
                        jsonMode && position == PromptBlockPosition.ATTACH_TO_MESSAGE -> PromptBlockPosition.RELATIVE
                        else -> position
                    }
                    val finalBlankRole = !isChatHistoryMarker && !jsonMode && savedRoleOption == PromptBlockRoleOption.BLANK
                    val finalRole = when (savedRoleOption) {
                        PromptBlockRoleOption.SYSTEM -> MessageRole.SYSTEM
                        PromptBlockRoleOption.USER -> MessageRole.USER
                        PromptBlockRoleOption.ASSISTANT -> MessageRole.ASSISTANT
                        PromptBlockRoleOption.BLANK -> initialBlock.role
                    }
                    onSave(
                        initialBlock.copy(
                            name = name,
                            enabled = enabled,
                            role = finalRole,
                            blankRole = finalBlankRole,
                            jsonMode = !initialBlock.isMarker && jsonMode,
                            rawJson = rawJson,
                            inChatDepth = finalPos != PromptBlockPosition.RELATIVE,
                            position = finalPos,
                            followPresetInToolCall = !isChatHistoryMarker && followPresetInToolCall,
                            depth = (depthText.toIntOrNull() ?: 4).coerceAtLeast(0),
                            order = orderText.toIntOrNull() ?: 100,
                            content = savedContent,
                            reasoning = if (finalBlankRole) "" else savedReasoning,
                            reasoningSignature = if (finalBlankRole) "" else savedSignature,
                        )
                    )
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PresetNameDialog(
    title: String,
    initialName: String,
    initialDesc: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("预设名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("描述（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, desc) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ==================== Tab 2: 世界书 (Lorebooks) ====================

@Composable
private fun LorebookTab(
    lorebooks: List<Lorebook>,
    initialLorebookId: String? = null,
    onUpdate: (List<Lorebook>) -> Unit,
) {
    val toaster = LocalToaster.current
    val currentLorebooks by rememberUpdatedState(lorebooks)
    val editState = useEditState<Lorebook> { edited ->
        val list = currentLorebooks
        val updated = if (list.any { it.id == edited.id }) {
            list.map { if (it.id == edited.id) edited else it }
        } else {
            list + edited
        }
        onUpdate(updated)
    }

    var consumedInitialLorebook by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialLorebookId, lorebooks) {
        if (!consumedInitialLorebook && !initialLorebookId.isNullOrBlank()) {
            val target = lorebooks.find { it.id.toString() == initialLorebookId }
            if (target != null) {
                consumedInitialLorebook = true
                editState.open(target)
            }
        }
    }

    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate(currentLorebooks + imported)
            toaster.show("已导入世界书「${imported.name}」（共 ${imported.entries.size} 个条目）")
        }.onFailure {
            it.printStackTrace()
            toaster.show("导入世界书失败：${it.message ?: "格式不支持"}")
        }
    }

    val lazyListState = rememberLazyListState()
    var expanded by rememberSaveable { mutableStateOf(true) }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (from.index in currentLorebooks.indices && to.index in currentLorebooks.indices) {
            val updated = currentLorebooks.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            onUpdate(updated)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (lorebooks.isEmpty()) {
            ExtensionEmptyState(
                message = "暂无世界书",
                description = "支持直接导入酒馆 (SillyTavern) 世界书 JSON，或新建世界书",
                icon = HugeIcons.Book01,
            )
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false },
                    ),
                contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(
                    items = lorebooks,
                    key = { _, item -> item.id }
                ) { index, book ->
                    ReorderableItem(reorderableState, key = book.id) { isDragging ->
                        LorebookCard(
                            book = book,
                            shape = cardGroupItemShape(index, lorebooks.size),
                            onEdit = { editState.open(book) },
                            onToggle = { enabled ->
                                onUpdate(
                                    currentLorebooks.map {
                                        if (it.id == book.id) it.copy(enabled = enabled) else it
                                    }
                                )
                            },
                            onDelete = {
                                onUpdate(currentLorebooks.filter { it.id != book.id })
                            },
                            modifier = longPressReorder(
                                isDragging = isDragging,
                                enabled = lorebooks.size > 1,
                            ),
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            colors = CustomColors.floatingToolbarColors,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, contentDescription = "导入世界书")
                }
            },
        ) {
            Button(onClick = { editState.open(Lorebook(name = "新建世界书")) }) {
                Icon(HugeIcons.Add01, contentDescription = null)
                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = stringResource(R.string.prompt_page_add),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    editState.currentState?.let { book ->
        if (editState.isEditing) {
            LorebookEditSheet(
                book = book,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun LorebookCard(
    book: Lorebook,
    shape: Shape,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)

    Card(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardGroupIcon(
                    icon = HugeIcons.Book01,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = book.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val enabledCount = book.entries.count { it.enabled }
                        Tag(type = TagType.INFO) {
                            Text("条目 $enabledCount/${book.entries.size}")
                        }
                        Tag(type = TagType.SUCCESS) {
                            Text(getGreenLightShortLabel(book.greenLightMode))
                        }
                    }
                }

                ItemActionMenu(
                    actions = listOf(
                        ItemAction(
                            text = "导出",
                            icon = HugeIcons.Share03,
                            onClick = { showExportDialog = true },
                        ),
                        ItemAction(
                            text = "删除",
                            icon = HugeIcons.Delete01,
                            destructive = true,
                            onClick = { showDeleteDialog = true },
                        ),
                    )
                )

                Switch(
                    checked = book.enabled,
                    onCheckedChange = onToggle
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = "删除世界书",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text("确定要删除世界书「${book.name}」吗？")
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

private fun getGreenLightShortLabel(mode: LorebookGreenLightMode): String = when (mode) {
    LorebookGreenLightMode.STICKY_LOCKED_POSITION -> "🟢持续+锁位置"
    LorebookGreenLightMode.STICKY_FLOATING -> "🟢持续+浮动"
    LorebookGreenLightMode.DYNAMIC_NATIVE -> "🟢原生动态"
}

private fun getGreenLightFullLabel(mode: LorebookGreenLightMode): String = when (mode) {
    LorebookGreenLightMode.STICKY_LOCKED_POSITION -> "触发后持续 + 固定首次注入位置（直至压缩，保缓存）"
    LorebookGreenLightMode.STICKY_FLOATING -> "触发后持续 + 深度位置随消息浮动（直至压缩）"
    LorebookGreenLightMode.DYNAMIC_NATIVE -> "酒馆原生绿灯（仅在扫描窗口内命中当轮生效）"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LorebookEditSheet(
    book: Lorebook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (Lorebook) -> Unit
) {
    val currentBook by rememberUpdatedState(book)
    var editingEntry by remember { mutableStateOf<PromptInjection.RegexInjection?>(null) }
    var showGlobalSettingsDialog by remember { mutableStateOf(false) }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶部极简标题栏：不再同屏塞一堆输入框，点击「世界书设置」弹窗修改全局项
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.name.ifBlank { "未命名世界书" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${getGreenLightShortLabel(book.greenLightMode)} · 默认扫 ${book.defaultScanDepth} 条 · 共 ${book.entries.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(onClick = { showGlobalSettingsDialog = true }) {
                    Icon(HugeIcons.Settings03, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("世界书设置")
                }

                FilledTonalIconButton(
                    onClick = {
                        editingEntry = PromptInjection.RegexInjection()
                    }
                ) {
                    Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
                }
            }

            if (book.entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无条目，点击右上角 + 添加",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = book.entries,
                        key = { it.id }
                    ) { entry ->
                        LoreEntryRowCard(
                            entry = entry,
                            onEdit = { editingEntry = entry },
                            onToggleEnabled = { enabled ->
                                onEdit(
                                    currentBook.copy(
                                        entries = currentBook.entries.map {
                                            if (it.id == entry.id) it.copy(enabled = enabled) else it
                                        }
                                    )
                                )
                            },
                            onToggleConstant = { constant ->
                                onEdit(
                                    currentBook.copy(
                                        entries = currentBook.entries.map {
                                            if (it.id == entry.id) it.copy(constantActive = constant) else it
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onEdit(currentBook.copy(entries = currentBook.entries.sortedBy { it.priority }))
                    }
                ) {
                    Text("按顺序(Order)排列")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Button(onClick = onConfirm) {
                        Text("保存世界书")
                    }
                }
            }
        }
    }

    if (showGlobalSettingsDialog) {
        LorebookGlobalSettingsDialog(
            book = book,
            onDismiss = { showGlobalSettingsDialog = false },
            onSave = { updated ->
                onEdit(updated)
                showGlobalSettingsDialog = false
            }
        )
    }

    // 轻量 AlertDialog 编辑条目（消除双层 ModalBottomSheet 套娃卡顿）
    editingEntry?.let { target ->
        val isExisting = book.entries.any { it.id == target.id }
        RegexInjectionEditDialog(
            initialEntry = target,
            onDismiss = { editingEntry = null },
            onSave = { saved ->
                editingEntry = null
                val list = currentBook.entries
                val updated = if (isExisting) {
                    list.map { if (it.id == saved.id) saved else it }
                } else {
                    list + saved
                }
                onEdit(currentBook.copy(entries = updated))
            },
            onDelete = if (isExisting) {
                {
                    val delId = target.id
                    editingEntry = null
                    onEdit(currentBook.copy(entries = currentBook.entries.filterNot { it.id == delId }))
                }
            } else null,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LorebookGlobalSettingsDialog(
    book: Lorebook,
    onDismiss: () -> Unit,
    onSave: (Lorebook) -> Unit,
) {
    var name by remember(book.id) { mutableStateOf(book.name) }
    var enabled by remember(book.id) { mutableStateOf(book.enabled) }
    var greenMode by remember(book.id) { mutableStateOf(book.greenLightMode) }
    var scanDepthText by remember(book.id) { mutableStateOf(book.defaultScanDepth.toString()) }
    var maxRecText by remember(book.id) { mutableStateOf(book.maxRecursionSteps.toString()) }
    var recursiveScan by remember(book.id) { mutableStateOf(book.recursiveScan) }
    var caseSensitive by remember(book.id) { mutableStateOf(book.caseSensitive) }
    var matchWholeWords by remember(book.id) { mutableStateOf(book.matchWholeWords) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("世界书全局与绿灯设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("世界书名称") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Text("🟢 绿灯触发与缓存策略", style = MaterialTheme.typography.labelLarge)
                Select(
                    options = LorebookGreenLightMode.entries,
                    selectedOption = greenMode,
                    onOptionSelected = { greenMode = it },
                    optionToString = { getGreenLightFullLabel(it) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = scanDepthText,
                        onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) scanDepthText = it },
                        label = { Text("默认扫描深度") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = maxRecText,
                        onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) maxRecText = it },
                        label = { Text("最大递归层数") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = recursiveScan,
                        onClick = { recursiveScan = !recursiveScan },
                        label = { Text("递归扫描") },
                    )
                    FilterChip(
                        selected = caseSensitive,
                        onClick = { caseSensitive = !caseSensitive },
                        label = { Text("全局区分大小写") },
                    )
                    FilterChip(
                        selected = matchWholeWords,
                        onClick = { matchWholeWords = !matchWholeWords },
                        label = { Text("全局完整单词") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        book.copy(
                            name = name,
                            enabled = enabled,
                            greenLightMode = greenMode,
                            defaultScanDepth = scanDepthText.toIntOrNull()?.coerceAtLeast(1) ?: book.defaultScanDepth,
                            maxRecursionSteps = maxRecText.toIntOrNull()?.coerceAtLeast(0) ?: book.maxRecursionSteps,
                            recursiveScan = recursiveScan,
                            caseSensitive = caseSensitive,
                            matchWholeWords = matchWholeWords,
                        )
                    )
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun LoreEntryRowCard(
    entry: PromptInjection.RegexInjection,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleConstant: (Boolean) -> Unit,
) {
    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onToggleConstant(!entry.constantActive) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (entry.constantActive) "🔵" else "🟢",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val titleText = entry.name.ifEmpty {
                    entry.keywords.firstOrNull() ?: "未命名条目"
                }
                Text(
                    text = "$titleText  [${getLoreEntryPositionBadge(entry)} · 序:${entry.priority}]",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val keySummary = buildString {
                    if (entry.constantActive) {
                        append("[🔵常驻] ")
                    } else if (entry.keywords.isNotEmpty()) {
                        append("主词: ${entry.keywords.take(3).joinToString(", ")}")
                        if (entry.keywords.size > 3) append("...")
                    } else {
                        append("（未设置关键字）")
                    }
                    if (entry.secondaryKeys.isNotEmpty()) {
                        append(" | ${getSelectiveLogicLabel(entry.selectiveLogic)}: ${entry.secondaryKeys.take(2).joinToString(", ")}")
                    }
                }
                Text(
                    text = keySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Switch(
                checked = entry.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
    }
}

private enum class TriStateOption(val value: Boolean?, val label: String) {
    GLOBAL(null, "使用全局"),
    TRUE(true, "是"),
    FALSE(false, "否");

    companion object {
        fun from(v: Boolean?): TriStateOption = when (v) {
            null -> GLOBAL
            true -> TRUE
            false -> FALSE
        }
    }
}

private enum class UiLorePosition(val label: String) {
    BEFORE_CHAR("角色定义之前"),
    AFTER_CHAR("角色定义之后"),
    AT_DEPTH_SYSTEM("@D ⚙️ [系统]在深度"),
    AT_DEPTH_USER("@D 👤 [用户]在深度"),
    AT_DEPTH_ASSISTANT("@D 🤖 [AI]在深度"),
    OUTLET("➡️ 锚点"),
}

private fun resolveUiLorePosition(entry: PromptInjection.RegexInjection): UiLorePosition {
    return when (entry.position) {
        InjectionPosition.BEFORE_SYSTEM_PROMPT -> UiLorePosition.BEFORE_CHAR
        InjectionPosition.AFTER_SYSTEM_PROMPT -> UiLorePosition.AFTER_CHAR
        InjectionPosition.OUTLET -> UiLorePosition.OUTLET
        InjectionPosition.TOP_OF_CHAT,
        InjectionPosition.BOTTOM_OF_CHAT,
        InjectionPosition.AT_DEPTH -> when (entry.role) {
            MessageRole.USER -> UiLorePosition.AT_DEPTH_USER
            MessageRole.ASSISTANT -> UiLorePosition.AT_DEPTH_ASSISTANT
            else -> UiLorePosition.AT_DEPTH_SYSTEM
        }
    }
}

private fun getLoreEntryPositionBadge(entry: PromptInjection.RegexInjection): String {
    return when (entry.position) {
        InjectionPosition.BEFORE_SYSTEM_PROMPT -> "角色前"
        InjectionPosition.AFTER_SYSTEM_PROMPT -> "角色后"
        InjectionPosition.OUTLET -> "➡️锚点:${entry.outletName.ifBlank { "未命名" }}"
        InjectionPosition.TOP_OF_CHAT -> "@D:9999"
        InjectionPosition.BOTTOM_OF_CHAT -> "@D:1"
        InjectionPosition.AT_DEPTH -> {
            val roleIcon = when (entry.role) {
                MessageRole.USER -> "👤"
                MessageRole.ASSISTANT -> "🤖"
                else -> "⚙️"
            }
            "@D$roleIcon:${entry.injectDepth}"
        }
    }
}

private fun getSelectiveLogicLabel(logic: SelectiveLogic): String = when (logic) {
    SelectiveLogic.AND_ANY -> "与任意"
    SelectiveLogic.AND_ALL -> "与全部"
    SelectiveLogic.NOT_ANY -> "非任意"
    SelectiveLogic.NOT_ALL -> "非全部"
}

private fun splitCommaKeywords(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inSlashRegex = false
    for (i in raw.indices) {
        val c = raw[i]
        if (c == '/' && (i == 0 || raw[i - 1] != '\\')) {
            if (!inSlashRegex && current.toString().isBlank()) {
                inSlashRegex = true
            } else if (inSlashRegex) {
                inSlashRegex = false
            }
            current.append(c)
        } else if (!inSlashRegex && (c == ',' || c == '，' || c == '\n')) {
            val token = current.toString().trim()
            if (token.isNotEmpty()) result.add(token)
            current.clear()
        } else {
            current.append(c)
        }
    }
    val tail = current.toString().trim()
    if (tail.isNotEmpty()) result.add(tail)
    return result
}

/**
 * 世界书单条目轻量编辑弹窗（AlertDialog + 全本地状态，打字/切换零卡顿）
 */
@Composable
private fun RegexInjectionEditDialog(
    initialEntry: PromptInjection.RegexInjection,
    onDismiss: () -> Unit,
    onSave: (PromptInjection.RegexInjection) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(initialEntry.id) { mutableStateOf(initialEntry.name) }
    var constantActive by remember(initialEntry.id) { mutableStateOf(initialEntry.constantActive) }
    var enabled by remember(initialEntry.id) { mutableStateOf(initialEntry.enabled) }
    var uiPosition by remember(initialEntry.id) { mutableStateOf(resolveUiLorePosition(initialEntry)) }
    var outletName by remember(initialEntry.id) { mutableStateOf(initialEntry.outletName) }
    var depthText by remember(initialEntry.id) { mutableStateOf(initialEntry.injectDepth.toString()) }
    var orderText by remember(initialEntry.id) { mutableStateOf(initialEntry.priority.toString()) }
    var probText by remember(initialEntry.id) { mutableStateOf(initialEntry.probability.toString()) }
    var primaryKeysText by remember(initialEntry.id) { mutableStateOf(initialEntry.keywords.joinToString(", ")) }
    var selectiveLogic by remember(initialEntry.id) { mutableStateOf(initialEntry.selectiveLogic) }
    var secondaryKeysText by remember(initialEntry.id) { mutableStateOf(initialEntry.secondaryKeys.joinToString(", ")) }
    var scanDepthInput by remember(initialEntry.id) { mutableStateOf(initialEntry.overrideScanDepth?.toString().orEmpty()) }
    var caseOpt by remember(initialEntry.id) { mutableStateOf(TriStateOption.from(initialEntry.overrideCaseSensitive)) }
    var wholeWordsOpt by remember(initialEntry.id) { mutableStateOf(TriStateOption.from(initialEntry.matchWholeWords)) }
    var excludeRecursion by remember(initialEntry.id) { mutableStateOf(initialEntry.excludeRecursion) }
    var preventRecursion by remember(initialEntry.id) { mutableStateOf(initialEntry.preventRecursion) }
    var delayUntilRecursion by remember(initialEntry.id) { mutableStateOf(initialEntry.delayUntilRecursion) }
    var ignoreBudget by remember(initialEntry.id) { mutableStateOf(initialEntry.ignoreBudget) }
    var content by remember(initialEntry.id) { mutableStateOf(initialEntry.content) }

    val isAtDepthMode = uiPosition == UiLorePosition.AT_DEPTH_SYSTEM ||
        uiPosition == UiLorePosition.AT_DEPTH_USER ||
        uiPosition == UiLorePosition.AT_DEPTH_ASSISTANT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("编辑世界书条目", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = constantActive,
                        onClick = { constantActive = !constantActive },
                        label = { Text(if (constantActive) "🔵常驻" else "🟢绿灯") },
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("条目名称 / 备注") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("位置：", style = MaterialTheme.typography.labelLarge)
                Select(
                    options = UiLorePosition.entries,
                    selectedOption = uiPosition,
                    onOptionSelected = { uiPosition = it },
                    optionToString = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (uiPosition == UiLorePosition.OUTLET) {
                    OutlinedTextField(
                        value = outletName,
                        onValueChange = { outletName = it },
                        label = { Text("锚点名称 ({{outlet::名称}})") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = depthText,
                        onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) depthText = it },
                        enabled = isAtDepthMode,
                        label = { Text("深度(9999=前)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = orderText,
                        onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() || c == '-' }) orderText = it },
                        label = { Text("顺序(越小越上)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = probText,
                        onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) probText = it },
                        label = { Text("触发%") },
                        modifier = Modifier.weight(0.8f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = primaryKeysText,
                    onValueChange = { primaryKeysText = it },
                    label = { Text("主要关键字（逗号分隔，支持 /regex/s）") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.width(116.dp)) {
                        Select(
                            options = listOf(
                                SelectiveLogic.AND_ANY,
                                SelectiveLogic.AND_ALL,
                                SelectiveLogic.NOT_ANY,
                                SelectiveLogic.NOT_ALL,
                            ),
                            selectedOption = selectiveLogic,
                            onOptionSelected = { selectiveLogic = it },
                            optionToString = { getSelectiveLogicLabel(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = secondaryKeysText,
                        onValueChange = { secondaryKeysText = it },
                        label = { Text("可选过滤器（为空忽略）") },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = scanDepthInput,
                        onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) scanDepthInput = it },
                        label = { Text("扫描深度") },
                        placeholder = { Text("全局") },
                        modifier = Modifier.weight(0.8f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("区分大小写", style = MaterialTheme.typography.labelSmall)
                        Select(
                            options = TriStateOption.entries,
                            selectedOption = caseOpt,
                            onOptionSelected = { caseOpt = it },
                            optionToString = { it.label },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("完整单词", style = MaterialTheme.typography.labelSmall)
                        Select(
                            options = TriStateOption.entries,
                            selectedOption = wholeWordsOpt,
                            onOptionSelected = { wholeWordsOpt = it },
                            optionToString = { it.label },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column {
                    LoreCheckboxRow(
                        checked = excludeRecursion,
                        onCheckedChange = { excludeRecursion = it },
                        label = "不可递归（不会被其他条目激活）",
                    )
                    LoreCheckboxRow(
                        checked = preventRecursion,
                        onCheckedChange = { preventRecursion = it },
                        label = "防止进一步递归",
                    )
                    LoreCheckboxRow(
                        checked = delayUntilRecursion,
                        onCheckedChange = { delayUntilRecursion = it },
                        label = "延迟到递归",
                    )
                    LoreCheckboxRow(
                        checked = ignoreBudget,
                        onCheckedChange = { ignoreBudget = it },
                        label = "无视回复限额",
                    )
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容 (词符: ${content.length})") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                )

                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除此条目", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val mappedPosition = when (uiPosition) {
                        UiLorePosition.BEFORE_CHAR -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                        UiLorePosition.AFTER_CHAR -> InjectionPosition.AFTER_SYSTEM_PROMPT
                        UiLorePosition.OUTLET -> InjectionPosition.OUTLET
                        UiLorePosition.AT_DEPTH_SYSTEM,
                        UiLorePosition.AT_DEPTH_USER,
                        UiLorePosition.AT_DEPTH_ASSISTANT -> InjectionPosition.AT_DEPTH
                    }
                    val mappedRole = when (uiPosition) {
                        UiLorePosition.AT_DEPTH_USER -> MessageRole.USER
                        UiLorePosition.AT_DEPTH_ASSISTANT -> MessageRole.ASSISTANT
                        else -> MessageRole.SYSTEM
                    }
                    val parsedScan = scanDepthInput.toIntOrNull()
                    onSave(
                        initialEntry.copy(
                            name = name,
                            constantActive = constantActive,
                            enabled = enabled,
                            position = mappedPosition,
                            role = mappedRole,
                            outletName = outletName,
                            injectDepth = depthText.toIntOrNull()?.coerceAtLeast(0) ?: initialEntry.injectDepth,
                            priority = orderText.toIntOrNull() ?: initialEntry.priority,
                            probability = (probText.toIntOrNull() ?: initialEntry.probability).coerceIn(0, 100),
                            keywords = splitCommaKeywords(primaryKeysText),
                            selectiveLogic = selectiveLogic,
                            secondaryKeys = splitCommaKeywords(secondaryKeysText),
                            selective = true,
                            overrideScanDepth = parsedScan,
                            scanDepth = parsedScan ?: initialEntry.scanDepth,
                            overrideCaseSensitive = caseOpt.value,
                            caseSensitive = caseOpt.value ?: false,
                            matchWholeWords = wholeWordsOpt.value,
                            excludeRecursion = excludeRecursion,
                            preventRecursion = preventRecursion,
                            delayUntilRecursion = delayUntilRecursion,
                            ignoreBudget = ignoreBudget,
                            content = content,
                        )
                    )
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun LoreCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
