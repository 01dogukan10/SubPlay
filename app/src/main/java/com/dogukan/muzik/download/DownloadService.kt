package com.dogukan.muzik.download

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
import com.dogukan.muzik.MainActivity
import com.dogukan.muzik.data.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * İndirme sürerken uygulamayı ayakta tutan ön plan servisi.
 * Böylece uygulamadan çıksan da Android indirmeleri yarıda kesmez.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "indirme"
        private const val NOTIF_ID = 3

        fun start(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(0, 0f),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job == null) {
            val repo = MusicRepository.get(this)
            job = scope.launch {
                repo.downloads.collect { list ->
                    val active = list.filter { it.error == null }
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val known = active.mapNotNull { it.progress }
                        val avg = if (known.isEmpty()) 0f else known.average().toFloat()
                        runCatching { nm.notify(NOTIF_ID, build(active.size, avg)) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(count: Int, progress: Float) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (count > 0) "$count şarkı indiriliyor" else "İndirme hazırlanıyor")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
