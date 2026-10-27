package com.melody.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.data.PlayStat
import com.melody.player.ui.LibraryUiState
import com.melody.player.ui.NoInsets
import com.melody.player.ui.PlayerActions
import com.melody.player.ui.components.AlbumArt
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.SongRow
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * 列表详情页：For you 的快捷按钮与分组标题点进来的地方
 * （对应上游的 detailListFragment，一次只显示一种列表）。
 *
 * 类型（route 上的 type 参数）：
 * - 歌曲类：history / last_added / top_tracks / favorites
 * - 专辑类：top_albums / recent_albums
 * - 艺术家类：top_artists / recent_artists
 *
 * 歌曲类每行可切换收藏；专辑 / 艺术家类点击进详情页。
 */

object ListType {
    const val HISTORY = "history"
    const val LAST_ADDED = "last_added"
    const val TOP_TRACKS = "top_tracks"
    const val FAVORITES = "favorites"
    const val TOP_ALBUMS = "top_albums"
    const val RECENT_ALBUMS = "recent_albums"
    const val TOP_ARTISTS = "top_artists"
    const val RECENT_ARTISTS = "recent_artists"

    val titles = mapOf(
        HISTORY to "历史",
        LAST_ADDED to "最近添加",
        TOP_TRACKS to "最多播放",
        FAVORITES to "我的最爱",
        TOP_ALBUMS to "最多播放的专辑",
        RECENT_ALBUMS to "最近播放的专辑",
        TOP_ARTISTS to "最多播放的艺术家",
        RECENT_ARTISTS to "最近播放的艺术家",
    )
}

internal fun tracksForList(
    type: String,
    state: LibraryUiState,
    stats: Map<Long, PlayStat>,
    historySort: Int = 0,
): List<com.melody.player.data.model.Track> = when (type) {
    ListType.HISTORY -> {
        val played = state.tracks
            .filter { (stats[it.id]?.lastPlayedAt ?: 0L) > 0L }
        if (historySort == 1) {
            played.sortedByDescending { stats[it.id]?.count ?: 0 }
        } else {
            played.sortedByDescending { stats[it.id]?.lastPlayedAt ?: 0L }
        }
    }

    ListType.LAST_ADDED -> state.tracks.sortedByDescending { it.dateAdded }
    ListType.TOP_TRACKS -> state.tracks
        .filter { (stats[it.id]?.count ?: 0) > 0 }
        .sortedByDescending { stats[it.id]?.count ?: 0 }

    ListType.FAVORITES -> state.tracks  // 由调用方按收藏集合过滤
    else -> emptyList()
}

internal fun albumsForList(
    type: String,
    state: LibraryUiState,
    stats: Map<Long, PlayStat>,
): List<com.melody.player.data.model.Album> {
    fun score(pick: (PlayStat) -> Long): Long? = null
    val scored = state.albums.map { album ->
        val maxCount = album.tracks.maxOfOrNull { stats[it.id]?.count ?: 0 } ?: 0
        val maxLast = album.tracks.maxOfOrNull { stats[it.id]?.lastPlayedAt ?: 0L } ?: 0L
        album to (maxCount to maxLast)
    }
    return when (type) {
        ListType.TOP_ALBUMS -> scored
            .filter { it.second.first > 0 }
            .sortedByDescending { it.second.first }
            .map { it.first }

        ListType.RECENT_ALBUMS -> scored
            .filter { it.second.second > 0L }
            .sortedByDescending { it.second.second }
            .map { it.first }

        else -> emptyList()
    }
}

