package com.melody.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 一首歌的音频参数，用于「正在播放」页的额外信息行。
 * 任一字段解析不出来就是 null，展示时自动跳过。
 */
data class TrackInfo(
    /** 展示用格式名，例如 MP3 / FLAC / M4A。 */
    val format: String? = null,
    /** 位深（bit per sample），例如 16 / 24。有损格式没有这个概念，恒为 null。 */
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    /** 码率是否由「文件体积 ÷ 时长」估算得来（解析器没给准确值时）。 */
    val bitrateEstimated: Boolean = false,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
) {
    val isEmpty: Boolean
        get() = format == null && bitDepth == null && bitrateKbps == null && sampleRateHz == null

    /**
     * 拼成单行文本，缺项自动省略。
     *
     * 格式对齐 RetroMusicPlayer 的 `getSongInfo()`：
     * `FLAC • 24-bit • 1411 kb/s • 48 kHz`
     * 分隔符用 ` • `，位深写成 `24-bit`，码率单位 `kb/s`。
     * 码率即使是「体积 ÷ 时长」估算出来的也照常显示，不加 `≈` 前缀。
     */
    fun summary(): String {
        if (isEmpty) return ""
        val parts = ArrayList<String>(4)
        format?.let { parts += it }
        bitDepth?.takeIf { it in 1..64 }?.let { parts += "$it-bit" }
        // 估算值也按普通码率显示，不加 ≈ 之类的标记（用户明确要求不要）
        bitrateKbps?.takeIf { it > 0 }?.let { parts += "$it kb/s" }
        sampleRateHz?.takeIf { it > 0 }?.let { parts += formatSampleRate(it) }
        return parts.joinToString(SEPARATOR)
    }

    companion object {
        /** 与 RetroMusicPlayer 一致的字段分隔符（U+2022）。 */
        const val SEPARATOR = " • "

        /** 44100 → "44.1 kHz"；48000 → "48 kHz"；22050 → "22.05 kHz"。 */
        fun formatSampleRate(hz: Int): String {
            val khz = hz / 1000.0
            val text = if (khz % 1.0 == 0.0) {
                khz.toInt().toString()
            } else {
                String.format(Locale.US, "%.2f", khz).trimEnd('0').trimEnd('.')
            }
            return "$text kHz"
        }

        /**
         * 用「体积 ÷ 时长」估算平均码率（kbps）。
         *
         * FLAC / WAV 这类无损文件是恒定质量的 VBR，容器头里往往不写 `KEY_BIT_RATE`，
         * 但平均码率对它们反而最有参考价值（1411 kbps ≈ CD 音质）。
         */
        fun estimateBitrateKbps(sizeBytes: Long, durationMs: Long): Int? {
            if (sizeBytes <= 0L || durationMs <= 0L) return null
            // bytes * 8 / (durationMs / 1000) / 1000 化简后就是 bytes * 8 / durationMs
            val kbps = (sizeBytes * 8.0 / durationMs).roundToInt()
            return kbps.takeIf { it in 16..20_000 }
        }
    }
}

/**
 * 解析并缓存音频参数。
 *
 * 只解析当前播放的这一首：先用 [MediaExtractor] 读容器头（几毫秒），
 * 拿不到位深时再读文件头几十字节自己解析（FLAC 的 STREAMINFO、WAV 的 fmt 块、AIFF 的 COMM 块）。
 * 结果按 trackId 缓存在 LRU 里，切歌来回切不会重复解析。
 * 任何异常都吞掉并返回 null —— 信息行只是锦上添花，绝不能影响播放。
 */
object TrackInfoStore {

    private const val TAG = "TrackInfo"
    private val cache = LruCache<Long, TrackInfo>(48)

