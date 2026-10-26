# 交接说明 — Melody 本地音乐播放器

> 这份文档是写给**接手这个项目的人或 AI** 的。技术细节在 `Melody/README.md`（很长，但准确），
> 这里只讲**怎么接手、从哪改、别踩哪些坑**。
> 生成时间：2026-10-27 · 对应版本：Melody 1.6.2 / Melody Retro 2.4.2-retro

---

## 0. 一句话

纯本地 Android 音乐播放器，Kotlin + Jetpack Compose + Media3(ExoPlayer)，严格 MD3。
无后端、无账号，唯一的外网请求是**在线歌词**和**在线封面**两个功能。

同一个工程有两个独立变体，**可以同时装在一台手机上**：

| 目录 | 应用名 | applicationId | 版本 |
| --- | --- | --- | --- |
| `Melody/` | 旋律 | `com.melody.player` | 1.6.2 (10) |
| `MelodyRetro/` | Melody Retro | `com.melody.retro` | 2.4.2-retro (7) |

`MelodyRetro` 的业务逻辑与 `Melody` **逐行相同**，只把视觉层换成 RetroMusicPlayer 的设计语言
（封面取色、通栏迷你播放器、Retro 排版）。改功能时**两个目录都要改**，这是本项目最大的维护成本。

> 注意：两个工程的 `namespace` 都是 `com.melody.player`（只有 `applicationId` 不同）。
> 这是刻意保留的，这样两份代码几乎可以整文件复制粘贴；代价是**不能把其中一个当 library 依赖进另一个**。

---

## 1. 新电脑三步跑起来

> 你是通过 `git clone` 拿到这个仓库的，还是拿到了一个 zip？两种都一样：
> 仓库里**不含** `.buildtools/`、`build/`、`local.properties`——这些都是每台机器自己生成的，
> 所以第一步必须先把工具链装好。

### 第 1 步：装构建工具链（约 1.5 GB，一次性）

**不需要装 Android Studio**，仓库自带一键脚本：

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup-buildtools.ps1
```

它会下载 JDK 17（Temurin）+ Android cmdline-tools 到项目根 `.buildtools/`，
安装 `platforms;android-35`、`build-tools;35.0.0`、`platform-tools`，接受 licenses，
并为两个工程写好 `local.properties`。

> 已经有 Android Studio 的话也可以跳过，改用它的 SDK；只要保证
> **JDK 17 + compileSdk 35 + build-tools 35.0.0**，并把 `local.properties` 指过去即可。

### 第 2 步：构建

```bash
export JAVA_HOME="<项目根>/.buildtools/jdk"
export ANDROID_HOME="<项目根>/.buildtools/sdk"

