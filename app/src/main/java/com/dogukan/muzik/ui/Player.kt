package com.dogukan.muzik.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.AutoAwesome
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.dogukan.muzik.MainViewModel
import com.dogukan.muzik.PlayerUi

private fun PlayerUi.fraction() =
    if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

@Composable
fun MiniPlayer(ui: PlayerUi, vm: MainViewModel, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val neon = LocalNeon.current
    val shape = RoundedCornerShape(16.dp)
    // Kaydırma: sola -> sonraki, sağa -> önceki, yukarı -> büyük oynatıcı
    val esik = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    var kayX by remember { mutableFloatStateOf(0f) }
    val gorunenX by androidx.compose.animation.core.animateFloatAsState(kayX, label = "mini")
    Surface(
        color = if (neon) c.surface.copy(alpha = 0.92f) else c.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(if (neon) Modifier.border(1.dp, neonKenar(), shape) else Modifier)
            .clip(shape)
            .pointerInput(Unit) {
                var dx = 0f; var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        when {
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < -esik -> vm.next()
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx > esik -> vm.previous()
                            dy < -esik / 2 -> onOpen()
                        }
                        dx = 0f; dy = 0f; kayX = 0f
                    },
                    onDragCancel = { dx = 0f; dy = 0f; kayX = 0f },
                ) { ch, d ->
                    ch.consume(); dx += d.x; dy += d.y
                    kayX = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) dx * 0.6f else 0f
                }
            }
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.graphicsLayer { translationX = gorunenX; alpha = 1f - (kotlin.math.abs(gorunenX) / (esik * 4)).coerceAtMost(0.5f) }) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Kapak(ui.artwork, Modifier.size(46.dp), corner = 10.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ui.oneri) { OneriIsareti(16.dp); Spacer(Modifier.width(4.dp)) }
                        Text(ui.title.orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (ui.artist.isNotBlank()) {
                        Text(ui.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant)
                    }
                }
                IconButton(onClick = vm::togglePlay, modifier = Modifier.neonCerceve(CircleShape, glow = false)) {
                    Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (ui.isPlaying) "Duraklat" else "Çal", modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, "Sonraki") }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.onSurface.copy(alpha = 0.08f))) {
                Box(
                    Modifier.fillMaxWidth(ui.fraction()).height(2.dp)
                        .background(if (neon) neonKenar() else Brush.horizontalGradient(listOf(c.primary, c.primary)))
                )
            }
        }
    }
}

@Composable
fun FullPlayer(ui: PlayerUi, vm: MainViewModel, onClose: () -> Unit, onOpenQueue: () -> Unit = {}, ekstra: OynaticiEkstra = OynaticiEkstra()) =
    androidx.compose.runtime.CompositionLocalProvider(LocalAkilli provides ui.akilli) { FullPlayerIc(ui, vm, onClose, onOpenQueue, ekstra) }

