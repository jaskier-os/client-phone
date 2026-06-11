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
