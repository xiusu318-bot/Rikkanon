package me.rerere.rikkahub.ui.custom

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** 当前主题是否配置了 `ui.shapes`（切换 `cut` 切角或自定义了任意形状等级的圆角半径）。关闭界面自定义时恒为 false。 */
@Composable
@ReadOnlyComposable
fun hasUICustomShapes(): Boolean =
    LocalActiveUIPreset.current?.globalSection?.let {
        it.enabled && (it.shapeStyle == "cut" || it.shapeCorners.isNotEmpty())
    } == true

/** 搜索框形状：优先读 `ui.style("search_bar", { corner })`，其次跟随 `ui.shapes`，未启用时保持原版胶囊 `CircleShape`。 */
@Composable
@ReadOnlyComposable
fun uiSearchBarShape(defaultShape: Shape = CircleShape): Shape =
    uiComponentStyle("search_bar")?.cornerDp?.let { uiCornerShape(it.dp) }
        ?: if (hasUICustomShapes()) MaterialTheme.shapes.large else defaultShape

/** 普通按钮形状：优先读 `ui.style("button", { corner })`，其次跟随 `ui.shapes`，未启用时保持原版 `ButtonDefaults.shape`。 */
@Composable
fun uiButtonShape(defaultShape: Shape = ButtonDefaults.shape): Shape {
    val style = uiComponentStyle("button")
    if (style?.cornerDp != null) return uiCornerShape(style.cornerDp.dp)
    if (hasUICustomShapes()) return MaterialTheme.shapes.medium
    return defaultShape
}

/** 分页切换 / 独立切换按钮形状：消除 Material 3 Expressive 未选中态写死的 `CircleShape`。 */
@Composable
fun uiToggleButtonShapes(
    styleName: String = "pager_tab",
    buttonSize: ToggleButtonSize = ToggleButtonSize.Small,
): ToggleButtonShapes {
    val style = uiComponentStyle(styleName)
    if (style?.cornerDp == null && !hasUICustomShapes()) {
        return ToggleButtonDefaults.shapesFor(buttonSize)
    }
    val cut = isUICutCornerShape()
    val base: CornerBasedShape = style?.cornerDp?.let { uiCornerShape(it.coerceAtLeast(0f).dp, cut = cut) }
        ?: MaterialTheme.shapes.medium
    val pressed: CornerBasedShape = style?.cornerDp?.let { uiCornerShape((it * 0.6f).coerceAtLeast(0f).dp, cut = cut) }
        ?: MaterialTheme.shapes.extraSmall
    return ToggleButtonShapes(
        shape = base,
        pressedShape = pressed,
        checkedShape = base,
    )
}

/** 分页切换 / 连接式按钮配色覆盖：`bg` 为选中底色，`color` 为选中文字色，`secondaryColor` 为未选中底色。 */
@Composable
fun uiToggleButtonColors(
    styleName: String,
    defaultContainerColor: Color,
): ToggleButtonColors {
    val style = uiComponentStyle(styleName)
        ?: return ToggleButtonDefaults.colors(containerColor = defaultContainerColor)
    val scheme = MaterialTheme.colorScheme
    val uncheckedBg = style.colorOr({ it.secondaryColor }, scheme, defaultContainerColor)
    val checkedBg = style.colorOr({ it.bgOrNull() }, scheme, scheme.primary)
    val checkedFg = style.colorOr({ it.color }, scheme, contentColorFor(checkedBg))
    return ToggleButtonDefaults.colors(
        containerColor = uncheckedBg,
        checkedContainerColor = checkedBg,
        checkedContentColor = checkedFg,
    )
}

private fun me.rerere.rikkahub.data.model.UIComponentStyle.bgOrNull(): String? = background

/** 连接式单选/多选按钮组形状：消除 `ButtonGroupDefaults.connected*` 两端与选中态写死的半圆/全圆胶囊头。 */
@Composable
fun uiConnectedButtonShapes(
    index: Int,
    count: Int,
    styleName: String = "connected_button",
): ToggleButtonShapes {
    val style = uiComponentStyle(styleName)
    if (style?.cornerDp == null && !hasUICustomShapes()) {
        return when {
            count <= 1 -> ToggleButtonDefaults.shapesFor(ToggleButtonSize.Small)
            index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        }
    }
    val cut = isUICutCornerShape()
    val outer: CornerBasedShape = style?.cornerDp?.let { uiCornerShape(it.coerceAtLeast(0f).dp, cut = cut) }
        ?: MaterialTheme.shapes.medium
    val configuredOuterDp = style?.cornerDp
        ?: LocalActiveUIPreset.current?.globalSection?.shapeCorners?.get("medium")
        ?: 8f
    val innerDp = minOf(configuredOuterDp.coerceAtLeast(0f), 2f)
    val inner = CornerSize(innerDp.dp)
    val itemShape: CornerBasedShape = when {
        count <= 1 -> outer
        index == 0 -> outer.copy(topEnd = inner, bottomEnd = inner)
        index == count - 1 -> outer.copy(topStart = inner, bottomStart = inner)
        else -> uiCornerShape(innerDp.dp, cut = cut)
    }
    val pressedShape: CornerBasedShape = uiCornerShape(minOf(configuredOuterDp.coerceAtLeast(0f), 2f).dp, cut = cut)
    return ToggleButtonShapes(
        shape = itemShape,
        pressedShape = pressedShape,
        checkedShape = itemShape,
    )
}

