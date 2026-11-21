package me.rerere.rikkahub.ui.components.nav

import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.custom.colorOr

/** 当二级页面作为部件直接内嵌在主页骨架（如 `chat.layout` 的 `Host("page:Setting")`）中时为 true，隐藏无处可退的返回键。 */
val LocalUIEmbeddedPage = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    if (LocalUIEmbeddedPage.current) return
    val navController = LocalNavController.current
    // 界面自定义：ui.slot("back_button", ...) 接管（动作 back）；ui.style("back_button", { bg, color })；未设置时为原版
    me.rerere.rikkahub.ui.custom.UISlot(
        name = "back_button",
        actions = mapOf("back" to { navController.popBackStack() }),
        modifier = modifier,
    ) {
        val uiStyle = me.rerere.rikkahub.ui.custom.uiComponentStyle("back_button")
        val scheme = androidx.compose.material3.MaterialTheme.colorScheme
        val containerColor = uiStyle.colorOr({ it.background }, scheme, CustomColors.listItemColors.containerColor)
        FilledTonalIconButton(
            onClick = {
                navController.popBackStack()
            },
            modifier = modifier,
            shapes = IconButtonDefaults.shapes(),
            colors = if (uiStyle == null) {
                IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomColors.listItemColors.containerColor)
            } else {
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = containerColor,
                    contentColor = uiStyle.colorOr({ it.color }, scheme, androidx.compose.material3.contentColorFor(containerColor)),
                )
            },
        ) {
            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = stringResource(R.string.back)
            )
        }
    }
}
