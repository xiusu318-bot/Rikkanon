package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlin.uuid.Uuid

@Serializable
data class UIPreset(
    val id: Uuid = Uuid.random(),
    val name: String = "默认界面预设",
    val description: String = "",
    val enabled: Boolean = false,
    val code: String = "",
    val backgroundSection: UIBackgroundSection = UIBackgroundSection(),
    val typographySection: UITypographySection = UITypographySection(),
    /** 全局外观（`ui.font` / `ui.shapes` / `ui.motion`）：作用于全 App 的字体、形状与动效。 */
    val globalSection: UIGlobalSection = UIGlobalSection(),
    /**
     * `ui.style(名字, {...})`：宿主组件的样式覆盖（一套通用属性，组件用得上哪个读哪个）；没写的按原版绘制。
     * 名字不在宿主登记，写了宿主没读的名字不起作用。
     */
    val componentStyles: Map<String, UIComponentStyle> = emptyMap(),
    /**
     * `ui.slot(位置名, 节点树)`：固定写法的接入点内容（不随数据变化、不含回调）。
     * 宿主每个接入点用 `UISlot(位置名, 数据, 原版部件, 动作) { 原版 }` 包一层；主题没写的位置走原版。
     */
    val slots: Map<String, UIMessageNode> = emptyMap(),
    /** `ui.slot(位置名, (数据, ctx) => 节点树)`：按数据渲染的接入点，运行时由主题 JS 实例计算（`UIThemeRuntime`）。 */
    val dynamicSlots: List<String> = emptyList(),
    /** `ui.page(路由名, {...})`：按页面（`Screen` 子类名，`*` = 除聊天页外的全部页面）设置背景节点树与配色。 */
    val pages: Map<String, UIPageSpec> = emptyMap(),
    /** `ui.transition({...})`：页面切换动画；null = 宿主原版。 */
    val transitions: UITransitionSpec? = null,
    /** 上面各接管区是由哪份代码编译出来的（`CustomUICodeEngine.codeHash`）；与当前 [code] 不符时需重新编译。 */
    val compiledCodeHash: String? = null,
    /** 最近一次编译 [code] 失败的错误信息（含行号）；此时各接管区保持上次成功编译的结果。 */
    val compileError: String? = null,
) {
    /** 主题是否接管了某个位置（`ui.slot`）。 */
    fun hasSlot(name: String): Boolean = name in slots || name in dynamicSlots
}

@Serializable
data class UIBackgroundSection(
    val enabled: Boolean = false,
    /** 种子色：用 Material 官方算法生成整套配色；空 = 沿用宿主当前配色。 */
    val seedColorHex: String = "",
    /** 深浅色：null = 有 `colors.background` 时按它的亮度判断，否则跟随宿主。影响种子配色与状态栏图标颜色。 */
    val dark: Boolean? = null,
    /** `ui.theme({ colors: {...} })`：直接指定任意 Material 3 颜色槽位（如 `onPrimary`、`surfaceContainerHigh`），在其余推导完成后最后覆盖。 */
    val colorOverrides: Map<String, String> = emptyMap(),
)

/**
 * 全局外观。`enabled` 为 false（代码里没写 `ui.font` / `ui.shapes` / `ui.motion`）时宿主完全按原样运行。
 * 字体取值：`default` / `sans-serif` / `serif` / `monospace` / `cursive` / `chat`（跟随设置里的聊天字体）/ `file:<相对 filesDir 的路径>`。
 */
@Serializable
data class UIGlobalSection(
    val enabled: Boolean = false,
    /** 全局字体；空 = 不改。 */
    val fontFamily: String = "",
    /** 全局字号倍率（同时缩放行高）。 */
    val fontScale: Float = 1f,
    /** 全局字间距（em）；null = 不改。 */
    val letterSpacingEm: Float? = null,
    /** 按 Material 3 文字样式名（`titleLarge`、`bodyMediumEmphasized` 等 30 种）单独覆盖。 */
    val textStyles: Map<String, UITextStyleOverride> = emptyMap(),
    /** 形状风格：`rounded`（圆角）或 `cut`（切角）。 */
    val shapeStyle: String = "rounded",
    /** 按 Material 3 形状等级（`extraSmall` / `small` / `medium` / `large` / `extraLarge` / `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge`）指定角半径 dp；没写的保持默认。 */
    val shapeCorners: Map<String, Float> = emptyMap(),
    /** 动效方案：`expressive`（宿主默认）或 `standard`。 */
    val motionScheme: String = "expressive",
)

