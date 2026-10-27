package com.melody.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import com.melody.player.data.model.Lyrics
import com.melody.player.data.model.LyricsQuery
import com.melody.player.data.model.LyricsResult
import com.melody.player.data.model.LyricsSource
import com.melody.player.playback.NowPlaying
import com.melody.player.playback.PlayerUiState
import com.melody.player.data.TrackInfo
import com.melody.player.data.TrackInfoStore
import com.melody.player.ui.LocalOnlineLyrics
import com.melody.player.ui.LocalPlayerActions
import com.melody.player.ui.LocalLyricFontScale
import com.melody.player.ui.LocalLyricSpacing
import com.melody.player.ui.LocalShowTrackInfo
import com.melody.player.ui.LocalPlayerState
import com.melody.player.ui.LyricsViewModel
import com.melody.player.ui.NoInsets
import com.melody.player.ui.PlayerActions
import com.melody.player.ui.components.AlbumArt
import com.melody.player.ui.theme.RetroColors
import com.melody.player.ui.theme.rememberRetroColors
import com.melody.player.util.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 点击歌词行后点亮保持的时长，之后恢复原状。 */
private const val FLASH_DURATION_MS = 420L

// ---------------------------------------------------------------------------
// 歌词动效参数
//
// 参照 RetroMusicPlayer / Apple Music 那一类"焦点行"歌词的做法（Halcyon 的实现里同样
// 使用这组数值：`ReferenceLyricMotion.kt`）：
//   - 基准字号不动，**其它行缩小**，靠尺度的相对差把焦点行托起来，而不是把焦点行吹大
//     （放大焦点行会让行高变化，LazyColumn 需要重新测量，滚动就会一顿一顿）
//   - 透明度按与焦点行的距离线性递减，远处淡到刚好看得见的程度
//   - 缓动统一用 CubicBezier(.4, .1, 0, 1)：起步快、收尾极慢，是"顺滑"观感的来源
//   - 每行按距离错峰 40ms 启动，换行时形成一道自上而下的波浪
// ---------------------------------------------------------------------------

/** 起步快、收尾慢，Apple Music 歌词位移用的就是这条曲线。 */
private val LyricEasing = CubicBezierEasing(0.4f, 0.1f, 0f, 1f)

/** 单行缩放/透明度的过渡时长。 */
private const val LYRIC_MOTION_MS = 450

/** 每远离焦点行一行，动画延后 40ms 启动（最多累计 3 行），形成波浪。 */
private const val LYRIC_STAGGER_MS = 40

/** 相邻行相对焦点行的缩小比例；再乘以距离，最多缩到 3 行。 */
private const val LYRIC_SCALE_STEP = 0.017f

/** 非焦点行在焦点行正下方时的透明度，以及每远一行再减多少、最低到多少。 */
private const val LYRIC_ALPHA_NEAR = 0.30f
private const val LYRIC_ALPHA_STEP = 0.030f
private const val LYRIC_ALPHA_MIN = 0.18f

/** 焦点行滚到视口中央所用的时长。 */
private const val LYRIC_SCROLL_MS = 500


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(onBack: () -> Unit) {
    val state = LocalPlayerState.current
    val actions = LocalPlayerActions.current
    val current = state.current

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "还没有正在播放的歌曲",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val retro = rememberRetroColors(current.albumId)
    val gradientTop by animateColorAsState(retro.gradientTop, tween(560), label = "gradientTop")
    val gradientMiddle by animateColorAsState(retro.gradientMiddle, tween(560), label = "gradientMiddle")
    val gradientBottom by animateColorAsState(retro.gradientBottom, tween(560), label = "gradientBottom")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to gradientTop,
                        0.42f to gradientMiddle,
                        1f to gradientBottom,
                    ),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = NoInsets,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起")
                        }
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = current.artist.ifBlank { current.album },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) { page ->
                    if (page == 0) {
                        ArtworkPage(current)
                    } else {
                        LyricsPage(state, accent = retro.accent)
                    }
                }
                PageDots(count = 2, current = pagerState.currentPage, accent = retro.accent)
                SeekBar(state = state, actions = actions, colors = retro)
                TransportControls(state = state, actions = actions, colors = retro)
            }
        }
    }
}

@Composable
private fun ArtworkPage(current: NowPlaying) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AlbumArt(
            albumId = current.albumId,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(elevation = 18.dp, shape = shape, clip = false),
            shape = shape,
            iconFraction = 0.26f,
        )
        Spacer(Modifier.height(30.dp))
        Text(
            text = current.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = current.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 歌曲信息紧跟在歌手名下方（Retro 的单行小字排版）
        TrackInfoRow(current = current)
    }
}

/**
 * 额外歌曲信息，显示方式对齐 RetroMusicPlayer 的 `songInfo`：
 * 单行、居中、12sp、次要色、无背景色块，位置在封面页「歌名 / 歌手」下方。
 *
 * 例：`FLAC • 24-bit • 1411 kb/s • 48 kHz`
 * 位深只对无损格式（FLAC / WAV / AIFF）有意义，有损格式自动省略；
 * 容器头没给码率时按「体积 ÷ 时长」估算，值前加 `≈`。
 */
