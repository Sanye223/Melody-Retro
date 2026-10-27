package com.melody.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.melody.player.data.PlayStat
import com.melody.player.data.model.Album
import com.melody.player.data.model.Artist
import com.melody.player.data.model.Track
import com.melody.player.ui.LibraryUiState
import com.melody.player.ui.NoInsets
import com.melody.player.ui.PlayerActions
import com.melody.player.ui.components.AlbumArt
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.LoadingState
import java.util.Calendar
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.ButtonColors
import com.melody.player.ui.theme.rememberRetroColors

/**
 * 「For you」首页，对齐 RetroMusicPlayer 的 HomeFragment：
 * 横幅 + 欢迎语、四个快捷播放按钮、随机八首拼贴（Suggestions）、
 * 以及按播放次数 / 最近播放排出来的「最多播放」「最近播放」专辑与艺术家分组。
 *
 * 上游把统计存在 SQLite 的 play_count / last_played；这里用 DataStore 的
 * 播放统计（PlayStatsRepository），聚合逻辑一样：按曲目聚合到专辑 / 艺术家。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    state: LibraryUiState,
    stats: Map<Long, PlayStat>,
    actions: PlayerActions,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Suggestions：随机八首，点刷新换一批
    var suggestionSeed by remember { mutableIntStateOf(0) }
    val suggestions = remember(state.tracks, suggestionSeed) {
        state.tracks.shuffled().take(8)
    }

    val recentTracks = remember(state.tracks, stats) {
        state.tracks
            .filter { (stats[it.id]?.lastPlayedAt ?: 0L) > 0L }
            .sortedByDescending { stats[it.id]?.lastPlayedAt ?: 0L }
    }
    val topTracks = remember(state.tracks, stats) {
        state.tracks
            .filter { (stats[it.id]?.count ?: 0) > 0 }
            .sortedByDescending { stats[it.id]?.count ?: 0 }
    }
    val recentAdded = remember(state.tracks) {
        state.tracks.sortedByDescending { it.dateAdded }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            // 上游标题是「Retro Music」且 Music 用强调色，这里同样分色
            LargeTopAppBar(
                title = {
                    Text(
                        text = buildAnnotatedString {
                            append("Melody ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                append("Retro")
                            }
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.tracks.isEmpty() -> EmptyState(
                noSearchResult = false,
                onRescan = {},
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                // ---------------------------------------------------------- 横幅
                item {
                    val bannerTrack = recentTracks.firstOrNull()
                        ?: suggestions.firstOrNull()
                        ?: state.tracks.first()
                    HomeBanner(
                        track = bannerTrack,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                // ---------------------------------------------------------- 快捷按钮
                item {
                    HomeButtons(
                        hasHistory = recentTracks.isNotEmpty(),
                        history = recentTracks,
                        lastAdded = recentAdded,
                        topTracks = topTracks,
                        all = state.tracks,
                        actions = actions,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                        ),
                    )
                }

                // ---------------------------------------------------------- Suggestions
                if (suggestions.size >= 4) {
                    item {
                        Column(modifier = Modifier.padding(top = 20.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "新音乐混音",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { suggestionSeed++ }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "换一批")
                                }
                            }
                            SuggestionGrid(
                                tracks = suggestions,
                                actions = actions,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                // ---------------------------------------------------------- 分组
                val sections = buildHomeSections(state, stats)
                sections.forEach { section ->
                    item(key = section.title) {
                        SectionHeader(title = section.title)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (section.albums.isNotEmpty()) {
                                items(section.albums, key = { "a${section.title}${it.id}" }) { album ->
                                    AlbumMiniCard(
                                        album = album,
                                        onClick = { onOpenAlbum(album.id) },
                                    )
                                }
                            } else {
                                items(section.artists, key = { "r${section.title}${it.id}" }) { artist ->
                                    ArtistMiniCard(
                                        artist = artist,
                                        onClick = { onOpenArtist(artist.id) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/** 分区数据：一个分组要么是专辑列表，要么是艺术家列表（上游两者都不会同时给）。 */