    /** 正在解析中的曲目，避免同一首歌被并发解析两次。 */
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<Long>())

    suspend fun info(
        context: Context,
        trackId: Long,
        path: String?,
        durationMs: Long = 0L,
    ): TrackInfo? {
        if (trackId <= 0L) return null
        cache.get(trackId)?.let { return it }
        if (!inFlight.add(trackId)) return null
        return try {
            val parsed = withContext(Dispatchers.IO) { parse(context, trackId, path, durationMs) }
            if (parsed != null) cache.put(trackId, parsed)
            parsed
        } finally {
            inFlight.remove(trackId)
        }
    }

    fun clearCache() {
        cache.evictAll()
    }

    private fun parse(context: Context, trackId: Long, path: String?, durationMs: Long): TrackInfo? {
        val raw = runCatching { readWithExtractor(context, trackId) }.getOrNull()
        val format = formatFromPath(path) ?: raw?.format

        // 位深：容器头能给就用（API 31+ 的 KEY_BITS_PER_SAMPLE / PCM 编码），
        // 否则按格式自己读文件头；有损格式不提供位深。
        val bitDepth = raw?.bitDepth
            ?: runCatching { readBitDepthFromHeader(context, trackId, format) }.getOrNull()

        // 码率：容器头优先，没有就按体积/时长估算（FLAC、部分 WAV 常见）
        val headerBitrate = raw?.bitrateKbps
        val estimated = if (headerBitrate == null) {
            TrackInfo.estimateBitrateKbps(
                sizeBytes = runCatching { fileSize(context, trackId) }.getOrNull() ?: 0L,
                durationMs = durationMs,
            )
        } else {
            null
        }

        val result = TrackInfo(
            format = format,
            bitDepth = bitDepth,
            bitrateKbps = headerBitrate ?: estimated,
            bitrateEstimated = headerBitrate == null && estimated != null,
            sampleRateHz = raw?.sampleRateHz,
            channels = raw?.channels,
        )
        if (result.isEmpty) {
            Log.i(TAG, "no info for track $trackId")
            return null
        }
        return result
    }

    /** 从容器头读到的原始参数。 */
    private data class RawInfo(
        val format: String?,
        val bitDepth: Int?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
    )

    private fun readWithExtractor(context: Context, trackId: Long): RawInfo? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, audioUri(trackId), null)
            if (extractor.trackCount <= 0) return null
            val format = extractor.getTrackFormat(0)

            val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull()
            val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull()
            val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()

            return RawInfo(
                format = formatFromMime(mime),
                bitDepth = extractorBitDepth(format),
                bitrateKbps = bitrate?.takeIf { it > 0 }?.div(1000)?.takeIf { it > 0 },
                sampleRateHz = sampleRate?.takeIf { it > 0 },
                channels = channels?.takeIf { it > 0 },
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * MediaFormat 里的位深信息。
     *
     * `bits-per-sample` 是 Android 12 起部分 extractor（尤其是 PCM / FLAC）会写进 MediaFormat
     * 的键，但 SDK 里没有对应的公开常量，只能用字符串取；取不到就交给文件头解析。
     */
    private fun extractorBitDepth(format: MediaFormat): Int? {
        runCatching { format.getInteger(KEY_BITS_PER_SAMPLE) }
            .getOrNull()?.takeIf { it in 4..64 }?.let { return it }
        val pcm = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrNull()
        return when (pcm) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }

    // ------------------------------------------------------------ 文件头解析

    /**
     * 从文件头读位深，只支持头信息在文件开头的无损格式。
     * 有损格式（MP3 / AAC / Vorbis / Opus）解码后统一是浮点，谈位深没有意义，直接返回 null。
     */
    internal fun readBitDepthFromHeader(context: Context, trackId: Long, format: String?): Int? {
        if (format !in HEADER_FORMATS) return null
        val head = runCatching {
            context.contentResolver.openInputStream(audioUri(trackId))?.use { readHead(it, 64 * 1024) }
        }.getOrNull() ?: return null
        return when {
            head.startsWithAscii("fLaC") -> flacBitDepth(head)
            head.startsWithAscii("RIFF") || head.startsWithAscii("RF64") -> wavBitDepth(head)
            head.startsWithAscii("FORM") -> aiffBitDepth(head)
            else -> null
        }
    }

    /** FLAC：第一个 metadata 块必须是 STREAMINFO，位深藏在 STREAMINFO 第 12/13 字节（文件内 20/21）。 */
    internal fun flacBitDepth(head: ByteArray): Int? {
        // 4("fLaC") + 4(块头) + 34(STREAMINFO) = 42
        if (head.size < 42 || !head.startsWithAscii("fLaC")) return null
        val blockType = head[4].toInt() and 0x7F
        if (blockType != 0) return null // 0 = STREAMINFO
        // STREAMINFO 体从第 8 字节开始：
        // minBlock(2) maxBlock(2) minFrame(3) maxFrame(3) 之后是
        // 20 bit 采样率 + 3 bit (声道-1) + 5 bit (位深-1) + 36 bit 总采样数
        val bodyStart = 8
        val high = head[bodyStart + 12].toInt() and 0x01
        val low = (head[bodyStart + 13].toInt() and 0xF0) ushr 4
        val bits = ((high shl 4) or low) + 1
        return bits.takeIf { it in 4..64 }
    }

    /** WAV：遍历 RIFF 块找 "fmt "，位深在该块数据偏移 14（小端 16 位）。 */
    internal fun wavBitDepth(head: ByteArray): Int? {
        if (!head.startsWithAscii("RIFF") && !head.startsWithAscii("RF64")) return null
        var pos = 12
        while (pos + 8 <= head.size) {
            val id = String(head, pos, 4, Charsets.US_ASCII)
            val size = readIntLE(head, pos + 4)
            if (id == "fmt " && size >= 16 && pos + 8 + 16 <= head.size) {
                return readShortLE(head, pos + 8 + 14).takeIf { it in 4..64 }
            }
            if (size < 0 || size > head.size) return null
            pos += 8 + size + (size and 1) // 块按偶数字节对齐
        }
        return null
    }

    /** AIFF：FORM 之后遍历块找 "COMM"，位深在数据偏移 6（大端 16 位）。 */
    internal fun aiffBitDepth(head: ByteArray): Int? {
        if (!head.startsWithAscii("FORM")) return null
        var pos = 12
        while (pos + 8 <= head.size) {
            val id = String(head, pos, 4, Charsets.US_ASCII)
            val size = readIntBE(head, pos + 4)
            if (id == "COMM" && size >= 18 && pos + 8 + 18 <= head.size) {
                val data = pos + 8
                return (((head[data + 6].toInt() and 0xFF) shl 8) or
                    (head[data + 7].toInt() and 0xFF)).takeIf { it in 4..64 }
            }
            if (size < 0 || size > head.size) return null
            pos += 8 + size + (size and 1)
        }
        return null
    }

    private fun readHead(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = input.read(buffer, filled, limit - filled)
            if (read <= 0) break
            filled += read
        }
        return if (filled == limit) buffer else buffer.copyOf(filled)
    }

    private fun ByteArray.startsWithAscii(tag: String): Boolean {
        if (size < tag.length) return false
        return String(this, 0, tag.length, Charsets.US_ASCII) == tag
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        readShortLE(bytes, offset) or (readShortLE(bytes, offset + 2) shl 16)

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    // ------------------------------------------------------------ 工具

    private fun audioUri(trackId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

    /** 文件体积（字节），用于没有准确码率时按体积/时长估算。 */
    private fun fileSize(context: Context, trackId: Long): Long? =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(audioUri(trackId), "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 }

    /** 优先用文件后缀（用户更熟悉 mp3/flac 这种写法）。 */
    internal fun formatFromPath(path: String?): String? {
        val name = path?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp3" -> "MP3"
            "flac" -> "FLAC"
            "m4a", "mp4", "aac" -> "M4A"
            "wav", "wave" -> "WAV"
            "ogg", "oga" -> "OGG"
            "opus" -> "OPUS"
            "wma" -> "WMA"
            "ape" -> "APE"
            "aiff", "aif" -> "AIFF"
            "dsf" -> "DSD"
            "" -> null
            else -> ext.uppercase(Locale.ROOT)
        }
    }

    internal fun formatFromMime(mime: String?): String? = when {
        mime == null -> null
        mime.contains("mpeg") -> "MP3"
        mime.contains("flac") -> "FLAC"
        mime.contains("mp4a") || mime.contains("aac") -> "M4A"
        mime.contains("ogg") -> "OGG"
        mime.contains("opus") -> "OPUS"
        mime.contains("raw") -> "WAV"
        mime.contains("x-ms-wma") -> "WMA"
        mime.contains("x-ape") -> "APE"
        mime.contains("aiff") -> "AIFF"
        else -> mime.substringAfter("audio/", "").uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    /** 只有这几种格式值得去读文件头找位深。 */
    private val HEADER_FORMATS = setOf("FLAC", "WAV", "AIFF")

    /** MediaFormat 里的位深键，SDK 未公开常量。 */
    private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"
}
