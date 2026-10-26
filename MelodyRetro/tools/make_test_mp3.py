"""生成一个带 ID3v2.3 标签的静音 MP3，用于在模拟器里验证「媒体库扫描 + 在线歌词匹配」。

结构：ID3v2.3 头（TIT2/TPE1/TALB/TRCK/TYER）+ N 个静音 MPEG-1 Layer III 帧。
静音帧 = 合法帧头 0xFF 0xFB 0x90 0x00 + 全 0 主数据（解码为静音），
时长按 128kbps 估算（字节数 * 8 / 128000），与真实歌曲时长接近，便于歌词按时长匹配。
"""

import io
import struct
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "稻香.mp3"
TITLE = "稻香"
ARTIST = "周杰伦"
ALBUM = "魔杰座"
YEAR = "2008"
TRACK = "1"
TARGET_SECONDS = 223  # 与原曲一致，便于 LRCLIB 按时长匹配


def id3_frame(frame_id: str, text: str) -> bytes:
    # 0x01 = UTF-16（带 BOM），ID3v2.3 支持中文
    payload = b"\x01" + text.encode("utf-16")
    return frame_id.encode("ascii") + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def syncsafe(size: int) -> bytes:
    return bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])


frames = (
    id3_frame("TIT2", TITLE)
    + id3_frame("TPE1", ARTIST)
    + id3_frame("TALB", ALBUM)
    + id3_frame("TRCK", TRACK)
    + id3_frame("TYER", YEAR)
)
tag = b"ID3\x03\x00\x00" + syncsafe(len(frames)) + frames

FRAME = bytes([0xFF, 0xFB, 0x90, 0x00]) + b"\x00" * (417 - 4)
FRAME_SECONDS = 1152 / 44100
frame_count = int(TARGET_SECONDS / FRAME_SECONDS)

with io.open(PATH, "wb") as fh:
    fh.write(tag)
    fh.write(FRAME * frame_count)

print(f"wrote {PATH}: tag={len(tag)}B frames={frame_count} size={(len(tag) + len(FRAME) * frame_count) / 1024 / 1024:.2f}MB")
