package com.melody.player.data

/** 从文件名里拆出来的「歌名 / 艺术家」。 */
data class GuessedMeta(
    val title: String,
    val artist: String,
)

/**
 * 文件名 → 元信息的兜底解析。
 *
 * 本地下载的 MP3 经常**只有编码器信息、没有任何 ID3 标签**（TIT2/TPE1/TALB 全空），
 * 这时 MediaStore 里的标题往往就是文件名、艺术家和专辑为空 —— 没有歌名/歌手，
 * 在线歌词、在线封面都无从查起。所以这里按常见的下载命名再拆一次：
 *
 * ```
 * 布拉格广场 - 蔡依林&周杰伦.mp3   →  title=布拉格广场  artist=蔡依林&周杰伦
 * 01. 稻香.mp3                    →  title=稻香        artist=""
 * 稻香 (Live) [320k].mp3          →  title=稻香
 * 稻香 feat. 某某.mp3              →  title=稻香        artist=某某
 * ```
 *
 * 拆不准的时候（两边都不像歌手），会把两种解释都返回，由打分环节挑更合理的那个。
 */
object FileNameMeta {

    /** 两侧带空格的短横线才算分隔符，避免误伤 `B-side`、`1979-2000` 这类名字。 */
    private val SEPARATOR = Regex("""\s+[-–—－]\s+""")

    /** 编号前缀：`01. `、`01 - `、`01_`。 */
    private val TRACK_PREFIX = Regex("""^\s*\d{1,3}\s*[.、_\-]\s*""")

    /** 末尾的音质/来源标记：`[320k]`、`(Live)`、`【HQ】`。 */
    private val TRAILING_TAG = Regex("""\s*[\[\(（【][^\]\)）】]{0,24}[\]\)）】]\s*$""")

    /** 一眼能看出"这是多个歌手"的分隔符。 */
    private const val ARTIST_MARKERS = "&＆、,，;；/／+＋"

    private val FEAT = Regex("""(?i)\s*(feat\.?|ft\.|featuring)\s*""")

    fun guesses(rawName: String?): List<GuessedMeta> {
        val name = rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        if (name.isBlank()) return emptyList()

        var cleaned = name.substringBeforeLast('.', name).ifBlank { name }
        cleaned = cleaned.replace(TRACK_PREFIX, "").trim()
        // 可能有多个尾巴（`稻香 (Live) [320k]`），循环剥到没变化为止
        repeat(3) {
            val next = cleaned.replace(TRAILING_TAG, "").trim()
            if (next == cleaned || next.isEmpty()) return@repeat
            cleaned = next
        }
        if (cleaned.isBlank()) return emptyList()

        // feat. / ft. 后面一定是合作歌手
        val feat = FEAT.split(cleaned)
        if (feat.size >= 2) {
            val title = feat.first().trim()
            val artist = feat.drop(1).joinToString(" ").trim()
            if (title.isNotBlank() && artist.isNotBlank()) {
                return listOf(GuessedMeta(title, artist))
            }
        }

        val parts = SEPARATOR.split(cleaned).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return listOf(GuessedMeta(cleaned, ""))

        val left = parts[0]
        val right = parts[1]
        val leftLooksArtist = looksLikeArtist(left)
        val rightLooksArtist = looksLikeArtist(right)
        return when {
            rightLooksArtist && !leftLooksArtist -> listOf(GuessedMeta(left, right))
            leftLooksArtist && !rightLooksArtist -> listOf(GuessedMeta(right, left))
            // 判断不了方向时两种都留，交给评分挑选
            else -> listOf(GuessedMeta(left, right), GuessedMeta(right, left))
        }
    }

    /** 最简单的一条规则：含 `&`/`、`/`,` 这类分隔符的一侧更像是歌手列表。 */
    private fun looksLikeArtist(value: String): Boolean = value.any { it in ARTIST_MARKERS }
}