/** 页面级定制。[background] 铺在整页内容后面（此时页面底色变透明），[foreground] 叠在整页内容上面，[colors] 只作用于该页面。 */
@Serializable
data class UIPageSpec(
    val background: UIMessageNode? = null,
    val foreground: UIMessageNode? = null,
    val colors: Map<String, String> = emptyMap(),
)

/** 一侧（进入或退出）的切换动画：淡入淡出、按宽 / 高比例平移、缩放。 */
@Serializable
data class UITransitionSide(
    val fade: Boolean = false,
    val slideX: Float = 0f,
    val slideY: Float = 0f,
    /** 缩放起点（进入）或终点（退出）；1 = 不缩放。 */
    val scale: Float = 1f,
    val durationMs: Int = 300,
    val delayMs: Int = 0,
)

@Serializable
data class UITransitionPair(
    val enter: UITransitionSide = UITransitionSide(),
    val exit: UITransitionSide = UITransitionSide(),
)

/** 页面切换：打开新页面（push）与返回（pop，也用于预测性返回手势）。 */
@Serializable
data class UITransitionSpec(
    val push: UITransitionPair? = null,
    val pop: UITransitionPair? = null,
)

/** 宿主组件样式覆盖；为 null 的字段保持原版。颜色支持 #hex 与语义色名（如 `secondaryContainer`）。 */
@Serializable
data class UIComponentStyle(
    val background: String? = null,
    val color: String? = null,
    /** 次要内容色（如思考框正文）。 */
    val secondaryColor: String? = null,
    val cornerDp: Float? = null,
    val borderWidthDp: Float? = null,
    val borderColor: String? = null,
    val paddingHorizontalDp: Float? = null,
    val paddingVerticalDp: Float? = null,
    val marginHorizontalDp: Float? = null,
    val marginVerticalDp: Float? = null,
    val fontSizeSp: Float? = null,
    /** 背景不透明度 0～1。 */
    val opacity: Float? = null,
    val widthDp: Float? = null,
    /** 子项间距 dp。 */
    val spacingDp: Float? = null,
)

@Serializable
data class UITextStyleOverride(
    val size: Float? = null,
    val lineHeight: Float? = null,
    /** 字重 1～1000（400 常规、500 中等、600 半粗、700 粗）。 */
    val weight: Int? = null,
    val letterSpacingEm: Float? = null,
    val fontFamily: String? = null,
    val italic: Boolean? = null,
)

@Serializable
enum class UIMessageNodeType(val label: String, val isContainer: Boolean) {
    @SerialName("column")
    COLUMN("竖排容器 (Column)", true),

    @SerialName("row")
    ROW("横排容器 (Row)", true),

    @SerialName("box")
    BOX("层叠容器 (Box)", true),

    @SerialName("surface")
    SURFACE("卡片/气泡容器 (Surface)", true),

    @SerialName("collapsible")
    COLLAPSIBLE("可折叠容器 (Collapsible)", true),

    @SerialName("flow_row")
    FLOW_ROW("折行容器 (FlowRow)", true),

    @SerialName("popup")
    POPUP("浮层/弹窗 (Popup)", true),

    @SerialName("avatar")
    AVATAR("头像 (Avatar)", false),

    @SerialName("author_name")
    AUTHOR_NAME("发送者名称 (AuthorName)", false),

    @SerialName("model_icon")
    MODEL_ICON("模型图标 (ModelIcon)", false),

    @SerialName("model_name")
    MODEL_NAME("模型显示名 (ModelName)", false),

