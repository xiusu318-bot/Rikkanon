package me.rerere.rikkahub.ui.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.UIBackgroundSection
import me.rerere.rikkahub.data.model.UIComponentStyle
import me.rerere.rikkahub.data.model.UIGlobalSection
import me.rerere.rikkahub.data.model.UIMessageNode
import me.rerere.rikkahub.data.model.UIMessageNodeType
import me.rerere.rikkahub.data.model.UIPageSpec
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.model.UITransitionPair
import me.rerere.rikkahub.data.model.UITransitionSide
import me.rerere.rikkahub.data.model.UITypographySection

/**
 * 把编译结果（JSON / 预设各区）转回可读的 UI 代码。导入 JSON、旧的纯积木预设转为代码都用它。
 * 输出用 `ui.*` 与组件构造函数的简写形式，只写与默认值不同的属性；
 * 保证 `compileUICode(generate(p))` 得到与 p 相同的各区（见单元测试）。
 */
object UICodeGenerator {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    fun generate(preset: UIPreset): String = buildString {
        appendLine("// ${preset.name}")
        section("theme", json.encodeToJsonElement(UIBackgroundSection.serializer(), preset.backgroundSection).jsonObject, preset.backgroundSection.enabled, UIBackgroundSection()) { o ->
            o.rename("colorOverrides", "colors")
            o.rename("seedColorHex", "seed")
        }
        generateGlobal(preset.globalSection)
        section("typography", json.encodeToJsonElement(UITypographySection.serializer(), preset.typographySection).jsonObject, preset.typographySection.enabled, UITypographySection()) {}
        preset.componentStyles.forEach { (name, style) -> appendLine("ui.style(${str(name)}, ${literal(componentStyle(style), 0)});") }
        preset.slots.forEach { (name, root) -> appendLine("ui.slot(${str(name)}, ${node(root, 0)});") }
        // 按数据渲染的接入点是 JS 函数，只存在于代码里，无法从编译结果还原
        preset.dynamicSlots.forEach { appendLine("// ui.slot(${str(it)}, (data, ctx) => ...) 是函数，请保留原代码") }
        preset.pages.forEach { (route, page) -> appendLine("ui.page(${str(route)}, ${pageSpec(page)});") }
        preset.transitions?.let { tr ->
            val o = buildJsonObject {
                tr.push?.let { put("push", transitionPair(it)) }
                tr.pop?.let { put("pop", transitionPair(it)) }
            }
            appendLine("ui.transition(${literal(o, 0)});")
        }
    }

    /** 与默认值不同才输出；`enabled` 总是显式写出（解析时缺省为 true）。 */
    private inline fun StringBuilder.section(
        name: String,
        encoded: JsonObject,
        enabled: Boolean,
        default: Any,
        transform: (MutableMap<String, JsonElement>) -> Unit,
    ) {
        // 全部为默认值（含 enabled = false）：不输出
        if (!enabled && encoded.isEmpty()) return
        val m = encoded.toMutableMap()
        m.remove("enabled")
        transform(m)
        val o = JsonObject(linkedMapOf<String, JsonElement>("enabled" to JsonPrimitive(enabled)) + m)
        appendLine()
        appendLine("ui.$name(${literal(o, 0)});")
    }

    private fun StringBuilder.generateGlobal(g: UIGlobalSection) {
        if (!g.enabled) return
        appendLine()
        val font = buildJsonObject {
            if (g.fontFamily.isNotBlank()) put("family", g.fontFamily)
            if (g.fontScale != 1f) put("scale", g.fontScale)
            g.letterSpacingEm?.let { put("letterSpacing", it) }
            if (g.textStyles.isNotEmpty()) put("styles", JsonObject(g.textStyles.mapValues { (_, s) ->
                buildJsonObject {
                    s.size?.let { put("size", it) }
                    s.lineHeight?.let { put("lineHeight", it) }
                    s.weight?.let { put("weight", it) }
                    s.letterSpacingEm?.let { put("letterSpacing", it) }
                    s.fontFamily?.let { put("family", it) }
                    s.italic?.let { put("italic", it) }
                }
            }))
        }
        if (font.isNotEmpty()) appendLine("ui.font(${literal(font, 0)});")
        val shapes = buildJsonObject {
            if (g.shapeStyle != "rounded") put("style", g.shapeStyle)
            g.shapeCorners.forEach { (k, v) -> put(k, v) }
        }
        if (shapes.isNotEmpty()) appendLine("ui.shapes(${literal(shapes, 0)});")
        // 动效总是写出：全局外观启用但字体、形状都没写时，靠它保持启用
        appendLine("ui.motion(${str(g.motionScheme)});")
    }

