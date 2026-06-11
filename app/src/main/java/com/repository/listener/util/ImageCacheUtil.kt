package com.repository.listener.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ImageCacheUtil {
    private val executor = Executors.newFixedThreadPool(4)
    val imageCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadImage(imageView: ImageView, url: String, cacheKey: String, apiKey: String) {
        imageView.setImageBitmap(null)
        imageView.tag = cacheKey

        val cached = imageCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        executor.execute {
            try {
                if (url.startsWith("data:image")) {
                    val base64Data = url.substringAfter(",")
                    val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        imageCache.put(cacheKey, bitmap)
                        imageView.post {
                            if (imageView.tag == cacheKey) {
                                imageView.setImageBitmap(bitmap)
                            }
                        }
                    }
                    return@execute
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("x-api-key", apiKey)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                imageCache.put(cacheKey, bitmap)
                                imageView.post {
                                    if (imageView.tag == cacheKey) {
                                        imageView.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Image load failed silently
            }
        }
    }

    fun loadUrl(imageView: ImageView, url: String) {
        val cacheKey = "url_$url"
        imageView.setImageBitmap(null)
        imageView.tag = cacheKey

        val cached = imageCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        executor.execute {
            try {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                imageCache.put(cacheKey, bitmap)
                                imageView.post {
                                    if (imageView.tag == cacheKey) {
                                        imageView.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadOsintPhoto(imageView: ImageView, baseUrl: String, apiKey: String, filename: String) {
        val url = "$baseUrl/api/v1/reid/osint-photos/$filename"
        val cacheKey = "osint_$filename"
        loadImage(imageView, url, cacheKey, apiKey)
    }

    fun loadPersonImage(imageView: ImageView, personId: String, baseUrl: String, apiKey: String) {
        imageView.setImageBitmap(null)
        imageView.tag = personId

        val cached = imageCache.get(personId)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        executor.execute {
            try {
                val url = "$baseUrl/api/v1/reid/persons/$personId/image"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("x-api-key", apiKey)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                imageCache.put(personId, bitmap)
                                imageView.post {
                                    if (imageView.tag == personId) {
                                        imageView.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Image load failed silently
            }
        }
    }
}
