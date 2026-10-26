package com.melody.player.data.artwork

import android.util.Log
import com.melody.player.data.GuessedMeta
import com.melody.player.data.lyrics.QqMusicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * 一张专辑在本地能拿到的元信息，用于在线检索。
 * 全部来自 MediaStore，不额外读文件，避免影响扫描/播放性能。
 */
data class AlbumMeta(
    val albumId: Long,
    val album: String,
    val artist: String,
    val trackTitle: String,
    /**
     * 从文件名推断出的 (歌名, 艺术家) 候选。
     * 无标签文件（下载来的 MP3 很常见）只有靠它才有东西可搜。
     */
    val guessVariants: List<GuessedMeta> = emptyList(),
) {
    /**
     * 可用于搜索/打分的 (歌名, 艺术家) 组合：标签优先，其次文件名推断。
     * 方向判断不确定时会有两条（`A - B` 与 `B - A`），打分时取更高的那个。
     */
    val keys: List<Pair<String, String>>
        get() {
            val out = LinkedHashSet<Pair<String, String>>()
            if (trackTitle.isNotBlank()) out += trackTitle to artist
            guessVariants.forEach { out += it.title to it.artist }
            return out.filter { it.first.isNotBlank() }
        }

    val hasUsableText: Boolean
        get() = album.isNotBlank() || keys.isNotEmpty()
}

/** 在线检索到的封面候选。 */
data class CoverHit(
    val url: String,
    val albumName: String,
    val artistName: String,
    /** 展示用数据源名，例如 "QQ音乐"。 */
    val source: String,
    /** 匹配得分，越高越可信。 */
    val score: Int,
)

/**
 * 在线专辑封面检索。
 *
 * 优先级（命中即返回，失败才降级）：
 *
 * 1. **QQ音乐**：复用歌词模块的搜索接口拿到 `albummid`，再拼封面地址
 *    `https://y.gtimg.cn/music/photo_new/T002R500x500M000<albummid>.jpg`。
 *    华语/日韩命中率最高；不存在的专辑直接 404，很容易判别。
 * 2. **iTunes Search**：`search?term=&entity=album`，把 `artworkUrl100` 里的
 *    `100x100bb` 换成 `600x600bb` 拿大图。用于补 QQ 音乐没有的欧美曲目。
 *
 * 两者都只用到歌名 / 艺术家 / 专辑名这类公开元数据，不发送设备标识。
 * 任何一档都可能因为网络或接口变动失效，因此都包在 try/catch 里，抛错即降级。
 */
object OnlineArtworkClient {

    private const val TAG = "OnlineArtwork"

    private const val QQ_COVER = "https://y.gtimg.cn/music/photo_new/T002R500x500M000"
    private const val ITUNES_SEARCH = "https://itunes.apple.com/search"

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 6_000

    /** 小于这个体积的响应基本都是占位图/错误页，直接丢弃。 */
    private const val MIN_IMAGE_BYTES = 6 * 1024

    /** 单张封面最大接受体积，防止误下大文件。 */
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    private val jsonHeaders = mapOf(
        "Accept" to "application/json",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )

    /** 检索结果：把"确实没有"和"这次没连上"分开，二者的缓存策略不同。 */
    sealed interface LookupResult {
        data class Found(val cover: FetchedCover) : LookupResult
        data object NoMatch : LookupResult
        data class Failed(val reason: String) : LookupResult
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 依次询问各数据源，返回最可信且已通过体积校验的封面字节。
     * 全部失败时返回 null（调用方据此走"无封面"降级），不会抛异常。
     */
    suspend fun fetchCover(meta: AlbumMeta): LookupResult {
        if (!meta.hasUsableText) return LookupResult.NoMatch

        val qq = searchQq(meta)
        val hits = if (qq.hits.isNotEmpty()) qq.hits else searchItunes(meta).hits
        if (hits.isEmpty()) {
            val failed = qq.failed
            if (failed) {
                Log.i(TAG, "search failed for ${meta.album}/${meta.artist}")
                return LookupResult.Failed("search failed")
            }
            Log.i(TAG, "no candidate for ${meta.album}/${meta.artist}")
            return LookupResult.NoMatch
        }

        // 同一数据源内按得分降序，逐个试下载，第一个能用的就用
        for (hit in hits.sortedByDescending { it.score }) {
            val bytes = download(hit.url)
            if (bytes != null) {
                Log.i(TAG, "cover ok: ${hit.source} score=${hit.score} ${hit.albumName}")
                return LookupResult.Found(FetchedCover(bytes, hit))
            }
        }
        Log.i(TAG, "all candidates unreadable for ${meta.album}/${meta.artist}")
        return LookupResult.Failed("download failed")
    }

    data class FetchedCover(val bytes: ByteArray, val hit: CoverHit)

