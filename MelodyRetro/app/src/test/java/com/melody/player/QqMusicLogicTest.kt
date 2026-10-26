package com.melody.player

import com.melody.player.data.LyricRepository
import com.melody.player.data.lyrics.LyricsException
import com.melody.player.data.lyrics.QqMusicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QQ 音乐歌词接口的解析与匹配逻辑（不联网，全部用真实返回结构做夹具）。 */
class QqMusicLogicTest {

    /** 真实接口返回结构的精简版（字段名一致）。 */
    private val searchJson = """
        {"code":0,"data":{"keyword":"稻香","song":{"curnum":3,"totalnum":600,"list":[
          {"songmid":"003aAYrm3GE0Ac","songname":"稻香","albumname":"魔杰座","interval":223,
           "singer":[{"name":"周杰伦"}]},
          {"songmid":"003joRpF3wA7e8","songname":"稻香 (Demo)","albumname":"","interval":244,
           "singer":[{"name":"周杰伦"}]},
          {"songmid":"000CTiz73EZBjB","songname":"稻香 (Live)","albumname":"世界巡回演唱会","interval":213,
           "singer":[{"name":"周杰伦"}]}
        ]}}}
    """.trimIndent()

    /** QQ 音乐会把 `:` 转义成 `&#58;`，这里用转义后的真实形态做夹具。 */
    private val lyricJson = """
        {"retcode":0,"code":0,"subcode":0,"lyric":"[ti:稻香]\n[ar:周杰伦]\n[al:魔杰座]\n[offset:0]\n[00&#58;30.94]对这个世界如果你有太多的抱怨\n[00&#58;34.22]跌倒了就不敢继续往前走\n[00&#58;37.31]为什么人要这么的脆弱堕落\n[00&#58;41.30]请你打开电视看看"}
    """.trimIndent()

    private val noLyricJson = """{"retcode":1101,"code":1101,"subcode":1101}"""

    @Test
    fun `解析搜索结果`() {
        val songs = QqMusicClient.parseSearch(searchJson)
        assertEquals(3, songs.size)
        assertEquals("003aAYrm3GE0Ac", songs[0].mid)
        assertEquals("稻香", songs[0].name)
        assertEquals("周杰伦", songs[0].singers)
        assertEquals("魔杰座", songs[0].album)
        assertEquals(223, songs[0].intervalSec)
    }

    @Test
    fun `解析歌词并还原 HTML 转义`() {
        val text = QqMusicClient.parseLyric(lyricJson)
        assertTrue("应返回歌词文本", text != null)
        val lyric = requireNotNull(text)

        assertTrue("时间轴冒号应已还原", lyric.contains("[00:30.94]"))
        assertTrue("不应残留转义字符", !lyric.contains("&#58;"))
        assertTrue("元信息行应保留", lyric.contains("[ti:稻香]"))
    }

    @Test
    fun `歌词可直接解析出时间轴并定位`() {
        val text = requireNotNull(QqMusicClient.parseLyric(lyricJson))
        val lyrics = requireNotNull(LyricRepository.parseLrc(text))

        assertTrue(lyrics.synced)
        assertEquals(4, lyrics.lines.size)
        assertEquals(30_940L, lyrics.lines.first().timeMs)
        assertEquals("对这个世界如果你有太多的抱怨", lyrics.lines.first().text)

        assertEquals(-1, lyrics.indexAt(10_000))
        assertEquals(0, lyrics.indexAt(30_940))
        assertEquals(1, lyrics.indexAt(34_220))
        assertEquals(3, lyrics.indexAt(120_000))
    }

    @Test
    fun `没有歌词时返回 null`() {
        assertNull(QqMusicClient.parseLyric(noLyricJson))
        assertNull(QqMusicClient.parseLyric("""{"retcode":0,"lyric":""}"""))
    }

    @Test
    fun `搜索返回异常码时抛出可读异常`() {
        val error = runCatching { QqMusicClient.parseSearch("""{"code":-1}""") }.exceptionOrNull()
        assertTrue(error is LyricsException)
        assertTrue(error!!.message!!.contains("QQ音乐搜索返回异常"))
    }

    @Test
    fun `候选优先同名且时长最接近`() {
        val songs = QqMusicClient.parseSearch(searchJson)

        // 本地时长 224s，应选中 223s 的正版（而不是 244s 的 Demo）
        val best = QqMusicClient.pickBest(songs, title = "稻香", artist = "周杰伦", durationSec = 224)
        assertEquals("003aAYrm3GE0Ac", best?.mid)

        // 曲名带后缀时退回时长排序
        val live = QqMusicClient.pickBest(songs, title = "稻香 (Live)", artist = "周杰伦", durationSec = 213)
        assertEquals("000CTiz73EZBjB", live?.mid)
    }

    @Test
    fun `HTML 实体还原`() {
        assertEquals("[00:12.34]", QqMusicClient.decodeEntities("[00&#58;12.34]"))
        assertEquals("a&b <c> \"d\"", QqMusicClient.decodeEntities("a&amp;b &lt;c&gt; &quot;d&quot;"))
        assertEquals("普通文本", QqMusicClient.decodeEntities("普通文本"))
    }

    @Test
    fun `空搜索结果返回空列表`() {
        assertTrue(QqMusicClient.parseSearch("""{"code":0,"data":{}}""").isEmpty())
        assertTrue(QqMusicClient.parseSearch("""{"code":0,"data":{"song":{"list":[]}}}""").isEmpty())
        assertNull(QqMusicClient.pickBest(emptyList(), "x", "y", 0))
    }
}
