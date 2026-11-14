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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Share
import com.dogukan.muzik.data.siraAnahtari
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Image
import androidx.activity.result.PickVisualMediaRequest
import com.dogukan.muzik.data.Siralama
import com.dogukan.muzik.data.sirala
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.ListItemDefaults
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
    val spotifyBagli by com.dogukan.muzik.data.SpotifyApi.bagli.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val neon = LocalNeon.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var libSort by remember { mutableStateOf(vm.repo.siralama("kutuphane", Siralama.YENI)) }

    var showAdd by remember { mutableStateOf(false) }
    var addInitial by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var showBulk by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.repo.notices.collect { notice = it } }
    var bulkInitial by remember { mutableStateOf("") }
    var pickPlaylistFor by remember { mutableStateOf<List<Track>?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Track>?>(null) }
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
        DropdownMenuItem(text = { Text("Listeye ekle") }, onClick = { close(); pickPlaylistFor = listOf(t) })
        DropdownMenuItem(text = { Text("Adını düzenle") }, onClick = { close(); editTrack = t })
        DropdownMenuItem(text = { Text("Paylaş") }, onClick = { close(); Paylas.dosyalar(ctx, t.title, listOf(vm.repo.fileOf(t))) })
        DropdownMenuItem(text = { Text("Sil") }, onClick = { close(); confirmDelete = listOf(t) })
    }

    Box(Modifier.fillMaxSize().background(arkaPlan())) {
      // Şeffaf zeminde yazı/simge rengi temaya göre olsun (koyu modda siyah kalmasın)
      CompositionLocalProvider(LocalContentColor provides c.onBackground) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = c.onBackground,
            floatingActionButton = {
                if (tab == 0) {
                    FloatingActionButton(
                        onClick = { addInitial = ""; showAdd = true },
                        shape = CircleShape,
                        containerColor = c.primary,
                        contentColor = c.onPrimary,
                        modifier = Modifier.neonCerceve(CircleShape),
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
                        onPlay = { list, i, shuffle -> vm.playTracks(list, i, shuffle) },
                        onDismissDownload = vm.repo::dismissDownload,
                        onRetryDownload = vm.repo::retryDownload,
                        onSelAddToPlaylist = { pickPlaylistFor = it },
                        onSelDelete = { confirmDelete = it },
                        fileOf = vm.repo::fileOf,
                        sira = libSort,
                        onSira = { libSort = it; vm.repo.setSiralama("kutuphane", it) },
                        menu = trackMenu,
                    )
                    tab == 1 && openPl != null -> PlaylistDetail(
                        openPl, tracks, player.currentId, coverOf,
                        onBack = { openPlaylistId = null },
                        onPlay = { list, i, shuffle -> vm.playTracks(list, i, shuffle) },
                        onRemove = { vm.repo.removeFromPlaylist(openPl.id, it.id) },
                        onRename = { vm.repo.renamePlaylist(openPl.id, it) },
                        onMove = { t, d -> vm.repo.moveInPlaylist(openPl.id, t.id, d) },
                        onAddTracks = { vm.repo.addTracksToPlaylist(openPl.id, it) },
                        onRemoveMany = { list -> list.forEach { vm.repo.removeFromPlaylist(openPl.id, it.id) } },
                        onSelAddToPlaylist = { pickPlaylistFor = it },
                        onSelDelete = { confirmDelete = it },
                        fileOf = vm.repo::fileOf,
                        customCover = vm.repo.playlistCover(openPl),
                        onCover = { vm.repo.setPlaylistCover(openPl.id, it) },
                        sira = vm.repo.siralama("liste_${openPl.id}", Siralama.LISTE),
                        onSira = { vm.repo.setSiralama("liste_${openPl.id}", it) },
                    )
                    tab == 1 -> PlaylistsScreen(
                        playlists, tracks, coverOf, vm.repo::playlistCover,
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
                        spotifyBagli = spotifyBagli,
                        spotifyClientId = com.dogukan.muzik.data.SpotifyApi.clientId(ctx),
                        onSpotifyClientId = { com.dogukan.muzik.data.SpotifyApi.setClientId(ctx, it) },
                        onSpotifyLogin = {
                            vm.repo.startSpotifyLogin()?.let { runCatching { ctx.startActivity(it) } }
                        },
                        onSpotifyLogout = { com.dogukan.muzik.data.SpotifyApi.logout(ctx) },
                        onImportLiked = { vm.repo.importLikedSongs(); tab = 0 },
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

    pickPlaylistFor?.let { list ->
        val ids = list.map { it.id }
        AddToPlaylistDialog(
            playlists,
            onDismiss = { pickPlaylistFor = null },
            onPick = { vm.repo.addTracksToPlaylist(it, ids); pickPlaylistFor = null; if (ids.size > 1) toast("${ids.size} şarkı listeye eklendi") },
            onCreate = { name -> vm.repo.addTracksToPlaylist(vm.repo.createPlaylist(name), ids); pickPlaylistFor = null },
        )
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (list.size == 1) "Şarkı silinsin mi?" else "${list.size} şarkı silinsin mi?") },
            text = {
                Text(
                    if (list.size == 1) "\"${list[0].title}\" telefondan silinecek ve tüm listelerden çıkarılacak."
                    else "Seçilen ${list.size} şarkı telefondan silinecek ve tüm listelerden çıkarılacak."
                )
            },
            confirmButton = { TextButton(onClick = { vm.deleteTracks(list); confirmDelete = null }) { Text("Sil") } },
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
    onPlay: (List<Track>, Int, Boolean?) -> Unit,
    onDismissDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onSelAddToPlaylist: (List<Track>) -> Unit,
    onSelDelete: (List<Track>) -> Unit,
    fileOf: (Track) -> File,
    sira: Siralama,
    onSira: (Siralama) -> Unit,
    menu: @Composable (Track, () -> Unit) -> Unit,
) {
    val listState = rememberLazyListState()
    val c = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    val filtered = remember(tracks, query, sira) {
        val q = query.trim().lowercase(TR)
        val sorted = tracks.sirala(sira)
        if (q.isEmpty()) sorted
        else sorted.filter { it.title.lowercase(TR).contains(q) || it.artist.lowercase(TR).contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                onClose = { selected = emptySet() },
                onSelectAll = {
                    val ids = filtered.map { it.id }.toSet()
                    selected = if (selected.containsAll(ids)) emptySet() else ids
                },
            ) {
                ShareMenu("Seçilen şarkılar", { tracks.filter { it.id in selected } }, fileOf)
                IconButton(onClick = { onSelAddToPlaylist(tracks.filter { it.id in selected }); selected = emptySet() }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Listeye ekle")
                }
                IconButton(onClick = { onSelDelete(tracks.filter { it.id in selected }); selected = emptySet() }) {
                    Icon(Icons.Default.Delete, "Sil", tint = c.error)
                }
            }
        } else {
            ScreenTitle("Kütüphane") {
                if (tracks.isNotEmpty()) ShareMenu("Kütüphanem", { filtered }, fileOf)
            }
        }
        // Şarkı sayısı (aramada kaç sonuç olduğu da görünür)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatChip(
                if (query.isBlank()) "${tracks.size} şarkı" else "${filtered.size} / ${tracks.size} şarkı",
                highlight = true,
            )
            val active = downloads.count { it.error == null }
            val failed = downloads.size - active
            if (active > 0) StatChip("$active indiriliyor")
            if (failed > 0) StatChip("$failed hata")
            Spacer(Modifier.weight(1f))
            SortMenu(sira, Siralama.entries.filter { it != Siralama.LISTE }, onSira)
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
                NButton(onClick = { onPlay(filtered, 0, false) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Tümünü çal")
                }
                NTonalButton(onClick = { onPlay(filtered, filtered.indices.random(), true) }) {
                    Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Karışık çal")
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
                TrackRow(t, coverOf(t), playing = t.id == currentId,
                    onClick = { if (selecting) toggle(t.id) else onPlay(filtered, i, null) },
                    selected = if (selecting) t.id in selected else null,
                    onLongClick = { toggle(t.id) },
                    menu = { close -> menu(t, close) })
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        val offset = downloads.size + if (filtered.isEmpty() && downloads.isEmpty()) 1 else 0
        FastScroller(listState, Modifier.align(Alignment.TopEnd)) { idx ->
            filtered.getOrNull(idx - offset)?.let { scrollLabel(it, sira, idx - offset) }
        }
        }
    }
}

/** Kaydırma baloncuğunda gösterilecek: harf, sanatçı harfi, tarih ya da sıra no. */
private fun scrollLabel(t: Track, sira: Siralama, index: Int): String = when (sira) {
    Siralama.AD_AZ, Siralama.AD_ZA -> siraAnahtari(t.title).take(1).uppercase(TR).ifBlank { "#" }
    Siralama.SANATCI -> siraAnahtari(t.artist).take(1).uppercase(TR).ifBlank { "?" }
    Siralama.YENI, Siralama.ESKI -> java.text.SimpleDateFormat("d MMM\nyyyy", TR).format(java.util.Date(t.addedAt))
    Siralama.LISTE -> "${index + 1}"
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
@OptIn(ExperimentalFoundationApi::class)
private fun TrackRow(
    t: Track,
    cover: File?,
    playing: Boolean,
    onClick: () -> Unit,
    selected: Boolean? = null, // null = seçim modu kapalı
    onLongClick: (() -> Unit)? = null,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = if (selected == true) ListItemDefaults.colors(containerColor = c.primary.copy(alpha = 0.14f)) else transparentRow(),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Kapak(cover, Modifier.size(52.dp), corner = 10.dp)
                if (selected == true) {
                    Box(Modifier.size(52.dp).background(c.primary.copy(alpha = 0.55f), RoundedCornerShape(10.dp)))
                    Icon(Icons.Default.CheckCircle, null, tint = c.onPrimary)
                } else if (playing) {
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
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                Box {
                    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Seçenekler") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        menu { expanded = false }
                    }
                }
            }
        },
    )
}

