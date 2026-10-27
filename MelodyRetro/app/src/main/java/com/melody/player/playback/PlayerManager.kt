package com.melody.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.melody.player.data.PlayStatsRepository
import com.melody.player.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val KEY_ALBUM_ID = "album_id"
const val KEY_PATH = "file_path"

/** 当前播放歌曲在 UI 层需要的全部信息。 */
data class NowPlaying(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val path: String?,
    val durationMs: Long = 0L,
)

data class PlayerUiState(
    val connected: Boolean = false,
    val current: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val queueSize: Int = 0,
)

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(artist)
            .setTrackNumber(trackNumber)
            .setDurationMs(durationMs)
            .setArtworkUri(artworkUri)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(
                Bundle().apply {
                    putLong(KEY_ALBUM_ID, albumId)
                    putString(KEY_PATH, path)
                }
            )
            .build()
    )
    .build()

/**
 * 应用级播放控制器：绑定 [PlaybackService] 的 MediaSession，
 * 对 UI 暴露单一 [PlayerUiState] 与一组播放操作。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object PlayerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var appContext: Context
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()

    /** mediaId -> Track，用于在 MediaSession 往返后仍然取到专辑/文件路径等附加信息。 */
    private val knownTracks = HashMap<String, Track>()

    /** 上一次已经记入播放统计的曲目，避免同一首反复触发。 */
    private var lastRecordedTrackId = 0L

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        connect()
    }

    private fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java)
        )
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(listener)
                    while (pendingActions.isNotEmpty()) {
                        runCatching { pendingActions.removeFirst().invoke(mediaController) }
                    }
                    sync(mediaController)
                    _state.update { it.copy(connected = true) }
                    startTicker()
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    _state.update {
                        it.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0L),
                            isPlaying = c.isPlaying,
                            durationMs = if (c.duration > 0) c.duration else it.durationMs,
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    private fun sync(player: Player) {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val fallback = item?.mediaId?.let { knownTracks[it] }
        val current = if (item == null) {
            null
        } else {
            NowPlaying(
                id = item.mediaId.toLongOrNull() ?: 0L,
                title = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: fallback?.title.orEmpty(),
                artist = metadata?.artist?.toString()?.takeIf { it.isNotBlank() }
                    ?: fallback?.artist.orEmpty(),
                album = metadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                    ?: fallback?.album.orEmpty(),
                albumId = metadata?.extras?.getLong(KEY_ALBUM_ID)?.takeIf { it > 0L }
                    ?: fallback?.albumId ?: 0L,
                path = metadata?.extras?.getString(KEY_PATH) ?: fallback?.path,
                durationMs = metadata?.durationMs?.takeIf { it > 0L } ?: fallback?.durationMs ?: 0L,
            )
        }
        val duration = player.duration
        val metaDuration = metadata?.durationMs ?: fallback?.durationMs ?: 0L

        // 播放统计：只在「换了一首且真的在播」时记一次，
        // sync 会被位置 / 状态频繁触发，不做去重会把次数刷上天
        if (current != null && player.isPlaying && current.id != lastRecordedTrackId) {
            lastRecordedTrackId = current.id
            if (::appContext.isInitialized) {
                val id = current.id
                val ctx = appContext
                scope.launch { PlayStatsRepository.recordPlay(ctx, id) }
            }
        } else if (current == null) {
            lastRecordedTrackId = 0L
        }

        _state.update {
            it.copy(
                connected = true,
                current = current,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = when {
                    duration > 0 -> duration
                    metaDuration > 0 -> metaDuration
                    current == null -> 0L
                    else -> it.durationMs
                },
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                queueSize = player.mediaItemCount,
            )
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            runCatching { action(c) }
        } else {
            pendingActions.addLast(action)
        }
    }

    /**
     * 用给定列表替换播放队列并从 [startIndex] 开始播放。
     * [shuffle] 为 true 时同时打开随机播放模式，并随机挑选第一首。
     */
    fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        if (knownTracks.size > 2000) knownTracks.clear()
        tracks.forEach { knownTracks[it.id.toString()] = it }

        val items = tracks.map { it.toMediaItem() }
        val start = if (shuffle && items.size > 1) items.indices.random() else startIndex
        withController { c ->
            if (shuffle) c.shuffleModeEnabled = true
            c.setMediaItems(items, start.coerceIn(0, items.lastIndex), 0L)
            c.prepare()
            c.play()
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { c ->
        if (c.currentPosition > 3_000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeatMode() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
