package com.dogukan.muzik.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Şarkı sözleri. satirlar boş değilse zamanlı (senkronize) sözlerdir. */
data class Sozler(val satirlar: List<Pair<Long, String>>, val duz: String?, val enstrumantal: Boolean = false) {
    val zamanli get() = satirlar.isNotEmpty()
    val bos get() = satirlar.isEmpty() && duz.isNullOrBlank() && !enstrumantal
}

/** LRCLIB (ücretsiz, anahtarsız) üzerinden şarkı sözü; sonuçlar cihazda saklanır. */
object SozKaynagi {
    private fun dir(c: Context) = File(c.filesDir, "sozler").apply { mkdirs() }

    fun sil(c: Context, trackId: String) { File(dir(c), "$trackId.json").delete() }

    /** Önce cihazdaki kayda bakar, yoksa LRCLIB'de arar (ağ işi: arka planda çağır). */
    fun getir(c: Context, t: Track, sureSn: Int): Sozler {
        val cache = File(dir(c), "${t.id}.json")
        if (cache.exists()) runCatching { return coz(JSONObject(cache.readText())) }
        val sonuc = ara(t, sureSn)
        val kayit = sonuc ?: JSONObject().put("yok", true)
        runCatching { cache.writeText(kayit.toString()) }
        return sonuc?.let(::coz) ?: Sozler(emptyList(), null)
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "SubPlay (https://github.com/01dogukan10/SubPlay)")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Söz servisi yanıt vermedi (HTTP ${conn.responseCode})")
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun ara(t: Track, sureSn: Int): JSONObject? {
        val baslik = t.title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim().ifBlank { t.title }
        val sanatci = t.artist.substringBefore(',').substringBefore('&').trim()
        val denemeler = buildList {
            if (sanatci.isNotBlank()) add("https://lrclib.net/api/search?track_name=${enc(baslik)}&artist_name=${enc(sanatci)}")
            add("https://lrclib.net/api/search?q=${enc("$sanatci $baslik".trim())}")
        }
        for (url in denemeler) {
            val arr = runCatching { JSONArray(get(url)) }.getOrNull() ?: continue
            val adaylar = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            if (adaylar.isEmpty()) continue
            // Zamanlı sözü olan ve süresi en yakın olanı seç
            val secilen = adaylar.sortedWith(
                compareBy<JSONObject> { if (it.optString("syncedLyrics").isNullOrBlank() || it.isNull("syncedLyrics")) 1 else 0 }
                    .thenBy { if (sureSn > 0) kotlin.math.abs(it.optDouble("duration", 0.0) - sureSn) else 0.0 }
            ).first()
            if (sureSn > 0 && kotlin.math.abs(secilen.optDouble("duration", sureSn.toDouble()) - sureSn) > 20) continue
            return secilen
        }
        return null
    }

    private fun coz(o: JSONObject): Sozler {
        if (o.optBoolean("yok")) return Sozler(emptyList(), null)
        val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
        val plain = if (o.isNull("plainLyrics")) null else o.optString("plainLyrics").ifBlank { null }
        return Sozler(lrc(synced), plain, o.optBoolean("instrumental"))
    }

    /** "[01:23.45] metin" satırlarını (ms, metin) listesine çevirir. */
    fun lrc(s: String): List<Pair<Long, String>> {
        val re = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
        val out = ArrayList<Pair<Long, String>>()
        s.lines().forEach { line ->
            val stamps = re.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val mm = m.groupValues[1].toLong(); val ss = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += (mm * 60_000 + ss * 1000 + ms) to text
            }
        }
        return out.sortedBy { it.first }
    }
}
