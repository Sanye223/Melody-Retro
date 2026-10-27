package com.melody.player

import com.melody.player.ui.components.LYRIC_ALPHA_FLOOR
import com.melody.player.ui.components.LYRIC_ALPHA_NEAR
import com.melody.player.ui.components.LyricRowSpring
import com.melody.player.ui.components.lyricFocusOffsetPx
import com.melody.player.ui.components.lyricLineAlpha
import com.melody.player.ui.components.lyricLineBlurDp
import com.melody.player.ui.components.lyricTrailingPaddingPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词焦点动效的纯逻辑测试（不依赖设备）。
 *
 * 弹簧用闭式解，所以可以直接断言任意时刻的位置，不需要跑帧循环；
 * 透明度、模糊、焦点位置、尾部内边距都是纯函数，覆盖边界值即可。
 */
class LyricFocusMotionTest {

    // ---------------------------------------------------------------- 弹簧

    @Test
    fun `spring settles exactly on target`() {
        val spring = LyricRowSpring(start = 120f, initialVelocity = 0f, target = 0f)
        assertEquals(0f, spring.position(4f), 0.01f)
        assertEquals(0f, spring.velocity(4f), 0.01f)
    }

    @Test
    fun `spring keeps position and velocity when retargeted`() {
        val first = LyricRowSpring(start = 100f, initialVelocity = 0f, target = 0f)
        val position = first.position(0.2f)
        val velocity = first.velocity(0.2f)

        val second = LyricRowSpring(start = position, initialVelocity = velocity, target = -100f)
        // 中途改目标不能跳变，否则换行时会出现一帧的闪动
        assertEquals(position, second.position(0f), 0.0001f)
        assertEquals(velocity, second.velocity(0f), 0.001f)
        assertEquals(-100f, second.position(4f), 0.01f)
    }

    @Test
    fun `spring trajectory does not depend on refresh rate`() {
        fun stepAt(rate: Int): Float {
            var position = 100f
            var velocity = -20f
            repeat(rate) {
                val spring = LyricRowSpring(position, velocity, 0f)
                position = spring.position(1f / rate)
                velocity = spring.velocity(1f / rate)
            }
            return position
        }

        val direct = LyricRowSpring(100f, -20f, 0f).position(1f)
        // 60Hz 与 120Hz 屏幕上走过的轨迹必须一致，否则高刷设备的观感会不一样
        assertEquals(direct, stepAt(60), 0.002f)
        assertEquals(direct, stepAt(120), 0.002f)
    }

    @Test
    fun `spring moves toward target monotonically at start`() {
        val spring = LyricRowSpring(start = 80f, initialVelocity = 0f, target = 0f)
        val samples = listOf(0.02f, 0.05f, 0.1f, 0.2f).map { spring.position(it) }
        samples.zipWithNext().forEach { (a, b) -> assertTrue("距离应持续变小", b < a) }
    }

    // ---------------------------------------------------------------- 视觉层级

    @Test
    fun `focused line is fully opaque`() {
        assertEquals(1f, lyricLineAlpha(distance = 0, focused = true), 0f)
        assertEquals(1f, lyricLineAlpha(distance = 3, focused = true), 0f)
    }

    @Test
    fun `alpha decays with distance down to the floor`() {
        assertEquals(LYRIC_ALPHA_NEAR, lyricLineAlpha(distance = 0, focused = false), 0.0001f)
        assertEquals(0.215f, lyricLineAlpha(distance = 1, focused = false), 0.0001f)
        assertEquals(0.19f, lyricLineAlpha(distance = 2, focused = false), 0.0001f)
        assertEquals(0.14f, lyricLineAlpha(distance = 4, focused = false), 0.0001f)
        // 再远也不会淡到看不见
        assertEquals(LYRIC_ALPHA_FLOOR, lyricLineAlpha(distance = 20, focused = false), 0.0001f)
        // 负距离（焦点行上方）按绝对值算
        assertEquals(0.215f, lyricLineAlpha(distance = -1, focused = false), 0.0001f)
    }

    @Test
    fun `blur only applies from two lines away`() {
        assertEquals(0f, lyricLineBlurDp(distance = 0, percent = 40), 0f)
        assertEquals(0f, lyricLineBlurDp(distance = 1, percent = 40), 0f)
        assertEquals(1.6f, lyricLineBlurDp(distance = 2, percent = 40), 0.0001f)
        assertEquals(2.0f, lyricLineBlurDp(distance = 3, percent = 40), 0.0001f)
        assertEquals(2.4f, lyricLineBlurDp(distance = 4, percent = 40), 0.0001f)
        // 关掉模糊时一律为 0，包括负距离
        assertEquals(0f, lyricLineBlurDp(distance = -4, percent = 0), 0f)
    }

    @Test
    fun `blur radius follows the strength setting`() {
        assertEquals(1.2f, lyricLineBlurDp(distance = 2, percent = 30), 0.0001f)
        assertEquals(3.2f, lyricLineBlurDp(distance = 2, percent = 80), 0.0001f)
        // 超过 100% 的输入被夹住，不会算出离谱的半径
        assertEquals(4.0f, lyricLineBlurDp(distance = 2, percent = 250), 0.0001f)
    }

    // ---------------------------------------------------------------- 布局

    @Test
    fun `focus offset is a quarter of the viewport`() {
        assertEquals(240f, lyricFocusOffsetPx(viewportPx = 1000f, minTopPx = 100f), 0.01f)
    }

    @Test
    fun `focus offset never collapses below the minimum padding`() {
        // 矮视口上 24% 只有 72px，仍要留出 100px，否则焦点行贴到页头
        assertEquals(100f, lyricFocusOffsetPx(viewportPx = 300f, minTopPx = 100f), 0.01f)
    }

    @Test
    fun `focus offset ratio is clamped`() {
        assertEquals(0f, lyricFocusOffsetPx(viewportPx = 800f, minTopPx = 0f, ratio = -1f), 0.01f)
        assertEquals(800f, lyricFocusOffsetPx(viewportPx = 800f, minTopPx = 0f, ratio = 3f), 0.01f)
    }

    @Test
    fun `trailing padding lets the last line reach the focus offset`() {
        assertEquals(
            660f,
            lyricTrailingPaddingPx(
                viewportPx = 1000f,
                focusOffsetPx = 240f,
                trailingLinePx = 100f,
                minBottomPx = 100f,
            ),
            0.01f,
        )
    }

    @Test
    fun `trailing padding keeps its minimum for short lists`() {
        assertEquals(
            100f,
            lyricTrailingPaddingPx(
                viewportPx = 300f,
                focusOffsetPx = 240f,
                trailingLinePx = 100f,
                minBottomPx = 100f,
            ),
            0.01f,
        )
    }
}
