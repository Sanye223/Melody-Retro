package com.melody.player

import android.app.Application
import com.melody.player.data.model.ArtworkUris
import com.melody.player.playback.PlayerManager

class MelodyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ArtworkUris.init(this)
        PlayerManager.init(this)
    }
}
