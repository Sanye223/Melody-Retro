package com.melody.player

import com.melody.player.data.LyricRepository
import com.melody.player.data.lyrics.LrclibClient
import com.melody.player.data.lyrics.LrclibRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌词解析与在线匹配的纯逻辑测试（不需要设备）。 */
class LyricsLogicTest {

    /** 取自 LRCLIB 真实返回的片段（稻香 / 周杰伦）。 */
    private val lrclibSample = """
        [ti:稻香]
        [ar:周杰伦]
        [al:魔杰座]
        [offset:0]
        [00:31.05] 在这个世界如果你有太多的抱怨
        [00:34.03] 跌倒了就不敢继续往前走
        [00:37.06] 为什么人要这么的脆弱堕落
        [00:41.30] 请你打开电视看看
        [00:42.99] 多少人为生命在努力勇敢的走下去
    """.trimIndent()

    @Test
    fun `解析带时间轴的 LRC`() {
        val lyrics = requireNotNull(LyricRepository.parseLrc(lrclibSample))
        assertTrue(lyrics.synced)
        assertEquals(5, lyrics.lines.size)
        assertEquals(31_050L, lyrics.lines.first().timeMs)
        assertEquals("在这个世界如果你有太多的抱怨", lyrics.lines.first().text)
        assertEquals(42_990L, lyrics.lines.last().timeMs)
    }

    @Test
    fun `按播放进度定位当前歌词行`() {
        val lyrics = requireNotNull(LyricRepository.parseLrc(lrclibSample))

        assertEquals(-1, lyrics.indexAt(0))
        assertEquals(0, lyrics.indexAt(31_050))
        assertEquals(0, lyrics.indexAt(34_000))
        assertEquals(1, lyrics.indexAt(34_030))
        assertEquals(4, lyrics.indexAt(300_000))
    }

    @Test
    fun `一行多时间标签与 offset 偏移`() {
        val text = """
            [offset:-500]
            [00:10.00][01:00.00]重复的副歌
            [00:20.5]半点五秒
        """.trimIndent()

        val lyrics = requireNotNull(LyricRepository.parseLrc(text))
        assertEquals(3, lyrics.lines.size)
        // 排序后：[00:10.00] -> 9500，[00:20.5] -> 20000，[01:00.00] -> 59500（均已叠加 offset -500）
        assertEquals(9_500L, lyrics.lines[0].timeMs)
        assertEquals(20_000L, lyrics.lines[1].timeMs)
        assertEquals(59_500L, lyrics.lines[2].timeMs)
        assertEquals("重复的副歌", lyrics.lines[0].text)
        assertEquals("半点五秒", lyrics.lines[1].text)
    }

    @Test
    fun `纯文本歌词不滚动`() {
        val lyrics = requireNotNull(LyricRepository.parseLrc("第一行\n第二行\n第三行"))
        assertFalse(lyrics.synced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(-1, lyrics.indexAt(10_000))
    }

    @Test
    fun `空内容返回空`() {
        assertEquals(null, LyricRepository.parseLrc(""))
        assertEquals(null, LyricRepository.parseLrc("   \n  "))
        assertEquals(null, LyricRepository.parseLrc(null))
    }

    @Test
    fun `在线候选优先同步歌词且时长最接近`() {
        val candidates = listOf(
            record(id = 1, track = "稻香", artist = "周杰伦", duration = 100, synced = null, plain = "纯文本"),
            record(id = 2, track = "稻香", artist = "周杰伦", duration = 250, synced = "[00:01.00]远处版本"),
            record(id = 3, track = "稻香", artist = "周杰伦", duration = 223, synced = "[00:31.05]正确版本"),
            record(id = 4, track = "稻香 (伴奏)", artist = "周杰伦", duration = 223, synced = "[00:01.00]伴奏"),
        )

        val best = LrclibClient.pickBest(candidates, title = "稻香", artist = "周杰伦", durationSec = 224)
        assertEquals(3L, best?.id)
    }

    @Test
    fun `过滤纯音乐并回退到宽松匹配`() {
        val candidates = listOf(
            record(id = 9, track = "Yellow", artist = "Coldplay", duration = 200, synced = null, plain = null, instrumental = true),
            record(id = 10, track = "Yellow (Live)", artist = "Coldplay", duration = 266, synced = "[00:05.00]live"),
        )

        val best = LrclibClient.pickBest(candidates, title = "Yellow", artist = "Coldplay", durationSec = 266)
        assertEquals(10L, best?.id)
    }

    @Test
    fun `没有可用候选时返回 null`() {
        val candidates = listOf(
            record(id = 20, track = "未知", artist = "未知", duration = 0, synced = null, plain = null),
        )
        assertEquals(null, LrclibClient.pickBest(candidates, "某歌", "某人", 100))
        assertEquals(null, LrclibClient.pickBest(emptyList(), "某歌", "某人", 100))
    }

    private fun record(
        id: Long,
        track: String,
        artist: String,
        duration: Int,
        synced: String?,
        plain: String? = null,
        instrumental: Boolean = false,
    ) = LrclibRecord(
        id = id,
        trackName = track,
        artistName = artist,
        albumName = null,
        durationSec = duration,
        instrumental = instrumental,
        plainLyrics = plain,
        syncedLyrics = synced,
    )
}
