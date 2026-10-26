package com.melody.player.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.melody.player.data.ArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 由专辑封面提取出来的配色（Retro 风格的核心）：
 * 播放页的整屏渐变、进度条、播放按钮、迷你播放器背景都取自封面的主色。
 */
data class RetroColors(
    /** 强调色：进度条、播放按钮底、当前歌词。 */
    val accent: Color,
    /** 强调色上的前景色。 */
    val onAccent: Color,
    /** 播放页渐变：顶部。 */
    val gradientTop: Color,
    /** 播放页渐变：中部。 */
    val gradientMiddle: Color,
    /** 播放页渐变：底部（回到 surface）。 */
    val gradientBottom: Color,
    /** 迷你播放器背景。 */
    val miniBackground: Color,
    val onMini: Color,
    val onMiniSecondary: Color,
)

@Composable
fun rememberRetroColors(albumId: Long): RetroColors {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val fallbackAccent = MaterialTheme.colorScheme.primary
    val dark = surface.luminance() < 0.35f

    val fallback = remember(fallbackAccent, surface, container, dark) {
        buildRetroColors(fallbackAccent, surface, container, dark)
    }
    var extracted by remember(albumId) { mutableStateOf<Color?>(null) }

    LaunchedEffect(albumId, dark) {
        extracted = null
        if (albumId <= 0L) return@LaunchedEffect
        extracted = withContext(Dispatchers.IO) { extractAccent(context, albumId) }
    }

    val raw = extracted ?: return fallback
    return remember(raw, surface, container, dark) {
        buildRetroColors(raw, surface, container, dark)
    }
}

/** 把封面主色修正到「在深浅主题下都能用」的区间，再派生各种派生色。 */
private fun buildRetroColors(
    raw: Color,
    surface: Color,
    container: Color,
    dark: Boolean,
): RetroColors {
    val accent = when {
        !dark && raw.luminance() > 0.78f -> lerp(raw, Color.Black, 0.38f)
        !dark && raw.luminance() < 0.10f -> lerp(raw, Color.White, 0.32f)
        dark && raw.luminance() < 0.28f -> lerp(raw, Color.White, 0.48f)
        dark && raw.luminance() > 0.94f -> lerp(raw, Color.Black, 0.18f)
        else -> raw
    }
    val miniBackground = lerp(container, accent, if (dark) 0.58f else 0.46f)
    val mini = onColorFor(miniBackground)
    return RetroColors(
        accent = accent,
        onAccent = onColorFor(accent),
        gradientTop = lerp(surface, accent, if (dark) 0.36f else 0.24f),
        gradientMiddle = lerp(surface, accent, if (dark) 0.15f else 0.09f),
        gradientBottom = surface,
        miniBackground = miniBackground,
        onMini = mini,
        onMiniSecondary = mini.copy(alpha = 0.62f),
    )
}

private fun onColorFor(background: Color): Color = if (background.luminance() > 0.45f) {
    Color.Black.copy(alpha = 0.87f)
} else {
    Color.White
}

/** 在小尺寸位图上跑 Palette，取「鲜艳 → 柔和 → 主色」的第一命中。 */
private fun extractAccent(context: Context, albumId: Long): Color? {
    val bytes = ArtworkStore.artworkBytes(context, albumId) ?: return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 96) sample *= 2

    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    return try {
        val palette = Palette.from(bitmap)
            .clearFilters()
            .maximumColorCount(24)
            .generate()
        val swatch = palette.vibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkMutedSwatch
        swatch?.let { Color(it.rgb) }
    } finally {
        bitmap.recycle()
    }
}
