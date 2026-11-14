package com.dogukan.muzik.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.dogukan.muzik.download.DownloadService

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val fileName: String,
    val sourceUrl: String,
    val addedAt: Long,
    val cover: String? = null, // covers/ klasöründeki dosya adı
)

data class Playlist(val id: String, val name: String, val trackIds: List<String>)

data class BulkResult(val found: Int, val added: Int)

data class SearchResult(
    val id: String,
    val title: String,
    val channel: String,
    val duration: Long,
    val url: String,
)

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

    val coversDir: File = File(ctx.filesDir, "covers").apply { mkdirs() }
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

    /** Kullanıcıya gösterilecek bilgi mesajları (ekranda pencere olarak çıkar). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    private val trLocale = java.util.Locale("tr")

    /** Karşılaştırma için sadeleştir: küçük harf, parantez içi ve "official/lyrics" gibi ekler atılır. */
    private fun norm(s: String): String = s.lowercase(trLocale)
        .replace(Regex("[(\\[].*?[)\\]]"), " ")
        .replace(Regex("\\b(official|video|audio|lyrics?|lyric video|music video|hd|4k|remastered|klip|sözleri)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun ytId(u: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(u)?.groupValues?.get(1)

    /** Bu kaynak / şarkı daha önce indirildiyse onu döndürür. */
    private fun findExisting(source: String, title: String?, artist: String?): Track? {
        val list = _tracks.value
        list.find { it.sourceUrl == source }?.let { return it }
        ytId(source)?.let { id -> list.find { ytId(it.sourceUrl) == id }?.let { return it } }
        val t = title?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return null
        val a = artist?.let(::norm).orEmpty()
        return list.find { tr ->
            norm(tr.title) == t && (a.isEmpty() || norm(tr.artist).let { it == a || it.contains(a) || a.contains(it) && it.isNotEmpty() })
        }
    }

    /** Hata veren indirmeleri yeniden başlatmak için. */
    private val retries = ConcurrentHashMap<String, () -> Unit>()

    /** Aynı kaynak inerken ikinci kez istenirse, bitince eklenecek listeler. */
    private val pendingPlaylists = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        SpotifyApi.init(ctx)
        // Önceki oturumdan kalan yarım dosyaları ve kurulmuş güncelleme APK'sını temizle
        scope.launch {
            musicDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            ctx.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("yt_") }
                ?.forEach { it.deleteRecursively() }
            File(ctx.cacheDir, "updates").deleteRecursively()
        }
    }

    fun fileOf(t: Track) = File(musicDir, t.fileName)
    fun coverOf(t: Track): File? = t.cover?.let { File(coversDir, it) }?.takeIf { it.exists() }

    /** Kapak resmini indirip kaydeder, dosya adını döndürür (başarısızsa null). */
    private fun saveCover(trackId: String, url: String?): String? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SubPlay")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val name = "$trackId.img"
                conn.inputStream.use { i -> File(coversDir, name).outputStream().use { o -> i.copyTo(o) } }
                name
            } finally { conn.disconnect() }
        }.getOrNull()
    }

    private fun saveEmbeddedCover(trackId: String, f: File): String? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val pic = r.embeddedPicture ?: return null
            val name = "$trackId.img"
            File(coversDir, name).writeBytes(pic)
            name
        } finally { runCatching { r.release() } }
    }.getOrNull()

    // ---------------- İndirme ----------------

    /** Link türüne göre yönlendir: YouTube, Spotify ya da doğrudan dosya. */
    fun startDownload(input: String, customTitle: String?) {
        val url = input.trim()
        when {
            isYouTube(url) && isYtPlaylist(url) -> expandYtPlaylist(url)
            isYouTube(url) -> enqueueYt(url, customTitle, null, null, url)
            isSpotify(url) -> expandSpotify(url)
            else -> startDirect(url, customTitle)
        }
    }

    fun downloadSearchResult(r: SearchResult) = enqueueYt(r.url, null, null, null, r.title)

    /**
     * Toplu ekleme: her satır bir şarkı ("Sanatçı - Şarkı" ya da sadece ad).
     * Numaralar/madde işaretleri temizlenir, Exportify CSV'si de okunur.
     * Daha önce aynı aramayla indirilenler atlanır. Eklenen şarkı sayısını döndürür.
     */
    fun bulkImport(text: String, playlistName: String?): BulkResult {
        val allLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val linkRe = Regex("https?://\\S+")
        val links = allLines.mapNotNull { l -> linkRe.find(l)?.value?.takeIf { l.startsWith("http") } }
        val spotifyLinks = links.filter { it.contains("spotify.com/track/") || it.contains("spotify.link/") }
        val ytLinks = links.filter { isYouTube(it) && !isYtPlaylist(it) }
        val items = parseSongList(allLines.filterNot { l -> l.startsWith("http") }.joinToString("\n"))
        val total = items.size + spotifyLinks.size + ytLinks.size
        if (total == 0) return BulkResult(0, 0)

        val plId = playlistName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            _playlists.value.find { it.name.equals(name, ignoreCase = true) }?.id ?: createPlaylist(name)
        }
        var count = 0
        ytLinks.forEach { if (enqueueYt(it, null, null, plId, it)) count++ }
        if (spotifyLinks.isNotEmpty()) importSpotifyLinks(spotifyLinks, plId)
        val already = _tracks.value.map { it.sourceUrl }.toSet()
        for ((artist, title) in items) {
            val label = if (artist.isBlank()) title else "$artist - $title"
            val query = "ytsearch1:$label audio"
            if (query in already) {
                // Zaten indirilmiş: sadece listeye ekle
                if (plId != null) _tracks.value.find { it.sourceUrl == query }?.let { addToPlaylist(plId, it.id) }
                continue
            }
            if (enqueueYt(query, title.takeIf { artist.isNotBlank() }, artist.ifBlank { null }, plId, label)) count++
        }
        // Spotify linkleri arka planda tek tek okunur; sayıları sonra ayrıca bildirilir
        return BulkResult(total, count + spotifyLinks.size)
    }

    /** Kopyalanan Spotify şarkı linklerini sırayla okuyup indirme kuyruğuna ekler. */
    private fun importSpotifyLinks(links: List<String>, plId: String?) {
        val id = newTask(links.first(), "Spotify linkleri okunuyor (0/${links.size})")
        scope.launch {
            var added = 0; var skipped = 0; var failed = 0
            links.forEachIndexed { i, link ->
                updateTask(id) { it.copy(name = "Spotify linkleri okunuyor (${i + 1}/${links.size})", progress = (i + 1f) / links.size) }
                try {
                    val (_, items, cover) = resolveSpotify(link)
                    items.forEach { (title, artist) ->
                        val label = if (artist.isBlank()) title else "$artist - $title"
                        if (enqueueYt("ytsearch1:$label audio", title, artist, plId, label, cover)) added++ else skipped++
                    }
                } catch (e: Exception) {
                    failed++
                }
                kotlinx.coroutines.delay(150) // Spotify'ı yormamak için
            }
            removeTask(id)
            _notices.tryEmit(
                buildString {
                    append("$added şarkı indiriliyor.")
                    if (skipped > 0) append(" $skipped şarkı zaten vardı, atlandı.")
                    if (failed > 0) append(" $failed link okunamadı.")
                }
            )
        }
    }

    private fun parseSongList(text: String): List<Pair<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        // Exportify / Spotify CSV
        val header = splitCsv(lines.first()).map { it.lowercase() }
        val ti = header.indexOfFirst { it == "track name" || it == "title" || it == "şarkı" }
        val ai = header.indexOfFirst { it.startsWith("artist name") || it == "artist" || it == "sanatçı" }
        if (ti >= 0 && lines.first().contains(',')) {
            return lines.drop(1).mapNotNull { l ->
                val cols = splitCsv(l)
                val t = cols.getOrNull(ti)?.trim().orEmpty()
                if (t.isEmpty()) null
                else (cols.getOrNull(ai)?.substringBefore(';')?.substringBefore(',')?.trim().orEmpty()) to t
            }.distinct()
        }

        val junk = Regex("^(\\d+[.)\\-:]\\s*|[-*•·▶►]+\\s*)")
        return lines.map { l ->
            val clean = l.replace(junk, "").trim()
            val sep = listOf(" - ", " – ", " — ", " by ").firstOrNull { clean.contains(it) }
            if (sep == null) "" to clean
            else clean.substringBefore(sep).trim() to clean.substringAfter(sep).trim()
        }.filter { it.second.isNotEmpty() }.distinct()
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun isYouTube(u: String) = u.contains("youtube.com/") || u.contains("youtu.be/")
    private fun isYtPlaylist(u: String) = u.contains("/playlist") && u.contains("list=")
    private fun isSpotify(u: String) = u.contains("spotify.com/") || u.contains("spotify.link/")

    private fun newTask(url: String, name: String): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) { _downloads.value = _downloads.value + DownloadTask(id, url, name.take(80)) }
        DownloadService.start(ctx)
        return id
    }

    private fun fail(id: String, e: Throwable, retry: () -> Unit) {
        retries[id] = retry
        updateTask(id) { it.copy(error = friendly(e)) }
    }

    fun retryDownload(id: String) {
        val r = retries.remove(id) ?: return
        removeTask(id)
        r()
    }

    private fun removeTask(id: String) = synchronized(lock) {
        _downloads.value = _downloads.value.filterNot { it.id == id }
    }

    private fun friendly(e: Throwable): String {
        val msg = e.message ?: return "Bilinmeyen hata"
        val line = msg.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: msg
        return line.removePrefix("ERROR: ").take(200)
    }

    private fun startDirect(url: String, customTitle: String?) {
        val id = newTask(url, url.substringAfterLast('/'))
        scope.launch {
            try {
                download(id, url, customTitle)
                removeTask(id)
            } catch (e: Exception) {
                fail(id, e) { startDirect(url, customTitle) }
            }
        }
    }

    // ---------------- YouTube (yt-dlp) ----------------

    private val prefs = ctx.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
    private val ytMutex = Mutex()
    private var ytReady = false
    private val ytSlots = Semaphore(2) // aynı anda en fazla 2 indirme

    private suspend fun ensureYt() = ytMutex.withLock {
        if (ytReady) return@withLock
        YoutubeDL.getInstance().init(ctx)
        ytReady = true
        // YouTube sık değiştiği için yt-dlp'yi günde bir kez kendiliğinden güncelle
        val last = prefs.getLong("ytdlp_guncelleme", 0L)
        if (System.currentTimeMillis() - last > 24 * 3600_000L) {
            runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx) }
                .onSuccess { prefs.edit().putLong("ytdlp_guncelleme", System.currentTimeMillis()).apply() }
        }
    }

    suspend fun updateYtDlp(): String = withContext(Dispatchers.IO) {
        try {
            ensureYt()
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx)
            prefs.edit().putLong("ytdlp_guncelleme", System.currentTimeMillis()).apply()
            when (status?.name) {
                "ALREADY_UP_TO_DATE" -> "İndirici zaten güncel"
                else -> "İndirici güncellendi"
            }
        } catch (e: Exception) {
            "Güncellenemedi: ${friendly(e)}"
        }
    }

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        ensureYt()
        val req = YoutubeDLRequest("ytsearch10:$query").apply {
            addOption("--flat-playlist")
            addOption("-J")
        }
        val out = YoutubeDL.getInstance().execute(req).out
        val root = JSONObject(out.substring(out.indexOf('{')))
        val entries = root.optJSONArray("entries") ?: JSONArray()
        (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val vid = e.optString("id").ifBlank { return@mapNotNull null }
            SearchResult(
                id = vid,
                title = e.optString("title"),
                channel = e.optString("channel").ifBlank { e.optString("uploader") },
                duration = e.optDouble("duration", 0.0).toLong(),
                url = "https://www.youtube.com/watch?v=$vid",
            )
        }
    }

    private fun enqueueYt(
        source: String, title: String?, artist: String?, playlistId: String?, label: String,
        coverUrl: String? = null,
    ): Boolean {
        // Zaten indirilmişse tekrar indirme; sadece listeye ekle
        findExisting(source, title, artist)?.let { t ->
            if (playlistId != null) addToPlaylist(playlistId, t.id)
            return false
        }
        // Aynı şarkı zaten iniyorsa ikinci kez indirme; sadece listesini not et
        val inFlight = synchronized(lock) { _downloads.value.any { it.url == source && it.error == null } }
        if (inFlight) {
            if (playlistId != null) pendingPlaylists.getOrPut(source) { ConcurrentHashMap.newKeySet() }.add(playlistId)
            return false
        }
        val id = newTask(source, label)
        scope.launch {
            try {
                ytSlots.withPermit {
                    ensureYt()
                    val t = ytDownload(id, source, title, artist, coverUrl)
                    if (playlistId != null) addToPlaylist(playlistId, t.id)
                    pendingPlaylists.remove(source)?.forEach { addToPlaylist(it, t.id) }
                }
                removeTask(id)
            } catch (e: Exception) {
                fail(id, e) { enqueueYt(source, title, artist, playlistId, label, coverUrl) }
            }
        }
        return true
    }

    private fun ytDownload(taskId: String, source: String, title: String?, artist: String?, coverUrl: String?): Track {
        val tmp = File(ctx.cacheDir, "yt_$taskId").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(source).apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-o", "${tmp.absolutePath}/ses.%(ext)s")
                addOption("--print", "after_move:%(title)s\t%(uploader)s\t%(thumbnail)s")
                addOption("--no-simulate")
            }
            val resp = YoutubeDL.getInstance().execute(req, taskId) { p, _, _ ->
                if (p >= 0f) updateTask(taskId) { it.copy(progress = (p / 100f).coerceIn(0f, 1f)) }
            }
            val f = tmp.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") }
                ?: throw IOException("Ses dosyası inmedi")

            val info = resp.out.lines().lastOrNull { it.contains('\t') }
            val parts = info?.split('\t').orEmpty()
            fun part(i: Int) = parts.getOrNull(i)?.trim()?.takeIf { it.isNotBlank() && it != "NA" }
            val ytTitle = part(0)
            val ytUploader = part(1)?.removeSuffix(" - Topic")
            val ytThumb = part(2)

            val finalTitle = title?.takeIf { it.isNotBlank() } ?: ytTitle ?: "Şarkı"
            val target = uniqueFile(sanitize(finalTitle).ifBlank { "sarki" }, f.extension.ifBlank { "m4a" })
            f.copyTo(target)
            val newId = UUID.randomUUID().toString()
            val track = Track(
                id = newId,
                title = finalTitle,
                artist = artist?.takeIf { it.isNotBlank() } ?: ytUploader.orEmpty(),
                fileName = target.name,
                sourceUrl = source,
                addedAt = System.currentTimeMillis(),
                cover = saveCover(newId, coverUrl) ?: saveCover(newId, ytThumb),
            )
            addTrack(track)
            return track
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun expandYtPlaylist(url: String) {
        val id = newTask(url, "YouTube listesi okunuyor…")
        scope.launch {
            try {
                ensureYt()
                val req = YoutubeDLRequest(url).apply { addOption("--flat-playlist"); addOption("-J") }
                val out = YoutubeDL.getInstance().execute(req).out
                val root = JSONObject(out.substring(out.indexOf('{')))
                val entries = root.optJSONArray("entries") ?: JSONArray()
                if (entries.length() == 0) throw IOException("Listede şarkı bulunamadı")
                val plId = createPlaylist(root.optString("title").ifBlank { "YouTube listesi" })
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val vid = e.optString("id")
                    if (vid.isBlank()) continue
                    enqueueYt("https://www.youtube.com/watch?v=$vid", null, null, plId, e.optString("title").ifBlank { vid })
                }
                removeTask(id)
            } catch (e: Exception) {
                fail(id, e) { expandYtPlaylist(url) }
            }
        }
    }

    // ---------------- Spotify ----------------

    private var loginServer: java.net.ServerSocket? = null

    /**
     * Spotify girişini başlatır: telefonda 127.0.0.1 üzerinde geçici bir dinleyici açar,
     * tarayıcı giriş sonrası oraya döndüğünde kodu alır. Açılacak tarayıcı Intent'ini döndürür.
     */
    fun startSpotifyLogin(): android.content.Intent? {
        val intent = SpotifyApi.loginIntent(ctx) ?: return null
        runCatching { loginServer?.close() }
        val server = try {
            java.net.ServerSocket(SpotifyApi.PORT, 4, java.net.InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            _notices.tryEmit("Giriş dinleyicisi açılamadı: ${e.message}")
            return null
        }
        loginServer = server
        server.soTimeout = 5 * 60_000
        scope.launch {
            try {
                while (true) {
                    val sock = server.accept()
                    val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = line.split(' ').getOrNull(1).orEmpty()
                    val isCallback = path.startsWith("/callback")
                    val html = if (isCallback) {
                        "<html><head><meta name=viewport content='width=device-width'>" +
                            "<script>setTimeout(function(){location.href='submuzik://spotify-callback?done=1'},300)</script></head>" +
                            "<body style='font-family:sans-serif;background:#0d0d10;color:#eee;text-align:center;padding-top:30vh'>" +
                            "<h2>Spotify girişi tamam</h2><p><a style='color:#1db954' href='submuzik://spotify-callback?done=1'>SubMüzik'e dön</a></p></body></html>"
                    } else ""
                    val bytes = html.toByteArray()
                    sock.getOutputStream().use { o ->
                        o.write(("HTTP/1.1 ${if (isCallback) "200 OK" else "404 Not Found"}\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        o.write(bytes)
                    }
                    sock.close()
                    if (isCallback) {
                        SpotifyApi.finishLogin(ctx, android.net.Uri.parse("http://127.0.0.1$path"))
                        _notices.tryEmit("Spotify'a bağlandın. Artık listelerin tamamı gelir.")
                        break
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // kullanıcı girişi bitirmedi
            } catch (e: Exception) {
                if (!server.isClosed) _notices.tryEmit(friendly(e))
            } finally {
                runCatching { server.close() }
                if (loginServer === server) loginServer = null
            }
        }
        return intent
    }

    fun finishSpotifyLogin(data: android.net.Uri) {
        scope.launch {
            try {
                SpotifyApi.finishLogin(ctx, data)
                _notices.tryEmit("Spotify'a bağlandın. Artık listelerin tamamı gelir.")
            } catch (e: Exception) {
                _notices.tryEmit(friendly(e))
            }
        }
    }

    fun importLikedSongs() {
        val id = newTask("spotify:liked", "Beğenilen şarkılar okunuyor…")
        scope.launch {
            try {
                val items = SpotifyApi.likedSongs(ctx)
                if (items.isEmpty()) throw IOException("Beğenilen şarkı bulunamadı")
                val plName = "Beğenilen şarkılar"
                val plId = _playlists.value.find { it.name == plName }?.id ?: createPlaylist(plName)
                var added = 0
                items.forEach { (title, artist) ->
                    val label = if (artist.isBlank()) title else "$artist - $title"
                    if (enqueueYt("ytsearch1:$label audio", title, artist, plId, label)) added++
                }
                removeTask(id)
                _notices.tryEmit("${items.size} beğenilen şarkı bulundu. $added tanesi indiriliyor, ${items.size - added} tanesi zaten vardı.")
            } catch (e: Exception) {
                fail(id, if (e is SpotifyApi.Yasak) IOException("Spotify beğenilen şarkıları vermedi: ${e.message}") else e) { importLikedSongs() }
            }
        }
    }

    private fun expandSpotify(url: String) {
        val id = newTask(url, "Spotify okunuyor…")
        scope.launch {
            try {
                val (name, items, cover) = resolveSpotify(url)
                if (items.isEmpty()) throw IOException("Spotify'da şarkı bulunamadı")
                val plName = name.ifBlank { "Spotify listesi" }
                val plId = if (items.size > 1) {
                    _playlists.value.find { it.name == plName }?.id ?: createPlaylist(plName)
                } else null
                var added = 0
                items.forEach { (title, artist) ->
                    val label = if (artist.isBlank()) title else "$artist - $title"
                    if (enqueueYt("ytsearch1:$label audio", title, artist, plId, label, cover)) added++
                }
                removeTask(id)
                val skipped = items.size - added
                if (url.contains("/playlist/") && items.size == 100 && !SpotifyApi.bagli.value) {
                    _notices.tryEmit(
                        "Spotify girişsiz sadece ilk 100 şarkıyı veriyor.\n\n" +
                            "En kolayı: Ayarlar → Spotify bölümünden giriş yap ve linki tekrar yapıştır, listenin tamamı gelir.\n\n" +
                            "Diğer yollar:\n\n" +
                            "1) Telefon tarayıcısında exportify.net'e gir, Spotify ile giriş yap, listeyi CSV olarak indir. " +
                            "Sonra burada + → Toplu ekle → Dosyadan aç ile o CSV'yi seç.\n\n" +
                            "2) Bilgisayardaki Spotify uygulamasında listeyi aç, Ctrl+A ile tüm şarkıları seç, Ctrl+C ile kopyala. " +
                            "Kopyalanan şarkı linklerini Toplu ekle kutusuna yapıştır.\n\n" +
                            "Daha önce inen şarkılar tekrar inmez."
                    )
                } else if (skipped > 0) {
                    _notices.tryEmit("$added şarkı indiriliyor. $skipped şarkı zaten kütüphanede olduğu için atlandı.")
                }
            } catch (e: Exception) {
                fail(id, e) { expandSpotify(url) }
            }
        }
    }

    private fun httpGet(url: String): Pair<String, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "tr,en;q=0.8")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.url.toString() to conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Spotify'ın gömme sayfasından ad + sanatçı listesini okur (hesap/anahtar gerekmez). */
    private fun resolveSpotify(input: String): Triple<String, List<Pair<String, String>>, String?> {
        val pattern = Regex("open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist)/([A-Za-z0-9]+)")
        var m = pattern.find(input)
        if (m == null) {
            // spotify.link kısa linki -> asıl adrese git
            val (finalUrl, body) = httpGet(input)
            m = pattern.find(finalUrl) ?: pattern.find(body)
        }
        m ?: throw IOException("Spotify linki anlaşılamadı (şarkı, albüm veya çalma listesi linki olmalı)")
        val (type, sid) = m.destructured

        // Spotify'a giriş yapıldıysa listenin tamamını resmi API'den al (100 sınırı yok)
        if (type == "playlist" && SpotifyApi.bagli.value) {
            try {
                val (n, items) = SpotifyApi.playlist(ctx, sid)
                if (items.isNotEmpty()) return Triple(n, items, null)
            } catch (e: SpotifyApi.Yasak) {
                throw IOException(
                    "Spotify bu listenin şarkılarını vermiyor (liste senin değil). Spotify'da listeyi aç → ⋯ → " +
                        "\"Diğer çalma listesine ekle\" → Yeni çalma listesi ile kendine kopyala, sonra yeni listenin linkini yapıştır."
                )
            } catch (e: Exception) {
                if (e.message?.contains("oturumu") == true) _notices.tryEmit(e.message!!)
                // diğer hatalarda anahtarsız yönteme dön
            }
        }

        val (_, html) = httpGet("https://open.spotify.com/embed/$type/$sid")
        val json = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IOException("Spotify bilgisi okunamadı")
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            ?: throw IOException("Spotify bilgisi okunamadı")

        val name = entity.optString("name").ifBlank { entity.optString("title") }
        val cover = spotifyCover(entity)
        val list = entity.optJSONArray("trackList")
        if (type != "track" && list != null) {
            val items = (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title").ifBlank { return@mapNotNull null }
                title to t.optString("subtitle").replace('\u00a0', ' ')
            }
            // Albümde tüm şarkılar aynı kapağı paylaşır; çalma listesinde YouTube kapağı kullanılır
            return Triple(name, items, if (type == "album") cover else null)
        }
        val artists = entity.optJSONArray("artists")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")
        }.orEmpty().ifBlank { entity.optString("subtitle") }
        return Triple(name, listOf(name to artists), cover)
    }

    private fun spotifyCover(entity: JSONObject): String? {
        fun best(arr: JSONArray?, w: String): String? {
            arr ?: return null
            var bestUrl: String? = null; var bestW = -1
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                if (u.isBlank()) continue
                val width = o.optInt(w, 0)
                if (width > bestW) { bestW = width; bestUrl = u }
            }
            return bestUrl
        }
        return best(entity.optJSONObject("visualIdentity")?.optJSONArray("image"), "maxWidth")
            ?: best(entity.optJSONObject("coverArt")?.optJSONArray("sources"), "width")
            ?: best(entity.optJSONArray("images"), "width")
    }

    fun dismissDownload(id: String) = synchronized(lock) {
        retries.remove(id)
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
        val newId = UUID.randomUUID().toString()
        val track = Track(
            id = newId,
            title = customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: tagTitle ?: stem,
            artist = tagArtist.orEmpty(),
            fileName = target.name,
            sourceUrl = rawUrl,
            addedAt = System.currentTimeMillis(),
            cover = saveEmbeddedCover(newId, target),
        )
        addTrack(track)
    }

    private fun addTrack(track: Track) = synchronized(lock) {
        _tracks.value = listOf(track) + _tracks.value
        saveTracks()
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
        coverOf(t)?.delete()
        _tracks.value = _tracks.value.filterNot { it.id == t.id }
        _playlists.value = _playlists.value.map { p -> p.copy(trackIds = p.trackIds - t.id) }
        saveTracks(); savePlaylists()
    }

    fun renameTrack(id: String, title: String, artist: String): Track? = synchronized(lock) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(title = title, artist = artist) else it }
        saveTracks()
        _tracks.value.find { it.id == id }
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

    fun renamePlaylist(playlistId: String, name: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map { if (it.id == playlistId) it.copy(name = name.trim()) else it }
        savePlaylists()
    }

    fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) = synchronized(lock) {
        _playlists.value = _playlists.value.map { p ->
            if (p.id == playlistId) p.copy(trackIds = p.trackIds + trackIds.filterNot { it in p.trackIds }) else p
        }
        savePlaylists()
    }

    /** Listedeki bir şarkıyı başka bir sıraya taşır. */
    fun moveInPlaylist(playlistId: String, trackId: String, delta: Int) = synchronized(lock) {
        _playlists.value = _playlists.value.map { p ->
            if (p.id != playlistId) return@map p
            val from = p.trackIds.indexOf(trackId)
            val to = (from + delta).coerceIn(0, p.trackIds.lastIndex)
            if (from < 0 || from == to) return@map p
            val list = p.trackIds.toMutableList()
            list.add(to, list.removeAt(from))
            p.copy(trackIds = list)
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
                o.getString("fileName"), o.optString("sourceUrl"), o.optLong("addedAt"),
                o.optString("cover").ifBlank { null })
        }.filter { File(musicDir, it.fileName).exists() }
    }.getOrDefault(emptyList())

    private fun saveTracks() {
        val arr = JSONArray()
        _tracks.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("fileName", it.fileName).put("sourceUrl", it.sourceUrl).put("addedAt", it.addedAt)
                .put("cover", it.cover ?: ""))
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
