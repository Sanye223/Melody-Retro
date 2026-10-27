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

    // ---------------------------------------------------------------- 增强型 LRC

    /** 取自真实 QRC 转出的增强型 LRC：每字一个 `<mm:ss.xxx>`，行首没有 `[...]`。 */
    private val enhancedSample = """
        <00:00.245>周<00:00.382>杰<00:00.473>伦 <00:00.608>-<00:00.745>三<00:00.881>年<00:01.018>二<00:01.155>班
        <00:06.512>训<00:06.695>导<00:06.832>处<00:07.015>报<00:07.243>告
    """.trimIndent()

    @Test
    fun `逐字时间标签不会出现在歌词正文里`() {
        val lyrics = requireNotNull(LyricRepository.parseLrc(enhancedSample))
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        // 空格是原文带的（"伦 " 后面才是 "-"），只剔标签、不重排文字
        assertEquals("周杰伦 -三年二班", lyrics.lines[0].text)
        assertEquals("训导处报告", lyrics.lines[1].text)
        assertFalse(lyrics.lines.any { it.text.contains('<') })
    }

    @Test
    fun `没有行级标签时用第一个逐字标签当行时间`() {
        val lyrics = requireNotNull(LyricRepository.parseLrc(enhancedSample))
        // 235 + 10 = 245ms，6512ms —— 一个字的标签不能各算一行
        assertEquals(245L, lyrics.lines[0].timeMs)
        assertEquals(6_512L, lyrics.lines[1].timeMs)
    }

    @Test
    fun `行级与逐字标签混用时只按行级时间切分`() {
        val lyrics = requireNotNull(
            LyricRepository.parseLrc(
                """
                [00:10.00]<00:10.00>爱<00:10.20>你
                [00:20.00]没<00:20.00>有<00:20.30>标签的行
                """.trimIndent(),
            ),
        )
        assertEquals(2, lyrics.lines.size)
        assertEquals(10_000L, lyrics.lines[0].timeMs)
        assertEquals("爱你", lyrics.lines[0].text)
        assertEquals(20_000L, lyrics.lines[1].timeMs)
        assertEquals("没有标签的行", lyrics.lines[1].text)
    }

    @Test
    fun `逐字标签不冒充元数据`() {
        // 只有逐字标签的文件不该被误判成纯文本，也不该把 <...> 当 [ar:...] 丢掉
        val lyrics = requireNotNull(LyricRepository.parseLrc("<00:01.00>a"))
        assertTrue(lyrics.synced)
        assertEquals("a", lyrics.lines.single().text)
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
