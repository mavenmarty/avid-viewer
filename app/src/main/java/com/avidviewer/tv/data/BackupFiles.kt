package com.avidviewer.tv.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Where backups live: the device's shared Download folder (in an "AvidViewer" folder), which stays
 * when the app is deleted. Android 10+ saves through MediaStore (no permission needed); older
 * versions write the file directly (needs the storage permission, asked for first).
 */
object BackupFiles {
    const val FOLDER = "AvidViewer"

    /** A backup found on the device. */
    class Found(val name: String, val modified: Long, val read: () -> String)

    fun needsWritePermission() = Build.VERSION.SDK_INT < 29

    /** Saves the backup; returns where it went, in words for the viewer. */
    fun save(context: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create the backup file.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Couldn't write the backup file.")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            File(dir, name).writeText(text)
        }
        return "Download › $FOLDER › $name"
    }

    /** Backups in the Download folder that this app can read, newest first. */
    fun findInDownloads(context: Context): List<Found> {
        val out = ArrayList<Found>()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val cols = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?", arrayOf("%." + Backup.EXTENSION), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                    out.add(Found(c.getString(1), c.getLong(2) * 1000) { readUri(context, uri) })
                }
            }
        }
        // Direct look (older Android, or where the folder is readable).
        runCatching {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            listOf(File(root, FOLDER), root).forEach { dir ->
                dir.listFiles()?.filter { it.isFile && it.name.endsWith("." + Backup.EXTENSION) && it.canRead() }?.forEach { f ->
                    if (out.none { it.name == f.name }) out.add(Found(f.name, f.lastModified()) { f.readText() })
                }
            }
        }
        return out.sortedByDescending { it.modified }
    }

    fun readUri(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Couldn't open that file.")
}
