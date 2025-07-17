import net.ltgt.gradle.errorprone.errorprone
import java.time.Duration
import java.io.ByteArrayOutputStream

plugins {
    java
    scala
    application
    alias(libs.plugins.errorprone)
    alias(libs.plugins.shadow)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.spotless)
}

group = "io.github.jbellis"
version = getVersionFromGit()
description = "Brokk - Semantic code assistant with agent-based architecture"

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

// Common JVM arguments used across tasks
val commonJvmArgs = listOf(
    "-ea",  // Enable assertions
    "--add-modules=jdk.incubator.vector",  // Vector API support
    "-Dbrokk.devmode=true"  // Development mode flag
)

// Common ErrorProne JVM exports for JDK 16+
val errorProneJvmArgs = listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("io.github.jbellis.brokk.Brokk")
    applicationDefaultJvmArgs = commonJvmArgs
}

dependencies {
    implementation(libs.scala.library)

    // NullAway - version must match local jar version
    implementation(libs.nullaway)

    // LangChain4j dependencies
    implementation(libs.okhttp)
    implementation(libs.opennlp)
    implementation(libs.jtokkit)

    implementation(libs.jlama.core)

    // Console and logging
    implementation(libs.bundles.logging)

    // Joern dependencies
    implementation(libs.bundles.joern)

    // Utilities
    implementation(libs.bundles.ui)
    implementation(libs.java.diff.utils)
    implementation(libs.snakeyaml)
    implementation(libs.jackson.databind)
    implementation(libs.jspecify)

    // Markdown and templating
    implementation(libs.bundles.markdown)

    // GitHub API
    implementation(libs.github.api)
    implementation(libs.jsoup)

    // JGit and SSH
    implementation(libs.bundles.git)

    // TreeSitter parsers
    implementation(libs.bundles.treesitter)

    // Java Decompiler
    implementation(libs.java.decompiler)

    implementation(libs.checker.util)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testImplementation("com.github.sbt.junit:jupiter-interface:0.13.3")
    testRuntimeOnly(libs.bundles.junit.runtime)
    testImplementation(libs.bundles.scalatest)

    // Error Prone and NullAway for null safety checking
    "errorprone"(libs.errorprone.core)
    "errorprone"(libs.nullaway)
    compileOnly(libs.jsr305)
    compileOnly(libs.checker.qual)
}

buildConfig {
    buildConfigField("String", "version", "\"${project.version}\"")
    packageName("io.github.jbellis.brokk")
    className("BuildInfo")
}

spotless {
    scala {
        target("src/**/*.scala")
        scalafmt("3.8.1").configFile(".scalafmt.conf")
    }
}

sourceSets {
    main {
        scala {
            setSrcDirs(listOf("src/main/scala", "src/main/java", "build/generated/sources/buildConfig/main"))
        }
        java {
            setSrcDirs(emptyList<String>())
        }
    }
    test {
        scala {
            setSrcDirs(listOf("src/test/scala", "src/test/java"))
        }
        java {
            setSrcDirs(emptyList<String>())
        }
    }
}

// Handle duplicate files in JAR
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Enable incremental compilation for better performance
tasks.withType<JavaCompile> {
    options.isIncremental = true

    if (name == "compileJava") {
        // Force forked compilation with advanced JVM options
        options.isFork = true
        options.forkOptions.jvmArgs?.addAll(errorProneJvmArgs + listOf(
            "-J-Xmx2g",  // Increase compiler heap size
            "-J-XX:+UseG1GC"  // Use G1 GC for compiler
        ))

        // Optimized javac options for performance
        options.compilerArgs.addAll(listOf(
            "-parameters",  // Preserve method parameter names
            "-g:source,lines,vars",  // Generate full debugging information
            "-Xmaxerrs", "500",  // Maximum error count
            "-XDcompilePolicy=simple",  // Error Prone compilation policy
            "--should-stop=ifError=FLOW",  // Stop compilation policy
            "-Werror",  // Treat warnings as errors
            "-Xlint:deprecation,unchecked"  // Combined lint warnings for efficiency
        ))

        // Enhanced ErrorProne configuration with NullAway
        options.errorprone {
            // Disable specific Error Prone checks that are handled in SBT config
            disable("FutureReturnValueIgnored")
            disable("MissingSummary")
            disable("EmptyBlockTag")
            disable("NonCanonicalType")

            // Enable NullAway with comprehensive configuration
            error("NullAway")
            warn("RedundantNullCheck")

            // Core NullAway options
            option("NullAway:AnnotatedPackages", "io.github.jbellis.brokk")
            option("NullAway:ExcludedFieldAnnotations",
                   "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll,org.junit.jupiter.api.Test")
            option("NullAway:ExcludedClassAnnotations",
                   "org.junit.jupiter.api.extension.ExtendWith,org.junit.jupiter.api.TestInstance")
            option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
            option("NullAway:JarInferStrictMode", "true")
            option("NullAway:CheckOptionalEmptiness", "true")
            option("NullAway:KnownInitializers",
                   "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll")
            option("NullAway:HandleTestAssertionLibraries", "true")
        }
    } else if (name == "compileTestJava") {
        // Test compilation should not use ErrorProne
        options.compilerArgs.addAll(listOf(
            "-parameters",
            "-g:source,lines,vars",
            "-Xlint:deprecation",
            "-Xlint:unchecked"
        ))
    }
}