    // ------------------------------------------------------------------ QQ 音乐

    /** 搜索产物：候选列表 + 本次是否发生过网络/解析错误。 */
    private data class SearchOutcome(val hits: List<CoverHit>, val failed: Boolean)

    private suspend fun searchQq(meta: AlbumMeta): SearchOutcome = runCatching {
        val best = meta.keys.firstOrNull()
        val keyword = buildString {
            append(best?.first?.takeIf { it.isNotBlank() } ?: meta.album)
            val artist = best?.second.orEmpty()
            if (artist.isNotBlank()) {
                append(' ')
                append(artist)
            }
        }.trim()
        if (keyword.isBlank()) return@runCatching emptyList()

        QqMusicClient.search(keyword, limit = 10).mapNotNull { song ->
            val albumMid = song.albumMid
            if (albumMid.isBlank()) return@mapNotNull null
            val albumName = song.album.orEmpty()
            val score = meta.scoreAgainst(
                gotAlbum = albumName,
                gotArtist = song.singers,
                gotTitle = song.name,
            )
            if (score < WEAK_THRESHOLD) return@mapNotNull null
            CoverHit(
                url = QQ_COVER + albumMid + ".jpg",
                albumName = albumName,
                artistName = song.singers,
                source = "QQ音乐",
                score = score,
            )
        }.distinctBy { it.url }
    }.fold(
        onSuccess = { SearchOutcome(it, failed = false) },
        onFailure = {
            Log.w(TAG, "qq search failed: ${it.javaClass.simpleName}: ${it.message}")
            SearchOutcome(emptyList(), failed = true)
        },
    )

    // ------------------------------------------------------------------ iTunes

    private suspend fun searchItunes(meta: AlbumMeta): SearchOutcome = runCatching {
        val best = meta.keys.firstOrNull()
        val term = buildString {
            append(best?.first?.takeIf { it.isNotBlank() } ?: meta.album)
            val artist = best?.second.orEmpty()
            if (artist.isNotBlank()) {
                append(' ')
                append(artist)
            }
        }.trim()
        if (term.isBlank()) return@runCatching emptyList()

        val url = buildString {
            append(ITUNES_SEARCH)
            append("?term=").append(URLEncoder.encode(term, "UTF-8"))
            // 按曲目搜而不是按专辑：无标签文件没有专辑名，只能靠歌名匹配
            append("&entity=song&limit=10&country=cn")
        }
        val body = httpGet(url, jsonHeaders)
        parseItunes(body).mapNotNull { hit ->
            val score = meta.scoreAgainst(hit.album, hit.artist, hit.track)
            if (score < WEAK_THRESHOLD) return@mapNotNull null
            CoverHit(
                url = hit.url.replace("100x100bb", "600x600bb"),
                albumName = hit.album,
                artistName = hit.artist,
                source = "iTunes",
                score = score,
            )
        }.distinctBy { it.url }
    }.fold(
        onSuccess = { SearchOutcome(it, failed = false) },
        onFailure = {
            Log.w(TAG, "itunes search failed: ${it.javaClass.simpleName}: ${it.message}")
            SearchOutcome(emptyList(), failed = true)
        },
    )

    /** 一条 iTunes 搜索结果。 */
    internal data class ItunesHit(
        val track: String,
        val album: String,
        val artist: String,
        val url: String,
    )

