package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCapabilities
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val AllWorkspaceToolNames: List<String> = listOf(
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
    "workspace_terminal",
)

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_terminal" to true,
)

/** 直接写文件的工具, 只在支持 [WorkspaceCapabilities.fileWriteTools] 的工作区提供 */
val WorkspaceFileWriteToolNames: Set<String> = setOf("workspace_write_file", "workspace_edit_file")

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

fun resolveWorkspaceToolEnabled(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: true

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    disabledTools: Set<String> = emptySet(),
    terminalSessionManager: WorkspaceTerminalSessionManager? = runCatching { getKoin().get<WorkspaceTerminalSessionManager>() }.getOrNull(),
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val layout = workspaceRepository.layout(workspaceId) ?: return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    val enabledOverrides = workspace.toolEnabledOverrides()
    val capabilities = workspace.capabilities()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
    fun isEnabled(name: String) = resolveWorkspaceToolEnabled(name, enabledOverrides) && name !in disabledTools

    val shellCwd = cwd?.let(layout::relativeToWorkspace)

    return buildList {
        if (isEnabled("workspace_read_file")) {
            add(createReadFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        }
        if (capabilities.fileWriteTools) {
            if (isEnabled("workspace_write_file")) {
                add(createWriteFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
            }
            if (isEnabled("workspace_edit_file")) {
                add(createEditFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
            }
        }
        if (isEnabled("workspace_shell")) {
            add(createShellTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd))
        }
        if (terminalSessionManager != null && capabilities.terminal && isEnabled("workspace_terminal")) {
            add(
                createTerminalTool(
                    workspace = workspace,
                    needsApproval = ::needsApproval,
                    terminalManager = terminalSessionManager,
                )
            )
        }
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico"
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append(
            "Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at ${layout.workspaceDir}. "
        )
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        } else {
            append("Defaults to root. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        },
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to ${WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS / 1_000}, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = layout.relativeToWorkspace(params.string("cwd") ?: defaultCwd.orEmpty())
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) {
                        put("truncated", true)
                    }
                }.toString()
            )
        )
    },
)

