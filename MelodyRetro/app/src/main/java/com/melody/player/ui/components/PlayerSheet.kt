package com.melody.player.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.activity.compose.BackHandler

/**
 * 播放页「抽屉」：把整块 NowPlaying 放在主界面之上，整块上下平移。
 *
 * 结构对齐 RetroMusicPlayer（`AbsSlidingMusicPanelActivity` + `sliding_music_panel_layout.xml`）：
 * 面板自己带背景，**迷你播放器是面板顶部的一条**，播放页叠在它上面。
 * 所以收起/展开时迷你播放器跟着面板一起走 —— 它不是固定在底栏里的另一个控件。
 *
 * - `progress = 0` 完全展开（贴住屏幕顶，迷你播放器被推到上面并淡掉）
 * - `progress = 1` 完全收起（面板顶边落在底部栏顶边，露出的正是迷你播放器）
 *
 * 手势挂在面板根上（相当于上游由 `BottomSheetBehavior` 统一接管），
 * 面板内部的内容只管显示，这样迷你播放器淡出/被卸载时也不会把手势打断。
 */

/** 从起点锚点拖出屏幕高度的这个比例，就按拖动方向提交（否则回弹到起点）。 */
internal const val SHEET_DISMISS_THRESHOLD = 0.32f

/** 每秒越过屏幕高度的比例超过它时，按甩动方向直接定夺。 */
internal const val SHEET_FLING_FRACTION = 0.9f

/**
 * 松手后落到哪个锚点：1 = 收起，0 = 展开。
 *
 * 判定以**本次手势的起点**为准，而不是「屏幕一半」——
 * 从播放页往下拖 32% 就收起，从迷你播放器往上拖 32% 就展开，两个方向一样好使。
 */
internal fun sheetProgressAfterDrag(current: Float, deltaY: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return (current + deltaY / heightPx).coerceIn(0f, 1f)
}

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

/** 面板下滑过程中顶部露出的圆角，展开后收敛到 0，重新变成整屏。 */
private val SheetCornerRadius = 22.dp

/** 底栏高度尚未测量时的兜底比例（迷你播放器 + 导航栏大致占屏高这么多）。 */
private const val SHEET_BAR_FALLBACK_RATIO = 0.12f

/** 面板浮在列表上方时的投影高度。 */
private val SheetShadow = 22.dp

/**
 * 面板向下平移的距离（px）。
 *
 * `progress = 1` 时正好是 `height - bottomBar`：面板顶边落在底部栏顶边上，
 * 也就是「板子整块退到屏幕下方，只留迷你播放器探出来」。
 */
internal fun sheetOffsetAt(progress: Float, travelPx: Float): Float =
    progress.coerceIn(0f, 1f) * travelPx.coerceAtLeast(0f)

/** 交叉淡化的中点：到这里播放页已完全让位、迷你播放器完全接上。 */
internal const val SHEET_CROSSFADE_CENTER = 0.80f

/** 交叉区间半宽，总区间是 [0.72, 0.88]。 */
internal const val SHEET_CROSSFADE_HALF = 0.08f

/** 播放页内容的透明度：展开时为 1，退到最后一段时让给迷你播放器。 */
internal fun playerAlphaAt(progress: Float): Float =
    ((SHEET_CROSSFADE_CENTER + SHEET_CROSSFADE_HALF - progress) / (SHEET_CROSSFADE_HALF * 2f))
        .coerceIn(0f, 1f)

/** 面板顶部迷你播放器的透明度：只在最后一段出现，与播放页互补。 */
internal fun miniAlphaAt(progress: Float): Float =
    ((progress - (SHEET_CROSSFADE_CENTER - SHEET_CROSSFADE_HALF)) / (SHEET_CROSSFADE_HALF * 2f))
        .coerceIn(0f, 1f)

@Stable
class PlayerSheetState(initiallyOpen: Boolean = false) {

    /** 0 = 展开，1 = 收起。手势与动画共写同一个值。 */
    var progress by mutableFloatStateOf(if (initiallyOpen) 0f else 1f)
        private set

    /**
     * 屏幕高度（px）。必须是 State：面板的 translationY 在 `graphicsLayer` 里读它，
     * 普通字段改了不会触发重绘，面板会一直停在测量前的错位置。
     */
    internal var heightPx by mutableFloatStateOf(0f)