/** Çoklu seçim modunda başlık yerine gösterilen çubuk. */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Seçimi kapat") }
        Text("$count seçili", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "Tümünü seç") }
        actions()
    }
}

// ---------------- Çalma listeleri ----------------

@Composable
private fun PlaylistsScreen(
    playlists: List<Playlist>,
    library: List<Track>,
    coverOf: (Track) -> File?,
    playlistCoverOf: (Playlist) -> File?,
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Playlist?>(null) }
    val byId = remember(library) { library.associateBy { it.id } }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Listeler") {
            NTonalButton(onClick = { showNew = true }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Yeni")
            }
        }
        if (playlists.isEmpty()) EmptyState("Henüz çalma listesi yok.")
        LazyColumn {
            items(playlists, key = { it.id }) { p ->
                var expanded by remember { mutableStateOf(false) }
                val first = playlistCoverOf(p) ?: p.trackIds.firstNotNullOfOrNull { byId[it]?.let(coverOf) }
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
private fun SortMenu(current: Siralama, options: List<Siralama>, onPick: (Siralama) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(current.ad, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.ad, fontWeight = if (o == current) FontWeight.Bold else null) },
                    leadingIcon = { if (o == current) Icon(Icons.Default.Check, null) },
                    onClick = { open = false; onPick(o) },
                )
            }
        }
    }
}

