// Root build file for multi-module Brokk project

// Get the version from the latest git tag and current version
fun getVersionFromGit(): String {
    return try {
        // First, try to get exact tag match
        val exactTagProcess = ProcessBuilder("git", "describe", "--tags", "--exact-match", "HEAD")
            .directory(rootDir)
            .start()
        exactTagProcess.waitFor()

        if (exactTagProcess.exitValue() == 0) {
            // On exact tag - clean release version
            exactTagProcess.inputStream.bufferedReader().readText().trim()
        } else {
            // Not on exact tag - get development version
            val devVersionProcess = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty=-SNAPSHOT")
                .directory(rootDir)
                .start()
            devVersionProcess.waitFor()
            devVersionProcess.inputStream.bufferedReader().readText().trim()
        }
    } catch (e: Exception) {
        "0.0.0-UNKNOWN"
    }
}

allprojects {
    group = "io.github.jbellis"
    version = getVersionFromGit()

    repositories {
        mavenCentral()
    }
}

tasks.register("printVersion") {
    description = "Prints the current project version"
    group = "help"
    doLast {
        println(version)
    }
}

subprojects {
    repositories {
        mavenCentral()
        // Additional repositories for dependencies
        maven {
            url = uri("https://repo.gradle.org/gradle/libs-releases")
        }
        maven {
            url = uri("https://www.jetbrains.com/intellij-repository/releases")
        }
    }
}
