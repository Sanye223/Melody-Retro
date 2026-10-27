# Melody Retro — RetroMusicPlayer 风格的本地音乐播放器

一个纯本地播放的 Android 音乐播放器。业务逻辑（扫描、播放、歌词、在线封面）就是标准做法，
**视觉层完全按 [RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer) 的设计语言重做**：
配色由当前歌曲封面决定、整屏竖向渐变、通栏迷你播放器、紧凑网格。

- 包名 `com.melody.retro`，应用名「Melody Retro」，版本 **2.4.5-retro**，目录 `MelodyRetro/`
- 最低支持 **Android 7.0（API 24）**，目标 **Android 15（API 35）**
- 这是项目当前**唯一**的版本。早期基于 Material Design 3 动态取色的「旋律」（`com.melody.player`）
  已于 2026-10-27 彻底移除（代码、git 历史、本机备份、手机安装包全部清除），
  它独有的测试与 `tools/` 在删除前已迁移到本项目

---

## 0. 设计语言：照着 RetroMusicPlayer 重做

这是本版本存在的理由，也是它和"一个普通 Compose 播放器"的全部区别。核心变化是一句话：
**主题色不再来自壁纸，而是来自当前这首歌的封面。**

### 0.1 与 Material Design 3 原版的差异

| 位置 | MD3 原版 | Melody Retro |
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

封面补全在 Retro 版本里多一层收益：`RetroColors` 是从封面字节取色的，所以
**补全封面之后，播放页渐变、进度条、播放键与迷你播放器的颜色会跟着这张新封面一起变**。

### 0.2 取色实现

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

### 0.3 设计依据（来自 RetroMusicPlayer v7.0.3 源码）

参考仓库克隆在上级目录 `.refs/RetroMusicPlayer`（不入库），本次对齐的几处：

- `res/layout/fragment_adaptive_player_playback_controls.xml`：控制区顺序为
  repeat → previous → playPause → next → shuffle，播放键是 `FloatingActionButton`，
  进度条两侧各有一个时间 `TextView`。
- `res/layout/fragment_mini_player.xml`：`56dp` 高、`40dp` 封面（`cardCornerRadius="6dp"`）、
  **只有一行标题**（`miniPlayerTitle`，marquee）、右侧 `previous/playPause/next`。
- 播放页右键顺序与时间标签位置即照此实现。

**许可证**：本项目**以 [GPL-3.0](../LICENSE) 开源**，与 RetroMusicPlayer 保持一致。界面用
Kotlin + Jetpack Compose 重新实现（原项目是 `compileSdk 36` + KSP/Realm 的传统 View + XML 架构，
代码无法直接复用），对齐的是设计规范与布局参数。

### 0.4 已知边界

- 只移植了 **视觉与交互风格**，没有搬 RetroMusicPlayer 的代码与功能（播放列表管理、均衡器、
  标签编辑、桌面小部件、睡眠定时等都没有）。
- 原仓库本身是 `compileSdk 36` + KSP/Realm + 需要 `public.properties` 里的 Google Play
  授权 key 才能构建，本次没有构建它，只作为设计参考。

---

## 1. 功能

| 分类 | 说明 |
| --- | --- |
| 本地扫描 | 通过 `MediaStore` 扫描设备中所有音频（MP3 / FLAC / M4A / OGG / WAV …），自动过滤铃声等短音频，支持按标题 / 添加时间 / 艺术家排序 |
| 歌曲列表 | 大标题 App Bar + 滚动收缩、实时搜索（歌名 / 艺术家 / 专辑）、当前播放高亮与波形图标 |
| 专辑 | 自适应网格（`LazyVerticalGrid` + `GridCells.Adaptive`），点击进入专辑详情 |
| 艺术家 | 圆形封面列表，点击进入艺术家详情 |
| 播放控制 | 播放 / 暂停 / 上一曲 / 下一曲 / 进度条拖动 / 随机播放 / 单曲循环 / 列表循环 |
| 滚动歌词 | 解析 LRC（含 `[mm:ss.xx]` 时间轴、offset 偏移），随播放进度逐行滚动并高亮，点击歌词行可跳转 |
| 在线歌词 | 对接 **QQ 音乐**（主源）与 LRCLIB（兜底），按 标题 + 艺术家 + 时长 自动匹配同步歌词，结果本地缓存，可开关与手动重搜 |
| 在线封面 | 内嵌封面缺失时，按专辑名/艺术家从 **QQ 音乐 → iTunes** 检索并缓存，支持批量补全，可开关 |
| 歌曲信息 | 歌手名下方一行小字显示 **格式 • 位深 • 码率 • 采样率**（Retro 同款排版，FLAC/WAV/AIFF 读文件头取位深，码率缺失时按体积估算），开关可关 |
| 歌词设置 | 字号 0.8~1.5×、行间距 2~24dp 可调，带实时预览；高亮色跟随播放控件主色；**焦点行停在视口 24%，其余行缩小、渐隐并虚化**（只改绘制矩阵） |
| 界面 | **封面取色**（换歌即换色，见第 0 章）、浅色 / 深色 / 跟随系统、手机底部 `NavigationBar`、平板 / 横屏 `NavigationRail` |
| 后台播放 | `MediaSessionService` + ExoPlayer，通知栏 / 锁屏媒体控制、音频焦点、拔耳机自动暂停 |

