package com.melody.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melody.player.data.LibraryData
import com.melody.player.data.MediaLibraryRepository
import com.melody.player.data.model.Album
import com.melody.player.data.model.Artist
import com.melody.player.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = !loading && tracks.isEmpty()
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaLibraryRepository(application)
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var loadedSortOrder = -1
    private var loadedOnce = false

    fun refresh(sortOrder: Int, force: Boolean = false) {
        if (!force && loadedOnce && loadedSortOrder == sortOrder && _state.value.error == null) return
        loadedSortOrder = sortOrder
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.load(sortOrder) }
                .onSuccess { data: LibraryData ->
                    loadedOnce = true
                    _state.value = LibraryUiState(
                        loading = false,
                        tracks = data.tracks,
                        albums = data.albums,
                        artists = data.artists,
                    )
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = throwable.message ?: "读取媒体库失败",
                        )
                    }
                }
        }
    }
}
