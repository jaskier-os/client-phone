pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven("https://alphacephei.com/maven/")
        maven("https://maven.rokid.com/repository/maven-public/")
        maven("https://maven.yandex.ru/releases/")
    }
}

rootProject.name = "RepositoryListener"
include(":app")
include(":navigation")
// Frozen remote-input wire contract shared by :app and :wear. A pure java-library
// has no Android variants, so the `environment` flavor dimension declared by :app
// cannot propagate into it and no consumer needs missingDimensionStrategy.
include(":remote-input-protocol")
