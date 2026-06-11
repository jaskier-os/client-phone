package com.repository.listener.network

import org.json.JSONObject

object Protocol {
    const val TYPE_IDENTIFY = "identify"
    const val TYPE_REQUEST = "request"
    const val TYPE_REQUEST_ACK = "request_ack"
    const val TYPE_RESPONSE = "response"
    const val TYPE_DEVICE_COMMAND = "device_command"
    const val TYPE_DEVICE_RESPONSE = "device_response"
    const val TYPE_ERROR = "error"
    const val TYPE_HEALTH = "health"
    const val TYPE_TTS_AUDIO = "tts_audio"
    const val TYPE_TTS_INTERRUPT = "tts_interrupt"
    const val TYPE_ABORT = "abort"
    const val TYPE_STREAMING_TEXT = "streaming_text"
    const val TYPE_TOOL_STATUS = "tool_status"
    const val TYPE_STREAM_REQUEST = "stream_request"
    const val TYPE_STREAM_ACK = "stream_ack"
    const val TYPE_STREAM_STOP = "stream_stop"
    const val TYPE_STREAM_SWITCH_MONITOR = "stream_switch_monitor"
    const val TYPE_STREAM_ENDED = "stream_ended"
    const val TYPE_AUDIO_RELAY_START = "audio_relay_start"
    const val TYPE_AUDIO_RELAY_STOP = "audio_relay_stop"
    const val TYPE_AUDIO_RELAY_ACK = "audio_relay_ack"
    const val TYPE_STREAM_CONNECT = "stream_connect"
    const val TYPE_AUDIO_RELAY_CONFIG = "audio_relay_config"
    const val TYPE_AUDIO_RELAY_ERROR = "audio_relay_error"
    const val TYPE_STREAM_ERROR = "stream_error"
    const val TYPE_NOTIFICATION_TTS = "notification_tts"
    const val TYPE_NOTIFICATION_TTS_AUDIO = "notification_tts_audio"
    const val TYPE_WEBRTC_OFFER = "webrtc_offer"
    const val TYPE_WEBRTC_ANSWER = "webrtc_answer"
    const val TYPE_WEBRTC_ICE = "webrtc_ice"

    // Remote Control
    const val TYPE_RC_SESSION_START = "rc_session_start"
    const val TYPE_RC_SESSION_END = "rc_session_end"
    const val TYPE_RC_MESSAGE = "rc_message"
    const val TYPE_RC_PERMISSION_REQUEST = "rc_permission_request"
    const val TYPE_RC_PERMISSION_RESPONSE = "rc_permission_response"
    const val TYPE_RC_TOOL_STATUS = "rc_tool_status"
    const val TYPE_RC_PLAN_UPDATE = "rc_plan_update"
    const val TYPE_RC_AGENT_STATUS = "rc_agent_status"
    const val TYPE_RC_THINKING = "rc_thinking"
    const val TYPE_RC_THINKING_END = "rc_thinking_end"
    const val TYPE_RC_MODE_CHANGE = "rc_mode_change"
    const val TYPE_RC_USER_INPUT = "rc_user_input"
    const val TYPE_RC_USER_RESPONSE = "rc_user_response"
    const val TYPE_RC_TRANSCRIPT = "rc_transcript"
    const val TYPE_RC_TRANSCRIPT_REQ = "rc_transcript_request"
    const val TYPE_RC_USER_MESSAGE = "rc_user_message"
    const val TYPE_RC_USER_MESSAGE_ACK = "rc_user_message_ack"
    const val TYPE_RC_ERROR = "rc_error"
    const val TYPE_RC_INTERRUPT = "rc_interrupt"
    const val TYPE_RC_REVIVE = "rc_revive"
    const val TYPE_RC_SETTING_CHANGE = "rc_setting_change"

    // Telegram chat
    const val TYPE_TG_CHAT_LIST = "telegram_chat_list"
    const val TYPE_TG_CHAT_LIST_RESULT = "telegram_chat_list_result"
    const val TYPE_TG_MESSAGES = "telegram_messages"
    const val TYPE_TG_MESSAGES_RESULT = "telegram_messages_result"
    const val TYPE_TG_SEND = "telegram_send"
    const val TYPE_TG_SEND_RESULT = "telegram_send_result"
    const val TYPE_TG_SUBSCRIBE = "telegram_subscribe"
    const val TYPE_TG_UNSUBSCRIBE = "telegram_unsubscribe"
    const val TYPE_TG_NEW_MESSAGE = "telegram_new_message"
    const val TYPE_TG_TOPICS = "telegram_topics"
    const val TYPE_TG_TOPICS_RESULT = "telegram_topics_result"

    // Assistant (real-time conversational fact-check)
    const val TYPE_ASSISTANT = "assistant"
    const val TYPE_ASSISTANT_NEW = "assistant_new"
    const val TYPE_ASSISTANT_RESULT = "assistant_result"

    /**
     * activeCards entries are (id, heard, note) triples. The orchestrator's
     * HUD-state reconciliation needs id+note; heard is optional and may be
     * empty (the phone does not cache heard for active cards).
     */
    fun createAssistantMessage(
        requestId: String,
        wearerText: String,
        interlocutorText: String,
        activeCards: List<Triple<String, String, String>>,
        model: String
    ): JSONObject {
        val cardsArr = org.json.JSONArray()
        for ((id, heard, note) in activeCards) {
            cardsArr.put(JSONObject().put("id", id).put("heard", heard).put("note", note))
        }
        return JSONObject().apply {
            put("type", TYPE_ASSISTANT)
            put("requestId", requestId)
            put("wearerText", wearerText)
            put("interlocutorText", interlocutorText)
            put("activeCards", cardsArr)
            if (model.isNotBlank()) put("model", model)
        }
    }

