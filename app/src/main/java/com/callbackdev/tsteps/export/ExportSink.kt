package com.callbackdev.tsteps.export

/**
 * Where a rendered file lands. The seam exists for the same reason the Health
 * Connect gateway does: everything above it runs on the JVM with a fake, and
 * only [DownloadsExportSink] touches Android.
 */
interface ExportSink {

    /**
     * Writes [file] and returns the name it actually got. The store may rename
     * on collision (`tsteps-export-2026-08-20 (1).json`) and the terminal line
     * reports what was written, not what was asked for — telling the user a
     * filename that isn't there is exactly the kind of lie the file rule bans.
     *
     * Throws on failure; the caller turns it into an `// ERROR:` line.
     */
    suspend fun write(file: ExportFile): String
}
