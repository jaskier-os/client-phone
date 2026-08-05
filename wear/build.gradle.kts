import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Same precedence as :app -- environment variables, then local.properties, then a
// safe default. No secrets committed; see local.properties.example.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String): String =
    System.getenv(key) ?: localProps.getProperty(key) ?: default

android {
    namespace = "com.repository.listener.wear"

    // Per-module compileSdk is fully supported and inert for :app, which stays at
    // 34. The Wear UI artifacts require 35+.
    compileSdk = 36

    defaultConfig {
        // MUST match :app exactly, with no suffix. Google Play services requires
        // an identical applicationId AND an identical signing certificate for Wear
        // Data Layer messages to be delivered between the watch and the phone.
        // When they differ the failure is SILENT: nodes still resolve and
        // sendMessage simply no-ops.
        applicationId = "com.repository.listener"

        minSdk = 30

        // Deliberately 35, not 36. At targetSdk 36 a Wear activity becomes
        // always-on and stays RESUMED in ambient, which changes the lifecycle this
        // design relies on. Moving to 36 later means rewriting the session
        // lifecycle around AmbientLifecycleObserver.
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // No abiFilters: there is no native code here, so it would be a no-op
        // today and a silent hazard later.

        // Shared HMAC key for remote input. Empty by default; supply via env or
        // local.properties (both gitignored). An empty key disables signing and
        // the watch refuses to start a session rather than sending unauthenticated
        // input.
        buildConfigField(
            "String", "REMOTE_INPUT_HMAC_KEY",
            "\"${cfg("REMOTE_INPUT_HMAC_KEY", "")}\"",
        )
    }

    // NO signingConfigs block on purpose. AGP then uses the same
    // ~/.android/debug.keystore that :app's productionDebug is signed with, which
    // is what makes the Data Layer certificate match. Verify with
    // `./gradlew :wear:signingReport` before installing.

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // No product flavors: the `environment` dimension belongs to :app only.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":remote-input-protocol"))

    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    // Wear Compose. 1.6.2 is the latest stable line; 1.7.x is alpha.
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // Tiles.
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    // TileService returns ListenableFuture, so Guava is a direct requirement.
    implementation("com.google.guava:guava:33.3.1-android")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.wear.tiles:tiles-testing:1.6.2")
    testImplementation("org.robolectric:robolectric:4.13")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
