package com.melody.player.data.artwork

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.melody.player.data.FileNameMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 在线封面的磁盘缓存 + 负缓存 + 并发控制。
 *
 * 存储策略：
 * - 图片落在 `filesDir/online-artwork/<albumId>.jpg`（files 而不是 cache，
 *   因为这是用户可见的"资产"，不该被系统清缓存清掉）。
 * - `filesDir/online-artwork/index.json` 记录每张的来源、时间、体积与最近访问时间，
 *   以及检索失败的负缓存时间戳。
 * - 容量上限 [MAX_BYTES] / [MAX_ENTRIES]，超了按最近访问时间淘汰。
 *
 * 稳定性：
 * - 全局最多 [CONCURRENCY] 个并发请求，避免列表里几十个封面同时发请求；
 * - 同一专辑同一时刻只会有一个请求（in-flight 去重）；
 * - 失败记 7 天负缓存，避免每次滑动列表都重复打接口。
 */
object OnlineArtworkStore {

    private const val TAG = "OnlineArtwork"
    private const val DIR_NAME = "online-artwork"
    private const val INDEX_NAME = "index.json"

    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRIES = 600
    private const val CONCURRENCY = 2
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 索引结构版本。改过匹配逻辑就 +1：旧版本写下的负缓存会被丢掉，
     * 否则升级后仍会因为"以前判定过没有"而一周不重试。
     */
    private const val INDEX_VERSION = 2

    private val mutex = Mutex()
    private val gate = Semaphore(CONCURRENCY)
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private class Entry(
        val source: String,
        var bytes: Long,
        var writtenAt: Long,
        var lastAccess: Long,
    )

    private var loaded = false
    private val entries = HashMap<Long, Entry>()
    private val misses = HashMap<Long, Long>()

    /** 单次补全的结果，用于设置页统计与 UI 提示。 */
    enum class Result {
        /** 本地已有（内嵌或已缓存）。 */
        HAVE_LOCAL,

        /** 本次从网络取到并已落盘。 */
        FETCHED,

        /** 查过了，确实没有匹配（含负缓存命中和负缓存写入）。 */
        NO_MATCH,

        /** 网络/接口异常，本次放弃，不写负缓存。 */
        FAILED,

        /** 同一专辑已有请求在跑，直接跳过。 */
        BUSY,
    }

    /** 已缓存的在线封面文件，供 [com.melody.player.data.ArtworkStore] 读取。 */
    fun cachedFile(context: Context, albumId: Long): File? {
        if (albumId == 0L) return null
        return dir(context).resolve("$albumId.jpg").takeIf { it.isFile && it.length() > 0 }
    }