// Enhanced Scala compiler options
tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf(
        "-Xfatal-warnings",           // Treat warnings as errors (strict quality)
        "-print-lines",               // Print source line numbers with messages
        "-encoding", "UTF-8",         // Explicit UTF-8 encoding
        "-language:reflectiveCalls",  // Enable reflective calls language feature
        "-feature",                   // Warn about misused language features
        "-Wunused:imports"            // Warn about unused imports
    )
}

tasks.withType<JavaExec> {
    jvmArgs = commonJvmArgs
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Use a single forked JVM for all tests (for TreeSitter native library isolation)
    maxParallelForks = 1
    forkEvery = 0  // Never fork new JVMs during test execution

    jvmArgs = commonJvmArgs + listOf(
        // Additional test-specific JVM arguments
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

    // Collect failed tests and their output for end summary
    val failedTests = mutableListOf<String>()
    val testOutputs = mutableMapOf<String, String>()

    // Capture test output for failed tests
    addTestOutputListener(object : TestOutputListener {
        override fun onOutput(testDescriptor: TestDescriptor, outputEvent: TestOutputEvent) {
            val testKey = "${testDescriptor.className}.${testDescriptor.name}"
            if (outputEvent.destination == TestOutputEvent.Destination.StdOut || 
                outputEvent.destination == TestOutputEvent.Destination.StdErr) {
                testOutputs.merge(testKey, outputEvent.message) { existing, new -> existing + new }
            }
        }
    })

    // Capture individual test failures for later reporting
    afterTest(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (result.resultType == TestResult.ResultType.FAILURE) {
            val testKey = "${desc.className}.${desc.name}"
            val errorMessage = result.exception?.message ?: "Unknown error"
            val stackTrace = result.exception?.stackTrace?.joinToString("\n") { "      at $it" } ?: ""
            val output = testOutputs[testKey]?.let { "\n   Output:\n$it" } ?: ""
            failedTests.add("❌ $testKey\n   Error: $errorMessage\n$stackTrace$output")
        }
    }))

    // Show all failures grouped at the end
    afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (desc.parent == null) { // Only execute once for the root suite
            if (result.failedTestCount > 0) {
                println("\n" + "=".repeat(80))
                println("FAILED TESTS SUMMARY")
                println("=".repeat(80))
                failedTests.forEach { failure ->
                    println("\n$failure")
                }
                println("\n" + "=".repeat(80))
                println("Total tests: ${result.testCount}")
                println("Passed: ${result.successfulTestCount}")
                println("Failed: ${result.failedTestCount}")
                println("Skipped: ${result.skippedTestCount}")
                println("=".repeat(80))
            }
        }
    }))

    // Fail fast on first test failure
    failFast = false

    // Test timeout
    timeout.set(Duration.ofMinutes(30))

    // System properties for tests
    systemProperty("brokk.test.mode", "true")
    systemProperty("java.awt.headless", "true")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    isZip64 = true  // Enable zip64 for large archives

    // Assembly merge strategy equivalent
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)

    // Exclude signature files
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/MANIFEST.MF")

    manifest {
        attributes["Main-Class"] = "io.github.jbellis.brokk.Brokk"
    }
}

tasks.named("compileScala") {
    dependsOn("generateBuildConfig")
}

// Disable distribution tasks by default - only use shadowJar for distribution
tasks.named("distZip") {
    enabled = false
}

tasks.named("distTar") {
    enabled = false
}

tasks.named("startScripts") {
    enabled = false
}

tasks.named("startShadowScripts") {
    enabled = false
}

tasks.named("shadowDistTar") {
    enabled = false
}

tasks.named("shadowDistZip") {
    enabled = false
}

// Only run shadowJar when explicitly requested or in CI
tasks.shadowJar {
    enabled = project.hasProperty("enableShadowJar") ||
              System.getenv("CI") == "true" ||
              gradle.startParameter.taskNames.contains("shadowJar")
}

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


