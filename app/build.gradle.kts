import net.ltgt.gradle.errorprone.errorprone
import java.time.Duration
import org.gradle.process.CommandLineArgumentProvider
import org.apache.tools.ant.types.Commandline
import org.gradle.api.GradleException

plugins {
    java
    application
    alias(libs.plugins.errorprone)
    alias(libs.plugins.shadow)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.spotless)
    alias(libs.plugins.javafx)
    alias(libs.plugins.node)
}

group = "ai.brokk"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

application {
    mainClass.set("ai.brokk.Brokk")
    applicationDefaultJvmArgs = buildList {
        // enable feature flags; JavaExec baseline supplies other args
        add("-Dbrokk.architectshell=true")
        add("-Dwatch.service.polling=true")
        // JDK 24+ requires explicit flags for unsafe memory access and native access
        if (java.toolchain.languageVersion.get().asInt() >= 24) {
            add("--sun-misc-unsafe-memory-access=allow")
            add("--enable-native-access=javafx.graphics,javafx.media,javafx.web,ALL-UNNAMED")
        }
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.swing", "javafx.web")
}

node {
    version.set(libs.versions.nodejs.get())
    npmVersion.set("")
    pnpmVersion.set(libs.versions.pnpm.get())
    download.set(true)
    workDir.set(file("${project.rootDir}/.gradle/nodejs"))
    npmWorkDir.set(file("${project.rootDir}/.gradle/npm"))
    pnpmWorkDir.set(file("${project.rootDir}/.gradle/pnpm"))
    nodeProjectDir.set(file("${project.rootDir}/frontend-mop"))
}

tasks.named("pnpmInstall") {
    inputs.file("${project.rootDir}/frontend-mop/package.json")
    inputs.file("${project.rootDir}/frontend-mop/pnpm-lock.yaml")
    outputs.dir("${project.rootDir}/frontend-mop/node_modules")
}

repositories {
    // Use local Maven cache first to minimize network calls
    mavenLocal()

    mavenCentral()
    google()

    // Additional repositories for dependencies
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases")
    }
    maven {
        url = uri("https://www.jetbrains.com/intellij-repository/releases")
    }
}

val verifyNoEmptyJavaSources = tasks.register("verifyNoEmptyJavaSources") {
    group = "verification"
    description =
        "Fails the build if any .java file in the main source set is empty or whitespace-only. " +
        "This complements Error Prone: as a compiler plugin, Error Prone may not be invoked for truly empty sources " +
        "or may not be able to retrieve source text for an effectively-empty compilation unit."

    val sources = sourceSets.main.get().allJava.matching {
        include("**/*.java")
        exclude("**/package-info.java")
    }

    inputs.files(sources)
    outputs.upToDateWhen { true }

    doLast {
        val emptyFiles = sources.files
            .asSequence()
            .filter { it.isFile }
            .filter { file -> file.useLines { lines -> lines.all { it.isBlank() } } }
            .toList()

        if (emptyFiles.isNotEmpty()) {
            val formatted = emptyFiles.joinToString("\n") { file ->
                val rel = file.relativeToOrNull(projectDir)?.path ?: file.path
                " - $rel"
            }
            throw GradleException(
                "Empty or whitespace-only .java files detected. Delete them or add valid Java content:\n$formatted"
            )
        }
    }
}

// Create a resolvable configuration for Error Prone that extends the declarable 'errorprone' configuration
// This is needed for our custom compileJavaErrorProne task
val errorproneCompile by configurations.creating {
    extendsFrom(configurations.getByName("errorprone"))
    isCanBeResolved = true
    isCanBeConsumed = false
}

