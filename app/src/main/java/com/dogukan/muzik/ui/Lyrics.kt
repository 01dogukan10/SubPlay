package com.dogukan.muzik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dogukan.muzik.MainViewModel
import com.dogukan.muzik.PlayerUi
import com.dogukan.muzik.data.Sozler

/** Şarkı sözleri: zamanlıysa çalan satır vurgulanır ve ekran onu takip eder; satıra dokununca oraya gider. */
@Composable
fun LyricsScreen(player: PlayerUi, vm: MainViewModel, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var sozler by remember { mutableStateOf<Sozler?>(null) }
    var yukleniyor by remember { mutableStateOf(true) }
    var hata by remember { mutableStateOf<String?>(null) }
    var deneme by remember { mutableIntStateOf(0) }
    val id = player.currentId

    LaunchedEffect(id, deneme) {
        if (id == null) return@LaunchedEffect
        yukleniyor = true; hata = null; sozler = null
        try {
            sozler = vm.sozler(id, player.duration)
        } catch (e: Exception) {
            hata = "Sözler alınamadı. İnternet bağlantını kontrol et."
        }
        yukleniyor = false
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.primary.copy(alpha = 0.35f), c.background, c.background)))
            .background(c.background.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides c.onBackground,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Kapat", modifier = Modifier.size(32.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(player.title.orEmpty(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (player.artist.isNotBlank()) {
                            Text(player.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    IconButton(onClick = { id?.let { vm.sozleriYenile(it) }; deneme++ }) {
                        Icon(Icons.Default.Refresh, "Yeniden ara")
                    }
                }
                val s = sozler
                when {
                    yukleniyor -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    hata != null -> Mesaj(hata!!)
                    s == null || s.bos -> Mesaj("Bu şarkının sözleri bulunamadı.\n\nŞarkının adı ya da sanatçısı yanlışsa ⋮ → Adını düzenle ile düzeltip ↻'ye basabilirsin.")
                    s.enstrumantal && !s.zamanli && s.duz.isNullOrBlank() -> Mesaj("♪ Enstrümantal şarkı ♪")
                    s.zamanli -> ZamanliSozler(s, player, vm)
                    else -> Text(
                        s.duz.orEmpty(),
                        style = MaterialTheme.typography.titleLarge.copy(lineHeight = 34.sp),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Mesaj(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ZamanliSozler(s: Sozler, player: PlayerUi, vm: MainViewModel) {
    val c = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    val aktif = s.satirlar.indexOfLast { it.first <= player.position + 300 }.coerceAtLeast(0)
    LaunchedEffect(aktif) {
        if (!state.isScrollInProgress) state.animateScrollToItem((aktif - 3).coerceAtLeast(0))
    }
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        item { Spacer(Modifier.height(16.dp)) }
        itemsIndexed(s.satirlar) { i, (ms, text) ->
            val gecti = i < aktif
            Text(
                text.ifBlank { "♪" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = when {
                    i == aktif -> c.primary
                    gecti -> c.onBackground.copy(alpha = 0.45f)
                    else -> c.onBackground.copy(alpha = 0.8f)
                },
                modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(ms) }.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
        item { Spacer(Modifier.height(240.dp)) }
    }
}
