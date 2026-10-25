package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.termux.terminal.LocalPtyTransport
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalTransport
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.rerere.workspace.WorkspaceTerminalLaunch
import java.io.File

internal fun createWorkspaceTerminalSession(
    transport: TerminalTransport,
    root: String,
    client: TerminalSessionClient,
): TerminalSession =
    TerminalSession(transport, 2_000, client).apply {
        mSessionName = root
    }

internal fun WorkspaceTerminalLaunch.toTransport(): TerminalTransport =
    LocalPtyTransport(executable, cwd, args.toTypedArray(), env.toTypedArray())

internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onTitleUpdated: (String?) -> Unit,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null
    val lastOutputAtMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    val outputEvents = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 64)

    override fun onTextChanged(changedSession: TerminalSession) {
        val now = System.currentTimeMillis()
        lastOutputAtMs.set(now)
        outputEvents.tryEmit(now)
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleUpdated(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val now = System.currentTimeMillis()
        lastOutputAtMs.set(now)
        outputEvents.tryEmit(now)
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }
}

internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        if (openUrlAtTap(e)) return
        focusAndShowKeyboard()
    }

    /**
     * 检测点击位置是否落在一个 URL 上, 是则用浏览器打开并返回 true.
     * TerminalView 0.118.0 没有内置链接点击, 这里基于 getColumnAndRow() + 屏幕缓冲文本自行实现,
     * 并通过 getLineWrap() 还原被软换行拆开的长 URL.
     */
    private fun openUrlAtTap(e: MotionEvent): Boolean {
        val view = terminalView ?: return false
        if (view.isSelectingText) return false
        val emulator = view.mEmulator ?: return false
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val columnAndRow = view.getColumnAndRow(e, true)
        val column = columnAndRow[0]
        val row = columnAndRow[1]
        val rows = emulator.mRows
        val minAccessibleRow = -screen.activeTranscriptRows
        val maxAccessibleRow = rows - 1
        if (column < 0 || column >= columns) return false
        if (row < minAccessibleRow || row > maxAccessibleRow) return false

        // 向上/向下扩展到完整逻辑行(被软换行拆开的行 mLineWrap 为 true).
        // 限制最多扩展 URL_MAX_WRAP_ROWS 行: 真实 URL 跨不了这么多行, 同时避免连续无换行的
        // 长输出导致单次点击遍历整个 transcript.
        val minRow = (row - URL_MAX_WRAP_ROWS).coerceAtLeast(minAccessibleRow)
        val maxRow = (row + URL_MAX_WRAP_ROWS).coerceAtMost(maxAccessibleRow)
        var startRow = row
        while (startRow > minRow && screen.getLineWrap(startRow - 1)) startRow--
        var endRow = row
        while (endRow < maxRow && screen.getLineWrap(endRow)) endRow++

        val line = StringBuilder()
        var tapIndex = -1
        for (r in startRow..endRow) {
            if (r == row) {
                // 用 [0, column] 这段文本的长度精确换算点击字符在本行内的下标, 避免宽字符错位
                tapIndex = line.length + (screen.getSelectedText(0, r, column, r).length - 1).coerceAtLeast(0)
            }
            line.append(screen.getSelectedText(0, r, columns - 1, r))
        }
        if (tapIndex < 0) return false

        val match = URL_REGEX.findAll(line).firstOrNull { tapIndex in it.range } ?: return false
        val url = match.value.trimEnd(*URL_TRAILING_TRIM)
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w("WorkspaceTerminal", "Failed to open url: $url", it)
            false
        }
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }
}

internal data class TerminalProcessTreeState(
    val prootPid: Int,
    val shellPid: Int,
    val foregroundPgid: Int?,
    val foregroundCommand: String?,
    val isAtPrompt: Boolean,
    val processExited: Boolean,
)

private data class ProcStatEntry(
    val pid: Int,
    val comm: String,
    val state: Char,
    val ppid: Int,
    val pgrp: Int,
    val session: Int,
    val tpgid: Int,
)

private val cachedShellPids = java.util.concurrent.ConcurrentHashMap<Int, Int>()
private val lastSlowScanCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, TerminalProcessTreeState>>()
private const val SLOW_SCAN_THROTTLE_MS = 250L