    /** 底部栏高度（迷你播放器 + 导航栏）。收起时面板就退到这么高。同样是 State。 */
    internal var bottomBarHeightPx by mutableFloatStateOf(0f)

    /**
     * 手势要走完的距离：从贴住屏幕顶，到退成「只剩底部栏」。
     *
     * 底栏高度还没量到时**不能**退化成整屏高 —— 那样 `translationY` 会把整块面板
     * （连同面板顶部的迷你播放器）推到屏幕外面，用户只看到歌单、找不到迷你播放器。
     * 这里按屏高的固定比例兜底，等实测值回来再收敛。
     */
    internal val travelPx: Float
        get() {
            if (heightPx <= 0f) return 1f
            val bar = if (bottomBarHeightPx > 0f) {
                bottomBarHeightPx
            } else {
                heightPx * SHEET_BAR_FALLBACK_RATIO
            }
            return (heightPx - bar.coerceAtMost(heightPx * 0.5f)).coerceAtLeast(1f)
        }

    /** 面板是否处于（部分）展开状态 —— 返回键要用。 */
    val isOpen: Boolean get() = progress < 0.999f

    private var motion: Job? = null

    /** 本次手势的起点锚点（0 或 1），松手时用来判断「拖得够不够远」。 */
    private var gestureOrigin: Float? = null

    /** 收起/展开动画是否正在跑（用于区分「动画在路上」和「停在半路」）。 */
    val isAnimating: Boolean get() = motion?.isActive == true

    fun dragBy(deltaY: Float) {
        if (gestureOrigin == null) {
            gestureOrigin = if (progress >= 0.5f) 1f else 0f
        }
        // 用 travel 而不是整屏高度换算：手指走多远，板子就走多远，一一对应
        progress = sheetProgressAfterDrag(progress, deltaY, travelPx)
    }

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
                // 弹簧会轻微过冲，夹住以免面板超出两端
                progress = value.coerceIn(0f, 1f)
                velocity
            }
            progress = target
        }
    }
}

@Composable
fun rememberPlayerSheetState(initiallyOpen: Boolean = false): PlayerSheetState =
    remember { PlayerSheetState(initiallyOpen) }

@Composable
fun PlayerSheetOverlay(
    state: PlayerSheetState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat()
        SideEffect { state.heightPx = heightPx }

        val scope = rememberCoroutineScope()
        val progress = state.progress
        val bottomBarPx = state.bottomBarHeightPx

        // 播放页是叠在导航之上的浮层，不是导航目的地：不接管返回键的话，
        // 按返回会直接落到 NavHost（弹栈 / 退出应用），而播放页纹丝不动 ——
        // 体感就是「在播放界面按返回键没用」。展开时先收起播放页。
        BackHandler(enabled = state.isOpen) {
            state.collapse(scope)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 整块平移：面板底部随之浮出屏幕，顶边跟着手指走，全程不变形。
                // 顶部圆角与投影只在「浮起来」时出现，贴满屏幕时收敛到底。
                .graphicsLayer {
                    translationY = sheetOffsetAt(progress, state.travelPx)
                    val radius = SheetCornerRadius.toPx() * progress
                    shape = RoundedCornerShape(radius)
                    clip = true
                    shadowElevation = SheetShadow.toPx() * progress
                }
                // 拖动手势挂在面板根上：内容怎么淡入淡出都不会打断这次拖动
                .draggable(
                    state = rememberDraggableState { delta ->
                        state.cancelMotion()
                        state.dragBy(delta)
                    },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> state.settle(scope, velocity) },
                )
                .nestedScroll(
                    remember(state, scope) { sheetNestedScroll(state, scope) },
                )
                // 只观察、不消费：手指离开屏幕时，如果抽屉停在半路就把它结算掉。
                // 子级（歌词列表）拖到边界后交上来的位移走 nestedScroll，
                // 松手时它未必会跑惯性，所以这里补一个「手抬起」的判据。
                .pointerInput(state, scope, bottomBarPx) {
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
            state.dragBy(available.y)
            moved = true
            return Offset(0f, available.y)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!moved) return Velocity.Zero
            moved = false
            state.settle(scope, available.y)
            return Velocity(0f, available.y)
        }
    }
}
