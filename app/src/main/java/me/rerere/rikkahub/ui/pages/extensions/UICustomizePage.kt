package me.rerere.rikkahub.ui.pages.extensions

import android.content.ClipData
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.UIBackgroundSection
import me.rerere.rikkahub.data.model.UIPreset
import me.rerere.rikkahub.data.model.UITypographySection
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.custom.CustomUICodeEngine
import me.rerere.rikkahub.ui.custom.UICodeAnalysis
import me.rerere.rikkahub.ui.custom.UICodeEditor
import me.rerere.rikkahub.ui.custom.UICodeGenerator
import me.rerere.rikkahub.ui.custom.UIEditResult
import me.rerere.rikkahub.ui.custom.UIEditTarget
import me.rerere.rikkahub.ui.custom.UIRuntimeErrors
import me.rerere.rikkahub.ui.custom.UIThemeRuntimes
import me.rerere.rikkahub.ui.custom.resolveUIColor
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.UIPresetThemeOverlay
import me.rerere.ui.components.SelectTextField
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

private val uiPresetPrettyJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

private const val LAST_SELECTED_UI_PRESET_KEY = "last_selected_ui_preset_id"

private fun initialCodeOf(preset: UIPreset): String = preset.code.ifBlank {
    if (preset.backgroundSection.enabled || preset.typographySection.enabled || preset.globalSection.enabled ||
        preset.componentStyles.isNotEmpty() || preset.slots.isNotEmpty() || preset.pages.isNotEmpty() || preset.transitions != null
    ) CustomUICodeEngine.generateCodeFromPreset(preset) else ""
}

@Stable
private class UICodeDraftBuffer(initialCode: String) {
    var text by mutableStateOf(initialCode)
        private set
    var dirty by mutableStateOf(false)
        private set
    var editSeq by mutableIntStateOf(0)
        private set

    fun onUserInput(next: String) {
        text = next
        dirty = true
        editSeq++
    }

    fun syncFromPreset(code: String) {
        text = code
        dirty = false
    }

    fun markFlushed() {
        dirty = false
    }
}

