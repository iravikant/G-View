package com.acoder.gallery.core.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * Makes the gallery show up in the system file picker ("Open from" drawer) so other apps
 * can pick photos and videos from it.
 *
 * Structure shown in the picker:
 *   <App name>
 *     All media
 *     <Album 1>
 *     <Album 2> ...
 *
 * Read-only. Needs the media permission (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO, or
 * READ_EXTERNAL_STORAGE on older Android) to have been granted to the app; otherwise it is empty.
 */
class GalleryDocumentsProvider : DocumentsProvider() {

    private companion object {
        const val TAG = "GalleryDocs"

        const val ROOT_ID = "gallery"
        const val ROOT_DOC = "root"
        const val ALBUM = "album:"
        const val ALL = "all"
        const val IMAGE = "img:"
        const val VIDEO = "vid:"

        val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES
        )
        val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )

        const val MEDIA_FILTER =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
                    "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
    }

    override fun onCreate(): Boolean = true

    // ---------------------------------------------------------------------
    // Roots
    // ---------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(columns(projection, ROOT_PROJECTION))
        val ctx = context ?: return result
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            add(Root.COLUMN_TITLE, appName(ctx))
            add(Root.COLUMN_SUMMARY, "Photos & videos")
            add(Root.COLUMN_ICON, ctx.applicationInfo.icon)
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_MIME_TYPES, "image/*\nvideo/*")
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Documents
    // ---------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(columns(projection, DOC_PROJECTION))
        val ctx = context ?: return result

        when {
            documentId == ROOT_DOC -> addDir(result, ROOT_DOC, appName(ctx))

            documentId.startsWith(ALBUM) -> {
                val bucket = documentId.removePrefix(ALBUM)
                addDir(result, documentId, if (bucket == ALL) "All media" else albumName(ctx.contentResolver, bucket))
            }

            else -> {
                val uri = mediaUri(documentId)
                try {
                    ctx.contentResolver.query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.MIME_TYPE,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.DATE_MODIFIED
                        ),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            addMedia(
                                result, documentId,
                                c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)
                            )
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No media permission", e)
                }
            }
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(columns(projection, DOC_PROJECTION))
        val resolver = context?.contentResolver ?: return result

        try {
            when {
                parentDocumentId == ROOT_DOC -> {
                    addDir(result, ALBUM + ALL, "All media")
                    albums(resolver).forEach { (id, name) -> addDir(result, ALBUM + id, name) }
                }

                parentDocumentId.startsWith(ALBUM) -> {
                    val bucket = parentDocumentId.removePrefix(ALBUM)
                    val selection = if (bucket == ALL) MEDIA_FILTER
                    else "$MEDIA_FILTER AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
                    val args = if (bucket == ALL) null else arrayOf(bucket)

                    resolver.query(
                        MediaStore.Files.getContentUri("external"),
                        arrayOf(
                            MediaStore.Files.FileColumns._ID,
                            MediaStore.Files.FileColumns.DISPLAY_NAME,
                            MediaStore.Files.FileColumns.MIME_TYPE,
                            MediaStore.Files.FileColumns.SIZE,
                            MediaStore.Files.FileColumns.DATE_MODIFIED,
                            MediaStore.Files.FileColumns.MEDIA_TYPE
                        ),
                        selection, args,
                        "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val prefix =
                                if (c.getInt(5) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) VIDEO else IMAGE
                            addMedia(
                                result, prefix + c.getLong(0),
                                c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)
                            )
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            // Media permission not granted yet: show an empty list instead of crashing.
            Log.w(TAG, "No media permission", e)
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = when {
        parentDocumentId == ROOT_DOC -> true
        parentDocumentId.startsWith(ALBUM) -> documentId.startsWith(IMAGE) || documentId.startsWith(VIDEO)
        else -> false
    }

    // ---------------------------------------------------------------------
    // File access
    // ---------------------------------------------------------------------

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (!mode.startsWith("r") || mode.contains('w')) throw SecurityException("Read-only provider")
        val resolver = context?.contentResolver ?: throw FileNotFoundException(documentId)
        return resolver.openFileDescriptor(mediaUri(documentId), "r", signal)
            ?: throw FileNotFoundException(documentId)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val ctx = context ?: throw FileNotFoundException(documentId)
        val uri = mediaUri(documentId)

        val bitmap: Bitmap = try {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(uri, Size(sizeHint.x, sizeHint.y), signal)
            } else if (documentId.startsWith(IMAGE)) {
                decodeSampled(ctx, uri, max(sizeHint.x, sizeHint.y))
            } else {
                null
            }
        } catch (e: IOException) {
            null
        } ?: throw FileNotFoundException(documentId)

        val dir = File(ctx.cacheDir, "doc_thumbs").apply { mkdirs() }
        val file = File(dir, documentId.replace(':', '_') + ".jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()

        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun columns(requested: Array<out String>?, default: Array<String>): Array<String> =
        if (requested == null) default else Array(requested.size) { requested[it] }

    private fun appName(ctx: Context): String = ctx.applicationInfo.loadLabel(ctx.packageManager).toString()

    private fun addDir(cursor: MatrixCursor, id: String, name: String) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, null)
            add(Document.COLUMN_LAST_MODIFIED, null)
        }
    }

    private fun addMedia(
        cursor: MatrixCursor,
        id: String,
        name: String?,
        mime: String?,
        size: Long,
        modifiedSeconds: Long
    ) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, name ?: id)
            add(Document.COLUMN_MIME_TYPE, mime ?: if (id.startsWith(VIDEO)) "video/*" else "image/*")
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
            add(Document.COLUMN_SIZE, size)
            add(Document.COLUMN_LAST_MODIFIED, modifiedSeconds * 1000L)
        }
    }

    private fun mediaUri(documentId: String): Uri {
        val base: Uri
        val raw: String
        when {
            documentId.startsWith(IMAGE) -> {
                base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                raw = documentId.removePrefix(IMAGE)
            }
            documentId.startsWith(VIDEO) -> {
                base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                raw = documentId.removePrefix(VIDEO)
            }
            else -> throw FileNotFoundException(documentId)
        }
        val id = raw.toLongOrNull() ?: throw FileNotFoundException(documentId)
        return ContentUris.withAppendedId(base, id)
    }

    /** bucketId -> album name, sorted by name. */
    private fun albums(resolver: ContentResolver): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
            ),
            MEDIA_FILTER, null,
            "${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                if (id !in out) out[id] = c.getString(1) ?: "Unknown"
            }
        }
        return out
    }

    private fun albumName(resolver: ContentResolver, bucketId: String): String {
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME),
            "$MEDIA_FILTER AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?",
            arrayOf(bucketId),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "Album"
        }
        return "Album"
    }

    /** Thumbnail for Android 9 and below. */
    private fun decodeSampled(ctx: Context, uri: Uri, target: Int): Bitmap? {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}