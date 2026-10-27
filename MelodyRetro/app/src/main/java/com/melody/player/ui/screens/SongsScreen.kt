package com.melody.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melody.player.ui.LibraryUiState
import com.melody.player.ui.LocalPlayerActions
import com.melody.player.ui.LocalPlayerState
import com.melody.player.ui.NoInsets
import com.melody.player.ui.components.EmptyState
import com.melody.player.ui.components.ErrorState
import com.melody.player.ui.components.LoadingState
import com.melody.player.ui.components.SongRow
import kotlinx.coroutines.launch

private val HeaderRowHeight = 56.dp
private val SearchBarHeight = 56.dp
private val HeaderGap = 8.dp
private val SidePadding = 16.dp
private val IconButtonSize = 48.dp
private val IconRowEndPadding = 4.dp
private val IconGap = 4.dp
private val SearchBarCorner = 28.dp

/** 展开 → 收缩的滚动距离：正好是「图标行下方的搜索栏 + 间距」那段高度。 */
private val CollapseDistance = SearchBarHeight + HeaderGap

/**
 * 滚动量 → 收缩进度（0 展开，1 收成圆形按钮）。
 *
 * 用滚动距离而不是布尔状态，是为了让搜索栏跟着手指连续变形：
 * 滚多少收多少，回滚就原路展开，没有「播一段动画再追上」的迟滞。
 */
internal fun searchCollapseAt(travelledPx: Float, distancePx: Float): Float {
    if (distancePx <= 0f) return 0f
    return (travelledPx / distancePx).coerceIn(0f, 1f)
}

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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // 收缩进度直接由滚动量驱动：手指走多少，搜索栏收多少，松手跟惯性一起走，
    // 所以全程跟手、没有「先播一段动画再跟上」的迟滞感。
    val collapseDistancePx = with(LocalDensity.current) { CollapseDistance.toPx() }
    val scrollCollapse by remember(collapseDistancePx) {
        derivedStateOf {
            val travelled = if (listState.firstVisibleItemIndex > 0) {
                collapseDistancePx
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
            searchCollapseAt(travelled, collapseDistancePx)
        }
    }
    // 正在筛选时不收起来 —— 否则打字打到一半搜索框会自己飞走
    val collapse = if (query.isBlank()) scrollCollapse else 0f

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
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
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(message = state.error, onRetry = onRescan)
                filtered.isEmpty() -> EmptyState(
                    noSearchResult = query.isNotBlank(),
                    onRescan = onRescan,
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 顶部只给「收缩后」的图标行留位；展开时多出来的那段由首项 spacer 撑开，
                    // 滚动时 spacer 自己滚走，头部高度不用变 —— 列表不会因为头部收缩而重排。
                    contentPadding = PaddingValues(
                        top = statusInset + HeaderRowHeight,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.Top,
                ) {
                    item(key = "__search_gap__") {
                        Spacer(modifier = Modifier.height(CollapseDistance))
                    }
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

            SearchHeader(
                collapse = collapse,
                statusInset = statusInset,
                query = query,
                onQueryChange = { query = it },
                onRescan = onRescan,
                onOpenSettings = onOpenSettings,
                onExpand = {
                    // 点收缩后的圆形按钮：把列表滚回顶部，搜索栏自己展开
                    scope.launch { listState.animateScrollToItem(0) }
                },
            )
        }
    }
}

/**
 * 折叠搜索栏（MD3 docked search bar 的收缩形态）。
 *
 * 展开时是图标行下方的一条全宽搜索栏；列表往上滚，它**同时**上移到右上角并收成 56dp 的圆。
 * 位置与宽度都由 [collapse]（0→1）连续插值，没有状态切换，所以不会出现「跳一下」。
 * 高度恒为 56dp、圆角恒为 28dp，缩到最小时正好是正圆，中途始终是合法胶囊形状。
 */
@Composable
private fun SearchHeader(
    collapse: Float,
    statusInset: Dp,
    query: String,
    onQueryChange: (String) -> Unit,
    onRescan: () -> Unit,
    onOpenSettings: () -> Unit,
    onExpand: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fullWidth = maxWidth
        // 收缩后停在刷新按钮左侧：右起 4dp 边距 + 设置 48 + 刷新 48 + 间隔 4
        val collapsedX = fullWidth - IconRowEndPadding - IconButtonSize * 2 - IconGap - SearchBarHeight
        val expandedX = SidePadding
        val expandedWidth = fullWidth - SidePadding * 2

        val x = expandedX + (collapsedX - expandedX) * collapse
        val y = statusInset + CollapseDistance * (1f - collapse)
        val width = expandedWidth + (SearchBarHeight - expandedWidth) * collapse

        // 图标行固定不动：收缩后的搜索圆并到它左边，看起来像「收进标题栏」
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusInset)
                .height(HeaderRowHeight)
                .padding(horizontal = IconRowEndPadding),
            horizontalArrangement = Arrangement.spacedBy(IconGap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRescan, modifier = Modifier.size(IconButtonSize)) {
                Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描")
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(IconButtonSize)) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置")
            }
        }

        Box(
            modifier = Modifier
                .offset(x = x, y = y)
                .size(width = width, height = SearchBarHeight)
                .clip(RoundedCornerShape(SearchBarCorner))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = collapse > 0.5f, onClick = onExpand),
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxSize()
                    // 宽高比一被压窄就先淡出文字，避免看到「文字被挤扁」的过程
                    .graphicsLayer { alpha = (1f - collapse * 3f).coerceIn(0f, 1f) },
                placeholder = { Text("搜索歌曲、专辑或艺术家") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清除")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(SearchBarCorner),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            // 收成圆之后露出来的，就是这一颗放大镜
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
                    .graphicsLayer { alpha = ((collapse - 0.4f) / 0.6f).coerceIn(0f, 1f) },
            )
        }
    }
}
