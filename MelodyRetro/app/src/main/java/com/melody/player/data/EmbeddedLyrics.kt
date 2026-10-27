package com.melody.player.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 读取音频文件**内嵌**的歌词标签。
 *
 * 很多从各处下载的音源（尤其是 FLAC / 抓轨 / 影视原声合集）其实自带带时间轴的歌词，
 * 只是躺在标签里没人读。只要标签里有，就完全不需要联网，更不会因为标签写错而搜错歌。
 *
 * 支持：
 * - **FLAC**：Vorbis comment 的 `LYRICS` / `SYNCEDLYRICS` / `UNSYNCEDLYRICS`（UTF-8）
 * - **MP3 / AIFF 等带 ID3v2 的**：`USLT` 帧（v2.2 里叫 `ULT`），支持 4 种编码
 *
 * 明确不支持：M4A / MP4 的 `©lyr` 原子、OGG。这些容器会安全返回 `null`，走在线歌词。
 *
 * 只读文件开头 2MB —— 标签几乎都在文件头，没必要把几十 MB 的音频读进内存。
 */
object EmbeddedLyrics {

    private const val MAX_SCAN_BYTES = 2 * 1024 * 1024

    /** FLAC 的 Vorbis comment 里可能出现的歌词键，按优先级从高到低。 */
    private val SYNCED_KEYS = setOf("LYRICS", "SYNCEDLYRICS", "SYNCLYRICS")
    private const val UNSYNCED_KEY = "UNSYNCEDLYRICS"

    /** 从文件读取；读不到或没有标签返回 `null`。任何异常都吞掉，歌词缺失不该影响播放。 */
    fun read(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        val head = runCatching { readHead(file) }.getOrNull() ?: return null
        return runCatching { parse(head) }.getOrNull()
    }

    /** 读文件开头一段就够：标签几乎都在头部，没必要把几十 MB 的音频读进内存。 */
    private fun readHead(file: File): ByteArray {
        val limit = minOf(file.length(), MAX_SCAN_BYTES.toLong()).toInt()
        if (limit <= 0) return ByteArray(0)
        val buffer = ByteArray(limit)
        var filled = 0
        file.inputStream().use { input ->
            while (filled < limit) {
                val read = input.read(buffer, filled, limit - filled)
                if (read <= 0) break
                filled += read
            }
        }
        return if (filled == limit) buffer else buffer.copyOf(filled)
    }

    /** 纯解析：输入文件头字节，返回歌词原文（可能是有时间轴的 LRC，也可能是纯文本）。 */
    internal fun parse(bytes: ByteArray): String? = when {
        bytes.hasAsciiPrefix("fLaC") -> parseFlac(bytes)
        bytes.hasAsciiPrefix("ID3") -> parseId3(bytes)
        else -> null
    }.cleanOrNull()

    // ------------------------------------------------------------------ FLAC