    private fun componentStyle(s: UIComponentStyle): JsonObject = buildJsonObject {
        s.background?.let { put("bg", it) }
        s.color?.let { put("color", it) }
        s.secondaryColor?.let { put("secondaryColor", it) }
        s.cornerDp?.let { put("corner", it) }
        if (s.borderWidthDp != null || s.borderColor != null) {
            put("border", JsonArray(listOf(JsonPrimitive(s.borderWidthDp ?: 0f), JsonPrimitive(s.borderColor ?: "outlineVariant"))))
        }
        pair(s.paddingHorizontalDp, s.paddingVerticalDp)?.let { put("padding", it) }
        pair(s.marginHorizontalDp, s.marginVerticalDp)?.let { put("margin", it) }
        s.fontSizeSp?.let { put("fontSize", it) }
        s.opacity?.let { put("opacity", it) }
        s.widthDp?.let { put("width", it) }
        s.spacingDp?.let { put("spacing", it) }
    }

    private fun pair(h: Float?, v: Float?): JsonElement? {
        if (h == null && v == null) return null
        return JsonArray(listOf(JsonPrimitive(h ?: 0f), JsonPrimitive(v ?: 0f)))
    }

    private fun pageSpec(p: UIPageSpec): String {
        val parts = mutableListOf<String>()
        p.background?.let { parts += "background: ${node(it, 1)}" }
        p.foreground?.let { parts += "foreground: ${node(it, 1)}" }
        if (p.colors.isNotEmpty()) parts += "colors: ${literal(JsonObject(p.colors.mapValues { JsonPrimitive(it.value) }), 1)}"
        return "{ ${parts.joinToString(", ")} }"
    }

    private fun transitionPair(p: UITransitionPair): JsonObject = buildJsonObject {
        put("enter", transitionSide(p.enter))
        put("exit", transitionSide(p.exit))
    }

    private fun transitionSide(s: UITransitionSide): JsonObject = buildJsonObject {
        if (s.fade) put("fade", true)
        if (s.slideX != 0f) put("slideX", s.slideX)
        if (s.slideY != 0f) put("slideY", s.slideY)
        if (s.scale != 1f) put("scale", s.scale)
        put("duration", s.durationMs)
        if (s.delayMs != 0) put("delay", s.delayMs)
    }

    // ---------- 节点 ----------

    private val CONSTRUCTORS = mapOf(
        "column" to "Column", "row" to "Row", "flow_row" to "FlowRow", "box" to "Box", "popup" to "Popup",
        "surface" to "Surface", "collapsible" to "Collapsible",
        "avatar" to "Avatar", "author_name" to "AuthorName", "model_icon" to "ModelIcon", "model_name" to "ModelName",
        "timestamp" to "Timestamp", "branch_pager" to "BranchPager", "reasoning_block" to "ReasoningBlock",
        "markdown_body" to "MarkdownBody", "translation" to "Translation", "edited_files" to "EditedFiles",
        "nerd_stats" to "NerdStats", "action_bar" to "ActionBar", "custom_text" to "Text", "text_field" to "TextField",
        "switch" to "Switch", "slider" to "Slider", "icon" to "Icon",
        "button" to "Button", "icon_button" to "IconButton", "badge" to "Badge", "canvas" to "Canvas",
        "folder_bar" to "FolderBar", "conversation_list" to "ConversationList", "assistant_picker" to "AssistantPicker",
        "divider" to "Divider", "spacer" to "Spacer", "image" to "Image", "path" to "Path", "host" to "Host",
        "shader" to "Shader", "video" to "Video",
        "lazy_column" to "LazyColumn", "lazy_row" to "LazyRow", "compose" to "Compose", "native_view" to "NativeView",
    )

