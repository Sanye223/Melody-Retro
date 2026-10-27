package com.melody.player.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 播放页「抽屉」：把整块 NowPlaying 放在主界面之上，靠一个 0→1 的进度值上下滑。
 *
 * - `progress = 0` 完全展开（贴住屏幕）
 * - `progress = 1` 完全收起（整块移出屏幕下沿，只剩迷你播放器）
 *
 * 手势与动画共用同一个 progress，所以手指松开的瞬间动画能从当前位置与速度接着走，
 * 不会「先跳回原位再播收起动画」——这是手感丝滑的关键。
 */

/** 从起点锚点拖出屏幕高度的这个比例，就按拖动方向提交（否则回弹到起点）。 */
internal const val SHEET_DISMISS_THRESHOLD = 0.32f

/** 每秒越过屏幕高度的比例超过它时，按甩动方向直接定夺。 */
internal const val SHEET_FLING_FRACTION = 0.9f

/** 下层页面的最大压暗程度。 */
private const val SHEET_SCRIM_ALPHA = 0.34f

/** 拖动后的进度：向下拖（deltaY > 0）趋向收起。 */
internal fun sheetProgressAfterDrag(current: Float, deltaY: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return (current + deltaY / heightPx).coerceIn(0f, 1f)
}

/**
 * 松手后落到哪个锚点：1 = 收起，0 = 展开。
 *
 * 判定以**本次手势的起点**为准，而不是「屏幕一半」——
 * 从播放页往下拖 32% 就收起，从迷你播放器往上拖 32% 就展开，两个方向一样好使。
 */
internal fun sheetSettleTarget(
    current: Float,
    origin: Float,
    velocityPxPerSecond: Float,
    heightPx: Float,
): Float {
    val velocityFraction = if (heightPx > 0f) velocityPxPerSecond / heightPx else 0f
    if (velocityFraction >= SHEET_FLING_FRACTION) return 1f
    if (velocityFraction <= -SHEET_FLING_FRACTION) return 0f
    val travelled = if (current > origin) current - origin else origin - current
    if (travelled < SHEET_DISMISS_THRESHOLD) return origin
    return if (current > origin) 1f else 0f
}

/** 页面收起/展开过程中的圆角，完全展开时收敛到 0。 */
private val SheetCornerRadius = 20.dp

/** 尾段淡出的起点：缩到这一步时页面只剩一小块，直接融进迷你播放器。 */
internal const val SHEET_FADE_START = 0.90f

/**
 * 页面在该进度下的缩放比例。
 *
 * 页面以**底部中心**为原点整体缩放，底边始终贴着屏幕底部，
 * 顶边落在 `progress × travelPx` 的高度上 —— 手指往上走多少，页面就长多少。
 * `progress = 1` 时页面正好缩成底部栏那么高，也就是迷你播放器本身，
 * 而不是「滑到屏幕外面只剩一条边」。
 */
internal fun sheetScaleAt(progress: Float, heightPx: Float, travelPx: Float): Float {
    if (heightPx <= 0f) return 1f
    val p = progress.coerceIn(0f, 1f)
    val visible = heightPx - p * travelPx
    return (visible / heightPx).coerceIn(0.02f, 1f)
}

/** 尾段透明度：1 → 0（smoothstep），让最后一小块「缩小版页面」不露出像素感。 */
internal fun sheetAlphaAt(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= SHEET_FADE_START) return 1f
    val t = (p - SHEET_FADE_START) / (1f - SHEET_FADE_START)
    return 1f - t * t * (3f - 2f * t)
}

@Stable
class PlayerSheetState(initiallyOpen: Boolean = false) {

    /** 0 = 展开，1 = 收起。手势与动画共写同一个值。 */
    var progress by mutableFloatStateOf(if (initiallyOpen) 0f else 1f)
        private set

    /** 是否还需要绘制：收起动画跑完才置 false，避免动画中途被拆掉。 */
    var isMounted by mutableStateOf(initiallyOpen)
        private set

    /** 屏幕高度（px），手势换算比例要用。 */
    internal var heightPx: Float = 0f

    /**
     * 底部栏高度（迷你播放器 + 导航栏）。收起时页面就缩到这么高 ——
     * 也就是缩成迷你播放器本身，而不是滑到屏幕外面去。
     */
    internal var bottomBarHeightPx: Float = 0f

    /** 手势要走完的距离：从迷你播放器顶边推到屏幕顶端。 */
    internal val travelPx: Float
        get() = (heightPx - bottomBarHeightPx).coerceAtLeast(1f)

    private var motion: Job? = null

    /** 本次手势的起点锚点（0 或 1），松手时用来判断「拖得够不够远」。 */
    private var gestureOrigin: Float? = null

    /** 收起/展开动画是否正在跑（用于区分「动画在路上」和「停在半路」）。 */
    val isAnimating: Boolean get() = motion?.isActive == true

    fun dragBy(deltaY: Float) {
        if (gestureOrigin == null) {
            gestureOrigin = if (progress >= 0.5f) 1f else 0f
        }
        // 用 travel 而不是整屏高度换算：手指走多远，页面就长多大，一一对应
        val next = sheetProgressAfterDrag(progress, deltaY, travelPx)
        if (next < 1f) isMounted = true
        progress = next
    }

