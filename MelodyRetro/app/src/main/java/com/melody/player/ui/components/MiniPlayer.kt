package com.melody.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.playback.PlayerUiState
import com.melody.player.ui.PlayerActions
import com.melody.player.ui.theme.rememberRetroColors

/**
 * 迷你播放器的高度。抽屉收起时面板退到「底部栏顶边」，面板顶部这一条正好是这么高 ——
 * 两处必须用同一个值，否则收起后会和底栏错开几个像素。
 */
val MiniPlayerHeight = 56.dp

/**
 * 底部迷你播放器（Retro 风格）：整条用封面主色铺底，无卡片留白、无圆角，
 * 布局为「封面 40dp + 单行歌名 + 上一曲 / 播放暂停 / 下一曲」。
 *
 * 它在 RetroMusicPlayer 里是**播放页面板顶部的一条**，跟着面板一起上下走；
 * 上下滑手势由宿主的面板统一接管，这里只负责被点击时展开。
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    actions: PlayerActions,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current ?: return
    val colors = rememberRetroColors(current.albumId)
    val background by animateColorAsState(
        targetValue = colors.miniBackground,
        animationSpec = tween(320),
        label = "miniBackground",
    )
    val foreground by animateColorAsState(
        targetValue = colors.onMini,
        animationSpec = tween(320),
        label = "miniForeground",
    )
    val secondary = foreground.copy(alpha = 0.62f)
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onExpand),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(
                albumId = current.albumId,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(6.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = current.title,
                style = MaterialTheme.typography.titleSmall,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions.previous, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "上一曲",
                    tint = secondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = actions.togglePlayPause, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Rounded.Pause
                    else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = foreground,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(
                onClick = actions.next,
                enabled = state.hasNext,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一曲",
                    tint = if (state.hasNext) secondary else secondary.copy(alpha = 0.28f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 顶部极细进度：Retro 本身没有，保留它让进度一眼可见。
        // 叠在内容上而不是占位，整条高度才是精确的 MiniPlayerHeight。
        // 轨道本身兼作「内容区 / 播放条」的分界线，所以要比纯进度条更明显一点：
        // 16% 白在深色下几乎看不见，播放条就像凭空贴在列表上。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(foreground.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(colors.accent),
            )
        }
    }
}