    /** 以字符串作第一个参数的构造函数及其对应字段。 */
    private val LEAD_STRING = mapOf(
        "custom_text" to "customText", "text_field" to "customText", "button" to "customText", "badge" to "customText",
        "icon" to "iconName", "icon_button" to "iconName", "image" to "imageSrc", "path" to "pathData", "host" to "hostBlock",
        "video" to "imageSrc", "shader" to "shaderSrc", "native_view" to "viewClass",
    )

    private val RENAMES = linkedMapOf(
        "textColorHex" to "color", "fontSizeSp" to "fontSize", "fontWeightBold" to "bold", "spacingDp" to "spacing",
        "crossSpacingDp" to "crossSpacing",
        "horizontalAlignment" to "align", "verticalAlignment" to "vAlign", "avatarSizeDp" to "size",
        "iconSizeDp" to "iconSize", "shadowElevationDp" to "shadow", "enableHazeBlur" to "blur",
        "offsetXDp" to "offsetX", "offsetYDp" to "offsetY", "rotationDeg" to "rotate", "maxWidthFraction" to "maxWidth",
        "widthDp" to "width", "heightDp" to "height", "letterSpacingEm" to "letterSpacing", "lineHeightSp" to "lineHeight",
        "collapsibleTitle" to "title", "collapsibleDefaultExpanded" to "defaultExpanded", "canvasDrawType" to "drawType",
        "avatarCornerDp" to "avatarCorner", "blurRadiusDp" to "blurRadius",
        "sliderValue" to "value", "minValue" to "min", "maxValue" to "max",
    )

