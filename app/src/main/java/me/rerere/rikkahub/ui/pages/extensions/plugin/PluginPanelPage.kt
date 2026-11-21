package me.rerere.rikkahub.ui.pages.extensions.plugin

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.runtime.PluginJson
import me.rerere.rikkahub.data.plugin.PluginUiEvent
import me.rerere.rikkahub.data.plugin.PluginUiEvents
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

/**
 * 插件自有面板（presentation = page）。WebView 只负责界面：
 * 页面中 `window.rikka.call(name, args)` 调用插件 `rikka.expose(name, fn)` 暴露的方法，返回 Promise；
 * `window.rikka.on(event, fn)` 监听插件 `rikka.ui.emit(event, data)` 推送的事件；
 * `window.rikka.args` 为 ui.open 传入的参数；
 * `window.rikka.theme` 与 `:root` CSS 变量自动同步宿主当前 Material 3 主题色。
 */
@Composable
fun PluginPanelPage(pluginId: String, path: String, argsJson: String, title: String? = null) {
    val host = koinInject<PluginHost>()
    val installed by host.installed.collectAsStateWithLifecycle()
    val plugin = installed.find { it.id == pluginId }
    val navController = LocalNavController.current

    LaunchedEffect(pluginId, path) {
        PluginUiEvents.events.collect { ev ->
            if (ev is PluginUiEvent.ClosePanel && ev.pluginId == pluginId && (ev.path == null || ev.path == path)) {
                navController.popBackStack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title ?: plugin?.manifest?.name ?: pluginId) },
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        PluginWebViewBox(
            pluginId = pluginId,
            path = path,
            argsJson = argsJson,
            autoHeight = false,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

private fun Color.toHexColor(): String {
    val argb = toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}

private data class PluginThemeTokens(
    val isDark: Boolean,
    val colors: Map<String, String>,
) {
    val cssVariables: String
        get() = buildString {
            append(":root{")
            append("color-scheme:${if (isDark) "dark" else "light"};")
            colors.forEach { (k, v) ->
                append("--rikka-$k:$v;")
            }
            append("}")
            append("html,body{color:var(--rikka-on-surface);font-family:system-ui,-apple-system,sans-serif;margin:0;padding:0;background:transparent;}")
        }

    val themeJson: String
        get() = buildJsonObject {
            put("isDark", isDark)
            put("colors", buildJsonObject {
                colors.forEach { (k, v) -> put(k, v) }
            })
        }.toString()
}

@Composable
private fun rememberPluginThemeTokens(): PluginThemeTokens {
    val isDark = LocalDarkMode.current
    val cs: ColorScheme = MaterialTheme.colorScheme
    return remember(isDark, cs) {
        PluginThemeTokens(
            isDark = isDark,
            colors = mapOf(
                "primary" to cs.primary.toHexColor(),
                "on-primary" to cs.onPrimary.toHexColor(),
                "primary-container" to cs.primaryContainer.toHexColor(),
                "on-primary-container" to cs.onPrimaryContainer.toHexColor(),
                "secondary" to cs.secondary.toHexColor(),
                "on-secondary" to cs.onSecondary.toHexColor(),
                "secondary-container" to cs.secondaryContainer.toHexColor(),
                "on-secondary-container" to cs.onSecondaryContainer.toHexColor(),
                "tertiary" to cs.tertiary.toHexColor(),
                "tertiary-container" to cs.tertiaryContainer.toHexColor(),
                "on-tertiary-container" to cs.onTertiaryContainer.toHexColor(),
                "background" to cs.background.toHexColor(),
                "on-background" to cs.onBackground.toHexColor(),
                "surface" to cs.surface.toHexColor(),
                "on-surface" to cs.onSurface.toHexColor(),
                "surface-variant" to cs.surfaceVariant.toHexColor(),
                "on-surface-variant" to cs.onSurfaceVariant.toHexColor(),
                "surface-container" to cs.surfaceContainer.toHexColor(),
                "surface-container-low" to cs.surfaceContainerLow.toHexColor(),
                "surface-container-high" to cs.surfaceContainerHigh.toHexColor(),
                "surface-container-highest" to cs.surfaceContainerHighest.toHexColor(),
                "outline" to cs.outline.toHexColor(),
                "outline-variant" to cs.outlineVariant.toHexColor(),
                "error" to cs.error.toHexColor(),
                "on-error" to cs.onError.toHexColor(),
                "error-container" to cs.errorContainer.toHexColor(),
                "on-error-container" to cs.onErrorContainer.toHexColor(),
            ),
        )
    }
}

/**
 * 可复用的插件 HTML WebView 容器（用于 page / sheet / dialog / floating 及内嵌 html / 代码块 / 消息部件挂载）。
 * - 自动注入宿主 Material 3 颜色变量（`--rikka-primary`、`--rikka-surface-container` 等）与透明背景；
 * - 当 [autoHeight] 为 true 时，自动监听内部 DOM 高度变化并实时伸缩容器高度（介于 [minHeightDp]..[maxHeightDp]）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebViewBox(
    pluginId: String,
    path: String,
    argsJson: String = "null",
    inlineHtml: String? = null,
    autoHeight: Boolean = false,
    initialHeightDp: Int = 200,
    minHeightDp: Int = 36,
    maxHeightDp: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    val host = koinInject<PluginHost>()
    val installed by host.installed.collectAsStateWithLifecycle()
    val plugin = installed.find { it.id == pluginId }
    val scope = rememberCoroutineScope()
    val themeTokens = rememberPluginThemeTokens()

    if (plugin == null) {
        Text("插件 $pluginId 未安装", modifier = modifier.padding(16.dp))
        return
    }
    val file = remember(plugin, path) {
        if (path.isBlank()) null else File(plugin.dir, path).canonicalFile
    }
    if (inlineHtml == null && (file == null || !file.path.startsWith(plugin.dir.canonicalPath + File.separator) || !file.isFile)) {
        Text("面板文件不存在: $path", modifier = modifier.padding(16.dp))
        return
    }

    var dynamicHeightDp by remember(pluginId, path, inlineHtml, initialHeightDp) {
        mutableIntStateOf(initialHeightDp.coerceIn(minHeightDp, maxHeightDp))
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(pluginId, path, inlineHtml) {
        onDispose { webViewRef?.destroy() }
    }
    LaunchedEffect(webViewRef, pluginId, path) {
        val wv = webViewRef ?: return@LaunchedEffect
        PluginUiEvents.events.collect { ev ->
            if (ev is PluginUiEvent.EmitToPanel && ev.pluginId == pluginId && (ev.path == null || ev.path == path)) {
                val js = "window.__rikkaPanelDispatch && window.__rikkaPanelDispatch(${JsonPrimitive(ev.event)}, ${JsonPrimitive(ev.dataJson)})"
                wv.evaluateJavascript(js, null)
            }
        }
    }

    val effectiveModifier = if (autoHeight) {
        modifier.height(dynamicHeightDp.dp)
    } else {
        modifier
    }

    AndroidView(
        modifier = effectiveModifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (autoHeight) {
                            view?.evaluateJavascript("window.__rikkaMeasureHeight && window.__rikkaMeasureHeight()", null)
                        }
                    }
                }
                addJavascriptInterface(
                    PanelBridge(
                        webView = this,
                        host = host,
                        pluginId = pluginId,
                        scope = scope,
                        onReportHeight = { h ->
                            if (autoHeight && h > 0) {
                                dynamicHeightDp = h.coerceIn(minHeightDp, maxHeightDp)
                            }
                        },
                    ),
                    "__rikkaPanel",
                )
                val rawHtml = inlineHtml ?: file!!.readText()
                val baseUrl = (file?.parentFile ?: plugin.dir).toURI().toString()
                val injectBlock = "<style id=\"__rikka_theme\">${themeTokens.cssVariables}</style>\n" +
                    "<script>${panelBootstrap(argsJson, themeTokens.themeJson, autoHeight)}</script>\n"
                val html = injectBlock + rawHtml
                loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * 全局插件面板覆盖层：处理 `sheet`、`dialog`、`floating` 三种打开方式。
 * 放在 RouteActivity 顶层，可在任意页面弹出或悬浮（floating 支持最小化折叠与自定义宽高）。
 */
@Composable
fun PluginPanelOverlays() {
    val host = koinInject<PluginHost>()
    val installed by host.installed.collectAsStateWithLifecycle()
    val panels = remember { mutableStateListOf<PluginUiEvent.OpenPanel>() }

    LaunchedEffect(Unit) {
        PluginUiEvents.events.collect { ev ->
            when (ev) {
                is PluginUiEvent.OpenPanel -> if (ev.presentation != "page") {
                    panels.removeAll { it.pluginId == ev.pluginId && it.path == ev.path && it.presentation == ev.presentation }
                    panels.add(ev)
                }
                is PluginUiEvent.ClosePanel -> {
                    panels.removeAll { it.pluginId == ev.pluginId && (ev.path == null || it.path == ev.path) }
                }
                else -> Unit
            }
        }
    }

    panels.toList().forEach { panel ->
        val title = panel.title ?: installed.find { it.id == panel.pluginId }?.manifest?.name ?: panel.pluginId
        val reqHeight = (panel.heightDp ?: 420).coerceIn(160, 680)
        val reqWidth = (panel.widthDp ?: 320).coerceIn(220, 520)
        when (panel.presentation) {
            "sheet" -> {
                me.rerere.rikkahub.ui.custom.UIModalBottomSheet(
                    onDismissRequest = { panels.remove(panel) },
                    sheetState = rememberBottomSheetState(
                        initialValue = SheetValue.Expanded,
                        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 640.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        PluginWebViewBox(
                            pluginId = panel.pluginId,
                            path = panel.path,
                            argsJson = panel.argsJson,
                            autoHeight = panel.heightDp == null,
                            initialHeightDp = reqHeight,
                            minHeightDp = 180,
                            maxHeightDp = 560,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(reqHeight.dp),
                        )
                    }
                }
            }

            "dialog" -> {
                AlertDialog(
                    onDismissRequest = { panels.remove(panel) },
                    title = { Text(title) },
                    text = {
                        PluginWebViewBox(
                            pluginId = panel.pluginId,
                            path = panel.path,
                            argsJson = panel.argsJson,
                            autoHeight = panel.heightDp == null,
                            initialHeightDp = (panel.heightDp ?: 340).coerceIn(140, 520),
                            minHeightDp = 120,
                            maxHeightDp = 500,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((panel.heightDp ?: 340).coerceIn(140, 520).dp),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { panels.remove(panel) }) {
                            Text("关闭")
                        }
                    },
                )
            }

            "floating" -> {
                var offsetX by remember(panel) { mutableFloatStateOf(32f) }
                var offsetY by remember(panel) { mutableFloatStateOf(160f) }
                var minimized by remember(panel) { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (minimized) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                                .clip(RoundedCornerShape(50))
                                .pointerInput(panel) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                    }
                                }
                                .clickable { minimized = false },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(HugeIcons.Puzzle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(title, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    } else {
                        ElevatedCard(
                            modifier = Modifier
                                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                                .width(reqWidth.dp)
                                .height((panel.heightDp ?: 360).coerceIn(180, 600).dp),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerInput(panel) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                offsetX += dragAmount.x
                                                offsetY += dragAmount.y
                                            }
                                        }
                                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { minimized = true },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                HugeIcons.ArrowDown01,
                                                contentDescription = "最小化",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { panels.remove(panel) },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                HugeIcons.Cancel01,
                                                contentDescription = "关闭",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                PluginWebViewBox(
                                    pluginId = panel.pluginId,
                                    path = panel.path,
                                    argsJson = panel.argsJson,
                                    autoHeight = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun panelBootstrap(argsJson: String, themeJson: String, autoHeight: Boolean) = """
(function () {
  if (window.rikka) return;
  const pending = {};
  const listeners = {};
  let seq = 0;
  let lastHeight = 0;
  window.__rikkaPanelResolve = function (id, ok, json) {
    const p = pending[id]; delete pending[id];
    if (!p) return;
    if (ok) p.resolve(json === "null" ? null : JSON.parse(json)); else p.reject(new Error(json));
  };
  window.__rikkaPanelDispatch = function (event, dataJson) {
    const list = listeners[event] || [];
    const data = dataJson === "null" ? null : JSON.parse(dataJson);
    list.slice().forEach(function (fn) { try { fn(data); } catch (e) { console.error(e); } });
  };
  function measureHeight() {
    if (!$autoHeight) return;
    const b = document.body;
    const d = document.documentElement;
    if (!b && !d) return;
    const h = Math.ceil(Math.max(
      b ? b.scrollHeight : 0,
      b ? b.offsetHeight : 0,
      d ? d.scrollHeight : 0,
      d ? d.offsetHeight : 0
    ));
    if (h > 0 && Math.abs(h - lastHeight) > 2) {
      lastHeight = h;
      __rikkaPanel.reportHeight(h);
    }
  }
  window.__rikkaMeasureHeight = measureHeight;
  if ($autoHeight) {
    window.addEventListener("DOMContentLoaded", function () {
      measureHeight();
      if (typeof ResizeObserver !== "undefined" && document.body) {
        new ResizeObserver(measureHeight).observe(document.body);
      }
      if (typeof MutationObserver !== "undefined" && document.documentElement) {
        new MutationObserver(measureHeight).observe(document.documentElement, { childList: true, subtree: true, attributes: true });
      }
    });
    window.addEventListener("load", measureHeight);
    setTimeout(measureHeight, 60);
    setTimeout(measureHeight, 250);
  }
  window.rikka = {
    args: $argsJson,
    theme: $themeJson,
    call: function (name, args) {
      return new Promise(function (resolve, reject) {
        const id = String(++seq);
        pending[id] = { resolve: resolve, reject: reject };
        __rikkaPanel.call(id, name, JSON.stringify(args === undefined ? null : args));
      });
    },
    on: function (event, fn) {
      if (!listeners[event]) listeners[event] = [];
      listeners[event].push(fn);
      return function () {
        listeners[event] = (listeners[event] || []).filter(function (f) { return f !== fn; });
      };
    },
    resize: function (heightDp) {
      const h = Math.ceil(Number(heightDp) || 0);
      if (h > 0) __rikkaPanel.reportHeight(h);
    },
    close: function () {
      __rikkaPanel.close();
    },
  };
})();
""".trimIndent()

private class PanelBridge(
    private val webView: WebView,
    private val host: PluginHost,
    private val pluginId: String,
    private val scope: CoroutineScope,
    private val onReportHeight: (Int) -> Unit = {},
) {
    @JavascriptInterface
    fun call(id: String, name: String, argsJson: String) {
        scope.launch {
            val (ok, payload) = runCatching {
                host.callExposed(pluginId, name, PluginJson.parseToJsonElement(argsJson)).toString()
            }.fold({ true to it }, { e ->
                host.recordError(pluginId, e)
                false to (e.message ?: e.javaClass.name)
            })
            withContext(Dispatchers.Main) {
                val js = "window.__rikkaPanelResolve(${JsonPrimitive(id)}, $ok, ${JsonPrimitive(payload)})"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    @JavascriptInterface
    fun reportHeight(heightDp: Int) {
        scope.launch(Dispatchers.Main) {
            onReportHeight(heightDp)
        }
    }

    @JavascriptInterface
    fun close() {
        PluginUiEvents.emit(PluginUiEvent.ClosePanel(pluginId))
    }
}
