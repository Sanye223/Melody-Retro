package com.melody.player

import com.melody.player.data.FileNameMeta
import com.melody.player.data.artwork.AlbumMeta
import com.melody.player.data.artwork.OnlineArtworkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 在线封面匹配/解析逻辑的纯 JVM 测试，不联网。 */
class OnlineArtworkLogicTest {

    // ------------------------------------------------------------------ 归一化

    @Test
    fun `normalize strips brackets and punctuation`() {
        assertEquals("魔杰座", OnlineArtworkClient.normalize("魔杰座 (Deluxe Edition)"))
        assertEquals("稻香", OnlineArtworkClient.normalize("《稻香》"))
        assertEquals("album", OnlineArtworkClient.normalize("Album【Remastered 2011】"))
        assertEquals("jaychou", OnlineArtworkClient.normalize("Jay Chou"))
        assertEquals("", OnlineArtworkClient.normalize("   "))
    }

    @Test
    fun `normalize converts full width characters`() {
        assertEquals("123abc", OnlineArtworkClient.normalize("１２３ＡＢＣ"))
    }

    // ------------------------------------------------------------------ 打分

    @Test
    fun `exact album and artist scores full`() {
        assertEquals(
            100,
            OnlineArtworkClient.scoreMatch("魔杰座", "周杰伦", "魔杰座", "周杰伦"),
        )
    }

    @Test
    fun `same album different artist is penalised`() {
        // 80 - 10 = 70，仍高于阈值，但低于"歌手也对得上"的候选
        val wrongArtist = OnlineArtworkClient.scoreMatch("魔杰座", "周杰伦", "魔杰座", "某某翻唱")
        val rightArtist = OnlineArtworkClient.scoreMatch("魔杰座", "周杰伦", "魔杰座", "周杰伦")
        assertTrue(wrongArtist < rightArtist)
        assertEquals(70, wrongArtist)
    }

    @Test
    fun `album name containment still passes threshold`() {
        val score = OnlineArtworkClient.scoreMatch(
            "周杰伦地表最强世界巡回演唱会",
            "周杰伦",
            "地表最强世界巡回演唱会",
            "周杰伦",
        )
        assertTrue("包含关系应能通过 60 分阈值，实际 $score", score >= 60)
    }

    @Test
    fun `unrelated album and artist is rejected`() {
        val score = OnlineArtworkClient.scoreMatch("魔杰座", "周杰伦", "范特西", "陈奕迅")
        assertTrue("完全无关应被筛掉，实际 $score", score < 60)
    }

    @Test
    fun `missing local album falls back to artist only`() {
        assertEquals(70, OnlineArtworkClient.scoreMatch("", "周杰伦", "魔杰座", "周杰伦"))
        assertEquals(0, OnlineArtworkClient.scoreMatch("", "周杰伦", "魔杰座", "陈奕迅"))
        assertEquals(0, OnlineArtworkClient.scoreMatch("", "", "魔杰座", "周杰伦"))
    }

    @Test
    fun `untagged file matches by song title`() {
        // 布拉格广场 - 蔡依林&周杰伦.mp3（无 ID3）：专辑为空，只能靠歌名 + 歌手
        val score = OnlineArtworkClient.scoreMatch(
            wantAlbum = "",
            wantArtist = "蔡依林&周杰伦",
            gotAlbum = "看我72变",
            gotArtist = "蔡依林/周杰伦",
            wantTitle = "布拉格广场",
            gotTitle = "布拉格广场",
        )
        assertTrue("应能命中，实际 $score", score >= 60)
        assertEquals(100, score)
    }

    @Test
    fun `same title different artist is still penalised`() {
        val right = OnlineArtworkClient.scoreMatch("", "蔡依林&周杰伦", "看我72变", "蔡依林/周杰伦", "布拉格广场", "布拉格广场")
        val wrong = OnlineArtworkClient.scoreMatch("", "蔡依林&周杰伦", "某专辑", "某某翻唱", "布拉格广场", "布拉格广场")
        assertTrue(wrong < right)
        assertEquals(75, wrong)
    }

    @Test
    fun `title match works even when album names differ`() {
        // 同一首歌在不同精选集里，专辑名对不上也要能取到封面
        val score = OnlineArtworkClient.scoreMatch(
            wantAlbum = "周杰伦精选",
            wantArtist = "周杰伦",
            gotAlbum = "魔杰座",
            gotArtist = "周杰伦",
            wantTitle = "稻香",
            gotTitle = "稻香",
        )
        assertEquals(100, score)
    }

    @Test
    fun `version suffix in song name is ignored`() {
        val score = OnlineArtworkClient.scoreMatch("", "", "看我72变", "蔡依林", "布拉格广场", "布拉格广场 (Live)")
        assertEquals(85, score)
    }

