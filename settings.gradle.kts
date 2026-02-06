pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

rootProject.name = "brokk"

include("app")
include("errorprone-checks")
include("brokk-code")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