    /** 已在本地缓存则直接返回字节，不触发任何网络。 */
    fun cachedBytes(context: Context, albumId: Long): ByteArray? {
        val file = cachedFile(context, albumId) ?: return null
        val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (bytes != null) entries[albumId]?.lastAccess = System.currentTimeMillis()
        return bytes
    }
    /**
     * 按需补一张封面：本地已有就直接返回，没有才联网。
     *
     * @param force 忽略负缓存，强制重试（"重新获取封面"用）。
     */
    suspend fun ensure(context: Context, albumId: Long, force: Boolean = false): Result {
        // 0 表示这张专辑根本没有 id；负数是"无标签单曲"的合成 id，要照常联网
        if (albumId == 0L) return Result.NO_MATCH

        // 1) 本地优先：内嵌封面或已缓存，直接返回，零网络开销
        if (!force) {
            val local = withContext(Dispatchers.IO) {
                com.melody.player.data.ArtworkStore.artworkBytes(context, albumId)
            }
            if (local != null) return Result.HAVE_LOCAL
        } else if (cachedFile(context, albumId) != null) {
            return Result.HAVE_LOCAL
        }

        // 2) 负缓存：最近查过且没有结果，7 天内不再打扰接口
        if (!force && hasFreshMiss(context, albumId)) return Result.NO_MATCH

        // 3) 去重：同一专辑只允许一个请求
        if (!inFlight.add(albumId)) return Result.BUSY

        return try {
            gate.withPermit {
                val meta = withContext(Dispatchers.IO) { loadMeta(context, albumId) }
                if (meta == null || !meta.hasUsableText) {
                    recordMiss(context, albumId)
                    return@withPermit Result.NO_MATCH
                }

                // 只有"确实没有匹配"才写负缓存；网络异常不写，下次进页面还能再试
                when (val lookup = OnlineArtworkClient.fetchCover(meta)) {
                    is OnlineArtworkClient.LookupResult.Found -> {
                        if (save(context, albumId, lookup.cover)) {
                            clearMiss(context, albumId)
                            com.melody.player.data.ArtworkStore.invalidate(albumId)
                            Result.FETCHED
                        } else {
                            Result.FAILED
                        }
                    }

                    OnlineArtworkClient.LookupResult.NoMatch -> {
                        recordMiss(context, albumId)
                        Result.NO_MATCH
                    }

                    is OnlineArtworkClient.LookupResult.Failed -> Result.FAILED
                }
            }
        } catch (throwable: Exception) {
            Log.w(TAG, "ensure failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
            Result.FAILED
        } finally {
            inFlight.remove(albumId)
        }
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir(context).listFiles()?.forEach { runCatching { it.delete() } }
            entries.clear()
            misses.clear()
            loaded = false
            com.melody.player.data.ArtworkStore.invalidateAll()
        }
    }

    // ------------------------------------------------------------------ 落盘

