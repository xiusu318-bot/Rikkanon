package me.rerere.rikkahub.ui.pages.extensions.plugin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.plugin.host.PluginHost
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.util.zip.ZipInputStream

/** 插件管理：列表、开关、优先级、安装。插件挂到哪里、有没有自己的界面由插件决定。 */
@Composable
fun PluginsPage() {
    val host = koinInject<PluginHost>()
    val installed by host.installed.collectAsStateWithLifecycle()
    val states by host.states.collectAsStateWithLifecycle()
    val active by host.activeRuntimes.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun reportError(e: Throwable) {
        toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
    }

    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val files = withContext(Dispatchers.IO) { readPluginPackage(context, uri) }
                host.install(files)
            }.onSuccess { toaster.show("已安装 ${it.manifest.name}（默认未启用）") }
                .onFailure(::reportError)
        }
    }

    val sorted = installed.sortedWith(compareBy({ states[it.id]?.priority ?: 0 }, { it.id }))

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("插件") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { installLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Icon(HugeIcons.Add01, contentDescription = "安装插件")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "插件拥有与应用相同的能力（读写对话与设置、联网、执行工作区命令等）。只安装自己编写或完全理解的代码，不要使用来路不明的插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item {
                CardGroup(title = { Text("已安装（按执行顺序）") }) {
                    if (sorted.isEmpty()) {
                        item(
                            headlineContent = { Text("还没有插件") },
                            supportingContent = { Text("点右上角 + 选择插件包（.zip，包含 manifest.json 与入口 JS）") },
                        )
                    }
                    sorted.forEachIndexed { index, plugin ->
                        val enabled = states[plugin.id]?.enabled == true
                        val runtime = active[plugin.id]
                        val running = runtime != null && runtime.fatalError == null
                        item(
                            onClick = { navController.navigate(Screen.PluginDetail(plugin.id)) },
                            leadingContent = {
                                CardGroupIcon(
                                    HugeIcons.Puzzle,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            },
                            headlineContent = { Text("${plugin.manifest.name}  v${plugin.manifest.version}") },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(plugin.id)
                                        if (runtime?.fatalError != null) append(" · 已终止，见详情")
                                        else if (enabled && !running) append(" · 启用失败，见详情")
                                    }
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(enabled = index > 0, onClick = {
                                        scope.launch { swapPriority(host, sorted, index, index - 1) }
                                    }) { Icon(HugeIcons.ArrowUp01, contentDescription = "上移") }
                                    IconButton(enabled = index < sorted.lastIndex, onClick = {
                                        scope.launch { swapPriority(host, sorted, index, index + 1) }
                                    }) { Icon(HugeIcons.ArrowDown01, contentDescription = "下移") }
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { checked ->
                                            scope.launch {
                                                runCatching { host.setEnabled(plugin.id, checked) }.onFailure {
                                                    host.recordError(plugin.id, it)
                                                    reportError(it)
                                                }
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 重新编号优先级：交换两项后按列表顺序写回 0..n。 */
private suspend fun swapPriority(host: PluginHost, list: List<me.rerere.plugin.store.InstalledPlugin>, a: Int, b: Int) {
    val reordered = list.toMutableList().apply { add(b, removeAt(a)) }
    reordered.forEachIndexed { i, p -> host.updateState(p.id) { it.copy(priority = i) } }
}

/** 读取插件包：zip（可带一层顶级目录）。 */
internal fun readPluginPackage(context: android.content.Context, uri: Uri): Map<String, ByteArray> {
    val entries = mutableMapOf<String, ByteArray>()
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取所选文件" }
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            }
        }
    }
    require(entries.isNotEmpty()) { "不是有效的插件包（需要 zip，包含 manifest.json）" }
    if ("manifest.json" in entries) return entries
    // 允许压缩时多包了一层目录
    val manifestPath = entries.keys.singleOrNull { it.endsWith("/manifest.json") && it.count { c -> c == '/' } == 1 }
        ?: error("插件包中找不到 manifest.json")
    val prefix = manifestPath.removeSuffix("manifest.json")
    return entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
}