## 2. 技术栈

- **Kotlin 2.0.21** + **Jetpack Compose**（BOM 2024.10.01，Material 3 1.3.x）
- **Media3 / ExoPlayer 1.4.1**：`ExoPlayer` + `MediaSession` + `MediaSessionService`
- **Navigation Compose** + **DataStore**（主题 / 排序 / 歌词显示偏好持久化）
- **Coil** 加载专辑封面；**Palette**（`palette-ktx`）从封面取色
- 单 Activity、MVVM
- 网络只在「在线歌词」和「在线封面」两处使用（QQ 音乐 / iTunes / LRCLIB），两个开关都关掉后不发起任何请求

## 3. 构建

环境要求：JDK 17、Android SDK Platform 35、Build Tools 35.0.0。

```bash
# 1) 配置 SDK 路径（Android Studio 打开工程会自动生成）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 构建 Debug 包
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3) 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
# 4) 跑单元测试（含一条真实访问 LRCLIB 的联网冒烟测试；无网络时自动跳过）
./gradlew testDebugUnitTest
#    若本机需要代理：./gradlew testDebugUnitTest -PproxyHost=127.0.0.1 -PproxyPort=8080
```

也可以直接用 Android Studio（Ladybug 或更新版本）打开 `MelodyRetro` 目录，Sync 后点击 Run。
`release` 构建使用了 debug 签名以便直接安装体验，正式发布请替换为自己的签名配置。

最低支持 **Android 7.0（API 24）**，目标 **Android 15（API 35）**。

## 4. 歌词：本地优先 + 在线兜底

歌词按以下顺序加载，命中即返回：

```
1. 同名 .lrc 文件（本地）
2. LRCLIB 在线歌词库（https://lrclib.net）
3. 都没有 → 「暂无歌词」占位
```

### 4.1 本地歌词

```
/sdcard/Music/周杰伦 - 稻香.mp3
/sdcard/Music/周杰伦 - 稻香.lrc     ← 同名 .lrc 即可自动显示
```

- 支持标准 LRC：`[00:12.34]歌词内容`、一行多时间标签、`[offset:±毫秒]` 偏移
- 自动识别 `UTF-8` / `GB18030` 编码（含 BOM）
- 若 `.lrc` 只有纯文本没有时间轴，则以静态歌词展示（不滚动）

> Android 13 起分区存储只授予"音频类文件"的读取权限，公共目录下的 `.lrc`
> 可能不可读；此时会自动降级到在线歌词。

### 4.2 在线歌词（QQ 音乐为主，LRCLIB 兜底）

```
QQ 音乐搜索 → 选歌 → 取歌词（标准 LRC）
        └─ 没搜到 / 没歌词 → LRCLIB 兜底
```

#### 主源：QQ 音乐

用 QQ 音乐网页版自己的两个公开 CGI（**无需登录、无需 API Key**），
必须带 `Referer: https://y.qq.com/`，实现见 `data/lyrics/QqMusicClient.kt`：

```
# 1) 搜索：标题 + 艺术家
GET https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w=<稻香 周杰伦>&format=json&n=10&p=1&g_tk=5381
→ data.song.list[]: { songmid, songname, singer[].name, albumname, interval }

# 2) 取歌词：songmid 来自上一步
GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>&format=json&nobase64=1&g_tk=5381
→ { retcode: 0, lyric: "[00:30.94]对这个世界如果你有太多的抱怨\n..." }
```

要点：

- `nobase64=1` 才会返回明文 LRC；万一仍拿到 base64，客户端会自动解码后再解析。
- QQ 部分接口会把 `:` 转义成 `&#58;`（变成 `[00&#58;30.94]`），客户端统一还原数字/命名实体，
  否则时间轴会全部解析失败 —— 这是这类接口最常见的坑。
- `retcode != 0`（如 `1101`）表示库里没有这首歌的歌词，按「无歌词」处理而不是报错。
- `interval` 是秒级时长，用于在多个同名版本（原版 / Demo / Live）里挑最接近本地文件时长的那首。

