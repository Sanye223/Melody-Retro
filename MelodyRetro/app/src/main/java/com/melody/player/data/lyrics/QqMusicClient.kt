package com.melody.player.data.lyrics

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/** QQ 音乐搜索结果里的一首歌。 */
data class QqSong(
    val mid: String,
    val name: String,
    val singers: String,
    val album: String?,
    val intervalSec: Int,
    /** 专辑封面用的 albummid，形如 002Neh8l0uciQZ；部分单曲没有，为空串。 */
    val albumMid: String = "",
)

/**
 * QQ 音乐歌词来源。
 *
 * 两个接口（均为 QQ 音乐网页版自己使用的公开 CGI，无需登录、无需密钥）：
 *
 * 1. 搜索：`GET /soso/fcgi-bin/search_for_qq_cp?w=<关键词>&format=json&n=10&p=1&g_tk=5381`
 *    → `data.song.list[]`，含 `songmid` / `songname` / `singer[].name` / `albumname` / `interval`
 * 2. 歌词：`GET /lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>&format=json&nobase64=1&g_tk=5381`
 *    → `lyric` 字段即标准 LRC 文本；`retcode != 0` 表示无歌词
 *
 * 两个接口都要求带 `Referer: https://y.qq.com/`，否则会被拒绝。
 *
 * 注意：这不是 QQ 音乐官方开放平台接口，字段与可用性可能变化；
 * 因此上层做了降级（回退 LRCLIB）与可读的失败提示。
 */
object QqMusicClient {

    private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
    private const val LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"

    private val headers = mapOf(
        "Referer" to "https://y.qq.com/",
        "Origin" to "https://y.qq.com",
    )

    val label: String = "QQ音乐"

    suspend fun search(keyword: String, limit: Int = 10): List<QqSong> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            val url = buildString {
                append(SEARCH_URL)
                append("?w=").append(encode(keyword))
                append("&format=json&n=").append(limit)
                append("&p=1&g_tk=5381&t=0&cr=1&new_json=1")
            }
            parseSearch(LyricsHttp.get(url, headers))
        }

    /** 返回 LRC 原文；没有歌词时返回 null。网络/接口异常会抛出 [LyricsException]。 */
    suspend fun fetchLyric(mid: String): String? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(LYRIC_URL)
            append("?songmid=").append(encode(mid))
            append("&format=json&nobase64=1&g_tk=5381")
        }
        parseLyric(LyricsHttp.get(url, headers))
    }

    // ------------------------------------------------------------------ 解析

    internal fun parseSearch(body: String): List<QqSong> {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw LyricsException("QQ音乐返回内容无法解析")
        }
        val code = root.optInt("code", -1)
        if (code != 0) {
            throw LyricsException("QQ音乐搜索返回异常（code=$code）")
        }
        val list = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return emptyList()

        val songs = ArrayList<QqSong>(list.length())
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val mid = item.optString("songmid").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("songname").takeIf { it.isNotBlank() } ?: continue

            val singerArray = item.optJSONArray("singer")
            val singers = buildString {
                if (singerArray != null) {
                    for (i in 0 until singerArray.length()) {
                        val singer = singerArray.optJSONObject(i)?.optString("name") ?: continue
                        if (singer.isBlank()) continue
                        if (isNotEmpty()) append('/')
                        append(singer)
                    }
                }
            }

            songs += QqSong(
                mid = mid,
                name = name,
                singers = singers,
                album = item.optString("albumname").takeIf { it.isNotBlank() && it != "null" },
                intervalSec = item.optInt("interval", 0),
                albumMid = item.optString("albummid").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            )
        }
        return songs
    }

    internal fun parseLyric(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw LyricsException("QQ音乐返回内容无法解析")
        }
        val retcode = if (root.has("retcode")) root.optInt("retcode", -1) else root.optInt("code", -1)
        if (retcode != 0) return null // 例如 1101：库里没有这首歌的歌词

        val raw = root.optString("lyric")
        if (raw.isBlank() || raw == "null") return null

        val text = decodeEntities(maybeDecodeBase64(raw)).trim()
        if (text.isEmpty()) return null
        // 有些歌曲只有“纯音乐，请欣赏”之类的一行提示
        if (text.length < 10 && !text.contains('[')) return null
        return text
    }

    /** 在候选里挑最合适的：先严格匹配曲名+歌手，再按时长最接近排序。 */
    internal fun pickBest(
        songs: List<QqSong>,
        title: String,
        artist: String,
        durationSec: Int,
    ): QqSong? = pickCandidates(songs, title, artist, durationSec).firstOrNull()

    /**
     * 按优先级排好全部候选：严格匹配（曲名+歌手）在前，其余在后，同类按时长接近度排序。
     *
     * 之所以要整份列表：搜索结果里常有同名的翻唱/山寨条目，严格匹配反而可能命中它，
     * 而它的歌词往往没有时间轴。上层会逐个取词，挑到第一个带时间轴的就停。
     */
    internal fun pickCandidates(
        songs: List<QqSong>,
        title: String,
        artist: String,
        durationSec: Int,
    ): List<QqSong> {
        if (songs.isEmpty()) return emptyList()
        val normTitle = normalize(title)
        val normArtist = normalize(artist)

        val strict = songs.filter { song ->
            normalize(song.name) == normTitle &&
                (
                    normArtist.isEmpty() ||
                        isUnknown(artist) ||
                        normalize(song.singers).contains(normArtist) ||
                        normArtist.contains(normalize(song.singers.substringBefore('/')))
                    )
        }
        val loose = songs.filterNot { it in strict }

        fun byDuration(pool: List<QqSong>): List<QqSong> =
            if (durationSec > 0) pool.sortedBy { abs(it.intervalSec - durationSec) } else pool

        // 去掉 mid 重复（同一首歌可能在两个池子里都出现）
        return (byDuration(strict) + byDuration(loose)).distinctBy { it.mid }
    }

    // ------------------------------------------------------------------ 工具

    /** QQ 音乐部分接口会把 `[00:12.34]` 转义成 `[00&#58;12.34]`，这里还原。 */
    internal fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var result = numericEntity.replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        result = result
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return result
    }

    /** 少数情况下接口返回 base64 编码的歌词，这里做一次兜底解码。 */
    private fun maybeDecodeBase64(value: String): String {
        val trimmed = value.trim()
        if (trimmed.contains('[')) return trimmed
        if (trimmed.length < 40 || trimmed.any { it.isWhitespace() }) return trimmed
        if (!trimmed.matches(base64Pattern)) return trimmed
        return runCatching {
            String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(trimmed)
    }

    private fun isUnknown(value: String): Boolean =
        value.isBlank() || value.contains("未知") || value.equals("unknown", true)

    internal fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\s\\p{Punct}·・、，。（）()\\[\\]【】《》!?.,'\"-]"), "")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val numericEntity = Regex("&#(\\d{1,5});")
    private val base64Pattern = Regex("^[A-Za-z0-9+/=\\r\\n]+$")
}
