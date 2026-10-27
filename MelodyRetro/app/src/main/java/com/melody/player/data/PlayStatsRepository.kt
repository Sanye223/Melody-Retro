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

/** 一首歌的播放统计。 */
data class PlayStat(
    /** 累计播放次数，「我的最爱」按它排序。 */
    val count: Int,
    /** 最后一次播放的时间戳，「最近播放」按它排序。 */
    val lastPlayedAt: Long,
)

private val Context.playStatsDataStore by preferencesDataStore(name = "play_stats")

/**
 * 播放统计（RetroMusicPlayer 的 For you 页靠它撑起「最多播放 / 最近播放」两组）。
 *
 * 上游存在 SQLite 的 `play_count` / `last_played` 两列里；我们的曲库直接从 MediaStore
 * 读，没有自己的表，所以单独存一份。
 *
 * 编码用 `id,count,last;id,count,last`：几百首歌量级，解析开销可以忽略，
 * 也省掉引入序列化库 / 建 Room 表的成本。
 */
object PlayStatsRepository {

    private val KEY_STATS = stringPreferencesKey("stats")

    fun stats(context: Context): Flow<Map<Long, PlayStat>> = context.playStatsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> parse(prefs[KEY_STATS]) }

    /** 记一次播放：次数 +1，刷新时间戳。 */
    suspend fun recordPlay(context: Context, trackId: Long) {
        if (trackId <= 0L) return
        context.playStatsDataStore.edit { prefs ->
            val map = parse(prefs[KEY_STATS]).toMutableMap()
            val old = map[trackId]
            map[trackId] = PlayStat(
                count = (old?.count ?: 0) + 1,
                lastPlayedAt = System.currentTimeMillis(),
            )
            prefs[KEY_STATS] = encode(map)
        }
    }

    internal fun encode(map: Map<Long, PlayStat>): String = map.entries.joinToString(";") {
        "${it.key},${it.value.count},${it.value.lastPlayedAt}"
    }

    /** 解析容错：坏行直接跳过，不让一条脏数据把整个统计读挂。 */
    internal fun parse(raw: String?): Map<Long, PlayStat> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = HashMap<Long, PlayStat>()
        raw.split(';').forEach { line ->
            val parts = line.split(',')
            if (parts.size != 3) return@forEach
            val id = parts[0].toLongOrNull() ?: return@forEach
            val count = parts[1].toIntOrNull() ?: return@forEach
            val last = parts[2].toLongOrNull() ?: return@forEach
            out[id] = PlayStat(count, last)
        }
        return out
    }
}
