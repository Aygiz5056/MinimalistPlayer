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
        mavenCentral()
        // Для библиотек с GitHub (DroidSpeech)
        maven {
            setUrl("https://jitpack.io")
        }
    }
}

rootProject.name = "MinimalistPlayer"
include(":app")