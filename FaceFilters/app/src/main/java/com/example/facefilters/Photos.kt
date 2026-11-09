package com.example.facefilters

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes a full-resolution photo, upright and cropped to what the preview shows, and mirrored if
 * [mirror] so it looks like the preview of a front camera. The work is done on [executor].
 */
suspend fun CameraController.takePhoto(executor: Executor, mirror: Boolean): Bitmap =
    suspendCancellableCoroutine { continuation ->
        takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val photo = runCatching { image.use { it.toUprightBitmap(mirror) } }
                photo.onSuccess { continuation.resume(it) }
                photo.onFailure { continuation.resumeWithException(it) }
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        })
    }

private fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap {
    val full = toBitmap()
    // The crop is what the preview shows. CameraX may have applied it already.
    val crop = Rect(cropRect)
    if (!crop.intersect(0, 0, full.width, full.height)) crop.set(0, 0, full.width, full.height)
    val transform = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height(), transform, true)
}
