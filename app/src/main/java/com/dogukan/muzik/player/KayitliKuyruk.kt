package com.dogukan.muzik.player

import android.content.Context
import androidx.media3.common.Player
import com.dogukan.muzik.data.MusicRepository
import com.dogukan.muzik.data.Track
import org.json.JSONArray

/**
 * Çalan listeyi, çalan şarkıyı ve kaldığı saniyeyi saklar; uygulama kapanıp açılınca
 * (ya da widget / kulaklık tuşuyla) oradan devam edilir. Öneri şarkıları kaydedilmez.
 */
object KayitliKuyruk {
    private fun prefs(c: Context) = c.getSharedPreferences("oynatici", Context.MODE_PRIVATE)

    fun kaydet(c: Context, p: Player) {
        val n = p.mediaItemCount
        if (n == 0) return
        val cur = p.currentMediaItemIndex.coerceIn(0, n - 1)
        val ids = ArrayList<String>(n)
        var idx = 0
        for (i in 0 until n) {
            if (i == cur) idx = ids.size
            val id = p.getMediaItemAt(i).mediaId
            if (id.startsWith("oneri:")) continue
            ids += id
        }
        if (ids.isEmpty()) return
        val curOneri = p.getMediaItemAt(cur).mediaId.startsWith("oneri:")
        prefs(c).edit()
            .putString("son_kuyruk", JSONArray(ids).toString())
            .putInt("son_index", idx.coerceIn(0, ids.size - 1))
            .putLong("son_konum", if (curOneri) 0L else p.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    /** (şarkılar, başlangıç dizini, konum ms) ya da kayıt yoksa null. Kütüphaneden kaldırılan şarkılar atlanır. */
    fun oku(c: Context, repo: MusicRepository): Triple<List<Track>, Int, Long>? {
        val pr = prefs(c)
        val raw = pr.getString("son_kuyruk", null) ?: return null
        val ids = runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() ?: return null
        val byId = repo.tracks.value.associateBy { it.id }
        val idx = pr.getInt("son_index", 0)
        val curId = ids.getOrNull(idx)
        val list = ids.mapNotNull { byId[it] }
        if (list.isEmpty()) return null
        val yeniIdx = list.indexOfFirst { it.id == curId }
        // Çalan şarkı kaldırıldıysa listenin aynı yerindeki şarkıdan, baştan başla
        return if (yeniIdx >= 0) Triple(list, yeniIdx, pr.getLong("son_konum", 0L))
        else Triple(list, idx.coerceIn(0, list.size - 1), 0L)
    }
}
