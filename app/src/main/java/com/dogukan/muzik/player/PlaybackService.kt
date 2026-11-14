package com.dogukan.muzik.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dogukan.muzik.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Arka planda çalmayı ve bildirim kontrollerini sağlayan servis. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var sonKuyruk = 0 // karıştırılan kuyruğun imzası (aynı kuyruğu tekrar karıştırmamak için)

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
                if (on) { sonKuyruk = 0; dengeliKaristir(player) }
            }

            override fun onRepeatModeChanged(mode: Int) {
                prefs.edit().putInt("tekrar", mode).apply()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Yeni kuyruk geldiyse (şarkıya dokunma, liste çalma) ve karışık açıksa yeniden karıştır
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled) {
                    dengeliKaristir(player)
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
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

    private fun kuyrukImzasi(p: ExoPlayer): Int {
        var h = p.mediaItemCount
        for (i in 0 until p.mediaItemCount) h = 31 * h + p.getMediaItemAt(i).mediaId.hashCode()
        return h
    }

    /**
     * Spotify gibi: çalan şarkı yerinde kalır, kalan şarkılar aynı sanatçı art arda gelmeyecek
     * şekilde dağıtılarak karıştırılır.
     */
    private fun dengeliKaristir(p: ExoPlayer) {
        val n = p.mediaItemCount
        if (n < 2) return
        val imza = kuyrukImzasi(p)
        if (imza == sonKuyruk) return
        sonKuyruk = imza
        val cur = p.currentMediaItemIndex.coerceIn(0, n - 1)
        val rest = (0 until n).filter { it != cur }
        val order = intArrayOf(cur) + KarisikSira.dengeli(rest, { i ->
            p.getMediaItemAt(i).mediaMetadata.artist?.toString().orEmpty()
        })
        p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
