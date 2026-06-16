package com.repository.listener.network

import android.os.Handler
import android.os.Looper
import com.repository.listener.util.LogCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatSummary(
    val id: String,
    val deviceId: String,
    val deviceType: String,
    val startedAt: String,
    val lastActivityAt: String,
    val turnCount: Int,
    val closed: Boolean,
    val firstUserMessage: String?
)

data class ChatListResponse(
    val conversations: List<ChatSummary>,
    val total: Int,
    val offset: Int,
    val limit: Int
)

data class ToolCallRecord(
    val id: String,
    val name: String,
    val arguments: org.json.JSONObject?,
    val result: String?,
    val status: String
)

data class ChatTurn(
    val ts: String,
    val requestId: String,
    val userText: String?,
    val userImage: String?,
    val responseText: String?,
    val responseStatus: String?,
    val toolCalls: List<ToolCallRecord>
)

data class ChatDetailResponse(
    val id: String,
    val deviceId: String,
    val deviceType: String,
    val startedAt: String,
    val lastActivityAt: String,
    val turnCount: Int,
    val turns: List<ChatTurn>
)

data class SendMessageResponse(
    val requestId: String,
    val text: String,
    val status: String
)

data class CreateChatResponse(
    val conversationId: String,
    val requestId: String,
    val text: String,
    val status: String
)

