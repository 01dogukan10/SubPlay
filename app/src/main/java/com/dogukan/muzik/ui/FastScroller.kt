package com.dogukan.muzik.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Sağ kenarda sürüklenebilir kaydırma çubuğu. Sürüklerken [label] ile o konumun
 * harfini/tarihini gösteren bir baloncuk çıkar. Kısa listelerde görünmez.
 */
@Composable
fun FastScroller(state: LazyListState, modifier: Modifier = Modifier, label: (Int) -> String? = { null }) {
    val total = state.layoutInfo.totalItemsCount
    if (total < 25) return
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val frac = if (dragging) dragFrac
    else (state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
    val alpha by animateFloatAsState(if (dragging || state.isScrollInProgress) 1f else 0.4f, label = "fs")
    val target = (dragFrac * (total - 1)).roundToInt()

    fun jump(f: Float) {
        dragFrac = f.coerceIn(0f, 1f)
        val idx = (dragFrac * (total - 1)).roundToInt()
        scope.launch { state.scrollToItem(idx) }
    }

    BoxWithConstraints(modifier.fillMaxHeight().width(22.dp)) {
        val thumbH = 52.dp
        val y = (maxHeight - thumbH) * frac
        Box(
            Modifier.fillMaxSize().pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = { o -> dragging = true; jump(o.y / size.height) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> change.consume(); jump(change.position.y / size.height) }
            }
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = y)
                    .padding(end = 4.dp)
                    .width(if (dragging) 8.dp else 5.dp)
                    .height(thumbH)
                    .alpha(alpha)
                    .background(if (LocalNeon.current) neonKenar() else androidx.compose.ui.graphics.SolidColor(c.primary), RoundedCornerShape(50))
            )
        }
        if (dragging) {
            label(target)?.let { text ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-44).dp, y = y)
                        .size(width = 72.dp, height = 52.dp)
                        .background(c.primary, RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 26.dp, bottomEnd = 4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, color = c.onPrimary, fontWeight = FontWeight.Bold,
                        style = if (text.length <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelMedium,
                        maxLines = 2)
                }
            }
        }
    }
}
