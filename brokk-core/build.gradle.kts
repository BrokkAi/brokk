import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    alias(libs.plugins.errorprone)
    alias(libs.plugins.shadow)
    alias(libs.plugins.spotless)
}

group = "ai.brokk"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

val coreMcpJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "-Djava.awt.headless=true"
)

// Force Jackson version alignment (same as app)
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
    // Shared analyzer, git, concurrent, and utility code
    api(project(":brokk-shared"))

    // TreeSitter parsers (native libs)
    implementation(project(":treesitter-provider"))

    // Eclipse JDT Core for Java parsing without classpath
    implementation(libs.eclipse.jdt.core)

    // Caching
    implementation(libs.caffeine)
    implementation(libs.disklrucache)

    // Serialization
    implementation(libs.jackson.databind)
    implementation(libs.jackson.smile)
    implementation(libs.jackson.jq)
    implementation(libs.jtokkit)
    api(libs.jackson.annotations)
    implementation(libs.lz4)

    // Immutable collections
    implementation(libs.pcollections)

    // Guava (used by some analyzers for Splitter, etc.)
    implementation("com.google.guava:guava:32.0.1-jre")

    // Logging
    implementation(libs.bundles.logging)

    // Annotations
    implementation(libs.jspecify)
    compileOnly(libs.checker.qual)
    compileOnly(libs.errorprone.annotations)

    // Git
    implementation(libs.bundles.git)

    // Diff utilities
    implementation(libs.java.diff.utils)

    // Markdown
    implementation(libs.bundles.markdown)

    // MCP SDK
    implementation(libs.mcp.sdk)

    // File watching
    implementation("io.methvin:directory-watcher:0.18.0")

    // NullAway
    implementation(libs.nullaway)

    // Error Prone
    "errorprone"(files("${rootDir}/app/libs/error_prone_core-brokk_build-with-dependencies.jar"))
    "errorprone"(libs.nullaway)
    "errorprone"(libs.dataflow.errorprone)
    "errorprone"(project(":errorprone-checks"))

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testImplementation(libs.jupiter.iface)
    testRuntimeOnly(libs.bundles.junit.runtime)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all,-processing,-serial,-try,-classfile,-rawtypes,-this-escape",
        "-Werror",
    ))
    options.errorprone {
        error("NullAway")
        option("NullAway:AnnotatedPackages", "ai.brokk")
        // Disable checks that trigger on pre-existing code copied from app
        disable("MissingSummary")
        disable("FutureReturnValueIgnored")
        disable("NonCanonicalType")
    }
}

// Disable ErrorProne for test compilation (same as app module)
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.isEnabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
    filter {
        isFailOnNoMatchingTests = false
    }
}

tasks.register<JavaExec>("runCoreMcp") {
    group = "application"
    description = "Runs the Brokk Core MCP server"
    mainClass.set("ai.brokk.mcpserver.BrokkCoreMcpServer")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs(coreMcpJvmArgs)
}

tasks.shadowJar {
    archiveBaseName.set("brokk-core")
    archiveClassifier.set("")
    mergeServiceFiles()
    isZip64 = true

    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")

    dependsOn(tasks.jar)
    mustRunAfter(tasks.jar)
}

// Only run shadowJar when explicitly requested or in CI
tasks.shadowJar {
    enabled = project.hasProperty("enableShadowJar") ||
              System.getenv("CI") == "true" ||
              gradle.startParameter.taskNames.any {
                  it.contains("shadowJar") || it.contains("deployCoreMcpShadowJar")
              }
}

tasks.jar {
    enabled = !tasks.shadowJar.get().enabled
}
