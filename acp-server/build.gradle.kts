plugins {
    `java-library`
    application
    alias(libs.plugins.shadow)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

application {
    mainClass.set("ai.brokk.acpserver.BrokkAcpServer")
}

dependencies {
    // Core Brokk dependencies - exclude MCP to avoid conflicts
    implementation(project(":app")) {
        exclude(group = "io.modelcontextprotocol")
    }

    // Jackson for JSON serialization (explicit to ensure available)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.annotations)

    // Logging
    implementation(libs.bundles.logging)

    // Null safety annotations
    implementation(libs.jspecify)
    compileOnly(libs.checker.qual)
    compileOnly("org.jetbrains:annotations:24.1.0")
    testCompileOnly("org.jetbrains:annotations:24.1.0")

    // Testing
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.bundles.junit.runtime)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("brokk-acp-server")
    archiveClassifier.set("")
    mergeServiceFiles()
    isZip64 = true

    // Log4j2 plugin cache merge
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)

    // Exclude signature files
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/MANIFEST.MF")

    // Exclude MCP classes that might have leaked through transitives
    exclude("io/modelcontextprotocol/**")

    manifest {
        attributes["Main-Class"] = "ai.brokk.acpserver.BrokkAcpServer"
    }
}

// Ensure shadowJar is the default artifact
tasks.named("jar") {
    enabled = false
}

tasks.named("build") {
    dependsOn("shadowJar")
}