internal fun artistsForList(
    type: String,
    state: LibraryUiState,
    stats: Map<Long, PlayStat>,
): List<com.melody.player.data.model.Artist> {
    val scored = state.artists.map { artist ->
        val maxCount = artist.tracks.maxOfOrNull { stats[it.id]?.count ?: 0 } ?: 0
        val maxLast = artist.tracks.maxOfOrNull { stats[it.id]?.lastPlayedAt ?: 0L } ?: 0L
        artist to (maxCount to maxLast)
    }
    return when (type) {
        ListType.TOP_ARTISTS -> scored
            .filter { it.second.first > 0 }
            .sortedByDescending { it.second.first }
            .map { it.first }

        ListType.RECENT_ARTISTS -> scored
            .filter { it.second.second > 0L }
            .sortedByDescending { it.second.second }
            .map { it.first }

        else -> emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    type: String,
    state: LibraryUiState,
    stats: Map<Long, PlayStat>,
    favorites: Set<Long>,
    actions: PlayerActions,
    onToggleFavorite: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onBack: () -> Unit,
    historySort: Int = 0,
    onHistorySortChange: ((Int) -> Unit)? = null,
) {
    val title = ListType.titles[type] ?: "列表"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            var menuOpen by remember { mutableStateOf(false) }
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 只有历史页提供排序切换（最近播放 / 播放次数）
                    if (type == ListType.HISTORY && onHistorySortChange != null) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "排序方式")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(if (historySort == 0) "✓ 最近播放排序" else "最近播放排序")
                                },
                                onClick = {
                                    onHistorySortChange(0)
                                    menuOpen = false
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(if (historySort == 1) "✓ 播放次数排序" else "播放次数排序")
                                },
                                onClick = {
                                    onHistorySortChange(1)
                                    menuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val isSongList = type in listOf(
            ListType.HISTORY,
            ListType.LAST_ADDED,
            ListType.TOP_TRACKS,
            ListType.FAVORITES,
        )
        when {
            isSongList -> {
                val songs = when (type) {
                    ListType.FAVORITES -> state.tracks.filter { it.id in favorites }
                    else -> tracksForList(type, state, stats, historySort)
                }
                if (songs.isEmpty()) {
                    EmptyState(
                        noSearchResult = false,
                        onRescan = {},
                        modifier = Modifier.padding(padding),
                        message = when (type) {
                            ListType.FAVORITES -> "还没有收藏的歌。\n在播放页点心形即可收藏。"
                            ListType.HISTORY -> "还没有播放记录。"
                            ListType.TOP_TRACKS -> "还没有播放记录，多听几首就有了。"
                            else -> ""
                        },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(songs, key = { it.id }) { track ->
                            SongRow(
                                track = track,
                                isCurrent = false,
                                isPlaying = false,
                                onClick = { actions.playQueue(songs, songs.indexOf(track)) },
                                isFavorite = track.id in favorites,
                                onToggleFavorite = { onToggleFavorite(track.id) },
                                // 历史页 / 收藏页 / 最多播放页显示次数，其余不显示
                                playCount = if (type == ListType.HISTORY ||
                                    type == ListType.FAVORITES ||
                                    type == ListType.TOP_TRACKS
                                ) stats[track.id]?.count ?: 0 else -1,
                            )
                        }
                    }
                }
            }

            type == ListType.TOP_ALBUMS || type == ListType.RECENT_ALBUMS -> {
                val albums = albumsForList(type, state, stats)
                if (albums.isEmpty()) {
                    ListEmpty(padding, type)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 152.dp),
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(albums, key = { it.id }) { album ->
                            Column(
                                modifier = Modifier
                                    .clickable { onOpenAlbum(album.id) }
                                    .padding(2.dp),
                            ) {
                                AlbumArt(
                                    albumId = album.id,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    iconFraction = 0.28f,
                                )
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(
                                    text = album.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                val artists = artistsForList(type, state, stats)
                if (artists.isEmpty()) {
                    ListEmpty(padding, type)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(artists, key = { it.id }) { artist ->
                            Column(
                                modifier = Modifier
                                    .clickable { onOpenArtist(artist.id) }
                                    .padding(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AlbumArt(
                                    albumId = artist.tracks.firstOrNull()?.albumId ?: 0L,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    shape = CircleShape,
                                    iconFraction = 0.4f,
                                )
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(
                                    text = "${artist.albumCount} 张专辑 · ${artist.tracks.size} 首歌",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListEmpty(padding: PaddingValues, type: String) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = "还没有数据，多听几首就有了。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
