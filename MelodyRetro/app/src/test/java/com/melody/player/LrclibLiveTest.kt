package com.melody.player

import com.melody.player.data.LyricRepository
import com.melody.player.data.lyrics.LrclibClient
import com.melody.player.data.lyrics.QqMusicClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实联网的端到端测试：走的是生产代码（QQ 音乐 / LRCLIB 客户端 + LRC 解析），
 * 只是把运行环境从 Android 换成了 JVM。
 *
 * 无网络时自动跳过（assumeTrue），不会造成 CI 失败。
 * 若本机需要代理：`./gradlew testDebugUnitTest -PproxyHost=127.0.0.1 -PproxyPort=8080`
 */
class LrclibLiveTest {

    private val title = "稻香"
    private val artist = "周杰伦"
    private val durationSec = 223

    @Test
    fun `从QQ音乐搜索并解析出同步歌词`() = runBlocking {
        val songs = runCatching { QqMusicClient.search("$title $artist") }.getOrElse { emptyList() }
        assumeTrue("QQ音乐接口不可达，跳过联网测试", songs.isNotEmpty())

        println("[live][QQ] 搜索返回 ${songs.size} 条候选")
        songs.take(3).forEach {
            println("[live][QQ]   ${it.name} - ${it.singers} / ${it.album} / ${it.intervalSec}s")
        }

        val best = QqMusicClient.pickBest(songs, title, artist, durationSec)
        assertTrue("应当匹配到一首歌", best != null)
        val song = requireNotNull(best)
        println("[live][QQ] 选中: mid=${song.mid} ${song.name} - ${song.singers}")

        val text = requireNotNull(QqMusicClient.fetchLyric(song.mid)) { "QQ音乐应返回歌词文本" }
        val lyrics = requireNotNull(LyricRepository.parseLrc(text)) { "歌词应能解析出时间轴" }
        println("[live][QQ] 解析出 ${lyrics.lines.size} 行，首行 ${lyrics.lines.first().timeMs}ms: ${lyrics.lines.first().text}")

        assertTrue("应为带时间轴的歌词", lyrics.synced)
        assertTrue("歌词行数应大于 20", lyrics.lines.size > 20)
        assertTrue("歌词内容应包含真实歌词", lyrics.lines.any { it.text.contains("抱怨") })

        val at35s = lyrics.indexAt(35_000)
        assertTrue("35s 应能定位到歌词行", at35s >= 0)
        println("[live][QQ] 35s 定位到第 ${at35s + 1} 行: ${lyrics.lines[at35s].text}")
    }

    @Test
    fun `从LRCLIB兜底获取同步歌词`() = runBlocking {
        val records = runCatching { LrclibClient.search(title, artist, null, durationSec) }
            .getOrElse { emptyList() }
        assumeTrue("LRCLIB 不可达，跳过联网测试", records.isNotEmpty())

        val best = LrclibClient.pickBest(records, title, artist, durationSec)
        assertTrue("应当匹配到一条歌词", best != null)
        val lyrics = requireNotNull(LyricRepository.parseLrc(requireNotNull(best).syncedLyrics))

        println("[live][LRCLIB] 选中 id=${best.id} ${best.trackName} - ${best.artistName} (${best.durationSec}s)")
        println("[live][LRCLIB] 解析出 ${lyrics.lines.size} 行")
        assertTrue(lyrics.synced)
        assertTrue(lyrics.lines.size > 20)
    }
}
