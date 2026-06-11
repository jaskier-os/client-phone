# CLAUDE.md -- Phone Companion App

Companion Android app for Rokid AR glasses. Kotlin, native Android. Multi-module project (`:app` + `:navigation`). Handles wake word detection (openWakeWord via ONNX Runtime), VAD (Silero), audio recording, transcription, orchestrator communication, Bluetooth relay to/from glasses, Telegram notification forwarding, real-time translation, navigation (Yandex MapKit), network scanning, ReID analytics, teleprompter, camera/screen capture, biometric lock, and todo management.

**Package:** `com.repository.listener`

## Modules

- **`:app`** -- Main application module. All phone-side logic.
- **`:navigation`** -- Navigation library module (Yandex MapKit, Room DB, AIDL service). Package: `com.repository.navigation`.

## Build Flavors

Two flavors: `production` (default) and `local`. Biometric auth is tied to the flavor, not the build type: `production` always requires biometric unlock, `local` never does.

- **production** (default): HTTPS only, orchestrator at `wss://<ORCHESTRATOR_HOST>:8443/ws/device`, trusts user-installed CAs, `REQUIRE_BIOMETRIC=true`.
- **local**: cleartext HTTP allowed, orchestrator at `ws://<LAN_HOST>:10001/ws/device`, `REQUIRE_BIOMETRIC=false` for fast dev iteration.

## ReID OSINT feature flags

The russia-specific ReID OSINT / intel / phone-number feature is gated by build
flags that all default OFF. Set them in `local.properties` (or as env vars of the
same name -- env wins); they are declared via the `cfg()` helper in
`app/build.gradle.kts` and read as `BuildConfig.*`. Documented in
`local.properties.example`.

This repo's flags:

- **`ENABLE_REID_RU_TABS`** (default `false`) -- shows the ReID "Phone Numbers"
  sub-tab plus the person-detail "Intel" tab. Gated in `ui/ReidSubTabAdapter.kt`,
  `ui/ReidFragment.kt`, `ui/PersonDetailTabAdapter.kt`,
  `ui/PersonDetailActivity.kt` (all keyed on `BuildConfig.ENABLE_REID_RU_TABS`).
- **`ENABLE_REID_OSINT`** (default `false`) -- enables the assistant-driven OSINT
  lookup tool (`lookup_person_info` -> `searchPersonInfo`) in
  `service/ListenerService.kt`. Core face re-identification (`identify_person`) is
  NOT gated and works regardless.

### Related switches in other repos

The feature spans the whole stack; flipping it on end-to-end means setting flags
in three repos:

- **client-glasses** (`local.properties` / env, `BuildConfig.*`):
  `ENABLE_REID_OSINT` -- enables the person "intel" (OSINT) request + intel modal
  (`requestPersonIntel` in `MainActivity.kt`). Glasses' core ReID tab + face
  recognition are NOT gated.
- **reid/reid-analytics backend** (`backend/.env`): `ENABLE_OSINT` -- mounts the
  OSINT/sherlock API routes (`osint-photos`/`osint-reports`, `search-phone`,
  `persons/batch-phone-lookup`, `persons/:id/search-info`) in
  `backend/routes/reidRoutes.js`. When off, those routes are not mounted and
  return 404.
- **reid/reid-analytics frontend** (`frontend/.env`): `VITE_ENABLE_OSINT` -- shows
  the person "OSINT" tab + sections in `PersonDetail.jsx`.

The phone (and glasses) OSINT lookups call the reid-analytics backend as their
data source, so flipping only the client flag without the backend's `ENABLE_OSINT`
yields 404s.

**To enable the whole feature:** phone `local.properties`
`ENABLE_REID_RU_TABS=true` + `ENABLE_REID_OSINT=true`; glasses `local.properties`
`ENABLE_REID_OSINT=true`; reid-analytics backend `.env` `ENABLE_OSINT=true`;
reid-analytics frontend `.env` `VITE_ENABLE_OSINT=true`.

## Common Commands

```bash
# Build local (default)
.../gradlew -p .../phone assembleLocalDebug

# Build production
.../gradlew -p .../phone assembleProductionDebug

# Deploy local (default)
bash scripts/deploy-to-phone.sh

# Deploy production
bash scripts/deploy-to-phone.sh --prod

# Clean
.../gradlew -p .../phone clean
```

