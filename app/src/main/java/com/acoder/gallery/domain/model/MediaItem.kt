package com.acoder.gallery.domain.model

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

data class MediaItem(
    val id: Long, val uri: Uri, val name: String, val type: MediaType,
    val dateTaken: Long, val dateAdded: Long, val dateModified: Long,
    val size: Long, val width: Int, val height: Int, val duration: Long = 0L,
    val bucketId: String? = null, val bucketName: String? = null, val relativePath: String? = null,
    val mimeType: String? = null,
    val isFavorite: Boolean = false
) {
    val isVideo get() = type == MediaType.VIDEO
    val displayDate get() = if (dateTaken > 0) dateTaken else dateModified

    /** MediaStore ids are only unique per table, so images and videos need a combined key. */
    val key: String get() = if (isVideo) "v$id" else "i$id"
}

data class Album(val id: String, val name: String, val count: Int, val coverUri: Uri?, val relativePath: String?)

enum class MediaFilter { ALL, PHOTOS, VIDEOS, LARGE, RECENT, FAVORITES }
enum class SortOrder { NEWEST, OLDEST, NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC, PHOTOS_FIRST, VIDEOS_FIRST }

/** A row in the photo grid: either a date header or a media tile. */
sealed class GridRow {
    abstract val key: String
    data class Header(val label: String, override val key: String) : GridRow()
    data class Media(val item: MediaItem) : GridRow() {
        override val key: String get() = item.key
    }
}
