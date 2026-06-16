# Remove Rokid CXR SDK from phone↔glasses BT stack

**Goal:** Delete `com.rokid.cxr:client-m:1.0.8` + `CxrApi` entirely, AND delete the APK-sideload
subsystem outright (it is the sole consumer of `CxrApi.initWifiHot` hotspot). The failing per-session
GATT-UUID discovery loop ("no rx char") goes away. Reconnect collapses to: bond present → RFCOMM
message relay (fixed MESSAGE_UUID) + map socket + BLE wake. The SINGLE connection truth is the existing
`GlassesReachability` state machine, **not** a new parallel boolean.

Paths relative to `AI/clients/`. Phone pkg `com.repository.listener`, glasses
`com.repository.glasses.listener`. P-A/P-B prerequisites land BEFORE any feature swap; CXR is deleted
last (5b), after 5a proves the new reconnect on device.

**Phase order:** P-A → P-B → 0 → 1 → DEL → 4 → 5a → 5b.

**Blast-radius finding:** `CxrApi.initWifiHot`/`deinitWifiHot` (via `PhoneBtHost.enableHotspot`/
`disableHotspot`/`HotspotInfo`) is consumed ONLY by APK sideload. File sync uses a SEPARATE path
(`OPEN_WIFI`/`WIFI_READY` over `listener_sync`; `WifiDirectJoiner`/`GlassesSyncClient`; glasses
`WifiDirectHost` is group owner). So deleting sideload removes the only `initWifiHot` consumer — no
hotspot migration and no new glasses-side APK installer are needed (old Phases 2+3 are gone).

**Parallelization boundary:** glasses-side work (bt-manager `0x07`; glasses `onCommand` handlers for
`get_glass_info`/`set_time`/`voice_ctrl_off`; boot re-apply) is INDEPENDENT of phone-side and can be
built in parallel. Phone-side BT core (`PhoneBtHost`/`BluetoothHelper`/`ListenerService`) is ONE coupled
unit — sequence its phases.

---

## Phase P-A — Phone-side CH_COMMAND_RESPONSE receiver (CRITICAL, blocks Phase 1)
Phone only **sends** `CH_COMMAND_RESPONSE` (`PhoneBtHost.kt:1829,1839`); zero consumers
(`GlassesRfcommClient.kt` has no handler). Glasses `sendCommandResult(requestId,json)` replies
(`GlassesBtClient.kt:437`, callers `ListenerService.kt:787…`) have nowhere to land. Build the reply bus,
mirroring BLE-ping correlation (`pendingPings`, `PhoneBtHost.kt:88`):
- `PhoneBtHost.kt`: add `pendingCommands = ConcurrentHashMap<String, CompletableDeferred<String>>()` and
  `suspend fun sendDeviceCommand(type, paramsJson, timeoutMs=8000): String?` — registers requestId, sends
  on `CH_DEVICE_COMMAND`, awaits w/ timeout, removes on completion/timeout.
- Extend `RelayListener` in `installCustomCmdListener()` (`PhoneBtHost.kt:~838`): add
  `CH_COMMAND_RESPONSE -> { pendingCommands.remove(requestId)?.complete(paramsJson) }`. It already calls
  `reachability.onProofOfLife()` first — keep that.
- **Device-test:** throwaway `ping_info` round-trip; assert deferred resolves. **Revert:** delete map +
  method + the one `when` branch.

## Phase P-B — bt-manager cold-start relaunch of glasses listener (CRITICAL, blocks deletion)
Glasses `ListenerService` is `START_STICKY` (`glasses ListenerService.kt:3328`) but that won't restart a
force-stopped/OOM-killed process; `BootReceiver` only fires at boot. `CxrApi.openApp` was the ONLY remote
cold-start. Add a wake-driven launch (BLE wake = `bt-manager BleWakeService.kt`, `rxCallback` 85/88, GATT
107):
- `bt-manager BleWakeService.kt`: on NEW code **`WAKE_LAUNCH_LISTENER = 0x07`** (declare in all three
  `BleWakeEvent.kt` mirrors), `startForegroundService(ComponentName("com.repository.glasses.listener",
  "...listener.service.ListenerService"))`. bt-manager is priv-app/system, cross-package start +
  `FLAG_INCLUDE_STOPPED_PACKAGES` allowed (pattern in `BtManagerBridge.kt:271`).