private fun readProcStat(pid: Int): ProcStatEntry? {
    if (pid <= 0) return null
    return runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val commStart = stat.indexOf('(')
        val commEnd = stat.lastIndexOf(')')
        if (commStart < 0 || commEnd <= commStart || commEnd + 2 >= stat.length) return null
        val comm = stat.substring(commStart + 1, commEnd)
        val fields = stat.substring(commEnd + 2).trim().split(' ')
        if (fields.size < 6) return null
        ProcStatEntry(
            pid = pid,
            comm = comm,
            state = fields[0].firstOrNull() ?: '?',
            ppid = fields[1].toIntOrNull() ?: 0,
            pgrp = fields[2].toIntOrNull() ?: 0,
            session = fields[3].toIntOrNull() ?: 0,
            tpgid = fields[5].toIntOrNull() ?: -1,
        )
    }.getOrNull()
}

/**
 * 检查指定终端会话 (以 PRoot 进程 [prootPid] 为会话首进程 sid == prootPid) 的进程树状态:
 * - 优先使用缓存的 `shellPid` 和 `tpgid` 走 O(1) 快速路径（仅读取 2~3 个已知 PID 的 `/proc/<pid>/stat`），
 *   彻底避免轮询期间反复全盘扫描 `/proc` 目录。
 * - 自动识别 PRoot 内部的顶层交互式 Shell 进程 (bash/sh/ash/dash/zsh)
 * - 判断顶层 Shell 是否已退出 (如执行了 `exit` 或 `Ctrl+D`)
 * - 判断当前是否回到了顶层 Shell 提示符 (`isAtPrompt`) 还是有前台子命令正在运行 (`foregroundCommand`)
 */
