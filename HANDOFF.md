# 交接说明 — Melody Retro

> 这份文档是写给**接手这个项目的人或 AI** 的。技术细节在 `MelodyRetro/README.md`（很长，但准确），
> 这里只讲**怎么接手、从哪改、别踩哪些坑**。
> 生成时间：2026-10-27 · 对应版本：Melody Retro 2.4.7-retro

---

## 0. 一句话

纯本地 Android 音乐播放器，Kotlin + Jetpack Compose + Media3(ExoPlayer)。
视觉层按 [RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer) 的设计语言重做
——**主题色来自当前歌曲封面**，不是壁纸。对齐的是它的设计规范与布局参数，界面用 Compose 重新实现
（原项目 `compileSdk 36` + KSP/Realm 的传统 View + XML 架构，代码无法直接复用）。
无后端、无账号，唯一的外网请求是**在线歌词**和**在线封面**两个功能。

**本项目以 GPL-3.0 开源**（见仓库根目录 `LICENSE`），与 RetroMusicPlayer 一致：
分发衍生作品时必须同样以 GPL-3.0 开源并保留版权声明。

| 项 | 值 |
| --- | --- |
| 目录 | `MelodyRetro/`（项目根就是这一层） |
| 应用名 | Melody Retro |
| applicationId | `com.melody.retro` |
| namespace | `com.melody.player`（历史原因保留，见下） |
| 版本 | 2.4.7-retro (versionCode 12) |
| 支持 | minSdk 24 / targetSdk 35 / compileSdk 35 |

> **只有一个版本了**。早期基于 Material Design 3 动态取色的「旋律」（`com.melody.player`，1.6.2）
> 已于 2026-10-27 彻底移除——代码、GitHub 历史、本机备份、手机安装包全部清除，**不可恢复**。
> 它的 57 项测试与 `tools/` 在删除前已迁移到本项目，所以能力没有损失。
>
> **namespace 仍是 `com.melody.player`**，只有 `applicationId` 是 `com.melody.retro`。
> 如果哪天要统一包名，需要重命名整个 `app/src/*/java/com/melody/player/` 目录 + 全量改 import，
> 是一次纯体力但有风险的改动，**不要在改功能时顺手做**。

---

## 1. 新电脑三步跑起来

> 仓库里**不含** `.buildtools/`、`build/`、`local.properties`——这些都是每台机器自己生成的，
> 所以第一步必须先把工具链装好。

### 第 1 步：装构建工具链（约 1.5 GB，一次性）

**不需要装 Android Studio**，仓库自带一键脚本（在项目根执行）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup-buildtools.ps1
```

它会下载 JDK 17（Temurin）+ Android cmdline-tools 到项目根 `.buildtools/`，
安装 `platforms;android-35`、`build-tools;35.0.0`、`platform-tools`，接受 licenses，
并写好 `MelodyRetro/local.properties`。

> 已经有 Android Studio 的话也可以跳过，改用它的 SDK；只要保证
> **JDK 17 + compileSdk 35 + build-tools 35.0.0**，并把 `local.properties` 指过去即可。

### 第 2 步：构建

```bash
export JAVA_HOME="<项目根>/.buildtools/jdk"
export ANDROID_HOME="<项目根>/.buildtools/sdk"

