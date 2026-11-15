package com.dogukan.muzik.player

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Kapak resimlerini telefonun medya bildirimi, kilit ekranı, Bluetooth ve Android Auto
 * okuyabilsin diye sadece okunur olarak paylaşır. Yalnızca kapak klasöründeki dosyalar verilir.
 */
class KapakSaglayici : ContentProvider() {
    companion object {
        fun yetki(c: Context) = "${c.packageName}.kapak"
        fun uri(c: Context, dosya: File): Uri = Uri.parse("content://${yetki(c)}/${Uri.encode(dosya.name)}")
        /** content://...kapak/<ad> ya da file:// adresinden dosya yolu (widget için). */
        fun dosyaYolu(c: Context, u: Uri?): String? = when (u?.scheme) {
            "file" -> u.path
            "content" -> if (u.authority == yetki(c)) u.lastPathSegment?.let { kapakDosyasi(c, it)?.path } else null
            else -> null
        }
        private fun kapakDosyasi(c: Context, ad: String): File? {
            if (ad.contains('/') || ad.contains("..") || ad.isBlank()) return null
            return File(File(c.filesDir, "covers"), ad).takeIf { it.isFile }
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Sadece okunur")
        val c = context ?: throw FileNotFoundException()
        val f = uri.lastPathSegment?.let { kapakDosyasi(c, it) } ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
