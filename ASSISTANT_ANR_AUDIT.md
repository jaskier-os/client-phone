# Assistant feature — UI-thread / ANR audit

Thread map (load-bearing):
- Phone `onStartCommand` -> `ACTION_ADB_DISPATCH` -> `handleAdbCommand` runs on the **service MAIN thread** (foreground-service callbacks are main-looper). So `start_assistant`/`stop_assistant` -> `startAssistant`/`stopAssistant` are MAIN thread.
- Phone audio callbacks `onGlassesAudioData`/`onGlassesInwardAudioData` run on the single **`GlassesRfcommConnect`** RFCOMM read thread (GlassesRfcommClient.readLoop -> dispatchRelay -> onCustomCmd). `onSystemAudioChunk` runs on **SystemAudioCapturer captureThread**.
- Phone batch Handler = `Looper.getMainLooper()` -> `flushAssistantBatch` is MAIN thread.
- Glasses `onCommand` is invoked from `BtManagerBridge` AIDL `IBtManagerCallback.Stub().onRfcommData`, i.e. a **Binder pool thread** (NOT glasses main). AudioRecord read runs on its own `recordingThread`.

---

## CRITICAL

### C1 — startAssistant blocks the phone MAIN thread up to ~20s (guaranteed ANR)
`service/ListenerService.kt:1794` `startAssistant` runs on main. It calls `AzureTranslationSession.startRecognition` twice sequentially (`:1819` wearer, `:1834` interlocutor). Each delegates to `AzureTranslationClient.startRecognition` whose `rec.startContinuousRecognitionAsync().get(START_TIMEOUT_MS)` blocks up to **10s** (`network/AzureTranslationClient.kt:403`, `START_TIMEOUT_MS=10_000` :26). Two sessions = **up to 20s on main**. ANR fires at 5s. Even the happy path (network handshake) routinely costs hundreds of ms x2. Also `orchestratorClient.sendAssistantNew()` (:1817) and `phoneBtHost.sendCommand` (:1851) run here.
Fix: dispatch the whole `startAssistant` body off main — `serviceScope.launch(Dispatchers.IO)`. Keep the two `startRecognition` calls IO-bound (parallelize with `async`/`awaitAll` to cap at ~10s instead of 20s). `handleAdbCommand` `start_assistant` branch (:5337) must not depend on a synchronous boolean return on main; report start asynchronously.

### C2 — stopAssistant blocks the phone MAIN thread up to ~6s
`service/ListenerService.kt:1866` `stopAssistant` (main). Each session `stop()` calls `AzureTranslationClient.stop` which now does TWO bounded `.get(STOP_TIMEOUT_MS)` (`:482` recognizer + `:487` speechRecognizer, `STOP_TIMEOUT_MS=3_000` :27). Worst case 3s+3s per client; two sessions => **up to ~12s** if both recognizer fields are populated, realistically ~6s (assistant only uses the speechRecognizer path -> 3s x2 sessions). `stopSystemAudioCapture()` also joins a thread (1s). All on main -> ANR.
Note: `startAssistant` calls `stopAssistant(silent=true)` first (:1800), so the start path eats the stop latency too.
Fix: same — run `stopAssistant` on `Dispatchers.IO`.

---

## HIGH

### H1 — pushLock held across the blocking start `.get()` stalls the RFCOMM audio thread
`AzureTranslationClient.start*` acquire `synchronized(pushLock)` to flush preStart frames AFTER the blocking `.get()` returns (`:404-417`, `:166-179`) — the lock itself is NOT held during `.get()`, good. BUT `pushPcm` (`:428`) also takes `pushLock`, and is called from the **RFCOMM read thread** (`onGlassesInwardAudioData`/`onGlassesAudioData` -> `pushPcm`) at ~50 frames/s. While `startAssistant` runs on main, the recognizer is being constructed; pushPcm just buffers into `preStartFrames` (cheap). Real risk: `PushAudioInputStream.write` inside the lock can block on the Azure SDK's internal buffer when the network is slow, stalling the single RFCOMM read thread — which also carries control messages and the OTHER session's audio (head-of-line blocking). Severity High because one slow Azure write freezes all inbound BT.
Fix: move pushPcm off the RFCOMM thread — hand frames to a per-session bounded queue drained by a dedicated worker, so a slow SDK write never blocks BT reads. At minimum, don't decode base64 + write on the read thread.

