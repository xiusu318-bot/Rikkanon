package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.json

object PluginSearchService : SearchService<SearchServiceOptions.PluginOptions> {
    override val name: String = "Plugin"

    @Composable
    override fun Description() {
        Text("由已启用插件通过 rikka.registerSearch 提供搜索与抓取实现")
    }

    override fun parameters(options: SearchServiceOptions.PluginOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", SEARCH_QUERY_DESCRIPTION)
                })
            },
            required = listOf("query"),
        )

    override fun scrapingParameters(options: SearchServiceOptions.PluginOptions): InputSchema? {
        if (!options.enableScrape) return null
        return InputSchema.Obj(
            properties = buildJsonObject {
                put("urls", buildJsonObject {
                    put("type", "array")
                    put("description", "urls to scrape")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
            },
            required = listOf("urls"),
        )
    }

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.PluginOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val exec = SearchService.pluginExecutor ?: error("插件搜索引擎未初始化")
            val opts = buildJsonObject {
                put("resultSize", commonOptions.resultSize)
            }
            val resultEl = exec(
                serviceOptions.pluginId,
                serviceOptions.serviceName,
                "search",
                listOf(params, opts),
            )
            json.decodeFromJsonElement(SearchResult.serializer(), resultEl)
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.PluginOptions,
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val exec = SearchService.pluginExecutor ?: error("插件搜索引擎未初始化")
            val resultEl = exec(
                serviceOptions.pluginId,
                serviceOptions.serviceName,
                "scrape",
                listOf(params),
            )
            json.decodeFromJsonElement(ScrapedResult.serializer(), resultEl)
        }.onFailure { if (it is CancellationException) throw it }
    }
}
