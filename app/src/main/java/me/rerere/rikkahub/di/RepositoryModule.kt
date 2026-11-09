package me.rerere.rikkahub.di

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.remote.OutboundTarget
import me.rerere.rikkahub.data.remote.WorkspaceLoopbackProxyBridge
import me.rerere.rikkahub.data.remote.configureOutboundProxy
import me.rerere.rikkahub.data.remote.resolveProxy
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.ssh.SshConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        TranslationHistoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        val settingsStore: SettingsStore = get()
        val appScope: me.rerere.rikkahub.AppScope = get()
        val bridgePort = WorkspaceLoopbackProxyBridge.ensureStarted {
            settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.WORKSPACE)
        }
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val hookSoFile = File(nativeLibDir, "librikka_net_hook.so").takeIf { it.isFile }
        val activeWorkspaceProxyUrl: () -> String? = {
            val activeProfile = settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.WORKSPACE)
            if (activeProfile != null) "http://127.0.0.1:$bridgePort" else null
        }
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = nativeLibDir,
                proxyUrlProvider = activeWorkspaceProxyUrl,
            ),
            // 同一份挂载表同时用于 PRoot 的 -b 参数 (命令执行与终端)、文件工具的路径解析和写入审批, 避免多处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                    writable = true,
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() },
                    target = "/builtin_skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        ).also {
            // 当用户在 UI 切换工作区出站代理时，实时更新所有已有工作区的 /run/rikkahub-net.conf，已打开的终端立即生效
            appScope.launch(Dispatchers.IO) {
                val patcher = RootfsPatcher()
                settingsStore.settingsFlow.collect { settings ->
                    val proxy = settings.networkSetting.resolveProxy(OutboundTarget.WORKSPACE)
                    val url = if (proxy != null) "http://127.0.0.1:$bridgePort" else null
                    File(context.filesDir, "workspaces").listFiles()?.forEach { wsDir ->
                        val linuxDir = File(wsDir, "linux")
                        if (linuxDir.isDirectory) {
                            runCatching {
                                patcher.patch(
                                    linuxDir,
                                    RootfsPatchOptions(
                                        proxyUrl = url,
                                        netHookSoFile = hookSoFile,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    single {
        val settingsStore: SettingsStore = get()
        val workspaceDownloadClient = OkHttpClient.Builder()
            .configureOutboundProxy {
                settingsStore.settingsFlow.value.networkSetting.resolveProxy(OutboundTarget.WORKSPACE)
            }
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        RootfsInstaller(
            manager = get(),
            openUrlStream = { url, connectTimeoutMs, readTimeoutMs ->
                val callClient = workspaceDownloadClient.newBuilder()
                    .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()
                val response = callClient.newCall(
                    Request.Builder().url(url).get().build()
                ).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("Rootfs download failed: HTTP ${response.code}")
                }
                val body = response.body ?: run {
                    response.close()
                    throw IOException("Rootfs download returned empty body")
                }
                val contentLength = body.contentLength()
                val total: Long? = if (contentLength > 0L) contentLength else null
                Pair(total, body.byteStream())
            },
        )
    }

    single {
        SshConnectionPool()
    } onClose { it?.close() }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
