package com.melody.player

import com.melody.player.ui.screens.searchCollapseAt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌曲页折叠搜索栏的几何换算。
 *
 * 搜索栏的位置/宽度都由「收缩进度」连续插值而来，所以这里的映射一旦错，
 * 表现就是「搜索栏飞过头」或者「滚到底了还没收完」。
 */
class SearchHeaderTest {

    private val distance = 64f // 搜索栏 56dp + 间距 8dp

    @Test
    fun `顶部时完全展开`() {
        assertEquals(0f, searchCollapseAt(0f, distance), 0f)
    }

    @Test
    fun `滚到一半就收一半`() {
        assertEquals(0.5f, searchCollapseAt(32f, distance), 0.0001f)
        assertEquals(0.25f, searchCollapseAt(16f, distance), 0.0001f)
    }

    @Test
    fun `滚满距离刚好收成圆形按钮`() {
        assertEquals(1f, searchCollapseAt(distance, distance), 0f)
        // 再往下滚不会超额，否则搜索栏会被推出标题栏
        assertEquals(1f, searchCollapseAt(distance * 5f, distance), 0f)
    }

    @Test
    fun `回滚原路展开`() {
        // 进度只由「当前滚动位置」决定，与方向无关：同一个位置上进度相同，回滚不会跳变
        val forward = searchCollapseAt(48f, distance)
        val backward = searchCollapseAt(48f, distance)
        assertEquals(forward, backward, 0f)
        assertEquals(0.75f, forward, 0.0001f)
    }

    @Test
    fun `收缩距离为零时保持展开`() {
        assertEquals(0f, searchCollapseAt(120f, 0f), 0f)
    }
}
