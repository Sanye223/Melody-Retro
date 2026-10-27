"""创建 GitHub Release 并上传 dist 里的 release APK。

用法（仓库根目录，先 `git tag -a vX.Y.Z && git push origin vX.Y.Z`）：

    python tools/github-release.py

发新版本时改下面四个常量：TAG / NAME / APK / BODY。
凭据从 git credential manager 读取 —— 不读环境变量、不落盘、不打印。
重复执行是安全的：Release 已存在则复用，附件已上传则跳过。
"""
import json
import subprocess
import urllib.request

REPO = "Sanye223/Melody-Retro"
TAG = "v2.6.1"
NAME = "Melody Retro 2.6.1"
APK = "MelodyRetro/dist/Melody-Retro-2.6.1-release.apk"

BODY = """纯本地 Android 音乐播放器，视觉语言参考 [RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer)，以 **GPL-3.0** 开源。

## 这个版本做了什么

- **歌曲页**：顶栏常驻（滚动只把大标题压成小标题，不会滑走），搜索按钮固定在右上角，点开在标题下方展开 MD3 填充式搜索栏
- **播放页**：抽屉整块 `translationY` 平移，对齐上游的 `BottomSheetBehavior`；迷你播放器长在面板顶部，跟着手指一起上移、淡出，不再是钉在底栏里不动的独立控件
- **修复**：抽屉收起位置算错（底栏高度未测量时退化成整屏），导致整块面板连同迷你播放器被推出屏幕

## 主要特性

- **主题色来自当前歌曲封面**：`Palette` 取 `vibrant`，整屏竖向渐变、播放按钮、进度条、迷你播放器、歌词高亮同色，换歌即换色
- **歌词四段兜底**：同名 `.lrc` → 音频内嵌标签（FLAC Vorbis / ID3v2 USLT，离线）→ QQ 音乐（多候选取带时间轴的）→ LRCLIB
- **歌词动效**：焦点行固定在视口 24%，换行时各行按距离错峰弹簧追上去，形成自上而下的波浪；远处的行渐淡并轻微虚化。全部走 `graphicsLayer`，不参与布局，绝不跳行
- **自绘进度条**：两端各留一个拇指半径的 inset，按下与拖动都不偏移
- **完全离线**：无账号、无后端、无埋点。网络请求只用于在线歌词与在线封面，两项都能单独关闭

## 安装

1. 下载下面的 `Melody-Retro-2.6.1-release.apk`
2. 直接安装（**debug keystore 签名**，方便体验，不用于上架）
3. 首次启动授予「音乐和音频」权限（Android 13+ 是 `READ_MEDIA_AUDIO`）

要求 Android 7.0（API 24）及以上，targetSdk 35。

## 文档

- [中文](https://github.com/Sanye223/Melody-Retro#readme) · [English](https://github.com/Sanye223/Melody-Retro/blob/main/README.en.md) · [日本語](https://github.com/Sanye223/Melody-Retro/blob/main/README.ja.md)

## 致谢

设计规范与交互参考 [RetroMusicPlayer](https://github.com/RetroMusicPlayer/RetroMusicPlayer)（GPL-3.0），歌词动效参考 [Kifranei/Halcyon](https://github.com/Kifranei/Halcyone)（Apache-2.0）。
"""


def token() -> str:
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    raise SystemExit("no credential found")


def api(url, tok, data=None, method="GET", accept="application/vnd.github+json", content_type=None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {tok}")
    req.add_header("Accept", accept)
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if content_type:
        req.add_header("Content-Type", content_type)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode())


tok = token()

payload = json.dumps(
    {
        "tag_name": TAG,
        "target_commitish": "main",
        "name": NAME,
        "body": BODY,
        "draft": False,
        "prerelease": False,
    }
).encode()

try:
    release = api(
        f"https://api.github.com/repos/{REPO}/releases",
        tok,
        payload,
        "POST",
        content_type="application/json",
    )
    print("created release:", release["html_url"])
except urllib.error.HTTPError as e:
    detail = e.read().decode()
    if e.code == 422 and "already_exists" in detail:
        release = api(f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}", tok)
        print("release already exists:", release["html_url"])
    else:
        raise SystemExit(f"create failed {e.code}: {detail}")

upload_url = release["upload_url"].split("{")[0]
name = APK.rsplit("/", 1)[-1]
with open(APK, "rb") as f:
    blob = f.read()

print(f"uploading {name} ({len(blob) / 1024 / 1024:.1f} MB)")
try:
    asset = api(
        f"{upload_url}?name={name}",
        tok,
        blob,
        "POST",
        accept="application/vnd.github+json",
        content_type="application/octet-stream",
    )
except urllib.error.HTTPError as e:
    detail = e.read().decode()
    if e.code != 422:
        raise SystemExit(f"upload failed {e.code}: {detail}")
    existing = api(f"https://api.github.com/repos/{REPO}/releases/{release['id']}/assets", tok)
    asset = next(a for a in existing if a["name"] == name)
    print("asset already uploaded")
print("asset:", asset["name"], asset["size"], "bytes")
print("download:", asset["browser_download_url"])
