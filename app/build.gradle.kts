import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build-time config resolution. Precedence:
//   1. environment variables (CI-friendly)
//   2. local.properties (gitignored, see local.properties.example)
//   3. safe placeholder defaults supplied at call sites
// No secrets or real hosts are committed; configure them locally.
// NOTE: `Properties` is imported explicitly above rather than referenced as
// `java.util.Properties`. AGP puts a `util` member in the build-script scope
// that shadows the `java.util` package in a fully-qualified reference, which
// makes the Kotlin DSL script fail to compile with "Unresolved reference: util".
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String): String =
    System.getenv(key) ?: localProps.getProperty(key) ?: default

android {
    namespace = "com.repository.listener"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.repository.listener"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // Google Maps API key (optional; map features degrade gracefully if absent).
        manifestPlaceholders["MAPS_API_KEY"] = cfg("GOOGLE_MAPS_API_KEY", "")

        // Optional WebRTC TURN relay. Empty TURN_URL -> STUN-only (public Google STUN).
        buildConfigField("String", "TURN_URL", "\"${cfg("TURN_URL", "")}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${cfg("TURN_USERNAME", "")}\"")
        buildConfigField("String", "TURN_PASSWORD", "\"${cfg("TURN_PASSWORD", "")}\"")

        // Service credentials. Empty by default; supply via env or local.properties
        // (both gitignored). All are also overridable at runtime in the app's
        // settings screens (stored in SharedPreferences via AppConfig).
        buildConfigField("String", "ORCHESTRATOR_API_KEY", "\"${cfg("ORCHESTRATOR_API_KEY", "")}\"")
        buildConfigField("String", "AZURE_SPEECH_KEY", "\"${cfg("AZURE_SPEECH_KEY", "")}\"")
        buildConfigField("String", "LOCATOR_API_KEY", "\"${cfg("LOCATOR_API_KEY", "")}\"")

        // Region-specific ReID tabs ("Phone Numbers" sub-tab + person "Intel" tab).
        // Hidden by default; set ENABLE_REID_RU_TABS=true in local.properties (or env)
        // to enable them on your build.
        buildConfigField("Boolean", "ENABLE_REID_RU_TABS", cfg("ENABLE_REID_RU_TABS", "false"))

        // OSINT-style ReID lookups (person-info / phone-number / sherlock intel)
        // exposed as an assistant tool. Disabled by default; set
        // ENABLE_REID_OSINT=true in local.properties (or env) to enable.
        // Core face re-identification (identify_person) is NOT gated by this.
        buildConfigField("Boolean", "ENABLE_REID_OSINT", cfg("ENABLE_REID_OSINT", "false"))
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            dimension = "environment"
            // Overridable at runtime in-app (SharedPreferences via AppConfig).
            // Placeholder localhost default keeps the build working with no real host committed.
            buildConfigField(
                "String", "DEFAULT_ORCHESTRATOR_URL",
                "\"${cfg("LOCAL_ORCHESTRATOR_URL", "ws://127.0.0.1:10001/ws/device")}\""
            )
            buildConfigField("Boolean", "REQUIRE_BIOMETRIC", "false")
        }
        create("production") {
            dimension = "environment"
            isDefault = true
            buildConfigField(
                "String", "DEFAULT_ORCHESTRATOR_URL",
                "\"${cfg("PRODUCTION_ORCHESTRATOR_URL", "wss://127.0.0.1:8443/ws/device")}\""
            )
            buildConfigField("Boolean", "REQUIRE_BIOMETRIC", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Navigation module
    implementation(project(":navigation"))

    // Silero VAD + openWakeWord via ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Whisper.cpp native library loaded via jniLibs (prebuilt .so files)

    // ExoPlayer for ADTS AAC playback (MediaPlayer can't seek in ADTS)
    implementation("androidx.media3:media3-exoplayer:1.2.1")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // WorkManager (weather widget periodic fetch)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp for WebSocket + HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC for audio streaming
    implementation("io.getstream:stream-webrtc-android:1.3.7")

    // Azure Cognitive Services Speech SDK (translation provider option)
    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.40.0")

    // JSON
    implementation("org.json:json:20231013")

    // Markdown rendering for AI messages
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")

// Biometric
    implementation("androidx.biometric:biometric:1.1.0")

// AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Unit test dependencies (JVM, no Android runtime)
    testImplementation("junit:junit:4.13.2")

    // Instrumented test dependencies
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// Development helper tasks
tasks.register("installDebugAndOpen") {
    group = "install"
    description = "Install debug APK and open app automatically"
    dependsOn("installDebug")
    doLast {
        exec {
            commandLine("adb", "shell", "am", "start", "-n",
                "com.repository.listener/.ui.MainActivity")
        }
        println("Phone app installed and opened")
    }
}

tasks.register("installDebugAndClose") {
    group = "install"
    description = "Install debug APK, open to initialize services, then close"
    dependsOn("installDebug")
    doLast {
        exec {
            commandLine("adb", "shell", "am", "start", "-n",
                "com.repository.listener/.ui.MainActivity")
        }
        println("Services initializing...")
        Thread.sleep(2000)
        exec {
            commandLine("adb", "shell", "am", "force-stop",
                "com.repository.listener")
        }
        println("Phone app installed, services initialized, app closed")
    }
}
