package com.melody.player

import com.melody.player.data.PlayStat
import com.melody.player.data.model.Album
import com.melody.player.data.model.Artist
import com.melody.player.data.model.Track
import com.melody.player.ui.screens.buildHomeSections
import com.melody.player.ui.LibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** For you 首页的分组聚合：按播放次数 / 最近播放把曲目聚合到专辑与艺术家。 */
class ForYouSectionsTest {

    private fun track(id: Long, albumId: Long, artistId: Long, added: Long = 0L) = Track(
        id = id,
        title = "t$id",
        artist = "ar$artistId",
        artistId = artistId,
        album = "al$albumId",
        albumId = albumId,
        durationMs = 0L,
        trackNumber = 1,
        year = 0,
        path = null,
        uriString = "content://x/$id",
        dateAdded = added,
    )

    @Test
    fun `没有任何播放记录时分组为空`() {
        val t = track(1, 100, 200)
        val state = LibraryUiState(
            tracks = listOf(t),
            albums = listOf(Album(100, "a", "ar", 0, listOf(t))),
            artists = listOf(Artist(200, "ar", listOf(t), 1)),
        )
        assertTrue(buildHomeSections(state, emptyMap()).isEmpty())
    }

    @Test
    fun `最多播放按累计次数排序`() {
        val a1 = track(1, 100, 200)
        val a2 = track(2, 100, 200)
        val b1 = track(3, 300, 400)
        val albums = listOf(
            Album(100, "常听", "ar", 0, listOf(a1, a2)),
            Album(300, "少听", "ar", 0, listOf(b1)),
        )
        val artists = listOf(Artist(200, "ar", listOf(a1, a2, b1), 2))
        val state = LibraryUiState(
            tracks = listOf(a1, a2, b1),
            albums = albums,
            artists = artists,
        )
        // 专辑 100：两首各 3 次 → 记 6 次（取最大值口径与上游一致，按单首最高计）
        val stats = mapOf(
            1L to PlayStat(3, 100L),
            2L to PlayStat(3, 90L),
            3L to PlayStat(1, 200L),
        )
        val sections = buildHomeSections(state, stats)
        val topAlbums = sections.first { it.title == "最多播放的专辑" }.albums
        assertEquals(100L, topAlbums.first().id)
        val topArtists = sections.first { it.title == "最多播放的艺术家" }.artists
        assertEquals(1, topArtists.size)
    }

    @Test
    fun `最近播放按最后时间排序`() {
        val t1 = track(1, 100, 200)
        val t2 = track(2, 300, 400)
        val state = LibraryUiState(
            tracks = listOf(t1, t2),
            albums = listOf(
                Album(100, "a", "ar", 0, listOf(t1)),
                Album(300, "b", "ar", 0, listOf(t2)),
            ),
            artists = listOf(
                Artist(200, "ar", listOf(t1), 1),
                Artist(400, "ar2", listOf(t2), 1),
            ),
        )
        val stats = mapOf(
            1L to PlayStat(1, 1000L),
            2L to PlayStat(1, 5000L),
        )
        val sections = buildHomeSections(state, stats)
        val recentAlbums = sections.first { it.title == "最近播放的专辑" }.albums
        assertEquals(300L, recentAlbums.first().id)
        val recentArtists = sections.first { it.title == "最近播放的艺术家" }.artists
        assertEquals(400L, recentArtists.first().id)
    }
}
