package com.dogukan.muzik.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import com.google.common.collect.ImmutableList
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.dogukan.muzik.MainActivity
import com.dogukan.muzik.data.MusicRepository
import com.dogukan.muzik.data.Istatistik
import com.dogukan.muzik.data.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID

/** Uygulama ile oynatıcı servisi arasındaki sıra komutları. */
object SiraKomut {
    const val EKLE = "sira.ekle"           // ids: String[], basa: Boolean
    const val CIKAR = "sira.cikar"         // pos: Int (sıradakiler içindeki konum)
    const val TASI = "sira.tasi"           // from: Int, to: Int
    const val TEMIZLE = "sira.temizle"
    const val YENI_KUYRUK = "sira.yeni"    // yeni liste çalındı -> (karışıksa) karıştır
    const val UYKU = "uyku"                // dk: Int (>0 dakika, -1 şarkı bitince, 0 kapat)
    const val ONERI_EKLE = "oneri.ekle"    // ids, titles, artists: String[] (akıllı karıştırma)
    const val ONERI_TEMIZLE = "oneri.temizle"
    val HEPSI = listOf(EKLE, CIKAR, TASI, TEMIZLE, YENI_KUYRUK, UYKU, ONERI_EKLE, ONERI_TEMIZLE)
}

/** Şarkıdan oynatıcı öğesi üretir. kuyruk=true -> kullanıcının "sıraya ekle" dediği şarkı. */
object MedyaOge {
    fun of(repo: MusicRepository, t: Track, kuyruk: Boolean = false): MediaItem {
        // İndirilmişse dosyadan, değilse internetten (servis "subplay://akis/..." adresini çözer)
        val file = repo.fileOf(t)
        val uri = if (t.indirildi && file.exists()) Uri.fromFile(file) else Uri.parse("subplay://akis/${t.id}")
        val extras = Bundle().apply {
            putBoolean("kuyruk", kuyruk)
            putString("uid", UUID.randomUUID().toString())
        }
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist.ifBlank { null })
                    .setArtworkUri(repo.coverOf(t)?.let { Uri.fromFile(it) })
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    fun kuyrukta(item: MediaItem?) = item?.mediaMetadata?.extras?.getBoolean("kuyruk") == true

    /** Akıllı karıştırmanın önerdiği, kütüphanede olmayan şarkı (internetten çalar). */
    fun oneri(videoId: String, title: String, artist: String): MediaItem {
        val uri = Uri.parse("subplay://yt/$videoId")
        return MediaItem.Builder()
            .setMediaId("oneri:$videoId")
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist.ifBlank { null })
                    .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"))
                    .setExtras(Bundle().apply {
                        putBoolean("kuyruk", false); putBoolean("oneri", true)
                        putString("uid", UUID.randomUUID().toString())
                    })
                    .build()
            )
            .build()
    }
}

