package be.verdonckit.zapper.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import be.verdonckit.zapper.data.SyncResult
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.plugin.UpdateOutcome
import java.util.concurrent.TimeUnit

/**
 * Nachtelijk onderhoud op de TV zelf:
 *  1. plugin-updates ophalen (met self-test en rollback)
 *  2. catalogus van elke provider opnieuw indexeren
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val prefs = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

        // Worker kan in een vers proces starten, vóór de app zelf de plugins laadde.
        if (graph.plugins.plugins.value.isEmpty()) graph.plugins.loadInstalled()

        // Na een app-update met een nieuwe plugin-API laden de oude builds niet meer: dan meteen updaten.
        if (inputData.getBoolean(KEY_ONLY_IF_STALE, false) && !graph.plugins.hasUnloadedPlugins) {
            val last = prefs.getLong("lastSync", 0)
            val fresh = System.currentTimeMillis() - last < TimeUnit.HOURS.toMillis(12)
            if (fresh && graph.dao.count() > 0) return Result.success()
        }

        runCatching { graph.plugins.checkForUpdates() }
            .onSuccess { outcomes -> outcomes.forEach { Log.i(TAG, describe(it)) } }
            .onFailure { Log.w(TAG, "plugin-update mislukt: ${it.message}") }

        var anyOk = false
        for (plugin in graph.plugins.plugins.value.values) {
            val result = runCatching { graph.repository.sync(plugin) }
                .getOrElse { SyncResult.Failed(plugin.id, it.message ?: it.toString()) }
            Log.i(TAG, result.toString())
            if (result is SyncResult.Ok) anyOk = true
        }
        if (anyOk) prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
        return Result.success()
    }

    private fun describe(o: UpdateOutcome) = when (o) {
        is UpdateOutcome.Installed -> "${o.id}: ${o.version} geïnstalleerd"
        is UpdateOutcome.RolledBack -> "${o.id}: ${o.version} teruggedraaid (${o.reason})"
        is UpdateOutcome.Failed -> "${o.id}: update mislukt (${o.reason})"
    }

    companion object {
        private const val TAG = "Maintenance"
        private const val KEY_ONLY_IF_STALE = "onlyIfStale"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context, onlyIfStale: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<MaintenanceWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_ONLY_IF_STALE to onlyIfStale))
                .build()
            // Achter een lopende sync aanschuiven, niet onderbreken: een sync schrijft pas op het einde
            // per zender weg (een tweede login of "Catalogus nu syncen" brak zo de Play-sync af).
            WorkManager.getInstance(context).enqueueUniqueWork("maintenance-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
