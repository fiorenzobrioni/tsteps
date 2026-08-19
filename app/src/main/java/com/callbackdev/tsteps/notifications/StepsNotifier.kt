package com.callbackdev.tsteps.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.callbackdev.tsteps.MainActivity
import com.callbackdev.tsteps.R

/**
 * Posts the two notifications. Both are runtime-gated on POST_NOTIFICATIONS (the
 * grant is the real opt-in) and land on their own channels so the system settings
 * can silence one without the other:
 *
 * - `daily_commit`: IMPORTANCE_LOW and silent — it often posts at midnight, and a
 *   summary that buzzes people awake is exactly the noise the VISION forbids.
 * - `goal_check`: IMPORTANCE_DEFAULT — a once-a-day event worth a ping.
 */
object StepsNotifier {

    private const val CHANNEL_DAILY = "daily_commit"
    private const val CHANNEL_GOAL = "goal_check"
    private const val ID_DAILY = 2
    private const val ID_GOAL = 3

    fun canPost(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun postDailyCommit(context: Context, content: StepsNotifications.Content) {
        if (!canPost(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY,
                context.getString(R.string.notif_channel_daily),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.notify(ID_DAILY, build(context, CHANNEL_DAILY, content, silent = true))
    }

    fun postGoalReached(context: Context, content: StepsNotifications.Content) {
        if (!canPost(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GOAL,
                context.getString(R.string.notif_channel_goal),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.notify(ID_GOAL, build(context, CHANNEL_GOAL, content, silent = false))
    }

    private fun build(
        context: Context,
        channel: String,
        content: StepsNotifications.Content,
        silent: Boolean
    ) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_tsteps)
        .setContentTitle(content.title)
        // Collapsed = the one-line summary; expanded = the same facts one per
        // line (device feedback: give the message room when the shade does).
        .setContentText(content.summary)
        .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setAutoCancel(true)
        .setSilent(silent)
        .build()
}
