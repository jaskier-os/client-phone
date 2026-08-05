plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    // For :remote-input-protocol, a pure JVM library. `apply false` keeps this
    // inert for :app and :navigation.
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    // For :wear (Compose for Wear OS). Kotlin 2.x moved the Compose compiler out
    // of composeOptions into its own plugin. `apply false` keeps it inert for
    // :app and :navigation, neither of which uses Compose.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
