package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegexPhase
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.extractThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

@Composable
private fun rememberReasoningState(reasoning: UIMessagePart.Reasoning): Pair<ReasoningState, Boolean> {
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()

    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = scrollState,
            initialDuration = reasoning.finishedAt?.let { it - reasoning.createdAt }
                ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent)
                state.expandState = ReasoningCardState.Preview
            scrollState.animateScrollTo(scrollState.maxValue)
        } else {
            if (state.expandState.expanded) {
                state.expandState = if (settings.displaySetting.autoCloseThinking)
                    ReasoningCardState.Collapsed
                else
                    ReasoningCardState.Expanded
            }
        }
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                state.duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }

    return state to loading
}

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    regexDepth: Int?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
    loading: Boolean,
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio
    val bodySmall = MaterialTheme.typography.bodySmall
    // 界面自定义 ui.style("reasoning") 的 secondaryColor：思考正文颜色；未设置时为原版
    val customReasoningTextColor = me.rerere.rikkahub.ui.custom.uiComponentStyle("reasoning")?.secondaryColor?.let {
        me.rerere.rikkahub.ui.custom.resolveUIColor(it, MaterialTheme.colorScheme)
    } ?: Color.Unspecified
    val reasoningTextStyle = bodySmall.copy(
        fontSize = bodySmall.fontSize * fontSizeRatio,
        lineHeight = bodySmall.lineHeight * fontSizeRatio,
        fontFamily = LocalTextStyle.current.fontFamily,
        color = if (customReasoningTextColor != Color.Unspecified) customReasoningTextColor else bodySmall.color,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeHeight / size.height) to Color.Black,
                                    (1 - fadeHeight / size.height) to Color.Black,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                }
            }
    ) {
        val reasoningContent = @Composable {
            val displayReasoning = remember(reasoning.reasoning, assistant?.regexes, regexDepth) {
                reasoning.reasoning.replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    phase = AssistantRegexPhase.VISUAL,
                    depth = regexDepth,
                    // 思考过程仍在流式输出时不写入全局结果缓存
                    useCache = !loading,
                )
            }
            MarkdownBlock(
                content = displayReasoning,
                style = reasoningTextStyle,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 流式生成期间不启用 SelectionContainer，避免 selectable 列表并发修改导致的
        // ConcurrentModificationException（详见 ChatMessage.kt 文本块同样处理）。
        if (loading) {
            reasoningContent()
        } else {
            SelectionContainer {
                reasoningContent()
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    regexDepth: Int? = null,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val (state, loading) = rememberReasoningState(reasoning)
    val thinkingTitle = reasoning.reasoning.extractThinkingTitle()
    val showThinkingTitle = loading && thinkingTitle != null
    val chatFontFamily = LocalTextStyle.current.fontFamily
    // 界面自定义 ui.style("reasoning") 的 color：标题与图标颜色；未设置时为原版
    val titleColor = me.rerere.rikkahub.ui.custom.uiComponentStyle("reasoning")?.color?.let {
        me.rerere.rikkahub.ui.custom.resolveUIColor(it, MaterialTheme.colorScheme, MaterialTheme.colorScheme.secondary)
    } ?: MaterialTheme.colorScheme.secondary

    // 界面自定义：主题可用 ui.slot("chat.reasoning.header", ...) 接管标题行；没写时走原版
    val headerSlotActive = me.rerere.rikkahub.ui.custom.LocalActiveUIPreset.current?.hasSlot("chat.reasoning.header") == true

    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { state.onExpandedChange(it, loading) },
        icon = {
            Icon(
                imageVector = HugeIcons.Idea01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = titleColor,
            )
        },
        label = if (headerSlotActive) {
            {
                ReasoningHeaderSlot(
                    loading = loading,
                    thinkingTitle = thinkingTitle,
                    duration = state.duration,
                    title = { info ->
                        ReasoningLabel(
                            showThinkingTitle = showThinkingTitle,
                            thinkingTitle = thinkingTitle,
                            duration = state.duration,
                            loading = loading,
                            color = if (info.textColor != Color.Unspecified) info.textColor else titleColor,
                            modifier = info.modifier,
                        )
                    },
                    durationBlock = { info ->
                        if (state.duration > 0.seconds) {
                            ReasoningDuration(
                                duration = state.duration,
                                loading = loading,
                                color = if (info.textColor != Color.Unspecified) info.textColor else titleColor,
                                modifier = info.modifier,
                            )
                        }
                    },
                    original = {
                        ReasoningLabel(
                            showThinkingTitle = showThinkingTitle,
                            thinkingTitle = thinkingTitle,
                            duration = state.duration,
                            loading = loading,
                            color = titleColor,
                        )
                    },
                )
            }
        } else {
            {
                ReasoningLabel(
                    showThinkingTitle = showThinkingTitle,
                    thinkingTitle = thinkingTitle,
                    duration = state.duration,
                    loading = loading,
                    color = titleColor,
                )
            }
        },
        extra = if (headerSlotActive) null else {
            {
                if (showThinkingTitle && state.duration > 0.seconds) {
                    ReasoningDuration(duration = state.duration, loading = loading, color = titleColor)
                }
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                regexDepth = regexDepth,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
                loading = loading,
            )
        },
    )
}


@Composable
private fun ReasoningLabel(
    showThinkingTitle: Boolean,
    thinkingTitle: String?,
    duration: kotlin.time.Duration,
    loading: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val chatFontFamily = LocalTextStyle.current.fontFamily
    if (showThinkingTitle && thinkingTitle != null) {
        // 原版路径不多包一层
        if (modifier == Modifier) ReasoningTitle(title = thinkingTitle, color = color)
        else androidx.compose.foundation.layout.Box(modifier) { ReasoningTitle(title = thinkingTitle, color = color) }
    } else {
        Text(
            text = stringResource(
                R.string.deep_thinking_seconds,
                duration.toDouble(DurationUnit.SECONDS).toFloat()
            ),
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
            color = color,
            modifier = modifier.shimmer(isLoading = loading),
        )
    }
}

@Composable
private fun ReasoningDuration(
    duration: kotlin.time.Duration,
    loading: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val chatFontFamily = LocalTextStyle.current.fontFamily
    Text(
        text = duration.toString(DurationUnit.SECONDS, 1),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily),
        color = color,
        modifier = modifier.shimmer(isLoading = loading),
    )
}

/**
 * 思考步骤标题行接入点 `chat.reasoning.header`。展开 / 收起、左侧图标与时间线、思考正文仍由宿主负责。
 * 数据：loading / title（思考中提取的小标题）/ seconds；原版部件：title / duration / original。
 */
@Composable
private fun ReasoningHeaderSlot(
    loading: Boolean,
    thinkingTitle: String?,
    duration: kotlin.time.Duration,
    title: @Composable (me.rerere.rikkahub.ui.custom.UINodeRenderInfo) -> Unit,
    durationBlock: @Composable (me.rerere.rikkahub.ui.custom.UINodeRenderInfo) -> Unit,
    original: @Composable () -> Unit,
) {
    me.rerere.rikkahub.ui.custom.UISlot(
        name = "chat.reasoning.header",
        data = mapOf(
            "loading" to loading,
            "title" to thinkingTitle.orEmpty(),
            "seconds" to "%.1f".format(duration.toDouble(DurationUnit.SECONDS)),
        ),
        parts = mapOf("title" to title, "duration" to durationBlock),
        modifier = Modifier.fillMaxWidth(),
        original = original,
    )
}

@Composable
private fun ReasoningTitle(title: String, color: Color = MaterialTheme.colorScheme.secondary) {
    val chatFontFamily = LocalTextStyle.current.fontFamily
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                slideOutVertically { height -> -height } + fadeOut()
            )
        }
    ) {
        Text(
            text = it,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
            color = color,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
