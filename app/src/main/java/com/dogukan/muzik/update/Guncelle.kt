package com.dogukan.muzik.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dogukan.muzik.BuildConfig
import com.dogukan.muzik.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class Surum(val kod: Int, val ad: String, val assetUrl: String, val not: String)

/** Gizli GitHub reposunun Releases'ından token ile sürüm kontrolü, indirme ve kurulum. */
object Guncelle {
    const val TABAN = 100 // app/build.gradle.kts: versionCode = 100 + yapı numarası

    private fun prefs(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
    fun token(c: Context): String = prefs(c).getString("gh_token", "").orEmpty()
    fun setToken(c: Context, t: String) = prefs(c).edit().putString("gh_token", t.trim()).apply()

    fun mevcutAd(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""

    private fun mevcutKod(c: Context): Long {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }

    fun yeniMi(c: Context, s: Surum) = TABAN + s.kod > mevcutKod(c)

    private fun ac(url: String, token: String, accept: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SubPlay")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }

    fun sonSurum(c: Context): Surum? {
        val repo = BuildConfig.REPO
        if (repo.isEmpty()) throw IOException("Depo adı bilinmiyor (GitHub dışında derlenmiş)")
        val tok = token(c)
        if (tok.isEmpty()) throw IOException("Önce ⋮ → GitHub token menüsünden token'ı gir")
        val conn = ac("https://api.github.com/repos/$repo/releases/latest", tok, "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                401 -> throw IOException("Token geçersiz ya da süresi dolmuş")
                403, 404 -> throw IOException("Sürüm bulunamadı ya da token'ın bu repoya izni yok")
                else -> throw IOException("GitHub hatası: HTTP $code")
            }
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val kod = j.getString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: return null
            val a = j.getJSONArray("assets")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.getString("name").endsWith(".apk")) {
                    return Surum(kod, j.optString("name", "v$kod"), o.getString("url"), j.optString("body", ""))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    fun indir(c: Context, s: Surum, ilerleme: (Float?) -> Unit): File {
        val f = File(File(c.cacheDir, "updates").apply { mkdirs() }, "guncelle.apk")
        var conn = ac(s.assetUrl, token(c), "application/octet-stream")
        var code = conn.responseCode
        if (code in 300..399) {
            // GitHub geçici bir indirme adresine yönlendirir; oraya token gönderilmez
            val loc = conn.getHeaderField("Location") ?: throw IOException("Yönlendirme adresi yok")
            conn.disconnect()
            conn = ac(loc, "", "application/octet-stream")
            code = conn.responseCode
        }
        try {
            if (code !in 200..299) throw IOException("İndirilemedi (HTTP $code)")
            val total = conn.contentLengthLong
            conn.inputStream.use { i ->
                f.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        ilerleme(if (total > 0) done.toFloat() / total else null)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return f
    }

    fun kurabilir(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun izinAc(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Sistem kurulum ekranını açar; Android orada ayrıca onay ister. */
    fun kur(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun bildir(c: Context, s: Surum) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("guncelle", "Güncellemeler", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(
            c, 2, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, "guncelle")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SubPlay: yeni sürüm var (${s.ad})")
            .setContentText("Güncellemek için dokun")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(2, n) } catch (_: SecurityException) { }
    }

    fun zamanla(c: Context) {
        val req = PeriodicWorkRequestBuilder<GuncelleIsci>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("guncelle", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    internal fun bildirilen(c: Context) = prefs(c).getInt("bildirilen", 0)
    internal fun setBildirilen(c: Context, kod: Int) = prefs(c).edit().putInt("bildirilen", kod).apply()
}

/** Saat başı arka planda bakar; yeni sürüm varsa bildirim gösterir. */
class GuncelleIsci(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        try {
            val c = applicationContext
            val s = withContext(Dispatchers.IO) { Guncelle.sonSurum(c) } ?: return Result.success()
            if (Guncelle.yeniMi(c, s) && Guncelle.bildirilen(c) != s.kod) {
                Guncelle.setBildirilen(c, s.kod)
                Guncelle.bildir(c, s)
            }
        } catch (_: Exception) { }
        return Result.success()
    }
}
