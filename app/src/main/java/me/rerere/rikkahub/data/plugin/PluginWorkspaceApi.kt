package me.rerere.rikkahub.data.plugin

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea

/** rikka.workspace 的实现：文件、shell、持久化终端（与 workspace_terminal 工具共用同一终端池）。 */
class PluginWorkspaceApi(
    private val repo: WorkspaceRepository,
    private val terminals: WorkspaceTerminalSessionManager,
) {
    private suspend fun workspace(o: JsonObject) = repo.getById(o.str("id")) ?: error("工作区不存在: ${o.str("id")}")

    suspend fun call(name: String, o: JsonObject): JsonElement = when (name) {
        "workspace.list" -> {
            JsonArray(repo.listFlow().first().map { w ->
                buildJsonObject {
                    put("id", w.id)
                    put("name", w.name)
                    put("shellStatus", w.shellStatus)
                }
            })
        }

        "workspace.files" -> {
            val area = if (o.strOrNull("area") == "linux") WorkspaceStorageArea.LINUX else WorkspaceStorageArea.FILES
            JsonArray(repo.listFiles(workspace(o).id, area, o.strOrNull("path").orEmpty()).map { f ->
                buildJsonObject {
                    put("path", f.path)
                    put("name", f.name)
                    put("isDirectory", f.isDirectory)
                    put("size", f.sizeBytes)
                    put("updatedAt", f.updatedAt)
                }
            })
        }

        "workspace.read" -> JsonPrimitive(repo.readText(workspace(o).id, o.str("path")))

        "workspace.write" -> {
            repo.writeText(workspace(o).id, o.str("path"), o.str("text"), o.boolOr("overwrite", true))
            JsonNull
        }

        "workspace.delete" -> {
            val area = if (o.strOrNull("area") == "linux") WorkspaceStorageArea.LINUX else WorkspaceStorageArea.FILES
            JsonPrimitive(repo.deleteFile(workspace(o).id, area, o.str("path"), o.boolOr("recursive", false)))
        }

        "workspace.exec" -> {
            val r = repo.executeCommand(
                id = workspace(o).id,
                command = o.str("command"),
                cwd = o.strOrNull("cwd").orEmpty(),
                timeoutMillis = o.longOr("timeoutMs", WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS),
                stdin = o.strOrNull("stdin")?.toByteArray(),
            )
            buildJsonObject {
                put("exitCode", r.exitCode)
                put("stdout", r.stdout)
                put("stderr", r.stderr)
                put("timedOut", r.timedOut)
                put("truncated", r.truncated)
            }
        }

        "workspace.terminal" -> {
            val r = terminals.interact(
                workspace = workspace(o),
                tabId = o.strOrNull("tabId")?.toLong(),
                title = o.strOrNull("title"),
                input = o.strOrNull("input"),
                sendEnter = o.boolOr("sendEnter", true),
                waitMs = o.longOr("waitMs", 5_000L),
                createNew = o.boolOr("newSession", false),
                kill = o.boolOr("kill", false),
                killAll = o.boolOr("killAll", false),
            )
            buildJsonObject {
                put("id", r.id)
                put("number", r.number)
                r.title?.let { put("title", it) }
                put("status", r.status)
                put("isAtPrompt", r.isAtPrompt)
                r.foregroundCommand?.let { put("foregroundCommand", it) }
                put("incrementalOutput", r.incrementalOutput)
                put("screenSnapshot", r.screenSnapshot)
                put("timedOut", r.timedOut)
            }
        }

        "workspace.terminals" -> JsonArray(terminals.listActiveTabs(workspace(o).root).map { t ->
            buildJsonObject {
                put("id", t.id)
                put("number", t.number)
                t.title?.let { put("title", it) }
                put("finished", t.finished)
                put("isAtPrompt", t.isAtPrompt)
                t.foregroundCommand?.let { put("foregroundCommand", it) }
                put("screenPreview", t.screenPreview)
            }
        })

        else -> error("未知宿主 API: $name")
    }
}
