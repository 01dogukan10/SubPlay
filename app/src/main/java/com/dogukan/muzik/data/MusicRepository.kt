package com.dogukan.muzik.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val fileName: String,
    val sourceUrl: String,
    val addedAt: Long,
)

data class Playlist(val id: String, val name: String, val trackIds: List<String>)

/** progress == null -> boyut bilinmiyor (belirsiz çubuk). error != null -> başarısız. */
data class DownloadTask(
    val id: String,
    val url: String,
    val name: String,
    val progress: Float? = null,
    val error: String? = null,
)

class MusicRepository private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun get(c: Context): MusicRepository =
            instance ?: synchronized(this) {
                instance ?: MusicRepository(c.applicationContext).also { instance = it }
            }
    }

    val musicDir: File =
        (ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "music"))
            .apply { mkdirs() }

    private val libFile = File(ctx.filesDir, "library.json")
    private val plFile = File(ctx.filesDir, "playlists.json")
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tracks = MutableStateFlow(loadTracks())
    val tracks = _tracks.asStateFlow()

    private val _playlists = MutableStateFlow(loadPlaylists())
    val playlists = _playlists.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads = _downloads.asStateFlow()

    fun fileOf(t: Track) = File(musicDir, t.fileName)

    // ---------------- İndirme ----------------

    fun startDownload(url: String, customTitle: String?) {
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(id, url.trim(), url.trim().substringAfterLast('/').take(60))
        synchronized(lock) { _downloads.value = _downloads.value + task }
        scope.launch {
            try {
                download(id, url.trim(), customTitle)
                synchronized(lock) { _downloads.value = _downloads.value.filterNot { it.id == id } }
            } catch (e: Exception) {
                updateTask(id) { it.copy(error = e.message ?: "Bilinmeyen hata") }
            }
        }
    }

    fun dismissDownload(id: String) = synchronized(lock) {
        _downloads.value = _downloads.value.filterNot { it.id == id }
    }

    private fun updateTask(id: String, f: (DownloadTask) -> DownloadTask) = synchronized(lock) {
        _downloads.value = _downloads.value.map { if (it.id == id) f(it) else it }
    }

    private fun download(taskId: String, rawUrl: String, customTitle: String?) {
        var url = try { URL(rawUrl) } catch (e: Exception) { throw IOException("Geçersiz link") }
        lateinit var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Muzik/1.0")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("Yönlendirme adresi yok")
                conn.disconnect()
                url = URL(url, loc)
                if (++redirects > 8) throw IOException("Çok fazla yönlendirme")
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("Sunucu hatası: HTTP $code")
            }
            break
        }

        val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.startsWith("text/") || type.contains("json")) {
            conn.disconnect()
            throw IOException("Bu link ses dosyası değil, web sayfası döndü. Doğrudan .mp3/.m4a gibi bir dosya linki gerekli.")
        }

        val baseName = fileNameFrom(conn, url)
        val dot = baseName.lastIndexOf('.')
        val hasExt = dot > 0 && baseName.length - dot in 2..5
        val stem = sanitize(if (hasExt) baseName.substring(0, dot) else baseName).ifBlank { "sarki_${System.currentTimeMillis()}" }
        val ext = if (hasExt) baseName.substring(dot + 1).lowercase() else extFromType(type)
        val target = uniqueFile(stem, ext)
        val tmp = File(musicDir, target.name + ".part")
        updateTask(taskId) { it.copy(name = target.name) }

        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastEmit > total / 100) {
                            lastEmit = done
                            updateTask(taskId) { it.copy(progress = done.toFloat() / total) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IOException("İndirme yarıda kesildi: ${e.message}")
        } finally {
            conn.disconnect()
        }

        if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }

        val (tagTitle, tagArtist) = readTags(target)
        val track = Track(
            id = UUID.randomUUID().toString(),
            title = customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: tagTitle ?: stem,
            artist = tagArtist.orEmpty(),
            fileName = target.name,
            sourceUrl = rawUrl,
            addedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            _tracks.value = listOf(track) + _tracks.value
            saveTracks()
        }
    }

    private fun fileNameFrom(conn: HttpURLConnection, url: URL): String {
        conn.getHeaderField("Content-Disposition")?.let { cd ->
            Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8") }.getOrDefault(it.groupValues[1])
            }
            Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        }
        val last = url.path.substringAfterLast('/')
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(120)

    private fun extFromType(type: String) = when {
        type.contains("mpeg") || type.contains("mp3") -> "mp3"
        type.contains("mp4") || type.contains("aac") || type.contains("m4a") -> "m4a"
        type.contains("ogg") -> "ogg"
        type.contains("opus") -> "opus"
        type.contains("flac") -> "flac"
        type.contains("wav") -> "wav"
        type.contains("webm") -> "webm"
        else -> "mp3"
    }

    private fun uniqueFile(stem: String, ext: String): File {
        var f = File(musicDir, "$stem.$ext")
        var i = 2
        while (f.exists()) { f = File(musicDir, "$stem ($i).$ext"); i++ }
        return f
    }

    private fun readTags(f: File): Pair<String?, String?> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } to
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null to null
        } finally {
            runCatching { r.release() }
        }
    }

    // ---------------- Kütüphane ----------------

    fun deleteTrack(t: Track) = synchronized(lock) {
        fileOf(t).delete()
        _tracks.value = _tracks.value.filterNot { it.id == t.id }
        _playlists.value = _playlists.value.map { p -> p.copy(trackIds = p.trackIds - t.id) }
        saveTracks(); savePlaylists()
    }

    fun renameTrack(id: String, title: String, artist: String) = synchronized(lock) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(title = title, artist = artist) else it }
        saveTracks()
    }

    // ---------------- Çalma listeleri ----------------

    fun createPlaylist(name: String): String = synchronized(lock) {
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), emptyList())
        _playlists.value = _playlists.value + p
        savePlaylists()
        p.id
    }

    fun deletePlaylist(id: String) = synchronized(lock) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        savePlaylists()
    }

    fun addToPlaylist(playlistId: String, trackId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId && trackId !in it.trackIds) it.copy(trackIds = it.trackIds + trackId) else it
        }
        savePlaylists()
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(trackIds = it.trackIds - trackId) else it
        }
        savePlaylists()
    }

    // ---------------- Kayıt (JSON) ----------------

    private fun loadTracks(): List<Track> = runCatching {
        val arr = JSONArray(libFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Track(o.getString("id"), o.getString("title"), o.optString("artist"),
                o.getString("fileName"), o.optString("sourceUrl"), o.optLong("addedAt"))
        }.filter { File(musicDir, it.fileName).exists() }
    }.getOrDefault(emptyList())

    private fun saveTracks() {
        val arr = JSONArray()
        _tracks.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("fileName", it.fileName).put("sourceUrl", it.sourceUrl).put("addedAt", it.addedAt))
        }
        libFile.writeText(arr.toString())
    }

    private fun loadPlaylists(): List<Playlist> = runCatching {
        val arr = JSONArray(plFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("trackIds")
            Playlist(o.getString("id"), o.getString("name"), (0 until ids.length()).map { ids.getString(it) })
        }
    }.getOrDefault(emptyList())

    private fun savePlaylists() {
        val arr = JSONArray()
        _playlists.value.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("trackIds", JSONArray(p.trackIds)))
        }
        plFile.writeText(arr.toString())
    }
}
