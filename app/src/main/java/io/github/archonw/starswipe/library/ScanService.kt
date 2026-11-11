package io.github.archonw.starswipe.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.archonw.starswipe.StarSwipeApp
import io.github.archonw.starswipe.MainActivity
import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.ui.storages.scanText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Vordergrund-Dienst, solange Ordner eingelesen werden: Android beendet die App dann nicht im Hintergrund
 * und lässt ihr das Netz. Zeigt den Fortschritt als Benachrichtigung und beendet sich selbst, wenn nichts mehr läuft.
 */
class ScanService : Service() {
    companion object {
        private const val CHANNEL = "scan"
        private const val NOTIFICATION_ID = 1
        private const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L

        /** Aus der laufenden App heraus aufrufen (Android erlaubt den Start nur im Vordergrund). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarSwipe:scan").apply { acquire(MAX_WAKE_MS) }
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val c = (application as StarSwipeApp).container
        val progress = scope.launch {
            c.library.scanning.collect { st ->
                val name = st?.let { c.db.locations().get(it.storageId)?.name }
                runCatching { NotificationManagerCompat.from(this@ScanService).notify(NOTIFICATION_ID, notification(st, name)) }
            }
        }
        c.library.active.first { it == 0 }
        // Erst den Fortschritt anhalten, sonst taucht nach dem Ende noch eine verwaiste Meldung auf.
        progress.cancelAndJoin()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun notification(st: ScanStatus?, name: String? = null) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.scan_notification_title))
        .setContentText(st?.let { listOfNotNull(name, scanText(it.found, it.scanned).resolve(resources)).joinToString(" · ") })
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    /** Android 15: Zeitlimit für Datenabgleich erreicht – sauber beenden. */
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
