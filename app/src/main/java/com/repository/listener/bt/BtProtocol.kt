package com.repository.listener.bt

/**
 * BT custom command channel names for glasses <-> phone communication.
 */
object BtProtocol {
    // Glasses -> phone
    const val CH_STATUS = "listener_status"
    const val CH_COMMAND = "listener_command"

    // Glasses -> phone authoritative state snapshot, sent on every (re)connect.
    // Args: [state: String, requestId: String]. Phone reconciles its own
    // glassesAudioState to match. Per-transition CH_STATUS messages remain a
    // low-latency optimization; this is the source of truth across reconnects.
    const val CH_STATE_SNAPSHOT = "listener_state_snapshot"

    // Phone -> glasses
    const val CH_RESPONSE = "listener_response"
    const val CH_COMMAND_RESPONSE = "listener_command_response"
    const val CH_TTS_AUDIO = "listener_tts_audio"
    const val CH_TTS_INTERRUPT = "listener_tts_interrupt"
    const val CH_SETTINGS = "listener_settings"
    const val CH_TOOL_STATUS = "listener_tool_status"
    const val CH_STREAMING_TEXT = "listener_streaming_text"
    const val CH_DISMISS_SESSION = "listener_dismiss_session"
    const val CH_ROKID_COMMAND = "listener_rokid_command"
    const val CH_ACTIVATE = "listener_activate"

    // Phone -> glasses device tool commands (dedicated channel to avoid CXR-S subscription bugs)
    const val CH_DEVICE_COMMAND = "listener_device_command"

    // Phone -> glasses map frame: raw WEBP bytes on the DEDICATED map RFCOMM
    // socket (MapRfcommClient on MAP_UUID), sent as a single binary arg (no
    // base64). Isolated from the control socket so 10-15 FPS map traffic cannot
    // head-of-line-block audio/control.
    const val CH_MAP_BITMAP_BIN = "listener_map_bitmap_bin"

    // Phone -> glasses arrow position+heading samples for the minimap.
    // Args: [normX: String(Float), normY: String(Float), headingDeg: String(Float)]
    // Sent at ~5 Hz; the glasses interpolate between samples for smooth motion.
    const val CH_MAP_ARROW = "listener_map_arrow"

    // Health check (phone -> glasses -> phone)
    const val CH_HEALTH_PING = "listener_health_ping"
    const val CH_HEALTH_PONG = "listener_health_pong"

    // Chat list (glasses -> phone -> glasses)
    const val CH_CHAT_LIST_REQUEST = "listener_chat_list_req"
    const val CH_CHAT_LIST_RESPONSE = "listener_chat_list_resp"
    const val CH_SWITCH_CHAT = "listener_switch_chat"
    const val CH_NEW_CHAT = "listener_new_chat"
    const val CH_CHAT_HISTORY = "listener_chat_history"

    // Glasses -> phone heading (IMU rotation vector)
    const val CH_GLASSES_HEADING = "listener_glasses_heading"

    // Glasses -> phone mouse HID report (6-byte base64-encoded).
    // Sent at ~30 Hz while head tracking is active. Phone forwards to BT Classic HID.
    const val CH_MOUSE_REPORT = "listener_mouse_report"

    // Phone -> glasses translation (custom UI, bypasses Rokid OS)
    const val CH_TRANSLATION_RESULT = "listener_trans_result"
    const val CH_TRANSLATION_CONFIG = "listener_trans_config"

    // Phone -> glasses desired translation on/off state (authoritative reconciliation).
    // The phone owns translationMode; the glasses reconcile their translationFrontMicRecorder
    // to match. Heals any lost edge-triggered start_translation/stop_translation command
    // (fire-and-forget RFCOMM can drop, esp. during the reconnect burst). Sent on glasses
    // connect, whenever translationMode changes, and periodically while translation is active.
    // Single JSON arg: {active, from, to, fromNllb, toNllb, fontSize, twoWay}.
    const val CH_TRANSLATION_STATE = "listener_trans_state"

    // Glasses -> phone mic audio (raw PCM16LE, Base64 encoded)
    const val CH_AUDIO_DATA = "listener_audio_data"

    // Glasses -> phone inward mic audio (raw PCM16LE, Base64 encoded).
    // Used in two-way translation: inward mic captures the wearer's speech.
    const val CH_AUDIO_DATA_INWARD = "listener_audio_data_inward"

    // Glasses -> phone HFP call downlink audio (far party). Opus-compressed 16kHz mono:
    // concatenated 2-byte-LE-length Opus frames, Base64 (NO_WRAP) encoded. Tapped from
    // the glasses' 8ch mic-array hardware-echo channel (the SCO downlink) during a call,
    // Opus-encoded to keep RFCOMM from saturating, and fed into the phone's translation
    // session as the "system audio" sub-source, so "System Audio" translation also covers
    // phone calls.
    const val CH_AUDIO_DATA_CALL = "listener_audio_data_call"

