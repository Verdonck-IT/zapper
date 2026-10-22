pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Haalt automatisch een JDK 17 binnen als er lokaal geen gevonden wordt (jvmToolchain(17)).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "zapper"

include(":provider-api")
include(":plugin-testkit")
include(":app")
include(":plugins:vrtmax")
include(":plugins:play")