cd Melody
./gradlew.bat --no-daemon --console=plain assembleDebug assembleRelease
```

- 首次运行 wrapper 会自动下载 Gradle 8.9（约 130 MB），之后走缓存。
- Release 用的是 **debug 签名**（`signingConfig = signingConfigs.getByName("debug")`），
  目的是"能直接装到手机上体验"；真要上架必须换成自己的 keystore。
- 输出：`app/build/outputs/apk/{debug,release}/`。

### 第 3 步：装到真机

```bash
adb install -r Melody/dist/Melody-1.6.2-release.apk     # 已经有打好的
adb install -r MelodyRetro/dist/Melody-Retro-2.4.2-release.apk
```

首次启动要授予「音乐和音频」权限（Android 13+ 是 `READ_MEDIA_AUDIO`）。

**没有模拟器**：这台开发机不支持硬件虚拟化，QEMU 里 guest CPU 恒为 0%，已彻底删除。
验证只能靠真机 + `adb`，不要试图再起模拟器。

---

## 2. 代码地图（改哪里）

路径前缀 `Melody/app/src/main/java/com/melody/player/`：

| 文件 | 职责 | 什么时候要动 |
| --- | --- | --- |
| `data/MediaLibraryRepository.kt` | MediaStore 扫描、派生专辑/艺术家、中文排序 | 改扫描范围、排序、无标签兜底 |
| `data/FileNameMeta.kt` | 从文件名猜 `歌名 / 艺术家`（无 ID3 的文件） | 猜错歌名时 |
| `data/ArtworkStore.kt` | 封面读取链路：内存 → 内嵌缓存 → 在线缓存 → 内嵌提取 | 封面显示不出来 |
| `data/ArtworkProvider.kt` | `content://<pkg>.artwork/album/<id>`，给 Coil 和通知用 | 一般不用动 |
| `data/artwork/OnlineArtworkClient.kt` | 联网找封面：QQ 音乐 → iTunes、**打分**、下载、图片校验 | 匹配不准 / 新增数据源 |
| `data/artwork/OnlineArtworkStore.kt` | 封面磁盘缓存、负缓存 7 天、并发 2、LRU | 缓存策略 |
| `data/artwork/ArtworkBackfill.kt` | 设置页「补全缺失封面」批处理 | — |
| `data/LyricRepository.kt` | 歌词加载链：本地 `.lrc` → 在线 → 空；LRC 解析 | 歌词显示问题 |
| `data/lyrics/QqMusicClient.kt` | QQ 音乐搜索 + 取词（**主源**） | 歌词搜不到 |
| `data/lyrics/LrclibClient.kt` | LRCLIB（兜底，补欧美） | — |
| `data/lyrics/LyricsHttp.kt` | 共用 HTTP GET、超时、异常模型 | — |
| `data/TrackInfo.kt` | 格式/位深/码率/采样率，`MediaExtractor` + 文件头解析 | 歌曲信息显示 |
| `data/SettingsRepository.kt` | DataStore：主题、排序、`show_track_info`、`lyric_font_scale`、`lyric_spacing` | 加新设置项 |
| `playback/PlaybackService.kt` | `MediaSessionService`，通知/锁屏、音频焦点、拔耳机暂停 | 后台播放 |
| `playback/PlayerManager.kt` | `MediaController` 封装，`StateFlow<PlayerUiState>` | 播放状态 |
| `ui/AppRoot.kt` | 自适应 Scaffold、NavigationBar/Rail、NavHost、权限申请 | 导航结构 |
| `ui/screens/NowPlayingScreen.kt` | 正在播放：封面页 + 歌词页 Pager、歌曲信息、控制区 | 播放页所有视觉 |
| `ui/screens/SettingsScreen.kt` | 设置页（含歌词设置实时预览） | 设置项 |
| `ui/theme/Theme.kt` | MD3 配色 + 动态取色 + 系统栏 | 主题 |
| `ui/theme/RetroColors.kt` | **仅 Retro**：从封面字节取色 | Retro 配色 |

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
| FAB/按钮叠加 | `Slider` 用自定义 thumb 要 `@OptIn(ExperimentalMaterial3Api::class)` |
| 布局跳动 | 歌词放大动画**只能加在 `graphicsLayer`**，一旦改 `fontSize` 参与测量，`LazyColumn` 会重排、行会跳 |

---

## 5. 测试

```bash
cd Melody
./gradlew.bat testDebugUnitTest                       # 57 项，含真实联网冒烟测试
./gradlew.bat testDebugUnitTest -PproxyHost=127.0.0.1 -PproxyPort=8080
```

| 测试类 | 内容 | 数量 |
| --- | --- | --- |
| `LyricsLogicTest` | LRC 解析 / offset / 多时间标签 / 二分定位 / 候选排序 | 8 |
| `QqMusicLogicTest` | QQ 搜索 JSON、`&#58;` 还原、无歌词、异常码、选歌 | 8 |
| `OnlineArtworkLogicTest` | 打分 / 归一化 / 文件名解析 / iTunes 解析 / 图片校验 | 22 |
| `TrackInfoTest` | 位深解析（FLAC/WAV/AIFF）、码率估算、格式化 | — |
| `LrclibLiveTest` | **真实联网**：QQ 搜索→取词→解析→定位，LRCLIB 兜底 | 跳网自动 skip |
| `OnlineArtworkLiveTest` | **真实联网**：搜索→打分→下载→校验 | 同上 |

