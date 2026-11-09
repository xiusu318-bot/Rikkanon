package me.rerere.rikkahub.di

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.plugin.http.PluginInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.MemoryConsolidator
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.remote.RikkaHubAPI
import me.rerere.rikkahub.data.remote.ModelsDevUpdater
import me.rerere.rikkahub.data.remote.SponsorAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.remote.OutboundTarget
import me.rerere.rikkahub.data.remote.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.remote.SettingsProxySelector
import me.rerere.rikkahub.data.remote.SettingsSocks5Authenticator
import me.rerere.rikkahub.data.remote.WorkspaceLoopbackProxyBridge
import me.rerere.rikkahub.data.remote.configureOutboundProxy
import me.rerere.rikkahub.data.remote.resolveProxy
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import me.rerere.rikkahub.service.memoryDreamKeepAlive
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        AppDatabaseFactory.create(context)
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        val engine = PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
        // 消息模板按助手 ID 缓存，内容取自设置，设置变了就让已编译的模板失效
        val settingsStore = get<SettingsStore>()
        get<AppScope>().launch {
            settingsStore.settingsFlow.collect { engine.templateCache.invalidateAll() }
        }
        engine
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().memoryConsolidationDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        get<AppDatabase>().mediaCreationDao()
    }

    single {
        get<AppDatabase>().translationHistoryDao()
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        GenerationLoop(
            context = get(),
            providerManager = get(),
            json = get(),
            pluginBridge = get(),
        )
    }

    single {
        MemoryConsolidator(
            generationLoop = get(),
            memoryRepository = get(),
            conversationRepo = get(),
            consolidationDao = get(),
            settingsStore = get(),
            pluginBridge = get(),
        )
    }

    single {
        MemoryConsolidationScheduler(
            scope = get<AppScope>(),
            worker = get<MemoryConsolidator>(),
            keepAlive = memoryDreamKeepAlive(get<Context>()),
            isAppVisible = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
        )
    }

    single {
        TranslationHandler(providerManager = get(), pluginBridge = get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        // 启动本地工作区回环代理桥（供 PRoot 沙盒环境与已打开终端动态路由到 workspaceProxy）
        WorkspaceLoopbackProxyBridge.ensureStarted {
            settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.WORKSPACE)
        }
        val initialLlmProxy = settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.LLM)
        val appliedProxySetting = AtomicReference(initialLlmProxy)
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .configureOutboundProxy {
                settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.LLM)
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val currentProxy = settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.LLM)
                if (appliedProxySetting.getAndSet(currentProxy) != currentProxy) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "RikkaHub-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(PluginInterceptor(get()))
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
        val host: me.rerere.plugin.host.PluginHost = get()
        SearchService.pluginExecutor = { pluginId, name, method, args ->
            host.callMethod(pluginId, me.rerere.plugin.manifest.PluginHookKind.SEARCH, name, method, args)
        }
        client.also { SearchService.init(it, get()) }
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        ModelsDevUpdater(context = get(), client = get())
    }

    single {
        ProviderManager(client = get(), context = get()).apply {
            registerProvider("plugin", me.rerere.rikkahub.data.plugin.PluginAiProvider(get()))
        }
    }

    single { BackupManager(context = get(), database = get(), settingsStore = get(), json = get()) }

    single {
        WebDavSync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        val baseOkHttpClient: OkHttpClient = get()
        HttpClient(OkHttp) {
            engine {
                preconfigured = baseOkHttpClient
            }
        }
    }

    single {
        S3Sync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single {
        RemoteFileStore(
            settingsStore = get(),
            httpClient = get(),
            scope = get<AppScope>()
        )
    }

    single {
        MediaGenerationManager(
            client = get(),
            pluginDelegate = me.rerere.rikkahub.data.plugin.PluginMediaGenProvider(get()),
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .client(get())
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