- Phone: `BleWakeNotifyClient.sendLaunchListener()` writes `0x07`; PhoneBtHost calls from
  `requestImmediateReconnect`/`wakeAndReconnect` when RFCOMM connect fails twice.
- **Keep `CxrApi.openApp`** as fallback until proven on device (force-stop → 0x07 → relaunch +
  reconnect). **Revert:** drop 0x07 handler + client method.

## Phase 0 — Single source of truth = GlassesReachability (no parallel boolean)
Do NOT add `isRelayConnected`. `GlassesReachability` (`PhoneBtHost.kt:94-200`) already fuses BLE pong/hello
+ rfcomm connect/disconnect + inbound-frame proof-of-life + debounced confirm-ping (sole writer of
`glassesConnected`). Expose it; re-point each CXR `connectionState` reader (now FEWER — the
`ApkRelayServer`/`ApkSideloadManager` readers are GONE with the deletion):
- Add `val isReachable: Boolean` (`state == Reachable`) + `glassesConnectionPhase():
  NOT_CONNECTED|CONNECTING|CONNECTED` for the HTTP status endpoint.
- Re-point the 3 lambdas `getConnectionState = { …bluetoothHelper.connectionState }`
  (`ListenerService.kt:5806, 9958, 9984`) → `{ phoneBtHost.glassesConnectionPhase() }`. These + PhoneBtHost
  guards are the only remaining readers.
- `connectionStateSince` (`PhoneBtHost.kt:542`, stuck-CONNECTING guard): reachability already debounces, so
  **remove** that guard; replace its `scheduleRetry()` with the Phase 5a watchdog. CXR stays compiled.
- **Verify:** messaging gates correctly with CXR still present.

## Phase 1 — Relay-command replacements (uses P-A bus). Add in parallel, verify, drop CXR call.
Bus = `CH_DEVICE_COMMAND` (args `[type, requestId, paramsJson]`, glasses dispatch `GlassesBtClient.kt:117`
→ `onCommand`, `when(type)` at `ListenerService.kt:5145`, else `:6626`). Each item: phone sender +
**glasses-side branch** (glasses branches are the parallelizable half).
- **`get_glass_info`** — phone `GlassesInfoDialog.kt:64` / `ListenerService.kt:9252`:
  `sendDeviceCommand("get_glass_info", …)`. Glasses: new branch beside `"list_storage"` (`5438`) reads
  `Build.*`/`getprop`, replies `{model,serial,fw,mac,name,battery}`.
- **`set_time`** — replaces `cxr.setGlassTime()` (`ListenerService.kt:9270`). paramsJson `{epochMs,tz}`.
  Glasses branch sets time via `Runtime.exec("sh -c 'date …'")` (root). MUST re-apply on boot — add to
  glasses `BootReceiver`/`onCreate` (not one-shot).
- **`voice_ctrl_off`** — replaces `setVoiceCtrl("0")` (`PhoneBtHost.kt:2243`). Glasses branch drives
  `AssistantSuppressor` + writes local voice-control setting. MUST re-apply on every glasses reboot. (Or
  fold into authoritative `CH_SETTINGS`.)
- **App activate/launch** — `openApp/stopApp/activateWithEnsure` (`PhoneBtHost.kt:1713/1741/1765`,
  `ensureGlassesAppRunning`/`stopGlassesApp` health relaunch): warm → existing `CH_ACTIVATE`; cold → P-B
  `0x07`. Drop `openApp` only in 5b.
- **Verify each:** run new path w/ CXR still wired, diff behavior, then remove the CXR call.