    private suspend fun save(
        context: Context,
        albumId: Long,
        cover: OnlineArtworkClient.FetchedCover,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = dir(context).resolve("$albumId.jpg")
            file.writeBytes(cover.bytes)
            mutex.withLock {
                ensureLoaded(context)
                val now = System.currentTimeMillis()
                entries[albumId] = Entry(
                    source = cover.hit.source,
                    bytes = cover.bytes.size.toLong(),
                    writtenAt = now,
                    lastAccess = now,
                )
                trimLocked(context)
                persistLocked(context)
            }
            true
        }.getOrElse {
            Log.w(TAG, "save cover failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /** 超过容量/条目数时按最近访问时间淘汰，同时删掉图片文件。 */
    private fun trimLocked(context: Context) {
        if (entries.size <= MAX_ENTRIES && entries.values.sumOf { it.bytes } <= MAX_BYTES) return
        val ordered = entries.entries.sortedBy { it.value.lastAccess }
        var total = entries.values.sumOf { it.bytes }
        var count = entries.size
        for ((id, entry) in ordered) {
            if (count <= MAX_ENTRIES && total <= MAX_BYTES) break
            entries.remove(id)
            total -= entry.bytes
            count--
            runCatching { dir(context).resolve("$id.jpg").delete() }
        }
    }

    // ------------------------------------------------------------------ 索引

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), INDEX_NAME)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val file = indexFile(context)
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            root.optJSONObject("entries")?.let { obj ->
                obj.keys().forEach { key ->
                    val id = key.toLongOrNull() ?: return@forEach
                    val item = obj.optJSONObject(key) ?: return@forEach
                    entries[id] = Entry(
                        source = item.optString("source"),
                        bytes = item.optLong("bytes", 0L),
                        writtenAt = item.optLong("writtenAt", 0L),
                        lastAccess = item.optLong("lastAccess", 0L),
                    )
                }
            }
            // 旧版本索引：匹配逻辑已经不同，负缓存不再可信，直接丢弃重试
            if (root.optInt("version", 1) < INDEX_VERSION) {
                Log.i(TAG, "index version upgraded, dropping stale misses")
                return@runCatching
            }
            root.optJSONObject("misses")?.let { obj ->
                obj.keys().forEach { key ->
                    val id = key.toLongOrNull() ?: return@forEach
                    misses[id] = obj.optLong(key, 0L)
                }
            }
        }.onFailure { Log.w(TAG, "index broken, rebuilding") }
    }

    private fun persistLocked(context: Context) {
        runCatching {
            val root = JSONObject()
            val entryObj = JSONObject()
            entries.forEach { (id, e) ->
                entryObj.put(
                    id.toString(),
                    JSONObject()
                        .put("source", e.source)
                        .put("bytes", e.bytes)
                        .put("writtenAt", e.writtenAt)
                        .put("lastAccess", e.lastAccess),
                )
            }
            val missObj = JSONObject()
            misses.forEach { (id, ts) -> missObj.put(id.toString(), ts) }
            root.put("version", INDEX_VERSION).put("entries", entryObj).put("misses", missObj)
            indexFile(context).writeText(root.toString())
        }.onFailure { Log.w(TAG, "persist index failed: ${it.javaClass.simpleName}") }
    }

    private suspend fun hasFreshMiss(context: Context, albumId: Long): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded(context)
                val ts = misses[albumId] ?: return@withLock false
                System.currentTimeMillis() - ts < MISS_TTL_MS
            }
        }

    private suspend fun recordMiss(context: Context, albumId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(context)
            misses[albumId] = System.currentTimeMillis()
            persistLocked(context)
        }
    }

    private suspend fun clearMiss(context: Context, albumId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (misses.remove(albumId) != null) persistLocked(context)
        }
    }

    // ------------------------------------------------------------------ 元信息

    /** 从 MediaStore 取一张专辑的代表性元信息（标题/艺术家/专辑名）。 */
    fun loadMeta(context: Context, albumId: Long): AlbumMeta? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
        )
        // 负数 id 是"无标签单曲"的合成专辑（-trackId），按曲目查；正数按专辑查
        val byTrack = albumId < 0L
        val selection = if (byTrack) {
            "${MediaStore.Audio.Media._ID} = ?"
        } else {
            "${MediaStore.Audio.Media.ALBUM_ID} = ?"
        }
        val argument = if (byTrack) (-albumId).toString() else albumId.toString()

        var best: AlbumMeta? = null
        runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(argument),
                null,
            )?.use { cursor ->
                val limit = minOf(cursor.count, 8)
                for (i in 0 until limit) {
                    if (!cursor.moveToPosition(i)) break
                    val rawTitle = cursor.getString(0).orEmpty()
                    val rawArtist = cleanTag(cursor.getString(1))
                    val rawAlbum = cleanTag(cursor.getString(2))
                    val path = cursor.getString(3)

                    // 没有歌手标签时按文件名拆「歌名 - 艺术家」。缺了这一步，
                    // 无标签文件的 title/artist/album 全空，hasUsableText 为 false，
                    // 一次网络请求都不会发出去（就是这么搜不到封面的）。
                    val variants = if (rawArtist.isBlank()) {
                        FileNameMeta.guesses(path ?: rawTitle)
                    } else {
                        emptyList()
                    }
                    val first = variants.firstOrNull()
                    val useGuess = first != null && (rawTitle.isBlank() || first.artist.isNotBlank())
                    val title = if (useGuess) first!!.title else rawTitle
                    val artist = if (useGuess && first!!.artist.isNotBlank()) first.artist else rawArtist

                    val candidate = AlbumMeta(
                        albumId = albumId,
                        album = rawAlbum,
                        artist = artist,
                        trackTitle = title,
                        guessVariants = variants,
                    )
                    if (best == null || (!best!!.hasUsableText && candidate.hasUsableText)) {
                        best = candidate
                    }
                    if (candidate.album.isNotBlank() && candidate.artist.isNotBlank()) break
                }
            }
        }.onFailure { Log.w(TAG, "meta query failed: ${it.javaClass.simpleName}") }
        return best
    }

    private fun cleanTag(value: String?): String =
        value.orEmpty().takeUnless { it.equals("<unknown>", ignoreCase = true) }.orEmpty()
}