@Composable
private fun FullPlayerIc(ui: PlayerUi, vm: MainViewModel, onClose: () -> Unit, onOpenQueue: () -> Unit, ekstra: OynaticiEkstra) {
    val c = MaterialTheme.colorScheme
    val neon = LocalNeon.current
    var swipe by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (swipe > 180f) onClose(); swipe = 0f },
                    onDragCancel = { swipe = 0f },
                ) { _, dy -> swipe = (swipe + dy).coerceAtLeast(0f) }
            },
    ) {
        // Kapaktan renk: arka plan geçişi çalan şarkının kapağının renginde olur
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var kapakRengi by remember { mutableStateOf<Color?>(null) }
        LaunchedEffect(ui.artwork) {
            kapakRengi = ui.artwork?.let { uri ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val req = coil.request.ImageRequest.Builder(ctx).data(uri).allowHardware(false).size(96).build()
                        val bmp = (coil.Coil.imageLoader(ctx).execute(req) as? coil.request.SuccessResult)?.drawable
                            ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                        bmp?.let { b ->
                            val p = androidx.palette.graphics.Palette.from(b).generate()
                            val r = p.getVibrantColor(p.getMutedColor(p.getDominantColor(0)))
                            if (r != 0) Color(r) else null
                        }
                    }.getOrNull()
                }
            }
        }
        val anaRenk by androidx.compose.animation.animateColorAsState(kapakRengi ?: c.primary, label = "kapakRengi")
        // Arka plan: bulanık kapak (Android 12+) + renk geçişi
        if (ui.artwork != null && Build.VERSION.SDK_INT >= 31) {
            AsyncImage(
                model = ui.artwork, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(70.dp).alpha(if (neon) 0.55f else 0.4f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        anaRenk.copy(alpha = if (neon) 0.45f else if (kapakRengi != null) 0.38f else 0.20f),
                        Color.Transparent,
                        c.background.copy(alpha = 0.85f),
                        c.background,
                    )
                )
            )
        )

        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides c.onBackground,
        ) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val wide = maxWidth > maxHeight
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.KeyboardArrowDown, "Kapat", modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("ŞİMDİ ÇALIYOR", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp,
                        color = c.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    UykuDugmesi(ekstra, ui.podcast)
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, "Sıra")
                    }
                }
                if (wide) {
                    // Yatay: kapak solda, kontroller sağda
                    Row(
                        Modifier.fillMaxSize().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            CoverArt(ui, neon, vm)
                        }
                        Column(Modifier.weight(1.3f)) { InfoAndControls(ui, vm, ekstra, kutu = false) }
                    }
                } else {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CoverArt(ui, neon, vm) }
                    InfoAndControls(ui, vm, ekstra)
                    Spacer(Modifier.height(36.dp))
                }
            }
        }
        }
    }
}

/** Ekrana sığacak en büyük kare kapak. Sola / sağa kaydırınca şarkı değişir. */
@Composable
private fun CoverArt(ui: PlayerUi, neon: Boolean, vm: MainViewModel? = null) {
    val c = MaterialTheme.colorScheme
    val coverShape = RoundedCornerShape(24.dp)
    val esik = with(androidx.compose.ui.platform.LocalDensity.current) { 90.dp.toPx() }
    var kayX by remember { mutableFloatStateOf(0f) }
    val gorunenX by androidx.compose.animation.core.animateFloatAsState(kayX, label = "kapak")
    Kapak(
        ui.artwork,
        Modifier
            .then(if (vm == null || ui.podcast) Modifier else Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kayX < -esik) vm.next() else if (kayX > esik) vm.previous()
                        kayX = 0f
                    },
                    onDragCancel = { kayX = 0f },
                ) { ch, d -> ch.consume(); kayX += d }
            })
            .graphicsLayer {
                translationX = gorunenX * 0.7f
                rotationZ = gorunenX / 60f
                alpha = 1f - (kotlin.math.abs(gorunenX) / (esik * 4)).coerceAtMost(0.4f)
            }
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .shadow(
                elevation = if (neon) 36.dp else 18.dp, shape = coverShape,
                ambientColor = if (neon) c.primary else Color.Black,
                spotColor = if (neon) c.primary else Color.Black,
            )
            .then(if (neon) Modifier.border(1.5.dp, neonKenar(), coverShape) else Modifier),
        corner = 24.dp,
    )
}