cd MelodyRetro
./gradlew.bat --no-daemon --console=plain assembleDebug assembleRelease
```

- 首次运行 wrapper 会自动下载 Gradle 8.9（约 130 MB），之后走缓存。
- Release 用的是 **debug 签名**（`signingConfig = signingConfigs.getByName("debug")`），
  目的是"能直接装到手机上体验"；真要上架必须换成自己的 keystore。
- 输出：`app/build/outputs/apk/{debug,release}/`。

### 第 3 步：装到真机

```bash
adb install -r MelodyRetro/dist/Melody-Retro-2.4.7-release.apk    # 已经有打好的
```

首次启动要授予「音乐和音频」权限（Android 13+ 是 `READ_MEDIA_AUDIO`）。

**没有模拟器**：这台开发机不支持硬件虚拟化，QEMU 里 guest CPU 恒为 0%，已彻底删除。
验证只能靠真机 + `adb`，不要试图再起模拟器。

---

## 2. 代码地图（改哪里）

路径前缀 `MelodyRetro/app/src/main/java/com/melody/player/`：

| 文件 | 职责 | 什么时候要动 |
| --- | --- | --- |
| `ui/theme/RetroColors.kt` | **封面取色**：accent / 渐变 / 迷你播放器底色 | 改配色逻辑、觉得取色偏了 |
| `ui/screens/NowPlayingScreen.kt` | 正在播放：封面页 + 歌词页 Pager、渐变、控制区、歌曲信息 | 播放页所有视觉 |
| `ui/components/MiniPlayer.kt` | 通栏色块迷你播放器 | 迷你播放器 |
| `data/MediaLibraryRepository.kt` | MediaStore 扫描、派生专辑/艺术家、中文排序 | 改扫描范围、排序、无标签兜底 |
| `data/FileNameMeta.kt` | 从文件名猜 `歌名 / 艺术家`（无 ID3 的文件） | 猜错歌名时 |
| `data/ArtworkStore.kt` | 封面读取链路：内存 → 内嵌缓存 → 在线缓存 → 内嵌提取 | 封面显示不出来 |
| `data/ArtworkProvider.kt` | `content://<pkg>.artwork/album/<id>`，给 Coil 和通知用 | 一般不用动 |
| `data/artwork/OnlineArtworkClient.kt` | 联网找封面：QQ 音乐 → iTunes、**打分**、下载、图片校验 | 匹配不准 / 新增数据源 |
| `data/artwork/OnlineArtworkStore.kt` | 封面磁盘缓存、负缓存 7 天、并发 2、LRU | 缓存策略 |
| `data/artwork/ArtworkBackfill.kt` | 设置页「补全缺失封面」批处理 | — |
| `data/LyricRepository.kt` | 歌词加载链：本地 `.lrc` → 内嵌标签 → QQ → LRCLIB → 空；LRC 解析 | 歌词显示问题 |
| `data/EmbeddedLyrics.kt` | 读音频内嵌歌词（FLAC Vorbis comment / ID3v2 USLT），离线 | 歌词来源 / 「不滚动」 |
| `data/lyrics/QqMusicClient.kt` | QQ 音乐搜索 + 取词（**主源**，多候选取带轴的） | 歌词搜不到 |
| `data/lyrics/LrclibClient.kt` | LRCLIB（兜底，补欧美） | — |
| `data/lyrics/LyricsHttp.kt` | 共用 HTTP GET、超时、异常模型 | — |
| `data/TrackInfo.kt` | 格式/位深/码率/采样率，`MediaExtractor` + 文件头解析 | 歌曲信息显示 |
| `data/SettingsRepository.kt` | DataStore：主题、排序、`show_track_info`、`lyric_font_scale`、`lyric_spacing` | 加新设置项 |
| `playback/PlaybackService.kt` | `MediaSessionService`，通知/锁屏、音频焦点、拔耳机暂停 | 后台播放 |
| `playback/PlayerManager.kt` | `MediaController` 封装，`StateFlow<PlayerUiState>` | 播放状态 |
| `ui/AppRoot.kt` | 自适应 Scaffold、NavigationBar/Rail、NavHost、权限申请 | 导航结构 |
| `ui/screens/SettingsScreen.kt` | 设置页（含歌词设置实时预览） | 设置项 |
| `ui/theme/Theme.kt` | MD3 配色（取色失败时兜底）+ 系统栏 | 主题 |

测试在 `app/src/test/java/com/melody/player/`，共 **57 项**（见第 5 节）。

---

## 3. 外部接口（细节照抄，别"优化"）

全部无需登录、无 API Key，但**必须带请求头**，否则返回空或 403。

### 3.1 QQ 音乐 — 歌词（主源）与封面搜索

```
搜索  GET https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w=<标题 艺术家>&format=json&n=10&p=1&g_tk=5381
取词  GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>&format=json&nobase64=1&g_tk=5381
封面  https://y.gtimg.cn/music/photo_new/T002R500x500M000<albummid>.jpg
Header: Referer: https://y.qq.com/    User-Agent: 任意浏览器 UA
```

四个必须记住的坑，全都在 `QqMusicClient.kt` 里处理好了：

1. **`client_search_cp` 已经 500 了**，必须用 `search_for_qq_cp`。
2. `nobase64=1` 才返回明文 LRC；万一还是 base64，客户端会自己解码。
3. 接口会把 `:` 转义成 `&#58;`（`[00&#58;30.94]`），**不解码时间轴就全废**。
4. `retcode = 1101` 表示"这首歌没歌词"，按无歌词处理，**不要当异常报错**。

5. **不能只取第一个候选**。搜索结果里常有同名翻唱/山寨条目排在正版前面，它们的歌词往往没有时间轴，
   表现为「歌词能显示但不滚动」。`pickCandidates` 会返回整份排序过的候选，上层逐个取词，
   拿到带时间轴的就停（最多 4 个）。
6. **标签里的 ARTIST 经常是错的**（例如把歌手写成歌名）。这类文件靠在线搜索必然搜错歌，
   所以必须优先读内嵌歌词标签 —— 见 `data/EmbeddedLyrics.kt`。

