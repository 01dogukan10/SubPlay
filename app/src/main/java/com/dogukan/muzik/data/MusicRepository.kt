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
    val videoId: String? = null, // YouTube video kimliği (akış ve indirme için)
    val album: String? = null,
    val kazancDb: Float? = null, // ses dengeleme: bu şarkıya uygulanacak kazanç (dB)
) {
    /** Telefona indirilmiş mi? İndirilmemiş şarkılar internetten akış olarak çalınır. */
    val indirildi: Boolean get() = fileName.isNotBlank()
}

/** kaynak: Spotify / YouTube liste linki -> liste oradan kendiliğinden eşitlenir. */
data class Playlist(
    val id: String, val name: String, val trackIds: List<String>, val cover: String? = null, val kaynak: String? = null,
    /** Karışım (Spotify Blend gibi): kaynak listelerin kimlikleri. Doluysa şarkılar bu listelerden kendiliğinden oluşur. */
    val karisim: List<String>? = null,
)

/** Akıllı karıştırmanın önerdiği (kütüphanede olmayan) şarkı. */
data class Oneri(val videoId: String, val title: String, val artist: String)

/** Eski şarkı adı düzeltmesi önizlemesi. */
data class AdDuzeltme(val track: Track, val yeniAd: String, val yeniSanatci: String)

/** "A, B feat. C" -> "A" (sanatçı sayfaları için ana sanatçı). */
fun anaSanatci(a: String): String =
    a.split(Regex("\\s*(,|&|;| feat\\.? | ft\\.? | x | vs\\.? )\\s*", RegexOption.IGNORE_CASE)).first().trim()

/** Kütüphane ve listelerde sıralama seçenekleri. LISTE = listenin kendi (elle düzenlenen) sırası. */
enum class Siralama(val ad: String) {
    LISTE("Liste sırası"),
    YENI("Eklenme: en yeni"),
    ESKI("Eklenme: en eski"),
    AD_AZ("İsim: A → Z"),
    AD_ZA("İsim: Z → A"),
    SANATCI("Sanatçı: A → Z"),
}

/** Sıralarken baştaki İngilizce tanımlıkları ("The", "A", "An") ve noktalama işaretlerini sayma. */
private val baslikEki = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE)
fun siraAnahtari(s: String): String =
    s.trim().trimStart { !it.isLetterOrDigit() }.replace(baslikEki, "").trimStart { !it.isLetterOrDigit() }