    fun createAssistantNewMessage(): JSONObject {
        return JSONObject().apply { put("type", TYPE_ASSISTANT_NEW) }
    }

    fun createIdentifyMessage(deviceId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_IDENTIFY)
            put("deviceId", deviceId)
            put("deviceType", "phone")
        }
    }

    fun createRequestMessage(text: String, imageBase64: String? = null, model: String? = null, deviceType: String? = null, userSystemPrompt: String? = null): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_REQUEST)
            put("text", text)
            if (imageBase64 != null) {
                put("image", imageBase64)
            }
            if (model != null) {
                put("model", model)
            }
            if (deviceType != null) {
                put("deviceType", deviceType)
            }
            if (!userSystemPrompt.isNullOrBlank()) {
                put("userSystemPrompt", userSystemPrompt)
            }
        }
    }

    fun createDeviceResponse(
        requestId: String,
        commandType: String,
        imageBase64: String? = null,
        screenBase64: String? = null,
        text: String? = null,
        data: JSONObject? = null
    ): JSONObject {
        val payload = JSONObject().apply {
            put("requestId", requestId)
            put("commandType", commandType)
            if (imageBase64 != null) put("imageBase64", imageBase64)
            if (screenBase64 != null) put("screenBase64", screenBase64)
            if (text != null) put("text", text)
            if (data != null) put("data", data)
        }
        return JSONObject().apply {
            put("type", TYPE_DEVICE_RESPONSE)
            put("payload", payload)
        }
    }

    fun createHealthPong(): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_HEALTH)
            put("status", "pong")
        }
    }

    fun createTtsInterruptMessage(requestId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_TTS_INTERRUPT)
            put("requestId", requestId)
        }
    }

    fun createAbortMessage(requestId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_ABORT)
            put("requestId", requestId)
        }
    }

    fun createStreamRequest(
        targetDeviceId: String,
        resolution: String = "720p",
        monitor: Int = 0,
        fps: Int = 24,
        preset: String = "ultrafast",
        profile: String = "baseline",
        keyframeInterval: Int = 2
    ): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_STREAM_REQUEST)
            put("targetDeviceId", targetDeviceId)
            put("resolution", resolution)
            put("monitor", monitor)
            put("fps", fps)
            put("preset", preset)
            put("profile", profile)
            put("keyframeInterval", keyframeInterval)
        }
    }

    fun createStreamSwitchMonitor(streamId: Int, monitor: Int): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_STREAM_SWITCH_MONITOR)
            put("streamId", streamId)
            put("monitor", monitor)
        }
    }

    fun createStreamStop(streamId: Int): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_STREAM_STOP)
            put("streamId", streamId)
        }
    }

    fun createAudioRelayStart(targetDeviceId: String, bitrate: Int, sampleRate: Int = 48000, channels: Int = 2): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_AUDIO_RELAY_START)
            put("targetDeviceId", targetDeviceId)
            put("bitrate", bitrate)
            put("sampleRate", sampleRate)
            put("channels", channels)
        }
    }

    fun createAudioRelayStop(targetDeviceId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_AUDIO_RELAY_STOP)
            put("targetDeviceId", targetDeviceId)
        }
    }

    fun createNotificationTtsMessage(notifId: String, sender: String, text: String, chat: String) = JSONObject().apply {
        put("type", TYPE_NOTIFICATION_TTS)
        put("notifId", notifId)
        put("sender", sender)
        put("text", text)
        put("chat", chat)
    }

    fun createWebRTCAnswer(streamId: Int, sdp: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_WEBRTC_ANSWER)
            put("streamId", streamId)
            put("sdp", sdp)
        }
    }

    fun createWebRTCIce(streamId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_WEBRTC_ICE)
            put("streamId", streamId)
            put("candidate", candidate)
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        }
    }

    fun createRcPermissionResponse(sessionId: String, requestId: String, approved: Boolean, modeChange: String? = null, reason: String? = null): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_PERMISSION_RESPONSE)
            put("sessionId", sessionId)
            put("requestId", requestId)
            put("approved", approved)
            if (modeChange != null) put("modeChange", modeChange)
            if (reason != null) put("reason", reason)
        }
    }

    fun createRcUserResponse(sessionId: String, requestId: String, text: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_USER_RESPONSE)
            put("sessionId", sessionId)
            put("requestId", requestId)
            put("text", text)
        }
    }

    fun createRcModeChangeRequest(sessionId: String, mode: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_MODE_CHANGE)
            put("sessionId", sessionId)
            put("mode", mode)
        }
    }

    /**
     * Create an outbound rc_user_message envelope. The requestId is a
     * client-generated UUID that the orchestrator echoes back on receipt
     * via rc_user_message_ack -- it lets the phone match acks to in-flight
     * messages and stop the retry timer for the right one.
     */
    fun createRcUserMessage(sessionId: String, text: String, requestId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_USER_MESSAGE)
            put("sessionId", sessionId)
            put("text", text)
            put("requestId", requestId)
        }
    }

    fun createRcTranscriptRequest(sessionId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_TRANSCRIPT_REQ)
            put("sessionId", sessionId)
        }
    }

    fun createRcInterrupt(sessionId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_INTERRUPT)
            put("sessionId", sessionId)
        }
    }

    fun createRcRevive(sessionId: String, workDir: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_REVIVE)
            put("sessionId", sessionId)
            put("workDir", workDir)
        }
    }

    fun createRcSettingChange(sessionId: String, setting: String, value: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_RC_SETTING_CHANGE)
            put("sessionId", sessionId)
            put("setting", setting)
            put("value", value)
        }
    }

}
