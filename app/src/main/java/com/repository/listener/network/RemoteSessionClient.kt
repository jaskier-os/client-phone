package com.repository.listener.network

import android.os.Handler
import android.os.Looper
import com.repository.listener.util.LogCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SlashCommand(val name: String, val description: String)
data class RemoteDirsResponse(val available: Boolean, val dirs: List<String>)
data class RemoteSessionResponse(val sessionId: String, val workDir: String)
data class ConversationInfo(
    val sessionId: String,
    val summary: String,
    val lastModified: Long,
    val customTitle: String? = null,
    val firstPrompt: String? = null,
    val gitBranch: String? = null,
    val cwd: String? = null,
    val createdAt: Long? = null
) {
    /** Best human label for a row: user title, else first prompt, else summary. */
    val label: String get() = customTitle ?: firstPrompt ?: summary
}
data class LiveSession(
    val pid: Int,
    val workDir: String,
    val sessionId: String,
    val startedAt: String,
    val alive: Boolean,
    val title: String? = null,
    val permissionMode: String? = null
)

class RemoteSessionClient(
    wsUrl: String,
    private val apiKey: String,
    private val deviceId: String = ""
) {
    companion object {
        private const val TAG = "RemoteSessionClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val baseUrl: String
    private val handler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        baseUrl = wsUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .replace("/ws/device", "")
    }

    fun getDirs(callback: (Result<RemoteDirsResponse>) -> Unit) {
        val url = "$baseUrl/api/v1/remote-sessions/dirs"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "getDirs failed: ${e.message}")
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
                        val available = json.optBoolean("available", false)
                        val arr = json.optJSONArray("dirs")
                        val dirs = mutableListOf<String>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                dirs.add(arr.getString(i))
                            }
                        }
                        val result = RemoteDirsResponse(available = available, dirs = dirs)
                        handler.post { callback(Result.success(result)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse getDirs failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun startSession(workDir: String, permissionMode: String?, sessionId: String? = null, callback: (Result<RemoteSessionResponse>) -> Unit) {
        val url = "$baseUrl/api/v1/remote-sessions/start"
        val body = JSONObject().apply {
            put("workDir", workDir)
            put("deviceId", deviceId)
            if (permissionMode != null) {
                put("permissionMode", permissionMode)
            }
            // When resuming a prior conversation, pass its id so the CLI spawns
            // with --resume instead of minting a fresh session.
            if (sessionId != null) {
                put("sessionId", sessionId)
            }
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "startSession failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        val msg = try {
                            JSONObject(errBody).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        } catch (_: Exception) {
                            "HTTP ${resp.code}"
                        }
                        val err = IOException(msg)
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val result = RemoteSessionResponse(
                            sessionId = json.getString("sessionId"),
                            workDir = json.getString("workDir")
                        )
                        handler.post { callback(Result.success(result)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse startSession failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun listSessions(callback: (Result<List<LiveSession>>) -> Unit) {
        val url = "$baseUrl/api/v1/remote-sessions"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "listSessions failed: ${e.message}")
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
                        val arr = json.optJSONArray("sessions")
                        val sessions = mutableListOf<LiveSession>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                sessions.add(LiveSession(
                                    pid = obj.getInt("pid"),
                                    workDir = obj.getString("workDir"),
                                    sessionId = obj.optString("sessionId", ""),
                                    startedAt = obj.getString("startedAt"),
                                    alive = obj.optBoolean("alive", true),
                                    title = obj.optString("title", null).takeIf { !it.isNullOrEmpty() },
                                    permissionMode = obj.optString("permissionMode", null).takeIf { !it.isNullOrEmpty() }
                                ))
                            }
                        }
                        handler.post { callback(Result.success(sessions)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse listSessions failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun stopSession(pid: Int, callback: (Result<Unit>) -> Unit) {
        val url = "$baseUrl/api/v1/remote-sessions/stop"
        val body = JSONObject().apply {
            put("pid", pid)
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "stopSession failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        val msg = try {
                            JSONObject(errBody).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        } catch (_: Exception) {
                            "HTTP ${resp.code}"
                        }
                        val err = IOException(msg)
                        handler.post { callback(Result.failure(err)) }
                        return
                    }
                    handler.post { callback(Result.success(Unit)) }
                }
            }
        })
    }

    fun endRcSession(sessionId: String, callback: (Result<Unit>) -> Unit) {
        val encodedId = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val url = "$baseUrl/api/v1/remote-control/sessions/$encodedId"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .delete()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "endRcSession failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        val msg = try {
                            JSONObject(errBody).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        } catch (_: Exception) {
                            "HTTP ${resp.code}"
                        }
                        handler.post { callback(Result.failure(IOException(msg))) }
                        return
                    }
                    handler.post { callback(Result.success(Unit)) }
                }
            }
        })
    }

    data class RcSessionInfo(
        val sessionId: String,
        val workDir: String,
        val status: String,
        val title: String?,
        val createdAt: String
    )

    fun listRcSessions(workDir: String? = null, limit: Int = 50, offset: Int = 0, callback: (Result<List<RcSessionInfo>>) -> Unit) {
        val params = mutableListOf<String>()
        if (workDir != null) params.add("workDir=${java.net.URLEncoder.encode(workDir, "UTF-8")}")
        params.add("limit=$limit")
        params.add("offset=$offset")
        val url = "$baseUrl/api/v1/remote-control/sessions?${params.joinToString("&")}"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "listRcSessions failed: ${e.message}")
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
                        val arr = json.optJSONArray("sessions")
                        val sessions = mutableListOf<RcSessionInfo>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                // Default must be "ended": a missing status must NOT
                                // manufacture an active folder chip downstream.
                                sessions.add(RcSessionInfo(
                                    sessionId = obj.getString("sessionId"),
                                    workDir = obj.optString("workDir", ""),
                                    status = obj.optString("status", "ended"),
                                    title = obj.optString("title", null).takeIf { !it.isNullOrEmpty() },
                                    createdAt = obj.optString("createdAt", "")
                                ))
                            }
                        }
                        handler.post { callback(Result.success(sessions)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse listRcSessions failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    // List resumable Claude Code conversations for a directory (sourced from the
    // remote-session CLI's on-disk transcripts via pc-agent). Used by the resume
    // picker at launch and the in-session /resume command.
    fun listConversations(workDir: String, limit: Int = 50, offset: Int = 0, callback: (Result<List<ConversationInfo>>) -> Unit) {
        val params = mutableListOf<String>()
        params.add("workDir=${java.net.URLEncoder.encode(workDir, "UTF-8")}")
        params.add("limit=$limit")
        params.add("offset=$offset")
        val url = "$baseUrl/api/v1/remote-sessions/conversations?${params.joinToString("&")}"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "listConversations failed: ${e.message}")
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        val msg = try {
                            JSONObject(errBody).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        } catch (_: Exception) {
                            "HTTP ${resp.code}"
                        }
                        handler.post { callback(Result.failure(IOException(msg))) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val arr = json.optJSONArray("sessions")
                        val sessions = mutableListOf<ConversationInfo>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                sessions.add(ConversationInfo(
                                    sessionId = obj.getString("sessionId"),
                                    summary = obj.optString("summary", ""),
                                    lastModified = obj.optLong("lastModified", 0L),
                                    customTitle = obj.optString("customTitle", null).takeIf { !it.isNullOrEmpty() },
                                    firstPrompt = obj.optString("firstPrompt", null).takeIf { !it.isNullOrEmpty() },
                                    gitBranch = obj.optString("gitBranch", null).takeIf { !it.isNullOrEmpty() },
                                    cwd = obj.optString("cwd", null).takeIf { !it.isNullOrEmpty() },
                                    createdAt = if (obj.has("createdAt")) obj.optLong("createdAt") else null
                                ))
                            }
                        }
                        handler.post { callback(Result.success(sessions)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse listConversations failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun searchRcSessions(query: String, limit: Int = 50, callback: (Result<List<RcSessionInfo>>) -> Unit) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/api/v1/remote-control/sessions?search=$encodedQuery&limit=$limit"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "searchRcSessions failed: ${e.message}")
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
                        val arr = json.optJSONArray("sessions")
                        val sessions = mutableListOf<RcSessionInfo>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                // Default "ended": see listRcSessions rationale.
                                sessions.add(RcSessionInfo(
                                    sessionId = obj.getString("sessionId"),
                                    workDir = obj.optString("workDir", ""),
                                    status = obj.optString("status", "ended"),
                                    title = obj.optString("title", null).takeIf { !it.isNullOrEmpty() },
                                    createdAt = obj.optString("createdAt", "")
                                ))
                            }
                        }
                        handler.post { callback(Result.success(sessions)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse searchRcSessions failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun listRcDirs(callback: (Result<List<String>>) -> Unit) {
        val url = "$baseUrl/api/v1/remote-control/dirs"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                handler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        handler.post { callback(Result.failure(IOException("HTTP ${resp.code}"))) }
                        return
                    }
                    try {
                        val json = JSONObject(resp.body!!.string())
                        val arr = json.optJSONArray("dirs")
                        val dirs = mutableListOf<String>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                dirs.add(arr.getString(i))
                            }
                        }
                        handler.post { callback(Result.success(dirs)) }
                    } catch (e: Exception) {
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun getTranscript(sessionId: String, callback: (Result<String>) -> Unit) {
        val encodedId = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val url = "$baseUrl/api/v1/remote-control/sessions/$encodedId/transcript"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "getTranscript failed: ${e.message}")
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
                        val transcript = json.optJSONArray("transcript")?.toString() ?: "[]"
                        handler.post { callback(Result.success(transcript)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse getTranscript failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun fetchCommands(callback: (Result<List<SlashCommand>>) -> Unit) {
        val url = "$baseUrl/api/v1/remote-sessions/commands"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                LogCollector.e(TAG, "fetchCommands failed: ${e.message}")
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
                        val arr = json.optJSONArray("commands")
                        val commands = mutableListOf<SlashCommand>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                commands.add(SlashCommand(
                                    name = obj.getString("name"),
                                    description = obj.optString("description", "")
                                ))
                            }
                        }
                        handler.post { callback(Result.success(commands)) }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Parse fetchCommands failed: ${e.message}")
                        handler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }
}