/** 分段列表项单行形状：联动 `ui.style("card_group")` 与 `ui.shapes`。 */
@Composable
@ReadOnlyComposable
fun uiSegmentedItemShape(index: Int, count: Int): CornerBasedShape {
    val groupStyle = uiComponentStyle("card_group")
    val cut = isUICutCornerShape()
    val outerCorner = groupStyle?.cornerDp?.coerceAtLeast(0f)?.dp
        ?: LocalActiveUIPreset.current?.globalSection?.shapeCorners?.get("large")?.coerceAtLeast(0f)?.dp
        ?: 14.dp
    val innerCorner = minOf(outerCorner, 4.dp)
    val top = if (index <= 0) outerCorner else innerCorner
    val bottom = if (index >= count - 1) outerCorner else innerCorner
    return uiCornerShape(
        topStart = top,
        topEnd = top,
        bottomEnd = bottom,
        bottomStart = bottom,
        cut = cut,
    )
}

/** 分段列表项（`SegmentedListItem`）形状集：未启用界面自定义时走官方 `ListItemDefaults.segmentedShapes`。 */
@Composable
fun uiSegmentedListItemShapes(index: Int, count: Int): ListItemShapes {
    if (uiComponentStyle("card_group") == null && !hasUICustomShapes()) {
        return ListItemDefaults.segmentedShapes(index = index, count = count)
    }
    val s = uiSegmentedItemShape(index, count)
    return ListItemShapes(
        shape = s,
        selectedShape = s,
        pressedShape = s,
        focusedShape = s,
        hoveredShape = s,
        draggedShape = s,
    )
}

/** 分段列表项间距：优先读 `ui.style("card_group", { spacing })`，默认 `ListItemDefaults.SegmentedGap`。 */
@Composable
@ReadOnlyComposable
fun uiSegmentedListGap(): Dp =
    uiComponentStyle("card_group")?.spacingDp?.coerceAtLeast(0f)?.dp ?: ListItemDefaults.SegmentedGap

/** 分段列表项描边修饰符：当 `ui.style("card_group", { border })` 设置了描边时自动勾勒分段轮廓。 */
@Composable
fun Modifier.uiSegmentedItemBorder(index: Int, count: Int): Modifier {
    val groupStyle = uiComponentStyle("card_group") ?: return this
    val bw = groupStyle.borderWidthDp?.takeIf { it > 0f } ?: return this
    val scheme = MaterialTheme.colorScheme
    val borderColor = groupStyle.colorOr({ it.borderColor }, scheme, scheme.outlineVariant)
    val shape = uiSegmentedItemShape(index, count)
    return this.border(BorderStroke(bw.dp, borderColor), shape)
}

/** 分段列表项配色：联动 `ui.style("list_item")` 与 `ui.style("card_group")`，未配置时走原版 `ListItemDefaults.segmentedColors()`。 */
@Composable
fun uiSegmentedListItemColors(destructive: Boolean = false): ListItemColors {
    val scheme = MaterialTheme.colorScheme
    if (destructive) {
        return ListItemDefaults.segmentedColors(
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
            leadingContentColor = scheme.onErrorContainer,
        )
    }
    val groupStyle = uiComponentStyle("card_group")
    val listStyle = uiComponentStyle("list_item")
    if (groupStyle == null && listStyle == null) {
        return ListItemDefaults.segmentedColors()
    }
    val base = ListItemDefaults.segmentedColors()
    val bg = groupStyle?.background?.let { resolveUIColor(it, scheme, base.containerColor) }
        ?: listStyle?.background?.let { resolveUIColor(it, scheme, base.containerColor) }
        ?: base.containerColor
    val fg = groupStyle?.color?.let { resolveUIColor(it, scheme, base.contentColor) }
        ?: listStyle?.color?.let { resolveUIColor(it, scheme, base.contentColor) }
        ?: base.contentColor
    val sub = groupStyle?.secondaryColor?.let { resolveUIColor(it, scheme, base.supportingContentColor) }
        ?: listStyle?.secondaryColor?.let { resolveUIColor(it, scheme, base.supportingContentColor) }
        ?: base.supportingContentColor
    return ListItemDefaults.segmentedColors(
        containerColor = bg,
        contentColor = fg,
        leadingContentColor = fg,
        trailingContentColor = fg,
        supportingContentColor = sub,
        selectedContainerColor = scheme.secondaryContainer,
        selectedContentColor = scheme.onSecondaryContainer,
        selectedLeadingContentColor = scheme.onSecondaryContainer,
        selectedTrailingContentColor = scheme.onSecondaryContainer,
        selectedSupportingContentColor = scheme.onSecondaryContainer,
    )
}