fun List<Track>.sirala(s: Siralama): List<Track> {
    val col = java.text.Collator.getInstance(java.util.Locale("tr")).apply { strength = java.text.Collator.PRIMARY }
    val ad = { t: Track -> siraAnahtari(t.title) }
    val sanatci = { t: Track -> siraAnahtari(t.artist).ifBlank { "\uFFFF" } }
    return when (s) {
        Siralama.LISTE -> this
        Siralama.YENI -> sortedByDescending { it.addedAt }
        Siralama.ESKI -> sortedBy { it.addedAt }
        Siralama.AD_AZ -> sortedWith { a, b -> col.compare(ad(a), ad(b)) }
        Siralama.AD_ZA -> sortedWith { a, b -> col.compare(ad(b), ad(a)) }
        Siralama.SANATCI -> sortedWith { a, b ->
            val x = col.compare(sanatci(a), sanatci(b))
            if (x != 0) x else col.compare(ad(a), ad(b))
        }
    }
}

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
        /** "Beğendiklerim" listesinin sabit kimliği (♥ düğmesi bu listeye ekler). */
        const val BEGENI_ID = "begendiklerim"
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
        ytId(source)?.let { id -> list.find { it.videoId == id || ytId(it.sourceUrl) == id }?.let { return it } }
        val t = title?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return null
        // Sanatçı bilinmiyorsa sadece isimden "aynı şarkı" demek yanlış olur (farklı sanatçıların aynı adlı şarkıları)
        val a = artist?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return null
        return list.find { tr ->
            norm(tr.title) == t && norm(tr.artist).let { ta ->
                ta == a || (ta.length >= 3 && a.length >= 3 && (ta.contains(a) || a.contains(ta)))
            }
        }
    }

    /** Hata veren indirmeleri yeniden başlatmak için. */
    private val retries = ConcurrentHashMap<String, () -> Unit>()

    /** Aynı kaynak inerken ikinci kez istenirse, bitince eklenecek listeler (liste id -> sıra). */
    private val pendingPlaylists = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    /** İçe aktarılan listelerde her şarkının olması gereken sıra (liste id -> şarkı id -> sıra). */
    private val plannedOrder = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    /**
     * Şarkıyı listeye, içe aktarılan listedeki sırasına göre yerleştirir.
     * Şarkılar hangi sırayla inerse insin liste orijinal sırasında kalır.
     */
    private fun placeInPlaylist(playlistId: String, trackId: String, order: Int?) {
        if (order == null) { addToPlaylist(playlistId, trackId); return }
        synchronized(lock) {
            val plan = plannedOrder.getOrPut(playlistId) { ConcurrentHashMap() }
            plan[trackId] = order
            _playlists.value = _playlists.value.map { p ->
                if (p.id != playlistId) return@map p
                // Zaten listedeyse yerinden çıkarıp doğru sıraya koy (eski karışık listeler de düzelsin)
                val list = p.trackIds.toMutableList().apply { remove(trackId) }
                val pos = list.indexOfLast { id -> plan[id]?.let { it < order } == true } + 1
                // Önceki sırada şarkı yoksa: planlı şarkıların başına, eski şarkıların sonrasına koy
                val at = if (pos > 0) pos else (list.indexOfFirst { plan.containsKey(it) }.takeIf { it >= 0 } ?: list.size)
                list.add(at, trackId)
                p.copy(trackIds = list)
            }
            savePlaylists()
        }
    }

    /** Var olan listeyi adıyla bulur ya da yenisini açar (aynı link ikinci kez yapıştırılınca ikinci liste açılmasın). */
    private fun playlistByName(name: String): String =
        _playlists.value.find { it.name.equals(name, ignoreCase = true) }?.id ?: createPlaylist(name)

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

    /** İndirilmemiş şarkı için var olmayan bir dosya döner (asla müzik klasörünün kendisi değil). */
    fun fileOf(t: Track): File = if (t.fileName.isBlank()) File(musicDir, ".yok-${t.id}") else File(musicDir, t.fileName)

    private fun updateTrack(id: String, f: (Track) -> Track): Track? = synchronized(lock) {
        _tracks.value = _tracks.value.map { if (it.id == id) f(it) else it }
        saveTracks()
        _tracks.value.find { it.id == id }
    }
    fun coverOf(t: Track): File? = t.cover?.let { File(coversDir, it) }?.takeIf { it.exists() }

    /** Bildirim, kilit ekranı, Bluetooth ve Android Auto'nun da okuyabildiği kapak adresi. */
    fun kapakUri(t: Track): android.net.Uri? = coverOf(t)?.let { com.dogukan.muzik.player.KapakSaglayici.uri(ctx, it) }

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
    /** indir=false -> şarkı sadece kütüphaneye eklenir, çalınca internetten akış olarak gelir. */
    fun startDownload(input: String, customTitle: String?, indir: Boolean = true) {
        val url = input.trim()
        when {
            isYouTube(url) && isYtPlaylist(url) -> expandYtPlaylist(url, indir)
            isYouTube(url) -> enqueueYt(url, customTitle, null, null, url, indir = indir)
            isSpotify(url) -> expandSpotify(url, indir)
            else -> startDirect(url, customTitle, indir)
        }
    }

    /** Arama sonucu için temiz şarkı adı ve sanatçı ("Official Video" vb. olmadan). */
    fun adSanatci(r: SearchResult): Pair<String, String> = ytAdSanatci(r.title, r.channel)

    fun downloadSearchResult(r: SearchResult, indir: Boolean = true) =
        enqueueYt(r.url, null, null, null, r.title, ytMeta = r.title to r.channel, indir = indir)

    /** İndirilmemiş (ya da telefondan silinmiş) şarkıları indirir. */
    fun downloadTracks(list: List<Track>) {
        list.filter { !it.indirildi || !fileOf(it).exists() }.forEach { t ->
            val src = t.videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: t.sourceUrl
            when {
                src.isBlank() -> _notices.tryEmit("\"${t.title}\" için kaynak bilinmiyor, indirilemiyor.")
                src.startsWith("ytsearch") || isYouTube(src) ->
                    enqueueYt(src, t.title, t.artist, null, if (t.artist.isBlank()) t.title else "${t.artist} - ${t.title}", existingId = t.id)
                else -> startDirect(src, t.title, true, existingId = t.id)
            }
        }
    }

    /**
     * Toplu ekleme: her satır bir şarkı ("Sanatçı - Şarkı" ya da sadece ad).
     * Numaralar/madde işaretleri temizlenir, Exportify CSV'si de okunur.
     * Daha önce aynı aramayla indirilenler atlanır. Eklenen şarkı sayısını döndürür.
     */
    fun bulkImport(text: String, playlistName: String?, indir: Boolean = true): BulkResult {
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
        var order = 0
        ytLinks.forEach { if (enqueueYt(it, null, null, plId, it, order = order++, indir = indir)) count++ }
        if (spotifyLinks.isNotEmpty()) importSpotifyLinks(spotifyLinks, plId, startOrder = order, indir = indir)
        order += spotifyLinks.size * 1000 // Spotify linklerinden sonra gelsinler
        for ((artist, title) in items) {
            val label = if (artist.isBlank()) title else "$artist - $title"
            val query = "ytsearch1:$label audio"
            if (enqueueYt(query, title.takeIf { artist.isNotBlank() }, artist.ifBlank { null }, plId, label, order = order++, indir = indir)) count++
        }
        // Spotify linkleri arka planda tek tek okunur; sayıları sonra ayrıca bildirilir
        return BulkResult(total, count + spotifyLinks.size)
    }

    /** Kopyalanan Spotify şarkı linklerini sırayla okuyup indirme kuyruğuna ekler. */
    private fun importSpotifyLinks(links: List<String>, plId: String?, startOrder: Int = 0, indir: Boolean = true) {
        val id = newTask(links.first(), "Spotify linkleri okunuyor (0/${links.size})")
        scope.launch {
            var added = 0; var skipped = 0; var failed = 0
            links.forEachIndexed { i, link ->
                updateTask(id) { it.copy(name = "Spotify linkleri okunuyor (${i + 1}/${links.size})", progress = (i + 1f) / links.size) }
                try {
                    val (_, items, cover) = resolveSpotify(link)
                    items.forEachIndexed { j, (title, artist) ->
                        val label = if (artist.isBlank()) title else "$artist - $title"
                        val ord = startOrder + i * 1000 + j
                        if (enqueueYt("ytsearch1:$label audio", title, artist, plId, label, cover, ord, indir = indir)) added++ else skipped++
                    }
                } catch (e: Exception) {
                    failed++
                }
                kotlinx.coroutines.delay(150) // Spotify'ı yormamak için
            }
            removeTask(id)
            _notices.tryEmit(
                buildString {
                    append(if (indir) "$added şarkı indiriliyor." else "$added şarkı kütüphaneye eklendi.")
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
        // "Şarkı - Remastered 2011", "Şarkı - Live" gibi ekler sanatçı-şarkı ayrımı sayılmaz
        val versionTag = Regex("^(\\d{4}\\b|(remaster(ed)?|live|radio edit|single version|album version|mono|stereo|demo|acoustic|instrumental|bonus track|remix|edit)\\b)", RegexOption.IGNORE_CASE)
        return lines.map { l ->
            val clean = l.replace(junk, "").trim()
            val sep = listOf(" - ", " – ", " — ").firstOrNull { clean.contains(it) }
            if (sep == null) return@map "" to clean
            val left = clean.substringBefore(sep).trim()
            val right = clean.substringAfter(sep).trim()
            if (versionTag.containsMatchIn(right)) "" to clean else left to right
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

    fun retryAllFailed() = _downloads.value.filter { it.error != null }.forEach { retryDownload(it.id) }
    fun dismissAllFailed() = _downloads.value.filter { it.error != null }.forEach { dismissDownload(it.id) }

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

    private fun startDirect(url: String, customTitle: String?, indir: Boolean = true, existingId: String? = null) {
        if (!indir) {
            findExisting(url, null, null)?.let { return }
            val stem = runCatching { URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "UTF-8") }
                .getOrDefault("").substringBeforeLast('.')
            addTrack(Track(UUID.randomUUID().toString(), customTitle?.takeIf { it.isNotBlank() } ?: stem.ifBlank { "Şarkı" },
                "", "", url, System.currentTimeMillis()))
            return
        }
        val id = newTask(url, customTitle ?: url.substringAfterLast('/'))
        scope.launch {
            try {
                wifiBekle(id)
                download(id, url, customTitle, existingId)
                removeTask(id)
            } catch (e: Exception) {
                fail(id, e) { startDirect(url, customTitle, true, existingId) }
            }
        }
    }

    // ---------------- YouTube (yt-dlp) ----------------

    private val prefs = ctx.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    /** Mobil veride indirme yapma; Wi-Fi gelince devam et. */
    var sadeceWifi: Boolean
        get() = prefs.getBoolean("sadece_wifi", false)
        set(v) = prefs.edit().putBoolean("sadece_wifi", v).apply()

    /** "Sadece Wi-Fi" açıksa ve mobil verideysek Wi-Fi gelene kadar bekle. */
    private suspend fun wifiBekle(taskId: String) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        var bekledi = false
        while (sadeceWifi && cm.isActiveNetworkMetered) {
            if (!bekledi) { updateTask(taskId) { it.copy(name = "Wi-Fi bekleniyor · " + it.name) }; bekledi = true }
            kotlinx.coroutines.delay(5_000)
        }
        if (bekledi) updateTask(taskId) { it.copy(name = it.name.removePrefix("Wi-Fi bekleniyor · ")) }
    }

    /** ♥: şarkıyı "Beğendiklerim" listesine ekler/çıkarır (en yeni en üstte). Beğenildiyse true. */
    fun toggleLike(trackId: String): Boolean = synchronized(lock) {
        var list = _playlists.value
        if (list.none { it.id == BEGENI_ID }) list = listOf(Playlist(BEGENI_ID, "Beğendiklerim", emptyList())) + list
        var liked = false
        _playlists.value = list.map { p ->
            if (p.id != BEGENI_ID) p
            else if (trackId in p.trackIds) p.copy(trackIds = p.trackIds - trackId)
            else { liked = true; p.copy(trackIds = listOf(trackId) + p.trackIds) }
        }
        savePlaylists()
        liked
    }

    /** Eklerken telefona indirilsin mi (son seçim hatırlanır). */
    var indirVarsayilan: Boolean
        get() = prefs.getBoolean("indir_varsayilan", true)
        set(v) = prefs.edit().putBoolean("indir_varsayilan", v).apply()
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
        order: Int? = null,
        ytMeta: Pair<String, String>? = null, // (YouTube başlığı, kanal) — indirmeden eklerken ad için
        indir: Boolean = true,
        existingId: String? = null, // kütüphanedeki indirilmemiş şarkıyı indir
    ): Boolean {
        // Kütüphanede zaten varsa tekrar ekleme; sadece listeye (doğru sırasına) ekle
        if (existingId == null) findExisting(source, title, artist)?.let { t ->
            if (playlistId != null) placeInPlaylist(playlistId, t.id, order)
            return false
        }
        if (!indir) {
            addOnline(source, title, artist, playlistId, order, coverUrl, ytMeta, label)
            return true
        }
        // Aynı şarkı zaten iniyorsa ikinci kez indirme; sadece listesini not et
        val inFlight = synchronized(lock) { _downloads.value.any { it.url == source && it.error == null } }
        if (inFlight) {
            if (playlistId != null) pendingPlaylists.getOrPut(source) { ConcurrentHashMap() }[playlistId] = order ?: Int.MAX_VALUE
            return false
        }
        val id = newTask(source, label)
        scope.launch {
            try {
                wifiBekle(id)
                ytSlots.withPermit {
                    ensureYt()
                    val t = ytDownload(id, source, title, artist, coverUrl, existingId)
                    if (playlistId != null) placeInPlaylist(playlistId, t.id, order)
                    pendingPlaylists.remove(source)?.forEach { (pl, ord) ->
                        placeInPlaylist(pl, t.id, ord.takeIf { it != Int.MAX_VALUE })
                    }
                }
                removeTask(id)
            } catch (e: Exception) {
                fail(id, e) { enqueueYt(source, title, artist, playlistId, label, coverUrl, order, ytMeta, true, existingId) }
            }
        }
        return true
    }

    private fun ytDownload(taskId: String, source: String, title: String?, artist: String?, coverUrl: String?, existingId: String? = null): Track {
        val tmp = File(ctx.cacheDir, "yt_$taskId").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(source).apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-o", "${tmp.absolutePath}/ses.%(ext)s")
                addOption("--print", "after_move:%(title)s\t%(uploader)s\t%(thumbnail)s\t%(id)s\t%(album)s")
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
            val ytVid = part(3)?.takeIf { it.length == 11 }
            val ytAlbum = part(4)

            // Başlık/sanatçı verilmediyse YouTube başlığını temizle: "Sanatçı - Şarkı (Official Video)" -> ayrı ayrı
            var cleanTitle = ytTitle?.let(::temizBaslik)
            var cleanArtist = ytUploader?.let(::temizKanal)
            val ct = cleanTitle
            if (title.isNullOrBlank() && ct != null) {
                val sep = listOf(" - ", " – ", " — ").firstOrNull { ct.contains(it) }
                if (sep != null) {
                    val l = ct.substringBefore(sep).trim()
                    val r = ct.substringAfter(sep).trim()
                    if (l.isNotEmpty() && r.isNotEmpty()) { cleanArtist = l; cleanTitle = r }
                }
            }
            val finalTitle = title?.takeIf { it.isNotBlank() } ?: cleanTitle?.ifBlank { null } ?: ytTitle ?: "Şarkı"
            val target = uniqueFile(sanitize(finalTitle).ifBlank { "sarki" }, f.extension.ifBlank { "m4a" })
            f.copyTo(target)
            if (existingId != null) {
                // Kütüphanede zaten olan (indirilmemiş) şarkı: sadece dosyasını bağla
                // Kapak indirme ağ işi: kilidin dışında yap
                val cur = _tracks.value.find { it.id == existingId }
                val cov = cur?.cover ?: saveCover(existingId, coverUrl) ?: saveCover(existingId, ytThumb)
                return updateTrack(existingId) { t ->
                    t.copy(fileName = target.name, videoId = t.videoId ?: ytVid, cover = t.cover ?: cov, album = t.album ?: ytAlbum)
                }?.also { sesOlcumuBaslat() } ?: throw IOException("Şarkı kütüphanede bulunamadı")
            }
            val newId = UUID.randomUUID().toString()
            val track = Track(
                id = newId,
                title = finalTitle,
                artist = artist?.takeIf { it.isNotBlank() } ?: cleanArtist.orEmpty(),
                fileName = target.name,
                sourceUrl = source,
                addedAt = System.currentTimeMillis(),
                cover = saveCover(newId, coverUrl) ?: saveCover(newId, ytThumb),
                videoId = ytVid,
                album = ytAlbum,
            )
            addTrack(track)
            sesOlcumuBaslat()
            return track
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** YouTube başlığı + kanal -> temiz (şarkı, sanatçı). */
    private fun ytAdSanatci(ytTitle: String, channel: String): Pair<String, String> {
        val ct = temizBaslik(ytTitle)
        val sep = listOf(" - ", " – ", " — ").firstOrNull { ct.contains(it) }
        if (sep != null) {
            val l = ct.substringBefore(sep).trim(); val r = ct.substringAfter(sep).trim()
            if (l.isNotEmpty() && r.isNotEmpty()) return r to l
        }
        return ct.ifBlank { ytTitle } to temizKanal(channel)
    }

    /** Şarkıyı indirmeden kütüphaneye ekler (çalınca internetten akış olarak gelir). */
    private fun addOnline(
        source: String, title: String?, artist: String?, playlistId: String?, order: Int?,
        coverUrl: String?, ytMeta: Pair<String, String>?, label: String,
    ) {
        // Tek YouTube linki: adını öğrenmek için önce bilgisini al
        if (title.isNullOrBlank() && ytMeta == null && isYouTube(source)) {
            val id = newTask(source, "Bilgi alınıyor…")
            scope.launch {
                try {
                    ensureYt()
                    val req = YoutubeDLRequest(source).apply {
                        addOption("--no-playlist"); addOption("--skip-download")
                        addOption("--print", "%(title)s\t%(uploader)s")
                    }
                    val line = YoutubeDL.getInstance().execute(req).out.lines().lastOrNull { it.contains('\t') }
                    val p = line?.split('\t').orEmpty()
                    addOnline(source, null, artist, playlistId, order, coverUrl,
                        p.getOrNull(0).orEmpty().ifBlank { label } to p.getOrNull(1).orEmpty(), label)
                    removeTask(id)
                } catch (e: Exception) {
                    fail(id, e) { addOnline(source, title, artist, playlistId, order, coverUrl, ytMeta, label) }
                }
            }
            return
        }
        var t = title?.takeIf { it.isNotBlank() }
        var a = artist?.takeIf { it.isNotBlank() }
        if (t == null && ytMeta != null) {
            val (mt, ma) = ytAdSanatci(ytMeta.first, ytMeta.second)
            t = mt; if (a == null) a = ma
        }
        val vid = ytId(source)
        val newId = UUID.randomUUID().toString()
        addTrack(Track(newId, t ?: label, a.orEmpty(), "", source, System.currentTimeMillis(), null, vid))
        if (playlistId != null) placeInPlaylist(playlistId, newId, order)
        val cu = coverUrl ?: vid?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        if (cu != null) scope.launch { saveCover(newId, cu)?.let { c -> updateTrack(newId) { it.copy(cover = c) } } }
    }

    // ---------------- Akış (indirilmemiş şarkılar) ----------------

    private val streamCache = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * İndirilmemiş şarkının çalınabilir internet adresini bulur (oynatıcı servisi çağırır, bekletir).
     * YouTube adresleri birkaç saat geçerli olduğu için önbelleğe alınır.
     */
    fun streamUrl(trackId: String): String {
        streamCache[trackId]?.let { if (it.second > System.currentTimeMillis()) return it.first }
        val t = _tracks.value.find { it.id == trackId } ?: throw IOException("Şarkı kütüphanede yok")
        val src = t.videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: t.sourceUrl
        if (src.isBlank()) throw IOException("Şarkının kaynağı bilinmiyor")
        if (src.startsWith("http") && !isYouTube(src)) return src // doğrudan dosya linki
        kotlinx.coroutines.runBlocking { ensureYt() }
        val req = YoutubeDLRequest(src).apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            addOption("--no-playlist")
            addOption("--print", "%(id)s")
            addOption("--print", "urls")
            addOption("--print", "%(thumbnail)s")
        }
        val lines = YoutubeDL.getInstance().execute(req).out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val vid = lines.firstOrNull { it.length == 11 && !it.startsWith("http") }
        val url = lines.firstOrNull { it.startsWith("http") && !it.contains("ytimg") && !it.contains("/vi/") }
            ?: throw IOException("Akış adresi bulunamadı")
        val thumb = lines.lastOrNull { it.startsWith("http") && (it.contains("ytimg") || it.contains("/vi/")) }
        streamCache[trackId] = url to System.currentTimeMillis() + 4 * 3600_000L
        // Bulunan videoyu ve kapağı kaydet: sonraki çalmalar daha hızlı, kapak görünür
        if (t.videoId == null && vid != null) updateTrack(trackId) { it.copy(videoId = vid) }
        if (t.cover == null && thumb != null) saveCover(trackId, thumb)?.let { c -> updateTrack(trackId) { it.copy(cover = c) } }
        return url
    }

    /** Sıradaki şarkının adresini önceden bul (geçişte bekleme olmasın). */
    fun prefetchStream(trackId: String) {
        if (trackId.startsWith("oneri:")) {
            scope.launch { runCatching { streamUrlVideo(trackId.removePrefix("oneri:")) } }
            return
        }
        val t = _tracks.value.find { it.id == trackId } ?: return
        if (t.indirildi && fileOf(t).exists()) return
        scope.launch { runCatching { streamUrl(trackId) } }
    }

    fun notice(msg: String) { _notices.tryEmit(msg) }

    /** "(Official Video)", "[Lyrics]", "| Official Audio" gibi ekleri atar. */
    private fun temizBaslik(s: String): String {
        val tag = "official|video|audio|lyrics?|lyric|visuali[sz]er|music|hd|hq|4k|klip|resmi|sözleri|şarkı sözleri|mv|m/v|clip|oficial|videoclip"
        return s
            .replace(Regex("\\s*[(\\[]([^)\\]]*\\b($tag)\\b[^)\\]]*)[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*[|｜].*\\b($tag)\\b.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim().trimEnd('-', '–', '—', '|').trim()
    }

    /** Kanal adından "VEVO", "- Topic", "Official" eklerini atar. */
    private fun temizKanal(s: String): String =
        s.removeSuffix(" - Topic").replace(Regex("(?i)\\s*vevo$"), "").replace(Regex("(?i)\\s*official$"), "").trim()

    private fun expandYtPlaylist(url: String, indir: Boolean = true, sessiz: Boolean = false, hedef: String? = null) {
        val id = newTask(url, if (sessiz) "Liste eşitleniyor…" else "YouTube listesi okunuyor…")
        scope.launch {
            try {
                ensureYt()
                val req = YoutubeDLRequest(url).apply { addOption("--flat-playlist"); addOption("-J") }
                val out = YoutubeDL.getInstance().execute(req).out
                val root = JSONObject(out.substring(out.indexOf('{')))
                val entries = root.optJSONArray("entries") ?: JSONArray()
                if (entries.length() == 0) throw IOException("Listede şarkı bulunamadı")
                val plId = hedef?.takeIf { h -> _playlists.value.any { it.id == h } }
                    ?: playlistByName(root.optString("title").ifBlank { "YouTube listesi" })
                setKaynak(plId, url)
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val vid = e.optString("id")
                    if (vid.isBlank()) continue
                    enqueueYt("https://www.youtube.com/watch?v=$vid", null, null, plId, e.optString("title").ifBlank { vid }, order = i,
                        ytMeta = e.optString("title").ifBlank { vid } to e.optString("channel").ifBlank { e.optString("uploader") },
                        indir = indir)
                }
                removeTask(id)
            } catch (e: Exception) {
                if (sessiz) removeTask(id) else fail(id, e) { expandYtPlaylist(url, indir) }
            }
        }
    }

    // ---------------- Yedek / taşıma ----------------

    private val YEDEK_KLASORU = "SubPlay yedek"

    private fun copyToTree(src: File, dir: androidx.documentfile.provider.DocumentFile,
                           existing: Map<String?, androidx.documentfile.provider.DocumentFile>) {
        if (!src.exists()) return
        val old = existing[src.name]
        if (old != null && old.length() == src.length()) return // zaten var
        old?.delete()
        val f = dir.createFile("application/octet-stream", src.name) ?: throw IOException("${src.name} yazılamadı")
        ctx.contentResolver.openOutputStream(f.uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
    }

    /** Seçilen klasörün içine "SubPlay yedek" oluşturup şarkıları, kapakları, kütüphaneyi ve listeleri yazar. */
    fun exportBackup(tree: android.net.Uri) {
        val id = newTask("yedek", "Yedekleniyor…")
        scope.launch {
            try {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Klasör açılamadı")
                val dir = root.findFile(YEDEK_KLASORU)?.takeIf { it.isDirectory } ?: root.createDirectory(YEDEK_KLASORU)
                    ?: throw IOException("Yedek klasörü oluşturulamadı")
                val songs = dir.findFile("sarkilar") ?: dir.createDirectory("sarkilar") ?: throw IOException("Klasör oluşturulamadı")
                val covers = dir.findFile("kapaklar") ?: dir.createDirectory("kapaklar") ?: throw IOException("Klasör oluşturulamadı")
                val haveSongs = songs.listFiles().associateBy { it.name }
                val haveCovers = covers.listFiles().associateBy { it.name }

                val tracks = _tracks.value
                val pls = _playlists.value
                tracks.forEachIndexed { i, t ->
                    updateTask(id) { it.copy(name = "Yedekleniyor (${i + 1}/${tracks.size})", progress = (i + 1f) / tracks.size) }
                    copyToTree(fileOf(t), songs, haveSongs)
                    coverOf(t)?.let { copyToTree(it, covers, haveCovers) }
                }
                pls.forEach { p -> playlistCover(p)?.let { copyToTree(it, covers, haveCovers) } }

                val json = JSONObject()
                    .put("surum", 1)
                    .put("tarih", System.currentTimeMillis())
                    .put("kutuphane", JSONArray(libFile.takeIf { it.exists() }?.readText() ?: "[]"))
                    .put("listeler", JSONArray(plFile.takeIf { it.exists() }?.readText() ?: "[]"))
                    .put("podcastlar", PodcastRepo.get(ctx).yedekJson())
                    .put("sabit_listeler", JSONArray(_sabitler.value.toList()))
                    .put("son_listeler", JSONArray(_sonListeler.value))
                dir.findFile("yedek.json")?.delete()
                val jf = dir.createFile("application/octet-stream", "yedek.json") ?: throw IOException("yedek.json yazılamadı")
                ctx.contentResolver.openOutputStream(jf.uri)!!.use { it.write(json.toString().toByteArray()) }

                removeTask(id)
                _notices.tryEmit(
                    "Yedek hazır: ${tracks.size} şarkı, ${pls.size} liste.\n\n" +
                        "Seçtiğin klasörde \"$YEDEK_KLASORU\" adıyla duruyor. Yeni uygulamada Ayarlar → Yedekten geri yükle ile bu klasörü seç."
                )
            } catch (e: Exception) {
                fail(id, e) { exportBackup(tree) }
            }
        }
    }

    /** "SubPlay yedek" klasöründen (ya da onu içeren klasörden) her şeyi geri yükler; var olanlar atlanır. */
    fun importBackup(tree: android.net.Uri) {
        val id = newTask("geri-yukle", "Yedek okunuyor…")
        scope.launch {
            try {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Klasör açılamadı")
                val dir = if (root.findFile("yedek.json") != null) root
                else root.findFile(YEDEK_KLASORU) ?: root.findFile("SubMüzik yedek") ?: throw IOException("Seçilen klasörde SubPlay yedeği yok. \"$YEDEK_KLASORU\" klasörünü seç.")
                val jf = dir.findFile("yedek.json") ?: throw IOException("yedek.json bulunamadı")
                val json = JSONObject(ctx.contentResolver.openInputStream(jf.uri)!!.bufferedReader().use { it.readText() })
                val songs = dir.findFile("sarkilar")?.listFiles()?.associateBy { it.name }.orEmpty()
                val covers = dir.findFile("kapaklar")?.listFiles()?.associateBy { it.name }.orEmpty()

                fun copyIn(src: androidx.documentfile.provider.DocumentFile, dst: File) {
                    ctx.contentResolver.openInputStream(src.uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                }

                json.optJSONArray("podcastlar")?.let { PodcastRepo.get(ctx).yedektenYukle(it) }
                json.optJSONArray("sabit_listeler")?.let { a -> (0 until a.length()).forEach { sabitle(a.getString(it), true) } }
                json.optJSONArray("son_listeler")?.let { a -> (a.length() - 1 downTo 0).forEach { listeAcildi(a.getString(it)) } }
                val lib = json.optJSONArray("kutuphane") ?: JSONArray()
                val haveIds = _tracks.value.map { it.id }.toSet()
                val added = mutableListOf<Track>()
                for (i in 0 until lib.length()) {
                    updateTask(id) { it.copy(name = "Geri yükleniyor (${i + 1}/${lib.length()})", progress = (i + 1f) / lib.length()) }
                    val o = lib.optJSONObject(i) ?: continue
                    val tid = o.optString("id")
                    if (tid.isBlank() || tid in haveIds) continue
                    val name = o.optString("fileName")
                    val src = songs[name]
                    var target: File? = null
                    if (name.isNotBlank() && src != null) {
                        var tf = File(musicDir, name)
                        if (tf.exists() && tf.length() != src.length()) {
                            tf = uniqueFile(name.substringBeforeLast('.'), name.substringAfterLast('.', "m4a"))
                        }
                        if (!tf.exists()) copyIn(src, tf)
                        target = tf
                    }
                    val coverName = o.optString("cover").ifBlank { null }
                    coverName?.let { cn -> covers[cn]?.let { c -> File(coversDir, cn).takeIf { !it.exists() }?.let { copyIn(c, it) } } }
                    added += Track(tid, o.optString("title"), o.optString("artist"), target?.name ?: "",
                        o.optString("sourceUrl"), o.optLong("addedAt"), coverName, o.optString("videoId").ifBlank { null })
                }

                val pls = json.optJSONArray("listeler") ?: JSONArray()
                var plCount = 0
                synchronized(lock) {
                    _tracks.value = (_tracks.value + added).sortedByDescending { it.addedAt }
                    saveTracks()
                    val current = _playlists.value.toMutableList()
                    for (i in 0 until pls.length()) {
                        val o = pls.optJSONObject(i) ?: continue
                        val ids = o.optJSONArray("trackIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                        val cover = o.optString("cover").ifBlank { null }
                        cover?.let { cn -> covers[cn]?.let { c -> File(coversDir, cn).takeIf { !it.exists() }?.let { copyIn(c, it) } } }
                        val idx = current.indexOfFirst { it.id == o.optString("id") }
                        if (idx >= 0) {
                            val p = current[idx]
                            current[idx] = p.copy(trackIds = p.trackIds + ids.filterNot { it in p.trackIds }, cover = p.cover ?: cover,
                                kaynak = p.kaynak ?: o.optString("kaynak").ifBlank { null })
                        } else {
                            // Eşitleme bağlantısı ve karışım bilgisi de geri gelsin
                            current += Playlist(o.optString("id"), o.optString("name"), ids, cover,
                                o.optString("kaynak").ifBlank { null },
                                o.optJSONArray("karisim")?.let { k -> (0 until k.length()).map { k.getString(it) } })
                            plCount++
                        }
                    }
                    _playlists.value = current
                    savePlaylists()
                }
                removeTask(id)
                _notices.tryEmit("Geri yükleme tamam: ${added.size} şarkı ve $plCount liste eklendi. Zaten olanlar atlandı.")
            } catch (e: Exception) {
                fail(id, e) { importBackup(tree) }
            }
        }
    }

    // ---------------- Liste eşitleme ----------------

    private fun setKaynak(plId: String, url: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map { if (it.id == plId && it.kaynak == null) it.copy(kaynak = url) else it }
        savePlaylists()
    }

    fun eslitlemeyiKapat(plId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map { if (it.id == plId) it.copy(kaynak = null) else it }
        savePlaylists()
    }

    /** Kaynağı olan listeleri (Spotify / YouTube) yeniden okur, yeni şarkıları ekler. */
    fun listeyiEsitle(p: Playlist, sessiz: Boolean) {
        val url = p.kaynak ?: return
        if (isSpotify(url)) expandSpotify(url, indirVarsayilan, sessiz, hedef = p.id)
        else expandYtPlaylist(url, indirVarsayilan, sessiz, hedef = p.id)
    }

    /** Uygulama açılınca: son eşitlemeden 3 saat geçtiyse tüm bağlı listeleri sessizce eşitle. */
    fun esitleGerekirse() {
        val son = prefs.getLong("son_esitleme", 0L)
        if (System.currentTimeMillis() - son < 3 * 3600_000L) return
        prefs.edit().putLong("son_esitleme", System.currentTimeMillis()).apply()
        _playlists.value.filter { it.kaynak != null }.forEach { listeyiEsitle(it, sessiz = true) }
    }

    // ---------------- Akıllı karıştırma önerileri ----------------

    private fun aramaIdBul(q: String): String? {
        val req = YoutubeDLRequest("ytsearch1:$q").apply { addOption("--flat-playlist"); addOption("--print", "%(id)s") }
        return YoutubeDL.getInstance().execute(req).out.lines().map { it.trim() }.firstOrNull { it.length == 11 }
    }

    /** Verilen şarkılara benzeyen, kütüphanede olmayan şarkılar (YouTube'un "Mix" listelerinden). */
    suspend fun oneriler(seeds: List<Track>, adet: Int): List<Oneri> = withContext(Dispatchers.IO) {
        ensureYt()
        val have = _tracks.value.flatMap { listOfNotNull(it.videoId, ytId(it.sourceUrl)) }.toMutableSet()
        val out = LinkedHashMap<String, Oneri>()
        for (t in seeds.shuffled().take(4)) {
            val vid = t.videoId ?: ytId(t.sourceUrl)
                ?: runCatching { aramaIdBul("${t.artist} ${t.title}".trim()) }.getOrNull() ?: continue
            val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$vid&list=RD$vid").apply {
                addOption("--flat-playlist"); addOption("-J"); addOption("--playlist-end", "25")
            }
            val json = runCatching {
                val o = YoutubeDL.getInstance().execute(req).out
                JSONObject(o.substring(o.indexOf('{')))
            }.getOrNull() ?: continue
            val entries = json.optJSONArray("entries") ?: continue
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val id = e.optString("id")
                if (id.length != 11 || id == vid || id in have || out.containsKey(id)) continue
                val (ad, sanatci) = ytAdSanatci(e.optString("title"), e.optString("channel").ifBlank { e.optString("uploader") })
                if (ad.isBlank()) continue
                out[id] = Oneri(id, ad, sanatci)
            }
            if (out.size >= adet * 2) break
        }
        out.values.shuffled().take(adet)
    }

    /**
     * Çalan şarkıya göre öneri (YouTube Mix). vid: çalanın YouTube kimliği (biliniyorsa), t: kütüphanedeki şarkı.
     * haric: zaten sırada olan / daha önce önerilmiş kimlikler.
     */
    suspend fun oneriCalandan(vid: String?, t: Track?, adet: Int, haric: Set<String>): List<Oneri> = withContext(Dispatchers.IO) {
        ensureYt()
        val have = _tracks.value.flatMap { listOfNotNull(it.videoId, ytId(it.sourceUrl)) }.toMutableSet()
        val v = vid ?: t?.let { it.videoId ?: ytId(it.sourceUrl) }
            ?: t?.let { runCatching { aramaIdBul("${it.artist} ${it.title}".trim()) }.getOrNull() }
            ?: return@withContext emptyList()
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$v&list=RD$v").apply {
            addOption("--flat-playlist"); addOption("-J"); addOption("--playlist-end", "25")
        }
        val json = runCatching {
            val o = YoutubeDL.getInstance().execute(req).out
            JSONObject(o.substring(o.indexOf('{')))
        }.getOrNull() ?: return@withContext emptyList()
        val entries = json.optJSONArray("entries") ?: return@withContext emptyList()
        val out = ArrayList<Oneri>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id")
            if (id.length != 11 || id == v || id in have || id in haric || out.any { it.videoId == id }) continue
            val (ad, sanatci) = ytAdSanatci(e.optString("title"), e.optString("channel").ifBlank { e.optString("uploader") })
            if (ad.isBlank()) continue
            out += Oneri(id, ad, sanatci)
        }
        // Mix'in ilk sıraları çalana en yakın olanlar; onlardan rastgele seç
        out.take(12).shuffled().take(adet)
    }

    // ---------------- Akıllı karıştırma önbelleği ----------------
    // Öneri şarkıları kütüphaneye inmez; sıraya eklenince geçici olarak önbelleğe iner,
    // çalınıp geçilince (ya da sıradan çıkınca) silinir.
    private val oneriKlasoru: File get() = File(ctx.cacheDir, "oneri").apply { mkdirs() }

    /** Önbelleğe tamamen inmiş öneri dosyası (yoksa null). */
    fun oneriDosyasi(vid: String): File? =
        oneriKlasoru.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$vid.") }

    suspend fun oneriyiOnbellegeAl(vid: String) = withContext(Dispatchers.IO) {
        if (oneriDosyasi(vid) != null) return@withContext
        ensureYt()
        val tmp = File(oneriKlasoru, "_$vid").apply { deleteRecursively(); mkdirs() }
        try {
            val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$vid").apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--no-playlist"); addOption("--no-mtime")
                addOption("-o", "${tmp.absolutePath}/ses.%(ext)s")
            }
            YoutubeDL.getInstance().execute(req, "oneri_$vid") { _, _, _ -> }
            val f = tmp.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?: throw IOException("Öneri önbelleğe inmedi")
            f.renameTo(File(oneriKlasoru, "$vid.${f.extension.ifBlank { "m4a" }}"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Beğenilen öneri: önbelleğe inmişse o dosyayı kütüphaneye indirilmiş şarkı olarak kaydet,
     * henüz inmediyse normal şekilde indir.
     */
    fun oneriyiKaydet(trackId: String, vid: String) {
        scope.launch(Dispatchers.IO) {
            val t = _tracks.value.find { it.id == trackId } ?: return@launch
            if (t.indirildi && fileOf(t).exists()) return@launch
            val f = oneriDosyasi(vid)
            if (f == null) { downloadTracks(listOf(t)); return@launch }
            val target = uniqueFile(sanitize(t.title).ifBlank { "sarki" }, f.extension.ifBlank { "m4a" })
            if (runCatching { f.copyTo(target) }.isFailure) { downloadTracks(listOf(t)); return@launch }
            updateTrack(trackId) { it.copy(fileName = target.name) }
            sesOlcumuBaslat()
        }
    }

    /** Bir önerinin önbellek dosyasını (ve süren indirmesini) sil. */
    fun oneriyiSil(vid: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById("oneri_$vid") }
        oneriKlasoru.listFiles()?.filter { it.name.startsWith("$vid.") || it.name == "_$vid" }?.forEach { it.deleteRecursively() }
    }

    /** "tut" dışındaki tüm öneri önbelleğini sil. */
    fun oneriOnbellegiTemizle(tut: Set<String> = emptySet()) {
        oneriKlasoru.listFiles()?.forEach { f ->
            val vid = f.name.removePrefix("_").substringBefore('.')
            if (vid !in tut) {
                if (f.name.startsWith("_")) runCatching { YoutubeDL.getInstance().destroyProcessById("oneri_$vid") }
                f.deleteRecursively()
            }
        }
    }

    /** Önerilen şarkıyı kütüphaneye (indirmeden) ekler; şarkı kimliğini döndürür. */
    fun oneriyiEkle(o: Oneri): String {
        findExisting("https://www.youtube.com/watch?v=${o.videoId}", o.title, o.artist)?.let { return it.id }
        val newId = UUID.randomUUID().toString()
        addTrack(Track(newId, o.title, o.artist, "", "https://www.youtube.com/watch?v=${o.videoId}",
            System.currentTimeMillis(), null, o.videoId))
        scope.launch { saveCover(newId, "https://i.ytimg.com/vi/${o.videoId}/hqdefault.jpg")?.let { c -> updateTrack(newId) { it.copy(cover = c) } } }
        return newId
    }

    // ---------------- Eski şarkı adlarını düzeltme ----------------

    /** YouTube'dan gelmiş şarkılarda "(Official Video)", "VEVO" gibi ekleri ve "Sanatçı - Şarkı" adlarını düzeltme önerileri. */
    fun adDuzeltmeleri(): List<AdDuzeltme> = _tracks.value.mapNotNull { t ->
        val youtubeKaynakli = isYouTube(t.sourceUrl)
        if (!youtubeKaynakli) return@mapNotNull null
        var ad = temizBaslik(t.title).ifBlank { t.title }
        var sanatci = temizKanal(t.artist)
        val sep = listOf(" - ", " – ", " — ").firstOrNull { ad.contains(it) }
        if (sep != null) {
            val l = ad.substringBefore(sep).trim(); val r = ad.substringAfter(sep).trim()
            // "Şarkı - Remastered 2011" gibi ekler sanatçı-şarkı ayrımı değildir
            val surumEki = Regex("^(\\d{4}\\b|(remaster(ed)?|live|radio edit|single version|album version|mono|stereo|demo|acoustic|instrumental|bonus track|remix|edit)\\b)", RegexOption.IGNORE_CASE)
            // Sadece sanatçı alanı bir kanal adı gibiyse ya da soldaki adla eşleşiyorsa böl (temkinli)
            val kanalGibi = t.artist.isBlank() || Regex("(?i)(vevo|- topic|official|records|music)$").containsMatchIn(t.artist.trim()) ||
                norm(l) == norm(sanatci) || (norm(sanatci).length >= 3 && (norm(l).contains(norm(sanatci)) || norm(sanatci).contains(norm(l))))
            if (l.isNotEmpty() && r.isNotEmpty() && !surumEki.containsMatchIn(r) && kanalGibi) { sanatci = l; ad = r }
        }
        if (ad == t.title && sanatci == t.artist) null else AdDuzeltme(t, ad, sanatci)
    }

    fun adlariDuzelt(list: List<AdDuzeltme>) = synchronized(lock) {
        val m = list.associateBy { it.track.id }
        _tracks.value = _tracks.value.map { t -> m[t.id]?.let { t.copy(title = it.yeniAd, artist = it.yeniSanatci) } ?: t }
        saveTracks()
    }

    // ---------------- Ses dengeleme (ses seviyesi ölçümü) ----------------

    private var olcumIsi: kotlinx.coroutines.Job? = null

    /** İndirilmiş ama sesi ölçülmemiş şarkıları arka planda tek tek ölçer. */
    fun sesOlcumuBaslat() {
        // Sadece "Ses dengeleme" açıksa ölç (gereksiz pil harcamasın)
        if (!ctx.getSharedPreferences("oynatici", Context.MODE_PRIVATE).getBoolean("normalize", false)) return
        if (olcumIsi?.isActive == true) return
        olcumIsi = scope.launch(Dispatchers.Default) {
            while (true) {
                val t = _tracks.value.firstOrNull { it.indirildi && it.kazancDb == null && fileOf(it).exists() } ?: break
                val g = sesOlc(fileOf(t)) ?: 0f
                updateTrack(t.id) { it.copy(kazancDb = g) }
            }
        }
    }

    /** Dosyayı çözüp ortalama ses seviyesini (RMS) ölçer, hedef seviyeye göre kazancı (dB) verir. */
    private fun sesOlc(f: File): Float? = runCatching {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val ti = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); return null }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val codec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = android.media.MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sumSq = 0.0
        var n = 0L
        val deadline = System.currentTimeMillis() + 90_000
        try {
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sb = ob.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var k = 0
                        while (k < sb.remaining()) {
                            val v = sb.get(k) / 32768.0
                            sumSq += v * v; n++
                            k += 4 // her 4 örnekten birini say (yeterince doğru, daha hızlı)
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { ex.release() }
        }
        if (n == 0L || sumSq <= 0.0) return null
        val db = 20 * kotlin.math.log10(kotlin.math.sqrt(sumSq / n))
        (HEDEF_RMS_DB - db).toFloat().coerceIn(-12f, 6f)
    }.getOrNull()

    private val HEDEF_RMS_DB = -18.0

    fun kazancOf(trackId: String): Float? = _tracks.value.find { it.id == trackId }?.kazancDb

    // ---------------- Önerilen şarkı akışı ----------------

    /** Kütüphanede olmayan (öneri) şarkının internet adresi. */
    fun streamUrlVideo(videoId: String): String {
        val key = "yt:$videoId"
        streamCache[key]?.let { if (it.second > System.currentTimeMillis()) return it.first }
        kotlinx.coroutines.runBlocking { ensureYt() }
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio"); addOption("--no-playlist"); addOption("--print", "urls")
        }
        val url = YoutubeDL.getInstance().execute(req).out.lines().map { it.trim() }.firstOrNull { it.startsWith("http") }
            ?: throw IOException("Akış adresi bulunamadı")
        streamCache[key] = url to System.currentTimeMillis() + 4 * 3600_000L
        return url
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
                            "<h2>Spotify girişi tamam</h2><p><a style='color:#1db954' href='submuzik://spotify-callback?done=1'>SubPlay'e dön</a></p></body></html>"
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

    fun importLikedSongs(indir: Boolean = true) {
        val id = newTask("spotify:liked", "Beğenilen şarkılar okunuyor…")
        scope.launch {
            try {
                val items = SpotifyApi.likedSongs(ctx)
                if (items.isEmpty()) throw IOException("Beğenilen şarkı bulunamadı")
                val plName = "Beğenilen şarkılar"
                val plId = playlistByName(plName)
                var added = 0
                items.forEachIndexed { i, (title, artist) ->
                    val label = if (artist.isBlank()) title else "$artist - $title"
                    if (enqueueYt("ytsearch1:$label audio", title, artist, plId, label, order = i, indir = indir)) added++
                }
                removeTask(id)
                _notices.tryEmit("${items.size} beğenilen şarkı bulundu. $added tanesi ${if (indir) "indiriliyor" else "kütüphaneye eklendi"}, ${items.size - added} tanesi zaten vardı.")
            } catch (e: Exception) {
                fail(id, if (e is SpotifyApi.Yasak) IOException("Spotify beğenilen şarkıları vermedi: ${e.message}") else e) { importLikedSongs(indir) }
            }
        }
    }

    private fun expandSpotify(url: String, indir: Boolean = true, sessiz: Boolean = false, hedef: String? = null) {
        val id = newTask(url, if (sessiz) "Liste eşitleniyor…" else "Spotify okunuyor…")
        scope.launch {
            try {
                val (name, items, cover) = resolveSpotify(url)
                if (items.isEmpty()) throw IOException("Spotify'da şarkı bulunamadı")
                val plName = name.ifBlank { "Spotify listesi" }
                val plId = hedef?.takeIf { h -> _playlists.value.any { it.id == h } }
                    ?: if (items.size > 1) playlistByName(plName) else null
                if (plId != null && url.contains("/playlist/")) setKaynak(plId, url)
                var added = 0
                items.forEachIndexed { i, (title, artist) ->
                    val label = if (artist.isBlank()) title else "$artist - $title"
                    if (enqueueYt("ytsearch1:$label audio", title, artist, plId, label, cover, i, indir = indir)) added++
                }
                removeTask(id)
                val skipped = items.size - added
                if (sessiz) {
                    if (added > 0) _notices.tryEmit("\"$plName\" eşitlendi: $added yeni şarkı eklendi.")
                } else if (url.contains("/playlist/") && items.size == 100 && !SpotifyApi.bagli.value) {
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
                if (sessiz) removeTask(id) else fail(id, e) { expandSpotify(url, indir) }
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
            } catch (e: SpotifyApi.Bulunamadi) {
                throw IOException("Spotify'da bu liste bulunamadı. Silinmiş ya da link hatalı olabilir.")
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

    private fun download(taskId: String, rawUrl: String, customTitle: String?, existingId: String? = null) {
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

        if (existingId != null) {
            val cov = saveEmbeddedCover(existingId, target)
            updateTrack(existingId) { it.copy(fileName = target.name, cover = it.cover ?: cov) }
            return
        }
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

    /** Sadece telefondaki dosyayı siler; şarkı kütüphanede kalır (internetten çalınabilir, tekrar indirilebilir). */
    fun deleteFile(t: Track) = synchronized(lock) {
        if (t.fileName.isNotBlank()) File(musicDir, t.fileName).delete()
        _tracks.value = _tracks.value.map { if (it.id == t.id) it.copy(fileName = "") else it }
        saveTracks()
    }

    /** Şarkıyı kütüphaneden tamamen kaldırır (dosyası, kapağı ve listelerdeki yeri dahil). */
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
        _playlists.value.find { it.id == id }?.cover?.let { File(coversDir, it).delete() }
        _playlists.value = _playlists.value.filterNot { it.id == id }
        savePlaylists()
    }

    fun addToPlaylist(playlistId: String, trackId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId && trackId !in it.trackIds) it.copy(trackIds = it.trackIds + trackId) else it
        }
        savePlaylists()
    }

    fun playlistCover(p: Playlist): File? = p.cover?.let { File(coversDir, it) }?.takeIf { it.exists() }

    /** Galeriden seçilen resmi listenin kapağı yapar (uri null ise kapağı kaldırır). */
    fun setPlaylistCover(playlistId: String, uri: android.net.Uri?) {
        scope.launch {
            val newName = uri?.let {
                runCatching {
                    val name = "pl_${playlistId}_${System.currentTimeMillis()}.img"
                    ctx.contentResolver.openInputStream(it)!!.use { i -> File(coversDir, name).outputStream().use { o -> i.copyTo(o) } }
                    name
                }.getOrNull() ?: run { _notices.tryEmit("Resim okunamadı"); return@launch }
            }
            synchronized(lock) {
                _playlists.value.find { it.id == playlistId }?.cover?.let { File(coversDir, it).delete() }
                _playlists.value = _playlists.value.map { if (it.id == playlistId) it.copy(cover = newName) else it }
                savePlaylists()
            }
        }
    }

    // Sıralama tercihleri (ekran başına)
    fun siralama(key: String, varsayilan: Siralama): Siralama =
        runCatching { Siralama.valueOf(prefs.getString("sirala_$key", varsayilan.name)!!) }.getOrDefault(varsayilan)
    fun setSiralama(key: String, s: Siralama) = prefs.edit().putString("sirala_$key", s.name).apply()

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
            val file = o.optString("fileName")
            Track(o.getString("id"), o.getString("title"), o.optString("artist"),
                // Dosyası telefondan silinmiş şarkılar kütüphanede kalır, "indirilmemiş" olur
                if (file.isNotBlank() && File(musicDir, file).exists()) file else "",
                o.optString("sourceUrl"), o.optLong("addedAt"),
                o.optString("cover").ifBlank { null }, o.optString("videoId").ifBlank { null },
                o.optString("album").ifBlank { null },
                if (o.has("kazanc")) o.optDouble("kazanc").toFloat() else null)
        }
    }.getOrDefault(emptyList())

    private fun saveTracks() {
        val arr = JSONArray()
        _tracks.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("fileName", it.fileName).put("sourceUrl", it.sourceUrl).put("addedAt", it.addedAt)
                .put("cover", it.cover ?: "").put("videoId", it.videoId ?: "").put("album", it.album ?: "")
                .apply { it.kazancDb?.let { k -> put("kazanc", k.toDouble()) } })
        }
        libFile.writeText(arr.toString())
    }

    private fun loadPlaylists(): List<Playlist> = runCatching {
        val arr = JSONArray(plFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("trackIds")
            Playlist(o.getString("id"), o.getString("name"), (0 until ids.length()).map { ids.getString(it) },
                o.optString("cover").ifBlank { null }, o.optString("kaynak").ifBlank { null },
                o.optJSONArray("karisim")?.let { k -> (0 until k.length()).map { k.getString(it) } })
        }
    }.getOrDefault(emptyList())

    private fun savePlaylists() {
        karisimlariHesapla()
        val arr = JSONArray()
        _playlists.value.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("trackIds", JSONArray(p.trackIds))
                .put("cover", p.cover ?: "").put("kaynak", p.kaynak ?: "")
                .apply { p.karisim?.let { put("karisim", JSONArray(it)) } })
        }
        plFile.writeText(arr.toString())
    }

    // ================= Karışım listeleri (Spotify Blend gibi) =================

    /**
     * Karışımın şarkıları: kaynak listelerden sırayla birer şarkı (A1, B1, A2, B2…); iki listede de olan
     * şarkı bir kez yer alır ve öne alınır. Kaynak listeler değişince karışım da kendiliğinden güncellenir.
     */
    private fun karisimSarkilari(p: Playlist, hepsi: List<Playlist>, var_: Set<String>): List<String> {
        val kaynaklar = p.karisim.orEmpty().mapNotNull { id -> hepsi.find { it.id == id && it.karisim == null } }
            .map { k -> k.trackIds.filter { it in var_ } }
        if (kaynaklar.isEmpty()) return emptyList()
        val ortak = if (kaynaklar.size > 1) kaynaklar.drop(1).fold(kaynaklar.first().toSet()) { a, l -> a intersect l.toSet() } else emptySet()
        val sonuc = LinkedHashSet<String>()
        // Önce ortak zevk: hepsinde olanlar
        kaynaklar.first().filter { it in ortak }.forEach { sonuc += it }
        val enUzun = kaynaklar.maxOf { it.size }
        for (i in 0 until enUzun) for (l in kaynaklar) l.getOrNull(i)?.let { sonuc += it }
        return sonuc.toList()
    }

    private fun karisimlariHesapla() {
        val hepsi = _playlists.value
        if (hepsi.none { it.karisim != null }) return
        val var_ = _tracks.value.mapTo(HashSet()) { it.id }
        _playlists.value = hepsi.map { p ->
            if (p.karisim == null) p
            else karisimSarkilari(p, hepsi, var_).let { y -> if (y == p.trackIds) p else p.copy(trackIds = y) }
        }
    }

    /** İki (ya da daha çok) listeyi karıştıran yeni liste oluşturur. */
    fun karisimOlustur(ad: String, kaynaklar: List<String>): String = synchronized(lock) {
        val p = Playlist(UUID.randomUUID().toString(), ad.trim(), emptyList(), karisim = kaynaklar.distinct())
        _playlists.value = _playlists.value + p
        savePlaylists()
        p.id
    }

    fun karisimiDuzenle(id: String, kaynaklar: List<String>) = synchronized(lock) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(karisim = kaynaklar.distinct()) else it }
        savePlaylists()
    }

    /** Karışımı sabitle: şarkılar olduğu gibi kalır, artık kendiliğinden değişmez, elle düzenlenebilir. */
    fun karisimiNormaleCevir(id: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(karisim = null) else it }
        savePlaylists()
    }

    // ================= Konfor özellikleri =================

    /** Listelerde kapak: diske bakmadan (eksikse Coil boş gösterir). Kaydırmayı hızlandırır. */
    fun kapakDosyasi(t: Track): File? = t.cover?.let { File(coversDir, it) }

    /** Şarkıyı (ya da akıllı karıştırma önerisini) beğen / vazgeç. Podcast için null. */
    fun begen(mediaId: String, title: String, artist: String): Boolean? {
        if (mediaId.startsWith(PodcastRepo.ON_EK)) return null
        // Akıllı karıştırma önerisi ya da aramadan dinlenen (önizleme) şarkı: önce kütüphaneye eklenir
        val oneri = mediaId.startsWith("oneri:") || mediaId.startsWith("yt:")
        val vid = mediaId.removePrefix("oneri:").removePrefix("yt:")
        val id = if (oneri) oneriyiEkle(Oneri(vid, title, artist)) else mediaId
        val b = toggleLike(id)
        // Akıllı karıştırma önerisi önbellekten kütüphaneye iner; aramadan dinlenen sadece eklenir (indirme kararı senin)
        if (mediaId.startsWith("oneri:") && b) oneriyiKaydet(id, vid)
        return b
    }

    fun begenildiMi(mediaId: String?): Boolean {
        if (mediaId == null) return false
        val liked = _playlists.value.find { it.id == BEGENI_ID }?.trackIds?.toSet() ?: return false
        if (mediaId.startsWith("oneri:") || mediaId.startsWith("yt:")) {
            val vid = mediaId.removePrefix("oneri:").removePrefix("yt:")
            return _tracks.value.any { it.videoId == vid && it.id in liked }
        }
        return mediaId in liked
    }

    // ---- Geri alınabilir kaldırma: dosyalar birkaç saniye sonra silinir, "Geri al" her şeyi döndürür ----
    private val bekleyenSilmeler = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private var silmeNo = 0L

    /** Şarkıları kütüphaneden kaldırır; geri alma işlevini döndürür. */
    fun kaldirGeriAlinabilir(list: List<Track>): () -> Unit = synchronized(lock) {
        val ids = list.map { it.id }.toSet()
        val uyelik = _playlists.value.associate { pl ->
            pl.id to pl.trackIds.withIndex().filter { it.value in ids }.map { it.index to it.value }
        }.filterValues { it.isNotEmpty() }
        _tracks.value = _tracks.value.filterNot { it.id in ids }
        _playlists.value = _playlists.value.map { pl -> pl.copy(trackIds = pl.trackIds.filterNot { it in ids }) }
        saveTracks(); savePlaylists()
        val no = ++silmeNo
        bekleyenSilmeler[no] = scope.launch {
            kotlinx.coroutines.delay(8000)
            list.forEach { t -> fileOf(t).delete(); t.cover?.let { File(coversDir, it).delete() } }
            bekleyenSilmeler.remove(no)
        }
        return@synchronized {
            synchronized(lock) {
                bekleyenSilmeler.remove(no)?.cancel()
                val var_ = _tracks.value.map { it.id }.toSet()
                _tracks.value = (_tracks.value + list.filter { it.id !in var_ }).sortedByDescending { it.addedAt }
                _playlists.value = _playlists.value.map { pl ->
                    val u = uyelik[pl.id] ?: return@map pl
                    val l = pl.trackIds.toMutableList()
                    u.sortedBy { it.first }.forEach { (i, id) -> if (id !in l) l.add(i.coerceAtMost(l.size), id) }
                    pl.copy(trackIds = l)
                }
                saveTracks(); savePlaylists()
            }
        }
    }

    /** Listeyi siler; geri alma işlevini döndürür. */
    fun listeSilGeriAlinabilir(id: String): () -> Unit = synchronized(lock) {
        val idx = _playlists.value.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized {}
        val pl = _playlists.value[idx]
        _playlists.value = _playlists.value.filterNot { it.id == id }
        savePlaylists()
        val no = ++silmeNo
        bekleyenSilmeler[no] = scope.launch {
            kotlinx.coroutines.delay(8000)
            pl.cover?.let { File(coversDir, it).delete() }
            bekleyenSilmeler.remove(no)
        }
        return@synchronized {
            synchronized(lock) {
                bekleyenSilmeler.remove(no)?.cancel()
                if (_playlists.value.none { it.id == id }) {
                    val l = _playlists.value.toMutableList(); l.add(idx.coerceAtMost(l.size), pl)
                    _playlists.value = l; savePlaylists()
                }
            }
        }
    }

    /** Şarkıları listeden çıkarır; geri alma işlevini döndürür. */
    fun listedenCikarGeriAlinabilir(playlistId: String, trackIds: Collection<String>): () -> Unit = synchronized(lock) {
        val pl = _playlists.value.find { it.id == playlistId } ?: return@synchronized {}
        val eski = pl.trackIds
        _playlists.value = _playlists.value.map { if (it.id == playlistId) it.copy(trackIds = it.trackIds.filterNot { t -> t in trackIds }) else it }
        savePlaylists()
        return@synchronized {
            synchronized(lock) {
                _playlists.value = _playlists.value.map { p ->
                    if (p.id != playlistId) p
                    else {
                        // Bu arada eklenenleri koru, çıkarılanları eski yerlerine koy
                        val sonradan = p.trackIds.filter { it !in eski }
                        p.copy(trackIds = eski.filter { it in p.trackIds || it in trackIds } + sonradan)
                    }
                }
                savePlaylists()
            }
        }
    }

    // ---- Sabit listeler ve son açılan listeler ----
    private val _sabitler = MutableStateFlow(prefs.getStringSet("sabit_listeler", emptySet())!!.toSet())
    val sabitler = _sabitler.asStateFlow()
    fun sabitle(id: String, on: Boolean) {
        _sabitler.value = if (on) _sabitler.value + id else _sabitler.value - id
        prefs.edit().putStringSet("sabit_listeler", _sabitler.value).apply()
    }

    private val _sonListeler = MutableStateFlow(prefs.getString("son_listeler", "")!!.split(',').filter { it.isNotBlank() })
    val sonListeler = _sonListeler.asStateFlow()
    fun listeAcildi(id: String) {
        val l = (listOf(id) + (_sonListeler.value - id)).take(8)
        if (l == _sonListeler.value) return
        _sonListeler.value = l
        prefs.edit().putString("son_listeler", l.joinToString(",")).apply()
    }

    // ---- Çift şarkılar ----
    private fun ciftAnahtar(t: Track): String {
        fun temiz(x: String) = x.lowercase(java.util.Locale("tr"))
            .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
            .replace(Regex("official|video|audio|lyrics?|music|hd|4k|remaster(ed)?"), "")
            .filter { it.isLetterOrDigit() }
        return temiz(t.title) + "|" + temiz(anaSanatci(t.artist))
    }

    /** Aynı şarkının birden çok kaydı (aynı YouTube kimliği ya da aynı ad + sanatçı). */
    fun ciftler(): List<List<Track>> {
        val lib = _tracks.value
        val ebeveyn = HashMap<String, String>()
        fun bul(x: String): String { var r = x; while (ebeveyn[r] != null && ebeveyn[r] != r) r = ebeveyn[r]!!; return r }
        fun birles(a: String, b: String) { val ra = bul(a); val rb = bul(b); if (ra != rb) ebeveyn[ra] = rb }
        lib.forEach { ebeveyn[it.id] = it.id }
        lib.groupBy { it.videoId ?: ytId(it.sourceUrl) }.forEach { (k, l) -> if (k != null && l.size > 1) l.zipWithNext { a, b -> birles(a.id, b.id) } }
        lib.groupBy { ciftAnahtar(it) }.forEach { (k, l) -> if (k.length > 3 && l.size > 1) l.zipWithNext { a, b -> birles(a.id, b.id) } }
        return lib.groupBy { bul(it.id) }.values.filter { it.size > 1 }
    }

    /** Her gruptan en iyisini (indirilmiş, kapaklı, en eski) tutar; listelerdeki yerini ve beğenisini korur. */
    fun ciftleriBirlestir(gruplar: List<List<Track>>): Int = synchronized(lock) {
        var silinen = 0
        val idDegis = HashMap<String, String>()
        val silinecekDosyalar = ArrayList<File>()
        var tracks = _tracks.value
        for (g in gruplar) {
            val guncel = g.mapNotNull { t -> tracks.find { it.id == t.id } }
            if (guncel.size < 2) continue
            var tut = guncel.sortedWith(compareByDescending<Track> { if (it.indirildi && fileOf(it).exists()) 1 else 0 }
                .thenByDescending { if (it.cover != null) 1 else 0 }.thenBy { it.addedAt }).first()
            val digerleri = guncel - tut
            if (!tut.indirildi) digerleri.firstOrNull { it.indirildi && fileOf(it).exists() }?.let { d -> tut = tut.copy(fileName = d.fileName) }
            if (tut.cover == null) digerleri.firstOrNull { it.cover != null }?.let { d -> tut = tut.copy(cover = d.cover) }
            if (tut.album == null) tut = tut.copy(album = digerleri.firstNotNullOfOrNull { it.album })
            digerleri.forEach { d ->
                idDegis[d.id] = tut.id
                if (d.fileName.isNotBlank() && d.fileName != tut.fileName) silinecekDosyalar += File(musicDir, d.fileName)
                if (d.cover != null && d.cover != tut.cover) silinecekDosyalar += File(coversDir, d.cover)
            }
            val sabit = tut
            tracks = tracks.filterNot { t -> digerleri.any { it.id == t.id } }.map { if (it.id == sabit.id) sabit else it }
            silinen += digerleri.size
        }
        _tracks.value = tracks
        _playlists.value = _playlists.value.map { pl -> pl.copy(trackIds = pl.trackIds.map { idDegis[it] ?: it }.distinct()) }
        saveTracks(); savePlaylists()
        silinecekDosyalar.forEach { it.delete() }
        silinen
    }

    // ---- Depolama ----
    data class Depolama(val sarkiBayt: Long, val indirilen: Int, val podcastBayt: Long, val onbellekBayt: Long, val kapakBayt: Long)

    private fun boyut(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { boyut(it) } ?: 0L

    suspend fun depolama(): Depolama = withContext(Dispatchers.IO) {
        Depolama(boyut(musicDir), _tracks.value.count { it.indirildi }, boyut(PodcastRepo.get(ctx).klasor),
            boyut(ctx.cacheDir), boyut(coversDir))
    }

    /** "gun" gündür dinlenmemiş (ya da hiç dinlenmemiş ve o kadar önce eklenmiş) indirmeler ve boyutları. */
    suspend fun dinlenmeyenIndirmeler(gun: Int): List<Pair<Track, Long>> = withContext(Dispatchers.IO) {
        val son = Istatistik.get(ctx).sonDinleme()
        val sinir = System.currentTimeMillis() - gun * 86_400_000L
        _tracks.value.filter { it.indirildi && (son[it.id] ?: it.addedAt) < sinir }
            .mapNotNull { t -> fileOf(t).takeIf { it.exists() }?.let { t to it.length() } }
            .sortedByDescending { it.second }
    }

    /** Geçici dosyaları (kapak önbelleği, inmiş güncellemeler) temizle. Öneri önbelleği çalarken korunur. */
    suspend fun onbellegiTemizle() = withContext(Dispatchers.IO) {
        ctx.cacheDir.listFiles()?.forEach { if (it.name != "oneri") it.deleteRecursively() }
    }
}
