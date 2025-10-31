plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(org.gradle.jvm.toolchain.JvmVendorSpec.ADOPTIUM)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Compile against the vendored Error Prone core to guarantee ABI compatibility
    compileOnly(files("${rootProject.projectDir}/app/libs/error_prone_core-brokk_build-with-dependencies.jar"))

    // AutoService for service registration
    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")

    // For @NullMarked package annotations (compileOnly is sufficient)
    compileOnly(libs.jspecify)

    // Test dependencies: Error Prone test helpers and JUnit 4
    testImplementation(files("${rootProject.projectDir}/app/libs/error_prone_core-brokk_build-with-dependencies.jar"))
    testImplementation("com.google.errorprone:error_prone_test_helpers:2.27.1")
    testImplementation("junit:junit:4.13.2")
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
    // Force tests to run on Eclipse Temurin JDK 21 (full JDK with jdk.compiler)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(org.gradle.jvm.toolchain.JvmVendorSpec.ADOPTIUM)
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