`LrclibLiveTest` 走的是**生产代码**（真实 HTTP → `pickBest` → `parseLrc`），只是运行在 JVM，
所以除 Compose 渲染外的全链路都能验证。

各工程实际覆盖不同，别以为两边一样：

| 工程 | 测试结果 | 说明 |
| --- | --- | --- |
| `Melody` | **57 通过 / 0 失败 / 0 跳过** | 含 `OnlineArtworkLogicTest`(22) + `TrackInfoTest` |
| `MelodyRetro` | **18 通过 / 0 失败 / 0 跳过** | 只有 `LyricsLogicTest`(8) + `QqMusicLogicTest`(8) + `LrclibLiveTest`(2)，**缺封面与 TrackInfo 的测试** |

所以改动封面打分或歌曲信息解析后，**必须在 `Melody` 里跑测试**，Retro 那边测不出来。

真机验证脚本（歌词）：

```bash
cd Melody && ./tools/verify_online_lyrics.sh
```
自动完成：装 APK → 授权 → 推送 `tools/sample-稻香.mp3`（带 ID3）→ 触发扫描 → 启动 App。

---

## 6. 当前状态与待办

**最近修复**：无 ID3 标签的文件（例：`布拉格广场 - 蔡依林&周杰伦.mp3`）取不到封面。
根因有两个 —— ① 三字段全空时直接不发请求还写 7 天负缓存；② 打分以专辑名为主判据，专辑空就 0 分。
已通过「文件名兜底 + 主判据改为 `max(歌名, 专辑)` + 合成 albumId」修复，并联网实测命中
（`QQ音乐 score=100 看我72变 - 蔡依林/周杰伦 87392 B`）。

**待真机确认**（用户还没反馈）：

- [ ] 无标签 MP3 的封面能否补上；列表/播放页显示的歌名是否正确
- [ ] 歌词当前行放大 1.10× 是否顺滑、有没有跳行
- [ ] 歌曲信息一行（`FLAC • 24-bit • 1411 kb/s • 48 kHz`）在歌手名下方是否成图
- [ ] Retro 版封面取色驱动的渐变/按钮颜色是否跟着新封面变

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
5. **两个变体同步**：业务逻辑改动 `Melody/` 后，把同样的改动搬到 `MelodyRetro/`；
   只有 `ui/theme/RetroColors.kt` 和 `NowPlayingScreen` / `MiniPlayer` / 详情页的视觉是各自独立的。
6. **隐私**：在线请求只发 标题/艺术家/专辑/时长，不发设备标识、账号、播放记录。
7. **两个开关关掉后，一个网络请求都不发**。

---

## 8. 版本与产物对照

| 位置 | 内容 |
| --- | --- |
| `Melody/dist/Melody-1.6.2-{debug,release}.apk` | 当前版本 |
| `MelodyRetro/dist/Melody-Retro-2.4.2-{debug,release}.apk` | 当前版本 |
| `backup/2026-10-27_v1.5.0/` | 上一个版本的源码 zip + 4 个 APK + 回滚说明（**只在原开发机上**，未随分享包发出） |

版本号约定：`versionCode` 每次发版 +1；`Melody` 用 `1.x.y`，`MelodyRetro` 用 `2.x.y-retro`
（2.x 对应"视觉重构后"的独立版本线，与 1.x 的次版本号没有数值关系）。

打好的包用这两种方式校验：

```bash
aapt2 dump badging app-release.apk | head -3        # 看 package / versionName
apksigner verify --print-certs app-release.apk      # 看签名
```

---

## 9. 更详细的技术细节

`Melody/README.md` 有 9 个章节，逐项写了：歌词三级加载与容错矩阵、封面打分表、图片校验阈值、
缓存目录结构与 LRU、歌曲信息四项的来源与缺失策略、歌词设置三档持久化、工程结构树。
遇到"为什么这么设计"的问题，先查那里，再改代码。
