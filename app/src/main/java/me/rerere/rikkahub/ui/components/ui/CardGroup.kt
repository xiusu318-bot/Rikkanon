package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.data.model.UIComponentStyle
import me.rerere.rikkahub.ui.custom.LocalActiveUIPreset
import me.rerere.rikkahub.ui.custom.UISlot
import me.rerere.rikkahub.ui.custom.colorOr
import me.rerere.rikkahub.ui.custom.uiComponentStyle
import me.rerere.rikkahub.ui.custom.uiSafeClip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.unit.sp

private val CardGroupCorner = 14.dp

private data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: (@Composable () -> Unit)?,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
    val formContent: (@Composable ColumnScope.() -> Unit)? = null,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        headlineContent: @Composable () -> Unit,
    )

    fun formItem(
        modifier: Modifier = Modifier,
        headlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
            )
        )
    }

    override fun formItem(
        modifier: Modifier,
        headlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = null,
                modifier = modifier,
                overlineContent = null,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = null,
                formContent = content,
            )
        )
    }
}

fun CardGroupScope.switchItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
) = item(
    onClick = if (enabled) {
        { onCheckedChange(!checked) }
    } else null,
    modifier = modifier,
    supportingContent = supportingContent,
    leadingContent = leadingContent,
    trailingContent = {
        me.rerere.rikkahub.ui.custom.UISwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
    headlineContent = headlineContent,
)

fun cardGroupItemShape(
    index: Int,
    count: Int,
    corner: androidx.compose.ui.unit.Dp = CardGroupCorner,
    cut: Boolean = false,
): Shape {
    val top = if (index == 0) corner else 4.dp
    val bottom = if (index == count - 1) corner else 4.dp
    return me.rerere.rikkahub.ui.custom.uiCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom, cut = cut)
}

@Composable
fun CardGroupIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val tint = if (contentColor != Color.Unspecified) {
        contentColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 界面自定义 ui.style("card_group.icon", { color, bg, corner, padding, width（图标尺寸）})；未设置时为原版
    val uiStyle = uiComponentStyle("card_group.icon")
    if (uiStyle == null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier.size(22.dp),
            tint = tint,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val shape = me.rerere.rikkahub.ui.custom.uiCornerShape((uiStyle.cornerDp ?: 0f).dp)
    val bg = uiStyle.colorOr({ it.background }, scheme, Color.Unspecified)
    Box(
        modifier = modifier
            .then(if (bg != Color.Unspecified) Modifier.clip(shape).background(bg, shape) else Modifier)
            .padding(horizontal = (uiStyle.paddingHorizontalDp ?: 0f).dp, vertical = (uiStyle.paddingVerticalDp ?: 0f).dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size((uiStyle.widthDp ?: 22f).dp),
            tint = if (contentColor != Color.Unspecified) contentColor else uiStyle.colorOr({ it.color }, scheme, tint),
        )
    }
}

@Composable
fun CardGroupValue(
    text: String,
    modifier: Modifier = Modifier,
) {
    // 界面自定义 ui.style("card_group.value", { bg, color, corner, padding, fontSize })；未设置时各值与原版相同
    val uiStyle = uiComponentStyle("card_group.value")
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = uiStyle?.cornerDp?.let { me.rerere.rikkahub.ui.custom.uiCornerShape(it.dp) }
            ?: if (me.rerere.rikkahub.ui.custom.hasUICustomShapes()) MaterialTheme.shapes.small else CircleShape,
        color = uiStyle.colorOr({ it.background }, scheme, MaterialTheme.colorScheme.secondaryContainer),
        contentColor = uiStyle.colorOr({ it.color }, scheme, MaterialTheme.colorScheme.onSecondaryContainer),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = (uiStyle?.paddingHorizontalDp ?: 10f).dp, vertical = (uiStyle?.paddingVerticalDp ?: 4f).dp),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum").let { st ->
                uiStyle?.fontSizeSp?.let { st.copy(fontSize = it.sp) } ?: st
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun CardGroupFormItem(
    item: CardGroupItem,
    formContent: @Composable ColumnScope.() -> Unit,
    style: UIComponentStyle? = null,
) {
    Surface(
        modifier = item.modifier.fillMaxWidth(),
        color = style.colorOr({ it.background }, MaterialTheme.colorScheme, CustomColors.listItemColors.containerColor),
        contentColor = style.colorOr({ it.color }, MaterialTheme.colorScheme, contentColorFor(CustomColors.listItemColors.containerColor)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.headlineContent != null || item.leadingContent != null || item.trailingContent != null) {
                Row(
                    verticalAlignment = if (item.supportingContent != null) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item.leadingContent?.invoke()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item.headlineContent?.let { headline ->
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge, headline)
                        }
                        item.supportingContent?.let { supporting ->
                            CompositionLocalProvider(
                                LocalContentColor provides style.colorOr({ it.secondaryColor }, MaterialTheme.colorScheme, MaterialTheme.colorScheme.onSurfaceVariant)
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium, supporting)
                            }
                        }
                    }
                    item.trailingContent?.invoke()
                }
            }
            formContent()
        }
    }
}

