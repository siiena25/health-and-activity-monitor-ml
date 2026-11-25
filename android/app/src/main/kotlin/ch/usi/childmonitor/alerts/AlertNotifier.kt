package ch.usi.childmonitor.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ch.usi.childmonitor.MainActivity
import ch.usi.childmonitor.R
import ch.usi.childmonitor.domain.ActiveAlert
import ch.usi.childmonitor.domain.AlertText
import ch.usi.childmonitor.domain.AlertType

interface AlertNotifier {
    fun show(alert: ActiveAlert, nowMillis: Long, demo: Boolean)
    fun cancel(type: AlertType)
}

class AndroidAlertNotifier(context: Context) : AlertNotifier {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        val channel = NotificationChannel(CHANNEL_ID, "Heart rate warnings", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Warnings about unusual heart rate readings from the wearable"
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    override fun show(alert: ActiveAlert, nowMillis: Long, demo: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = AlertText.title(alert.type)
        val text = "${AlertText.message(alert, nowMillis)}. ${AlertText.ADVICE}"
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(if (demo) "DEMO · $title" else title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(alert.type.ordinal, notification)
        } catch (_: SecurityException) {
        }
    }

    override fun cancel(type: AlertType) {
        manager.cancel(type.ordinal)
    }

    private companion object {
        const val CHANNEL_ID = "heart_rate_alerts"
    }
}