internal fun inspectTerminalProcessTree(prootPid: Int): TerminalProcessTreeState {
    if (prootPid <= 0) {
        return TerminalProcessTreeState(
            prootPid = prootPid,
            shellPid = 0,
            foregroundPgid = null,
            foregroundCommand = null,
            isAtPrompt = false,
            processExited = true,
        )
    }

    val prootStat = readProcStat(prootPid)
    if (prootStat == null || prootStat.state == 'Z' || prootStat.state == 'X') {
        cachedShellPids.remove(prootPid)
        lastSlowScanCache.remove(prootPid)
        return TerminalProcessTreeState(
            prootPid = prootPid,
            shellPid = 0,
            foregroundPgid = null,
            foregroundCommand = null,
            isAtPrompt = false,
            processExited = true,
        )
    }

    val targetSid = if (prootStat.session > 0) prootStat.session else prootPid

    // O(1) 快速路径：若已缓存该终端的顶层交互式 Shell PID，直接按 PID 精确读取，无需遍历 /proc
    val knownShellPid = cachedShellPids[prootPid]
    if (knownShellPid != null) {
        val shellStat = readProcStat(knownShellPid)
        if (shellStat != null &&
            shellStat.state != 'Z' &&
            shellStat.state != 'X' &&
            (shellStat.ppid == prootPid || shellStat.session == targetSid)
        ) {
            val fgPgid = shellStat.tpgid.takeIf { it > 0 } ?: prootStat.tpgid.takeIf { it > 0 }
            if (fgPgid != null && (fgPgid == shellStat.pgrp || fgPgid == prootStat.pgrp)) {
                return TerminalProcessTreeState(
                    prootPid = prootPid,
                    shellPid = shellStat.pid,
                    foregroundPgid = fgPgid,
                    foregroundCommand = null,
                    isAtPrompt = true,
                    processExited = false,
                )
            }
            if (fgPgid != null) {
                val fgLeader = readProcStat(fgPgid)
                if (fgLeader != null && fgLeader.state != 'Z' && fgLeader.state != 'X') {
                    return TerminalProcessTreeState(
                        prootPid = prootPid,
                        shellPid = shellStat.pid,
                        foregroundPgid = fgPgid,
                        foregroundCommand = fgLeader.comm,
                        isAtPrompt = false,
                        processExited = false,
                    )
                }
            }
        } else {
            // 之前存活的顶层 Shell 已退出（例如在终端执行了 exit 或 Ctrl+D）
            cachedShellPids.remove(prootPid)
            lastSlowScanCache.remove(prootPid)
            return TerminalProcessTreeState(
                prootPid = prootPid,
                shellPid = 0,
                foregroundPgid = null,
                foregroundCommand = null,
                isAtPrompt = false,
                processExited = true,
            )
        }
    }

    // 慢路径节流：仅在首次发现 shellPid 或前台进程组组长已退出需查找组内其余进程时触发
    val now = System.currentTimeMillis()
    val cachedSlow = lastSlowScanCache[prootPid]
    if (cachedSlow != null && now - cachedSlow.first < SLOW_SCAN_THROTTLE_MS) {
        return cachedSlow.second
    }

    val sessionProcs = runCatching {
        File("/proc").listFiles()
            ?.asSequence()
            ?.mapNotNull { dir -> dir.name.toIntOrNull() }
            ?.mapNotNull { pid -> readProcStat(pid) }
            ?.filter { entry ->
                entry.state != 'Z' && entry.state != 'X' &&
                    (entry.session == targetSid || entry.pid == prootPid || entry.ppid == prootPid)
            }
            ?.toList()
            .orEmpty()
    }.getOrDefault(listOf(prootStat))

    // PRoot 的直接子进程即顶层交互式 Shell
    val shellProc = sessionProcs
        .filter { it.pid != prootPid && it.ppid == prootPid }
        .minByOrNull { it.pid }

    if (shellProc != null) {
        cachedShellPids[prootPid] = shellProc.pid
    }

    // 如果 PRoot 尚未完成顶层 Shell 启动
    val childProcesses = sessionProcs.filter { it.pid != prootPid }
    if (shellProc == null && childProcesses.isEmpty()) {
        val fgPgid = prootStat.tpgid.takeIf { it > 0 }
        val state = TerminalProcessTreeState(
            prootPid = prootPid,
            shellPid = prootPid,
            foregroundPgid = fgPgid,
            foregroundCommand = null,
            isAtPrompt = false,
            processExited = false,
        )
        lastSlowScanCache[prootPid] = now to state
        return state
    }

    val topShell = shellProc ?: childProcesses.minByOrNull { it.pid } ?: prootStat
    val activeSubprocs = childProcesses.filter { it.pid != topShell.pid }
    val fgPgid = topShell.tpgid.takeIf { it > 0 } ?: prootStat.tpgid.takeIf { it > 0 }

    val fgSubprocs = if (fgPgid != null && fgPgid != topShell.pgrp && fgPgid != prootStat.pgrp) {
        activeSubprocs.filter { it.pgrp == fgPgid }
    } else {
        // 兼容未开启独立进程组控制的 POSIX sh: 直接子进程仍在运行时视为前台任务
        activeSubprocs.filter { it.ppid == topShell.pid }
    }

    val isAtPrompt = fgSubprocs.isEmpty() && (fgPgid == null || fgPgid == topShell.pgrp || fgPgid == prootStat.pgrp)
    val fgCommand = fgSubprocs.lastOrNull()?.comm

    val result = TerminalProcessTreeState(
        prootPid = prootPid,
        shellPid = topShell.pid,
        foregroundPgid = fgPgid,
        foregroundCommand = fgCommand,
        isAtPrompt = isAtPrompt,
        processExited = false,
    )
    lastSlowScanCache[prootPid] = now to result
    return result
}

internal fun readTerminalForegroundPgid(pid: Int): Int? =
    inspectTerminalProcessTree(pid).foregroundPgid

// 一个 URL 最多还原跨越的软换行行数(向上/向下各算), 足够覆盖任意真实 URL
private const val URL_MAX_WRAP_ROWS = 50

private val URL_REGEX =
    Regex("""(https?|ftp)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""", RegexOption.IGNORE_CASE)

// 终端里 URL 后面常跟标点(行尾句号、被括号包裹等), 打开前去掉这些结尾字符
private val URL_TRAILING_TRIM = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

internal fun Context.activeDnsServers(): List<String> {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    val network = connectivityManager.activeNetwork ?: return emptyList()
    return connectivityManager.getLinkProperties(network)
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        .orEmpty()
}
