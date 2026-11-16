package com.dogukan.muzik

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dogukan.muzik.ui.App
import com.dogukan.muzik.ui.SubPlayTema
import com.dogukan.muzik.ui.TemaDeposu

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)
    private val sharedList = mutableStateOf<String?>(null)
    private val sekme = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        // Bildirim iznini sadece bir kez sor
        val prefs = getSharedPreferences("ayarlar", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("bildirim_soruldu", false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("bildirim_soruldu", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val temaDeposu = TemaDeposu.get(this)
        setContent {
            val tema by temaDeposu.ayar.collectAsStateWithLifecycle()
            SubPlayTema(tema) {
                val vm: MainViewModel = viewModel()
                App(
                    vm = vm,
                    sekmeIstegi = sekme.value,
                    onSekmeAcildi = { sekme.value = null },
                    sharedUrl = sharedUrl.value,
                    onSharedConsumed = { sharedUrl.value = null },
                    sharedList = sharedList.value,
                    onSharedListConsumed = { sharedList.value = null },
                    tema = tema,
                    onTema = temaDeposu::kaydet,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Tarayıcıdan "Paylaş → Müzik" ile gelen linki yakala. */
    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("sekme")?.let { sekme.value = it }
        // Uygulama simgesi kısayolları: devam / karışık / beğendiklerim
        if (i?.action == "com.subplay.muzik.KISAYOL") {
            i.data?.host?.let { com.dogukan.muzik.MainViewModel.bekleyenKisayol.value = it }
            return
        }
        if (i?.action == Intent.ACTION_VIEW && i.data?.scheme == "submuzik") {
            // "done" ise sadece uygulamaya dönüş; kod geldiyse girişi tamamla
            i.data?.takeIf { it.getQueryParameter("code") != null || it.getQueryParameter("error") != null }
                ?.let { com.dogukan.muzik.data.MusicRepository.get(this).finishSpotifyLogin(it) }
            return
        }
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value
        when {
            text.lines().count { it.isNotBlank() } >= 2 -> sharedList.value = text // birden çok şarkı / link
            url != null -> sharedUrl.value = url
        }
    }
}
