package com.dogukan.muzik.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dogukan.muzik.MainActivity
import java.util.concurrent.TimeUnit

/** Günde bir kez kütüphanendeki sanatçıların yeni şarkılarına bakar, yeni çıkan varsa bildirir. */
class YeniCikanIsci(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val repo = MusicRepository.get(applicationContext)
        if (repo.tracks.value.isEmpty()) return Result.success()
        val yeni = runCatching { repo.yeniCikanlariBul() }.getOrElse { return Result.retry() }
        if (yeni.isNotEmpty()) bildir(applicationContext, yeni)
        return Result.success()
    }

    companion object {
        fun zamanla(c: Context) {
            val req = PeriodicWorkRequestBuilder<YeniCikanIsci>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("yeni_cikanlar", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun bildir(c: Context, yeni: List<MusicRepository.YeniCikan>) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("yeni_cikan", "Yeni çıkan şarkılar", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra("sekme", "listeler"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val ilk = yeni.first()
            val n = NotificationCompat.Builder(c, "yeni_cikan")
                .setSmallIcon(com.dogukan.muzik.R.drawable.ic_bildirim_kalp)
                .setContentTitle(if (yeni.size == 1) "${ilk.sanatci}: yeni şarkı" else "${yeni.size} yeni şarkı çıktı")
                .setContentText(if (yeni.size == 1) ilk.ad else yeni.map { it.sanatci }.distinct().take(4).joinToString(", "))
                .setContentIntent(pi).setAutoCancel(true).build()
            try { nm.notify(8, n) } catch (_: SecurityException) { }
        }
    }
}