// Force Jackson version alignment to prevent MCP SDK from pulling in unreleased versions
configurations.all {
    resolutionStrategy {
        force("com.fasterxml.jackson.core:jackson-databind:2.18.3")
        force("com.fasterxml.jackson.core:jackson-core:2.18.3")
        force("com.fasterxml.jackson.core:jackson-annotations:2.18.3")
        force("com.fasterxml.jackson.dataformat:jackson-dataformat-smile:2.18.3")
        force("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
    }
}

dependencies {
    // NullAway - version must match local jar version
    implementation(libs.nullaway)

    implementation(libs.okhttp)

    implementation(libs.jtokkit)

    // Console and logging
    implementation(libs.bundles.logging)

    // Utilities
    implementation(libs.bundles.ui)
    implementation(libs.java.diff.utils)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.smile)
    implementation(libs.jackson.jq)
    implementation(libs.lz4)
    implementation(libs.jspecify)
    implementation(libs.picocli)
    implementation(libs.bundles.jediterm)
    implementation(libs.bundles.apache)
    implementation(libs.bundles.jdkmon)
    implementation(libs.disklrucache)
    implementation(libs.uuid.creator)
    implementation(libs.mcp.sdk)
    implementation(libs.pcollections)
    implementation(libs.caffeine)
    // For JSON serialization interfaces (used by CodeUnit)
    api(libs.jackson.annotations)

    // Markdown and templating
    implementation(libs.bundles.markdown)

    // GitHub API
    implementation(libs.github.api)
    implementation(libs.jsoup)

    // JGit and SSH
    implementation(libs.bundles.git)

    // TreeSitter parsers
    implementation(libs.bundles.treesitter)

    // Eclipse JDT Core for Java parse without classpath
    implementation(libs.eclipse.jdt.core)

    // Java Decompiler
    implementation(libs.java.decompiler)

    // Maven Resolver for dependency import
    implementation(libs.bundles.maven.resolver)

    implementation(libs.checker.util)

    // File watching - native recursive directory watching
    implementation("io.methvin:directory-watcher:0.18.0")

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testImplementation(libs.jupiter.iface)
    testRuntimeOnly(libs.bundles.junit.runtime)

    // Error Prone and NullAway for null safety checking
    "errorprone"(files("libs/error_prone_core-brokk_build-with-dependencies.jar"))
    "errorprone"(libs.nullaway)
    "errorprone"(libs.dataflow.errorprone)
    "errorprone"(project(":errorprone-checks"))
    compileOnly(libs.checker.qual)
    compileOnly(libs.errorprone.annotations)
}

// Force version computation at configuration time
val actualVersion = project.rootProject.version.toString().ifEmpty {
    // Fallback: read from cache file
    val versionCacheFile = File(project.rootDir, "build/version.txt")
    if (versionCacheFile.exists()) {
        val lines = versionCacheFile.readLines()
        if (lines.size >= 2) lines[1] else "0.0.0-UNKNOWN"
    } else {
        "0.0.0-UNKNOWN"
    }
}

buildConfig {
    buildConfigField("String", "version", "\"$actualVersion\"")
    packageName("ai.brokk")
    className("BuildInfo")
}

