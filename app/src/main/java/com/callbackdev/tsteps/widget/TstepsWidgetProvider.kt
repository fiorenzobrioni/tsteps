package com.callbackdev.tsteps.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import com.callbackdev.tsteps.tracking.SampleService
import com.callbackdev.tsteps.work.SyncScheduler
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
 * tracking service's minute tick, the app leaving the foreground). Simpler than
 * its tweather parent on purpose: there are no cities, so there is nothing to pin
 * and no configuration activity.
 *
 * The ↻ glyph is the only user-initiated sample, and this receiver does not take
 * it: it hands the tap to [SampleService] and gets out of the way. `TYPE_STEP_COUNTER`
 * reports **on change**, and Android delivers no on-change event to an app that
 * is not in the foreground — a broadcast is not the foreground, so reading the
 * counter here returned silence however carefully it was asked. The service is
 * the platform's own answer, and starting it from inside `onReceive` is what
 * keeps the tap inside the widget-interaction exemption that allows it.
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
        if (intent.action == ACTION_REFRESH) {
            tap(context)
            return
        }
        val render = needsRender
        val reconcile = needsReconcile
        if (!render && !reconcile) return

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
                    if (render) TstepsWidgetUpdater.updateAllSafely(appContext)
                }
            } catch (e: Exception) {
                // Includes the timeout above. An unhandled throw here would crash
                // the app from a broadcast; the widget keeps what it was showing.
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * The ↻ tap, handed on **synchronously**. Not `goAsync()` and a coroutine: the
     * exemption that lets a widget tap start a foreground service lasts as long as
     * the broadcast is being dispatched, and work left running after `onReceive`
     * returns lands outside it.
     *
     * When the start does not happen — no permission, or a platform that refuses
     * the exemption — nobody painted `…`, so nothing has to take it off; the
     * widget is repainted anyway so a revoked permission reaches the glass
     * instead of waiting for the next pass, and the queue still gets the tail.
     */
    private fun tap(context: Context) {
        if (SampleService.start(context)) return
        val appContext = context.applicationContext
        val pendingResult: BroadcastReceiver.PendingResult? = goAsync()
        inFlight = CoroutineScope(workContext).launch {
            try {
                withTimeout(BROADCAST_BUDGET_MS) {
                    SampleService.enqueueTailSync(appContext, alreadySampled = false)
                    TstepsWidgetUpdater.updateAllSafely(appContext)
                }
            } catch (e: Exception) {
                // Includes the timeout above. An unhandled throw here would crash
                // the app from a broadcast; the widget keeps what it was showing.
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.callbackdev.tsteps.widget.REFRESH"

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
    }
}