@Composable
private fun StatChip(text: String, highlight: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .background(if (highlight) c.primary.copy(alpha = 0.18f) else c.surfaceVariant.copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (highlight) c.primary else c.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold)
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
    onRename: (String) -> Unit,
    onMove: (Track, Int) -> Unit,
    onAddTracks: (List<String>) -> Unit,
    onRemoveMany: (List<Track>) -> Unit,
    onSelAddToPlaylist: (List<Track>) -> Unit,
    onSelDelete: (List<Track>) -> Unit,
    fileOf: (Track) -> File,
    customCover: File?,
    onCover: (android.net.Uri?) -> Unit,
    sira: Siralama,
    onSira: (Siralama) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var editing by rememberSaveable { mutableStateOf(false) }
    var order by remember(playlist.id) { mutableStateOf(sira) }
    var selected by remember(playlist.id) { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    val byId = remember(library) { library.associateBy { it.id } }
    val manual = remember(playlist, byId) { playlist.trackIds.mapNotNull { byId[it] } }
    // Düzenlerken her zaman listenin kendi sırası gösterilir
    val songs = remember(manual, order, editing) { if (editing) manual else manual.sirala(order) }
    val headerCover = customCover ?: manual.firstNotNullOfOrNull(coverOf)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onCover(uri)
    }
    fun pickCover() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    var showRename by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onSelectAll = {
                        val ids = songs.map { it.id }.toSet()
                        selected = if (selected.containsAll(ids)) emptySet() else ids
                    },
                ) {
                    IconButton(onClick = { onRemoveMany(songs.filter { it.id in selected }); selected = emptySet() }) {
                        Icon(Icons.Default.RemoveCircleOutline, "Listeden çıkar")
                    }
                    ShareMenu(playlist.name, { songs.filter { it.id in selected } }, fileOf)
                    IconButton(onClick = { onSelAddToPlaylist(songs.filter { it.id in selected }); selected = emptySet() }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Başka listeye ekle")
                    }
                    IconButton(onClick = { onSelDelete(songs.filter { it.id in selected }); selected = emptySet() }) {
                        Icon(Icons.Default.Delete, "Telefondan sil", tint = c.error)
                    }
                }
            } else
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                Spacer(Modifier.weight(1f))
                if (editing) {
                    NTonalButton(onClick = { editing = false }) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text("Bitti")
                    }
                } else {
                    if (songs.isNotEmpty()) ShareMenu(playlist.name, { songs }, fileOf)
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Düzenle") }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Kapak(
                        headerCover,
                        Modifier.size(128.dp).shadow(16.dp, RoundedCornerShape(18.dp),
                            ambientColor = c.primary, spotColor = c.primary)
                            .neonCerceve(RoundedCornerShape(18.dp), glow = false)
                            .then(if (editing) Modifier.clickable { pickCover() } else Modifier),
                        corner = 18.dp,
                    )
                    if (editing) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(6.dp)
                        ) { Icon(Icons.Default.Image, "Kapağı değiştir", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (editing) {
                            IconButton(onClick = { showRename = true }) { Icon(Icons.Default.Edit, "Adını değiştir") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    StatChip("${songs.size} şarkı", highlight = true)
                    if (!editing && songs.size > 1) {
                        SortMenu(order, Siralama.entries) { order = it; onSira(it) }
                    }
                }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing) {
                    NButton(onClick = { showPicker = true }) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Şarkı ekle")
                    }
                    NOutlinedButton(onClick = { pickCover() }) {
                        Icon(Icons.Default.Image, null); Spacer(Modifier.width(4.dp)); Text("Kapak")
                    }
                } else {
                    NButton(enabled = songs.isNotEmpty(), onClick = { onPlay(songs, 0, false) }) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Çal")
                    }
                    NTonalButton(enabled = songs.isNotEmpty(), onClick = { onPlay(songs, songs.indices.random(), true) }) {
                        Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Karışık")
                    }
                    NOutlinedButton(onClick = { showPicker = true }) { Icon(Icons.Default.Add, "Şarkı ekle") }
                }
            }
            if (editing) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NOutlinedButton(onClick = { showRename = true }) { Text("Adını değiştir") }
                    if (customCover != null) NOutlinedButton(onClick = { onCover(null) }) { Text("Kapağı kaldır") }
                }
                if (order != Siralama.LISTE) {
                    Text("Düzenlerken listenin kendi sırası gösteriliyor.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
            }
            if (songs.isEmpty()) {
                EmptyState("Liste boş. \"Şarkı ekle\" ile kütüphaneden şarkı seç.")
            }
        }
        itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
            if (editing) {
                ListItem(
                    colors = transparentRow(),
                    leadingContent = { Kapak(coverOf(t), Modifier.size(44.dp), corner = 8.dp) },
                    headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (t.artist.isNotBlank()) {
                        { Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant) }
                    } else null,
                    trailingContent = {
                        Row {
                            IconButton(enabled = i > 0, onClick = { onMove(t, -1) }) {
                                Icon(Icons.Default.KeyboardArrowUp, "Yukarı")
                            }
                            IconButton(enabled = i < songs.lastIndex, onClick = { onMove(t, 1) }) {
                                Icon(Icons.Default.KeyboardArrowDown, "Aşağı")
                            }
                            IconButton(onClick = { onRemove(t) }) {
                                Icon(Icons.Default.RemoveCircleOutline, "Listeden çıkar", tint = c.error)
                            }
                        }
                    },
                )
            } else {
                TrackRow(
                    t, coverOf(t), t.id == currentId,
                    onClick = { if (selecting) toggle(t.id) else onPlay(songs, i, null) },
                    selected = if (selecting) t.id in selected else null,
                    onLongClick = { toggle(t.id) },
                ) { close ->
                    if (order == Siralama.LISTE) {
                        DropdownMenuItem(text = { Text("Yukarı taşı") }, onClick = { close(); onMove(t, -1) })
                        DropdownMenuItem(text = { Text("Aşağı taşı") }, onClick = { close(); onMove(t, 1) })
                    }
                    DropdownMenuItem(text = { Text("Listeden çıkar") }, onClick = { close(); onRemove(t) })
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (!editing) {
        FastScroller(listState, Modifier.align(Alignment.TopEnd)) { idx ->
            songs.getOrNull(idx - 1)?.let { scrollLabel(it, order, idx - 1) }
        }
    }
    }

    if (showRename) {
        var name by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Listenin adı") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onRename(name); showRename = false }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Vazgeç") } },
        )
    }

    if (showPicker) {
        TrackPickerDialog(
            library = library.filterNot { it.id in playlist.trackIds },
            coverOf = coverOf,
            onDismiss = { showPicker = false },
            onConfirm = { onAddTracks(it); showPicker = false },
        )
    }
}