@Composable
private fun TrackInfoRow(current: NowPlaying) {
    if (!LocalShowTrackInfo.current) return
    val context = LocalContext.current
    var info by remember(current.id) { mutableStateOf<TrackInfo?>(null) }

    LaunchedEffect(current.id) {
        info = TrackInfoStore.info(context, current.id, current.path, current.durationMs)
    }

    val summary = info?.summary().orEmpty()
    if (summary.isBlank()) return
    // Retro 的做法：不套卡片/胶囊，一行小字居中显示、单行截断不换行，
    // 高度固定（12sp 行高 + 8dp 上下内边距），不会因为内容长短让上下元素挪位。
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ------------------------------------------------------------------ 歌词页

@Composable
private fun LyricsPage(state: PlayerUiState, accent: Color) {
    val current = state.current ?: return
    val onlineEnabled = LocalOnlineLyrics.current
    val viewModel: LyricsViewModel = viewModel()
    val ui by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(current.id, current.title, onlineEnabled) {
        viewModel.load(
            query = LyricsQuery(
                id = current.id,
                path = current.path,
                title = current.title,
                artist = current.artist,
                album = current.album,
                durationMs = current.durationMs,
            ),
            onlineEnabled = onlineEnabled,
        )
    }

    val result = ui.result
    when {
        ui.loading -> LyricsLoading(onlineEnabled)

        result.isEmpty -> NoLyrics(
            onlineEnabled = onlineEnabled,
            message = result.message,
            onRetry = viewModel::retry,
        )

        else -> LyricsContent(
            result = result,
            positionMs = state.positionMs,
            accent = accent,
        )
    }
}

@Composable
private fun LyricsContent(result: LyricsResult, positionMs: Long, accent: Color) {
    val lyrics: Lyrics = result.lyrics ?: return
    val fontScale = LocalLyricFontScale.current
    val spacing = LocalLyricSpacing.current.dp
    val lyricStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontScale,
        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * fontScale,
    )
    val listState = rememberLazyListState()
    val actions = LocalPlayerActions.current
    val activeIndex = if (lyrics.synced) lyrics.indexAt(positionMs) else -1

    // 点击瞬间短暂点亮的行；tick 用于每次点击都重新计时
    var flashIndex by remember { mutableIntStateOf(-1) }
    var flashTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(flashTick) {
        if (flashTick > 0) {
            delay(FLASH_DURATION_MS)
            flashIndex = -1
        }
    }

    // 用户正在手动翻歌词时不强行拉回去，松手后下一次换行再自然归位
    val dragging by listState.interactionSource.collectIsDraggedAsState()

    // 焦点行滚到视口正中。
    //
    // 不用 animateScrollToItem：它只会把目标行顶到 contentPadding 的位置，时长由跨过的行数
    // 隐式决定 —— 相邻两行几乎瞬移、跨十行又拖得很长，节奏忽快忽慢。
    // 这里取目标行当前的实测中心与视口中心的像素差，用同一条缓动曲线滚过去，
    // 无论换行跨多远，起落手感都一致。
    LaunchedEffect(activeIndex, lyrics, dragging) {
        if (activeIndex < 0 || dragging) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        if (item == null) {
            // 目标行还没被懒加载出来：先滚过去，下一次换行再按像素校准
            listState.animateScrollToItem(activeIndex)
            return@LaunchedEffect
        }
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        val delta = itemCenter - viewportCenter
        if (abs(delta) > 1f) {
            listState.animateScrollBy(
                value = delta,
                animationSpec = tween(durationMillis = LYRIC_SCROLL_MS, easing = LyricEasing),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        LyricsSourceBadge(result)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val centerPadding = ((maxHeight - 72.dp) / 2).coerceAtLeast(0.dp)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = centerPadding),
            ) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val active = index == activeIndex
                    val flashing = index == flashIndex
                    // 与焦点行隔了几行；没在播放（静态歌词）时统一按 0，整屏一样亮
                    val distance = if (activeIndex < 0) 0 else abs(index - activeIndex)
                    // 越远的行越晚启动动画，换行时自上而下扫过一道波浪
                    val stagger = distance.coerceAtMost(3) * LYRIC_STAGGER_MS

                    // 只缩不放：基准字号不动，其它行按距离轻微缩小，焦点行因此"显得"更大。
                    // 关键是不把焦点行放大 —— 放大字号会改变行的测量高度，LazyColumn 要重新
                    // 布局，滚动就会卡顿、行会跳；这里所有行的尺寸始终一致，只有绘制层在缩放。
                    val scale = animateFloatAsState(
                        targetValue = if (active) 1f else 1f - distance.coerceAtMost(3) * LYRIC_SCALE_STEP,
                        animationSpec = tween(
                            durationMillis = LYRIC_MOTION_MS,
                            delayMillis = stagger,
                            easing = LyricEasing,
                        ),
                        label = "lyricScale",
                    )
                    // 渐隐：越远越淡，淡到 LYRIC_ALPHA_MIN 为止，保证还能看出下一句的轮廓
                    val lineAlpha = animateFloatAsState(
                        targetValue = when {
                            active -> 1f
                            !lyrics.synced -> 0.85f
                            else -> (LYRIC_ALPHA_NEAR - distance.coerceAtMost(4) * LYRIC_ALPHA_STEP)
                                .coerceAtLeast(LYRIC_ALPHA_MIN)
                        },
                        animationSpec = tween(
                            durationMillis = LYRIC_MOTION_MS,
                            delayMillis = stagger,
                            easing = LyricEasing,
                        ),
                        label = "lyricAlpha",
                    )
                    // 颜色只表达"谁是焦点行"，不承载透明度 —— 透明度交给绘制层，
                    // 否则点击柔光会被行的整体透明度一起压暗。
                    val color by animateColorAsState(
                        targetValue = when {
                            flashing -> MaterialTheme.colorScheme.onSurface
                            active -> accent
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = if (flashing) 80 else 260),
                        label = "lyricColor",
                    )
                    // 点击时短暂亮起的一层柔光，随后淡出恢复原状
                    val flashAlpha by animateFloatAsState(
                        targetValue = if (flashing) 1f else 0f,
                        animationSpec = tween(durationMillis = if (flashing) 70 else 380),
                        label = "lyricFlash",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.16f * flashAlpha))
                            // 关掉默认水波纹（整行的涟漪看起来就像一块大色块），
                            // 点击反馈统一由上面的短暂点亮承担
                            .clickable(
                                enabled = lyrics.synced,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                flashIndex = index
                                flashTick++
                                if (line.timeMs >= 0) actions.seekTo(line.timeMs)
                            }
                            .padding(horizontal = 10.dp, vertical = spacing),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = line.text,
                            color = color,
                            style = lyricStyle,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    // 在绘制层读动画值：换行时只失效这一层的缓存，
                                    // 不会让整行重新组合、重建 TextStyle（那才是掉帧的来源）
                                    scaleX = scale.value
                                    scaleY = scale.value
                                    // 点击点亮的那一瞬间不被渐隐压暗
                                    alpha = if (flashing) 1f else lineAlpha.value
                                    // 左对齐：以左边缘中点为中心缩放，视线不会被往右推
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSourceBadge(result: LyricsResult) {
    val label = when (result.source) {
        LyricsSource.ONLINE -> buildString {
            append(result.provider ?: "在线歌词")
            result.matched?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        LyricsSource.LOCAL_FILE -> "本地歌词文件"
        LyricsSource.NONE -> return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (result.source == LyricsSource.ONLINE) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (result.source == LyricsSource.ONLINE) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun LyricsLoading(onlineEnabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (onlineEnabled) "正在获取在线歌词…" else "正在读取歌词…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoLyrics(onlineEnabled: Boolean, message: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(text = "暂无歌词", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = buildString {
                if (!message.isNullOrBlank()) {
                    append(message)
                    append("\n")
                }
                if (onlineEnabled) {
                    append("也可把同名 .lrc 文件放到歌曲所在目录。")
                } else {
                    append("在线歌词已在设置中关闭，\n可开启后自动从 LRCLIB 获取。")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onlineEnabled) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("重新搜索在线歌词")
            }
        }
    }
}

// ------------------------------------------------------------------ 通用控件

@Composable
private fun PageDots(count: Int, current: Int, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val active = index == current
            val color by animateColorAsState(
                targetValue = if (active) accent
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                label = "dotColor",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (active) 18.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: PlayerUiState, actions: PlayerActions, colors: RetroColors) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(1L)
    val sliderValue = if (dragging) {
        dragValue
    } else {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }
    val displayedPosition = if (dragging) (dragValue * duration).toLong() else state.positionMs
    val sliderColors = SliderDefaults.colors(
        thumbColor = colors.accent,
        activeTrackColor = colors.accent,
        inactiveTrackColor = colors.accent.copy(alpha = 0.22f),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDuration(displayedPosition),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp),
        )
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                dragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                dragging = false
                actions.seekTo((dragValue * duration).toLong())
            },
            valueRange = 0f..1f,
            colors = sliderColors,
            thumb = {
                Box(
                    modifier = Modifier
                        .size(if (dragging) 16.dp else 12.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    enabled = true,
                    colors = sliderColors,
                    drawStopIndicator = {},
                )
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(state.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(42.dp),
        )
    }
}

@Composable
private fun TransportControls(state: PlayerUiState, actions: PlayerActions, colors: RetroColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Retro 的按钮顺序：循环 / 上一曲 / 播放 / 下一曲 / 随机
        IconButton(onClick = actions.cycleRepeatMode) {
            Icon(
                imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                else Icons.Rounded.Repeat,
                contentDescription = "循环模式",
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) colors.accent
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = actions.previous, modifier = Modifier.size(52.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "上一曲",
                modifier = Modifier.size(32.dp),
            )
        }
        FloatingActionButton(
            onClick = actions.togglePlayPause,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(
            onClick = actions.next,
            modifier = Modifier.size(52.dp),
            enabled = state.hasNext,
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "下一曲",
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = actions.toggleShuffle) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = "随机播放",
                tint = if (state.shuffle) colors.accent
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
