package edu.sustech.mobile.core.memory

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * `content://` over [MemoryStore].
 *
 * Not exported: the only way in is FLAG_GRANT_READ_URI_PERMISSION on an intent
 * this app sent, so another app can read a file this one never wrote to disk.
 */
class MemoryDocumentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = entry(uri).mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" && mode != "rt") throw FileNotFoundException("read-only provider: $mode")
        val file = entry(uri)
        // Pin for the lifetime of the fd: an LRU eviction mid-read would surface
        // in the other app as a truncated PDF, not as our bug.
        MemoryStore.pin(file.token)
        return try {
            MemoryFd.open(requireNotNull(context), file.bytes) { MemoryStore.unpin(file.token) }
        } catch (e: Exception) {
            MemoryStore.unpin(file.token)
            throw FileNotFoundException("cannot open ${file.name}: ${e.message}")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = entry(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { name ->
                    when (name) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.size
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only provider")

    /** `content://edu.sustech.mobile.memory/<token>/<name>` */
    private fun entry(uri: Uri): MemoryFile =
        uri.pathSegments.getOrNull(0)?.let { MemoryStore.byToken(it) }
            ?: throw FileNotFoundException("no such memory file: $uri")
}