## Source Structure

```
app/src/main/java/com/repository/listener/
  adb/            -- ADB command receiver + result writer + streaming test runner
  audio/          -- Wake word, VAD, recorder, TTS player, speaker verification, pipeline tracing
  boot/           -- Boot receiver (auto-start on boot)
  bt/             -- Bluetooth: PhoneBtHost (CXR-M relay), GlassesGattClient, WowMouse, health monitor
  capture/        -- Camera, screen, system audio capture, location provider
  config/         -- AppConfig (shared preferences)
  network/        -- OrchestratorClient (WebSocket), TranscriptionClient, TranslationClient,
                     ChatHistoryClient, ReidAnalyticsClient, RemoteSessionClient, StreamWebSocket
  notification/   -- Telegram notification listener + notification queue
  phone/          -- CallLogReader
  scanner/        -- Network scanning (ARP, Bluetooth, TCP, nmap, service discovery, WiFi info)
  security/       -- Biometric lock manager
  service/        -- ListenerService (foreground service, central coordinator)
  sync/           -- Glasses file sync (WiFi P2P), video overlay merger
  ui/             -- All UI: MainActivity, fragments (Chat, Glasses, Config, Reid, Todo, Desktop,
                     Navigation), adapters, dialogs, settings activities
  util/           -- LogCollector, AssetExtractor, ImageCacheUtil, ScreenStateReceiver

navigation/src/main/
  aidl/           -- INavigationService, INavigationCallback, TransportMethod
  java/.../       -- NavigationService, NavigationManager, RouteEngine, ArrivalDetector,
                     GeocoderHelper, MapBitmapStreamer, PlaceSearchHelper, Room DB (sessions, destinations)
```

## Log Tag System

In-app log viewer (Config > Server tab) filters logs by tag categories defined in `LogCollector.kt`:

- **GLASSES_TAGS**: `PhoneBtHost`, `Rokid Glasses CXR-M`, `GlassesSettings`, `GlassesInfoDialog`, `GlassesFragment`, `TelegramNotif`, `AudioRecordingList`, `AudioViewer` -- phone-side glasses-domain logs only (the BT log relay was removed; for glasses-side logs see "Debugging Glasses App Problems" below)
- **FILESYNC_TAGS**: `GlassesFileSync`
- **VOICE_TAGS**: `WakeWordDetector`, `VadEngine`, `AudioRecorder`, `TranscriptionClient`, `SpeakerVerifier`, `PipelineTracer`, `WebRTCClient`, `TtsPlayer`, `AudioToolRecorder`, `SpeechPosTracker`

New components MUST use a tag from the appropriate category, or create a new category in `LogCollector.kt` and wire it to a new button in `ConfigFragment.kt`.

## Log Files

Persistent logs are written to `filesDir/logs/` with two streams:

```
files/logs/listener/latest.log              # current session -- phone service logs
files/logs/listener/2026-03-04_15-30-00.log # previous sessions (max 10, pruned after 7 days)
files/logs/glasses/latest.log               # current session -- phone-side glasses-domain logs (BT host, settings UI, Telegram relay, audio viewer)
files/logs/glasses/2026-03-04_15-30-00.log  # previous sessions (max 10, pruned after 7 days)
```

Note: `glasses/latest.log` only contains phone-side events tagged with one of the `GLASSES_TAGS`. Glasses-device logs are NOT relayed here -- the BT log relay was removed for battery reasons (see glasses CLAUDE.md). To read actual glasses-device logs, see the next section.

### ADB Commands

```bash
# Read latest phone logs
adb shell run-as com.repository.listener cat files/logs/listener/latest.log

# Read latest phone-side glasses-domain logs
adb shell run-as com.repository.listener cat files/logs/glasses/latest.log

# List all session files
adb shell run-as com.repository.listener ls -la files/logs/listener/
adb shell run-as com.repository.listener ls -la files/logs/glasses/

# Tail live (requires tail binary on device)
adb shell run-as com.repository.listener tail -f files/logs/listener/latest.log

# Pull to PC
adb exec-out run-as com.repository.listener cat files/logs/listener/latest.log > phone_listener.log
adb exec-out run-as com.repository.listener cat files/logs/glasses/latest.log > phone_glasses.log
```

## Debugging Glasses App Problems

