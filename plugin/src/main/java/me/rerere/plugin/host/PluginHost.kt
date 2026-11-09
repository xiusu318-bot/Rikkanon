package me.rerere.plugin.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import me.rerere.plugin.error.PluginException
import me.rerere.plugin.registry.PluginRegistration
import me.rerere.plugin.runtime.PluginRuntime
import me.rerere.plugin.runtime.PluginRuntimeServices
import me.rerere.plugin.store.InstalledPlugin
import me.rerere.plugin.store.PluginKvStorage
import me.rerere.plugin.store.PluginState
import me.rerere.plugin.store.PluginStore

/** 插件最近一次错误，供管理页展示。 */
data class PluginErrorRecord(val pluginId: String, val message: String, val at: Long)

data class PluginLogLine(val pluginId: String, val level: String, val text: String, val at: Long)

/**
 * 插件宿主：负责安装、启停、按优先级提供 hook 链。
 *
 * 只有通过 [setEnabled] 由用户界面调用才能启用插件，插件运行时没有任何修改自身状态的途径。
 * 没有启用的插件时，[chain] 恒为空列表。
 */
class PluginHost(
    val store: PluginStore,
    private val servicesFactory: (PluginHost) -> PluginRuntimeServices,
) {
    private val mutex = Mutex()
    private val runtimes = MutableStateFlow<Map<String, PluginRuntime>>(emptyMap())
    val activeRuntimes: StateFlow<Map<String, PluginRuntime>> = runtimes.asStateFlow()

    private val _errors = MutableStateFlow<List<PluginErrorRecord>>(emptyList())
    val errors: StateFlow<List<PluginErrorRecord>> = _errors.asStateFlow()

    private val _logs = MutableStateFlow<List<PluginLogLine>>(emptyList())
    val logs: StateFlow<List<PluginLogLine>> = _logs.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    private val _states = MutableStateFlow<Map<String, PluginState>>(emptyMap())
    val states: StateFlow<Map<String, PluginState>> = _states.asStateFlow()

    private val services by lazy { servicesFactory(this) }

    /** 任一运行中插件的注册表变化（mount / unmount / 启停）时递增，界面据此重组。 */
    private val _version = MutableStateFlow(0)
    val registrationsVersion: StateFlow<Int> = _version.asStateFlow()
    fun bumpVersion() = _version.update { it + 1 }

    /** 运行时内注册变化监听（如定时任务需要重新调度）。 */
    @Volatile
    var onChanged: () -> Unit = {}

    /** 启动时调用：加载所有已启用插件。单个插件加载失败会记录错误并保持未运行（不影响其他插件）。 */
    suspend fun startEnabled() = mutex.withLock {
        refreshLocked()
        _states.value.filterValues { it.enabled }.keys.forEach { id ->
            val plugin = _installed.value.find { it.id == id } ?: return@forEach
            runCatching { startLocked(plugin) }.onFailure { recordError(id, it) }
        }
    }

    suspend fun refresh() = mutex.withLock { refreshLocked() }

    private fun refreshLocked() {
        _installed.value = store.list()
        _states.value = store.readStates().plugins
    }

    suspend fun install(files: Map<String, ByteArray>): InstalledPlugin = mutex.withLock {
        val plugin = store.install(files)
        runtimes.value[plugin.id]?.let { stopLocked(plugin.id) }
        refreshLocked()
        if (_states.value[plugin.id]?.enabled == true) startLocked(plugin)
        plugin
    }

    suspend fun uninstall(id: String) = mutex.withLock {
        stopLocked(id)
        store.uninstall(id)
        refreshLocked()
    }

    /** 启用失败时抛出异常并保持禁用状态。 */
    suspend fun setEnabled(id: String, enabled: Boolean) = mutex.withLock {
        val plugin = _installed.value.find { it.id == id } ?: error("插件 $id 未安装")
        if (enabled) {
            startLocked(plugin)
        } else {
            stopLocked(id)
        }
        store.updateStates { s ->
            val cur = s.plugins[id] ?: PluginState()
            s.copy(plugins = s.plugins + (id to cur.copy(enabled = enabled)))
        }
        refreshLocked()
    }

    suspend fun updateState(id: String, update: (PluginState) -> PluginState) = mutex.withLock {
        store.updateStates { s ->
            val cur = s.plugins[id] ?: PluginState()
            s.copy(plugins = s.plugins + (id to update(cur)))
        }
        refreshLocked()
        val state = _states.value[id]
        runtimes.value[id]?.callTimeoutMillis = timeoutOf(state)
        bumpVersion()
        onChanged()
    }

    private suspend fun startLocked(plugin: InstalledPlugin) {
        stopLocked(plugin.id)
        val state = _states.value[plugin.id]
        val runtime = PluginRuntime(
            manifest = plugin.manifest,
            source = plugin.readMain(),
            services = services,
            callTimeoutMillis = timeoutOf(state),
            baseDir = plugin.dir,
            memoryLimitBytes = (state?.memoryLimitMb ?: PluginRuntime.DEFAULT_MEMORY_LIMIT_MB) * 1024L * 1024,
            onRegistrationsChanged = { bumpVersion() },
        )
        runtime.start()
        runtimes.update { it + (plugin.id to runtime) }
        bumpVersion()
        onChanged()
    }

    private fun timeoutOf(state: PluginState?): Long? = when (val t = state?.callTimeoutMillis) {
        null -> PluginRuntime.DEFAULT_CALL_TIMEOUT_MS
        0L -> null
        else -> t
    }

    private fun stopLocked(id: String) {
        val rt = runtimes.value[id] ?: return
        rt.close()
        runtimes.update { it - id }
        bumpVersion()
        onChanged()
    }

    fun installedPlugin(id: String): InstalledPlugin? = _installed.value.find { it.id == id }

    fun storage(id: String): PluginKvStorage = store.storage(id)

    /**
     * 返回某个 hook 的处理链：按插件优先级（数值小在前，相同按 id）排序，
     * 每个插件内部按注册顺序。[filter] 可按助手启用列表过滤插件。
     */
    fun chain(kind: String, filter: (String) -> Boolean = { true }): List<Pair<PluginRuntime, PluginRegistration>> {
        val active = runtimes.value
        if (active.isEmpty()) return emptyList()
        val states = _states.value
        return active.values
            .filter { filter(it.id) }
            .sortedWith(compareBy<PluginRuntime>({ states[it.id]?.priority ?: 0 }, { it.id }))
            .flatMap { rt ->
                val disabled = states[rt.id]?.disabled.orEmpty()
                rt.registrations.filter { it.kind == kind && it.key !in disabled }.map { rt to it }
            }
    }

    /**
     * 所有已启用插件注册的工具（按优先级）。插件之间工具重名直接报错；
     * 与内置 / MCP 工具的重名由组装工具列表的调用方检查。
     */
    fun tools(filter: (String) -> Boolean = { true }): List<Pair<PluginRuntime, PluginRegistration>> {
        val list = chain(me.rerere.plugin.manifest.PluginHookKind.TOOL, filter)
        list.groupBy { it.second.toolName }.forEach { (name, regs) ->
            if (regs.size > 1) {
                throw PluginException(
                    regs[1].first.id, "tool",
                    "工具名 \"$name\" 与插件 ${regs.map { it.first.id }.distinct().joinToString()} 冲突",
                )
            }
        }
        return list
    }

    /**
     * 派发只读事件：异步执行，不阻塞主流程；处理器出错记录到插件错误（主流程已在进行，不回滚）。
     */
    fun emitEvent(name: String, payload: kotlinx.serialization.json.JsonElement, scope: kotlinx.coroutines.CoroutineScope) {
        val handlers = chain(me.rerere.plugin.manifest.PluginHookKind.EVENT_PREFIX + name)
        if (handlers.isEmpty()) return
        handlers.forEach { (runtime, reg) ->
            scope.launch {
                runCatching { runtime.invoke("event:$name", reg.handlerId, listOf(payload)) }
                    .onFailure { recordError(runtime.id, it) }
            }
        }
    }

    /** 调用插件通过 rikka.expose 暴露的方法（插件面板 / 宿主界面使用）。 */
    suspend fun callExposed(pluginId: String, name: String, args: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val runtime = runtimes.value[pluginId] ?: throw PluginException(pluginId, "expose", "插件未启用")
        val reg = runtime.registrations.find {
            it.kind == me.rerere.plugin.manifest.PluginHookKind.EXPOSE &&
                (it.meta as? kotlinx.serialization.json.JsonObject)?.get("name")
                    ?.let { n -> (n as? kotlinx.serialization.json.JsonPrimitive)?.content } == name
        } ?: throw PluginException(pluginId, "expose", "插件没有暴露方法 \"$name\"")
        return runtime.invoke("expose:$name", reg.handlerId, listOf(args))
    }

    /** 按 kind + meta.name 找到某个插件的注册项。 */
    fun findNamed(kind: String, pluginId: String, name: String): Pair<PluginRuntime, PluginRegistration>? =
        chain(kind).find { (rt, reg) ->
            rt.id == pluginId && (reg.meta as? kotlinx.serialization.json.JsonObject)?.get("name")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == name
        }

    /** 调用多方法注册项（provider / search / tts ...）。 */
    suspend fun callMethod(
        pluginId: String,
        kind: String,
        name: String,
        method: String,
        args: List<kotlinx.serialization.json.JsonElement>,
        timeoutMillis: Long? = runtimes.value[pluginId]?.callTimeoutMillis,
    ): kotlinx.serialization.json.JsonElement {
        val (rt, reg) = findNamed(kind, pluginId, name)
            ?: throw PluginException(pluginId, kind, "插件未启用或没有注册 $kind \"$name\"")
        val methods = ((reg.meta as kotlinx.serialization.json.JsonObject)["methods"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
        if (method !in methods) throw PluginException(pluginId, kind, "$kind \"$name\" 没有实现 $method")
        return rt.invokeMethod(kind, reg.handlerId, method, args, timeoutMillis)
    }

    /** 调用 ui.mount 注册的回调（点击入口等）。 */
    suspend fun callUiHandler(pluginId: String, handlerId: String, args: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val runtime = runtimes.value[pluginId] ?: throw PluginException(pluginId, "ui", "插件未启用")
        return runtime.invoke("ui", handlerId, listOf(args))
    }

    fun recordError(pluginId: String, error: Throwable) {
        val message = (error as? PluginException)?.message ?: PluginException.wrap(pluginId, "error", error).message!!
        _errors.update { (it + PluginErrorRecord(pluginId, message, System.currentTimeMillis())).takeLast(MAX_RECORDS) }
    }

    fun appendLog(pluginId: String, level: String, text: String) {
        _logs.update { (it + PluginLogLine(pluginId, level, text, System.currentTimeMillis())).takeLast(MAX_RECORDS) }
    }

    suspend fun stopAll() = mutex.withLock {
        runtimes.value.keys.toList().forEach { stopLocked(it) }
    }

    companion object {
        private const val MAX_RECORDS = 500
    }
}
