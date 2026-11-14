package com.dogukan.muzik.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dogukan.muzik.MainViewModel
import com.dogukan.muzik.data.DownloadTask
import com.dogukan.muzik.data.Playlist
import com.dogukan.muzik.data.SearchResult
import com.dogukan.muzik.data.Track
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private val TR = Locale("tr")

@Composable
fun App(
    vm: MainViewModel,
    sharedUrl: String?,
    onSharedConsumed: () -> Unit,
    sharedList: String?,
    onSharedListConsumed: () -> Unit,
    tema: TemaAyar,
    onTema: (TemaAyar) -> Unit,
) {
    val tracks by vm.repo.tracks.collectAsStateWithLifecycle()
    val playlists by vm.repo.playlists.collectAsStateWithLifecycle()
    val downloads by vm.repo.downloads.collectAsStateWithLifecycle()
    val player by vm.ui.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val neon = LocalNeon.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }

    var showAdd by remember { mutableStateOf(false) }
    var addInitial by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var showBulk by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.repo.notices.collect { notice = it } }
    var bulkInitial by remember { mutableStateOf("") }
    var pickPlaylistFor by remember { mutableStateOf<Track?>(null) }
    var confirmDelete by remember { mutableStateOf<Track?>(null) }
    var editTrack by remember { mutableStateOf<Track?>(null) }

    val coverOf: (Track) -> File? = { vm.repo.coverOf(it) }
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            addInitial = sharedUrl
            showAdd = true
            tab = 0
            onSharedConsumed()
        }
    }

    LaunchedEffect(sharedList) {
        if (sharedList != null) {
            bulkInitial = sharedList
            showBulk = true
            tab = 0
            onSharedListConsumed()
        }
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = !showPlayer && tab == 1 && openPlaylistId != null) { openPlaylistId = null }

    val trackMenu: @Composable (Track, () -> Unit) -> Unit = { t, close ->
        DropdownMenuItem(text = { Text("Listeye ekle") }, onClick = { close(); pickPlaylistFor = t })
        DropdownMenuItem(text = { Text("Adını düzenle") }, onClick = { close(); editTrack = t })
        DropdownMenuItem(text = { Text("Sil") }, onClick = { close(); confirmDelete = t })
    }

    Box(Modifier.fillMaxSize().background(arkaPlan())) {
        Scaffold(
            containerColor = Color.Transparent,
            floatingActionButton = {
                if (tab == 0) {
                    FloatingActionButton(
                        onClick = { addInitial = ""; showAdd = true },
                        shape = CircleShape,
                        containerColor = c.primary,
                        contentColor = c.onPrimary,
                        modifier = if (neon) Modifier.shadow(18.dp, CircleShape, ambientColor = c.primary, spotColor = c.primary) else Modifier,
                    ) { Icon(Icons.Default.Add, contentDescription = "Şarkı ekle") }
                }
            },
            bottomBar = {
                Column {
                    if (player.title != null) MiniPlayer(player, vm, onOpen = { showPlayer = true })
                    NavigationBar(
                        containerColor = if (neon) c.surface.copy(alpha = 0.85f) else c.surfaceContainer,
                        tonalElevation = 0.dp,
                    ) {
                        val itemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.onPrimary, indicatorColor = c.primary,
                            selectedTextColor = c.primary,
                        )
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 }, colors = itemColors,
                            icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Kütüphane") },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 }, colors = itemColors,
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }, label = { Text("Listeler") },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 }, colors = itemColors,
                            icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ayarlar") },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                val openPl = playlists.find { it.id == openPlaylistId }
                when {
                    tab == 0 -> LibraryScreen(
                        tracks, downloads, player.currentId, coverOf,
                        onPlay = { list, i -> vm.playTracks(list, i) },
                        onDismissDownload = vm.repo::dismissDownload,
                        onRetryDownload = vm.repo::retryDownload,
                        menu = trackMenu,
                    )
                    tab == 1 && openPl != null -> PlaylistDetail(
                        openPl, tracks, player.currentId, coverOf,
                        onBack = { openPlaylistId = null },
                        onPlay = { list, i, shuffle -> vm.playTracks(list, i, shuffle) },
                        onRemove = { vm.repo.removeFromPlaylist(openPl.id, it.id) },
                    )
                    tab == 1 -> PlaylistsScreen(
                        playlists, tracks, coverOf,
                        onOpen = { openPlaylistId = it.id },
                        onCreate = { vm.repo.createPlaylist(it) },
                        onDelete = { vm.repo.deletePlaylist(it.id) },
                    )
                    else -> SettingsScreen(
                        tema = tema,
                        onTema = onTema,
                        version = vm.currentVersion,
                        onCheckUpdate = { vm.checkUpdate(manual = true, onMessage = toast) },
                        onToken = { showToken = true },
                        onUpdateDownloader = vm.repo::updateYtDlp,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer && player.title != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FullPlayer(player, vm, onClose = { showPlayer = false })
        }
    }

    // ---------------- Diyaloglar ----------------

    if (showAdd) {
        AddDialog(
            initial = addInitial,
            onDismiss = { showAdd = false },
            onAdd = { url, title -> vm.repo.startDownload(url, title); showAdd = false },
            onSearch = vm.repo::search,
            onPick = { vm.repo.downloadSearchResult(it); showAdd = false },
            onBulk = { showAdd = false; bulkInitial = ""; showBulk = true },
        )
    }

    if (showBulk) {
        BulkDialog(
            initial = bulkInitial,
            onDismiss = { showBulk = false },
            onImport = { text, name ->
                val r = vm.repo.bulkImport(text, name)
                showBulk = false
                toast(
                    when {
                        r.found == 0 -> "Listede şarkı bulunamadı"
                        r.added == 0 -> "Yeni şarkı yok: ${r.found} şarkının hepsi zaten kütüphanede ya da iniyor"
                        r.added < r.found -> "${r.added} şarkı işleniyor (${r.found - r.added} tanesi zaten vardı, atlandı)"
                        else -> "${r.added} şarkı işleniyor"
                    }
                )
            },
        )
    }

    if (showToken) {
        var tok by remember { mutableStateOf(vm.token()) }
        AlertDialog(
            onDismissRequest = { showToken = false },
            title = { Text("GitHub token") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gizli SubPlay reposundan güncelleme almak için salt-okunur (Contents: Read) bir token yapıştır.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(tok, { tok = it }, label = { Text("github_pat_…") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setToken(tok); showToken = false
                    vm.checkUpdate(manual = true, onMessage = toast)
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { showToken = false }) { Text("Vazgeç") } },
        )
    }

    update.surum?.let { s ->
        AlertDialog(
            onDismissRequest = { if (!update.downloading) vm.dismissUpdate() },
            title = { Text("Yeni sürüm: ${s.ad}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Şu anki sürüm: ${vm.currentVersion}")
                    if (s.not.isNotBlank()) Text(s.not, style = MaterialTheme.typography.bodySmall, maxLines = 6,
                        overflow = TextOverflow.Ellipsis)
                    if (update.downloading) {
                        val p = update.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    update.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !update.downloading, onClick = vm::installUpdate) {
                    Text(if (update.downloading) "İndiriliyor…" else "Güncelle")
                }
            },
            dismissButton = {
                TextButton(enabled = !update.downloading, onClick = vm::dismissUpdate) { Text("Sonra") }
            },
        )
    }

    notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("Bilgi") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("Tamam") } },
        )
    }

    pickPlaylistFor?.let { t ->
        AddToPlaylistDialog(
            playlists,
            onDismiss = { pickPlaylistFor = null },
            onPick = { vm.repo.addToPlaylist(it, t.id); pickPlaylistFor = null },
            onCreate = { name -> vm.repo.addToPlaylist(vm.repo.createPlaylist(name), t.id); pickPlaylistFor = null },
        )
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Şarkı silinsin mi?") },
            text = { Text("\"${t.title}\" telefondan silinecek ve tüm listelerden çıkarılacak.") },
            confirmButton = { TextButton(onClick = { vm.deleteTrack(t); confirmDelete = null }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Vazgeç") } },
        )
    }

    editTrack?.let { t ->
        var title by remember(t.id) { mutableStateOf(t.title) }
        var artist by remember(t.id) { mutableStateOf(t.artist) }
        AlertDialog(
            onDismissRequest = { editTrack = null },
            title = { Text("Şarkı bilgisi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Ad") }, singleLine = true)
                    OutlinedTextField(artist, { artist = it }, label = { Text("Sanatçı") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = { vm.renameTrack(t.id, title.trim(), artist.trim()); editTrack = null },
                ) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { editTrack = null }) { Text("Vazgeç") } },
        )
    }
}