## Phase DEL — Delete APK sideload + hotspot (replaces old Phases 2+3)
No new component. **DELETE files:** `app/.../apk/ApkSideloadManager.kt`, `app/.../apk/ApkRelayServer.kt`
(the `apk/` package empties).
**EDIT (remove sideload refs only):**
- `service/ListenerService.kt`: imports L65-66; fields L692-693 (`apkSideloadManager`,`apkRelayServer`);
  `sideloadingReceiver` L695 + `registerReceiver` L3239-3242 (`ACTION_TOGGLE_SIDELOADING`) + unregister; ADB
  `"sideload_apk"` case L5782-5824; `EXTRA_RELAY_INSTALL_STATUS` L163 + sibling `EXTRA_RELAY_*`/
  `ACTION_APK_RELAY_STATUS` consts; `startSideloading()`/`stopSideloading()` L9951-10020 + callers L9298 +
  `onGlassesDisconnected`; `wifiLock` ~L2726 (sideload-HTTP-only — **verify no other reader** before
  removing).
- `bt/PhoneBtHost.kt`: `HotspotInfo` L457, `hotspotInfo` L465, `enableHotspot()` L468-494,
  `disableHotspot()` L496-501, teardown calls L379 & L2251. **KEEP** L1707 `ensureGlassesAppRunning` /
  L1739 `stopGlassesApp` (health relaunch, not sideload).
- `ui/GlassesSettingsFragment.kt`: `chkSideloading` L45/134/183-186; `chkOpenAfterInstall`/
  `chkOpenCloseAfterInstall` L46/194-208.
- `ui/ConfigFragment.kt`: import L29; `btnViewSideloaderLogs`/`btnShareSideloaderLogs` L208-212.
- `ui/GlassesFragment.kt`: `ACTION_TOGGLE_SIDELOADING` const + emitters.
- `util/LogCollector.kt`: `SIDELOAD_TAGS` L183-184 (`GlassesFileSync` already in `FILESYNC_TAGS`).
- `config/AppConfig.kt`: `KEY_SIDELOAD_ENABLED` L13; `get/setSideloadEnabled` L214;
  `get/setOpenAfterInstall`; `get/setOpenCloseAfterInstall`.
- Layouts: `res/layout/fragment_glasses_settings.xml` (`chkSideloading` L319 + openAfterInstall
  checkboxes); `res/layout/fragment_config_server.xml` (`btnViewSideloaderLogs` L205,
  `btnShareSideloaderLogs` L213).
- ADB: remove `sideload_apk` (confirm no test/adb script references it).
**PRESERVE (file sync — do NOT touch):** `WifiDirectJoiner.kt`, `GlassesSyncClient.kt`,
`OPEN_WIFI`/`WIFI_READY`, `BtProtocol.CH_SYNC="listener_sync"`, `pull_glasses_log`,
`ensureGlassesAppRunning`/`stopGlassesApp`, `BluetoothHelper` `deinitWifiP2P` generic teardown.
- **Device-test:** file sync still works (separate path untouched); messaging unaffected; no dangling
  `wifiLock`/receiver. **Revert:** restore the two deleted files + the edited refs (one commit).

## Phase 4 — Audio
RFCOMM audio (`CH_AUDIO_DATA`/`CH_AUDIO_DATA_INWARD`) already carries glasses audio. **Parity checkpoint:**
confirm the RFCOMM path sets the same **mic scene/config** that `changeAudioSceneId`/`openAudioRecord` set
(not just PCM streaming) — inspect glasses audio-capture init for an equivalent scene set; add if missing.
Only then delete `openAudioRecord/closeAudioRecord/changeAudioSceneId` (`PhoneBtHost.kt:1807-1820`).
- **Verify:** AI voice capture + STT still work with CXR audio gone.

## Phase 5a — Reconnect simplification (CXR STILL COMPILED-IN, own commit)
No CXR deletions here — only reconnect behavior changes, with CXR present-but-unused, so revertible.
- **Feed BLE-down into reachability:** in `setOnConnectionStateCallback` (`PhoneBtHost.kt:~785`) the
  `up==false` branch only logs. Add `else { reachability.onBleLinkDown() }` — new debounced method arming a
  confirm-ping; on fail → `Unreachable`. ("Any BT stack down ⇒ debounced Disconnected.")