private fun createTerminalTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    terminalManager: WorkspaceTerminalSessionManager,
) = Tool(
    name = "workspace_terminal",
    description = """
        Interact with persistent, stateful PTY terminal sessions bound to this workspace (shared across the workspace by unique `id`).
        Unlike `workspace_shell`, each terminal preserves working directory, environment variables, background/foreground jobs, and interactive REPL/CLI state (e.g. python, node, gdb, ssh, confirmation prompts).
        Multiple terminals are supported simultaneously in the same workspace:
        - Pass `new_session=true` (optionally with `title` and `input`) to open a new terminal tab and get its unique `id`.
        - Pass `id` to operate on a specific terminal session, or omit `id` to reuse the current active session (creates #1 if none exists).
        - Pass `input` to send a command or interactive keystrokes to the PTY (e.g. `python3 -i`, `y`, `\u0003` for Ctrl+C, `\u0004` for Ctrl+D, or `exit` to close the shell itself).
        - Omit `input` to read the latest output and screen snapshot of the terminal.
        - Set `list_only=true` to list all active terminal sessions and their IDs in this workspace.
        - Set `kill=true` (with optional `id`) to terminate and close that terminal session, or `kill_all=true` to close all terminal sessions in this workspace. Running `exit` inside the shell also closes that terminal session automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Unique terminal session ID in this workspace. Omit to use the active session or create one.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional label/name for this terminal tab (e.g. 'dev-server', 'test-runner').")
                })
                put("input", buildJsonObject {
                    put("type", "string")
                    put("description", "Command or interactive text/keys to write to the PTY. Omit to only read screen output.")
                })
                put("send_enter", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to append Enter (\\r) after `input`. Defaults to true. Set false when sending raw control characters like \\u0003 (Ctrl+C).")
                })
                put("wait_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum time in milliseconds to wait for output to settle or return to prompt. Defaults to 5000, max 120000.")
                })
                put("new_session", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, create a new terminal tab in the workspace (supporting multiple concurrent terminals) and return its new unique ID.")
                })
                put("kill", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, close and terminate the target terminal session (by `id` or active session).")
                })
                put("kill_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, close and terminate all terminal sessions in this workspace.")
                })
                put("list_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, only list all active terminal sessions in this workspace without sending input.")
                })
            },
        )
    },
    needsApproval = { args ->
        val obj = runCatching { args.jsonObject }.getOrNull()
        val isReadOnly = obj != null &&
            obj["input"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() &&
            obj["kill"]?.jsonPrimitive?.booleanOrNull != true &&
            obj["kill_all"]?.jsonPrimitive?.booleanOrNull != true &&
            obj["new_session"]?.jsonPrimitive?.booleanOrNull != true
        if (isReadOnly) false else needsApproval("workspace_terminal")
    },
    execute = { args ->
        val params = args.jsonObject
        val listOnly = params["list_only"]?.jsonPrimitive?.booleanOrNull ?: false
        if (listOnly) {
            val tabs = terminalManager.listActiveTabs(workspace.root)
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("activeSessionsCount", tabs.size)
                        put("sessions", buildJsonArray {
                            tabs.forEach { tab ->
                                add(buildJsonObject {
                                    put("id", tab.id)
                                    put("tabNumber", tab.number)
                                    put("title", tab.title ?: "")
                                    put("finished", tab.finished)
                                    put("isAtPrompt", tab.isAtPrompt)
                                    put("shellPid", tab.shellPid)
                                    tab.foregroundPgid?.let { put("foregroundPgid", it) }
                                    tab.foregroundCommand?.let { put("foregroundCommand", it) }
                                    if (tab.screenPreview.isNotBlank()) {
                                        put("screenPreview", tab.screenPreview)
                                    }
                                })
                            }
                        })
                    }.toString()
                )
            )
        }

        val tabId = params["id"]?.jsonPrimitive?.longOrNull
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val input = params["input"]?.jsonPrimitive?.contentOrNull
        val sendEnter = params["send_enter"]?.jsonPrimitive?.booleanOrNull ?: true
        val waitMs = (params["wait_ms"]?.jsonPrimitive?.longOrNull ?: 5_000L).coerceIn(100L, 120_000L)
        val createNew = params["new_session"]?.jsonPrimitive?.booleanOrNull ?: false
        val kill = params["kill"]?.jsonPrimitive?.booleanOrNull ?: false
        val killAll = params["kill_all"]?.jsonPrimitive?.booleanOrNull ?: false

        val result = terminalManager.interact(
            workspace = workspace,
            tabId = tabId,
            title = title,
            input = input,
            sendEnter = sendEnter,
            waitMs = waitMs,
            createNew = createNew,
            kill = kill,
            killAll = killAll,
        )

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("id", result.id)
                    put("tabNumber", result.number)
                    if (!result.title.isNullOrBlank()) put("title", result.title)
                    put("status", result.status)
                    put("isAtPrompt", result.isAtPrompt)
                    put("shellPid", result.shellPid)
                    result.foregroundPgid?.let { put("foregroundPgid", it) }
                    result.foregroundCommand?.let { put("foregroundCommand", it) }
                    put("activeSessionsCount", result.activeSessionsCount)
                    put("timedOut", result.timedOut)
                    put("incrementalOutput", result.incrementalOutput)
                    put("screenSnapshot", result.screenSnapshot)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按环境内绝对路径读入内存。路径映射交给工作区后端, 本地工作区由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = absoluteFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportAbsoluteFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()
    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private fun String.normalizeRootfsAbsolutePath(): String {
    val parts = split('/')
    val stack = ArrayDeque<String>()
    for (part in parts) {
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(part)
        }
    }
    return if (stack.isEmpty()) "/" else "/" + stack.joinToString("/")
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path.normalizeRootfsAbsolutePath()
}

// 免强制审批的可写安全区由工作区后端给出, 本地为工作区文件目录、临时目录和技能目录
private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    layout: WorkspaceLayout,
    name: String,
): Boolean =
    runCatching {
        !layout.isWritable(jsonObject.absolutePath(name))
    }.getOrDefault(true)

private fun JsonObjectBuilder.putPathProperty(layout: WorkspaceLayout, required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
