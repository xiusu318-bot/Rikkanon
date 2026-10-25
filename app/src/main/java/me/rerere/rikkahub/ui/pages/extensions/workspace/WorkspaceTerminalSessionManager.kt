package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class WorkspaceTerminalTabInfo(
    val id: Long,
    val number: Int,
    val title: String?,
    val finished: Boolean,
    val shellPid: Int,
    val foregroundPgid: Int?,
    val foregroundCommand: String? = null,
    val isAtPrompt: Boolean,
    val screenPreview: String = "",
)

data class WorkspaceTerminalInteractResult(
    val id: Long,
    val number: Int,
    val title: String?,
    val status: String, // "prompt_ready" | "running_or_waiting_input" | "exited_and_closed" | "killed" | "killed_all"
    val isAtPrompt: Boolean,
    val shellPid: Int,
    val foregroundPgid: Int?,
    val foregroundCommand: String? = null,
    val incrementalOutput: String,
    val screenSnapshot: String,
    val timedOut: Boolean,
    val activeSessionsCount: Int = 0,
)

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 *
 * Both the UI terminal page and the AI `workspace_terminal` tool share this manager keyed only by
 * workspace [root] and unique tab [WorkspaceTerminalTab.id].
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val workspaceManager: WorkspaceManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    internal val allWorkspaceStates: StateFlow<Map<String, WorkspaceTerminalTabsState>> = workspaceStates.asStateFlow()
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()
    private val creationMutex = Mutex()
    private val tabCursors = ConcurrentHashMap<Long, Int>()
    private val tabTailAnchors = ConcurrentHashMap<Long, String>()

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(workspace: WorkspaceEntity) {
        launchCreateTab(workspace = workspace, onlyIfEmpty = true)
    }

    internal fun createTab(workspace: WorkspaceEntity) {
        launchCreateTab(workspace = workspace, onlyIfEmpty = false)
    }

    internal fun createTab(root: String, shellCompatibilityMode: Boolean) {
        appScope.launch {
            val workspace = workspaceRepository.getByRoot(root)
                ?: WorkspaceEntity(
                    id = root,
                    name = root,
                    root = root,
                    createdAt = 0L,
                    updatedAt = 0L,
                    shellCompatibilityMode = shellCompatibilityMode,
                )
            launchCreateTab(workspace = workspace, onlyIfEmpty = false)
        }
    }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        tabCursors.remove(tabId)
        tabTailAnchors.remove(tabId)
        closedTab?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    private fun inspectTabState(tab: WorkspaceTerminalTab, previewLines: List<String>? = null): TerminalProcessTreeState {
        if (tab.finished || !tab.session.isRunning) {
            return TerminalProcessTreeState(
                prootPid = 0,
                shellPid = 0,
                foregroundPgid = null,
                foregroundCommand = null,
                isAtPrompt = false,
                processExited = true,
            )
        }
        // Transport is still starting on background thread (e.g. fork or SSH handshake in progress)
        if (!tab.session.isTransportStarted) {
            return TerminalProcessTreeState(
                prootPid = 0,
                shellPid = 0,
                foregroundPgid = null,
                foregroundCommand = null,
                isAtPrompt = false,
                processExited = false,
            )
        }
        val pid = runCatching { tab.session.pid }.getOrDefault(-1)
        if (pid > 0) {
            return inspectTerminalProcessTree(pid)
        }
        // Remote SSH transport (pid == -1): infer prompt readiness from screen tail & output idle state
        val lines = previewLines ?: readTranscript(tab.session)
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        val lastLine = lines.lastOrNull()?.trim().orEmpty()
        val idleMs = System.currentTimeMillis() - tab.client.lastOutputAtMs.get()
        val looksLikePrompt = lastLine.endsWith("$") || lastLine.endsWith("#") || lastLine.endsWith("%") || lastLine.endsWith(">")
        return TerminalProcessTreeState(
            prootPid = -1,
            shellPid = -1,
            foregroundPgid = null,
            foregroundCommand = null,
            isAtPrompt = looksLikePrompt && idleMs >= 150L,
            processExited = false,
        )
    }

    /**
     * Returns a snapshot of all live terminal tabs currently open in [root].
     * Automatically cleans up any background tabs whose shell process has already exited.
     */
    fun listActiveTabs(root: String): List<WorkspaceTerminalTabInfo> {
        val state = currentState(root)
        val deadTabIds = mutableListOf<Long>()
        val result = buildList {
            state.tabs.forEach { tab ->
                val nonBlankLines = readTranscript(tab.session)
                    .lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .toList()
                val tree = inspectTabState(tab, nonBlankLines)
                val exited = tab.finished || tree.processExited
                if (exited && tab.client.terminalView == null) {
                    deadTabIds += tab.id
                } else {
                    val previewLines = nonBlankLines.takeLast(6).joinToString("\n")
                    add(
                        WorkspaceTerminalTabInfo(
                            id = tab.id,
                            number = tab.number,
                            title = tab.customTitle ?: tab.title,
                            finished = exited,
                            shellPid = tree.shellPid,
                            foregroundPgid = tree.foregroundPgid,
                            foregroundCommand = tree.foregroundCommand,
                            isAtPrompt = !exited && tree.isAtPrompt,
                            screenPreview = previewLines,
                        )
                    )
                }
            }
        }
        if (deadTabIds.isNotEmpty()) {
            deadTabIds.forEach { deadId -> closeTab(root, deadId) }
        }
        return result
    }

    /**
     * Interacts with a persistent PTY terminal session in [workspace] by unique [tabId].
     */
    suspend fun interact(
        workspace: WorkspaceEntity,
        tabId: Long? = null,
        title: String? = null,
        input: String? = null,
        sendEnter: Boolean = true,
        waitMs: Long = 5_000L,
        createNew: Boolean = false,
        kill: Boolean = false,
        killAll: Boolean = false,
    ): WorkspaceTerminalInteractResult {
        val root = workspace.root
        if (killAll) {
            val existingTabs = currentState(root).tabs
            withContext(Dispatchers.Main.immediate) {
                existingTabs.forEach { tab -> closeTab(root, tab.id) }
            }
            return WorkspaceTerminalInteractResult(
                id = tabId ?: existingTabs.lastOrNull()?.id ?: 0L,
                number = existingTabs.size,
                title = title,
                status = "killed_all",
                isAtPrompt = false,
                shellPid = 0,
                foregroundPgid = null,
                foregroundCommand = null,
                incrementalOutput = "Closed ${existingTabs.size} terminal session(s).",
                screenSnapshot = "",
                timedOut = false,
                activeSessionsCount = 0,
            )
        }

        if (kill) {
            val state = currentState(root)
            val targetId = tabId ?: state.selectedTabId
                ?: state.tabs.lastOrNull()?.id
                ?: error("No active terminal session to kill in workspace")
            val existing = state.tabs.firstOrNull { it.id == targetId }
                ?: error("Terminal session #$targetId not found")
            withContext(Dispatchers.Main.immediate) {
                closeTab(root, targetId)
            }
            val remainingCount = currentState(root).tabs.size
            return WorkspaceTerminalInteractResult(
                id = existing.id,
                number = existing.number,
                title = existing.customTitle ?: existing.title,
                status = "killed",
                isAtPrompt = false,
                shellPid = 0,
                foregroundPgid = null,
                foregroundCommand = null,
                incrementalOutput = "",
                screenSnapshot = "",
                timedOut = false,
                activeSessionsCount = remainingCount,
            )
        }

        val targetTab = resolveOrCreateTargetTab(
            workspace = workspace,
            tabId = tabId,
            createNew = createNew,
            initialTitle = title,
        )

        if (!title.isNullOrBlank()) {
            updateCustomTitle(root, targetTab.id, title)
        }

        val beforeTranscript = readTranscript(targetTab.session)
        val cursorOffset = tabCursors[targetTab.id]?.coerceAtMost(beforeTranscript.length) ?: beforeTranscript.length
        val savedAnchor = tabTailAnchors[targetTab.id] ?: extractTailAnchor(beforeTranscript, cursorOffset)
        val sendTimestamp = System.currentTimeMillis()

        val trimmedInput = input?.trim()
        val isExitCommand = trimmedInput == "exit" ||
            trimmedInput?.startsWith("exit ") == true ||
            trimmedInput == "logout" ||
            input == "\u0004"

        if (!input.isNullOrEmpty()) {
            val payload = if (sendEnter && !input.endsWith("\n") && !input.endsWith("\r")) {
                "$input\r"
            } else {
                input
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            withContext(Dispatchers.Main.immediate) {
                if (targetTab.session.emulator == null) {
                    targetTab.session.updateSize(DEFAULT_TERMINAL_COLS, DEFAULT_TERMINAL_ROWS)
                }
                targetTab.session.write(bytes, 0, bytes.size)
            }
        }

        val boundedWaitMs = waitMs.coerceIn(100L, 120_000L)
        val deadline = System.currentTimeMillis() + boundedWaitMs
        var timedOut = false
        var detectedExit = false

        // Wait for output to settle, command to return to top-level prompt, or shell to exit
        while (System.currentTimeMillis() < deadline) {
            delay(50)
            val latestTab = currentState(root).tabs.firstOrNull { it.id == targetTab.id }
            if (latestTab == null || latestTab.finished) {
                detectedExit = true
                break
            }

            val tree = inspectTabState(latestTab)
            if (tree.processExited) {
                detectedExit = true
                break
            }

            val now = System.currentTimeMillis()
            val elapsedSinceSend = now - sendTimestamp
            val lastOut = latestTab.client.lastOutputAtMs.get()
            val idleMs = now - maxOf(sendTimestamp, lastOut)

            if (input.isNullOrEmpty()) {
                if (idleMs >= 200L) break
            } else {
                val hasProducedOutput = lastOut >= sendTimestamp
                if (tree.isAtPrompt && elapsedSinceSend >= 150L && idleMs >= 150L) {
                    // Subprocess finished and control returned to top-level shell prompt
                    break
                }
                if (!tree.isAtPrompt && hasProducedOutput && idleMs >= 800L) {
                    // Foreground interactive program (e.g. python REPL, ssh, [y/N] prompt) has settled and is waiting for input
                    break
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
            }
        }

        val finalTab = currentState(root).tabs.firstOrNull { it.id == targetTab.id } ?: targetTab
        val afterTranscript = readTranscript(finalTab.session)
        val incremental = extractIncrementalTranscript(afterTranscript, cursorOffset, savedAnchor)
        tabCursors[finalTab.id] = afterTranscript.length
        tabTailAnchors[finalTab.id] = extractTailAnchor(afterTranscript, afterTranscript.length)
        val snapshot = afterTranscript.lineSequence().toList().takeLast(DEFAULT_TERMINAL_ROWS).joinToString("\n").trimEnd()
        val finalTree = inspectTabState(finalTab)
        val hasExited = detectedExit || finalTab.finished || finalTree.processExited ||
            (isExitCommand && finalTree.isAtPrompt && finalTree.prootPid > 0 && finalTree.shellPid == finalTree.prootPid)

        // 当终端内顶层 Shell 执行了 exit / Ctrl+D 退出时，自动关闭并移除该终端标签页
        if (hasExited) {
            withContext(Dispatchers.Main.immediate) {
                closeTab(root, finalTab.id)
            }
        }

        val isAtPrompt = !hasExited && finalTree.isAtPrompt
        val status = when {
            hasExited -> "exited_and_closed"
            isAtPrompt -> "prompt_ready"
            else -> "running_or_waiting_input"
        }
        val activeCount = currentState(root).tabs.count { !it.finished }

        return WorkspaceTerminalInteractResult(
            id = finalTab.id,
            number = finalTab.number,
            title = finalTab.customTitle ?: finalTab.title,
            status = status,
            isAtPrompt = isAtPrompt,
            shellPid = if (hasExited) 0 else finalTree.shellPid,
            foregroundPgid = if (hasExited) null else finalTree.foregroundPgid,
            foregroundCommand = if (hasExited) null else finalTree.foregroundCommand,
            incrementalOutput = incremental,
            screenSnapshot = snapshot,
            timedOut = timedOut,
            activeSessionsCount = activeCount,
        )
    }

    private suspend fun resolveOrCreateTargetTab(
        workspace: WorkspaceEntity,
        tabId: Long?,
        createNew: Boolean,
        initialTitle: String? = null,
    ): WorkspaceTerminalTab = creationMutex.withLock {
        val root = workspace.root
        if (!createNew) {
            val state = currentState(root)
            if (tabId != null) {
                val matched = state.tabs.firstOrNull { it.id == tabId }
                if (matched != null) {
                    val exited = matched.finished || inspectTabState(matched).processExited
                    if (!exited) {
                        selectTab(root, matched.id)
                        return@withLock matched
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            closeTab(root, matched.id)
                        }
                    }
                } else {
                    error("Terminal session #$tabId does not exist in this workspace. Omit `id` or set `new_session=true` to create a new terminal, or use `list_only=true` to inspect active terminal IDs.")
                }
            } else {
                val aliveSelected = state.tabs.firstOrNull { tab ->
                    tab.id == state.selectedTabId && !tab.finished && !inspectTabState(tab).processExited
                }
                if (aliveSelected != null) return@withLock aliveSelected

                val aliveLast = state.tabs.lastOrNull { tab ->
                    !tab.finished && !inspectTabState(tab).processExited
                }
                if (aliveLast != null) {
                    selectTab(root, aliveLast.id)
                    return@withLock aliveLast
                }
            }
        }

        val created = createTabNow(
            workspace = workspace,
            onlyIfEmpty = false,
            initialTitle = initialTitle,
        ) ?: error("Failed to create terminal session for workspace $root")

        // Wait for background TermSessionRunner to start transport (local fork or SSH handshake) and render initial prompt
        val start = System.currentTimeMillis()
        val maxStartupWait = if (workspace.isSsh()) 10_000L else 1_000L
        while (System.currentTimeMillis() - start < maxStartupWait) {
            delay(50)
            if (created.finished || !created.session.isRunning) break
            if (created.session.isTransportStarted &&
                created.client.lastOutputAtMs.get() > start &&
                System.currentTimeMillis() - created.client.lastOutputAtMs.get() >= 100L
            ) {
                break
            }
        }
        created
    }

    private fun readTranscript(session: TerminalSession): String =
        runCatching {
            session.emulator?.screen?.transcriptText?.replace("\r\n", "\n") ?: ""
        }.getOrDefault("")

    /**
     * Extracts a compact tail anchor (up to 512 chars before [endOffset]) for O(1) cursor validation
     * and ring-buffer wrap-around recovery without retaining the full 2000-line transcript in memory.
     */
    private fun extractTailAnchor(transcript: String, endOffset: Int): String {
        val safeEnd = endOffset.coerceIn(0, transcript.length)
        if (safeEnd == 0) return ""
        val rawStart = (safeEnd - 512).coerceAtLeast(0)
        val lineStart = if (rawStart > 0) {
            val nl = transcript.indexOf('\n', rawStart)
            if (nl in rawStart until safeEnd) nl + 1 else rawStart
        } else {
            0
        }
        return transcript.substring(lineStart, safeEnd)
    }

    /**
     * Extracts newly appended terminal output using O(1) [String.regionMatches] on the normal path,
     * falling back to multi-line tail anchor lookup only when the 2000-line scrollback buffer wraps.
     */
    private fun extractIncrementalTranscript(
        after: String,
        cursorOffset: Int,
        savedAnchor: String,
    ): String {
        if (after.isEmpty()) return ""
        if (cursorOffset <= 0 || savedAnchor.isEmpty()) {
            return if (cursorOffset in 0..after.length) after.substring(cursorOffset).trimEnd() else after.trimEnd()
        }
        if (cursorOffset <= after.length &&
            cursorOffset >= savedAnchor.length &&
            after.regionMatches(cursorOffset - savedAnchor.length, savedAnchor, 0, savedAnchor.length)
        ) {
            return after.substring(cursorOffset).trimEnd()
        }
        val directIdx = after.lastIndexOf(savedAnchor)
        if (directIdx >= 0) {
            return after.substring(directIdx + savedAnchor.length).trimStart('\r', '\n').trimEnd()
        }
        val anchorLines = savedAnchor.trimEnd('\n', '\r').lines()
        for (window in minOf(6, anchorLines.size) downTo 2) {
            val subAnchor = anchorLines.takeLast(window).joinToString("\n")
            if (subAnchor.isBlank()) continue
            val matchIdx = after.lastIndexOf(subAnchor)
            if (matchIdx >= 0) {
                return after.substring(matchIdx + subAnchor.length).trimStart('\r', '\n').trimEnd()
            }
        }
        return after.trimEnd()
    }

    internal fun readTabScreenSnapshot(root: String, tabId: Long, maxLines: Int = DEFAULT_TERMINAL_ROWS): String {
        val tab = currentState(root).tabs.firstOrNull { it.id == tabId } ?: return ""
        val transcript = readTranscript(tab.session)
        return transcript.lineSequence().toList().takeLast(maxLines).joinToString("\n").trimEnd()
    }

    internal fun writeToTab(root: String, tabId: Long, text: String, appendEnter: Boolean = true) {
        val tab = currentState(root).tabs.firstOrNull { it.id == tabId && !it.finished } ?: return
        val payload = if (appendEnter && !text.endsWith("\n") && !text.endsWith("\r")) {
            "$text\r"
        } else {
            text
        }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (tab.session.emulator == null) {
            tab.session.updateSize(DEFAULT_TERMINAL_COLS, DEFAULT_TERMINAL_ROWS)
        }
        tab.session.write(bytes, 0, bytes.size)
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        creationJobs[root]?.cancelAndJoin()

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            tabCursors.remove(tab.id)
            tabTailAnchors.remove(tab.id)
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    private fun launchCreateTab(workspace: WorkspaceEntity, onlyIfEmpty: Boolean) {
        val root = workspace.root
        if (root in creationJobs) return

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                creationMutex.withLock {
                    createTabNow(workspace = workspace, onlyIfEmpty = onlyIfEmpty)
                }
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTabNow(
        workspace: WorkspaceEntity,
        onlyIfEmpty: Boolean,
        initialTitle: String? = null,
    ): WorkspaceTerminalTab? = withContext(Dispatchers.Main.immediate) {
        val root = workspace.root
        val initialState = currentState(root)
        if (onlyIfEmpty && initialState.tabs.isNotEmpty()) {
            return@withContext initialState.tabs.lastOrNull()
        }
        updateState(root) { it.copy(isCreating = true) }

        try {
            // 不在本机 Rootfs 里运行的终端 (如远程 SSH) 无需准备, 连接在会话启动时建立
            val prepared = if (!workspace.capabilities().rootfs ||
                initialState.readiness == WorkspaceTerminalReadiness.Ready
            ) {
                true
            } else {
                try {
                    withContext(Dispatchers.IO) {
                        if (!workspaceManager.hasRootfs(root)) {
                            false
                        } else {
                            workspaceManager.prepareTerminal(root, appContext.activeDnsServers())
                            true
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to prepare terminal for workspace $root", error)
                    false
                }
            }

            if (!prepared) {
                updateState(root) {
                    it.copy(
                        readiness = WorkspaceTerminalReadiness.NotInstalled,
                        isCreating = false,
                    )
                }
                return@withContext null
            }

            val tabId = nextTabId.getAndIncrement()
            val tabNumber = currentState(root).nextTabNumber
            val client = WorkspaceTerminalSessionClient(
                context = appContext,
                onTitleUpdated = { title -> updateTitle(root, tabId, title) },
                onFinished = { markFinished(root = root, tabId = tabId) },
            )
            val session = runCatching {
                createWorkspaceTerminalSession(
                    transport = terminalTransport(workspace),
                    root = root,
                    client = client,
                ).also { termSession ->
                    // Ensure PTY reader/writer threads and screen buffer start immediately even before a TerminalView attaches
                    if (termSession.emulator == null) {
                        termSession.updateSize(DEFAULT_TERMINAL_COLS, DEFAULT_TERMINAL_ROWS)
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to create terminal for workspace $root", error)
            }.getOrNull() ?: return@withContext null

            val normalizedCustomTitle = initialTitle?.trim()?.takeIf { it.isNotEmpty() }
            val tab = WorkspaceTerminalTab(
                id = tabId,
                number = tabNumber,
                session = session,
                client = client,
                title = normalizedCustomTitle,
                customTitle = normalizedCustomTitle,
            )
            updateState(root) { state ->
                state.copy(
                    tabs = state.tabs + tab,
                    selectedTabId = tab.id,
                    readiness = WorkspaceTerminalReadiness.Ready,
                    isCreating = false,
                    nextTabNumber = tabNumber + 1,
                )
            }
            tab
        } finally {
            updateState(root) { it.copy(isCreating = false) }
        }
    }

    private fun terminalTransport(workspace: WorkspaceEntity): TerminalTransport =
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: ${workspace.id}")
            SshTerminalTransport(address = "${settings.username}@${settings.host}:${settings.port}") { columns, rows ->
                workspaceRepository.openSshShell(workspace, columns, rows)
            }
        } else {
            workspaceManager.terminalLaunch(workspace.root, workspace.shellCompatibilityMode)?.toTransport()
                ?: error("Interactive terminal is not supported")
        }

    private fun updateCustomTitle(root: String, tabId: Long, customTitle: String?) {
        val normalized = customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states
            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(customTitle = normalized, title = normalized) else tab
                },
            ))
        }
    }

    private fun updateTitle(root: String, tabId: Long, title: String?) {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(title = tab.customTitle ?: normalizedTitle)
                    } else {
                        tab
                    }
                },
            ))
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        val targetTab = currentState(root).tabs.firstOrNull { it.id == tabId } ?: return
        if (targetTab.client.terminalView == null) {
            // 后台终端（未被 UI 页面挂载查看）退出时直接自动关闭并清理标签页
            closeTab(root, tabId)
            return
        }
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"
        const val DEFAULT_TERMINAL_COLS = 120
        const val DEFAULT_TERMINAL_ROWS = 40
    }
}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val title: String? = null,
    val customTitle: String? = null,
    val finished: Boolean = false,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
