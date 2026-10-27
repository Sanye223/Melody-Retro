package com.melody.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.Cloud
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur
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
import com.melody.player.ui.components.LYRIC_FOCUS_SCALE
import com.melody.player.ui.components.LYRIC_IDLE_SCALE
import com.melody.player.ui.components.LYRIC_MOTION_MS
import com.melody.player.ui.components.LyricAutoScrollShift
import com.melody.player.ui.components.LyricMotionEasing
import com.melody.player.ui.components.lyricFocusOffsetPx
import com.melody.player.ui.components.lyricLineAlpha
import com.melody.player.ui.components.lyricLineBlurDp
import com.melody.player.ui.components.lyricRowMotion
import com.melody.player.ui.components.lyricTrailingPaddingPx
import com.melody.player.ui.theme.RetroColors
import com.melody.player.ui.theme.rememberRetroColors
import com.melody.player.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** 点击歌词行后点亮保持的时长，之后恢复原状。 */
private const val FLASH_DURATION_MS = 420L

/** 手动翻歌词后，隔多久自动回到当前行。 */
private const val MANUAL_SCROLL_RECENTER_MS = 2_000L

/** 焦点行与目标位置的差在这个像素以内就不再纠位，避免每帧抖一下。 */
private const val LYRIC_SCROLL_THRESHOLD_PX = 0.75f

/** 纠位最多重试两轮：第一轮移动布局，第二轮确认各行的视觉位置已跟上。 */
private const val LYRIC_SCROLL_CORRECTION_PASSES = 2

/** 非焦点行的模糊强度（%），与上游默认值一致。 */
private const val LYRIC_BLUR_PERCENT = 40

/** 视口顶部至少留出这么多，矮屏上焦点比例才不至于贴顶。 */
private val LYRIC_MIN_TOP_PADDING = 56.dp

/** 视口底部至少留出这么多。 */
private val LYRIC_MIN_BOTTOM_PADDING = 56.dp

/** 估算最后一行占用的高度，用来算尾部内边距，让末行也能顶到焦点位置。 */
private val LYRIC_TRAILING_LINE_HEIGHT = 72.dp

// ------------------------------------------------------------------ 进度条尺寸

/**
 * 进度条两端给圆点留的余量，等于圆点最大半径。
 *
 * 不留余量的话，播到 0% / 100% 时圆点会有一半被裁掉，看起来像「圆点跑出轨道」。
 */
internal val SeekThumbInset = 8.dp

/** 圆点半径：静息 / 拖动时。 */
internal val SeekThumbRadius = 6.dp
internal val SeekThumbRadiusActive = 8.dp

/** 轨道粗细。 */
private val SeekTrackThickness = 4.dp

/** 整条进度条的触摸高度（比视觉高度大，方便点中）。 */
private val SeekBarHeight = 34.dp

/** 触点 x → 进度。扣掉两端余量，边缘附近才拖得到 0 / 1。 */
internal fun seekProgressAtX(x: Float, widthPx: Float, insetPx: Float): Float {
    val usable = (widthPx - insetPx * 2f).coerceAtLeast(1f)
    return ((x - insetPx) / usable).coerceIn(0f, 1f)
}

/** 进度 → 圆点圆心 x。与 [seekProgressAtX] 互为逆运算，缩放/拖动才对得上。 */
internal fun seekThumbCenterX(progress: Float, widthPx: Float, insetPx: Float): Float {
    val usable = (widthPx - insetPx * 2f).coerceAtLeast(0f)
    return insetPx + progress.coerceIn(0f, 1f) * usable
}


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
            // 上下滑由宿主的抽屉统一接管（手势挂在面板根上），这里只负责显示
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
        // 播放页背景是自绘渐变、不是 Material 的 Surface，不会自动派生出内容色。
        // 不显式提供的话，没写 color/tint 的 Text、Icon 会落到 LocalContentColor 的默认值
        // （纯黑）：浅色渐变上勉强能看，深色渐变上就是「黑字糊在深色底上」完全看不见。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
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
                            // 顶栏是 CenterAligned 样式，标题块整体居中；块内若还用 Start
                            // 对齐，短的那行（艺术家）就会贴到最长那行的左边，看起来「没对齐」。
                            // 两行都居中，中心线才一致。
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = current.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = current.artist.ifBlank { current.album },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
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
}

