package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.Settings

internal const val ASK_KEY_TOOL_NAME = "ask_key"

internal fun buildAskKeyTool(settings: Settings): Tool {
    val activeKeys = settings.secretKeys.filter { it.enabled && it.keyName.isNotBlank() }
    val availableAliasesDesc = if (activeKeys.isEmpty()) {
        "Currently configured secret key aliases: [none]."
    } else {
        val listStr = activeKeys.joinToString("; ") { entry ->
            if (entry.description.isNotBlank()) {
                "\"${entry.keyName}\" (${entry.description.replace("\n", " ").trim()})"
            } else {
                "\"${entry.keyName}\""
            }
        }
        "Currently available secret key aliases (you can write these `key_name` strings directly in any tool inputs/commands and they will be replaced with the real secret values at execution time): [$listStr]."
    }

    return Tool(
        name = ASK_KEY_TOOL_NAME,
        description = """
            Request a secret key alias (password, token, API key, passphrase, etc.) from the user without exposing the raw secret in chat history.
            $availableAliasesDesc
            - If one of the available secret key aliases above already matches what you need, you do NOT need to call `ask_key`; simply write its `key_name` directly in your tool arguments (e.g. in `workspace_terminal` or `workspace_shell`), and the runtime will substitute the real secret automatically.
            - If the required secret is not listed above, or you are unsure which alias to use, call `ask_key` with `reason` and an optional `suggested_key_name`. The user will select an existing alias or create a new one, and this tool will return the `key_name` alias for you to use directly in subsequent tool calls.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "Clear description of what secret/password/token is needed and why (shown to the user).")
                    })
                    put("suggested_key_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Suggested alias identifier for this secret (e.g. 'SSH_PASS', 'GITHUB_TOKEN', 'DB_PASSWORD').")
                    })
                },
                required = listOf("reason"),
            )
        },
        needsApproval = { true },
        execute = {
            error("ask_key tool should be handled by interactive secret key selection flow")
        },
    )
}
