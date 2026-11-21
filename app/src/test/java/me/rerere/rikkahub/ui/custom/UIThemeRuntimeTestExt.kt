package me.rerere.rikkahub.ui.custom

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 单元测试辅助：通过生产路径 [UIThemeRuntime.renderNow] + [UIThemeRuntime.renderTick] 等待渲染结果，不在正式类里留单测方法。 */
internal fun UIThemeRuntime.renderAwait(
    name: String,
    dataJson: String,
    version: Int,
    timeoutMs: Long,
): UISlotResult? = runBlocking {
    withTimeoutOrNull(timeoutMs) {
        ready.first { it }
        while (true) {
            val tickBefore = renderTick.value
            renderNow(name, dataJson, version, wait = false)?.let { return@withTimeoutOrNull it }
            renderTick.first { it != tickBefore }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
}

internal fun UIThemeRuntime.renderItemAwait(
    itemFn: String,
    index: Int,
    timeoutMs: Long,
): UISlotResult? = runBlocking {
    withTimeoutOrNull(timeoutMs) {
        ready.first { it }
        while (true) {
            val tickBefore = renderTick.value
            renderItemNow(itemFn, index, wait = false)?.let { return@withTimeoutOrNull it }
            renderTick.first { it != tickBefore }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
}
