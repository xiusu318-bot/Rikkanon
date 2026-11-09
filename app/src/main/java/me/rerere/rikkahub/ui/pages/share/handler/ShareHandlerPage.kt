package me.rerere.rikkahub.ui.pages.share.handler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun ShareHandlerPage(text: String, image: String?) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val host = koinInject<PluginHost>()
    val version by host.registrationsVersion.collectAsStateWithLifecycle()
    val shareHandlers = androidx.compose.runtime.remember(version) {
        host.chain(PluginHookKind.SHARE)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.share_handler_page_title))
                }
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = vm.shareText,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )

                        image?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (shareHandlers.isNotEmpty()) {
                items(shareHandlers, key = { "${it.first.id}:${it.second.key}" }) { (runtime, reg) ->
                    val meta = reg.meta.jsonObject
                    val title = meta["title"]?.jsonPrimitive?.contentOrNull
                        ?: meta["name"]?.jsonPrimitive?.contentOrNull
                        ?: runtime.manifest.name
                    val desc = meta["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    Surface(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val ctx = buildJsonObject {
                                        put("task", "other")
                                        image?.let { put("image", it) }
                                    }
                                    runtime.invoke(
                                        PluginHookKind.SHARE,
                                        reg.handlerId,
                                        listOf(JsonPrimitive(vm.shareText), ctx),
                                    )
                                }.onSuccess {
                                    toaster.show("已交给插件 ${runtime.manifest.name}")
                                }.onFailure { e ->
                                    host.recordError(runtime.id, e)
                                    toaster.show(e.message ?: e.javaClass.name, type = ToastType.Error)
                                }
                            }
                        },
                        tonalElevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        ListItem(
                            headlineContent = { Text(text = title, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    text = if (desc.isNotBlank()) "$desc · ${runtime.manifest.name}" else runtime.manifest.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            items(settings.assistants) { assistant ->
                Surface(
                    onClick = {
                        scope.launch {
                            vm.updateAssistant(assistant.id)
                            navigateToChatPage(
                                navigator = navController,
                                initText = vm.shareText.base64Encode(),
                                initFiles = image?.let { listOf(it.toUri()) } ?: emptyList()
                            )
                        }
                    },
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = assistant.name.ifEmpty {
                                    stringResource(R.string.assistant_page_default_assistant)
                                },
                                maxLines = 1
                            )
                        },
                    )
                }
            }
        }
    }
}
