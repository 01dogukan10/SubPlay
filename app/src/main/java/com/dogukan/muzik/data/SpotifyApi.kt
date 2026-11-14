package com.dogukan.muzik.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Kullanıcının kendi Spotify geliştirici uygulamasıyla (Client ID) resmi Web API.
 * Giriş PKCE ile yapılır, gizli anahtar gerekmez. Liste başına 100 şarkı sınırı yoktur.
 * Not (Şubat 2026 kuralları): liste şarkıları sadece kullanıcının kendi / ortak listeleri için verilir.
 */
object SpotifyApi {
    const val PORT = 43117
    /** Spotify panelinde birebir bu adres kayıtlı olmalı. */
    const val REDIRECT = "http://127.0.0.1:$PORT/callback"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
    private const val API = "https://api.spotify.com/v1"

    /** Spotify'ın şarkıları vermediği durum (başkasının listesi, yetki yok). */
    class Yasak(msg: String) : IOException(msg)

    private val _bagli = MutableStateFlow(false)
    val bagli = _bagli.asStateFlow()

    private fun p(c: Context) = c.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    fun init(c: Context) { _bagli.value = p(c).getString("refresh", null) != null }

    fun clientId(c: Context): String = p(c).getString("client_id", "").orEmpty()
    fun setClientId(c: Context, id: String) = p(c).edit().putString("client_id", id.trim()).apply()

    fun logout(c: Context) {
        p(c).edit().remove("access").remove("refresh").remove("expires").apply()
        _bagli.value = false
    }

    /** Tarayıcıda açılacak Spotify giriş sayfası. Client ID yoksa null. */
    fun loginIntent(c: Context): Intent? {
        val id = clientId(c).ifBlank { return null }
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        val verifier = (1..64).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        val state = (1..16).map { chars[rnd.nextInt(62)] }.joinToString("")
        p(c).edit().putString("verifier", verifier).putString("state", state).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Giriş dönüşündeki kodu token'a çevirir (arka planda çağır). */
    fun finishLogin(c: Context, data: Uri) {
        data.getQueryParameter("error")?.let { throw IOException("Spotify girişi iptal edildi ya da reddedildi ($it)") }
        val code = data.getQueryParameter("code") ?: throw IOException("Spotify giriş kodu gelmedi")
        if (data.getQueryParameter("state") != p(c).getString("state", null)) throw IOException("Giriş doğrulanamadı, tekrar dene")
        val j = post(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT,
                "client_id" to clientId(c),
                "code_verifier" to p(c).getString("verifier", "").orEmpty(),
            )
        )
        save(c, j)
        _bagli.value = true
    }

    private fun save(c: Context, j: JSONObject) {
        val e = p(c).edit()
            .putString("access", j.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
        j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh", it) }
        e.apply()
    }

    private fun post(form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("error_description") }.getOrNull().orEmpty()
                throw IOException("Spotify giriş hatası (HTTP $code) $msg".trim())
            }
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun token(c: Context, force: Boolean = false): String {
        val pr = p(c)
        val access = pr.getString("access", null)
        if (!force && access != null && System.currentTimeMillis() < pr.getLong("expires", 0) - 60_000) return access
        val refresh = pr.getString("refresh", null) ?: throw IOException("Spotify'a giriş yapılmamış")
        val j = try {
            post(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId(c)))
        } catch (e: IOException) {
            logout(c)
            throw IOException("Spotify oturumu sona erdi, Ayarlar'dan tekrar giriş yap")
        }
        save(c, j)
        return j.getString("access_token")
    }

    private fun get(c: Context, url: String, attempt: Int = 0): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${token(c, force = attempt == 1)}")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            when {
                code in 200..299 -> return JSONObject(text)
                code == 401 && attempt == 0 -> return get(c, url, 1)
                code == 429 && attempt < 3 -> {
                    Thread.sleep((conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 2L).coerceAtMost(30) * 1000)
                    return get(c, url, attempt + 1)
                }
                code == 403 || code == 404 -> {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
                    throw Yasak(msg)
                }
                else -> throw IOException("Spotify hatası (HTTP $code)")
            }
        } finally { conn.disconnect() }
    }

    /** Sayfalayarak tüm öğeleri okur; her öğe (şarkı adı, ilk sanatçı). */
    private fun readAll(c: Context, firstUrl: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var url: String? = firstUrl
        while (url != null) {
            val j = get(c, url)
            val arr = j.optJSONArray("items") ?: break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tr = o.optJSONObject("item") ?: o.optJSONObject("track") ?: continue
                if (tr.optString("type", "track") != "track") continue
                val name = tr.optString("name")
                if (name.isBlank()) continue
                val artist = tr.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                out += name to artist
            }
            url = if (j.isNull("next")) null else j.optString("next").takeIf { it.startsWith("http") }
        }
        return out.distinct()
    }

    /** Listenin adı ve tüm şarkıları. Liste kullanıcının değilse [Yasak] fırlatır. */
    fun playlist(c: Context, id: String): Pair<String, List<Pair<String, String>>> {
        val name = runCatching { get(c, "$API/playlists/$id?fields=name").optString("name") }.getOrNull().orEmpty()
        return name to readAll(c, "$API/playlists/$id/items?limit=50")
    }

    fun likedSongs(c: Context): List<Pair<String, String>> = readAll(c, "$API/me/tracks?limit=50")
}
