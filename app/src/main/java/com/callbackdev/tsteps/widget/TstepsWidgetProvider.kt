package com.callbackdev.tsteps.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.work.StepSyncWorker
import com.callbackdev.tsteps.work.SyncScheduler
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
        val appContext = context.applicationContext
        inFlight = CoroutineScope(workContext).launch {
            try {
                // A broadcast has a budget and nothing below is unbounded on its
                // own: one hung DataStore read would hold the PendingResult open
                // until the system killed the app for not finishing.
                withTimeout(BROADCAST_BUDGET_MS) {
                    if (reconcile) SyncScheduler.reconcile(appContext)
                    if (sample) {
                        runTap(appContext)
                    } else if (render) {
                        TstepsWidgetUpdater.updateAllSafely(appContext)
                    }
                }
            } catch (e: Exception) {
                // Includes the timeout above. An unhandled throw here would crash
                // the app from a broadcast; the widget keeps what it was showing —
                // except for the glyph, which runTap's own finally always settles.
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
    private suspend fun runTap(context: Context) {
        sampleAndRepaint(context)
        enqueueTailSync(context, alreadySampled = true)
    }

    /**
     * Acknowledge, sample, settle — and exactly one full repaint, at the end.
     *
     * It used to be two: a full gather-and-paint to show `…`, then another to show
     * the answer. The first one read the whole day, the whole commit history and
     * the day's walks off disk to change one glyph, on the one path whose entire
     * value is latency. The acknowledgment now replays the frame already on screen
     * (see [TstepsWidgetUpdater.ackTap]) and the repaint below is the only pass.
     */
    @VisibleForTesting
    internal suspend fun sampleAndRepaint(context: Context) {
        TstepsWidgetUpdater.ackTap(context)
        try {
            sampleNow(context)
        } catch (e: Exception) {
            // Fall through to the repaint regardless: a widget left wearing `…`
            // for good would be a worse lie than a number that did not move.
        } finally {
            // endTap BEFORE the repaint is requested, never after: a pass that
            // starts from here on is then guaranteed to paint ↻ — including the
            // one that swallows this request by being queued already.
            TstepsWidgetUpdater.endTap()
            TstepsWidgetUpdater.updateAllUninterruptibly(context)
        }
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

        /**
         * The whole broadcast, capped. The refresh intent carries
         * `FLAG_RECEIVER_FOREGROUND`, which buys dispatch latency at the price of
         * the 10s foreground-receiver deadline instead of 60s; this leaves room
         * to finish the PendingResult inside it.
         */
        private const val BROADCAST_BUDGET_MS = 8_000L

        /**
         * Where the broadcast's work runs. IO, not Default: from end to end it is
         * DataStore, Room and a WorkManager enqueue, and Default is sized for CPU
         * work — a handful of blocking reads there can starve the pool.
         */
        @VisibleForTesting
        internal var workContext: CoroutineContext = Dispatchers.IO

        /**
         * The last broadcast's work, so a test can await it instead of racing it.
         * Written from onReceive (main looper) and never read in production.
         */
        @VisibleForTesting
        internal var inFlight: Job? = null
            private set

        /**
         * The rest of a sync pass after the tap already got its number: the day
         * commit, the walk detector, Health Connect.
         *
         * REPLACE, not KEEP. KEEP was meant to let a second tap piggyback on a
         * pending tail, but [StepSyncWorker] answers a bad pass with `retry()`,
         * and a retrying unique work is *pending* — so KEEP handed every later
         * tap to a job sitting out an exponential backoff that tops out at five
         * hours. The part the user watches has already run inline either way;
         * what is left is idempotent and belongs to the newest tap.
         */
        fun enqueueTailSync(context: Context, alreadySampled: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<StepSyncWorker>()
                // Safe without `getForegroundInfo` on minSdk 33 (the
                // foreground-service fallback is a pre-S requirement).
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                // The tap read the counter milliseconds ago; a second read would
                // return the same value and re-anchor on it for nothing.
                .setInputData(workDataOf(StepSyncWorker.KEY_SKIP_SAMPLE to alreadySampled))
                // Linear and short: the default exponential backoff is built for
                // work that can wait, and none of this can wait five hours.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_SYNC_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
