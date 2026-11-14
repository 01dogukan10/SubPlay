package com.dogukan.muzik.ui

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private class EqBilgi(val frekanslar: List<Int>, val min: Int, val max: Int, val hazirlar: List<Pair<String, List<Int>>>)

private val PRESET_TR = mapOf(
    "Normal" to "Normal", "Classical" to "Klasik", "Dance" to "Dans", "Flat" to "Düz", "Folk" to "Folk",
    "Heavy Metal" to "Heavy Metal", "Hip Hop" to "Hip Hop", "Jazz" to "Caz", "Pop" to "Pop", "Rock" to "Rock",
)

/** Telefonun ekolayzırını geçici bir ses oturumunda sorgular (bant sayısı, aralık, hazır ayarlar). */
private fun eqBilgi(ctx: Context): EqBilgi? = runCatching {
    val sid = ctx.getSystemService(AudioManager::class.java).generateAudioSessionId()
    val e = Equalizer(0, sid)
    try {
        val n = e.numberOfBands.toInt()
        val bands = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
        val r = e.bandLevelRange
        val presets = (0 until e.numberOfPresets.toInt()).map { i ->
            e.usePreset(i.toShort())
            val ad = e.getPresetName(i.toShort())
            (PRESET_TR[ad] ?: ad) to (0 until n).map { e.getBandLevel(it.toShort()).toInt() }
        }
        EqBilgi(bands, r[0].toInt(), r[1].toInt(), presets)
    } finally { e.release() }
}.getOrNull()

private fun frekansYazi(hz: Int) = if (hz >= 1000) "${"%.1f".format(hz / 1000f).removeSuffix(".0")} kHz" else "$hz Hz"

@Composable
fun EqualizerScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val prefs = remember { ctx.getSharedPreferences("oynatici", Context.MODE_PRIVATE) }
    val bilgi = remember { eqBilgi(ctx) }
    var acik by remember { mutableStateOf(prefs.getBoolean("eq_acik", false)) }
    var seviyeler by remember {
        mutableStateOf(
            prefs.getString("eq_seviyeler", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                .let { l -> List(bilgi?.frekanslar?.size ?: 0) { l.getOrElse(it) { 0 } } }
        )
    }
    var bass by remember { mutableIntStateOf(prefs.getInt("bass", 0)) }
    var secili by remember { mutableStateOf<String?>(null) }

    fun kaydet() {
        prefs.edit().putBoolean("eq_acik", acik).putString("eq_seviyeler", seviyeler.joinToString(","))
            .putInt("bass", bass).apply()
    }

    Box(
        Modifier.fillMaxSize().background(c.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides c.onBackground,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                    Text("Ekolayzır", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Switch(checked = acik, onCheckedChange = { acik = it; kaydet() }, modifier = Modifier.padding(end = 12.dp))
                }
                if (bilgi == null) {
                    Text("Bu telefon ekolayzırı desteklemiyor.", color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    return@Column
                }
                Text("Hazır ayarlar", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (listOf("Düz" to List(bilgi.frekanslar.size) { 0 }) + bilgi.hazirlar.filter { it.first != "Düz" }).forEach { (ad, lv) ->
                        FilterChip(
                            selected = secili == ad,
                            onClick = { secili = ad; seviyeler = lv; acik = true; kaydet() },
                            label = { Text(ad) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                bilgi.frekanslar.forEachIndexed { i, hz ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(frekansYazi(hz), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                        Slider(
                            value = seviyeler.getOrElse(i) { 0 }.toFloat(),
                            onValueChange = { v -> seviyeler = seviyeler.toMutableList().also { it[i] = v.toInt() }; secili = null },
                            onValueChangeFinished = { acik = true; kaydet() },
                            valueRange = bilgi.min.toFloat()..bilgi.max.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        Text("%+d dB".format(seviyeler.getOrElse(i) { 0 } / 100), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(56.dp).padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Bas güçlendirme", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
                Slider(
                    value = bass.toFloat(), onValueChange = { bass = it.toInt() },
                    onValueChangeFinished = { if (bass > 0) acik = true; kaydet() },
                    valueRange = 0f..1000f,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "Ayarlar çalan şarkıya hemen uygulanır. Bazı telefonlarda bas güçlendirme sadece kulaklıkta duyulur.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}
