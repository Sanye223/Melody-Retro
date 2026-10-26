package com.melody.player.util

import java.util.Locale

/** 3:45 / 1:02:03 */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** 用于专辑/艺术家头部的「12 首 · 48 分钟」这类摘要。 */
fun formatSummary(count: Int, totalMs: Long): String {
    val totalMinutes = totalMs / 60_000
    return when {
        totalMinutes >= 60 -> {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            "$count 首 · ${hours} 小时 ${minutes} 分"
        }

        else -> "$count 首 · $totalMinutes 分钟"
    }
}
