package com.callbackdev.tsteps.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.distanceMeters
import com.callbackdev.tsteps.data.toItem
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.Streaks
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Re-renders every widget instance from persisted state. Called by the sync
 * workers after each ingest/commit, by the tracking service's minute tick (a
 * walk with the screen off is exactly when the widget matters), from
 * MainActivity's settings collector (theme/units/goal/profile/opacity changes)
 * and again when the app leaves the foreground (the live listener has been
 * ingesting the whole session — the widget is the last thing to hear about it),
 * on provider onUpdate, and around the ↻ tap.
 *
 * **Single-flight.** Every one of those callers is fire-and-forget, and a repaint
 * is gather-then-paint: read the stores, then hand one RemoteViews to the host.
 * Run two of those concurrently and the launcher shows whichever *finished* last,
 * which is not the one that *started* last — a slow pass could repaint the number
 * from before the ↻ tap right over the number the tap had just delivered. So the
 * passes are serialized ([renderMutex]) and coalesced ([queued]): while one pass
 * runs at most one more is queued, and every request that arrives meanwhile is
 * already covered by it, because that queued pass has yet to read a thing.
 *
 * Unlike its tweather parent there is one data source — the day — so a single
 * render feeds every instance.
 */
object TstepsWidgetUpdater {

    /**
     * Outlives any single caller: MainActivity's `onStop` is routinely followed by
     * `onDestroy` (swipe away), which would cancel `lifecycleScope` mid-render.
     * IO, not Default: a pass is DataStore and Room from end to end, and Default
     * is sized for CPU work.
     */
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One gather+paint at a time, so no pass can repaint over a newer one. */
    private val renderMutex = Mutex()

    /** True while a pass is waiting its turn — the one that covers all comers. */
    private val queued = AtomicBoolean(false)

    /**
     * How many ↻ taps are waiting for their sample. Sticky, and deliberately not a
     * per-call flag: the tap's `…` used to be a parameter of one repaint, so any
     * other repaint landing in between (the minute tick, a worker) cleared it
     * early. Whoever paints, paints the glyph this says.
     */
    private val tapsInFlight = AtomicInteger(0)

    /**
     * The last frame handed to the launcher. Kept so the ↻ acknowledgment can be
     * painted without touching the disk — see [ackTap].
     */
    @Volatile
    private var lastFrame: Frame? = null

    /** Everything a render pass needs after the gather; the ack replays one. */
    private data class Frame(
        val data: WidgetData,
        val units: UnitsSystem,
        val palette: WidgetPalette,
        val opacityPct: Int
    )

