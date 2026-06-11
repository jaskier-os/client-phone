package com.repository.navigation.provider

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Small private HTTP helper shared only by the Google* provider seams
 * (GoogleGeocoder / GooglePlaceSearch / GooglePlaceSuggest). New file, not used
 * anywhere else. All result delivery is marshalled back onto the MAIN thread so
 * downstream callers (which assume main, matching the Yandex impls) stay correct.
 */
internal object GoogleHttp {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Run [block] on the MAIN thread. */
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Enqueue [request] off the main thread. [onBody] receives the response body
     * string for any 2xx response; [onFailure] is invoked on network error or
     * non-2xx status. Both are delivered ON THE MAIN thread. Returns the Call so
     * the caller can cancel it (used by GooglePlaceSuggest.reset()).
     */
    fun enqueue(
        request: Request,
        onBody: (String) -> Unit,
        onFailure: () -> Unit
    ): Call {
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onMain(onFailure)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body == null) {
                        onMain(onFailure)
                    } else {
                        onMain { onBody(body) }
                    }
                }
            }
        })
        return call
    }
}