/**
 * 跟随 `ui.shapes` 与 `ui.style("switch")` 的开关组件。
 * - 关闭界面自定义或未配置 `ui.shapes` / `ui.style("switch")` 时，100% 调用原版 Material 3 `Switch`；
 * - 开启自定义形状或 `ui.style("switch")` 时，轨道与滑块同步变为切角或对应小圆角。
 */
@Composable
fun UISwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
    cornerOverrideDp: Float? = null,
) {
    val switchStyle = uiComponentStyle("switch")
    if (switchStyle == null && cornerOverrideDp == null && !hasUICustomShapes()) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            thumbContent = thumbContent,
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
        )
        return
    }

    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by actualInteractionSource.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val cut = isUICutCornerShape()

    val trackWidth = (switchStyle?.widthDp ?: 52f).coerceIn(36f, 80f).dp
    val trackHeight = 32.dp
    val rawCorner = cornerOverrideDp
        ?: switchStyle?.cornerDp
        ?: LocalActiveUIPreset.current?.globalSection?.shapeCorners?.get("small")
        ?: if (cut) 6f else 16f
    val trackCornerDp = rawCorner.coerceIn(0f, 16f)
    val thumbCornerDp = (trackCornerDp - 2f).coerceAtLeast(if (trackCornerDp > 0f) 1.5f else 0f)
    val trackShape = uiCornerShape(trackCornerDp.dp, cut = cut)
    val thumbShape = uiCornerShape(thumbCornerDp.dp, cut = cut)

    val checkedTrack = switchStyle.colorOr({ it.background }, scheme, colors.checkedTrackColor)
    val uncheckedTrack = switchStyle.colorOr({ it.secondaryColor }, scheme, colors.uncheckedTrackColor)
    val checkedThumb = switchStyle.colorOr({ it.color }, scheme, colors.checkedThumbColor)
    val uncheckedThumb = colors.uncheckedThumbColor
    val uncheckedBorder = switchStyle.colorOr({ it.borderColor }, scheme, colors.uncheckedBorderColor)
    val checkedBorder = switchStyle?.borderColor?.let { resolveUIColor(it, scheme, colors.checkedBorderColor) }
        ?: colors.checkedBorderColor

    val targetTrackColor = when {
        !enabled && checked -> colors.disabledCheckedTrackColor
        !enabled -> colors.disabledUncheckedTrackColor
        checked -> checkedTrack
        else -> uncheckedTrack
    }
    val targetThumbColor = when {
        !enabled && checked -> colors.disabledCheckedThumbColor
        !enabled -> colors.disabledUncheckedThumbColor
        checked -> checkedThumb
        else -> uncheckedThumb
    }
    val targetBorderColor = when {
        !enabled && checked -> colors.disabledCheckedBorderColor
        !enabled -> colors.disabledUncheckedBorderColor
        checked -> checkedBorder
        else -> uncheckedBorder
    }
    val targetIconColor = when {
        !enabled && checked -> colors.disabledCheckedIconColor
        !enabled -> colors.disabledUncheckedIconColor
        checked -> colors.checkedIconColor
        else -> colors.uncheckedIconColor
    }

    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val targetThumbSize = when {
        pressed -> 26.dp
        checked || thumbContent != null -> 24.dp
        else -> 18.dp
    }
    val targetThumbOffsetX = if (checked) {
        trackWidth - targetThumbSize - 4.dp
    } else {
        (trackHeight - targetThumbSize) / 2
    }

    val thumbSize by animateDpAsState(targetValue = targetThumbSize, animationSpec = spatialSpec, label = "UISwitchThumbSize")
    val thumbOffsetX by animateDpAsState(targetValue = targetThumbOffsetX, animationSpec = spatialSpec, label = "UISwitchOffset")
    val animTrackColor by animateColorAsState(targetValue = targetTrackColor, animationSpec = effectsSpec, label = "UISwitchTrack")
    val animThumbColor by animateColorAsState(targetValue = targetThumbColor, animationSpec = effectsSpec, label = "UISwitchThumb")
    val animBorderColor by animateColorAsState(targetValue = targetBorderColor, animationSpec = effectsSpec, label = "UISwitchBorder")

    val borderWidth = if (checked) {
        (switchStyle?.borderWidthDp ?: 0f).coerceAtLeast(0f).dp
    } else {
        (switchStyle?.borderWidthDp ?: 2f).coerceAtLeast(0.5f).dp
    }

    val toggleMod = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                interactionSource = actualInteractionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(toggleMod)
            .size(width = trackWidth, height = trackHeight)
            .clip(trackShape)
            .background(animTrackColor, trackShape)
            .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, animBorderColor, trackShape) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(x = thumbOffsetX.roundToPx(), y = 0) }
                .size(thumbSize)
                .clip(thumbShape)
                .background(animThumbColor, thumbShape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbContent != null) {
                CompositionLocalProvider(LocalContentColor provides targetIconColor) {
                    thumbContent()
                }
            }
        }
    }
}

