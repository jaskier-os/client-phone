import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

android {
    namespace = "com.repository.navigation"
    compileSdk = 34

    defaultConfig {
        minSdk = 29

        // Optional map API keys. Read from env vars first, then gitignored
        // local.properties, else empty (map features degrade gracefully).
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun cfg(key: String): String = System.getenv(key) ?: localProps.getProperty(key, "")
        buildConfigField("String", "MAPKIT_API_KEY", "\"${cfg("MAPKIT_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${cfg("GOOGLE_MAPS_API_KEY")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Yandex MapKit (api so :app can use Point, etc.)
    api("com.yandex.android:maps.mobile:4.33.1-full")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Google Maps Android SDK (interactive map for the Google provider)
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // OkHttp (Google REST/Static Maps HTTP calls)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Testing
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath: android.jar ships stubbed
    // org.json methods that return null under unitTests.isReturnDefaultValues,
    // which would make JSON-fixture parsing tests silently see empty objects.
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