When the glasses are reachable via USB cable, use `adb -s <GLASSES_SERIAL> logcat` directly -- that's the live source of truth. Otherwise (BT-only, no USB), pull the persistent log file the glasses write to `/sdcard/Download/glasses-client.log` via WiFi P2P:

```bash
bash AI/clients/phone/test/adb/pull_glasses_log.sh
```

The phone's `files/logs/glasses/latest.log` only captures phone-side glasses-domain events (PhoneBtHost, GlassesSettings, etc.) -- it does NOT contain glasses-device logs. The BT log relay that previously mirrored them was removed for battery reasons (it pinned the RFCOMM connection awake during idle).

## ADB Command System

The app exposes an exported BroadcastReceiver (`AdbCommandReceiver`) that accepts commands via ADB broadcast intents. This enables Claude (on PC) to trigger and verify phone/glasses features without the orchestrator.

### Sending Commands

```bash
# Explicit component target required (Android 8+ implicit broadcast restrictions)
adb shell am broadcast \
  -n com.repository.listener/.adb.AdbCommandReceiver \
  -a com.repository.listener.ADB_COMMAND \
  --es type "<command_type>" \
  --es command_id "<unique_id>" \
  --es params '{"key":"value"}'
```

### Reading Results

Results are written as JSON to `filesDir/adb_results/`:

```bash
# Read specific result
adb shell run-as com.repository.listener cat files/adb_results/<command_id>.json

# Read latest result
adb shell run-as com.repository.listener cat files/adb_results/latest.json
```

Result format:
```json
{
  "command_id": "rec001",
  "type": "record_ar_screen",
  "status": "success|error|pending",
  "timestamp": "2026-03-04T15:30:00.000+0300",
  "data": {},
  "error": "message if status=error"
}
```

### Available ADB Commands

| Type | Params | Glasses Required | Description |
|------|--------|-----------------|-------------|
| `status` | none | No | Returns BT connection state, service state, orchestrator state |
| `record_ar_screen` | `{"duration": 10}` | Yes | Records AR screen on glasses, returns file path |
| `record_video` | `{"duration": 10}` | Yes | Records video on glasses, returns file path |
| `stop_recording` | none | Yes | Stops active recording on glasses |
| `list_storage` | none | Yes | Lists storage on glasses |
| `sync_files` | none | Yes | Triggers WiFi P2P file sync from glasses to phone |
| `pull_glasses_log` | none | No (WiFi P2P only) | Downloads glasses-client.log from glasses via HTTP, BT-independent |
| `start_translation` | `{"source_lang": "en", "target_lang": "ru"}` | Yes | Starts real-time translation via glasses mic |
| `stop_translation` | none | No | Stops active translation session |
| `switch_audio_source` | `{"audio_source": "system"}` | No | Switches translation audio source (glasses/system) |
| `start_teleprompter` | `{"text": "...", "speed": 3}` | Yes | Starts teleprompter on glasses |
| `teleprompter_control` | `{"action": "stop", "original_command_id": "..."}` | Yes | Controls active teleprompter session |
| `pipeline_diag` | `{"toggle_debug": true}` (optional) | No | Returns pipeline diagnostic counters, optionally toggles debug mode |
| `dump_audio` | none | No | Dumps wake word rolling buffer to WAV file |
| `test_oww` | `{"wav_file": "/path/to/file.wav"}` | No | Tests wake word detection on a WAV file |
| `test_streaming` | `{"pcm_file": "/path/to/file.pcm"}` | No | Tests transcription streaming pipeline |
| `test_notif` | `{"sender": "...", "text": "...", "chat": "..."}` | No | Enqueues a test notification |
| `notif_queue_status` | none | No | Returns notification queue state |
| `notif_queue_clear` | none | No | Clears notification queue |
| `chat_status` | none | No | Returns orchestrator connection state and pending chat info |
| `chat_send` | `{"text": "...", "device_type": "glasses"}` | No | Sends a chat request to orchestrator, waits for async response (120s timeout) |
| `chat_list` | `{"limit": 20}` | No | Lists conversations from chat history |
| `chat_get` | `{"conversation_id": "uuid"}` | No | Gets conversation turns (latest if no ID) |
| `chat_new` | `{"device_type": "glasses"}` | No | Creates a new conversation |
| `diag_*` | varies | Yes | Various glasses diagnostic commands (diag_ar, diag_screen_record, etc.) |
| `alarm_list` | none | No | Returns all alarms from AlarmStore |
| `alarm_create` | `{"hour": 8, "minute": 30, "title": "Wake up"}` | No | Creates and schedules an alarm |
| `alarm_delete` | `{"id": 1}` or `{"hour": 8, "minute": 30}` or `{"title": "Wake up"}` | No | Deletes an alarm by id, time, or title |
| `job_list` | none | No (orchestrator required) | Lists all jobs from orchestrator |
| `job_create` | `{"name": "...", "prompt": "...", "scheduled_at": "ISO8601"}` | No (orchestrator required) | Creates a scheduled job |
| `job_delete` | `{"id": "job_uuid"}` | No (orchestrator required) | Deletes a job by ID |

