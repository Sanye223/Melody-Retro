package com.melody.player.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.melody.player.data.model.Track
import com.melody.player.playback.PlayerUiState

/** 播放操作集合，供各屏幕调用，避免层层传递回调。 */
data class PlayerActions(
    val playQueue: (List<Track>, Int) -> Unit = { _, _ -> },
    val shufflePlay: (List<Track>) -> Unit = {},
    val togglePlayPause: () -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val toggleShuffle: () -> Unit = {},
    val cycleRepeatMode: () -> Unit = {},
)

val LocalPlayerActions = staticCompositionLocalOf { PlayerActions() }

/** 是否允许在缺少内嵌封面时联网补全（设置 → 歌词与封面）。 */
val LocalOnlineArtwork = staticCompositionLocalOf { true }
val LocalPlayerState = compositionLocalOf { PlayerUiState() }

/** 收藏的曲目 id（「我的最爱」）。播放页与列表页的心形都读它。 */
val LocalFavoriteIds = staticCompositionLocalOf { emptySet<Long>() }

/** 切换收藏状态。 */
val LocalToggleFavorite = staticCompositionLocalOf<(Long) -> Unit> { {} }
