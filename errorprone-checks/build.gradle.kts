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

    // ASM for bytecode analysis (call graph analyzer)
    implementation("org.ow2.asm:asm:9.7")

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

// Task to analyze synchronized method calls using bytecode analysis
tasks.register<JavaExec>("analyzeSynchronizedCalls") {
    group = "verification"
    description = "Analyze bytecode to find all methods that call synchronized methods (transitively)"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.brokk.errorprone.SynchronizedCallGraphAnalyzer")

    // Analyze app's compiled classes
    args(
        "${rootProject.projectDir}/app/build/classes/java/main",
        "${rootProject.projectDir}/app/build/classes/java/errorprone"
    )

    // Also analyze dependencies if needed (optional, can be slow)
    // args += configurations.runtimeClasspath.get().files.map { it.absolutePath }

    doFirst {
        val outputDir = file("${rootProject.layout.buildDirectory.get().asFile}/edt-analysis")
        outputDir.mkdirs()
        println("Analyzing synchronized method calls...")
        println("Output will be written to: ${outputDir}/synchronized-methods.txt")
    }

    workingDir = file("${rootProject.layout.buildDirectory.get().asFile}/edt-analysis")
}

// Task to generate EDT violations report
tasks.register<JavaExec>("generateEdtReport") {
    group = "verification"
    description = "Generate report of EDT methods that call synchronized methods"

    dependsOn("analyzeSynchronizedCalls")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.brokk.errorprone.EdtSynchronizedReportGenerator")

    val edtAnalysisDir = file("${rootProject.layout.buildDirectory.get().asFile}/edt-analysis")

    args(
        "${rootProject.projectDir}/app/build/classes/java/main",
        "${edtAnalysisDir}/synchronized-methods.txt"
    )

    doFirst {
        println("Generating EDT violations report...")
        println("Output will be written to: ${edtAnalysisDir}/edt-synchronized-violations.txt")
    }

    workingDir = edtAnalysisDir
}
