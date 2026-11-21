package com.dogukan.muzik.player

import android.content.Context
import androidx.media3.common.Player
import com.dogukan.muzik.data.MusicRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Çalan listeyi, çalan şarkıyı ve kaldığı saniyeyi saklar; uygulama kapanıp açılınca
 * (ya da widget / kulaklık tuşuyla) oradan devam edilir. Öneri şarkıları kaydedilmez.
 * Liste ayrı dosyada tutulur ve sadece değişince yazılır; saniye küçük bir dosyaya yazılır.
 */
object KayitliKuyruk {
    class Kayit(val ogeler: List<androidx.media3.common.MediaItem>, val index: Int, val konum: Long)

    private fun liste(c: Context) = c.getSharedPreferences("kaldigi_yer_liste", Context.MODE_PRIVATE)
    private fun konum(c: Context) = c.getSharedPreferences("kaldigi_yer", Context.MODE_PRIVATE)
    @Volatile private var sonYazilan: String? = null

    fun kaydet(c: Context, p: Player) {
        val n = p.mediaItemCount
        if (n == 0) return
        val cur = p.currentMediaItemIndex.coerceIn(0, n - 1)
        val ids = JSONArray(); val sira = JSONArray(); val onizleme = JSONObject()
        var idx = 0
        for (i in 0 until n) {
            val item = p.getMediaItemAt(i)
            if (i == cur) idx = ids.length()
            if (item.mediaId.startsWith("oneri:")) continue
            ids.put(item.mediaId); sira.put(MedyaOge.kuyrukta(item))
            // Aramadan dinlenen şarkının adını da sakla (kütüphanede olmadığı için)
            if (item.mediaId.startsWith("yt:")) onizleme.put(item.mediaId, JSONArray()
                .put(item.mediaMetadata.title?.toString().orEmpty()).put(item.mediaMetadata.artist?.toString().orEmpty()))
        }
        if (ids.length() == 0) return
        val json = JSONObject().put("ids", ids).put("kuyruk", sira).put("onizleme", onizleme).toString()
        if (json != sonYazilan) {
            liste(c).edit().putString("liste", json).apply()
            sonYazilan = json
        }
        val curOneri = p.getMediaItemAt(cur).mediaId.startsWith("oneri:")
        konum(c).edit()
            .putInt("index", idx.coerceIn(0, ids.length() - 1))
            .putLong("konum", if (curOneri) 0L else p.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    /** Liste tamamen boşaltıldıysa kaydı sil; böylece eski liste geri gelmez. */
    fun sil(c: Context) {
        liste(c).edit().clear().apply(); konum(c).edit().clear().apply()
        sonYazilan = null
    }

    /** Kayıt yoksa null. Kütüphaneden kaldırılan şarkılar atlanır. */
    fun oku(c: Context, repo: MusicRepository): Kayit? {
        val raw = liste(c).getString("liste", null) ?: return null
        val onz = runCatching { JSONObject(raw).optJSONObject("onizleme") }.getOrNull()
        val (ids, sira) = runCatching {
            val o = JSONObject(raw)
            val a = o.getJSONArray("ids"); val k = o.optJSONArray("kuyruk")
            (0 until a.length()).map { a.getString(it) } to (0 until a.length()).map { k?.optBoolean(it) == true }
        }.getOrNull() ?: return null
        val byId = repo.tracks.value.associateBy { it.id }
        val pr = com.dogukan.muzik.data.PodcastRepo.get(c)
        val idx = konum(c).getInt("index", 0)
        val curId = ids.getOrNull(idx)
        // Şarkılar kütüphaneden, podcast bölümleri podcast kaydından; silinmiş olanlar atlanır
        val ogeler = ArrayList<androidx.media3.common.MediaItem>(); val kimlikler = ArrayList<String>()
        ids.forEachIndexed { i, id ->
            val oge = if (id.startsWith("yt:")) onz?.optJSONArray(id)?.let { a -> MedyaOge.onizleme(id.removePrefix("yt:"), a.optString(0), a.optString(1)) }
                else if (MedyaOge.podcastMi(id)) pr.bolum(com.dogukan.muzik.data.PodcastRepo.idOf(id))?.let { MedyaOge.bolum(pr, it) }
                else byId[id]?.let { MedyaOge.of(repo, it, sira[i]) }
            if (oge != null) { ogeler += oge; kimlikler += id }
        }
        if (ogeler.isEmpty()) return null
        val yeniIdx = kimlikler.indexOf(curId)
        // Çalan şarkı kaldırıldıysa listenin aynı yerindeki şarkıdan, baştan başla
        return if (yeniIdx >= 0) Kayit(ogeler, yeniIdx, konum(c).getLong("konum", 0L))
        else Kayit(ogeler, idx.coerceIn(0, ogeler.size - 1), 0L)
    }
}
