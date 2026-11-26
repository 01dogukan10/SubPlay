package com.dogukan.muzik.player

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.json.JSONObject

/**
 * Cihaza göre ekolayzır: her ses çıkışı (Bluetooth kulaklık / hoparlör / araba, kablolu kulaklık,
 * telefon hoparlörü) için ekolayzır ayarı ayrı saklanır; cihaz değişince onun ayarı gelir.
 */
object EqProfil {
    /** (anahtar, görünen ad) */
    fun simdikiCihaz(c: Context): Pair<String, String> {
        val am = c.getSystemService(AudioManager::class.java) ?: return "hoparlor" to "Telefon hoparlörü"
        val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun bul(vararg t: Int) = d.firstOrNull { it.type in t }
        bul(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */)?.let {
            val ad = it.productName?.toString().orEmpty().ifBlank { "Bluetooth cihazı" }
            return "bt:$ad" to ad
        }
        bul(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)?.let { return "usb" to "USB kulaklık" }
        bul(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)?.let { return "kablo" to "Kablolu kulaklık" }
        return "hoparlor" to "Telefon hoparlörü"
    }

    /** Şimdiki ekolayzır ayarını şimdiki cihazın profili olarak sakla. */
    fun kaydet(p: SharedPreferences) {
        val k = p.getString("eq_cihaz", null) ?: return
        val o = JSONObject().put("acik", p.getBoolean("eq_acik", false))
            .put("seviyeler", p.getString("eq_seviyeler", "")).put("bass", p.getInt("bass", 0))
        p.edit().putString("eq_profil_$k", o.toString()).apply()
    }

    /** Ses çıkışı değişti: yeni cihazın ayarı varsa onu yükle, yoksa şimdiki ayarla başlat. */
    fun cihazaGec(p: SharedPreferences, anahtar: String, ad: String) {
        if (p.getString("eq_cihaz", null) == anahtar) return
        val profil = p.getString("eq_profil_$anahtar", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val e = p.edit().putString("eq_cihaz", anahtar).putString("eq_cihaz_ad", ad)
        if (profil != null) {
            e.putBoolean("eq_acik", profil.optBoolean("acik")).putString("eq_seviyeler", profil.optString("seviyeler"))
                .putInt("bass", profil.optInt("bass"))
        }
        e.apply()
        if (profil == null) kaydet(p)
    }
}
