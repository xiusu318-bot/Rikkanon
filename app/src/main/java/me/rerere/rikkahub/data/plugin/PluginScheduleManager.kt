package me.rerere.rikkahub.data.plugin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

private const val WORK_TAG = "rikkanon_plugin_schedule"

class PluginScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val pluginId = inputData.getString("pluginId") ?: return Result.success()
        val name = inputData.getString("name") ?: return Result.success()
        val host = get<PluginHost>()
        val (rt, reg) = host.findNamed(PluginHookKind.SCHEDULE, pluginId, name) ?: return Result.success()
        return runCatching {
            val ctx = buildJsonObject {
                put("task", "other")
                put("schedule", name)
            }
            rt.invoke(PluginHookKind.SCHEDULE, reg.handlerId, listOf(ctx))
            Result.success()
        }.getOrElse { e ->
            host.recordError(pluginId, e)
            Result.failure()
        }
    }
}

object PluginScheduleManager {
    fun sync(context: Context, host: PluginHost) {
        val wm = WorkManager.getInstance(context)
        val activeSchedules = host.chain(PluginHookKind.SCHEDULE)
        val activeWorkNames = mutableSetOf<String>()

        for ((runtime, reg) in activeSchedules) {
            val meta = reg.meta.jsonObject
            val name = meta["name"]?.jsonPrimitive?.content ?: continue
            val interval = (meta["intervalMinutes"]?.jsonPrimitive?.intOrNull ?: 15).coerceAtLeast(15).toLong()
            val workName = "plugin_schedule_${runtime.id}_$name"
            activeWorkNames += workName

            val req = PeriodicWorkRequestBuilder<PluginScheduleWorker>(interval, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .setInputData(
                    workDataOf(
                        "pluginId" to runtime.id,
                        "name" to name,
                    )
                )
                .build()
            wm.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        if (activeWorkNames.isEmpty()) {
            wm.cancelAllWorkByTag(WORK_TAG)
        }
    }
}
