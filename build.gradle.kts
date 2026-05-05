import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// Get the version from the latest git tag and current version with caching
fun getVersionFromGit(): String {
    val versionCacheFile = File(rootDir, "build/version.txt")

    try {
        // Get current git HEAD (works for both regular repos and worktrees)
        val currentGitHead = getCurrentGitHead()

        // Check if we can use cached version
        if (versionCacheFile.exists() && currentGitHead != null) {
            val cacheLines = versionCacheFile.readLines()
            if (cacheLines.size >= 2) {
                val cachedGitHead = cacheLines[0]
                val cachedVersion = cacheLines[1]

                // If git HEAD hasn't changed, use cached version
                if (cachedGitHead == currentGitHead) {
                    return cachedVersion
                }
            }
        }

        // Calculate version from git
        val version = calculateVersionFromGit()

        // Cache the result
        if (currentGitHead != null) {
            versionCacheFile.parentFile.mkdirs()
            versionCacheFile.writeText("$currentGitHead\n$version\n")
        }

        return version
    } catch (e: Exception) {
        return "0.0.0-UNKNOWN"
    }
}

fun getCurrentGitHead(): String? {
    return try {
        val gitHeadProcess = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(rootDir)
            .start()
        gitHeadProcess.waitFor()
        if (gitHeadProcess.exitValue() == 0) {
            gitHeadProcess.inputStream.bufferedReader().readText().trim()
        } else null
    } catch (e: Exception) {
        null
    }
}

fun calculateVersionFromGit(): String {
    return try {
        // First, try to get exact tag match with version pattern
        val exactTagProcess = ProcessBuilder("git", "describe", "--tags", "--exact-match", "--match", "[0-9]*", "HEAD")
            .directory(rootDir)
            .start()
        exactTagProcess.waitFor()

        if (exactTagProcess.exitValue() == 0) {
            // On exact tag - clean release version
            exactTagProcess.inputStream.bufferedReader().readText().trim()
        } else {
            // Not on exact tag - get development version with version tags only
            val devVersionProcess =
                ProcessBuilder("git", "describe", "--tags", "--always", "--match", "[0-9]*", "--dirty=-SNAPSHOT")
                    .directory(rootDir)
                    .start()
            devVersionProcess.waitFor()
            devVersionProcess.inputStream.bufferedReader().readText().trim()
        }
    } catch (e: Exception) {
        "0.0.0-UNKNOWN"
    }
}

plugins {
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.spotless)
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                // the objective is to get this to fail, i.e. don't allow failing dependencies
                severity("warn")
            }
        }
    }
}

allprojects {
    group = "ai.brokk"
    version = getVersionFromGit()

    repositories {
        mavenCentral()
        google()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
        maven("https://repo.eclipse.org/content/groups/releases/")
    }

    configurations.all {
        resolutionStrategy {
            // Cache dynamic versions for 24 hours instead of default 24 minutes
            cacheDynamicVersionsFor(24, java.util.concurrent.TimeUnit.HOURS)
            // Cache changing modules for 24 hours
            cacheChangingModulesFor(24, java.util.concurrent.TimeUnit.HOURS)
        }
    }
}

tasks.register("printVersion") {
    description = "Prints the current project version"
    group = "help"
    doLast {
        println(version)
    }
}


tasks.register("deployMcpShadowJar") {
    description = "Builds :app:shadowJar and copies it to a stable MCP jar path."
    group = "distribution"

    dependsOn(":app:shadowJar")

    doLast {
        val shadowJarFile = rootDir.resolve("app/build/libs/brokk-${project.version}.jar")
        if (!shadowJarFile.exists()) {
            throw GradleException("Expected shadow jar not found: ${shadowJarFile.absolutePath}")
        }

        val targetPath = (findProperty("mcpJarTarget") as String?)?.let(::File)
            ?: File(System.getProperty("user.home"), ".brokk/mcp/brokk-mcp.jar")
        targetPath.parentFile.mkdirs()
        shadowJarFile.copyTo(targetPath, overwrite = true)
        println("Deployed MCP jar to ${targetPath.absolutePath}")
    }
}

// Resolves an executable on PATH so the JetBrains/Zed-spawned subprocess (which doesn't inherit
// the user's shell PATH on macOS) gets an absolute command. Cross-platform: tries .exe/.cmd/.bat
// extensions on Windows.
fun findExecutableOnPath(name: String): String? {
    val pathEnv = System.getenv("PATH") ?: return null
    val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
    val sep = if (isWindows) ";" else ":"
    val exts = if (isWindows) listOf("", ".exe", ".cmd", ".bat") else listOf("")
    for (dir in pathEnv.split(sep)) {
        if (dir.isBlank()) continue
        for (ext in exts) {
            val candidate = File(dir, name + ext)
            if (candidate.canExecute()) return candidate.absolutePath
        }
    }
    return null
}

