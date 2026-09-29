package com.acoder.gallery.domain.repository

import android.content.IntentSender
import androidx.paging.PagingSource
import com.acoder.gallery.domain.model.Album
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.SortOrder

/** Result of an operation that may need the user to confirm via a system dialog on Android 10+. */
sealed class MediaOpResult {
    data class Done(val count: Int) : MediaOpResult()
    data class NeedsConsent(val intentSender: IntentSender) : MediaOpResult()
    data class Failed(val reason: String) : MediaOpResult()
}

interface MediaRepository {
    fun pagingSource(sort: SortOrder, filter: MediaFilter, query: String, albumId: String? = null): PagingSource<String, MediaItem>
    suspend fun queryMedia(sort: SortOrder = SortOrder.NEWEST, filter: MediaFilter = MediaFilter.ALL, query: String = "", albumId: String? = null): List<MediaItem>
    suspend fun queryAlbums(): List<Album>
    suspend fun delete(items: List<MediaItem>): Int
    suspend fun requestDelete(items: List<MediaItem>): MediaOpResult
    suspend fun setFavorite(items: List<MediaItem>, favorite: Boolean): MediaOpResult
}
