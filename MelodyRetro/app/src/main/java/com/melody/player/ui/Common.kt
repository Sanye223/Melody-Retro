package com.melody.player.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.compositionLocalOf

/** 内层页面不使用 Scaffold 的默认系统栏内边距（由外层与 TopAppBar 负责）。 */
val NoInsets = WindowInsets(0, 0, 0, 0)

/** 是否开启在线歌词（来自设置页，默认开启）。 */
val LocalOnlineLyrics = compositionLocalOf { true }

/** 是否在播放界面展示格式 / 比特率 / 采样率（设置 → 正在播放）。 */
val LocalShowTrackInfo = compositionLocalOf { true }

/** 歌词字号缩放，1.0 为默认（设置 → 歌词设置）。 */
val LocalLyricFontScale = compositionLocalOf { 1.0f }

/** 歌词行上下间距，单位 dp（设置 → 歌词设置）。 */
val LocalLyricSpacing = compositionLocalOf { 9 }