- **Bounded watchdog retained (NOT a ping flood):** keep the 5s `reconnectRunnable`
  (`PhoneBtHost.kt:263,636`) calling `startScanning()`, no-op when `rfcommConnected` (early return `:510`);
  `GlassesRfcommClient` connect thread stays "no sleep, no retry-loop" (~240). This self-clearing reconnect
  ATTEMPT (not a per-second ping) re-kicks a failed relay so a missed event is never permanent. Optional
  5→60s backoff in the connect loop.
- **Downgrade/remove the 5-min ticker:** once BLE-down feeds reachability, `reachabilityTickerJob`
  (`:91,718`) is redundant; keep only per-event BLE-up ping (`:~790`) + confirm-ping. Remove OR raise
  interval to a safety floor; document choice.
- **Collapse `startScanning`:** sole path = bonded → `requestImmediateReconnect("bonded")` + map socket +
  arm BLE wake. (CXR `cachedUuid`/`reconnectFromCache`/GATT branches stay present-but-dead; deleted in 5b.)
- **Device-test (the gate):** sleep, unbond/rebond, range-loss/return, and **app force-stop** of the glasses
  listener — UI flips Disconnected on every stack-down and recovers via BLE wake (P-B relaunch) or watchdog.
  **Revert:** this whole commit.

## Phase 5b — DELETE CXR + GATT/UUID machinery (only after 5a proven on device)
- Remove dep `app/build.gradle.kts:141-142`; native load `ListenerApp.kt:20-22`.
- Delete all `CxrApi`/`com.rokid.cxr` imports: PhoneBtHost, BluetoothHelper, ListenerService,
  GlassesInfoDialog, GlassesSettingsActivity/Fragment, ListenerApp. (ApkSideloadManager already gone.)
- Delete `bt/GlassesGattClient.kt` (whole file). Delete `glasses_socket_uuid` (`AppConfig.kt:26,327,330`,
  PhoneBtHost consumers `555,563,623`, GATT branches `423-444,574-594`).
- `BluetoothHelper.kt`: delete `reconnectFromCache(437-489)`, `connectWithFallback(589-665)`,
  `doInitDevice(542-587)`, `connectBluetooth`+GATT (`614,784-865`), `connectionState`/`ConnectionState`
  enum, `connectionStateSince`, `forceConnected`, reflection neuters, `cachedUuid` gate,
  `MAX_CACHED_FAILURES`.
- **`onCustomCmd` promote (`PhoneBtHost.kt:840-853`) survives without `forceConnected`/`connectionState`:**
  it already calls `reachability.onProofOfLife()` — restores app-restart-with-live-socket recovery. Drop the
  `forceConnected(...)` lines; keep `onProofOfLife()`. Confirm on device that restarting the phone app over a
  live RFCOMM socket re-promotes to Reachable via inbound frame.
- Shrink `BluetoothHelper` to just the BLE `WAKE_UUID` scan, OR fold the scan into `BleWakeNotifyClient`.
- **NEVER** delete `forceConnected`/`connectionState` in the same commit that rewrote their replacement —
  that was 5a; this is a separate revertible commit.

## Final end-to-end verification (on device)
Glasses serial `1901092544026001`, phone `65TKQWDIEIL7W8LF` (always `-s`). Deploy phone first (`--prod`),
then glasses, via Recon deploy scripts only. Check: messaging round-trips; reachability after bond w/o
GATT; **UI flips Disconnected on each stack-down (BLE-down + force-stop)**; cold-start relaunch via 0x07;
file sync (separate path, untouched); settings push; glass-info dialog; `set_time` + `voice_ctrl_off`
survive a glasses reboot; audio mic-scene parity. Confirm **no GATT discovery loop** (`logcat` no "no rx
char"), **no battery drain**, **no per-second ping spam**. Location ON for any P2P/file-sync test.
