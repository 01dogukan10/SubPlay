package com.dogukan.muzik.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dogukan.muzik.data.Istatistik
import com.dogukan.muzik.data.MusicRepository
import com.dogukan.muzik.data.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

private fun sure(ms: Long): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

// ======================= Zil sesi yapma =======================

/** Şarkının bir bölümünü seçip zil / bildirim / alarm sesi yapar. */
@Composable
fun ZilSesiDiyalogu(t: Track, repo: MusicRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dosya = remember(t) { repo.fileOf(t).takeIf { t.indirildi && it.exists() } }
    val toplam = remember(dosya) {
        dosya?.let { f ->
            runCatching {
                val m = android.media.MediaMetadataRetriever(); m.setDataSource(f.absolutePath)
                val d = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                m.release(); d
            }.getOrDefault(0L)
        } ?: 0L
    }
    var aralik by remember(toplam) { mutableStateOf(0f..(minOf(30_000L, toplam).toFloat())) }
    var tur by remember { mutableStateOf("zil") }
    var calisiyor by remember { mutableStateOf(false) }
    var kaydediliyor by remember { mutableStateOf(false) }
    var sonuc by remember { mutableStateOf<android.net.Uri?>(null) }
    val oynatici = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { oynatici.value?.release() } } }
    fun durdur() { runCatching { oynatici.value?.release() }; oynatici.value = null; calisiyor = false }

    AlertDialog(
        onDismissRequest = { durdur(); onClose() },
        title = { Text("Zil sesi yap") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    dosya == null -> Text("Zil sesi yapmak için önce şarkıyı indirmelisin.", color = MaterialTheme.colorScheme.error)
                    sonuc != null -> {
                        Spacer(Modifier.height(8.dp))
                        Text("Kaydedildi. Telefonun ${when (tur) { "bildirim" -> "bildirim sesleri"; "alarm" -> "alarm sesleri"; else -> "zil sesleri" }} listesinde \"${t.title} (SubPlay)\" adıyla görünür.")
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            if (Settings.System.canWrite(ctx)) {
                                val tip = when (tur) { "bildirim" -> RingtoneManager.TYPE_NOTIFICATION; "alarm" -> RingtoneManager.TYPE_ALARM; else -> RingtoneManager.TYPE_RINGTONE }
                                runCatching { RingtoneManager.setActualDefaultRingtoneUri(ctx, tip, sonuc) }
                                    .onSuccess { Toast.makeText(ctx, "Varsayılan yapıldı", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, "Varsayılan yapılamadı", Toast.LENGTH_SHORT).show() }
                            } else {
                                Toast.makeText(ctx, "\"Sistem ayarlarını değiştirme\" iznini ver, sonra tekrar bas", Toast.LENGTH_LONG).show()
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Varsayılan yap") }
                    }
                    else -> {
                        Spacer(Modifier.height(8.dp))
                        Text("${sure(aralik.start.toLong())} – ${sure(aralik.endInclusive.toLong())}  (${sure((aralik.endInclusive - aralik.start).toLong())})",
                            style = MaterialTheme.typography.bodyMedium)
                        RangeSlider(
                            value = aralik, valueRange = 0f..toplam.toFloat().coerceAtLeast(1f),
                            onValueChange = { r ->
                                // En çok 40 saniye
                                aralik = if (r.endInclusive - r.start > 40_000f) r.start..(r.start + 40_000f) else r
                                durdur()
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (calisiyor) durdur() else {
                                    val mp = MediaPlayer().apply { setDataSource(dosya.absolutePath); prepare(); seekTo(aralik.start.toInt()); start() }
                                    oynatici.value = mp; calisiyor = true
                                    scope.launch {
                                        while (calisiyor && oynatici.value === mp) {
                                            if (runCatching { mp.currentPosition }.getOrDefault(0) >= aralik.endInclusive) { durdur(); break }
                                            delay(100)
                                        }
                                    }
                                }
                            }) { Icon(if (calisiyor) Icons.Default.Stop else Icons.Default.PlayArrow, "Dinle") }
                            Text("Seçili bölümü dinle")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("zil" to "Zil sesi", "bildirim" to "Bildirim", "alarm" to "Alarm").forEach { (k, ad) ->
                                FilterChip(selected = tur == k, onClick = { tur = k }, label = { Text(ad) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (dosya != null && sonuc == null) TextButton(enabled = !kaydediliyor, onClick = {
                durdur(); kaydediliyor = true
                scope.launch {
                    runCatching { repo.zilSesiYap(t, aralik.start.toLong(), aralik.endInclusive.toLong(), tur) }
                        .onSuccess { sonuc = it }
                        .onFailure { Toast.makeText(ctx, it.message ?: "Kaydedilemedi", Toast.LENGTH_LONG).show() }
                    kaydediliyor = false
                }
            }) { if (kaydediliyor) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Kaydet") }
            else TextButton(onClick = { durdur(); onClose() }) { Text("Tamam") }
        },
        dismissButton = { if (dosya != null && sonuc == null) TextButton(onClick = { durdur(); onClose() }) { Text("Vazgeç") } },
    )
}

// ======================= Yıllık özet =======================

/** Spotify Wrapped gibi: bu yılın en çokları; paylaşılabilir görsel. */
@Composable
fun YillikOzetDiyalogu(lib: List<Track>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val yil = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val gun = remember { Calendar.getInstance().get(Calendar.DAY_OF_YEAR) }
    val ozet = remember(lib) { Istatistik.get(ctx).ozet(lib, gun) }
    val dk = ozet.toplamMs / 60_000
    val enAy = remember(ozet) {
        val aylar = listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")
        ozet.gunlukDk.groupBy { Calendar.getInstance().apply { timeInMillis = it.first }.get(Calendar.MONTH) }
            .mapValues { e -> e.value.sumOf { it.second } }.maxByOrNull { it.value }?.let { aylar[it.key] }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("$yil yılın") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (ozet.dinlemeSayisi == 0) Text("Bu yıl henüz dinleme kaydı yok.")
                else {
                    Text("$dk dakika", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("müzik dinledin · ${ozet.farkliSarki} farklı şarkı" + (enAy?.let { " · en çok $it" } ?: ""))
                    Spacer(Modifier.height(12.dp))
                    Text("En çok dinlediğin şarkılar", fontWeight = FontWeight.SemiBold)
                    ozet.enCokSarkilar.take(5).forEachIndexed { i, s ->
                        Text("${i + 1}. ${s.track.title} — ${s.track.artist} (${s.kez} kez)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("En çok dinlediğin sanatçılar", fontWeight = FontWeight.SemiBold)
                    ozet.enCokSanatcilar.take(5).forEachIndexed { i, s ->
                        Text("${i + 1}. ${s.ad} (${s.ms / 60_000} dk)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            if (ozet.dinlemeSayisi > 0) TextButton(onClick = { ozetiPaylas(ctx, yil, dk, ozet.farkliSarki, enAy,
                ozet.enCokSarkilar.take(5).map { "${it.track.title} — ${it.track.artist}" }, ozet.enCokSanatcilar.take(5).map { it.ad }) }) { Text("Paylaş") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Kapat") } },
    )
}

/** Özeti dikey bir kart görseli olarak çizip paylaşır. */
private fun ozetiPaylas(ctx: android.content.Context, yil: Int, dk: Long, farkli: Int, enAy: String?, sarkilar: List<String>, sanatcilar: List<String>) {
    val w = 1080; val h = 1920
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1DB954.toInt(), 0xFF0B1A2E.toInt(), Shader.TileMode.CLAMP)
    })
    fun yaz(t: String, x: Float, y: Float, boy: Float, kalin: Boolean = false, renk: Int = 0xFFFFFFFF.toInt()) {
        cv.drawText(t.take(42), x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = renk; textSize = boy; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (kalin) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        })
    }
    yaz("SubPlay · $yil", 80f, 180f, 56f, true)
    yaz("$dk", 80f, 420f, 200f, true)
    yaz("dakika müzik", 80f, 500f, 60f)
    yaz("$farkli farklı şarkı" + (enAy?.let { " · en çok $it" } ?: ""), 80f, 580f, 44f, renk = 0xCCFFFFFF.toInt())
    yaz("En çok dinlediğin şarkılar", 80f, 760f, 52f, true)
    sarkilar.forEachIndexed { i, s -> yaz("${i + 1}. $s", 80f, 850f + i * 72f, 44f) }
    yaz("En çok dinlediğin sanatçılar", 80f, 1310f, 52f, true)
    sanatcilar.forEachIndexed { i, s -> yaz("${i + 1}. $s", 80f, 1400f + i * 72f, 44f) }
    val f = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "subplay_ozet_$yil.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Yıllık özeti paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ======================= Yeni çıkanlar =======================

/** Listeler sayfasında: kütüphanendeki sanatçıların son 30 günde çıkan şarkıları. */
@Composable
fun YeniCikanlarSirasi(repo: MusicRepository, onDinle: (MusicRepository.YeniCikan) -> Unit) {
    val c = MaterialTheme.colorScheme
    val liste by repo.yeniCikanlar.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var yukleniyor by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // Hiç bakılmadıysa ilk açılışta bir kez bak
    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences("ayarlar", android.content.Context.MODE_PRIVATE)
        if (liste.isEmpty() && System.currentTimeMillis() - p.getLong("yeni_son_bakis", 0) > 6 * 3600_000L && repo.tracks.value.isNotEmpty()) {
            p.edit().putLong("yeni_son_bakis", System.currentTimeMillis()).apply()
            yukleniyor = true; runCatching { repo.yeniCikanlariBul() }; yukleniyor = false
        }
    }
    if (liste.isEmpty() && !yukleniyor) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Yeni çıkanlar", style = MaterialTheme.typography.titleSmall, color = c.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (yukleniyor) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { yukleniyor = true; runCatching { repo.yeniCikanlariBul() }; yukleniyor = false } }) {
                Icon(Icons.Default.Refresh, "Yeni çıkanlara bak")
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(liste, key = { it.anahtar }) { y ->
                Column(Modifier.width(112.dp).clickable { onDinle(y) }) {
                    Kapak(y.kapak, Modifier.size(112.dp), corner = 12.dp)
                    Text(y.ad, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    Text(y.sanatci, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text(java.text.SimpleDateFormat("d MMM", java.util.Locale("tr")).format(java.util.Date(y.tarih)),
                        style = MaterialTheme.typography.labelSmall, color = c.primary)
                }
            }
        }
    }
}


// ======================= Müzikle uyanma (Ayarlar) =======================

@Composable
fun UyandirmaAyari(playlists: List<com.dogukan.muzik.data.Playlist>) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf(com.dogukan.muzik.player.Uyandirma.oku(ctx)) }
    fun kaydet(y: com.dogukan.muzik.player.Uyandirma.Ayar) { a = y; com.dogukan.muzik.player.Uyandirma.kaydet(ctx, y) }
    var listeSec by remember { mutableStateOf(false) }
    val listeAdi = playlists.find { it.id == a.liste }?.name ?: "Tüm kütüphane"
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.ListItem(
            colors = transparentRow(),
            modifier = Modifier.clickable { kaydet(a.copy(acik = !a.acik)) },
            headlineContent = { Text("Müzikle uyan") },
            supportingContent = {
                Text(if (a.acik) "%02d:%02d · $listeAdi · ${if (a.herGun) "her gün" else "bir kez"}".format(a.saat, a.dakika)
                    else "Seçtiğin listeyle çalan alarm; ses yavaşça açılır")
            },
            trailingContent = { androidx.compose.material3.Switch(checked = a.acik, onCheckedChange = { kaydet(a.copy(acik = it)) }) },
        )
        if (a.acik) Column(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    android.app.TimePickerDialog(ctx, { _, s, d -> kaydet(a.copy(saat = s, dakika = d)) }, a.saat, a.dakika, true).show()
                }) { Text("Saat: %02d:%02d".format(a.saat, a.dakika)) }
                TextButton(onClick = { listeSec = true }) { Text("Liste: $listeAdi", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Her gün", modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = a.herGun, onCheckedChange = { kaydet(a.copy(herGun = it)) })
            }
        }
    }
    if (listeSec) {
        AlertDialog(
            onDismissRequest = { listeSec = false },
            title = { Text("Hangi liste çalsın?") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(320.dp)) {
                    item { Text("Tüm kütüphane", Modifier.fillMaxWidth().clickable { kaydet(a.copy(liste = null)); listeSec = false }.padding(12.dp)) }
                    items(playlists, key = { it.id }) { p ->
                        Text(p.name, Modifier.fillMaxWidth().clickable { kaydet(a.copy(liste = p.id)); listeSec = false }.padding(12.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { listeSec = false }) { Text("Kapat") } },
        )
    }
}
