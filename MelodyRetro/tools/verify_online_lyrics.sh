#!/usr/bin/env bash
# =============================================================================
# 在真机 / 模拟器上验证「在线歌词」功能（LRCLIB）
#
# 用法：
#   tools/verify_online_lyrics.sh [APK 路径] [音频文件路径]
#
# 默认使用 app/build/outputs/apk/debug/app-debug.apk 与 tools/sample-稻香.mp3
# （仓库里那个 mp3 是带 ID3 标签的静音测试音频，元数据为 稻香 / 周杰伦 / 魔杰座，
#   专用于验证媒体库扫描与歌词匹配，请勿用于其他用途）。
# =============================================================================
set -uo pipefail

ADB="${ADB:-adb}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
AUDIO="${2:-tools/sample-稻香.mp3}"
PKG="com.melody.player"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$1"; }

[ -f "$APK" ] || { echo "找不到 APK: $APK"; exit 1; }

step "1/6 检查设备"
"$ADB" devices
"$ADB" wait-for-device

step "2/6 安装 APK"
"$ADB" install -r "$APK" || exit 1

step "3/6 授予权限"
"$ADB" shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO 2>/dev/null || warn "READ_MEDIA_AUDIO 授权失败（Android 12 及以下请改授 READ_EXTERNAL_STORAGE）"
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

if [ -f "$AUDIO" ]; then
  step "4/6 推送测试音频并触发媒体扫描"
  NAME="$(basename "$AUDIO")"
  "$ADB" shell mkdir -p /sdcard/Music
  "$ADB" push "$AUDIO" "/sdcard/Music/$NAME" >/dev/null || exit 1
  # 不同 Android 版本可用的扫描方式不同，逐个尝试
  "$ADB" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d "file:///sdcard/Music/$NAME" >/dev/null 2>&1
  "$ADB" shell content call --uri content://media/external --method scan_volume \
      --arg external_primary >/dev/null 2>&1
  warn "已推送 $NAME，若列表里没出现，请手动执行："
  warn "  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/$NAME"
else
  step "4/6 跳过推送测试音频（未找到 $AUDIO）"
fi

step "5/6 启动应用"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3

step "6/6 请手动完成最后两步（脚本无法代替）"
cat <<'EOF'
    1. 在「我的音乐」列表里点击刚推入的歌曲（稻香）
    2. 点底部迷你播放器进入「正在播放」，左右滑动切到歌词页

    预期结果：
      · 歌词页顶部出现标签「QQ音乐 · 稻香 - 周杰伦」
      · 当前行高亮并随播放进度滚动
      · 点歌词行可以跳转到对应时间

    抓取证据：
      adb exec-out screencap -p > lyrics.png
      adb exec-out uiautomator dump /dev/tty | grep -o 'text="[^"]*"' | head -40

    关闭在线歌词后的预期（设置 → 歌词 → 关闭「在线歌词」）：
      · 只有本地 .lrc 才会显示，否则为「暂无歌词」+「在线歌词已在设置中关闭」
EOF
