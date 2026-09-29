package com.acoder.gallery.data.mediastore

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.MediaType
import com.acoder.gallery.domain.model.SortOrder

/**
 * Cursor-based MediaStore pagination over Images and Video collections.
 */
class MediaStorePagingSource(
    private val resolver: ContentResolver,
    private val sort: SortOrder,
    private val filter: MediaFilter,
    private val query: String,
    private val albumId: String?
) : PagingSource<String, MediaItem>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, MediaItem> = try {
        val key = params.key?.split("#", limit = 2)
        val rawCursor = key?.getOrNull(0)
        val cursorValue = if (sort == SortOrder.NAME_ASC || sort == SortOrder.NAME_DESC) {
            rawCursor?.let { String(Base64.decode(it, Base64.NO_WRAP)) }
        } else {
            rawCursor
        }
        val cursorId = key?.getOrNull(1)?.toLongOrNull()
        val limit = params.loadSize.coerceIn(30, 60)
        val candidates = ArrayList<MediaItem>(limit * 2)

        queryTable(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaType.IMAGE,
            cursorValue,
            cursorId,
            limit,
            candidates
        )
        queryTable(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaType.VIDEO,
            cursorValue,
            cursorId,
            limit,
            candidates
        )

        candidates.sortWith(comparator(sort))
        val page = candidates.take(limit)
        val last = page.lastOrNull()
        val next = last?.let(::cursorFor)

        LoadResult.Page(
            data = page,
            prevKey = null,
            nextKey = if (page.size < limit) null else next
        )
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    private fun queryTable(
        base: Uri,
        type: MediaType,
        cursorValue: String?,
        cursorId: Long?,
        limit: Int,
        out: MutableList<MediaItem>
    ) {
        val selection = ArrayList<String>()
        val args = ArrayList<String>()

        if (query.isNotBlank()) {
            selection += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            args += "%${query.trim()}%"
        }

        if (albumId != null && albumId != "RECENT") {
            selection += "${MediaStore.MediaColumns.BUCKET_ID} = ?"
            args += albumId
        }

        if (albumId == "RECENT") {
            selection += "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
            args += (System.currentTimeMillis() / 1000 - 7 * 86400).toString()
        }

        when (filter) {
            MediaFilter.PHOTOS -> if (type != MediaType.IMAGE) return
            MediaFilter.VIDEOS -> if (type != MediaType.VIDEO) return
            MediaFilter.LARGE -> {
                selection += "${MediaStore.MediaColumns.SIZE} >= ?"
                args += (25L * 1024 * 1024).toString()
            }
            MediaFilter.RECENT -> {
                selection += "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
                args += (System.currentTimeMillis() / 1000 - 7 * 86400).toString()
            }
            MediaFilter.FAVORITES -> {
                if (Build.VERSION.SDK_INT < 29) return
                selection += "is_favorite = 1"
            }
            else -> Unit
        }

        val order = when (sort) {
            SortOrder.NEWEST, SortOrder.OLDEST ->
                "${MediaStore.MediaColumns.DATE_MODIFIED} ${if (sort == SortOrder.NEWEST) "DESC" else "ASC"}, " +
                    "${MediaStore.MediaColumns._ID} ${if (sort == SortOrder.NEWEST) "DESC" else "ASC"}"
            SortOrder.NAME_ASC ->
                "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC, ${MediaStore.MediaColumns._ID} ASC"
            SortOrder.NAME_DESC ->
                "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE DESC, ${MediaStore.MediaColumns._ID} DESC"
            SortOrder.SIZE_DESC ->
                "${MediaStore.MediaColumns.SIZE} DESC, ${MediaStore.MediaColumns._ID} DESC"
            SortOrder.SIZE_ASC ->
                "${MediaStore.MediaColumns.SIZE} ASC, ${MediaStore.MediaColumns._ID} ASC"
            SortOrder.PHOTOS_FIRST, SortOrder.VIDEOS_FIRST ->
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        }

        if (cursorValue != null) {
            val column = when (sort) {
                SortOrder.NEWEST, SortOrder.OLDEST -> MediaStore.MediaColumns.DATE_MODIFIED
                SortOrder.NAME_ASC, SortOrder.NAME_DESC -> MediaStore.MediaColumns.DISPLAY_NAME
                SortOrder.SIZE_DESC, SortOrder.SIZE_ASC -> MediaStore.MediaColumns.SIZE
                else -> MediaStore.MediaColumns.DATE_MODIFIED
            }
            val ascending = sort == SortOrder.OLDEST ||
                sort == SortOrder.NAME_ASC ||
                sort == SortOrder.SIZE_ASC
            val operator = if (ascending) ">" else "<"
            val idOperator = operator

            selection += "($column $operator ? OR ($column = ? AND ${MediaStore.MediaColumns._ID} $idOperator ?))"
            args += cursorValue
            args += cursorValue
            args += cursorId?.toString() ?: "0"
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Video.Media.DURATION
        ).let { if (Build.VERSION.SDK_INT >= 29) it + "is_favorite" else it }

        val sortOrder = if (sort == SortOrder.PHOTOS_FIRST || sort == SortOrder.VIDEOS_FIRST) {
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        } else {
            order
        }

        resolver.query(
            base,
            projection,
            selection.joinToString(" AND ").ifBlank { null },
            args.toTypedArray().ifEmpty { null },
            sortOrder
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val itemId = cursor.getLong(idIndex)
                out += MediaItem(
                    id = itemId,
                    uri = android.content.ContentUris.withAppendedId(base, itemId),
                    name = cursor.str(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(),
                    type = type,
                    dateTaken = cursor.lng(MediaStore.MediaColumns.DATE_TAKEN),
                    dateAdded = cursor.lng(MediaStore.MediaColumns.DATE_ADDED),
                    dateModified = cursor.lng(MediaStore.MediaColumns.DATE_MODIFIED) * 1000,
                    size = cursor.lng(MediaStore.MediaColumns.SIZE),
                    width = cursor.int(MediaStore.MediaColumns.WIDTH),
                    height = cursor.int(MediaStore.MediaColumns.HEIGHT),
                    duration = cursor.lng(MediaStore.Video.Media.DURATION),
                    bucketId = cursor.str(MediaStore.MediaColumns.BUCKET_ID),
                    bucketName = cursor.str(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                    relativePath = cursor.str(MediaStore.MediaColumns.RELATIVE_PATH),
                    mimeType = cursor.str(MediaStore.MediaColumns.MIME_TYPE),
                    isFavorite = cursor.int("is_favorite") == 1
                )
            }
        }
    }

    private fun comparator(sortOrder: SortOrder): Comparator<MediaItem> = when (sortOrder) {
        SortOrder.NEWEST -> compareByDescending<MediaItem> { it.dateModified }.thenByDescending { it.id }
        SortOrder.OLDEST -> compareBy<MediaItem> { it.dateModified }.thenBy { it.id }
        SortOrder.NAME_ASC -> compareBy<MediaItem> { it.name.lowercase() }.thenBy { it.id }
        SortOrder.NAME_DESC -> compareByDescending<MediaItem> { it.name.lowercase() }.thenByDescending { it.id }
        SortOrder.SIZE_DESC -> compareByDescending<MediaItem> { it.size }.thenByDescending { it.id }
        SortOrder.SIZE_ASC -> compareBy<MediaItem> { it.size }.thenBy { it.id }
        SortOrder.PHOTOS_FIRST -> compareBy<MediaItem> { if (it.isVideo) 1 else 0 }.thenByDescending { it.dateModified }
        SortOrder.VIDEOS_FIRST -> compareBy<MediaItem> { if (it.isVideo) 0 else 1 }.thenByDescending { it.dateModified }
    }

    private fun cursorFor(item: MediaItem): String {
        val value = when (sort) {
            SortOrder.NEWEST, SortOrder.OLDEST -> item.dateModified.toString()
            SortOrder.NAME_ASC, SortOrder.NAME_DESC ->
                Base64.encodeToString(item.name.toByteArray(), Base64.NO_WRAP)
            SortOrder.SIZE_DESC, SortOrder.SIZE_ASC -> item.size.toString()
            SortOrder.PHOTOS_FIRST, SortOrder.VIDEOS_FIRST -> item.dateModified.toString()
        }
        return "$value#${item.id}"
    }

    override fun getRefreshKey(state: PagingState<String, MediaItem>): String? = null
}

private fun android.database.Cursor.str(name: String): String? =
    getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun android.database.Cursor.lng(name: String): Long =
    getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

private fun android.database.Cursor.int(name: String): Int =
    getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getInt) ?: 0
