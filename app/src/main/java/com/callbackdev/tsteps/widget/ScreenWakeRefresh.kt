package com.callbackdev.tsteps.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.recording.RecordingInterop
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The widget brought up to date the moment the home screen becomes reachable.
 *
 * The 15-minute sampler is the floor, not the ceiling, and the hours it misses
 * are exactly the hours nobody is looking: in Doze, WorkManager's maintenance
 * windows stretch to hours, so a phone left alone all night wakes up with a
 * widget showing last night's figures under a red `# stale`. That is the one
 * moment the widget is most wrong and most looked at — the morning pickup — and
 * waiting up to another quarter of an hour for the sampler to notice is the gap
 * this closes.
 *
 * **The signal.** `ACTION_SCREEN_ON` is a protected broadcast that can only be
 * registered at runtime (no manifest receiver will ever see it) and the platform
 * sends it with `FLAG_RECEIVER_FOREGROUND`, so it reaches a cached process
 * promptly instead of queueing behind the background broadcast queue. On its own
 * it is too eager: the screen also comes on for a notification glance, and the
 * home screen is behind the keyguard then. So a screen-on over a locked keyguard
 * does nothing and `ACTION_USER_PRESENT` — the unlock — is what fires the pass;
 * on a phone with no lock at all there is no unlock to wait for, and the
 * screen-on is already the moment the widget is on show. Between them the rule is
 * one sentence: **refresh when the widget becomes visible, never when it cannot
 * be.**
 *
 * **The battery contract of Fase 10 is unchanged.** Nothing here polls, nothing
 * registers a sensor listener, nothing holds a wake lock and nothing wakes a
 * sleeping device — the device is awake by definition, it is the user who woke
 * it. A pass is the same read-and-repaint the sampler already does, minus the
 * sampling: the counter cannot be read outside the foreground (Fase 19), so a
 * wake pass never even asks. What it does do is ask the recorder for the hours
 * that have ended (Fase 24c — Play services is the one source that counts with
 * the app closed) and repaint.
 *
 * Three things keep the cost where it belongs:
 *
 * - **Nobody registers without a widget.** No instance on a home screen, no
 *   permission, no counter — no receiver, so a screen-on never even reaches this
 *   process. [reconcile] is the single owner of that decision, and a pass that
 *   finds the last widget gone takes the receiver down itself.
 * - **A debounce**, because unlocking a phone is something people do dozens of
 *   times a day and most of those glances land on a picture that cannot have
 *   changed: with the app closed the only writer is the hourly import, the
 *   `# stale` marker turns at 45 minutes and the sampler runs at 15, so
 *   [DEBOUNCE_MILLIS] spends nothing on repainting identical pixels.
 * - **Except across an hour**, which is the one boundary the debounce must not
 *   swallow: the wake right after an hour ends is the only wake that can carry a
 *   number the recorder did not have before.
 *
 * The receiver lives as long as the process, which is the honest contract of a
 * runtime registration: when the process is gone there is nothing to refresh
 * *into* either, and the next thing to start it — the sampler, a widget
 * broadcast, the app — arms this again through [com.callbackdev.tsteps.TstepsApplication].
 * Losing it costs the wake refresh and nothing else: the 15-minute pass, the
 * midnight rollover and the ↻ tap are all untouched.
 */
object ScreenWakeRefresh {

    /**
     * Long enough that a burst of pickups is one pass, short enough to stay well
     * inside the sampler's period: five minutes is a third of it, and nothing
     * that can change the widget's picture with the app closed moves faster.
     */
    @VisibleForTesting
    internal const val DEBOUNCE_MILLIS = 5 * 60_000L

    /** The whole pass, capped, as the provider's broadcast is (Fase 18). */
    private const val BROADCAST_BUDGET_MS = 8_000L