/** Kütüphaneden birden çok şarkı seçip listeye ekleme. */
@Composable
private fun TrackPickerDialog(
    library: List<Track>,
    coverOf: (Track) -> File?,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val shown = remember(library, query) {
        val q = query.trim().lowercase(TR)
        if (q.isEmpty()) library
        else library.filter { it.title.lowercase(TR).contains(q) || it.artist.lowercase(TR).contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Şarkı ekle") },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Ara") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("${selected.size} seçili", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val ids = shown.map { it.id }
                        selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids
                    }) { Text("Görünenlerin hepsi") }
                }
                if (library.isEmpty()) {
                    Text("Kütüphanedeki tüm şarkılar zaten bu listede.", modifier = Modifier.padding(8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { t ->
                        val on = t.id in selected
                        ListItem(
                            colors = transparentRow(),
                            modifier = Modifier.clickable { selected = if (on) selected - t.id else selected + t.id },
                            leadingContent = { Kapak(coverOf(t), Modifier.size(40.dp), corner = 8.dp) },
                            headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (t.artist.isNotBlank()) {
                                { Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            } else null,
                            trailingContent = {
                                Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - t.id else selected + t.id })
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = {
                // kütüphane sırasını koru
                onConfirm(library.map { it.id }.filter { it in selected })
            }) { Text("Ekle (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
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
