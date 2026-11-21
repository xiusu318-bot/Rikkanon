package me.rerere.rikkahub.ui.custom

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIMessageNodeType
import me.rerere.rikkahub.ui.components.message.RenderUICanvasPrimitive
import me.rerere.rikkahub.ui.pages.extensions.plugin.resolvePluginIcon

private const val TAG = "UINodeRenderer"

/** 不同位置（消息、侧边栏……）在通用节点上的默认样式差异。 */
data class UINodeDefaults(
    /** 文字节点的基础样式；null = 沿用外层 LocalTextStyle。 */
    val textBaseStyle: TextStyle? = null,
    val textColor: Color = Color.Unspecified,
    val boldWeight: FontWeight = FontWeight.Bold,
    /** 文字节点默认最多几行；0 = 不限。 */
    val textMaxLines: Int = 0,
    val buttonContainer: Color = Color.Unspecified,
    val buttonContent: Color = Color.Unspecified,
    val iconTint: Color = Color.Unspecified,
    val iconButtonDefaultIcon: String = "sparkles",
    /** Column 里带 weight 的子节点是否占满分到的空间。 */
    val columnWeightFill: Boolean = false,
    /** Surface 背景额外乘的透明度（消息气泡跟随「气泡不透明度」设置）。 */
    val surfaceAlpha: Float = 1f,
)

/**
 * 宿主给节点的已计算好的样式；业务节点（头像、正文、会话列表……）照此绘制。
 * data class：内容相同时 Compose 可跳过宿主原样块的重绘。
 */
@Immutable
data class UINodeRenderInfo(
    /** 节点本身（宿主原样块可读取它的属性，如 Host("text_field", { text }) 的占位文字）。 */
    val node: UIMessageNode,
    val modifier: Modifier,
    val textStyle: TextStyle,
    /** 节点指定的文字颜色；未指定为 [Color.Unspecified]。 */
    val textColor: Color,
    /** 不含节点自身内边距的外层修饰符（供列表类宿主部件把 padding 转作滚动区 contentPadding，使内容滚过半透明悬浮栏底层）。 */
    val modifierWithoutPadding: Modifier = modifier,
)

/**
 * 节点渲染所需的宿主上下文。消息、侧边栏、顶栏、输入栏、页面背景各一份，用 [rememberUINodeHost] 取得。
 */
@Stable
class UINodeHost internal constructor() {
    internal var displaySettingState by mutableStateOf(DisplaySetting())
    internal var defaultsState by mutableStateOf(UINodeDefaults())
    internal var fontSizeRatioState by mutableFloatStateOf(1f)
    internal var isVisibleState by mutableStateOf<(String) -> Boolean>({ true })
    internal var expandTextState by mutableStateOf<(String) -> String>({ it })
    internal var onActionState by mutableStateOf<(UIMessageNode, String) -> Unit>({ _, _ -> })
    internal var blocksState by mutableStateOf<Map<String, @Composable (UINodeRenderInfo) -> Unit>>(emptyMap())
    internal var onCallbackState by mutableStateOf<(String, JsonElement?) -> Unit>({ _, _ -> })
    internal var onErrorState by mutableStateOf<(UIMessageNode, Throwable) -> Unit>(DEFAULT_ON_ERROR)
    internal var onCallbackArgsState by mutableStateOf<(String, List<Any?>) -> Unit>({ _, _ -> })
    internal var onRunActionState by mutableStateOf<(String, List<JsonElement>) -> Unit>({ _, _ -> })
    internal var runtimeState by mutableStateOf<UIThemeRuntime?>(null)

    val displaySetting: DisplaySetting get() = displaySettingState
    val defaults: UINodeDefaults get() = defaultsState
    /** 字号额外倍率（消息跟随聊天字号设置）。 */
    val fontSizeRatio: Float get() = fontSizeRatioState

    /** 显示条件：空 / always = 显示，never = 隐藏，其余交给接入点按数据字段判断。 */
    fun isVisible(node: UIMessageNode): Boolean {
        if (!node.enabled) return false
        return when (val c = node.visibleWhen.trim()) {
            "", "always" -> true
            "never" -> false
            else -> isVisibleState(c)
        }
    }

    fun expandText(raw: String): String = if ("{{" !in raw) raw else expandTextState(raw)

