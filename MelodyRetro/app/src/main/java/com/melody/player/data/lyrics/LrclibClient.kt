package com.melody.player.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/** LRCLIB 返回的一条歌词记录。 */
data class LrclibRecord(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationSec: Int,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
) {
    val bestText: String? get() = syncedLyrics?.takeIf { it.isNotBlank() } ?: plainLyrics
    val hasSync: Boolean get() = !syncedLyrics.isNullOrBlank()
}

/**
 * 在线歌词备用源：LRCLIB（https://lrclib.net），开放、免密钥的公共歌词库，
 * 返回标准 LRC（syncedLyrics）与纯文本（plainLyrics）。
 *
 * 主要覆盖海外曲目；境内网络可能访问不稳定，因此只作为 QQ 音乐之后的兜底。
 * 只发送歌曲的 标题 / 艺术家 / 专辑 用于匹配，不发送任何设备或用户信息。
 */
object LrclibClient {

    private const val BASE = "https://lrclib.net/api"

    val label: String = "LRCLIB"

    suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<LrclibRecord> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()

        val params = buildList {
            add("track_name=" + encode(title))
            if (artist.isNotBlank() && !isUnknown(artist)) add("artist_name=" + encode(artist))
            if (!album.isNullOrBlank() && !isUnknown(album)) add("album_name=" + encode(album))
        }
        val url = "$BASE/search?" + params.joinToString("&")
        parseRecords(LyricsHttp.get(url, connectTimeoutMs = 6_000, readTimeoutMs = 8_000))
    }

    /** 在候选里挑最合适的一条：优先带时间轴、艺术家/曲名匹配、时长最接近。 */
    fun pickBest(records: List<LrclibRecord>, title: String, artist: String, durationSec: Int): LrclibRecord? {
        val usable = records.filter { !it.instrumental && !it.bestText.isNullOrBlank() }
        if (usable.isEmpty()) return null

        val normTitle = normalize(title)
        val normArtist = normalize(artist)

        val strict = usable.filter { record ->
            normalize(record.trackName) == normTitle &&
                (normArtist.isEmpty() || isUnknown(artist) ||
                    normalize(record.artistName).contains(normArtist) ||
                    normArtist.contains(normalize(record.artistName)))
        }
        val pool = strict.ifEmpty { usable }

        return pool.sortedWith(
            compareBy<LrclibRecord> { if (it.hasSync) 0 else 1 }
                .thenBy { if (durationSec > 0) abs(it.durationSec - durationSec) else 0 }
        ).firstOrNull()
    }

    private fun isUnknown(value: String): Boolean =
        value.isBlank() || value.contains("未知") || value.equals("unknown", true)

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\s\\p{Punct}·・、，。（）()\\[\\]【】《》!?.,'\"-]"), "")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun parseRecords(body: String): List<LrclibRecord> {
        val array = runCatching { JSONArray(body) }.getOrElse {
            throw LyricsException("LRCLIB 返回内容无法解析")
        }
        val records = ArrayList<LrclibRecord>(array.length())
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            records += LrclibRecord(
                id = json.optLong("id"),
                trackName = json.optString("trackName"),
                artistName = json.optString("artistName"),
                albumName = json.optStringOrNull("albumName"),
                durationSec = json.optDouble("duration", 0.0).toInt(),
                instrumental = json.optBoolean("instrumental", false),
                plainLyrics = json.optStringOrNull("plainLyrics"),
                syncedLyrics = json.optStringOrNull("syncedLyrics"),
            )
        }
        return records
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
