package com.callbackdev.tsteps.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.widget.TstepsWidgetUpdater
import com.callbackdev.tsteps.work.StepSyncWorker
import com.callbackdev.tsteps.work.SyncScheduler
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One counter reading, taken in the foreground because that is the only place
 * Android delivers one.
 *
 * `TYPE_STEP_COUNTER` reports **on change**, and since Android 9 an app that is
 * not in the foreground receives no events from an on-change sensor at all — the
 * platform's own remedy is a foreground service. That single rule explains the
 * whole symptom the widget had: `readCurrent()` from the ↻ broadcast and from the
 * 15-minute worker returned silence, the working tree never advanced, and the
 * only number the widget ever showed was the one the app's live listener had
 * ingested while it was on screen. The tap painted `…`, waited, and repainted the
 * same figures.
 *
 * The battery contract is unchanged. This is not passive counting — it is the
 * user's own `↻`, and the service lives exactly as long as one sample: start,
 * read, ingest, repaint, stop, typically under a second. Passive counting still
 * runs no service, keeps no listener and holds no wake lock. The exception is the
 * same one `$ tsteps track` already is: an explicit command, in the foreground,
 * for as long as it takes and not one moment more.
 *
 * Started from [com.callbackdev.tsteps.widget.TstepsWidgetProvider]'s broadcast:
 * a widget tap is an explicit exemption from Android 12's ban on starting a
 * foreground service from the background, and `health` backed by
 * `ACTIVITY_RECOGNITION` is not one of the while-in-use types that lose that
 * exemption. Starting it from inside `onReceive` (never from a coroutine the
 * broadcast leaves behind) is what keeps the tap inside that window.
 */
class SampleService : Service() {

    /** IO: from end to end this is a sensor wait, DataStore and Room. */
    private val scope = CoroutineScope(SupervisorJob() + workContext)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A PendingIntent outlives the grant that justified it: without the
        // permission a `health` service is a SecurityException, and there would
        // be nothing to read anyway. Stopping before startForeground is the one
        // way out that does not trip the five-second start deadline.
        if (!SyncScheduler.hasPermission(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForegroundWith()
        inFlight = scope.launch {
            // Before anything slow: the numbers cannot move until the sample
            // lands, so without the glyph the tap reads as dead. Costs no disk —
            // it replays the frame already on screen (see ackTap). Off the main
            // thread, because painting it is a binder call to the launcher.
            TstepsWidgetUpdater.ackTap(applicationContext)
            var sampled = false
            try {
                withTimeout(SAMPLE_BUDGET_MS) { sampled = sample() }
            } catch (e: Exception) {
                // Includes the timeout above. Fall through to the repaint
                // regardless: a widget left wearing `…` for good would be a worse
                // lie than a number that did not move.
            } finally {
                // endTap BEFORE the repaint is requested, never after: a pass that
                // starts from here on is then guaranteed to paint ↻ — including
                // the one that swallows this request by being queued already.
                TstepsWidgetUpdater.endTap()
                TstepsWidgetUpdater.updateAllUninterruptibly(applicationContext)
                // The counter was read a moment ago when the sample landed, so the
                // queue must not read it again; when it did not, the queue is the
                // second chance and reads for itself.
                enqueueTailSync(applicationContext, alreadySampled = sampled)
                stop(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** True when a reading landed and became rows. */
    private suspend fun sample(): Boolean {
        val reader = ServiceLocator.stepSensorReader(applicationContext)
        if (!reader.isAvailable) return false
        val reading = reader.readCurrent(SAMPLE_TIMEOUT_MS) ?: return false
        ServiceLocator.stepRepository(applicationContext).ingest(reading)
        return true
    }

    /**
     * [stopSelf] with the id, not without: two quick taps are two starts, and the
     * bare form would let the first one to finish take the second one down with
     * it, sensor read and all.
     */
    private fun stop(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun startForegroundWith() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )
    }

    /**
     * Required by the platform, and in practice never seen: since Android 12 the
     * system holds a foreground service's notification back for ten seconds, and
     * this service is done in one. [SAMPLE_BUDGET_MS] is under that ceiling on
     * purpose — a sample slow enough to be visible is a sample worth explaining.
     */
    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tsteps)
            .setContentTitle(TITLE)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_sample),
                // MIN: it exists to satisfy startForeground, not to be read.
                NotificationManager.IMPORTANCE_MIN
            )
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "sample"

        /** Distinct from the tracking service's: the two can overlap. */
        private const val NOTIFICATION_ID = 2

        /** The command the ↻ stands for. Code, so English, like every `$` line. */
        private const val TITLE = "$ tsteps sync"

        /** Distinct from the periodic job so a tap never disturbs its cycle. */
        const val MANUAL_SYNC_NAME = "step-sync-manual"

        /** The workers' own: nothing here is racing a broadcast deadline. */
        private const val SAMPLE_TIMEOUT_MS = 5_000L

        /**
         * Sample plus ingest, capped under the ten seconds the platform waits
         * before it shows a foreground service's notification: the read is bounded
         * by [SAMPLE_TIMEOUT_MS], the DataStore and Room writes after it are not.
         */
        private const val SAMPLE_BUDGET_MS = 9_000L

        /**
         * Where the sample runs. IO, not Default: a sensor wait, DataStore, Room
         * and a WorkManager enqueue, and Default is sized for CPU work.
         */
        @VisibleForTesting
        internal var workContext: CoroutineContext = Dispatchers.IO

        /**
         * The last start's work, so a test can await it instead of racing it.
         * Written from onStartCommand (main looper) and never read in production.
         */
        @VisibleForTesting
        internal var inFlight: Job? = null
            private set

        /**
         * The ↻ tap. Called straight from `onReceive`, never from a coroutine it
         * leaves behind: the widget-tap exemption from Android 12's background
         * foreground-service ban lasts as long as the broadcast, and work handed
         * to another thread lands outside it.
         *
         * Returns false when the tap has nothing to ask for (no permission) or the
         * platform refused the start — the caller then owes the widget a repaint,
         * because no `…` was ever painted for it to take off.
         */
        fun start(context: Context): Boolean {
            if (!SyncScheduler.hasPermission(context)) return false
            return try {
                context.startForegroundService(Intent(context, SampleService::class.java))
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException and its relatives: the
                // exemption is documented, an OEM refusing it is not a crash.
                false
            }
        }

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
        fun enqueueTailSync(context: Context, alreadySampled: Boolean) {
            val request = OneTimeWorkRequestBuilder<StepSyncWorker>()
                // Safe without `getForegroundInfo` on minSdk 33 (the
                // foreground-service fallback is a pre-S requirement).
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
