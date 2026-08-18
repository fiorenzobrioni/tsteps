package com.callbackdev.tsteps.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.callbackdev.tsteps.work.StepSyncWorker
import com.callbackdev.tsteps.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The `tsteps --today` home widget. Deliberately passive on battery (tweather's
 * contract): no `updatePeriodMillis`, no polling of its own — it renders
 * persisted state and is repainted by whoever moves it (the sync workers, the
 * tracking service's minute tick, the app's settings collector). The ↻ glyph is
 * the only user-initiated sample. Simpler than its tweather parent on purpose:
 * there are no cities, so there is nothing to pin and no configuration activity.
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
            enqueueManualSync(context)
            needsRender = true // paint the freshly-tapped state; the sample lands later
        }
        val render = needsRender
        val reconcile = needsReconcile
        if (!render && !reconcile) return

        // Nullable despite the platform signature: goAsync() only returns a result
        // while a real broadcast is being dispatched. The work still has to run.
        val pendingResult: BroadcastReceiver.PendingResult? = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (reconcile) SyncScheduler.reconcile(context)
                if (render) TstepsWidgetUpdater.updateAll(context)
            } catch (e: Exception) {
                // An unhandled throw here would crash the app from a broadcast; the
                // widget simply keeps whatever it was showing.
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.callbackdev.tsteps.widget.REFRESH"

        /** Distinct from the periodic job so a tap never disturbs its cycle. */
        const val MANUAL_SYNC_NAME = "step-sync-manual"

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, TstepsWidgetProvider::class.java))
                .isNotEmpty()

        /**
         * ↻ tap: one counter sample, right now. KEEP swallows tap-spam — a second
         * tap while one sample is pending piggybacks on it. Expedited is safe
         * without `getForegroundInfo` on minSdk 33 (the foreground-service
         * fallback is a pre-S requirement).
         */
        fun enqueueManualSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<StepSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_SYNC_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