// Writes the given JSON model to the config file, replacing any existing
// agent_servers["Brokk Code (Local Jar)"] entry while leaving every other key intact.
@Suppress("UNCHECKED_CAST")
fun writeLocalJarAcpEntry(configFile: File, jar: File, uvxPath: String, includeZedTypeField: Boolean) {
    val parsed: MutableMap<String, Any> = if (configFile.exists() && configFile.length() > 0L) {
        JsonSlurper().parseText(configFile.readText()) as? MutableMap<String, Any>
            ?: throw GradleException("Existing config at ${configFile.absolutePath} is not a JSON object.")
    } else {
        mutableMapOf()
    }
    val agentServers = (parsed["agent_servers"] as? MutableMap<String, Any>) ?: mutableMapOf<String, Any>().also {
        parsed["agent_servers"] = it
    }
    val entry = linkedMapOf<String, Any>()
    if (includeZedTypeField) entry["type"] = "custom"
    entry["command"] = uvxPath
    entry["args"] = listOf("brokk", "acp", "--jar", jar.absolutePath)
    entry["env"] = emptyMap<String, String>()
    agentServers["Brokk Code (Local Jar)"] = entry

    configFile.parentFile.mkdirs()
    configFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(parsed)) + "\n")
}

tasks.register("buildAcpServerJarForJetbrains") {
    description = "Builds :app:shadowJar and rewrites the 'Brokk Code (Local Jar)' entry in ~/.jetbrains/acp.json to point at the just-built jar."
    group = "distribution"

    dependsOn(":app:shadowJar")

    doLast {
        val jar = rootDir.resolve("app/build/libs/brokk-${project.version}.jar")
        if (!jar.exists()) {
            throw GradleException("Expected shadow jar not found: ${jar.absolutePath}")
        }
        val uvxPath = findExecutableOnPath("uvx")
            ?: throw GradleException("uvx not found on PATH. Install via 'pipx install brokk' or 'pip install --user brokk' and retry.")

        val configFile = (findProperty("acpJetbrainsConfig") as String?)?.let(::File)
            ?: File(System.getProperty("user.home"), ".jetbrains/acp.json")
        writeLocalJarAcpEntry(configFile, jar, uvxPath, includeZedTypeField = false)
        println("Updated 'Brokk Code (Local Jar)' in ${configFile.absolutePath} -> ${jar.absolutePath}")
    }
}

tasks.register("buildAcpServerJarForZed") {
    description = "Builds :app:shadowJar and rewrites the 'Brokk Code (Local Jar)' entry in ~/.config/zed/settings.json to point at the just-built jar."
    group = "distribution"

    dependsOn(":app:shadowJar")

    doLast {
        val jar = rootDir.resolve("app/build/libs/brokk-${project.version}.jar")
        if (!jar.exists()) {
            throw GradleException("Expected shadow jar not found: ${jar.absolutePath}")
        }
        val uvxPath = findExecutableOnPath("uvx")
            ?: throw GradleException("uvx not found on PATH. Install via 'pipx install brokk' or 'pip install --user brokk' and retry.")

        val configFile = (findProperty("acpZedConfig") as String?)?.let(::File)
            ?: File(System.getProperty("user.home"), ".config/zed/settings.json")
        writeLocalJarAcpEntry(configFile, jar, uvxPath, includeZedTypeField = true)
        println("Updated 'Brokk Code (Local Jar)' in ${configFile.absolutePath} -> ${jar.absolutePath}")
    }
}

tasks.register("deployCoreMcpShadowJar") {
    description = "Builds :brokk-core:shadowJar and copies it to a stable MCP jar path."
    group = "distribution"

    dependsOn(":brokk-core:shadowJar")

    doLast {
        val shadowJarFile = rootDir.resolve("brokk-core/build/libs/brokk-core-${project.version}.jar")
        if (!shadowJarFile.exists()) {
            throw GradleException("Expected shadow jar not found: ${shadowJarFile.absolutePath}")
        }

        val targetPath = (findProperty("coreMcpJarTarget") as String?)?.let(::File)
            ?: File(System.getProperty("user.home"), ".brokk/mcp/brokk-core-mcp.jar")
        targetPath.parentFile.mkdirs()
        shadowJarFile.copyTo(targetPath, overwrite = true)
        println("Deployed core MCP jar to ${targetPath.absolutePath}")
    }
}

