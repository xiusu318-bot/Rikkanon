package me.rerere.plugin.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.plugin.manifest.PluginManifest
import me.rerere.plugin.runtime.PluginJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 已安装插件在磁盘上的表示：`<root>/<id>/manifest.json`、`main.js`、`storage.json`。 */
class InstalledPlugin(val dir: File, val manifest: PluginManifest) {
    val id: String get() = manifest.id
    fun readMain(): String = File(dir, manifest.main).readText()
    val storageFile: File get() = File(dir, "storage.json")
}

/** 宿主侧插件状态（启用、优先级、超时），由宿主持久化；插件自身无法修改。插件配置由插件自己存在 storage 中。 */
@Serializable
data class PluginState(
    val enabled: Boolean = false,
    val priority: Int = 0,
    /** null = 默认；0 = 不限。 */
    val callTimeoutMillis: Long? = null,
    /** QuickJS 内存上限（MB），null = 默认。 */
    val memoryLimitMb: Int? = null,
    /** 被单独关闭的注册项 key。 */
    val disabled: Set<String> = emptySet(),
)

@Serializable
data class PluginStates(val plugins: Map<String, PluginState> = emptyMap())

class PluginStore(private val root: File) {
    private val statesFile = File(root, "_states.json")
    private val storages = ConcurrentHashMap<String, PluginKvStorage>()

    init {
        root.mkdirs()
    }

    /** 获取插件的 KV 存储单例（JS 与宿主 UI 共用同一实例，保证内存缓存实时同步）。 */
    fun storage(id: String): PluginKvStorage = storages.getOrPut(id) {
        PluginKvStorage(File(File(root, id), "storage.json"))
    }

    fun list(): List<InstalledPlugin> = root.listFiles()
        .orEmpty()
        .filter { it.isDirectory && File(it, "manifest.json").isFile }
        .map { dir -> InstalledPlugin(dir, PluginManifest.parse(File(dir, "manifest.json").readText())) }
        .sortedBy { it.id }

    fun get(id: String): InstalledPlugin? = list().find { it.id == id }

    /** 从一组文件（相对路径 -> 内容）安装插件。已存在同 id 时覆盖文件但保留 storage 与状态。 */
    fun install(files: Map<String, ByteArray>): InstalledPlugin {
        val manifestBytes = files["manifest.json"] ?: error("插件包缺少 manifest.json")
        val manifest = PluginManifest.parse(manifestBytes.decodeToString())
        require(files.containsKey(manifest.main)) { "插件包缺少入口文件 ${manifest.main}" }
        val dir = File(root, manifest.id)
        val storage = File(dir, "storage.json").takeIf { it.isFile }?.readBytes()
        dir.deleteRecursively()
        dir.mkdirs()
        files.forEach { (path, bytes) ->
            val target = File(dir, path).canonicalFile
            require(target.path.startsWith(dir.canonicalPath + File.separator)) { "插件包路径越界: $path" }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        storage?.let { File(dir, "storage.json").writeBytes(it) }
        storages.remove(manifest.id)
        return InstalledPlugin(dir, manifest)
    }

    fun uninstall(id: String) {
        storages.remove(id)
        File(root, id).deleteRecursively()
        updateStates { it.copy(plugins = it.plugins - id) }
    }

    @Synchronized
    fun readStates(): PluginStates =
        if (statesFile.isFile) PluginJson.decodeFromString(PluginStates.serializer(), statesFile.readText())
        else PluginStates()

    @Synchronized
    fun updateStates(update: (PluginStates) -> PluginStates): PluginStates {
        val next = update(readStates())
        statesFile.writeText(PluginJson.encodeToString(PluginStates.serializer(), next))
        return next
    }
}

/** 插件私有 KV 存储，写入即落盘。 */
class PluginKvStorage(private val file: File) {
    private var cache: MutableMap<String, JsonElement>? = null

    @Synchronized
    private fun data(): MutableMap<String, JsonElement> = cache ?: (
        if (file.isFile) PluginJson.decodeFromString(JsonObject.serializer(), file.readText()).toMutableMap()
        else mutableMapOf()
        ).also { cache = it }

    @Synchronized
    fun get(key: String): JsonElement? = data()[key]

    @Synchronized
    fun set(key: String, value: JsonElement) {
        data()[key] = value
        flush()
    }

    @Synchronized
    fun delete(key: String) {
        data().remove(key)
        flush()
    }

    @Synchronized
    fun keys(): List<String> = data().keys.toList()

    private fun flush() {
        file.parentFile?.mkdirs()
        file.writeText(PluginJson.encodeToString(JsonObject.serializer(), JsonObject(data())))
    }
}
