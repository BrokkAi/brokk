import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotless)
}

group = "ai.brokk"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

// Force Jackson version alignment (same as app and brokk-core)
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
    // TreeSitter parsers (native libs)
    api(project(":treesitter-provider"))

    // Eclipse JDT Core for Java parsing without classpath
    implementation(libs.eclipse.jdt.core)

    // Caching
    implementation(libs.caffeine)
    implementation(libs.disklrucache)

    // Serialization
    implementation(libs.jackson.databind)
    implementation(libs.jackson.smile)
    api(libs.jackson.annotations)
    implementation(libs.lz4)

    // Immutable collections
    implementation(libs.pcollections)

    // Guava (used by some analyzers for Splitter, etc.)
    implementation("com.google.guava:guava:32.0.1-jre")

    // Logging
    api(libs.bundles.logging)

    // Annotations
    api(libs.jspecify)
    api(libs.jetbrains.annotations)
    compileOnly(libs.checker.qual)
    compileOnly(libs.errorprone.annotations)

    // Git
    api(libs.bundles.git)

    // Diff utilities
    implementation(libs.java.diff.utils)

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
        disable("MissingSummary")
        disable("FutureReturnValueIgnored")
        disable("NonCanonicalType")
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.isEnabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
    filter {
        isFailOnNoMatchingTests = false
    }
}