### 3.2 iTunes — 封面兜底

```
GET https://itunes.apple.com/search?term=<歌名 艺术家>&entity=song&limit=15&country=cn
→ results[].artworkUrl100，把 100x100bb 换成 600x600bb
```

`entity=album` 会漏掉"歌名能对上但专辑名对不上"的情况，所以用 **`entity=song`**。

### 3.3 LRCLIB — 歌词兜底

```
GET https://lrclib.net/api/search?q=<关键词>      （或 /api/get?artist_name=&track_name=）
```

### 3.4 打分规则（`OnlineArtworkClient.scoreMatch`）

主判据是 **`max(歌名分, 专辑分)`**，不是相加 —— 同一张专辑里任何一首歌的封面都能用：

| 条件 | 分 |
| --- | --- |
| 歌名归一化后相同 / 互相包含（都 ≥3 字） | 85 / 60 |
| 专辑名相同 / 互相包含 | 80 / 55 |
| 歌手相同 / 包含 / 无关 | +20 / +14 / −10（未知歌手不罚） |
| 歌名专辑都比不上时兜底 | 只看歌手：70 / 62 |

**< 60 分直接丢弃**。归一化会去掉括号补充（`(Live)`、`(Remastered)`）、标点、全角转半角、转小写。

---

## 4. 已知的坑（都是真踩过的）

| 坑 | 真相 |
| --- | --- |
| `MediaFormat.KEY_BITS_PER_SAMPLE` | **SDK 35 里不存在**这个常量。用字符串键 `"bits-per-sample"` 或自己读文件头 |
| FLAC 位深位置 | 第一个 metadata 块（STREAMINFO）的**第 20/21 字节**里 5 bit 的「位深−1」。不是 12/13 |
| `org.json` 在 JVM 单测 | 是空实现，`new JSONObject()` 直接抛异常。单测不能碰 JSON 解析路径 |
| 单测要联网 | 需要透传代理：`./gradlew testDebugUnitTest -PproxyHost=127.0.0.1 -PproxyPort=<port>` |
| 无标签 MP3 | `album_id` 常是 0 或 −1。若直接拿来当缓存 key，几百首会共用一张封面 → 扫描时给合成 id `−trackId` |
| 负数 albumId 判空 | 必须用 `== 0L`，**不是** `<= 0L`（负数现在是合法 id） |
| 旧负缓存 | `index.json` 的 `version` 已升到 **2**；读到旧版本必须丢弃 `misses`，否则"曾经没搜到"会让那首歌 7 天不再尝试 |
| CDN 灰图 | QQ 音乐对不存在的专辑有时返回 200 + 一张灰色默认图。必须校验 content-type + 体积(6KB~4MB) + 魔数(JPEG/PNG/WebP) |
| `Slider` 自定义 thumb | 需要 `@OptIn(ExperimentalMaterial3Api::class)` |
| 布局跳动 | 歌词的缩放/透明度**只能加在 `graphicsLayer`**，一旦改 `fontSize` 参与测量，`LazyColumn` 会重排、行会跳 |
| 歌词换行卡顿 | 别用 `animateScrollToItem` 让整块文字平移（节奏由跨行数决定，忽快忽慢）。改成布局 `dispatchRawDelta` 瞬移 + 每行 `Modifier.lyricRowMotion` 弹簧追上来，才有波浪 |
| 歌词里露出 `<00:00.245>` | 那是**增强型 LRC 的逐字时间标签**（QQ 下载的曲子常见）。行级 `[mm:ss.xx]` 与逐字 `<mm:ss.xxx>` 都要从正文里剥掉；整行没有行级标签时，取**第一个**逐字标签当行时间（全取会一行拆成几十行） |
| 取色死黑 | `Palette` 要优先 `vibrant` 而不是 `dominant`，否则大面积黑封面的专辑会得到一片黑 |
| 歌词「能显示但不滚动」 | 只有一个原因：拿到的是没有时间轴的纯文本（`Lyrics.synced = false`）。来源标签会标注「无时间轴」。常见成因是标签把歌手写错 → 搜到山寨条目 → 那份歌词没时间轴 |

---

## 5. 测试

```bash
cd MelodyRetro
./gradlew.bat testDebugUnitTest                       # 92 项，含真实联网冒烟测试
./gradlew.bat testDebugUnitTest -PproxyHost=127.0.0.1 -PproxyPort=8080
```