internal data class HomeSectionData(
    val title: String,
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

internal fun buildHomeSections(state: LibraryUiState, stats: Map<Long, PlayStat>): List<HomeSectionData> {
    val out = mutableListOf<HomeSectionData>()

    fun scoreOf(tracks: List<Track>, pick: (PlayStat) -> Long): Long =
        tracks.maxOfOrNull { stats[it.id]?.let(pick) ?: 0L } ?: 0L

    val topArtists = state.artists
        .filter { scoreOf(it.tracks) { s -> s.count.toLong() } > 0L }
        .sortedByDescending { scoreOf(it.tracks) { s -> s.count.toLong() } }
        .take(10)
    val topAlbums = state.albums
        .filter { scoreOf(it.tracks) { s -> s.count.toLong() } > 0L }
        .sortedByDescending { scoreOf(it.tracks) { s -> s.count.toLong() } }
        .take(10)
    val recentArtists = state.artists
        .filter { scoreOf(it.tracks) { s -> s.lastPlayedAt } > 0L }
        .sortedByDescending { scoreOf(it.tracks) { s -> s.lastPlayedAt } }
        .take(10)
    val recentAlbums = state.albums
        .filter { scoreOf(it.tracks) { s -> s.lastPlayedAt } > 0L }
        .sortedByDescending { scoreOf(it.tracks) { s -> s.lastPlayedAt } }
        .take(10)

    if (topArtists.isNotEmpty()) out += HomeSectionData("最多播放的艺术家", artists = topArtists)
    if (topAlbums.isNotEmpty()) out += HomeSectionData("最多播放的专辑", albums = topAlbums)
    if (recentArtists.isNotEmpty()) out += HomeSectionData("最近播放的艺术家", artists = recentArtists)
    if (recentAlbums.isNotEmpty()) out += HomeSectionData("最近播放的专辑", albums = recentAlbums)
    return out
}

/** 顶部横幅：用最近播放专辑的主色铺底，圆形头像显示首字母，附问候语。 */
@Composable
private fun HomeBanner(track: Track, modifier: Modifier = Modifier) {
    val retro = rememberRetroColors(track.albumId)
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "早上好"
        in 12..17 -> "下午好"
        else -> "晚上好"
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(retro.gradientTop, retro.accent.copy(alpha = 0.72f)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.titleMedium,
                color = retro.onAccent.copy(alpha = 0.85f),
            )
            Text(
                text = track.artist.ifBlank { "Melody Retro" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = retro.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 头像：上游是用户设置的头像，这里用歌手首字母占位
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(retro.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = track.artist.trim().firstOrNull()?.toString() ?: "M",
                style = MaterialTheme.typography.titleLarge,
                color = retro.onAccent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 2×2 快捷播放按钮（历史 / 最近添加 / 我的最爱 / 随机播放）。 */
@Composable
private fun HomeButtons(
    hasHistory: Boolean,
    history: List<Track>,
    lastAdded: List<Track>,
    topTracks: List<Track>,
    all: List<Track>,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
    Column(modifier = modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(
                text = "历史",
                icon = Icons.Rounded.History,
                colors = colors,
                enabled = hasHistory,
                onClick = { actions.playQueue(history, 0) },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                text = "最近添加",
                icon = Icons.Rounded.LibraryAdd,
                colors = colors,
                enabled = lastAdded.isNotEmpty(),
                onClick = { actions.playQueue(lastAdded, 0) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(
                text = "我的最爱",
                icon = Icons.Rounded.TrendingUp,
                colors = colors,
                enabled = topTracks.isNotEmpty(),
                onClick = { actions.playQueue(topTracks, 0) },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                text = "随机播放",
                icon = Icons.Rounded.Shuffle,
                colors = colors,
                enabled = all.isNotEmpty(),
                onClick = { actions.shufflePlay(all) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: ButtonColors,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        modifier = modifier.height(44.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 4×2 封面拼贴，第 6 格是「点击整批播放」的文案卡（上游同样留了这一格）。 */
@Composable
private fun SuggestionGrid(
    tracks: List<Track>,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 4) {
                    val index = row * 4 + col
                    val track = tracks.getOrNull(index)
                    if (index == 5 || track == null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (index == 5) "一起听\n这 8 首" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .clickable(enabled = index == 5) {
                                        actions.playQueue(tracks.take(8), 5)
                                    }
                                    .padding(6.dp),
                            )
                        }
                    } else {
                        AlbumArt(
                            albumId = track.albumId,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { actions.playQueue(tracks.take(8), index) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AlbumMiniCard(album: Album, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(152.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

@Composable
private fun ArtistMiniCard(artist: Artist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${artist.albumCount} 张专辑",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