tasks.register("configureMcpStableJar") {
    description = "Updates Codex and Claude MCP configs to use the stable local MCP jar path."
    group = "distribution"

    doLast {
        val home = File(System.getProperty("user.home"))
        val targetPath = (findProperty("mcpJarTarget") as String?)?.let(::File)
            ?: File(home, ".brokk/mcp/brokk-mcp.jar")
        if (!targetPath.exists()) {
            throw GradleException(
                "Stable MCP jar not found at ${targetPath.absolutePath}. Run deployMcpShadowJar first."
            )
        }

        val codexPath = File(home, ".codex/config.toml")
        if (!codexPath.exists()) {
            throw GradleException("Codex config not found at ${codexPath.absolutePath}")
        }
        val codexBlock = """
[mcp_servers.brokk]
command = "java"
args = ["--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true", "-cp", "${
            targetPath.absolutePath.replace(
                "\\",
                "/"
            )
        }", "ai.brokk.mcpserver.BrokkExternalMcpServer"]
type = "stdio"
startup_timeout_sec = 60.0
tool_timeout_sec = 300.0
"""
            .trimIndent()
        val codexOriginal = codexPath.readText()
        val codexWithoutBrokk = codexOriginal.replace(
            Regex("""(?ms)^\[mcp_servers\.brokk]\R.*?(?=^\[|\z)"""),
            ""
        )
        val codexUpdated = codexWithoutBrokk.trimEnd() + System.lineSeparator() + System.lineSeparator() +
                codexBlock + System.lineSeparator()
        codexPath.writeText(codexUpdated)

        val claudePath = File(home, ".claude.json")
        if (!claudePath.exists()) {
            throw GradleException("Claude config not found at ${claudePath.absolutePath}")
        }
        @Suppress("UNCHECKED_CAST")
        val root = (JsonSlurper().parseText(claudePath.readText()) as? MutableMap<String, Any?>)
            ?: throw GradleException("Expected JSON object in ${claudePath.absolutePath}")

        @Suppress("UNCHECKED_CAST")
        val mcpServers = (root["mcpServers"] as? MutableMap<String, Any?>) ?: mutableMapOf<String, Any?>().also {
            root["mcpServers"] = it
        }

        @Suppress("UNCHECKED_CAST")
        val existingBrokk = (mcpServers["brokk"] as? MutableMap<String, Any?>) ?: mutableMapOf()
        val envMap = mutableMapOf<String, Any?>("MCP_TIMEOUT" to "60000", "MCP_TOOL_TIMEOUT" to "300000")
        @Suppress("UNCHECKED_CAST")
        (existingBrokk["env"] as? Map<String, Any?>)?.forEach { (k, v) -> envMap[k] = v }

        val brokkConfig = mutableMapOf<String, Any?>(
            "command" to "java",
            "args" to listOf(
                "--enable-native-access=ALL-UNNAMED",
                "-Djava.awt.headless=true",
                "-Dapple.awt.UIElement=true",
                "-cp",
                targetPath.absolutePath,
                "ai.brokk.mcpserver.BrokkExternalMcpServer"
            ),
            "type" to "stdio",
            "env" to envMap
        )
        mcpServers["brokk"] = brokkConfig
        claudePath.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(root)) + System.lineSeparator())

        println("Updated Codex MCP config at ${codexPath.absolutePath}")
        println("Updated Claude MCP config at ${claudePath.absolutePath}")
        println("Configured stable MCP jar at ${targetPath.absolutePath}")
    }
}

tasks.register("tidy") {
    description = "Formats code using Spotless (alias for spotlessApply in all projects)"
    group = "formatting"

    dependsOn(
        subprojects.map { it.tasks.matching { t -> t.name == "spotlessApply" } }
    )
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.autonomousapps.dependency-analysis")
    apply(plugin = "com.diffplug.spotless")

    // Palantir Java Format (used by Spotless) depends on Guava classes removed in Guava 32
    // (e.g. com.google.common.base.Throwables). Force a compatible Guava version only for
    // Spotless' formatter configurations, so the main project can still use Guava 32.
    configurations.matching { it.name.contains("spotless") }.configureEach {
        resolutionStrategy.force("com.google.guava:guava:31.1-jre")
    }

    // Spotless formatting rules
    spotless {
        java {
            // Format all Java sources, excluding generated or build outputs
            target("src/**/*.java")
            targetExclude("**/build/**", "**/test/resources/**", "**/generated/**")
            // Use Palantir Java Format (opinionated formatter similar to Google Java Format,
            // but with improved blank-line and lambda indentation handling)
            palantirJavaFormat(libs.versions.palantirJavaFormat.get())
            removeUnusedImports()
        }
    }

    repositories {
        mavenCentral()
        google()
        maven("https://repo.gradle.org/gradle/libs-releases")
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://repo.eclipse.org/content/groups/releases/")
    }


    tasks.withType<Test> {
        filter {
            isFailOnNoMatchingTests = false
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

}
