package com.acoder.gallery.domain.repository

import android.content.IntentSender
import androidx.paging.PagingSource
import com.acoder.gallery.domain.model.Album
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.SortOrder
import kotlinx.coroutines.flow.Flow

/** Result of an operation that may need the user to confirm via a system dialog on Android 10+. */
sealed class MediaOpResult {
    data class Done(val count: Int) : MediaOpResult()
    data class NeedsConsent(val intentSender: IntentSender) : MediaOpResult()
    data class Failed(val reason: String) : MediaOpResult()
}

interface MediaRepository {
    /** True when the OS has a real Recycle bin (Android 11+). Below that, "delete" is permanent. */
    val supportsTrash: Boolean

    /**
     * Emits the full, unfiltered media list (newest first) and re-emits whenever MediaStore changes.
     * All work happens off the main thread; identical consecutive results are dropped.
     */
    fun observeMedia(): Flow<List<MediaItem>>

    /** Items currently in the Recycle bin (always empty below Android 11). */
    fun observeTrash(): Flow<List<MediaItem>>

    suspend fun delete(items: List<MediaItem>): Int

    /** Permanently deletes [items]; also works for items that are already in the Recycle bin. */
    suspend fun requestDelete(items: List<MediaItem>): MediaOpResult

    /** Moves [items] to the Recycle bin ([trashed] = true) or restores them ([trashed] = false). */
    suspend fun trash(items: List<MediaItem>, trashed: Boolean): MediaOpResult

    suspend fun setFavorite(items: List<MediaItem>, favorite: Boolean): MediaOpResult

    fun pagingSource(sort: SortOrder, filter: MediaFilter, query: String, albumId: String? = null): PagingSource<String, MediaItem>

    suspend fun queryAlbums(): List<Album>
}
