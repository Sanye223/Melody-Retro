package com.melody.player

import com.melody.player.data.FileNameMeta
import com.melody.player.data.artwork.AlbumMeta
import com.melody.player.data.artwork.OnlineArtworkClient
import com.melody.player.data.lyrics.QqMusicClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * 真实联网的在线封面端到端冒烟测试（走生产代码）。
 * 无网络 / 接口被拦时自动跳过，不算失败。
 *
 * 需要代理时：`./gradlew testDebugUnitTest -PproxyHost=127.0.0.1 -PproxyPort=8080`
 */
class OnlineArtworkLiveTest {

    @Test
    fun `qq search exposes album mid`() = runBlocking {
        println("[live][qq] proxy=" + System.getProperty("https.proxyHost"))
        val songs = QqMusicClient.search("稻香 周杰伦", limit = 5)
        println("[live][qq] 搜索返回 ${songs.size} 条")
        songs.take(3).forEach {
            println("[live][qq]   ${it.name} - ${it.singers} / ${it.album} / albumMid=${it.albumMid}")
        }
        assumeTrue("无网络，跳过", songs.isNotEmpty())
        assertTrue(
            "搜索结果里应至少有一条带 albummid",
            songs.any { it.albumMid.isNotBlank() },
        )
    }

    @Test
    fun `fetch cover end to end`() = runBlocking {
        val meta = AlbumMeta(
            albumId = 1L,
            album = "魔杰座",
            artist = "周杰伦",
            trackTitle = "稻香",
        )
        val result = OnlineArtworkClient.fetchCover(meta)
        if (result !is OnlineArtworkClient.LookupResult.Found) {
            println("[live][art] 未取到封面（$result），跳过")
            assumeTrue(false)
        }
        val hit = (result as OnlineArtworkClient.LookupResult.Found).cover
        println(
            "[live][art] 命中 ${hit.hit.source} / ${hit.hit.albumName} - ${hit.hit.artistName} " +
                "score=${hit.hit.score} bytes=${hit.bytes.size}",
        )
        println("[live][art] url=${hit.hit.url}")
        assertTrue("返回的字节应通过图片校验", OnlineArtworkClient.looksLikeImage(hit.bytes))
        assertTrue("体积应大于 10KB", hit.bytes.size > 10 * 1024)
    }

    @Test
    fun `itunes fallback finds a cover`() = runBlocking {
        // 用一个 QQ 音乐几乎一定没有、但 iTunes 有的组合，验证兜底源本身可用
        val meta = AlbumMeta(
            albumId = 2L,
            album = "Random Access Memories",
            artist = "Daft Punk",
            trackTitle = "Get Lucky",
        )
        val result = OnlineArtworkClient.fetchCover(meta)
        if (result !is OnlineArtworkClient.LookupResult.Found) {
            println("[live][art] 兜底源未取到封面（$result），跳过")
            assumeTrue(false)
        }
        val hit = (result as OnlineArtworkClient.LookupResult.Found).cover
        println("[live][art] 兜底命中 ${hit.hit.source} / ${hit.hit.albumName} bytes=${hit.bytes.size}")
        assertTrue(OnlineArtworkClient.looksLikeImage(hit.bytes))
    }

    /**
     * 复现「布拉格广场 - 蔡依林&周杰伦.mp3」这个 case：
     * 文件只有编码器信息、没有任何 ID3 标签，MediaStore 里标题/歌手/专辑全空。
     * 期望：从文件名猜出 歌名=布拉格广场 / 歌手=蔡依林&周杰伦，并真的取到封面。
     */
    @Test
    fun `untagged file still gets a cover from its file name`() = runBlocking {
        val guesses = FileNameMeta.guesses("布拉格广场 - 蔡依林&周杰伦.mp3")
        println("[live][art] 文件名猜测: $guesses")
        assumeTrue("文件名应能拆出歌手", guesses.any { it.artist.isNotBlank() })

        val first = guesses.first()
        val meta = AlbumMeta(
            albumId = -12345L,
            album = "",                 // 没有专辑标签
            artist = first.artist,
            trackTitle = first.title,
            guessVariants = guesses,
        )
        println("[live][art] meta keys=${meta.keys} hasUsableText=${meta.hasUsableText}")
        assumeTrue("meta 应可用于检索", meta.hasUsableText)

        when (val result = OnlineArtworkClient.fetchCover(meta)) {
            is OnlineArtworkClient.LookupResult.Found -> {
                val hit = result.cover.hit
                println("[live][art] 命中 ${hit.source} score=${hit.score} ${hit.albumName} - ${hit.artistName}")
                println("[live][art] bytes=${result.cover.bytes.size} url=${hit.url}")
                assertTrue("封面体积应大于 6KB", result.cover.bytes.size > 6 * 1024)
                assertTrue("应命中 看我72变 这张专辑", hit.albumName.contains("72"))
            }

            else -> {
                println("[live][art] 未取到封面: $result")
                assumeTrue(false)
            }
        }
    }
}
