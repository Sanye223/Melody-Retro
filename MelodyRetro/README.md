# Melody Retro

Melody 的第二个变体：**功能与 1.2.1 完全一致**，视觉层按 [RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer)
的设计语言重做。

- 包名 `com.melody.retro`，应用名「Melody Retro」，可与「旋律」同时安装、互不影响。
- 原来的 `../Melody`（com.melody.player，1.2.1）没有改动，保持原样。

## 与 Melody 1.2.1 的差异

| 位置 | Melody 1.2.1 | Melody Retro |
| --- | --- | --- |
| 配色 | MD3 动态取色（系统壁纸）固定一套 | **跟随当前歌曲封面取色**，换歌即换色 |
| 正在播放背景 | 纯 `surface` 平铺 | 封面主色 → `surface` 的整屏竖向渐变，切歌 560ms 过渡 |
| 封面 | 28dp 圆角、无阴影 | 16dp 圆角 + 18dp 投影，居中最大 400dp |
| 播放按钮 | MD3 `FilledIconButton` 78dp | **64dp 圆形 FAB，底色取自封面主色** |
| 控制按钮顺序 | 随机 / 上一曲 / 播放 / 下一曲 / 循环 | **循环 / 上一曲 / 播放 / 下一曲 / 随机**（对齐 Retro） |
| 进度条 | 标准 M3 Slider，时间在下方两端 | **4dp 细轨道 + 拖动时放大的圆点**，时间在滑杆左右同一行 |
| 顶栏 | 居中「正在播放 / 专辑名」 | 左对齐「歌名 / 艺术家」，背景透明叠在渐变上 |
| 迷你播放器 | MD3 圆角卡片、浮起 3dp | **通栏色块（封面主色）、无圆角无阴影、56dp 高**，40dp 封面 + 单行歌名 + 上一曲/播放/下一曲 |
| 专辑 / 艺术家卡片 | 18dp 圆角、14dp 间距 | 10dp 圆角、10dp 间距，更贴近 Retro 的紧凑网格 |
| 封面来源 | 只读本地内嵌封面 | 内嵌缺失时联网补全（QQ 音乐 → iTunes），**取到的封面同时驱动播放页配色** |
| 歌词高亮色 | 主题 `primary` | **封面取色的 `accent`**，和播放键/进度条永远同色 |

歌词页的逻辑（本地 `.lrc` → QQ 音乐 → LRCLIB、滚动、点击瞬亮、左对齐）与 1.2.1 相同。

在线封面的检索、打分、校验、缓存、降级策略与主版本完全一致，见 `../Melody/README.md` 第 5 节。
在 Retro 版本里多一层收益：`RetroColors` 是从封面字节取色的，所以**补全封面之后，播放页渐变、
进度条、播放键与迷你播放器的颜色会跟着这张新封面一起变**。

歌曲信息与歌词字号、间距设置同样可用，实现与主版本一致，见 `../Melody/README.md` 第 6 节。
本版本的歌曲信息**就是 Retro 的排版**（单行 ` • ` 分隔、12sp 居中小字、位于歌名 / 歌手下方），
只是把 Retro 用 JAudiotagger 读的参数换成了 `MediaExtractor` + 文件头解析：

```
FLAC • 24-bit • 1411 kb/s • 48 kHz
```

歌词页同样有「当前行平滑放大 1.10 倍」的动效（`graphicsLayer` 缩放，不参与布局，绝不跳行）。差别只有一处：歌词高亮与点击柔光用的是**本版本的 `accent`
（封面取色）**，所以换一张封面，歌词的高亮色也会跟着换。

## 取色实现

`ui/theme/RetroColors.kt`：

```kotlin
rememberRetroColors(albumId) ->
    ArtworkStore.artworkBytes()        // 复用已有的封面字节缓存
    BitmapFactory(inSampleSize)        // 缩到 ~96px 再取色，避免解码大图
    Palette.from(bitmap)
        .vibrantSwatch ?: mutedSwatch ?: dominantSwatch ?: lightVibrantSwatch ?: darkMutedSwatch
    -> 亮度修正（浅色主题压低过亮的色、深色主题提亮过暗的色）
    -> accent / onAccent / gradientTop / gradientMiddle / gradientBottom / miniBackground / onMini
```

要点：

- 取 `vibrant` 优先而不是 `dominant`：封面大面积是黑色时 `dominant` 会得到一片死黑，`vibrant` 才是"这张专辑的颜色"。
- 取色在 `Dispatchers.IO` 上跑，`LaunchedEffect(albumId)` 触发；取不到就回退到 MD3 的 `primary`，不会闪烁或空屏。
- 迷你播放器和播放页共用同一份 `RetroColors`，所以条状色块和整屏渐变永远一致。
- `onAccent` / `onMini` 按背景亮度自动选黑或白，避免浅色封面配白字看不清。

## 设计依据（来自 RetroMusicPlayer v7.0.3 源码）

参考仓库已克隆到上级目录 `.refs/RetroMusicPlayer`，本次对齐的几处：

- `res/layout/fragment_adaptive_player_playback_controls.xml`：控制区顺序为
  repeat → previous → playPause → next → shuffle，播放键是 `FloatingActionButton`，
  进度条两侧各有一个时间 `TextView`。
- `res/layout/fragment_mini_player.xml`：`56dp` 高、`40dp` 封面（`cardCornerRadius="6dp"`）、
  **只有一行标题**（`miniPlayerTitle`，marquee）、右侧 `previous/playPause/next`。
- 播放页右键顺序与时间标签位置即照此实现。

## 构建

```bash
export JAVA_HOME=<JDK 17>
export ANDROID_HOME=<Android SDK 35>
./gradlew assembleDebug        # 或直接 ./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/`，已签名版本另存一份在 `dist/`：

| 文件 | 说明 |
| --- | --- |
| `dist/Melody-Retro-2.0.0-debug.apk` | 21.8 MB，未混淆 |
| `dist/Melody-Retro-2.0.0-release.apk` | 14.9 MB，R8 压缩，debug 证书签名 |

## 已知边界

- 只移植了 **视觉与交互风格**，没有搬 RetroMusicPlayer 的代码与功能（播放列表管理、均衡器、
  标签编辑、桌面小部件、睡眠定时等都没有）。
- 原仓库本身是 `compileSdk 36` + KSP/Realm + 需要 `public.properties` 里的 Google Play
  授权 key 才能构建，本次没有构建它，只作为设计参考。
