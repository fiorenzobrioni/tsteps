package com.callbackdev.tsteps.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.work.StepSyncWorker
import com.callbackdev.tsteps.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The `tsteps --today` home widget. Deliberately passive on battery (tweather's
 * contract): no `updatePeriodMillis`, no polling of its own — it renders
 * persisted state and is repainted by whoever moves it (the sync workers, the
 * tracking service's minute tick, the app leaving the foreground). The ↻ glyph
 * is the only user-initiated sample, and it is served here in the broadcast
 * rather than queued, because a queued read arrives when the scheduler feels
 * like it. Simpler than its tweather parent on purpose: there are no cities, so
 * there is nothing to pin and no configuration activity.
 */
class TstepsWidgetProvider : AppWidgetProvider() {

    // The hooks only record what the broadcast needs; the actual suspend work runs
    // once in onReceive. goAsync() is consume-once, and a single broadcast can hit
    // two hooks (ACTION_APPWIDGET_ENABLE_AND_UPDATE → onEnabled + onUpdate), where
    // a second goAsync() would return null (tweather's crash, kept fixed).
    private var needsRender = false
    private var needsReconcile = false

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        needsRender = true
    }

    /**
     * Resize is deliberately NOT handled: the sizes map exists so the host
     * re-picks the right tier itself, in-process. Pushing a fresh RemoteViews from
     * here raced that and left shrunk widgets showing a clipped tall transcript.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) = Unit

    /** Placement/removal: reconcile is idempotent and keeps the jobs honest. */
    override fun onEnabled(context: Context) {
        needsReconcile = true
    }

    override fun onDisabled(context: Context) {
        needsReconcile = true
    }

    override fun onReceive(context: Context, intent: Intent) {
        needsRender = false
        needsReconcile = false
        super.onReceive(context, intent)
        val sample = intent.action == ACTION_REFRESH
        val render = needsRender
        val reconcile = needsReconcile
        if (!sample && !render && !reconcile) return

        // Nullable despite the platform signature: goAsync() only returns a result
        // while a real broadcast is being dispatched. The work still has to run.
        val pendingResult: BroadcastReceiver.PendingResult? = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (reconcile) SyncScheduler.reconcile(context)
                if (sample) {
                    refresh(context)
                } else if (render) {
                    TstepsWidgetUpdater.updateAllSafely(context)
                }
            } catch (e: Exception) {
                // An unhandled throw here would crash the app from a broadcast; the
                // widget simply keeps whatever it was showing.
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * The ↻ tap, start to finish: everything the user waits for, then the tail
     * nobody watches. The split is also the seam the tests drive — a queued tail
     * that runs synchronously (as it does under a test WorkManager) would hide
     * whether the answer came from the tap or from the queue.
     */
    private suspend fun refresh(context: Context) {
        sampleAndRepaint(context)
        enqueueTailSync(context)
    }

    @VisibleForTesting
    internal suspend fun sampleAndRepaint(context: Context) {
        // The numbers cannot move until the sample lands, so a plain repaint here
        // would be pixel-identical and the tap would read as dead. The glyph says
        // "heard you" on every tier, which `# last_sync` cannot (it is last in the
        // transcript, so the common sizes cut it).
        TstepsWidgetUpdater.updateAllSafely(context, syncing = true)
        try {
            sampleNow(context)
        } catch (e: Exception) {
            // Fall through to the repaint regardless: a widget left wearing `…`
            // for good would be a worse lie than a number that did not move.
        }
        TstepsWidgetUpdater.updateAllSafely(context)
    }

    /**
     * Reads the counter HERE rather than delegating the read to WorkManager: an
     * expedited request degrades to an ordinary job once the quota is spent —
     * that is what `RUN_AS_NON_EXPEDITED_WORK_REQUEST` means — and an app the
     * user never opens sits in a standby bucket where an ordinary job can be
     * deferred for minutes. That delay was the whole "the tap doesn't always
     * work". One counter read is a single sensor event; it belongs in the tap.
     */
    private suspend fun sampleNow(context: Context) {
        if (!SyncScheduler.hasPermission(context)) return
        val reader = ServiceLocator.stepSensorReader(context)
        if (!reader.isAvailable) return
        val reading = reader.readCurrent(SAMPLE_TIMEOUT_MS) ?: return
        ServiceLocator.stepRepository(context).ingest(reading)
    }

    companion object {
        const val ACTION_REFRESH = "com.callbackdev.tsteps.widget.REFRESH"

        /** Distinct from the periodic job so a tap never disturbs its cycle. */
        const val MANUAL_SYNC_NAME = "step-sync-manual"

        /**
         * Tighter than the workers' 5s default: a broadcast's budget is not a
         * worker's, and the tail sync below covers a counter that stays silent.
         */
        private const val SAMPLE_TIMEOUT_MS = 3_000L

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, TstepsWidgetProvider::class.java))
                .isNotEmpty()

        /**
         * The rest of a sync pass after the tap already got its number: the day
         * commit, the walk detector, Health Connect. KEEP swallows tap-spam — a
         * second tap while one is pending piggybacks on it, and the part the user
         * actually watches has already run inline. Expedited is safe without
         * `getForegroundInfo` on minSdk 33 (the foreground-service fallback is a
         * pre-S requirement).
         */
        fun enqueueTailSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<StepSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_SYNC_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
