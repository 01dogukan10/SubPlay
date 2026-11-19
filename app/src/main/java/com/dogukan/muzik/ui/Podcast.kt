package com.dogukan.muzik.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dogukan.muzik.MainViewModel
import com.dogukan.muzik.PlayerUi
import com.dogukan.muzik.data.Bolum
import com.dogukan.muzik.data.Podcast
import com.dogukan.muzik.data.PodcastArama
import com.dogukan.muzik.data.PodcastRepo
import kotlinx.coroutines.launch

private val TR = java.util.Locale.forLanguageTag("tr")

/** Podcast sekmesi: abonelikler, arama, devam edilecek bölümler. */
@Composable
fun PodcastEkrani(vm: MainViewModel, player: PlayerUi, toast: (String) -> Unit) {
    val pr = vm.pod
    val podcastlar by pr.podcastlar.collectAsStateWithLifecycle()
    val bolumler by pr.bolumler.collectAsStateWithLifecycle()
    val yenileniyor by pr.yenileniyor.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var acik by rememberSaveable { mutableStateOf<String?>(null) }
    var sorgu by rememberSaveable { mutableStateOf("") }
    var sonuclar by remember { mutableStateOf<List<PodcastArama>?>(null) }
    var sonucBaslik by remember { mutableStateOf("Arama sonuçları") }
    val ilgi by pr.ilgi.collectAsStateWithLifecycle()
    var dil by remember { mutableStateOf(pr.oneriDili) }
    val onerilen = remember(ilgi, podcastlar, bolumler) { pr.onerilenKategoriler() }
    var araniyor by remember { mutableStateOf(false) }
    var ekleniyor by remember { mutableStateOf(setOf<String>()) }

    // Genel aramadan bir podcast açılması istendiyse
    val acilacak by pr.acilacak.collectAsStateWithLifecycle()
    LaunchedEffect(acilacak) { acilacak?.let { acik = it; pr.acilacak.value = null } }
    // Sekme açılınca (son kontrolden 1 saat geçtiyse) yeni bölümlere bak
    LaunchedEffect(Unit) {
        val son = podcastlar.maxOfOrNull { it.sonKontrol } ?: 0L
        if (podcastlar.isNotEmpty() && System.currentTimeMillis() - son > 3600_000L) runCatching { pr.yenile() }
    }

    val acikPodcast = podcastlar.find { it.id == acik }
    if (acik != null && acikPodcast == null) acik = null
    if (acikPodcast != null) {
        BackHandler { acik = null }
        PodcastDetay(acikPodcast, bolumler.filter { it.podcastId == acikPodcast.id }.sortedByDescending { it.tarih },
            vm, player, onBack = { acik = null }, toast = toast)
        return
    }

    fun abone(feed: String) {
        ekleniyor = ekleniyor + feed
        scope.launch {
            runCatching { pr.aboneOl(feed) }
                .onSuccess { acik = it; sonuclar = null; sorgu = "" }
                .onFailure { toast("Abone olunamadı: ${it.message ?: "bağlantı hatası"}") }
            ekleniyor = ekleniyor - feed
        }
    }

    fun ara() {
        val q = sorgu.trim()
        if (q.isEmpty()) return
        if (q.startsWith("http://") || q.startsWith("https://")) { abone(q); return }
        araniyor = true
        scope.launch {
            sonucBaslik = "Arama sonuçları"
            sonuclar = runCatching { pr.ara(q) }.getOrElse { toast("Arama yapılamadı. İnternet bağlantını kontrol et."); null }
            araniyor = false
        }
    }

    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Podcast") {
            if (podcastlar.isNotEmpty()) {
                if (yenileniyor) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                else IconButton(onClick = {
                    scope.launch {
                        val y = runCatching { pr.yenile() }.getOrDefault(emptyList())
                        toast(if (y.isEmpty()) "Yeni bölüm yok" else "${y.size} yeni bölüm")
                    }
                }) { Icon(Icons.Default.Refresh, "Yeni bölümlere bak") }
            }
        }
        OutlinedTextField(
            value = sorgu, onValueChange = { sorgu = it; if (it.isBlank()) sonuclar = null },
            placeholder = { Text("Podcast ara ya da RSS adresi yapıştır") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (sorgu.isNotEmpty()) IconButton(onClick = { sorgu = ""; sonuclar = null }) { Icon(Icons.Default.Close, "Temizle") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { ara() }),
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = c.surfaceVariant.copy(alpha = 0.55f),
                focusedContainerColor = c.surfaceVariant.copy(alpha = 0.55f),
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val listeDurumu = androidx.compose.foundation.lazy.rememberLazyListState()
        // Arama sonucu ya da "Tümü" açılınca en üste çık
        LaunchedEffect(sonuclar) { if (sonuclar != null) listeDurumu.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = listeDurumu) {
            if (araniyor) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            val s = sonuclar
            if (s != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Baslik(sonucBaslik) }
                        if (sonucBaslik != "Arama sonuçları") TextButton(onClick = { sonuclar = null }) { Text("Kapat") }
                    }
                }
                if (s.isEmpty()) item { Bilgi("Sonuç bulunamadı.") }
                items(s, key = { "a" + it.feed }) { r ->
                    val abone = podcastlar.any { it.feed == r.feed }
                    ListItem(
                        colors = transparentRow(),
                        leadingContent = { Kapak(r.kapak, Modifier.size(56.dp), corner = 10.dp) },
                        headlineContent = { Text(r.ad, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(r.yazar, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant) },
                        trailingContent = {
                            when {
                                r.feed in ekleniyor -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                abone -> TextButton(onClick = { acik = podcastlar.first { it.feed == r.feed }.id }) { Text("Aç") }
                                else -> TextButton(onClick = { abone(r.feed) }) { Text("Abone ol") }
                            }
                        },
                        modifier = Modifier.clickable { if (abone) acik = podcastlar.first { it.feed == r.feed }.id else abone(r.feed) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // Yarıda kalanlar
            val devam = bolumler.filter { it.konumMs > 0 && !it.dinlendi }.sortedByDescending { it.tarih }.take(5)
            if (s == null && devam.isNotEmpty()) {
                item { Baslik("Kaldığın yerden devam et") }
                items(devam, key = { "d" + it.id }) { b ->
                    BolumSatiri(b, pr, vm, player, kapakGoster = true, toast = toast)
                }
            }

            // Kişisel öneriler: ilgi alanları, aboneliklerin ve dinlediklerine göre
            if (s == null) {
                item {
                    Baslik("Senin için")
                    Text("Seçtiğin konulara, abone olduklarına ve dinlediklerine göre. İlgini çeken konuları seç:",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(com.dogukan.muzik.data.KATEGORILER, key = { "c" + it.id }) { k ->
                            androidx.compose.material3.FilterChip(selected = k.id in ilgi, onClick = { pr.ilgiDegistir(k.id) }, label = { Text(k.ad) })
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dil:", color = c.onSurfaceVariant)
                        listOf("tr" to "Türkçe", "en" to "İngilizce").forEach { (k, ad) ->
                            androidx.compose.material3.FilterChip(selected = dil == k, onClick = { dil = k; pr.oneriDili = k }, label = { Text(ad) })
                        }
                    }
                }
                if (onerilen.isEmpty()) item { Bilgi("Yukarıdan birkaç konu seç; sana göre podcast'ler burada görünecek.") }
                items(onerilen, key = { "o" + it.id + dil }) { k ->
                    KategoriSirasi(k, dil, pr, ekleniyor,
                        onAbone = { abone(it) },
                        onTumu = { list -> sonucBaslik = k.ad; sonuclar = list })
                }
            }

            if (s == null) {
                item { Baslik("Aboneliklerin") }
                if (podcastlar.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Podcasts, null, tint = c.primary, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Henüz abone olduğun podcast yok.\nYukarıdan adıyla ara ya da RSS adresini yapıştır.",
                                color = c.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                items(podcastlar.sortedBy { it.ad.lowercase(TR) }, key = { "p" + it.id }) { p ->
                    val yeni = bolumler.count { it.podcastId == p.id && it.yeni }
                    ListItem(
                        colors = transparentRow(),
                        leadingContent = { Kapak(p.kapak, Modifier.size(56.dp), corner = 10.dp) },
                        headlineContent = { Text(p.ad, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(p.yazar.ifBlank { "Podcast" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant) },
                        trailingContent = if (yeni > 0) {
                            {
                                Box(Modifier.background(c.primary, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text("$yeni yeni", color = c.onPrimary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else null,
                        modifier = Modifier.clickable { acik = p.id },
                    )
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun Baslik(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Bilgi(t: String) {
    Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun PodcastDetay(p: Podcast, list: List<Bolum>, vm: MainViewModel, player: PlayerUi, onBack: () -> Unit, toast: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var cik by remember { mutableStateOf(false) }
    var sadeceDinlenmemis by rememberSaveable(p.id) { mutableStateOf(false) }
    // Sayfa açılınca "yeni" işaretlerini kaldır
    LaunchedEffect(p.id) { vm.pod.yeniGoruldu(p.id) }
    val gosterilen = if (sadeceDinlenmemis) list.filter { !it.dinlendi } else list

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                Spacer(Modifier.weight(1f))
                Box {
                    var m by remember { mutableStateOf(false) }
                    IconButton(onClick = { m = true }) { Icon(Icons.Default.MoreVert, "Diğer") }
                    DropdownMenu(m, { m = false }) {
                        DropdownMenuItem(text = { Text("Abonelikten çık", color = c.error) }, onClick = { m = false; cik = true })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Kapak(p.kapak, Modifier.size(112.dp), corner = 16.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.ad, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (p.yazar.isNotBlank()) Text(p.yazar, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${list.size} bölüm", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ilk = list.firstOrNull { !it.dinlendi }
                if (ilk != null) Button(onClick = { vm.bolumCal(ilk, list) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp))
                    Text(if (ilk.konumMs > 0) "Devam et" else "En yeniyi çal")
                }
                OutlinedButton(onClick = { sadeceDinlenmemis = !sadeceDinlenmemis }) {
                    Text(if (sadeceDinlenmemis) "Hepsini göster" else "Dinlenmemişler")
                }
            }
        }
        if (gosterilen.isEmpty()) item { Bilgi(if (sadeceDinlenmemis) "Dinlenmemiş bölüm kalmadı." else "Bölüm bulunamadı.") }
        items(gosterilen, key = { it.id }) { b -> BolumSatiri(b, vm.pod, vm, player, kapakGoster = false, toast = toast, liste = gosterilen) }
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (cik) {
        AlertDialog(
            onDismissRequest = { cik = false },
            title = { Text("Abonelikten çık") },
            text = { Text("\"${p.ad}\" ve indirilmiş bölümleri telefondan kaldırılsın mı?") },
            confirmButton = { TextButton(onClick = { cik = false; vm.pod.aboneliktenCik(p.id); onBack() }) { Text("Çık", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { cik = false }) { Text("Vazgeç") } },
        )
    }
}

private fun sureYaz(sn: Int): String = when {
    sn <= 0 -> ""
    sn >= 3600 -> "${sn / 3600} sa ${(sn % 3600) / 60} dk"
    else -> "${(sn + 59) / 60} dk"
}

@Composable
private fun BolumSatiri(b: Bolum, pr: PodcastRepo, vm: MainViewModel, player: PlayerUi, kapakGoster: Boolean, toast: (String) -> Unit, liste: List<Bolum>? = null) {
    val c = MaterialTheme.colorScheme
    val ilerleme by pr.ilerleme.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var notlar by remember { mutableStateOf(false) }
    val calan = player.currentId == PodcastRepo.ON_EK + b.id
    val indirildi = pr.dosyaOf(b) != null
    val tarih = if (b.tarih > 0) java.text.SimpleDateFormat("d MMM yyyy", TR).format(java.util.Date(b.tarih)) else ""
    val kalan = if (b.konumMs > 0 && b.sureSn > 0) "${sureYaz((b.sureSn - (b.konumMs / 1000).toInt()).coerceAtLeast(0))} kaldı" else sureYaz(b.sureSn)
    ListItem(
        colors = transparentRow(),
        leadingContent = if (kapakGoster) { { Kapak(b.kapak ?: pr.podcast(b.podcastId)?.kapak, Modifier.size(48.dp), corner = 8.dp) } } else null,
        overlineContent = {
            Text(
                listOfNotNull(tarih.ifBlank { null }, if (b.yeni) "YENİ" else null).joinToString(" · "),
                color = if (b.yeni) c.primary else c.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(b.baslik, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (b.dinlendi) FontWeight.Normal else FontWeight.SemiBold,
                color = when { calan -> c.primary; b.dinlendi -> c.onSurfaceVariant; else -> c.onSurface })
        },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (b.dinlendi) {
                        Icon(Icons.Default.CheckCircle, null, tint = c.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp)); Text("Dinlendi", color = c.onSurfaceVariant)
                    } else Text(kalan, color = c.onSurfaceVariant)
                }
                if (!b.dinlendi && b.konumMs > 0 && b.sureSn > 0) {
                    LinearProgressIndicator(
                        progress = { (b.konumMs / 1000f / b.sureSn).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.6f).padding(top = 6.dp),
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    b.indirmeNo != null -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        val p = ilerleme[b.id]
                        if (p != null && p > 0f) CircularProgressIndicator(progress = { p }, Modifier.size(22.dp), strokeWidth = 2.dp)
                        else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    indirildi -> Icon(Icons.Default.DownloadDone, "İndirildi", tint = c.primary, modifier = Modifier.padding(8.dp))
                    else -> IconButton(onClick = { pr.indir(b); toast("İndirme başladı") }) { Icon(Icons.Default.Download, "İndir") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Diğer") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (b.dinlendi) "Dinlenmedi olarak işaretle" else "Dinlendi olarak işaretle") },
                            onClick = { menu = false; pr.dinlendiYap(b.id, !b.dinlendi) })
                        if (b.aciklama.isNotBlank()) DropdownMenuItem(text = { Text("Bölüm notları") }, onClick = { menu = false; notlar = true })
                        if (indirildi || b.indirmeNo != null) DropdownMenuItem(
                            text = { Text(if (indirildi) "İndirileni sil" else "İndirmeyi iptal et") },
                            onClick = { menu = false; pr.sil(b) },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable { vm.bolumCal(b, liste) },
    )
    if (notlar) {
        AlertDialog(
            onDismissRequest = { notlar = false },
            title = { Text(b.baslik, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = { Text(b.aciklama, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { notlar = false }) { Text("Kapat") } },
        )
    }
}

/** Büyük oynatıcıda podcast için: hız seçici. */
@Composable
fun HizDugmesi(hiz: Float, onHiz: (Float) -> Unit) {
    var acik by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { acik = true }) {
            Text(hizYaz(hiz), fontWeight = FontWeight.Bold)
        }
        DropdownMenu(acik, { acik = false }) {
            listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f).forEach { h ->
                DropdownMenuItem(
                    text = { Text(hizYaz(h), fontWeight = if (h == hiz) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { acik = false; onHiz(h) },
                )
            }
        }
    }
}

/** 15 sn geri / 30 sn ileri düğmesi. */
@Composable
fun AtlaDugmesi(sn: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Refresh, if (sn < 0) "${-sn} saniye geri" else "$sn saniye ileri",
                modifier = Modifier.size(36.dp).graphicsLayer { scaleX = if (sn < 0) -1f else 1f })
            Text("${kotlin.math.abs(sn)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

fun hizYaz(h: Float): String = (if (h == h.toInt().toFloat()) h.toInt().toString() else h.toString().trimEnd('0').trimEnd('.'))
    .replace('.', ',') + "x"

/** Bir kategoride önerilen podcast'ler: yatay kaydırılan kapaklar. Dokununca abone olunur ve açılır. */
@Composable
private fun KategoriSirasi(
    k: com.dogukan.muzik.data.Kategori, dil: String, pr: PodcastRepo, ekleniyor: Set<String>,
    onAbone: (String) -> Unit, onTumu: (List<PodcastArama>) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val podcastlar by pr.podcastlar.collectAsStateWithLifecycle()
    var liste by remember(k.id, dil) { mutableStateOf<List<PodcastArama>?>(null) }
    var hata by remember(k.id, dil) { mutableStateOf(false) }
    LaunchedEffect(k.id, dil, podcastlar.size) {
        runCatching { pr.kategoriOnerileri(k.id, dil) }.onSuccess { liste = it }.onFailure { hata = true }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(k.ad, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val l = liste
            if (!l.isNullOrEmpty()) TextButton(onClick = { onTumu(l) }) { Text("Tümü") }
        }
        val l = liste
        when {
            hata -> Bilgi("Öneriler alınamadı. İnternet bağlantını kontrol et.")
            l == null -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            l.isEmpty() -> Bilgi("Bu konuda yeni öneri yok.")
            else -> androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(l.take(15), key = { it.feed }) { r ->
                    Column(Modifier.width(120.dp).clickable(enabled = r.feed !in ekleniyor) { onAbone(r.feed) }) {
                        Box(contentAlignment = Alignment.Center) {
                            Kapak(r.kapak, Modifier.size(120.dp), corner = 12.dp)
                            if (r.feed in ekleniyor) {
                                Box(Modifier.size(120.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)))
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            }
                        }
                        Text(r.ad, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        Text(r.yazar, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
