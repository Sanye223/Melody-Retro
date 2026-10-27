package com.melody.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** 用户资料：For you 横幅上的昵称与自定义背景图。 */
data class UserProfile(
    val name: String = "",
    /** 横幅图的 content uri，null 表示用默认渐变。 */
    val bannerUri: String? = null,
)

private val Context.profileDataStore by preferencesDataStore(name = "user_profile")

/** 对应上游的 user info 页（昵称 + 横幅图）。 */
object ProfileRepository {

    private val KEY_NAME = stringPreferencesKey("name")
    private val KEY_BANNER = stringPreferencesKey("banner_uri")

    fun profile(context: Context): Flow<UserProfile> = context.profileDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            UserProfile(
                name = prefs[KEY_NAME].orEmpty(),
                bannerUri = prefs[KEY_BANNER],
            )
        }

    suspend fun setName(context: Context, name: String) {
        context.profileDataStore.edit { it[KEY_NAME] = name.trim() }
    }

    /** 传 null 清除横幅，For you 会退回封面主色渐变。 */
    suspend fun setBanner(context: Context, uri: String?) {
        context.profileDataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_BANNER) else prefs[KEY_BANNER] = uri
        }
    }
}
