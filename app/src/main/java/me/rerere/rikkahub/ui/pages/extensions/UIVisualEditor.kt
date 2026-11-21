package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.custom.UICodeAnalysis
import me.rerere.rikkahub.ui.custom.UICodeEditor
import me.rerere.rikkahub.ui.custom.UIEditCheck
import me.rerere.rikkahub.ui.custom.UIEditNode
import me.rerere.rikkahub.ui.custom.UIEditResult
import me.rerere.rikkahub.ui.custom.UIEditTarget
import me.rerere.rikkahub.ui.custom.UI_LEAD_KEYS

/** 一次可视化修改：拿到当前代码，返回改写后的代码（或不能改的原因）。 */
typealias UICodeEdit = suspend (code: String) -> UIEditResult

/** 节点在界面树里的位置（位置名 + 各层子节点下标），代码改写后据此在新的分析结果里找回同一个节点。 */
private data class NodePath(val slot: String, val indexes: List<Int>)

private fun UICodeAnalysis.find(path: NodePath): Pair<UIEditNode, UIEditNode?>? {
    var node = slots.firstOrNull { it.name == path.slot }?.tree ?: return null
    var parent: UIEditNode? = null
    for (i in path.indexes) {
        parent = node
        node = node.children.getOrNull(i) ?: return null
    }
    return node to parent
}

private val CONTAINER_TYPES = setOf("column", "row", "flow_row", "box", "popup", "surface", "collapsible", "shader")

private val TYPE_LABELS = mapOf(
    "column" to "竖排", "row" to "横排", "flow_row" to "折行横排", "box" to "层叠", "popup" to "浮层弹窗",
    "surface" to "卡片", "collapsible" to "折叠",
    "custom_text" to "文字", "text_field" to "输入框", "switch" to "开关", "slider" to "滑杆",
    "icon" to "图标", "button" to "按钮", "icon_button" to "图标按钮", "badge" to "徽标",
    "image" to "图片", "path" to "路径", "host" to "原版部件", "divider" to "分割线", "spacer" to "空白",
    "canvas" to "画布", "shader" to "着色器", "video" to "视频", "action_bar" to "操作栏",
)

/** 可加的属性：名字 → 说明。值的类型按写入的内容判断（字符串 / 数字 / true·false / JSON）。 */
private val COMMON_PROPS = listOf(
    "color" to "文字 / 图标色", "bg" to "底色", "fontSize" to "字号", "bold" to "加粗", "fontWeight" to "字重",
    "padding" to "内边距", "margin" to "外边距", "corners" to "圆角", "border" to "边框 [宽, 色]",
    "spacing" to "子项间距", "align" to "水平对齐", "vAlign" to "垂直对齐", "width" to "宽", "height" to "高",
    "weight" to "占剩余空间", "fillMaxWidth" to "占满宽度", "alpha" to "透明度", "maxLines" to "最多行数",
    "size" to "尺寸", "visibleWhen" to "显示条件", "onClick" to "点击动作", "onLongClick" to "长按动作",
    "gradient" to "渐变 [起, 止, 角度]", "blur" to "毛玻璃", "compact" to "紧凑（解除 48dp）",
    "scroll" to "滚动 (vertical/horizontal)", "insets" to "安全区 (status/nav/ime/safe)",
    "blendMode" to "混合模式 (plus/screen…)", "pointerEvents" to "手势穿透 (auto/none)",
    "clip" to "边界裁剪 (true/false)", "zIndex" to "层级顺序", "haptic" to "触感震动 (tick/click…)",
    "wakeDuration" to "着色器触控唤醒秒数", "animate" to "动画", "press" to "按压反馈",
)

private val CHILD_TEMPLATES = listOf(
    "文字" to "Text(\"新文字\")", "横排" to "Row([])", "竖排" to "Column([])", "层叠" to "Box([])",
    "卡片" to "Surface({ padding: 12, corners: 12, bg: \"surfaceContainer\" }, [])", "图标" to "Icon(\"sparkles\")",
    "按钮" to "Button(\"按钮\")", "空白" to "Spacer(1)", "分割线" to "Divider()",
    "图片" to "Image(\"https://\", { height: 80 })", "原版部件" to "Host(\"original\")",
)

private fun JsonElement.display(): String = when {
    this is JsonPrimitive && isString -> content
    else -> toString()
}

