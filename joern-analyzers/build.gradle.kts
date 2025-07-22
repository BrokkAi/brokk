plugins {
    scala
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

scala {
    zincVersion.set("1.10.4")
}

tasks.compileScala {
  // this is what is set by default but adding seems to improve incremental compilation
  destinationDirectory = file("$buildDir/classes/scala/main")
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

    // Allow caching and trust Gradle's incremental compilation
    outputs.cacheIf { true }

    // Use Gradle's built-in incremental compilation
    options.isIncremental = true

    // Ensure task only runs when actually needed by providing clear inputs/outputs
    inputs.files(source)
    outputs.dir(destinationDirectory)
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

    // Test execution settings
    testLogging {
        events("passed", "skipped")  // Only show passed/skipped during execution
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = false
        showCauses = false
        showStackTraces = false
        showStandardStreams = false
    }
}

// Handle duplicate files in JAR
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
