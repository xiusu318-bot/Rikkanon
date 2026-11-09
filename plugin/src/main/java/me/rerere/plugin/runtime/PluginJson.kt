package me.rerere.plugin.runtime

import kotlinx.serialization.json.Json

val PluginJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
