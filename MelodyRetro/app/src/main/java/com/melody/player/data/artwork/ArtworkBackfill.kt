package com.melody.player.data.artwork

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "补全缺失封面"批处理。
 *
 * 只在用户主动点击时启动，按专辑逐个走 [OnlineArtworkStore.ensure]：
 * 已有内嵌封面或已缓存的专辑直接跳过（不发请求），只有真正缺封面的才联网。
 * 每张之间间隔 [THROTTLE_MS]，把带宽和 CPU 让给正在播放的音频解码。
 */
object ArtworkBackfill {

    private const val TAG = "ArtworkBackfill"
    private const val THROTTLE_MS = 120L

    data class State(
        val running: Boolean = false,
        val finished: Boolean = false,
        val total: Int = 0,
        val processed: Int = 0,
        /** 本次新补到的封面数。 */
        val fetched: Int = 0,
        /** 检索了但没有匹配。 */
        val noMatch: Int = 0,
        /** 网络/接口异常。 */
        val failed: Int = 0,
        /** 取消时未处理的数量。 */
        val cancelled: Boolean = false,
    ) {
        val progress: Float
            get() = if (total <= 0) 0f else processed.toFloat() / total
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope, context: Context, force: Boolean = false) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            val ids = withContext(kotlinx.coroutines.Dispatchers.IO) { allAlbumIds(appContext) }
            _state.value = State(running = true, total = ids.size)
            var fetched = 0
            var noMatch = 0
            var failed = 0
            var processed = 0
            for ((index, albumId) in ids.withIndex()) {
                val result = OnlineArtworkStore.ensure(appContext, albumId, force = force)
                when (result) {
                    OnlineArtworkStore.Result.FETCHED -> fetched++
                    OnlineArtworkStore.Result.NO_MATCH -> noMatch++
                    OnlineArtworkStore.Result.FAILED -> failed++
                    else -> Unit
                }
                processed = index + 1
                _state.value = State(
                    running = true,
                    total = ids.size,
                    processed = processed,
                    fetched = fetched,
                    noMatch = noMatch,
                    failed = failed,
                )
                delay(THROTTLE_MS)
            }
            _state.value = State(
                running = false,
                finished = true,
                total = ids.size,
                processed = processed,
                fetched = fetched,
                noMatch = noMatch,
                failed = failed,
            )
            Log.i(TAG, "backfill done: total=$processed fetched=$fetched noMatch=$noMatch failed=$failed")
        }
    }

    fun cancel() {
        val current = _state.value
        job?.cancel()
        job = null
        if (current.running) {
            _state.value = current.copy(running = false, finished = true, cancelled = true)
        }
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.value = State()
    }

    /** 媒体库里所有有音乐的专辑 id（去重）。 */
    private fun allAlbumIds(context: Context): List<Long> {
        val ids = LinkedHashSet<Long>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id > 0L) ids += id
                }
            }
        }.onFailure { Log.w(TAG, "album id query failed: ${it.javaClass.simpleName}") }
        return ids.toList()
    }
}