tasks.register("frontendPatch") {
    dependsOn(tasks.pnpmInstall)

    inputs.dir("${project.rootDir}/frontend-mop/node_modules/svelte-exmarkdown").optional(true)
    outputs.file("${project.rootDir}/frontend-mop/node_modules/svelte-exmarkdown/package.json").optional(true)

    // New: also declare micromark-util-subtokenize index.js as an input/output for patching
    inputs.file("${project.rootDir}/frontend-mop/node_modules/micromark-util-subtokenize/index.js").optional(true)
    outputs.file("${project.rootDir}/frontend-mop/node_modules/micromark-util-subtokenize/index.js").optional(true)
    // Also track package.json to assert the resolved version
    inputs.file("${project.rootDir}/frontend-mop/node_modules/micromark-util-subtokenize/package.json").optional(true)

    doLast {
        // Patch svelte-exmarkdown package.json paths
        val packageJsonFile = file("${project.rootDir}/frontend-mop/node_modules/svelte-exmarkdown/package.json")
        if (packageJsonFile.exists()) {
            var content = packageJsonFile.readText()
            content = content.replace("\"./dist/contexts.d.ts\"", "\"./dist/contexts.svelte.d.ts\"")
            content = content.replace("\"./dist/contexts.js\"", "\"./dist/contexts.svelte.js\"")
            packageJsonFile.writeText(content)
        }

        // Patch micromark-util-subtokenize to avoid identity jumps that cause infinite loop
        val micromarkIndex = file("${project.rootDir}/frontend-mop/node_modules/micromark-util-subtokenize/index.js")
        if (micromarkIndex.exists()) {
            var content = micromarkIndex.readText()

            // 1) Guard while(index in jumps) against identity mapping
            content = content.replace(
                Regex("""while\s*\(\s*index\s+in\s+jumps\s*\)\s*\{\s*index\s*=\s*jumps\s*\[\s*index\s*]\s*;\s*}"""),
                """
                while (index in jumps) {
                  const next = jumps[index];
                  if (next === index) { index++; break; }
                  index = next;
                }
                """.trimIndent()
            )

            // 2) Prevent creating identity jumps when slice.length === 1 in subcontent()
            content = content.replace(
                Regex("""jumps\.push\(\s*\[\s*start\s*,\s*start\s*\+\s*slice\.length\s*-\s*1\s*]\s*\)\s*;"""),
                """
                const end = start + slice.length - 1;
                if (end > start) jumps.push([start, end]);
                """.trimIndent()
            )

            micromarkIndex.writeText(content)
        }

        // ----- Verification: fail fast if patch not applied or wrong version -----
        val micromarkPkg = file("${project.rootDir}/frontend-mop/node_modules/micromark-util-subtokenize/package.json")
        if (!micromarkPkg.exists()) {
            throw GradleException("micromark-util-subtokenize/package.json not found; pnpm install might not have completed.")
        }
        val versionText = micromarkPkg.readText()
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(versionText)?.groupValues?.get(1) ?: "UNKNOWN"
        if (version != "2.1.0") {
            throw GradleException("micromark-util-subtokenize resolved to $version, expected 2.1.0. Check package.json overrides/pnpm overrides.")
        }

        if (!micromarkIndex.exists()) {
            throw GradleException("micromark-util-subtokenize/index.js not found; cannot verify patch.")
        }
        val patched = micromarkIndex.readText()
        val hasLoopGuard = patched.contains("if (next === index) { index++; break; }")
        val hasIdentityGuard = patched.contains("const end = start + slice.length - 1;") &&
                               patched.contains("if (end > start) jumps.push([start, end]);")

        if (!hasLoopGuard || !hasIdentityGuard) {
            throw GradleException(
                "micromark-util-subtokenize patch incomplete:\n" +
                "- loopGuard present: $hasLoopGuard\n" +
                "- identityGuard present: $hasIdentityGuard\n" +
                "Aborting build to prevent worker infinite loop. Please re-run pnpmInstall and try again."
            )
        }
    }
}

tasks.register<com.github.gradle.node.pnpm.task.PnpmTask>("frontendBuild") {
    description = "Build frontend with Vite"
    group = "frontend"
    dependsOn(tasks.pnpmInstall, "frontendPatch")

    args.set(listOf("run", "build"))

    inputs.dir("${project.rootDir}/frontend-mop/src")
    inputs.file("${project.rootDir}/frontend-mop/package.json")
    inputs.file("${project.rootDir}/frontend-mop/vite.config.mjs")
    inputs.file("${project.rootDir}/frontend-mop/vite.worker.config.mjs")
    inputs.file("${project.rootDir}/frontend-mop/tsconfig.json")
    inputs.file("${project.rootDir}/frontend-mop/tsconfig.node.json")
    inputs.file("${project.rootDir}/frontend-mop/index.html")
    inputs.file("${project.rootDir}/frontend-mop/dev.html")

    outputs.dir("${project.projectDir}/src/main/resources/mop-web")
}

