import net.ltgt.gradle.errorprone.errorprone
import java.time.Duration

plugins {
    java
    application
    alias(libs.plugins.errorprone)
    alias(libs.plugins.shadow)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.spotless)
}

group = "io.github.jbellis"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("io.github.jbellis.brokk.Brokk")
    applicationDefaultJvmArgs = listOf(
        "-ea",  // Enable assertions
        "--add-modules=jdk.incubator.vector",  // Vector API support
        "-Dbrokk.devmode=true"  // Development mode flag
    )
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

dependencies {
    // API interfaces and supporting classes
    implementation(project(":analyzer-api"))

    // Direct implementation dependency on Scala analyzers
    implementation(project(":joern-analyzers"))

    // NullAway - version must match local jar version
    implementation(libs.nullaway)
    implementation(libs.dataflow.errorprone)

    implementation(libs.okhttp)

    implementation(libs.jtokkit)
    implementation(libs.jlama.core)

    // Console and logging
    implementation(libs.bundles.logging)

    // Utilities
    implementation(libs.bundles.ui)
    implementation(libs.java.diff.utils)
    implementation(libs.snakeyaml)
    implementation(libs.jackson.databind)
    implementation(libs.jspecify)
    implementation(libs.picocli)

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
    testCompileOnly(libs.bundles.joern)

    // Error Prone and NullAway for null safety checking
    "errorprone"(files("libs/error_prone_core-brokk_build-with-dependencies.jar"))
    "errorprone"(libs.nullaway)
    "errorprone"(libs.dataflow.errorprone)
    compileOnly(libs.jsr305)
    compileOnly(libs.checker.qual)
}

buildConfig {
    buildConfigField("String", "version", "\"${project.version}\"")
    packageName("io.github.jbellis.brokk")
    className("BuildInfo")
}

// Handle duplicate files in JAR
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

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

// Configure main source compilation with ErrorProne/NullAway
tasks.named<JavaCompile>("compileJava") {
    options.isIncremental = true
    options.isFork = true
    options.forkOptions.jvmArgs?.addAll(errorProneJvmArgs + listOf(
        "-Xmx2g",  // Increase compiler heap size
        "-XX:+UseG1GC"  // Use G1 GC for compiler
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

        // Exclude dev/ directory from all ErrorProne checks
        excludedPaths = ".*/src/main/java/dev/.*"

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
        option("NullAway:ExcludedPaths", ".*/src/main/java/dev/.*")

        // RedundantNullCheck
        enable("RedundantNullCheck")
    }
}

// Configure test compilation without ErrorProne
tasks.named<JavaCompile>("compileTestJava") {
    options.isIncremental = true
    options.isFork = false
    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-g:source,lines,vars",
        "-Xlint:deprecation",
        "-Xlint:unchecked"
    ))

    // Completely disable ErrorProne for test compilation
    options.errorprone.isEnabled = false
}

tasks.withType<JavaExec> {
    jvmArgs = listOf(
        "-ea",  // Enable assertions
        "--add-modules=jdk.incubator.vector",
        "-Dbrokk.devmode=true"
    )
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Use a single forked JVM for all tests (for TreeSitter native library isolation)
    maxParallelForks = 1
    forkEvery = 0  // Never fork new JVMs during test execution

    jvmArgs = listOf(
        "-ea",  // Enable assertions
        "--add-modules=jdk.incubator.vector",
        "-Dbrokk.devmode=true",
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

tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Runs the Brokk CLI"
    mainClass.set("io.github.jbellis.brokk.cli.BrokkCli")
    classpath = sourceSets.main.get().runtimeClasspath
    val jvmArgs = listOf(
        "-ea",
        "-Dbrokk.devmode=true"
    )
    if (project.hasProperty("args")) {
        args((project.property("args") as String).split(" "))
    }
}

tasks.shadowJar {
    archiveBaseName.set("brokk")
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

tasks.named("compileJava") {
    dependsOn("generateBuildConfig")
}

// Disable script and distribution generation since we don't need them
tasks.named("startScripts") {
    enabled = false
}

tasks.named("startShadowScripts") {
    enabled = false
}

tasks.named("distTar") {
    enabled = false
}

tasks.named("distZip") {
    enabled = false
}

tasks.named("shadowDistTar") {
    enabled = false
}

tasks.named("shadowDistZip") {
    enabled = false
}

// Ensure the main jar task runs before shadowJar to establish proper dependencies
tasks.shadowJar {
    dependsOn(tasks.jar)
    mustRunAfter(tasks.jar)
}

// Only run shadowJar when explicitly requested or in CI
tasks.shadowJar {
    enabled = project.hasProperty("enableShadowJar") ||
              System.getenv("CI") == "true" ||
              gradle.startParameter.taskNames.contains("shadowJar")
}