### Architecture

```
Claude (PC) -> adb broadcast -> AdbCommandReceiver -> ListenerService.handleAdbCommand()
  - status: returns immediately
  - glasses commands: phoneBtHost.sendCommand() -> BT -> glasses -> result callback -> AdbResultWriter
  - sync_files: triggers GlassesFileSyncManager.startSync()
  - pull_glasses_log: GlassesFileSyncManager.downloadSingleFile() via WiFi P2P (BT-independent)
  - chat_send: orchestratorClient.sendRequest() -> WS -> orchestrator -> response callback -> AdbResultWriter
  - test_notif: notificationQueue.enqueue() -> TTS playback on glasses
  - alarm_*: AlarmStore CRUD (local SharedPreferences + AlarmManager) -> AdbResultWriter
  - job_*: orchestratorClient.sendJob*() -> WS -> orchestrator -> onJobResult callback -> AdbResultWriter
```

## Chat Testing Suite (AI Conversation via ADB)

The chat testing suite allows Claude (on PC) to have full AI conversations through the phone, simulating what a user would experience speaking through the glasses or typing on the phone -- bypassing only voice input. Conversations are **observable in real-time** on the actual device UIs: glasses chat shows streaming text + TTS, phone chat shows messages in the chat tab.

### Quick Start

```bash
cd AI/clients/phone/test/adb

# Check connection status
./chat.sh status

# Send as glasses (default) -- observable on glasses chat UI + TTS
./chat.sh send "Hello, how are you?"

# Send as phone -- observable on phone chat UI
./chat.sh phone send "Hello, how are you?"

# Explicit glasses
./chat.sh glasses send "Tell me more about that"

# Continue the conversation (same context)
./chat.sh send "Tell me more about that"

# List all conversations
./chat.sh list

# Get current conversation history
./chat.sh get

# Get a specific conversation by ID
./chat.sh get abc123-def456

# Start a new conversation
./chat.sh new
```

### How It Works

**Glasses mode** (`./chat.sh send` or `./chat.sh glasses send`):
```
ADB -> chat_send {device_type: "glasses"}
  -> orchestratorClient.sendRequest(text, deviceType="glasses")
  -> glassesRequestIds.add(requestId)           # wire into glasses flow
  -> phoneBtHost.sendGlassesUserText()           # user message on glasses
  -> setGlassesState(RESPONDING)                 # glasses shows "thinking..."
  -> [orchestrator -> AI -> response]
  -> onStreamingText -> phoneBtHost relay         # real-time text on glasses
  -> onTtsAudio -> phoneBtHost relay              # TTS playback on glasses
  -> onResponse -> phoneBtHost relay + ADB result # final text + ADB file
```

**Phone mode** (`./chat.sh phone send`):
```
ADB -> chat_send {device_type: "phone"}
  -> orchestratorClient.sendRequest(text, deviceType="phone")
  -> broadcastChatMessage(requestId, "USER", text)  # user msg in phone chat
  -> [orchestrator -> AI -> response]
  -> onStreamingText -> ACTION_STREAMING_TEXT         # real-time in phone chat
  -> onResponse -> broadcastChatMessage + ADB result  # final text in phone chat
```

Both modes write the ADB result file for CLI consumption. The `chat_send` command is async: it writes "pending" immediately, sends the request via WebSocket, and the response callback writes the final result. The shell script polls until the result arrives or times out (120s default).

### chat_send Response Format