tasks.register<Delete>("frontendClean") {
    description = "Clean frontend build artifacts"
    group = "frontend"
    delete("${project.projectDir}/src/main/resources/mop-web")
    delete("${project.rootDir}/frontend-mop/node_modules")
    delete("${project.rootDir}/frontend-mop/.gradle")
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

// Baseline JVM args provider; composes with applicationDefaultJvmArgs and other providers
val baselineJvmArgsProvider = object : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-ea",  // Enable assertions
        "-Dbrokk.devmode=true"
    )
}

val jdwpDebugArgsProvider = object : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        // Only enable debugging when explicitly requested
        val enableDebug = (project.findProperty("enableDebug") as String?)?.toBoolean() ?: false
        if (!enableDebug) {
            return emptyList()
        }

        val port = (project.findProperty("debugPort") as String?) ?: "5005"
        // Use "*" so it works on macOS 13+/JDK 21+ where "address=*:5005" is the recommended form.
        return listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$port")
    }
}

// Configure main source compilation without ErrorProne (fast incremental)
tasks.named<JavaCompile>("compileJava") {
    dependsOn(verifyNoEmptyJavaSources)

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
        "-Werror",  // Treat warnings as errors
        "-Xlint:deprecation,unchecked"  // Combined lint warnings for efficiency
    ))

    // ErrorProne is disabled for regular builds via line 353
    // This configuration block is still needed for the plugin but has no effect when disabled
    options.errorprone {
        // Exclude dev/ and eu/ directories
        excludedPaths = ".*/src/main/java/(dev/|eu/).*"
    }
}

// Separate task for Error Prone analysis (runs during analyze and check tasks)
tasks.register<JavaCompile>("compileJavaErrorProne") {
    group = "verification"
    description = "Compile with Error Prone and NullAway enabled"

    dependsOn(verifyNoEmptyJavaSources)

    // Ensure generated sources (e.g., BuildConfig) exist before compiling
    dependsOn("generateBuildConfig")

    // Use same sources as main compilation
    source = sourceSets.main.get().java
    classpath = sourceSets.main.get().compileClasspath
    destinationDirectory.set(file("${layout.buildDirectory.get()}/classes/java/errorprone"))

    options.isIncremental = false  // Disable incremental for Error Prone
    options.isFork = true
    options.forkOptions.jvmArgs?.addAll(errorProneJvmArgs + listOf(
        "-Xmx2g",  // Increase compiler heap size
        "-XX:+UseG1GC"  // Use G1 GC for compiler
    ))

    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-g:source,lines,vars",
        "-Xmaxerrs", "500",
        "-XDcompilePolicy=simple",
        "--should-stop=ifError=FLOW",
        "-Werror",
        "-Xlint:deprecation,unchecked"
    ))

    // Enable ErrorProne and NullAway
    options.errorprone {
        // Disable specific Error Prone checks
        disable("FutureReturnValueIgnored")
        disable("MissingSummary")
        disable("NonCanonicalType")

        // Exclude dev/ directory from all ErrorProne checks
        excludedPaths = ".*/src/main/java/(dev/|eu/).*"

        // Always enable NullAway in this task
        error("NullAway")
        enable("RedundantNullCheck")
        warn("UnnecessarilyFullyQualified")
        warn("EmptyBlockTag")


        // Core NullAway options
        option("NullAway:AnnotatedPackages", "ai.brokk")
        option("NullAway:ExcludedFieldAnnotations",
               "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll,org.junit.jupiter.api.Test")
        option("NullAway:ExcludedClassAnnotations",
               "org.junit.jupiter.api.extension.ExtendWith,org.junit.jupiter.api.TestInstance")
        option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
        option("NullAway:CheckOptionalEmptiness", "true")
        option("NullAway:KnownInitializers",
               "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll")
        option("NullAway:HandleTestAssertionLibraries", "true")
        option("NullAway:ExcludedPaths", ".*/src/main/java/dev/.*")
        option("RedundantNullCheck:CheckRequireNonNull", "true")
    }
}

