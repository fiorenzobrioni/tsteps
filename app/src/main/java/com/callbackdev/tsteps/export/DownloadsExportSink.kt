package com.callbackdev.tsteps.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The public Downloads folder through MediaStore — the only file the export
 * feature needs from Android. No storage permission is involved: an app owns
 * what it inserts into `MediaStore.Downloads`, and the file survives the app's
 * uninstall because it belongs to the user, not to tsteps.
 *
 * Written as pending and published only once the bytes are down, so a killed
 * process leaves no half file in the user's Downloads.
 */
class DownloadsExportSink(context: Context) : ExportSink {

    private val appContext = context.applicationContext

    override suspend fun write(file: ExportFile): String = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, file.mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
            ?: throw IOException("Downloads is not writable")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(file.content.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("cannot open ${file.name}")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
        } catch (error: Throwable) {
            // A failed export leaves nothing behind, not even an empty stub.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        displayName(uri) ?: file.name
    }

    /** What the store actually called it — collisions get a `(1)` suffix. */
    private fun displayName(uri: Uri): String? =
        appContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
