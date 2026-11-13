package com.dogukan.muzik.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    Surface(
        color = if (neon) c.surface.copy(alpha = 0.92f) else c.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(if (neon) Modifier.border(1.dp, neonKenar(), shape) else Modifier)
            .clip(shape)
            .clickable(onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Kapak(ui.artwork, Modifier.size(46.dp), corner = 10.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(ui.title.orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ui.artist.isNotBlank()) {
                        Text(ui.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant)
                    }
                }
                IconButton(onClick = vm::togglePlay) {
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
fun FullPlayer(ui: PlayerUi, vm: MainViewModel, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val neon = LocalNeon.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
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
                        c.primary.copy(alpha = if (neon) 0.40f else 0.20f),
                        Color.Transparent,
                        c.background.copy(alpha = 0.85f),
                        c.background,
                    )
                )
            )
        )

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, "Kapat", modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("ŞİMDİ ÇALIYOR", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp,
                    color = c.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            Spacer(Modifier.weight(1f))

            val coverShape = RoundedCornerShape(24.dp)
            Kapak(
                ui.artwork,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(
                        elevation = if (neon) 36.dp else 18.dp, shape = coverShape,
                        ambientColor = if (neon) c.primary else Color.Black,
                        spotColor = if (neon) c.primary else Color.Black,
                    )
                    .then(if (neon) Modifier.border(1.5.dp, neonKenar(), coverShape) else Modifier),
                corner = 24.dp,
            )

            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth()) {
                Text(ui.title.orEmpty(), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ui.artist.isNotBlank()) {
                    Text(ui.artist, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Karışık", tint = if (ui.shuffle) c.primary else off)
                }
                IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Önceki", modifier = Modifier.size(40.dp))
                }
                FilledIconButton(
                    onClick = vm::togglePlay,
                    modifier = Modifier
                        .size(76.dp)
                        .then(if (neon) Modifier.shadow(20.dp, CircleShape, ambientColor = c.primary, spotColor = c.primary) else Modifier),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary),
                ) {
                    Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (ui.isPlaying) "Duraklat" else "Çal", modifier = Modifier.size(40.dp))
                }
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
            Spacer(Modifier.height(36.dp))
        }
    }
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
