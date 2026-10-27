package com.melody.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

/**
 * 收藏的歌（「我的最爱」）。
 *
 * 上游是 Room 里的 FavoritesPlaylist；我们只需要「哪些歌被收藏了」这一个集合，
 * 用 DataStore 的 StringSet 存 trackId 就够，切换是 O(1) 的增删。
 */
object FavoriteRepository {

    private val KEY_IDS = stringSetPreferencesKey("ids")

    fun ids(context: Context): Flow<Set<Long>> = context.favoritesDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> (prefs[KEY_IDS] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun toggle(context: Context, trackId: Long) {
        if (trackId <= 0L) return
        context.favoritesDataStore.edit { prefs ->
            val current = prefs[KEY_IDS] ?: emptySet()
            val key = trackId.toString()
            prefs[KEY_IDS] = if (key in current) current - key else current + key
        }
    }
}