    // Glasses -> phone HFP SCO call-audio state. The glasses are the hands-free endpoint,
    // so their SCO state is the authoritative signal for far-party call audio arriving.
    // The phone flips its translation system sub-source (playback<->call) on this.
    // Args: ["1" sco active, "0" sco idle].
    const val CH_CALL_STATE = "listener_call_state"

    // Phone -> glasses live partial transcription (partials while speaking)
    const val CH_GLASSES_PARTIAL_TEXT = "listener_partial_text"

    // Phone -> glasses user's final transcribed text
    const val CH_GLASSES_USER_TEXT = "listener_user_text"

    // ReID face detection (glasses -> phone -> API -> phone -> glasses)
    const val CH_REID_FACE = "listener_reid_face"
    const val CH_REID_RESULT = "listener_reid_result"
    const val CH_REID_MERGE = "listener_reid_merge"

    // ReID best thumbnail (phone -> glasses, sent after successful match)
    const val CH_REID_BEST_THUMB = "listener_reid_best_thumb"

    // ReID person intel (glasses -> phone -> glasses)
    const val CH_REID_PERSON_REQ = "listener_reid_person_req"
    const val CH_REID_PERSON_RESP = "listener_reid_person_resp"

    // Phone -> glasses system status
    const val CH_SYSTEM_STATUS = "listener_system_status"

    // Todo list (glasses -> phone -> glasses)
    const val CH_TODO_LIST_REQ = "listener_todo_list_req"
    const val CH_TODO_LIST_RESP = "listener_todo_list_resp"
    const val CH_TODO_TOGGLE = "listener_todo_toggle"
    const val CH_TODO_ADD = "listener_todo_add"
    const val CH_TODO_REMOVE = "listener_todo_remove"

    // Alarm list (glasses <-> phone, plus proactive push)
    const val CH_ALARM_LIST_REQ = "listener_alarm_list_req"
    const val CH_ALARM_LIST_RESP = "listener_alarm_list_resp"

    // Job list (glasses <-> phone, plus proactive push)
    const val CH_JOB_LIST_REQ = "listener_job_list_req"
    const val CH_JOB_LIST_RESP = "listener_job_list_resp"

    // Telegram saved messages (glasses -> phone -> glasses)
    const val CH_TELEGRAM_SAVED_REQ = "listener_tg_saved_req"
    const val CH_TELEGRAM_SAVED_RESP = "listener_tg_saved_resp"

    // Telegram chat (glasses <-> phone)
    const val CH_TG_CHAT_LIST_REQ = "listener_tg_chat_list_req"
    const val CH_TG_CHAT_LIST_RESP = "listener_tg_chat_list_resp"
    const val CH_TG_MESSAGES_REQ = "listener_tg_msgs_req"
    const val CH_TG_MESSAGES_RESP = "listener_tg_msgs_resp"
    const val CH_TG_SEND_REQ = "listener_tg_send_req"
    const val CH_TG_SEND_RESP = "listener_tg_send_resp"
    const val CH_TG_SUBSCRIBE = "listener_tg_subscribe"
    const val CH_TG_UNSUBSCRIBE = "listener_tg_unsubscribe"
    const val CH_TG_NEW_MESSAGE = "listener_tg_new_msg"
    const val CH_TG_OPEN_CHAT = "listener_tg_open_chat"
    const val CH_TG_CLOSE_CHAT = "listener_tg_close_chat"
    const val CH_TG_TOPICS_REQ = "listener_tg_topics_req"
    const val CH_TG_TOPICS_RESP = "listener_tg_topics_resp"
    const val CH_TG_VOICE_START = "listener_tg_voice_start"
    const val CH_TG_VOICE_STOP = "listener_tg_voice_stop"

    // --- RC mirror (spec 2026-08-06, six channels; there is deliberately no CH_RC_LIST_REQ:
    // the state push is a full unsolicited snapshot, re-sent on every link-up) ---
    const val CH_RC_STATE_PUSH    = "listener_rc_state_push"   // phone -> glasses
    const val CH_RC_MESSAGES_REQ  = "listener_rc_msgs_req"     // glasses -> phone
    const val CH_RC_MESSAGES_RESP = "listener_rc_msgs_resp"    // phone -> glasses
    const val CH_RC_SEND_REQ      = "listener_rc_send_req"     // glasses -> phone
    const val CH_RC_SEND_RESP     = "listener_rc_send_resp"    // phone -> glasses (errors only)
    const val CH_RC_ANSWER_REQ    = "listener_rc_answer_req"   // glasses -> phone