    /** 解析 iTunes 结果（`entity=song`，所以同时有曲名和专辑名）。 */
    internal fun parseItunes(body: String): List<ItunesHit> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<ItunesHit>(results.length())
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val artwork = item.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: continue
            val track = item.optString("trackName").orEmpty()
            val album = item.optString("collectionName").orEmpty()
            if (track.isBlank() && album.isBlank()) continue
            out += ItunesHit(
                track = track,
                album = album,
                artist = item.optString("artistName").orEmpty(),
                url = artwork,
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 匹配打分

    /** 低于该分数视为"不是同一张专辑"，直接丢候选，宁缺毋滥。 */
    private const val WEAK_THRESHOLD = 60

    /**
     * 匹配打分（0-100），低于 [WEAK_THRESHOLD] 的候选直接丢弃。
     *
     * 两条主判据取**较高分**（不是相加），因为同一张专辑里任何一首歌的封面都是对的，
     * 而"专辑名缺失、只有歌名"（无标签文件）时也得能匹配上：
     *
     * | 判据 | 完全相同 | 互相包含 |
     * | --- | --- | --- |
     * | 歌名 | 85 | 60（两边都 ≥3 字才认） |
     * | 专辑名 | 80 | 55 |
     *
     * 歌手在此基础上加减：
     *
     * - 完全相同 +20；互相包含 +14；完全无关 **-10**（挡掉同名不同歌手的翻唱）；
     * - 本地歌手未知时 +0，不惩罚。
     *
     * 两条主判据都是 0 时，只剩「歌手完全一致」这一条弱依据（70/62），
     * 用于确实没有任何名字可比的情况，但绝不会把歌手不同的结果放进来。
     */
    internal fun scoreMatch(
        wantAlbum: String,
        wantArtist: String,
        gotAlbum: String,
        gotArtist: String,
        wantTitle: String = "",
        gotTitle: String = "",
    ): Int {
        val albumA = normalize(wantAlbum)
        val albumB = normalize(gotAlbum)
        val titleA = normalize(wantTitle)
        val titleB = normalize(gotTitle)
        val artistA = normalize(wantArtist)
        val artistB = normalize(gotArtist)

        val albumScore = when {
            albumA.isEmpty() || albumB.isEmpty() -> 0
            albumA == albumB -> 80
            albumA.contains(albumB) || albumB.contains(albumA) -> 55
            else -> 0
        }
        val titleScore = when {
            titleA.isEmpty() || titleB.isEmpty() -> 0
            titleA == titleB -> 85
            titleA.length >= 3 && titleB.length >= 3 &&
                (titleA.contains(titleB) || titleB.contains(titleA)) -> 60
            else -> 0
        }

        val primary = maxOf(albumScore, titleScore)
        if (primary == 0) {
            // 没有任何名字可比时，退化到"歌手完全一致"
            val noName = albumA.isEmpty() && titleA.isEmpty()
            return when {
                !noName -> 0
                artistA.isEmpty() || artistB.isEmpty() -> 0
                artistA == artistB -> 70
                artistA.contains(artistB) || artistB.contains(artistA) -> 62
                else -> 0
            }
        }

        val artistScore = when {
            artistA.isEmpty() || artistB.isEmpty() -> 0
            artistA == artistB -> 20
            artistA.contains(artistB) || artistB.contains(artistA) -> 14
            else -> -10
        }
        return (primary + artistScore).coerceIn(0, 100)
    }

    /** 用 meta 里所有 (歌名, 歌手) 组合打分，取最高的一个。 */
    private fun AlbumMeta.scoreAgainst(
        gotAlbum: String,
        gotArtist: String,
        gotTitle: String,
    ): Int {
        val variants = keys
        if (variants.isEmpty()) return scoreMatch(album, artist, gotAlbum, gotArtist)
        return variants.maxOf { (title, artist) ->
            scoreMatch(album, artist, gotAlbum, gotArtist, wantTitle = title, gotTitle = gotTitle)
        }
    }

    /**
     * 归一化：小写、全角转半角、去掉括号内补充信息（(Live)/(Remastered) 等）、
     * 去掉所有非字母数字与汉字。
     */
    internal fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val sb = StringBuilder(value.length)
        var depth = 0
        for (ch in value) {
            when (ch) {
                '(', '（', '[', '【' -> depth++
                ')', '）', ']', '】' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return buildString {
            for (ch in sb) {
                val mapped = when {
                    ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    ch == '\u3000' -> ' '
                    else -> ch
                }
                if (mapped.isLetterOrDigit()) append(mapped)
            }
        }.lowercase(Locale.ROOT)
    }

    // ------------------------------------------------------------------ HTTP

    private fun httpGet(url: String, headers: Map<String, String>): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 下载并做最基本的合法性校验：HTTP 200、content-type 是图片（有的 CDN 会缺省）、
     * 体积在 [MIN_IMAGE_BYTES]~[MAX_IMAGE_BYTES] 之间、且 JPEG/PNG 魔数匹配。
     *
     * 这三条能挡掉大部分"找不到就用默认灰图/1px 图/错误页"的情况，
     * 避免把占位图当封面缓存下来。
     */
    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "image/jpeg,image/png,image/*;q=0.8")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.i(TAG, "cover HTTP $code: $url")
                return@withContext null
            }
            val type = connection.contentType.orEmpty()
            if (type.isNotBlank() && !type.startsWith("image/")) {
                Log.i(TAG, "cover not an image ($type): $url")
                return@withContext null
            }
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream(64 * 1024)
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) return@use null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: return@withContext null
            if (!looksLikeImage(bytes)) {
                Log.i(TAG, "cover rejected (${bytes.size}B): $url")
                return@withContext null
            }
            bytes
        } catch (throwable: Exception) {
            Log.w(TAG, "cover download failed: ${throwable.javaClass.simpleName}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** JPEG(FFD8FF) / PNG(89504E47) / WebP(RIFF....WEBP) 魔数 + 最小体积。 */
    internal fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_IMAGE_BYTES) return false
        val jpeg = bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        if (jpeg) return true
        val png = bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        if (png) return true
        val webp = bytes.size > 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
        return webp
    }
}
