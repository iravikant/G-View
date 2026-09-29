package com.acoder.gallery.presentation.home

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acoder.gallery.data.preferences.PreferencesRepository
import com.acoder.gallery.domain.model.Album
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.SortOrder
import com.acoder.gallery.domain.repository.MediaOpResult
import com.acoder.gallery.domain.usecase.DeleteMediaUseCase
import com.acoder.gallery.domain.usecase.MediaListBuilder
import com.acoder.gallery.domain.usecase.MediaUiState
import com.acoder.gallery.domain.usecase.ObserveMediaUseCase
import com.acoder.gallery.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-shot events the UI must react to imperatively (system dialogs, snackbars). */
sealed class HomeEvent {
    data class ConfirmDelete(val intentSender: IntentSender, val pendingKeys: Set<String>) : HomeEvent()
    data class ConfirmFavorite(val intentSender: IntentSender) : HomeEvent()
    data class Message(val text: String) : HomeEvent()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeMedia: ObserveMediaUseCase,
    private val deleteMedia: DeleteMediaUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val prefs: PreferencesRepository
) : ViewModel() {

    val sort: StateFlow<SortOrder> = prefs.sortOrder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOrder.NEWEST)
    val theme: StateFlow<String> = prefs.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "SYSTEM")
    val grid: StateFlow<Int> = prefs.gridSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)
    val autoPlay: StateFlow<Boolean> = prefs.autoPlay.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val dynamicColor: StateFlow<Boolean> = prefs.dynamicColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val query = MutableStateFlow("")
    val viewerItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val viewerIndex = MutableStateFlow(0)
    val filter = MutableStateFlow(MediaFilter.ALL)
    val album = MutableStateFlow<String?>(null)
    val albumName = MutableStateFlow<String?>(null)

    /** Selection keyed by [MediaItem.key], not the raw MediaStore id — image and video ids can collide. */
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val busy = MutableStateFlow(false)

    val events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 8)

    // ---- Media list: loaded in the background, cached here, kept fresh by a MediaStore observer ----

    private val accessGranted = MutableStateFlow(false)
    private val refreshTick = MutableStateFlow(0)

    /** Full unfiltered library. `null` until the first load finishes; keeps its last value while reloading. */
    private val allMedia: StateFlow<List<MediaItem>?> =
        combine(accessGranted, refreshTick) { granted, _ -> granted }
            .flatMapLatest { granted -> if (granted) observeMedia() else emptyFlow() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Rows for the grid (filtered, sorted, date-grouped). Rebuilt on Dispatchers.Default, never on the UI thread. */
    val mediaState: StateFlow<MediaUiState> = combine(
        allMedia,
        sort,
        filter,
        query.debounce { if (it.isEmpty()) 0L else 200L }.distinctUntilChanged(),
        album
    ) { all, currentSort, currentFilter, currentQuery, currentAlbum ->
        if (all == null) MediaUiState() else MediaListBuilder.build(all, currentSort, currentFilter, currentQuery, currentAlbum)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, MediaUiState())

    /** Albums come from the same cache — no extra MediaStore scan when opening the Albums tab. */
    val albums: StateFlow<List<Album>> = allMedia
        .filterNotNull()
        .map { MediaListBuilder.albums(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albumsLoading: StateFlow<Boolean> = allMedia
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // Drop selected items that disappeared (deleted elsewhere, moved, etc.).
        viewModelScope.launch {
            allMedia.filterNotNull().collect { list ->
                if (selected.value.isNotEmpty()) {
                    val keys = withContext(Dispatchers.Default) { list.mapTo(HashSet()) { it.key } }
                    selected.update { it.intersect(keys) }
                }
            }
        }
    }

    /** Start loading once storage access exists (also called again if access is granted later). */
    fun setAccess(granted: Boolean) { accessGranted.value = granted }

    /** Force a re-query, e.g. when the app returns to the foreground. Unchanged results cause no UI update. */
    fun refresh() { if (allMedia.value != null) refreshTick.update { it + 1 } }

    fun setSort(value: SortOrder) { viewModelScope.launch { prefs.setSort(value) } }
    fun setGrid(value: Int) { viewModelScope.launch { prefs.setGrid(value) } }
    fun setTheme(value: String) { viewModelScope.launch { prefs.setTheme(value) } }
    fun setAutoPlay(value: Boolean) { viewModelScope.launch { prefs.setAutoPlay(value) } }
    fun setDynamicColor(value: Boolean) { viewModelScope.launch { prefs.setDynamicColor(value) } }
    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: MediaFilter) { filter.value = value }

    fun openAlbum(id: String?, name: String?) {
        album.value = id
        albumName.value = name
        filter.value = MediaFilter.ALL
        query.value = ""
    }

    fun openViewer(items: List<MediaItem>, index: Int) {
        viewerItems.value = items
        viewerIndex.value = index
    }

    fun toggle(key: String) {
        selected.update { current -> if (key in current) current - key else current + key }
    }

    fun clearSelection() { selected.value = emptySet() }
    fun selectAll(keys: List<String>) { selected.value = keys.toSet() }

    /** Kicks off deletion; if the platform requires user confirmation, emits [HomeEvent.ConfirmDelete] instead of deleting immediately. */
    fun requestDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            when (val result = deleteMedia(items)) {
                is MediaOpResult.Done -> {
                    busy.value = false
                    selected.value = emptySet()
                    events.emit(HomeEvent.Message("${result.count} item${if (result.count == 1) "" else "s"} deleted"))
                }
                is MediaOpResult.NeedsConsent -> {
                    busy.value = false
                    events.emit(HomeEvent.ConfirmDelete(result.intentSender, items.map { it.key }.toSet()))
                }
                is MediaOpResult.Failed -> {
                    busy.value = false
                    events.emit(HomeEvent.Message(result.reason))
                }
            }
        }
    }

    /** Called after the system delete dialog returns OK, to clear selection and report the outcome. */
    fun onDeleteConfirmed(keys: Set<String>) {
        selected.value -= keys
        viewModelScope.launch { events.emit(HomeEvent.Message("${keys.size} item${if (keys.size == 1) "" else "s"} deleted")) }
    }

    fun toggleFavorite(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val makeFavorite = items.any { !it.isFavorite }
        viewModelScope.launch {
            when (val result = toggleFavoriteUseCase(items, makeFavorite)) {
                is MediaOpResult.Done -> events.emit(HomeEvent.Message(if (makeFavorite) "Added to favorites" else "Removed from favorites"))
                is MediaOpResult.NeedsConsent -> events.emit(HomeEvent.ConfirmFavorite(result.intentSender))
                is MediaOpResult.Failed -> events.emit(HomeEvent.Message(result.reason))
            }
        }
    }

    fun notify(text: String) { viewModelScope.launch { events.emit(HomeEvent.Message(text)) } }
}
