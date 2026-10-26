package com.melody.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melody.player.data.AppSettings
import com.melody.player.data.SettingsRepository
import com.melody.player.playback.PlayerManager
import com.melody.player.ui.AppRoot
import com.melody.player.ui.theme.MelodyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerManager.init(applicationContext)

        setContent {
            val context = LocalContext.current
            val settingsFlow = remember { SettingsRepository.settings(context.applicationContext) }
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())

            MelodyTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppRoot(settings = settings)
            }
        }
    }
}
