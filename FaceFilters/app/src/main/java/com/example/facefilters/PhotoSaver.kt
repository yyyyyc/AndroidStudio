package com.example.facefilters

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves photos to the gallery, in Pictures/FaceFilters, and videos in Movies/FaceFilters. */
object PhotoSaver {

    const val ALBUM = "FaceFilters"

    /** Whether saving needs the storage permission, which only Android 9 and older ask for. */
    val needsStoragePermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Saves [bitmap] as a JPEG. Slow: call off the main thread. Returns null if it couldn't. */
    fun save(context: Context, bitmap: Bitmap): Uri? {
        val name = "FaceFilters_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, bitmap, name)
        } else {
            saveToFile(context, bitmap, name)
        }
    }

    private fun saveToMediaStore(context: Context, bitmap: Bitmap, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true
        } catch (e: Exception) {
            false
        }
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Copies the video in [file] to the gallery, then deletes [file]. Slow: off the main thread. */
    fun saveVideo(context: Context, file: File): Uri? {
        val name = "FaceFilters_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".mp4"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val copied = resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
                if (!copied) {
                    resolver.delete(uri, null, null)
                    return null
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM)
                if (!dir.isDirectory && !dir.mkdirs()) return null
                val target = File(dir, name)
                file.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf("video/mp4"), null)
                Uri.fromFile(target)
            }
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    @Suppress("DEPRECATION") // The way to reach the gallery before Android 10.
    private fun saveToFile(context: Context, bitmap: Bitmap, name: String): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, name)
        val written = try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } catch (e: Exception) {
            false
        }
        if (!written) return null
        // So it shows in the gallery.
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg"), null)
        return Uri.fromFile(file)
    }
}