@Composable
private fun InfoAndControls(ui: PlayerUi, vm: MainViewModel, ekstra: OynaticiEkstra, kutu: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val neon = LocalNeon.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    if (ui.oneri) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            OneriIsareti(16.dp)
            Spacer(Modifier.width(6.dp))
            Text("Akıllı karıştırma önerisi", style = MaterialTheme.typography.labelMedium, color = c.primary)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ui.title.orEmpty(), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (ui.artist.isNotBlank()) {
                Text(ui.artist, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = ekstra.onArtist))
            }
        }
        // ♥ Beğen (podcast bölümlerinde yok)
        if (!ui.podcast) IconButton(onClick = ekstra.onLike) {
            Icon(
                if (ekstra.liked) Icons.Default.Favorite
                else Icons.Default.FavoriteBorder,
                if (ekstra.liked) "Beğenmekten vazgeç" else "Beğen",
                tint = if (ekstra.liked) c.primary else c.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    Slider(
        value = if (dragging) dragValue else ui.fraction(),
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = { vm.seekTo((dragValue * ui.duration).toLong()); dragging = false },
        enabled = ui.duration > 0,
        colors = SliderDefaults.colors(
            thumbColor = c.primary, activeTrackColor = if (neon) c.tertiary else c.primary,
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val shown = if (dragging) (dragValue * ui.duration).toLong() else ui.position
        Text(fmt(shown), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        Text(fmt(ui.duration), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
    }

    Spacer(Modifier.height(12.dp))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val off = c.onSurfaceVariant
        if (ui.podcast) {
            // Podcast: hız, 15 sn geri, çal, 30 sn ileri
            HizDugmesi(ui.hiz, vm::hizAyarla)
            AtlaDugmesi(-15) { vm.atla(-15_000) }
        } else {
        IconButton(onClick = vm::toggleShuffle) { KarisikIkon(ui.shuffle) }
        IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, "Önceki", modifier = Modifier.size(40.dp))
        }
        }
        FilledIconButton(
            onClick = vm::togglePlay,
            modifier = Modifier
                .size(76.dp)
                .neonCerceve(CircleShape),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary),
        ) {
            Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (ui.isPlaying) "Duraklat" else "Çal", modifier = Modifier.size(40.dp))
        }
        if (ui.podcast) {
            AtlaDugmesi(30) { vm.atla(30_000) }
            Spacer(Modifier.size(48.dp))
        } else {
        IconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, "Sonraki", modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = vm::cycleRepeat) {
            Icon(
                if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                "Tekrar", tint = if (ui.repeat == Player.REPEAT_MODE_OFF) off else c.primary,
            )
        }
        }
    }

    // Şarkı sözleri: küçük kutuda kendiliğinden görünür; dokununca tam ekran açılır
    if (ui.podcast) {
        // Podcast: söz yok
    } else if (kutu) {
        Spacer(Modifier.height(12.dp))
        SozKutusu(ui, vm, ekstra.onLyrics)
    } else {
        // Yatay ekranda yer dar: sözler için düğme
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            androidx.compose.material3.TextButton(onClick = ekstra.onLyrics) {
                Icon(Icons.Default.Lyrics, null)
                Spacer(Modifier.width(6.dp))
                Text("Şarkı sözleri")
            }
        }
    }
}

/** Tam ekran oynatıcıya dışarıdan verilenler: beğeni, sözler, uyku zamanlayıcısı. */
class OynaticiEkstra(
    val liked: Boolean = false,
    val onLike: () -> Unit = {},
    val onLyrics: () -> Unit = {},
    val uyku: Long? = null,
    val onSleep: (Int) -> Unit = {},
    val onArtist: () -> Unit = {},
)

/** ⏾ Uyku zamanlayıcısı: süre seç; açıkken kalan süre görünür. */
@Composable
private fun UykuDugmesi(e: OynaticiEkstra, podcast: Boolean = false) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        if (e.uyku != null) {
            val kalan = if (e.uyku == -1L) (if (podcast) "bölüm sonu" else "şarkı sonu") else "${((e.uyku - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)} dk"
            androidx.compose.material3.TextButton(onClick = { open = true }) {
                Icon(Icons.Default.Bedtime, null, tint = c.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(kalan, color = c.primary, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Default.Bedtime, "Uyku zamanlayıcısı")
            }
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            Text("Uyku zamanlayıcısı", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            listOf(5, 15, 30, 45, 60, 90).forEach { dk ->
                androidx.compose.material3.DropdownMenuItem(text = { Text("$dk dakika") }, onClick = { open = false; e.onSleep(dk) })
            }
            androidx.compose.material3.DropdownMenuItem(text = { Text(if (podcast) "Bu bölüm bitince" else "Bu şarkı bitince") }, onClick = { open = false; e.onSleep(-1) })
            if (e.uyku != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Kapat", color = c.error) }, onClick = { open = false; e.onSleep(0) })
            }
        }
    }
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/** Akıllı karıştırmadan gelen şarkının simgesi (✦). */
@Composable
fun OneriIsareti(boyut: androidx.compose.ui.unit.Dp = 16.dp) {
    Icon(Icons.Default.AutoAwesome, "Akıllı karıştırma önerisi",
        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(boyut))
}
