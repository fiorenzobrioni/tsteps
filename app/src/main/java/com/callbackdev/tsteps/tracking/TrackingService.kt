package com.callbackdev.tsteps.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.callbackdev.tsteps.MainActivity
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.TrackingState
import com.callbackdev.tsteps.notifications.GoalWatcher
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * The running process behind `$ tsteps track` — the app's ONLY service, alive
 * exactly as long as a session is being tracked (the sanctioned exception to the
 * no-services battery rule, VISION §4.5). It keeps the sensor listener registered
 * with the screen off, feeds the [com.callbackdev.tsteps.data.TrackingManager],
 * ingests the same readings into the daily pipeline (one stream, two views — no
 * double counting by construction), and wears the transcript's command line as
 * its notification. `^C` stops it and the session commits as a hunk.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorsStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = ServiceLocator.trackingManager(this)
        when (intent?.action) {
            ACTION_START -> {
                val type = intent.getStringExtra(EXTRA_TYPE) ?: "walk"
                scope.launch {
                    manager.start(type, System.currentTimeMillis())
                    startCollectors()
                }
                startForegroundWith(manager.state.value)
            }
            ACTION_PAUSE -> {
                manager.pause(System.currentTimeMillis())
                updateNotification(manager.state.value)
            }
            ACTION_RESUME -> {
                manager.resume(System.currentTimeMillis())
                updateNotification(manager.state.value)
            }
            ACTION_CYCLE_TYPE -> {
                manager.cycleType()
                updateNotification(manager.state.value)
            }
            ACTION_STOP -> {
                scope.launch {
                    manager.stop(System.currentTimeMillis())
                    ServiceCompat.stopForeground(
                        this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startCollectors() {
        if (collectorsStarted) return
        collectorsStarted = true
        val manager = ServiceLocator.trackingManager(this)
        val reader = ServiceLocator.stepSensorReader(this)
        val repository = ServiceLocator.stepRepository(this)
        // Sensor stream: every reading updates the live session; the same stream
        // is ingested (spaced) into the daily pipeline so today's totals stay
        // fresh while tracking.
        scope.launch {
            reader.readings().conflate().collect { reading ->
                manager.onReading(reading)
                repository.ingest(reading)
                updateNotification(manager.state.value)
                delay(INGEST_SPACING_MS)
            }
        }
        // Minute marks for the transcript + a notification refresh even when idle.
        // The goal watcher rides the tick: a walk with the screen off is exactly
        // where today's check tends to go green.
        scope.launch {
            while (true) {
                delay(60_000L)
                manager.onMinuteTick(System.currentTimeMillis())
                updateNotification(manager.state.value)
                GoalWatcher.evaluate(this@TrackingService)
            }
        }
    }

    private fun startForegroundWith(state: TrackingState?) {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )
    }

    private fun updateNotification(state: TrackingState?) {
        if (state == null) return
        // Runtime-gated (Fase 9 requests it): without the grant the transcript
        // simply isn't mirrored to the shade — the service runs regardless.
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    /** Title = the command line; body = the live transcript line. Code stays English. */
    private fun buildNotification(state: TrackingState?): Notification {
        val session = state?.session
        val title = "$ tsteps track ${session?.type ?: "walk"}"
        val now = System.currentTimeMillis()
        val text = if (session == null) {
            "starting…"
        } else {
            val elapsed = formatElapsed(session.activeMillis(now))
            val km = "%.1f".format(Locale.ROOT, state.distanceMeters / 1_000.0)
            val stateSuffix = if (session.paused) " · ^Z paused" else ""
            "$elapsed · ${session.steps} steps · $km km$stateSuffix"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tsteps)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_tracking),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val INGEST_SPACING_MS = 2_000L

        private const val ACTION_START = "com.callbackdev.tsteps.tracking.START"
        private const val ACTION_PAUSE = "com.callbackdev.tsteps.tracking.PAUSE"
        private const val ACTION_RESUME = "com.callbackdev.tsteps.tracking.RESUME"
        private const val ACTION_STOP = "com.callbackdev.tsteps.tracking.STOP"
        private const val ACTION_CYCLE_TYPE = "com.callbackdev.tsteps.tracking.CYCLE_TYPE"
        private const val EXTRA_TYPE = "type"

        fun formatElapsed(activeMillis: Long): String {
            val totalSeconds = activeMillis / 1_000
            return "%d:%02d".format(Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
        }

        fun start(context: Context, type: String) {
            context.startForegroundService(
                intent(context, ACTION_START).putExtra(EXTRA_TYPE, type)
            )
        }

        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        fun cycleType(context: Context) = context.startService(intent(context, ACTION_CYCLE_TYPE))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        private fun intent(context: Context, action: String) =
            Intent(context, TrackingService::class.java).setAction(action)
    }
}
