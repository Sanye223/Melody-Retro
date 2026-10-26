package com.melody.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.melody.player.data.artwork.OnlineArtworkStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections

/**
 * 专辑封面缓存。优先使用系统缩略图（API 29+ 的 loadThumbnail），
 * 退化时用 MediaMetadataRetriever 读取音频内嵌图片。
 * 结果会以 JPEG 字节写入磁盘缓存，供 [ArtworkProvider] 通过 content:// 输出。
 */
object ArtworkStore {

    private const val MEMORY_CACHE_BYTES = 10 * 1024 * 1024
    private const val THUMB_SIZE = 512

    private val memory = object : LruCache<String, ByteArray>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val missing: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    fun artworkBytes(context: Context, albumId: Long): ByteArray? {
        // 负数 id 是"无标签文件"的合成专辑 id（-trackId），要照常处理
        if (albumId == 0L) return null
        val key = albumId.toString()

        memory.get(key)?.let { return it.takeIf { bytes -> bytes.isNotEmpty() } }
        // 内嵌封面确实没有时，才看在线补全留下的缓存（不触发网络）
        if (missing.contains(albumId)) return onlineBytes(context, albumId, key)

        val dir = cacheDir(context)
        val file = File(dir, "$albumId.img")
        if (file.exists()) {
            val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
            memory.put(key, bytes)
            if (bytes.isEmpty()) {
                // 上一次运行已经确认没有内嵌封面，但可能已经有在线补全的图
                missing.add(albumId)
                return onlineBytes(context, albumId, key)
            }
            return bytes
        }

        val bytes = runCatching { extract(context, albumId) }.getOrNull()
        runCatching {
            if (bytes == null) file.createNewFile() else file.writeBytes(bytes)
        }
        memory.put(key, bytes ?: ByteArray(0))
        if (bytes == null) {
            missing.add(albumId)
            return onlineBytes(context, albumId, key)
        }
        return bytes
    }

    /** 读取在线补全落盘的封面；命中后放进内存缓存，后续不再读盘。 */
    private fun onlineBytes(context: Context, albumId: Long, key: String): ByteArray? {
        val bytes = OnlineArtworkStore.cachedBytes(context, albumId) ?: return null
        memory.put(key, bytes)
        return bytes
    }

    /**
     * 在线封面写入后调用：清掉"这张专辑没有封面"的会话内标记，
     * 让 [ArtworkProvider] / Coil 的下一次请求能读到新图。
     */
    fun invalidate(albumId: Long) {
        missing.remove(albumId)
        memory.remove(albumId.toString())
    }

    fun invalidateAll() {
        missing.clear()
        memory.evictAll()
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "artwork").apply { if (!exists()) mkdirs() }

    private fun extract(context: Context, albumId: Long): ByteArray? {
        val resolver = context.contentResolver
        var trackId = -1L
        var path: String? = null

        // 负数 id 是单曲合成专辑（-trackId），直接按曲目取；正数按专辑取
        val selection = if (albumId < 0L) {
            "${MediaStore.Audio.Media._ID} = ?"
        } else {
            "${MediaStore.Audio.Media.ALBUM_ID} = ?"
        }
        val argument = if (albumId < 0L) (-albumId).toString() else albumId.toString()

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
            selection,
            arrayOf(argument),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                trackId = cursor.getLong(0)
                path = if (cursor.isNull(1)) null else cursor.getString(1)
            }
        }
        if (trackId <= 0L) return null

        var bitmap: Bitmap? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val songUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                trackId,
            )
            bitmap = runCatching {
                resolver.loadThumbnail(songUri, Size(THUMB_SIZE, THUMB_SIZE), null)
            }.getOrNull()
        }

        if (bitmap == null && !path.isNullOrBlank()) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val picture = retriever.embeddedPicture
                if (picture != null) {
                    bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
                }
            } catch (_: Exception) {
                // 没有内嵌封面，正常情况
            } finally {
                runCatching { retriever.release() }
            }
        }

        val result = bitmap ?: return null
        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 86, out)
        return out.toByteArray()
    }
}
