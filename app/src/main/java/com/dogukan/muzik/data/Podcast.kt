package com.dogukan.muzik.data

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Xml
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dogukan.muzik.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Podcast(
    val id: String,
    val ad: String,
    val yazar: String,
    val kapak: String?,
    val feed: String,
    val eklenme: Long,
    val sonKontrol: Long = 0,
    val kategori: Int? = null, // Apple Podcasts kategori numarası (öneriler için)
    val dil: String? = null,   // "tr", "en"...
)

data class Bolum(
    val id: String,
    val podcastId: String,
    val baslik: String,
    val aciklama: String,
    val tarih: Long,
    val sureSn: Int,
    val ses: String,
    val kapak: String? = null,
    val konumMs: Long = 0,
    val dinlendi: Boolean = false,
    val dosya: String? = null,   // indirilmiş dosya adı (Podcastler klasöründe)
    val indirmeNo: Long? = null, // süren indirme (DownloadManager)
    val yeni: Boolean = false,   // abone olduktan sonra gelen, henüz açılmamış bölüm
)

data class PodcastArama(val ad: String, val yazar: String, val kapak: String?, val feed: String)

/** Apple Podcasts kategorileri (öneriler). en: RSS'teki İngilizce adları (eşleştirmek için). */
data class Kategori(val id: Int, val ad: String, val en: List<String>)

val KATEGORILER = listOf(
    Kategori(1324, "Toplum ve Kültür", listOf("Society & Culture", "Documentary", "Personal Journals", "Philosophy", "Places & Travel", "Relationships")),
    Kategori(1303, "Komedi", listOf("Comedy", "Comedy Interviews", "Improv", "Stand-Up")),
    Kategori(1318, "Teknoloji", listOf("Technology")),
    Kategori(1533, "Bilim", listOf("Science", "Astronomy", "Chemistry", "Earth Sciences", "Life Sciences", "Mathematics", "Natural Sciences", "Nature", "Physics", "Social Sciences")),
    Kategori(1487, "Tarih", listOf("History")),
    Kategori(1512, "Sağlık ve Spor", listOf("Health & Fitness", "Alternative Health", "Fitness", "Medicine", "Mental Health", "Nutrition", "Sexuality")),
    Kategori(1304, "Eğitim", listOf("Education", "Courses", "How To", "Language Learning", "Self-Improvement")),
    Kategori(1321, "İş Dünyası", listOf("Business", "Careers", "Entrepreneurship", "Investing", "Management", "Marketing", "Non-Profit")),
    Kategori(1488, "Gerçek Suç", listOf("True Crime")),
    Kategori(1489, "Haber", listOf("News", "Business News", "Daily News", "Entertainment News", "News Commentary", "Politics", "Sports News", "Tech News")),
    Kategori(1545, "Spor", listOf("Sports", "Baseball", "Basketball", "Football", "Soccer", "Fantasy Sports", "Wrestling", "Running")),
    Kategori(1310, "Müzik", listOf("Music", "Music Commentary", "Music History", "Music Interviews")),
    Kategori(1309, "Dizi ve Film", listOf("TV & Film", "After Shows", "Film History", "Film Interviews", "Film Reviews", "TV Reviews")),
    Kategori(1301, "Sanat", listOf("Arts", "Books", "Design", "Fashion & Beauty", "Food", "Performing Arts", "Visual Arts")),
    Kategori(1502, "Hobi ve Oyun", listOf("Leisure", "Video Games", "Games", "Hobbies", "Animation & Manga", "Automotive", "Aviation", "Crafts", "Home & Garden")),
    Kategori(1483, "Kurgu", listOf("Fiction", "Drama", "Science Fiction", "Comedy Fiction")),
    Kategori(1314, "Din ve Maneviyat", listOf("Religion & Spirituality", "Spirituality", "Islam", "Christianity", "Buddhism", "Hinduism", "Judaism", "Religion")),
    Kategori(1305, "Çocuk ve Aile", listOf("Kids & Family", "Parenting", "Stories for Kids", "Education for Kids", "Pets & Animals")),
)