#### 备用源：LRCLIB

QQ 音乐没有结果时回退 [LRCLIB](https://lrclib.net)（开放公共歌词库），
主要补海外曲目；若它也不可用只影响兜底，不影响主流程。

#### 匹配策略（两个源一致）

1. 优先「曲名（去空格/标点、小写归一化）完全相同 + 歌手互相包含」的严格匹配；
2. 无严格匹配时放宽为全部候选，按**与本地音频时长最接近**排序；
3. 排除纯音乐（instrumental）与无正文的记录。

#### 缓存

- 歌词原文写入 `cacheDir/lyrics/<sha1(artist|title|album)>.lrc`，命中的数据源与曲目信息写在
  同名 `.source` 文件里，因此二次播放**不再请求网络**，标签仍能显示「QQ音乐 · 稻香 - 周杰伦」。
- 内存里另有一层 LRU 缓存（24 首）。

#### 失败与容错

| 情况 | 用户看到 |
| --- | --- |
| 关闭了「在线歌词」 | 「暂无歌词」+「在线歌词已在设置中关闭」 |
| 搜索无结果 | 「QQ音乐没有搜到「稻香」」+ 重新搜索按钮 |
| 搜到歌但没歌词（retcode 1101） | 「QQ音乐里这首歌暂无歌词」 |
| 接口异常（HTTP 5xx / code != 0） | 「歌词服务返回异常（HTTP 500）」 |
| 无网络 / DNS 失败 | 「网络请求失败，请检查网络后重试」（此时不再浪费时间试备用源） |
| 有本地 `.lrc` | 直接使用本地歌词，标签显示「本地歌词文件」 |

歌词页无内容时始终提供「重新搜索在线歌词」按钮（强制绕过缓存重试）。

**隐私**：在线请求只发送歌曲的 标题 / 艺术家 / 专辑 / 时长，不发送设备标识、账号或播放记录。

### 4.3 滚动与高亮

- 播放器每 **250ms** 上报一次播放位置，歌词页用二分查找定位当前行（`Lyrics.indexAt`）。
- 文本**左对齐**，行距紧凑，长句自然换行。
- 当前行：主题色 + `SemiBold`，停在视口 **24%** 处；其余行 `onSurface` 按行距递减到 13% 透明度，
  距焦点两行开外再叠一层轻微高斯模糊。
- 纯色块这类"大色块"效果已移除，界面更干净，缩放只走绘制矩阵，行距不会跳。
- **点击反馈**：点某一行时，该行瞬间点亮（正文色 + 一层 16% 透明度的主题色柔光），
  保持 `420ms` 后自动淡出（淡出 380ms）恢复原状；跳转仍然发生。
- 默认水波纹被关闭（整行涟漪在浅色主题下同样像一块大色块），点击反馈只由上述短暂点亮承担。
- 行位置变化时先把布局**瞬间**移到目标位置（`dispatchRawDelta`），再由每一行自己的弹簧
  （`Modifier.lyricRowMotion`）把视觉位置从旧处追上来，按距离错峰 30ms —— 详见 6.3。

## 5. 在线专辑封面

本地文件没有内嵌封面时，按「专辑名 + 艺术家（+ 歌名）」联网检索封面并缓存到本机。

### 5.1 触发时机

| 时机 | 行为 |
| --- | --- |
| 界面需要显示某张专辑封面（列表/详情/正在播放）且本地没有内嵌图 | 异步补一张，**不阻塞渲染**，先显示音符占位 |
| 用户点「设置 → 封面 → 补全缺失封面」 | 遍历媒体库，只为缺封面的专辑联网，显示进度条 |
| 已有内嵌封面 / 已有缓存 / 7 天内已确认无匹配 | **一次请求都不发** |

不会在**扫描媒体库**时批量请求 —— 那样几千张专辑会瞬间打爆网络和内存。
播放本身也完全不经过这条链路，封面补全失败不影响任何播放行为。

### 5.2 匹配与优先级

数据源两级，前一档无结果才走下一档：

```
1. QQ音乐   albummid → https://y.gtimg.cn/music/photo_new/T002R500x500M000<albummid>.jpg
2. iTunes   search?term=&entity=album → artworkUrl100 换成 600x600bb
```

- QQ 音乐复用歌词模块已经接好的搜索接口（无需登录），华语/日韩命中率最高；
- iTunes 补 QQ 音乐没有的欧美曲目；
- 两档都失败 → 记负缓存，界面保持占位图。

**打分规则**（`OnlineArtworkClient.scoreMatch`，0-100，低于 60 直接丢掉候选）：

主判据取**歌名与专辑名的较高分**（不是相加 —— 同一张专辑里任何一首歌的封面都对）：

| 条件 | 分值 |
| --- | --- |
| 歌名归一化后完全相同 / 互相包含（两边都 ≥3 字） | 85 / 60 |
| 专辑名归一化后完全相同 / 互相包含 | 80 / 55 |
| 歌手相同 / 互相包含 / 完全无关 | +20 / +14 / **-10** |
| 歌名与专辑名都比不上时的兜底 | 只看歌手：相同 70、包含 62 |

歌名进主判据是关键：**没有专辑标签的文件**只能靠歌名匹配（见 5.6）。

归一化会去掉括号补充信息（`(Live)`、`(Remastered)`）、标点、全角转半角并转小写，
所以「稻香 (Live)」和「稻香」能对上。同名不同歌手的候选会掉到 70 分以下被筛掉，宁缺毋滥。

同一数据源内按分数降序逐个试下载，第一个通过校验的就用。

### 5.3 图片校验

下载后必须同时满足才落盘，否则当作"没找到"：

- HTTP 200 且 content-type 是 `image/*`（缺失时放行）；
- 体积 ≥ 6 KB 且 ≤ 4 MB —— 挡掉 1px 占位图和错误页；
- 魔数匹配 JPEG / PNG / WebP。

这三条是必须的：QQ 音乐对不存在的专辑返回 404，但部分 CDN 会返回 200 + 一张灰色默认图，
不校验就会把灰图当封面缓存下来。

### 5.4 缓存与存储

```
filesDir/online-artwork/
├── <albumId>.jpg      # 封面本体
└── index.json         # 来源、写入时间、体积、最近访问；以及负缓存时间戳
```

- 放 `filesDir` 而不是 `cacheDir`：这是用户可见资产，不该被系统清缓存清掉；
- 上限 **128 MB / 600 张**，超出按最近访问时间 LRU 淘汰（同时删文件）；
- 命中后额外进 `ArtworkStore` 的内存 LRU（10 MB），后续不再读盘；
- 负缓存 **7 天**：确认没有匹配的专辑 7 天内不再请求，避免滑动列表反复打接口；
- 同一专辑同一时刻只允许一个请求（in-flight 去重），全局并发上限 **2**。

读取顺序（`ArtworkStore.artworkBytes`）：内存 → 内嵌封面缓存 → 在线缓存 → 内嵌提取 → null。
所以对 `ArtworkProvider` / Coil / 播放通知都是无感的，它们只看到一个 content:// URI。

### 5.5 加载状态与异常提示

| 情况 | 用户看到 |
| --- | --- |
| 正在联网补图 | 占位音符 + 右下角小转圈（**耗时超过 400ms 才出现**，缓存命中不会闪） |
| 补图成功 | 封面淡入替换占位图（URI 带 `?v=n` 绕过 Coil 的旧缓存） |
| 没有匹配 / 网络失败 | **静默**，继续用占位图，不弹任何错误 |
| 批量补全进行中 | 设置页进度条 +「已检查 12/48，新增 3 张」 |
| 批量补全结束 | 「上次完成：检查 48 张专辑，新增 3 张，无匹配 5 张，失败 0 张」 |
| 开关关闭 | 不再联网，已缓存的封面照常显示 |

批量补全每张之间间隔 120ms，把带宽和 CPU 让给正在解码的音频。

### 5.6 没有 ID3 标签的文件（"获取不了封面"的根因）

手机里从各处下载来的 MP3 经常**只有编码器信息、没有任何 ID3 标签**：

```
$ id3 布拉格广场 - 蔡依林&周杰伦.mp3
TSSE = 'Lavf58.76.100'      ← 只有这个，没有 TIT2/TPE1/TALB，也没有 APIC
```

这时 MediaStore 里标题/艺术家/专辑全是空（`<unknown>`），旧逻辑会遇到两个死结：

1. `AlbumMeta.hasUsableText` 要求「专辑名非空」或「歌名 + 歌手都非空」，
   三个都空 → 直接判定"没有可用信息"，**一个请求都不发**；
2. 就算发了，打分以专辑名为主判据，专辑名空 → 0 分，候选全被丢掉。

现在的处理（`data/FileNameMeta.kt`）：

```
布拉格广场 - 蔡依林&周杰伦.mp3  →  歌名=布拉格广场  艺术家=蔡依林&周杰伦
01. 稻香.mp3                   →  歌名=稻香
稻香 (Live) [320k].mp3          →  歌名=稻香
Stay feat. Justin Bieber.mp3   →  歌名=Stay  艺术家=Justin Bieber
```

- 分隔符要求**两侧有空格**的短横线，不会把 `B-side`、`1979-2000` 拆开；
- 方向判断不了时（`周杰伦 - 稻香` 两边都像人名），两种解释都保留，评分时取更高的那个；
- 猜出来的歌名/艺术家同时用于**列表显示**和**在线歌词**，不再显示"未知曲目 / 未知艺术家"。

**无标签文件的专辑 id**

MediaStore 给这类文件的 `album_id` 往往是 0 或 -1，若直接用它缓存封面，
几百首无标签歌曲会共用同一张图。所以扫描时给它们一个**合成 id = `-trackId`**：

| 场景 | albumId | 取元信息 / 缓存 |
| --- | --- | --- |
| 正常带专辑标签 | MediaStore 的正数 id | 按 `ALBUM_ID` 查、按 id 缓存 |
| 无标签 | `-trackId` | 按 `_ID` 查那一首、按 id 缓存 |

负数 id 用 `== 0L`（而不是 `<= 0L`）判空，所有读取入口（`ArtworkStore`、`OnlineArtworkStore`、
`AlbumArt` 的补全触发）都已同步。

**升级后旧负缓存自动失效**：`index.json` 的 `version` 升到 2，读到旧版本直接丢弃 `misses`，
否则之前那次"没搜到"会让这首歌在 7 天内都不再尝试。

## 6. 显示与歌词设置

设置页 → **正在播放 / 歌词设置**，两项都可持久化（DataStore），改完立即生效、重启保留。

### 6.1 歌曲信息

「正在播放 → 歌曲信息」开关（默认开）。显示方式对齐 RetroMusicPlayer 的 `songInfo`：
**单行小字、水平居中、12sp、次要色、无背景色块**，位置在封面页**歌名 / 歌手下方**。

```
FLAC • 24-bit • 1411 kb/s • 48 kHz
```

顺序固定为 **格式 • 位深 • 码率 • 采样率**，缺哪项就跳过哪项（不留占位）。分隔符是 ` • `（U+2022），
位深写成 `24-bit`、码率单位 `kb/s`——全部照搬 Retro 的 `getSongInfo()`。

| 属性 | 值 |
| --- | --- |
| 字号 | 12sp（`bodySmall`，`letterSpacing = 0`） |
| 颜色 | `onSurfaceVariant` 80%（≈ Retro 的 `textColorSecondary`） |
| 对齐 | 居中，`maxLines = 1` + `ellipsize = end` |
| 内边距 | 水平 16dp、垂直 8dp |

高度是固定的（12sp 行高 + 16dp padding），内容长短不会让上下元素挪位；关掉开关只是少这一行。

| 项 | 来源 | 说明 |
| --- | --- | --- |
| 格式 | 文件后缀 → MIME | 后缀写法用户更熟（`mp3`/`flac`/`m4a`） |
| 位深 | `MediaFormat.KEY_BITS_PER_SAMPLE`（12+）→ 读文件头 | **只有无损格式有**（FLAC / WAV / AIFF），有损格式自动省略 |
| 码率 | `MediaFormat.KEY_BIT_RATE` → 体积÷时长估算 | 估算值前加 `≈`，例如 `≈1411 kbps` |
| 采样率 | `MediaFormat.KEY_SAMPLE_RATE` | 44100 显示为 `44.1 kHz` |

**位深怎么来的**

`MediaExtractor` 读容器头（毫秒级，不读整个文件）。Android 12+ 有 `KEY_BITS_PER_SAMPLE`；
拿不到时读文件前 64 KB 自己解析，这是最可靠的一路：

| 格式 | 位置 |
| --- | --- |
| FLAC | 第一个 metadata 块 `STREAMINFO`，第 12/13 字节里 5 bit 的 `位深-1` |
| WAV / RF64 | 遍历 RIFF 块找到 `fmt `，数据偏移 14 的 16 位小端整数 |
| AIFF | `COMM` 块，数据偏移 6 的 16 位大端整数 |

MP3 / AAC / Vorbis / Opus 解码后统一是浮点，谈「多少 bit」没有意义，所以这些格式**不显示位深**
—— 宁可少一项，也不要显示一个会误导人的数字（比如把 MP3 标成 16 bit）。

**码率缺失时怎么算**

FLAC、部分 WAV 的容器头里没有 `KEY_BIT_RATE`（无损是恒定质量 VBR，没有固定码率）。
这时用 `体积 × 8 ÷ 时长` 估算平均码率，**显示时不加任何标记**，和解析器给出的码率长得一样：

```
52,912,500 B × 8 ÷ 300 s = 1411 kb/s   ← 典型 16bit/44.1kHz 双声道 FLAC
```

估算结果落在 16 ~ 20000 kbps 之外就直接丢弃（防止读错文件头闹出天文数字）。

- 结果按 trackId 进 LRU（48 首），来回切歌不重复解析；解析在 IO 线程，异常全部吞掉。
- 任一字段缺失只省略该字段；四项全无则整行不渲染（不留空行）。
- 这行信息只是锦上添花，**解析失败绝不影响播放**。

### 6.2 歌词设置

| 参数 | 范围 | 默认 | 效果 |
| --- | --- | --- | --- |
| 歌词字号 | 0.8× ~ 1.5×，7 档 | **1.2×** | 同时缩放 `fontSize` 与 `lineHeight`，行高不会挤在一起 |
| 上下间距 | 2 ~ 24 dp，11 档 | **4 dp** | 歌词行垂直内边距；字号调大后默认收窄，整屏能多看两行 |

> 旧版本默认是 1.0× / 9dp。老用户升级时会做一次性迁移（`SETTINGS_SCHEMA = 2`）：
> 只有仍停在旧默认或从未写过的项会被改，自己调过的数值一律保留。

- 两个滑块下方有**实时预览**：两句歌词，当前行按真实样式（字号、间距、主色、半粗）渲染，
  调参数时不用来回切页面。
- **高亮色自动跟随播放控件主色**：`LyricsContent` 不硬编码 `colorScheme.primary`，
  而是接收 `accent` 参数；本版本传的是封面取色得到的 `RetroColors.accent`（就是播放键和进度条的底色）
  —— 换一张封面，歌词高亮色也跟着换，永远和播放键同色。
- 点击歌词行的瞬亮柔光也用的是同一个 accent，不会出现"两种高亮色"。

### 6.3 焦点行滚动与层级

歌词不是「整块滚动」，而是**布局瞬移 + 每一行各自用弹簧追上去**。

| 环节 | 实现 |
| --- | --- |
| 焦点位置 | 视口高度的 **24%**（`lyricFocusOffsetPx`），不是正中 |
| 布局移动 | `dispatchRawDelta(目标行实测偏移 − 焦点位置)`，**不带滚动动画**，最多两轮纠位确认 |
| 视觉移动 | 每行自己的 `Modifier.lyricRowMotion`：读本行布局偏移，用闭式弹簧把 `translationY` 从旧位置收到 0 |
| 错峰 | 距焦点行每远一行延后 **30ms** 启动，换行时是一道自上而下的波浪 |
| 弹簧 | 质量 .9 / 阻尼 15 / 刚度 90；闭式解，60Hz 与 120Hz 轨迹一致；重定向时继承速度，不顿一下 |
| 手动拖动 | 拖动期间直接跟手，松手 **2 秒**后才回到当前行 |

**为什么不用 `animateScrollToItem`**

它把整个列表当成一个刚性块平移，所有行同时同速，节奏还由跨过的行数隐式决定 ——
相邻两行几乎瞬移、跨十行又拖得很长。改成「布局先跳、每行再追」之后，无论跨多远起落手感一致，
而且天然产生波浪。

**层级**

| 项目 | 焦点行 | 其它行 |
| --- | --- | --- |
| 缩放 | 1.0 | **0.98**（只缩别人，不放大自己） |
| 透明度 | 1.0 | 0.24 起，每远一行 −0.025，下限 0.13 |
| 模糊 | 无 | 距焦点 ≥2 行才模糊，半径 `(2 + 距离) × 40%` dp |
| 字重 | SemiBold | Normal |

缩放只作用在 `graphicsLayer`，不参与测量：`LazyColumn` 的行高、行距、相邻行位置全都不变，
所以既不会重排，也不会因为放大把相邻行挤开。

**为什么是「缩小别人」而不是「放大自己」**

放大焦点行会改变这一行在 `LazyColumn` 里的**测量高度**，列表必须重新布局 —— 换行时就会顿一下、
甚至跳行。缩小其它行时所有行尺寸始终一致，布局完全不动，只有绘制矩阵在变。

> 参考实现：[Kifranei/Halcyon](https://github.com/Kifranei/Halcyon)（Apache-2.0）的
> `ReferenceLyricMotion.kt` 与 `AppleMusicLyricsView.kt`。本仓库的 `ui/components/LyricFocusMotion.kt`
> 是移植版本，曲线、弹簧参数与层级数值与上游一致；纯逻辑部分有 `LyricFocusMotionTest`（13 项）覆盖。

### 6.4 持久化

| 设置 | DataStore key | 类型 |
| --- | --- | --- |
| 歌曲信息 | `show_track_info` | Boolean |
| 歌词字号 | `lyric_font_scale` | Float |
| 上下间距 | `lyric_spacing` | Int |

三个值都在 `AppSettings` 里，随 `settings` Flow 一起下发；通过
`LocalShowTrackInfo` / `LocalLyricFontScale` / `LocalLyricSpacing` 三个 CompositionLocal 传给 UI，
所以列表项、播放页、设置页预览用的是同一份状态，不会出现两处不一致。
写入时会 `coerceIn` 到合法区间，避免历史脏数据把界面搞坏。

## 7. 工程结构

```
app/src/main/java/com/melody/player/          # namespace 仍是 com.melody.player
├── MainActivity.kt              # 唯一 Activity，edge-to-edge + 主题注入
├── MelodyApp.kt                 # Application：初始化封面 Uri 与播放控制器
├── data/
│   ├── MediaLibraryRepository.kt# MediaStore 扫描，派生专辑/艺术家，中文排序，无标签兜底
│   ├── FileNameMeta.kt          # 无 ID3 时从文件名猜「歌名 - 艺术家」
│   ├── ArtworkStore.kt          # 封面提取（loadThumbnail / 内嵌图片）+ 内存/磁盘缓存
│   ├── TrackInfo.kt             # 格式/位深/码率/采样率解析（MediaExtractor + 文件头）+ LRU
│   ├── ArtworkProvider.kt       # content://<pkg>.artwork/album/<id> 供 Coil 与通知使用
│   ├── LyricRepository.kt       # 歌词加载：本地 .lrc → 在线 → 空；LRC 解析 + 磁盘缓存
│   ├── SettingsRepository.kt    # DataStore：主题 / 排序 / 歌曲信息 / 歌词字号与间距
│   ├── lyrics/
│   │   ├── QqMusicClient.kt     # QQ 音乐搜索 + 歌词（主源）
│   │   ├── LrclibClient.kt      # LRCLIB（备用源）
│   │   └── LyricsHttp.kt        # 共用 HTTP GET + 异常模型
│   ├── artwork/
│   │   ├── OnlineArtworkClient.kt # 封面检索（QQ → iTunes）、打分、下载与图片校验
│   │   ├── OnlineArtworkStore.kt  # 磁盘缓存 + 负缓存 + 并发控制 + LRU
│   │   └── ArtworkBackfill.kt     # 「补全缺失封面」批处理与进度
│   └── model/Models.kt          # Track / Album / Artist / Lyrics
├── playback/
│   ├── PlaybackService.kt       # MediaSessionService（媒体通知、音频焦点、后台播放）
│   └── PlayerManager.kt         # MediaController 封装，暴露 StateFlow<PlayerUiState>
├── ui/
│   ├── AppRoot.kt               # 自适应 Scaffold + NavigationBar/Rail + NavHost + 权限
│   ├── Common.kt                # 通用小部件与占位图
│   ├── LocalPlayer.kt           # 播放器 CompositionLocal
│   ├── LibraryViewModel.kt      # 媒体库状态
│   ├── LyricsViewModel.kt       # 歌词加载状态（加载中 / 结果 / 来源 / 重试）
│   ├── components/              # AlbumArt、MiniPlayer、SongRow、空态/错误态
│   ├── screens/                 # 歌曲、专辑、艺术家、详情、正在播放、设置
│   └── theme/
│       ├── Theme.kt             # MD3 配色（取色失败时的兜底）+ 系统栏图标
│       └── RetroColors.kt       # ★ 封面取色：accent / 渐变 / 迷你播放器底色
└── util/                        # AudioPermission、Format（时长格式化）

app/src/test/java/com/melody/player/          # 共 57 项，见第 9 节
├── LyricsLogicTest.kt           # LRC 解析 / 定位 / 在线候选排序（8 项，纯逻辑）
├── QqMusicLogicTest.kt          # QQ 接口 JSON / 实体还原 / 异常码 / 选歌（8 项）
├── OnlineArtworkLogicTest.kt    # 打分 / 归一化 / 文件名解析 / iTunes / 图片校验（22 项）
├── TrackInfoTest.kt             # 位深解析（FLAC/WAV/AIFF）、码率估算（13 项）
├── LrclibLiveTest.kt            # 真实联网：QQ 搜索→取词→解析→定位，LRCLIB 兜底（2 项）
└── OnlineArtworkLiveTest.kt     # 真实联网：搜索→打分→下载→校验（4 项）

tools/
├── verify_online_lyrics.sh      # 一键在真机上验证在线歌词（装机 + 授权 + 推音频）
├── make_test_mp3.py             # 生成带 ID3 标签的静音测试音频
└── sample-稻香.mp3              # 上面脚本生成的测试音频（稻香 / 周杰伦）
```

## 8. 权限说明

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_AUDIO`（13+）/ `READ_EXTERNAL_STORAGE`（≤12） | 扫描本地音频 |
| `POST_NOTIFICATIONS`（13+） | 显示媒体播放通知 |
| `INTERNET` | 仅用于在线歌词（QQ 音乐 / LRCLIB）与在线封面（QQ 音乐 / iTunes），两个开关都关掉后不发起任何网络请求 |
| `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)` | 后台播放 |
| `WAKE_LOCK` | 息屏继续播放 |

音频、封面、播放列表全部留在设备本地；外网只用于「在线歌词」和「在线封面」两个功能，
用户在设置里把两个开关都关掉后，应用不发起任何网络请求。


## 9. 验证

### 9.1 已完成的自动化验证

全部用例：**57 项通过 / 0 失败 / 0 跳过**。

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| LRC 解析 / offset / 多时间标签 / 定位 / 候选排序 | `./gradlew testDebugUnitTest` | 8 项通过 |
| QQ 音乐接口解析（搜索 JSON、`&#58;` 实体还原、无歌词、异常码、选歌） | 同上（`QqMusicLogicTest`） | 8 项通过 |
| 真实 QQ 音乐链路（搜索 → 选歌 → 取词 → 解析 → 定位） | 同上（`LrclibLiveTest`） | 通过：10 条候选 → `稻香 - 周杰伦` → 48 行 → 35s 定位到第 6 行 |
| 真实 LRCLIB 兜底链路 | 同上 | 通过：52 行 |
| 封面匹配打分 / 归一化 / 文件名解析 / iTunes 解析 / 图片校验 | 同上（`OnlineArtworkLogicTest`） | 22 项通过 |
| 真实封面链路（搜索 → 打分 → 下载 → 图片校验） | 同上（`OnlineArtworkLiveTest`） | 4 项通过：`魔杰座 - 周杰伦` score=100，78888 B；无标签 case 命中 `看我72变` 87392 B |
| 位深解析（FLAC/WAV/AIFF）/ 码率估算 / 格式化 | 同上（`TrackInfoTest`） | 13 项通过 |

`LrclibLiveTest` 走的是**生产代码**（真实 HTTP → `pickBest` → `LyricRepository.parseLrc`），
只是把运行环境从 Android 换成了 JVM，因此能验证除「Compose 渲染」之外的全部链路。实测日志：

```
[live][QQ] 搜索返回 10 条候选
[live][QQ]   稻香 - 周杰伦 / 魔杰座 / 223s
[live][QQ]   稻香 (Demo) - 周杰伦 / null / 244s
[live][QQ] 选中: mid=003aAYrm3GE0Ac 稻香 - 周杰伦
[live][QQ] 解析出 48 行，首行 0ms: 稻香 - 周杰伦 (Jay Chou)
[live][QQ] 35s 定位到第 6 行: 跌倒了就不敢继续往前走
[live][LRCLIB] 选中 id=34495816 稻香 - 周杰伦 (223s)，解析出 52 行
```

### 9.2 在真机上验证（推荐）

```bash
# 手机连上 USB 并开启调试，然后：
chmod +x tools/verify_online_lyrics.sh
./tools/verify_online_lyrics.sh
```

脚本会自动完成：安装 APK → 授予权限 → 推送测试音频 `tools/sample-稻香.mp3`
（带 ID3 标签：稻香 / 周杰伦 / 魔杰座）→ 触发媒体扫描 → 启动应用。

之后手动两步：

1. 在「我的音乐」里点这首歌（`稻香`）；
2. 点底部迷你播放器进入「正在播放」，左滑到**歌词页**。

预期：

- 歌词页顶部出现标签「**QQ音乐 · 稻香 - 周杰伦**」；
- 当前行高亮，并随播放进度自动滚动；
- 点击任意歌词行可跳转到对应时间点。

还可以验证降级路径：

| 场景 | 预期 |
| --- | --- |
| 设置 → 歌词 → 关闭「在线歌词」 | 只剩本地 `.lrc`，否则显示「暂无歌词 + 在线歌词已在设置中关闭」 |
| 飞行模式 + 首次播放（无缓存） | 「网络请求失败，请检查网络后重试」+「重新搜索在线歌词」按钮 |
| 播放同一首第二次 | 命中 `cacheDir/lyrics/` 缓存，秒出歌词且不再请求网络 |
| 歌词页停留一段时间 | 当前行以封面取色（`accent`）文字跟随滚动（前奏/间奏时停在上一句），无任何色块 |
| 点击任意歌词行 | 该行短暂亮起约 0.4s 后恢复原状，同时跳转到对应时间点 |