    /** 动作名（侧边栏：new_chat、open_search……；顶栏：open_drawer……）。 */
    fun onAction(node: UIMessageNode, name: String) = onActionState(node, name)

    fun onError(node: UIMessageNode, e: Throwable) = onErrorState(node, e)

    /** 主题 JS 回调（`onClick: () => {...}` 或 `onChange: (v, ctx) => {...}`）：回到主题实例执行。 */
    fun callback(id: String, arg: JsonElement? = null) = onCallbackState(id, arg)

    /** 带多个参数的主题回调（参数可以是宿主对象，交给主题时变成 Java 句柄）。 */
    fun callbackArgs(id: String, args: List<Any?>) = onCallbackArgsState(id, args)

    /** 执行回调里请求的宿主动作。 */
    fun runAction(name: String, args: List<JsonElement>) = onRunActionState(name, args)

    /** 主题的运行时实例（按数据渲染的位置才有）：懒加载列表按行渲染、`Compose` / `NativeView` 取 Java 对象都靠它。 */
    val runtime: UIThemeRuntime? get() = runtimeState

    /** 宿主原样块（`Host("名")`）：名字 → 按原版绘制的宿主部件；没有时为 null。 */
    fun block(name: String): (@Composable (UINodeRenderInfo) -> Unit)? = blocksState[name]

    val blockNames: Set<String> get() = blocksState.keys
}

private val DEFAULT_ON_ERROR: (UIMessageNode, Throwable) -> Unit =
    { n, e -> Log.w(TAG, "node ${n.id} (${n.type}) failed: ${e.message}") }

@Composable
fun rememberUINodeHost(
    displaySetting: DisplaySetting,
    defaults: UINodeDefaults = UINodeDefaults(),
    fontSizeRatio: Float = 1f,
    isVisible: (String) -> Boolean = { true },
    expandText: (String) -> String = { it },
    onAction: (UIMessageNode, String) -> Unit = { _, _ -> },
    blocks: Map<String, @Composable (UINodeRenderInfo) -> Unit> = emptyMap(),
    onError: (UIMessageNode, Throwable) -> Unit = DEFAULT_ON_ERROR,
    onCallback: ((String) -> Unit)? = null,
    onCallbackWithArg: ((String, JsonElement?) -> Unit)? = null,
    onCallbackArgs: ((String, List<Any?>) -> Unit)? = null,
    onRunAction: (String, List<JsonElement>) -> Unit = { _, _ -> },
    runtime: UIThemeRuntime? = null,
): UINodeHost {
    val host = remember { UINodeHost() }
    host.displaySettingState = displaySetting
    host.defaultsState = defaults
    host.fontSizeRatioState = fontSizeRatio
    host.isVisibleState = isVisible
    host.expandTextState = expandText
    host.onActionState = onAction
    host.blocksState = blocks
    host.onErrorState = onError
    val unsupportedCb: (String) -> Unit = { id ->
        onError(UIMessageNode(id = id), IllegalStateException("当前静态位置不支持运行时回调（请写在 ui.slot / ui.page 的内容里）"))
    }
    host.onCallbackState = onCallbackWithArg ?: if (onCallback != null) ({ id, _ -> onCallback(id) }) else ({ id, _ -> unsupportedCb(id) })
    host.onCallbackArgsState = onCallbackArgs ?: { id, _ -> unsupportedCb(id) }
    host.onRunActionState = onRunAction
    host.runtimeState = runtime
    return host
}

private inline fun <T> UINodeHost.safe(node: UIMessageNode, fallback: T, block: () -> T): T = try {
    block()
} catch (e: Exception) {
    onError(node, e)
    fallback
}

