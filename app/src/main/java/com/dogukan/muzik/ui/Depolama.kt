package com.dogukan.muzik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dogukan.muzik.data.MusicRepository
import com.dogukan.muzik.data.Track
import kotlinx.coroutines.launch

fun boyutYaz(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale("tr"), "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale("tr"), "%.0f MB", b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

/** Depolama: ne kadar yer kaplıyor, uzun süredir dinlenmeyen indirmeleri temizle. */
@Composable
fun DepolamaEkrani(repo: MusicRepository, coverOf: (Track) -> java.io.File?, onSilDosyalar: (List<Track>) -> Unit, onClose: () -> Unit, toast: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<MusicRepository.Depolama?>(null) }
    var gun by remember { mutableIntStateOf(90) }
    var liste by remember { mutableStateOf<List<Pair<Track, Long>>?>(null) }
    var secili by remember { mutableStateOf(setOf<String>()) }
    var onay by remember { mutableStateOf(false) }
    var surum by remember { mutableIntStateOf(0) }

    LaunchedEffect(surum) { d = repo.depolama() }
    LaunchedEffect(gun, surum) {
        liste = null
        val l = repo.dinlenmeyenIndirmeler(gun)
        liste = l; secili = l.map { it.first.id }.toSet()
    }

    Box(Modifier.fillMaxSize().background(c.background).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                Text("Depolama", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    val dd = d
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(c.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(16.dp)) {
                        if (dd == null) CircularProgressIndicator(Modifier.size(24.dp))
                        else {
                            Text(boyutYaz(dd.sarkiBayt + dd.podcastBayt + dd.onbellekBayt + dd.kapakBayt),
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("toplam kullanılan", color = c.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Satir("İndirilen şarkılar (${dd.indirilen})", dd.sarkiBayt)
                            Satir("Podcast bölümleri", dd.podcastBayt)
                            Satir("Kapak resimleri", dd.kapakBayt)
                            Satir("Geçici dosyalar", dd.onbellekBayt)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch { repo.onbellegiTemizle(); toast("Geçici dosyalar temizlendi"); surum++ }
                            }) { Text("Geçici dosyaları temizle") }
                        }
                    }
                }
                item {
                    Text("Uzun süredir dinlemediğin indirmeler", style = MaterialTheme.typography.titleSmall, color = c.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                    Text("Silinen şarkılar kütüphanede kalır, istediğinde internetten çalınır.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 90, 180, 365).forEach { g ->
                            FilterChip(selected = gun == g, onClick = { gun = g }, label = { Text(if (g == 365) "1 yıl" else "$g gün") })
                        }
                    }
                }
                val l = liste
                if (l == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else if (l.isEmpty()) item {
                    Text("Bu süreden uzun dinlenmeyen indirme yok.", color = c.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                } else {
                    item {
                        val toplam = l.filter { it.first.id in secili }.sumOf { it.second }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${secili.size} şarkı · ${boyutYaz(toplam)}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { secili = if (secili.size == l.size) emptySet() else l.map { it.first.id }.toSet() }) {
                                Text(if (secili.size == l.size) "Hiçbiri" else "Hepsi")
                            }
                            Button(enabled = secili.isNotEmpty(), onClick = { onay = true }) { Text("Sil") }
                        }
                    }
                    items(l, key = { it.first.id }) { (t, b) ->
                        ListItem(
                            colors = transparentRow(),
                            modifier = Modifier.clickable { secili = if (t.id in secili) secili - t.id else secili + t.id },
                            leadingContent = { Kapak(coverOf(t), Modifier.size(44.dp), corner = 8.dp) },
                            headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${t.artist} · ${boyutYaz(b)}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant) },
                            trailingContent = { Checkbox(checked = t.id in secili, onCheckedChange = { secili = if (it) secili + t.id else secili - t.id }) },
                        )
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
    if (onay) {
        val l = liste.orEmpty().filter { it.first.id in secili }
        AlertDialog(
            onDismissRequest = { onay = false },
            title = { Text("${l.size} indirme silinsin mi?") },
            text = { Text("${boyutYaz(l.sumOf { it.second })} yer açılacak. Şarkılar kütüphanede ve listelerde kalır.") },
            confirmButton = { TextButton(onClick = { onay = false; onSilDosyalar(l.map { it.first }); surum++ }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { onay = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun Satir(ad: String, b: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(ad, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(boyutYaz(b), fontWeight = FontWeight.SemiBold)
    }
}

/** Çift şarkılar: grupları gösterir, onaylanınca birleştirir. */
@Composable
fun CiftlerDiyalogu(repo: MusicRepository, onClose: () -> Unit, toast: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var gruplar by remember { mutableStateOf<List<List<Track>>?>(null) }
    LaunchedEffect(Unit) { gruplar = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { repo.ciftler() } }
    val g = gruplar
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (g == null) "Aranıyor…" else if (g.isEmpty()) "Çift şarkı yok" else "${g.size} şarkının birden çok kaydı var") },
        text = {
            when {
                g == null -> CircularProgressIndicator()
                g.isEmpty() -> Text("Kütüphanende aynı şarkıdan iki tane bulunmadı.")
                else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        Text("Her birinden indirilmiş olan (yoksa ilk eklenen) tutulur, diğerleri kaldırılır. Listelerdeki yerleri ve beğeniler korunur.",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(g) { grup ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(grup.first().title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${grup.first().artist} · ${grup.size} kayıt" + if (grup.any { it.indirildi }) " · indirilmiş var" else "",
                                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!g.isNullOrEmpty()) TextButton(onClick = {
                val n = repo.ciftleriBirlestir(g)
                toast("$n fazla kayıt kaldırıldı"); onClose()
            }) { Text("Birleştir") }
            else TextButton(onClick = onClose) { Text("Tamam") }
        },
        dismissButton = { if (!g.isNullOrEmpty()) TextButton(onClick = onClose) { Text("Vazgeç") } },
    )
}