```json
{
  "command_id": "chat_1234567890",
  "type": "chat_send",
  "status": "success",
  "data": {
    "text": "The AI response text",
    "request_id": "uuid",
    "status": "success",
    "total_tokens": 150,
    "elapsed_ms": 2300,
    "tool_calls": [{"tool_name": "web_search", "status": "success", ...}]
  }
}
```

### Environment Variables

```bash
CHAT_TIMEOUT=120   # Response timeout in seconds (default: 120)
CHAT_DEVICE=glasses  # Device type sent to orchestrator (default: glasses)
```

### Running Chat Tests

```bash
# Individual tests
bash AI/clients/phone/test/adb/test_chat_status.sh   # Status check (no orchestrator needed)
bash AI/clients/phone/test/adb/test_chat_list.sh      # List conversations
bash AI/clients/phone/test/adb/test_chat_get.sh       # Get conversation detail
bash AI/clients/phone/test/adb/test_chat_send.sh      # Send message (needs orchestrator)
bash AI/clients/phone/test/adb/test_chat_flow.sh      # Full e2e flow (needs orchestrator)

# All chat tests
bash AI/clients/phone/test/adb/run_chat_tests.sh
```

### Implementation Details

- **Real-time observability**: `chat_send` wires into the real UI flow. For glasses: adds to `glassesRequestIds`, sends user text via `phoneBtHost`, sets `GlassesAudioState.RESPONDING` -- so `onStreamingText`, `onToolStatus`, and `onTtsAudio` all relay to glasses normally. For phone: broadcasts `ACTION_CHAT_MESSAGE` with role "USER" so the phone chat UI shows it, then `onResponse` broadcasts the assistant message.
- **Async response collection**: `onResponse()` captures the ADB result first, then falls through to the normal glasses relay or phone broadcast (no early return). For phone ADB commands, voice pipeline management (TTS player, wake word, dismiss) is skipped.
- **Thread safety**: `ConcurrentHashMap` for pending commands, `AtomicReference<String>` for streamed text, `CopyOnWriteArrayList` for tool calls. Timeout `Runnable` cancelled on response via `mainHandler.removeCallbacks()`.
- **Conversation continuity**: The orchestrator manages conversation context per device. Successive `chat_send` commands continue the same conversation. Use `chat_new` to start fresh.

## Copilot e2e tests & recordings

