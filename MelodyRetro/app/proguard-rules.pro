# Media3 / ExoPlayer
-dontwarn org.checkerframework.**
-keep class androidx.media3.** { *; }
-keep class com.google.common.** { *; }

# 数据模型（序列化到 Bundle 的字段名）
-keep class com.melody.player.data.model.** { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
