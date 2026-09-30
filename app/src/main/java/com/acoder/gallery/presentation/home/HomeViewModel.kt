package com.acoder.gallery.presentation.home

import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acoder.gallery.core.pdf.PdfCreator
import com.acoder.gallery.core.sharing.ExportManager
import com.acoder.gallery.core.sharing.MediaShareManager
import com.acoder.gallery.data.preferences.PreferencesRepository
import com.acoder.gallery.domain.model.Album
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.PdfPageSize
import com.acoder.gallery.domain.model.PdfQuality
import com.acoder.gallery.domain.model.SortOrder
import com.acoder.gallery.domain.repository.MediaOpResult
import com.acoder.gallery.domain.usecase.DeleteMediaUseCase
import com.acoder.gallery.domain.usecase.MediaListBuilder
import com.acoder.gallery.domain.usecase.MediaUiState
import com.acoder.gallery.domain.usecase.ObserveMediaUseCase
import com.acoder.gallery.domain.usecase.ObserveTrashUseCase
import com.acoder.gallery.domain.usecase.ToggleFavoriteUseCase
import com.acoder.gallery.domain.usecase.TrashMediaUseCase
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

/** Things the user can do to media that may need the OS to confirm. */
enum class MediaAction { TRASH, RESTORE, DELETE_FOREVER, FAVORITE }

/** One-shot events the UI must react to imperatively (system dialogs, snackbars). */
sealed class HomeEvent {
    /** The OS wants the user to approve [action] on [keys]; [flag] is the new favourite state for FAVORITE. */
    data class ConfirmAction(val intentSender: IntentSender, val action: MediaAction, val keys: Set<String>, val flag: Boolean = false) : HomeEvent()
    /** [action] finished for [keys] (either straight away or after the OS dialog). */
    data class ActionDone(val action: MediaAction, val keys: Set<String>, val flag: Boolean = false) : HomeEvent()
    data class Message(val text: String) : HomeEvent()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeMedia: ObserveMediaUseCase,
    observeTrash: ObserveTrashUseCase,
    private val trashMedia: TrashMediaUseCase,
    private val deleteMedia: DeleteMediaUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val prefs: PreferencesRepository,
    private val pdfCreator: PdfCreator
) : ViewModel() {

    val sort: StateFlow<SortOrder> = prefs.sortOrder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOrder.NEWEST)
    val theme: StateFlow<String> = prefs.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "SYSTEM")
    val grid: StateFlow<Int> = prefs.gridSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 4)
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

    /** Items in the Recycle bin (empty on Android 10 and below, where delete is permanent). */
    val trashItems: StateFlow<List<MediaItem>> = combine(accessGranted, refreshTick) { granted, _ -> granted }
        .flatMapLatest { granted -> if (granted) observeTrash() else emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether "delete" means "move to Recycle bin" on this device. */
    val trashSupported: Boolean get() = trashMedia.supported

    val sortSheetOpen = MutableStateFlow(false)

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

    /** Leaves album mode without touching the user's search / filter. */
    fun clearAlbum() {
        if (album.value != null) { album.value = null; albumName.value = null }
    }

    fun cycleGrid() { setGrid(if (grid.value >= 6) 2 else grid.value + 1) }

    // ---- Delete / Recycle bin / Favourite ----------------------------------------------------

    /** "Delete" in the UI: moves to the Recycle bin (permanent delete where the OS has no bin). */
    fun moveToTrash(items: List<MediaItem>) = runAction(MediaAction.TRASH, items) { trashMedia(items) }

    fun restore(items: List<MediaItem>) = runAction(MediaAction.RESTORE, items) { trashMedia.restore(items) }

    fun deleteForever(items: List<MediaItem>) = runAction(MediaAction.DELETE_FOREVER, items) { deleteMedia(items) }

    fun emptyTrash() = deleteForever(trashItems.value)

    fun toggleFavorite(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val makeFavorite = items.any { !it.isFavorite }
        runAction(MediaAction.FAVORITE, items, makeFavorite) { toggleFavoriteUseCase(items, makeFavorite) }
    }

    private fun runAction(action: MediaAction, items: List<MediaItem>, flag: Boolean = false, op: suspend () -> MediaOpResult) {
        if (items.isEmpty()) return
        val keys = items.map { it.key }.toSet()
        viewModelScope.launch {
            busy.value = true
            val result = try { op() } catch (e: Exception) { MediaOpResult.Failed(e.message ?: "Something went wrong") } finally { busy.value = false }
            when (result) {
                is MediaOpResult.Done -> finishAction(action, keys, flag, result.count)
                is MediaOpResult.NeedsConsent -> events.emit(HomeEvent.ConfirmAction(result.intentSender, action, keys, flag))
                is MediaOpResult.Failed -> events.emit(HomeEvent.Message(result.reason))
            }
        }
    }

    /** Called after the OS confirmation dialog returns OK. */
    fun onActionConfirmed(action: MediaAction, keys: Set<String>, flag: Boolean) {
        viewModelScope.launch { finishAction(action, keys, flag, keys.size) }
    }

    private suspend fun finishAction(action: MediaAction, keys: Set<String>, flag: Boolean, count: Int) {
        selected.update { it - keys }
        events.emit(HomeEvent.ActionDone(action, keys, flag))
        val noun = if (count == 1) "item" else "items"
        events.emit(
            HomeEvent.Message(
                when (action) {
                    MediaAction.TRASH -> if (trashSupported) "$count $noun moved to Recycle bin" else "$count $noun deleted"
                    MediaAction.RESTORE -> "$count $noun restored"
                    MediaAction.DELETE_FOREVER -> "$count $noun deleted permanently"
                    MediaAction.FAVORITE -> if (flag) "Added to favourites" else "Removed from favourites"
                }
            )
        )
    }

    fun createPdf(context: Context, items: List<MediaItem>, size: PdfPageSize, quality: PdfQuality) {
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                val file = pdfCreator.create(items, size, quality) { _ -> }
                val saved = ExportManager.saveToDownloads(context, file, "application/pdf", "Gallery_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.Main) {
                    if (saved != null) {
                        MediaShareManager.share(context, listOf(saved), "application/pdf")
                    } else {
                        events.emit(HomeEvent.Message("Couldn't save the PDF"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    events.emit(HomeEvent.Message("PDF creation failed: ${e.message ?: "unknown error"}"))
                }
            } finally {
                busy.value = false
                clearSelection()
            }
        }
    }

    fun notify(text: String) { viewModelScope.launch { events.emit(HomeEvent.Message(text)) } }
}
