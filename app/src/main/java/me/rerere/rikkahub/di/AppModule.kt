package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.remote.OutboundTarget
import me.rerere.rikkahub.data.remote.configureOutboundProxy
import me.rerere.rikkahub.data.remote.resolveProxy
import me.rerere.rikkahub.service.ChatFloatingTracker
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.ui.floating.ChatFloatingWindows
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    // 插件注册表：PluginHost 只是登记与运行容器；PluginBridge 供各挂点读取 hook 链
    single {
        val context: android.content.Context = get()
        val appScope: AppScope = get()
        val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = get()
        me.rerere.plugin.host.PluginHost(
            store = me.rerere.plugin.store.PluginStore(java.io.File(context.filesDir, "plugins")),
        ) { host ->
            me.rerere.plugin.host.DefaultPluginServices(
                host = host,
                // 插件 fetch 使用独立客户端，不挂载插件 intercept，默认跟随 LLM 出站代理设置
                fetchClient = okhttp3.OkHttpClient.Builder()
                    .configureOutboundProxy {
                        settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.LLM)
                    }
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                    .build(),
                hostApi = me.rerere.rikkahub.data.plugin.AppPluginHostApi(context),
            )
        }.also { host ->
            host.onChanged = {
                runCatching { me.rerere.rikkahub.data.plugin.PluginScheduleManager.sync(context, host) }
                appScope.launch {
                    runCatching { me.rerere.rikkahub.data.plugin.PluginCapabilitySync.sync(host, settingsStore) }
                }
            }
        }
    }

    single { me.rerere.rikkahub.data.plugin.PluginBridge(get()) }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(
            context = get(),
            baseClient = get(),
            pluginDelegate = me.rerere.rikkahub.data.plugin.PluginTtsProvider(get()),
        )
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get(), get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 确保进程启动即开始订阅，不用由 ChatService 持有引用。
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    // ChatService 只在后台才用到，延迟创建
    single {
        ChatFloatingTracker(
            appScope = get(),
            lazyChatService = lazy { get() },
            settingsStore = get(),
        )
    }

    // 同上，进程启动即开始监听前后台切换
    single(createdAtStart = true) {
        ChatFloatingWindows(
            context = get(),
            appScope = get(),
            tracker = get(),
            lazyChatService = lazy { get() },
        )
    }

    single {
        ChatToolFactory(
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            pluginBridge = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationLoop = get(),
            memoryConsolidationScheduler = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            pluginBridge = get(),
            folderRepository = get()
        )
    }

    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            remoteFileStore = get(),
            okHttpClient = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
