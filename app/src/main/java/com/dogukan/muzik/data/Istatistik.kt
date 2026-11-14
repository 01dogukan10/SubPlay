package com.dogukan.muzik.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Bir dinleme: hangi şarkı, ne zaman başladı, kaç ms dinlendi. */
data class Dinleme(val trackId: String, val zaman: Long, val ms: Long)

data class SarkiSayisi(val track: Track, val kez: Int, val ms: Long)
data class SanatciSayisi(val ad: String, val kez: Int, val ms: Long)

data class Ozet(
    val toplamMs: Long,
    val dinlemeSayisi: Int,
    val farkliSarki: Int,
    val enCokSarkilar: List<SarkiSayisi>,
    val enCokSanatcilar: List<SanatciSayisi>,
    val gunlukDk: List<Pair<Long, Int>>, // (gün başlangıcı, dakika)
)

/**
 * Dinleme geçmişi (cihazda, dinleme.jsonl dosyasında). En az 30 saniye dinlenen şarkılar sayılır.
 * Akıllı listeler ve istatistik ekranı buradan beslenir.
 */
class Istatistik private constructor(ctx: Context) {
    companion object {
        const val SAYILMA_MS = 30_000L
        @Volatile private var inst: Istatistik? = null
        fun get(c: Context) = inst ?: synchronized(this) { inst ?: Istatistik(c.applicationContext).also { inst = it } }
    }

    private val file = File(ctx.filesDir, "dinleme.jsonl")
    private val lock = Any()
    private val olaylar: MutableList<Dinleme> = runCatching {
        file.readLines().mapNotNull { l ->
            runCatching { JSONObject(l).let { Dinleme(it.getString("id"), it.getLong("t"), it.getLong("ms")) } }.getOrNull()
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    /** Her yeni dinlemede artar; ekranlar yeniden hesaplasın diye. */
    private val _surum = MutableStateFlow(0)
    val surum = _surum.asStateFlow()

    fun kaydet(trackId: String, zaman: Long, ms: Long) {
        if (ms < SAYILMA_MS) return
        synchronized(lock) {
            olaylar += Dinleme(trackId, zaman, ms)
            runCatching { file.appendText(JSONObject().put("id", trackId).put("t", zaman).put("ms", ms).toString() + "\n") }
        }
        _surum.value++
    }

    private fun kopya(): List<Dinleme> = synchronized(lock) { olaylar.toList() }

    fun dinlemeSayilari(): Map<String, Int> = kopya().groupingBy { it.trackId }.eachCount()
    fun sonDinleme(): Map<String, Long> = kopya().groupBy { it.trackId }.mapValues { (_, l) -> l.maxOf { it.zaman } }

    /** Akıllı liste: en çok dinlenenler. */
    fun enCokDinlenen(lib: List<Track>, limit: Int = 50): List<Track> {
        val say = dinlemeSayilari()
        return lib.filter { (say[it.id] ?: 0) > 0 }.sortedByDescending { say[it.id] ?: 0 }.take(limit)
    }

    /** Akıllı liste: en son eklenenler. */
    fun sonEklenen(lib: List<Track>, limit: Int = 50): List<Track> = lib.sortedByDescending { it.addedAt }.take(limit)

    /** Akıllı liste: uzun süredir (ya da hiç) dinlenmeyenler; son 7 günde eklenenler hariç. */
    fun unutulan(lib: List<Track>, limit: Int = 50): List<Track> {
        val son = sonDinleme()
        val hafta = System.currentTimeMillis() - 7 * 86_400_000L
        return lib.filter { it.addedAt < hafta }
            .sortedBy { son[it.id] ?: 0L }
            .take(limit)
    }

    /** gun = 0 -> tüm zamanlar. */
    fun ozet(lib: List<Track>, gun: Int): Ozet {
        val simdi = System.currentTimeMillis()
        val bas = if (gun > 0) simdi - gun * 86_400_000L else 0L
        val l = kopya().filter { it.zaman >= bas }
        val byId = lib.associateBy { it.id }
        val sarkilar = l.groupBy { it.trackId }.mapNotNull { (id, ev) ->
            byId[id]?.let { SarkiSayisi(it, ev.size, ev.sumOf { e -> e.ms }) }
        }.sortedWith(compareByDescending<SarkiSayisi> { it.kez }.thenByDescending { it.ms })
        val sanatcilar = l.mapNotNull { e -> byId[e.trackId]?.let { it.artist.ifBlank { null }?.let { a -> a to e } } }
            .groupBy({ it.first }, { it.second })
            .map { (a, ev) -> SanatciSayisi(a, ev.size, ev.sumOf { it.ms }) }
            .sortedWith(compareByDescending<SanatciSayisi> { it.ms }.thenByDescending { it.kez })
        val gunSayisi = if (gun in 1..31) gun else 0
        val gunluk = if (gunSayisi > 0) {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val bugun = cal.timeInMillis
            (gunSayisi - 1 downTo 0).map { k ->
                val g = bugun - k * 86_400_000L
                g to (l.filter { it.zaman in g until g + 86_400_000L }.sumOf { it.ms } / 60_000L).toInt()
            }
        } else emptyList()
        return Ozet(l.sumOf { it.ms }, l.size, l.map { it.trackId }.distinct().size,
            sarkilar.take(10), sanatcilar.take(10), gunluk)
    }
}
