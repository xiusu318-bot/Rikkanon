package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.ui.custom.colorOr
import androidx.compose.ui.unit.sp

enum class TagType {
    DEFAULT,
    SUCCESS,
    ERROR,
    WARNING,
    INFO
}

@Composable
fun Tag(
    modifier: Modifier = Modifier,
    type: TagType = TagType.DEFAULT,
    onClick: (() -> Unit)? = null,
    children: @Composable RowScope.() -> Unit
) {
    val background = when (type) {
        TagType.SUCCESS -> MaterialTheme.extendColors.green2
        TagType.ERROR -> MaterialTheme.extendColors.red2
        TagType.WARNING -> MaterialTheme.extendColors.orange2
        TagType.INFO -> MaterialTheme.extendColors.blue2
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = when (type) {
        TagType.SUCCESS -> MaterialTheme.extendColors.gray8
        TagType.ERROR -> MaterialTheme.extendColors.red8
        TagType.WARNING -> MaterialTheme.extendColors.orange8
        TagType.INFO -> MaterialTheme.extendColors.blue8
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    // 界面自定义 ui.style("tag", { bg, color, corner, padding, fontSize })：bg / color 只改默认类型（成功 / 错误等语义色不变）；未设置时为原版
    val uiStyle = me.rerere.rikkahub.ui.custom.uiComponentStyle("tag")
    val scheme = MaterialTheme.colorScheme
    val isDefault = type == TagType.DEFAULT
    val shape = uiStyle?.cornerDp?.let { me.rerere.rikkahub.ui.custom.uiCornerShape(it.dp) }
        ?: if (me.rerere.rikkahub.ui.custom.hasUICustomShapes()) MaterialTheme.shapes.extraSmall else RoundedCornerShape(50)
    val textStyle = MaterialTheme.typography.labelSmall.copy(
        color = if (isDefault) uiStyle.colorOr({ it.color }, scheme, textColor) else textColor,
    ).let { st -> uiStyle?.fontSizeSp?.let { st.copy(fontSize = it.sp) } ?: st }
    ProvideTextStyle(textStyle) {
        Row(
            modifier = modifier
                .clip(shape)
                .background(if (isDefault) uiStyle.colorOr({ it.background }, scheme, background) else background)
                .let {
                    if (onClick != null) {
                        it.clickable { onClick() }
                    } else {
                        it
                    }
                }
                .padding(horizontal = (uiStyle?.paddingHorizontalDp ?: 6f).dp, vertical = (uiStyle?.paddingVerticalDp ?: 1f).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            children()
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun TagPreview() {
    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tag(type = TagType.SUCCESS) {
            Text("测试")
        }
        Tag(type = TagType.ERROR) {
            Text("测试")
        }
        Tag(type = TagType.WARNING) {
            Text("测试")
        }
        Tag(type = TagType.INFO) {
            Text("测试")
        }
        Tag(type = TagType.DEFAULT) {
            Text("测试")
        }
    }
}