private enum class UICustomizeTab(
    override val icon: ImageVector,
    private val titleText: String,
) : PagerTab {
    CODE_DSL(HugeIcons.Code, "UI 代码"),
    STRUCTURE(HugeIcons.Layers01, "界面结构"),
    BACKGROUND(HugeIcons.PaintBoard, "配色"),
    TYPOGRAPHY(HugeIcons.Sparkles, "正文排版");

    override val label: String
        @Composable get() = titleText
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UICustomizePage(
    initialPresetId: String? = null,
    vm: PromptVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val presets = settings.uiPresets
    var rememberedPresetId by rememberSharedPreferenceString(LAST_SELECTED_UI_PRESET_KEY, "")
    var selectedPresetId by rememberSaveable(initialPresetId) {
        mutableStateOf(
            initialPresetId?.takeIf { id -> presets.any { it.id.toString() == id } }
                ?: rememberedPresetId.takeIf { id -> presets.any { it.id.toString() == id } }
                ?: presets.firstOrNull { it.enabled }?.id?.toString()
                ?: presets.firstOrNull()?.id?.toString().orEmpty()
        )
    }

    LaunchedEffect(selectedPresetId) {
        if (selectedPresetId.isNotBlank()) {
            rememberedPresetId = selectedPresetId
        }
    }

    val currentSavedPreset = remember(presets, selectedPresetId) {
        presets.find { it.id.toString() == selectedPresetId } ?: presets.firstOrNull()
    }

    var draftPreset by remember(currentSavedPreset) {
        mutableStateOf(currentSavedPreset ?: UIPreset(name = "默认界面预设", enabled = true))
    }
    val codeBuffer = remember(draftPreset.id) { UICodeDraftBuffer(initialCodeOf(draftPreset)) }
    LaunchedEffect(draftPreset.code) {
        if (!codeBuffer.dirty) {
            val resolved = initialCodeOf(draftPreset)
            if (codeBuffer.text != resolved) codeBuffer.syncFromPreset(resolved)
        }
    }
    val hasUnsavedChanges by remember(currentSavedPreset, codeBuffer) {
        derivedStateOf {
            currentSavedPreset != null && (draftPreset != currentSavedPreset || (codeBuffer.dirty && codeBuffer.text != currentSavedPreset.code))
        }
    }

    var showPreviewSandbox by rememberSaveable { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    // ---- 可视化编辑 → 改代码（代码是唯一来源）----
    val codeEditor = remember { UICodeEditor() }
    DisposableEffect(Unit) { onDispose { codeEditor.close() } }
    var analysis by remember(draftPreset.id) { mutableStateOf<UICodeAnalysis?>(null) }
    var lockedFields by remember(draftPreset.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val editMutex = remember { Mutex() }

    /** 将代码框本地未落草稿的缓冲同步写回 [draftPreset]（保存、导出、切标签页前立即执行）。 */
    fun flushCodeBuffer(base: UIPreset = draftPreset): UIPreset {
        if (!codeBuffer.dirty) return base
        val latestText = codeBuffer.text
        codeBuffer.markFlushed()
        if (base.code == latestText) return base
        val updated = base.copy(code = latestText)
        if (base === draftPreset) draftPreset = updated
        return updated
    }

    /** 当前代码；旧的无代码预设先由编译结果生成代码。 */
    fun currentCode(): String = if (codeBuffer.dirty) codeBuffer.text else initialCodeOf(draftPreset)

    fun applyEdit(edit: UICodeEdit, onSuccess: (() -> Unit)? = null) {
        scope.launch {
            editMutex.withLock {
                val r = edit(currentCode())
                if (r.ok) {
                    val newCode = r.code!!
                    codeBuffer.syncFromPreset(newCode)
                    draftPreset = draftPreset.copy(code = newCode)
                    onSuccess?.invoke()
                } else {
                    toaster.show(r.message, type = ToastType.Warning)
                }
            }
        }
    }

    // 代码框输入缓冲：敲字过程只更新本地缓冲，避免大脚本每个按键都拷贝草稿并重组整页预览；停手 0.16 秒再同步回草稿
    LaunchedEffect(codeBuffer.editSeq) {
        if (codeBuffer.editSeq == 0 || !codeBuffer.dirty) return@LaunchedEffect
        delay(160)
        flushCodeBuffer()
    }

    // 表单（配色、排版）：预览立刻变，代码改写停手 0.15 秒后批量做
    val pendingFormEdits = remember { mutableStateMapOf<UIFormField, JsonElement?>() }
    var pendingTick by remember { mutableIntStateOf(0) }
    fun queueFormEdit(field: UIFormField, value: JsonElement?) {
        pendingFormEdits[field] = value
        pendingTick++
    }
    LaunchedEffect(pendingTick) {
        if (pendingTick == 0) return@LaunchedEffect
        delay(150)
        val edits = pendingFormEdits.toMap()
        pendingFormEdits.clear()
        applyEdit({ code ->
            var c = code
            for ((f, v) in edits) {
                val r = if (v == null) codeEditor.removeProp(c, f.target, f.path) else codeEditor.setProp(c, f.target, f.path, v)
                if (!r.ok) return@applyEdit r
                c = r.code!!
            }
            UIEditResult(c, null, null)
        })
    }

    // 代码变了：停手后重新分析（界面结构、表单哪些项能改）并编译（预览）
    LaunchedEffect(draftPreset.id, draftPreset.code) {
        delay(80)
        val code = currentCode()
        analysis = codeEditor.analyze(code)
        lockedFields = codeEditor.lockedFields(code, UI_FORM_FIELDS)
        val d = draftPreset
        if (d.code == code && d.compiledCodeHash != CustomUICodeEngine.codeHash(code)) {
            val compiled = try {
                CustomUICodeEngine.compileUICode(code, d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                d.copy(compileError = e.message ?: e.toString())
            }
            if (draftPreset.code == code) draftPreset = compiled
        }
    }

    fun saveDraftToStore(target: UIPreset = flushCodeBuffer()) = scope.launch {
        // 以代码为准重新编译；编译失败仍保存（记录错误，各接管区保持上次成功的结果）
        val compiled = try {
            CustomUICodeEngine.compileUICode(target.code, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            target.copy(compileError = e.message ?: e.toString())
        }
        UIRuntimeErrors.clear(compiled.id)
        draftPreset = compiled
        vm.updateSettings { latest ->
            val exists = latest.uiPresets.any { it.id == compiled.id }
            val updatedList = if (exists) {
                latest.uiPresets.map { item ->
                    if (item.id == compiled.id) compiled
                    else if (compiled.enabled) item.copy(enabled = false)
                    else item
                }
            } else {
                val cleared = if (compiled.enabled) latest.uiPresets.map { it.copy(enabled = false) } else latest.uiPresets
                cleared + compiled
            }
            latest.copy(uiPresets = updatedList)
        }
        val error = compiled.compileError
        if (error != null) {
            toaster.show("已保存「${compiled.name}」，但 UI 代码编译失败：$error", type = ToastType.Error)
        } else {
            toaster.show("已保存界面预设「${compiled.name}」", type = ToastType.Success)
        }
    }

    val pagerState = rememberPagerState { UICustomizeTab.entries.size }
    LaunchedEffect(pagerState.currentPage) {
        if (UICustomizeTab.entries[pagerState.currentPage] != UICustomizeTab.CODE_DSL) {
            flushCodeBuffer()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    val collapsedFraction = scrollBehavior.state.collapsedFraction
                    val switchVisible = collapsedFraction < 0.5f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "界面自定义",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        me.rerere.rikkahub.ui.custom.UISwitch(
                            checked = settings.enableCustomUi,
                            onCheckedChange = if (switchVisible) {
                                { enabled ->
                                    vm.updateSettings { latest ->
                                        latest.copy(enableCustomUi = enabled)
                                    }
                                }
                            } else null,
                            modifier = Modifier
                                .padding(start = 12.dp, end = 12.dp)
                                .graphicsLayer {
                                    alpha = (1f - collapsedFraction * 2f).coerceIn(0f, 1f)
                                },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = settings.enableCustomUi && hasUnsavedChanges,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "当前预设存在未保存的草稿改动",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    if (currentSavedPreset != null) {
                                        draftPreset = currentSavedPreset
                                        codeBuffer.syncFromPreset(initialCodeOf(currentSavedPreset))
                                        toaster.show("已还原未保存的修改")
                                    }
                                }
                            ) {
                                Text("放弃")
                            }
                            Button(onClick = { saveDraftToStore(flushCodeBuffer()) }) {
                                Icon(HugeIcons.Tick01, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("保存预设")
                            }
                        }
                    }
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (!settings.enableCustomUi) {
            // 总开关关闭：后面所有内容全部隐藏（只保留右上角总开关）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            return@Scaffold
        }

        if (presets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.PaintBoard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "还没有界面预设",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "宿主不内置任何样式预设。你可以新建空白预设自己写 UI 代码，或直接导入现有的 .ui.js / .json 主题文件。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { showCreateDialog = true }) {
                                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("新建空白预设")
                            }
                            Button(onClick = { showImportDialog = true }) {
                                Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("导入主题")
                            }
                        }
                    }
                }
            }
        } else {
            val runtime = remember(draftPreset.id, draftPreset.compiledCodeHash) {
                UIThemeRuntimes.get(draftPreset)
            }
            val globalErrors by UIRuntimeErrors.flowOf(draftPreset.id).collectAsState()
            val runtimeErrors = runtime?.errors?.collectAsState()?.value.orEmpty()
            val mergedErrors = remember(globalErrors, runtimeErrors) {
                (globalErrors + runtimeErrors).distinct().takeLast(50)
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 预览最多占 40% 高度（超出在预览内滚动），其余留给下面的标签页内容
                val previewMaxHeight = maxHeight * 0.4f
                Column(modifier = Modifier.fillMaxSize()) {
                    UIPresetControlHeader(
                        presets = presets,
                        draftPreset = draftPreset,
                        onSelectPreset = { id ->
                            selectedPresetId = id.toString()
                        },
                        onToggleEnabled = { enabled ->
                            val updated = flushCodeBuffer().copy(enabled = enabled)
                            draftPreset = updated
                            saveDraftToStore(updated)
                        },
                        onOpenCreate = { showCreateDialog = true },
                        onOpenRename = { showRenameDialog = true },
                        onOpenImport = { showImportDialog = true },
                        onExport = {
                            val jsonStr = uiPresetPrettyJson.encodeToString(UIPreset.serializer(), flushCodeBuffer())
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ui_preset", jsonStr)))
                            }
                            toaster.show("预设 JSON 已复制到剪贴板", type = ToastType.Success)
                        },
                        onDelete = {
                            val remaining = presets.filterNot { it.id == draftPreset.id }
                            selectedPresetId = remaining.firstOrNull()?.id?.toString().orEmpty()
                            vm.updateSettings { it.copy(uiPresets = remaining) }
                            toaster.show("已删除预设")
                        },
                        showPreview = showPreviewSandbox,
                        onTogglePreview = { showPreviewSandbox = !showPreviewSandbox },
                    )

                    if (mergedErrors.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "运行时错误（${mergedErrors.size}）：" + mergedErrors.last(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { UIRuntimeErrors.clear(draftPreset.id) }) {
                                    Text("清空")
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showPreviewSandbox,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        UIPresetLivePreviewSandbox(
                            preset = draftPreset,
                            displaySetting = settings.displaySetting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .heightIn(max = previewMaxHeight),
                        )
                    }

                    PagerTabs(
                        pagerState = pagerState,
                        tabs = UICustomizeTab.entries,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    )

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) { page ->
                        when (UICustomizeTab.entries[page]) {
                            UICustomizeTab.CODE_DSL -> UICodeDslSectionEditor(
                                preset = draftPreset,
                                codeBuffer = codeBuffer,
                                runtimeErrors = mergedErrors,
                                onApplyCompiled = { updated ->
                                    UIRuntimeErrors.clear(updated.id)
                                    codeBuffer.syncFromPreset(updated.code)
                                    draftPreset = updated
                                    toaster.show("已编译 UI 代码到当前草稿", type = ToastType.Success)
                                },
                            )

                            UICustomizeTab.STRUCTURE -> UIStructureEditor(
                                analysis = analysis,
                                code = currentCode(),
                                editor = codeEditor,
                                onEdit = ::applyEdit,
                            )

                            UICustomizeTab.BACKGROUND -> UIBackgroundSectionEditor(
                                section = draftPreset.backgroundSection,
                                locked = lockedFields,
                                onSet = { path, value ->
                                    // 预览立刻变，代码稍后改写
                                    draftPreset = draftPreset.copy(backgroundSection = draftPreset.backgroundSection.withThemeValue(path, value))
                                    queueFormEdit(UIFormField(UIEditTarget.Ui("theme"), path), value)
                                },
                            )

                            UICustomizeTab.TYPOGRAPHY -> UITypographySectionEditor(
                                section = draftPreset.typographySection,
                                locked = lockedFields,
                                onChange = { updated ->
                                    val old = draftPreset.typographySection
                                    draftPreset = draftPreset.copy(typographySection = updated)
                                    TYPOGRAPHY_FIELDS.forEach { (path, get) ->
                                        val v = get(updated)
                                        if (v != get(old)) queueFormEdit(UIFormField(UIEditTarget.Ui("typography"), path), v)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateUIPresetDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { created ->
                showCreateDialog = false
                scope.launch {
                    val newPreset = try {
                        CustomUICodeEngine.compileUICode(created.code, created)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        created.copy(compileError = e.message ?: e.toString())
                    }
                    vm.updateSettings { latest ->
                        val list = if (newPreset.enabled) {
                            latest.uiPresets.map { it.copy(enabled = false) } + newPreset
                        } else {
                            latest.uiPresets + newPreset
                        }
                        latest.copy(uiPresets = list)
                    }
                    selectedPresetId = newPreset.id.toString()
                    draftPreset = newPreset
                    toaster.show("已创建预设「${newPreset.name}」", type = ToastType.Success)
                }
            }
        )
    }

    if (showRenameDialog) {
        RenameUIPresetDialog(
            preset = draftPreset,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName, newDesc ->
                showRenameDialog = false
                val updated = flushCodeBuffer().copy(name = newName, description = newDesc)
                draftPreset = updated
                saveDraftToStore(updated)
            }
        )
    }

    if (showImportDialog) {
        ImportUIPresetDialog(
            onDismiss = { showImportDialog = false },
            onImport = { imported ->
                showImportDialog = false
                val fresh = imported.copy(id = Uuid.random())
                vm.updateSettings { latest ->
                    val list = if (fresh.enabled) {
                        latest.uiPresets.map { it.copy(enabled = false) } + fresh
                    } else {
                        latest.uiPresets + fresh
                    }
                    latest.copy(uiPresets = list)
                }
                selectedPresetId = fresh.id.toString()
                draftPreset = fresh
                toaster.show("已导入预设「${fresh.name}」", type = ToastType.Success)
            }
        )
    }
}

@Composable
private fun UIPresetControlHeader(
    presets: List<UIPreset>,
    draftPreset: UIPreset,
    onSelectPreset: (Uuid) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenCreate: () -> Unit,
    onOpenRename: () -> Unit,
    onOpenImport: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    showPreview: Boolean,
    onTogglePreview: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectTextField(
                value = draftPreset.name.ifBlank { "未命名预设" },
                options = presets.map { it.id.toString() },
                readOnly = true,
                modifier = Modifier.weight(1f),
                optionToString = { idStr ->
                    val item = presets.find { it.id.toString() == idStr }
                    val prefix = if (item?.enabled == true) "🟢 " else ""
                    prefix + (item?.name?.ifBlank { "未命名" } ?: idStr)
                },
                onOptionSelected = { idStr ->
                    runCatching { Uuid.parse(idStr) }.getOrNull()?.let(onSelectPreset)
                },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("启用预设", style = MaterialTheme.typography.labelSmall)
                me.rerere.rikkahub.ui.custom.UISwitch(
                    checked = draftPreset.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showPreview,
                onClick = onTogglePreview,
                label = { Text(if (showPreview) "收起预览" else "展开预览") },
                leadingIcon = { Icon(HugeIcons.Eye, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
            OutlinedButton(
                onClick = onOpenCreate,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onOpenRename,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Icon(HugeIcons.Edit01, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("重命名", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onOpenImport,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onExport,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Icon(HugeIcons.Share03, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("导出", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(HugeIcons.Delete01, contentDescription = "删除预设", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun UIPresetLivePreviewSandbox(
    preset: UIPreset,
    displaySetting: DisplaySetting,
    modifier: Modifier = Modifier,
) {
    val sampleUserNode = remember {
        MessageNode.of(
            UIMessage.user("帮我看看这套自定义 UI 的效果怎么样？")
        )
    }
    val sampleAiNode = remember {
        val now = Clock.System.now()
        val msg1 = UIMessage(
            role = MessageRole.ASSISTANT,
            usage = TokenUsage(promptTokens = 512, completionTokens = 128, totalTokens = 640, cachedTokens = 384),
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "正在根据自定义 UI 节点树与样式修饰符渲染预览气泡...",
                    finishedAt = now + 1.seconds,
                ),
                UIMessagePart.Text(
                    "「这是一句对话台词高亮测试！」*她微微一笑，指了指屏幕上的 **粗体重点参数** 与 `inline_code`。*"
                ),
            ),
        )
        val msg2 = UIMessage.assistant("这是第 2 条分支回复预览。")
        MessageNode(messages = listOf(msg1, msg2), selectIndex = 0)
    }
    val sampleAssistant = remember {
        Assistant(name = "Rikka 助手", useAssistantAvatar = true)
    }
    val sampleModel = remember {
        Model(modelId = "claude-sonnet-4-5", displayName = "Claude 4.5 Sonnet")
    }

    // 套上完整的 UIPresetThemeOverlay：草稿里的配色、深浅色、全局字体、形状、动效在预览框里实时生效
    UIPresetThemeOverlay(
        preset = preset,
        displaySetting = displaySetting,
        updateSystemBars = false,
    ) {
        val colorScheme = MaterialTheme.colorScheme
        Surface(
            modifier = modifier
                .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "实时预览沙盒（所见即所得）",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.primary,
                    )
                    val activeSections = buildList {
                        if (preset.backgroundSection.enabled) add("配色")
                        if (preset.globalSection.enabled) add("全局字体/形状")
                        if (preset.typographySection.enabled) add("排版")
                        if (preset.componentStyles.isNotEmpty()) add("${preset.componentStyles.size}组件")
                        (preset.slots.keys + preset.dynamicSlots).forEach { add(it) }
                    }
                    Text(
                        text = if (activeSections.isEmpty()) "原版默认" else "已接管: ${activeSections.joinToString("/")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val messagesSample: @Composable (Modifier) -> Unit = { mod ->
                    Column(
                        modifier = mod.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChatMessage(
                            node = sampleUserNode,
                            loading = false,
                            model = sampleModel,
                            assistant = sampleAssistant,
                            lastMessage = false,
                            onFork = {},
                            onRegenerate = {},
                            onEdit = {},
                            onShare = {},
                            onDelete = {},
                            onUpdate = {},
                        )

                        ChatMessage(
                            node = sampleAiNode,
                            loading = false,
                            model = sampleModel,
                            assistant = sampleAssistant,
                            lastMessage = true,
                            onFork = {},
                            onRegenerate = {},
                            onEdit = {},
                            onShare = {},
                            onDelete = {},
                            onUpdate = {},
                        )
                    }
                }

                if (preset.hasSlot("chat.layout")) {
                    var previewDrawerOpen by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    ) {
                        me.rerere.rikkahub.ui.custom.UISlot(
                            name = "chat.layout",
                            data = mapOf(
                                "title" to "界面自定义预览会话",
                                "assistant" to sampleAssistant.name,
                                "assistantId" to sampleAssistant.id.toString(),
                                "model" to sampleModel.displayName,
                                "provider" to "Anthropic",
                                "bigScreen" to false,
                                "drawerOpen" to previewDrawerOpen,
                                "previewMode" to false,
                                "loading" to false,
                                "hasMessages" to true,
                                "messageCount" to 2,
                                "empty" to true,
                            ),
                            parts = mapOf(
                                "background" to { info -> Box(info.modifier.fillMaxSize().background(colorScheme.background)) },
                                "topbar" to { info ->
                                    Row(
                                        modifier = info.modifier
                                            .fillMaxWidth()
                                            .background(colorScheme.surfaceContainerLow)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "≡ 界面自定义预览会话",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.clickable { previewDrawerOpen = !previewDrawerOpen },
                                        )
                                        Text("＋", style = MaterialTheme.typography.labelMedium)
                                    }
                                },
                                "messages" to { info ->
                                    Box(
                                        modifier = info.modifier
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                    ) {
                                        messagesSample(Modifier)
                                    }
                                },
                                "input" to { info ->
                                    Surface(
                                        modifier = info.modifier.fillMaxWidth().padding(4.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        color = colorScheme.surfaceContainerLow,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text("输入消息预览…", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                                            Text("↑", style = MaterialTheme.typography.labelMedium, color = colorScheme.primary)
                                        }
                                    }
                                },
                                "drawer" to { info ->
                                    Surface(
                                        modifier = info.modifier,
                                        color = colorScheme.surfaceContainer,
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text("会话抽屉预览", style = MaterialTheme.typography.labelMedium, color = colorScheme.primary)
                                            Text("• 当前对话", style = MaterialTheme.typography.bodySmall)
                                            Text("• 历史会话示例", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                "page:Assistant" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌助手页预览", style = MaterialTheme.typography.bodySmall) } },
                                "page:History" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌历史页预览", style = MaterialTheme.typography.bodySmall) } },
                                "page:Favorite" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌收藏页预览", style = MaterialTheme.typography.bodySmall) } },
                                "page:Stats" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌统计页预览", style = MaterialTheme.typography.bodySmall) } },
                                "page:Setting" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌设置页预览", style = MaterialTheme.typography.bodySmall) } },
                                "page:Workspaces" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌工作区页预览", style = MaterialTheme.typography.bodySmall) } },
                                "page:Plugins" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌插件页预览", style = MaterialTheme.typography.bodySmall) } },
                                "page:Skills" to { info -> Box(info.modifier.padding(12.dp)) { Text("内嵌技能页预览", style = MaterialTheme.typography.bodySmall) } },
                            ),
                            actions = mapOf(
                                "open_drawer" to { previewDrawerOpen = true },
                                "close_drawer" to { previewDrawerOpen = false },
                                "toggle_drawer" to { previewDrawerOpen = !previewDrawerOpen },
                                "new_chat" to {},
                                "toggle_preview" to {},
                                "chat_options" to {},
                                "open_files" to {},
                                "more" to {},
                                "stop" to {},
                                "scroll_bottom" to {},
                                "scroll_top" to {},
                            ),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            messagesSample(Modifier)
                        }
                    }
                } else {
                    messagesSample(Modifier)
                }
            }
        }
    }
}

/** ui.theme 里常用的颜色槽位（其余在「显示全部」里）。 */
private val COMMON_THEME_COLOR_SLOTS = listOf(
    "background", "surface", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh",
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "secondaryContainer", "onSecondaryContainer",
    "onBackground", "onSurface", "onSurfaceVariant", "outline", "outlineVariant",
)

/** 正文排版表单字段 → ui.typography 里的属性（"a|b" 为别名，按代码里已写的那个改）；值为 null 表示删掉该属性（回到原版）。 */
private val TYPOGRAPHY_FIELDS: List<Pair<List<String>, (UITypographySection) -> JsonElement?>> = listOf(
    listOf("enabled") to { s -> JsonPrimitive(s.enabled) },
    listOf("fontSizeMultiplier") to { s -> s.fontSizeMultiplier.takeIf { it != 1.0f }?.let(::JsonPrimitive) },
    listOf("lineHeightMultiplier") to { s -> s.lineHeightMultiplier?.let(::JsonPrimitive) },
    listOf("blockSpacingDp") to { s -> s.blockSpacingDp?.let(::JsonPrimitive) },
    listOf("firstLineIndentSp") to { s -> s.firstLineIndentSp.takeIf { it != 0f }?.let(::JsonPrimitive) },
    listOf("quoteHighlightEnabled") to { s -> s.quoteHighlightEnabled?.let(::JsonPrimitive) },
    listOf("quoteFontWeightBold") to { s -> s.quoteFontWeightBold.takeIf { it }?.let(::JsonPrimitive) },
    listOf("quoteTextColor|quoteTextColorHex") to { s -> s.quoteTextColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("italicTextColor|italicTextColorHex") to { s -> s.italicTextColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("boldTextColor|boldTextColorHex") to { s -> s.boldTextColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("bodyTextColor|bodyTextColorHex") to { s -> s.bodyTextColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("userBodyTextColor|userBodyTextColorHex") to { s -> s.userBodyTextColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("headingColor|headingColorHex") to { s -> s.headingColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("blockquoteBarColor|blockquoteBarColorHex") to { s -> s.blockquoteBarColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("blockquoteBgColor|blockquoteBgColorHex") to { s -> s.blockquoteBgColorHex.ifBlank { null }?.let(::JsonPrimitive) },
    listOf("inlineCodeColor|inlineCodeColorHex") to { s -> s.inlineCodeColorHex.ifBlank { null }?.let(::JsonPrimitive) },
)

/** 表单里全部字段（用于检查哪些在代码里由变量 / 表达式决定、不能在表单里改）。 */
private val UI_FORM_FIELDS: List<UIFormField> =
    listOf(UIFormField(UIEditTarget.Ui("theme"), listOf("seed")), UIFormField(UIEditTarget.Ui("theme"), listOf("dark"))) +
        me.rerere.rikkahub.ui.custom.UI_COLOR_SLOTS.map { UIFormField(UIEditTarget.Ui("theme"), listOf("colors", it)) } +
        TYPOGRAPHY_FIELDS.map { UIFormField(UIEditTarget.Ui("typography"), it.first) }

private fun Map<String, String>.lockedOf(target: UIEditTarget, path: List<String>): String? = this[UIFormField(target, path).key]

/** 表单改了 ui.theme 的某项后，预览里的配色立刻跟着变（代码改写与编译稍后完成）。 */
private fun UIBackgroundSection.withThemeValue(path: List<String>, value: JsonElement?): UIBackgroundSection {
    val str = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (path.first()) {
        "seed" -> copy(enabled = true, seedColorHex = str.orEmpty())
        "dark" -> copy(enabled = true, dark = (value as? JsonPrimitive)?.booleanOrNull)
        "colors" -> {
            val slot = path[1]
            copy(enabled = true, colorOverrides = if (str.isNullOrBlank()) colorOverrides - slot else colorOverrides + (slot to str))
        }
        else -> this
    }
}

@Composable
private fun UIBackgroundSectionEditor(
    section: UIBackgroundSection,
    locked: Map<String, String>,
    onSet: (path: List<String>, value: JsonElement?) -> Unit,
) {
    val theme = UIEditTarget.Ui("theme")
    var showAll by rememberSaveable { mutableStateOf(false) }
    val slots = if (showAll) me.rerere.rikkahub.ui.custom.UI_COLOR_SLOTS else COMMON_THEME_COLOR_SLOTS
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LockedHint(locked.filterKeys { it.startsWith(theme.toString()) }.values)
        }
        item {
            CardGroup(title = { Text("配色（改写代码里的 ui.theme）") }) {
                formItem(
                    headlineContent = { Text("种子色") },
                    supportingContent = { Text("用 Material 官方算法由一个主色生成整套配色；下面逐个指定的颜色在它之后覆盖") },
                ) {
                    UIColorTextField(
                        label = "种子色（#RRGGBB，留空不用）",
                        value = section.seedColorHex,
                        onValueChange = { onSet(listOf("seed"), it.ifBlank { null }?.let(::JsonPrimitive)) },
                        enabled = locked.lockedOf(theme, listOf("seed")) == null,
                        showTokens = false,
                    )
                }
                formItem(headlineContent = { Text("深浅色") }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "跟随", false to "浅色", true to "深色").forEach { (v, label) ->
                            FilterChip(
                                selected = section.dark == v,
                                enabled = locked.lockedOf(theme, listOf("dark")) == null,
                                onClick = { onSet(listOf("dark"), v?.let(::JsonPrimitive)) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
        item {
            CardGroup(title = { Text("逐个指定颜色槽位（留空 = 不改）") }) {
                slots.forEach { slot ->
                    formItem {
                        UIColorTextField(
                            label = slot,
                            value = section.colorOverrides[slot].orEmpty(),
                            onValueChange = { onSet(listOf("colors", slot), it.ifBlank { null }?.let(::JsonPrimitive)) },
                            enabled = locked.lockedOf(theme, listOf("colors", slot)) == null,
                            showTokens = false,
                        )
                    }
                }
                item(
                    onClick = { showAll = !showAll },
                    headlineContent = { Text(if (showAll) "只显示常用颜色" else "显示全部 ${me.rerere.rikkahub.ui.custom.UI_COLOR_SLOTS.size} 个颜色槽位") },
                )
            }
        }
    }
}

@Composable
private fun UITypographySectionEditor(
    section: UITypographySection,
    locked: Map<String, String>,
    onChange: (UITypographySection) -> Unit,
) {
    val typo = UIEditTarget.Ui("typography")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LockedHint(locked.filterKeys { it.startsWith(typo.toString()) }.values)
        }
        item {
            CardGroup(title = { Text("段落排版与语义染色（改写代码里的 ui.typography）") }) {
                switchItem(
                    checked = section.enabled,
                    onCheckedChange = { onChange(section.copy(enabled = it)) },
                    headlineContent = { Text("启用正文排版定制") },
                    supportingContent = { Text("支持字号/行距/段距/首行缩进，以及台词、斜体、粗体、标题、引用块、行内代码独立配色") },
                )
                formItem(
                    headlineContent = { Text("正文字号倍率") },
                    trailingContent = { CardGroupValue("${"%.2f".format(section.fontSizeMultiplier)}x") },
                ) {
                    Slider(
                        value = section.fontSizeMultiplier,
                        enabled = locked.lockedOf(typo, listOf("fontSizeMultiplier")) == null,
                        onValueChange = { onChange(section.copy(enabled = true, fontSizeMultiplier = (it * 20).roundToInt() / 20f)) },
                        valueRange = 0.75f..1.6f,
                    )
                }
                formItem(
                    headlineContent = { Text("行距倍率") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CardGroupValue(section.lineHeightMultiplier?.let { "${"%.2f".format(it)}x" } ?: "原版")
                            if (section.lineHeightMultiplier != null && locked.lockedOf(typo, listOf("lineHeightMultiplier")) == null) {
                                TextButton(onClick = { onChange(section.copy(lineHeightMultiplier = null)) }) { Text("重置") }
                            }
                        }
                    },
                ) {
                    Slider(
                        value = section.lineHeightMultiplier ?: 1.5f,
                        enabled = locked.lockedOf(typo, listOf("lineHeightMultiplier")) == null,
                        onValueChange = { onChange(section.copy(enabled = true, lineHeightMultiplier = (it * 20).roundToInt() / 20f)) },
                        valueRange = 1.1f..2.2f,
                    )
                }
                formItem(
                    headlineContent = { Text("段落间距") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CardGroupValue(section.blockSpacingDp?.let { "${it.roundToInt()} dp" } ?: "原版")
                            if (section.blockSpacingDp != null && locked.lockedOf(typo, listOf("blockSpacingDp")) == null) {
                                TextButton(onClick = { onChange(section.copy(blockSpacingDp = null)) }) { Text("重置") }
                            }
                        }
                    },
                ) {
                    Slider(
                        value = section.blockSpacingDp ?: 12f,
                        enabled = locked.lockedOf(typo, listOf("blockSpacingDp")) == null,
                        onValueChange = { onChange(section.copy(enabled = true, blockSpacingDp = it.roundToInt().toFloat())) },
                        valueRange = 0f..36f,
                    )
                }
                formItem(
                    headlineContent = { Text("段落首行缩进（小说排版常用 28sp）") },
                    trailingContent = { CardGroupValue("${section.firstLineIndentSp.roundToInt()} sp") },
                ) {
                    Slider(
                        value = section.firstLineIndentSp,
                        enabled = locked.lockedOf(typo, listOf("firstLineIndentSp")) == null,
                        onValueChange = { onChange(section.copy(enabled = true, firstLineIndentSp = it.roundToInt().toFloat())) },
                        valueRange = 0f..48f,
                    )
                }
                formItem(headlineContent = { Text("引号台词高亮（“…”、「…」、『…』）") }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "跟随宿主", true to "开启", false to "关闭").forEach { (v, label) ->
                            FilterChip(
                                selected = section.quoteHighlightEnabled == v,
                                enabled = locked.lockedOf(typo, listOf("quoteHighlightEnabled")) == null,
                                onClick = { onChange(section.copy(enabled = true, quoteHighlightEnabled = v)) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                switchItem(
                    checked = section.quoteFontWeightBold,
                    onCheckedChange = { onChange(section.copy(enabled = true, quoteFontWeightBold = it)) },
                    headlineContent = { Text("引号台词自动加粗") },
                )
                formItem {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        UIColorTextField(
                            label = "引号台词颜色（留空使用主题 tertiary 色）",
                            value = section.quoteTextColorHex,
                            enabled = locked.lockedOf(typo, listOf("quoteTextColor|quoteTextColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, quoteTextColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "斜体文字 (*动作/心理描写*) 颜色",
                            value = section.italicTextColorHex,
                            enabled = locked.lockedOf(typo, listOf("italicTextColor|italicTextColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, italicTextColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "粗体文字 (**重点**) 颜色",
                            value = section.boldTextColorHex,
                            enabled = locked.lockedOf(typo, listOf("boldTextColor|boldTextColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, boldTextColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "正文默认文字颜色",
                            value = section.bodyTextColorHex,
                            enabled = locked.lockedOf(typo, listOf("bodyTextColor|bodyTextColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, bodyTextColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "用户消息文字颜色",
                            value = section.userBodyTextColorHex,
                            enabled = locked.lockedOf(typo, listOf("userBodyTextColor|userBodyTextColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, userBodyTextColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "标题 (H1~H6) 颜色",
                            value = section.headingColorHex,
                            enabled = locked.lockedOf(typo, listOf("headingColor|headingColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, headingColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "引用块竖线颜色",
                            value = section.blockquoteBarColorHex,
                            enabled = locked.lockedOf(typo, listOf("blockquoteBarColor|blockquoteBarColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, blockquoteBarColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "引用块底色",
                            value = section.blockquoteBgColorHex,
                            enabled = locked.lockedOf(typo, listOf("blockquoteBgColor|blockquoteBgColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, blockquoteBgColorHex = it)) },
                        )
                        UIColorTextField(
                            label = "行内代码 (`code`) 颜色",
                            value = section.inlineCodeColorHex,
                            enabled = locked.lockedOf(typo, listOf("inlineCodeColor|inlineCodeColorHex")) == null,
                            onValueChange = { onChange(section.copy(enabled = true, inlineCodeColorHex = it)) },
                        )
                    }
                }
            }
        }
    }
}

private val QUICK_COLOR_TOKENS = listOf(
    "" to "默认",
    "primary" to "主题主色",
    "primaryContainer" to "主色容器",
    "secondaryContainer" to "次色容器",
    "surfaceContainerHigh" to "高对比卡片",
    "surfaceContainer" to "标准卡片",
    "#D97706" to "琥珀金",
    "#8B5CF6" to "星云紫",
    "#00E5FF" to "赛博青",
    "#EC4899" to "珊瑚粉",
    "#10B981" to "翡翠绿",
    "#0E131F" to "极夜黑",
)

@Composable
private fun UIColorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    /** 显示快捷色（含语义色名）；ui.theme 的颜色只接受 #hex，不显示。 */
    showTokens: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val previewColor = resolveUIColor(value, colorScheme)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            trailingIcon = {
                if (previewColor != Color.Unspecified) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(previewColor)
                            .border(1.dp, colorScheme.outline, CircleShape)
                    )
                }
            },
        )
        if (showTokens && enabled) Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_COLOR_TOKENS.forEach { (token, tokenLabel) ->
                FilterChip(
                    selected = value == token,
                    onClick = { onValueChange(token) },
                    label = { Text(tokenLabel, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun CreateUIPresetDialog(
    onDismiss: () -> Unit,
    onCreate: (UIPreset) -> Unit,
) {
    var name by remember { mutableStateOf("我的界面预设") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建空白界面预设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("预设描述（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        UIPreset(
                            id = Uuid.random(),
                            name = name.trim().ifEmpty { "自定义界面" },
                            description = desc.trim(),
                            enabled = true,
                            code = "",
                        )
                    )
                }
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RenameUIPresetDialog(
    preset: UIPreset,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(preset) { mutableStateOf(preset.name) }
    var desc by remember(preset) { mutableStateOf(preset.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑预设名称与说明") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("预设描述") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim().ifEmpty { "未命名预设" }, desc.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ImportUIPresetDialog(
    onDismiss: () -> Unit,
    onImport: (UIPreset) -> Unit,
) {
    var codeOrJsonText by remember { mutableStateOf("") }
    var presetName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 从文件导入：读出文本填进下面的输入框，预设名取文件名（去掉 .ui.js / .js / .json）
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val (fileName, text) = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        val content = context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes().decodeToString() }
                            ?: error("无法读取文件")
                        name to content
                    }
                    codeOrJsonText = text
                    errorMsg = null
                    if (fileName != null && !text.trim().startsWith("{")) {
                        presetName = fileName.removeSuffix(".js").removeSuffix(".ui").removeSuffix(".json")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMsg = "读取文件失败：${e.message}"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 UI 代码 或 JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("预设名称（导入 UI 代码时使用）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { fileLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("从文件选择（.js / .json）")
                }
                OutlinedTextField(
                    value = codeOrJsonText,
                    onValueChange = {
                        codeOrJsonText = it
                        errorMsg = null
                    },
                    label = { Text("UI 代码（ui.theme / ui.slot …）或 JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 7,
                    maxLines = 14,
                    isError = errorMsg != null,
                    supportingText = errorMsg?.let { msg -> { Text(msg) } },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val raw = codeOrJsonText.trim()
                    if (raw.isEmpty()) {
                        errorMsg = "内容不能为空"
                        return@Button
                    }
                    scope.launch {
                        try {
                            val parsed = if (raw.startsWith("{")) {
                                // JSON 导入：如果本身带有原始 code（如从编辑页导出的 JSON），保留原代码重新编译（不丢变量、函数与动态接入点）；
                                // 只有旧的无代码 JSON 才由编译结果生成代码
                                val decoded = uiPresetPrettyJson.decodeFromString<UIPreset>(raw).copy(enabled = true)
                                val code = decoded.code.ifBlank { UICodeGenerator.generate(decoded) }
                                val finalName = presetName.trim().ifBlank { decoded.name.ifBlank { "导入界面预设" } }
                                CustomUICodeEngine.compileUICode(code, decoded.copy(name = finalName, code = code))
                            } else {
                                CustomUICodeEngine.compileUICode(
                                    code = raw,
                                    basePreset = UIPreset(
                                        name = presetName.trim().ifBlank { "自定义 UI 代码预设" },
                                        description = "由 UI 代码编译生成",
                                        enabled = true,
                                        code = raw,
                                    ),
                                )
                            }
                            onImport(parsed)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errorMsg = "解析/编译失败: ${e.message}"
                        }
                    }
                }
            ) {
                Text("导入并启用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun UICodeDslSectionEditor(
    preset: UIPreset,
    codeBuffer: UICodeDraftBuffer,
    runtimeErrors: List<String>,
    onApplyCompiled: (UIPreset) -> Unit,
) {
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var compileError by remember(preset.id, preset.compileError) { mutableStateOf(preset.compileError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CardGroup(title = { Text("全局原生 Compose UI 代码引擎 (ui.* DSL)") }) {
                formItem {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "用 JS 写界面：ui.theme / ui.font / ui.shapes / ui.motion / ui.page / ui.transition 管全局外观，ui.style 改宿主组件样式，ui.slot(位置名, 节点树或 (数据, ctx) => 节点树) 接管任意位置（chat.layout 重排页面大模块位置、chat.topbar、chat.input、chat.drawer、chat.message.user / assistant、conversation_item……）。空代码即 100% 原版。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    val snapshotCode = codeBuffer.text
                                    scope.launch {
                                        try {
                                            val compiled = CustomUICodeEngine.compileUICode(snapshotCode, preset.copy(code = snapshotCode))
                                            compileError = null
                                            onApplyCompiled(compiled)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            compileError = e.message ?: e.toString()
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(HugeIcons.Sparkles, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("编译并应用")
                            }

                            OutlinedButton(
                                onClick = {
                                    val snapshotCode = codeBuffer.text
                                    scope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ui_code", snapshotCode)))
                                        toaster.show("已复制 UI 代码到剪贴板", type = ToastType.Success)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("复制代码")
                            }
                        }

                        if (compileError != null) {
                            Text(
                                text = "编译错误: $compileError",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (runtimeErrors.isNotEmpty()) {
                            Text(
                                text = "运行时错误（最近 ${runtimeErrors.size} 条）：\n" + runtimeErrors.takeLast(8).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        OutlinedTextField(
                            value = codeBuffer.text,
                            onValueChange = {
                                codeBuffer.onUserInput(it)
                                compileError = null
                            },
                            label = { Text("UI 代码 (ES2020 DSL)") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 16,
                            maxLines = 36,
                        )
                    }
                }
            }
        }
    }
}
