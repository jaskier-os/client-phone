package com.repository.listener.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CameraCapturer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "CameraCapturer"
        private const val JPEG_QUALITY = 85
    }

    fun capture(callback: (String?) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            var cameraProvider: ProcessCameraProvider? = null
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                val executor = ContextCompat.getMainExecutor(context)

                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        try {
                            val base64 = imageProxyToBase64(imageProxy)
                            Log.i(TAG, "Photo captured")
                            callback(base64)
                        } catch (e: Exception) {
                            Log.e(TAG, "Photo processing error: ${e.message}")
                            callback(null)
                        } finally {
                            imageProxy.close()
                            cameraProvider?.unbindAll()
                        }
                    }

                    override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exception.message}")
                        cameraProvider?.unbindAll()
                        callback(null)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup error: ${e.message}")
                cameraProvider?.unbindAll()
                callback(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun imageProxyToBase64(imageProxy: ImageProxy): String {
        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Apply rotation if needed
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            bitmap = rotated
        }

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        bitmap.recycle()

        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