fun kategoriBul(ingilizce: String?): Int? = ingilizce?.trim()?.let { ad ->
    KATEGORILER.firstOrNull { k -> k.en.any { it.equals(ad, ignoreCase = true) } }?.id
}

/** Podcast abonelikleri, bölümler, kaldığı yer ve indirmeler. Şarkı kütüphanesinden ayrı tutulur. */
class PodcastRepo private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var inst: PodcastRepo? = null
        fun get(c: Context) = inst ?: synchronized(this) { inst ?: PodcastRepo(c.applicationContext).also { inst = it } }
        const val ON_EK = "pod:"
        fun idOf(mediaId: String) = mediaId.removePrefix(ON_EK)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val dosyaP = File(ctx.filesDir, "podcastlar.json")
    private val dosyaB = File(ctx.filesDir, "bolumler.json")
    val klasor: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Podcastler").apply { mkdirs() }
    val prefs = ctx.getSharedPreferences("podcast", Context.MODE_PRIVATE)

    private val _podcastlar = MutableStateFlow(okuP())
    val podcastlar = _podcastlar.asStateFlow()
    private val _bolumler = MutableStateFlow(okuB())
    val bolumler = _bolumler.asStateFlow()
    /** Başka bir ekrandan (genel arama) açılması istenen podcast. */
    val acilacak = MutableStateFlow<String?>(null)
    private val _yenileniyor = MutableStateFlow(false)
    val yenileniyor = _yenileniyor.asStateFlow()

    /** Yeni bölümleri Wi-Fi'deyken kendiliğinden indir. */
    var otoIndir: Boolean
        get() = prefs.getBoolean("oto_indir", false)
        set(v) { prefs.edit().putBoolean("oto_indir", v).apply() }

    /** Dinlenip biten bölümün indirilen dosyasını sil. */
    var otoSil: Boolean
        get() = prefs.getBoolean("oto_sil", false)
        set(v) { prefs.edit().putBoolean("oto_sil", v).apply() }

    var hiz: Float
        get() = prefs.getFloat("hiz", 1f)
        set(v) { prefs.edit().putFloat("hiz", v).apply() }

    // ---------------- Kayıt ----------------

    private fun okuP(): List<Podcast> = runCatching {
        val a = JSONArray(dosyaP.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Podcast(o.getString("id"), o.optString("ad"), o.optString("yazar"), o.optString("kapak").ifBlank { null },
                o.getString("feed"), o.optLong("eklenme"), o.optLong("sonKontrol"),
                if (o.has("kategori")) o.optInt("kategori") else null, o.optString("dil").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    private fun okuB(): List<Bolum> = runCatching {
        val a = JSONArray(dosyaB.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Bolum(o.getString("id"), o.getString("podcastId"), o.optString("baslik"), o.optString("aciklama"),
                o.optLong("tarih"), o.optInt("sureSn"), o.getString("ses"), o.optString("kapak").ifBlank { null },
                o.optLong("konumMs"), o.optBoolean("dinlendi"), o.optString("dosya").ifBlank { null },
                if (o.has("indirmeNo")) o.optLong("indirmeNo") else null, o.optBoolean("yeni"))
        }
    }.getOrDefault(emptyList())

    private fun kaydetP() {
        val a = JSONArray()
        _podcastlar.value.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("ad", p.ad).put("yazar", p.yazar).put("kapak", p.kapak ?: "")
                .put("feed", p.feed).put("eklenme", p.eklenme).put("sonKontrol", p.sonKontrol).put("dil", p.dil ?: "")
                .apply { p.kategori?.let { put("kategori", it) } })
        }
        dosyaP.writeText(a.toString())
    }

    private fun kaydetB() {
        val a = JSONArray()
        _bolumler.value.forEach { b ->
            a.put(JSONObject().put("id", b.id).put("podcastId", b.podcastId).put("baslik", b.baslik)
                .put("aciklama", b.aciklama.take(4000)).put("tarih", b.tarih).put("sureSn", b.sureSn).put("ses", b.ses)
                .put("kapak", b.kapak ?: "").put("konumMs", b.konumMs).put("dinlendi", b.dinlendi).put("dosya", b.dosya ?: "")
                .put("yeni", b.yeni).apply { b.indirmeNo?.let { put("indirmeNo", it) } })
        }
        dosyaB.writeText(a.toString())
    }

    private var bekleyenKayit: kotlinx.coroutines.Job? = null

    /** Bölüm listesini 20 sn içinde bir kez, arka planda yaz (sık konum güncellemeleri için). */
    private fun kaydetSonra() {
        if (bekleyenKayit?.isActive == true) return
        bekleyenKayit = scope.launch {
            kotlinx.coroutines.delay(20_000)
            synchronized(lock) { kaydetB() }
        }
    }

    /** Bekleyen kaydı hemen yaz (uygulama kapanırken, çalma dururken). */
    fun kaydetSimdi() {
        bekleyenKayit?.cancel(); bekleyenKayit = null
        synchronized(lock) { kaydetB() }
    }

    private fun bolumGuncelle(id: String, kaydet: Boolean = true, f: (Bolum) -> Bolum) = synchronized(lock) {
        _bolumler.value = _bolumler.value.map { if (it.id == id) f(it) else it }
        if (kaydet) kaydetB()
    }

    fun bolum(id: String) = _bolumler.value.find { it.id == id }
    fun podcast(id: String) = _podcastlar.value.find { it.id == id }
    fun bolumleri(podcastId: String) = _bolumler.value.filter { it.podcastId == podcastId }.sortedByDescending { it.tarih }
    fun dosyaOf(b: Bolum): File? = b.dosya?.let { File(klasor, it) }?.takeIf { it.exists() && b.indirmeNo == null }

    // ---------------- Ağ ----------------

    private fun getir(url: String): String {
        var u = url
        repeat(5) {
            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SubPlay/1 (Android; podcast)")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) { u = conn.getHeaderField("Location")?.let { URL(URL(u), it).toString() } ?: throw IOException("Yönlendirme hatası"); return@repeat }
                if (code !in 200..299) throw IOException("Sunucu yanıt vermedi (HTTP $code)")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
        }
        throw IOException("Çok fazla yönlendirme")
    }

    /** Apple Podcasts dizininde ara (ücretsiz, anahtarsız). */
    suspend fun ara(q: String): List<PodcastArama> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?media=podcast&limit=25&term=" + URLEncoder.encode(q, "UTF-8")
        val a = JSONObject(getir(url)).optJSONArray("results") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val feed = o.optString("feedUrl").ifBlank { return@mapNotNull null }
            PodcastArama(o.optString("collectionName"), o.optString("artistName"),
                o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null }, feed)
        }
    }

    private class Akis(val ad: String, val yazar: String, val kapak: String?, val bolumler: List<Bolum>,
        val kategori: Int? = null, val dil: String? = null)

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    private val tarihBicimleri = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssXXX")

    private fun tarihCoz(s: String): Long {
        val t = s.trim()
        for (f in tarihBicimleri) runCatching { return SimpleDateFormat(f, Locale.US).parse(t)!!.time }
        return 0L
    }

    private fun sureCoz(s: String): Int {
        val p = s.trim().split(':').mapNotNull { it.trim().toDoubleOrNull()?.toInt() }
        return when (p.size) { 1 -> p[0]; 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> 0 }
    }

    private fun temizle(html: String) = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
        .toString().replace(Regex("\\n{3,}"), "\n\n").trim()

    /** RSS akışını okur. */
    private fun akisOku(feed: String, podcastId: String): Akis {
        val xml = getir(feed)
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var ad = ""; var yazar = ""; var kapak: String? = null; var kategori: Int? = null; var dil: String? = null
        val list = ArrayList<Bolum>()
        var item = false
        var bBaslik = ""; var bAcik = ""; var bTarih = 0L; var bSure = 0; var bSes = ""; var bGuid = ""; var bKapak: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item", "entry" -> { item = true; bBaslik = ""; bAcik = ""; bTarih = 0; bSure = 0; bSes = ""; bGuid = ""; bKapak = null }
                    "title" -> { val t = runCatching { p.nextText() }.getOrDefault(""); if (item) bBaslik = t else if (ad.isBlank()) ad = t }
                    "itunes:author" -> { val t = runCatching { p.nextText() }.getOrDefault(""); if (!item && yazar.isBlank()) yazar = t }
                    "itunes:category" -> if (!item && kategori == null) kategori = kategoriBul(p.getAttributeValue(null, "text"))
                    "language" -> if (!item && dil == null) dil = runCatching { p.nextText() }.getOrNull()?.trim()?.lowercase()?.take(2)?.ifBlank { null }
                    "itunes:image" -> { val h = p.getAttributeValue(null, "href"); if (item) bKapak = h else if (kapak == null) kapak = h }
                    "url" -> if (!item && kapak == null) kapak = runCatching { p.nextText() }.getOrNull()?.trim()?.ifBlank { null }
                    "description", "itunes:summary", "content:encoded" -> if (item) {
                        val t = runCatching { p.nextText() }.getOrDefault("")
                        if (t.length > bAcik.length) bAcik = t
                    }
                    "pubDate", "published" -> if (item) bTarih = tarihCoz(runCatching { p.nextText() }.getOrDefault(""))
                    "itunes:duration" -> if (item) bSure = sureCoz(runCatching { p.nextText() }.getOrDefault(""))
                    "guid", "id" -> if (item) bGuid = runCatching { p.nextText() }.getOrDefault("").trim()
                    "enclosure" -> if (item) {
                        val tur = p.getAttributeValue(null, "type").orEmpty()
                        val u = p.getAttributeValue(null, "url").orEmpty()
                        if (u.isNotBlank() && (tur.startsWith("audio") || tur.isBlank() || tur.startsWith("video"))) bSes = u
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && (p.name == "item" || p.name == "entry")) {
                item = false
                if (bSes.isNotBlank()) {
                    list += Bolum(hash(podcastId + (bGuid.ifBlank { bSes })), podcastId, bBaslik.trim().ifBlank { "Bölüm" },
                        temizle(bAcik), bTarih, bSure, bSes.replace("&amp;", "&"), bKapak)
                }
            }
            ev = runCatching { p.next() }.getOrElse { XmlPullParser.END_DOCUMENT }
        }
        if (ad.isBlank() && list.isEmpty()) throw IOException("Bu adreste podcast bulunamadı")
        return Akis(ad.trim(), yazar.trim(), kapak, list, kategori, dil)
    }

    // ---------------- Abonelik ----------------

    /** Abone ol (feed adresi ya da arama sonucu). Podcast kimliğini döndürür. */
    suspend fun aboneOl(feed: String): String = withContext(Dispatchers.IO) {
        _podcastlar.value.find { it.feed == feed }?.let { return@withContext it.id }
        val id = hash(feed)
        val a = akisOku(feed, id)
        synchronized(lock) {
            _podcastlar.value = _podcastlar.value + Podcast(id, a.ad.ifBlank { "Podcast" }, a.yazar, a.kapak, feed,
                System.currentTimeMillis(), System.currentTimeMillis(), a.kategori, a.dil)
            val var_ = _bolumler.value.map { it.id }.toSet()
            _bolumler.value = _bolumler.value + a.bolumler.filter { it.id !in var_ }
            kaydetP(); kaydetB()
        }
        id
    }

    fun aboneliktenCik(id: String) {
        synchronized(lock) {
            _bolumler.value.filter { it.podcastId == id }.forEach { b -> sil(b, kaydet = false) }
            _podcastlar.value = _podcastlar.value.filterNot { it.id == id }
            _bolumler.value = _bolumler.value.filterNot { it.podcastId == id }
            kaydetP(); kaydetB()
        }
    }

    /** Tüm akışları kontrol eder; yeni bölümleri döndürür. */
    suspend fun yenile(): List<Bolum> = withContext(Dispatchers.IO) {
        if (_yenileniyor.value) return@withContext emptyList()
        _yenileniyor.value = true
        try {
            val sonuclar = _podcastlar.value.map { p -> async { p to runCatching { akisOku(p.feed, p.id) }.getOrNull() } }.awaitAll()
            val yeniler = ArrayList<Bolum>()
            synchronized(lock) {
                val var_ = _bolumler.value.associateBy { it.id }
                val eklenecek = ArrayList<Bolum>()
                var pods = _podcastlar.value
                for ((p, a) in sonuclar) {
                    if (a == null) continue
                    pods = pods.map { if (it.id == p.id) it.copy(sonKontrol = System.currentTimeMillis(),
                        kapak = it.kapak ?: a.kapak, yazar = it.yazar.ifBlank { a.yazar },
                        kategori = it.kategori ?: a.kategori, dil = it.dil ?: a.dil) else it }
                    a.bolumler.filter { it.id !in var_ }.forEach { b ->
                        val y = b.copy(yeni = true); eklenecek += y; yeniler += y
                    }
                }
                _podcastlar.value = pods
                _bolumler.value = _bolumler.value + eklenecek
                kaydetP(); kaydetB()
            }
            indirmeleriDenetle()
            if (otoIndir) yeniler.forEach { indir(it, sadeceWifi = true) }
            yeniler
        } finally { _yenileniyor.value = false }
    }

    fun yeniGoruldu(podcastId: String) = synchronized(lock) {
        if (_bolumler.value.none { it.podcastId == podcastId && it.yeni }) return@synchronized
        _bolumler.value = _bolumler.value.map { if (it.podcastId == podcastId && it.yeni) it.copy(yeni = false) else it }
        kaydetB()
    }

    // ---------------- Dinleme ----------------

    /** Kaldığı yeri kaydet; sonuna gelindiyse dinlendi say. */
    fun konumKaydet(id: String, konum: Long, sure: Long, hemen: Boolean = false) {
        val bitti = sure > 0 && konum >= sure - 30_000
        // Çalarken her 5 sn'de bir gelir: sadece bellekte güncelle, dosyaya arada bir (arka planda) yaz.
        // Bölüm bittiyse ya da çalma durduysa hemen yaz.
        bolumGuncelle(id, kaydet = false) { it.copy(konumMs = if (bitti) 0 else konum, dinlendi = it.dinlendi || bitti, yeni = false) }
        if (bitti || hemen) kaydetSimdi() else kaydetSonra()
        if (bitti) bitinceSil(id)
    }

    fun dinlendiYap(id: String, d: Boolean, kendiliginden: Boolean = false) {
        bolumGuncelle(id) { it.copy(dinlendi = d, konumMs = 0, yeni = false) }
        if (d && kendiliginden) bitinceSil(id)
    }

    /** "Dinlenince sil" açıksa biten bölümün dosyasını sil (çalarken de güvenli: açık dosya çalmaya devam eder). */
    private fun bitinceSil(id: String) {
        if (!otoSil) return
        val b = bolum(id) ?: return
        if (b.dosya != null && b.indirmeNo == null) sil(b)
    }

    // ---------------- İndirme ----------------

    private fun dm() = ctx.getSystemService(DownloadManager::class.java)

    fun indir(b: Bolum, sadeceWifi: Boolean = false) {
        if (dosyaOf(b) != null || b.indirmeNo != null) return
        val uzanti = b.ses.substringBefore('?').substringAfterLast('.', "mp3").take(4).ifBlank { "mp3" }
        val ad = "${b.id}.$uzanti"
        File(klasor, ad).delete()
        val req = DownloadManager.Request(Uri.parse(b.ses))
            .setTitle(b.baslik)
            .setDescription(podcast(b.podcastId)?.ad ?: "Podcast")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, "Podcastler/$ad")
            .addRequestHeader("User-Agent", "SubPlay/1 (Android; podcast)")
            .apply { if (sadeceWifi) setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI) }
        val no = runCatching { dm().enqueue(req) }.getOrNull() ?: return
        bolumGuncelle(b.id) { it.copy(dosya = ad, indirmeNo = no) }
        izle()
    }

    private var izleniyor = false
    /** Süren indirmeleri biter bitmez işaretle. */
    private fun izle() {
        if (izleniyor) return
        izleniyor = true
        scope.launch {
            while (_bolumler.value.any { it.indirmeNo != null }) {
                indirmeleriDenetle()
                kotlinx.coroutines.delay(1500)
            }
            izleniyor = false
        }
    }

    private val _ilerleme = MutableStateFlow<Map<String, Float>>(emptyMap())
    val ilerleme = _ilerleme.asStateFlow()

    fun indirmeleriDenetle() {
        val surenler = _bolumler.value.filter { it.indirmeNo != null }
        if (surenler.isEmpty()) { _ilerleme.value = emptyMap(); return }
        val yeni = HashMap<String, Float>()
        for (b in surenler) {
            val no = b.indirmeNo ?: continue
            val c = runCatching { dm().query(DownloadManager.Query().setFilterById(no)) }.getOrNull()
            var durum = -1; var inen = 0L; var toplam = -1L
            c?.use { if (it.moveToFirst()) {
                durum = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                inen = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                toplam = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            } }
            when (durum) {
                DownloadManager.STATUS_SUCCESSFUL -> bolumGuncelle(b.id) { it.copy(indirmeNo = null) }
                DownloadManager.STATUS_FAILED, -1 -> bolumGuncelle(b.id) { it.copy(indirmeNo = null, dosya = null) }
                else -> yeni[b.id] = if (toplam > 0) inen.toFloat() / toplam else 0f
            }
        }
        _ilerleme.value = yeni
    }

    fun sil(b: Bolum, kaydet: Boolean = true) {
        b.indirmeNo?.let { runCatching { dm().remove(it) } }
        b.dosya?.let { File(klasor, it).delete() }
        if (kaydet) bolumGuncelle(b.id) { it.copy(dosya = null, indirmeNo = null) }
    }

    init {
        if (_bolumler.value.any { it.indirmeNo != null }) izle()
    }

    // ---------------- Kişisel öneriler ----------------

    /** Kullanıcının seçtiği ilgi alanları (kategori numaraları). */
    private val _ilgi = MutableStateFlow(prefs.getStringSet("ilgi", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet())
    val ilgi = _ilgi.asStateFlow()
    fun ilgiDegistir(id: Int) {
        _ilgi.value = if (id in _ilgi.value) _ilgi.value - id else _ilgi.value + id
        prefs.edit().putStringSet("ilgi", _ilgi.value.map { it.toString() }.toSet()).apply()
    }

    /** Öneri dili: "tr" ya da "en". Seçilmediyse aboneliklerin çoğunluğuna göre. */
    var oneriDili: String
        get() = prefs.getString("oneri_dili", null) ?: run {
            val diller = _podcastlar.value.mapNotNull { it.dil }
            if (diller.isNotEmpty() && diller.count { it != "tr" } > diller.count { it == "tr" }) "en" else "tr"
        }
        set(v) { prefs.edit().putString("oneri_dili", v).apply() }

    /**
     * Kategorilere ilgi puanı: seçilen ilgi alanları (+5), abone olunan podcast'lerin kategorisi (+3),
     * dinlenen bölümler (bölüm başına +0,3; en çok +4).
     */
    fun ilgiPuanlari(): Map<Int, Double> {
        val puan = HashMap<Int, Double>()
        _ilgi.value.forEach { puan[it] = (puan[it] ?: 0.0) + 5 }
        val pods = _podcastlar.value.associateBy { it.id }
        pods.values.forEach { p -> p.kategori?.let { puan[it] = (puan[it] ?: 0.0) + 3 } }
        _bolumler.value.filter { it.dinlendi || it.konumMs > 0 }.groupBy { pods[it.podcastId]?.kategori }
            .forEach { (k, l) -> if (k != null) puan[k] = (puan[k] ?: 0.0) + (l.size * 0.3).coerceAtMost(4.0) }
        return puan
    }

    /** Öneri gösterilecek kategoriler (en ilgili 3). Hiç bilgi yoksa boş: önce ilgi alanı seçilsin. */
    fun onerilenKategoriler(adet: Int = 3): List<Kategori> {
        val p = ilgiPuanlari()
        return KATEGORILER.filter { (p[it.id] ?: 0.0) > 0 }.sortedByDescending { p[it.id] }.take(adet)
    }

    private val oneriOnbellek = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<PodcastArama>>>()

    /** Bir kategorinin en popüler podcast'leri (Apple Podcasts listesi), abone olunanlar hariç. */
    suspend fun kategoriOnerileri(kategori: Int, dil: String = oneriDili, adet: Int = 30): List<PodcastArama> = withContext(Dispatchers.IO) {
        val ulke = if (dil == "tr") "tr" else "us"
        val anahtar = "$ulke-$kategori"
        val liste = oneriOnbellek[anahtar]?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.second ?: run {
            val js = JSONObject(getir("https://itunes.apple.com/$ulke/rss/toppodcasts/limit=$adet/genre=$kategori/json"))
            val giris = js.optJSONObject("feed")?.optJSONArray("entry") ?: JSONArray()
            val idler = (0 until giris.length()).mapNotNull { i ->
                giris.optJSONObject(i)?.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.ifBlank { null }
            }
            if (idler.isEmpty()) return@run emptyList()
            // RSS adreslerini toplu olarak al
            val lk = JSONObject(getir("https://itunes.apple.com/lookup?country=$ulke&entity=podcast&id=" + idler.joinToString(",")))
                .optJSONArray("results") ?: JSONArray()
            val byId = (0 until lk.length()).mapNotNull { lk.optJSONObject(it) }.associateBy { it.optLong("collectionId").toString() }
            idler.mapNotNull { id ->
                val o = byId[id] ?: return@mapNotNull null
                val feed = o.optString("feedUrl").ifBlank { return@mapNotNull null }
                PodcastArama(o.optString("collectionName"), o.optString("artistName"),
                    o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null }, feed)
            }.also { oneriOnbellek[anahtar] = System.currentTimeMillis() to it }
        }
        val abone = _podcastlar.value.map { it.feed }.toSet()
        liste.filter { it.feed !in abone }
    }

    // ---------------- Yedek ----------------

    fun yedekJson(): JSONArray = JSONArray().apply { _podcastlar.value.forEach { put(it.feed) } }

    fun yedektenYukle(a: JSONArray) {
        scope.launch { for (i in 0 until a.length()) a.optString(i).ifBlank { null }?.let { runCatching { aboneOl(it) } } }
    }
}

