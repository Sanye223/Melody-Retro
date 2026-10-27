package com.melody.player

import com.melody.player.data.EmbeddedLyrics
import com.melody.player.data.LyricRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 内嵌歌词标签的解析测试（纯字节，不需要设备）。
 *
 * 用例里的文件都是按真实容器格式拼出来的最小样本：
 * FLAC 走 metadata block（VORBIS_COMMENT），MP3 走 ID3v2.3 的 USLT 帧。
 */
class EmbeddedLyricsTest {

    private val timedLrc = "[ti:倒带]\n[ar:蔡依林]\n[00:16.40]我受够了等待你所谓的安排\n[00:21.12]说的未来到底多久才来"

    // ------------------------------------------------------------------ FLAC

    @Test
    fun `FLAC 的 LYRICS 标签能取出来`() {
        val bytes = flac(listOf("TITLE=倒带", "LYRICS=$timedLrc"))
        assertEquals(timedLrc, EmbeddedLyrics.parse(bytes))
    }

    @Test
    fun `FLAC 只有 UNSYNCEDLYRICS 时作为兜底`() {
        val bytes = flac(listOf("UNSYNCEDLYRICS=我受够了等待\n说的未来到底多久才来"))
        assertEquals("我受够了等待\n说的未来到底多久才来", EmbeddedLyrics.parse(bytes))
    }

    @Test
    fun `FLAC 同时有两种标签时优先带时间轴的`() {
        val bytes = flac(
            listOf(
                "UNSYNCEDLYRICS=纯文本版本",
                "LYRICS=$timedLrc",
            ),
        )
        assertEquals(timedLrc, EmbeddedLyrics.parse(bytes))
    }

    @Test
    fun `FLAC 没有歌词标签返回 null`() {
        assertNull(EmbeddedLyrics.parse(flac(listOf("TITLE=倒带", "ARTIST=蔡依林"))))
    }

    @Test
    fun `FLAC 空歌词视为没有`() {
        assertNull(EmbeddedLyrics.parse(flac(listOf("LYRICS=   "))))
    }

    @Test
    fun `键名大小写不敏感`() {
        val bytes = flac(listOf("lyrics=$timedLrc"))
        assertEquals(timedLrc, EmbeddedLyrics.parse(bytes))
    }

    // ------------------------------------------------------------------ ID3

    @Test
    fun `ID3v2_3 的 USLT 帧 UTF-8 能取出来`() {
        val bytes = id3v23Uslt(timedLrc, encoding = 3)
        assertEquals(timedLrc, EmbeddedLyrics.parse(bytes))
    }

    @Test
    fun `ID3v2_3 的 USLT 帧 UTF-16 能取出来`() {
        val bytes = id3v23Uslt(timedLrc, encoding = 1)
        assertEquals(timedLrc, EmbeddedLyrics.parse(bytes))
    }

    @Test
    fun `ID3 没有 USLT 帧返回 null`() {
        val bytes = id3Frame("TIT2", byteArrayOf(3) + "倒带".toByteArray(Charsets.UTF_8))
        assertNull(EmbeddedLyrics.parse(bytes))
    }

    // ------------------------------------------------------------------ 健壮性

    @Test
    fun `不认识的文件头返回 null`() {
        assertNull(EmbeddedLyrics.parse("OggS....".toByteArray()))
        assertNull(EmbeddedLyrics.parse(ByteArray(0)))
    }

    @Test
    fun `截断的数据不会抛异常`() {
        val full = flac(listOf("LYRICS=$timedLrc"))
        (1 until full.size step 7).forEach { size ->
            EmbeddedLyrics.parse(full.copyOf(size))
        }
        // 到这里没抛异常即可；完整输入仍需正常解析
        assertEquals(timedLrc, EmbeddedLyrics.parse(full))
    }

    // ------------------------------------------------------------------ 接到解析链上

    @Test
    fun `内嵌歌词直接喂给 parseLrc 就是可滚动的歌词`() {
        val text = EmbeddedLyrics.parse(flac(listOf("LYRICS=$timedLrc")))
        val lyrics = requireNotNull(LyricRepository.parseLrc(text))
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(16_400L, lyrics.lines[0].timeMs)
        assertEquals("我受够了等待你所谓的安排", lyrics.lines[0].text)
    }

    @Test
    fun `内嵌的纯文本歌词解析后没有时间轴`() {
        val text = EmbeddedLyrics.parse(flac(listOf("UNSYNCEDLYRICS=第一行\n第二行")))
        val lyrics = requireNotNull(LyricRepository.parseLrc(text))
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
    }

    // ------------------------------------------------------------------ 样本构造

    private fun flac(comments: List<String>): ByteArray {
        val vendor = "melody-test".toByteArray(Charsets.UTF_8)
        val commentBody = ByteArrayOutputStream().apply {
            write(u32le(vendor.size))
            write(vendor)
            write(u32le(comments.size))
            comments.forEach { entry ->
                val bytes = entry.toByteArray(Charsets.UTF_8)
                write(u32le(bytes.size))
                write(bytes)
            }
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            // STREAMINFO（34 字节，不是最后一块）
            write(0x00)
            write(u24(34))
            write(ByteArray(34))
            // VORBIS_COMMENT（最后一块：0x80 | 4）
            write(0x84)
            write(u24(commentBody.size))
            write(commentBody)
        }.toByteArray()
    }

    private fun id3v23Uslt(text: String, encoding: Int): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(encoding)
            write("chi".toByteArray(Charsets.ISO_8859_1))
            if (encoding == 0 || encoding == 3) {
                write(0) // 空的内容描述符，单字节终止
            } else {
                write(byteArrayOf(0, 0))
            }
            val body = when (encoding) {
                1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
                2 -> text.toByteArray(Charsets.UTF_16BE)
                else -> text.toByteArray(Charsets.UTF_8)
            }
            write(body)
        }.toByteArray()
        return id3Frame("USLT", payload)
    }

    private fun id3Frame(id: String, payload: ByteArray): ByteArray {
        val frames = ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            write(u32be(payload.size))
            write(byteArrayOf(0, 0)) // flags
            write(payload)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(byteArrayOf(3, 0)) // v2.3.0
            write(0) // flags：没有扩展头
            write(syncSafe(frames.size))
            write(frames)
        }.toByteArray()
    }

    private fun u32le(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun u32be(value: Int) = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun u24(value: Int) = byteArrayOf(
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