    /**
     * 页面**内部**发来的位移（歌词列表、页面空白处的拖动）。
     *
     * 页面整体被 `graphicsLayer` 缩放过，Compose 报上来的位移是「缩放后的本地坐标」：
     * 缩到 0.5 时手指走 10px，这里收到的是 20px。不还原的话，页面会比手指跑得快一倍。
     */
    fun dragByFromContent(deltaY: Float) {
        dragBy(toScreenDelta(deltaY))
    }

    /** 页面内部的甩动速度同理，也要还原成屏幕坐标。 */
    fun settleFromContent(scope: CoroutineScope, velocityPxPerSecond: Float) {
        settle(scope, toScreenDelta(velocityPxPerSecond))
    }

    private fun toScreenDelta(value: Float): Float =
        value * sheetScaleAt(progress, heightPx, travelPx).coerceIn(0.05f, 1f)

    /** 手指重新按下时掐掉上一段动画，避免「追着手指又被动画拽回去」。 */
    fun cancelMotion() {
        motion?.cancel()
        motion = null
    }

    fun settle(scope: CoroutineScope, velocityPxPerSecond: Float) {
        val origin = gestureOrigin ?: if (progress >= 0.5f) 1f else 0f
        gestureOrigin = null
        animateTo(
            scope = scope,
            target = sheetSettleTarget(progress, origin, velocityPxPerSecond, travelPx),
            velocityPxPerSecond = velocityPxPerSecond,
        )
    }

    fun expand(scope: CoroutineScope) {
        gestureOrigin = null
        animateTo(scope, 0f, 0f)
    }

    fun collapse(scope: CoroutineScope) {
        gestureOrigin = null
        animateTo(scope, 1f, 0f)
    }

    private fun animateTo(scope: CoroutineScope, target: Float, velocityPxPerSecond: Float) {
        motion?.cancel()
        if (target < 1f) isMounted = true
        motion = scope.launch {
            val initial = if (travelPx > 0f) velocityPxPerSecond / travelPx else 0f
            animate(
                initialValue = progress,
                targetValue = target,
                initialVelocity = initial,
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { value, velocity ->
                // 弹簧会轻微过冲，夹住以免露出页面外的空白
                progress = value.coerceIn(0f, 1f)
                velocity
            }
            progress = target
            if (target >= 1f) isMounted = false
        }
    }
}

@Composable
fun rememberPlayerSheetState(initiallyOpen: Boolean = false): PlayerSheetState =
    remember { PlayerSheetState(initiallyOpen) }

/** 由宿主注入的抽屉拖动能力：播放页内部各块不必知道抽屉怎么动。 */
@Stable
class PlayerSheetDrag(
    /** [fromContent] = 手势来自被缩放的页面内部（位移是缩放后的本地坐标）。 */
    val onDrag: (deltaY: Float, fromContent: Boolean) -> Unit,
    val onEnd: (velocityY: Float, fromContent: Boolean) -> Unit,
)

val LocalPlayerSheetDrag = staticCompositionLocalOf<PlayerSheetDrag?> { null }

@Composable
fun PlayerSheetOverlay(
    state: PlayerSheetState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!state.isMounted) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat()
        SideEffect { state.heightPx = heightPx }

        val scope = rememberCoroutineScope()
        val progress = state.progress
        val travel = state.travelPx
        val scale = sheetScaleAt(progress, heightPx, travel)
        val alpha = sheetAlphaAt(progress)
        val corner = SheetCornerRadius * progress

        // 下层页面压暗：页面张得越大越暗，缩回迷你播放器时恢复原亮度
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SHEET_SCRIM_ALPHA * (1f - progress))),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 以底部中心为原点缩放：底边钉在屏幕底部，顶边跟着手指走。
                // 用缩放而不是平移，页面才是「从迷你播放器长出来」，而不是「从屏幕下沿冒出来」。
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(RoundedCornerShape(corner))
                .nestedScroll(
                    remember(state, scope) { sheetNestedScroll(state, scope) },
                )
                // 只观察、不消费：手指离开屏幕时，如果抽屉停在半路就把它结算掉。
                // 子级（歌词列表）拖到边界后交上来的位移走 nestedScroll，
                // 松手时它未必会跑惯性，所以这里补一个「手抬起」的判据。
                .pointerInput(state, scope) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val stillPressed = event.changes.any { it.pressed }
                            if (stillPressed) continue
                            if (state.isAnimating) continue
                            if (state.progress <= 0.001f || state.progress >= 0.999f) continue
                            state.settle(scope, 0f)
                        }
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * 歌词列表这类「自己会滚」的区域，把滚不动的余量交给抽屉。
 *
 * 手指往下拖时子级无法再滚（已经到顶），剩下的位移在这里变成抽屉的位移；
 * 松手后子级跑惯性，把剩余速度交给抽屉决定收还是回弹。
 */
private fun sheetNestedScroll(
    state: PlayerSheetState,
    scope: CoroutineScope,
): NestedScrollConnection {
    var moved = false
    return object : NestedScrollConnection {

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (available.y <= 0f) return Offset.Zero
            state.cancelMotion()
            // 歌词列表也在被缩放的层里，位移同样是本地坐标
            state.dragByFromContent(available.y)
            moved = true
            return Offset(0f, available.y)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!moved) return Velocity.Zero
            moved = false
            state.settleFromContent(scope, available.y)
            return Velocity(0f, available.y)
        }
    }
}
