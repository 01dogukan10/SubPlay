package com.dogukan.muzik.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dogukan.muzik.MainActivity

/**
 * Uygulama içi kurulumun sonucu. Telefon onay isterse onay ekranını açar; kurulum başarısız olursa
 * eski yöntemle (sistemin kurulum ekranı) dener.
 */
class KurulumAlici : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val onay: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                onay?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { c.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // uygulama yeniden başlar; "güncellendi" bildirimi GuncellendiAlici'dan
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // kullanıcı vazgeçti
            else -> {
                // Bu yolla olmadıysa sistemin kurulum ekranıyla dene
                Guncelle.sonApk(c)?.let { runCatching { Guncelle.kurEski(c, it) } }
            }
        }
    }
}

/** Güncelleme bitince "SubPlay güncellendi" bildirimi: dokununca uygulama açılır. */
class GuncellendiAlici : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("guncelle", "Güncellemeler", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(c, 5, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "guncelle")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SubPlay güncellendi")
            .setContentText("Açmak için dokun")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(6, n) } catch (_: SecurityException) { }
    }
}
