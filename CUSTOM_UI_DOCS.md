# Rikkanon 主题美化（UI 代码）开发与宿主字典文档

> **适用版本**：`v1.3.0+`（实验性功能，引擎版本 `ENGINE_VERSION = 14`）  
> **运行环境**：内置独立 QuickJS（ES2020 标准 JavaScript），保存即编译生效，无需重打包 APK、不依赖插件系统。  
> **⚠️ 使用前说明（重要）**：本模块**目前不太稳定，也不太好用**（默认关闭，日常求稳建议保持关闭）。出于个人偏好，宿主侧**只暴露了偏底层的节点、插槽与反射底座能力**，没有提供一键换皮模板或高度封装好的便捷开关——如果只是微调颜色还好，**想要把客户端彻底改成另一种风格，基本需要手写大量代码逐个覆盖插槽乃至重写很多二级页面，并不灵活省事**（相对来说只是底层限制少、自由度高一些），且当前可能存在较多未知 Bug。  
> **宿主字段速查**：本文档第七、九、十章收录了 **正式包 R8 反射规则、宿主服务与数据模型字段字典、以及可借用宿主控件的参数签名表**，编写或覆盖二级页面时可直接对照查阅。

---

## 目录

1. [核心机制与执行生命周期](#一核心机制与执行生命周期)
2. [顶层全局配置函数（`ui.*` & `color.*`）](#二顶层全局配置函数ui--color)
   - [`ui.theme`：全局配色与 48 槽位覆盖](#1-uithemecfg--全局配色)
   - [`color.*`：内置 Oklab 颜色运算工具](#2-color--内置颜色运算工具)
   - [`ui.font`：全局字体与 30 级文字样式](#3-uifontcfg--全局字体与文字阶梯)
   - [`ui.shapes` & `ui.motion`：圆角切角与动效方案](#4-uishapescfg--uimotionscheme--全局形状与动效)
   - [`ui.typography`：聊天 Markdown 正文排版与语义染色](#5-uitypographycfg--消息正文排版)
   - [`ui.page`、`ui.overlay` & `ui.transition`：单页背景/前景/整页替换、全局前景层与路由转场](#6-uipageroute-cfguioverlaytreeorfn--uitransitioncfg--页面级定制全局顶层前景层与转场动画)
3. [宿主组件样式覆写：`ui.style(name, spec)`](#三宿主组件样式覆写uistylename-spec)
4. [UI 节点构造函数大全（容器 / 基础 / 矢量 / 媒体 / 宿主块）](#四ui-节点构造函数大全)
   - [构造函数签名总表](#1-构造函数签名总表)
   - [节点通用属性详解（尺寸 / 边距 / 外观 / 文本 / 手势 / 条件）](#2-节点通用属性全表)
   - [内置图标名（`Icon` / `IconButton`）](#3-内置图标名映射表)
5. [插槽接管核心：`ui.slot(name, treeOrFn)`](#五插槽接管核心uislotname-treeorfn)
   - [写法一：静态节点树（编译期固化，零运行时 JS 开销）](#1-写法一静态节点树)
   - [写法二：动态渲染函数 `(data, ctx) => Node | null` 与交互回调](#2-写法二动态渲染函数与状态回调)
   - [页面级大组件骨架重排与跨页直嵌：`chat.layout`](#3-页面级大组件骨架重排与跨页直嵌chatlayout)
   - [全量插槽位置速查表（`data` 字段 / `Host` 部件 / `action` 动作）](#4-全量插槽位置速查表)
   - [全量宿主页面路由名（用于 `navigate:` 与 `ui.page`）](#5-全量页面路由名一览表)
6. [动态视觉特效（关键帧动画 `animate` / 按压 `press` / 着色器 `Shader` / 视频 `Video`）](#六动态视觉特效)
7. [原生数据桥与正式包 R8 反射铁律（`java` / `host` / `ctx.watch`）](#七原生数据桥与正式包-r8-反射铁律)
8. [实战进阶技巧与常见排坑指南](#八实战进阶技巧与常见排坑指南)
9. [整页重写的三项底座：懒加载列表 / 手势 / 放入宿主控件](#九整页重写的三项底座引擎版本-14)
10. [宿主服务、数据模型与可借用控件字典（免看宿主源码速查表）](#十宿主服务数据模型与可借用控件字典免看宿主源码速查表)
11. [宿主模块实现文件索引](#十一宿主模块实现文件索引)

---

## 一、核心机制与执行生命周期

### 1. 四条核心设计原则
1. **不开零改变**：总开关位于「设置 → 偏好设置 → 界面自定义」（全局生效）。总开关关闭或未启用任何预设时，宿主 100% 走原版原生代码；开启后，**主题代码没写到的属性、组件和插槽一律保持原版**。
2. **代码是唯一事实来源**：`.ui.js` 代码保存时编译为预设结构；编辑页中的「界面结构 / 配色 / 正文排版」可视化面板通过内置 `acorn` AST 解析器直接反向改写你的 JS 源码字面量（若某属性由变量/循环算出，可视化控件会自动锁定并提示对应源码行号）。
3. **两阶段执行模型（非常重要）**：
   - **阶段 A —— 保存编译期（顶层代码）**：点击保存或导入时，引擎在沙箱中执行顶层 JS，收集 `ui.theme`、`ui.font`、`ui.style` 以及静态 `ui.slot(name, Node)` 的结果落盘。**顶层代码执行时不连接宿主，不能在顶层直接调 `java.*` 或 `host.*`**。
   - **阶段 B —— 界面运行期（动态插槽函数与手势回调）**：若代码中注册了函数形式的 `ui.slot(name, (data, ctx) => ...)` 或点击回调 `onClick: (ctx) => ...`，宿主会为该主题启动一个常驻 QuickJS 实例（按 `(插槽名, 数据JSON, 状态版本, watch指纹)` 缓存最多 400 条渲染树；回调触发后状态版本自动 `+1` 驱动局部重算）。
4. **单点容错不崩溃**：未知属性名、枚举拼写错误会在编译期直接报出 `ui-code.js:行号`；运行期某插槽抛错、缺字段、未知 `Host` 部件或着色器编译失败时，**只有出错的那一个位置回退原版或隐藏**，错误实时汇总在编辑页顶部横幅，绝不会导致 App 闪退。

> **注意**：旧版过渡写法 `ui.drawer`、`ui.topBar`、`ui.inputBar`、`ui.messages`、`ui.userMessage`、`ui.assistantMessage`、`ui.template` 已废弃，调用会直接给出新写法提示。

---

## 二、顶层全局配置函数（`ui.*` & `color.*`）

所有 `ui.*` 函数均支持**多次调用自动合并**（对象字段如 `colors`、`styles` 会按第一层键名合并覆盖）。

### 1. `ui.theme(cfg)` —— 全局配色
用于定义整套 Material 3 调色板（优先级高于宿主「偏好设置 → 主题」页）。`ui.background(cfg)` 是它的同名别名。

```ts
ui.theme({
  enabled?: boolean,           // 默认 true
  seed?: "#RRGGBB",            // 可选：种子色，先按 Material 3 HCT 官方算法推导全套深浅色板
  dark?: boolean,              // 可选：true 深色 / false 浅色（同时决定系统状态栏图标深浅）；省略时按 colors.background 亮度自动判断，再省略则跟随宿主深浅色开关
  colors?: {                   // 可选：逐槽位精确覆盖（在 seed 推导结果之上生效，只接受 #RGB / #ARGB / #RRGGBB / #AARRGGBB）
    primary: "#141413",
    background: "#FAF9F5",
    // ...共支持全部 48 个 Material 3 颜色槽位
  }
})
```

**支持的全部 48 个颜色槽位名**（同时也可作为节点 `bg`、`color`、`border` 的语义色名引用）：
- **主色 / 次色 / 第三色**：
  `primary`、`onPrimary`、`primaryContainer`、`onPrimaryContainer`、`inversePrimary`、
  `secondary`、`onSecondary`、`secondaryContainer`、`onSecondaryContainer`、
  `tertiary`、`onTertiary`、`tertiaryContainer`、`onTertiaryContainer`
- **背景与表面层级**：
  `background`、`onBackground`、`surface`、`onSurface`、`surfaceVariant`、`onSurfaceVariant`、`surfaceTint`、
  `surfaceBright`、`surfaceDim`、
  `surfaceContainerLowest`、`surfaceContainerLow`、`surfaceContainer`、`surfaceContainerHigh`、`surfaceContainerHighest`、
  `inverseSurface`、`inverseOnSurface`
- **状态、描边与遮罩**：
  `error`、`onError`、`errorContainer`、`onErrorContainer`、`outline`、`outlineVariant`、`scrim`
- **Fixed 固定色槽（12 个）**：
  `primaryFixed`、`primaryFixedDim`、`onPrimaryFixed`、`onPrimaryFixedVariant`、
  `secondaryFixed`、`secondaryFixedDim`、`onSecondaryFixed`、`onSecondaryFixedVariant`、
  `tertiaryFixed`、`tertiaryFixedDim`、`onTertiaryFixed`、`onTertiaryFixedVariant`

### 2. `color.*` —— 内置颜色运算工具
引擎内置全局 `color` 对象，采用与 Jetpack Compose `lerp` 完全一致的 **Oklab 感知均匀色彩空间**运算，返回 `#AARRGGBB` 字符串：

| 函数签名 | 说明 |
|---|---|
| `color.mix(hexA, hexB, t)` | 在 Oklab 空间按比例 `t`（`0 ~ 1`）混合两种颜色 |
| `color.alpha(hex, a)` | 修改颜色不透明度 `a`（`0 ~ 1`），例如做半透明遮罩或渐变透明端 |
| `color.lighten(hex, t)` | 向纯白 `#FFFFFF` 混合 `t`（`0 ~ 1`）提亮 |
| `color.darken(hex, t)` | 向纯黑 `#000000` 混合 `t`（`0 ~ 1`）压暗 |
| `color.luminance(hex)` | 计算相对亮度（返回 `0 ~ 1` 数值，可用于自动判断深浅色：`color.luminance(bg) < 0.5`） |
| `color.hex(hex)` | 标准化任意简写 `#RGB` / `#RRGGBB` 为 `#AARRGGBB` |

```js
const baseBg = "#18181B";
const accent = "#D97757";
ui.theme({
  seed: accent,
  dark: color.luminance(baseBg) < 0.5,
  colors: {
    background: baseBg,
    surface: baseBg,
    surfaceContainerLow: color.lighten(baseBg, 0.04),
    surfaceContainer: color.lighten(baseBg, 0.08),
    primaryContainer: color.mix(baseBg, accent, 0.22),
    outlineVariant: color.alpha("#FFFFFF", 0.1),
  },
});
```

### 3. `ui.font(cfg)` —— 全局字体与文字阶梯
控制全 App 字体族、字号缩放及 30 个 Material Typography 文字样式。

```ts
ui.font({
  family?: "default" | "sans-serif" | "sans" | "serif" | "monospace" | "mono" | "cursive" | "chat" | "file:<相对路径>",
  scale?: number,              // 全局字号与行高缩放倍率（0 < scale <= 4，默认 1）
  letterSpacing?: number,      // 全局字间距（单位 em）
  styles?: {                   // 按样式名逐个覆盖
    [styleName]: {
      size?: number,           // 字号 sp（别名 fontSize）
      lineHeight?: number,     // 行高 sp
      weight?: number,         // 字重 1 ~ 1000（如 400 常规、500 中黑、600 半粗、700 粗体；别名 fontWeight）
      letterSpacing?: number,  // 字间距 em（别名 letterSpacingEm）
      family?: string,         // 单独指定该层级的字体（取值同上；别名 fontFamily）
      italic?: boolean,        // 是否斜体
    }
  }
})
```
- **自定义字体文件 `file:<路径>`**：相对于宿主内部存储 `filesDir` 的路径（例如 `"file:fonts/LXGWWenKai.ttf"`），文件不存在时安全回退默认字体。
- **30 个可用 `styleName`**：
  `displayLarge`、`displayMedium`、`displaySmall`、`headlineLarge`、`headlineMedium`、`headlineSmall`、`titleLarge`、`titleMedium`、`titleSmall`、`bodyLarge`、`bodyMedium`、`bodySmall`、`labelLarge`、`labelMedium`、`labelSmall`，以及每一项对应的 `...Emphasized` 版本（如 `titleSmallEmphasized`、`headlineMediumEmphasized`）。
- **与聊天字体的联动**：当用户在 App 设置里将「聊天字体」保持为「默认」时，聊天消息正文会自动跟随 `ui.font({ family })`！

### 4. `ui.shapes(cfg)` & `ui.motion(scheme)` —— 全局形状与动效

```js
ui.shapes({
  style: "rounded", // "rounded"（圆角）| "cut"（45° 切角机甲风：同时作用于宿主 Material 组件、ui.style 组件与自定义节点 corners / avatarCorner）
  extraSmall: 6,
  small: 8,
  medium: 10,
  large: 14,
  largeIncreased: 16,
  extraLarge: 18,
  extraLargeIncreased: 20,
  extraExtraLarge: 24,
});

// 动效物理曲线："expressive"（原版弹性灵动）| "standard"（克制平滑无回弹）
ui.motion("standard");
```

### 5. `ui.typography(cfg)` —— 消息正文排版
专门控制聊天消息 Markdown 的段落、行高、首行缩进与小说/台词语义染色（不写某项则保持原版）：

| 字段名 | 类型 | 说明 |
|---|---|---|
| `enabled` | `boolean` | 默认调用即 `true` |
| `fontSizeMultiplier` | `number` | 正文字号倍率（`0 ~ 4`） |
| `lineHeightMultiplier` | `number` | 行高相对字号倍率（如 `1.65`） |
| `blockSpacingDp` | `number` | 段落/块级元素间距 dp |
| `firstLineIndentSp` | `number` | 段落首行缩进 sp（写 `28` 约为两个中文字符缩进，适合轻小说排版） |
| `bodyTextColor` | `string` | AI 消息正文颜色（`#hex` 或语义色名） |
| `userBodyTextColor` | `string` | 用户消息正文颜色 |
| `quoteHighlightEnabled` | `boolean` | 是否开启引号台词染色（匹配 `“…”`、`「…」`、`『…』`） |
| `quoteTextColor` | `string` | 引号台词文字颜色 |
| `quoteFontWeightBold` | `boolean` | 引号台词是否加粗 |
| `italicTextColor` | `string` | `*斜体*` 文字颜色（适合心理描写/旁白染色） |
| `boldTextColor` | `string` | `**粗体**` 文字颜色 |
| `headingColor` | `string` | `# 标题` 文字颜色 |
| `blockquoteBarColor` / `blockquoteBgColor` | `string` | `> 引用块` 左侧竖条颜色 / 整块底色 |
| `inlineCodeColor` | `string` | `` `行内代码` `` 文字颜色 |

> 开启 `ui.typography` 后，消息 Markdown 中含内联 `padding / border / border-radius` 的 `<div style="...">` 会升级渲染为完整圆角边框盒子。

### 6. `ui.page(route, cfg)`、`ui.overlay(treeOrFn)` & `ui.transition(cfg)` —— 页面级定制、全局顶层前景层与转场动画
- `ui.page(route, cfg)`：
  - `route`：填宿主页面路由名（见第五章第 4 节，写错编译直接报错并列出全部合法名字），或填 `"*"` 作用于**除聊天页（`Chat`）以外的所有页面**。
  - `cfg` 支持四个字段：
    - `background`：铺在该页最底层的节点树（无论该页 `Scaffold` 使用 `background` 还是 `CustomColors.topBarColors` 作底色，都会自动变透明露出背景；列表上滑折叠时顶栏自动补半透明底色防叠字）；
    - `foreground`：叠在该页内容最上方的节点树（例如单页扫描线、暗角框或手势特效层）；
    - `colors`：仅在该页面内覆盖的 Material 3 颜色槽位对象；
    - `content`：整页替换节点树或渲染函数 `(data, ctx) => Node`（等同于 `ui.slot("page:" + route, ...)`）。
- `ui.overlay(treeOrFn)`（等同于 `ui.slot("overlay", treeOrFn)`，动态函数收到 `{ route }`）：
  - 挂载在全 App 根窗口最顶层（盖在聊天页、侧边栏、所有二级页面之上），未设置时零渲染零开销；
  - 纯绘制节点与 `Shader` 默认不拦截任何点击或滑动手势（也可显式加 `pointerEvents: "none"` 强制整棵子树手势穿透），专门用于实现**全屏指尖粒子爆发、全局 HUD 边框、飘雪/星尘前景层**。

```js
// 给除聊天页外的所有二级页面铺一层微暖渐变纸面背景
ui.page("*", {
  background: Box({ fillMaxSize: true, gradient: ["#FAF9F5", "#F2EFE6", 90] }, []),
});

// 自定义页面进出与手势返回转场（slideX / slideY 为屏幕宽高比例，如 0.12 = 12% 宽度）
ui.transition({
  push: {
    enter: { fade: true, slideX: 0.1, scale: 0.98, duration: 240 },
    exit:  { fade: true, slideX: -0.04, duration: 180 },
  },
  pop: {
    enter: { fade: true, slideX: -0.04, duration: 180 },
    exit:  { fade: true, slideX: 0.1, scale: 0.98, duration: 220 },
  },
});
```

---

## 三、宿主组件样式覆写：`ui.style(name, spec)`

当不想重写组件结构、只想改宿主现成组件的底色、圆角、边框、间距时使用 `ui.style(组件名, 属性对象)`。

**允许写的样式键**（写其他未知键会编译报错）：
- `bg`（别名 `background`）：背景色（`#hex`、`transparent` 或 48 个语义色名）
- `color`：主文字 / 图标颜色
- `secondaryColor`：副标题 / 次要内容颜色
- `corner`（别名 `corners` / `cornerDp`）：圆角半径 dp（数值）
- `border`：`[宽度dp, 颜色]`，例如 `[1, "#E5E3DA"]` 或 `[1, "outlineVariant"]`
- `padding` / `margin`：单个数值 `12`，或二元数组 `[水平dp, 垂直dp]`（注意：在 `ui.style` 里只能写 1 个或 2 个数）
- `fontSize`：字号 sp
- `opacity`：不透明度 `0 ~ 1`
- `width`：宽度或图标尺寸 dp
- `spacing`：子项间距 dp

**宿主内置读取 `ui.style` 的全部组件名与生效字段**：

| 组件名 | 宿主实际读取的属性 | 作用范围与特殊效果 |
|---|---|---|
| `topbar` | `bg`、`color` | 全 App 100+ 处二级页面顶栏颜色，且 **52 个页面的整页默认底色也跟随它的 `bg`** |
| `back_button` | `bg`、`color` | 全 App 60 处左上角返回按钮的圆底色与箭头色 |
| `list_item` | `bg`、`color`、`secondaryColor` | 通用列表项（含默认返回按钮底色、设置行默认色） |
| `card_group` | `bg`、`color`、`secondaryColor`、`corner`、`border`、**`spacing`** | 全 App 63 处设置卡片组。**不写 `spacing` 时各行连成整张卡片带细分割线；一旦写了 `spacing`（如 `spacing: 2`），各行自动拆分为独立分段圆角块** |
| `card_group.title` | `color`、`fontSize`、`padding` | 设置卡片组上方的小标题 |
| `card_group.icon` | `color`、`bg`、`corner`、`padding`、`width` | 设置行左侧图标（`width` 控制图标大小；写了 `bg` 会自动给图标包一层圆角底色块） |
| `card_group.value` | `bg`、`color`、`corner`、`padding`、`fontSize` | 设置行右侧胶囊数值框 |
| `card` | `bg`、`corner`、`border` | 助手列表、历史记录、收藏夹等二级页面的独立大卡片样式 |
| `search_bar` | `corner` | 助手列表、历史记录页顶部搜索框形状圆角/切角半径 |
| `tag` | `bg`、`color`、`corner`、`padding`、`fontSize` | 全 App 标签（`bg`/`color` 仅改默认型标签，保留成功/错误等状态色） |
| `chat.drawer` | `width`、`bg`、`corner` | 聊天左侧抽屉宽度（默认 300）、背景色与右侧圆角 |
| `chat.messages` | `padding`（取水平值）、`spacing` | 聊天消息列表左右内边距（默认 16）与消息上下间距（默认 12） |
| `conversation_item` | `bg`、`color`、`corner`、`padding`、`fontSize` | 侧边栏单条会话项（未用 `ui.slot` 接管时生效） |
| `conversation_empty` | `bg`、`color`、`corner`、`padding`、`margin`、`border`、`fontSize` | 侧边栏「暂无对话」空状态提示框 |
| `sort_button` / `folder_chip` | `bg`、`color`、`corner` / `corner` | 侧边栏排序图标按钮 / 文件夹筛选胶囊圆角 |
| `chat.jumper` | `bg`、`color`、`corner`、`border`、`opacity`、`spacing` | 聊天列表右侧（或左侧）快速上下翻页浮动按钮组（开启 `cut` 时自动变为切角） |
| `chat.suggestion` | `bg`、`color`、`corner`、`border`、`padding`、`fontSize`、`spacing` | 聊天输入框上方的建议回复胶囊条 |
| `reasoning` | `bg`、`color`（标题与灯泡图标）、`secondaryColor`（思考正文）、`corner`、`border`、`opacity` | 聊天消息中的思维链 / 工具调用折叠卡片 |
| `code_block` | `bg`、`corner`、`border` | Markdown 代码块外框与底色 |
| `bottom_sheet` | `bg`、`color`、`secondaryColor`（拖拽指示条色）、`corner`、`opacity`、`width`（拖拽条宽度，`0` 隐藏拖拽条）、`border`（顶部分割描边） | 全 App 40+ 处原生底部弹窗（`ModalBottomSheet`）；即使不写此项，只要配置了 `ui.shapes` 也会自动消除胶囊拖拽条与顶部圆弧 |
| `pager_tab` | `bg`（选中底色）、`color`（选中文字色）、`secondaryColor`（未选中底色）、`corner`、`spacing` | 全 App 分页切换按钮条（如扩展管理 `模式注入 / 世界书 / Skills / 快捷消息`、提示词页、设置子页等）；配置 `ui.shapes` 时自动消除未选中态胶囊圆头 |
| `connected_button` | `bg`（选中底色）、`color`（选中文字色）、`secondaryColor`（未选中底色）、`corner`、`spacing` | 全 App 连接式单选/多选按钮组（如联网搜索档位、供应商类型、昼夜模式等）；配置 `ui.shapes` 时自动消除左右半圆头与选中胶囊 |
| `switch` | `bg`（开启轨道色）、`color`（开启滑块色）、`secondaryColor`（关闭轨道色）、`corner`、`width`、`border` | 全 App 开关控件（含设置行、扩展列表、MCP 列表与自定义 `Switch` 节点）；配置 `ui.shapes` 时自动消除圆形滑块与胶囊轨道 |
| `attachment_tile` | `bg`、`color`（图标色）、`secondaryColor`（文字色）、`corner`、`border`、`padding`、`width`（图标尺寸） | 聊天 `+` 抽屉顶部的附件快捷方块（未单独配置时自动继承 `card` 与 `ui.shapes`） |
| `button` / `avatar` | `corner` / `corner` | 原生操作按钮圆角/切角半径 / 全局原生头像框（`UIAvatar` / `ModelIcon`）圆角/切角半径 |

---

## 四、UI 节点构造函数大全

在 `ui.slot` 和 `ui.page` 中，使用内置全局构造函数拼装原生 Jetpack Compose 界面树。

### 1. 构造函数签名总表

容器类函数全部兼容三种传参形式：`Column(props, [children])`、`Column([children])`、`Column(props)`。子节点数组中的 **`null`、`undefined`、`false` 会被自动忽略**，因此可以直接写 `d.pinned && Icon("pin")` 条件渲染！

| 构造函数 | 调用写法 | 说明 |
|---|---|---|
| `Column` | `Column(props?, children?)` | 垂直线性布局（支持子节点 `weight`、`scroll: "vertical"`） |
| `Row` | `Row(props?, children?)` | 水平线性布局（支持子节点 `weight`、`scroll: "horizontal"`） |
| `FlowRow` | `FlowRow(props?, children?)` | 自动折行流式布局（`spacing` 控制水平间距，`crossSpacing` 控制行间距） |
| `Box` | `Box(props?, children?)` | 层叠布局（后写的子节点叠在上面） |
| `Popup` | `Popup(props?, children?)` | 独立弹窗/浮层容器（`modal: true` 为居中模态对话框，`modal: false` 为按九宫格对齐的轻量浮层；支持 `onDismiss`） |
| `Surface`（别名 `Card`） | `Surface(props?, children?)` | 卡片表面容器（垂直排列子节点，消息槽内自动响应宿主气泡透明度设置） |
| `Collapsible` | `Collapsible(props?, children?)` | 自带展开/收起标题栏的折叠容器（专属属性：`title: "标题"`、`defaultExpanded: false`） |
| `Text`（别名 `CustomText`） | `Text("文本", props?)` | 文本节点。静态槽中支持 `"{{字段}}"` 模板插值 |
| `TextField` | `TextField(value?, props?)` | 原生文本输入框（支持 `placeholder`、`singleLine`、`onChange: (text, ctx) => ...`、`onSubmit: (text, ctx) => ...`） |
| `Switch` | `Switch(checked?, props?)` | 开关控件（支持 `checked`、`color`、`bg`、`onChange: (checked, ctx) => ...`） |
| `Slider` | `Slider(value?, props?)` | 连续/分段滑杆控件（支持 `value`、`min`、`max`、`steps`、`onChange: (val, ctx) => ...`） |
| `Icon` | `Icon("图标名", props?)` | 矢量图标（尺寸读 `size`，默认 `28`；颜色读 `color`） |
| `IconButton` | `IconButton("图标名", props?)` | 精确尺寸图标按钮（`size` 控制外框宽高默认 `28`，`iconSize` 控制内部图标大小默认 `18`） |
| `Button` | `Button("按钮文字", props?)` | 胶囊/圆角文字按钮（可选 `icon: "图标名"` 前置小图标） |
| `Badge` | `Badge("徽标文字", props?)` | 50% 全圆角小胶囊徽标 |
| `Image` | `Image(src, props?)` | 图片/动图节点。支持 `https://`、`data:image/...`、`file:<filesDir相对路径>`，原生支持 GIF、动态 WebP、SVG |
| `Path` | `Path(svgPathD, props?)` | 自定义 SVG 矢量路径绘制！支持 `viewBox: 24` 或 `[宽, 高]`、`strokeWidth`（`0` 为填充，大于 `0` 为描边）、`strokeCap: "butt" \| "round" \| "square"`、`color` |
| `Divider` | `Divider(props?)` | 水平细分割线（颜色读 `color`） |
| `Spacer` | `Spacer(1)` 或 `Spacer({ width, height })` | 弹性占位（传数字 `Spacer(1)` 等价于 `{ weight: 1 }` 撑满剩余空间）或固定宽高空白 |
| `Canvas` | `Canvas({ drawType, size, color })` | 基础几何图元：`drawType` 支持 `"circle"`（实心圆）或 `"ring"`（圆环） |
| `Shader` | `Shader(agslSrc, props?, children?)` | Android 13+ AGSL GPU 实时着色器背景容器（详见第六章） |
| `Video` | `Video(src, props?)` | 循环静音动态视频节点（支持 `https://` 或 `file:<相对路径>`，退后台自动暂停） |
| `Host` | `Host("部件名", props?)` | **嵌入宿主原版功能部件**（如 `Host("markdown_body")`、`Host("send_button")`）；传 `Host("original")` 代表放入该插槽的完整原版 UI |
| `LazyColumn` / `LazyRow` | `LazyColumn({ count 或 keys, item: (index, ctx) => 节点, ... })` | **懒加载列表**：只为看得见的行调用 `item`；支持分页 `onEndReached`、拖拽排序 `onReorder`、下拉刷新 `onRefresh`（详见第九章） |
| `Compose` | `Compose(类名, 函数名, [参数], props?)` | **按名字调用宿主的任意界面函数**（参数按位置传：值 / Java 对象 / 回调函数 / 节点树，`null` = 默认值；详见第九章） |
| `NativeView` | `NativeView(类名, props?)` | **放入任意安卓原生视图**（宿主创建后把句柄交给 `onCreate: (view, ctx) => ...`；详见第九章） |

> 快捷兼容简写：`Avatar(p)`、`AuthorName(p)`、`ModelIcon(p)`、`ModelName(p)`、`Timestamp(p)`、`BranchPager(p)`、`ReasoningBlock(p)`、`MarkdownBody(p)`、`Translation(p)`、`EditedFiles(p)`、`NerdStats(p)`、`ActionBar(["copy", ...], p)`、`FolderBar(p)`、`ConversationList(p)`、`AssistantPicker(p)` 均等价于对应的 `Host("同名", p)`。

### 2. 节点通用属性全表

> 引擎开启了**严格属性校验**：写了下表之外的未知属性名、颜色格式错误或枚举越界，编译时会立即报错指出具体节点与字段名。

| 分类 | 属性名（含可用别名） | 取值格式与说明 |
|---|---|---|
| **尺寸与权重** | `width`、`height` | 固定宽高 dp（数值） |
| | `fillMaxWidth`、`fillMaxHeight`、`fillMaxSize` | `boolean`。`fillMaxSize: true` 同时占满宽高 |
| | `maxWidth`（别名 `maxWidthFraction`） | `0.01 ~ 1` 占父容器宽度比例（如用户气泡常用 `maxWidth: 0.85`） |
| | `weight` | 在 `Row` / `Column` 中按比例分配剩余空间（如 `weight: 1`） |
| | `compact` | `boolean`。设为 `true` 时解除本节点及所有子节点的 Material 48dp 最小触控尺寸限制（做紧凑顶栏/底栏/侧栏必开） |
| | `scroll` | `"vertical"`（`"y"`）\| `"horizontal"`（`"x"`）。为容器开启原生滚动（适用于 `ui.page` 整页重写或横向工具条） |
| | `insets` | 系统安全区内边距组合（逗号/空格分隔）：`"status"`（状态栏）、`"nav"`（底部手势条）、`"ime"`（软键盘避让）、`"safe"`（状态栏+导航栏） |
| | `zIndex` | `number`。同层兄弟节点的绘制叠放优先级 |
| **边距与圆角** | `padding`、`margin` | 1 个数 `12`（四边）、2 个数 `[水平, 垂直]`、或 4 个数 `[左, 上, 右, 下]`（写 3 个数报错；`margin` 在 `width`/`height` 外侧生效） |
| | `corners`（别名 `corner`） | 1 个数 `16`（四角）、2 个数、或 4 个数 `[左上, 右上, 右下, 左下]`（如聊天气泡尖角 `[18, 18, 5, 18]`；开启 `ui.shapes({ style: "cut" })` 时自动变为斜切角） |
| | `clip` | `boolean`。默认有圆角/底色时自动裁剪；显式设 `clip: false` 可允许内部发光或角标溢出容器边界 |
| **排列对齐** | `align`（别名 `horizontalAlignment`） | `"start"`（`left`）\| `"center"` \| `"end"`（`right`）\| `"space_between"`（`between`）；在 `Box` / `Shader` / `Popup` 中与 `vAlign` 组合为九宫格对齐（未写时默认左上 `TopStart`） |
| | `vAlign`（别名 `verticalAlignment`） | `"top"` \| `"center"` \| `"bottom"` |
| | `spacing` / `crossSpacing` | 容器子元素间距 dp（默认 `4`）；`crossSpacing` 为 `FlowRow` 折行行距 |
| **背景与视觉** | `bg`（别名 `background`、`backgroundColorHex`） | `#hex`、`"transparent"` 或 48 个 Material 语义色名 |
| | `gradient` | `[起始色, 结束色]` 或 `[起始色, 结束色, 角度deg]`（0 度水平向右，90 度垂直向下，按组件实际尺寸投影） |
| | `border` | `[线宽dp, 颜色]`，例如 `[1, "#E5E3DA"]` |
| | `shadow`（别名 `shadowElevationDp`） | 阴影高度 dp |
| | `opacity` | **仅作用于背景色/渐变**的不透明度（`0 ~ 1`），不影响内部文字清晰度 |
| | `alpha` | **整个节点（含子节点文字图标）**的不透明度（`0 ~ 1`） |
| | `blendMode` | 图层混合模式：`"srcOver"` \| `"plus"`（`"add"` 加法发光叠加！）\| `"screen"` \| `"multiply"` \| `"overlay"` \| `"lighten"` \| `"darken"` |
| | `blur`（别名 `enableHazeBlur`） | `boolean`，聊天页毛玻璃背景模糊 |
| | `blurRadius` | 节点自身高斯模糊半径 dp（Android 12+） |
| | `offsetX`、`offsetY`、`scale`、`rotate` | 静态位移 dp、缩放倍率（默认 `1`）、旋转角度 deg |
| **文字与内容色** | `color`（别名 `textColor`） | 文字色，**同时设置 `LocalContentColor`，使子节点图标与 `Host` 原版按钮自动跟着变色** |
| | `fontSize`、`lineHeight`、`letterSpacing` | 字号 sp、行高 sp、字间距 em |
| | `fontWeight` / `bold` / `italic` | `fontWeight`: `1 ~ 1000` 精确字重；`bold`: `boolean`；`italic`: `boolean` |
| | `fontFamily`（别名 `family`） | `"default"` \| `"sans-serif"` \| `"serif"` \| `"monospace"` \| `"cursive"` \| `"chat"` \| `"file:..."` |
| | `maxLines`、`textAlign` | 最大行数（超出自动省略号 `...`）；对齐 `"start"` \| `"center"` \| `"end"` \| `"justify"` |
| **媒体与部件** | `contentScale` | `Image` / `Video` 缩放模式：`"crop"` \| `"fit"` \| `"fill"` \| `"fill_width"` \| `"fill_height"` \| `"none"` |
| | `size`、`iconSize`、`avatarCorner` | 头像/图标尺寸 dp、`IconButton` 内图标尺寸 dp、头像圆角 dp（默认圆形） |
| | `actionItems` | 仅用于 `Host("action_bar")`：指定要显示的消息操作按钮数组，可选值：`"copy"`、`"regenerate"`、`"edit"`、`"parts_editor"`、`"tts"`、`"translate"`、`"fork"`、`"favorite"`、`"webview"`、`"more"`、`"branch_selector"`、`"timestamp"` |
| **显隐与手势** | `visibleWhen` | 静态条件：填插槽数据字段名（如 `"has_usage"`、`"!loading"`、`"params.id"`）或 `"never"` |
| | `pointerEvents` | `"auto"`（默认）\| `"none"`（本节点及全部子节点完全穿透触摸，不阻挡底层点击与滑动） |
| | `haptic` | 交互物理震动反馈：`"tick"` \| `"click"` \| `"heavy"` \| `"confirm"` \| `"reject"` \| `"long_press"`（回调中也可调 `ctx.haptic("tick")`） |
| | `onClick`、`onLongClick`、`onDoubleClick` | 填插槽提供的动作名字符串、`"navigate:页面名"`，或在 `ui.slot` 内直接传 JS 函数 `(ctx) => { ... }` |
| | `onChange`、`onSubmit`、`onDismiss` | `TextField` / `Switch` / `Slider` 值变化回调 `(val, ctx) => ...`、输入提交回调、`Popup` 关闭回调 |
| | `swipe`、`onSwipe` | 滑动手势：`swipe` 写方向（`"left"` / `"right"` / `"horizontal"` / `"up"` / `"down"` / `"vertical"`）或 `{ direction, follow, dismiss, threshold }`；拖动由宿主跟手，松手越过阈值后回调一次 `onSwipe: (direction, ctx) => ...`（详见第九章） |
| | `zoom` | `true` 或 `{ min, max }`：双指缩放与平移，全部由宿主完成，不回调主题 |
| **动态效果** | `animate`、`press`、`wakeDuration` | 关键帧动画对象/数组、按压回弹配置、`Shader` 触控按需唤醒秒数（松手超时后自动降为 0 FPS 休眠，见第六章） |

### 3. 内置图标名映射表
`Icon("名称")` 与 `IconButton("名称")` 支持以下名称（不区分大小写，未知名称回退显示为拼图图标；更复杂的品牌图形请用 `Path` 绘制 SVG）：
- **AI 与扩展**：`sparkles` (`magic`, `star`), `ai` (`wand`), `brain` (`model`, `robot`, `subagent`), `assistant` (`persona`), `puzzle` (`plugin`), `package` (`extension`), `mcp`, `book` (`lorebook`, `preset`, `prompt`), `bolt` (`zap`, `quick`, `fast`)
- **开发与工作区**：`terminal` (`console`, `shell`), `sandbox` (`workspace`), `code` (`script`, `json`), `webdesign` (`html`, `panel`), `database` (`storage`, `memory`), `server` (`proxy`), `tools` (`tool`, `wrench`), `layers`
- **导航与方向**：`menu`, `more`, `close` (`cancel`), `back` (`arrow_left`), `arrow_right` (`forward`), `arrow_up`, `arrow_down` (`expand`), `add` (`plus`), `new_chat` (`chat_add`), `search` (`find`), `sort` (`sorting`)
- **沟通与操作**：`chat` (`message`), `edit` (`pen`), `copy`, `delete` (`trash`, `remove`), `refresh` (`reload`, `sync`), `share`, `download` (`save`), `check` (`tick`, `ok`), `pin`, `heart` (`favorite`), `translate` (`language`), `link` (`url`), `eye` (`view`), `play` (`run`, `start`)
- **媒体与系统**：`settings` (`config`, `gear`), `tune` (`sliders`), `palette` (`theme`, `paint`), `sun` (`light`), `moon` (`dark`), `bell` (`notification`), `user` (`account`), `key` (`secret`, `lock`, `auth`), `file` (`doc`, `document`), `folder` (`dir`), `image` (`photo`, `picture`), `camera`, `video` (`movie`), `voice` (`mic`, `asr`), `tts` (`speaker`, `audio`), `globe` (`web`, `network`), `history` (`clock`), `time`, `chart` (`stats`), `info` (`help`, `idea`), `warning` (`alert`, `error`)

---

## 五、插槽接管核心：`ui.slot(name, treeOrFn)`

### 1. 写法一：静态节点树
保存时一次性编译为静态结构，**滚动与流式输出期间零 JS 运行开销**。动态内容通过 `{{字段}}` 和 `visibleWhen: "字段"`（前加 `!` 取反）绑定：

```js
ui.slot("chat.topbar", Row({
  compact: true, fillMaxWidth: true, height: 52, padding: [6, 0], vAlign: "center", bg: "surface",
}, [
  Host("drawer_button"),
  Column({ weight: 1, align: "center", onClick: "edit_title" }, [
    Text("{{title}}", { fontSize: 15, fontWeight: 600, maxLines: 1 }),
    Text("{{model}}", { fontSize: 11, color: "onSurfaceVariant", visibleWhen: "model", maxLines: 1 }),
  ]),
  Host("follower_capsules"),
  Host("new_chat_button"),
]));
```

### 2. 写法二：动态渲染函数与状态回调
当需要用 JS 写三元分支、数组 `.map`、维护展开/折叠局部状态或调用数据桥时，传入函数 `(data, ctx) => Node | null`：
- **原样输出文本（不做二次 `{{变量}}` 替换）**：`{{字段}}` 模板插值**仅在写法一（静态节点树）中运行**；动态渲染函数在 JS 内直接通过 `d.xxx` 拼接字符串，输出的 `Text(...)` 原样绘制——因此直接展示含有酒馆宏（如 `{{user}}`、`{{char}}`）的助手系统提示词或会话标题完全安全，不会误触发“文字变量不存在”报错。
- **返回 `null` 或 `false`**：表示该条目不接管，直接渲染宿主原版 UI（例如在 `card_group.item` 里对复杂表单项 `if (it.form) return null;` 放行原版，只重排普通设置行）。
- **交互回调与局部状态更新**：在 `onClick` / `onLongClick` / `onDoubleClick` 中传入函数 `(ctx) => { ... }`。修改脚本内的 `let` 变量后，宿主会自动递增状态版本号，触发关联的动态插槽重新计算！
- **触发宿主动作 `ctx.action(动作名, ...参数)`**：
  - 调用当前插槽内置动作：`ctx.action("open")`、`ctx.action("edit_title")`；
  - 跳转任意无参或带参路由：`ctx.action("navigate", "Setting")` 或 `ctx.action("navigate", "AssistantDetail", { id: d.assistantId })`。

```js
let showDetailStats = false;

ui.slot("chat.message.assistant", (d, ctx) => Column({ fillMaxWidth: true, spacing: 8 }, [
  d.has_reasoning && Host("reasoning_block"),
  Column({ fillMaxWidth: true, onLongClick: () => ctx.action("open_action_sheet") }, [
    Host("markdown_body"),
  ]),
  d.not_loading_if_last && Row({ compact: true, fillMaxWidth: true, vAlign: "center" }, [
    Host("action_bar", { actionItems: ["copy", "regenerate", "more"] }),
    Spacer(1),
    d.has_usage && Text(d.tokens + " tok", {
      fontSize: 11, color: "onSurfaceVariant", padding: [6, 2], corners: 6,
      onClick: () => { showDetailStats = !showDetailStats; },
    }),
  ]),
  d.has_usage && showDetailStats && Host("nerd_stats", { fontSize: 11 }),
]));
```

### 3. 页面级大组件骨架重排与跨页直嵌：`chat.layout`

如果只想改颜色或局部按钮顺序，用局部插槽即可；如果要**彻底改变客户端的交互范式**（例如去掉左上角汉堡侧滑抽屉、把顶栏沉底变成单手拇指模式、把输入框移到顶部、做成底部多 Tab 切换应用、或平板多栏并排工作台），使用最外层骨架插槽 `ui.slot("chat.layout", ...)`：

- **父子插槽俄罗斯套娃嵌套（互不冲突、同时生效）**：
  ```text
  chat.layout（最外层页面骨架：决定大模块摆在哪、如何切换）
   ├── Host("background")  ──▶ chat.background（背景层，自动充当 Haze 毛玻璃采样源）
   ├── Host("topbar")      ──▶ chat.topbar（顶栏内部布局；点击其菜单按钮会自动切换 d.drawerOpen）
   ├── Host("messages")    ──▶ chat.message.* / chat.empty / chat.jumper / chat.suggestions / chat.loading
   ├── Host("input")       ──▶ chat.input（输入栏内部布局，完整保留附件面板、语音、斜杠补全）
   ├── Host("drawer")      ──▶ chat.drawer ──▶ conversation_item（会话与助手面板）
   └── Host("page:<路由>") ──▶ 直接在主页内同屏嵌入 Assistant / History / Favorite / Stats / Setting / Workspaces / Plugins / Skills
  ```
- **四项专为大组件换位设计的底层保障**：
  1. **消息列表滚动穿透留白**：`Host("messages", { fillMaxSize: true, padding: [左, 上, 右, 下] })` 不会把外层视口裁死，而是自动将 `padding` 映射为内部 `LazyColumn` 的 `contentPadding`（外层使用 `modifierWithoutPadding`）。这意味着把半透明毛玻璃 `Host("topbar")` 或 `Host("input")` 用 `Box` 悬浮叠在消息流上方时，消息滚动可以丝滑穿过毛玻璃底层，停下时又恰好避开遮挡区域。
  2. **系统安全区解耦**：在 `chat.layout` 内，`Host("topbar")` 与 `Host("input")` 不再写死原版的“顶部状态栏”或“底部手势条”留白，完全交由节点的 `insets`（`"status"`、`"nav"`、`"ime"`、`"safe"`）按实际摆放位置精准控制——把顶栏搬到屏幕最底部也不会多出一截空白。
  3. **抽屉彻底解除左侧 300dp 限制**：`Host("drawer")` 剥离了原版 `ModalDrawerSheet` 的宽度锁与单侧圆角，可任意放进右侧分栏、底部面板、居中 `Popup` 模态卡片或全屏 Tab；状态 `d.drawerOpen` 自动与系统返回键、切换对话收起、软键盘收起及 `Host("topbar")` 菜单键联动。
  4. **跨页直嵌自动隐去冗余返回键**：通过 `Host("page:Setting")` 等把二级主页面直接嵌进主页 Tab 或分栏时，宿主自动注入 `LocalUIEmbeddedPage` 隐藏无处可退的左上角返回按钮，而点击里面的子项仍可正常推入下一级页面。
- **实时预览联动**：编辑页的「实时预览沙盒」原生支持 `chat.layout` 骨架实时渲染与抽屉开关预览。

### 4. 全量插槽位置速查表

> 所有插槽均内置 `Host("original")`（代表该位置完整的宿主原版界面）以及通用导航动作 `"navigate:页面名"`。

| 插槽名 (`slot`) | 传入 `data` 字段 | 可放置的 `Host("部件名")` | 可触发的动作 (`action`) |
|---|---|---|---|
| `chat.layout` | `title`, `assistant`, `assistantId`, `model`, `provider`, `bigScreen`, `drawerOpen`, `previewMode`, `loading`, `hasMessages`, `messageCount`, `empty` | `background`, `topbar`, `messages`（`padding` 直接作为消息列表滚动区上下留白，支持消息滚过悬浮毛玻璃顶底栏下层）, `input`, `drawer`（解除原版左侧 300dp 限制，可放右侧栏/底栏/居中弹窗/全屏 Tab）, `page:Assistant`, `page:History`, `page:Favorite`, `page:Stats`, `page:Setting`, `page:Workspaces`, `page:Plugins`, `page:Skills` | `open_drawer`, `close_drawer`, `toggle_drawer`, `new_chat`, `toggle_preview` (`chat_options`), `open_files` (`more`), `stop`, `scroll_bottom`, `scroll_top` |
| `chat.topbar` | `title`, `assistant`, `model`, `provider`, `bigScreen`, `hasMessages`, `previewMode` | `drawer_button`, `title`, `subtitle`, `assistant_avatar`, `follower_capsules`, `plugin_buttons`, `options_button`, `new_chat_button` | `open_drawer`, `edit_title`, `chat_options`, `new_chat` |
| `chat.input` | `loading`, `empty`, `hasAttachments`, `voice`, `recording`, `asrAvailable`, `model`, `modelId`, `provider`, `searchEnabled`, `searchMode`, `canReason`, `reasoningLevel` | `queue_panel`, `voice_row`, `attachments`, `text_field`（`text` 改占位词）, `model_selector`, `search_button`, `reasoning_button`, `plugin_buttons`, `more_button`, `asr_button`, `send_button`（`color` 改发送键底色） | `send`, `send_without_answer`, `stop`, `more`, `asr`, `pick_model`, `pick_search`, `pick_reasoning` |
| `chat.more` | `assistant`, `assistantId`, `conversationId`, `model`, `isGoogle`, `canVoice`, `hasWorkspace`, `workspace`, `workspaceId`, `canTerminal`, `showCwd`, `cwd`, `subAgentEnabled`, `subAgentCount`, `subAgentSummary`, `hasMcp`, `mcpCount`, `extensionCount`, `messageCount` | `original`（完整原生底部弹窗）, `content`（不含外层弹窗的完整面板内容，可直接放进自定义 `Popup`）, `attachments`, `options`, `plugins` | `dismiss` (`close`), `take_photo` (`camera`), `pick_image` (`photo`), `sketch`, `pick_video` (`video`), `pick_audio` (`audio`), `pick_file` (`file`), `voice`, `open_workspace`, `open_workspace_detail`, `open_terminal`, `open_cwd`, `open_sub_agents`, `open_mcp`, `open_extensions`, `compress` |
| `chat.drawer` | `user`, `assistant`, `assistantId` | `avatar`, `author_name`, `folder_bar`, `conversation_list`, `assistant_picker`, `backup_reminder` | `new_chat`, `open_assistant_picker`, `edit_assistant`, `edit_nickname`, `create_folder` |
| `chat.message.user` / `chat.message.assistant` | `role`, `user`, `char`, `name`, `model`, `time`, `shortTime`（`HH:mm`）, `tokens`, `loading`, `lastMessage`, `favorite`, `branchIndex`, `branchCount`, `translated`；布尔简写：`not_empty`, `not_loading_if_last`, `when_loading`, `when_not_loading`, `only_last_message`, `has_branches`, `has_reasoning`（首段存在前置思考/工具链时为真）, `has_usage` | `avatar`, `author_name`, `model_icon`, `model_name`, `timestamp`, `branch_pager`, `reasoning_block`（仅首段正文之前的前置思考与工具链）, `markdown_body`（首段正文起按真实时间序穿插的正文、媒体与中途工具调用）, `message_parts`（全量部件按原始时间序一并渲染）, `translation`, `edited_files`, `nerd_stats`, `action_bar`, `plugin_footer` | `edit_message`, `open_parts_editor`, `open_action_sheet`, `copy_message`, `select_and_copy`, `regenerate`, `fork_message`, `toggle_favorite`, `translate_message`, `speak_tts`, `webview_preview`, `delete_message` |
| `conversation_item` | `id`, `title`, `pinned`, `selected`, `loading`, `updatedAt`, `children`, `childrenExpanded` | `original` | `open`, `menu`（弹出宿主长按菜单）, `toggle_children` |
| `conversation_empty` | — | `original` | — |
| `chat.reasoning.header` | `loading`, `title`, `seconds` | `title`, `duration` | —（外层展开/折叠仍由宿主接管） |
| `chat.tool.header` | `loading`, `pending`, `denied`, `title`, `tool`, `seconds` | `title`, `duration`, `approval_buttons` | `open`, `approve`, `deny` |
| `code_block.header` | `language`, `lines`, `previewMode`, `canPreview` | `language`, `save_button`, `copy_button`, `preview_toggle`, `web_preview_button` | `copy`, `save`, `toggle_preview` |
| `avatar` | `name`, `initial`, `kind` (`"image" \| "emoji" \| "dummy"`), `url`, `emoji`, `editable` | `original` | — |
| `avatar.edit_badge` | — | `original` | —（写空盒子 `Box({})` 即可全局隐藏头像右下角编辑小铅笔） |
| `chat.background` | `wallpaper`, `opacity`, `gradient` | `mesh_gradient` | — |
| `chat.empty` | `assistant`, `assistantId`, `model`, `user` | — | `ctx.action("fill_input", "提示词")`（新对话无消息时显示在聊天区中央的欢迎舱） |
| `chat.jumper` | `onLeft`, `firstVisible`, `totalItems` | `original` | `top`, `up`, `down`, `bottom` |
| `chat.suggestions` | `items`（建议词字符串数组） | `original` | `ctx.action("select", text)` |
| `chat.loading` | `status` | `original` | — |
| `overlay`（即 `ui.overlay`） | `route`（当前页面名） | — | —（全屏最顶层透明画布，适合指尖粒子爆发、全局 HUD 装饰） |
| `card_group.title` | — | `title` | — |
| `card_group.item` | `index`, `count`, `first`, `last`, `clickable`, `form`, `hasSupporting`, `hasLeading`, `hasTrailing`, `hasOverline` | `headline`, `supporting`, `overline`, `leading`, `trailing`, `form` | `click` |
| `back_button` | — | `original` | `back` |
| `page:<路由名>` / `page:*` | `route`, `params`（路由参数对象） | `original` | `back` |

### 5. 全量页面路由名一览表
可用于 `onClick: "navigate:路由名"`、`ctx.action("navigate", "路由名", params)` 以及 `ui.page("路由名", ...)`：
- **无参页面**：`History`（历史对话）、`Favorite`（收藏夹）、`MessageSearch`（全局消息搜索）、`Stats`（统计）、`Assistant`（助手列表）、`Translator`（翻译）、`ImageGen`（生图）、`MediaCreationSessions`、`Setting`（设置首页）、`SettingTheme`、`SettingPreferences`、`SettingPreferencesTheme`、`SettingPreferencesNotification`、`SettingPreferencesGeneral`、`SettingPreferencesUI`、`SettingPreferencesNetwork`、`SettingProvider`、`SettingModels`、`SettingSearch`、`SettingSpeech`、`SettingMedia`、`SettingMcp`、`SettingDonate`、`SettingFiles`、`SettingWeb`、`SettingAbout`、`Backup`、`Extensions`、`Skills`、`Workspaces`、`SecretKeys`、`Plugins`、`Log`、`Debug`
- **常用带参页面**（通过 `ctx.action("navigate", name, params)` 传参，在 `ui.page` 中通过 `d.params` 读取）：
  - `Chat` (`{ chatId? }`)、`AssistantDetail` / `AssistantBasic` / `AssistantPrompt` / `AssistantMemory` / `AssistantRequest` / `AssistantMcp` / `AssistantLocalTool` / `AssistantSubAgent` / `AssistantInjections` (`{ id }`)
  - `SettingProviderDetail` (`{ providerId }`)、`SettingSearchDetail` (`{ serviceId }`)、`SettingMcpDetail` (`{ serverId }`)
  - `UICustomize` / `Prompts` / `QuickMessages`、`PluginDetail` (`{ id }`)、`WorkspaceDetail` / `WorkspaceTerminal` (`{ id }`)、`SkillDetail` (`{ skillName }`)

---

## 六、动态视觉特效

宿主不写死任何固定特效模板，而是直接暴露底层渲染管线，未挂载特效的节点零额外开销。

### 1. 关键帧动画 `animate`（支持任意节点，可传单个对象或数组）
```ts
{
  prop: "alpha" | "scale" | "scaleX" | "scaleY" | "rotate" | "rotateX" | "rotateY" | "translateX" | "translateY" | "gradientAngle" | "bg" | "color",
  from?: number | string,      // 二帧简写起点（与 to 配合）
  to?: number | string,        // 二帧简写终点
  values?: (number | string)[],// 多关键帧序列，例如 [1, 0.85, 1] 或 ["#D97757", "#7C3AED", "#D97757"]
  times?: number[],            // 各关键帧时间点（0 ~ 1，长度与 values 一致；省略则均分）
  duration?: number,           // 单次时长 ms（默认 1000）
  delay?: number,              // 延迟启动 ms（默认 0）
  repeat?: number | "infinite",// 播放次数（默认 1，有限次数播完自动停表省电；无限循环写 "infinite"）
  alternate?: boolean,         // 偶数轮是否反向往返播放
  easing?: "linear" | "ease" | "easeIn" | "easeOut" | "easeInOut" | "cubic-bezier(x1, y1, x2, y2)",
}
```
> 性能机制：几何变换走 GPU `graphicsLayer`，背景渐变旋转走 `drawBehind`，文字变色走 `ColorProducer`——全部**只重绘、不触发 Compose 重组**！

### 2. 按压物理回弹 `press`
```js
ui.slot("back_button", IconButton("back", {
  size: 36, iconSize: 20, onClick: "back",
  press: { scale: 0.9, alpha: 0.7, duration: 110 },
}));
```

### 3. AGSL 实时像素着色器 `Shader(agslSrc, props?, children?)`（含多连击队列、后处理折射与 0 FPS 空闲休眠）
在 Android 13+（API 33+）上编译为原生 `RuntimeShader`；Android 12 及以下或编译报错时自动优雅渲染 `fallback` 备用节点与正常子节点。
- **两种渲染模式（自动识别）**：
  1. **生成式画布模式（默认）**：铺满节点底部绘制像素（配合 `ui.overlay` + `blendMode: "plus"` 可做全屏发光粒子层）；
  2. **屏幕/子内容实时后处理滤镜模式（声明 `uniform shader content;`）**：自动切换为 `RenderEffect.createRuntimeShaderEffect`，通过 `content.eval(coord)` 对内部包裹的真实界面画面做实时水波折射、RGB 色散故障、CRT 曲面畸变！
- **宿主自动注入的内建 Uniform（源码里声明了才会绑定更新，零多余开销）**：
  - `uniform float2 iResolution;` —— 节点实际像素宽高（px）
  - `uniform float iTime;` —— 运行秒数（未声明 `iTime` 时只画静态首帧）
  - `uniform float3 iTouch;` —— 当前手指交互坐标 `(x像素, y像素, 按下为1/松开为0)`（全屏无损穿透观察，不拦截任何点击和列表滑动）
  - `uniform float4 iClicks[N];`（`N` 取 `1 ~ 16`，常用 `8`）—— **最近 `N` 次屏幕点击环形队列**（下标 `0` 最新，支持多指同时连点）！每项为 `(localX, localY, tapTimeSec, randomSeed01)`，未触发槽位的 `tapTimeSec` 为 `-10000.0`，用 `float age = iTime - iClicks[i].z;` 即可在松手后继续演算完整的粒子抛射、重力下坠与扩散冲击波！
  - `uniform float4 iTrail[N];`（`N` 取 `1 ~ 16`）—— **最近 `N` 个手指滑动轨迹采样点** `(localX, localY, sampleTimeSec, strength)`，轻松绘制顺滑跟手彗星拖尾。
- **按需唤醒省电开关 `wakeDuration`（秒）**：
  - 例如设 `wakeDuration: 1.1`：手指未触屏或松手超过 `1.1 秒`（粒子已消散）后，宿主画完最后一帧干净画面即**自动挂起帧循环进入真 · 0 FPS 休眠（0% CPU / 0% GPU）**，下次手指触屏瞬间零延迟唤醒！
- **自定义参数 `uniforms`**：支持 `number`（`float`）、`[1~4个数字]`（`float`~`float4`/`half4`）、`"#hex"`（自动拆解为 `[r, g, b, a]` 归一化 `0~1` 向量，编译期严格校验变量名与维数）。

```js
// 全屏指尖连击爆裂火花前景层（平时 0 FPS 零耗电，点哪爆哪，100% 不挡底层点击与滑动）
const tapBurstShader = `
  uniform float2 iResolution;
  uniform float iTime;
  uniform float4 iClicks[8];
  uniform half4 uAccent;
  half4 main(float2 p) {
    float3 acc = float3(0.0);
    for (int i = 0; i < 8; i++) {
      float age = iTime - iClicks[i].z;
      if (age > 0.0 && age < 0.85) {
        float2 d = p - iClicks[i].xy;
        float r = length(d);
        float ring = (1.0 - smoothstep(0.0, 3.0, abs(r - age * 220.0))) * (1.0 - age / 0.85);
        acc += float3(uAccent.rgb) * ring;
      }
    }
    float a = clamp(max(acc.r, max(acc.g, acc.b)), 0.0, 1.0);
    return half4(half3(acc), half(a));
  }
`;

ui.overlay(Shader(tapBurstShader, {
  fillMaxSize: true,
  pointerEvents: "none",
  blendMode: "plus",
  wakeDuration: 0.9,
  uniforms: { uAccent: "#F59E0B" },
}));
```

### 4. 动态视频壁纸 `Video(src, props?)`
基于 ExoPlayer + TextureView，默认 `{ loop: true, muted: true, contentScale: "crop" }`，App 切到后台自动暂停解码：
```js
ui.slot("chat.background", Box({ fillMaxSize: true }, [
  Video("file:wallpapers/ambient.mp4", { fillMaxSize: true, contentScale: "crop" }),
  Box({ fillMaxSize: true, bg: "#000000", opacity: 0.3 }, []),
]));
```

---

## 七、原生数据桥与正式包 R8 反射铁律

在 `ui.slot` 或 `ui.page` 的**渲染函数与手势回调内部**，主题无需声明任何清单权限即可通过反射桥读写/调用宿主对象（导入他人主题前请审阅代码）：

### 1. 全局 `host` 与 `java` 接口
| 接口 | 说明 |
|---|---|
| `host.context()` | 获取 Android `Application` 实例（如读写 `SharedPreferences`、访问文件目录） |
| `host.activity()` | 获取当前前台 `Activity` |
| `host.koin()` / `host.get(类名, qualifier?)` | 从宿主 Koin 容器获取任意单例服务（如 `SettingsStore`、`MemoryRepository`、`ConversationRepository`、`FilesManager`） |
| `java.use(类名)` / `java.new(类名, ...args)` | 加载 Java/Kotlin 类（内部类用 `$`，如 `Avatar$Emoji`；顶层文件函数类用 `*Kt`）、构造实例；拿到对象后直接 `obj.method(...)` 同步调用 |
| `obj.$call("方法名", ...args)` | 显式按名调用实例或静态方法（当方法名与 JS 内置属性重名或需展开参数数组 `...args` 时使用） |
| `obj.$get("字段")` / `obj.$set("字段", 值)` | 读写实例或静态字段（含 `private` 成员！注意：**静态字段必须用 `Cls.$get("字段")`**，直接写 `Cls.FIELD` 会被当作调用同名静态方法） |
| `java.copy(obj, patch)` / `obj.$copy(patch)` | **无损浅拷贝 Kotlin `data class` 实例**：传入字段补丁对象（如 `java.copy(cur, { chatModelId: nextId, enableAutoTitle: true })`），支持一次修改单个或多个字段、支持继承自父类的属性（如 `ProviderSetting` 子类的 `enabled`）；传 `null` 可置空可空字段，传 `undefined` 自动跳过该字段；写错字段名或向基本类型传 `null` 会直接报错并列出全部合法字段名 |
| `obj.$json()` / `obj.$members()` | 将 Java 集合/Map 深度转为普通 JS 对象 / 列出类全部方法与字段签名 |
| `obj.$main("方法名", ...args)` | 切到 Android 主线程执行并同步返回结果（⚠️ **只能在点击/视图回调里调用**，切勿在首次同步渲染函数里调 `$main` 以免互锁） |
| `java.proxy(接口或数组, 实现对象或函数, opts?)` | 动态实现任意 Java/Android 接口（如 `Runnable`、`Comparator`）！支持原样返回回调入参（如 `(cur) => cur`）或返回新实例；默认回调触发后自动刷新插槽，高频回调可传 `{ async: true, autoInvalidate: false }` |
| `java.array(类型名, jsArray)` | 构造指定元素类型的 Java 原生数组（如 `java.array("java.lang.String", ["a", "b"])`） |
| `setTimeout` / `setInterval` / `ui.invalidate()` | 运行时定时器（预设停用或切换时自动全部销毁；定时器函数显式 `return false` 时不自动递增重绘版本号）与手动触发动态插槽重算 |
| `java.keep(obj)` / `java.release(obj)` | **句柄生命周期管理**：① 渲染函数内创建的句柄默认随该条缓存淘汰且离开屏幕后释放；② 宿主回调传入的临时 Java 对象在返回值解码完成后自动释放（防内存泄漏），且 `Color` / `Dp` 值类参数自动拆箱为 `"#RRGGBB"` 与数值；③ **凡是要存进顶层 `let` 变量跨次复用的宿主对象，必须包一层 `java.keep(obj)`**！ |

### 2. 响应式订阅 `ctx.watch(stateObj)`
向 `ctx.watch(...)` 传入宿主的 Kotlin `StateFlow`、`Flow` 或 Compose `State` 对象，它会立即返回当前值，并在宿主状态变化时**自动触发当前插槽重新渲染**：

```js
let settingsStore = null;

ui.slot("chat.drawer", (d, ctx) => {
  if (!settingsStore) {
    try {
      settingsStore = java.keep(host.get("me.rerere.rikkahub.data.datastore.SettingsStore"));
    } catch (_) {
      return null;
    }
  }
  const settings = ctx.watch(settingsStore.getSettingsFlow());
  const ext = java.use("me.rerere.rikkahub.data.datastore.SettingsExtKt");
  const model = ext.getCurrentChatModel(settings);
  const modelName = model ? String(model.getDisplayName() || model.getModelId()) : "未选模型";

  return Column({ compact: true, fillMaxWidth: true, fillMaxHeight: true, padding: 14, spacing: 8 }, [
    Text(d.user + " · " + modelName, { fontSize: 14, fontWeight: 500, maxLines: 1 }),
    Host("conversation_list", { weight: 1, fillMaxWidth: true }),
  ]);
});
```

### 3. 五条正式包 R8 反射铁律（写整页替换必看，避免真机回退原版！）

正式版 APK 开启了 R8 Full Mode 优化，保留名单为 **宿主自身包名 `me.rerere.**`（不含图标库 `me.rerere.hugeicons.**`）、`kotlin.uuid.**` 以及 Android 系统 SDK（`java.**` / `android.**`）**，并开启了全模块 `-java-parameters`（`MethodParameters` 参数名元数据保留）。编写二级页面与自定义调配面板时请严格遵守以下五条铁律：

1. **铁律一：严禁直接反射未保留的第三方库包名（如 `org.koin.**`、`kotlinx.coroutines.**`），取服务走 `host.get(...)`，读状态流走 `ctx.watch(...)`**：
   - 未保留的第三方库在正式包中会被 R8 裁剪或改名：例如直接调 `org.koin.core.context.GlobalContext` 或对 `StateFlow` 直接调 `.getValue()` / `.setValue()` 会抛 `NoSuchMethodException`。
   - **正确写法**：取单例服务用 `try { store = java.keep(host.get("me.rerere.rikkahub.data.datastore.SettingsStore")); } catch (_) { return false; }`；读取 `StateFlow` / `Flow` 当前值（无论在渲染函数还是点击回调 `onClick: (ctx) => ...` 内）一律调宿主桥 `ctx.watch(flow)`；写状态走下方第五条的 ViewModel 方法。
2. **铁律二：修改 Kotlin `data class`（如 `Settings`、`Assistant`、`DisplaySetting`、`ProviderSetting` 子类）时，严禁调用 `component1()..componentN()` 或自行按 `getDeclaredFields()` 拼参，一律使用内置 `java.copy(instance, patch)`（或 `instance.$copy(patch)`）**：
   - 宿主保留规则为 `-keep,allowshrinking`，未使用的 `component29()` 会被 R8 裁掉；同时 **Android 真机 ART 虚拟机的 `Class.getDeclaredFields()` 会强制将对象引用字段按字典序前置、基本类型字段后置**，顺序不等于主构造函数顺序，手写反射拼参在电脑单测能过、上真机必报错！
   - 宿主内置的 `java.copy(instance, patch)` 直接读取编译期保留的 `MethodParameters` 精确对齐主 `copy(...)` 参数顺序，支持单字段、多字段、嵌套对象拷贝与可空字段置 `null`：
   ```js
   // 在点击回调内通过 AssistantVM.updateSettings 安全修改单个、多个或嵌套字段
   function applyCarrierPatch(assistantVm, nextUuid) {
     assistantVm.updateSettings((cur) => java.copy(cur, {
       chatModelId: nextUuid,
       enableAutoTitle: true,
       displaySetting: java.copy(cur.getDisplaySetting(), { bubbleOpacity: 0.9 }),
     }));
   }
   ```
3. **铁律三：Kotlin 顶层属性/顶层函数编译在 `文件名Kt` 类上，静态字段必须用 `.$get("字段名")` 读取，枚举名用 `e.name()` 读取**：
   - 例如 `DefaultSettings.kt` 里的顶层属性 `DEFAULT_ASSISTANTS_IDS`，调用其 Getter 为 `java.use("me.rerere.rikkahub.data.datastore.DefaultSettingsKt").getDEFAULT_ASSISTANTS_IDS()`；
   - 读取类的 `Companion` 或单例 `INSTANCE` 时，必须写 `Cls.$get("Companion")` / `Cls.$get("INSTANCE")`（直接写 `Cls.Companion` 会被当作调用同名静态方法）；读取枚举常量标识符统一用 `String(enumVal.name())`（`java.lang.Enum.name()`）。
4. **铁律四：区分 `java.util.List` 与 Java 原生数组 `T[]` 的遍历方式，且列表渲染缓存不要跨轮次持有临时句柄**：
   - 宿主模型的集合属性（如 `settings.getAssistants()`、`provider.getModels()`）均为 `java.util.List`，用 **`list.size()`** 与 **`list.get(i)`** 遍历；需要可变副本时用 `java.new("java.util.ArrayList", list)` 或 `java.new("java.util.LinkedHashSet", set)`；
   - Java 反射接口返回的原生数组（如 `enumCls.getEnumConstants()`）不是 JS 数组，没有 `.length`，必须用 `const JArr = java.use("java.lang.reflect.Array"); JArr.getLength(arr)` 与 `JArr.get(arr, i)` 读取；
   - 在 `LazyColumn` 等跨帧/跨行组件里传递目标条目时，建议在 JS 对象里存纯字符串 ID（如 `uuidStr`），点击时通过 `Uuid.Companion.parse(uuidStr)` 或从最新 `cur` 中查找，避免面板收起后引用已淘汰的旧渲染句柄。
5. **铁律五：不要在 JS 里直接同步调用 Kotlin `suspend` 挂起函数，改调宿主 ViewModel 封装好的非挂起方法**：
   - Kotlin `suspend` 方法（如 `SettingsStore.update`）在字节码末尾多一个 `Continuation` 参数，不能从 JS 直接调用；
   - 宿主各页面的 ViewModel（如 `AssistantVM.updateSettings(fn)`、`SettingVM.updateSettings(fn)`、`AssistantVM.addAssistant(a)`、`HistoryVM.deleteConversation(c)`，完整签名见第十章）内部已在协程作用域封装好异步落盘，JS 直接同步调用即可！

> **边界说明**：主题运行时支持完整的 `java.use` / `java.new` / `java.proxy` 接口代理与定时器；仅进程级 ART 方法拦截 `rikka.hook` 保留在 `.zip` 插件系统中（需声明 `hook` 权限）。

---

## 八、实战进阶技巧与常见排坑指南

### 二级页面能改到什么程度（当前边界）

- **不重写页面时**：二级页面自动跟随 `ui.theme` / `ui.font` / `ui.shapes`、`ui.slot("avatar")`、`ui.slot("back_button")`、`ui.slot("card_group.*")` 以及第三章列出的全套共用组件样式（包括底部弹窗 `bottom_sheet`、分页切换条 `pager_tab`、连接式按钮组 `connected_button`、开关 `switch`、设置分段项等均已联动 `ui.shapes` 与 `ui.style`）；仅系统 `AlertDialog` / `DropdownMenu` 等少量原生弹窗结构保持宿主原貌。
- **要彻底变样就整页重写**：`ui.page(名, { content })` 对任何页面都可用，数据与动作通过第七章的 `java` / `host` 读写（完整宿主模型与方法名直接查第十章字典）。大标题用的是 `ui.font` 的 `displaySmall` / `headlineMedium` 这几级样式，不重写也可以先把它们改小改字体。
- **整页重写所需的三项底座已补齐**（引擎版本 14，见第九章）：
  1. 长列表用 `LazyColumn` / `LazyRow`，只算看得见的行，可分页、拖拽排序、下拉刷新；
  2. 手势有滑动（`swipe` + `onSwipe` 必须成对编写，可做左滑删除、左右滑切换分页）与双指缩放（`zoom`）；
  3. 终端视图、网页视图、二维码、酒馆导入器、模型选择器、视频播放器等宿主控件，用 `Compose(类名, 函数名, [参数])` 或 `NativeView(类名)` 按名字放进自己画的页面（现成控件签名见第十章第 4 节）。
- **仍然做不到的**：把 Material 原生控件（底部弹窗、下拉菜单等）原样借来用——它们在正式包里按名字找不稳，用主题自己的节点画（如 `Popup`、`Switch`、`Slider`、`TextField`）。
- **六个容易踩的真机细节（必看）**：
  1. **`Host("部件名")` 只认当前插槽支持的部件，写错会触发单点容错导致整块回退原版**：`ui.page` 内部**只支持 `Host("original")`**，千万不要在 `ui.page` 里写 `Host("back_button")`（返回键直接写自绘按钮配 `onClick: "back"` 即可）；同理 `card_group.item` 里多数行没有 `overline`（先判断 `it.hasOverline` 再放 `Host("overline")`），遇到 `if (it.form) return null;` 直接放行原版表单最稳妥；
  2. **在 `chat.layout` 里同屏切换助手/设置等主视图时，若已重绘对应页面，请直接调用抽出的 JS 渲染函数而非 `Host("page:Setting")`**：`Host("page:路由名")` 嵌入的是宿主原版页面 Composable，不会经过 `ui.page` 路由装饰器；把渲染逻辑抽成 `renderSettingView(d, ctx, embedded)` 同时供 `chat.layout` 内联调用和 `ui.page("Setting", ...)` 注册，主界面 Tab 与独立路由都能保持 100% 统一外观；
  3. **动态插槽 `(d, ctx) => ...` 每次重绘时 `d` 都是 `JSON.parse` 新生成的对象，严禁写 `activeMsg === d` 做对象引用相等判断**！若点击消息的 `⋮` 按钮展开自定义 `Popup` 或统计条，必须拼出稳定的字符串特征键（如 `const key = d.role + "|" + d.time + "|" + d.tokens + "|" + d.branchIndex; activeKey === key`），否则重绘后 `activeMsg === d` 恒为 `false` 导致弹窗打不开；
  4. **`ui.page(route, { background, foreground, content })` 中只有 `content` 支持函数 `(d, ctx) => Node`，`background` 和 `foreground` 必须直接传静态节点树**（如 `Shader(...)` 或 `Box(...)`）；
  5. **带 `iClicks` + `wakeDuration` 的指尖粒子 `Shader` 防休眠残影**：务必校验 `iClicks[i].z > 0.01`，且让 `wakeDuration` 比单次粒子寿命宽出至少 `0.3s`（如粒子 `age < 0.70` 消散、`wakeDuration: 1.25`），确保进入 0 FPS 休眠前最后一帧 100% 清空透明；
  6. 带删除功能的分页 `LazyColumn` 生成行 `id` 时务必使用单调递增计数器（`let nextId = 0; "row-" + (nextId++)`）或拼接渠道前缀（`pid + "::" + modelUuid + "::" + idx`），切勿用 `rows.length` 或可能跨渠道重复的裸 ID，避免重复 `keys` 触发容错回退；模态 `Popup({ modal: true })` 是居中对话框，贴边抽屉直接在 `chat.layout` 根 `Box` 叠放更可控。

编写深度定制主题时，以下几个宿主细节最值得注意：

1. **接管用户消息（`chat.message.user`）时务必显式放置操作栏，不要只绑外层双击**：
   - 宿主 `Host("markdown_body")` 内部需要处理文本选择与链接点击，会优先消费触摸事件；如果把气泡底下的操作栏删掉、只在外层 `Surface` 上写 `onDoubleClick: "edit_message"`，真机上双击正文经常无法触发，用户会发现「编辑、重发」功能全丢了。
   - **正确做法**：在用户气泡下方（`d.not_loading_if_last` 时）显式排布轻量操作条（绑定 `edit_message`、`regenerate`、`copy_message`、`open_action_sheet` 以及 `d.has_branches && Host("branch_pager")`），或放置 `Host("action_bar")`。
2. **避免 `Row` 里 `Spacer(1)` 把多元素组件挤成一坨**：
   - 在 Compose 的 `Row` 中，固定宽度的兄弟会优先测量，剩下的窄空间才分给其他组件。宿主的 `Host("nerd_stats")` 内部是可换行的 `FlowRow`（含输入/缓存/输出/速率四项，总宽约 280dp），若跟操作栏按钮挤在同一行，会在右侧半边屏幕折成两三行。
   - **正确做法**：同行右侧只放单行 `Text(d.tokens + " tok", { maxLines: 1 })`，用点击回调切换 `showFullStats` 状态，把 `Host("nerd_stats")` 放在**下一行独立占满宽度**展开。
3. **如何让「AI 聊天正文用衬线体，而用户气泡和全 App 按钮列表用无衬线体」？**
   - 宿主 `Host("markdown_body")` 读取的是 `ChatFontProvider`（当设置里聊天字体为「默认」时取 `ui.font({ family })`）。
   - 因此只需设 `ui.font({ family: "serif", styles: { bodyLarge: { family: "sans-serif" }, ... } })` 把 30 个常规 UI 槽位显式设回 `"sans-serif"`，并在 `chat.message.user` 的容器上加 `fontFamily: "sans-serif"`，即可实现纯正的「无衬线提问 + 衬线回复 + 清秀无衬线设置页」。
4. **如何统一全 App 设置页分组图标颜色？**
   - 宿主部分页面（如设置首页的「模型与服务」）会给 `CardGroupIcon` 显式传入 `onTertiaryContainer` 等语义色，优先级高于 `ui.style("card_group.icon")`。在 `ui.theme({ colors })` 中把 `onPrimaryContainer`、`onSecondaryContainer`、`onTertiaryContainer` 统一设为同一种中性图标色，即可彻底消除杂色图标。
5. **如何彻底洗掉原版「Rikka 味」图标、空状态与聊天二级弹窗？**
   - **轻量改法（复用原版弹窗）**：在 `ui.slot("chat.input", (d, ctx) => ...)` 中不放原版 `Host("model_selector")` 等粗线条按钮，改为读取 `d.model`、`d.searchEnabled`、`d.canReason`、`d.reasoningLevel` 自绘按钮并绑定 `ctx.action("pick_model")` / `pick_search` / `pick_reasoning`。
   - **重度沉浸改法（连二级弹窗一并彻底重绘）**：虽然原生底部弹窗已支持 `ui.style("bottom_sheet")` 与切角联动，但模型卡片里的彩色品牌图标/状态色 `Tag` 与弹窗布局仍为原版结构。若主题追求极致档案风或蒸汽机甲风，可直接在 `chat.layout` 根 `Box` 叠放自定义调配层（`+` 面板用 `ui.slot("chat.more", ...)`，消息菜单用插槽内 `Popup({ modal: true })` 调用 `edit_message` / `open_parts_editor` / `fork_message` 等全部原生动作；抽屉内不放 `Host("assistant_picker")` 改为自绘助手切换列表），并通过 `AssistantVM.updateSettings((cur) => java.copy(cur, ...))` 直接改写当前 `Assistant` 的 `chatModelId`、`reasoningLevel`、`enableWebSearch` 与 `Settings.favoriteModels`（所有新旧对话均实时跟随其所属 `Assistant` 的最新配置）。
   - **新对话白屏与右侧灰圆球**：同时接管 `ui.slot("chat.empty", ...)`（用 `ctx.action("fill_input", prompt)` 做灵感起手卡片）和 `ui.slot("chat.jumper", ...)`（替换右侧三个默认圆形浮动球），并定制 `ui.slot("back_button", ...)`，整体感立竿见影。
6. **用 `ui.slot("card_group.item")` 精准改造特定设置项**：
   - 普通跳转行可统一重排紧凑间距、给左侧 `Host("leading")` 包一层圆角底框并补上右侧引导箭头；遇到包含滑杆/输入框的通用表单行时，一句 `if (it.form) return null;` 即可无缝回退宿主原版表单渲染。
7. **用 `ui.slot("chat.layout")` 彻底重排大组件空间关系与交互范式**：
   - `chat.layout` 把原本写死的「左抽屉 + 上顶栏 + 中消息 + 下输入栏」拆解为自由组合的原生容器，且**内部子插槽（`chat.topbar`、`chat.input`、`chat.drawer`、`chat.message.*`）照常嵌套生效**。
   - `Host("topbar")` 与 `Host("input")` 在 `chat.layout` 内不会硬塞状态栏或底部手势条留白，改由节点的 `insets`（如 `"status"`、`"nav,ime"`、`"safe,ime"`）按实际摆放位置精准控制；`Host("messages", { padding: [0, 顶部留白, 0, 底部留白] })` 会将 `padding` 映射为列表滚动区的 `contentPadding`，让消息既能滑过半透明毛玻璃悬浮栏底部、停下时又不被遮挡；点击 `Host("topbar")` 里的菜单按钮会自动切换 `d.drawerOpen`。

```js
// 示例 A：单手拇指底栏模式（消息置顶沉浸阅读 + 输入框与标题栏合并沉底 + 右侧滑出会话面板）
ui.slot("chat.layout", (d, ctx) => Box({ fillMaxSize: true }, [
  Host("background"),
  Column({ fillMaxSize: true, insets: "status" }, [
    Host("messages", { weight: 1, fillMaxWidth: true }),
    Column({ fillMaxWidth: true, bg: "surfaceContainerLow", blur: true, insets: "nav,ime" }, [
      Host("input"),
      Divider(),
      Host("topbar"),
    ]),
  ]),
  d.drawerOpen && Popup({ modal: true, align: "end", vAlign: "center", onDismiss: () => ctx.action("close_drawer") }, [
    Host("drawer", { width: 310, fillMaxHeight: true, bg: "surfaceContainer", insets: "safe", corners: [20, 0, 0, 20] }),
  ]),
]));
```

```js
// 示例 B：去侧滑化 —— 改造为底部四 Tab 主应用（对话 / 会话 / 助手 / 设置同屏切换）
let activeTab = "chat";

ui.slot("chat.layout", (d, ctx) => Column({ fillMaxSize: true }, [
  Box({ weight: 1, fillMaxWidth: true }, [
    activeTab === "chat" && Box({ fillMaxSize: true }, [
      Host("background"),
      Column({ fillMaxSize: true, insets: "status,ime" }, [
        Host("topbar"),
        Host("messages", { weight: 1, fillMaxWidth: true }),
        Host("input"),
      ]),
    ]),
    activeTab === "sessions" && Host("drawer", { fillMaxSize: true, insets: "status" }),
    activeTab === "assistants" && Host("page:Assistant", { fillMaxSize: true }),
    activeTab === "settings" && Host("page:Setting", { fillMaxSize: true }),
  ]),
  Row({
    compact: true, fillMaxWidth: true, height: 56, bg: "surfaceContainer",
    insets: "nav", align: "space_between", vAlign: "center", padding: [20, 0],
  }, [
    ["chat", "chat", "对话"],
    ["sessions", "history", "会话"],
    ["assistants", "assistant", "助手"],
    ["settings", "settings", "设置"],
  ].map(([key, icon, label]) => Column({
    align: "center", spacing: 2, padding: [12, 4], corners: 12,
    bg: activeTab === key ? "secondaryContainer" : "transparent",
    color: activeTab === key ? "onSecondaryContainer" : "onSurfaceVariant",
    onClick: () => { activeTab = key; },
  }, [
    Icon(icon, { size: 18 }),
    Text(label, { fontSize: 11, fontWeight: activeTab === key ? 600 : 400 }),
  ]))),
]));
```

---

## 九、整页重写的三项底座（引擎版本 14）

用 `ui.page(名, { content })` 重写二级页面时，下面三项是通用能力，宿主不为任何页面单独登记。主题没用到时没有任何开销。

### 1. 懒加载列表 `LazyColumn` / `LazyRow`

`scroll: "vertical"` 的普通滚动列会一次算出并建出所有行。懒加载列表只为屏幕上看得见的行调用主题的 `item(index, ctx)`，条目上千也不慢（只能写在 `ui.slot` / `ui.page` 的动态渲染位置）。

| 属性 | 说明 |
|---|---|
| `count` | 行数（写了 `keys` 可以不写；同时写时必须与 `keys.length` 一致） |
| `keys` | 每行的稳定标识数组（字符串或数字，**严禁重复**，重复会报错回退）。有增删、排序的列表务必填写，并用独立递增序号生成 ID（切勿用 `rows.length`） |
| `item` | **必填** `(index, ctx) => 节点`，返回 `null` 表示这一行不显示。行里可以用任何节点、回调、`Host(...)` |
| `itemSize` | 某一行还没算好时占位的高度（`LazyRow` 为宽度）dp，默认 `56`（必须 `> 0`） |
| `padding` / `spacing` | `padding` 作为滚动区的内容留白（内容可滚过悬浮栏底下），`spacing` 为行间距（默认 `4`，均不可为负数） |
| `onEndReached` | `(ctx) => {...}`：空列表（`count === 0`）或列表完成最新行数测量且滚到离末尾 2 行以内时调用一次，行数变化后可再次触发 |
| `onReorder` | `(from, to, ctx) => {...}`：写了它，长按某一行即可拖动排序。拖动过程由宿主完成，**松手后才回调一次**，含义是「把第 `from` 行移到第 `to` 行的位置」 |
| `onRefresh` + `refreshing` | 下拉刷新（**仅 `LazyColumn` 支持**，在 `LazyRow` 上写这两个属性会直接报错）：下拉后调用 `onRefresh(ctx)`，`refreshing: true` 期间显示转圈 |

```js
let rows = [];
let nextId = 0;
let loading = false;

function loadMore() {
  if (loading) return;
  loading = true;
  for (let i = 0; i < 50; i++) {
    const n = nextId++;
    rows.push({ id: "row-" + n, title: "第 " + (n + 1) + " 条" });
  }
  loading = false;
}

ui.page("Log", {
  content: (d, ctx) => Column({ fillMaxSize: true, insets: "safe", bg: "background" }, [
    Row({ fillMaxWidth: true, height: 52, padding: [8, 0], vAlign: "center" }, [
      IconButton("back", { size: 36, iconSize: 20, onClick: "back" }),
      Text("请求日志", { fontSize: 17, fontWeight: 600 }),
    ]),
    LazyColumn({
      weight: 1, fillMaxWidth: true, padding: [12, 8], spacing: 6,
      keys: rows.map((r) => r.id),
      item: (i) => {
        const row = rows[i];
        if (!row) return null;
        return Row({
          fillMaxWidth: true, padding: [12, 10], corners: 10, bg: "surfaceContainerLow",
          swipe: { direction: "left", dismiss: true },
          onSwipe: () => {
            const idx = rows.indexOf(row);
            if (idx >= 0) rows.splice(idx, 1);
          },
        }, [
          Text(row.title, { weight: 1, maxLines: 1 }),
          Icon("arrow_right", { size: 16, color: "onSurfaceVariant" }),
        ]);
      },
      onEndReached: () => loadMore(),
      onReorder: (from, to) => { rows.splice(to, 0, rows.splice(from, 1)[0]); },
    }),
  ]),
});
```

注意：
- 懒加载列表必须有确定的高度（`LazyRow` 为宽度）：给它 `weight`、`height` 或 `fillMaxHeight`。放进 `scroll: "vertical"` 的容器里时宿主会改按屏幕高度显示并记一条运行时错误。
- 行的计算与其他位置共用等待预算（单次最多等 48ms，任意 250ms 内合计 48ms）；来不及的行先占位 `itemSize`，算完再显示。
- 主题状态变化后只重算看得见的行；重算完成前继续显示这一行原来的样子，不会闪。

### 2. 手势：滑动 `swipe` / `onSwipe` 与缩放 `zoom`

原则：**拖动过程中的跟手移动由宿主完成，主题只在手势结束时收到一次回调**。（每次回调后所有按数据渲染的位置都会重算，逐帧回调会掉帧。）

- **`swipe` 与 `onSwipe` 必须成对同时编写**（只写 `swipe` 不写 `onSwipe` 或只写 `onSwipe` 不写 `swipe` 编译期直接报错；传 `false` 或 `undefined` 表示不启用）：
- `swipe`：方向字符串 `"left"` / `"right"` / `"horizontal"` / `"up"` / `"down"` / `"vertical"`，或对象 `{ direction, follow, dismiss, threshold }`：
  - `follow`（默认 `true`）：拖动时节点跟着手指移动；整页左右切换这类场景可设 `false`。
  - `dismiss`（默认 `false`）：触发后立即执行 `onSwipe` 并滑出屏幕；若主题移除了该节点，顶上来的新节点会自动归零不闪烁；若主题未移除该节点（如弹确认框后用户取消），0.6 秒后自动弹回原位。
  - `threshold`（默认 `0.35`）：位移达到节点宽（高）的这个比例才触发；甩动足够快也会触发。
- `onSwipe`：`(direction, ctx) => {...}`，`direction` 为 `"left"` / `"right"` / `"up"` / `"down"`。
- `zoom`：`true` 或 `{ min, max }`（默认 `1` ～ `4`，要求 `0 < min <= max`）。双指缩放与放大后的平移全部由宿主完成，不回调主题；没放大时单指滑动照常交给外层滚动。
- 拖拽排序见上一节的 `onReorder`，下拉刷新见 `onRefresh`。

```js
let tab = 0;
const tabs = ["概览", "明细", "图片"];

ui.page("Stats", {
  content: (d, ctx) => Column({ fillMaxSize: true, insets: "safe", bg: "background" }, [
    Row({ fillMaxWidth: true, padding: [12, 8], spacing: 8 }, tabs.map((name, i) => Text(name, {
      padding: [14, 6], corners: 16,
      bg: i === tab ? "secondaryContainer" : "transparent",
      onClick: () => { tab = i; },
    }))),
    Box({
      weight: 1, fillMaxWidth: true, align: "center", vAlign: "center",
      swipe: { direction: "horizontal", follow: false },
      onSwipe: (direction) => {
        if (direction === "left" && tab < tabs.length - 1) tab++;
        if (direction === "right" && tab > 0) tab--;
      },
    }, [
      tab === 2
        ? Image("https://example.com/chart.png", { fillMaxWidth: true, zoom: { max: 6 } })
        : Text(tabs[tab] + "（左右滑动切换）", { color: "onSurfaceVariant" }),
    ]),
  ]),
});
```

### 3. 放入宿主控件：`Compose` 与 `NativeView`

宿主不列「可借用控件」的清单，也不规定每个控件能传哪些参数：主题按名字直接取用，参数与 `java` 调用是同一套写法。

#### `Compose(类名, 函数名, [参数], 属性?)` —— 调用宿主的任意界面函数

- **前三个参数必填**：`(类名字符串, 函数名字符串, 参数数组)`（无参数时第 3 项写 `[]`；第 4 项属性对象里不要写 `children`，界面内容参数直接放进第 3 项参数数组中）。
- **类名**：函数所在的类。Kotlin 顶层函数的类是「包名.文件名Kt」（如 `QRCode.kt` → `me.rerere.rikkahub.ui.components.ui.QRCodeKt`；常用宿主控件完整签名见第十章第 4 节）。
- **函数名**：源码里的名字即可。带颜色、尺寸等内联值类参数的函数编译后名字带后缀（如 `QRCode-jA1GFJw`），宿主自动匹配；非界面普通函数会被自动排除。
- **参数数组**：按位置传。若目标函数声明了默认参数，可只写前几个或用 `null` 代表取默认值；**若目标函数没有任何默认参数，必须传满参数个数且基本类型不可传 `null`**。

| 写的是 | 宿主怎么传 |
|---|---|
| `null`、没写到的 | 用函数自己的默认值（仅限带默认参数的函数或可空对象参数） |
| 数字、布尔、字符串 | 按参数类型严格转换：整数 / 小数、枚举精确常量名（区分大小写）、颜色（内联 `Color` 或可空 `Color?`）写 `"#hex"` 或语义色名、尺寸（内联 `Dp` 或可空 `Dp?`）写数字 |
| Java 对象（`java.new(...)`、`host.get(...)` 拿到的句柄） | 原样传入。**有状态的对象请建一次并 `java.keep`**，每次渲染都新建会让控件重置 |
| JS 函数（作为顶层参数） | 作为回调（`(String) -> Unit`、`(Assistant) -> Unit`、`(Color) -> Unit` 等）；被调用时回到主题执行：**参数里的 `Color` / `Dp` 自动拆箱为 `"#RRGGBB"` 与数值，其余宿主对象以临时 Java 句柄传入并在回调结束后自动释放**（若需跨回调长期保留请调 `java.keep(obj)`）。回调在主题线程异步执行，无返回值。若需把函数放进数组/对象参数内部，请包一层 `java.proxy("接口类名", fn)` |
| 节点树（作为顶层参数） | 作为界面内容（`content: @Composable () -> Unit` 等最多带 3 个参数的 Composable lambda） |

- **属性**：通用的尺寸、边距、底色等。函数有 `modifier` 参数且没传时，这些属性通过它生效；否则包在外面一层。可写 `fallback: 节点`，出错时画它。
- 不知道参数顺序时，在回调里用 `java.use(类名).$members()` 查看签名；参数对不上时的报错也会列出可用的写法。

```js
let scanning = false;
let scanned = "";

ui.page("SettingAbout", {
  content: (d, ctx) => Column({ fillMaxSize: true, insets: "safe", padding: 20, spacing: 14, align: "center", bg: "background" }, [
    Text("分享与扫码", { fontSize: 20, fontWeight: 600 }),
    // QRCode(value, modifier, size, color, backgroundColor)：第 2、3 个留空用默认值
    Compose("me.rerere.rikkahub.ui.components.ui.QRCodeKt", "QRCode",
      ["https://github.com", null, null, "onSurface"],
      { width: 180, height: 180, fallback: Text("二维码不可用") }),
    Button("扫一扫", { onClick: () => { scanning = true; } }),
    scanned !== "" && Text("扫到：" + scanned, { fontSize: 13, color: "onSurfaceVariant" }),
    // QRCodeScannerDialog(onResult, onPermissionDenied, onDismissRequest)
    scanning && Compose("me.rerere.rikkahub.ui.components.ui.QRCodeScannerKt", "QRCodeScannerDialog", [
      (text) => { scanned = String(text); scanning = false; },
      () => { scanning = false; },
      () => { scanning = false; },
    ]),
    Button("返回", { onClick: "back" }),
  ]),
});
```

#### `NativeView(类名, 属性)` —— 放入任意安卓原生视图

宿主在主线程用 `(Context)`（没有则 `(Context, AttributeSet)`）构造该视图并放到界面上，然后把它的 Java 句柄交给主题：

- `onCreate: (view, ctx) => {...}`：创建后调用一次，在里面用 `view.$main("方法名", 参数...)` 配置（视图的方法必须在主线程调用）。
- `onRelease: (view, ctx) => {...}`：离开界面前调用；之后句柄自动回收。
- 需要长期操作这个视图时，在 `onCreate` 里把 `view` 存进顶层变量即可（离开界面后不要再用）。

```js
let web = null;

ui.page("SettingDonate", {
  content: (d, ctx) => Column({ fillMaxSize: true, insets: "safe", bg: "background" }, [
    Row({ fillMaxWidth: true, height: 52, padding: [8, 0], vAlign: "center", spacing: 4 }, [
      IconButton("back", { size: 36, iconSize: 20, onClick: "back" }),
      Text("网页", { weight: 1, fontSize: 17, fontWeight: 600 }),
      IconButton("refresh", { size: 36, iconSize: 20, onClick: () => { if (web) web.$main("reload"); } }),
    ]),
    NativeView("android.webkit.WebView", {
      weight: 1, fillMaxWidth: true,
      onCreate: (view) => {
        web = view;
        view.$main("getSettings").$main("setJavaScriptEnabled", true);
        view.$main("loadUrl", "https://example.com");
      },
      onRelease: (view) => { web = null; view.$main("destroy"); },
      fallback: Text("网页视图不可用"),
    }),
  ]),
});
```

#### 能调什么、调不到什么

- **能调**：宿主自己的界面函数（`me.rerere.**`，含私有函数）与任意安卓视图类。
- **调不到**：Material 的原生控件（`Switch`、`ModalBottomSheet`、`DropdownMenu` 等）。正式包里它们被打包工具合并、挪到别的类或删掉，按名字找不稳。这些控件用主题自己的节点画（`Switch`、`Slider`、`TextField`、`Popup` 节点等）。
- 宿主改了函数名或参数后，该处报运行时错误并画 `fallback`，与数据桥按名字取字段的约定相同。

#### 出错时的三层兜底

| 情况 | 表现 |
|---|---|
| 类名 / 函数名找不到、参数个数或类型对不上、视图创建失败 | 调用之前就查出：该处画 `fallback`（没写就不画），错误记入编辑页的错误横幅，App 不受影响 |
| 界面函数执行到一半抛异常 | 宿主接住并恢复：该处换成 `fallback` 并记录错误，页面其余部分照常工作 |
| 之后才出现的错误：控件内部延后生成的内容（列表行、弹窗内容）、排版与绘制、控件自己的后台任务、点击处理、原生视图自己内部出错 | 接不住，App 会崩溃一次。下次启动进入安全模式页（已自动隔离故障主题外观），点「关闭界面自定义后进入」即可进去修改主题 |

第二层依赖 Compose 运行时的内部实现（已用单元测试与 R8 保留规则验证当前版本）；Compose 升级后若内部实现变了，这一层自动失效，退到第三层的表现。

---

## 十、宿主服务、数据模型与可借用控件字典（免看宿主源码速查表）

本章汇总了整页重写二级页面（`Assistant`、`AssistantDetail`、`SettingModels`、`SettingProvider`、`SettingMedia`、`History`、`Favorite` 等）所需的**全部精确类名、字段 Getter、非挂起写方法以及可借用 `Compose` 控件签名**，均已在正式版 R8 字节码下核验。

### 1. 宿主 Koin 单例服务与常用 ViewModel 速查表

#### (1) 通过 `host.get("全类名")` 直接获取的单例服务
| 服务简称 | 精确全限定类名 | 常用可调方法 |
|---|---|---|
| `SettingsStore` | `me.rerere.rikkahub.data.datastore.SettingsStore` | `getSettingsFlow()` → `StateFlow<Settings>`（传给 `ctx.watch(...)` 实时订阅全局设置） |
| `MemoryRepository` | `me.rerere.rikkahub.data.repository.MemoryRepository` | `getFilesFlow(memoryIdStr)` → `Flow<List<MemoryFile>>`（全局记忆 ID 为 `"global"`，独立助手记忆为 `assistant.getId().toString()`） |
| `ConversationRepository` | `me.rerere.rikkahub.data.repository.ConversationRepository` | `getConversationsOfAssistant(assistantUuid)`、`searchConversations(queryStr)`、`getPinnedConversations()`（均返回 `Flow<List<Conversation>>`） |
| `FavoriteRepository` | `me.rerere.rikkahub.data.repository.FavoriteRepository` | 供构造 `FavoriteVM` 使用 |
| `ChatService` | `me.rerere.rikkahub.service.ChatService` | 供构造 `HistoryVM` 使用（管理活跃会话状态与置顶） |
| `FilesManager` | `me.rerere.rikkahub.data.files.FilesManager` | 供构造 `AssistantVM` 等使用 |
| `McpManager` | `me.rerere.rikkahub.data.ai.mcp.McpManager` | 供构造 `SettingVM` 等使用 |

#### (2) 封装了非挂起写操作的常用 ViewModel（建一次并 `java.keep` 复用）
> 提示：不要直接调 `Repository` / `SettingsStore` 的 `suspend` 挂起方法，直接实例化下列 ViewModel 调用其普通 `void` 方法即可自动异步落盘！

| 类名 | 构造方式（`java.new(...)`） | 非挂起写操作与状态方法 |
|---|---|---|
| `me.rerere.rikkahub.ui.pages.assistant.AssistantVM` | `java.new("...AssistantVM", settingsStore, memoryRepo, convRepo, filesMgr)` | `addAssistant(assistant)`、`removeAssistant(assistant)`（自动清理文件/记忆/关联对话）、`copyAssistant(assistant, copyMemoriesBool)`、`updateSettings((curSettings) => newSettings)` |
| `me.rerere.rikkahub.ui.pages.setting.SettingVM` | `java.new("...SettingVM", settingsStore, mcpManager)`（或直接复用 `AssistantVM.updateSettings`） | `updateSettings((curSettings) => newSettings)`、`getSettings()`、`getLaunchCount()` |
| `me.rerere.rikkahub.ui.pages.history.HistoryVM` | `java.new("...HistoryVM", convRepo, settingsStore, host.get("me.rerere.rikkahub.service.ChatService"))` | `getConversations()` → `StateFlow<List<Conversation>>`、`deleteConversation(conv)`、`deleteAllConversations()`、`togglePinStatus(convUuid)`、`restoreConversation(conv)` |

---

### 2. 全局设置 `Settings` / `DisplaySetting` / `SettingsExtKt` 全字段表

#### (1) `Settings`（`me.rerere.rikkahub.data.datastore.Settings`）精确 Getter 与字段名
> 修改任意字段时调用：`assistantVm.updateSettings((cur) => java.copy(cur, { 字段名: 新值 }))`（字段名即去掉 `get` 后首字母小写）。

| 分类 | 字段名（用于 `java.copy`） | 读取 Getter 方法 | 返回类型 |
|---|---|---|---|
| **当前助手与助手库** | `assistantId` | `getAssistantId()` | `kotlin.uuid.Uuid` |
| | `assistants` | `getAssistants()` | `List<Assistant>` |
| | `assistantTags` | `getAssistantTags()` | `List<Tag>` |
| **渠道与各功能默认模型** | `providers` | `getProviders()` | `List<ProviderSetting>` |
| | `favoriteModels` | `getFavoriteModels()` | `List<Uuid>` |
| | `chatModelId` | `getChatModelId()` | `Uuid`（聊天默认模型） |
| | `fastModelId` / `fastModelReasoningLevel` | `getFastModelId()` / `getFastModelReasoningLevel()` | `Uuid` / `ReasoningLevel`（标题与轻量任务模型） |
| | `memoryModelId` / `memoryModelReasoningLevel` | `getMemoryModelId()` / `getMemoryModelReasoningLevel()` | `Uuid` / `ReasoningLevel`（记忆整理模型） |
| | `translateModeId`（注意拼写无 `l`） | `getTranslateModeId()` | `Uuid`（翻译默认模型） |
| | `ocrModelId` / `compressModelId` | `getOcrModelId()` / `getCompressModelId()` | `Uuid`（OCR / 上下文压缩模型） |
| | `imageGenerationModelId` | `getImageGenerationModelId()` | `Uuid` |
| **系统任务提示词与开关** | `enableAutoTitle` / `titlePrompt` | `getEnableAutoTitle()` / `getTitlePrompt()` | `boolean` / `String` |
| | `enableSuggestion` / `suggestionPrompt` | `getEnableSuggestion()` / `getSuggestionPrompt()` | `boolean` / `String` |
| | `translatePrompt` / `translateTargetLanguage` | `getTranslateTargetLanguage()` 等 | `String` |
| | `ocrPrompt` / `compressPrompt` | `getOcrPrompt()` / `getCompressPrompt()` | `String` |
| **媒体 / 语音 / 搜索 / MCP** | `mediaGenerationProviders` | `getMediaGenerationProviders()` | `List<MediaGenerationProviderSetting>` |
| | `ttsProviders` / `selectedTTSProviderId` | `getTtsProviders()` / `getSelectedTTSProviderId()` | `List<TTSProviderSetting>` / `Uuid?` |
| | `defaultTTSPlaybackSpeed` | `getDefaultTTSPlaybackSpeed()` | `float` |
| | `asrProviders` / `selectedASRProviderId` | `getAsrProviders()` / `getSelectedASRProviderId()` | `List<ASRProviderSetting>` / `Uuid?` |
| | `searchServices` / `searchServiceSelected` | `getSearchServices()` / `getSearchServiceSelected()` | `List<SearchServiceOptions>` / `int` |
| | `mcpServers` | `getMcpServers()` | `List<McpServerConfig>` |
| **提示词预设 / 世界书 / 扩展** | `enableAdvancedPrompt` / `promptPresets` | `getEnableAdvancedPrompt()` / `getPromptPresets()` | `boolean` / `List<PromptPreset>` |
| | `modeInjections` / `lorebooks` | `getModeInjections()` / `getLorebooks()` | `List<ModeInjection>` / `List<Lorebook>` |
| | `quickMessages` | `getQuickMessages()` | `List<QuickMessage>` |
| | `enableSecretKeyAlias` / `secretKeys` | `getEnableSecretKeyAlias()` / `getSecretKeys()` | `boolean` / `List<SecretKeyEntry>` |
| **显示 / 网络 / 主题 / Web** | `displaySetting` / `networkSetting` | `getDisplaySetting()` / `getNetworkSetting()` | `DisplaySetting` / `NetworkSetting` |
| | `dynamicColor` / `themeId` / `developerMode` | `getDynamicColor()` / `getThemeId()` / `getDeveloperMode()` | `boolean` / `String` / `boolean` |
| | `webServerEnabled` / `webServerPort` | `getWebServerEnabled()` / `getWebServerPort()` | `boolean` / `int` |

#### (2) `SettingsExtKt` 与 `DefaultSettingsKt` 顶层工具方法
- `const Ext = java.use("me.rerere.rikkahub.data.datastore.SettingsExtKt");`
  - `Ext.getCurrentChatModel(settings)` → 当前生效 `Model?`
  - `Ext.getCurrentAssistant(settings)` → 当前生效 `Assistant`
  - `Ext.findModelById(settings.getProviders(), modelUuid)` → 按 `Uuid` 查找 `Model?`
  - `Ext.findProvider(model, settings.getProviders())` → 查找某模型所属 `ProviderSetting?`
  - `Ext.getSelectedTTSProvider(settings)` / `Ext.getSelectedASRProvider(settings)`
- `const Def = java.use("me.rerere.rikkahub.data.datastore.DefaultSettingsKt");`
  - `Def.getDEFAULT_ASSISTANTS_IDS()` → `List<Uuid>`（内置不可删基准助手的 ID 集合，用 `.contains(assistant.getId())` 判断是否为默认助手）
- **`kotlin.uuid.Uuid` 构造与转换（已在正式包 R8 规则中完整保留）**：
  - `uuidObj.toString()` → 标准 36 位字符串（如 `"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"`）；
  - 字符串转回 `Uuid`：`java.use("kotlin.uuid.Uuid").$get("Companion").parse(idStr)`；
  - 空占位 `Uuid`（用于清空 `Settings` 上的默认模型槽位）：`java.use("kotlin.uuid.Uuid").$get("Companion").getNIL()`。

---

### 3. 核心业务实体字段与方法字典

#### (1) 助手 `Assistant`（`me.rerere.rikkahub.data.model.Assistant`）、头像 `Avatar` 与标签 `Tag`
> **实时联动说明**：所有新旧对话均 100% 实时跟随其所属 `Assistant` 的最新模型、思考档位、联网开关与扩展挂载配置（无旧会话配置快照锁定）；修改 `settings.assistants` 中对应 `Assistant` 即刻在聊天页生效。

- **新建默认实例**：`const a = java.new("me.rerere.rikkahub.data.model.Assistant");`
- **常用 Getter（字段名同去掉 `get` 首字母小写，直接用于 `java.copy(a, { ... })`）**：
  - 基础：`getId()` (`Uuid`)、`getName()` (`String`)、`getAvatar()` (`Avatar`)、`getTags()` (`List<Uuid>`)、`getSystemPrompt()` (`String`)
  - 模型与采样：`getChatModelId()` (`Uuid?`，传 `null` 回退跟随 `Settings.chatModelId`)、`getReasoningLevel()` (`ReasoningLevel`，枚举常量含 `OFF` / `AUTO` / `LOW` / `MEDIUM` / `HIGH` / `XHIGH` / `MAX`，可通过 `a.getReasoningLevel().getClass().getEnumConstants()` 动态遍历)、`getTemperature()` (`Float?`)、`getTopP()` (`Float?`)、`getMaxTokens()` (`Integer?`)、`getContextMessageLimit()` (`int`)、`getStreamOutput()` (`boolean`)
  - 记忆与能力：`getEnableMemory()` (`boolean`)、`getUseGlobalMemory()` (`boolean`)、`getEnableWebSearch()` (`boolean`)、`getWorkspaceId()` (`Uuid?`)、`getMcpServers()` (`Set<Uuid>`)、`getEnableSubAgent()` (`boolean`)、`getSubAgentIds()` (`Set<Uuid>`)
  - 注入与美化：`getModeInjectionIds()` (`Set<Uuid>`)、`getLorebookIds()` (`Set<Uuid>`)、`getEnabledSkills()` (`Set<String>`)、`getBackground()` (`String?`)
- **头像密封类 `me.rerere.rikkahub.data.model.Avatar` 三种子类判别（用 `String(av.$type)` 判断后缀）**：
  - `...Avatar$Image`：调 **`av.getUrl()`** 取图片地址；构造：`java.new("me.rerere.rikkahub.data.model.Avatar$Image", urlStr)`
  - `...Avatar$Emoji`：调 **`av.getContent()`** 取 Emoji 字符（⚠️ 字段名是 `content`，不是 `emoji`！）；构造：`java.new("me.rerere.rikkahub.data.model.Avatar$Emoji", "🩸")`
  - `...Avatar$Dummy`：无字段默认占位；读取单例：`java.use("me.rerere.rikkahub.data.model.Avatar$Dummy").$get("INSTANCE")`
- **标签 `me.rerere.rikkahub.data.model.Tag`**：`getId()` (`Uuid`)、`getName()` (`String`)。

#### (2) 提供商 `ProviderSetting` 与模型 `Model`（重写 `SettingModels` / `SettingProvider` 用）
- **`me.rerere.ai.provider.ProviderSetting`**（子类为 `$OpenAI`、`$Google`、`$Claude`，支持直接 `java.copy(p, { enabled: false })` 无损浅拷贝）：
  - 通用 Getter：`getId()` (`Uuid`)、`getEnabled()` (`boolean`)、`getName()` (`String`)、`getModels()` (`List<Model>`)、`getBuiltIn()` (`boolean`)、`getCustomHeaders()` (`List<CustomHeader>`)
  - OpenAI/Google/Claude 子类特有：`getApiKey()` (`String`)、`getBaseUrl()` (`String`)
  - 模型增删排方法（返回新 `ProviderSetting` 实例）：`addModel(model)`、`editModel(model)`、`delModel(model)`、`moveMove(fromIdx, toIdx)`
- **`me.rerere.ai.provider.Model`**：
  - Getter：`getId()` (`Uuid`，内部唯一标识)、`getModelId()` (`String`，API 模型名如 `"gpt-4o"`)、`getDisplayName()` (`String`)、`getType()` (`ModelType`)、`getContextLength()` (`int`)、`getAbilities()` (`List<ModelAbility>`，常见常量 `"TOOL"`、`"REASONING"`)、`getInputModalities()` / `getOutputModalities()` (`List<Modality>`，常见常量 `"TEXT"`、`"IMAGE"`、`"AUDIO"`、`"VIDEO"`、`"PDF"`)、`getProviderOverwrite()` (`ProviderSetting?`)
  - `me.rerere.ai.provider.ModelType` 枚举常量名（区分大小写）：`"CHAT"`、`"EMBEDDING"`、`"IMAGE"`（以枚举 `enumConstants` 为准）。

#### (3) 媒体生成渠道 `MediaGenerationProviderSetting`（重写 `SettingMedia` 用）
- 类名：`me.rerere.mediagen.provider.MediaGenerationProviderSetting`（子类 `$OpenAI`、`$Aliyun` 等）
- Getter：`getId()` (`Uuid`)、`getName()` (`String`)、`getApiKey()` (`String`)、`getBaseUrl()` (`String`)、`getModels()` (`List<MediaGenerationModel>`)、`getSupportedKinds()` (`Set<MediaKind>`)。

#### (4) 会话 `Conversation` 与收藏 `FavoriteVM`（重写 `History` / `Favorite` 页用）
- **`me.rerere.rikkahub.data.model.Conversation`**：
  - Getter：`getId()` (`Uuid`)、`getAssistantId()` (`Uuid`)、`getTitle()` (`String`)、`isPinned()` (`boolean`，注意是 `isPinned` 而非 `getPinned`)、`getCreateAt()` / `getUpdateAt()` (`java.time.Instant`，调 `.toEpochMilli()` 取毫秒时间戳)、`getCurrentMessages()` (`List<UIMessage>`)、`getFolderId()` (`Uuid?`)。
- **`me.rerere.rikkahub.ui.pages.favorite.FavoriteVM`**：
  - 构造：`java.new("me.rerere.rikkahub.ui.pages.favorite.FavoriteVM", host.get("me.rerere.rikkahub.data.repository.FavoriteRepository"))`
  - 读取：`getNodeFavorites()` → `StateFlow<List<NodeFavoriteListItem>>`（传给 `ctx.watch(...)` 实时订阅收藏列表）。

#### (5) 显示设置 `DisplaySetting`、扩展实体与搜索/语音/MCP 配置（重写偏好设置与扩展各页用）
> 下列配置全部挂在 `Settings` 对象上，读取后用 `java.copy` 生成新对象并传给 `assistantVm.updateSettings((cur) => ...)` 即可直接持久化，无需额外找 ViewModel：

- **`DisplaySetting`（`settings.getDisplaySetting()`，`me.rerere.rikkahub.data.datastore.DisplaySetting`）**：
  - 用户资料与气泡：`getUserNickname()` (`String`)、`getUserAvatar()` (`Avatar`)、`getShowUserAvatar()` (`boolean`)、`getShowAssistantBubble()` (`boolean`)、`getBubbleOpacity()` (`float`)
  - 消息元信息：`getShowModelIcon()`、`getShowModelName()`、`getShowDateTimeInMessage()`、`getShowTokenUsage()`、`getShowThinkingContent()`、`getAutoCloseThinking()`（均为 `boolean`）
  - 排版与交互：`getFontSizeRatio()` (`float`)、`getChatFontFamily()` (`ChatFontFamily`)、`getChatCustomFontName()` (`String`)、`getCodeBlockAutoWrap()`、`getCodeBlockAutoCollapse()`、`getShowLineNumbers()`、`getEnableLatexRendering()`、`getEnableQuoteHighlight()`、`getEnableBlurEffect()`、`getShowMessageJumper()`、`getMessageJumperOnLeft()`、`getSendOnEnter()`、`getEnableFloatingChatWindow()`
  - 修改示例：`assistantVm.updateSettings((cur) => java.copy(cur, { displaySetting: java.copy(cur.getDisplaySetting(), { bubbleOpacity: 0.85 }) }))`
- **提示词预设 / 世界书 / 快捷消息 / 密钥代名词 / MCP / 语音 / 搜索（均从 `settings.getXxx()` 读取 `List`）**：
  - `PromptPreset`（`me.rerere.rikkahub.data.model.PromptPreset`）：`getId()` (`Uuid`)、`getName()` (`String`)、`getEnabled()` (`boolean`)、`getBlocks()` (`List<PromptBlock>`)
  - `Lorebook`（`me.rerere.rikkahub.data.model.Lorebook`）：`getId()` (`Uuid`)、`getName()` (`String`)、`getDescription()` (`String`)、`getEnabled()` (`boolean`)、`getEntries()` (`List<LorebookEntry>`)
  - `PromptInjection$ModeInjection`：`getId()` (`Uuid`)、`getName()` (`String`)、`getEnabled()` (`boolean`)、`getPrompt()` (`String`)
  - `QuickMessage`（`me.rerere.rikkahub.data.model.QuickMessage`）：`getId()` (`Uuid`)、`getTitle()` (`String`)、`getContent()` (`String`)
  - `SecretKeyEntry`（`me.rerere.rikkahub.data.datastore.SecretKeyEntry`）：`getId()` (`Uuid`)、`getAlias()` (`String`)、`getDescription()` (`String`)
  - `McpServerConfig`（`me.rerere.rikkahub.data.ai.mcp.McpServerConfig`）：`getId()` (`Uuid`)、`getEnable()` (`boolean`)、`getCommonOptions()`（含 `.getName()`）
  - `TTSProviderSetting` / `ASRProviderSetting`：`getId()` (`Uuid`)、`getName()` (`String`)、`getEnabled()` (`boolean`)
  - `Workspaces` / `Skills` / `Plugins` 服务：`host.get("me.rerere.rikkahub.data.repository.WorkspaceRepository")`、`host.get("me.rerere.rikkahub.data.skills.SkillManager")`、`host.get("me.rerere.plugin.host.PluginHost")`。

---

### 4. 可直接借用的宿主 `Compose(...)` 界面控件签名字典

在整页重写中遇到相机扫码、酒馆卡片解析、多渠道模型选择弹窗、终端模拟器等复杂功能控件时，无需手写底层逻辑，直接按下列精确签名调用 `Compose(类名, 函数名, [参数], 属性?)` 即可嵌入自己画的页面中：

| 功能说明 | 类名（第 1 参数） | 函数名（第 2 参数） | 位置参数列表（第 3 参数数组，`null` = 取宿主默认值） |
|---|---|---|---|
| **二维码生成图片** | `me.rerere.rikkahub.ui.components.ui.QRCodeKt` | `"QRCode"` | `[value: String, modifier = null, size: Int = 512, color: Color = "secondary", backgroundColor: Color = "transparent"]` |
| **全屏相机扫码弹窗** | `me.rerere.rikkahub.ui.components.ui.QRCodeScannerKt` | `"QRCodeScannerDialog"` | `[onResult: (text) => {}, onPermissionDenied: () => {}, onDismissRequest: () => {}]`（3 项必填，自动处理相机权限申请） |
| **酒馆 PNG/JSON 角色卡导入器** | `me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImporterKt` | `"AssistantImporter"` | `[modifier = null, onUpdate: (assistantHandle) => {}]`（回调收到解析好的 `Assistant` 句柄，直接传给 `assistantVm.addAssistant(a)` 即可） |
| **模型选择按钮 + 原生底部选择弹窗** | `me.rerere.rikkahub.ui.components.ai.ModelListKt` | `"ModelSelector"` | `[modelId: Uuid?, providers: List<ProviderSetting>, type: ModelType = "CHAT", modifier = null, allowClear: Boolean = false, showLabel: Boolean = true, onSelect: (modelHandle) => {}]` |
| **模型类型 / 模态 / 能力徽标组** | `me.rerere.rikkahub.ui.components.ai.ModelListKt` | `"ModelTypeTag"` / `"ModelModalityTag"` / `"ModelAbilityTag"` | `[model: Model]`（1 项必填，传入 `Model` 句柄即可渲染原版能力小标签） |
| **本地视频全屏播放弹窗** | `me.rerere.rikkahub.ui.components.ui.VideoPlayerDialogKt` | `"VideoPlayerDialog"` | `[file: java.io.File, onDismissRequest: () => {}]`（2 项必填） |
| **完整工作区交互式多标签终端页** | `me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPageKt` | `"WorkspaceTerminalPage"` | `[workspaceId: String]`（1 项必填） |

---

### 5. 二级页面整页重写标准脚手架（可直接复制改写）

下面以重写「默认模型设置页（`SettingModels`）」核心骨架为例，展示如何零宿主源码依赖地串联 `SettingsStore`、`ctx.watch`、`ModelSelector` 宿主控件、`java.copy` 落盘与「一键切回原版页面」安全出口：

```js
let __store = null;
let __vm = null;
let __showOriginalModels = false;

function ensureHostBridge() {
  if (__store && __vm) return true;
  try {
    __store = java.keep(host.get("me.rerere.rikkahub.data.datastore.SettingsStore"));
    const mem = host.get("me.rerere.rikkahub.data.repository.MemoryRepository");
    const conv = host.get("me.rerere.rikkahub.data.repository.ConversationRepository");
    const files = host.get("me.rerere.rikkahub.data.files.FilesManager");
    __vm = java.keep(java.new("me.rerere.rikkahub.ui.pages.assistant.AssistantVM", __store, mem, conv, files));
    return true;
  } catch (_) {
    return false;
  }
}

ui.page("SettingModels", {
  content: (d, ctx) => {
    if (__showOriginalModels) {
      return Box({ fillMaxSize: true, align: "end", vAlign: "bottom" }, [
        Host("original", { fillMaxSize: true }),
        Button("回到自定义页", { margin: 16, onClick: () => { __showOriginalModels = false; } }),
      ]);
    }
    const ok = ensureHostBridge();
    const settings = ok ? ctx.watch(__store.getSettingsFlow()) : null;
    const slots = [
      ["聊天默认模型", "chatModelId", settings && settings.getChatModelId()],
      ["标题与轻量任务模型", "fastModelId", settings && settings.getFastModelId()],
      ["翻译默认模型", "translateModeId", settings && settings.getTranslateModeId()],
      ["记忆整理模型", "memoryModelId", settings && settings.getMemoryModelId()],
    ];

    return Column({ fillMaxSize: true, insets: "safe", scroll: "vertical", padding: 16, spacing: 12, bg: "background" }, [
      Row({ fillMaxWidth: true, vAlign: "center", spacing: 8 }, [
        IconButton("back", { size: 36, iconSize: 20, onClick: "back" }),
        Text("默认模型矩阵", { weight: 1, fontSize: 18, fontWeight: 700 }),
        Button("原版页", { onClick: () => { __showOriginalModels = true; } }),
      ]),
      ...slots.map(([label, field, curUuid]) => Column({
        fillMaxWidth: true, padding: 12, corners: 10, bg: "surfaceContainerLow", spacing: 6,
      }, [
        Text(label, { fontSize: 13, fontWeight: 600, color: "onSurfaceVariant" }),
        settings
          ? Compose("me.rerere.rikkahub.ui.components.ai.ModelListKt", "ModelSelector", [
              curUuid,
              settings.getProviders(),
              "CHAT",
              null,
              true,
              false,
              (pickedModel) => {
                const nextId = pickedModel ? pickedModel.getId() : java.use("kotlin.uuid.Uuid").$get("Companion").getNIL();
                __vm.updateSettings((cur) => java.copy(cur, { [field]: nextId }));
              },
            ], { fillMaxWidth: true, fallback: Text("模型选择器不可用") })
          : Text("等待连接宿主设置…", { fontSize: 12, color: "onSurfaceVariant" }),
      ])),
    ]);
  },
});
```

---

## 十一、宿主模块实现文件索引

以下为宿主侧界面自定义引擎的核心源码位置（均相对于 `app/src/main/java/me/rerere/rikkahub/`，供维护宿主代码时定位查阅）：

- `data/model/UIPreset.kt`：预设、全局外观、组件样式、UI 节点与手势/动画数据结构
- `ui/custom/CustomUICodeEngine.kt`：DSL 引导脚本、保存期离线编译与严格属性/颜色/枚举校验
- `ui/custom/UIThemeRuntime.kt`：常驻 QuickJS 运行时、插槽与列表行双缓存、结果钉住、`java`/`host` 数据桥、定时器与临时句柄回收
- `ui/custom/UISlot.kt`：通用插槽入口 `UISlot`、`UIPinResult`、`ctx.watch` 响应式订阅与动作路由
- `ui/custom/UINodeRenderer.kt`：通用 UI 节点 Compose 渲染器
- `ui/custom/UILazyList.kt`：`LazyColumn` / `LazyRow`、`uiSwipe` 跟手滑动与 `uiZoom` 双指缩放
- `ui/custom/UIComposeResolver.kt` & `UIHostNodes.kt`：`Compose` 反射解析与参数转换、`UIGuardedComposableCall`（`GapComposer` 栈异常恢复）、`NativeView`
- `ui/custom/UIPageHost.kt`：导航路由级装饰器 `uiPageEntryDecorator`、`ui.page` 与 `ui.overlay`
- `ui/custom/CustomUIRuntime.kt`、`UIGlobalStyle.kt` & `UIHostComponents.kt`：颜色/切角/边距视觉修饰符、`uiSafeClip` 纯绘制裁剪（防止超长 `cut`/异形圆角容器触发 Skia `>8192px` `Path.op` 触摸精度失效）、全局字体/形状/排版构建，以及 `UIModalBottomSheet` / `UISwitch` / 分段列表与切换条组件桥接
- `ui/custom/UIAnimation.kt`、`UIShader.kt` & `UIVideo.kt`：关键帧动画、AGSL 着色器（含触控队列与 `wakeDuration` 空闲休眠）、ExoPlayer 视频壁纸
- `ui/custom/UICodeGenerator.kt`、`UICodeEditor.kt` & `app/src/main/resources/ui-editor/editor.js`：预设转代码与内置 `acorn` AST 可视化双向改码
- `ui/pages/extensions/UICustomizePage.kt`、`UIVisualEditor.kt` & `ui/activity/SafeModeActivity.kt`：界面自定义编辑页、实时预览沙盒与启动异常安全模式（强制 `ignoreCustomUi = true` 隔离故障主题）