    private fun parseFlac(bytes: ByteArray): String? {
        var pos = 4 // 跳过 "fLaC"
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = readUInt24(bytes, pos + 1)
            val start = pos + 4
            if (length <= 0 || start + length > bytes.size) return null

            // 4 = VORBIS_COMMENT
            if (type == 4) vorbisLyrics(bytes, start, length)?.let { return it }

            pos = start + length
            if (isLast) break
        }
        return null
    }

    /** Vorbis comment 结构：vendor 长度+内容，条目数，然后每条 长度+`KEY=value`。 */
    private fun vorbisLyrics(bytes: ByteArray, offset: Int, length: Int): String? {
        val end = offset + length
        var pos = offset

        if (pos + 4 > end) return null
        val vendorLength = readUInt32Le(bytes, pos)
        pos += 4
        if (vendorLength < 0 || pos + vendorLength > end) return null
        pos += vendorLength

        if (pos + 4 > end) return null
        val count = readUInt32Le(bytes, pos)
        pos += 4
        if (count < 0) return null

        var unsynced: String? = null
        repeat(count) {
            if (pos + 4 > end) return unsynced
            val entryLength = readUInt32Le(bytes, pos)
            pos += 4
            if (entryLength < 0 || pos + entryLength > end) return unsynced
            val entry = String(bytes, pos, entryLength, Charsets.UTF_8)
            pos += entryLength

            val separator = entry.indexOf('=')
            if (separator <= 0) return@repeat
            val key = entry.substring(0, separator).trim().uppercase()
            val value = entry.substring(separator + 1).cleanOrNull() ?: return@repeat
            when {
                key in SYNCED_KEYS -> return value
                key == UNSYNCED_KEY && unsynced == null -> unsynced = value
            }
        }
        return unsynced
    }

    // ------------------------------------------------------------------ ID3v2

    private fun parseId3(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        val major = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = readSyncSafe(bytes, 6)
        if (tagSize <= 0) return null
        val end = minOf(bytes.size, 10 + tagSize)

        var pos = 10
        // 扩展头：v2.4 的长度含自身，v2.3 的不含
        if (flags and 0x40 != 0 && pos + 4 <= end) {
            pos += if (major >= 4) readSyncSafe(bytes, pos) else readUInt32(bytes, pos) + 4
        }

        val idLength = if (major == 2) 3 else 4
        val frameHeader = if (major == 2) 6 else 10
        while (pos + frameHeader <= end) {
            val id = String(bytes, pos, idLength, Charsets.ISO_8859_1)
            if (id.isBlank() || id[0] == '\u0000') break // 进入 padding

            val declared = when {
                major == 2 -> readUInt24(bytes, pos + 3)
                major >= 4 -> readSyncSafe(bytes, pos + 4)
                else -> readUInt32(bytes, pos + 4)
            }
            val dataStart = pos + frameHeader
            // v2.4 个别写入器把帧长写成普通整数，同步安全值解析失败时按普通整数再试一次
            val frameSize = if (declared > 0 && dataStart + declared <= end) {
                declared
            } else {
                readUInt32(bytes, pos + 4)
            }
            if (frameSize <= 0 || dataStart + frameSize > end) break

            if (id == "USLT" || id == "ULT") {
                decodeUslt(bytes, dataStart, frameSize)?.let { return it }
            }
            pos = dataStart + frameSize
        }
        return null
    }

    /** USLT 帧：1 字节编码 + 3 字节语言 + 内容描述符（带终止符）+ 歌词正文。 */
    private fun decodeUslt(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length < 5) return null
        val encoding = bytes[offset].toInt() and 0xFF
        val end = offset + length
        var pos = offset + 4

        val terminator = if (encoding == 1 || encoding == 2) 2 else 1
        while (pos + terminator <= end) {
            val isTerminator = if (terminator == 1) {
                bytes[pos].toInt() == 0
            } else {
                bytes[pos].toInt() == 0 && bytes[pos + 1].toInt() == 0
            }
            if (isTerminator) {
                pos += terminator
                break
            }
            pos += terminator
        }
        if (pos >= end) return null
        return decodeText(bytes, pos, end - pos, encoding)
    }

    private fun decodeText(bytes: ByteArray, offset: Int, length: Int, encoding: Int): String? {
        if (length <= 0) return null
        val slice = ByteArray(length)
        System.arraycopy(bytes, offset, slice, 0, length)
        val text = when (encoding) {
            // 0x01：UTF-16，字节序看 BOM（FF FE = 小端，FE FF = 大端）
            1 -> {
                val bom = if (slice.size >= 2) {
                    ((slice[0].toInt() and 0xFF) shl 8) or (slice[1].toInt() and 0xFF)
                } else {
                    0
                }
                when (bom) {
                    0xFFFE -> decodeCharset(slice.copyOfRange(2, slice.size), Charsets.UTF_16LE)
                    0xFEFF -> decodeCharset(slice.copyOfRange(2, slice.size), Charsets.UTF_16BE)
                    // 没有 BOM 时按小端处理（Windows 下的写入器最常见）
                    else -> decodeCharset(slice, Charsets.UTF_16LE)
                }
            }
            2 -> decodeCharset(slice, Charsets.UTF_16BE)
            3 -> decodeCharset(slice, Charsets.UTF_8)
            // 0x00：规范写的是 ISO-8859-1，但中文歌词实际多是 GBK/UTF-8，按这两种猜
            else -> decodeLegacy(slice)
        }
        return text?.cleanOrNull()
    }

    private fun decodeCharset(bytes: ByteArray, charset: Charset): String? =
        runCatching { String(bytes, charset) }.getOrNull()

    /** 老编码的猜测顺序：UTF-8 严格解码 → GB18030。 */
    private fun decodeLegacy(bytes: ByteArray): String? {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
    }

    // ------------------------------------------------------------------ 字节工具

    private fun ByteArray.hasAsciiPrefix(prefix: String): Boolean {
        if (size < prefix.length) return false
        return prefix.indices.all { this[it] == prefix[it].code.toByte() }
    }

    private fun String?.cleanOrNull(): String? {
        if (this == null) return null
        val text = trim().trim('\u0000').trim()
        return text.ifBlank { null }
    }

    private fun readUInt24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** ID3 的同步安全整数：每字节只用低 7 位。 */
    private fun readSyncSafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        var value = 0
        for (i in 0 until 4) {
            value = (value shl 7) or (bytes[offset + i].toInt() and 0x7F)
        }
        return value
    }
}
