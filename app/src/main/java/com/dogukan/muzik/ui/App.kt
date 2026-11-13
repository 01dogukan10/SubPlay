package com.dogukan.muzik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.dogukan.muzik.MainViewModel
import com.dogukan.muzik.PlayerUi
import com.dogukan.muzik.data.DownloadTask
import com.dogukan.muzik.data.Playlist
import com.dogukan.muzik.data.SearchResult
import com.dogukan.muzik.data.Track
import kotlinx.coroutines.launch
import java.util.Locale

private val TR = Locale("tr")

@Composable
fun App(vm: MainViewModel, sharedUrl: String?, onSharedConsumed: () -> Unit) {
    val tracks by vm.repo.tracks.collectAsStateWithLifecycle()
    val playlists by vm.repo.playlists.collectAsStateWithLifecycle()
    val downloads by vm.repo.downloads.collectAsStateWithLifecycle()
    val player by vm.ui.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }

    var showAdd by remember { mutableStateOf(false) }
    var addInitial by remember { mutableStateOf("") }
    var pickPlaylistFor by remember { mutableStateOf<Track?>(null) }
    var confirmDelete by remember { mutableStateOf<Track?>(null) }
    var editTrack by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            addInitial = sharedUrl
            showAdd = true
            tab = 0
            onSharedConsumed()
        }
    }

    BackHandler(enabled = tab == 1 && openPlaylistId != null) { openPlaylistId = null }

    val trackMenu: @Composable (Track, () -> Unit) -> Unit = { t, close ->
        DropdownMenuItem(text = { Text("Listeye ekle") }, onClick = { close(); pickPlaylistFor = t })
        DropdownMenuItem(text = { Text("Adını düzenle") }, onClick = { close(); editTrack = t })
        DropdownMenuItem(text = { Text("Sil") }, onClick = { close(); confirmDelete = t })
    }

    Scaffold(
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { addInitial = ""; showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Link ekle")
                }
            }
        },
        bottomBar = {
            Column {
                if (player.title != null) MiniPlayer(player, vm)
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Kütüphane") },
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }, label = { Text("Listeler") },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val openPl = playlists.find { it.id == openPlaylistId }
            when {
                tab == 0 -> LibraryScreen(
                    tracks, downloads, player.currentId,
                    onPlay = { list, i -> vm.playTracks(list, i) },
                    onDismissDownload = vm.repo::dismissDownload,
                    onUpdateDownloader = vm.repo::updateYtDlp,
                    menu = trackMenu,
                )
                openPl != null -> PlaylistDetail(
                    openPl, tracks, player.currentId,
                    onBack = { openPlaylistId = null },
                    onPlay = { list, i, shuffle -> vm.playTracks(list, i, shuffle) },
                    onRemove = { vm.repo.removeFromPlaylist(openPl.id, it.id) },
                )
                else -> PlaylistsScreen(
                    playlists,
                    onOpen = { openPlaylistId = it.id },
                    onCreate = { vm.repo.createPlaylist(it) },
                    onDelete = { vm.repo.deletePlaylist(it.id) },
                )
            }
        }
    }

    if (showAdd) {
        AddDialog(
            initial = addInitial,
            onDismiss = { showAdd = false },
            onAdd = { url, title -> vm.repo.startDownload(url, title); showAdd = false },
            onSearch = vm.repo::search,
            onPick = { vm.repo.downloadSearchResult(it); showAdd = false },
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
                    onClick = { vm.repo.renameTrack(t.id, title.trim(), artist.trim()); editTrack = null },
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
    onPlay: (List<Track>, Int) -> Unit,
    onDismissDownload: (String) -> Unit,
    onUpdateDownloader: suspend () -> String,
    menu: @Composable (Track, () -> Unit) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val filtered = remember(tracks, query) {
        val q = query.trim().lowercase(TR)
        if (q.isEmpty()) tracks
        else tracks.filter { it.title.lowercase(TR).contains(q) || it.artist.lowercase(TR).contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Kütüphanede ara") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Temizle") }
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Box {
            if (updating) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
            } else {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menü") }
            }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("İndiriciyi güncelle") },
                    onClick = {
                        menuOpen = false
                        updating = true
                        scope.launch {
                            val msg = onUpdateDownloader()
                            updating = false
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(downloads, key = { it.id }) { DownloadRow(it, onDismissDownload) }
            if (filtered.isEmpty() && downloads.isEmpty()) {
                item {
                    Text(
                        if (tracks.isEmpty()) "Henüz şarkı yok.\n+ tuşuna basıp şarkı adı ara ya da YouTube/Spotify linki yapıştır. YouTube veya Spotify'da \"Paylaş → Müzik\" de olur."
                        else "Sonuç bulunamadı.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(filtered, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, playing = t.id == currentId, onClick = { onPlay(filtered, i) }, menu = { close -> menu(t, close) })
            }
            item { Spacer(Modifier.heightIn(min = 80.dp)) }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadTask, onDismiss: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (d.error != null) {
                Text(d.error, color = MaterialTheme.colorScheme.error)
            } else if (d.progress != null) {
                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        trailingContent = {
            if (d.error != null) IconButton(onClick = { onDismiss(d.id) }) { Icon(Icons.Default.Close, "Kapat") }
        },
    )
}

@Composable
private fun TrackRow(
    t: Track,
    playing: Boolean,
    onClick: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(if (playing) Icons.Default.VolumeUp else Icons.Default.MusicNote, null, tint = accent)
        },
        headlineContent = {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = accent,
                fontWeight = if (playing) FontWeight.Bold else null)
        },
        supportingContent = if (t.artist.isNotBlank()) {
            { Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Playlist?>(null) }

    Column(Modifier.fillMaxSize()) {
        Button(onClick = { showNew = true }, modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Yeni liste")
        }
        if (playlists.isEmpty()) {
            Text("Henüz çalma listesi yok.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(playlists, key = { it.id }) { p ->
                var expanded by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { onOpen(p) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    headlineContent = { Text(p.name) },
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
    onBack: () -> Unit,
    onPlay: (List<Track>, Int, Boolean) -> Unit,
    onRemove: (Track) -> Unit,
) {
    val byId = remember(library) { library.associateBy { it.id } }
    val songs = remember(playlist, byId) { playlist.trackIds.mapNotNull { byId[it] } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = songs.isNotEmpty(), onClick = { onPlay(songs, 0, false) }) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Çal")
            }
            OutlinedButton(enabled = songs.isNotEmpty(), onClick = { onPlay(songs, songs.indices.random(), true) }) {
                Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Karışık")
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        if (songs.isEmpty()) {
            Text("Liste boş. Kütüphanede bir şarkının ⋮ menüsünden \"Listeye ekle\"yi seç.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, t.id == currentId, onClick = { onPlay(songs, i, false) }) { close ->
                    DropdownMenuItem(text = { Text("Listeden çıkar") }, onClick = { close(); onRemove(t) })
                }
            }
        }
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
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (results.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(results, key = { it.id }) { r ->
                            ListItem(
                                modifier = Modifier.clickable { onPick(r) },
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

// ---------------- Mini oynatıcı ----------------

@Composable
private fun MiniPlayer(ui: PlayerUi, vm: MainViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (ui.duration > 0) (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f) else 0f

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(ui.title.orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.artist.isNotBlank()) {
                Text(ui.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = if (dragging) dragValue else fraction,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { vm.seekTo((dragValue * ui.duration).toLong()); dragging = false },
                enabled = ui.duration > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = if (dragging) (dragValue * ui.duration).toLong() else ui.position
                Text(fmt(shown), style = MaterialTheme.typography.labelSmall)
                Text(fmt(ui.duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val on = MaterialTheme.colorScheme.primary
                val off = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Karışık", tint = if (ui.shuffle) on else off)
                }
                IconButton(onClick = vm::previous) { Icon(Icons.Default.SkipPrevious, "Önceki") }
                FilledIconButton(onClick = vm::togglePlay) {
                    Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (ui.isPlaying) "Duraklat" else "Çal")
                }
                IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, "Sonraki") }
                IconButton(onClick = vm::cycleRepeat) {
                    Icon(
                        if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Tekrar", tint = if (ui.repeat == Player.REPEAT_MODE_OFF) off else on,
                    )
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
