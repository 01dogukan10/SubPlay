package com.dogukan.muzik

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.common.C
import android.os.Bundle
import com.dogukan.muzik.player.MedyaOge
import com.dogukan.muzik.player.SiraKomut
import androidx.media3.session.SessionToken
import com.dogukan.muzik.data.MusicRepository
import com.dogukan.muzik.data.Track
import com.dogukan.muzik.player.PlaybackService
import com.dogukan.muzik.update.Guncelle
import com.dogukan.muzik.update.Surum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Sıra ekranındaki bir satır. index = oynatıcıdaki dizini. */
data class SiraOge(val index: Int, val trackId: String, val title: String, val artist: String, val artwork: Uri?)

/** queued = kullanıcının sıraya ekledikleri; upcoming = sonra listeden gelecekler. */
data class SiraUi(val queued: List<SiraOge> = emptyList(), val upcoming: List<SiraOge> = emptyList(), val moreCount: Int = 0)

data class PlayerUi(
    val currentId: String? = null,
    val title: String? = null,
    val artist: String = "",
    val artwork: Uri? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val akilli: Boolean = false, // akıllı karıştırma (öneri ekleyen)
    val oneri: Boolean = false,  // çalan şarkı akıllı karıştırmanın önerisi
    val onizleme: Boolean = false, // aramadan dinlenen, kütüphanede olmayan şarkı
    val podcast: Boolean = false, // çalan bir podcast bölümü
    val hiz: Float = 1f,
    val repeat: Int = Player.REPEAT_MODE_OFF,
)

