package com.melody.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.melody.player.ui.LibraryUiState
import com.melody.player.ui.LocalPlayerActions
import com.melody.player.ui.LocalPlayerState
import com.melody.player.ui.NoInsets
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.ErrorState
import com.melody.player.ui.components.LoadingState
import com.melody.player.ui.components.SongRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(
    state: LibraryUiState,
    onOpenSettings: () -> Unit,
    onRescan: () -> Unit,
) {
    val playerState = LocalPlayerState.current
    val actions = LocalPlayerActions.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(state.tracks, query) {
        if (query.isBlank()) {
            state.tracks
        } else {
            state.tracks.filter { track ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artist.contains(query, ignoreCase = true) ||
                    track.album.contains(query, ignoreCase = true)
            }
        }
    }

    val clearSearch: (@Composable () -> Unit)? = if (query.isNotEmpty()) {
        {
            IconButton(onClick = { query = "" }) {
                Icon(Icons.Rounded.Close, contentDescription = "清除")
            }
        }
    } else {
        null
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            LargeTopAppBar(
                title = { Text("我的音乐") },
                actions = {
                    IconButton(onClick = onRescan) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (filtered.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { actions.shufflePlay(filtered) },
                    icon = { Icon(Icons.Rounded.Shuffle, contentDescription = null) },
                    text = { Text("随机播放") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索歌曲、专辑或艺术家") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = clearSearch,
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )

            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(message = state.error, onRetry = onRescan)
                filtered.isEmpty() -> EmptyState(
                    noSearchResult = query.isNotBlank(),
                    onRescan = onRescan,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    itemsIndexed(filtered, key = { _, track -> track.id }) { index, track ->
                        SongRow(
                            track = track,
                            isCurrent = playerState.current?.id == track.id,
                            isPlaying = playerState.isPlaying,
                            onClick = { actions.playQueue(filtered, index) },
                        )
                    }
                }
            }
        }
    }
}
