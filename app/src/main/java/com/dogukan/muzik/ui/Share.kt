package com.dogukan.muzik.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.dogukan.muzik.data.Track
import java.io.File

object Paylas {

    /** "Sanatçı - Şarkı" satırları; alan kişi SubMüzik'te Toplu ekle'ye yapıştırabilir. */
    fun metin(ctx: Context, baslik: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val text = buildString {
            append(baslik).append(" (").append(tracks.size).append(" şarkı)\n\n")
            tracks.forEach { append(if (it.artist.isBlank()) it.title else "${it.artist} - ${it.title}").append('\n') }
        }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, baslik)
            .putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(i, "Listeyi paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Şarkı dosyalarının kendisini gönderir (Quick Share, WhatsApp, Bluetooth…). */
    fun dosyalar(ctx: Context, baslik: String, files: List<File>) {
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) {
            Toast.makeText(ctx, "Paylaşılacak dosya yok", Toast.LENGTH_SHORT).show(); return
        }
        val uris = existing.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) }
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        i.setType("audio/*").putExtra(Intent.EXTRA_SUBJECT, baslik).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Paylaşım penceresinden seçilen uygulamaya okuma izni geçsin
        val clip = ClipData.newRawUri(baslik, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        i.clipData = clip
        ctx.startActivity(Intent.createChooser(i, "Şarkıları gönder").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun boyut(files: List<File>): String {
        val mb = files.sumOf { it.length() } / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
    }
}

/** Paylaş simgesi + menü: liste olarak (metin) ya da dosya olarak. */
@Composable
fun ShareMenu(baslik: String, tracks: () -> List<Track>, fileOf: (Track) -> File) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Share, "Paylaş") }
        DropdownMenu(open, { open = false }) {
            val list = if (open) tracks() else emptyList()
            DropdownMenuItem(
                text = { Text("Liste olarak paylaş (${list.size} şarkı)") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                onClick = { open = false; Paylas.metin(ctx, baslik, list) },
            )
            val files = remember(list) { list.map(fileOf) }
            DropdownMenuItem(
                text = { Text("Dosyaları gönder (${Paylas.boyut(files)})") },
                leadingIcon = { Icon(Icons.Default.AudioFile, null) },
                onClick = { open = false; Paylas.dosyalar(ctx, baslik, files) },
            )
        }
    }
}
