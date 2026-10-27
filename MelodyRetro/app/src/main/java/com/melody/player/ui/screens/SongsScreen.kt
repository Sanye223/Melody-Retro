package com.melody.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
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
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val focusRequester = remember { FocusRequester() }

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

    fun closeSearch() {
        searchOpen = false
        query = ""
    }

    // 展开搜索就聚焦并弹键盘；收起时 query 一并清掉
    LaunchedEffect(searchOpen) {
        if (searchOpen) focusRequester.requestFocus()
    }
    BackHandler(enabled = searchOpen) { closeSearch() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            // 顶栏常驻：滚动只把它压成小标题，不会滑走
            LargeTopAppBar(
                title = { Text("我的音乐") },
                actions = {
                    IconButton(onClick = { if (searchOpen) closeSearch() else searchOpen = true }) {
                        Icon(
                            imageVector = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                        )
                    }
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
            AnimatedVisibility(
                visible = searchOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = ::closeSearch,
                    focusRequester = focusRequester,
                )
            }

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

/** MD3 填充式搜索栏：无描边、全圆角、输入框与清除按钮在同一行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        placeholder = { Text("搜索歌曲、专辑或艺术家") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(
                onClick = {
                    if (query.isNotEmpty()) {
                        onQueryChange("")
                    } else {
                        focusManager.clearFocus()
                        onClose()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = if (query.isNotEmpty()) "清除" else "关闭搜索",
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}
