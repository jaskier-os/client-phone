plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

// Pure java-library on purpose. It has no Android variants, so the `environment`
// flavor dimension declared by :app cannot propagate here and no consumer ever
// needs missingDimensionStrategy. Both :app (Android application, flavored) and
// :wear (Android application, unflavored) depend on it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
