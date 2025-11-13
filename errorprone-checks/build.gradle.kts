plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Compile against the vendored Error Prone core to guarantee ABI compatibility
    compileOnly(files("${rootProject.projectDir}/app/libs/error_prone_core-brokk_build-with-dependencies.jar"))

    // AutoService for service registration
    compileOnly(libs.auto.service.annotations)
    annotationProcessor(libs.auto.service)

    // For @NullMarked package annotations (compileOnly is sufficient)
    compileOnly(libs.jspecify)

    // Test dependencies: Error Prone test helpers and JUnit 5
    testImplementation(files("${rootProject.projectDir}/app/libs/error_prone_core-brokk_build-with-dependencies.jar"))
    testImplementation(libs.errorprone.test.helpers)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testImplementation(libs.jupiter.iface)
    testRuntimeOnly(libs.bundles.junit.runtime)
}

tasks.withType<JavaCompile>().configureEach {
    options.isIncremental = true
    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-Xlint:deprecation,unchecked",
        // Allow compilation against non-exported javac internals used by Error Prone utilities
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    ))
}

tasks.withType<Test>().configureEach {
    // Use JUnit 5 platform for test discovery
    useJUnitPlatform()

    // Do not fail the build if no tests are discovered when running aggregate test tasks
    failOnNoDiscoveredTests = false

    // Force tests to run on Eclipse Temurin JDK 21 (full JDK with jdk.compiler)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Keep assertions enabled and export javac internals for Error Prone test harness
    jvmArgs(
        "-ea",
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

    // Surface more details if failures occur
    systemProperty("errorprone.test.debug", "true")

    // Improve visibility of failures to aid diagnosis
    testLogging {
        events("failed", "skipped", "passed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }
}