/**
 * 「界面结构」页：显示各位置固定写法的界面树，点节点改属性 / 增删移动子节点；所有修改都改写成代码。
 * 按数据渲染（函数）的位置只显示所在行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UIStructureEditor(
    analysis: UICodeAnalysis?,
    code: String,
    editor: UICodeEditor,
    onEdit: (UICodeEdit, onSuccess: (() -> Unit)?) -> Unit,
) {
    var selected by remember { mutableStateOf<NodePath?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "代码是唯一来源：这里的修改直接改写 UI 代码。值来自变量 / 表达式、由循环或函数生成的节点不能在这里改，会提示到代码第几行修改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            analysis == null -> item { Text("正在解析代码…", style = MaterialTheme.typography.bodyMedium) }
            analysis.error != null -> item {
                Text(
                    text = "代码无法解析：${analysis.error}" + (analysis.errorLine?.let { "（第 $it 行）" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            analysis.slots.isEmpty() -> item {
                Text("代码里还没有 ui.slot / ui.page 的内容。", style = MaterialTheme.typography.bodyMedium)
            }
            else -> items(analysis.slots, key = { it.name }) { slot ->
                CardGroup(title = { Text(slot.name + (slot.line?.let { " · 第 $it 行" } ?: "")) }) {
                    val tree = slot.tree
                    if (tree == null) {
                        item(
                            headlineContent = { Text("按数据渲染（函数）") },
                            supportingContent = { Text("内容由主题 JS 按数据生成，请到代码" + (slot.line?.let { "第 $it 行" } ?: "里") + "修改") },
                        )
                    } else {
                        fun add(node: UIEditNode, depth: Int, indexes: List<Int>) {
                            item(
                                onClick = { selected = NodePath(slot.name, indexes) },
                                leadingContent = { Spacer(Modifier.width((depth * 14).dp)) },
                                headlineContent = { NodeSummary(node) },
                            )
                            node.children.forEachIndexed { i, c -> add(c, depth + 1, indexes + i) }
                        }
                        add(tree, 0, emptyList())
                    }
                }
            }
        }
    }

    val path = selected
    val found = path?.let { p -> analysis?.find(p) }
    if (path != null && found != null) {
        val idxInParent = path.indexes.lastOrNull()
        val siblingCount = found.second?.children?.size ?: 0
        me.rerere.rikkahub.ui.custom.UIModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            UINodePropertySheet(
                node = found.first,
                parent = found.second,
                canMoveUp = idxInParent != null && idxInParent > 0,
                canMoveDown = idxInParent != null && idxInParent < siblingCount - 1,
                code = code,
                editor = editor,
                onEdit = onEdit,
                onMoved = { delta ->
                    if (path.indexes.isNotEmpty()) {
                        val nextIdx = (path.indexes.last() + delta).coerceIn(0, (siblingCount - 1).coerceAtLeast(0))
                        selected = path.copy(indexes = path.indexes.dropLast(1) + nextIdx)
                    }
                },
                onDeleted = { selected = null },
            )
        }
    }
}

@Composable
private fun NodeSummary(node: UIEditNode) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = (TYPE_LABELS[node.type] ?: node.type) + (node.lead?.let { "「$it」" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (node.count > 1) {
            Text("×${node.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        node.line?.let {
            Text("L$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UINodePropertySheet(
    node: UIEditNode,
    parent: UIEditNode?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    code: String,
    editor: UICodeEditor,
    onEdit: (UICodeEdit, onSuccess: (() -> Unit)?) -> Unit,
    onMoved: (Int) -> Unit,
    onDeleted: () -> Unit,
) {
    val src = node.src
    val leadKey = UI_LEAD_KEYS[node.type]
    val propKeys = node.props.keys.filter { it != leadKey }
    var added by remember(src) { mutableStateOf(listOf<String>()) }
    val shownKeys = propKeys + added.filter { it !in propKeys }

    // 各属性能否修改（值来自变量 / 表达式的不能改）
    var checks by remember { mutableStateOf<Map<String, UIEditCheck>>(emptyMap()) }
    LaunchedEffect(code, src, shownKeys) {
        if (src == null) return@LaunchedEffect
        val res = editor.check(code, shownKeys.map { UIEditTarget.Call(src) to listOf(it) })
        checks = shownKeys.zip(res).toMap()
    }

    val title = (TYPE_LABELS[node.type] ?: node.type) + (node.line?.let { " · 第 $it 行" } ?: "")
    // 不在 Column / key 等 inline 的 Composable lambda 里用 return@标签 提前返回：
    // Kotlin 会留下 $$$$$NON_LOCAL_RETURN$$$$$ 占位调用，R8 转 dex 时报错
    if (src == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text("找不到这个节点在代码里的位置，请在代码里修改。", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        if (node.count > 1) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(10.dp)) {
                Text(
                    text = "这处写法生成了 ${node.count} 个节点（循环或函数），修改会同时影响它们。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (leadKey != null) {
            PropField(
                label = when (node.type) {
                    "icon", "icon_button" -> "图标名"
                    "image", "video" -> "来源"
                    "path" -> "SVG 路径"
                    "host" -> "部件名"
                    "shader" -> "AGSL 源码"
                    else -> "文字"
                },
                initial = node.lead.orEmpty(),
                stringOnly = true,
                check = null,
                onCommit = { v -> onEdit({ c -> editor.setLead(c, src, (v as JsonPrimitive).content) }, null) },
                onRemove = null,
            )
        }

        shownKeys.forEach { key -> androidx.compose.runtime.key(src, key) {
            val value = node.props[key]
            if (value is JsonPrimitive && value.isString && value.content == "ƒ") {
                Text("$key：回调函数（请在代码" + (node.line?.let { "第 $it 行" } ?: "里") + "修改）", style = MaterialTheme.typography.bodySmall)
            } else {
                PropField(
                    label = key + (COMMON_PROPS.firstOrNull { it.first == key }?.let { " · ${it.second}" } ?: ""),
                    initial = value?.display().orEmpty(),
                    stringOnly = false,
                    check = checks[key],
                    onCommit = { v -> onEdit({ c -> editor.setProp(c, UIEditTarget.Call(src), listOf(key), v) }, null) },
                    onRemove = if (value == null) null else ({ onEdit({ c -> editor.removeProp(c, UIEditTarget.Call(src), listOf(key)) }, null) }),
                )
            }
        } }

        // 加属性
        var showAdd by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { showAdd = true }) {
                Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加属性")
            }
            DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                COMMON_PROPS.filter { it.first !in shownKeys }.forEach { (k, label) ->
                    DropdownMenuItem(
                        text = { Text("$k · $label") },
                        onClick = {
                            showAdd = false
                            added = added + k
                        },
                    )
                }
            }
        }

        // 结构
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (node.type in CONTAINER_TYPES) {
                var showChild by remember { mutableStateOf(false) }
                Box {
                    FilledTonalButton(onClick = { showChild = true }) {
                        Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加子节点")
                    }
                    DropdownMenu(expanded = showChild, onDismissRequest = { showChild = false }) {
                        CHILD_TEMPLATES.forEach { (label, childSrc) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    showChild = false
                                    onEdit({ c -> editor.addChild(c, src, childSrc) }, null)
                                },
                            )
                        }
                    }
                }
            }
            val parentSrc = parent?.src
            if (parentSrc != null) {
                AssistChip(
                    enabled = canMoveUp,
                    onClick = { onEdit({ c -> editor.moveChild(c, parentSrc, src, -1) }) { onMoved(-1) } },
                    label = { Text("上移") },
                    leadingIcon = { Icon(HugeIcons.ArrowUp01, null, modifier = Modifier.size(16.dp)) },
                )
                AssistChip(
                    enabled = canMoveDown,
                    onClick = { onEdit({ c -> editor.moveChild(c, parentSrc, src, 1) }) { onMoved(1) } },
                    label = { Text("下移") },
                    leadingIcon = { Icon(HugeIcons.ArrowDown01, null, modifier = Modifier.size(16.dp)) },
                )
                AssistChip(
                    onClick = { onEdit({ c -> editor.removeChild(c, parentSrc, src) }) { onDeleted() } },
                    label = { Text("删除") },
                    leadingIcon = { Icon(HugeIcons.Delete01, null, modifier = Modifier.size(16.dp)) },
                )
            }
        }
    }
}

/**
 * 一个属性的编辑框：停手 0.4 秒后提交。输入按 JSON 解析（数字、true / false、数组、对象），解析不了当作字符串；
 * [stringOnly] 时总是字符串。不能改的属性禁用并提示行号。
 */