class ChatHistoryClient(
    wsUrl: String,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "ChatHistoryClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val baseUrl: String
    private val handler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        // Derive HTTP base URL from WS URL: ws://host:port/ws/device -> http://host:port
        baseUrl = wsUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .replace("/ws/device", "")
    }

    private fun parseChatSummary(obj: JSONObject): ChatSummary {
        return ChatSummary(
            id = obj.getString("id"),
            deviceId = obj.optString("deviceId", ""),
            deviceType = obj.optString("deviceType", ""),
            startedAt = obj.optString("startedAt", ""),
            lastActivityAt = obj.optString("lastActivityAt", ""),
            turnCount = obj.optInt("turnCount", 0),
            closed = obj.optBoolean("closed", false),
            firstUserMessage = if (obj.isNull("firstUserMessage")) null else obj.getString("firstUserMessage")
        )
    }

    fun listChats(limit: Int = 50, offset: Int = 0, callback: (Result<ChatListResponse>) -> Unit) {
        val url = "$baseUrl/api/v1/chats?limit=$limit&offset=$offset"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "listChats failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = IOException("HTTP ${resp.code}")
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val arr = json.getJSONArray("conversations")
                        val conversations = mutableListOf<ChatSummary>()
                        for (i in 0 until arr.length()) {
                            conversations.add(parseChatSummary(arr.getJSONObject(i)))
                        }
                        val result = ChatListResponse(
                            conversations = conversations,
                            total = json.optInt("total", 0),
                            offset = json.optInt("offset", 0),
                            limit = json.optInt("limit", 50)
                        )
                        handler.post { callback(Result.success(result)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse listChats failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun searchChats(query: String, limit: Int = 50, offset: Int = 0, callback: (Result<ChatListResponse>) -> Unit) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/api/v1/chats?search=$encodedQuery&limit=$limit&offset=$offset"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "searchChats failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = IOException("HTTP ${resp.code}")
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val arr = json.getJSONArray("conversations")
                        val conversations = mutableListOf<ChatSummary>()
                        for (i in 0 until arr.length()) {
                            conversations.add(parseChatSummary(arr.getJSONObject(i)))
                        }
                        val result = ChatListResponse(
                            conversations = conversations,
                            total = json.optInt("total", 0),
                            offset = json.optInt("offset", 0),
                            limit = json.optInt("limit", 50)
                        )
                        handler.post { callback(Result.success(result)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse searchChats failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun deleteChat(conversationId: String, callback: (Result<Unit>) -> Unit) {
        val url = "$baseUrl/api/v1/chats/$conversationId"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .delete()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "deleteChat failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = IOException("HTTP ${resp.code}")
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    handler.post { callback(Result.success(Unit)) }
                }
            }
        })
    }

    fun getChatDetail(conversationId: String, callback: (Result<ChatDetailResponse>) -> Unit) {
        val url = "$baseUrl/api/v1/chats/$conversationId"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "getChatDetail failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = IOException("HTTP ${resp.code}")
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val turnsArr = json.optJSONArray("turns") ?: JSONArray()
                        val turns = mutableListOf<ChatTurn>()
                        for (i in 0 until turnsArr.length()) {
                            val t = turnsArr.getJSONObject(i)
                            val responseObj = t.optJSONObject("response")
                            val toolCallsArr = t.optJSONArray("toolCalls")
                            val toolCalls = mutableListOf<ToolCallRecord>()
                            if (toolCallsArr != null) {
                                for (j in 0 until toolCallsArr.length()) {
                                    val tc = toolCallsArr.getJSONObject(j)
                                    toolCalls.add(ToolCallRecord(
                                        id = tc.optString("id", ""),
                                        name = tc.optString("name", ""),
                                        arguments = tc.optJSONObject("arguments"),
                                        result = tc.optString("result", null),
                                        status = tc.optString("status", "complete")
                                    ))
                                }
                            }
                            turns.add(ChatTurn(
                                ts = t.optString("ts", ""),
                                requestId = t.optString("requestId", ""),
                                userText = t.optString("userText", null),
                                userImage = t.optString("userImage", null),
                                responseText = responseObj?.optString("text", null),
                                responseStatus = responseObj?.optString("status", null),
                                toolCalls = toolCalls
                            ))
                        }
                        val result = ChatDetailResponse(
                            id = json.getString("id"),
                            deviceId = json.optString("deviceId", ""),
                            deviceType = json.optString("deviceType", ""),
                            startedAt = json.optString("startedAt", ""),
                            lastActivityAt = json.optString("lastActivityAt", ""),
                            turnCount = json.optInt("turnCount", 0),
                            turns = turns
                        )
                        handler.post { callback(Result.success(result)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse getChatDetail failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    /**
     * Fetch the Copilot session list. GET /api/v1/copilot-chats.
     * Mirrors the listChats base URL + x-api-key auth. Suspends on the
     * OkHttp call via a cancellable continuation; runs off the main thread.
     * Returns emptyList() on any error or non-success response.
     */
    suspend fun getCopilotChats(): List<CopilotSummary> {
        val url = "$baseUrl/api/v1/copilot-chats?limit=50&offset=0"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        return try {
            val bodyStr = executeForString(request) ?: return emptyList()
            // The endpoint returns {conversations:[...],total,offset,limit};
            // tolerate a bare array too.
            val arr = if (bodyStr.trimStart().startsWith("[")) {
                JSONArray(bodyStr)
            } else {
                JSONObject(bodyStr).optJSONArray("conversations") ?: JSONArray()
            }
            val out = mutableListOf<CopilotSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(parseCopilotSummary(obj))
            }
            out
        } catch (e: Exception) {
            LogCollector.e(TAG, "getCopilotChats failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch one Copilot session log. GET /api/v1/copilot-chats/:id.
     * Tolerates either a top-level turns[] or a header+turns wrapper.
     * Returns null on any error or non-success response.
     */
    suspend fun getCopilotChatDetail(id: String): CopilotChatDetail? {
        val url = "$baseUrl/api/v1/copilot-chats/${java.net.URLEncoder.encode(id, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        return try {
            val bodyStr = executeForString(request) ?: return null
            val json = JSONObject(bodyStr)
            parseCopilotDetail(id, json)
        } catch (e: Exception) {
            LogCollector.e(TAG, "getCopilotChatDetail failed: ${e.message}")
            null
        }
    }

    /**
     * Run an OkHttp request and return the body string, or null if the call
     * failed or returned a non-success status. Suspends without blocking.
     */
    private suspend fun executeForString(request: Request): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { try { call.cancel() } catch (_: Exception) {} }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    LogCollector.e(TAG, "copilot request failed: ${e.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { resp ->
                        val result = if (resp.isSuccessful) resp.body?.string() else {
                            LogCollector.e(TAG, "copilot request HTTP ${resp.code}")
                            null
                        }
                        if (cont.isActive) cont.resumeWith(Result.success(result))
                    }
                }
            })
        }

    private fun parseCopilotSummary(obj: JSONObject): CopilotSummary {
        return CopilotSummary(
            id = obj.optString("id", ""),
            title = obj.optString("title", "").ifEmpty { "(untitled)" },
            startedAt = obj.optString("startedAt", ""),
            lastActivityAt = obj.optString("lastActivityAt", obj.optString("startedAt", "")),
            turnCount = obj.optInt("turnCount", 0)
        )
    }

    private fun parseCopilotCard(obj: JSONObject): CopilotCard {
        return CopilotCard(
            id = obj.optString("id", ""),
            kind = obj.optString("kind", ""),
            heard = obj.optString("heard", ""),
            note = obj.optString("note", ""),
            why = obj.optString("why", "")
        )
    }

    private fun parseCopilotTurn(obj: JSONObject): CopilotTurn {
        val cardsArr = obj.optJSONArray("cards") ?: JSONArray()
        val cards = mutableListOf<CopilotCard>()
        for (i in 0 until cardsArr.length()) {
            val c = cardsArr.optJSONObject(i) ?: continue
            cards.add(parseCopilotCard(c))
        }
        return CopilotTurn(
            ts = obj.optString("ts", ""),
            wearerText = obj.optString("wearerText", ""),
            interlocutorText = obj.optString("interlocutorText", ""),
            cards = cards
        )
    }

    private fun parseCopilotDetail(id: String, json: JSONObject): CopilotChatDetail {
        // Tolerate a header+turns wrapper or a flat object. Title and startedAt
        // may live at the top level or inside a "header" object.
        val header = json.optJSONObject("header")
        val title = json.optString("title", "").ifEmpty {
            header?.optString("title", "") ?: ""
        }.ifEmpty { "Copilot session" }
        val startedAt = json.optString("startedAt", "").ifEmpty {
            header?.optString("startedAt", "") ?: ""
        }
        val turnsArr = json.optJSONArray("turns") ?: JSONArray()
        val turns = mutableListOf<CopilotTurn>()
        for (i in 0 until turnsArr.length()) {
            val t = turnsArr.optJSONObject(i) ?: continue
            turns.add(parseCopilotTurn(t))
        }
        val resolvedId = json.optString("id", "").ifEmpty {
            header?.optString("id", "") ?: ""
        }.ifEmpty { id }
        return CopilotChatDetail(
            id = resolvedId,
            title = title,
            startedAt = startedAt,
            turns = turns
        )
    }

    fun sendMessage(
        conversationId: String,
        text: String,
        imageBase64: String?,
        deviceId: String,
        deviceType: String,
        callback: (Result<SendMessageResponse>) -> Unit
    ) {
        val url = "$baseUrl/api/v1/chats/$conversationId/message"
        // Stable idempotency key for this send. Built once, so an OkHttp
        // connection-level resend reuses the same body and the server collapses
        // the retry into a single turn instead of duplicating the user bubble.
        val body = JSONObject().apply {
            put("text", text)
            if (imageBase64 != null) put("image", imageBase64)
            put("deviceId", deviceId)
            put("deviceType", deviceType)
            put("requestId", java.util.UUID.randomUUID().toString())
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "sendMessage failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = IOException("HTTP ${resp.code}")
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val result = SendMessageResponse(
                            requestId = json.optString("requestId", ""),
                            text = json.optString("text", ""),
                            status = json.optString("status", "")
                        )
                        handler.post { callback(Result.success(result)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse sendMessage failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun createChat(
        text: String,
        imageBase64: String?,
        deviceId: String,
        deviceType: String,
        callback: (Result<CreateChatResponse>) -> Unit
    ) {
        val url = "$baseUrl/api/v1/chats"
        // Stable idempotency key (see sendMessage) so a transport-level resend of
        // this create does not produce a duplicate first turn.
        val body = JSONObject().apply {
            put("text", text)
            if (imageBase64 != null) put("image", imageBase64)
            put("deviceId", deviceId)
            put("deviceType", deviceType)
            put("requestId", java.util.UUID.randomUUID().toString())
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "createChat failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = IOException("HTTP ${resp.code}")
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val result = CreateChatResponse(
                            conversationId = json.optString("conversationId", ""),
                            requestId = json.optString("requestId", ""),
                            text = json.optString("text", ""),
                            status = json.optString("status", "")
                        )
                        handler.post { callback(Result.success(result)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse createChat failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }
}