@Composable
private fun CardGroupListItem(
    item: CardGroupItem,
    style: UIComponentStyle? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }

    ListItem(
        headlineContent = item.headlineContent ?: {},
        modifier = item.modifier
            .fillMaxWidth()
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            ),
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        colors = item.colors ?: CustomColors.listItemColors.let { base ->
            // 界面自定义 ui.style("card_group", { bg, color, secondaryColor })；未设置时为原版
            if (style == null) base else {
                val scheme = MaterialTheme.colorScheme
                base.copy(
                    containerColor = style.colorOr({ it.background }, scheme, base.containerColor),
                    contentColor = style.colorOr({ it.color }, scheme, base.contentColor),
                    supportingContentColor = style.colorOr({ it.secondaryColor }, scheme, base.supportingContentColor),
                )
            }
        },
    )
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()

    // 界面自定义：ui.style("card_group" / "card_group.title") 改样式，ui.slot("card_group.title" / "card_group.item") 接管标题与每一行；
    // 未设置时各值与原版相同
    val groupStyle = uiComponentStyle("card_group")
    val titleStyle = uiComponentStyle("card_group.title")
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        if (title != null) {
            UISlot(
                name = "card_group.title",
                parts = mapOf("title" to { info -> Box(info.modifier) { ProvideTextStyle(info.textStyle) { title() } } }),
            ) {
                CompositionLocalProvider(LocalContentColor provides titleStyle.colorOr({ it.color }, scheme, MaterialTheme.colorScheme.primary)) {
                    ProvideTextStyle(MaterialTheme.typography.titleSmallEmphasized.let { st -> titleStyle?.fontSizeSp?.let { st.copy(fontSize = it.sp) } ?: st }) {
                        Box(
                            modifier = Modifier.padding(
                                start = (titleStyle?.paddingHorizontalDp ?: 4f).dp,
                                top = (titleStyle?.paddingVerticalDp ?: 8f).dp,
                                bottom = (titleStyle?.paddingVerticalDp ?: 8f).dp,
                            )
                        ) {
                            title()
                        }
                    }
                }
            }
        }
        val count = scope.items.size
        if (count > 0) {
            val corner = groupStyle?.cornerDp?.dp ?: CardGroupCorner
            val cut = me.rerere.rikkahub.ui.custom.isUICutCornerShape()
            val groupShape = me.rerere.rikkahub.ui.custom.uiCornerShape(corner, cut = cut)
            val border = groupStyle?.borderWidthDp?.takeIf { it > 0f }?.let {
                BorderStroke(it.dp, groupStyle.colorOr({ s -> s.borderColor }, scheme, scheme.outlineVariant))
            }
            val gap = groupStyle?.spacingDp
            if (gap == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .uiSafeClip(groupShape)
                        .then(if (border != null) Modifier.border(border, groupShape) else Modifier)
                ) {
                    scope.items.fastForEachIndexed { index, item ->
                        CardGroupEntry(item = item, index = index, count = count, style = groupStyle)
                        if (index != count - 1) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
            } else {
                // 写了 spacing：各行分开，用间距代替分割线（首尾行外侧用组圆角，其余用小圆角）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(gap.dp),
                ) {
                    scope.items.fastForEachIndexed { index, item ->
                        val shape = cardGroupItemShape(index, count, corner, cut = cut)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .uiSafeClip(shape)
                                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                        ) {
                            CardGroupEntry(item = item, index = index, count = count, style = groupStyle)
                        }
                    }
                }
            }
        }
    }
}

/** 一行：主题可用 ui.slot("card_group.item", ...) 接管；没写时为原版的列表项 / 表单项。 */
@Composable
private fun CardGroupEntry(item: CardGroupItem, index: Int, count: Int, style: UIComponentStyle?) {
    val formContent = item.formContent
    val slotActive = LocalActiveUIPreset.current?.hasSlot("card_group.item") == true
    UISlot(
        name = "card_group.item",
        data = if (slotActive) mapOf(
            "index" to index,
            "count" to count,
            "first" to (index == 0),
            "last" to (index == count - 1),
            "clickable" to (item.onClick != null),
            "form" to (formContent != null),
            "hasSupporting" to (item.supportingContent != null),
            "hasLeading" to (item.leadingContent != null),
            "hasTrailing" to (item.trailingContent != null),
            "hasOverline" to (item.overlineContent != null),
        ) else emptyMap(),
        parts = if (slotActive) buildMap {
            fun part(name: String, content: (@Composable () -> Unit)?) {
                if (content != null) put(name) { info -> Box(info.modifier) { ProvideTextStyle(info.textStyle) { content() } } }
            }
            part("headline", item.headlineContent)
            part("supporting", item.supportingContent)
            part("overline", item.overlineContent)
            part("leading", item.leadingContent)
            part("trailing", item.trailingContent)
            if (formContent != null) put("form") { info -> Column(info.modifier) { formContent() } }
        } else emptyMap(),
        actions = item.onClick?.let { mapOf("click" to it) } ?: emptyMap(),
        modifier = item.modifier.fillMaxWidth(),
    ) {
        if (formContent != null) {
            CardGroupFormItem(item = item, formContent = formContent, style = style)
        } else {
            CardGroupListItem(item = item, style = style)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Card Group")
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = { Text("About") },
            ) {
                item(
                    headlineContent = { Text("第一项") },
                )
                item(
                    headlineContent = { Text("第二项") },
                    supportingContent = { Text("支持文本") },
                )
                item(
                    onClick = {},
                    headlineContent = { Text("第三项") },
                    trailingContent = { Text("→") },
                )
            }
        }
    }
}
