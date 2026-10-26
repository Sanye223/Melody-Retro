package com.melody.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melody.player.data.LyricRepository
import com.melody.player.data.model.LyricsQuery
import com.melody.player.data.model.LyricsResult
import com.melody.player.data.model.LyricsSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LyricsUiState(
    val loading: Boolean = false,
    val result: LyricsResult = LyricsResult(null, LyricsSource.NONE),
    val queryKey: String? = null,
)

class LyricsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastQuery: LyricsQuery? = null
    private var lastOnlineEnabled: Boolean = true

    fun load(query: LyricsQuery, onlineEnabled: Boolean, forceRefresh: Boolean = false) {
        val key = "${query.id}|${query.title}|$onlineEnabled"
        if (!forceRefresh && key == _state.value.queryKey && !_state.value.loading) return

        lastQuery = query
        lastOnlineEnabled = onlineEnabled
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = LyricsUiState(loading = true, queryKey = key)
            val result = runCatching {
                LyricRepository.load(getApplication(), query, onlineEnabled, forceRefresh)
            }.getOrElse { throwable ->
                LyricsResult(
                    lyrics = null,
                    source = LyricsSource.NONE,
                    message = "歌词加载失败：${throwable.javaClass.simpleName}",
                )
            }
            _state.value = LyricsUiState(loading = false, result = result, queryKey = key)
        }
    }

    fun retry() {
        lastQuery?.let { load(it, lastOnlineEnabled, forceRefresh = true) }
    }
}