// Separate task for applying Error Prone patches in-place
tasks.register<JavaCompile>("fix") {
    group = "verification"
    description = "Apply Error Prone patches in-place"

    dependsOn(verifyNoEmptyJavaSources)
    dependsOn("generateBuildConfig")

    source = sourceSets.main.get().java
    classpath = sourceSets.main.get().compileClasspath
    destinationDirectory.set(file("${layout.buildDirectory.get()}/classes/java/fix"))

    options.isIncremental = false
    options.isFork = true
    options.forkOptions.jvmArgs?.addAll(errorProneJvmArgs + listOf(
        "-Xmx2g",
        "-XX:+UseG1GC"
    ))

    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-g:source,lines,vars",
        "-Xmaxerrs", "500",
        "-XDcompilePolicy=simple",
        "-Xlint:deprecation,unchecked"
    ))

    options.errorprone {
        errorproneArgs.addAll(
            "-XepPatchChecks:UnnecessarilyFullyQualified,MissingOverride,RemoveUnusedImports,RedundantNullCheck,RedundantThrows,OperatorPrecedence,UnnecessaryParentheses,EmptyBlockTag,ClassCanBeStatic",
            "-XepPatchLocation:IN_PLACE"
        )

        disable("FutureReturnValueIgnored")
        disable("MissingSummary")
        disable("NonCanonicalType")

        excludedPaths = ".*/src/main/java/(dev/|eu/).*"

        error("NullAway")
        enable("RedundantNullCheck")
        warn("UnnecessarilyFullyQualified")
        warn("EmptyBlockTag")

        option("NullAway:AnnotatedPackages", "ai.brokk")
        option("NullAway:ExcludedFieldAnnotations",
               "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll,org.junit.jupiter.api.Test")
        option("NullAway:ExcludedClassAnnotations",
               "org.junit.jupiter.api.extension.ExtendWith,org.junit.jupiter.api.TestInstance")
        option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
        option("NullAway:CheckOptionalEmptiness", "true")
        option("NullAway:KnownInitializers",
               "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll")
        option("NullAway:HandleTestAssertionLibraries", "true")
        option("NullAway:ExcludedPaths", ".*/src/main/java/dev/.*")
        option("RedundantNullCheck:CheckRequireNonNull", "true")
    }
}

