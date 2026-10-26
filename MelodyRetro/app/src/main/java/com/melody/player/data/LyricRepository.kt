package com.melody.player.data

import android.content.Context
import com.melody.player.data.lyrics.LrclibClient
import com.melody.player.data.lyrics.LyricsException
import com.melody.player.data.lyrics.QqMusicClient
import com.melody.player.data.model.LyricLine
import com.melody.player.data.model.Lyrics
import com.melody.player.data.model.LyricsQuery
import com.melody.player.data.model.LyricsResult
import com.melody.player.data.model.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * 歌词加载，按以下顺序尝试：
 *
 * 1. 与音频同目录、同名的 `.lrc` / `.txt`（本地歌词，最快且离线可用）；
 * 2. **QQ 音乐**（主源，境内网络稳定）；
 * 3. LRCLIB（备用源，主要补 QQ 音乐没有的海外曲目）；
 * 4. 都没有 → 返回 [LyricsSource.NONE] 并带上可读的失败原因。
 *
 * 在线结果会写入 `cacheDir/lyrics/`，同一首歌后续播放不再请求网络。
 */
object LyricRepository {

    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("^\\[[a-zA-Z#]+:.*]$")

    private val memoryCache = object : LinkedHashMap<String, LyricsResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>?): Boolean =
            size > 24
    }

    suspend fun load(
        context: Context,
        query: LyricsQuery,
        onlineEnabled: Boolean,
        forceRefresh: Boolean = false,
    ): LyricsResult = withContext(Dispatchers.IO) {
        val memoryKey = "${cacheKey(query)}|$onlineEnabled"
        if (!forceRefresh) {
            synchronized(memoryCache) { memoryCache[memoryKey] }?.let {
                if (!it.isEmpty) return@withContext it
            }
        }

        // 1. 本地同名歌词文件
        val local = runCatching { readLocalFile(query.path) }.getOrNull()
        if (local != null) {
            return@withContext LyricsResult(local, LyricsSource.LOCAL_FILE).also { saveMemory(memoryKey, it) }
        }

        if (!onlineEnabled) {
            return@withContext LyricsResult(null, LyricsSource.NONE, message = "在线歌词已在设置中关闭")
        }

        val key = cacheKey(query)
        val diskFile = cacheFile(context, key)

        // 2. 磁盘缓存
        if (!forceRefresh) {
            val cachedText = runCatching { diskFile.takeIf { it.exists() }?.readText() }.getOrNull()
            if (!cachedText.isNullOrBlank()) {
                parseLrc(cachedText)?.let { lyrics ->
                    val meta = runCatching {
                        metaFile(context, key).takeIf { it.exists() }?.readText()
                    }.getOrNull()?.split('|', limit = 2)
                    val result = LyricsResult(
                        lyrics = lyrics,
                        source = LyricsSource.ONLINE,
                        matched = meta?.getOrNull(1)?.takeIf { it.isNotBlank() },
                        provider = meta?.getOrNull(0)?.takeIf { it.isNotBlank() },
                    )
                    saveMemory(memoryKey, result)
                    return@withContext result
                }
            }
        }

        // 3. 在线：QQ 音乐 → LRCLIB
        val qqAttempt = tryQq(query)
        if (qqAttempt is Attempt.Found) {
            return@withContext persist(context, key, qqAttempt).also { saveMemory(memoryKey, it) }
        }
        // 网络整体不可用时不再尝试第二个源，避免让用户多等一个超时
        if (qqAttempt is Attempt.Failed && qqAttempt.isNetwork) {
            return@withContext LyricsResult(null, LyricsSource.NONE, message = qqAttempt.message)
        }

        val lrclibAttempt = tryLrclib(query)
        if (lrclibAttempt is Attempt.Found) {
            return@withContext persist(context, key, lrclibAttempt).also { saveMemory(memoryKey, it) }
        }

        mergeFailure(qqAttempt, lrclibAttempt)
    }

    fun clearCache(context: Context) {
        synchronized(memoryCache) { memoryCache.clear() }
        runCatching { File(context.cacheDir, "lyrics").deleteRecursively() }
    }

    // ------------------------------------------------------------------ 各数据源

    private sealed interface Attempt {
        data class Found(val text: String, val provider: String, val matched: String?) : Attempt
        data class NotFound(val message: String) : Attempt
        data class Failed(val message: String, val isNetwork: Boolean = false) : Attempt
    }

    private suspend fun tryQq(query: LyricsQuery): Attempt {
        val keyword = buildString {
            append(query.title)
            if (query.artist.isNotBlank() && !query.artist.contains("未知")) {
                append(' ')
                append(query.artist)
            }
        }
        return try {
            val songs = QqMusicClient.search(keyword)
            if (songs.isEmpty()) {
                return Attempt.NotFound("QQ音乐没有搜到「${query.title}」")
            }
            val best = QqMusicClient.pickBest(
                songs = songs,
                title = query.title,
                artist = query.artist,
                durationSec = (query.durationMs / 1000L).toInt(),
            ) ?: return Attempt.NotFound("QQ音乐没有可用的搜索结果")

            val lyricText = QqMusicClient.fetchLyric(best.mid)
                ?: return Attempt.NotFound("QQ音乐里这首歌暂无歌词")

            Attempt.Found(
                text = lyricText,
                provider = QqMusicClient.label,
                matched = buildString {
                    append(best.name)
                    if (best.singers.isNotBlank()) append(" - ").append(best.singers)
                },
            )
        } catch (exception: LyricsException) {
            Attempt.Failed(exception.message ?: "QQ音乐请求失败", exception.isNetwork)
        } catch (throwable: Exception) {
            Attempt.Failed("QQ音乐请求失败：${throwable.javaClass.simpleName}")
        }
    }

    private suspend fun tryLrclib(query: LyricsQuery): Attempt = try {
        val durationSec = (query.durationMs / 1000L).toInt()
        val records = LrclibClient.search(
            title = query.title,
            artist = query.artist,
            album = query.album,
            durationSec = durationSec,
        )
        val best = LrclibClient.pickBest(
            records = records,
            title = query.title,
            artist = query.artist,
            durationSec = durationSec,
        ) ?: return Attempt.NotFound("LRCLIB 没有找到匹配歌词")

        val text = best.bestText ?: return Attempt.NotFound("LRCLIB 歌词内容为空")
        Attempt.Found(
            text = text,
            provider = LrclibClient.label,
            matched = "${best.trackName} - ${best.artistName}",
        )
    } catch (exception: LyricsException) {
        Attempt.Failed(exception.message ?: "LRCLIB 请求失败", exception.isNetwork)
    } catch (throwable: Exception) {
        Attempt.Failed("LRCLIB 请求失败：${throwable.javaClass.simpleName}")
    }

    private fun mergeFailure(qq: Attempt, lrclib: Attempt): LyricsResult {
        val networkFailed = (qq as? Attempt.Failed)?.isNetwork == true ||
            (lrclib as? Attempt.Failed)?.isNetwork == true
        if (networkFailed) {
            return LyricsResult(null, LyricsSource.NONE, message = "网络请求失败，请检查网络后重试")
        }
        val detail = listOfNotNull(
            (qq as? Attempt.NotFound)?.message,
            (qq as? Attempt.Failed)?.message,
        ).firstOrNull()
        return LyricsResult(
            lyrics = null,
            source = LyricsSource.NONE,
            message = detail ?: "没有找到这首歌的歌词",
        )
    }

    // ------------------------------------------------------------------ 缓存

    private fun persist(context: Context, key: String, found: Attempt.Found): LyricsResult {
        val lyrics = parseLrc(found.text)
            ?: return LyricsResult(null, LyricsSource.NONE, message = "歌词内容解析失败")

        runCatching {
            val file = cacheFile(context, key)
            file.parentFile?.mkdirs()
            file.writeText(found.text)
            metaFile(context, key).writeText("${found.provider}|${found.matched.orEmpty()}")
        }

        return LyricsResult(
            lyrics = lyrics,
            source = LyricsSource.ONLINE,
            matched = found.matched,
            provider = found.provider,
        )
    }

    private fun saveMemory(key: String, result: LyricsResult) {
        if (!result.isEmpty) synchronized(memoryCache) { memoryCache[key] = result }
    }

    private fun cacheFile(context: Context, key: String): File =
        File(File(context.cacheDir, "lyrics"), "$key.lrc")

    private fun metaFile(context: Context, key: String): File =
        File(File(context.cacheDir, "lyrics"), "$key.source")

    private fun cacheKey(query: LyricsQuery): String {
        val raw = "${query.artist.lowercase()}|${query.title.lowercase()}|${query.album.orEmpty().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    // ------------------------------------------------------------------ 本地歌词

    private fun readLocalFile(audioPath: String?): Lyrics? {
        if (audioPath.isNullOrBlank()) return null
        val audio = File(audioPath)
        val dir = audio.parentFile ?: return null
        val base = audio.nameWithoutExtension
        val candidate = listOf("$base.lrc", "$base.LRC", "$base.txt", "$base.Lrc")
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.isFile && it.canRead() }
            ?: return null

        return parseLrc(decode(candidate.readBytes()))
    }

    /** 优先按 UTF-8 严格解码，失败后回退 GB18030（覆盖大部分中文歌词文件）。 */
    private fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        val text = utf8 ?: runCatching {
            String(bytes, Charset.forName("GB18030"))
        }.getOrElse { String(bytes, Charsets.UTF_8) }
        return text.removePrefix("\uFEFF")
    }

    // ------------------------------------------------------------------ LRC 解析

    fun parseLrc(text: String?): Lyrics? {
        if (text.isNullOrBlank()) return null
        val offset = offsetTag.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

        val timed = ArrayList<LyricLine>(128)
        val plain = ArrayList<String>(64)

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val matches = timeTag.findAll(line).toList()
            if (matches.isEmpty()) {
                if (metaTag.matches(line)) continue
                plain += line
                continue
            }

            val content = line.replace(timeTag, "").trim()
            if (content.isEmpty()) continue
            for (match in matches) {
                val minute = match.groupValues[1].toLongOrNull() ?: 0L
                val second = match.groupValues[2].toLongOrNull() ?: 0L
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                timed += LyricLine(
                    timeMs = minute * 60_000 + second * 1_000 + millis + offset,
                    text = content,
                )
            }
        }

        if (timed.isNotEmpty()) {
            timed.sortBy { it.timeMs }
            return Lyrics(lines = timed, synced = true)
        }
        if (plain.isNotEmpty()) {
            return Lyrics(
                lines = plain.mapIndexed { index, line -> LyricLine(index.toLong(), line) },
                synced = false,
            )
        }
        return null
    }
}