    fun node(n: UIMessageNode, indent: Int): String {
        val m = json.encodeToJsonElement(UIMessageNode.serializer(), n).jsonObject.toMutableMap()
        val typeName = json.encodeToJsonElement(UIMessageNodeType.serializer(), n.type).jsonPrimitive.content
        m.remove("type")
        m.remove("children")
        m.remove("hasExplicitBoxAlign")
        RUNTIME_FN_KEYS.forEach { m.remove(it) }
        m.quad("padding", "paddingStartDp", "paddingTopDp", "paddingEndDp", "paddingBottomDp")
        m.quad("margin", "marginStartDp", "marginTopDp", "marginEndDp", "marginBottomDp")
        m.quad("corners", "cornerTopStartDp", "cornerTopEndDp", "cornerBottomEndDp", "cornerBottomStartDp")
        if ("borderWidthDp" in m || "borderColorHex" in m) {
            m["border"] = JsonArray(listOf(JsonPrimitive(n.borderWidthDp), JsonPrimitive(n.borderColorHex)))
            m.remove("borderWidthDp"); m.remove("borderColorHex")
        }
        if ("gradientEndColorHex" in m) {
            m["gradient"] = JsonArray(listOf(JsonPrimitive(n.backgroundColorHex), JsonPrimitive(n.gradientEndColorHex), JsonPrimitive(n.gradientAngleDeg)))
            m.remove("backgroundColorHex"); m.remove("gradientEndColorHex"); m.remove("gradientAngleDeg")
        } else {
            m.rename("backgroundColorHex", "bg")
        }
        if ("viewBoxWidth" in m || "viewBoxHeight" in m) {
            m["viewBox"] = if (n.viewBoxWidth == n.viewBoxHeight) JsonPrimitive(n.viewBoxWidth)
            else JsonArray(listOf(JsonPrimitive(n.viewBoxWidth), JsonPrimitive(n.viewBoxHeight)))
            m.remove("viewBoxWidth"); m.remove("viewBoxHeight")
        }
        (m.remove("animations") as? JsonArray)?.let { arr ->
            m["animate"] = JsonArray(arr.map { a ->
                val o = a.jsonObject.toMutableMap()
                o.remove("colors")?.let { o["values"] = it }
                if ((o["repeat"] as? JsonPrimitive)?.content == "-1") o["repeat"] = JsonPrimitive("infinite")
                JsonObject(o)
            })
        }
        n.fallback?.let { m["fallback"] = JsonPrimitive(RAW_PREFIX + node(it, indent + 1)) }
        m.rename("actionName", "onClick")
        m.rename("longClickAction", "onLongClick")
        m.rename("doubleClickAction", "onDoubleClick")
        RENAMES.forEach { (from, to) -> m.rename(from, to) }
        if ((n.type == UIMessageNodeType.BOX || n.type == UIMessageNodeType.SHADER || n.type == UIMessageNodeType.POPUP) &&
            n.hasExplicitBoxAlign && "align" !in m && "vAlign" !in m
        ) {
            m["vAlign"] = JsonPrimitive("center")
        }
        // 第一个参数
        val leadKey = LEAD_STRING[typeName]
        val lead = leadKey?.let { m.remove(it) }?.jsonPrimitive?.content
        val actionItems = if (typeName == "action_bar") m.remove("actionItems") else null
        val fn = CONSTRUCTORS[typeName] ?: error("unknown node type $typeName")
        if (n.type == UIMessageNodeType.COMPOSE) {
            // Compose(类名, 函数名, [参数], 属性?)：参数里的界面内容（{"$child": n}）还原成节点写法
            m.remove("composeClass"); m.remove("composeFn"); m.remove("args")
            val composeArgs = JsonArray((n.args ?: JsonArray(emptyList())).map { el ->
                val child = ((el as? JsonObject)?.get("\$child") as? JsonPrimitive)?.content?.toIntOrNull()
                if (child != null) JsonPrimitive(RAW_PREFIX + node(n.children[child], indent + 1)) else el
            })
            val parts = mutableListOf(str(n.composeClass), str(n.composeFn), literal(composeArgs, indent))
            if (m.isNotEmpty()) parts += literal(JsonObject(m), indent)
            return "$fn(${parts.joinToString(", ")})"
        }
        val props = JsonObject(m)
        val args = mutableListOf<String>()
        if (lead != null) args += str(lead)
        if (actionItems != null) args += literal(actionItems, indent)
        val isContainer = n.type.isContainer
        if (props.isNotEmpty()) args += literal(props, indent)
        if (n.type != UIMessageNodeType.NATIVE_VIEW && (isContainer || n.children.isNotEmpty())) {
            val pad = "  ".repeat(indent + 1)
            val children = if (n.children.isEmpty()) "[]" else n.children.joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = ",\n${"  ".repeat(indent)}]",
            ) { pad + node(it, indent + 1) }
            args += children
        }
        return "$fn(${args.joinToString(", ")})"
    }

    private val RUNTIME_FN_KEYS = listOf(
        "onClickFn", "onLongClickFn", "onDoubleClickFn", "onChangeFn", "onSubmitFn", "onDismissFn",
        "itemFn", "onEndReachedFn", "onReorderFn", "onRefreshFn", "onSwipeFn", "onCreateFn", "onReleaseFn",
    )

    private fun MutableMap<String, JsonElement>.quad(target: String, s: String, t: String, e: String, b: String) {
        if (keys.none { it == s || it == t || it == e || it == b }) return
        fun v(k: String) = (remove(k) as? JsonPrimitive)?.content?.toFloat() ?: 0f
        val vs = v(s); val vt = v(t); val ve = v(e); val vb = v(b)
        this[target] = when {
            vs == vt && vt == ve && ve == vb -> JsonPrimitive(vs)
            target != "corners" && vs == ve && vt == vb -> JsonArray(listOf(JsonPrimitive(vs), JsonPrimitive(vt)))
            else -> JsonArray(listOf(vs, vt, ve, vb).map { JsonPrimitive(it) })
        }
    }

    private fun MutableMap<String, JsonElement>.rename(from: String, to: String) {
        remove(from)?.let { this[to] = it }
    }

    // ---------- JS 字面量 ----------

    private const val RAW_PREFIX = "\u0000raw:"
    private val IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    private fun str(s: String): String = JsonPrimitive(s).toString()

    private fun literal(el: JsonElement, indent: Int): String = when (el) {
        is JsonObject -> if (el.isEmpty()) "{}" else el.entries.joinToString(", ", "{ ", " }") { (k, v) ->
            val key = if (IDENT.matches(k)) k else str(k)
            "$key: ${literal(v, indent)}"
        }
        is JsonArray -> el.joinToString(", ", "[", "]") { literal(it, indent) }
        is JsonNull -> "null"
        is JsonPrimitive -> if (el.isString && el.content.startsWith(RAW_PREFIX)) el.content.removePrefix(RAW_PREFIX)
        else if (el.isString) el.toString()
        else el.content.let { c -> c.toDoubleOrNull()?.let { d -> if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else c } ?: c }
    }
}
