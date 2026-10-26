package com.melody.player.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val themeMode: Int = SettingsRepository.THEME_SYSTEM,
    val dynamicColor: Boolean = true,
    val sortOrder: Int = MediaLibraryRepository.SORT_TITLE,
    val onlineLyrics: Boolean = true,
    val onlineArtwork: Boolean = true,
    /** 是否在播放界面显示格式 / 比特率 / 采样率。 */
    val showTrackInfo: Boolean = true,
    /** 歌词字号缩放，1.0 为默认（范围 0.8~1.5）。 */
    val lyricFontScale: Float = 1.0f,
    /** 歌词行上下间距，单位 dp（范围 2~24）。 */
    val lyricSpacing: Int = 9,
)

private val Context.settingsDataStore by preferencesDataStore(name = "melody_settings")

/** 使用 DataStore 持久化主题、动态取色与排序偏好。 */
object SettingsRepository {

    const val MIN_LYRIC_FONT_SCALE = 0.8f
    const val MAX_LYRIC_FONT_SCALE = 1.5f
    const val MIN_LYRIC_SPACING = 2
    const val MAX_LYRIC_SPACING = 24

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SORT_ORDER = intPreferencesKey("sort_order")
        val ONLINE_LYRICS = booleanPreferencesKey("online_lyrics")
        val ONLINE_ARTWORK = booleanPreferencesKey("online_artwork")
        val SHOW_TRACK_INFO = booleanPreferencesKey("show_track_info")
        val LYRIC_FONT_SCALE = floatPreferencesKey("lyric_font_scale")
        val LYRIC_SPACING = intPreferencesKey("lyric_spacing")
    }

    fun settings(context: Context): Flow<AppSettings> = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[Keys.THEME_MODE] ?: THEME_SYSTEM,
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                sortOrder = prefs[Keys.SORT_ORDER] ?: MediaLibraryRepository.SORT_TITLE,
                onlineLyrics = prefs[Keys.ONLINE_LYRICS] ?: true,
                onlineArtwork = prefs[Keys.ONLINE_ARTWORK] ?: true,
                showTrackInfo = prefs[Keys.SHOW_TRACK_INFO] ?: true,
                lyricFontScale = prefs[Keys.LYRIC_FONT_SCALE] ?: 1.0f,
                lyricSpacing = prefs[Keys.LYRIC_SPACING] ?: 9,
            )
        }

    suspend fun setThemeMode(context: Context, mode: Int) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setDynamicColor(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setSortOrder(context: Context, order: Int) {
        context.settingsDataStore.edit { it[Keys.SORT_ORDER] = order }
    }

    suspend fun setOnlineLyrics(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONLINE_LYRICS] = enabled }
    }

    suspend fun setOnlineArtwork(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONLINE_ARTWORK] = enabled }
    }

    suspend fun setShowTrackInfo(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_TRACK_INFO] = enabled }
    }

    suspend fun setLyricFontScale(context: Context, scale: Float) {
        context.settingsDataStore.edit {
            it[Keys.LYRIC_FONT_SCALE] = scale.coerceIn(MIN_LYRIC_FONT_SCALE, MAX_LYRIC_FONT_SCALE)
        }
    }

    suspend fun setLyricSpacing(context: Context, dp: Int) {
        context.settingsDataStore.edit {
            it[Keys.LYRIC_SPACING] = dp.coerceIn(MIN_LYRIC_SPACING, MAX_LYRIC_SPACING)
        }
    }
}
