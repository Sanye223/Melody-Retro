package com.melody.player

import com.melody.player.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词默认值从旧默认（1.0× / 9dp）升到新默认（1.2× / 4dp）时的判断逻辑。
 *
 * 迁移只该改「从未写过」或「仍停在旧默认」的项；用户自己调过的值必须原样保留，
 * 否则等于替用户做决定。这里把这条边界钉死。
 */
class LyricDefaultsMigrationTest {

    @Test
    fun `missing value counts as old default`() {
        assertTrue(SettingsRepository.isLegacyDefaultFontScale(null))
        assertTrue(SettingsRepository.isLegacyDefaultSpacing(null))
    }

    @Test
    fun `untouched values are upgraded`() {
        assertTrue(SettingsRepository.isLegacyDefaultFontScale(1.0f))
        assertTrue(SettingsRepository.isLegacyDefaultSpacing(9))
    }

    @Test
    fun `user adjusted values are left alone`() {
        assertFalse(SettingsRepository.isLegacyDefaultFontScale(1.5f))
        assertFalse(SettingsRepository.isLegacyDefaultFontScale(0.8f))
        assertFalse(SettingsRepository.isLegacyDefaultSpacing(20))
        assertFalse(SettingsRepository.isLegacyDefaultSpacing(2))
    }

    @Test
    fun `new defaults do not trigger another migration`() {
        // 迁移写入新值后再跑一次，不能再次命中，否则每次启动都会重写
        assertFalse(SettingsRepository.isLegacyDefaultFontScale(SettingsRepository.DEFAULT_LYRIC_FONT_SCALE))
        assertFalse(SettingsRepository.isLegacyDefaultSpacing(SettingsRepository.DEFAULT_LYRIC_SPACING))
    }

    @Test
    fun `defaults sit inside the slider range and on a font step`() {
        assertTrue(SettingsRepository.DEFAULT_LYRIC_FONT_SCALE in SettingsRepository.MIN_LYRIC_FONT_SCALE..SettingsRepository.MAX_LYRIC_FONT_SCALE)
        assertTrue(SettingsRepository.DEFAULT_LYRIC_SPACING in SettingsRepository.MIN_LYRIC_SPACING..SettingsRepository.MAX_LYRIC_SPACING)

        // 字号滑块 0.8~1.5 共 7 段（steps = 6），默认值必须落在档位上，
        // 否则拖一下就会跳变
        val segments = 7f
        val step = (SettingsRepository.MAX_LYRIC_FONT_SCALE - SettingsRepository.MIN_LYRIC_FONT_SCALE) / segments
        val offset = (SettingsRepository.DEFAULT_LYRIC_FONT_SCALE - SettingsRepository.MIN_LYRIC_FONT_SCALE) / step
        assertEquals(offset, Math.round(offset).toFloat(), 0.001f)
    }

    @Test
    fun `spacing default is tighter and font default is larger than before`() {
        assertTrue(SettingsRepository.DEFAULT_LYRIC_SPACING < 9)
        assertTrue(SettingsRepository.DEFAULT_LYRIC_FONT_SCALE > 1.0f)
    }
}
