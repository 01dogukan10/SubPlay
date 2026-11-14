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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dogukan.muzik.MainViewModel
import com.dogukan.muzik.PlayerUi
import com.dogukan.muzik.SiraOge
import com.dogukan.muzik.SiraUi

/** Spotify'daki "Sıra" ekranı: şimdi çalan, sıraya eklediklerin ve sonra gelecekler. */
@Composable
fun QueueScreen(sira: SiraUi, player: PlayerUi, vm: MainViewModel, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(c.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides c.onBackground,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Kapat", modifier = Modifier.size(32.dp)) }
                    Text("Sıra", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    if (player.shuffle) {
                        Icon(Icons.Default.Shuffle, null, tint = c.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.padding(4.dp))
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Baslik("Şimdi çalıyor")
                        ListItem(
                            colors = transparentRow(),
                            leadingContent = { Kapak(player.artwork, Modifier.size(52.dp), corner = 10.dp) },
                            headlineContent = {
                                Text(player.title.orEmpty(), color = c.primary, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = if (player.artist.isNotBlank()) {
                                { Text(player.artist, maxLines = 1, color = c.onSurfaceVariant) }
                            } else null,
                        )
                    }
                    if (sira.queued.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { Baslik("Sıradakiler") }
                                TextButton(onClick = vm::queueClear) { Text("Sırayı temizle") }
                            }
                        }
                        itemsIndexed(sira.queued, key = { _, q -> "q" + q.index + q.trackId }) { i, q ->
                            SiraSatiri(q, onClick = { vm.jumpTo(q.index) }) {
                                IconButton(enabled = i > 0, onClick = { vm.queueMove(i, i - 1) }) {
                                    Icon(Icons.Default.KeyboardArrowUp, "Yukarı")
                                }
                                IconButton(enabled = i < sira.queued.lastIndex, onClick = { vm.queueMove(i, i + 1) }) {
                                    Icon(Icons.Default.KeyboardArrowDown, "Aşağı")
                                }
                                IconButton(onClick = { vm.queueRemove(i) }) { Icon(Icons.Default.Close, "Sıradan çıkar") }
                            }
                        }
                    }
                    if (sira.upcoming.isNotEmpty()) {
                        item { Baslik(if (player.shuffle) "Sonra (karışık)" else "Sonra") }
                        itemsIndexed(sira.upcoming, key = { _, q -> "u" + q.index + q.trackId }) { _, q ->
                            SiraSatiri(q, onClick = { vm.jumpTo(q.index) })
                        }
                        if (sira.moreCount > 0) {
                            item {
                                Text("ve ${sira.moreCount} şarkı daha", color = c.onSurfaceVariant,
                                    modifier = Modifier.padding(20.dp))
                            }
                        }
                    }
                    if (sira.queued.isEmpty()) {
                        item {
                            Text(
                                "İpucu: bir şarkının ⋮ menüsünden \"Sıradaki olarak çal\" ya da \"Sıraya ekle\" diyerek " +
                                    "listenin geri kalanından önce çalmasını sağlayabilirsin.",
                                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Baslik(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SiraSatiri(q: SiraOge, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Kapak(q.artwork, Modifier.size(44.dp), corner = 8.dp) },
        headlineContent = {
            Text((if (q.trackId.startsWith("oneri:")) "✦ " else "") + q.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (q.artist.isNotBlank()) {
            { Text(q.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant) }
        } else null,
        trailingContent = trailing?.let { t -> { Row { t() } } },
    )
}
