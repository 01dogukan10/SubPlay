package com.dogukan.muzik

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dogukan.muzik.ui.App

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                val vm: MainViewModel = viewModel()
                App(vm, sharedUrl.value) { sharedUrl.value = null }
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
