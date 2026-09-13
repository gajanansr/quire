package app.quire.android.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Reads the file a user picked.
 *
 * Abstracted from `ContentResolver` so the importer can be tested on the JVM with a
 * plain file, and so the SAF details stay in one place.
 */
interface UriOpener {
    fun open(uri: Uri): InputStream
    /** Bytes, or -1 when the provider does not report a size. */
    fun sizeOf(uri: Uri): Long
    fun displayName(uri: Uri): String
}

class ContentResolverUriOpener(private val context: Context) : UriOpener {

    override fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: error("could not open $uri")

    override fun sizeOf(uri: Uri): Long = query(uri, OpenableColumns.SIZE)?.toLongOrNull() ?: -1L

    override fun displayName(uri: Uri): String =
        query(uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Untitled"

    private fun query(uri: Uri, column: String): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
}
