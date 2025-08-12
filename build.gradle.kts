// Get the version from the latest git tag and current version
fun getVersionFromGit(): String {
    return try {
        // First, try to get exact tag match with version pattern
        val exactTagProcess = ProcessBuilder("git", "describe", "--tags", "--exact-match", "--match", "[0-9]*", "HEAD")
            .directory(rootDir)
            .start()
        exactTagProcess.waitFor()

        if (exactTagProcess.exitValue() == 0) {
            // On exact tag - clean release version
            exactTagProcess.inputStream.bufferedReader().readText().trim()
        } else {
            // Not on exact tag - get development version with version tags only
            val devVersionProcess = ProcessBuilder("git", "describe", "--tags", "--always", "--match", "[0-9]*", "--dirty=-SNAPSHOT")
                .directory(rootDir)
                .start()
            devVersionProcess.waitFor()
            devVersionProcess.inputStream.bufferedReader().readText().trim()
        }
    } catch (e: Exception) {
        "0.0.0-UNKNOWN"
    }
}

plugins {
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.spotless)
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                // the objective is to get this to fail, i.e. don't allow failing dependencies
                severity("warn")
            }
        }
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

tasks.register("installGitHooks") {
    description = "Installs Git hooks from .githooks/ into .git/hooks/"
    group = "build setup"

    val hooksSrcDir = file("$rootDir/.githooks")
    val gitHooksDir = file("$rootDir/.git/hooks")

    inputs.dir(hooksSrcDir)
    outputs.dir(gitHooksDir)

    doLast {
        if (!gitHooksDir.exists()) return@doLast
        hooksSrcDir.listFiles()?.forEach { src ->
            val dest = File(gitHooksDir, src.name)
            src.copyTo(dest, overwrite = true)
            dest.setExecutable(true)
        }
    }
}

/* Ensure hooks are present whenever we build the project locally or in CI */
tasks.named("build") {
    dependsOn("installGitHooks")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.autonomousapps.dependency-analysis")
    apply(plugin = "com.diffplug.spotless")

    // Spotless formatting rules
    spotless {
        java {
            // Format all Java sources, excluding generated or build outputs
            target("src/**/*.java")
            targetExclude("**/build/**", "**/test/resources/**", "**/generated/**")
            // Use Palantir Java Format (opinionated formatter similar to Google Java Format,
            // but with improved blank-line and lambda indentation handling)
            palantirJavaFormat(libs.versions.palantirJavaFormat.get())
                .formatJavadoc(true)
        }
    }

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


    tasks.withType<Test> {
        filter {
            isFailOnNoMatchingTests = false
        }
    }
}
