package io.github.jbellis.brokk.analyzer.lsp;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

public interface LspFileUtilities {

    default Path findFile(Path dir, String partialName) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().contains(partialName) && p.toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new FileNotFoundException("Could not find launcher jar with name containing: " + partialName));
        }
    }

    /**
     * Unpacks the jdt.tar.gz file from the resources into a temporary directory.
     *
     * @return The path to the temporary directory where the server was unpacked.
     * @throws IOException If the resource is not found or an error occurs during extraction.
     */
    default Path unpackLspServer(String name) throws IOException {
        final var tempDir = Files.createTempDirectory(name + "-ls-unpacked-");
        tempDir.toFile().deleteOnExit(); // Clean up on JVM exit

        try (final var resourceStream = LspAnalyzer.class.getResourceAsStream("/lsp/" + name + ".tar.gz")) {
            if (resourceStream == null) {
                throw new FileNotFoundException("LSP server archive not found at /lsp/" + name + ".tar.gz");
            }
            final var gzipIn = new GzipCompressorInputStream(new BufferedInputStream(resourceStream));
            try (final var tarIn = new TarArchiveInputStream(gzipIn)) {
                TarArchiveEntry entry;
                while ((entry = tarIn.getNextEntry()) != null) {
                    final Path destination = tempDir.resolve(entry.getName());

                    // Security check to prevent path traversal attacks
                    if (!destination.normalize().startsWith(tempDir)) {
                        throw new IOException("Bad tar entry: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(tarIn, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        return tempDir;
    }

    @NotNull
    default Path findConfigDir(@NotNull Path dir) throws IOException {
        final String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        final String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        final String baseConfigDir;
        if (osName.contains("win")) {
            baseConfigDir = "config_win";
        } else if (osName.contains("mac")) {
            baseConfigDir = "config_mac";
        } else {
            baseConfigDir = "config_linux";
        }

        // First, check for the more specific ARM-based directory.
        if (osArch.equals("aarch64")) {
            final Path armConfigPath = dir.resolve(baseConfigDir + "_arm");
            if (Files.isDirectory(armConfigPath)) {
                return armConfigPath;
            }
        }

        //If the ARM-specific one isn't found (or we're not on ARM), fall back to the generic one.
        final Path genericConfigPath = dir.resolve(baseConfigDir);
        if (Files.isDirectory(genericConfigPath)) {
            return genericConfigPath;
        }

        // If neither is found, throw an error.
        throw new FileNotFoundException(String.format(
                "Could not find a valid configuration directory. Checked for '%s_arm' and '%s' in %s",
                baseConfigDir, baseConfigDir, dir
        ));
    }
    
}

