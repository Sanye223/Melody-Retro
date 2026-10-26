package com.melody.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import com.melody.player.data.model.Album
import com.melody.player.data.model.Artist
import com.melody.player.ui.LocalPlayerActions
import com.melody.player.ui.LocalPlayerState
import com.melody.player.ui.NoInsets
import com.melody.player.ui.components.AlbumArt
import com.melody.player.ui.components.SongRow
import com.melody.player.util.formatSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(album: Album?, onBack: () -> Unit) {
    val actions = LocalPlayerActions.current
    val playerState = LocalPlayerState.current

    if (album == null) {
        DetailLoading(onBack)
        return
    }

    val totalMs = album.tracks.sumOf { it.durationMs }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = {
                    Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                DetailHeader(
                    albumId = album.id,
                    title = album.title,
                    subtitle = album.artist,
                    meta = formatSummary(album.tracks.size, totalMs),
                    circle = false,
                    onPlay = { actions.playQueue(album.tracks, 0) },
                    onShuffle = { actions.shufflePlay(album.tracks) },
                )
            }
            itemsIndexed(album.tracks, key = { _, track -> track.id }) { index, track ->
                SongRow(
                    track = track,
                    isCurrent = playerState.current?.id == track.id,
                    isPlaying = playerState.isPlaying,
                    onClick = { actions.playQueue(album.tracks, index) },
                    showAlbum = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(artist: Artist?, onBack: () -> Unit) {
    val actions = LocalPlayerActions.current
    val playerState = LocalPlayerState.current

    if (artist == null) {
        DetailLoading(onBack)
        return
    }

    val totalMs = artist.tracks.sumOf { it.durationMs }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                DetailHeader(
                    albumId = artist.tracks.firstOrNull()?.albumId ?: 0L,
                    title = artist.name,
                    subtitle = "${artist.albumCount} 张专辑",
                    meta = formatSummary(artist.tracks.size, totalMs),
                    circle = true,
                    onPlay = { actions.playQueue(artist.tracks, 0) },
                    onShuffle = { actions.shufflePlay(artist.tracks) },
                )
            }
            itemsIndexed(artist.tracks, key = { _, track -> track.id }) { index, track ->
                SongRow(
                    track = track,
                    isCurrent = playerState.current?.id == track.id,
                    isPlaying = playerState.isPlaying,
                    onClick = { actions.playQueue(artist.tracks, index) },
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    albumId: Long,
    title: String,
    subtitle: String,
    meta: String,
    circle: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArt(
            albumId = albumId,
            modifier = Modifier.size(208.dp),
            shape = if (circle) CircleShape else RoundedCornerShape(24.dp),
            iconFraction = if (circle) 0.4f else 0.26f,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = meta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onShuffle) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("随机播放")
            }
            Button(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("播放全部")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailLoading(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
