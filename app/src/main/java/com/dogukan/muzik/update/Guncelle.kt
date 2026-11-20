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

/** etiket: "1.15.1" gibi sürüm numarası. */
data class Surum(val etiket: String, val ad: String, val assetUrl: String, val not: String)

/** "1.15.1" -> [1, 15, 1]. Eski tek sayılı sürümler ("31") en eski sayılır. */
private fun parcala(s: String): List<Int> {
    val p = s.trim().removePrefix("v").removePrefix("Sürüm ").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    return if (p.size < 3) listOf(0, 0, 0) else p
}

private fun daha_yeni(a: String, b: String): Boolean {
    val x = parcala(a); val y = parcala(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
        if (d != 0) return d > 0
    }
    return false
}

/** Gizli GitHub reposunun Releases'ından token ile sürüm kontrolü, indirme ve kurulum. */
object Guncelle {
    const val TABAN = 100 // app/build.gradle.kts: versionCode = 100 + yapı numarası

    private fun prefs(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
    fun token(c: Context): String = prefs(c).getString("gh_token", "").orEmpty()
    fun setToken(c: Context, t: String) = prefs(c).edit().putString("gh_token", t.trim()).apply()

    private fun mevcutNo(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    fun mevcutAd(c: Context): String = "Sürüm " + mevcutNo(c)

    fun yeniMi(c: Context, s: Surum) = daha_yeni(s.etiket, mevcutNo(c))

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
        val conn = ac("https://api.github.com/repos/$repo/releases/latest", tok, "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                401 -> throw IOException("Token geçersiz ya da süresi dolmuş")
                403, 404 -> throw IOException(
                    if (tok.isEmpty()) "Sürüm bulunamadı. Repo gizliyse herkese açık (public) yapınca güncellemeler çalışır."
                    else "Sürüm bulunamadı ya da token'ın bu repoya izni yok"
                )
                else -> throw IOException("GitHub hatası: HTTP $code")
            }
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val etiket = j.getString("tag_name").removePrefix("v")
            val a = j.getJSONArray("assets")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.getString("name").endsWith(".apk")) {
                    // Eski sürümlerdeki imza satırlarını gösterme
                    val not = j.optString("body", "").lines()
                        .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") }
                        .joinToString("\n").trim()
                    return Surum(etiket, j.optString("name", "Sürüm $etiket"), o.getString("url"), not)
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

    /**
     * Kurulum izni gerekiyor mu? Android 12+ ve uygulamayı en son SubPlay'in kendisi kurduysa
     * "bilinmeyen uygulamaları yükle" izni gerekmez (kendini onaysız güncelleyebilir).
     */
    fun kurabilir(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls() || kendiKurdu(c)

    fun kendiKurdu(c: Context): Boolean = Build.VERSION.SDK_INT >= 31 && runCatching {
        c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName == c.packageName
    }.getOrDefault(false)

    fun sonApk(c: Context): File? = File(File(c.cacheDir, "updates"), "guncelle.apk").takeIf { it.exists() }

    fun izinAc(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Güncellemeyi uygulamanın kendisi kurar (PackageInstaller). Android 12+ telefonlarda, bir kez bu yolla
     * kurulduktan sonraki güncellemeler onay ve "bilinmeyen uygulamalar" izni istemeden kurulur.
     * Telefon onay isterse onay ekranı açılır; olmazsa eski yönteme düşer.
     */
    fun kur(c: Context, f: File) {
        try {
            val yukleyici = c.packageManager.packageInstaller
            val ayar = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(c.packageName)
                if (Build.VERSION.SDK_INT >= 26) setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val no = yukleyici.createSession(ayar)
            yukleyici.openSession(no).use { s ->
                s.openWrite("subplay.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val bayrak = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(c, no, Intent(c, KurulumAlici::class.java).setPackage(c.packageName), bayrak)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            kurEski(c, f)
        }
    }

    /** Sistem kurulum ekranını açar; Android orada ayrıca onay ister. */
    fun kurEski(c: Context, f: File) {
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

    internal fun bildirilen(c: Context) = prefs(c).getString("bildirilen_surum", "").orEmpty()
    internal fun setBildirilen(c: Context, etiket: String) = prefs(c).edit().putString("bildirilen_surum", etiket).apply()
}

/** Saat başı arka planda bakar; yeni sürüm varsa bildirim gösterir. */
class GuncelleIsci(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        try {
            val c = applicationContext
            val s = withContext(Dispatchers.IO) { Guncelle.sonSurum(c) } ?: return Result.success()
            if (Guncelle.yeniMi(c, s) && Guncelle.bildirilen(c) != s.etiket) {
                Guncelle.setBildirilen(c, s.etiket)
                Guncelle.bildir(c, s)
            }
        } catch (_: Exception) { }
        return Result.success()
    }
}
