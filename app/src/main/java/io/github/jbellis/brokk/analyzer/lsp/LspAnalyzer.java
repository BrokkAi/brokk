package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.checkerframework.checker.nullness.util.Opt;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public interface LspAnalyzer extends IAnalyzer, AutoCloseable {

    Logger logger = LoggerFactory.getLogger(LspAnalyzer.class);

    @Override
    default boolean isCpg() {
        return false;
    }

    Set<SymbolKind> TYPE_KINDS = Set.of(
            SymbolKind.Class,
            SymbolKind.Interface,
            SymbolKind.Enum,
            SymbolKind.Struct
    );

    Set<SymbolKind> METHOD_KINDS = Set.of(
            SymbolKind.Method,
            SymbolKind.Function,
            SymbolKind.Constructor
    );

    /**
     * Unpacks the jdt.tar.gz file from the resources into a temporary directory.
     *
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

    /**
     * Helper to extract the URI string from the nested Either type in a WorkspaceSymbol's location.
     */
    @NotNull
    default String getUriStringFromLocation(@NotNull Either<Location, WorkspaceSymbolLocation> locationEither) {
        return locationEither.isLeft()
                ? locationEither.getLeft().getUri()
                : locationEither.getRight().getUri();
    }

    default Optional<String> getSourceForSymbol(List<WorkspaceSymbol> symbols) {
        if (symbols.isEmpty()) {
            return Optional.empty();
        } else return getSourceForSymbol(symbols.getFirst());
    }
    
    default Optional<String> getCodeForCallSite(CallHierarchyItem callSite) {
        try {
            final Path filePath = Paths.get(new URI(callSite.getUri()));
            return Optional.of(Files.readString(filePath))
                    .map(source -> getSourceForRange(source, callSite.getRange()));
        } catch (IOException | URISyntaxException e) {
            logger.error("Failed to read source for symbol '{}' at {}", callSite.getName(), callSite.getUri(), e);
            return Optional.empty();
        }
    }

    default Optional<String> getSourceForSymbol(WorkspaceSymbol symbol) {
        final String uriString = getUriStringFromLocation(symbol.getLocation());
        try {
            final Path filePath = Paths.get(new URI(uriString));
            return Optional.of(Files.readString(filePath));
        } catch (IOException | URISyntaxException e) {
            logger.error("Failed to read source for symbol '{}' at {}", symbol.getName(), uriString, e);
            return Optional.empty();
        }
    }

    /**
     * Gets the precise source code for a symbol's definition using its Range.
     *
     * @param symbol The symbol to get the source for.
     * @return An Optional containing the source code snippet, or empty if no range is available.
     */
    default Optional<String> getSourceForSymbolDefinition(WorkspaceSymbol symbol) {
        if (symbol.getLocation().isLeft()) {
            Location location = symbol.getLocation().getLeft();
            return getSourceForSymbol(symbol).map(fullSource -> getSourceForRange(fullSource, location.getRange()));
        }

        logger.warn("Cannot get source for symbol '{}' because its location has no Range information.", symbol.getName());
        return Optional.empty();
    }

    /**
     * A helper that extracts a block of text from a string based on LSP Range data.
     */
    default String getSourceForRange(String fileContent, Range range) {
        String[] lines = fileContent.split("\\R", -1); // Split by any line break
        int startLine = range.getStart().getLine();
        int endLine = range.getEnd().getLine();
        int startChar = range.getStart().getCharacter();
        int endChar = range.getEnd().getCharacter();

        if (startLine == endLine) {
            return lines[startLine].substring(startChar, endChar);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(lines[startLine].substring(startChar)).append(System.lineSeparator());

        for (int i = startLine + 1; i < endLine; i++) {
            sb.append(lines[i]).append(System.lineSeparator());
        }

        if (endChar > 0) {
            sb.append(lines[endLine], 0, endChar);
        }

        return sb.toString();
    }

}