    const val CH_SPEAKER_VERIFY_REQ = "listener_spk_verify_req"
    const val CH_SPEAKER_VERIFY_RESP = "listener_spk_verify_resp"

    // Notifications (phone -> glasses)
    // CH_NOTIFICATION args: [notifId, sender, text, chat, repliable("1"/"0")].
    // repliable=="1" means the originating notification carries a native reply
    // action (RemoteInput), so the glasses may offer a voice reply.
    const val CH_NOTIFICATION = "listener_notification"
    const val CH_NOTIFICATION_TTS = "listener_notification_tts"

    // Replace an already-displayed notification's overlay text (phone -> glasses).
    // Sent when a same-sender notification arrives while the previous one is still
    // in-flight on the glasses. The phone recomputes the FULL chronologically-sorted
    // body and the glasses REPLACE the overlay text with it (reorders correctly even
    // on out-of-order delivery) and restart its dismissal timer. Args: [notifId,
    // fullText]. Mirrors CH_NOTIFICATION's raw caps-string arg encoding (fullText may
    // contain newlines and Cyrillic).
    const val CH_NOTIFICATION_SETTEXT = "listener_notification_settext"

    // Notifications (glasses -> phone acknowledgement)
    const val CH_NOTIFICATION_DONE = "listener_notification_done"

    // Notification voice reply (glasses -> phone).
    // The glasses reuse the Telegram-voice capture/STT path to record a spoken
    // reply to a notification, then send the confirmed text back; the phone
    // fires the notification's own RemoteInput PendingIntent to deliver it.
    const val CH_NOTIF_REPLY_START = "listener_notif_reply_start"   // args: [notifId]
    const val CH_NOTIF_REPLY_SEND = "listener_notif_reply_send"     // args: [notifId, text]
    const val CH_NOTIF_REPLY_CANCEL = "listener_notif_reply_cancel" // args: [notifId]
    // Delivery confirmation phone -> glasses: did the RemoteInput actually fire?
    const val CH_NOTIF_REPLY_RESULT = "listener_notif_reply_result"  // args: [notifId, ok("1"/"0")]

    // Glasses -> phone audio ducking (duck phone STREAM_MUSIC during glasses TTS)
    const val CH_AUDIO_DUCK = "listener_audio_duck"

    // Glasses -> phone wear state (proximity-driven on-head detection).
    // Args: ["1" worn, "0" off-head]. Emitted on every transition and once on (re)connect.
    const val CH_WEAR_STATE = "listener_wear_state"

    // Glasses -> phone screen on/off. Used (alongside wear state) to gate the
    // heavy map-bitmap stream so we don't burn BT bandwidth on a dark HUD.
    // Args: ["1" on, "0" off]. Emitted on every transition and once on (re)connect.
    const val CH_SCREEN_STATE = "listener_screen_state"

    // File sync (glasses <-> phone). See GlassesSyncClient / SyncChannelHandler.
    const val CH_SYNC = "listener_sync"

    // Sideload control (phone <-> glasses). JSON frames in arg[0].
    // Phone -> glasses: {"t":"OPEN_WIFI"} | {"t":"CLOSE_WIFI"}.
    // Glasses -> phone: {"t":"WIFI_READY","details":{ssid,passphrase,ip,port,deviceAddress}}
    //                 | {"t":"WIFI_ERROR","reason":"..."}
    //                 | {"t":"WIFI_CLOSED"}.
    // Gated on the phone's enable_sideloading toggle; reuses WifiDirectJoiner.
    const val CH_SIDELOAD = "listener_sideload"

    // Glasses -> phone generic command channel (e.g. request_start_translation).
    // Args: JSON array [commandType: String, paramsObject: JSONObject?]
    const val CH_GLASSES_COMMAND = "glasses_command"

    // Glasses -> phone wake event (on-glasses WakeWordPipeline fired).
    // Args: [confidence: String(Float), epochNanos: String(Long)].
    // See ListenerService handleGlassesWakeEvent.
    const val CH_WAKE_EVENT = "listener_wake_event"

    // Phone -> glasses wall-clock + timezone sync.
    // Args: [epochMillis, tzId].
    //   epochMillis -- phone's System.currentTimeMillis() at send time
    //   tzId -- java.util.TimeZone id (e.g. "Europe/Moscow")
    // Glasses apply to their system clock and timezone on receive.
    const val CH_TIME_SYNC = "listener_time_sync"

    // Phone -> glasses weather widget.
    // Args: [iconTag: String, tempC: String, locationLabel: String].
    // iconTag in {"clear","cloudy","rain","snow","thunder","fog",""}; "" hides widget.
    const val CH_WEATHER = "listener_weather"