// Manually wire up Error Prone for tasks that need it
// The Error Prone Gradle plugin doesn't auto-configure lazily-registered custom tasks,
// so we need to explicitly enable it and configure the processor path.
tasks.withType<JavaCompile>().configureEach {
    // Configure annotation processor path for compilation tasks
    // They need ErrorProne JARs on the processor path, but only specific tasks enable the plugin
    if (name == "compileJava" || name == "compileJavaErrorProne" || name == "fix") {
        // Enable ErrorProne plugin for analysis and fixing tasks
        // Regular compileJava disables the plugin but still needs the processor path configured
        options.errorprone.isEnabled = (name == "compileJavaErrorProne" || name == "fix")

        // Add Error Prone JARs to annotation processor path so the compiler can find the plugin
        // This is what the Error Prone Gradle plugin normally does automatically
        options.annotationProcessorPath = files(
            options.annotationProcessorPath ?: files(),
            errorproneCompile
        )
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

tasks.withType<JavaExec>().configureEach {
    // Baseline JVM args provided lazily; composes with applicationDefaultJvmArgs and other plugins
    jvmArgumentProviders.add(baselineJvmArgsProvider)
    jvmArgumentProviders.add(jdwpDebugArgsProvider)
}

// Static analysis task without tests (fast, for git hooks)
tasks.register("analyze") {
    group = "verification"
    description = "Run static analysis (NullAway + spotless) without tests"

    dependsOn("compileJavaErrorProne", "spotlessCheck")
}

// Make check task run ErrorProne compilation, Python linting, and all tests for CI validation
tasks.named("check") {
    dependsOn("compileJavaErrorProne")
    val skipPythonTasks = project.rootProject.hasProperty("skipPython")
    if (!skipPythonTasks) {
        dependsOn(rootProject.tasks.named("brokkCodeRuffCheck"))
        dependsOn(rootProject.tasks.named("pytest"))
    }
}

// Ensure fix runs before other verification and compilation tasks when they run together.
// This prevents the build from using bytecode compiled from pre-fix sources.
tasks.named("compileJava") {
    mustRunAfter("fix")
}
tasks.named("classes") {
    mustRunAfter("fix")
}
tasks.named("compileJavaErrorProne") {
    mustRunAfter("fix")
}
tasks.matching { it.name == "spotlessCheck" || it.name == "test" || it.name == "compileTestJava" }.configureEach {
    mustRunAfter("fix")
}



tasks.withType<Test> {
    useJUnitPlatform {
        if (name == "test") {
            excludeTags("git-integration")
        }
    }

    // Exclude GitRepoTest on Windows when property is set
    if (project.hasProperty("excludeGitRepoTest")) {
        exclude("**/GitRepo*.class")
    }

    // On Windows, use only 1 fork to avoid CI issues; on other platforms use half core count
    // (half b/c spinning up JVMs is also slow so right now this is a good balance; as we add tests we will want to revisit)
    maxParallelForks = if (System.getProperty("os.name").lowercase().contains("windows")) 1 else maxOf(6, Runtime.getRuntime().availableProcessors() / 2)
    forkEvery = 0  // Never fork new JVMs during test execution

    jvmArgs = listOf(
        "-ea",  // Enable assertions
        "-Xmx1G",  // minimum heap size
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

    // Helper function to format exception with full cause chain
    fun formatExceptionWithCauses(e: Throwable?): String {
        if (e == null) return "Unknown error"
        val sb = StringBuilder()
        var current: Throwable? = e
        var isFirst = true
        while (current != null) {
            if (!isFirst) {
                sb.append("\n   Caused by: ")
            } else {
                isFirst = false
            }
            sb.append(current.message ?: current.javaClass.name)
            sb.append("\n")
            current.stackTrace.forEach { frame ->
                sb.append("      at $frame\n")
            }
            current = current.cause
        }
        return sb.toString().trimEnd()
    }

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
            val exceptionDetails = formatExceptionWithCauses(result.exception)
            val output = testOutputs[testKey]?.let { "\n   Output:\n$it" } ?: ""
            failedTests.add("❌ $testKey\n   $exceptionDetails$output")
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

    // System properties for tests (must be present at JVM startup)
    // Compute once outside the provider to ensure stable value for Gradle caching
    val testSandboxRoot = layout.buildDirectory.dir("test-sandbox").get().asFile.absolutePath
    jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> {
            return listOf(
                "-Dbrokk.test.mode=true",
                "-Dbrokk.test.sandbox.root=$testSandboxRoot"
            )
        }
    })
    systemProperty("java.awt.headless", "true")
}

tasks.register<Test>("intTest") {
    description = "Runs integration tests (e.g. git cloning)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("git-integration")
    }
    shouldRunAfter("test")
}

tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Runs the Brokk CLI"
    mainClass.set("ai.brokk.cli.BrokkCli")
    classpath = sourceSets.main.get().runtimeClasspath
    if (project.hasProperty("args")) {
        args(Commandline.translateCommandline(project.property("args") as String).toList())
    }
}

tasks.register<JavaExec>("runHeadlessExecutor") {
    group = "application"
    description = "Runs the Brokk Headless Executor"
    mainClass.set("ai.brokk.executor.HeadlessExecutorMain")
    classpath = sourceSets.main.get().runtimeClasspath

    // Configuration via environment variables:
    // EXEC_ID, LISTEN_ADDR, AUTH_TOKEN, WORKSPACE_DIR, SESSIONS_DIR (optional)
    systemProperty("brokk.devmode", "false")
}

tasks.register<JavaExec>("runHeadlessCli") {
    group = "application"
    description = "Runs the HeadlessExecCli"
    mainClass.set("ai.brokk.tools.HeadlessExecCli")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runSkeletonPrinter") {
    group = "application"
    description = "Runs the SkeletonPrinter tool"
    mainClass.set("ai.brokk.tools.SkeletonPrinter")
    classpath = sourceSets.test.get().runtimeClasspath
    if (project.hasProperty("args")) {
        args(Commandline.translateCommandline(project.property("args") as String).toList())
    }
}

