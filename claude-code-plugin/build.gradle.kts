plugins {
    java
    application
    alias(libs.plugins.shadow)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

dependencies {
    implementation(project(":app"))

    // MCP SDK
    implementation(libs.mcp.sdk)

    // Logging
    implementation(libs.bundles.logging)

    // Jackson (for MCP JSON handling)
    implementation(libs.jackson.databind)

    // Annotations
    compileOnly(libs.jspecify)
    compileOnly(libs.jetbrains.annotations)
}

application {
    mainClass.set("ai.brokk.claudecode.BrokkMcpServer")
}

// Ensure the :app jar is built even when app's own shadowJar would disable it.
// app/build.gradle.kts disables jar when any task name contains "shadowJar",
// which would include our :claude-code-plugin:shadowJar.
gradle.taskGraph.whenReady {
    val appJar = project(":app").tasks.findByName("jar")
    if (appJar != null && hasTask(tasks.shadowJar.get())) {
        appJar.enabled = true
    }
}

tasks.shadowJar {
    archiveBaseName.set("claude-code-plugin")
    archiveClassifier.set("all")
    mergeServiceFiles()
    isZip64 = true

    manifest {
        attributes["Main-Class"] = "ai.brokk.claudecode.BrokkMcpServer"
    }

    // Exclude signature files
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}