// ---------------- Kütüphane ----------------

@Composable
private fun LibraryScreen(
    tracks: List<Track>,
    downloads: List<DownloadTask>,
    currentId: String?,
    coverOf: (Track) -> File?,
    onPlay: (List<Track>, Int) -> Unit,
    onDismissDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    menu: @Composable (Track, () -> Unit) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(tracks, query) {
        val q = query.trim().lowercase(TR)
        if (q.isEmpty()) tracks
        else tracks.filter { it.title.lowercase(TR).contains(q) || it.artist.lowercase(TR).contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Kütüphane") {
            if (tracks.isNotEmpty()) {
                Text("${tracks.size} şarkı", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Kütüphanede ara") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Temizle") }
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = c.surfaceVariant.copy(alpha = 0.55f),
                focusedContainerColor = c.surfaceVariant.copy(alpha = 0.55f),
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (filtered.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPlay(filtered, 0) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Tümünü çal")
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(downloads, key = { it.id }) { DownloadRow(it, onDismissDownload, onRetryDownload) }
            if (filtered.isEmpty() && downloads.isEmpty()) {
                item {
                    EmptyState(
                        if (tracks.isEmpty()) "Henüz şarkı yok.\n+ tuşuna basıp şarkı adı ara ya da YouTube / Spotify linki yapıştır."
                        else "Sonuç bulunamadı."
                    )
                }
            }
            itemsIndexed(filtered, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, coverOf(t), playing = t.id == currentId, onClick = { onPlay(filtered, i) },
                    menu = { close -> menu(t, close) })
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Kapak(null, Modifier.size(96.dp), corner = 48.dp)
        Spacer(Modifier.height(16.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun DownloadRow(d: DownloadTask, onDismiss: (String) -> Unit, onRetry: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    ListItem(
        colors = transparentRow(),
        leadingContent = {
            Box(Modifier.size(52.dp).background(c.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Download, null, tint = if (d.error != null) c.error else c.primary)
            }
        },
        headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (d.error != null) {
                Text(d.error, color = c.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } else if (d.progress != null) {
                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        trailingContent = {
            if (d.error != null) {
                Row {
                    IconButton(onClick = { onRetry(d.id) }) { Icon(Icons.Default.Refresh, "Tekrar dene") }
                    IconButton(onClick = { onDismiss(d.id) }) { Icon(Icons.Default.Close, "Kapat") }
                }
            }
        },
    )
}

@Composable
private fun TrackRow(
    t: Track,
    cover: File?,
    playing: Boolean,
    onClick: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = transparentRow(),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Kapak(cover, Modifier.size(52.dp), corner = 10.dp)
                if (playing) {
                    Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp)))
                    Icon(Icons.Default.GraphicEq, null, tint = c.primary)
                }
            }
        },
        headlineContent = {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) c.primary else c.onSurface,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium)
        },
        supportingContent = if (t.artist.isNotBlank()) {
            { Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant) }
        } else null,
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Seçenekler") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    menu { expanded = false }
                }
            }
        },
    )
}

