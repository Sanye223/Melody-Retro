# 备份说明（2026-10-27，改动前快照）

这份备份是「歌曲信息改为 Retro 排版 + 歌词放大动效」**修改之前**的版本，用于回滚对比。

## 内容

| 文件 | 说明 |
| --- | --- |
| `Melody-1.5.0-src.zip` | 主版本完整源码（`app/src` + Gradle 脚本 + README），已排除 `build/`、`.gradle/`、`dist/` |
| `Melody-1.5.0-debug.apk` | 主版本 Debug 包（可直接安装） |
| `Melody-1.5.0-release.apk` | 主版本 Release 包（R8 压缩） |
| `MelodyRetro-2.3.0-src.zip` | Retro 变体完整源码 |
| `Melody-Retro-2.3.0-debug.apk` | Retro 变体 Debug 包（包名 `com.melody.retro`，可与主版本共存） |
| `Melody-Retro-2.3.0-release.apk` | Retro 变体 Release 包 |

版本对应：`versionCode 7 / 1.5.0`、`versionCode 4 / 2.3.0-retro`。

## 如何回滚

### 只看 APK

直接安装本目录里的 APK 即可，不需要动源码：

```bash
adb install -r "Melody-1.5.0-debug.apk"
```

### 恢复源码

```bash
cd <工作区>
rm -rf Melody
mkdir Melody
cd Melody && unzip ../backup/2026-10-27_v1.5.0/Melody-1.5.0-src.zip
```

zip 内顶层目录就是 `Melody/`，解压后 `./gradlew assembleDebug` 可直接构建
（`local.properties` 也在包内，指向本机 `.buildtools/sdk`）。

## 这一版里有什么

- 歌曲信息是**封面页歌名下方的胶囊标签**，格式 `FLAC · 24 bit · 1411 kbps · 48 kHz`；
- 歌词当前行只做**颜色 + 半粗**区分，没有任何缩放；
- 其余功能（在线歌词、在线封面、歌词字号/间距设置、Retro 配色）与新版一致。