    @SerialName("timestamp")
    TIMESTAMP("消息时间 (Timestamp)", false),

    @SerialName("branch_pager")
    BRANCH_PAGER("分支切换器 (< 1/2 >)", false),

    @SerialName("reasoning_block")
    REASONING_BLOCK("思考与工具链 (Reasoning & Tools)", false),

    @SerialName("markdown_body")
    MARKDOWN_BODY("消息正文与媒体 (Markdown & Media)", false),

    @SerialName("translation")
    TRANSLATION("翻译结果块 (Translation)", false),

    @SerialName("edited_files")
    EDITED_FILES("工作区修改文件列表 (EditedFiles)", false),

    @SerialName("nerd_stats")
    NERD_STATS("技术统计行 (Token/TPS/耗时)", false),

    @SerialName("action_bar")
    ACTION_BAR("操作按钮栏 (ActionBar)", false),

    @SerialName("custom_text")
    CUSTOM_TEXT("自定义文本/徽标 (CustomText)", false),

    @SerialName("icon")
    ICON("通用图标 (Icon)", false),

    @SerialName("button")
    BUTTON("交互按钮 (Button)", false),

    @SerialName("icon_button")
    ICON_BUTTON("图标按钮 (IconButton)", false),

    @SerialName("badge")
    BADGE("胶囊徽标 (Badge)", false),

    @SerialName("canvas")
    CANVAS("矢量绘图 (Canvas)", false),

    @SerialName("folder_bar")
    FOLDER_BAR("抽屉文件夹栏 (FolderBar)", false),

    @SerialName("conversation_list")
    CONVERSATION_LIST("抽屉会话列表 (ConversationList)", false),

    @SerialName("assistant_picker")
    ASSISTANT_PICKER("抽屉助手切换器 (AssistantPicker)", false),

    @SerialName("divider")
    DIVIDER("分割线 (Divider)", false),

    @SerialName("spacer")
    SPACER("空白占位 (Spacer)", false),

    @SerialName("image")
    IMAGE("图片 (Image)", false),

    @SerialName("path")
    PATH("矢量路径 (Path)", false),

    @SerialName("host")
    HOST("宿主原样块 (Host)", false),

    /** AGSL 着色器（Android 13+）铺满节点作背景，子节点画在上面；低版本或着色器出错时画 [UIMessageNode.fallback]。 */
    @SerialName("shader")
    SHADER("着色器 (Shader)", true),

    @SerialName("video")
    VIDEO("视频 (Video)", false),

    @SerialName("text_field")
    TEXT_FIELD("文本输入框 (TextField)", false),

    @SerialName("switch")
    SWITCH("开关 (Switch)", false),

    @SerialName("slider")
    SLIDER("滑杆 (Slider)", false),

    /** 懒加载列表：只为屏幕上看得见的行调用主题的 `item(index, ctx)`。 */
    @SerialName("lazy_column")
    LAZY_COLUMN("懒加载竖向列表 (LazyColumn)", false),

    @SerialName("lazy_row")
    LAZY_ROW("懒加载横向列表 (LazyRow)", false),

    /** 按「类名 + 函数名」反射调用宿主的任意界面函数（`Compose(类名, 函数名, [参数])`）。 */
    @SerialName("compose")
    COMPOSE("宿主界面函数 (Compose)", false),

    /** 按类名放入任意安卓原生视图（`NativeView(类名, { onCreate })`）。 */
    @SerialName("native_view")
    NATIVE_VIEW("安卓原生视图 (NativeView)", false),
}

@Serializable
enum class UIHorizontalAlignment(val label: String) {
    @SerialName("start")
    START("靠左 (Start)"),

    @SerialName("center")
    CENTER("居中 (Center)"),

    @SerialName("end")
    END("靠右 (End)"),

    @SerialName("space_between")
    SPACE_BETWEEN("两端对齐 (SpaceBetween)"),
}

@Serializable
enum class UIVerticalAlignment(val label: String) {
    @SerialName("top")
    TOP("靠上 (Top)"),

    @SerialName("center")
    CENTER("居中 (Center)"),

