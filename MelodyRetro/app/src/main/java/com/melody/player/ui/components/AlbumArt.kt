package com.melody.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.melody.player.data.artwork.OnlineArtworkStore
import com.melody.player.data.model.ArtworkUris
import com.melody.player.ui.LocalOnlineArtwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 专辑封面：底层是占位底色 + 音符图标，上层用 Coil 加载
 * 应用内 ContentProvider 提供的封面；没有封面时自然露出占位图。
 *
 * 内嵌封面缺失时，会在这里按需触发一次**在线补全**（见 [OnlineArtworkStore]）：
 * - 命中后递增 version，把 content:// 的 query 换掉，让 Coil 重新取图；
 * - 请求期间的转圈只在耗时超过 400ms 才出现，避免快速命中缓存时闪一下；
 * - 失败完全不打扰界面，占位图继续用，播放不受影响。
 */
@Composable
fun AlbumArt(
    albumId: Long,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    iconFraction: Float = 0.36f,
    onlineEnabled: Boolean = LocalOnlineArtwork.current,
) {
    val context = LocalContext.current
    // 在线补全成功一次就 +1，用于绕过 Coil 对"无封面"结果的缓存
    var version by remember(albumId) { mutableIntStateOf(0) }
    var showSpinner by remember(albumId) { mutableStateOf(false) }

    LaunchedEffect(albumId, onlineEnabled) {
        // 负数 id 是"无标签单曲"的合成专辑 id（-trackId），要正常触发补全
        if (albumId == 0L || !onlineEnabled) return@LaunchedEffect
        showSpinner = false
        val delayed = launch {
            delay(SPINNER_DELAY_MS)
            showSpinner = true
        }
        val result = OnlineArtworkStore.ensure(context, albumId)
        delayed.cancel()
        showSpinner = false
        if (result == OnlineArtworkStore.Result.FETCHED) version++
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxSize(iconFraction),
        )
        if (albumId > 0L) {
            AsyncImage(
                model = ArtworkUris.forAlbum(albumId, version),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** 在线取图超过这个时间才显示转圈，避免缓存命中时闪一下。 */
private const val SPINNER_DELAY_MS = 400L
