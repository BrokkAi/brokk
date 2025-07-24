package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

public interface LspAnalyzer extends IAnalyzer, AutoCloseable {
  
  @Override
  default boolean isCpg() {
    return false;
  }

  /**
   * Unpacks the jdt.tar.gz file from the resources into a temporary directory.
   * @return The path to the temporary directory where the server was unpacked.
   * @throws IOException If the resource is not found or an error occurs during extraction.
   */
  static Path unpackLspServer(String name) throws IOException {
    final var tempDir = Files.createTempDirectory("jdt-ls-unpacked-");
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
  
  static Path findFile(Path dir, String partialName) throws IOException {
    try (Stream<Path> stream = Files.walk(dir)) {
      return stream
              .filter(p -> p.getFileName().toString().contains(partialName) && p.toString().endsWith(".jar"))
              .findFirst()
              .orElseThrow(() -> new FileNotFoundException("Could not find launcher jar with name containing: " + partialName));
    }
  }

  static Path findConfigDir(Path dir) throws IOException {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String configSubDir;
    if (os.contains("win")) {
      configSubDir = "config_win";
    } else if (os.contains("mac")) {
      configSubDir = "config_mac";
    } else {
      configSubDir = "config_linux";
    }
    Path configPath = dir.resolve(configSubDir);
    if (Files.isDirectory(configPath)) {
      return configPath;
    }
    throw new FileNotFoundException("Could not find configuration directory: " + configSubDir);
  }

}