private val UIDefaultSheetDragHandle: @Composable () -> Unit = {}

/**
 * 跟随 `ui.shapes` 与 `ui.style("bottom_sheet")` 的底部弹窗。
 * - 关闭界面自定义时，100% 调用原版 `ModalBottomSheet`（默认顶部圆角与胶囊拖拽条）；
 * - 开启自定义形状或配置 `ui.style("bottom_sheet", { bg, color, secondaryColor, corner, opacity, width, border })` 时：
 *   顶部形状切换为切角或自定义圆角，拖拽指示条消除胶囊圆头（`width: 0` 可隐藏拖拽条，`secondaryColor` 改拖拽条颜色）。
 */
@Composable
fun UIModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: (@Composable () -> Unit)? = UIDefaultSheetDragHandle,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val preset = LocalActiveUIPreset.current
    if (preset == null) {
        val baseContainer = if (containerColor != Color.Unspecified) containerColor else BottomSheetDefaults.ContainerColor
        val baseContent = if (contentColor != Color.Unspecified) contentColor else contentColorFor(baseContainer)
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            sheetMaxWidth = sheetMaxWidth,
            sheetGesturesEnabled = sheetGesturesEnabled,
            shape = shape ?: BottomSheetDefaults.ExpandedShape,
            containerColor = baseContainer,
            contentColor = baseContent,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = if (dragHandle === UIDefaultSheetDragHandle) ({ BottomSheetDefaults.DragHandle() }) else dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            content = content,
        )
        return
    }

    val style = uiComponentStyle("bottom_sheet")
    val customShapes = hasUICustomShapes()
    val cut = isUICutCornerShape()
    val scheme = MaterialTheme.colorScheme

    val effectiveShape = shape
        ?: style?.cornerDp?.let { r ->
            val radius = r.coerceAtLeast(0f).dp
            uiCornerShape(topStart = radius, topEnd = radius, bottomEnd = 0.dp, bottomStart = 0.dp, cut = cut)
        }
        ?: BottomSheetDefaults.ExpandedShape

    val rawContainer = if (containerColor != Color.Unspecified) {
        containerColor
    } else {
        style.colorOr({ it.background }, scheme, BottomSheetDefaults.ContainerColor)
    }
    val effectiveContainer = style?.opacity?.let { op ->
        rawContainer.copy(alpha = (rawContainer.alpha * op.coerceIn(0f, 1f)).coerceIn(0f, 1f))
    } ?: rawContainer

    val effectiveContent = if (contentColor != Color.Unspecified) {
        contentColor
    } else {
        style.colorOr({ it.color }, scheme, contentColorFor(effectiveContainer))
    }

    val effectiveDragHandle: (@Composable () -> Unit)? = when {
        dragHandle !== UIDefaultSheetDragHandle -> dragHandle
        style?.widthDp != null && style.widthDp <= 0f -> null
        style != null || customShapes -> ({
            val handleCorner = (style?.cornerDp?.let { minOf(it, 2f) } ?: 2f).coerceAtLeast(0f).dp
            BottomSheetDefaults.DragHandle(
                width = (style?.widthDp ?: 32f).dp,
                shape = uiCornerShape(handleCorner, cut = cut),
                color = style.colorOr({ it.secondaryColor }, scheme, scheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        })
        else -> ({ BottomSheetDefaults.DragHandle() })
    }

    val borderThickness = style?.borderWidthDp?.takeIf { it > 0f }
    val borderColor = style.colorOr({ it.borderColor }, scheme, scheme.outlineVariant)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = effectiveShape,
        containerColor = effectiveContainer,
        contentColor = effectiveContent,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = effectiveDragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
    ) {
        if (borderThickness != null) {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = borderThickness.dp,
                color = borderColor,
            )
        }
        content()
    }
}