@Composable
private fun ArtworkPage(current: NowPlaying) {
    // 圆角梯度：小缩略图 12 / 网格卡片 16 / 大图 24，与详情页封面保持一致
    val shape = RoundedCornerShape(24.dp)
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
    val lyrics = result.lyrics ?: return
    val fontScale = LocalLyricFontScale.current
    val spacing = LocalLyricSpacing.current.dp
    val lyricStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontScale,
        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * fontScale,
    )
    val actions = LocalPlayerActions.current
    val density = LocalDensity.current

    // 纯文本歌词没有时间轴：不做焦点动效，整篇一样亮。
    val hasTimeline = lyrics.synced
    val activeIndex = if (hasTimeline) lyrics.indexAt(positionMs) else -1
    // 还没唱到第一句时，布局先锚在第一行
    val focusIndex = if (activeIndex >= 0) activeIndex else 0

    // 点击瞬间短暂点亮的行；tick 用于每次点击都重新计时
    var flashIndex by remember { mutableIntStateOf(-1) }
    var flashTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(flashTick) {
        if (flashTick > 0) {
            delay(FLASH_DURATION_MS)
            flashIndex = -1
        }
    }

    val listState = rememberLazyListState()
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    val autoScrollShift = remember(listState) { LyricAutoScrollShift() }
    // 换歌后重新做一次「不带动画的定位」，否则会从上一首的位置滑过去
    var hasPositioned by remember(lyrics) { mutableStateOf(false) }
    // 手动翻歌词时先让人读完，松手 2 秒后才回到当前行
    var deferAutoScroll by remember { mutableStateOf(false) }
    LaunchedEffect(userDragging) {
        if (userDragging) {
            deferAutoScroll = true
        } else if (deferAutoScroll) {
            delay(MANUAL_SCROLL_RECENTER_MS)
            deferAutoScroll = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        LyricsSourceBadge(result)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val viewportPx = with(density) { maxHeight.toPx() }
            val minTopPx = with(density) { LYRIC_MIN_TOP_PADDING.toPx() }
            val focusOffsetPx = lyricFocusOffsetPx(viewportPx, minTopPx)
            val focusOffsetDp = with(density) { focusOffsetPx.toDp() }
            val trailingPaddingDp = with(density) {
                lyricTrailingPaddingPx(
                    viewportPx = viewportPx,
                    focusOffsetPx = focusOffsetPx,
                    trailingLinePx = with(density) { LYRIC_TRAILING_LINE_HEIGHT.toPx() },
                    minBottomPx = with(density) { LYRIC_MIN_BOTTOM_PADDING.toPx() },
                ).toDp()
            }

            // 焦点行停在视口 24% 处，不是正中。
            //
            // 关键：这里不插值滚动。布局直接 dispatchRawDelta 跳到目标位置，
            // 再由每一行自己的弹簧把「视觉位置」从旧处追回来，并按行距错峰启动 ——
            // 于是换行是一道自上而下的波浪，而不是整块文字一起平移。
            LaunchedEffect(focusIndex, activeIndex, hasTimeline, userDragging, deferAutoScroll, focusOffsetPx) {
                if (!hasTimeline || userDragging || deferAutoScroll) return@LaunchedEffect
                // 视口还没测量出来时不要定位，否则焦点行会先落到页头顶部再弹回来
                val viewport = snapshotFlow {
                    listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                }.filter { it > 0 }.first()
                val desired = lyricFocusOffsetPx(viewport.toFloat(), minTopPx)
                if (!hasPositioned) {
                    listState.scrollToItem(focusIndex, 0)
                    hasPositioned = true
                    return@LaunchedEffect
                }
                repeat(LYRIC_SCROLL_CORRECTION_PASSES) {
                    val items = listState.layoutInfo.visibleItemsInfo
                    if (items.isEmpty()) return@repeat
                    val target = items.firstOrNull { it.index == focusIndex }
                    if (target == null) {
                        // 目标行不在视口内（跳转跨度大）：直接落位，下一轮确认
                        listState.scrollToItem(focusIndex, 0)
                    } else {
                        val delta = target.offset - desired
                        if (abs(delta) <= LYRIC_SCROLL_THRESHOLD_PX) return@LaunchedEffect
                        if (!listState.isScrollInProgress) {
                            autoScrollShift.record(delta)
                            listState.dispatchRawDelta(delta)
                        }
                    }
                    withFrameNanos { }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = focusOffsetDp,
                    bottom = trailingPaddingDp,
                ),
            ) {
                itemsIndexed(
                    items = lyrics.lines,
                    key = { index, line -> "lyric-$index-${line.timeMs}" },
                ) { index, line ->
                    val active = hasTimeline && index == activeIndex
                    val flashing = index == flashIndex
                    val distance = if (hasTimeline) (index - focusIndex).coerceIn(-4, 4) else 0
                    // 焦点行不放大，只把其它行缩小 —— 与参考实现一致：
                    // 放大焦点行会改变这一行的测量高度，LazyColumn 必须重新排版，滚动就顿了。
                    val scale by animateFloatAsState(
                        targetValue = if (!hasTimeline || active) LYRIC_FOCUS_SCALE else LYRIC_IDLE_SCALE,
                        animationSpec = tween(durationMillis = LYRIC_MOTION_MS, easing = LyricMotionEasing),
                        label = "lyricScale",
                    )
                    // 焦点行 1.0，其余按行距递减到 0.13：越远越淡，层次由这一个值统一控制
                    val lineAlpha by animateFloatAsState(
                        targetValue = if (!hasTimeline || active) 1f else lyricLineAlpha(distance, false),
                        animationSpec = tween(durationMillis = LYRIC_MOTION_MS, easing = LyricMotionEasing),
                        label = "lyricAlpha",
                    )
                    val color by animateColorAsState(
                        targetValue = when {
                            flashing -> MaterialTheme.colorScheme.onSurface
                            active -> accent
                            hasTimeline -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = if (flashing) 80 else LYRIC_MOTION_MS),
                        label = "lyricColor",
                    )
                    // 点击时短暂亮起的一层柔光，随后淡出恢复原状
                    val flashAlpha by animateFloatAsState(
                        targetValue = if (flashing) 1f else 0f,
                        animationSpec = tween(durationMillis = if (flashing) 70 else 380),
                        label = "lyricFlash",
                    )
                    // 只有离焦点行两行开外的行才模糊；手动拖动时全部保持清晰
                    val blurDp = if (hasTimeline && !active && !userDragging) {
                        lyricLineBlurDp(distance, LYRIC_BLUR_PERCENT)
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 每行自己追布局位置：布局瞬移，视觉滑过去
                            .lyricRowMotion(
                                targetY = {
                                    listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "lyric-$index-${line.timeMs}" }
                                        ?.offset?.toFloat()
                                },
                                enabled = hasTimeline && !userDragging,
                                distance = distance,
                                maxTravelPx = viewportPx,
                                enterShift = autoScrollShift,
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.16f * flashAlpha))
                            // 关掉默认水波纹（整行的涟漪看起来就像一块大色块），
                            // 点击反馈统一由上面的短暂点亮承担
                            .clickable(
                                enabled = hasTimeline,
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
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = lineAlpha
                                    // 左对齐：以左边缘中点为中心缩放，视线不会被往右推
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                                .then(if (blurDp > 0f) Modifier.blur(blurDp.dp) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSourceBadge(result: LyricsResult) {
    val lyrics = result.lyrics
    val label = when (result.source) {
        LyricsSource.ONLINE -> buildString {
            append(result.provider ?: "在线歌词")
            result.matched?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        LyricsSource.LOCAL_FILE -> "本地歌词文件"
        LyricsSource.EMBEDDED -> "内嵌歌词"
        LyricsSource.NONE -> return
    }
    // 纯文本歌词没有时间轴，不会跟着播放滚动 —— 这里直接说明，免得以为是坏了
    val hint = if (lyrics != null && !lyrics.synced) " · 无时间轴" else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 原来是个灰底胶囊，在深色渐变上发脏、又比歌词本身更抢眼；
        // 改成一行 11sp 的轻量说明，信息还在，但退回背景层。
        Icon(
            imageVector = if (result.source == LyricsSource.ONLINE) Icons.Rounded.Cloud else Icons.Rounded.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label + hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

@Composable
private fun SeekBar(state: PlayerUiState, actions: PlayerActions, colors: RetroColors) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(1L)
    // 拖动时用本地值，松手后交回播放器 —— 否则 seek 的往返延迟会让圆点跳回去再过来
    val progress = if (dragging) {
        dragValue
    } else {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }
    val displayedPosition = if (dragging) (dragValue * duration).toLong() else state.positionMs

    val density = LocalDensity.current
    val insetPx = with(density) { SeekThumbInset.toPx() }
    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) SeekThumbRadiusActive else SeekThumbRadius,
        animationSpec = tween(140),
        label = "seekThumbRadius",
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
            modifier = Modifier.width(44.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(SeekBarHeight)
                // 点一下直接跳到该位置
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        val value = seekProgressAtX(offset.x, size.width.toFloat(), insetPx)
                        dragValue = value
                        actions.seekTo((value * duration).toLong())
                    }
                }
                // 拖动跟手：换算用的是「扣掉两端余量后的有效宽度」，所以 0% / 100% 也对得上
                .pointerInput(duration) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragValue = seekProgressAtX(offset.x, size.width.toFloat(), insetPx)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragValue = seekProgressAtX(change.position.x, size.width.toFloat(), insetPx)
                        },
                        onDragEnd = {
                            dragging = false
                            actions.seekTo((dragValue * duration).toLong())
                        },
                        onDragCancel = { dragging = false },
                    )
                }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                },
        ) {
            val accent = colors.accent
            val idleTrack = accent.copy(alpha = 0.22f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                // DrawScope 本身就是 Density，直接换算
                val trackHeight = SeekTrackThickness.toPx()
                val radius = thumbRadius.toPx()
                val thumbX = seekThumbCenterX(progress, size.width, insetPx)

                drawRoundRect(
                    color = idleTrack,
                    topLeft = Offset(insetPx, centerY - trackHeight / 2f),
                    size = Size((size.width - insetPx * 2f).coerceAtLeast(0f), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                val playedWidth = thumbX - insetPx
                if (playedWidth > 0f) {
                    // 至少画出一个圆头，避免刚开始播时左边出现一个方角
                    val width = playedWidth.coerceAtLeast(trackHeight)
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(insetPx, centerY - trackHeight / 2f),
                        size = Size(width, trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2f),
                    )
                }
                if (dragging) {
                    // 拖动时的柔光圈，只做视觉提示，不参与命中
                    drawCircle(
                        color = accent.copy(alpha = 0.18f),
                        radius = radius * 2.4f,
                        center = Offset(thumbX, centerY),
                    )
                }
                drawCircle(color = accent, radius = radius, center = Offset(thumbX, centerY))
            }
        }
        Text(
            text = formatDuration(state.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
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