data class UpdateUi(
    val surum: Surum? = null,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Uygulama simgesi kısayolundan gelen istek (oynatıcı bağlanınca işlenir). */
        val bekleyenKisayol = MutableStateFlow<String?>(null)
    }

    val repo = MusicRepository.get(app)

    private val _ui = MutableStateFlow(PlayerUi())
    val ui = _ui.asStateFlow()

    private var controller: MediaController? = null
    private val future = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            // Uyku zamanlayıcısı bittiyse ekrandaki göstergeyi kapat
            val u = _uyku.value
            if (u != null && !player.playWhenReady &&
                (u == -1L || u <= System.currentTimeMillis())) _uyku.value = null
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) refreshSira()
        }
    }

    private val _update = MutableStateFlow(UpdateUi())
    val update = _update.asStateFlow()

    val currentVersion: String get() = Guncelle.mevcutAd(getApplication())
    fun token(): String = Guncelle.token(getApplication())
    fun setToken(t: String) = Guncelle.setToken(getApplication(), t)

    /** manual=false: açılışta sessiz kontrol. */
    fun checkUpdate(manual: Boolean, onMessage: (String) -> Unit = {}) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { Guncelle.sonSurum(app) }
                if (s != null && Guncelle.yeniMi(app, s)) _update.value = UpdateUi(surum = s)
                else if (manual) onMessage("Uygulama güncel (sürüm $currentVersion)")
            } catch (e: Exception) {
                if (manual) onMessage(e.message ?: "Güncelleme denetlenemedi")
            }
        }
    }

    fun installUpdate() {
        val app = getApplication<Application>()
        val s = _update.value.surum ?: return
        if (_update.value.downloading) return
        if (!Guncelle.kurabilir(app)) { Guncelle.izinAc(app); return }
        _update.value = _update.value.copy(downloading = true, progress = null, error = null)
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    Guncelle.indir(app, s) { p -> _update.value = _update.value.copy(progress = p) }
                }
                _update.value = _update.value.copy(downloading = false)
                Guncelle.kur(app, f)
            } catch (e: Exception) {
                _update.value = _update.value.copy(downloading = false, error = e.message ?: "İndirilemedi")
            }
        }
    }

    fun dismissUpdate() { _update.value = UpdateUi() }

    init {
        Guncelle.zamanla(app)
        com.dogukan.muzik.data.PodcastIsci.zamanla(app)
        repo.esitleGerekirse()
        checkUpdate(manual = false)
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(listener)
            kaldigiYerdenYukle()
            refresh()
            refreshSira()
            viewModelScope.launch { bekleyenKisayol.collect { k -> if (k != null) { bekleyenKisayol.value = null; kisayol(k) } } }
        }, ContextCompat.getMainExecutor(app))

        // Çalarken ilerleme çubuğunu güncelle
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (controller?.isPlaying == true) refresh()
            }
        }
    }

    private val _sira = MutableStateFlow(SiraUi())
    val sira = _sira.asStateFlow()

    /** Çalma sırasını oynatıcıdan okur (karışıksa karışık sırayla). */
    private fun refreshSira() {
        val c = controller ?: return
        val tl = c.currentTimeline
        if (tl.isEmpty || c.mediaItemCount == 0) { _sira.value = SiraUi(); return }
        val shuffle = c.shuffleModeEnabled
        val next = ArrayList<Int>()
        var i = c.currentMediaItemIndex
        while (next.size < c.mediaItemCount) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
            if (i == C.INDEX_UNSET) break
            next += i
        }
        fun oge(idx: Int): SiraOge {
            val m = c.getMediaItemAt(idx)
            return SiraOge(idx, m.mediaId, m.mediaMetadata.title?.toString().orEmpty(),
                m.mediaMetadata.artist?.toString().orEmpty(), m.mediaMetadata.artworkUri)
        }
        val queued = next.takeWhile { MedyaOge.kuyrukta(c.getMediaItemAt(it)) }
        val rest = next.drop(queued.size)
        _sira.value = SiraUi(queued.map(::oge), rest.take(80).map(::oge), (rest.size - 80).coerceAtLeast(0))
    }

    private fun komut(ad: String, args: Bundle = Bundle.EMPTY) {
        controller?.sendCustomCommand(SessionCommand(ad, Bundle.EMPTY), args)
    }

    /** Spotify'daki "Sıraya ekle" (basa=false) ve "Sıradaki olarak çal" (basa=true). */
    fun queueTracks(list: List<Track>, basa: Boolean) {
        val c = controller ?: return
        if (list.isEmpty()) return
        if (c.mediaItemCount == 0) { playTracks(list, 0); return }
        komut(SiraKomut.EKLE, Bundle().apply {
            putStringArray("ids", list.map { it.id }.toTypedArray())
            putBoolean("basa", basa)
        })
    }

    fun queueRemove(pos: Int) = komut(SiraKomut.CIKAR, Bundle().apply { putInt("pos", pos) })
    fun queueMove(from: Int, to: Int) = komut(SiraKomut.TASI, Bundle().apply { putInt("from", from); putInt("to", to) })
    fun queueClear() = komut(SiraKomut.TEMIZLE)

    // ---------- Uyku zamanlayıcısı ----------
    /** null = kapalı, -1 = şarkı bitince, diğer = bitiş zamanı (ms). */
    private val _uyku = MutableStateFlow<Long?>(null)
    val uyku = _uyku.asStateFlow()

    fun setSleep(dk: Int) {
        komut(SiraKomut.UYKU, Bundle().apply { putInt("dk", dk) })
        _uyku.value = when {
            dk > 0 -> System.currentTimeMillis() + dk * 60_000L
            dk == -1 -> -1L
            else -> null
        }
    }

    // ---------- Yumuşak geçiş ----------
    private val oynaticiPrefs = app.getSharedPreferences("oynatici", android.content.Context.MODE_PRIVATE)
    var normalize: Boolean
        get() = oynaticiPrefs.getBoolean("normalize", false)
        set(v) = oynaticiPrefs.edit().putBoolean("normalize", v).apply()

    var gecisSn: Int
        get() = oynaticiPrefs.getInt("gecis_sn", 0)
        set(v) = oynaticiPrefs.edit().putInt("gecis_sn", v).apply()

    // ---------- Şarkı sözleri ----------
    suspend fun sozler(trackId: String, sureMs: Long): com.dogukan.muzik.data.Sozler? = withContext(Dispatchers.IO) {
        // Öneri şarkıları kütüphanede yok: çalan şarkının bilgisinden geçici bir kayıt oluştur
        val t = repo.tracks.value.find { it.id == trackId } ?: withContext(Dispatchers.Main) {
            controller?.currentMediaItem?.takeIf { it.mediaId == trackId }?.let {
                Track(trackId, it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty(),
                    "", "", 0L)
            }
        } ?: return@withContext null
        com.dogukan.muzik.data.SozKaynagi.getir(getApplication(), t, (sureMs / 1000).toInt())
    }

    // Sözlerin şarkıya göre kayması (ms, şarkı başına). Artı: sözler daha geç gelir.
    private val kaymaPref = app.getSharedPreferences("soz_kayma", android.content.Context.MODE_PRIVATE)
    private val _sozKayma = MutableStateFlow(kaymaPref.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())
    val sozKayma = _sozKayma.asStateFlow()
    fun setSozKayma(trackId: String, ms: Long) {
        val v = ms.coerceIn(-60_000L, 60_000L)
        if (v == 0L) kaymaPref.edit().remove(trackId).apply() else kaymaPref.edit().putLong(trackId, v).apply()
        _sozKayma.value = if (v == 0L) _sozKayma.value - trackId else _sozKayma.value + (trackId to v)
    }

    fun sozleriYenile(trackId: String) = com.dogukan.muzik.data.SozKaynagi.sil(getApplication(), trackId)

    /** Sıradaki bir şarkıya atla. */
    fun jumpTo(index: Int) {
        controller?.let { it.seekTo(index, 0L); it.play() }
    }

    /** Oynatıcı boşsa (uygulama kapanıp açıldıysa) son çalınan listeyi, şarkıyı ve saniyeyi duraklatılmış olarak yükle. */
    private fun kaldigiYerdenYukle() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val k = com.dogukan.muzik.player.KayitliKuyruk.oku(getApplication(), repo) ?: return
        c.setMediaItems(k.ogeler, k.index, k.konum)
        komut(SiraKomut.YENI_KUYRUK)
        c.prepare() // çalmaya başlatma; kullanıcı ▶'ye basınca kaldığı yerden devam eder
        if (c.shuffleModeEnabled && _akilli.value) onerileriGetir()
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        _ui.value = PlayerUi(
            currentId = item?.mediaId,
            oneri = item?.mediaId?.startsWith("oneri:") == true,
            onizleme = item?.mediaId?.startsWith("yt:") == true &&
                repo.tracks.value.none { t -> t.videoId == item.mediaId.removePrefix("yt:") },
            podcast = MedyaOge.podcastMi(item?.mediaId),
            hiz = c.playbackParameters.speed,
            title = item?.let { c.mediaMetadata.title?.toString() ?: it.mediaMetadata.title?.toString() ?: "" },
            artist = c.mediaMetadata.artist?.toString().orEmpty(),
            artwork = c.mediaMetadata.artworkUri,
            isPlaying = c.isPlaying,
            position = c.currentPosition.coerceAtLeast(0),
            duration = c.duration.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            akilli = c.shuffleModeEnabled && _akilli.value,
            repeat = c.repeatMode,
        )
    }

    private fun Track.toMediaItem(kuyruk: Boolean = false): MediaItem = MedyaOge.of(repo, this, kuyruk)

    /** shuffle = null -> karışık mod olduğu gibi kalır. */
    fun playTracks(list: List<Track>, startIndex: Int = 0, shuffle: Boolean? = null) {
        val c = controller ?: return
        if (list.isEmpty()) return
        // Spotify gibi: kullanıcının sıraya eklediği şarkılar yeni liste çalınca da kaybolmaz
        val byId = repo.tracks.value.associateBy { it.id }
        val keep = _sira.value.queued.mapNotNull { byId[it.trackId] }
        val podcasttan = MedyaOge.podcastMi(c.currentMediaItem?.mediaId)
        c.setMediaItems(list.map { it.toMediaItem() }, startIndex, 0L)
        if (shuffle != null) c.shuffleModeEnabled = shuffle
        else if (podcasttan) c.shuffleModeEnabled = pod.prefs.getBoolean("muzik_karisik", false) // podcast'ten müziğe dönüş
        komut(SiraKomut.YENI_KUYRUK)
        if (keep.isNotEmpty()) queueTracks(keep, basa = false)
        c.prepare()
        c.play()
        if (c.shuffleModeEnabled && _akilli.value) onerileriGetir()
    }

    // ---------------- Podcast ----------------
    val pod = com.dogukan.muzik.data.PodcastRepo.get(app)

    /** Bölümü kaldığı yerden çal. */
    /**
     * Bölümü kaldığı yerden çal. Podcast'in diğer bölümleri de (listedeki sırayla) sıraya girer;
     * böylece "sonraki / önceki" (Bluetooth / interkom tuşları dahil) başka bölüme geçer.
     */
    fun bolumCal(b: com.dogukan.muzik.data.Bolum, liste: List<com.dogukan.muzik.data.Bolum>? = null) {
        val c = controller ?: return
        val baslangic = if (b.dinlendi) 0L else b.konumMs
        val l = (liste ?: pod.bolumleri(b.podcastId)).let { if (it.any { x -> x.id == b.id }) it else listOf(b) }
        val idx = l.indexOfFirst { it.id == b.id }.coerceAtLeast(0)
        // Bölümler sırayla çalar; müzikteki karışık ayarı saklanır, müziğe dönünce geri gelir
        if (!MedyaOge.podcastMi(c.currentMediaItem?.mediaId)) pod.prefs.edit().putBoolean("muzik_karisik", c.shuffleModeEnabled).apply()
        c.shuffleModeEnabled = false
        c.setMediaItems(l.map { MedyaOge.bolum(pod, it) }, idx, baslangic)
        c.prepare(); c.play()
        refresh()
    }

    /** Çalma hızı (sadece podcast'lerde geçerli, hatırlanır). */
    fun hizAyarla(h: Float) {
        pod.hiz = h
        controller?.let { c -> if (MedyaOge.podcastMi(c.currentMediaItem?.mediaId)) c.setPlaybackSpeed(h) }
        refresh()
    }

    fun atla(ms: Long) {
        controller?.let { c -> c.seekTo((c.currentPosition + ms).coerceIn(0L, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
        refresh()
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    /** Sonraki şarkı; listenin sonundaysa başa döner (tuş hiç boşa basılmasın). */
    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNext()
        else if (c.mediaItemCount > 0) {
            val ilk = c.currentTimeline.getFirstWindowIndex(c.shuffleModeEnabled)
            if (ilk != androidx.media3.common.C.INDEX_UNSET) c.seekToDefaultPosition(ilk)
        }
    }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); refresh() }
    /** Spotify gibi üç durum: kapalı -> karışık -> akıllı karışık (öneri ekler) -> kapalı. */
    fun toggleShuffle() {
        val c = controller ?: return
        when {
            !c.shuffleModeEnabled -> { setAkilli(false); c.shuffleModeEnabled = true }
            !_akilli.value -> { setAkilli(true); onerileriGetir() }
            else -> { setAkilli(false); komut(SiraKomut.ONERI_TEMIZLE); c.shuffleModeEnabled = false }
        }
        refresh()
    }

    // ---------- Akıllı karıştırma ----------
    private val _akilli = MutableStateFlow(
        app.getSharedPreferences("oynatici", android.content.Context.MODE_PRIVATE).getBoolean("akilli", false)
    )

    private fun setAkilli(on: Boolean) {
        _akilli.value = on
        getApplication<Application>().getSharedPreferences("oynatici", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("akilli", on).apply()
    }


    /** Çalan listeye benzeyen şarkıları bulup sıraya serpiştirir. */
    /** Akıllı karıştırma: önerileri servis çalan şarkıya göre getirir ve dinledikçe yenisini ekler. */
    private fun onerileriGetir() = komut(SiraKomut.ONERI_DOLDUR)


    /** ♥: çalan şarkı öneriyse önce kütüphaneye ekler. Beğenildiyse true. */
    fun begenCalani(): Boolean? {
        val item = controller?.currentMediaItem ?: return null
        return repo.begen(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** Adı değiştir; çalma kuyruğundaysa bildirim ve oynatıcı da güncellensin. */
    fun renameTrack(id: String, title: String, artist: String) {
        val t = repo.renameTrack(id, title, artist) ?: return
        controller?.let { c ->
            for (i in 0 until c.mediaItemCount) {
                val old = c.getMediaItemAt(i)
                if (old.mediaId == id) c.replaceMediaItem(i, t.toMediaItem(MedyaOge.kuyrukta(old)))
            }
        }
        refresh()
    }

    fun deleteTracks(list: List<Track>) = list.forEach { deleteTrack(it) }

    /**
     * Arama sonucunu indirmeden hemen çal (önizleme). Çalan listeyi bozmaz: şarkı çalanın hemen
     * arkasına eklenip çalınır; önceki önizleme şarkıları sıradan çıkarılır.
     */
    /** Çalan önizleme şarkısını kütüphaneye ekle (istersen indir). */
    fun onizlemeyiEkle(indir: Boolean) {
        val item = controller?.currentMediaItem ?: return
        if (!item.mediaId.startsWith("yt:")) return
        val id = repo.oneriyiEkle(com.dogukan.muzik.data.Oneri(item.mediaId.removePrefix("yt:"),
            item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty()))
        if (indir) repo.tracks.value.find { it.id == id }?.let { repo.downloadTracks(listOf(it)) }
        repo.notice(if (indir) "İndirme başladı" else "Kütüphaneye eklendi")
        refresh()
    }

    fun onizle(r: com.dogukan.muzik.data.SearchResult) {
        val c = controller ?: return
        val (ad, sanatci) = repo.adSanatci(r)
        val item = MedyaOge.onizleme(r.id, ad, sanatci)
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId.startsWith("yt:") && i != c.currentMediaItemIndex) c.removeMediaItem(i)
        }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(listOf(item))
        } else {
            val at = c.currentMediaItemIndex + 1
            c.addMediaItem(at, item)
            c.seekTo(at, 0L)
        }
        c.prepare(); c.play()
        refresh()
    }

    private fun kisayol(k: String) {
        val c = controller ?: return
        when (k) {
            "karisik" -> repo.tracks.value.takeIf { it.isNotEmpty() }?.let { l -> playTracks(l, l.indices.random(), shuffle = true) }
            "begeni" -> {
                val byId = repo.tracks.value.associateBy { it.id }
                val l = repo.playlists.value.find { it.id == com.dogukan.muzik.data.MusicRepository.BEGENI_ID }
                    ?.trackIds?.mapNotNull { byId[it] }.orEmpty()
                if (l.isNotEmpty()) playTracks(l, 0, null) else repo.notice("Henüz beğendiğin şarkı yok")
            }
            else -> if (c.mediaItemCount > 0) { c.prepare(); c.play() }
        }
    }

    /** Kütüphaneden kaldır (çalma sırasından da); geri alma işlevini döndürür. */
    fun kaldir(list: List<Track>): () -> Unit {
        val ids = list.map { it.id }.toSet()
        controller?.let { c -> for (i in c.mediaItemCount - 1 downTo 0) if (c.getMediaItemAt(i).mediaId in ids) c.removeMediaItem(i) }
        return repo.kaldirGeriAlinabilir(list)
    }

    /** Sadece telefondaki dosyaları sil; şarkılar kütüphanede kalır ve internetten çalınır. */
    fun deleteFiles(list: List<Track>) {
        list.forEach { repo.deleteFile(it) }
        val ids = list.map { it.id }.toSet()
        val byId = repo.tracks.value.associateBy { it.id }
        controller?.let { c ->
            for (i in 0 until c.mediaItemCount) {
                val old = c.getMediaItemAt(i)
                if (old.mediaId in ids && i != c.currentMediaItemIndex) {
                    byId[old.mediaId]?.let { c.replaceMediaItem(i, it.toMediaItem(MedyaOge.kuyrukta(old))) }
                }
            }
        }
    }

    fun deleteTrack(t: Track) {
        controller?.let { c ->
            for (i in c.mediaItemCount - 1 downTo 0) {
                if (c.getMediaItemAt(i).mediaId == t.id) c.removeMediaItem(i)
            }
        }
        repo.deleteTrack(t)
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
    }
}
