package com.melody.player.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 歌词焦点行的跟随动效：每一行自己跑一条闭式弹簧去追布局位置。
 *
 * 参考实现来自 Kifranei/Halcyon（Apache License 2.0）的
 * app/src/main/java/com/ella/music/ui/player/ReferenceLyricMotion.kt，
 * 按其条款在此保留署名；本仓库整体以 GPL-3.0 分发。
 * 上游注释进一步注明：弹簧参数取自 Flamingo 的 t7.B0/A0，
 * Apple Music 的强调曲线取自 player.C / C3722i。
 *
 * 为什么要「布局瞬移 + 逐行弹簧」，而不是 animateScrollToItem：
 * LazyColumn 的滚动动画会把所有行当成一个刚性整块平移，
 * 而这里让布局直接跳到目标位置，再由每一行用自己的弹簧从旧位置追上来，
 * 按与焦点行的距离错峰启动，于是换行时出现自上而下的波浪。
 */

/** 焦点行过渡曲线：起步快、收尾极慢。Apple Music 的手感来源。 */
internal val LyricMotionEasing = CubicBezierEasing(.4f, .1f, 0f, 1f)

/**
 * 闭式弹簧：给定起点、初速度与终点，任意时刻的位置/速度都能直接算出来，
 * 不依赖逐帧积分，所以 60Hz / 120Hz 屏幕上的轨迹完全一致。
 * 重定向（target 变化）时把当前 position / velocity 传进来即可无缝接续，不会顿一下。
 */
internal class LyricRowSpring(
    private val start: Float,
    private val initialVelocity: Float,
    private val target: Float,
    mass: Float = .9f,
    damping: Float = 15f,
    stiffness: Float = 90f,
) {
    private val omega = sqrt(stiffness / mass)
    private val decay = -damping / (2f * mass)
    private val frequency = sqrt(max(0f, stiffness / mass - decay * decay))
    private val delta = target - start

    fun position(seconds: Float): Float {
        if (seconds <= 0f) return start
        if (frequency < .0001f) {
            return target - (delta + (omega * delta - initialVelocity) * seconds) * exp(-omega * seconds)
        }
        val b = (-decay * delta - initialVelocity) / frequency
        return target - (delta * cos(frequency * seconds) + b * sin(frequency * seconds)) * exp(decay * seconds)
    }

    fun velocity(seconds: Float): Float {
        val t = seconds.coerceAtLeast(0f)
        if (frequency < .0001f) {
            val b = omega * delta - initialVelocity
            return (omega * (delta + b * t) - b) * exp(-omega * t)
        }
        val b = (-decay * delta - initialVelocity) / frequency
        val c = cos(frequency * t)
        val s = sin(frequency * t)
        return (delta * frequency * s - b * frequency * c - decay * (delta * c + b * s)) * exp(decay * t)
    }
}

/**
 * 记录「最近一次自动纠位」的位移量。
 *
 * 自动纠位会把布局瞬间挪走，此刻才进入视口的行没有「上一帧位置」可用，
 * 它们会直接出现在终点位置、像凭空冒出来。把这次的位移量交给它们，
 * 让它们从「被骗出来的旧位置」开始追，就能和其余行一起弹进来。
 */
internal class LyricAutoScrollShift {
    private var deltaPx = 0f
    private var atNanos = 0L

    fun record(delta: Float) {
        deltaPx = delta
        atNanos = System.nanoTime()
    }

    /** 窗口期内返回上次的位移；手动滚动、普通布局变化都返回 0。 */
    fun recent(windowMs: Long = 250L): Float =
        if (atNanos != 0L && System.nanoTime() - atNanos <= windowMs * 1_000_000L) deltaPx else 0f
}

/**
 * 单行的跟随动效。
 *
 * 关键点：`translationY` 只在绘制层里生效，绝不参与测量 ——
 * 否则每一帧都会触发 LazyColumn 重新排版，行距抖动、滚动卡顿。
 * 相减得到「视觉位置与布局位置的差」，布局一动，这个差就变成上一帧的偏移量，
 * 弹簧把它慢慢收到 0，视觉上就是平滑滑过去。
 *
 * @param targetY 该行当前的布局位置（由 LazyColumn 的 visibleItemsInfo 提供）
 * @param enabled 手动拖动时为 false，此时直接跟随布局，不插值
 * @param distance 与焦点行的行距，决定错峰启动的延迟
 * @param maxTravelPx 超过这个位移量就不做动画（换歌、跳转），避免整页乱飞
 */
