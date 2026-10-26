package com.melody.player

import com.melody.player.data.TrackInfo
import com.melody.player.data.TrackInfoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌曲信息解析/格式化的纯逻辑测试（不需要设备）。
 *
 * 位深与码率的用例都用合成出来的文件头，等价于真实 FLAC / WAV / AIFF 的前几十字节。
 */
class TrackInfoTest {

    @Test
    fun `summary joins available fields`() {
        val info = TrackInfo(format = "FLAC", bitrateKbps = 996, sampleRateHz = 44100)
        assertEquals("FLAC • 996 kb/s • 44.1 kHz", info.summary())
    }

    @Test
    fun `summary uses retro style separators`() {
        // 对齐 RetroMusicPlayer getSongInfo(): "FLAC • 24-bit • 1411 kb/s • 48 kHz"
        val info = TrackInfo("FLAC", bitDepth = 24, bitrateKbps = 1411, sampleRateHz = 48000)
        assertEquals("FLAC • 24-bit • 1411 kb/s • 48 kHz", info.summary())
        assertEquals(" • ", TrackInfo.SEPARATOR)
    }

    @Test
    fun `summary puts bit depth before bitrate`() {
        val info = TrackInfo(
            format = "FLAC",
            bitDepth = 24,
            bitrateKbps = 1411,
            sampleRateHz = 48000,
        )
        assertEquals("FLAC • 24-bit • 1411 kb/s • 48 kHz", info.summary())
    }

    @Test
    fun `estimated bitrate looks identical to a measured one`() {
        // 估算值不显示 ≈，和解析器给出的码率长得一样
        val info = TrackInfo(format = "FLAC", bitDepth = 16, bitrateKbps = 1411, bitrateEstimated = true)
        assertEquals("FLAC • 16-bit • 1411 kb/s", info.summary())
        assertEquals(
            "FLAC • 16-bit • 1411 kb/s",
            TrackInfo(format = "FLAC", bitDepth = 16, bitrateKbps = 1411).summary(),
        )
    }

    @Test
    fun `summary skips missing fields`() {
        assertEquals("MP3 • 44.1 kHz", TrackInfo(format = "MP3", sampleRateHz = 44100).summary())
        assertEquals("M4A • 256 kb/s", TrackInfo(format = "M4A", bitrateKbps = 256).summary())
        assertEquals("", TrackInfo().summary())
        assertEquals("", TrackInfo(format = null, bitrateKbps = 0, sampleRateHz = 0).summary())
        // 位深为 0 这种脏数据也不该冒出来
        assertEquals("WAV", TrackInfo(format = "WAV", bitDepth = 0).summary())
    }

    @Test
    fun `estimate bitrate from file size and duration`() {
        // 52.9 MB / 300s ≈ 1411 kbps（CD 音质 FLAC）
        assertEquals(1411, TrackInfo.estimateBitrateKbps(52_912_500L, 300_000L))
        // 5 MB / 300s ≈ 133 kbps
        assertEquals(133, TrackInfo.estimateBitrateKbps(5_000_000L, 300_000L))
        assertNull(TrackInfo.estimateBitrateKbps(0L, 300_000L))
        assertNull(TrackInfo.estimateBitrateKbps(5_000_000L, 0L))
        // 明显不合理的值（几秒钟却几百 MB）直接丢掉
        assertNull(TrackInfo.estimateBitrateKbps(900_000_000L, 1_000L))
    }

    @Test
    fun `flac bit depth from streaminfo`() {
        assertEquals(16, TrackInfoStore.flacBitDepth(flacHeader(16, 44100, 2)))
        assertEquals(24, TrackInfoStore.flacBitDepth(flacHeader(24, 96000, 2)))
        assertEquals(24, TrackInfoStore.flacBitDepth(flacHeader(24, 48000, 6)))
        assertNull(TrackInfoStore.flacBitDepth(ByteArray(10)))
        assertNull(TrackInfoStore.flacBitDepth(wavHeader(16, 44100)))
    }

    @Test
    fun `wav bit depth from fmt chunk`() {
        assertEquals(16, TrackInfoStore.wavBitDepth(wavHeader(16, 44100)))
        assertEquals(24, TrackInfoStore.wavBitDepth(wavHeader(24, 96000)))
        // 中间夹一个 LIST 块也要能找到 fmt
        assertEquals(24, TrackInfoStore.wavBitDepth(wavHeader(24, 48000, leadingListChunk = true)))
        assertNull(TrackInfoStore.wavBitDepth(flacHeader(16, 44100, 2)))
    }

    @Test
    fun `aiff bit depth from comm chunk`() {
        assertEquals(16, TrackInfoStore.aiffBitDepth(aiffHeader(16)))
        assertEquals(24, TrackInfoStore.aiffBitDepth(aiffHeader(24)))
    }

    @Test
    fun `sample rate formatting`() {
        assertEquals("44.1 kHz", TrackInfo.formatSampleRate(44100))
        assertEquals("48 kHz", TrackInfo.formatSampleRate(48000))
        assertEquals("96 kHz", TrackInfo.formatSampleRate(96000))
        assertEquals("22.05 kHz", TrackInfo.formatSampleRate(22050))
        assertEquals("8 kHz", TrackInfo.formatSampleRate(8000))
    }

