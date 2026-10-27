<div align="center">

# Melody Retro

**封面是什么颜色，界面就是什么颜色。**

一个完全离线的 Android 本地音乐播放器，基于
[RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer) 的设计语言重做，
主题色由当前歌曲封面实时提取 —— 换一首歌，整个界面跟着变。

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.10-4285F4?logo=jetpackcompose&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-1.4.1-FF6F00)
![Version](https://img.shields.io/badge/version-2.4.6--retro-1f6feb)
![Tests](https://img.shields.io/badge/tests-57%20passed-2ea043)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)


**简体中文** | [English](README.en.md) | [日本語](README.ja.md)

</div>

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [功能](#功能)
- [两个核心链路](#两个核心链路)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [权限](#权限)
- [已知限制](#已知限制)
- [来源与致谢](#来源与致谢)
- [许可证](#许可证)
- [文档](#文档)

---

## 它解决什么问题

安卓上的本地音乐播放器，大部分长这样：白底列表、固定主色、歌词要么没有要么不同步。
这个项目想做三件事：

1. **每首歌都有自己的颜色。** 用 `Palette` 从封面取 `vibrant` 色，驱动播放页的整屏渐变、播放键、
   进度条、迷你播放器和歌词高亮 —— 全部同色，换歌即换色。
2. **不用手动准备歌词和封面。** 手机里那些从各处下载来的 MP3，一半没有 ID3 标签、没有封面、
   没有歌词。播放器自己去 QQ 音乐、iTunes、LRCLIB 找，找到就缓存，下次不再联网。
3. **界面别抢戏。** 歌词左对齐、点击瞬亮 0.4 秒后淡出；当前行停在视口 **24%** 处不再动，
   换行时各行按距离错峰追上去，形成一道自上而下的波浪，远处的行渐淡并轻微虚化。
   所有缩放只改绘制矩阵，不参与布局，绝不跳行。

## 功能

| 分类 | 说明 |
| --- | --- |
| 本地扫描 | `MediaStore` 扫描全部音频（MP3 / FLAC / M4A / OGG / WAV / AIFF），过滤铃声等短音频，支持按标题 / 添加时间 / 艺术家排序 |
| 浏览 | 歌曲列表（实时搜索、当前播放高亮）、专辑自适应网格、艺术家圆形列表，各自带详情页 |
| 播放 | 播放 / 暂停 / 上一曲 / 下一曲 / 进度拖动 / 随机 / 单曲循环 / 列表循环 |
| 后台 | `MediaSessionService` + ExoPlayer，通知栏与锁屏控制、音频焦点、拔耳机自动暂停、息屏继续放 |
| 歌词 | 同名 `.lrc` → 音频内嵌标签 → QQ 音乐 → LRCLIB 四级加载，逐行波浪滚动、点击跳转、结果落盘缓存 |
| 封面 | 内嵌封面优先，缺失时按 歌名 / 专辑 / 艺术家 联网检索，打分筛选、图片校验、负缓存 |
| 歌曲信息 | 歌手名下方一行小字：`FLAC • 24-bit • 1411 kb/s • 48 kHz`，可开关 |
| 外观 | 封面取色、浅色 / 深色 / 跟随系统、手机 `NavigationBar` / 平板 `NavigationRail` 自适应 |
| 设置 | 歌词字号 0.8~1.5×（默认 1.2×）、行距 2~24dp（默认 4dp，带实时预览）、歌曲信息开关、两个联网开关，全部 DataStore 持久化 |

## 两个核心链路

**歌词**：同名 `.lrc` 优先，其次读音频自带的歌词标签，都没有才联网；全失败才显示"暂无歌词"。
标签里的歌词通常带时间轴，所以很多歌**完全不用联网就能滚动**。

```mermaid
flowchart LR
    A[开始播放] --> B{同名 .lrc?}
    B -->|有| C[本地歌词<br/>显示来源「本地歌词文件」]
    B -->|没有| K{"音频内嵌歌词?"}
    K -->|有且带时间轴| L[内嵌歌词<br/>来源「内嵌歌词」<br/>离线、不联网]
    K -->|没有或只有纯文本| D{在线歌词开关}
    D -->|关闭| E[暂无歌词<br/>提示到设置里开启]
    D -->|开启| F[QQ 音乐搜索<br/>多个候选依次取词]
    F -->|命中| G[取词 + 解析 LRC]
    F -->|没搜到| H[LRCLIB 兜底]
    H -->|命中| G
    H -->|没搜到| E
    G --> I[写 cacheDir/lyrics/ 缓存]
    I --> J[逐行波浪滚动 + 焦点行分层]
```

> 只拿到纯文本歌词时（没有时间轴）仍会显示，但不会滚动，来源标签上会注明「无时间轴」。
> QQ 音乐那一步会依次试多个候选，优先取带时间轴的那份 —— 搜索结果里常有同名翻唱顶在前面。

**封面**：任何一步命中就停，绝不为了"更好看"重复联网。

```mermaid
flowchart LR
    A[界面要显示封面] --> B{内存 LRU?}
    B -->|命中| Z[渲染]
    B -->|未命中| C{"内嵌图 / 磁盘缓存?"}
    C -->|命中| Z
    C -->|未命中| D{"开关开启<br/>且不在 7 天负缓存里?"}
    D -->|否| Y[音符占位图]
    D -->|是| E["QQ 音乐 → iTunes"]
    E -->|命中| F["图片校验<br/>HTTP 200 + image/* + 6KB~4MB + 魔数"]
    F --> G[落盘 + 进内存缓存]
    G --> Z
    E -->|未命中| H[记负缓存 7 天]
    H --> Y
```

两个链路的完整策略（匹配打分表、缓存目录、并发限制、降级话术）见
[`MelodyRetro/README.md`](MelodyRetro/README.md) 第 4、5 章。

## 快速开始

**环境要求**：JDK 17、Android SDK Platform 35、Build Tools 35.0.0。不需要装 Android Studio。

```bash
git clone https://github.com/Sanye223/Melody-Retro.git
cd Melody-Retro

# 一键装 JDK 17 + Android SDK 到项目内 .buildtools/（Windows）
powershell -ExecutionPolicy Bypass -File tools\setup-buildtools.ps1

# 构建
cd MelodyRetro
./gradlew.bat --no-daemon --console=plain assembleDebug
```

> 已有 Android Studio 的话跳过第 2 步，只需保证 `MelodyRetro/local.properties` 里的
> `sdk.dir` 指向你的 SDK（compileSdk 35 / build-tools 35.0.0）。

装到手机（首次启动需要授予「音乐和音频」权限）：

```bash
adb install -r MelodyRetro/dist/Melody-Retro-2.4.6-release.apk
```

> Release 包用的是 **debug 签名**，目的是能直接装上体验。要正式分发请换成你自己的 keystore。

跑测试（57 项，其中 6 项会真实联网，断网自动跳过）：

```bash
./gradlew.bat testDebugUnitTest
```

## 项目结构

```
.
├── MelodyRetro/
│   ├── app/src/main/java/com/melody/player/     # 源码（namespace 仍是 com.melody.player）
│   │   ├── data/          # MediaStore 扫描、封面、歌词、歌曲信息、DataStore
│   │   ├── playback/      # MediaSessionService + PlayerManager
│   │   └── ui/            # Compose 界面，theme/RetroColors.kt 是封面取色
│   ├── app/src/test/      # 57 项单元测试（含真实联网冒烟）
│   ├── tools/             # 真机验证脚本 + 测试音频
│   └── dist/              # 打好的 APK
├── tools/                 # 环境自举 + 分享包打包脚本
├── HANDOFF.md             # 给接手者 / AI 的说明书
└── backup/                # 上一版 2.3.0 归档（.gitignore，未推送）
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（BOM 2024.10.01，Material 3 1.3.x） |
| 播放 | Media3 / ExoPlayer 1.4.1（`ExoPlayer` + `MediaSession` + `MediaSessionService`） |
| 导航 / 状态 | Navigation Compose + ViewModel + `StateFlow` |
| 持久化 | DataStore Preferences |
| 图片 | Coil（加载）+ Palette（取色） |
| 网络 | 原生 `HttpURLConnection`，无三方库 |
| 架构 | 单 Activity、MVVM，无后端无账号 |

## 权限

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_AUDIO`（13+）/ `READ_EXTERNAL_STORAGE`（≤12） | 扫描本地音频 |
| `POST_NOTIFICATIONS`（13+） | 媒体播放通知 |
| `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)` | 后台播放 |
| `WAKE_LOCK` | 息屏继续播放 |
| `INTERNET` | 仅用于在线歌词与在线封面；两个开关都关掉后不发起任何网络请求 |

在线请求只发送歌曲的**标题 / 艺术家 / 专辑 / 时长**，不发送设备标识、账号或播放记录。
音频、封面、播放列表全部留在本机。

## 已知限制

- 只做了**播放**：没有播放列表管理、均衡器、标签编辑、桌面小部件、睡眠定时。
- 在线歌词与封面依赖第三方公开接口（QQ 音乐 / iTunes / LRCLIB），**对方改动就失效**，
  接口细节与踩过的坑记录在 [`HANDOFF.md`](HANDOFF.md) 第 3、4 节。
- 音乐文件本身没有内嵌封面、文件名也不含歌名和艺术家时，猜不准就只能显示占位图。
- 只在个人真机上验证过，没有做多机型 / 多分辨率测试。

## 来源与致谢

本项目的**视觉语言与交互规范来自 [RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer)**。
下面这些都是照它的实现对齐的：

| 照它实现的 | 出处（原项目文件） |
| --- | --- |
| 播放控制区顺序 `repeat → previous → playPause → next → shuffle`，播放键为圆形 FAB | `fragment_adaptive_player_playback_controls.xml` |
| 进度条两侧各放一个时间标签，时间与滑杆同一行 | 同上 |
| 迷你播放器 56dp 高、40dp 封面、只显示一行歌名 | `fragment_mini_player.xml` |
| 歌曲信息 `FLAC • 24-bit • 1411 kb/s • 48 kHz` 单行居中小字 | `getSongInfo()` |
| 专辑 / 艺术家紧凑网格（10dp 圆角、10dp 间距） | 各 item 布局 |

**关于实现方式**：界面用 Kotlin + Jetpack Compose 重新实现 —— 原项目是 `compileSdk 36` +
KSP/Realm 的传统 View + XML 架构，代码无法直接复用，本仓库**没有包含它的 Java / View / XML 代码**，
对齐的是设计规范与布局参数。

感谢 RetroMusicPlayer 的作者与所有贡献者 —— 这套设计语言值得被单独做一次实现。

### 歌词动效

歌词页的焦点行滚动与层级参考了 **[Kifranei/Halcyon](https://github.com/Kifranei/Halcyon)**（Apache-2.0）：

| 本仓库文件 | 上游来源 |
| --- | --- |
| `ui/components/LyricFocusMotion.kt` | `ui/player/ReferenceLyricMotion.kt` |
| `ui/screens/NowPlayingScreen.kt` 的 `LyricsContent` | `ui/player/AppleMusicLyricsView.kt` + `AppleMusicLyricLine.kt` |

移植的是**算法与数值**：闭式弹簧参数、`CubicBezier(.4, .1, 0, 1)` 曲线、45ms→450ms 的过渡时长、
按行距 30ms 错峰、「布局瞬移 + 逐行弹簧」的滚动模型，以及 1.0 / 0.98 的缩放与
0.24→0.13 的透明度层级。差异在于：本项目没有逐字时间轴，所以不做逐字抬起与卡拉 OK 填充，
焦点行的高亮色沿用封面取色的主题色而不是单一正文色。

Halcyon 以 Apache License 2.0 发布，其署名与许可声明按该许可证要求保留在源文件头部。

## 许可证

本项目**以 [GPL-3.0](LICENSE) 开源**，与 RetroMusicPlayer 保持一致。

```
Copyright (C) 2026 Sanye223

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

这意味着你可以自由地使用、修改、再分发，包括商用；但**分发衍生作品时必须同样以 GPL-3.0 开源**，
并保留版权声明。完整条款见 [`LICENSE`](LICENSE)。

上游项目 [RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer) 同样以 GPL-3.0 发布。

## 文档

| 文件 | 内容 |
| --- | --- |
| [`MelodyRetro/README.md`](MelodyRetro/README.md) | 完整技术文档：设计语言与取色实现、歌词容错矩阵、封面打分表、缓存与 LRU、工程结构 |
| [`HANDOFF.md`](HANDOFF.md) | 交接说明：三步上手、代码地图、外部接口的四个坑、7 条改动不变式、待验证项 |


---

<div align="center">

<sub><code>com.melody.retro</code> · versionCode 11 · 2.4.6-retro</sub>

</div>
