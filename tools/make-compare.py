"""把 2.6.3 / 2.7.0 的截图拼成「修改前 / 修改后」对比图。

用法：python tools/make-compare.py <截图目录> <输出目录>
"""

import os
import sys
from PIL import Image, ImageDraw, ImageFont

SHOT_DIR = sys.argv[1] if len(sys.argv) > 1 else ".tmp-shots"
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else "exports/ui-compare"

BG = (16, 17, 22)
CARD = (26, 27, 34)
TEXT = (232, 232, 236)
MUTED = (150, 150, 160)
ACCENT = (198, 146, 120)
OK = (126, 200, 154)

FONT_REG = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REG, size)


def load(name):
    return Image.open(os.path.join(SHOT_DIR, name)).convert("RGB")


def rounded(img, radius):
    """给缩略图加圆角遮罩，避免直角贴在图上看不出来。"""
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, img.size[0] - 1, img.size[1] - 1], radius=radius, fill=255
    )
    out = Image.new("RGB", img.size, BG)
    out.paste(img, (0, 0), mask)
    return out


def build_pair(pairs, out_name, title, note, thumb_w=470):
    """pairs: [(行标题, 前图, 后图), ...] 竖向排列，每行左右并排。"""
    thumb_h = int(thumb_w * 2800 / 1260)
    pad = 36
    gap = 28
    row_label_h = 44
    col_label_h = 30
    row_gap = 26
    title_h = 96
    note_h = 78

    width = pad * 2 + thumb_w * 2 + gap
    height = title_h + len(pairs) * (row_label_h + col_label_h + thumb_h + row_gap) + note_h
    canvas = Image.new("RGB", (width, height), BG)
    d = ImageDraw.Draw(canvas)

    d.text((pad, 32), title, font=font(32, True), fill=TEXT)

    x_left = pad
    x_right = pad + thumb_w + gap
    y = title_h
    for label, before, after in pairs:
        d.text((pad, y + 4), label, font=font(21, True), fill=TEXT)
        y += row_label_h

        d.text((x_left, y), "修改前 · 2.6.3", font=font(16), fill=MUTED)
        d.text((x_right, y), "修改后 · 2.7.0", font=font(16), fill=OK)
        y += col_label_h

        left = rounded(before.resize((thumb_w, thumb_h), Image.LANCZOS), 16)
        right = rounded(after.resize((thumb_w, thumb_h), Image.LANCZOS), 16)
        canvas.paste(left, (x_left, y))
        canvas.paste(right, (x_right, y))
        y += thumb_h + row_gap

    d.text((pad, y + 14), note, font=font(17), fill=ACCENT)

    path = os.path.join(OUT_DIR, out_name)
    canvas.save(path)
    print("saved", path, canvas.size)


def build_detail(rows, out_name, title, note, crop_w=250, crop_h=136, scale=2.0):
    """rows: [(说明, 前图, 后图, (x, y)) ...] —— 同一坐标裁一小块，放大对比。"""
    pad = 36
    gap = 28
    label_h = 42
    col_label_h = 28
    w_crop = int(crop_w * scale)
    h_crop = int(crop_h * scale)
    width = pad * 2 + w_crop * 2 + gap
    height = 96 + len(rows) * (label_h + col_label_h + h_crop + 24) + 78
    canvas = Image.new("RGB", (width, height), BG)
    d = ImageDraw.Draw(canvas)
    d.text((pad, 32), title, font=font(32, True), fill=TEXT)

    x_left = pad
    x_right = pad + w_crop + gap
    y = 96
    for label, before, after, (x, y0) in rows:
        d.text((pad, y + 2), label, font=font(20, True), fill=TEXT)
        y += label_h

        d.text((x_left, y), "修改前", font=font(15), fill=MUTED)
        d.text((x_right, y), "修改后", font=font(15), fill=OK)
        y += col_label_h

        for i, img in enumerate((before, after)):
            box = img.crop((x, y0, x + crop_w, y0 + crop_h))
            box = box.resize((w_crop, h_crop), Image.LANCZOS)
            px = x_left + i * (w_crop + gap)
            canvas.paste(rounded(box, 10), (px, y))
            d.rectangle([px, y, px + w_crop - 1, y + h_crop - 1], outline=(70, 70, 82))
        y += h_crop + 24

    d.text((pad, y + 10), note, font=font(17), fill=ACCENT)
    path = os.path.join(OUT_DIR, out_name)
    canvas.save(path)
    print("saved", path, canvas.size)


os.makedirs(OUT_DIR, exist_ok=True)

old_songs, new_songs = load("old_songs.png"), load("new_songs.png")
old_now, new_now = load("old_now.png"), load("new_now.png")
old_lyric, new_lyric = load("old_lyric.png"), load("new_lyric.png")
old_albums, new_albums = load("old_albums.png"), load("new_albums.png")
old_artists, new_artists = load("old_artists.png"), load("new_artists.png")

build_pair(
    [
        ("① 歌曲列表", old_songs, new_songs),
        ("② 专辑页", old_albums, new_albums),
    ],
    "compare-1-list-albums.png",
    "界面总览（一）",
    "缩略图圆角收一档；播放条顶边那条线变清楚；专辑卡片更圆、右上多出刷新入口",
)

build_pair(
    [
        ("③ 播放页", old_now, new_now),
        ("④ 歌词页", old_lyric, new_lyric),
    ],
    "compare-2-player-lyrics.png",
    "界面总览（二）",
    "封面圆角 16 → 24dp；歌词来源从灰底胶囊退回一行小字，不再压住歌词",
)

build_detail(
    [
        ("播放条顶边（兼作内容区分界）", old_songs, new_songs, (0, 2296)),
        ("列表缩略图圆角", old_songs, new_songs, (44, 1130)),
        ("专辑卡圆角 · 首张卡", old_albums, new_albums, (52, 680)),
        ("专辑页顶栏右上角", old_albums, new_albums, (990, 150)),
        ("歌词来源标", old_lyric, new_lyric, (380, 350)),
        ("艺术家页顶栏右上角", old_artists, new_artists, (990, 150)),
    ],
    "compare-3-detail.png",
    "细节放大",
    "左 = 2.6.3，右 = 2.7.0，同一坐标裁切后放大 2 倍",
)