/** 通用节点渲染器：布局、表单、文字、图标、按钮、徽标、画布、分割线、空白在此绘制，其余交给 [UINodeHost.block]。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenderUINode(
    node: UIMessageNode,
    host: UINodeHost,
    modifier: Modifier = Modifier,
    rootContentInsets: Modifier = Modifier,
) {
    if (!host.safe(node, false) { host.isVisible(node) }) return
    val colorScheme = MaterialTheme.colorScheme
    val hazeState = LocalChatHazeState.current
    val defaults = host.defaults
    val passthrough = LocalUIPointerPassthrough.current || node.pointerEvents == "none"
    val view = LocalView.current

    fun fireHaptic() {
        if (node.haptic.isNotEmpty()) performUIHaptic(view, node.haptic)
    }

    // 若根节点自身固定了 height（如顶栏写 `Row({ height: 52 })`），把 contentInsets 加在固定高度之外，避免状态栏高度吃掉 52dp 内容区；
    // 若根节点未固定 height（如 `Column([Row({ height: 52 }), Divider()])`），把 contentInsets 放在根节点背景之内、padding 之前，让背景/毛玻璃自动延伸覆盖状态栏！
    val insetsOutside = rootContentInsets !== Modifier && node.heightDp > 0f

    var baseModifier = modifier
    if (node.zIndex != 0f) {
        baseModifier = baseModifier.zIndex(node.zIndex)
    }
    if (node.marginStartDp > 0f || node.marginTopDp > 0f || node.marginEndDp > 0f || node.marginBottomDp > 0f) {
        baseModifier = baseModifier.padding(
            start = node.marginStartDp.coerceAtLeast(0f).dp,
            top = node.marginTopDp.coerceAtLeast(0f).dp,
            end = node.marginEndDp.coerceAtLeast(0f).dp,
            bottom = node.marginBottomDp.coerceAtLeast(0f).dp,
        )
    }
    if (node.fillMaxWidth) {
        baseModifier = baseModifier.fillMaxWidth()
    } else if (node.maxWidthFraction in 0.01f..0.999f) {
        baseModifier = baseModifier.fillMaxWidth(node.maxWidthFraction)
    }
    if (node.fillMaxHeight) baseModifier = baseModifier.fillMaxHeight()
    if (insetsOutside) baseModifier = baseModifier.then(rootContentInsets)
    if (node.widthDp > 0f) baseModifier = baseModifier.width(node.widthDp.dp)
    if (node.heightDp > 0f) baseModifier = baseModifier.height(node.heightDp.dp)
    node.zoom?.let { baseModifier = baseModifier.uiZoom(it, enabled = !passthrough) }
    node.swipe?.let { spec ->
        baseModifier = baseModifier.uiSwipe(node, spec, enabled = !passthrough) { direction ->
            fireHaptic()
            host.callbackArgs(node.onSwipeFn, listOf(direction))
        }
    }
    val cutCorners = isUICutCornerShape()

    val animator = rememberUINodeAnimator(node, colorScheme)
    val bubbleAlpha = if (node.type == UIMessageNodeType.SURFACE) defaults.surfaceAlpha else 1f
    val shaderMods = if (node.type == UIMessageNodeType.SHADER) {
        rememberUIShaderModifiers(node, passthrough) { e -> host.onError(node, e) }
    } else null
    val shaderBackground = shaderMods?.background
    val shaderContentEffect = shaderMods?.contentEffect ?: Modifier
    val animatedBackground = if (animator != null && animator.hasBackground) {
        val alphaMul = (node.opacity.coerceIn(0f, 1f) * bubbleAlpha.coerceIn(0.1f, 1f)).coerceIn(0f, 1f)
        fun withAlpha(c: Color) = if (c == Color.Unspecified) c else c.copy(alpha = c.alpha * alphaMul)
        uiAnimatedBackground(
            animator = animator,
            shape = node.toCornerShape(cut = cutCorners),
            baseColor = withAlpha(resolveUIColor(node.backgroundColorHex, colorScheme)),
            gradientEnd = withAlpha(resolveUIColor(node.gradientEndColorHex, colorScheme)),
            baseAngle = node.gradientAngleDeg,
            alphaMultiplier = alphaMul,
        )
    } else null
    val hasNodePadding = node.paddingStartDp > 0f || node.paddingTopDp > 0f || node.paddingEndDp > 0f || node.paddingBottomDp > 0f
    val visualWithoutPadding = baseModifier.applyUINodeVisuals(
        node = node,
        colorScheme = colorScheme,
        hazeState = hazeState,
        displaySetting = host.displaySetting,
        defaultBubbleAlpha = bubbleAlpha,
        layer = Modifier.uiAnimatedLayer(animator, passthrough = passthrough),
        background = shaderBackground ?: animatedBackground,
        contentEffect = shaderContentEffect,
        contentInsets = if (!insetsOutside) rootContentInsets else Modifier,
        applyPadding = false,
    )
    var outerWithoutPad = visualWithoutPadding
    val handlesOwnClick = node.type == UIMessageNodeType.BUTTON ||
        node.type == UIMessageNodeType.ICON_BUTTON ||
        node.type == UIMessageNodeType.TEXT_FIELD ||
        node.type == UIMessageNodeType.SWITCH ||
        node.type == UIMessageNodeType.SLIDER
    fun gesture(fn: String, action: String): (() -> Unit)? = when {
        fn.isNotEmpty() -> { { fireHaptic(); host.callback(fn) } }
        action.isNotBlank() -> { { fireHaptic(); host.onAction(node, action) } }
        else -> null
    }
    if (!passthrough) {
        val click = if (handlesOwnClick) null else gesture(node.onClickFn, node.actionName)
        val longClick = gesture(node.onLongClickFn, node.longClickAction)
        val doubleClick = gesture(node.onDoubleClickFn, node.doubleClickAction)
        if (click != null || longClick != null || doubleClick != null) {
            outerWithoutPad = outerWithoutPad.combinedClickable(
                onClick = { click?.invoke() },
                onLongClick = longClick,
                onDoubleClick = doubleClick,
            )
        }
    }
    val visual = if (hasNodePadding) {
        outerWithoutPad.padding(
            start = node.paddingStartDp.coerceAtLeast(0f).dp,
            top = node.paddingTopDp.coerceAtLeast(0f).dp,
            end = node.paddingEndDp.coerceAtLeast(0f).dp,
            bottom = node.paddingBottomDp.coerceAtLeast(0f).dp,
        )
    } else {
        outerWithoutPad
    }

    val customTextColor = resolveUIColor(node.textColorHex, colorScheme)
    val context = LocalContext.current
    val nodeFontFamily = if (node.fontFamily.isBlank()) null else remember(node.fontFamily, host.displaySetting.chatFontFamily, host.displaySetting.chatCustomFontPath) {
        host.safe(node, null) { resolveUIFontFamily(context, node.fontFamily, host.displaySetting) }
    }
    val baseStyle = defaults.textBaseStyle?.let { LocalTextStyle.current.merge(it) } ?: LocalTextStyle.current
    val textStyle = baseStyle.let { cur ->
        var next = cur
        val color = if (customTextColor != Color.Unspecified) customTextColor else defaults.textColor
        if (color != Color.Unspecified) next = next.copy(color = color)
        if (node.fontSizeSp > 0f) {
            next = next.copy(fontSize = TextUnit(node.fontSizeSp * host.fontSizeRatio, TextUnitType.Sp))
        }
        if (node.fontWeightBold) next = next.copy(fontWeight = defaults.boldWeight)
        if (node.fontWeight > 0) next = next.copy(fontWeight = FontWeight(node.fontWeight))
        if (node.italic) next = next.copy(fontStyle = FontStyle.Italic)
        node.letterSpacingEm?.let { next = next.copy(letterSpacing = it.em) }
        if (node.lineHeightSp > 0f) next = next.copy(lineHeight = TextUnit(node.lineHeightSp * host.fontSizeRatio, TextUnitType.Sp))
        when (node.textAlign) {
            "start" -> next = next.copy(textAlign = TextAlign.Start)
            "center" -> next = next.copy(textAlign = TextAlign.Center)
            "end" -> next = next.copy(textAlign = TextAlign.End)
            "justify" -> next = next.copy(textAlign = TextAlign.Justify)
        }
        nodeFontFamily?.let { next = next.copy(fontFamily = it) }
        next
    }

    fun onClick() {
        fireHaptic()
        if (node.onClickFn.isNotEmpty()) host.callback(node.onClickFn)
        else if (node.actionName.isNotBlank()) host.onAction(node, node.actionName)
    }

    val maxLines = when {
        node.maxLines > 0 -> node.maxLines
        defaults.textMaxLines > 0 -> defaults.textMaxLines
        else -> Int.MAX_VALUE
    }

    val contentColor = if (customTextColor != Color.Unspecified) customTextColor else LocalContentColor.current
    val locals = buildList {
        add(LocalContentColor provides contentColor)
        if (node.compact) add(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)
        if (passthrough && !LocalUIPointerPassthrough.current) add(LocalUIPointerPassthrough provides true)
    }.toTypedArray()

    CompositionLocalProvider(*locals) {
    ProvideTextStyle(textStyle) {
        when (node.type) {
            UIMessageNodeType.COLUMN, UIMessageNodeType.SURFACE -> {
                Column(
                    modifier = visual,
                    horizontalAlignment = node.horizontalAlignment.toComposeHorizontalAlignment(),
                    verticalArrangement = Arrangement.spacedBy(node.spacingDp.coerceAtLeast(0f).dp),
                ) {
                    node.children.forEach { child ->
                        val childMod = if (child.weight > 0f) Modifier.weight(child.weight, fill = defaults.columnWeightFill) else Modifier
                        RenderUINode(child, host, childMod)
                    }
                }
            }

            UIMessageNodeType.ROW -> {
                Row(
                    modifier = visual,
                    horizontalArrangement = node.horizontalAlignment.toHorizontalArrangement(node.spacingDp.coerceAtLeast(0f).dp),
                    verticalAlignment = node.verticalAlignment.toComposeVerticalAlignment(),
                ) {
                    node.children.forEach { child ->
                        val childMod = if (child.weight > 0f) Modifier.weight(child.weight) else Modifier
                        RenderUINode(child, host, childMod)
                    }
                }
            }

            UIMessageNodeType.FLOW_ROW -> {
                val hSpacing = node.spacingDp.coerceAtLeast(0f).dp
                val vSpacing = (if (node.crossSpacingDp > 0f) node.crossSpacingDp else node.spacingDp).coerceAtLeast(0f).dp
                FlowRow(
                    modifier = visual,
                    horizontalArrangement = node.horizontalAlignment.toHorizontalArrangement(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing),
                ) {
                    node.children.forEach { child -> RenderUINode(child, host) }
                }
            }

            UIMessageNodeType.BOX -> {
                Box(modifier = visual, contentAlignment = node.toBoxAlignment()) {
                    node.children.forEach { child -> RenderUINode(child, host) }
                }
            }

            UIMessageNodeType.POPUP -> {
                val onDismiss = {
                    fireHaptic()
                    if (node.onDismissFn.isNotEmpty()) host.callback(node.onDismissFn)
                    else if (node.actionName.isNotBlank()) host.onAction(node, node.actionName)
                }
                if (node.modal) {
                    Dialog(
                        onDismissRequest = onDismiss,
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        Box(modifier = visual, contentAlignment = node.toBoxAlignment()) {
                            node.children.forEach { child -> RenderUINode(child, host) }
                        }
                    }
                } else {
                    Popup(
                        alignment = node.toBoxAlignment(),
                        onDismissRequest = onDismiss,
                    ) {
                        Box(modifier = visual, contentAlignment = node.toBoxAlignment()) {
                            node.children.forEach { child -> RenderUINode(child, host) }
                        }
                    }
                }
            }

            UIMessageNodeType.COLLAPSIBLE -> {
                var expanded by remember(node.id) { mutableStateOf(node.collapsibleDefaultExpanded) }
                Column(modifier = visual.animateContentSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!passthrough) Modifier.clickable { fireHaptic(); expanded = !expanded } else Modifier)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = host.safe(node, "") { host.expandText(node.collapsibleTitle.ifBlank { node.displayTitle }) },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(node.spacingDp.coerceAtLeast(0f).dp)) {
                            node.children.forEach { child -> RenderUINode(child, host) }
                        }
                    }
                }
            }

            UIMessageNodeType.CUSTOM_TEXT -> {
                val text = host.safe(node, node.customText) { host.expandText(node.customText) }
                val overflow = if (maxLines != Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip
                if (animator != null && animator.hasColor) {
                    val baseColor = textStyle.color.takeOrElse { LocalContentColor.current }
                    BasicText(
                        text = text,
                        style = textStyle,
                        maxLines = maxLines,
                        overflow = overflow,
                        color = { animator.color("color", baseColor) },
                        modifier = visual,
                    )
                } else {
                    Text(
                        text = text,
                        style = textStyle,
                        maxLines = maxLines,
                        overflow = overflow,
                        modifier = visual,
                    )
                }
            }

            UIMessageNodeType.TEXT_FIELD -> {
                val extText = host.safe(node, node.customText) { host.expandText(node.customText) }
                var localText by remember(node.id) { mutableStateOf(extText) }
                var lastEmitted by remember(node.id) { mutableStateOf(extText) }
                LaunchedEffect(extText) {
                    if (extText != lastEmitted) {
                        localText = extText
                        lastEmitted = extText
                    }
                }
                val hasCustomBg = node.backgroundColorHex.isNotBlank() || node.borderWidthDp > 0f || node.enableHazeBlur
                val hasCustomPad = node.paddingStartDp > 0f || node.paddingTopDp > 0f || node.paddingEndDp > 0f || node.paddingBottomDp > 0f
                val defaultShape = uiCornerShape(8.dp, cut = cutCorners)
                val fieldMod = visual
                    .then(
                        if (!hasCustomBg) {
                            Modifier
                                .background(colorScheme.surfaceContainerLow, defaultShape)
                                .border(1.dp, colorScheme.outlineVariant, defaultShape)
                        } else Modifier
                    )
                    .then(if (!hasCustomPad) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier)
                val fg = if (customTextColor != Color.Unspecified) customTextColor else colorScheme.onSurface
                val mergedStyle = textStyle.copy(color = fg)
                val hint = host.safe(node, node.placeholder) { host.expandText(node.placeholder) }
                val hasSubmit = node.onSubmitFn.isNotEmpty() || node.actionName.isNotBlank()
                BasicTextField(
                    value = localText,
                    onValueChange = { next ->
                        if (!passthrough) {
                            localText = next
                            lastEmitted = next
                            if (node.onChangeFn.isNotEmpty()) host.callback(node.onChangeFn, JsonPrimitive(next))
                        }
                    },
                    readOnly = passthrough,
                    singleLine = node.singleLine,
                    maxLines = if (node.singleLine) 1 else maxLines,
                    textStyle = mergedStyle,
                    cursorBrush = SolidColor(colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        imeAction = when {
                            hasSubmit -> ImeAction.Send
                            node.singleLine -> ImeAction.Done
                            else -> ImeAction.Default
                        },
                    ),
                    keyboardActions = KeyboardActions(
                        onAny = {
                            fireHaptic()
                            if (node.onSubmitFn.isNotEmpty()) host.callback(node.onSubmitFn, JsonPrimitive(localText))
                            else if (node.actionName.isNotBlank()) host.onAction(node, node.actionName)
                        },
                    ),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (localText.isEmpty() && hint.isNotEmpty()) {
                                Text(
                                    text = hint,
                                    style = mergedStyle.copy(color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = fieldMod,
                )
            }

            UIMessageNodeType.SWITCH -> {
                val activeCol = if (customTextColor != Color.Unspecified) customTextColor else colorScheme.primary
                val uncheckedTrack = resolveUIColor(node.backgroundColorHex, colorScheme, colorScheme.surfaceContainerHighest)
                UISwitch(
                    checked = node.checked,
                    onCheckedChange = if (passthrough) null else ({ next ->
                        fireHaptic()
                        if (node.onChangeFn.isNotEmpty()) host.callback(node.onChangeFn, JsonPrimitive(next))
                        else if (node.onClickFn.isNotEmpty()) host.callback(node.onClickFn)
                        else if (node.actionName.isNotBlank()) host.onAction(node, node.actionName)
                    }),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = activeCol,
                        uncheckedTrackColor = uncheckedTrack,
                    ),
                    cornerOverrideDp = node.cornerTopStartDp.takeIf { it > 0f },
                    modifier = visual,
                )
            }

            UIMessageNodeType.SLIDER -> {
                val lo = node.minValue
                val hi = if (node.maxValue > lo) node.maxValue else lo + 1f
                val extVal = node.sliderValue.coerceIn(lo, hi)
                var localVal by remember(node.id, lo, hi) { mutableFloatStateOf(extVal) }
                var dragging by remember(node.id) { mutableStateOf(false) }
                LaunchedEffect(extVal) {
                    if (!dragging) localVal = extVal
                }
                val activeCol = if (customTextColor != Color.Unspecified) customTextColor else colorScheme.primary
                val inactiveCol = resolveUIColor(node.backgroundColorHex, colorScheme, colorScheme.surfaceContainerHighest)
                Slider(
                    value = localVal,
                    onValueChange = { v ->
                        if (!passthrough) {
                            dragging = true
                            localVal = v
                            if (node.onChangeFn.isNotEmpty()) host.callback(node.onChangeFn, JsonPrimitive(v))
                        }
                    },
                    onValueChangeFinished = {
                        dragging = false
                        fireHaptic()
                    },
                    enabled = !passthrough,
                    valueRange = lo..hi,
                    steps = node.steps.coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = activeCol,
                        activeTrackColor = activeCol,
                        inactiveTrackColor = inactiveCol,
                    ),
                    modifier = visual,
                )
            }

            UIMessageNodeType.ICON -> {
                val tint = when {
                    customTextColor != Color.Unspecified -> customTextColor
                    defaults.iconTint != Color.Unspecified -> defaults.iconTint
                    else -> colorScheme.primary
                }
                Icon(
                    imageVector = resolvePluginIcon(node.iconName.ifBlank { "sparkles" }),
                    contentDescription = node.name.ifBlank { null },
                    tint = if (animator != null && animator.hasColor) animator.color("color", tint) else tint,
                    modifier = visual.size(node.avatarSizeDp.coerceAtLeast(1f).dp),
                )
            }

            UIMessageNodeType.BUTTON -> {
                val bg = resolveUIColor(
                    node.backgroundColorHex,
                    colorScheme,
                    defaults.buttonContainer.takeIf { it != Color.Unspecified } ?: colorScheme.secondaryContainer,
                )
                val fg = when {
                    customTextColor != Color.Unspecified -> customTextColor
                    defaults.buttonContent != Color.Unspecified -> defaults.buttonContent
                    else -> colorScheme.onSecondaryContainer
                }
                val content: @Composable () -> Unit = {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (node.iconName.isNotBlank()) {
                            Icon(
                                imageVector = resolvePluginIcon(node.iconName),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = host.safe(node, node.customText) {
                                host.expandText(node.customText.ifBlank { node.name.ifBlank { "Button" } })
                            },
                            style = textStyle.copy(color = fg),
                        )
                    }
                }
                if (passthrough) {
                    Surface(
                        shape = node.toCornerShape(cut = cutCorners),
                        color = bg,
                        contentColor = fg,
                        modifier = visual,
                        content = content,
                    )
                } else {
                    Surface(
                        onClick = { onClick() },
                        shape = node.toCornerShape(cut = cutCorners),
                        color = bg,
                        contentColor = fg,
                        modifier = visual,
                        content = content,
                    )
                }
            }

            UIMessageNodeType.ICON_BUTTON -> {
                val shape = node.toCornerShape(cut = cutCorners)
                val bg = resolveUIColor(node.backgroundColorHex, colorScheme, Color.Transparent)
                val tint = when {
                    customTextColor != Color.Unspecified -> customTextColor
                    defaults.iconTint != Color.Unspecified -> defaults.iconTint
                    else -> colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = visual
                        .size(node.avatarSizeDp.coerceAtLeast(1f).dp)
                        .clip(shape)
                        .background(bg, shape)
                        .then(if (!passthrough) Modifier.clickable { onClick() } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = resolvePluginIcon(node.iconName.ifBlank { defaults.iconButtonDefaultIcon }),
                        contentDescription = node.name.ifBlank { null },
                        tint = tint,
                        modifier = Modifier.size(node.iconSizeDp.coerceAtLeast(1f).dp),
                    )
                }
            }

            UIMessageNodeType.BADGE -> {
                val bg = resolveUIColor(node.backgroundColorHex, colorScheme, colorScheme.primaryContainer)
                val fg = if (customTextColor != Color.Unspecified) customTextColor else colorScheme.onPrimaryContainer
                Surface(
                    shape = RoundedCornerShape(50),
                    color = bg,
                    contentColor = fg,
                    modifier = visual,
                ) {
                    Text(
                        text = host.safe(node, node.customText) { host.expandText(node.customText) },
                        style = textStyle.copy(color = fg),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            UIMessageNodeType.CANVAS -> {
                val color = if (customTextColor != Color.Unspecified) customTextColor
                else resolveUIColor(node.backgroundColorHex, colorScheme, colorScheme.primary)
                RenderUICanvasPrimitive(
                    drawType = node.canvasDrawType,
                    color = color,
                    modifier = visual.size(node.avatarSizeDp.coerceAtLeast(1f).dp),
                )
            }

            UIMessageNodeType.DIVIDER -> {
                val color = if (customTextColor != Color.Unspecified) customTextColor
                else colorScheme.outlineVariant.copy(alpha = 0.5f)
                HorizontalDivider(modifier = visual, color = color)
            }

            UIMessageNodeType.SPACER -> Spacer(modifier = visual)

            UIMessageNodeType.IMAGE -> {
                val model = remember(node.imageSrc) { host.safe(node, null) { resolveUIImageModel(context, node.imageSrc) } }
                val noSize = node.widthDp <= 0f && node.heightDp <= 0f && !node.fillMaxWidth && !node.fillMaxHeight
                AsyncImage(
                    model = model,
                    contentDescription = node.name.ifBlank { null },
                    contentScale = if (noSize) ContentScale.FillWidth else node.contentScale.toContentScale(),
                    modifier = if (noSize) visual.fillMaxWidth() else visual,
                )
            }

            UIMessageNodeType.PATH -> {
                val path = remember(node.pathData) {
                    host.safe(node, null) { PathParser().parsePathString(node.pathData).toPath() }
                }
                val color = when {
                    customTextColor != Color.Unspecified -> customTextColor
                    defaults.iconTint != Color.Unspecified -> defaults.iconTint
                    else -> colorScheme.onSurface
                }
                val square = node.avatarSizeDp.coerceAtLeast(1f).dp
                val noSize = node.widthDp <= 0f && node.heightDp <= 0f && !node.fillMaxWidth && !node.fillMaxHeight
                val sized = if (noSize) visual.size(square) else visual
                val cap = when (node.strokeCap) {
                    "round" -> StrokeCap.Round
                    "square" -> StrokeCap.Square
                    else -> StrokeCap.Butt
                }
                Canvas(modifier = sized) {
                    if (path != null) {
                        val drawColor = if (animator != null && animator.hasColor) animator.color("color", color) else color
                        val sx = size.width / node.viewBoxWidth
                        val sy = size.height / node.viewBoxHeight
                        scale(sx, sy, pivot = Offset.Zero) {
                            if (node.strokeWidth > 0f) {
                                drawPath(path, drawColor, style = Stroke(width = node.strokeWidth, cap = cap))
                            } else {
                                drawPath(path, drawColor)
                            }
                        }
                    }
                }
            }

            UIMessageNodeType.SHADER -> {
                Box(modifier = visual, contentAlignment = node.toBoxAlignment()) {
                    // 着色器不可用（Android 13 以下或出错）时画主题指定的替代
                    if (shaderMods == null) node.fallback?.let { RenderUINode(it, host, Modifier.matchParentSize()) }
                    node.children.forEach { child -> RenderUINode(child, host) }
                }
            }

            UIMessageNodeType.VIDEO -> UIVideo(node, visual) { e -> host.onError(node, e) }

            // 懒加载列表：节点的 padding 作为滚动区的 contentPadding（内容能滚过悬浮栏底下）
            UIMessageNodeType.LAZY_COLUMN, UIMessageNodeType.LAZY_ROW -> UILazyList(node, host, outerWithoutPad)

            UIMessageNodeType.COMPOSE -> UIComposeNode(node, host, visual)

            UIMessageNodeType.NATIVE_VIEW -> UINativeView(node, host, visual)

            else -> {
                val name = if (node.type == UIMessageNodeType.HOST) node.hostBlock else node.type.serialName()
                val block = host.block(name)
                if (block == null) {
                    host.onError(node, IllegalArgumentException("这里没有宿主原样块「$name」，可用：${host.blockNames.joinToString()}"))
                } else {
                    block(UINodeRenderInfo(node, visual, textStyle, customTextColor, outerWithoutPad))
                }
            }
        }
    }
    }
}

/** 节点类型在 UI 代码里的名字（`avatar`、`markdown_body`……）。 */
fun UIMessageNodeType.serialName(): String = UIMessageNodeType.serializer().descriptor.getElementName(ordinal)
