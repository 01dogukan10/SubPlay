package com.dogukan.muzik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dogukan.muzik.data.Istatistik
import com.dogukan.muzik.data.Track
import java.io.File

private fun sure(ms: Long): String {
    val dk = ms / 60_000
    return if (dk >= 60) "${dk / 60} sa ${dk % 60} dk" else "$dk dk"
}

/** Kendiliğinden güncellenen listeler (En çok dinlediklerim, Son eklenenler, Unutulanlar). */
@Composable
fun SmartListScreen(
    baslik: String,
    aciklama: String,
    songs: List<Track>,
    coverOf: (Track) -> File?,
    currentId: String?,
    shuffle: Boolean,
    onToggleShuffle: () -> Unit,
    onPlay: (List<Track>, Int, Boolean?) -> Unit,
    onBack: () -> Unit,
    menu: @Composable (Track, () -> Unit) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            }
            Text(baslik, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            Text(aciklama, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${songs.size} şarkı", color = c.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (songs.isNotEmpty()) {
                    SmartPlayControls(shuffle, onToggleShuffle) { onPlay(songs, if (shuffle) songs.indices.random() else 0, null) }
                }
            }
            if (songs.isEmpty()) {
                Text("Burada henüz bir şey yok. Şarkı dinledikçe dolacak.", color = c.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
            }
        }
        itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
            SmartRow(t, coverOf(t), t.id == currentId, onClick = { onPlay(songs, i, null) }, menu = { close -> menu(t, close) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SmartPlayControls(shuffle: Boolean, onToggleShuffle: () -> Unit, onPlay: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleShuffle) { KarisikIkon(shuffle) }
        androidx.compose.material3.FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(52.dp).neonCerceve(androidx.compose.foundation.shape.CircleShape),
        ) { Icon(Icons.Default.PlayArrow, "Çal") }
    }
}

@Composable
private fun SmartRow(t: Track, cover: File?, playing: Boolean, onClick: () -> Unit, menu: @Composable (() -> Unit) -> Unit) {
    val c = MaterialTheme.colorScheme
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ListItem(
        colors = transparentRow(),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Kapak(cover, Modifier.size(52.dp), corner = 10.dp) },
        headlineContent = {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) c.primary else c.onSurface, fontWeight = if (playing) FontWeight.Bold else null)
        },
        supportingContent = if (t.artist.isNotBlank()) { { Text(t.artist, maxLines = 1, color = c.onSurfaceVariant) } } else null,
        trailingContent = {
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Seçenekler") }
                androidx.compose.material3.DropdownMenu(open, { open = false }) { menu { open = false } }
            }
        },
    )
}

/** Dinleme istatistikleri: toplam süre, en çok dinlenen şarkılar ve sanatçılar, günlük grafik. */
@Composable
fun StatsScreen(lib: List<Track>, coverOf: (Track) -> File?, surum: Int, onYillik: () -> Unit = {}, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val donemler = listOf(7 to "Son 7 gün", 30 to "Son 30 gün", 365 to "Son 1 yıl", 0 to "Tüm zamanlar")
    var gun by rememberSaveable { mutableIntStateOf(30) }
    val ozet = remember(lib, gun, surum) { Istatistik.get(ctx).ozet(lib, gun) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                Text("İstatistikler", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                androidx.compose.material3.FilledTonalButton(onClick = onYillik, modifier = Modifier.padding(end = 8.dp)) { Text("Yıllık özet") }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                donemler.forEach { (g, ad) ->
                    FilterChip(selected = gun == g, onClick = { gun = g }, label = { Text(ad, maxLines = 1) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kutu("Dinleme süresi", sure(ozet.toplamMs), Modifier.weight(1f))
                Kutu("Dinleme", "${ozet.dinlemeSayisi}", Modifier.weight(1f))
                Kutu("Farklı şarkı", "${ozet.farkliSarki}", Modifier.weight(1f))
            }
            if (ozet.gunlukDk.isNotEmpty()) {
                val max = (ozet.gunlukDk.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                Text("Günlük dinleme (dakika)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (ozet.gunlukDk.size > 10) 2.dp else 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ozet.gunlukDk.forEach { (_, dk) ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight(fraction = (dk.toFloat() / max).coerceAtLeast(0.02f))
                                .background(if (dk > 0) c.primary else c.surfaceVariant, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        )
                    }
                }
            }
            if (ozet.dinlemeSayisi == 0) {
                Text("Bu dönemde kayıtlı dinleme yok. En az 30 saniye dinlenen şarkılar sayılır.",
                    color = c.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            if (ozet.enCokSarkilar.isNotEmpty()) {
                Text("En çok dinlediğin şarkılar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            }
        }
        itemsIndexed(ozet.enCokSarkilar) { i, s ->
            ListItem(
                colors = transparentRow(),
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontWeight = FontWeight.Bold, color = c.primary, modifier = Modifier.width(24.dp))
                        Kapak(coverOf(s.track), Modifier.size(44.dp), corner = 8.dp)
                    }
                },
                headlineContent = { Text(s.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(s.track.artist, maxLines = 1, color = c.onSurfaceVariant) },
                trailingContent = { Text("${s.kez} kez", color = c.onSurfaceVariant) },
            )
        }
        if (ozet.enCokSanatcilar.isNotEmpty()) {
            item {
                Text("En çok dinlediğin sanatçılar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            }
            itemsIndexed(ozet.enCokSanatcilar) { i, a ->
                ListItem(
                    colors = transparentRow(),
                    leadingContent = { Text("${i + 1}", fontWeight = FontWeight.Bold, color = c.primary, modifier = Modifier.width(24.dp)) },
                    headlineContent = { Text(a.ad, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text("${sure(a.ms)} · ${a.kez} kez", color = c.onSurfaceVariant) },
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun Kutu(baslik: String, deger: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.background(c.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .neonCerceve(RoundedCornerShape(16.dp), glow = false).padding(12.dp)
    ) {
        Text(deger, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.primary, maxLines = 1)
        Text(baslik, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
    }
}
