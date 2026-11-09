package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.ProxyProfile
import me.rerere.rikkahub.data.datastore.ProxyType
import me.rerere.rikkahub.data.remote.OutboundTarget
import me.rerere.rikkahub.data.remote.configureOutboundProxy
import me.rerere.rikkahub.data.remote.isValid
import me.rerere.rikkahub.data.remote.parseProxyProfileOrNull
import me.rerere.rikkahub.data.remote.resolveProxy
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val PROXY_TEST_URL = "https://www.google.com/generate_204"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val httpClient = koinInject<OkHttpClient>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val networkSetting = settings.networkSetting
    var userAgent by remember(networkSetting.userAgent) {
        mutableStateOf(networkSetting.userAgent)
    }

    var proxyListSheetVisible by remember { mutableStateOf(false) }
    var outboundSheetVisible by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var isCreatingNewProfile by remember { mutableStateOf(false) }

    val defaultUserAgent = "RikkaHub-Android/${BuildConfig.VERSION_NAME}"
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val toaster = LocalToaster.current

    // 记录每个代理节点的测速状态: null=未测, -1L=测速中, >0L=耗时ms, -2L=失败
    val testStatusMap = remember { mutableStateMapOf<Uuid, Long>() }

    fun updateUserAgent(value: String) {
        userAgent = value
        vm.updateSettings {
            it.copy(
                networkSetting = it.networkSetting.copy(userAgent = value),
            )
        }
    }

    suspend fun runProxyTest(profile: ProxyProfile): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.currentTimeMillis()
            val testClient = httpClient.newBuilder()
                .configureOutboundProxy { profile }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
            testClient.newCall(
                Request.Builder()
                    .url(PROXY_TEST_URL)
                    .head()
                    .build()
            ).execute().use { response ->
                if (response.code !in 200..399) {
                    throw IOException("HTTP ${response.code} ${response.message}")
                }
            }
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }
    }

    fun testSavedProfile(profile: ProxyProfile) {
        if (testStatusMap[profile.id] == -1L) return
        testStatusMap[profile.id] = -1L
        scope.launch {
            val res = runProxyTest(profile)
            res.onSuccess { ms ->
                testStatusMap[profile.id] = ms
                toaster.show(
                    "${profile.displayName}: ${resources.getString(R.string.backup_page_connection_success)} (${ms}ms)",
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                testStatusMap[profile.id] = -2L
                toaster.show(
                    resources.getString(
                        R.string.backup_page_connection_failed,
                        error.message.orEmpty(),
                    ),
                    type = ToastType.Error,
                )
            }
        }
    }

    if (proxyListSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { proxyListSheetVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ProxyListSheetContent(
                proxies = networkSetting.proxies,
                testStatusMap = testStatusMap,
                onAdd = {
                    isCreatingNewProfile = true
                    editingProfile = ProxyProfile()
                },
                onEdit = { profile ->
                    isCreatingNewProfile = false
                    editingProfile = profile
                },
                onTest = ::testSavedProfile,
                onDelete = { profile ->
                    vm.updateSettings { current ->
                        val remaining = current.networkSetting.proxies.filterNot { it.id == profile.id }
                        current.copy(
                            networkSetting = current.networkSetting.copy(
                                proxies = remaining,
                                llmProxyId = current.networkSetting.llmProxyId?.takeIf { it != profile.id },
                                workspaceProxyId = current.networkSetting.workspaceProxyId?.takeIf { it != profile.id },
                                mcpProxyId = current.networkSetting.mcpProxyId?.takeIf { it != profile.id },
                            )
                        )
                    }
                },
            )
        }
    }

    if (outboundSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { outboundSheetVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            OutboundRoutingSheetContent(
                networkSetting = networkSetting,
                onApplyAll = { proxyId ->
                    vm.updateSettings { current ->
                        current.copy(
                            networkSetting = current.networkSetting.copy(
                                llmProxyId = proxyId,
                                workspaceProxyId = proxyId,
                                mcpProxyId = proxyId,
                            )
                        )
                    }
                },
                onUpdateTarget = { target, proxyId ->
                    vm.updateSettings { current ->
                        val nextNet = when (target) {
                            OutboundTarget.LLM -> current.networkSetting.copy(llmProxyId = proxyId)
                            OutboundTarget.WORKSPACE -> current.networkSetting.copy(workspaceProxyId = proxyId)
                            OutboundTarget.MCP -> current.networkSetting.copy(mcpProxyId = proxyId)
                        }
                        current.copy(networkSetting = nextNet)
                    }
                },
            )
        }
    }

    editingProfile?.let { initialProfile ->
        ProxyProfileEditorDialog(
            initial = initialProfile,
            isNew = isCreatingNewProfile,
            onDismiss = { editingProfile = null },
            onTestDraft = { draft -> runProxyTest(draft) },
            onSave = { saved ->
                vm.updateSettings { current ->
                    val existing = current.networkSetting.proxies
                    val updatedList = if (existing.any { it.id == saved.id }) {
                        existing.map { if (it.id == saved.id) saved else it }
                    } else {
                        existing + saved
                    }
                    // 当用户首次新建第一个代理且所有出站均为空时，保持各出站由用户在“出站设置”自由选择
                    current.copy(
                        networkSetting = current.networkSetting.copy(
                            proxies = updatedList,
                        )
                    )
                }
                editingProfile = null
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_network)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = networkSetting.enableAutoRetry,
                        onCheckedChange = { enabled ->
                            vm.updateSettings {
                                it.copy(
                                    networkSetting = it.networkSetting.copy(
                                        enableAutoRetry = enabled,
                                    ),
                                )
                            }
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_auto_retry_desc))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_auto_retry))
                        },
                    )
                }
            }
            item {
                CardGroup(
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_user_agent))
                    },
                ) {
                    formItem {
                        OutlinedTextField(
                            value = userAgent,
                            onValueChange = ::updateUserAgent,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(defaultUserAgent) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        R.string.setting_page_preferences_network_user_agent_desc,
                                        defaultUserAgent,
                                    )
                                )
                            },
                            singleLine = true,
                        )
                        TextButton(
                            onClick = { updateUserAgent("") },
                            modifier = Modifier.align(Alignment.End),
                            enabled = userAgent.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.setting_model_page_reset_to_default))
                        }
                    }
                }
            }
            item {
                CardGroup(
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_proxy))
                    },
                ) {
                    item(
                        onClick = { proxyListSheetVisible = true },
                        headlineContent = {
                            Text("管理代理")
                        },
                        supportingContent = {
                            val count = networkSetting.proxies.size
                            Text(
                                if (count == 0) {
                                    "支持 HTTP / HTTPS / SOCKS5 代理，点击新建与测试"
                                } else {
                                    "已保存 $count 个代理（${networkSetting.proxies.joinToString("、") { it.displayName }}）"
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        onClick = { outboundSheetVisible = true },
                        headlineContent = {
                            Text("出站设置")
                        },
                        supportingContent = {
                            Text(networkSetting.outboundSummaryText())
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

private fun NetworkSetting.outboundSummaryText(): String {
    val llm = resolveProxy(OutboundTarget.LLM)
    val ws = resolveProxy(OutboundTarget.WORKSPACE)
    val mcp = resolveProxy(OutboundTarget.MCP)
    if (llm == null && ws == null && mcp == null) {
        return "全部直连（不使用代理）"
    }
    if (llm != null && llm.id == ws?.id && llm.id == mcp?.id) {
        return "全部出站 → ${llm.displayName}"
    }
    return "LLM: ${llm?.displayName ?: "直连"} · 工作区: ${ws?.displayName ?: "直连"} · MCP: ${mcp?.displayName ?: "直连"}"
}

@Composable
private fun ProxyListSheetContent(
    proxies: List<ProxyProfile>,
    testStatusMap: Map<Uuid, Long>,
    onAdd: () -> Unit,
    onEdit: (ProxyProfile) -> Unit,
    onTest: (ProxyProfile) -> Unit,
    onDelete: (ProxyProfile) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "管理代理",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "支持 HTTP、HTTPS（TLS 隧道）与 SOCKS5（远端 DNS）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onAdd) {
                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建代理")
            }
        }

        if (proxies.isEmpty()) {
            OutlinedCard(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "暂无已保存的代理",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "点击此处或右上角「新建代理」添加，支持在编辑面板直接测试连通性。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(proxies, key = { it.id }) { profile ->
                    val status = testStatusMap[profile.id]
                    Card(
                        onClick = { onEdit(profile) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = profile.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            text = profile.type.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (profile.username.isNotBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                        ) {
                                            Text(
                                                text = "认证",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = profile.displayUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            FilledTonalButton(
                                onClick = { onTest(profile) },
                                enabled = status != -1L,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                when {
                                    status == -1L -> LoadingIndicator(modifier = Modifier.size(18.dp))
                                    status != null && status > 0L -> Text("${status}ms")
                                    status == -2L -> Text("失败")
                                    else -> Text(stringResource(R.string.setting_provider_page_test))
                                }
                            }

                            IconButton(onClick = { onEdit(profile) }) {
                                Icon(HugeIcons.PencilEdit01, contentDescription = "编辑")
                            }

                            IconButton(onClick = { onDelete(profile) }) {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
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
private fun OutboundRoutingSheetContent(
    networkSetting: NetworkSetting,
    onApplyAll: (Uuid?) -> Unit,
    onUpdateTarget: (OutboundTarget, Uuid?) -> Unit,
) {
    val proxies = networkSetting.proxies
    val llmProxy = networkSetting.resolveProxy(OutboundTarget.LLM)
    val wsProxy = networkSetting.resolveProxy(OutboundTarget.WORKSPACE)
    val mcpProxy = networkSetting.resolveProxy(OutboundTarget.MCP)

    val allSame = llmProxy?.id == wsProxy?.id && llmProxy?.id == mcpProxy?.id
    val unifiedProxyId = if (allSame) llmProxy?.id else null
    var quickMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "出站设置",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "选择各模块出网使用的代理；若代理被删除将自动回退为空代理（直连）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 一键全出站快捷设置卡片
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "一键全出站",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (allSame) {
                                "当前全部模块统一走：${llmProxy?.displayName ?: "空代理（直连）"}"
                            } else {
                                "当前各模块分别独立设置"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onApplyAll(null) },
                            enabled = !(allSame && unifiedProxyId == null),
                        ) {
                            Text("一键全直连")
                        }

                        if (proxies.isNotEmpty()) {
                            FilledTonalButton(onClick = { quickMenuExpanded = true }) {
                                Text("全部设为")
                                Spacer(Modifier.width(4.dp))
                                Icon(HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = quickMenuExpanded,
                                onDismissRequest = { quickMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("空代理（直连）") },
                                    leadingIcon = {
                                        RadioButton(selected = allSame && unifiedProxyId == null, onClick = null)
                                    },
                                    onClick = {
                                        quickMenuExpanded = false
                                        onApplyAll(null)
                                    },
                                )
                                proxies.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(p.displayName)
                                                Text(
                                                    text = p.displayUrl,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            RadioButton(selected = allSame && unifiedProxyId == p.id, onClick = null)
                                        },
                                        onClick = {
                                            quickMenuExpanded = false
                                            onApplyAll(p.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // 三大独立出站模块
        OutboundTargetSelectorCard(
            title = "LLM 本体与常规请求",
            description = "模型对话、拉取模型列表及搜索/语音/媒体/备份等其他常规请求",
            selectedProxy = llmProxy,
            proxies = proxies,
            onSelect = { onUpdateTarget(OutboundTarget.LLM, it) },
        )

        OutboundTargetSelectorCard(
            title = "工作区（本地沙盒）",
            description = "本地 PRoot 沙盒环境（workspace_shell / 终端 / 镜像下载）透明出网",
            selectedProxy = wsProxy,
            proxies = proxies,
            onSelect = { onUpdateTarget(OutboundTarget.WORKSPACE, it) },
        )

        OutboundTargetSelectorCard(
            title = "MCP 服务",
            description = "远程 MCP 服务器连接（SSE / StreamableHTTP）与 OAuth 鉴权",
            selectedProxy = mcpProxy,
            proxies = proxies,
            onSelect = { onUpdateTarget(OutboundTarget.MCP, it) },
        )
    }
}

@Composable
private fun OutboundTargetSelectorCard(
    title: String,
    description: String,
    selectedProxy: ProxyProfile?,
    proxies: List<ProxyProfile>,
    onSelect: (Uuid?) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { menuExpanded = true },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selectedProxy != null) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selectedProxy != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = selectedProxy?.displayName ?: "空代理（直连）",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedProxy != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("空代理（直连）") },
                        leadingIcon = {
                            RadioButton(selected = selectedProxy == null, onClick = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onSelect(null)
                        },
                    )
                    proxies.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(p.displayName)
                                    Text(
                                        text = p.displayUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = {
                                RadioButton(selected = selectedProxy?.id == p.id, onClick = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onSelect(p.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyProfileEditorDialog(
    initial: ProxyProfile,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onTestDraft: suspend (ProxyProfile) -> Result<Long>,
    onSave: (ProxyProfile) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var type by remember(initial.id) { mutableStateOf(initial.type) }
    var hostInput by remember(initial.id) {
        mutableStateOf(
            if (initial.host.isNotBlank()) "${initial.host}:${initial.port}" else ""
        )
    }
    var username by remember(initial.id) { mutableStateOf(initial.username) }
    var password by remember(initial.id) { mutableStateOf(initial.password) }
    var passwordVisible by remember(initial.id) { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testResultSuccess by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    // 支持在地址框粘贴完整 URL (socks5://user:pass@host:port) 或家宽四段式 (host:port:user:pass) 自动识别
    fun onAddressInputChange(raw: String) {
        hostInput = raw
        testResultText = null
        val trimmed = raw.trim()
        val hasScheme = "://" in trimmed
        val hasAt = "@" in trimmed
        val colonCount = trimmed.count { it == ':' }
        if (hasScheme || hasAt || (!trimmed.startsWith("[") && colonCount >= 3)) {
            val parsed = trimmed.parseProxyProfileOrNull(
                id = initial.id,
                name = name,
                defaultType = type,
                fallbackUsername = username,
                fallbackPassword = password,
            )
            if (parsed != null) {
                type = parsed.type
                hostInput = "${parsed.host}:${parsed.port}"
                if (parsed.username.isNotEmpty()) username = parsed.username
                if (parsed.password.isNotEmpty()) password = parsed.password
            }
        }
    }

    val draftProfile: ProxyProfile? = remember(initial.id, name, type, hostInput, username, password) {
        hostInput.parseProxyProfileOrNull(
            id = initial.id,
            name = name.trim(),
            defaultType = type,
            fallbackUsername = username.trim(),
            fallbackPassword = password,
        )?.copy(
            type = type,
            username = username.trim(),
            password = password,
        )?.takeIf { it.isValid() }
    }

    val addressInvalid = hostInput.isNotBlank() && draftProfile == null

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = {
            Text(if (isNew) "新建代理" else "编辑代理")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ProxyType.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ProxyType.entries.size,
                            ),
                            selected = type == item,
                            onClick = {
                                type = item
                                testResultText = null
                            },
                            label = { Text(item.label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称（可选）") },
                    placeholder = { Text("如：美国家宽 / 本地 7890") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = hostInput,
                    onValueChange = ::onAddressInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址与端口") },
                    placeholder = { Text("127.0.0.1:7890") },
                    supportingText = {
                        Text(
                            if (addressInvalid) {
                                stringResource(R.string.setting_page_preferences_network_proxy_invalid)
                            } else {
                                "支持填 host:port，或直接粘贴协议 URL / host:port:user:pass 自动拆解"
                            }
                        )
                    },
                    isError = addressInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        testResultText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.backup_page_username)) },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        testResultText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.backup_page_password)) },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = null,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )

                Spacer(Modifier.height(2.dp))

                // 面板内直接测试按钮与状态展示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FilledTonalButton(
                        onClick = {
                            val profile = draftProfile ?: return@FilledTonalButton
                            if (testing) return@FilledTonalButton
                            testing = true
                            testResultText = null
                            scope.launch {
                                val res = onTestDraft(profile)
                                res.onSuccess { ms ->
                                    testResultSuccess = true
                                    testResultText = "连接成功 (${ms}ms)"
                                }.onFailure { err ->
                                    testResultSuccess = false
                                    testResultText = "连接失败: ${err.message ?: "未知错误"}"
                                }
                                testing = false
                            }
                        },
                        enabled = draftProfile != null && !testing,
                    ) {
                        if (testing) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("测试中...")
                        } else {
                            Text(stringResource(R.string.setting_provider_page_test_connection))
                        }
                    }

                    testResultText?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testResultSuccess == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = { draftProfile?.let(onSave) },
                enabled = draftProfile != null,
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}
