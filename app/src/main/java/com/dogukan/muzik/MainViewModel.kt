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
    val repeat: Int = Player.REPEAT_MODE_OFF,
)

data class UpdateUi(
    val surum: Surum? = null,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

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
        repo.esitleGerekirse()
        checkUpdate(manual = false)
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(listener)
            kaldigiYerdenYukle()
            refresh()
            refreshSira()
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

    fun sozleriYenile(trackId: String) = com.dogukan.muzik.data.SozKaynagi.sil(getApplication(), trackId)

    /** Sıradaki bir şarkıya atla. */
    fun jumpTo(index: Int) {
        controller?.let { it.seekTo(index, 0L); it.play() }
    }

    /** Oynatıcı boşsa (uygulama kapanıp açıldıysa) son çalınan listeyi, şarkıyı ve saniyeyi duraklatılmış olarak yükle. */
    private fun kaldigiYerdenYukle() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val (list, idx, pos) = com.dogukan.muzik.player.KayitliKuyruk.oku(getApplication(), repo) ?: return
        c.setMediaItems(list.map { it.toMediaItem() }, idx, pos)
        komut(SiraKomut.YENI_KUYRUK)
        c.prepare() // çalmaya başlatma; kullanıcı ▶'ye basınca kaldığı yerden devam eder
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        _ui.value = PlayerUi(
            currentId = item?.mediaId,
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
        c.setMediaItems(list.map { it.toMediaItem() }, startIndex, 0L)
        if (shuffle != null) c.shuffleModeEnabled = shuffle
        komut(SiraKomut.YENI_KUYRUK)
        if (keep.isNotEmpty()) queueTracks(keep, basa = false)
        c.prepare()
        c.play()
        if (c.shuffleModeEnabled && _akilli.value) onerileriGetir()
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNext() }
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

    private var oneriIsi: kotlinx.coroutines.Job? = null

    /** Çalan listeye benzeyen şarkıları bulup sıraya serpiştirir. */
    private fun onerileriGetir() {
        val c = controller ?: return
        val byId = repo.tracks.value.associateBy { it.id }
        val queue = (0 until c.mediaItemCount).mapNotNull { byId[c.getMediaItemAt(it).mediaId] }
        if (queue.isEmpty()) return
        val adet = (queue.size / 3).coerceIn(5, 20)
        oneriIsi?.cancel()
        oneriIsi = viewModelScope.launch {
            val list = runCatching { repo.oneriler(queue.shuffled().take(10), adet) }.getOrElse { emptyList() }
            if (!_akilli.value) return@launch
            if (list.isEmpty()) { repo.notice("Akıllı karıştırma için öneri bulunamadı. İnternet bağlantını kontrol et."); return@launch }
            komut(SiraKomut.ONERI_EKLE, Bundle().apply {
                putStringArray("ids", list.map { it.videoId }.toTypedArray())
                putStringArray("titles", list.map { it.title }.toTypedArray())
                putStringArray("artists", list.map { it.artist }.toTypedArray())
            })
        }
    }

    /** ♥: çalan şarkı öneriyse önce kütüphaneye ekler. Beğenildiyse true. */
    fun begenCalani(): Boolean? {
        val c = controller ?: return null
        val item = c.currentMediaItem ?: return null
        val id = if (item.mediaId.startsWith("oneri:")) {
            repo.oneriyiEkle(com.dogukan.muzik.data.Oneri(item.mediaId.removePrefix("oneri:"),
                item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty()))
        } else item.mediaId
        return repo.toggleLike(id)
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
