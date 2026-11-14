package com.dogukan.muzik.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Mod(val ad: String) { SISTEM("Sistem"), KOYU("Koyu"), ACIK("Açık") }

data class Vurgu(val ad: String, val renk: Color)

val VURGULAR = listOf(
    Vurgu("Yeşil", Color(0xFF1DB954)),
    Vurgu("Mor", Color(0xFF8B5CF6)),
    Vurgu("Turuncu", Color(0xFFFF7A1A)),
    Vurgu("Mavi", Color(0xFF3B82F6)),
    Vurgu("Pembe", Color(0xFFEC4899)),
    Vurgu("Kırmızı", Color(0xFFEF4444)),
    Vurgu("Turkuaz", Color(0xFF14B8A6)),
    Vurgu("Sarı", Color(0xFFFACC15)),
)

/** vurgu = -1 -> duvar kağıdı renkleri (Material You, Android 12+). */
data class TemaAyar(val mod: Mod = Mod.KOYU, val vurgu: Int = 0, val neon: Boolean = false)

val duvarKagidiDestekli: Boolean get() = Build.VERSION.SDK_INT >= 31

class TemaDeposu private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tema", Context.MODE_PRIVATE)
    private val _ayar = MutableStateFlow(
        TemaAyar(
            mod = runCatching { Mod.valueOf(prefs.getString("mod", Mod.KOYU.name)!!) }.getOrDefault(Mod.KOYU),
            vurgu = prefs.getInt("vurgu", 0),
            neon = prefs.getBoolean("neon", false),
        )
    )
    val ayar = _ayar.asStateFlow()

    fun kaydet(a: TemaAyar) {
        _ayar.value = a
        prefs.edit().putString("mod", a.mod.name).putInt("vurgu", a.vurgu).putBoolean("neon", a.neon).apply()
    }

    companion object {
        @Volatile private var inst: TemaDeposu? = null
        fun get(c: Context) = inst ?: synchronized(this) {
            inst ?: TemaDeposu(c.applicationContext).also { inst = it }
        }
    }
}

val LocalNeon = staticCompositionLocalOf { false }

@Composable
fun SubPlayTema(ayar: TemaAyar, content: @Composable () -> Unit) {
    val dark = when (ayar.mod) {
        Mod.SISTEM -> isSystemInDarkTheme()
        Mod.KOYU -> true
        Mod.ACIK -> false
    }
    val ctx = LocalContext.current
    val scheme = if (ayar.vurgu < 0 && duvarKagidiDestekli) {
        val d = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        if (dark && ayar.neon) d.copy(background = Color(0xFF0A0718), surface = Color(0xFF120D26)) else d
    } else {
        ozelRenkler(VURGULAR[ayar.vurgu.coerceIn(0, VURGULAR.lastIndex)].renk, dark, ayar.neon)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalNeon provides ayar.neon, content = content)
    }
}

/** Vurgu renginin tonunu kaydırarak neon geçişler için ikinci bir renk üretir. */
private fun esRenk(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + 70f) % 360f
    hsv[1] = hsv[1].coerceAtLeast(0.6f)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun ozelRenkler(accent: Color, dark: Boolean, neon: Boolean): ColorScheme {
    val onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White
    val tert = esRenk(accent)
    return if (dark) {
        val bg = if (neon) Color(0xFF0A0718) else Color(0xFF0D0D10)
        val sf = if (neon) Color(0xFF120D26) else Color(0xFF151518)
        val sv = if (neon) Color(0xFF231B44) else Color(0xFF26262C)
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.28f).compositeOver(sf), onPrimaryContainer = Color.White,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = accent.copy(alpha = 0.20f).compositeOver(sf), onSecondaryContainer = Color.White,
            tertiary = tert, onTertiary = Color.Black,
            background = bg, onBackground = Color(0xFFEDEDF2),
            surface = sf, onSurface = Color(0xFFEDEDF2),
            surfaceVariant = sv, onSurfaceVariant = Color(0xFFA6A6B3),
            surfaceContainerLowest = bg, surfaceContainerLow = sf,
            surfaceContainer = sf.copy(alpha = 1f).compositeOver(bg),
            surfaceContainerHigh = sv.copy(alpha = 0.7f).compositeOver(sf),
            surfaceContainerHighest = sv,
            surfaceTint = accent,
            outline = Color(0xFF45454F), outlineVariant = Color(0xFF2E2E36),
        )
    } else {
        val strong = accent.copy(alpha = 1f).let { if (it.luminance() > 0.5f) Color(0xFF5B4A00).copy(alpha = 0.35f).compositeOver(it) else it }
        lightColorScheme(
            primary = strong, onPrimary = if (strong.luminance() > 0.45f) Color.Black else Color.White,
            primaryContainer = accent.copy(alpha = 0.18f).compositeOver(Color.White), onPrimaryContainer = Color(0xFF15151A),
            secondary = strong,
            secondaryContainer = accent.copy(alpha = 0.16f).compositeOver(Color.White), onSecondaryContainer = Color(0xFF15151A),
            tertiary = tert,
            background = Color(0xFFF6F6F9), onBackground = Color(0xFF15151A),
            surface = Color.White, onSurface = Color(0xFF15151A),
            surfaceVariant = Color(0xFFEBEBF0), onSurfaceVariant = Color(0xFF5E5E6A),
            surfaceContainer = Color(0xFFF0F0F4), surfaceContainerHigh = Color(0xFFEAEAEF),
            surfaceContainerHighest = Color(0xFFE3E3E9), surfaceContainerLow = Color(0xFFF7F7FA),
            surfaceContainerLowest = Color.White,
            surfaceTint = strong,
        )
    }
}

/** Ekran arka planı: neon açıksa renkli geçiş, değilse düz renk. */
@Composable
fun arkaPlan(): Brush {
    val c = MaterialTheme.colorScheme
    return if (LocalNeon.current) {
        Brush.verticalGradient(
            listOf(
                c.primary.copy(alpha = 0.24f).compositeOver(c.background),
                c.background,
                c.background,
                c.tertiary.copy(alpha = 0.14f).compositeOver(c.background),
            )
        )
    } else SolidColor(c.background)
}

@Composable
fun neonKenar(): Brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))

/** Kapak resmi; yoksa nota simgesi. model: File, Uri ya da null. */
@Composable
fun Kapak(model: Any?, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(
            Brush.linearGradient(listOf(c.primaryContainer, c.surfaceVariant))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = c.onPrimaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxSize(0.45f))
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
    }
}


/** Neon modunda kenarına renkli çizgi + ışıma ekler. */
@Composable
fun Modifier.neonCerceve(shape: Shape = CircleShape, glow: Boolean = true): Modifier {
    if (!LocalNeon.current) return this
    val c = MaterialTheme.colorScheme
    return this
        .then(if (glow) Modifier.shadow(10.dp, shape, ambientColor = c.primary, spotColor = c.primary) else Modifier)
        .border(1.5.dp, neonKenar(), shape)
}

@Composable
fun NButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier.neonCerceve(glow = enabled), enabled = enabled, content = content)

@Composable
fun NTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(onClick, modifier.neonCerceve(glow = false), enabled = enabled, content = content)

@Composable
fun NOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick, modifier, enabled = enabled,
    border = if (LocalNeon.current) androidx.compose.foundation.BorderStroke(1.5.dp, neonKenar())
    else ButtonDefaults.outlinedButtonBorder(enabled),
    content = content,
)
