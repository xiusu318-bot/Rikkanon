package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.custom.colorOr
import me.rerere.rikkahub.ui.custom.uiSafeClip
import me.rerere.rikkahub.ui.pages.extensions.plugin.uiSpec
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.buildHighlightText
import me.rerere.highlight.CodeHighlightText
import me.rerere.highlight.CodeHighlighter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toDp
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.WebViewContentCache
import me.rerere.ui.webview.rememberWebViewState
import kotlin.time.Clock

private const val COLLAPSE_LINES = 10
private val PREVIEWABLE_LANGUAGES = setOf("html", "svg")

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val normalizedLanguage = remember(language) { language.lowercase() }
    val codeblockMounts = me.rerere.rikkahub.ui.pages.extensions.plugin.rememberPluginUiMounts("markdown.codeblock")
    val pluginRenderer = remember(codeblockMounts, normalizedLanguage) {
        codeblockMounts.find { (_, reg) ->
            val spec = reg.uiSpec
            val langs = spec.getValue("languages").jsonArray.map { it.jsonPrimitive.content.lowercase() }
            normalizedLanguage in langs || "*" in langs
        }
    }
    val canInlinePreview = completeCodeBlock && (normalizedLanguage in PREVIEWABLE_LANGUAGES || pluginRenderer != null)
    var previewMode by remember(canInlinePreview, code, normalizedLanguage, pluginRenderer) {
        mutableStateOf(pluginRenderer != null)
    }

    var isExpanded by remember(settings.displaySetting.codeBlockAutoCollapse) {
        mutableStateOf(!settings.displaySetting.codeBlockAutoCollapse)
    }
    val autoWrap = settings.displaySetting.codeBlockAutoWrap
    val showLineNumbers = settings.displaySetting.showLineNumbers

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it, "wt")?.use { outputStream ->
                        outputStream.write(code.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 界面自定义 ui.style("code_block")；未设置时为 null，各值与原版相同
    val codeStyle = me.rerere.rikkahub.ui.custom.uiComponentStyle("code_block")
    val colorScheme = MaterialTheme.colorScheme
    val codeShape = codeStyle?.cornerDp?.let { me.rerere.rikkahub.ui.custom.uiCornerShape(it.dp) } ?: MaterialTheme.shapes.large
    val codeContainerBg = codeStyle.colorOr({ it.background }, colorScheme, colorScheme.surfaceContainer)
    val codeHeaderBg = colorScheme.surfaceContainerHighest

    Column(
        modifier = modifier
            .border(
                (codeStyle?.borderWidthDp ?: 1f).dp,
                codeStyle.colorOr({ it.borderColor }, colorScheme, MaterialTheme.colorScheme.outlineVariant),
                codeShape,
            )
            .uiSafeClip(codeShape)
            .background(codeContainerBg),
    ) {
        // 界面自定义：主题可用 ui.slot("code_block.header", ...) 接管代码块顶栏；没写时绘制下面的原版
        CodeBlockHeaderSlot(
                    language = language,
                    scope = scope,
                    clipboardManager = clipboardManager,
                    code = code,
                    createDocumentLauncher = createDocumentLauncher,
                    navController = navController,
                    completeCodeBlock = completeCodeBlock,
                    previewMode = previewMode,
                    canInlinePreview = canInlinePreview,
                    onTogglePreviewMode = { previewMode = !previewMode },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(codeHeaderBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                HighlightCodeActions(
                    language = language,
                    scope = scope,
                    clipboardManager = clipboardManager,
                    code = code,
                    createDocumentLauncher = createDocumentLauncher,
                    navController = navController,
                    completeCodeBlock = completeCodeBlock,
                    previewMode = previewMode,
                    canInlinePreview = canInlinePreview,
                    onTogglePreviewMode = {
                        previewMode = !previewMode
                    },
                )
            }
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            when {
                completeCodeBlock && pluginRenderer != null && previewMode -> {
                    PluginCodeBlockPreview(
                        runtime = pluginRenderer.first,
                        reg = pluginRenderer.second,
                        language = normalizedLanguage,
                        code = code,
                    )
                }
                canInlinePreview && previewMode -> {
                    CodeBlockPreview(
                        code = code,
                        language = normalizedLanguage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }
                completeCodeBlock && normalizedLanguage == "mermaid" -> {
                    Mermaid(
                        code = code,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    val textStyle = LocalTextStyle.current.merge(style)
                    val codeLines = remember(code) { code.lines() }
                    val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
                    val displayCode = if (isExpanded) code else collapsedCode
                    val displayLines = remember(displayCode) { displayCode.lines() }

                    // 如果显示行号且自动换行，需要逐行渲染以保持对齐
                    when {
                        showLineNumbers && autoWrap -> {
                            CodeBlockWithLineNumbersWrapped(
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                            )
                        }
                        else -> {
                            CodeBlockDefault(
                                displayCode = displayCode,
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                                autoWrap = autoWrap,
                                showLineNumbers = showLineNumbers,
                                scrollState = scrollState,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    // 代码折叠按钮
                    if (settings.displaySetting.codeBlockAutoCollapse && codeLines.size > COLLAPSE_LINES) {
                        Box(
                            modifier = Modifier
                                .onClick {
                                    isExpanded = !isExpanded
                                }
                                .fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(textStyle.fontSize.toDp())
                                )
                                Text(
                                    text = if (isExpanded) {
                                        stringResource(id = R.string.code_block_collapse)
                                    } else {
                                        stringResource(id = R.string.code_block_expand)
                                    },
                                    fontSize = textStyle.fontSize,
                                    lineHeight = textStyle.lineHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockWithLineNumbersWrapped(
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
) {
    val lineNumberWidth = remember(displayLines.size) {
        displayLines.size.toString().length
    }
    SelectionContainer {
        Column {
            displayLines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DisableSelection {
                        Text(
                            text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                            fontSize = textStyle.fontSize,
                            lineHeight = textStyle.lineHeight,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            softWrap = false,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    CodeHighlightText(
                        code = line,
                        language = language,
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        colors = colorPalette,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                        fontFamily = JetbrainsMono,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockDefault(
    displayCode: String,
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
    autoWrap: Boolean,
    showLineNumbers: Boolean,
    scrollState: ScrollState,
) {
    Row(
        modifier = Modifier.then(
            if (autoWrap) {
                Modifier
            } else {
                Modifier.horizontalScroll(scrollState)
            }
        )
    ) {
        // 行号列
        if (showLineNumbers) {
            val lineNumberWidth = remember(displayLines.size) {
                displayLines.size.toString().length
            }
            Column(
                modifier = Modifier.padding(end = 8.dp)
            ) {
                displayLines.forEachIndexed { index, _ ->
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                    )
                }
            }
        }

        // 代码列
        SelectionContainer {
            CodeHighlightText(
                code = displayCode,
                language = language,
                modifier = Modifier.animateContentSize(),
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = autoWrap,
                fontFamily = JetbrainsMono
            )
        }
    }
}

@Composable
private fun HighlightCodeActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: Navigator,
    completeCodeBlock: Boolean = true,
    previewMode: Boolean = false,
    canInlinePreview: Boolean = false,
    onTogglePreviewMode: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CodeLanguageLabel(language)
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodeSaveButton(language, createDocumentLauncher)
            CodeCopyButton(scope, clipboardManager, code)
            if (canInlinePreview) {
                CodePreviewToggle(previewMode, onTogglePreviewMode)
            }
            if (completeCodeBlock && language.lowercase() in PREVIEWABLE_LANGUAGES) {
                CodeWebPreviewButton(code, language, navController)
            }
        }
    }
}

@Composable
private fun CodeLanguageLabel(language: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text = language,
        fontSize = 12.sp,
        lineHeight = 12.sp,
        fontFamily = JetbrainsMono,
        color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    )
}

@Composable
private fun CodeActionIcon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .onClick { onClick() }
            .padding(4.dp)
            .size(16.dp)
    )
}

private fun codeFileExtension(language: String): String = when (language.lowercase()) {
    "kotlin" -> "kt"
    "java" -> "java"
    "python" -> "py"
    "javascript" -> "js"
    "typescript" -> "ts"
    "cpp", "c++" -> "cpp"
    "c" -> "c"
    "html" -> "html"
    "css" -> "css"
    "xml" -> "xml"
    "json" -> "json"
    "yaml", "yml" -> "yml"
    "markdown", "md" -> "md"
    "sql" -> "sql"
    "sh", "bash" -> "sh"
    "svg" -> "svg"
    else -> "txt"
}

@Composable
private fun CodeSaveButton(
    language: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    CodeActionIcon(
        imageVector = HugeIcons.Download04,
        contentDescription = stringResource(id = R.string.chat_page_save),
        onClick = {
            createDocumentLauncher.launch(
                "code_${
                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                }.${codeFileExtension(language)}"
            )
        },
        modifier = modifier,
        tint = tint,
    )
}

@Composable
private fun CodeCopyButton(
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    CodeActionIcon(
        imageVector = HugeIcons.Copy01,
        contentDescription = stringResource(id = R.string.code_block_copy),
        onClick = {
            scope.launch {
                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
            }
        },
        modifier = modifier,
        tint = tint,
    )
}

@Composable
private fun CodePreviewToggle(
    previewMode: Boolean,
    onTogglePreviewMode: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    CodeActionIcon(
        imageVector = if (previewMode) HugeIcons.Code else HugeIcons.View,
        contentDescription = if (previewMode) "Code" else stringResource(id = R.string.code_block_preview),
        onClick = onTogglePreviewMode,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
private fun CodeWebPreviewButton(
    code: String,
    language: String,
    navController: Navigator,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    CodeActionIcon(
        imageVector = HugeIcons.Eye,
        contentDescription = stringResource(id = R.string.code_block_preview),
        onClick = {
            val content = buildCodePreviewHtml(code = code, language = language.lowercase())
            val contentId = WebViewContentCache.store(context.cacheDir, content)
            navController.navigate(Screen.WebView(contentId = contentId))
        },
        modifier = modifier,
        tint = tint,
    )
}

/**
 * 代码块顶栏接入点 `code_block.header`。
 * 数据：language / lines / previewMode / canPreview；原版部件：language / save_button / copy_button / preview_toggle / web_preview_button / original；
 * 动作：copy / save / toggle_preview。
 */
@Composable
private fun CodeBlockHeaderSlot(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: Navigator,
    completeCodeBlock: Boolean,
    previewMode: Boolean,
    canInlinePreview: Boolean,
    onTogglePreviewMode: () -> Unit,
    original: @Composable () -> Unit,
) {
    val slotActive = me.rerere.rikkahub.ui.custom.LocalActiveUIPreset.current?.hasSlot("code_block.header") == true
    me.rerere.rikkahub.ui.custom.UISlot(
        name = "code_block.header",
        data = if (slotActive) mapOf(
            "language" to language,
            "lines" to code.lines().size,
            "previewMode" to previewMode,
            "canPreview" to canInlinePreview,
        ) else emptyMap(),
        parts = if (slotActive) mapOf(
            "language" to { info -> CodeLanguageLabel(language, info.modifier, info.textColor) },
            "save_button" to { info -> CodeSaveButton(language, createDocumentLauncher, info.modifier, info.textColor) },
            "copy_button" to { info -> CodeCopyButton(scope, clipboardManager, code, info.modifier, info.textColor) },
            "preview_toggle" to { info ->
                if (canInlinePreview) CodePreviewToggle(previewMode, onTogglePreviewMode, info.modifier, info.textColor)
            },
            "web_preview_button" to { info ->
                if (completeCodeBlock && language.lowercase() in PREVIEWABLE_LANGUAGES) {
                    CodeWebPreviewButton(code, language, navController, info.modifier, info.textColor)
                }
            },
        ) else emptyMap(),
        actions = mapOf(
            "copy" to { scope.launch { clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code))) } },
            "save" to { createDocumentLauncher.launch("code_${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())}.${codeFileExtension(language)}") },
            "toggle_preview" to onTogglePreviewMode,
        ),
        modifier = Modifier.fillMaxWidth(),
        original = original,
    )
}

@Composable
private fun CodeBlockPreview(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = buildCodePreviewHtml(code = code, language = language),
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )

    WebView(
        state = state,
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
    )
}

private fun buildCodePreviewHtml(code: String, language: String): String {
    return if (language == "svg") {
        """<!DOCTYPE html><html><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$code</body></html>"""
    } else {
        code
    }
}

@Composable
private fun PluginCodeBlockPreview(
    runtime: me.rerere.plugin.runtime.PluginRuntime,
    reg: me.rerere.plugin.registry.PluginRegistration,
    language: String,
    code: String,
) {
    val host = org.koin.compose.koinInject<me.rerere.plugin.host.PluginHost>()
    val spec = reg.uiSpec
    val path = (spec["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val explicitHeight = (spec["height"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    val heightDp = (explicitHeight ?: 220).coerceIn(48, 800)
    val argsJson = remember(language, code) {
        kotlinx.serialization.json.buildJsonObject {
            put("language", kotlinx.serialization.json.JsonPrimitive(language))
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
        }.toString()
    }
    if (!path.isNullOrBlank()) {
        me.rerere.rikkahub.ui.pages.extensions.plugin.PluginWebViewBox(
            pluginId = runtime.id,
            path = path,
            argsJson = argsJson,
            autoHeight = explicitHeight == null,
            initialHeightDp = heightDp,
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp),
        )
    } else {
        val rendered = me.rerere.rikkahub.ui.pages.extensions.plugin.rememberPluginRendered(
            runtime.id,
            reg.handlerId,
            kotlinx.serialization.json.buildJsonObject {
                put("language", kotlinx.serialization.json.JsonPrimitive(language))
                put("code", kotlinx.serialization.json.JsonPrimitive(code))
            },
            language,
            code,
        )
        rendered?.exceptionOrNull()?.let { me.rerere.rikkahub.ui.pages.extensions.plugin.PluginRenderError(it) }
        val htmlOrText = rendered?.getOrNull()
        if (!htmlOrText.isNullOrEmpty()) {
            if (htmlOrText.trimStart().startsWith("<")) {
                me.rerere.rikkahub.ui.pages.extensions.plugin.PluginWebViewBox(
                    pluginId = runtime.id,
                    path = "",
                    argsJson = argsJson,
                    inlineHtml = htmlOrText,
                    autoHeight = explicitHeight == null,
                    initialHeightDp = heightDp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heightDp.dp),
                )
            } else {
                Text(
                    text = htmlOrText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: CodeHighlighter,
    val darkMode: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                val tokens = highlighter.highlight(text.text, language)
                buildAnnotatedString {
                    tokens.forEach { token ->
                        buildHighlightText(token, colorPalette)
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }
}