    /**
     * Repaint that never takes its caller down with it. Every background caller
     * wants this one: a widget holding its last frame is a nuisance, a worker
     * that dies because the repaint threw is a stopped schedule.
     *
     * Returns once the widget reflects state at least as new as the moment of the
     * call — either because this call painted it, or because a pass that had not
     * yet read anything was already queued to.
     */
    suspend fun updateAllSafely(context: Context) {
        val appContext = context.applicationContext
        // Coalesce: a pass already waiting for the mutex has read nothing yet, so
        // it will see everything this caller wants painted. Two queued passes
        // would paint the same state twice.
        if (!queued.compareAndSet(false, true)) return
        var slotHeld = true
        try {
            renderMutex.withLock {
                // Released before the gather, not after: from here on this pass is
                // the running one, and the next caller deserves a slot of its own.
                queued.set(false)
                slotHeld = false
                updateAll(appContext)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // The widget simply keeps whatever it was showing.
        } finally {
            // Cancelled while waiting for the lock: a slot left claimed by a pass
            // that never ran would silently swallow every repaint after it.
            if (slotHeld) queued.set(false)
        }
    }

    /** Fire-and-forget repaint for callers whose own scope dies with them. */
    fun updateAllDetached(context: Context) {
        val appContext = context.applicationContext
        detachedScope.launch { updateAllSafely(appContext) }
    }

    /**
     * The ↻ tap says "heard you", and says it before anything slow happens.
     *
     * The numbers cannot move until the sample lands, so a plain repaint here
     * would be pixel-identical and the tap would read as dead. The glyph reaches
     * every tier, which `# last_sync` cannot (it is last in the transcript, so
     * the common sizes cut it).
     *
     * It repaints the frame already on screen with only the glyph changed: no
     * DataStore, no Room, no disk at all. A full gather for an acknowledgment is
     * exactly the wrong thing on the one path where latency is the feature — on a
     * cold process that gather is a Room open and two DataStore file reads before
     * a single pixel moves. With no frame yet (cold process, first ever tap) this
     * does nothing and the pass at the end of the tap is the first paint.
     *
     * Not `partiallyUpdateAppWidget`, which looks like the natural fit: a partial
     * update is only merged if its layout id matches the one the host currently
     * has inflated, and with a sizes map that is whichever tier the launcher
     * picked — guess wrong and the host re-inflates from the partial views, i.e.
     * an empty widget. Replaying a whole frame costs a string build and is right
     * on every tier.
     */
    fun ackTap(context: Context) {
        tapsInFlight.incrementAndGet()
        val frame = lastFrame ?: return
        // A pass in flight already paints the sticky glyph, so skipping is correct
        // — and it keeps the acknowledgment off any lock it could wait on.
        if (!renderMutex.tryLock()) return
        try {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            paint(appContext, manager, widgetIds(manager, appContext), frame)
        } catch (e: Exception) {
            // Cosmetic: the tap's own repaint is still coming.
        } finally {
            renderMutex.unlock()
        }
    }

    /**
     * The tap is over — the glyph goes back to ↻ on the next paint. Called before
     * that repaint is requested, never after: a pass that starts afterwards is
     * then guaranteed to see the settled state, including one that swallowed this
     * caller's request by being queued already.
     */
    fun endTap() {
        tapsInFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** What the glyph must say right now, for whoever is painting. */
    internal fun isSyncing(): Boolean = tapsInFlight.get() > 0

    /**
     * One gather, one paint. Not for outside callers: it is neither serialized
     * nor safe, [updateAllSafely] is both.
     */
    internal suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = widgetIds(manager, context)
        if (ids.isEmpty()) return

        val settings = ServiceLocator.settingsStore(context).read()
        val repository = ServiceLocator.stepRepository(context)
        val anchor = ServiceLocator.trackerStateStore(context).read()
        val sensorOk = SyncScheduler.hasPermission(context) &&
            ServiceLocator.stepSensorReader(context).isAvailable

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val hourly = repository.hoursOfDay(today)
        val steps = hourly.sumOf { it.steps }
        val activeMinutes = Estimates.activeMinutes(hourly.map { it.steps })
        val lastWalk = repository.latestSessionOfDay(today)?.toItem()

        val data = WidgetData(
            hasEverSampled = anchor != null,
            sensorOk = sensorOk,
            todaySteps = steps,
            goalSteps = settings.dailyGoalSteps,
            distanceMeters = settings.distanceMeters(steps),
            activeMinutes = activeMinutes,
            activeKcal = Estimates.activeKcal(settings.weightKg, activeMinutes),
            streakDays = Streaks.current(repository.goalDays(), today),
            lastWalkStartMillis = lastWalk?.startMillis,
            lastWalkActiveMinutes = lastWalk?.activeMinutes,
            lastWalkApprox = lastWalk?.startApprox == true,
            // The instant the counter was READ, not the instant the anchored steps
            // were walked: on a still device the sensor hands back an event hours
            // old, and reading its timestamp as freshness marked a perfectly
            // healthy widget `# stale` — which no ↻ tap could then clear.
            lastSyncMillis = anchor?.lastReadMillis
        )
        paint(
            context,
            manager,
            ids,
            Frame(
                data = data,
                units = settings.units,
                palette = widgetPalette(settings.themeProfileName),
                opacityPct = settings.widgetOpacityPct
            )
        )
    }

    /** The half that talks to the launcher, shared by a full pass and the ack. */
    private fun paint(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        frame: Frame
    ) {
        if (ids.isEmpty()) return
        // Stamped once for the whole map: the lambda below runs per tier, and six
        // tiers asking the clock (and the zone table) six times over is six
        // answers that must agree for the frame to be coherent.
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val notes = widgetNotes(context.resources)
        manager.updateAppWidget(
            ids,
            WidgetRenderer.sizeMap(
                context,
                content = { tier ->
                    WidgetContentBuilder.build(
                        data = frame.data,
                        units = frame.units,
                        tier = tier,
                        zone = zone,
                        locale = Locale.getDefault(),
                        now = now,
                        notes = notes
                    )
                },
                palette = frame.palette,
                opacityPct = frame.opacityPct,
                syncing = isSyncing()
            )
        )
        lastFrame = frame
    }

    private fun widgetIds(manager: AppWidgetManager, context: Context): IntArray =
        manager.getAppWidgetIds(ComponentName(context, TstepsWidgetProvider::class.java))

    /**
     * A repaint that outlives its caller's cancellation. The ↻ tap's last act:
     * whatever went wrong upstream — a timeout, a throwing sample — the `…` must
     * come off, and a widget left wearing it is the one failure a user cannot
     * clear from the home screen.
     */
    suspend fun updateAllUninterruptibly(context: Context) {
        withContext(NonCancellable) { updateAllSafely(context) }
    }

    /** Test-only: the object outlives the test, and so would a stuck `…`. */
    internal fun resetForTests() {
        tapsInFlight.set(0)
        queued.set(false)
        lastFrame = null
    }
}