@Composable
private fun PropField(
    label: String,
    initial: String,
    stringOnly: Boolean,
    check: UIEditCheck?,
    onCommit: (JsonElement) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var text by remember { mutableStateOf(initial) }
    // 最近一次提交（或从代码读到）的值：代码改写后重新分析回来的还是它时不覆盖输入框，免得吞掉正在输入的字
    var committed by remember { mutableStateOf(initial) }
    val lockedMsg = check?.takeIf { !it.ok }?.message
    LaunchedEffect(initial) {
        if (initial != committed) {
            text = initial
            committed = initial
        }
    }
    LaunchedEffect(text) {
        if (text == committed || lockedMsg != null) return@LaunchedEffect
        delay(400)
        committed = text
        onCommit(parseValue(text, stringOnly))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            enabled = lockedMsg == null,
            supportingText = lockedMsg?.let { { Text("不能在这里改：$it") } },
            singleLine = !stringOnly || text.length < 60,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null && lockedMsg == null) {
            IconButton(onClick = onRemove) { Icon(HugeIcons.Cancel01, contentDescription = "删除属性") }
        }
    }
}

private fun parseValue(text: String, stringOnly: Boolean): JsonElement {
    if (stringOnly) return JsonPrimitive(text)
    val t = text.trim()
    if (t.isEmpty()) return JsonPrimitive("")
    // 数字、true / false、数组、对象按 JSON；其余（如 primary、#FFFFFF）按字符串
    return try {
        Json.parseToJsonElement(t)
    } catch (_: Exception) {
        JsonPrimitive(text)
    }
}

/** 表单字段在代码里的位置：ui.theme / ui.typography 的属性路径（每段可写 "a|b" 别名）。 */
internal data class UIFormField(val target: UIEditTarget, val path: List<String>) {
    val key: String get() = (listOf(target.toString()) + path).joinToString("/")
}

/** 表单里不能改的字段 → 原因（含行号）。 */
internal suspend fun UICodeEditor.lockedFields(code: String, fields: List<UIFormField>): Map<String, String> {
    if (fields.isEmpty()) return emptyMap()
    val res = check(code, fields.map { it.target to it.path })
    return fields.zip(res).filter { !it.second.ok }.associate { it.first.key to it.second.message }
}

@Composable
internal fun LockedHint(messages: Collection<String>) {
    if (messages.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "以下设置在代码里由变量或表达式决定，这里不能改：\n" + messages.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}
