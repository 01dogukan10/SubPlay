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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val temaDeposu = TemaDeposu.get(this)
        setContent {
            val tema by temaDeposu.ayar.collectAsStateWithLifecycle()
            SubPlayTema(tema) {
                val vm: MainViewModel = viewModel()
                App(
                    vm = vm,
                    sharedUrl = sharedUrl.value,
                    onSharedConsumed = { sharedUrl.value = null },
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
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.let { sharedUrl.value = it.value }
    }
}