    @SerialName("bottom")
    BOTTOM("靠下 (Bottom)"),
}

@Serializable
enum class UIMessageActionType(val label: String) {
    @SerialName("copy")
    COPY("复制"),

    @SerialName("regenerate")
    REGENERATE("重新生成"),

    @SerialName("edit")
    EDIT("编辑"),

    @SerialName("parts_editor")
    PARTS_EDITOR("深度部件编辑"),

    @SerialName("tts")
    TTS("朗读 (TTS)"),

    @SerialName("translate")
    TRANSLATE("翻译"),

    @SerialName("fork")
    FORK("分支 (Fork)"),

    @SerialName("favorite")
    FAVORITE("收藏"),

    @SerialName("webview")
    WEBVIEW("网页预览"),

    @SerialName("more")
    MORE("更多菜单 (⋮)"),

    @SerialName("branch_selector")
    BRANCH_SELECTOR("分支切换 (< 1/2 >)"),

    @SerialName("timestamp")
    TIMESTAMP("时间戳"),
}

@Serializable
data class UIMessageNode(
    val id: String = Uuid.random().toString().take(8),
    val name: String = "",
    val enabled: Boolean = true,
    val type: UIMessageNodeType = UIMessageNodeType.COLUMN,
    /**
     * 显示条件：空 / "always" = 始终显示，"never" = 隐藏；其余为接入点数据里的字段名（真值时显示），前面加 "!" 取反。
     * 例：`"pinned"`、`"!loading"`、`"has_reasoning"`。
     */
    val visibleWhen: String = "",
    val horizontalAlignment: UIHorizontalAlignment = UIHorizontalAlignment.START,
    val verticalAlignment: UIVerticalAlignment = UIVerticalAlignment.CENTER,
    val spacingDp: Float = 4f,
    val weight: Float = 0f,
    val fillMaxWidth: Boolean = false,
    val maxWidthFraction: Float = 1f,
    val paddingStartDp: Float = 0f,
    val paddingTopDp: Float = 0f,
    val paddingEndDp: Float = 0f,
    val paddingBottomDp: Float = 0f,
    val marginStartDp: Float = 0f,
    val marginTopDp: Float = 0f,
    val marginEndDp: Float = 0f,
    val marginBottomDp: Float = 0f,
    val cornerTopStartDp: Float = 0f,
    val cornerTopEndDp: Float = 0f,
    val cornerBottomEndDp: Float = 0f,
    val cornerBottomStartDp: Float = 0f,
    val backgroundColorHex: String = "",
    val gradientEndColorHex: String = "",
    val gradientAngleDeg: Float = 0f,
    val opacity: Float = 1.0f,
    val borderWidthDp: Float = 0f,
    val borderColorHex: String = "",
    val shadowElevationDp: Float = 0f,
    val enableHazeBlur: Boolean = false,
    val textColorHex: String = "",
    val fontSizeSp: Float = 0f,
    val fontWeightBold: Boolean = false,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    val scale: Float = 1.0f,
    val rotationDeg: Float = 0f,
    val avatarSizeDp: Float = 28f,
    val avatarCornerDp: Float = -1f,
    val iconSizeDp: Float = 18f,
    val customText: String = "",
    val iconName: String = "",
    val actionName: String = "",
    /** 画布：circle（实心圆）/ ring（圆环）。 */
    val canvasDrawType: String = "circle",
    val collapsibleTitle: String = "详情",
    val collapsibleDefaultExpanded: Boolean = false,
    val actionItems: List<UIMessageActionType> = listOf(
        UIMessageActionType.COPY,
        UIMessageActionType.REGENERATE,
        UIMessageActionType.TTS,
        UIMessageActionType.TRANSLATE,
        UIMessageActionType.MORE,
        UIMessageActionType.BRANCH_SELECTOR,
    ),
    /** 长按 / 双击时请求的动作名（单击为 [actionName]）。 */
    val longClickAction: String = "",
    val doubleClickAction: String = "",
    /** 纵向占满父容器（`fillMaxSize: true` 同时设置宽高）。 */
    val fillMaxHeight: Boolean = false,
    /** 模糊半径 dp（Android 12+，作用于本节点绘制的内容，如壁纸图片）。 */
    val blurRadiusDp: Float = 0f,
    /** 整个节点（含内容）的不透明度 0～1；[opacity] 只作用于背景色。 */
    val alpha: Float = 1f,
    /** 解除 Material 的 48dp 最小触控尺寸（作用于本节点及其全部子节点），按钮、图标按真实尺寸排版。 */
    val compact: Boolean = false,
    /** 固定宽 / 高 dp；0 = 不指定。所有节点通用。 */
    val widthDp: Float = 0f,
    val heightDp: Float = 0f,
    /** 文字：字体（同 ui.font 的 family 取值）；空 = 继承。 */
    val fontFamily: String = "",
    /** 文字：字重 1～1000；0 = 不指定（此时看 [fontWeightBold]）。 */
    val fontWeight: Int = 0,
    val italic: Boolean = false,
    /** 文字：最多几行，超出省略；0 = 该位置默认。 */
    val maxLines: Int = 0,
    /** 文字：字间距 em；null = 继承。 */
    val letterSpacingEm: Float? = null,
    /** 文字：行高 sp；0 = 继承。 */
    val lineHeightSp: Float = 0f,
    /** 文字：对齐 start / center / end / justify；空 = 继承。 */
    val textAlign: String = "",
    /** 图片：来源（http(s) 地址、data: URI 或 `file:<filesDir 相对路径>`），支持 GIF / 动态 WebP / SVG。 */
    val imageSrc: String = "",
    /** 图片缩放：crop / fit / fill / fill_width / fill_height / none。 */
    val contentScale: String = "fit",
    /** 矢量路径：SVG path 数据（`d` 属性）。 */
    val pathData: String = "",
    val viewBoxWidth: Float = 24f,
    val viewBoxHeight: Float = 24f,
    /** 矢量路径：描边宽度（按 viewBox 坐标）；0 = 填充。 */
    val strokeWidth: Float = 0f,
    /** 矢量路径：描边端点 butt / round / square。 */
    val strokeCap: String = "butt",
    /** 宿主原样块名（`Host("send_button")`），在所在位置由宿主按原版绘制。 */
    val hostBlock: String = "",
    /** 主题 JS 里写的回调（`onClick: () => {...}`）在运行时实例里的编号；点击时回到 JS 执行。 */
    val onClickFn: String = "",
    val onLongClickFn: String = "",
    val onDoubleClickFn: String = "",
    /** 关键帧动画（`animate`）：任意节点的透明度 / 缩放 / 旋转 / 位移 / 底色 / 文字色 / 渐变角度。 */
    val animations: List<UINodeAnimation> = emptyList(),
    /** 按压反馈（`press`）：手指按下时缩放 / 变透明，松开恢复。 */
    val press: UIPressEffect? = null,
    /** 着色器：AGSL 源码。宿主提供 `uniform float2 iResolution`（像素）、`uniform float iTime`（秒）、`uniform float3 iTouch`（x, y 像素，按下为 1）。 */
    val shaderSrc: String = "",
    /** 着色器：主题自己的 uniform（名字 → 1～4 个数，颜色按 r, g, b, a）。 */
    val uniforms: Map<String, List<Float>> = emptyMap(),
    /** 着色器不可用（Android 13 以下、编译出错）时代替它绘制的节点；null = 不画。 */
    val fallback: UIMessageNode? = null,
    /** 视频：循环播放 / 静音。 */
    val loop: Boolean = true,
    val muted: Boolean = true,
    /** Box / Shader / Popup 是否显式指定了 `align` 或 `vAlign`（未指定时保持 Compose Box 默认的 TopStart，指定后按 9 宫格对齐）。 */
    val hasExplicitBoxAlign: Boolean = false,
    /** 层叠顺序（Compose `zIndex`）；0 = 默认。 */
    val zIndex: Float = 0f,
    /** 是否按圆角/切角裁剪内容：null = 默认自动（有圆角/底色/着色器时裁剪），false = 允许光效或角标溢出容器边界。 */
    val clip: Boolean? = null,
    /** 图层混合模式：空 = 默认 srcOver；支持 plus / screen / multiply / overlay / lighten / darken。 */
    val blendMode: String = "",
    /** 触摸穿透：空 或 "auto" = 正常响应手势；"none" = 本节点及子树完全不拦截触摸事件（适合前景特效层）。 */
    val pointerEvents: String = "",
    /** 容器滚动：空 = 不滚动；"vertical" = 纵向滚动；"horizontal" = 横向滚动。 */
    val scroll: String = "",
    /** FlowRow 的行间距 dp；<= 0 时跟随 [spacingDp]。 */
    val crossSpacingDp: Float = 0f,
    /** 系统安全区内边距（逗号或空格分隔）：status / nav / ime / safe。 */
    val insets: String = "",
    /** TextField 占位提示词。 */
    val placeholder: String = "",
    /** TextField 是否单行（默认 true）。 */
    val singleLine: Boolean = true,
    /** Switch 开关选中状态。 */
    val checked: Boolean = false,
    /** Slider 当前数值、最小值、最大值与步数。 */
    val sliderValue: Float = 0f,
    val minValue: Float = 0f,
    val maxValue: Float = 1f,
    val steps: Int = 0,
    /** Popup 是否为模态对话框（true = Dialog，false = Popup 浮层）。 */
    val modal: Boolean = true,
    /** 交互触感震动：空 = 不震动；支持 tick / click / heavy / confirm / reject / long_press。 */
    val haptic: String = "",
    /** 着色器按需唤醒时长（秒）：> 0 时仅在触屏期间及松手后 [wakeDuration] 秒内逐帧渲染，之后自动休眠为 0 FPS。 */
    val wakeDuration: Float = 0f,
    /** 表单与浮层回调在运行时实例里的编号。 */
    val onChangeFn: String = "",
    val onSubmitFn: String = "",
    val onDismissFn: String = "",
    /** 懒加载列表：行数；[keys] 非空时以 [keys] 的长度为准。 */
    val count: Int = 0,
    /** 懒加载列表：每行的稳定标识（不重复）；空 = 用行号。拖拽排序、增删行时靠它保持各行状态。 */
    val keys: List<String> = emptyList(),
    /** 懒加载列表：某行还没算好时占位的高度（横向列表为宽度）dp。 */
    val itemSize: Float = 56f,
    /** 懒加载列表：下拉刷新的转圈状态（配合 `onRefresh`）。 */
    val refreshing: Boolean = false,
    /** 懒加载列表的回调编号：按行渲染 `item(index, ctx)`、滚到末尾 `onEndReached(ctx)`、拖拽排序结束 `onReorder(from, to, ctx)`、下拉刷新 `onRefresh(ctx)`。 */
    val itemFn: String = "",
    val onEndReachedFn: String = "",
    val onReorderFn: String = "",
    val onRefreshFn: String = "",
    /** 滑动手势：拖动过程由宿主跟手，松手越过阈值后回调 `onSwipe(方向, ctx)`。null = 不启用。 */
    val swipe: UISwipeSpec? = null,
    val onSwipeFn: String = "",
    /** 双指缩放与平移（宿主原生完成，不回调主题）；null = 不启用。 */
    val zoom: UIZoomSpec? = null,
    /** `Compose(类名, 函数名, [参数])`：宿主界面函数所在的类（顶层函数为 `文件名Kt`）与函数名。 */
    val composeClass: String = "",
    val composeFn: String = "",
    /**
     * `Compose` 的参数（按位置）。元素：数字 / 字符串 / 布尔原样；`null` = 用函数默认值；
     * `{"$j": 句柄}` = Java 对象；`{"$cb": 回调编号}` = 主题函数；`{"$child": n}` = 第 n 个子节点作为界面内容。
     */
    val args: JsonArray? = null,
    /** `NativeView(类名)`：安卓视图类名；宿主在主线程用 `(Context)` 构造。 */
    val viewClass: String = "",
    /** 原生视图创建后 / 离开界面前的回调编号：`onCreate(view, ctx)`、`onRelease(view, ctx)`。 */
    val onCreateFn: String = "",
    val onReleaseFn: String = "",
    val children: List<UIMessageNode> = emptyList(),
) {
    val displayTitle: String
        get() = name.ifBlank { type.label }
}

