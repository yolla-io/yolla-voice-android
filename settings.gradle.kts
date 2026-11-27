// The example app of the Yolla Voice SDK. It takes the SDK from this repository's maven/ folder, the same artifact
// your app gets from the address in the README.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven { url = rootDir.resolve("maven").toURI() }
    }
}

rootProject.name = "yolla-voice-android"

include(":example")