/** Arka planda çalmayı, bildirim kontrollerini ve çalma sırasını yöneten servis. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var prefs: SharedPreferences

    // Ses efektleri: ekolayzır, bas güçlendirme, ses dengeleme
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var loud: LoudnessEnhancer? = null
    private var normCarpan = 1f

    private val prefDinleyici = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null -> Unit
            key.startsWith("eq_") || key == "bass" -> efektleriUygula()
            key == "normalize" -> if (::player.isInitialized) normHesapla(player.currentMediaItem)
        }
    }
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())
    private var oncekiUid: String? = null
    private var oncekiKuyrukta = false
    private var artArdaHata = 0

    // Uyku zamanlayıcısı
    private var uykuBitis = 0L
    private var uykuSarkiSonu = false

    // Dinleme kaydı (istatistik ve akıllı listeler için)
    private var dinlenenId: String? = null
    private var dinlemeBas = 0L
    private var dinlenenMs = 0L

    private val TICK = 250L
    private var kayitSayac = 0
    private val tick = object : Runnable {
        override fun run() {
            if (::player.isInitialized) adim()
            main.postDelayed(this, TICK)
        }
    }

    /** Her 250 ms: ses (yumuşak geçiş + uyku kısması), uyku süresi, dinleme süresi. */
    private fun adim() {
        if (player.isPlaying) {
            dinlenenMs += TICK
            // Çalarken 5 saniyede bir nerede kaldığını kaydet
            if (++kayitSayac % 20 == 0) KayitliKuyruk.kaydet(this, player)
        }
        var v = 1f
        val fade = prefs.getInt("gecis_sn", 0) * 1000L
        val dur = player.duration
        val pos = player.currentPosition
        if (fade > 0 && dur > 0 && dur > 3 * fade) {
            if (pos < fade) v = minOf(v, pos.toFloat() / fade)
            val kalan = dur - pos
            val devami = player.hasNextMediaItem() || player.repeatMode != Player.REPEAT_MODE_OFF
            if (kalan < fade && devami) v = minOf(v, kalan.toFloat() / fade)
        }
        if (uykuBitis > 0) {
            val kalan = uykuBitis - System.currentTimeMillis()
            if (kalan <= 0) {
                player.pause(); uykuBitis = 0
            } else if (kalan < 10_000) v = minOf(v, kalan / 10_000f) // son 10 saniyede sesi yavaşça kıs
        }
        v = (v * normCarpan).coerceIn(0f, 1f)
        if (kotlin.math.abs(player.volume - v) > 0.01f) player.volume = v
    }

    private fun dinlemeyiKaydet() {
        val id = dinlenenId ?: return
        Istatistik.get(this).kaydet(id, dinlemeBas, dinlenenMs)
        dinlenenId = null; dinlenenMs = 0
    }

    private fun uyku(dk: Int) {
        uykuBitis = 0
        if (uykuSarkiSonu) { player.pauseAtEndOfMediaItems = false; uykuSarkiSonu = false }
        when {
            dk > 0 -> uykuBitis = System.currentTimeMillis() + dk * 60_000L
            dk == -1 -> { player.pauseAtEndOfMediaItems = true; uykuSarkiSonu = true }
        }
    }

    private fun efektleriKur(sessionId: Int) {
        runCatching { eq?.release() }; runCatching { bass?.release() }; runCatching { loud?.release() }
        eq = null; bass = null; loud = null
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
        loud = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        efektleriUygula()
        normHesapla(player.currentMediaItem)
    }

    private fun efektleriUygula() {
        val acik = prefs.getBoolean("eq_acik", false)
        eq?.let { e ->
            runCatching {
                if (acik) {
                    val lv = prefs.getString("eq_seviyeler", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                    for (b in 0 until e.numberOfBands) {
                        e.setBandLevel(b.toShort(), (lv.getOrNull(b) ?: 0).toShort())
                    }
                }
                e.enabled = acik
            }
        }
        bass?.let { b ->
            runCatching {
                val g = prefs.getInt("bass", 0)
                if (b.strengthSupported && g > 0) b.setStrength(g.coerceIn(0, 1000).toShort())
                b.enabled = acik && g > 0
            }
        }
    }

    /** Ses dengeleme: ölçülen kazanca göre sesi kıs ya da (en fazla +6 dB) yükselt. */
    private fun normHesapla(item: MediaItem?) {
        val g = if (prefs.getBoolean("normalize", false) && item != null && !item.mediaId.startsWith("oneri:"))
            MusicRepository.get(this).kazancOf(item.mediaId) else null
        normCarpan = 1f
        runCatching { loud?.enabled = false }
        if (g == null) return
        if (g < 0) {
            normCarpan = Math.pow(10.0, g / 20.0).toFloat()
        } else if (g > 0.5f) {
            runCatching { loud?.setTargetGain((g * 100).toInt()); loud?.enabled = true }
        }
    }

    // ---------------- Android Auto: göz atılabilir kütüphane ----------------

    private fun klasor(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        ).build()

    private fun parcaOge(parent: String, t: Track) = MediaItem.Builder()
        .setMediaId("parca|$parent|${t.id}")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist.ifBlank { null })
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
        ).build()

    private fun parcalar(parent: String): List<Track> {
        val repo = MusicRepository.get(this)
        val lib = repo.tracks.value
        val byId = lib.associateBy { it.id }
        val ist = Istatistik.get(this)
        return when {
            parent == "begeni" -> repo.playlists.value.find { it.id == MusicRepository.BEGENI_ID }?.trackIds?.mapNotNull { byId[it] }.orEmpty()
            parent == "son" -> ist.sonEklenen(lib)
            parent == "encok" -> ist.enCokDinlenen(lib)
            parent == "kutuphane" -> {
                val col = java.text.Collator.getInstance(java.util.Locale("tr"))
                lib.sortedWith { a, b -> col.compare(a.title, b.title) }
            }
            parent.startsWith("liste:") -> repo.playlists.value.find { it.id == parent.removePrefix("liste:") }
                ?.trackIds?.mapNotNull { byId[it] }.orEmpty()
            else -> emptyList()
        }
    }

    private fun cocuklar(parent: String): List<MediaItem> = when (parent) {
        "kok" -> listOf(
            klasor("begeni", "Beğendiklerim"),
            klasor("listeler", "Çalma listeleri"),
            klasor("son", "Son eklenenler"),
            klasor("encok", "En çok dinlediklerim"),
            klasor("kutuphane", "Tüm şarkılar"),
        )
        "listeler" -> MusicRepository.get(this).playlists.value
            .filter { it.id != MusicRepository.BEGENI_ID }
            .map { klasor("liste:${it.id}", it.name) }
        else -> parcalar(parent).map { parcaOge(parent, it) }
    }

    /** Uygulamadan, Auto'dan ya da bildirimden gelen öğeleri çalınabilir hale getirir. */
    private fun cozumle(items: List<MediaItem>): MutableList<MediaItem> {
        val repo = MusicRepository.get(this)
        val byId by lazy { repo.tracks.value.associateBy { it.id } }
        return items.mapNotNull { item ->
            val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
            when {
                uri != null -> item.buildUpon().setUri(uri).build()
                item.mediaId.startsWith("parca|") -> byId[item.mediaId.substringAfterLast('|')]?.let { MedyaOge.of(repo, it) }
                else -> byId[item.mediaId]?.let { MedyaOge.of(repo, it) }
            }
        }.toMutableList()
    }

    private fun oneriEkle(items: List<MediaItem>) {
        if (items.isEmpty() || player.mediaItemCount == 0) return
        val shuffle = player.shuffleModeEnabled
        val eski = if (shuffle) karisikSira() else null
        val blok = sirayiAl(shuffle).size
        val n0 = player.mediaItemCount
        player.addMediaItems(n0, items)
        if (eski != null) {
            val seq = eski.toMutableList()
            val cur = seq.indexOf(player.currentMediaItemIndex).coerceAtLeast(0)
            // Önerileri, sıradakilerden sonra her 3 şarkıda bir araya serpiştir
            items.indices.forEach { j ->
                val pos = (cur + 1 + blok + 2 + j * 3).coerceAtMost(seq.size)
                seq.add(pos, n0 + j)
            }
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(seq.toIntArray(), System.nanoTime()))
        }
    }

    private fun oneriTemizle() {
        val cur = player.currentMediaItemIndex
        (0 until player.mediaItemCount).filter { it != cur && player.getMediaItemAt(it).mediaId.startsWith("oneri:") }
            .sortedDescending().forEach { player.removeMediaItem(it) }
    }

    override fun onCreate() {
        super.onCreate()
        // İndirilmemiş şarkılar: "subplay://akis/<id>" adresi çalınacağı an gerçek internet adresine çevrilir
        val repo = MusicRepository.get(this)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            when {
                spec.uri.scheme != "subplay" -> spec
                spec.uri.host == "yt" -> spec.withUri(Uri.parse(repo.streamUrlVideo(spec.uri.lastPathSegment.orEmpty())))
                else -> spec.withUri(Uri.parse(repo.streamUrl(spec.uri.lastPathSegment.orEmpty())))
            }
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // kulaklık çıkınca durdur
            .setWakeMode(C.WAKE_MODE_LOCAL) // ekran kapalıyken de çalmaya devam et
            .build()

        // Karışık ve tekrar ayarı uygulama kapanıp açılınca da hatırlansın (Spotify gibi)
        prefs = getSharedPreferences("oynatici", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefDinleyici)
        player.shuffleModeEnabled = prefs.getBoolean("karisik", false)
        player.repeatMode = prefs.getInt("tekrar", Player.REPEAT_MODE_OFF)
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(on: Boolean) {
                prefs.edit().putBoolean("karisik", on).apply()
                if (on) karistir()
            }

            override fun onRepeatModeChanged(mode: Int) {
                prefs.edit().putInt("tekrar", mode).apply()
            }

            override fun onPlayerError(error: PlaybackException) {
                // Spotify gibi: çalınamayan şarkıyı (internet yok, video kalkmış) atla
                val title = player.currentMediaItem?.mediaMetadata?.title ?: "Şarkı"
                artArdaHata++
                if (artArdaHata <= 5 && player.hasNextMediaItem()) {
                    repo.notice("\"$title\" çalınamadı, sonrakine geçildi. İnternet bağlantını kontrol et ya da şarkıyı indir.")
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    repo.notice("\"$title\" çalınamadı. İndirilmemiş şarkılar için internet gerekir.")
                    artArdaHata = 0
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) artArdaHata = 0 else KayitliKuyruk.kaydet(this@PlaybackService, player)
                SubPlayWidget.guncelle(this@PlaybackService, player)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                SubPlayWidget.guncelle(this@PlaybackService, player)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                efektleriKur(audioSessionId)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // "Şarkı bitince" uyku: şarkının sonunda durduysa ayarı sıfırla
                if (!playWhenReady && uykuSarkiSonu && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    player.pauseAtEndOfMediaItems = false
                    uykuSarkiSonu = false
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Önceki şarkının dinlenme süresini kaydet, yenisini saymaya başla
                dinlemeyiKaydet()
                dinlenenId = mediaItem?.mediaId
                dinlemeBas = System.currentTimeMillis()
                normHesapla(mediaItem)
                main.post { KayitliKuyruk.kaydet(this@PlaybackService, player) }
                mediaItem?.mediaId?.takeIf { !it.startsWith("oneri:") }?.let { prefs.edit().putString("son_calan", it).apply() }
                // Sıradaki şarkının internet adresini önceden bul
                main.post {
                    val n = player.nextMediaItemIndex
                    if (n != C.INDEX_UNSET) repo.prefetchStream(player.getMediaItemAt(n).mediaId)
                }
                // Spotify gibi: sıraya eklenen şarkı çalıp bitince sıradan düşer
                val prevUid = oncekiUid
                val prevQueued = oncekiKuyrukta
                oncekiUid = mediaItem?.mediaMetadata?.extras?.getString("uid")
                oncekiKuyrukta = MedyaOge.kuyrukta(mediaItem)
                if (prevQueued && prevUid != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    main.post {
                        val idx = (0 until player.mediaItemCount).firstOrNull {
                            player.getMediaItemAt(it).mediaMetadata.extras?.getString("uid") == prevUid
                        }
                        if (idx != null && idx != player.currentMediaItemIndex) player.removeMediaItem(idx)
                    }
                }
            }
        })

        main.post(tick)
        efektleriKur(player.audioSessionId)
        MusicRepository.get(this).sesOlcumuBaslat()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?,
                ): ListenableFuture<LibraryResult<MediaItem>> =
                    Futures.immediateFuture(LibraryResult.ofItem(klasor("kok", "SubPlay"), params))

                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val all = cocuklar(parentId)
                    val ps = pageSize.coerceAtLeast(1).toLong()
                    val from = (page.coerceAtLeast(0) * ps).coerceAtMost(all.size.toLong()).toInt()
                    val sub = all.subList(from, (from + ps).coerceAtMost(all.size.toLong()).toInt())
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(sub), params))
                }

                override fun onGetItem(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    mediaId: String,
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val item = if (mediaId.startsWith("parca|")) {
                        val parts = mediaId.split("|")
                        MusicRepository.get(this@PlaybackService).tracks.value.find { it.id == parts.getOrNull(2) }
                            ?.let { parcaOge(parts.getOrElse(1) { "kutuphane" }, it) }
                    } else cocuklar("kok").find { it.mediaId == mediaId }
                        ?: cocuklar("listeler").find { it.mediaId == mediaId }
                        ?: klasor(mediaId, mediaId)
                    return Futures.immediateFuture(
                        item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    )
                }

                // Uygulama kapalıyken widget / kulaklık tuşuyla "çal" denirse: son çalınan şarkıdan devam et
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val repo = MusicRepository.get(this@PlaybackService)
                    // Kayıtlı liste, şarkı ve saniyeden devam et
                    KayitliKuyruk.oku(this@PlaybackService, repo)?.let { (list, idx, pos) ->
                        main.post { if (player.shuffleModeEnabled) karistir() }
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(list.map { MedyaOge.of(repo, it) }, idx, pos)
                        )
                    }
                    val lib = repo.tracks.value.sortedByDescending { it.addedAt }
                    val son = prefs.getString("son_calan", null)
                    val idx = lib.indexOfFirst { it.id == son }.coerceAtLeast(0)
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(lib.map { MedyaOge.of(repo, it) }, idx, 0L)
                    )
                }

                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val tek = mediaItems.singleOrNull()
                    if (tek != null && tek.mediaId.startsWith("parca|")) {
                        // Android Auto'da bir şarkıya dokunuldu: bulunduğu listenin tamamını o şarkıdan başlat
                        val parts = tek.mediaId.split("|")
                        val parent = parts.getOrElse(1) { "kutuphane" }
                        val list = parcalar(parent)
                        val idx = list.indexOfFirst { it.id == parts.getOrNull(2) }.coerceAtLeast(0)
                        val repo = MusicRepository.get(this@PlaybackService)
                        main.post { if (player.shuffleModeEnabled) karistir() }
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(list.map { MedyaOge.of(repo, it) }, idx, 0L)
                        )
                    }
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(cozumle(mediaItems), startIndex, startPositionMs)
                    )
                }

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().apply {
                        SiraKomut.HEPSI.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
                    }.build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(cmds)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        SiraKomut.EKLE -> {
                            val repo = MusicRepository.get(this@PlaybackService)
                            val byId = repo.tracks.value.associateBy { it.id }
                            val items = args.getStringArray("ids").orEmpty().mapNotNull { byId[it] }
                                .map { MedyaOge.of(repo, it, kuyruk = true) }
                            if (items.isNotEmpty()) siraya(items, args.getBoolean("basa"))
                        }
                        SiraKomut.CIKAR -> sirayiAl().getOrNull(args.getInt("pos"))?.let { player.removeMediaItem(it) }
                        SiraKomut.TASI -> tasi(args.getInt("from"), args.getInt("to"))
                        SiraKomut.TEMIZLE -> sirayiAl().sortedDescending().forEach { player.removeMediaItem(it) }
                        SiraKomut.YENI_KUYRUK -> if (player.shuffleModeEnabled) karistir()
                        SiraKomut.UYKU -> uyku(args.getInt("dk"))
                        SiraKomut.ONERI_EKLE -> {
                            val ids = args.getStringArray("ids").orEmpty()
                            val titles = args.getStringArray("titles").orEmpty()
                            val artists = args.getStringArray("artists").orEmpty()
                            oneriEkle(ids.indices.map { MedyaOge.oneri(ids[it], titles.getOrElse(it) { "" }, artists.getOrElse(it) { "" }) })
                        }
                        SiraKomut.ONERI_TEMIZLE -> oneriTemizle()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // Controller'dan gelen öğelerin URI'si requestMetadata içinde taşınır.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(cozumle(mediaItems))
            })
            .setSessionActivity(openApp)
            .build()
    }

    // ---------------- Sıra ----------------
    // Kural: sıraya eklenen şarkılar, çalan şarkıdan hemen sonra hem dizin sırasında
    // hem karışık sırada art arda durur. Böylece karışık açık da kapalı da olsa önce onlar çalar.

    /** Çalan şarkıdan sonra çalacak dizinler (çalma sırasıyla). */
    private fun sonrakiler(shuffle: Boolean = player.shuffleModeEnabled): List<Int> {
        val tl = player.currentTimeline
        if (tl.isEmpty) return emptyList()
        val out = ArrayList<Int>()
        var i = player.currentMediaItemIndex
        while (true) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
            if (i == C.INDEX_UNSET || out.size > player.mediaItemCount) break
            out += i
        }
        return out
    }

    /** Kullanıcının sıraya eklediği, henüz çalmamış şarkıların dizinleri (çalma sırasıyla). */
    private fun sirayiAl(shuffle: Boolean = player.shuffleModeEnabled): List<Int> =
        sonrakiler(shuffle).takeWhile { MedyaOge.kuyrukta(player.getMediaItemAt(it)) }

    /** Tüm karışık sıra (dizinler). */
    private fun karisikSira(): MutableList<Int> {
        val tl = player.currentTimeline
        val out = ArrayList<Int>()
        if (tl.isEmpty) return out
        var i = tl.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && out.size < player.mediaItemCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    private fun siraya(items: List<MediaItem>, basa: Boolean) {
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items)
            player.prepare()
            player.play()
            return
        }
        val shuffle = player.shuffleModeEnabled
        val block = sirayiAl(shuffle)
        val pos = if (basa) 0 else block.size
        val cur = player.currentMediaItemIndex
        val eski = if (shuffle) karisikSira() else null
        val at = cur + 1 + pos
        player.addMediaItems(at, items)
        if (eski != null) {
            val k = items.size
            val seq = eski.map { if (it >= at) it + k else it }.toMutableList()
            val curPos = seq.indexOf(cur).coerceAtLeast(0)
            seq.addAll((curPos + 1 + pos).coerceAtMost(seq.size), (at until at + k).toList())
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(seq.toIntArray(), System.nanoTime()))
        }
    }

    private fun tasi(from: Int, to: Int) {
        val block = sirayiAl()
        if (from !in block.indices || to !in block.indices || from == to) return
        val items = block.map { player.getMediaItemAt(it) }.toMutableList()
        items.add(to, items.removeAt(from))
        block.sortedDescending().forEach { player.removeMediaItem(it) }
        siraya(items, basa = true)
    }

    /**
     * Spotify gibi karıştır: çalan şarkı yerinde kalır, sıraya eklenenler hemen arkasından gelir,
     * kalan şarkılar aynı sanatçı art arda gelmeyecek şekilde dağıtılır.
     */
    private fun karistir() {
        val n = player.mediaItemCount
        if (n < 2) return
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val queued = (cur + 1 until n).takeWhile { MedyaOge.kuyrukta(player.getMediaItemAt(it)) }
        val rest = (0 until n).filter { it != cur && it !in queued }
        val order = intArrayOf(cur) + queued.toIntArray() + KarisikSira.dengeli(rest, { i ->
            player.getMediaItemAt(i).mediaMetadata.artist?.toString().orEmpty()
        })
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (::player.isInitialized) KayitliKuyruk.kaydet(this, player)
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        if (::player.isInitialized) KayitliKuyruk.kaydet(this, player)
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefDinleyici) }
        runCatching { eq?.release() }; runCatching { bass?.release() }; runCatching { loud?.release() }
        dinlemeyiKaydet()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
