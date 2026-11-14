package com.dogukan.muzik.player

import kotlin.random.Random

/**
 * Spotify'ın karıştırma mantığı: aynı sanatçının şarkıları listeye eşit aralıklarla dağıtılır,
 * böylece aynı sanatçı art arda gelmez ve sıra "gerçekten karışık" hissettirir.
 * (Her sanatçının şarkıları 0..1 aralığına rastgele bir başlangıç + eşit adımlarla yerleştirilir,
 * sonra tüm şarkılar bu konumlara göre sıralanır.)
 */
object KarisikSira {
    fun dengeli(indices: List<Int>, artistOf: (Int) -> String, rnd: Random = Random): IntArray {
        if (indices.size < 2) return indices.toIntArray()
        val groups = indices.groupBy { i -> artistOf(i).trim().lowercase().ifBlank { "#$i" } }
        val placed = ArrayList<Pair<Double, Int>>(indices.size)
        for ((_, group) in groups) {
            val songs = group.shuffled(rnd)
            val n = songs.size
            val step = 1.0 / n
            val offset = rnd.nextDouble() * step
            songs.forEachIndexed { k, idx ->
                // eşit aralık + küçük sapma (her seferinde aynı desen çıkmasın)
                val jitter = (rnd.nextDouble() - 0.5) * step * 0.2
                placed += (offset + k * step + jitter) to idx
            }
        }
        return placed.sortedBy { it.first }.map { it.second }.toIntArray()
    }
}
