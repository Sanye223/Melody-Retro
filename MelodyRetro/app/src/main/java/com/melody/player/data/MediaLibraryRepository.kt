package com.melody.player.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.melody.player.data.model.Album
import com.melody.player.data.model.Artist
import com.melody.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class LibraryData(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

/**
 * 通过 MediaStore 扫描设备中的本地音频（MP3 / FLAC / M4A / OGG / WAV ...），
 * 并派生出专辑、艺术家两类聚合数据。
 */
class MediaLibraryRepository(private val context: Context) {

    suspend fun load(sortOrder: Int): LibraryData = withContext(Dispatchers.IO) {
        val tracks = queryTracks()
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)

        val sorted = when (sortOrder) {
            SORT_RECENT -> tracks.sortedByDescending { it.dateAdded }
            SORT_ARTIST -> tracks.sortedWith(
                compareBy<Track, String>(collator) { it.artist }.thenBy(collator) { it.title }
            )
            else -> tracks.sortedWith(compareBy(collator) { it.title })
        }

        val albums = tracks
            .groupBy { it.albumId }
            .map { (albumId, list) ->
                Album(
                    id = albumId,
                    title = list.first().album,
                    artist = list.groupingBy { it.artist }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key
                        ?: list.first().artist,
                    year = list.maxOfOrNull { it.year } ?: 0,
                    tracks = list.sortedWith(
                        compareBy<Track> { track -> track.trackNumber }.thenBy(collator) { it.title }
                    ),
                )
            }
            .sortedWith(compareBy(collator) { it.title })

        val artists = tracks
            .groupBy { it.artistId }
            .map { (artistId, list) ->
                Artist(
                    id = artistId,
                    name = list.first().artist,
                    tracks = list.sortedWith(compareBy(collator) { it.title }),
                    albumCount = list.map { it.albumId }.distinct().size,
                )
            }
            .sortedWith(compareBy(collator) { it.name })

        LibraryData(sorted, albums, artists)
    }

    private fun queryTracks(): List<Track> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
        )

        val result = ArrayList<Track>(256)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val duration = cursor.getLong(durationCol)
                // 过滤铃声、提示音等过短音频
                if (duration < MIN_DURATION_MS) continue

                val rawTitle = cursor.getString(titleCol).orEmpty()
                val rawArtist = cursor.getString(artistCol)
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    .orEmpty()
                val rawAlbum = cursor.getString(albumCol)
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    .orEmpty()
                val path = if (cursor.isNull(dataCol)) null else cursor.getString(dataCol)
                val rawAlbumId = if (cursor.isNull(albumIdCol)) 0L else cursor.getLong(albumIdCol)
                val rawArtistId = if (cursor.isNull(artistIdCol)) 0L else cursor.getLong(artistIdCol)

                // 有的合辑碟会把 ARTIST 压成歌名（实测《倒带》的 artist 就是"倒带"），
                // 这种标签没有任何信息量：列表会显示成"倒带 · 倒带"，
                // 在线搜歌词也会拼出"倒带 倒带"。判为脏标签，一并走文件名兜底。
                val artistTagDirty = rawArtist.isNotBlank() &&
                    rawArtist.trim().equals(rawTitle.trim(), ignoreCase = true)

                // 没有 ID3 标签的文件（下载来的 MP3 很常见）标签全空，
                // 这时按文件名拆「歌名 - 艺术家」，否则列表里全是"未知曲目/未知艺术家"，
                // 在线歌词和在线封面也没法查。
                val guessed = if (rawArtist.isEmpty()) {
                    FileNameMeta.guesses(path ?: rawTitle).firstOrNull()
                } else if (artistTagDirty) {
                    // 标签是歌名：用文件名纠正。但方向要先判 —— 文件名既可能是
                    // 「歌名 - 歌手」也可能是「歌手 - 歌名」（实测《倒带》是后者：
                    // "蔡依林 - 倒带.flac"），取那一半等于原歌名的做锚点。
                    FileNameMeta.guesses(path ?: rawTitle).firstOrNull { g ->
                        g.title.trim().equals(rawTitle.trim(), ignoreCase = true) ||
                            g.artist.trim().equals(rawTitle.trim(), ignoreCase = true)
                    }
                } else {
                    null
                }
                val useGuess = guessed != null &&
                    (rawTitle.isBlank() || guessed.artist.isNotBlank())

                // 拆出来的「artist」半段正好是原歌名 → 文件名其实是「歌手 - 歌名」
                val guessReversed = guessed != null && rawTitle.isNotBlank() &&
                    guessed.artist.trim().equals(rawTitle.trim(), ignoreCase = true)

                val title = if (useGuess && !guessReversed) guessed!!.title else rawTitle
                val artist = when {
                    // 反序：前一半才是歌手
                    guessReversed -> guessed!!.title
                    useGuess && guessed!!.artist.isNotBlank() -> guessed.artist
                    // 标签是歌名、文件名也拆不出来：宁可显示"未知艺术家"，
                    // 也不要显示"倒带 · 倒带"
                    artistTagDirty -> ""
                    else -> rawArtist
                }

                result += Track(
                    id = id,
                    title = title.takeIf { it.isNotBlank() } ?: "未知曲目",
                    artist = artist.takeIf { it.isNotBlank() } ?: "未知艺术家",
                    // 没有 artistId 时按艺术家名字造一个稳定的负数 id，
                    // 否则所有"未知艺术家"会被并成一组、显示成第一个人的名字。
                    artistId = if (rawArtistId > 0L) rawArtistId else syntheticId(artist),
                    // 没有专辑标签时当成"单曲专辑"，标题用歌名
                    album = rawAlbum.takeIf { it.isNotBlank() } ?: title.ifBlank { "未知专辑" },
                    // 没有有效 albumId 时给每首歌一个稳定的负数 id（-trackId），
                    // 这样封面可以按曲目缓存，而不是所有无标签文件共用一张图
                    albumId = if (rawAlbumId > 0L) rawAlbumId else -id,
                    durationMs = duration,
                    trackNumber = cursor.getInt(trackCol) % 1000,
                    year = cursor.getInt(yearCol),
                    path = path,
                    uriString = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ).toString(),
                    dateAdded = cursor.getLong(dateCol) * 1000L,
                )
            }
        }
        return result
    }

    companion object {
        /** 负数合成 id：名字 → 稳定的负数，永远不会和 MediaStore 的真实 id 撞上。 */
        internal fun syntheticId(key: String): Long {
            val text = key.ifBlank { "未知" }
            return -((text.hashCode().toLong() and 0x7FFFFFFFL) + 1L)
        }

        const val SORT_TITLE = 0
        const val SORT_RECENT = 1
        const val SORT_ARTIST = 2

        private const val MIN_DURATION_MS = 15_000L
    }
}