| 测试类 | 内容 | 数量 |
| --- | --- | --- |
| `LyricsLogicTest` | LRC 解析 / offset / 多时间标签 / 增强型逐字标签 / 二分定位 / 候选排序 | 12 |
| `QqMusicLogicTest` | QQ 搜索 JSON、`&#58;` 还原、无歌词、异常码、选歌、候选排序 | 11 |
| `EmbeddedLyricsTest` | 内嵌歌词：合成 FLAC Vorbis comment / ID3v2 USLT、UTF-8 与 UTF-16、截断容错 | 13 |
| `LyricFocusMotionTest` | 弹簧轨迹 / 刷新率无关 / 透明度阶梯 / 模糊半径 / 焦点与尾部内边距 | 13 |
| `LyricDefaultsMigrationTest` | 歌词默认值迁移的边界（只改旧默认、不覆盖用户设置） | 6 |
| `OnlineArtworkLogicTest` | 打分 / 归一化 / 文件名解析 / iTunes 解析 / 图片校验 | 22 |
| `TrackInfoTest` | 位深解析（FLAC/WAV/AIFF）、码率估算、格式化 | 13 |
| `LrclibLiveTest` | **真实联网**：QQ 搜索→取词→解析→定位，LRCLIB 兜底 | 2（跳网自动 skip） |
| `OnlineArtworkLiveTest` | **真实联网**：搜索→打分→下载→校验 | 4（同上） |

当前全绿：**92 通过 / 0 失败 / 0 跳过**。

`LrclibLiveTest` 走的是**生产代码**（真实 HTTP → `pickBest` → `parseLrc`），只是运行在 JVM，
所以除 Compose 渲染外的全链路都能验证。

真机验证脚本（歌词）：

```bash
cd MelodyRetro && ./tools/verify_online_lyrics.sh
```
自动完成：装 APK → 授权 → 推送 `tools/sample-稻香.mp3`（带 ID3）→ 触发扫描 → 启动 App。

---

## 6. 当前状态与待办

**最近一次改动**：删除主版本 `Melody`，迁移其独有资产（3 个测试类、`tools/`）到本项目，
README 重写为自洽的单项目文档。

**待真机确认**（用户还没反馈）：

- [ ] 无标签 MP3 的封面能否补上；列表/播放页显示的歌名是否正确
- [ ] 歌词焦点行是否稳定停在视口 24% 处、换行有没有波浪感、远处行的模糊是否过重
- [ ] 手动拖动歌词时是否跟手、松手 2 秒后是否回到当前行
- [ ] 歌曲信息一行（`FLAC • 24-bit • 1411 kb/s • 48 kHz`）在歌手名下方是否成图
- [ ] 封面取色驱动的渐变/按钮/迷你播放器颜色是否跟着换歌一起变

若仍异常，抓日志：

```bash
adb logcat -s OnlineArtworkClient OnlineArtworkStore TrackInfo LyricsHttp QqMusicClient
```

---

## 7. 改动时必须守住的不变式

1. **不阻塞播放**：封面/歌词/歌曲信息全部异步，失败静默降级，绝不弹错误、绝不影响解码。
2. **不阻塞首帧**：封面先给占位音符，联网补图耗时超过 400ms 才显示小转圈（缓存命中不许闪）。
3. **不打爆接口**：并发上限 2、同一专辑 in-flight 去重、负缓存 7 天、批量补全每张间隔 120ms。
4. **不引起布局跳动**：动画只用 `graphicsLayer`；歌曲信息高度固定。
5. **取色只在前台做**：`RetroColors` 在 `Dispatchers.IO` 上取色，取不到回退 MD3 `primary`，不许空屏或闪烁。
6. **隐私**：在线请求只发 标题/艺术家/专辑/时长，不发设备标识、账号、播放记录。
7. **两个开关关掉后，一个网络请求都不发**。

---

## 8. 版本与产物对照

| 位置 | 内容 |
| --- | --- |
| `MelodyRetro/dist/Melody-Retro-2.4.7-{debug,release}.apk` | 当前版本 |
| `backup/2026-10-27_retro-2.3.0/` | 上一版 2.3.0 的源码 zip + 2 个 APK（**只在本机**，`backup/` 在 `.gitignore` 里） |

版本号约定：`versionCode` 每次发版 +1；版本线是 `2.x.y-retro`
（2.x 是"视觉重构后"的独立版本线）。

产物用这两种方式校验：

```bash
aapt2 dump badging app-release.apk | head -3        # 看 package / versionName
apksigner verify --print-certs app-release.apk      # 看签名
```

---

## 9. 更详细的技术细节

`MelodyRetro/README.md` 有 10 个章节（0~9）：设计语言与取色实现、歌词三级加载与容错矩阵、
封面打分表、图片校验阈值、缓存目录结构与 LRU、歌曲信息四项的来源与缺失策略、
歌词设置持久化、工程结构树。
遇到"为什么这么设计"的问题，先查那里，再改代码。
