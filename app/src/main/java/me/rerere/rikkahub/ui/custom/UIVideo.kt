package me.rerere.rikkahub.ui.custom

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import me.rerere.rikkahub.data.model.UIMessageNode
import java.io.File

/** 视频来源：http(s) 地址，或 `file:<filesDir 相对路径>`。 */
fun resolveUIVideoUri(context: android.content.Context, src: String): Uri =
    when (val model = resolveUIImageModel(context, src)) {
        is File -> Uri.fromFile(model)
        else -> Uri.parse(model.toString())
    }

/**
 * 视频节点：自动播放，默认循环、静音（适合做动态背景）。App 退到后台时暂停，回来继续。
 * 不给尺寸时占满宽度并按视频比例（加载前按 16:9）。
 */
@Composable
fun UIVideo(node: UIMessageNode, modifier: Modifier, onError: (Throwable) -> Unit) {
    val context = LocalContext.current
    val uri = remember(node.imageSrc) {
        try {
            resolveUIVideoUri(context, node.imageSrc)
        } catch (e: Exception) {
            onError(e)
            null
        }
    } ?: return

    var aspect by remember(uri) { mutableFloatStateOf(16f / 9f) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    player.repeatMode = if (node.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    player.volume = if (node.muted) 0f else 1f

    val textureHolder = remember(player) { arrayOfNulls<TextureView>(1) }
    val scaleMode = node.contentScale
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    textureHolder[0]?.applyVideoScale(aspect, scaleMode)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError(IllegalStateException("视频播放失败（${node.imageSrc}）：${error.message}", error))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val noSize = node.widthDp <= 0f && node.heightDp <= 0f && !node.fillMaxWidth && !node.fillMaxHeight
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                textureHolder[0] = tv
                player.setVideoTextureView(tv)
                tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> (v as TextureView).applyVideoScale(aspect, scaleMode) }
            }
        },
        update = { tv -> tv.applyVideoScale(aspect, scaleMode) },
        onRelease = { tv ->
            player.clearVideoTextureView(tv)
            textureHolder[0] = null
        },
        modifier = if (noSize) modifier.fillMaxWidth().aspectRatio(aspect) else modifier,
    )
}

/** TextureView 默认把画面拉伸到控件大小（fill）；crop 按视频比例裁剪铺满，其余取值完整显示。 */
private fun TextureView.applyVideoScale(videoAspect: Float, mode: String) {
    val crop = mode == "crop"
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f || videoAspect <= 0f) return
    val viewAspect = w / h
    var sx = 1f
    var sy = 1f
    if (mode == "fill") {
        // 拉伸铺满
    } else if (crop) {
        if (videoAspect > viewAspect) sx = videoAspect / viewAspect else sy = viewAspect / videoAspect
    } else {
        if (videoAspect > viewAspect) sy = viewAspect / videoAspect else sx = videoAspect / viewAspect
    }
    setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
}
