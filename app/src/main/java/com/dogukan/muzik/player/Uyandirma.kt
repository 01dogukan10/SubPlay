package com.dogukan.muzik.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.session.MediaButtonReceiver
import com.dogukan.muzik.MainActivity
import java.util.Calendar

/** Müzikle uyanma: seçilen saatte seçilen liste karışık çalar, ses yavaşça açılır. */
object Uyandirma {
    private fun p(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    data class Ayar(val acik: Boolean, val saat: Int, val dakika: Int, val liste: String?, val herGun: Boolean)

    fun oku(c: Context) = p(c).let {
        Ayar(it.getBoolean("uyan_acik", false), it.getInt("uyan_saat", 7), it.getInt("uyan_dk", 30),
            it.getString("uyan_liste", null), it.getBoolean("uyan_hergun", true))
    }

    fun kaydet(c: Context, a: Ayar) {
        p(c).edit().putBoolean("uyan_acik", a.acik).putInt("uyan_saat", a.saat).putInt("uyan_dk", a.dakika)
            .putString("uyan_liste", a.liste).putBoolean("uyan_hergun", a.herGun).apply()
        zamanla(c)
    }

    /** Bir sonraki çalma zamanı (ms). */
    fun sonraki(a: Ayar): Long {
        val k = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, a.saat); set(Calendar.MINUTE, a.dakika); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (k.timeInMillis <= System.currentTimeMillis()) k.add(Calendar.DAY_OF_YEAR, 1)
        return k.timeInMillis
    }

    private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(
        c, 31, Intent(c, UyandirmaAlici::class.java).setAction("com.subplay.muzik.UYAN"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun zamanla(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmIntent(c))
        val a = oku(c)
        if (!a.acik) return
        val zaman = sonraki(a)
        val goster = PendingIntent.getActivity(c, 32, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(zaman, goster), alarmIntent(c))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zaman, alarmIntent(c))
        }
    }

    /** Oynatıcı boşken alarm geldiyse çalınacak liste ("*" = tüm kütüphane). Bir kez okunur. */
    fun bekleyenListe(c: Context): String? {
        val v = p(c).getString("uyan_bekleyen", null) ?: return null
        p(c).edit().remove("uyan_bekleyen").apply()
        return v
    }

    internal fun bekleyenYap(c: Context, liste: String?) = p(c).edit().putString("uyan_bekleyen", liste ?: "*").commit()
}

class UyandirmaAlici : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            Uyandirma.zamanla(c); return
        }
        if (i.action != "com.subplay.muzik.UYAN") return
        val a = Uyandirma.oku(c)
        if (!a.acik) return
        // Bir sonraki günü kur (her gün değilse kapat)
        if (a.herGun) Uyandirma.zamanla(c) else c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE).edit().putBoolean("uyan_acik", false).apply()
        val calisan = PlaybackService.ornek
        if (calisan != null) {
            calisan.uyandir(a.liste)
        } else {
            Uyandirma.bekleyenYap(c, a.liste)
            val zaman = SystemClock.uptimeMillis()
            val tus = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(c.packageName)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(zaman, zaman, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            runCatching { MediaButtonReceiver().onReceive(c, tus) }
        }
    }
}
