package com.melody.player.data.model

import android.content.Context
import android.net.Uri

/**
 * 一首本地歌曲。全部字段来自 MediaStore，不依赖任何网络接口。
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val path: String?,
    val uriString: String,
    val dateAdded: Long,
) {
    val uri: Uri get() = Uri.parse(uriString)
    val artworkUri: Uri get() = ArtworkUris.forAlbum(albumId)
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val tracks: List<Track>,
) {
    val artworkUri: Uri get() = ArtworkUris.forAlbum(id)
}

data class Artist(
    val id: Long,
    val name: String,
    val tracks: List<Track>,
    val albumCount: Int,
) {
    val artworkUri: Uri? get() = tracks.firstOrNull()?.let { ArtworkUris.forAlbum(it.albumId) }
}

/** 一行歌词。[timeMs] 为 -1 表示没有时间轴（纯文本歌词）。 */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
) {
    /** 二分查找当前播放时间对应的歌词行下标，找不到返回 -1。 */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

/** 歌词来源：同名 .lrc 文件 / 在线歌词库 / 没有找到。 */
enum class LyricsSource { LOCAL_FILE, ONLINE, NONE }

data class LyricsResult(
    val lyrics: Lyrics?,
    val source: LyricsSource,
    /** 在线歌词命中的曲目信息，例如 "稻香 - 周杰伦"，用于界面提示。 */
    val matched: String? = null,
    /** 命中该歌词的数据源，例如 "QQ音乐" / "LRCLIB"。 */
    val provider: String? = null,
    /** 失败原因（网络异常等），仅在 [lyrics] 为空时有意义。 */
    val message: String? = null,
) {
    val isEmpty: Boolean get() = lyrics == null || lyrics.lines.isEmpty()
}

/** 查询一次歌词所需的上下文信息。 */
data class LyricsQuery(
    val id: Long,
    val path: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
)

/**
 * 专辑封面统一走应用自己的 ContentProvider（content://<package>.artwork/album/<albumId>），
 * 这样 Coil 与 Media3 的播放通知都能用同一个 Uri 取到封面。
 */
object ArtworkUris {
    private var authority: String = ""

    fun init(context: Context) {
        authority = "${context.packageName}.artwork"
    }

    /**
     * @param version 在线补全成功后递增，用 query 参数把 Coil 的缓存 key 换掉，
     *                否则它会一直返回上一轮"没有封面"的结果。
     */
    fun forAlbum(albumId: Long, version: Int = 0): Uri = Uri.parse(
        if (version <= 0) "content://$authority/album/$albumId"
        else "content://$authority/album/$albumId?v=$version"
    )
}
