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
import androidx.media3.session.SessionToken
import com.dogukan.muzik.data.MusicRepository
import com.dogukan.muzik.data.Track
import com.dogukan.muzik.player.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUi(
    val currentId: String? = null,
    val title: String? = null,
    val artist: String = "",
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
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
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(listener)
            refresh()
        }, ContextCompat.getMainExecutor(app))

        // Çalarken ilerleme çubuğunu güncelle
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (controller?.isPlaying == true) refresh()
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        _ui.value = PlayerUi(
            currentId = item?.mediaId,
            title = item?.let { c.mediaMetadata.title?.toString() ?: it.mediaMetadata.title?.toString() ?: "" },
            artist = c.mediaMetadata.artist?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            position = c.currentPosition.coerceAtLeast(0),
            duration = c.duration.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
        )
    }

    private fun Track.toMediaItem(): MediaItem {
        val uri = Uri.fromFile(repo.fileOf(this))
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist.ifBlank { null })
                    .build()
            )
            .build()
    }

    fun playTracks(list: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.setMediaItems(list.map { it.toMediaItem() }, startIndex, 0L)
        c.shuffleModeEnabled = shuffle
        c.prepare()
        c.play()
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); refresh() }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
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
