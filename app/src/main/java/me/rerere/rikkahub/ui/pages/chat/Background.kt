package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

@Composable
fun AssistantBackground(
    setting: Settings,
    modifier: Modifier,
    assistant: me.rerere.rikkahub.data.model.Assistant = setting.getCurrentAssistant(),
) {
    // 界面自定义：主题可用 ui.slot("chat.background", ...) 自己画聊天背景；没写时绘制原版
    me.rerere.rikkahub.ui.custom.UISlot(
        name = "chat.background",
        data = mapOf(
            "wallpaper" to assistant.background.orEmpty(),
            "opacity" to assistant.backgroundOpacity,
            "gradient" to assistant.useGradientBackground,
        ),
        parts = mapOf("mesh_gradient" to { info -> MeshGradientBackground(modifier = info.modifier) }),
        modifier = modifier.fillMaxSize(),
    ) {
        OriginalAssistantBackground(assistant, modifier)
    }
}

@Composable
private fun OriginalAssistantBackground(assistant: me.rerere.rikkahub.data.model.Assistant, modifier: Modifier) {
    if (assistant.useGradientBackground) {
        MeshGradientBackground(modifier = modifier)
        return
    }
    if (assistant.background != null) {
        val backgroundColor = MaterialTheme.colorScheme.background
        val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
        Box(modifier = modifier) {
            AsyncImage(
                model = assistant.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
            )

            // 全屏渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                backgroundColor.copy(alpha = 0.2f),
                                backgroundColor.copy(alpha = 0.5f)
                            )
                        )
                    )
            )
        }
    }
}