/**
 * 一条关键帧动画。数值类属性用 [values]，颜色类属性（bg、color）用 [colors]；[times] 为各关键帧在一次播放中的位置（0～1），
 * 空 = 均匀分布。每段之间按 [easing] 插值。
 */
@Serializable
data class UINodeAnimation(
    /** alpha / scale / scaleX / scaleY / rotate / rotateX / rotateY / translateX / translateY（dp）/ gradientAngle / bg / color。 */
    val prop: String,
    val values: List<Float> = emptyList(),
    val colors: List<String> = emptyList(),
    val times: List<Float> = emptyList(),
    /** 一次播放的时长（毫秒）。 */
    val duration: Int = 1000,
    /** 开始前等待（毫秒）。 */
    val delay: Int = 0,
    /** 播放次数；-1 = 无限循环。 */
    val repeat: Int = 1,
    /** 往返：偶数次倒着播放。 */
    val alternate: Boolean = false,
    /** linear / ease / easeIn / easeOut / easeInOut / cubic-bezier(x1, y1, x2, y2)。 */
    val easing: String = "easeInOut",
)

/**
 * 滑动手势。[direction]：left / right / horizontal / up / down / vertical；[follow]：拖动时内容是否跟手；
 * [dismiss]：触发后滑出不弹回（删除类操作）；[threshold]：触发所需的位移占节点宽 / 高的比例。
 */
