package com.dogukan.muzik.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaButtonReceiver
import com.dogukan.muzik.MainActivity
import com.dogukan.muzik.R

/** Ana ekran widget'ı: kapak, şarkı adı, önceki / çal-duraklat / sonraki. */
class SubPlayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ciz(context, manager, ids)
    }

    companion object {
        private const val PREF = "widget"

        /** Oynatıcı durumunu kaydedip tüm widget'ları yeniden çizer (servis çağırır). */
        fun guncelle(c: Context, p: Player) {
            val md = p.mediaMetadata
            val art = md.artworkUri
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("baslik", md.title?.toString() ?: "SubPlay")
                .putString("sanatci", md.artist?.toString().orEmpty())
                .putBoolean("caliyor", p.isPlaying)
                .putString("kapak", if (art?.scheme == "file") art.path else null)
                .apply()
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, SubPlayWidget::class.java))
            if (ids.isNotEmpty()) ciz(c, m, ids)
        }

        private fun medyaTusu(c: Context, kod: Int, istek: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(c, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, kod))
            return PendingIntent.getBroadcast(c, istek, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun kapakYukle(yol: String?): Bitmap? = runCatching {
            if (yol == null) return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(yol, o)
            var olcek = 1
            while (o.outWidth / olcek > 300 || o.outHeight / olcek > 300) olcek *= 2
            BitmapFactory.decodeFile(yol, BitmapFactory.Options().apply { inSampleSize = olcek })
        }.getOrNull()

        private fun ciz(c: Context, m: AppWidgetManager, ids: IntArray) {
            val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val v = RemoteViews(c.packageName, R.layout.widget_oynatici)
            v.setTextViewText(R.id.widget_baslik, p.getString("baslik", "SubPlay"))
            v.setTextViewText(R.id.widget_sanatci, p.getString("sanatci", "Çalmak için dokun"))
            val bmp = kapakYukle(p.getString("kapak", null))
            if (bmp != null) v.setImageViewBitmap(R.id.widget_kapak, bmp)
            else v.setImageViewResource(R.id.widget_kapak, R.mipmap.ic_launcher)
            v.setImageViewResource(
                R.id.widget_cal,
                if (p.getBoolean("caliyor", false)) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )
            v.setOnClickPendingIntent(R.id.widget_onceki, medyaTusu(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 11))
            v.setOnClickPendingIntent(R.id.widget_cal, medyaTusu(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 12))
            v.setOnClickPendingIntent(R.id.widget_sonraki, medyaTusu(c, KeyEvent.KEYCODE_MEDIA_NEXT, 13))
            v.setOnClickPendingIntent(
                R.id.widget_kok,
                PendingIntent.getActivity(c, 14, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            ids.forEach { m.updateAppWidget(it, v) }
        }
    }
}
