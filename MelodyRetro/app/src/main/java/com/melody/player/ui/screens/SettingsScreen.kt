package com.melody.player.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import com.melody.player.BuildConfig
import com.melody.player.data.AppSettings
import com.melody.player.data.MediaLibraryRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melody.player.data.SettingsRepository
import com.melody.player.data.artwork.ArtworkBackfill
import com.melody.player.ui.NoInsets
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    songCount: Int,
    onThemeMode: (Int) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onSortOrder: (Int) -> Unit,
    onOnlineLyrics: (Boolean) -> Unit,
    onOnlineArtwork: (Boolean) -> Unit,
    onShowTrackInfo: (Boolean) -> Unit,
    onLyricFontScale: (Float) -> Unit,
    onLyricSpacing: (Int) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backfill by ArtworkBackfill.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item { SectionTitle("外观") }

            item {
                SettingsGroup {
                    Text(
                        text = "主题模式",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                    ChoiceRow(
                        icon = Icons.Rounded.Settings,
                        title = "跟随系统",
                        selected = settings.themeMode == SettingsRepository.THEME_SYSTEM,
                        onClick = { onThemeMode(SettingsRepository.THEME_SYSTEM) },
                    )
                    ChoiceRow(
                        icon = Icons.Rounded.LightMode,
                        title = "浅色",
                        selected = settings.themeMode == SettingsRepository.THEME_LIGHT,
                        onClick = { onThemeMode(SettingsRepository.THEME_LIGHT) },
                    )
                    ChoiceRow(
                        icon = Icons.Rounded.DarkMode,
                        title = "深色",
                        selected = settings.themeMode == SettingsRepository.THEME_DARK,
                        onClick = { onThemeMode(SettingsRepository.THEME_DARK) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            item {
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = supportsDynamicColor()) {
                                if (supportsDynamicColor()) onDynamicColor(!settings.dynamicColor)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("动态取色", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (supportsDynamicColor()) {
                                    "使用壁纸生成的 Material You 配色"
                                } else {
                                    "需要 Android 12 及以上系统"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.dynamicColor && supportsDynamicColor(),
                            onCheckedChange = { if (supportsDynamicColor()) onDynamicColor(it) },
                            enabled = supportsDynamicColor(),
                        )
                    }
                }
            }

            item { SectionTitle("歌曲列表") }

            item {
                SettingsGroup {
                    ChoiceRow(
                        icon = Icons.Rounded.MusicNote,
                        title = "按标题排序",
                        selected = settings.sortOrder == MediaLibraryRepository.SORT_TITLE,
                        onClick = { onSortOrder(MediaLibraryRepository.SORT_TITLE) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ChoiceRow(
                        icon = Icons.Rounded.MusicNote,
                        title = "按添加时间排序",
                        selected = settings.sortOrder == MediaLibraryRepository.SORT_RECENT,
                        onClick = { onSortOrder(MediaLibraryRepository.SORT_RECENT) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ChoiceRow(
                        icon = Icons.Rounded.MusicNote,
                        title = "按艺术家排序",
                        selected = settings.sortOrder == MediaLibraryRepository.SORT_ARTIST,
                        onClick = { onSortOrder(MediaLibraryRepository.SORT_ARTIST) },
                    )
                }
            }

            item { SectionTitle("歌词") }

            item {
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOnlineLyrics(!settings.onlineLyrics) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Notes,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("在线歌词", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "优先 QQ音乐，其次 LRCLIB；仅发送歌名/艺术家/时长",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.onlineLyrics,
                            onCheckedChange = onOnlineLyrics,
                        )
                    }
                }
            }

            item { SectionTitle("封面") }

            item {
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOnlineArtwork(!settings.onlineArtwork) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("在线专辑封面", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "缺少内嵌封面时，按专辑名/艺术家联网检索并缓存在本机",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.onlineArtwork,
                            onCheckedChange = onOnlineArtwork,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = backfillSummary(backfill),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (backfill.running) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { backfill.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (backfill.total > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "已检查 ${backfill.processed}/${backfill.total}，新增 ${backfill.fetched} 张",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { ArtworkBackfill.cancel() }) { Text("停止") }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { ArtworkBackfill.start(scope, context) },
                                enabled = settings.onlineArtwork,
                            ) { Text("补全缺失封面") }
                        }
                    }
                }
            }

            item { SectionTitle("正在播放") }

            item {
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowTrackInfo(!settings.showTrackInfo) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tag,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("歌曲信息", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "在播放界面显示文件格式、比特率与采样频率",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.showTrackInfo,
                            onCheckedChange = onShowTrackInfo,
                        )
                    }
                }
            }

            item { SectionTitle("歌词设置") }

            item {
                SettingsGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        LyricSettingSlider(
                            title = "歌词字号",
                            valueLabel = String.format(Locale.US, "%.1f×", settings.lyricFontScale),
                            value = settings.lyricFontScale,
                            range = SettingsRepository.MIN_LYRIC_FONT_SCALE..
                                SettingsRepository.MAX_LYRIC_FONT_SCALE,
                            steps = 6,
                            onChange = onLyricFontScale,
                        )
                        Spacer(Modifier.height(10.dp))
                        LyricSettingSlider(
                            title = "上下间距",
                            valueLabel = "${settings.lyricSpacing} dp",
                            value = settings.lyricSpacing.toFloat(),
                            range = SettingsRepository.MIN_LYRIC_SPACING.toFloat()..
                                SettingsRepository.MAX_LYRIC_SPACING.toFloat(),
                            steps = 10,
                            onChange = { onLyricSpacing(it.roundToInt()) },
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    LyricPreview(
                        fontScale = settings.lyricFontScale,
                        spacingDp = settings.lyricSpacing,
                    )
                }
            }

            item { SectionTitle("媒体库") }

            item {
                SettingsGroup {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "已扫描到 $songCount 首本地歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRescan) { Text("重新扫描") }
                    }
                }
            }

            item { SectionTitle("关于") }

            item {
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Melody Retro", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "版本 ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "本地音乐播放器 · Material Design 3 · 全程离线，不上传任何数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** 设置页里"补全缺失封面"的状态文案。 */
private fun backfillSummary(state: ArtworkBackfill.State): String = when {
    state.running -> "正在检查媒体库，只会给缺少封面的专辑联网检索…"
    state.finished && state.cancelled ->
        "已停止：新增 ${state.fetched} 张，无匹配 ${state.noMatch} 张，失败 ${state.failed} 张"
    state.finished ->
        "上次完成：检查 ${state.total} 张专辑，新增 ${state.fetched} 张，" +
            "无匹配 ${state.noMatch} 张，失败 ${state.failed} 张"
    else -> "只处理本地没有内嵌封面的专辑；已缓存的不会重复下载。"
}

/** 设置页里的滑块行：左侧标题，右侧当前值。 */
@Composable
private fun LyricSettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/** 字号 / 间距的实时预览：高亮行用播放控件主色，和歌词页保持一致。 */
@Composable
private fun LyricPreview(fontScale: Float, spacingDp: Int) {
    val base = MaterialTheme.typography.bodyLarge
    val style = base.copy(
        fontSize = base.fontSize * fontScale,
        lineHeight = base.lineHeight * fontScale,
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "跌倒了就不敢继续往前走",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(vertical = spacingDp.dp),
        )
        Text(
            text = "为什么人要这么的脆弱 堕落",
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = spacingDp.dp),
        )
    }
}
