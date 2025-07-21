plugins {
    scala
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

scala {
    zincVersion.set("1.9.6")
}

repositories {
    mavenCentral()
    // Additional repositories for Joern dependencies
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases")
    }
    maven {
        url = uri("https://www.jetbrains.com/intellij-repository/releases")
    }
}

dependencies {
    // API interfaces and supporting classes
    implementation(project(":analyzer-api"))

    // Scala standard library
    implementation(libs.scala.library)

    // Joern dependencies for CPG analysis
    implementation(libs.bundles.joern)

    // Logging (needed by Joern)
    implementation(libs.bundles.logging)

    // Jackson for JSON serialization (used by analyzer interfaces)
    implementation(libs.jackson.databind)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.scalatest)
    testImplementation(project(":app", "default")) // Use default configuration to avoid shadowJar conflicts
}

// Enhanced Scala compiler options
tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf(
        "-print-lines",               // Print source line numbers with messages
        "-encoding", "UTF-8",         // Explicit UTF-8 encoding
        "-language:reflectiveCalls",  // Enable reflective calls language feature
        "-feature",                   // Warn about misused language features
        "-Wunused:imports"            // Warn about unused imports
    )
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Use a single forked JVM for all tests (for TreeSitter native library isolation)
    maxParallelForks = 1
    forkEvery = 0

    jvmArgs = listOf(
        "-ea",  // Enable assertions
        "--add-modules=jdk.incubator.vector",
        "-Dbrokk.test.mode=true",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=./build/test-heap-dumps/"
    )
}

// Handle duplicate files in JAR
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