    @Test
    fun `format from file extension`() {
        assertEquals("FLAC", TrackInfoStore.formatFromPath("/sdcard/Music/稻香.FLAC"))
        assertEquals("MP3", TrackInfoStore.formatFromPath("/a/b/c.mp3"))
        assertEquals("M4A", TrackInfoStore.formatFromPath("D:\\music\\x.m4a"))
        assertEquals("OPUS", TrackInfoStore.formatFromPath("song.opus"))
        assertNull(TrackInfoStore.formatFromPath(null))
        assertNull(TrackInfoStore.formatFromPath("/music/no_extension"))
        // 未知后缀原样大写，方便排查
        assertEquals("DSD2", TrackInfoStore.formatFromPath("a.dsd2"))
    }

    @Test
    fun `format from mime type`() {
        assertEquals("MP3", TrackInfoStore.formatFromMime("audio/mpeg"))
        assertEquals("FLAC", TrackInfoStore.formatFromMime("audio/flac"))
        assertEquals("M4A", TrackInfoStore.formatFromMime("audio/mp4a-latm"))
        assertEquals("OGG", TrackInfoStore.formatFromMime("audio/ogg"))
        assertEquals("WAV", TrackInfoStore.formatFromMime("audio/raw"))
        assertNull(TrackInfoStore.formatFromMime(null))
    }

    @Test
    fun `isEmpty reflects whether anything is known`() {
        assertEquals(true, TrackInfo().isEmpty)
        assertEquals(false, TrackInfo(format = "MP3").isEmpty)
        assertEquals(false, TrackInfo(sampleRateHz = 48000).isEmpty)
        assertEquals(false, TrackInfo(bitDepth = 24).isEmpty)
    }

    // ---------------------------------------------------------------- 测试数据

    /** 造一个 42 字节的 FLAC 头：fLaC + STREAMINFO 块头 + 34 字节 STREAMINFO。 */
    private fun flacHeader(bitsPerSample: Int, sampleRate: Int, channels: Int): ByteArray {
        val head = ByteArray(42)
        "fLaC".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[4] = 0x00 // 最后一个 metadata 块 + 类型 0(STREAMINFO)
        head[5] = 0x00
        head[6] = 0x00
        head[7] = 0x22 // 长度 34
        val body = 8
        head[body + 0] = 0x10 // min blocksize 4096
        head[body + 2] = 0x10 // max blocksize 4096
        val bps = bitsPerSample - 1
        head[body + 10] = ((sampleRate ushr 12) and 0xFF).toByte()
        head[body + 11] = ((sampleRate ushr 4) and 0xFF).toByte()
        head[body + 12] = (
            ((sampleRate and 0x0F) shl 4) or
                (((channels - 1) and 0x07) shl 1) or
                ((bps ushr 4) and 0x01)
            ).toByte()
        head[body + 13] = (((bps and 0x0F) shl 4) or 0).toByte()
        return head
    }

    /** 造一个 WAV 头：RIFF/WAVE + (可选 LIST 块) + fmt 块 + data 块头。 */
    private fun wavHeader(bitsPerSample: Int, sampleRate: Int, leadingListChunk: Boolean = false): ByteArray {
        val out = ArrayList<Byte>()
        "RIFF".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        out.addAll(listOf(0, 0, 0, 0)) // RIFF size，解析用不到
        "WAVE".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        if (leadingListChunk) {
            "LIST".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
            out.addAll(listOf(4, 0, 0, 0)) // size = 4
            out.addAll(listOf(1, 2, 3, 4)) // 4 字节内容
        }
        "fmt ".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        out.addAll(listOf(16, 0, 0, 0)) // fmt 块长度 16
        out.addAll(listOf(1, 0)) // PCM
        out.addAll(listOf(2, 0)) // 双声道
        repeat(4) { out.add(((sampleRate ushr (8 * it)) and 0xFF).toByte()) }
        out.addAll(listOf(0, 0, 0, 0)) // byte rate
        out.addAll(listOf(4, 0)) // block align
        out.addAll(listOf((bitsPerSample and 0xFF).toByte(), ((bitsPerSample ushr 8) and 0xFF).toByte()))
        "data".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        out.addAll(listOf(0, 0, 0, 0))
        return out.toByteArray()
    }

    /** 造一个 AIFF 头：FORM/AIFF + COMM 块。 */
    private fun aiffHeader(bitsPerSample: Int): ByteArray {
        val out = ArrayList<Byte>()
        "FORM".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        out.addAll(listOf(0, 0, 0, 0))
        "AIFF".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        "COMM".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        out.addAll(listOf(0, 0, 0, 18)) // 块长度 18（大端）
        out.addAll(listOf(0, 2)) // 声道
        out.addAll(listOf(0, 0, 0, 100)) // 帧数
        out.addAll(listOf(((bitsPerSample ushr 8) and 0xFF).toByte(), (bitsPerSample and 0xFF).toByte()))
        repeat(10) { out.add(0) } // 80 bit 采样率，解析用不到
        return out.toByteArray()
    }
}