@Serializable
data class UISwipeSpec(
    val direction: String = "horizontal",
    val follow: Boolean = true,
    val dismiss: Boolean = false,
    val threshold: Float = 0.35f,
)

/** 双指缩放范围。 */
@Serializable
data class UIZoomSpec(
    val min: Float = 1f,
    val max: Float = 4f,
)

/** 按压反馈：按下时缩放到 [scale]、不透明度变为 [alpha]，用时 [duration] 毫秒。 */
@Serializable
data class UIPressEffect(
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val duration: Int = 120,
)

@Serializable
data class UITypographySection(
    val enabled: Boolean = false,
    val fontSizeMultiplier: Float = 1.0f,
    /** 行距倍率（相对字号）；null = 原版。 */
    val lineHeightMultiplier: Float? = null,
    /** 段落间距 dp；null = 原版（跟随字号）。 */
    val blockSpacingDp: Float? = null,
    val firstLineIndentSp: Float = 0f,
    val bodyTextColorHex: String = "",
    val userBodyTextColorHex: String = "",
    /** 引号台词高亮；null = 跟随显示设置里的开关。 */
    val quoteHighlightEnabled: Boolean? = null,
    val quoteTextColorHex: String = "",
    val quoteFontWeightBold: Boolean = false,
    val italicTextColorHex: String = "",
    val boldTextColorHex: String = "",
    val headingColorHex: String = "",
    val blockquoteBarColorHex: String = "",
    val blockquoteBgColorHex: String = "",
    val inlineCodeColorHex: String = "",
)

/** 旧版内置样板的固定 ID：升级后自动清理（除非是用户已经启用或改写过的预设）。 */
internal val LEGACY_BUILT_IN_UI_PRESET_IDS: Set<Uuid> = setOf(
    Uuid.parse("20000000-0000-0000-0000-000000000001"),
    Uuid.parse("20000000-0000-0000-0000-000000000002"),
    Uuid.parse("20000000-0000-0000-0000-000000000003"),
    Uuid.parse("20000000-0000-0000-0000-000000000004"),
    Uuid.parse("20000000-0000-0000-0000-000000000005"),
    Uuid.parse("20000000-0000-0000-0000-000000000006"),
)