    // ------------------------------------------------------------------ 文件名兜底

    @Test
    fun `file name with title first`() {
        val guess = FileNameMeta.guesses("布拉格广场 - 蔡依林&周杰伦.mp3")
        assertEquals(1, guess.size)
        assertEquals("布拉格广场", guess[0].title)
        assertEquals("蔡依林&周杰伦", guess[0].artist)
    }

    @Test
    fun `file name with artist first`() {
        // 「周杰伦 - 稻香」没有 &/、这类特征，两种解释都留着，由打分挑
        val guess = FileNameMeta.guesses("周杰伦 - 稻香.mp3")
        assertEquals(2, guess.size)
        assertTrue(guess.any { it.title == "稻香" && it.artist == "周杰伦" })
        assertTrue(guess.any { it.title == "周杰伦" && it.artist == "稻香" })
    }

    @Test
    fun `file name strips track number and quality tags`() {
        assertEquals("稻香", FileNameMeta.guesses("01. 稻香.mp3")[0].title)
        assertEquals("稻香", FileNameMeta.guesses("稻香 (Live) [320k].mp3")[0].title)
        assertEquals("稻香", FileNameMeta.guesses("/sdcard/Music/稻香.mp3")[0].title)
    }

    @Test
    fun `file name with feat`() {
        val guess = FileNameMeta.guesses("Stay feat. Justin Bieber.mp3")
        assertEquals("Stay", guess[0].title)
        assertEquals("Justin Bieber", guess[0].artist)
    }

    @Test
    fun `file name without separator is title only`() {
        val guess = FileNameMeta.guesses("稻香.mp3")
        assertEquals("稻香", guess[0].title)
        assertEquals("", guess[0].artist)
    }

    // ------------------------------------------------------------------ iTunes 解析

    @Test
    fun `parse itunes results`() {
        val body = """
            {
              "resultCount": 2,
              "results": [
                {
                  "collectionName": "魔杰座",
                  "artistName": "周杰伦",
                  "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/a.jpg/100x100bb.jpg"
                },
                {
                  "collectionName": "跨时代",
                  "artistName": "周杰伦",
                  "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/b.jpg/100x100bb.jpg"
                },
                { "artistName": "没有封面的条目" }
              ]
            }
        """.trimIndent()

        val parsed = OnlineArtworkClient.parseItunes(body)
        assertEquals(2, parsed.size)
        assertEquals("魔杰座", parsed[0].album)
        assertEquals("周杰伦", parsed[0].artist)
        assertTrue(parsed[0].url.contains("100x100bb"))
    }

    @Test
    fun `parse itunes tolerates broken json`() {
        assertTrue(OnlineArtworkClient.parseItunes("<html>403</html>").isEmpty())
        assertTrue(OnlineArtworkClient.parseItunes("{}").isEmpty())
    }

    // ------------------------------------------------------------------ 图片校验

    @Test
    fun `rejects tiny payloads and non images`() {
        // 太小（占位图 / 错误页）
        assertFalse(OnlineArtworkClient.looksLikeImage(ByteArray(1024)))
        // 体积够了但不是图片
        assertFalse(OnlineArtworkClient.looksLikeImage(ByteArray(8 * 1024)))
    }

    @Test
    fun `accepts jpeg and png magic`() {
        val jpeg = ByteArray(8 * 1024).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xD8.toByte()
            this[2] = 0xFF.toByte()
        }
        assertTrue(OnlineArtworkClient.looksLikeImage(jpeg))

        val png = ByteArray(8 * 1024).apply {
            this[0] = 0x89.toByte()
            this[1] = 0x50.toByte()
            this[2] = 0x4E.toByte()
            this[3] = 0x47.toByte()
        }
        assertTrue(OnlineArtworkClient.looksLikeImage(png))
    }

    // ------------------------------------------------------------------ 元信息

    @Test
    fun `album meta usability`() {
        assertTrue(AlbumMeta(1, "魔杰座", "周杰伦", "稻香").hasUsableText)
        assertTrue(AlbumMeta(1, "", "周杰伦", "稻香").hasUsableText)
        // 只有歌名也值得一搜（无标签文件就靠它）
        assertTrue(AlbumMeta(1, "", "", "稻香").hasUsableText)
        assertFalse(AlbumMeta(1, "", "", "").hasUsableText)
    }

    @Test
    fun `album meta merges tag and file name variants`() {
        val meta = AlbumMeta(
            albumId = -9,
            album = "",
            artist = "蔡依林&周杰伦",
            trackTitle = "布拉格广场",
            guessVariants = listOf(
                com.melody.player.data.GuessedMeta("布拉格广场", "蔡依林&周杰伦"),
            ),
        )
        assertTrue(meta.keys.contains("布拉格广场" to "蔡依林&周杰伦"))
    }
}