tasks.register<JavaExec>("generateThemeCss") {
    group = "application"
    description = "Generates theme CSS variables from ThemeColors"
    mainClass.set("ai.brokk.tools.GenerateThemeCss")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("${project.rootDir}/frontend-mop/src/styles/theme-colors.generated.scss")
}

tasks.register<JavaExec>("runTreeSitterRepoRunner") {
    group = "application"
    description = "Runs the TreeSitterRepoRunner tool for TreeSitter performance analysis"
    mainClass.set("ai.brokk.tools.TreeSitterRepoRunner")
    classpath = sourceSets.test.get().runtimeClasspath
    // Additional JVM args specific to repository runner; baseline adds -ea and -Dbrokk.devmode=true
    jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> {
            val runnerXmxProp = (project.findProperty("runnerXmx") as String?)?.trim()
            val runnerXmxEnv = System.getenv("RUNNER_XMX")?.trim()
            val runnerXmx = (runnerXmxProp?.takeIf { it.isNotEmpty() }
                ?: runnerXmxEnv?.takeIf { it.isNotEmpty() }
                ?: "8g")
            return listOf(
                "-Xmx$runnerXmx",
                "-XX:+UseZGC",
                "-XX:+UnlockExperimentalVMOptions"
            )
        }
    })
    if (project.hasProperty("args")) {
        args(Commandline.translateCommandline(project.property("args") as String).toList())
    }
}

tasks.register<JavaExec>("runPageRankBenchmark") {
    group = "application"
    description = "Runs the PageRankBenchmark tool"
    mainClass.set("ai.brokk.tools.PageRankBenchmark")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf("-Xmx4g", "-XX:+UseG1GC")
    })
    if (project.hasProperty("args")) {
        args(Commandline.translateCommandline(project.property("args") as String).toList())
    }
}

tasks.register<JavaExec>("runSearchFileContentsBenchmark") {
    group = "application"
    description = "Runs the SearchFileContentsBenchmark tool"
    mainClass.set("ai.brokk.tools.SearchFileContentsBenchmark")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf("-Xmx4g", "-XX:+UseG1GC")
    })
    if (project.hasProperty("args")) {
        args(Commandline.translateCommandline(project.property("args") as String).toList())
    }
}

tasks.register<JavaExec>("runUsageBenchEval") {
    group = "application"
    description = "Runs the UsageBenchEval tool for UsageFinder evaluation"
    mainClass.set("ai.brokk.tools.UsageBenchEval")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf(
            "-Xmx4g",
            "-XX:+UseG1GC",
            "-Dlog4j.configurationFile=log4j2-usages.xml"
        )
    })
    if (project.hasProperty("args")) {
        args(Commandline.translateCommandline(project.property("args") as String).toList())
    }
}

tasks.register<JavaExec>("runUsageResultsExplorer") {
    group = "application"
    description = "Runs the UsageResultsExplorer GUI for browsing UsageBenchEval results"
    mainClass.set("ai.brokk.tools.UsageResultsExplorer")
    classpath = sourceSets.test.get().runtimeClasspath
    if (project.hasProperty("args")) {
        args(Commandline.translateCommandline(project.property("args") as String).toList())
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
        attributes["Main-Class"] = "ai.brokk.Brokk"
    }
}

tasks.named("compileJava") {
    dependsOn("generateBuildConfig")
}

tasks.named("processResources") {
    dependsOn("frontendBuild")
}

tasks.named("clean") {
    dependsOn("frontendClean")
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
              gradle.startParameter.taskNames.any {
                  it.contains("shadowJar") || it.contains("deployMcpShadowJar")
              }
}

// When shadowJar is enabled, disable the regular jar task to avoid creating two JARs
tasks.jar {
    enabled = !tasks.shadowJar.get().enabled
}
