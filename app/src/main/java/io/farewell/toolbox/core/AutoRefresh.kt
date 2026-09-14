package io.farewell.toolbox.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutoRefresh {

    private const val PREFS = "farewell"
    private const val KEY_ENABLED = "auto_refresh_enabled"
    private const val KEY_LAST = "auto_refresh_last"
    private const val JOB_ID = 8801
    private const val INTERVAL_MS = 6L * 60 * 60 * 1000

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, AutoRefreshJobService::class.java)
        val job = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(INTERVAL_MS)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun lastResult(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, "").orEmpty()

    fun storeResult(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST, message)
            .apply()
    }

    suspend fun runNow(context: Context): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val parts = mutableListOf<String>()

        val fetch = PifAutoFetch.fetch(context) { }
        parts += if (fetch != null) "PIF: ${fetch.source}" else "PIF: fetch failed"

        val applied = PlayIntegritySetup.apply(context, PlayIntegrityFlags())
        parts += applied.message

        val keyboxes = PlayIntegritySetup.keyboxFiles(context)
        if (keyboxes.isNotEmpty()) {
            val keyboxNote = try {
                PlayIntegritySetup.validateAndPickHealthiest(context) { }
                    .lineSequence()
                    .firstOrNull { it.startsWith("Selected") || it.startsWith("No usable") }
                    ?: "keybox checked"
            } catch (throwable: Throwable) {
                "keybox check failed: ${throwable.message}"
            }
            parts += keyboxNote
        }

        parts += NativeService.applyStock(context)

        RootShell.run("am force-stop com.google.android.gms.unstable", 30)
        return "[$stamp] " + parts.joinToString("; ")
    }
}

class AutoRefreshJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AutoRefresh.storeResult(context, AutoRefresh.runNow(context))
            } catch (throwable: Throwable) {
                AutoRefresh.storeResult(context, "auto-refresh failed: ${throwable.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
