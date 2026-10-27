package com.melody.player

import com.melody.player.ui.components.SHEET_DISMISS_THRESHOLD
import com.melody.player.ui.components.sheetProgressAfterDrag
import com.melody.player.ui.components.sheetSettleTarget
import com.melody.player.ui.screens.seekProgressAtX
import com.melody.player.ui.screens.seekThumbCenterX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页两组纯几何/手势换算的测试（不需要设备）。
 *
 * 进度条那几条针对的是「圆点在 0% / 100% 被裁、拖动位置对不上」这一类偏移问题。
 */
class PlaybackGestureTest {

    private val width = 1000f
    private val inset = 24f

    // ------------------------------------------------------------------ 进度条

    @Test
    fun `圆点在两端也不会被裁掉`() {
        // 圆心距两端正好留出一个半径，圆点整个落在可视区内
        assertEquals(inset, seekThumbCenterX(0f, width, inset), 0.01f)
        assertEquals(width - inset, seekThumbCenterX(1f, width, inset), 0.01f)
    }

    @Test
    fun `进度与位置互为逆运算`() {
        for (step in 0..10) {
            val progress = step / 10f
            val x = seekThumbCenterX(progress, width, inset)
            assertEquals(progress, seekProgressAtX(x, width, inset), 0.0001f)
        }
    }

    @Test
    fun `轨道中点对应百分之五十`() {
        assertEquals(0.5f, seekProgressAtX(width / 2f, width, inset), 0.0001f)
    }

    @Test
    fun `手指落在两端之外会被夹到零和一`() {
        assertEquals(0f, seekProgressAtX(-80f, width, inset), 0f)
        assertEquals(1f, seekProgressAtX(width + 80f, width, inset), 0f)
        // 圆点内侧的极窄拖动区间也要能给到端点值
        assertEquals(0f, seekProgressAtX(inset - 1f, width, inset), 0f)
        assertEquals(1f, seekProgressAtX(width - inset + 1f, width, inset), 0f)
    }

    @Test
    fun `宽度退化时不会出现除零`() {
        val tiny = 10f
        val bigInset = 24f
        val progress = seekProgressAtX(5f, tiny, bigInset)
        assertTrue(progress in 0f..1f)
        assertEquals(bigInset, seekThumbCenterX(0f, tiny, bigInset), 0.01f)
    }

    // ------------------------------------------------------------------ 抽屉手势

    @Test
    fun `向下拖收起向上拖展开`() {
        val height = 2000f
        assertEquals(0.25f, sheetProgressAfterDrag(0f, 500f, height), 0.0001f)
        assertEquals(0f, sheetProgressAfterDrag(0.25f, -500f, height), 0.0001f)
    }

    @Test
    fun `拖动进度始终夹在零和一之间`() {
        assertEquals(1f, sheetProgressAfterDrag(0.9f, 900f, 2000f), 0f)
        assertEquals(0f, sheetProgressAfterDrag(0.1f, -900f, 2000f), 0f)
    }

    @Test
    fun `没量到高度时不动`() {
        assertEquals(0.4f, sheetProgressAfterDrag(0.4f, 500f, 0f), 0f)
        // 高度为 0 时速度换算不出比例，只能按位置判断；没拖够就回弹到起点
        assertEquals(0f, sheetSettleTarget(0.1f, 0f, 5000f, 0f), 0f)
    }

    @Test
    fun `拖过阈值收起没到则回弹`() {
        val height = 2000f
        assertTrue(SHEET_DISMISS_THRESHOLD < 0.5f)
        assertEquals(1f, sheetSettleTarget(0.5f, 0f, 0f, height), 0f)
        assertEquals(0f, sheetSettleTarget(0.2f, 0f, 0f, height), 0f)
    }

    @Test
    fun `从迷你播放器上滑同样只需拖过阈值`() {
        val height = 2000f
        // 起点是「收起」：上滑 40% → 展开
        assertEquals(0f, sheetSettleTarget(0.6f, 1f, 0f, height), 0f)
        // 只上滑 10% → 回弹回收起
        assertEquals(1f, sheetSettleTarget(0.9f, 1f, 0f, height), 0f)
    }

    @Test
    fun `甩动优先于位置`() {
        val height = 2000f
        // 只拖了一点点但往上甩得很快：应该展开，而不是按位置回弹收起
        assertEquals(0f, sheetSettleTarget(0.18f, 0f, -3000f, height), 0f)
        // 反过来：只拖了一点点但往下甩：收起
        assertEquals(1f, sheetSettleTarget(0.05f, 0f, 3000f, height), 0f)
    }
}