    // Contact list cache for HFP caller-ID. Bidirectional.
    // Phone -> glasses on RFCOMM connect: ["HASH", agMac, hashHex].
    // Glasses -> phone if cache stale or absent: ["REQUEST_FULL", agMac].
    // Phone -> glasses chunked: ["LIST", agMac, hashHex, jsonChunk, isFinal("0"/"1")].
    // jsonArray of {"n": "+E164normalized", "d": "Display Name"}.
    const val CH_CONTACTS = "listener_contacts"

    // Remote input events from a registered input source (the Wear watch bezel
    // today, another gadget later). Carried on the DEDICATED input RFCOMM socket
    // (InputRfcommClient / INPUT_UUID), never the shared message socket, where
    // bulk frames head-of-line-block input for seconds.
    // Args: [v, src, sid, seq, type, steps, wms, tag] -- all decimal ASCII
    // except src, type and tag.
    //   type:  "SCROLL"|"SELECT"|"BACK"|"OPEN"|"CLOSE"|"PING"
    //   steps: signed detent count; positive = forward/down; "0" for non-SCROLL
    //   wms:   watch elapsedRealtime low 32 bits at the moment of the input
    //   tag:   16 hex chars, HMAC-SHA256 truncated to 8 bytes, computed ON THE
    //          WATCH over "v|src|sid|seq|type|steps|wms"
    // Receivers MUST check args.size >= 8, parse with toIntOrNull()/toUIntOrNull()
    // and wrap the whole parse in try/catch: onMessage runs on a Binder thread and
    // an uncaught throw kills the service.
    const val CH_REMOTE_INPUT = "listener_remote_input"

    // Glasses -> phone: whether the glasses input sink is currently attached
    // (their UI is foreground and able to accept injected input). Drives the
    // watch's GLASSES_BUSY state, so the user is told "glasses screen not
    // active" instead of seeing a connected status while events are discarded.
    // Args: ["1" attached, "0" detached].
    //
    // Received on the DEDICATED input socket (InputRfcommClient), which is the socket
    // the glasses publish it on -- NOT on the shared control socket.
    const val CH_REMOTE_INPUT_SINK = "listener_remote_input_sink"

    // Glasses -> phone, same dedicated input socket: the router's own account of why
    // input may be going nowhere.
    // Args: [sessionOpen, sinkAttached, droppedTotal] -- "1"/"0" for the flags,
    // decimal for the counter. `sinkAttached` reports the SAME bit as
    // CH_REMOTE_INPUT_SINK, so consumers must fold the two into one value rather
    // than tracking two that can disagree.
    const val CH_REMOTE_INPUT_STATUS = "listener_remote_input_status"

    // Glasses -> phone: a transcript produced by the ON-GLASSES recogniser.
    // Args: [sessionTag, status, text]
    //   sessionTag: "assistant" (AI hold / wake-word follow-on) or "tg_voice"
    //               (Telegram voice, notification reply and RC voice all share it;
    //               the glasses focusState disambiguates, exactly as today).
    //   status:     "ok"   -- local recognition produced a final, `text` is it.
    //               "fail" -- local STT could not do it (model absent, NPU busy,
    //                         Binder timeout, capture dead). The phone falls back
    //                         to batch-transcribing the PCM it buffered.
    //   text:       the final transcript. EMPTY IS MEANINGFUL: "" with status=ok
    //               is an explicit empty final, i.e. the wearer cancelled, and the
    //               phone must emit the empty user text and clear its pending
    //               notification reply. Encoders must keep "" in its argument
    //               slot; collapsing it to a missing arg hangs a notification
    //               reply in SENDING forever. See LocalTranscriptWire.
    // Finals only -- local mode emits no partials, so no ACTION_PARTIAL_TEXT.
    // Receivers MUST check args.size >= 3 and wrap the parse in try/catch:
    // onMessage runs on a Binder thread and an uncaught throw kills the service.
    const val CH_LOCAL_TRANSCRIPT = "listener_local_transcript"

    // Glasses -> phone, sent BEFORE the session opens: which recogniser will
    // handle it. Args: [mode, sessionTag], mode = "local" | "remote".
    // On "local" the phone opens no transcriber WebSocket, does not feed its VAD
    // and does not arm the no-speech watchdog -- but it KEEPS buffering the PCM,
    // because that buffer is what the "fail" fallback transcribes.
    // Anything not exactly "local" means remote: failing the other way would
    // leave nobody transcribing at all.
    const val CH_STT_MODE = "listener_stt_mode"

    // Glasses -> phone, on connect and on change: whether local recognition is
    // possible at all on this device. Args: [available ("1"|"0"), modelVersion].
    // Independent of any session, so the phone can surface the state rather than
    // inferring it from a mode announcement that may never come.
    const val CH_STT_CAPABILITY = "listener_stt_capability"
}