// ---------------- Çalma listeleri ----------------

@Composable
private fun PlaylistsScreen(
    playlists: List<Playlist>,
    library: List<Track>,
    coverOf: (Track) -> File?,
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Playlist?>(null) }
    val byId = remember(library) { library.associateBy { it.id } }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Listeler") {
            FilledTonalButton(onClick = { showNew = true }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Yeni")
            }
        }
        if (playlists.isEmpty()) EmptyState("Henüz çalma listesi yok.")
        LazyColumn {
            items(playlists, key = { it.id }) { p ->
                var expanded by remember { mutableStateOf(false) }
                val first = p.trackIds.firstNotNullOfOrNull { byId[it]?.let(coverOf) }
                ListItem(
                    colors = transparentRow(),
                    modifier = Modifier.clickable { onOpen(p) },
                    leadingContent = { Kapak(first, Modifier.size(60.dp), corner = 12.dp) },
                    headlineContent = { Text(p.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("${p.trackIds.size} şarkı") },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Seçenekler") }
                            DropdownMenu(expanded, { expanded = false }) {
                                DropdownMenuItem(text = { Text("Listeyi sil") }, onClick = { expanded = false; confirm = p })
                            }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showNew) NameDialog("Yeni çalma listesi", onDismiss = { showNew = false }) { onCreate(it); showNew = false }

    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("\"${p.name}\" silinsin mi?") },
            text = { Text("Şarkılar kütüphanede kalır, sadece liste silinir.") },
            confirmButton = { TextButton(onClick = { onDelete(p); confirm = null }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    library: List<Track>,
    currentId: String?,
    coverOf: (Track) -> File?,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int, Boolean?) -> Unit,
    onRemove: (Track) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val byId = remember(library) { library.associateBy { it.id } }
    val songs = remember(playlist, byId) { playlist.trackIds.mapNotNull { byId[it] } }
    val headerCover = songs.firstNotNullOfOrNull(coverOf)

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Kapak(
                    headerCover,
                    Modifier.size(128.dp).shadow(16.dp, RoundedCornerShape(18.dp),
                        ambientColor = c.primary, spotColor = c.primary),
                    corner = 18.dp,
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${songs.size} şarkı", color = c.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = songs.isNotEmpty(), onClick = { onPlay(songs, 0, false) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Çal")
                }
                FilledTonalButton(enabled = songs.isNotEmpty(), onClick = { onPlay(songs, songs.indices.random(), true) }) {
                    Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Karışık")
                }
            }
            if (songs.isEmpty()) {
                EmptyState("Liste boş. Kütüphanede bir şarkının ⋮ menüsünden \"Listeye ekle\"yi seç.")
            }
        }
        itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, coverOf(t), t.id == currentId, onClick = { onPlay(songs, i, null) }) { close ->
                DropdownMenuItem(text = { Text("Listeden çıkar") }, onClick = { close(); onRemove(t) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------- Diyaloglar ----------------

@Composable
private fun AddDialog(
    initial: String,
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit,
    onSearch: suspend (String) -> List<SearchResult>,
    onPick: (SearchResult) -> Unit,
    onBulk: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var title by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val input = text.trim()
    val isLink = input.startsWith("http://") || input.startsWith("https://")

    fun doSearch() {
        searching = true; error = null
        scope.launch {
            try {
                results = onSearch(input)
                if (results.isEmpty()) error = "Sonuç bulunamadı"
            } catch (e: Exception) {
                error = "Arama başarısız: ${e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: ""}"
            }
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Şarkı ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it; results = emptyList(); error = null },
                    label = { Text("Şarkı adı ya da YouTube / Spotify linki") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isLink && !input.contains("spotify")) {
                    OutlinedTextField(title, { title = it }, label = { Text("Ad (isteğe bağlı)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (results.isEmpty() && !searching) {
                    TextButton(onClick = onBulk) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null); Spacer(Modifier.width(6.dp))
                        Text("Elimde liste var, toplu ekle")
                    }
                }
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (results.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(results, key = { it.id }) { r ->
                            ListItem(
                                colors = transparentRow(),
                                modifier = Modifier.clickable { onPick(r) },
                                leadingContent = {
                                    Kapak("https://i.ytimg.com/vi/${r.id}/mqdefault.jpg", Modifier.size(48.dp), corner = 8.dp)
                                },
                                headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(listOf(r.channel, if (r.duration > 0) fmt(r.duration * 1000) else "")
                                        .filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isLink) {
                TextButton(onClick = { onAdd(input, title.ifBlank { null }) }) { Text("İndir") }
            } else {
                TextButton(enabled = input.isNotEmpty() && !searching, onClick = { doSearch() }) { Text("Ara") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun BulkDialog(
    initial: String,
    onDismiss: () -> Unit,
    onImport: (String, String?) -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var listName by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { text = it }
            if (listName.isBlank()) {
                uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.let { listName = it }
            }
        }
    }
    val count = remember(text) { text.lines().count { it.isNotBlank() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Toplu ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Her satıra bir şarkı yaz (\"Sanatçı - Şarkı\" en iyisi) ya da Spotify / YouTube şarkı linklerini yapıştır.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text("Tarkan - Şımarık\nSezen Aksu - Firuze\n…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "text/csv")) }) {
                        Text("Dosyadan aç (.txt / .csv)")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("$count satır", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(listName, { listName = it }, label = { Text("Çalma listesi adı (isteğe bağlı)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = count > 0, onClick = { onImport(text, listName.ifBlank { null }) }) {
                Text("Hepsini indir")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listeye ekle") },
        text = {
            Column {
                playlists.forEach { p ->
                    ListItem(
                        colors = transparentRow(),
                        modifier = Modifier.clickable { onPick(p.id) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        headlineContent = { Text(p.name) },
                    )
                }
                OutlinedTextField(newName, { newName = it }, label = { Text("Yeni liste adı") }, singleLine = true,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName.trim()) }) { Text("Oluştur ve ekle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun NameDialog(title: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Ad") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onOk(name.trim()) }) { Text("Oluştur") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