    private val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT)
    }

    /** When the last pass ran, on both clocks — see [isDue]. */
    private data class Pass(val elapsedRealtime: Long, val wallMillis: Long)

    private var lastPass: Pass? = null

    /** Guarded by this object's monitor, like every other decision here. */
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isWakeUp(context, intent)) return
            if (!isDue()) return
            // Nullable despite the platform signature: goAsync() only returns a
            // result while a real broadcast is being dispatched.
            val pendingResult: BroadcastReceiver.PendingResult? = goAsync()
            val appContext = context.applicationContext
            inFlight = CoroutineScope(workContext).launch {
                try {
                    // Nothing below is bounded on its own, and a hung DataStore
                    // read would hold the PendingResult open until the system
                    // killed the app for not finishing.
                    withTimeout(BROADCAST_BUDGET_MS) { pass(appContext) }
                } catch (e: Exception) {
                    // Includes the timeout above. An unhandled throw here would
                    // crash the app from a broadcast; the widget keeps its frame.
                } finally {
                    pendingResult?.finish()
                }
            }
        }
    }

    /**
     * Arms or disarms the wake refresh from the facts, and is idempotent: called
     * at every process start and from `SyncScheduler.reconcile`, which is the
     * single owner of "should this app be doing anything at all" and therefore
     * hears about a widget placed or removed, a permission granted or revoked.
     */
    fun reconcile(context: Context) = reconcile(context, canSample(context))

    @Synchronized
    fun reconcile(context: Context, canSample: Boolean) {
        val appContext = context.applicationContext
        // A widget nobody has placed is a broadcast nobody needs: without this,
        // every screen-on would unfreeze this process to paint nothing.
        val wanted = canSample && hasWidgets(appContext)
        if (wanted == registered) return
        try {
            if (wanted) {
                // NOT_EXPORTED is free here and correct: both actions are
                // protected system broadcasts, so the flag only shuts a door no
                // other app could have used anyway.
                ContextCompat.registerReceiver(
                    appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                appContext.unregisterReceiver(receiver)
            }
            registered = wanted
        } catch (e: Exception) {
            // An unregister of something the system does not think is registered
            // throws; either way the flag must end up describing reality.
            registered = false
        }
    }

    /** Fire-and-forget arming for callers on the startup path — see the class doc. */
    fun reconcileDetached(context: Context) {
        val appContext = context.applicationContext
        // runCatching, like every other detached arm in this app: a process start
        // is not a place where anything is allowed to take the app down.
        armScope.launch { runCatching { reconcile(appContext) } }
    }

    /**
     * The screen came on: is the widget actually on show?
     *
     * Over a locked keyguard it is not, and the unlock that follows — if it
     * follows at all — is the event worth spending on. With no lock configured
     * there is no `ACTION_USER_PRESENT` to wait for, and the screen-on *is* the
     * moment.
     */
    private fun isWakeUp(context: Context, intent: Intent): Boolean = when (intent.action) {
        Intent.ACTION_USER_PRESENT -> true
        Intent.ACTION_SCREEN_ON -> !isLocked(context)
        else -> false
    }

    private fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /**
     * The debounce, and the one thing it must never swallow.
     *
     * Elapsed-realtime rather than uptime for the interval, because the clock has
     * to keep running while the phone is in deep sleep — which is the whole
     * interval a wake refresh is about. Wall-clock for the hour, because the hour
     * is the recorder's unit: two wakes either side of an hour boundary are never
     * the same wake, however close together they are.
     */
    @Synchronized
    private fun isDue(): Boolean {
        val now = Pass(elapsedRealtime(), wallClock())
        val last = lastPass
        if (last != null &&
            now.elapsedRealtime - last.elapsedRealtime < DEBOUNCE_MILLIS &&
            sameHour(last.wallMillis, now.wallMillis)
        ) {
            return false
        }
        lastPass = now
        return true
    }

    private fun sameHour(a: Long, b: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return RecordingInterop.alignDownToHour(a, zone) ==
            RecordingInterop.alignDownToHour(b, zone)
    }

    /**
     * One pass: the hours the recorder has closed since last time, then the
     * repaint. No sample — an app that is not in the foreground receives no event
     * from the counter (Fase 19), and asking anyway would only cost the wait.
     *
     * The import is the same call the activity makes when it comes to the front,
     * and it is cheap when there is nothing to do: with no whole hour ended since
     * the last one it answers `UpToDate` after a state read, and where Play
     * services is missing it answers `Unsupported` without building a client.
     */
    private suspend fun pass(context: Context) {
        // The last widget was removed while this process was cached: the receiver
        // outlived its reason, so take it down instead of painting nothing.
        if (!hasWidgets(context)) {
            reconcile(context)
            return
        }
        try {
            ServiceLocator.stepImporter(context).run()
        } catch (e: Exception) {
            // The repaint is worth having either way: a recorder that would not
            // answer is exactly when `# stale` needs to reach the glass.
        }
        TstepsWidgetUpdater.updateAllSafely(context)
    }

    /** The same gate the sampling jobs live behind, asked the same way. */
    private fun canSample(context: Context): Boolean =
        SyncScheduler.hasPermission(context) &&
            ServiceLocator.stepSensorReader(context).isAvailable

    private fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, TstepsWidgetProvider::class.java))
            .isNotEmpty()

    /**
     * Arming reads the permission, the sensor list and the widget ids — three
     * system calls, none of them belonging on the first frame's thread.
     */
    private val armScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where a pass runs. IO, not Default: DataStore, Room and an IPC throughout. */
    @VisibleForTesting
    internal var workContext: CoroutineContext = Dispatchers.IO

    /** The two clocks [isDue] reads, injectable so a test can cross an hour. */
    @VisibleForTesting
    internal var elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }

    @VisibleForTesting
    internal var wallClock: () -> Long = { System.currentTimeMillis() }

    /**
     * The last wake's work, so a test can await it instead of racing it. Written
     * from onReceive (main looper) and never read in production.
     */
    @VisibleForTesting
    internal var inFlight: Job? = null
        private set

    /** Test-only: the object outlives the test, and so would the debounce. */
    @VisibleForTesting
    @Synchronized
    internal fun resetForTests(context: Context) {
        lastPass = null
        if (registered) {
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already gone: nothing to undo.
            }
        }
        registered = false
        inFlight = null
        elapsedRealtime = { SystemClock.elapsedRealtime() }
        wallClock = { System.currentTimeMillis() }
    }
}
