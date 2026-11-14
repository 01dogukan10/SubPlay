package com.dogukan.muzik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    tema: TemaAyar,
    onTema: (TemaAyar) -> Unit,
    version: String,
    onCheckUpdate: () -> Unit,
    onExportBackup: (android.net.Uri) -> Unit,
    onImportBackup: (android.net.Uri) -> Unit,
    onUpdateDownloader: suspend () -> String,
    spotifyBagli: Boolean,
    spotifyClientId: String,
    onSpotifyClientId: (String) -> Unit,
    onSpotifyLogin: () -> Unit,
    onSpotifyLogout: () -> Unit,
    onImportLiked: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Ayarlar")

        Section("Görünüm") {
            Text("Tema", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Mod.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = tema.mod == m,
                        onClick = { onTema(tema.copy(mod = m)) },
                        shape = SegmentedButtonDefaults.itemShape(i, Mod.entries.size),
                    ) { Text(m.ad) }
                }
            }

            Text("Renk", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (duvarKagidiDestekli) {
                    Swatch(
                        brush = Brush.sweepGradient(listOf(Color(0xFFEF4444), Color(0xFFFACC15), Color(0xFF22C55E),
                            Color(0xFF3B82F6), Color(0xFFA855F7), Color(0xFFEF4444))),
                        label = "Duvar kağıdı",
                        selected = tema.vurgu < 0,
                        icon = true,
                    ) { onTema(tema.copy(vurgu = -1)) }
                }
                VURGULAR.forEachIndexed { i, v ->
                    Swatch(Brush.linearGradient(listOf(v.renk, v.renk)), v.ad, tema.vurgu == i,
                        checkDark = v.renk.luminance() > 0.5f) { onTema(tema.copy(vurgu = i)) }
                }
            }

            ListItem(
                colors = transparentRow(),
                modifier = Modifier.padding(top = 8.dp).clickable { onTema(tema.copy(neon = !tema.neon)) },
                headlineContent = { Text("Neon efekti") },
                supportingContent = { Text("Renkli geçişler, parlayan kenarlar ve kapak ışıması") },
                trailingContent = { Switch(checked = tema.neon, onCheckedChange = { onTema(tema.copy(neon = it)) }) },
            )
        }

        Section("Spotify") {
            var id by remember(spotifyClientId) { mutableStateOf(spotifyClientId) }
            var showHelp by remember { mutableStateOf(spotifyClientId.isBlank()) }
            ListItem(
                colors = transparentRow(),
                leadingContent = { Icon(Icons.Default.Link, null) },
                headlineContent = { Text(if (spotifyBagli) "Bağlı" else "Bağlı değil") },
                supportingContent = {
                    Text(if (spotifyBagli) "Kendi listelerinin tamamı gelir (100 sınırı yok)"
                    else "Girişsiz sadece ilk 100 şarkı gelir")
                },
            )
            if (!spotifyBagli) {
                OutlinedTextField(
                    id, { id = it }, label = { Text("Client ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NButton(enabled = id.isNotBlank(), onClick = { onSpotifyClientId(id); onSpotifyLogin() }) {
                        Text("Spotify ile giriş yap")
                    }
                    TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Yardımı gizle" else "Nasıl?") }
                }
                if (showHelp) {
                    Text(
                        "1) developer.spotify.com/dashboard adresine Spotify hesabınla gir → Create app.\n" +
                            "2) Ad ve açıklama yaz. Redirect URI olarak tam olarak şunu ekle:\n    ${com.dogukan.muzik.data.SpotifyApi.REDIRECT}\n" +
                            "3) \"Web API\" kutusunu işaretle, kaydet.\n" +
                            "4) Uygulamanın Settings sayfasındaki Client ID'yi kopyalayıp buraya yapıştır, giriş yap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                ListItem(
                    colors = transparentRow(),
                    modifier = Modifier.clickable(onClick = onImportLiked),
                    leadingContent = { Icon(Icons.Default.Favorite, null) },
                    headlineContent = { Text("Beğenilen şarkılarımı indir") },
                    supportingContent = { Text("\"Beğenilen şarkılar\" adlı listeye toplanır") },
                )
                ListItem(
                    colors = transparentRow(),
                    modifier = Modifier.clickable(onClick = onSpotifyLogout),
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                    headlineContent = { Text("Spotify çıkış yap") },
                )
            }
        }

        Section("Yedek / taşıma") {
            val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri -> if (uri != null) onExportBackup(uri) }
            val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri -> if (uri != null) onImportBackup(uri) }
            ListItem(
                colors = transparentRow(),
                modifier = Modifier.clickable { exportPicker.launch(null) },
                leadingContent = { Icon(Icons.Default.Backup, null) },
                headlineContent = { Text("Yedekle") },
                supportingContent = { Text("Şarkıları, kapakları ve listeleri seçtiğin klasöre kopyalar (örn. Music ya da Download)") },
            )
            ListItem(
                colors = transparentRow(),
                modifier = Modifier.clickable { importPicker.launch(null) },
                leadingContent = { Icon(Icons.Default.Restore, null) },
                headlineContent = { Text("Yedekten geri yükle") },
                supportingContent = { Text("\"SubPlay yedek\" klasörünü seç; olan şarkılar atlanır") },
            )
        }

        Section("Güncelleme") {
            ListItem(
                colors = transparentRow(),
                leadingContent = { Icon(Icons.Default.Info, null) },
                headlineContent = { Text("Uygulama sürümü") },
                supportingContent = { Text(version) },
            )
            ListItem(
                colors = transparentRow(),
                modifier = Modifier.clickable(onClick = onCheckUpdate),
                leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
                headlineContent = { Text("Güncellemeleri denetle") },
            )
            ListItem(
                colors = transparentRow(),
                modifier = Modifier.clickable(enabled = !updating) {
                    updating = true
                    scope.launch {
                        val msg = onUpdateDownloader()
                        updating = false
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                leadingContent = { Icon(Icons.Default.Download, null) },
                headlineContent = { Text("İndiriciyi güncelle") },
                supportingContent = { Text("YouTube indirmeleri hata verirse") },
                trailingContent = { if (updating) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            )
        }
    }
}

@Composable
fun ScreenTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val neon = LocalNeon.current
    val shape = RoundedCornerShape(20.dp)
    Text(title, style = MaterialTheme.typography.titleSmall, color = c.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp))
    Surface(
        color = if (neon) c.surface.copy(alpha = 0.7f) else c.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(if (neon) Modifier.border(1.dp, neonKenar(), shape) else Modifier),
    ) {
        Column(Modifier.padding(bottom = 8.dp)) { content() }
    }
}

@Composable
private fun Swatch(
    brush: Brush,
    label: String,
    selected: Boolean,
    icon: Boolean = false,
    checkDark: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(44.dp)
                .then(if (selected) Modifier.border(3.dp, c.onSurface, CircleShape) else Modifier)
                .padding(if (selected) 5.dp else 0.dp)
                .clip(CircleShape)
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Default.Check, null, tint = if (checkDark) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp))
                icon -> Icon(Icons.Default.Wallpaper, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
    }
}

@Composable
fun transparentRow() = ListItemDefaults.colors(containerColor = Color.Transparent)
