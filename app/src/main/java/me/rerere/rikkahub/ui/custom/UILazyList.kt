package me.rerere.rikkahub.ui.custom

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIMessageNodeType
import me.rerere.rikkahub.data.model.UISwipeSpec
import me.rerere.rikkahub.data.model.UIZoomSpec
import me.rerere.rikkahub.ui.components.ui.draggingScale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs
import kotlin.math.min

/** 滚到离末尾还剩几行时触发 `onEndReached`。 */
private const val END_REACHED_THRESHOLD = 2

/**
 * 懒加载列表（`LazyColumn` / `LazyRow`）：宿主的懒加载列表 + 主题画每一行。
 *
 * - 只为屏幕上看得见的行调用主题的 `item(index, ctx)`；结果按行缓存，同一行滚出去再滚回来不重算。
 * - 行的渲染与各位置共用等待预算（单次最多 48ms、任意 250ms 内合计 48ms），超出的行先占位 [UIMessageNode.itemSize]，后台算完再显示。
 * - `onReorder`：长按拖动排序，拖动过程由宿主完成，松手后才回调 `(from, to, ctx)`。
 * - `onEndReached`：滚到末尾附近时回调一次（行数变化且列表测量更新后可再次触发），用来分页加载。
 * - `onRefresh` + `refreshing`：下拉刷新（仅竖向列表）。
 *
 * [modifier] 不含节点自身的内边距：内边距作为滚动区的 contentPadding。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UILazyList(node: UIMessageNode, host: UINodeHost, modifier: Modifier) {
    val runtime = requireNotNull(host.runtime) { "懒加载列表只能用在按数据渲染的位置（ui.slot / ui.page 的函数里）" }
    val vertical = node.type == UIMessageNodeType.LAZY_COLUMN
    val count = node.count
    val keys = node.keys
    val current by rememberUpdatedState(node)
    val listState = rememberLazyListState()

    // 拖拽排序过程中的本地顺序（位置 → 主题的行号）；主题重新渲染后（itemFn 变了）回到主题给出的顺序
    var order by remember(node.itemFn, count) { mutableStateOf<List<Int>?>(null) }
    var dragFrom by remember(node.itemFn, count) { mutableIntStateOf(-1) }
    val reorderEnabled = node.onReorderFn.isNotEmpty()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val cur = (order ?: List(current.count) { it }).toMutableList()
        if (from.index in cur.indices && to.index in cur.indices) {
            cur.add(to.index, cur.removeAt(from.index))
            order = cur
        }
    }

    fun rowOf(position: Int): Int = order?.get(position) ?: position
    fun keyOf(row: Int): Any = if (keys.isNotEmpty()) keys[row] else row

    if (node.onEndReachedFn.isNotEmpty()) {
        val reached by remember(listState, count) {
            derivedStateOf {
                if (count == 0) {
                    true
                } else {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    info.totalItemsCount == count && lastVisible >= count - 1 - END_REACHED_THRESHOLD
                }
            }
        }
        LaunchedEffect(reached, count) {
            if (reached) host.callbackArgs(current.onEndReachedFn, emptyList())
        }
    }

    val view = LocalView.current
    val content: LazyListScope.() -> Unit = {
        items(count = count, key = { position -> keyOf(rowOf(position)) }) { position ->
            val row = rowOf(position)
            if (reorderEnabled) {
                ReorderableItem(reorderState, key = keyOf(row)) { isDragging ->
                    Box(
                        modifier = Modifier
                            .draggingScale(isDragging)
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    dragFrom = order?.indexOf(row) ?: row
                                    performUIHaptic(view, "long_press")
                                },
                                onDragStopped = {
                                    val from = if (dragFrom >= 0) dragFrom else row
                                    dragFrom = -1
                                    val to = order?.indexOf(row) ?: row
                                    if (to != from) {
                                        performUIHaptic(view, "confirm")
                                        host.callbackArgs(current.onReorderFn, listOf(from, to))
                                    }
                                },
                            ),
                    ) {
                        UILazyItem(runtime, host, current.itemFn, row, node.itemSize, vertical)
                    }
                }
            } else {
                UILazyItem(runtime, host, current.itemFn, row, node.itemSize, vertical)
            }
        }
    }

    val padding = PaddingValues(
        start = node.paddingStartDp.dp,
        top = node.paddingTopDp.dp,
        end = node.paddingEndDp.dp,
        bottom = node.paddingBottomDp.dp,
    )
    val spacing = node.spacingDp.dp
    // 懒加载列表放进主轴不受限的容器（如 scroll: "vertical" 的 Column）会直接崩溃：这里改为按屏幕尺寸显示并报错
    val bounded = Modifier.uiBoundedMainAxis(vertical) {
        host.onError(
            node,
            IllegalStateException(
                (if (vertical) "LazyColumn 放在了高度不受限的容器里（如 scroll: \"vertical\" 的 Column），已按屏幕高度显示；请给它 height / weight / fillMaxHeight"
                else "LazyRow 放在了宽度不受限的容器里（如 scroll: \"horizontal\" 的 Row），已按屏幕宽度显示；请给它 width / weight / fillMaxWidth"),
            ),
        )
    }
    if (vertical) {
        val refreshable = node.onRefreshFn.isNotEmpty()
        val list: @Composable (Modifier) -> Unit = { m ->
            LazyColumn(
                modifier = m,
                state = listState,
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(spacing),
                horizontalAlignment = node.horizontalAlignment.toComposeHorizontalAlignment(),
                content = content,
            )
        }
        if (refreshable) {
            PullToRefreshBox(
                isRefreshing = node.refreshing,
                onRefresh = { host.callbackArgs(current.onRefreshFn, emptyList()) },
                modifier = modifier.then(bounded),
            ) {
                list(Modifier.fillMaxSize())
            }
        } else {
            list(modifier.then(bounded))
        }
    } else {
        LazyRow(
            modifier = modifier.then(bounded),
            state = listState,
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = node.verticalAlignment.toComposeVerticalAlignment(),
            content = content,
        )
    }
}

/** 列表的一行：没算好时先占位（或继续显示这一行上一次的样子），算好后绘制主题返回的节点树。 */
@Composable
private fun UILazyItem(
    runtime: UIThemeRuntime,
    host: UINodeHost,
    itemFn: String,
    index: Int,
    placeholderDp: Float,
    vertical: Boolean,
) {
    val ready by runtime.ready.collectAsState()
    val tick by runtime.renderTick.collectAsState()
    var last by remember(runtime) { mutableStateOf<UISlotResult?>(null) }
    val watchKey = watchKeyOf(last?.watched.orEmpty())
    // 这一行已经显示过内容时不在界面线程等待（先显示上一次的，后台算完再换）
    val now = if (ready && tick >= 0) runtime.renderItemNow(itemFn, index, wait = last == null, watchKey = watchKey) else null
    val shown = if (now != null) {
        SideEffect { last = now }
        now
    } else {
        last
    }
    UIPinResult(runtime, shown)
    if (shown == null) {
        Spacer(
            if (vertical) Modifier.fillMaxWidth().height(placeholderDp.dp)
            else Modifier.fillMaxHeight().width(placeholderDp.dp),
        )
    } else {
        shown.tree?.let { RenderUINode(it, host) }
    }
}