internal fun Modifier.lyricRowMotion(
    targetY: () -> Float?,
    enabled: Boolean,
    distance: Int,
    maxTravelPx: Float,
    enterShift: LyricAutoScrollShift? = null,
): Modifier = composed {
    var position by remember { mutableFloatStateOf(Float.NaN) }
    var velocity by remember { mutableFloatStateOf(0f) }
    val latestTarget by rememberUpdatedState(targetY)
    val latestDistance by rememberUpdatedState(distance)
    val latestMaxTravel by rememberUpdatedState(maxTravelPx)

    LaunchedEffect(enabled) {
        snapshotFlow { latestTarget() }.collectLatest { target ->
            if (target == null) return@collectLatest
            if (enabled && !position.isFinite()) {
                val shift = enterShift?.recent() ?: 0f
                if (shift != 0f && abs(shift) <= latestMaxTravel) {
                    position = target + shift
                    velocity = 0f
                }
            }
            if (!enabled || !position.isFinite() || abs(target - position) > latestMaxTravel) {
                position = target
                velocity = 0f
                return@collectLatest
            }
            if (abs(target - position) < .1f && abs(velocity) < .1f) return@collectLatest
            // 离焦点行越远，起步越晚：一次换行就是一道自上而下的波浪。
            delay(latestDistance.coerceIn(0, 6) * LYRIC_STAGGER_MS)
            val motion = LyricRowSpring(position, velocity, target)
            val start = withFrameNanos { it }
            do {
                val elapsed = withFrameNanos { (it - start) / 1_000_000_000f }
                position = motion.position(elapsed)
                velocity = motion.velocity(elapsed)
            } while (abs(position - target) > .1f || abs(velocity) > .1f)
            position = target
            velocity = 0f
        }
    }

    graphicsLayer {
        val target = latestTarget()
        alpha = if (target == null) 0f else 1f
        translationY = if (enabled && target != null && position.isFinite() && abs(position - target) <= maxTravelPx) {
            position - target
        } else {
            0f
        }
    }
}

// ---------------------------------------------------------------- 视觉层级

/** 焦点行在视口中的纵向位置：视口高度的 24%，不用正中。 */
internal const val LYRIC_FOCUS_OFFSET_RATIO = 0.24f

/** 每远一行延后多少毫秒启动弹簧。 */
internal const val LYRIC_STAGGER_MS = 30L

/** 焦点行缩放（与上游一致：焦点行不放大，只把其它行缩小）。 */
internal const val LYRIC_FOCUS_SCALE = 1f
internal const val LYRIC_IDLE_SCALE = 0.98f
internal const val LYRIC_MOTION_MS = 450

/** 非焦点行透明度：从 0.24 起，每远一行减 0.025，最低 0.13。 */
internal const val LYRIC_ALPHA_NEAR = 0.24f
internal const val LYRIC_ALPHA_STEP = 0.025f
internal const val LYRIC_ALPHA_FLOOR = 0.13f

/** 距焦点行 2 行以内不模糊，再远按 (2 + 距离) × 百分比 递增。 */
internal const val LYRIC_BLUR_MIN_DISTANCE = 2

/** 非焦点行的透明度。 */
internal fun lyricLineAlpha(distance: Int, focused: Boolean): Float =
    if (focused) 1f
    else (LYRIC_ALPHA_NEAR - abs(distance) * LYRIC_ALPHA_STEP).coerceAtLeast(LYRIC_ALPHA_FLOOR)

/** 非焦点行的模糊半径（dp）；0 表示不模糊。 */
internal fun lyricLineBlurDp(distance: Int, percent: Int): Float {
    val d = abs(distance)
    if (d < LYRIC_BLUR_MIN_DISTANCE || percent <= 0) return 0f
    return (2 + d) * (percent.coerceIn(0, 100) / 100f)
}

/** 顶部内边距 = 焦点位置，让第一行也能停在与其余行相同的高度。 */
internal fun lyricFocusOffsetPx(
    viewportPx: Float,
    minTopPx: Float,
    ratio: Float = LYRIC_FOCUS_OFFSET_RATIO,
): Float = maxOf(minTopPx, viewportPx * ratio.coerceIn(0f, 1f))

/** 尾部内边距：留够空间让最后一行也能顶到焦点位置，否则它会卡在底部上不来。 */
internal fun lyricTrailingPaddingPx(
    viewportPx: Float,
    focusOffsetPx: Float,
    trailingLinePx: Float,
    minBottomPx: Float,
): Float = maxOf(minBottomPx, viewportPx - focusOffsetPx - trailingLinePx)