The Copilot (formerly "assistant") ambient fact-check feature has a phone-side
instrumented e2e plus a debug ADB hook for driving real AI inference with mocked
speech. The glasses-side overlay e2e and the orchestrator live-AI WS drivers are
documented in their own CLAUDE.md / README (glasses CLAUDE.md "Copilot overlay
e2e"; `AI/orchestrator/test/copilot-e2e/README.md`).

### Phone instrumented e2e -- `CopilotChatLogE2ETest`

`app/src/androidTest/java/com/repository/listener/ui/CopilotChatLogE2ETest.kt`
drives the real UI (ViewPager2.setCurrentItem + UiAutomator text/content-desc
selectors only -- no coordinate taps): switch to the Chats tab, assert the
seeded Copilot row appears, open `CopilotChatActivity`, assert turns rendered,
tap "Share as PDF" and assert a PDF lands in `cacheDir/pdfs/`. Per-step
screenshots (`copilot_list.png`, `copilot_detail.png`, `copilot_share.png`) are
written as artifacts. Fails loudly if the row never appears, detail doesn't
render, or no PDF is produced.

Run (requires explicit user confirmation; install ONLY the `.test` APK):

```bash
.../gradlew -p AI/clients/phone assembleProductionDebugAndroidTest
# install ONLY the instrumentation APK, NOT the app (deploy the app via deploy-to-phone.sh)
adb install -r AI/clients/phone/app/build/outputs/apk/androidTest/production/debug/app-production-debug-androidTest.apk
adb shell am instrument -w \
  -e class com.repository.listener.ui.CopilotChatLogE2ETest \
  com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
```

### `copilot_inject` debug ADB hook (real AI, mocked speech)

`ListenerService.handleAdbCommand()` exposes a DEBUG-only `copilot_inject`
command (active only when `copilotMode` is on). It feeds scripted text into the
SAME copilot speech buffers the Azure recognizers write to, so the normal batch
timer flushes it to the REAL orchestrator and the real pending->resolve card
flow drives the glasses overlay -- AI inference is REAL, only the mic input is
mocked. Used for e2e recordings of the copilot UI.

```bash
adb shell am broadcast \
  -n com.repository.listener/.adb.AdbCommandReceiver \
  -a com.repository.listener.ADB_COMMAND \
  --es type "copilot_inject" --es command_id "ci001" \
  --es params '{"wearer":"...","interlocutor":"..."}'
```

### Glasses-side recording flow

To record the copilot HUD overlay on the glasses while injecting from the phone:
hold the screen awake, `adb shell screenrecord` (180s cap), do NOT SIGINT the
screenrecord process (let it exit so the MP4 finalizes), then bounce the file to
the user's Telegram Saved Messages and quote the shortId. The glasses app must be
deployed via the priv-app overlay slot
(`AI/clients/glasses/scripts/deploy-to-glasses.sh`) -- never `adb install` the app
APK (only the `.test` APK). See glasses
`docs/copilot-card-overlay-e2e.md`.

## TDD Workflow (MANDATORY for ADB features)

When adding or modifying ADB command types:

1. **Write test first**: Add a test script in `test/adb/test_<name>.sh`
2. **Verify it fails**: Run the test, confirm it fails as expected
3. **Implement**: Add the command handler in `ListenerService.handleAdbCommand()`
4. **Verify it passes**: Run the test again, confirm it passes
5. **Run full suite**: `bash test/adb/run_all_tests.sh`

### Running Tests

```bash
# Individual tests (no glasses needed)
bash AI/clients/phone/test/adb/test_adb_command_accepted.sh
bash AI/clients/phone/test/adb/test_unknown_command.sh
bash AI/clients/phone/test/adb/test_record_no_glasses.sh
bash AI/clients/phone/test/adb/test_notif.sh
bash AI/clients/phone/test/adb/test_notif_queue_sequential.sh
bash AI/clients/phone/test/adb/test_notif_single.sh
bash AI/clients/phone/test/adb/test_notif_multi_sender.sh
bash AI/clients/phone/test/adb/test_notif_sender_cap.sh
bash AI/clients/phone/test/adb/test_notif_tts_sender_dedup.sh
bash AI/clients/phone/test/adb/test_notif_tts_sender_reset.sh
bash AI/clients/phone/test/adb/test_streaming.sh
bash AI/clients/phone/test/adb/test_pull_glasses_log.sh

# Chat tests (no glasses needed, orchestrator required for send/flow)
bash AI/clients/phone/test/adb/test_chat_status.sh
bash AI/clients/phone/test/adb/test_chat_list.sh
bash AI/clients/phone/test/adb/test_chat_get.sh
bash AI/clients/phone/test/adb/test_chat_send.sh
bash AI/clients/phone/test/adb/test_chat_flow.sh
bash AI/clients/phone/test/adb/run_chat_tests.sh

# Alarm tests (no glasses or orchestrator needed)
bash AI/clients/phone/test/adb/test_alarm_list.sh
bash AI/clients/phone/test/adb/test_alarm_create.sh
bash AI/clients/phone/test/adb/test_alarm_delete.sh
bash AI/clients/phone/test/adb/test_alarm_create_invalid.sh
bash AI/clients/phone/test/adb/test_alarm_delete_not_found.sh
bash AI/clients/phone/test/adb/test_alarm_flow.sh
bash AI/clients/phone/test/adb/run_alarm_tests.sh

# Job tests (orchestrator required)
bash AI/clients/phone/test/adb/test_job_list.sh
bash AI/clients/phone/test/adb/test_job_create.sh
bash AI/clients/phone/test/adb/test_job_delete.sh
bash AI/clients/phone/test/adb/run_job_tests.sh

# Chat alarm/job E2E tests (orchestrator required, slower)
bash AI/clients/phone/test/adb/test_chat_alarm_flow.sh
bash AI/clients/phone/test/adb/test_chat_job_flow.sh
bash AI/clients/phone/test/adb/test_chat_alarm_edge_cases.sh
bash AI/clients/phone/test/adb/run_chat_alarm_job_tests.sh

# E2E test (glasses required)
bash AI/clients/phone/test/adb/test_ar_recording.sh

# Full suite
bash AI/clients/phone/test/adb/run_all_tests.sh
```
