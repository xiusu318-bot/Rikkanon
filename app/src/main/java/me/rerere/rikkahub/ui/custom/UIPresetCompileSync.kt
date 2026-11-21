package me.rerere.rikkahub.ui.custom

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.UIPreset

private const val TAG = "UIPresetCompileSync"

/**
 * 界面绘制只读预设里保存的编译结果。保存时编辑页会编译；这里负责其余情况：
 * 旧版本保存的预设、内置样板、引擎升级（[CustomUICodeEngine.ENGINE_VERSION]）后，
 * 编译结果与代码不符的预设在后台重新编译一次并写回设置。
 *
 * 只在界面自定义总开关开启时工作（关闭时什么都不做）。
 */
object UIPresetCompileSync {
    fun start(scope: CoroutineScope, store: SettingsStore) {
        scope.launch(Dispatchers.Default) {
            store.settingsFlow
                .filter { !it.init && it.enableCustomUi }
                .map { settings -> settings.uiPresets.filter { it.needsCompile() }.map { it.id to it.code } }
                .distinctUntilChanged()
                .collect { stale ->
                    for ((id, code) in stale) {
                        val base = store.settingsFlow.value.uiPresets.firstOrNull { it.id == id && it.code == code }
                            ?: continue
                        val result = try {
                            CustomUICodeEngine.compileUICode(code, base)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "compile ui preset ${base.name} failed: ${e.message}")
                            base.copy(compileError = e.message ?: e.toString())
                        }
                        store.update { latest ->
                            latest.copy(
                                uiPresets = latest.uiPresets.map { p ->
                                    // 编译期间用户改了代码就丢弃本次结果，下一轮按新代码处理
                                    if (p.id != id || p.code != code) p
                                    else result.copy(name = p.name, description = p.description, enabled = p.enabled)
                                }
                            )
                        }
                    }
                }
        }
    }
}

/** 有代码、不是 JSON、且保存的编译结果不是由当前代码与引擎版本得到的。 */
fun UIPreset.needsCompile(): Boolean {
    val trimmed = code.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("{")) return false
    return compiledCodeHash != CustomUICodeEngine.codeHash(code)
}
