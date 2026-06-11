# client-phone

> **Docs & wiki:** [github.com/jaskier-os/docs/wiki](https://github.com/jaskier-os/docs/wiki)

Companion phone app (Android, Kotlin) for the glasses + phone assistant. It
pairs with the glasses over Bluetooth, talks to a backend orchestrator over
WebSocket, and handles chat, notifications, photo attachments, navigation, and
audio streaming (WebRTC). Two Gradle modules: `app` and `navigation` (Yandex
MapKit).

## Build

Two flavors on the `environment` dimension: `local` and `production`
(production is the default). We ship production debug:

```
./gradlew :app:assembleProductionDebug
```

Local flavor for dev against a localhost orchestrator:

```
./gradlew :app:assembleLocalDebug
```

Built with JDK 17 (source/target 17).

## Configuration

Build-time config comes from `local.properties` or env vars of the same name
(env wins). Copy `local.properties.example` to `local.properties` and fill in
what you need. `sdk.dir` is the only required value; everything else has a safe
placeholder default and most can be overridden at runtime in the app's
server-config screen.

Keys that matter:

- `sdk.dir` -- Android SDK path (required).
- `LOCAL_ORCHESTRATOR_URL` / `PRODUCTION_ORCHESTRATOR_URL` -- default WS
  endpoints per flavor.
- `GOOGLE_MAPS_API_KEY`, `MAPKIT_API_KEY` -- maps (optional, degrade
  gracefully).
- `TURN_URL` / `TURN_USERNAME` / `TURN_PASSWORD` -- WebRTC TURN relay;
  empty falls back to public STUN.

See `local.properties.example` for the full list and comments.

## Dependencies

- JDK 17, Android SDK 34, NDK (`arm64-v8a`).
- onnxruntime-android 1.20.0 plus the speaker/VAD ONNX models committed in
  `app/src/main/assets` (`embedding_model.onnx`, `melspectrogram.onnx`,
  `sireneviy.onnx`, `silero_vad.onnx`).
- Extra Maven repos (Rokid, Yandex, alphacephei) in `settings.gradle.kts`.
- The `release` build currently signs with the debug key; there is no release
  keystore wired in this repo.
