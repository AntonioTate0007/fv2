pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Meta's Wearables Device Access Toolkit (com.meta.wearable:mwdat-*) and
        // Picovoice Porcupine both live on Maven Central — no GitHub token needed.
        mavenCentral()
    }
}

rootProject.name = "Jarvis"
include(":app")
