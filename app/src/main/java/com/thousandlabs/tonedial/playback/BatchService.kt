package com.thousandlabs.tonedial.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.thousandlabs.tonedial.MainActivity
import com.thousandlabs.tonedial.R
import com.thousandlabs.tonedial.data.Covers
import com.thousandlabs.tonedial.data.LyricsBatch
import com.thousandlabs.tonedial.data.OpeningsBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Garde Tone Dial actif pendant les recherches groupées (pochettes, images d'artistes, openings, paroles, romaji)
 * quand on quitte l'appli : Android, et surtout les téléphones Tecno, arrêtent sinon
 * le travail en arrière-plan. Une notification montre la progression, avec « Arrêter ».
 */
class BatchService : Service() {

    companion object {
        private const val CHANNEL = "batch"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "com.thousandlabs.tonedial.BATCH_STOP"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, BatchService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: kotlinx.coroutines.Job? = null
    /** Garde le processeur éveillé écran éteint, seulement pendant une recherche (libéré à la fin, 3 h au plus). */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recherches en arrière-plan", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progression de la recherche des pochettes, des paroles et des openings"
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OpeningsBatch.stop()
            LyricsBatch.stop()
            Covers.stopBatch()
            finish()
            return START_NOT_STICKY
        }
        val first = build("Recherche en cours…", 0, 0)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, first, type) }.onFailure { stopSelf(); return START_NOT_STICKY }

        if (wakeLock?.isHeld != true) runCatching {
            wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ToneDial:recherches")
                .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        }

        watcher?.cancel()
        watcher = scope.launch {
            combine(OpeningsBatch.progress, LyricsBatch.progress, Covers.progress) { o, l, c -> Triple(o, l, c) }.collect { (o, l, c) ->
                when {
                    o.running -> notify("Identification des openings : ${o.done} / ${o.total} · ${o.found} trouvé(s)", o.done, o.total)
                    l.running -> notify("Recherche des paroles : ${l.done} / ${l.total} · ${l.found} trouvée(s)", l.done, l.total)
                    c.running && c.artists -> notify("Images des artistes : ${c.done} / ${c.total}", c.done, c.total)
                    c.running -> notify("Recherche des pochettes : ${c.done} / ${c.total} · ${c.found} trouvée(s)", c.done, c.total)
                    else -> finish()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(text: String, done: Int, total: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text, done, total))
    }

    private fun build(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tone Dial")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    private fun releaseWake() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun finish() {
        releaseWake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }
}