/** Yeni bölümleri birkaç saatte bir kontrol eder ve bildirir. */
class PodcastIsci(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val repo = PodcastRepo.get(applicationContext)
        if (repo.podcastlar.value.isEmpty()) return Result.success()
        val yeniler = runCatching { repo.yenile() }.getOrElse { return Result.retry() }
        if (yeniler.isNotEmpty()) bildir(applicationContext, repo, yeniler)
        return Result.success()
    }

    companion object {
        fun zamanla(c: Context) {
            val req = PeriodicWorkRequestBuilder<PodcastIsci>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("podcast", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun bildir(c: Context, repo: PodcastRepo, yeniler: List<Bolum>) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("podcast", "Yeni podcast bölümleri", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(
                c, 3, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("sekme", "podcast"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val ilk = yeniler.first()
            val baslik = if (yeniler.size == 1) (repo.podcast(ilk.podcastId)?.ad ?: "Yeni bölüm") else "${yeniler.size} yeni podcast bölümü"
            val metin = if (yeniler.size == 1) ilk.baslik
                else yeniler.mapNotNull { repo.podcast(it.podcastId)?.ad }.distinct().joinToString(", ")
            val n = NotificationCompat.Builder(c, "podcast")
                .setSmallIcon(com.dogukan.muzik.R.drawable.ic_bildirim_podcast)
                .setContentTitle(baslik).setContentText(metin)
                .setContentIntent(pi).setAutoCancel(true).build()
            try { nm.notify(4, n) } catch (_: SecurityException) { }
        }
    }
}
