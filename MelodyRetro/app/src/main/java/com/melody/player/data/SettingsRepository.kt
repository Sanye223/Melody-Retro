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
import kotlinx.coroutines.flow.first
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
    /** 歌词字号缩放，1.2 为默认（范围 0.8~1.5），即正文 16sp → 19.2sp。 */
    val lyricFontScale: Float = SettingsRepository.DEFAULT_LYRIC_FONT_SCALE,
    /** 歌词行上下间距，单位 dp（范围 2~24）。 */
    val lyricSpacing: Int = SettingsRepository.DEFAULT_LYRIC_SPACING,
    /** 历史页排序：0 = 按最近播放，1 = 按播放次数。 */
    val historySort: Int = SettingsRepository.HISTORY_SORT_RECENT,
)

private val Context.settingsDataStore by preferencesDataStore(name = "melody_settings")

/** 使用 DataStore 持久化主题、动态取色与排序偏好。 */
object SettingsRepository {

    const val MIN_LYRIC_FONT_SCALE = 0.8f
    const val MAX_LYRIC_FONT_SCALE = 1.5f
    const val MIN_LYRIC_SPACING = 2
    const val MAX_LYRIC_SPACING = 24

    /** 默认歌词字号：比正文大 20%（16sp → 19.2sp）。取值落在字号滑块的档位上。 */
    const val DEFAULT_LYRIC_FONT_SCALE = 1.2f

    /** 默认上下间距：字号变大后间距收窄，整屏能多看两行。 */
    const val DEFAULT_LYRIC_SPACING = 4

    /** 历史页排序：按最近播放（默认）/ 按播放次数。 */
    const val HISTORY_SORT_RECENT = 0
    const val HISTORY_SORT_COUNT = 1

    /** 2.4.4 及更早版本的默认值，仅用于一次性迁移。 */
    private const val LEGACY_LYRIC_FONT_SCALE = 1.0f
    private const val LEGACY_LYRIC_SPACING = 9

    /** 设置项版本。改动默认值时必须 +1，老用户才会跟着升级。 */
    private const val SETTINGS_SCHEMA = 2

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
        val HISTORY_SORT = intPreferencesKey("history_sort")
        val SCHEMA = intPreferencesKey("settings_schema")
    }

    /**
     * 把仍是「旧默认值」的歌词字号/间距升级到新的默认值，只执行一次。
     *
     * 只动等于旧默认（1.0× / 9dp）或从未写过的项 —— 用户自己调过的数值一律保留，
     * 否则等于替人做决定。
     */
    suspend fun migrateLyricDefaultsIfNeeded(context: Context) {
        val prefs = context.settingsDataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .first()
        if ((prefs[Keys.SCHEMA] ?: 1) >= SETTINGS_SCHEMA) return

        val font = prefs[Keys.LYRIC_FONT_SCALE]
        val spacing = prefs[Keys.LYRIC_SPACING]
        context.settingsDataStore.edit { p ->
            if (isLegacyDefaultFontScale(font)) p[Keys.LYRIC_FONT_SCALE] = DEFAULT_LYRIC_FONT_SCALE
            if (isLegacyDefaultSpacing(spacing)) p[Keys.LYRIC_SPACING] = DEFAULT_LYRIC_SPACING
            p[Keys.SCHEMA] = SETTINGS_SCHEMA
        }
    }

    /** 该字号是否仍是旧默认（`null` 表示用户从未写过）。 */
    internal fun isLegacyDefaultFontScale(stored: Float?): Boolean =
        stored == null || stored == LEGACY_LYRIC_FONT_SCALE

    /** 该间距是否仍是旧默认（`null` 表示用户从未写过）。 */
    internal fun isLegacyDefaultSpacing(stored: Int?): Boolean =
        stored == null || stored == LEGACY_LYRIC_SPACING

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
                lyricFontScale = prefs[Keys.LYRIC_FONT_SCALE] ?: DEFAULT_LYRIC_FONT_SCALE,
                lyricSpacing = prefs[Keys.LYRIC_SPACING] ?: DEFAULT_LYRIC_SPACING,
                historySort = prefs[Keys.HISTORY_SORT] ?: HISTORY_SORT_RECENT,
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

    suspend fun setHistorySort(context: Context, sort: Int) {
        context.settingsDataStore.edit { it[Keys.HISTORY_SORT] = sort }
    }
}