### H2 — flushAssistantBatch does ws.send on the phone MAIN thread every 5-10s
Batch Handler is main-looper (`startAssistantBatchTimer:1892`). `flushAssistantBatch` (:1927) takes `assistantBufferLock`, builds JSON, then `orchestratorClient.sendAssistantBatch` (:1949) -> `ws?.send(...)` (`network/OrchestratorClient.kt:772`). OkHttp `WebSocket.send` enqueues and returns quickly normally, but `sendOrQueue` (`:1528`) wraps sends in `synchronized(pendingSends)`; if the writer is mid-drain or the socket buffer is full, this can stall. JSON-of-large-payload + lock on main at a 5-10s cadence is a latent jank/ANR source.
Fix: post the flush to a background dispatcher; only the timer tick needs main. `assistantBufferLock` is only held by main (flush) and the RFCOMM thread (onFinal append) — short critical sections, acceptable, but keep JSON building outside the lock (it already copies strings out first — good).

---

## MEDIUM

### M1 — Glasses start_assistant blocks the Binder thread (native init + AudioRecord), no watchdog
`glasses .../service/ListenerService.kt:6311` `start_assistant` (Binder thread) calls `BeamformController.init` (:6314) -> `nativeInit` (JNI, `capture/BeamformController.kt:55`) which may also `extractLib` (unzip AssistServer APK to filesDir, file I/O) on first run; `setScene` -> `nativeSetScene` JNI (:64); then `startFrontMicForTranslation` -> `TranslationFrontMicRecorder.start` -> `nativeCaeInit("/system/etc")` + `create8chRecord()` + `startRecording()` inline (`capture/TranslationFrontMicRecorder.kt:78,162,172`). AudioRecord 8-ch init can take 100s of ms. This is NOT main (binder pool), so it won't ANR the glasses UI, but it blocks the inbound RFCOMM frame pump (head-of-line: control + audio frames queue behind it). No watchdog/timeout around native init.
Fix: offload start_assistant heavy init to a dedicated worker thread (the recorder already reads on its own `recordingThread`; just move the init + first-run extract off the binder callback). Ack the command immediately, do init async.

### M2 — AssistantCardOverlay relayout measures every card on each show/dismiss (main)
`glasses ui/AssistantCardOverlay.kt:46/114/127` correctly `handler.post` to main (WindowManager requires main). `relayout()` (:140) loops all cards, calls `view.measure` (:145) + `wm.updateViewLayout` per card on every show/dismiss. With a handful of cards this is cheap; measure MUST stay on main. Acceptable now; only a concern if card count grows large. No fix required beyond capping active cards.

---

## LOW

### L1 — gateTileLaunch / ConfigDialog BT ping
`ui/GlassesAppsFragment.kt` tile handlers and `ui/AssistantConfigDialog.kt`: the start/stop tiles dispatch via ADB-style intents / `phoneBtHost` and read `phoneBtHost.isConnected` (a `@Volatile` getter — non-blocking). No synchronous BT round-trip ping found in `gateTileLaunch` on the UI tap path. Low/no risk; verify no future `.get()` is added on the tap path.

---

## Pre-existing pattern note
The translation path uses the SAME blocking `.get()` (`AzureTranslationClient.start*:162,313,403`). Translation start is reached via the same `handleAdbCommand` main-thread dispatch (`start_translation` branch) and `startAzureTranslation` — it is **NOT** currently dispatched off main either, so this is a pre-existing latent ANR that the Assistant doubles down on (assistant runs TWO sessions instead of one). Recommend fixing both by moving all Azure start/stop onto `Dispatchers.IO`; the assistant fix and translation fix share the same dispatcher wrapper.
