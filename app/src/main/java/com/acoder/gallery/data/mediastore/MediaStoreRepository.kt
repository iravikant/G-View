package com.acoder.gallery.data.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.paging.PagingSource
import com.acoder.gallery.domain.model.Album
import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.model.MediaType
import com.acoder.gallery.domain.model.SortOrder
import com.acoder.gallery.domain.repository.MediaOpResult
import com.acoder.gallery.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreRepository @Inject constructor(@ApplicationContext private val context: Context) : MediaRepository {
    private val resolver: ContentResolver get() = context.contentResolver
    private val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun observeMedia(): Flow<List<MediaItem>> = callbackFlow<Unit> {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(imageUri, true, observer)
        resolver.registerContentObserver(videoUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .conflate()
        // MediaStore fires many notifications during bulk operations; coalesce them.
        .debounce(400)
        .onStart { emit(Unit) }
        .mapLatest { queryAll() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private fun queryAll(): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        return try {
            queryCollection(imageUri, MediaType.IMAGE, out)
            queryCollection(videoUri, MediaType.VIDEO, out)
            out.sortWith(compareByDescending<MediaItem> { it.dateModified }.thenByDescending { it.id }.thenBy { it.type })
            out
        } catch (_: SecurityException) {
            emptyList() // permission not granted (yet)
        }
    }

    private fun queryCollection(base: Uri, type: MediaType, out: MutableList<MediaItem>) {
        val favoriteCol = if (Build.VERSION.SDK_INT >= 29) "is_favorite" else null
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH
        )
        if (type == MediaType.VIDEO) columns += MediaStore.Video.Media.DURATION
        if (favoriteCol != null) columns += favoriteCol
        resolver.query(base, columns.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            val idIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) {
                val itemId = c.getLong(idIndex)
                out += c.toMediaItem(itemId, ContentUris.withAppendedId(base, itemId), type, favoriteCol)
            }
        }
    }

    override fun pagingSource(sort: SortOrder, filter: MediaFilter, query: String, albumId: String?): PagingSource<String, MediaItem> =
        MediaStorePagingSource(resolver, sort, filter, query, albumId)

    override suspend fun queryAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, Pair<Album, Int>>()
        fun collect(base: Uri) {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
            resolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getStringOrNull(MediaStore.MediaColumns.BUCKET_ID) ?: continue
                    val current = map[id]
                    if (current == null) {
                        val mediaId = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val album = Album(id, c.getStringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME) ?: "Other", 1, ContentUris.withAppendedId(base, mediaId), c.getStringOrNull(MediaStore.MediaColumns.RELATIVE_PATH))
                        map[id] = album to 1
                    } else {
                        map[id] = current.first to current.second + 1
                    }
                }
            }
        }
        collect(imageUri)
        collect(videoUri)
        val result = map.values.map { it.first.copy(count = it.second) }.toMutableList()
        try {
            val cutoff = System.currentTimeMillis() / 1000 - 7 * 86400
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            val recent = ArrayList<Uri>()
            fun recentCollect(base: Uri) {
                resolver.query(base, projection, "${MediaStore.MediaColumns.DATE_ADDED}>=?", arrayOf(cutoff.toString()), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        recent += ContentUris.withAppendedId(base, c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
                    }
                }
            }
            recentCollect(imageUri)
            recentCollect(videoUri)
            if (recent.isNotEmpty()) {
                result.add(0, Album("RECENT", "Recent", recent.size, recent.first(), null))
            }
        } catch (_: Exception) {}
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
