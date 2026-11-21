package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.custom.UISwitch
import me.rerere.rikkahub.ui.custom.hasUICustomShapes
import me.rerere.rikkahub.ui.custom.uiButtonShape
import me.rerere.rikkahub.ui.custom.uiSegmentedItemBorder
import me.rerere.rikkahub.ui.custom.uiSegmentedListGap
import me.rerere.rikkahub.ui.custom.uiSegmentedListItemColors
import me.rerere.rikkahub.ui.custom.uiSegmentedListItemShapes

@Composable
fun PromptPresetsContent(
    promptPresets: List<PromptPreset>,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = uiSegmentedListItemColors(),
    onItemClick: ((PromptPreset) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = promptPresets,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
        key = { it.id },
    ) { index, count, preset, shapes ->
        val enabledBlocksCount = preset.blocks.count { it.enabled }
        ExtensionListItem(
            title = preset.name.ifBlank { stringResource(R.string.extension_content_unnamed) },
            description = preset.description.ifBlank { "启用块: $enabledBlocksCount / ${preset.blocks.size}" },
            checked = selectedIds.contains(preset.id),
            onCheckedChange = { checked -> onToggle(preset.id, checked) },
            onClick = onItemClick?.let { click -> { click(preset) } },
            shapes = shapes,
            colors = colors,
            modifier = Modifier.uiSegmentedItemBorder(index, count),
        )
    }
}

@Composable
fun ModeInjectionsContent(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = uiSegmentedListItemColors(),
    onItemClick: ((PromptInjection.ModeInjection) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = modeInjections,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
        key = { it.id },
    ) { index, count, injection, shapes ->
        ExtensionListItem(
            title = injection.name.ifBlank { stringResource(R.string.extension_content_unnamed) },
            checked = selectedIds.contains(injection.id),
            onCheckedChange = { checked -> onToggle(injection.id, checked) },
            onClick = onItemClick?.let { click -> { click(injection) } },
            shapes = shapes,
            colors = colors,
            modifier = Modifier.uiSegmentedItemBorder(index, count),
        )
    }
}

@Composable
fun LorebooksContent(
    lorebooks: List<Lorebook>,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = uiSegmentedListItemColors(),
    onItemClick: ((Lorebook) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = lorebooks,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
        key = { it.id },
    ) { index, count, lorebook, shapes ->
        ExtensionListItem(
            title = lorebook.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) },
            description = lorebook.description,
            checked = selectedIds.contains(lorebook.id),
            onCheckedChange = { checked -> onToggle(lorebook.id, checked) },
            onClick = onItemClick?.let { click -> { click(lorebook) } },
            shapes = shapes,
            colors = colors,
            modifier = Modifier.uiSegmentedItemBorder(index, count),
        )
    }
}

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = uiSegmentedListItemColors(),
    onItemClick: ((SkillMetadata) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = skills,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
        key = { it.skillDir.absolutePath },
    ) { index, count, skill, shapes ->
        ExtensionListItem(
            title = skill.name,
            description = skill.description,
            checked = enabledSkills.contains(skill.name),
            onCheckedChange = { checked -> onToggle(skill.name, checked) },
            onClick = onItemClick?.let { click -> { click(skill) } },
            shapes = shapes,
            colors = colors,
            modifier = Modifier.uiSegmentedItemBorder(index, count),
        )
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = uiSegmentedListItemColors(),
    onItemClick: ((QuickMessage) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = quickMessages,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
        key = { it.id },
    ) { index, count, quickMessage, shapes ->
        ExtensionListItem(
            title = quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) },
            description = quickMessage.content,
            descriptionMaxLines = 2,
            checked = selectedIds.contains(quickMessage.id),
            onCheckedChange = { checked -> onToggle(quickMessage.id, checked) },
            onClick = onItemClick?.let { click -> { click(quickMessage) } },
            shapes = shapes,
            colors = colors,
            modifier = Modifier.uiSegmentedItemBorder(index, count),
        )
    }
}

// 扩展项的分段列表，末尾可带一个跳转到管理页的按钮
@Composable
private fun <T> ExtensionList(
    items: List<T>,
    modifier: Modifier,
    contentPadding: PaddingValues,
    onManage: (() -> Unit)?,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (index: Int, count: Int, item: T, shapes: ListItemShapes) -> Unit,
) {
    val itemKey: ((Int, T) -> Any)? = key?.let { keyOf -> { _, item -> keyOf(item) } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(uiSegmentedListGap()),
    ) {
        itemsIndexed(items = items, key = itemKey) { index, item ->
            itemContent(index, items.size, item, uiSegmentedListItemShapes(index = index, count = items.size))
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
private fun ExtensionListItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ListItemShapes,
    colors: ListItemColors,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    description: String = "",
    // 技能等的描述可能很长，完整内容在管理页看
    descriptionMaxLines: Int = 3,
) {
    val supporting: (@Composable () -> Unit)? = if (description.isNotBlank()) {
        {
            Text(
                text = description,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else null

    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            modifier = modifier,
            shapes = shapes,
            colors = colors,
            supportingContent = supporting,
            trailingContent = {
                // 点击开关以外区域进入管理，只有点击开关才切换启用状态
                UISwitch(checked = checked, onCheckedChange = onCheckedChange)
            },
        ) {
            Text(title)
        }
    } else {
        SegmentedListItem(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            shapes = shapes,
            colors = colors,
            supportingContent = supporting,
            trailingContent = {
                UISwitch(checked = checked, onCheckedChange = onCheckedChange)
            },
        ) {
            Text(title)
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilledTonalButton(onClick = onClick, shape = uiButtonShape()) {
            Icon(
                imageVector = HugeIcons.Settings03,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.extension_content_manage))
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    description: String? = null,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = HugeIcons.Package,
) {
    val heroShape = if (hasUICustomShapes()) MaterialTheme.shapes.extraLarge else MaterialShapes.Cookie9Sided.toShape()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = heroShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (buttonText != null && onAction != null) {
            FilledTonalButton(onClick = onAction, shape = uiButtonShape()) {
                Icon(
                    imageVector = HugeIcons.Link01,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(buttonText)
            }
        }
    }
}
