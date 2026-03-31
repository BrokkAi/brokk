import java.net.URI
import java.security.MessageDigest

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val treeSitterNgVersion = libs.versions.treesitter.get()
val jarsDir = layout.buildDirectory.dir("jars").get().asFile

val downloadTreeSitterNg = tasks.register("downloadTreeSitterNg") {
    description = "Downloads and extracts tree-sitter-ng native libraries"
    group = "build setup"

    val version = treeSitterNgVersion
    val downloadUrl = "https://github.com/BrokkAi/tree-sitter-ng/releases/download/v$version/tree-sitter-ng-jar.zip"
    val checksumsUrl = "https://github.com/BrokkAi/tree-sitter-ng/releases/download/v$version/checksums.txt"
    
    val cacheDir = layout.buildDirectory.dir("cache/v$version").get().asFile
    val zipFile = layout.buildDirectory.file("cache/tree-sitter-ng-$version.zip").get().asFile
    val checksumsFile = cacheDir.resolve("checksums.txt")

    inputs.property("version", version)
    outputs.dir(cacheDir)
    outputs.file(zipFile)
    outputs.dir(jarsDir)

    doLast {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // 1. Download checksums.txt if missing
        if (!checksumsFile.exists()) {
            logger.lifecycle("Downloading checksums.txt for TreeSitter NG v$version...")
            URI(checksumsUrl).toURL().openStream().use { input ->
                checksumsFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // 2. Download ZIP if missing
        if (!zipFile.exists()) {
            logger.lifecycle("Downloading TreeSitter NG v$version...")
            zipFile.parentFile.mkdirs()
            URI(downloadUrl).toURL().openStream().use { input ->
                zipFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        // 3. Verify Checksum
        val expectedFileName = "tree-sitter-ng-jar.zip"
        val expectedHash = checksumsFile.useLines { lines ->
            lines.map { it.split(Regex("\\s+")) }
                .find { it.size >= 2 && it[1] == expectedFileName }
                ?.get(0)
        } ?: throw GradleException("No checksum entry found for $expectedFileName in $checksumsUrl")

        val digest = MessageDigest.getInstance("SHA-256")
        val actualHash = zipFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }

        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            zipFile.delete() // Delete corrupted file so it re-downloads next time
            throw GradleException(
                "SHA-256 checksum mismatch for $zipFile\n" +
                "Expected: $expectedHash\n" +
                "Actual:   $actualHash\n" +
                "The downloaded file may be corrupted or tampered with."
            )
        }

        logger.lifecycle("Extracting verified TreeSitter NG modules to ${jarsDir.absolutePath}...")
        if (!jarsDir.exists()) {
            jarsDir.mkdirs()
        }

        copy {
            from(zipTree(zipFile))
            into(jarsDir)
            include("**/*.jar")
            eachFile {
                path = name
                path = path.replace("tree-sitter-ng", "tree-sitter")
            }
            includeEmptyDirs = false
        }
    }
}

dependencies {
    api(fileTree(jarsDir) {
        include("*.jar")
        builtBy(downloadTreeSitterNg)
    })
}
