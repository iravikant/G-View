package com.acoder.gallery.data.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.acoder.gallery.domain.model.Album
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.MediaType
import com.acoder.gallery.domain.model.SortOrder
import com.acoder.gallery.domain.repository.MediaOpResult
import com.acoder.gallery.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreRepository @Inject constructor(@ApplicationContext private val context: Context) : MediaRepository {
    private val resolver: ContentResolver get() = context.contentResolver
    private val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    override suspend fun queryMedia(sort: SortOrder, filter: MediaFilter, query: String, albumId: String?): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaItem>()
        queryCollection(imageUri, MediaType.IMAGE, filter, query, albumId, out)
        queryCollection(videoUri, MediaType.VIDEO, filter, query, albumId, out)
        out.sortWith(comparator(sort))
        out
    }

    private fun favoriteProjectionColumn(): String? = if (Build.VERSION.SDK_INT >= 29) "is_favorite" else null

    private fun queryCollection(base: Uri, type: MediaType, filter: MediaFilter, query: String, albumId: String?, out: MutableList<MediaItem>) {
        val p = arrayListOf<String>()
        val a = arrayListOf<String>()
        if (query.isNotBlank()) { p += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"; a += "%${query.trim()}%" }
        if (albumId != null && albumId != "RECENT") { p += "${MediaStore.MediaColumns.BUCKET_ID} = ?"; a += albumId }
        if (albumId == "RECENT") { p += "${MediaStore.MediaColumns.DATE_ADDED} >= ?"; a += (System.currentTimeMillis() / 1000 - 7 * 86400).toString() }
        when (filter) {
            MediaFilter.PHOTOS -> if (type != MediaType.IMAGE) return
            MediaFilter.VIDEOS -> if (type != MediaType.VIDEO) return
            MediaFilter.LARGE -> { p += "${MediaStore.MediaColumns.SIZE} >= ?"; a += (25L * 1024 * 1024).toString() }
            MediaFilter.RECENT -> { p += "${MediaStore.MediaColumns.DATE_ADDED} >= ?"; a += (System.currentTimeMillis() / 1000 - 7 * 86400).toString() }
            MediaFilter.FAVORITES -> {
                if (Build.VERSION.SDK_INT < 29) return
                p += "is_favorite = 1"
            }
            else -> Unit
        }
        val favoriteCol = favoriteProjectionColumn()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Video.Media.DURATION
        ).let { if (favoriteCol != null) it + favoriteCol else it }
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        resolver.query(base, projection, p.joinToString(" AND ").ifBlank { null }, a.toTypedArray().ifEmpty { null }, order)?.use { c ->
            val idIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) {
                val itemId = c.getLong(idIndex)
                out += c.toMediaItem(itemId, ContentUris.withAppendedId(base, itemId), type, favoriteCol)
            }
        }
    }

    override fun pagingSource(sort: SortOrder, filter: MediaFilter, query: String, albumId: String?) =
        MediaStorePagingSource(resolver, sort, filter, query, albumId)

    override suspend fun queryAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, Pair<Album, Int>>()
        fun collect(base: Uri) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH
            )
            resolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val bucketId = c.getStringOrNull(MediaStore.MediaColumns.BUCKET_ID) ?: continue
                    val current = map[bucketId]
                    if (current == null) {
                        val mediaId = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val album = Album(
                            bucketId,
                            c.getStringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME) ?: "Other",
                            1,
                            ContentUris.withAppendedId(base, mediaId),
                            c.getStringOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
                        )
                        map[bucketId] = album to 1
                    } else {
                        map[bucketId] = current.first to current.second + 1
                    }
                }
            }
        }
        collect(imageUri); collect(videoUri)
        val result = map.values.map { it.first.copy(count = it.second) }.toMutableList()
        result.sortByDescending { it.count }
        try {
            val cutoff = System.currentTimeMillis() / 1000 - 7 * 86400
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            var recentCount = 0
            var recentCover: Uri? = null
            fun recentCollect(base: Uri) {
                resolver.query(base, projection, "${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf(cutoff.toString()), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        recentCount++
                        if (recentCover == null) recentCover = ContentUris.withAppendedId(base, c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
                    }
                }
            }
            recentCollect(imageUri); recentCollect(videoUri)
            if (recentCount > 0) result.add(0, Album("RECENT", "Recent", recentCount, recentCover, null))
        } catch (_: Exception) { }
        result
    }

    override suspend fun delete(items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        var n = 0
        items.forEach { try { if (resolver.delete(it.uri, null, null) > 0) n++ } catch (_: Exception) { } }
        n
    }

    override suspend fun requestDelete(items: List<MediaItem>): MediaOpResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext MediaOpResult.Done(0)
        if (Build.VERSION.SDK_INT >= 30) {
            return@withContext try {
                val pi = MediaStore.createDeleteRequest(resolver, items.map { it.uri })
                MediaOpResult.NeedsConsent(pi.intentSender)
            } catch (e: Exception) {
                MediaOpResult.Failed(e.message ?: "Unable to delete")
            }
        }
        MediaOpResult.Done(delete(items))
    }

    override suspend fun setFavorite(items: List<MediaItem>, favorite: Boolean): MediaOpResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext MediaOpResult.Done(0)
        if (Build.VERSION.SDK_INT >= 30) {
            return@withContext try {
                val pi = MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, favorite)
                MediaOpResult.NeedsConsent(pi.intentSender)
            } catch (e: Exception) {
                MediaOpResult.Failed(e.message ?: "Unable to update favorites")
            }
        }
        MediaOpResult.Failed("Favorites need Android 11 or later")
    }

    suspend fun createPending(name: String, mime: String, relativePath: String = "Pictures/Gallery/"): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath); put(MediaStore.MediaColumns.IS_PENDING, 1) }
        }
        resolver.insert(if (mime.startsWith("video")) videoUri else imageUri, values)
    }

    suspend fun finishPending(uri: Uri) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    suspend fun writeBytes(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to open output")
    }

    private fun comparator(s: SortOrder) = when (s) {
        SortOrder.NEWEST -> compareByDescending<MediaItem> { it.displayDate }
        SortOrder.OLDEST -> compareBy<MediaItem> { it.displayDate }
        SortOrder.NAME_ASC -> compareBy { it.name.lowercase() }
        SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase() }
        SortOrder.SIZE_DESC -> compareByDescending { it.size }
        SortOrder.SIZE_ASC -> compareBy { it.size }
        SortOrder.PHOTOS_FIRST -> compareBy<MediaItem> { if (it.isVideo) 1 else 0 }.thenByDescending { it.displayDate }
        SortOrder.VIDEOS_FIRST -> compareBy<MediaItem> { if (it.isVideo) 0 else 1 }.thenByDescending { it.displayDate }
    }

    private fun Cursor.toMediaItem(itemId: Long, uri: Uri, type: MediaType, favoriteCol: String?): MediaItem = MediaItem(
        id = itemId, uri = uri, name = getStringOrNull(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(), type = type,
        dateTaken = getLongOrZero(MediaStore.MediaColumns.DATE_TAKEN), dateAdded = getLongOrZero(MediaStore.MediaColumns.DATE_ADDED),
        dateModified = getLongOrZero(MediaStore.MediaColumns.DATE_MODIFIED) * 1000, size = getLongOrZero(MediaStore.MediaColumns.SIZE),
        width = getIntOrZero(MediaStore.MediaColumns.WIDTH), height = getIntOrZero(MediaStore.MediaColumns.HEIGHT),
        duration = getLongOrZero(MediaStore.Video.Media.DURATION), bucketId = getStringOrNull(MediaStore.MediaColumns.BUCKET_ID),
        bucketName = getStringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME), relativePath = getStringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
        mimeType = getStringOrNull(MediaStore.MediaColumns.MIME_TYPE),
        isFavorite = favoriteCol != null && getIntOrZero(favoriteCol) == 1
    )
}

private fun Cursor.getStringOrNull(name: String) = getColumnIndex(name).takeIf { it >= 0 }?.let { getString(it) }
private fun Cursor.getLongOrZero(name: String) = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0L
private fun Cursor.getIntOrZero(name: String) = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: 0
