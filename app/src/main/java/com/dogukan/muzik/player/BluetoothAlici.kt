package com.dogukan.muzik.player

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.session.MediaButtonReceiver

/**
 * Bluetooth kulaklık / hoparlör / araba bağlanınca (ayar açıksa) kaldığın yerden çalmaya başlar.
 * Çalma, uygulamanın medya tuşu alıcısına "çal" tuşu verilerek başlatılır; oynatıcı boşsa
 * son çalınan liste, şarkı ve saniye geri yüklenir.
 */
class BluetoothAlici : BroadcastReceiver() {
    companion object {
        const val AYAR = "bt_otomatik"
        fun acik(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE).getBoolean(AYAR, false)
        fun ayarla(c: Context, on: Boolean) =
            c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE).edit().putBoolean(AYAR, on).apply()
        fun izinGerekli() = Build.VERSION.SDK_INT >= 31
        fun izinVar(c: Context) = !izinGerekli() ||
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED || !acik(c) || !izinVar(c)) return
        val cihaz: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        // Sadece ses cihazları (kulaklık, hoparlör, araba); saat, klavye vb. değil
        val sinif = runCatching { cihaz?.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (sinif != null && sinif != BluetoothClass.Device.Major.AUDIO_VIDEO && sinif != BluetoothClass.Device.Major.UNCATEGORIZED) return
        val am = c.getSystemService(AudioManager::class.java)
        if (am?.isMusicActive == true) return // zaten bir şey çalıyor
        val bekle = goAsync()
        // Ses bağlantısının (A2DP) kurulmasını kısa bir süre bekle; yoksa ilk saniyeler telefondan çıkabilir
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (am?.isMusicActive != true) {
                    val zaman = SystemClock.uptimeMillis()
                    val tus = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(c.packageName)
                        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(zaman, zaman, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
                    runCatching { MediaButtonReceiver().onReceive(c, tus) }
                }
            } finally {
                bekle.finish()
            }
        }, 3500)
    }
}
