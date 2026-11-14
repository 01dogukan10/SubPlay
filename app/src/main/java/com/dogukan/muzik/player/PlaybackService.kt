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
import androidx.media3.session.MediaSessionService
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
    val HEPSI = listOf(EKLE, CIKAR, TASI, TEMIZLE, YENI_KUYRUK, UYKU)
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
}

/** Arka planda çalmayı, bildirim kontrollerini ve çalma sırasını yöneten servis. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
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
    private val tick = object : Runnable {
        override fun run() {
            if (::player.isInitialized) adim()
            main.postDelayed(this, TICK)
        }
    }

    /** Her 250 ms: ses (yumuşak geçiş + uyku kısması), uyku süresi, dinleme süresi. */
    private fun adim() {
        val prefs = getSharedPreferences("oynatici", MODE_PRIVATE)
        if (player.isPlaying) dinlenenMs += TICK
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
        v = v.coerceIn(0f, 1f)
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

    override fun onCreate() {
        super.onCreate()
        // İndirilmemiş şarkılar: "subplay://akis/<id>" adresi çalınacağı an gerçek internet adresine çevrilir
        val repo = MusicRepository.get(this)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == "subplay") spec.withUri(Uri.parse(repo.streamUrl(spec.uri.lastPathSegment.orEmpty())))
            else spec
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
        val prefs = getSharedPreferences("oynatici", MODE_PRIVATE)
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
                if (isPlaying) artArdaHata = 0
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

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
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
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // Controller'dan gelen öğelerin URI'si requestMetadata içinde taşınır.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri != null) item.buildUpon().setUri(uri).build() else item
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
            })
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        dinlemeyiKaydet()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
