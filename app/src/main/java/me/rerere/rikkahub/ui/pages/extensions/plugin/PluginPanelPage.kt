package me.rerere.rikkahub.ui.pages.extensions.plugin

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.runtime.PluginJson
import me.rerere.rikkahub.data.plugin.PluginUiEvent
import me.rerere.rikkahub.data.plugin.PluginUiEvents
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

/**
 * 插件自有面板（presentation = page）。WebView 只负责界面：
 * 页面中 `window.rikka.call(name, args)` 调用插件 `rikka.expose(name, fn)` 暴露的方法，返回 Promise；
 * `window.rikka.on(event, fn)` 监听插件 `rikka.ui.emit(event, data)` 推送的事件；
 * `window.rikka.args` 为 ui.open 传入的参数。
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * 可复用的插件 HTML WebView 容器（用于 page / sheet / dialog / floating 及内嵌 html 挂载）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebViewBox(
    pluginId: String,
    path: String,
    argsJson: String = "null",
    inlineHtml: String? = null,
    modifier: Modifier = Modifier,
) {
    val host = koinInject<PluginHost>()
    val installed by host.installed.collectAsStateWithLifecycle()
    val plugin = installed.find { it.id == pluginId }
    val scope = rememberCoroutineScope()

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

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(pluginId, path, inlineHtml) {
        onDispose { webViewRef?.destroy() }
    }
    LaunchedEffect(webViewRef, pluginId) {
        val wv = webViewRef ?: return@LaunchedEffect
        PluginUiEvents.events.collect { ev ->
            if (ev is PluginUiEvent.EmitToPanel && ev.pluginId == pluginId) {
                val js = "window.__rikkaPanelDispatch && window.__rikkaPanelDispatch(${JsonPrimitive(ev.event)}, ${JsonPrimitive(ev.dataJson)})"
                wv.evaluateJavascript(js, null)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                webViewClient = WebViewClient()
                addJavascriptInterface(PanelBridge(this, host, pluginId, scope), "__rikkaPanel")
                val rawHtml = inlineHtml ?: file!!.readText()
                val baseUrl = (file?.parentFile ?: plugin.dir).toURI().toString()
                val html = "<script>" + panelBootstrap(argsJson) + "</script>\n" + rawHtml
                loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * 全局插件面板覆盖层：处理 `sheet`、`dialog`、`floating` 三种打开方式。
 * 放在 RouteActivity 顶层，可在任意页面弹出或悬浮。
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
        when (panel.presentation) {
            "sheet" -> {
                ModalBottomSheet(
                    onDismissRequest = { panels.remove(panel) },
                    sheetState = rememberBottomSheetState(
                        initialValue = SheetValue.Expanded,
                        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 560.dp)
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
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
                Box(modifier = Modifier.fillMaxSize()) {
                    ElevatedCard(
                        modifier = Modifier
                            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                            .width(320.dp)
                            .height(360.dp),
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
                                Text(title, style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = { panels.remove(panel) }) {
                                    Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                                }
                            }
                            PluginWebViewBox(
                                pluginId = panel.pluginId,
                                path = panel.path,
                                argsJson = panel.argsJson,
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

private fun panelBootstrap(argsJson: String) = """
(function () {
  if (window.rikka) return;
  const pending = {};
  const listeners = {};
  let seq = 0;
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
  window.rikka = {
    args: $argsJson,
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
    fun close() {
        PluginUiEvents.emit(PluginUiEvent.ClosePanel(pluginId))
    }
}