/** 主轴约束无上限时改用屏幕尺寸（否则懒加载列表在测量阶段抛异常，整个 App 崩溃），并回调一次 [onUnbounded]。 */
@Composable
private fun Modifier.uiBoundedMainAxis(vertical: Boolean, onUnbounded: () -> Unit): Modifier {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val fallbackPx = with(density) { (if (vertical) config.screenHeightDp else config.screenWidthDp).dp.roundToPx() }
    val report by rememberUpdatedState(onUnbounded)
    val reported = remember { booleanArrayOf(false) }
    return this.layout { measurable, constraints ->
        val unbounded = if (vertical) !constraints.hasBoundedHeight else !constraints.hasBoundedWidth
        val c = when {
            !unbounded -> constraints
            vertical -> constraints.copy(maxHeight = fallbackPx.coerceAtLeast(constraints.minHeight))
            else -> constraints.copy(maxWidth = fallbackPx.coerceAtLeast(constraints.minWidth))
        }
        if (unbounded && !reported[0]) {
            reported[0] = true
            report()
        }
        val placeable = measurable.measure(c)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * 滑动手势（`swipe` + `onSwipe`）：拖动过程由宿主完成（[UISwipeSpec.follow] 时内容跟手），
 * 松手时位移超过阈值或甩动够快才回调一次 [onSwipe]（left / right / up / down）。
 */
@Composable
internal fun Modifier.uiSwipe(
    node: UIMessageNode,
    spec: UISwipeSpec,
    enabled: Boolean,
    onSwipe: (String) -> Unit,
): Modifier {
    val horizontal = spec.direction == "left" || spec.direction == "right" || spec.direction == "horizontal"
    val allowNegative = spec.direction == "left" || spec.direction == "up" || spec.direction == "horizontal" || spec.direction == "vertical"
    val allowPositive = spec.direction == "right" || spec.direction == "down" || spec.direction == "horizontal" || spec.direction == "vertical"
    var drag by remember { mutableFloatStateOf(0f) }
    var dismissedNode by remember { mutableStateOf<UIMessageNode?>(null) }
    if (dismissedNode != null && dismissedNode != node) {
        // 主题已在 onSwipe 后移除了原节点（如无 keys 列表下一行顶上来复用此槽位），立即归零避免新内容隐身在屏外
        drag = 0f
        dismissedNode = null
    }
    var sizePx by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(onSwipe)
    val latestNode by rememberUpdatedState(node)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val flingPx = with(density) { 800.dp.toPx() }
    val minFlingDistancePx = with(density) { 24.dp.toPx() }
    val maxBlindDistancePx = with(density) { 72.dp.toPx() }
    val state = rememberDraggableState { delta ->
        dismissedNode = null
        drag = (drag + delta).coerceIn(if (allowNegative) -Float.MAX_VALUE else 0f, if (allowPositive) Float.MAX_VALUE else 0f)
    }
    return this
        .onSizeChanged { sizePx = if (horizontal) it.width else it.height }
        .draggable(
            state = state,
            orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical,
            enabled = enabled,
            onDragStopped = { velocity ->
                val size = sizePx.coerceAtLeast(1).toFloat()
                // 不跟手时没有位移反馈，阈值不超过 72dp
                val distance = if (spec.follow) size * spec.threshold else min(size * spec.threshold, maxBlindDistancePx)
                val current = drag
                val sameWay = (velocity < 0f) == (current < 0f)
                val triggered = abs(current) >= distance || (sameWay && abs(velocity) >= flingPx && abs(current) >= minFlingDistancePx)
                if (triggered && current != 0f) {
                    val direction = when {
                        horizontal && current < 0f -> "left"
                        horizontal -> "right"
                        current < 0f -> "up"
                        else -> "down"
                    }
                    if (spec.follow && spec.dismiss) {
                        val snap = latestNode
                        dismissedNode = snap
                        latest(direction)
                        scope.launch {
                            animate(current, if (current < 0f) -size else size, velocity) { v, _ ->
                                if (dismissedNode == snap) drag = v
                            }
                            delay(600)
                            if (dismissedNode == snap) {
                                dismissedNode = null
                                animate(drag, 0f, 0f, spring()) { v, _ -> drag = v }
                            }
                        }
                    } else {
                        latest(direction)
                        animate(current, 0f, velocity, spring()) { v, _ -> drag = v }
                    }
                } else {
                    animate(current, 0f, velocity, spring()) { v, _ -> drag = v }
                }
            },
        )
        .then(
            if (spec.follow) {
                Modifier.graphicsLayer { if (horizontal) translationX = drag else translationY = drag }
            } else Modifier,
        )
}

/** 双指缩放与平移（`zoom`）：全部由宿主完成，不回调主题；没放大时单指滑动照常交给外层滚动。 */
@Composable
internal fun Modifier.uiZoom(spec: UIZoomSpec, enabled: Boolean): Modifier {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(spec.min, spec.max)
        scale = next
        val maxX = (size.width * (next - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (size.height * (next - 1f) / 2f).coerceAtLeast(0f)
        offset = Offset(
            (offset.x + panChange.x).coerceIn(-maxX, maxX),
            (offset.y + panChange.y).coerceIn(-maxY, maxY),
        )
    }
    return this
        .onSizeChanged { size = it }
        .transformable(state = state, canPan = { scale > 1f }, enabled = enabled)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
}
