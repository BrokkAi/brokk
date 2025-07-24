package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzer;
import io.github.jbellis.brokk.analyzer.lsp.SharedLspServer;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JdtAnalyzer implements LspAnalyzer {

    private final Logger logger = LoggerFactory.getLogger(JdtAnalyzer.class);

    @NotNull
    private final Path projectRoot;
    @NotNull
    private final String workspace;
    @NotNull
    private final SharedLspServer sharedServer;

    // Helper set for quick lookup of type-related symbol kinds
    private static final Set<SymbolKind> TYPE_KINDS = Set.of(
            SymbolKind.Class,
            SymbolKind.Interface,
            SymbolKind.Enum,
            SymbolKind.Struct
    );

    /**
     * Creates an analyzer for a specific project workspace.
     *
     * @param projectPath   The path to the Java project to be analyzed.
     * @param excludedPaths A set of glob patterns to exclude from analysis (e.g., "build", "**\/target").
     * @throws IOException if the server cannot be started.
     */
    public JdtAnalyzer(Path projectPath, Set<String> excludedPaths) throws IOException {
        this.projectRoot = projectPath.toAbsolutePath().normalize();
        if (!this.projectRoot.toFile().exists()) {
            throw new FileNotFoundException("Project directory does not exist: " + projectRoot);
        } else {
            ensureProjectConfiguration(this.projectRoot);
            this.sharedServer = SharedLspServer.getInstance();
            this.sharedServer.registerClient(this.projectRoot, excludedPaths);
            this.sharedServer.refreshWorkspace().join();

            loadProjectFiles();
            this.workspace = this.projectRoot.toUri().toString();
        }
    }

    /**
     * Finds all .java files in the project and sends a 'didOpen' notification for each one,
     * forcing the server to analyze them.
     *
     * @throws IOException if there is an error reading the files.
     */
    public void loadProjectFiles() throws IOException {
        logger.info("Programmatically opening all .java files in {}...", this.projectRoot);
        try (Stream<Path> stream = Files.walk(this.projectRoot)) {
            stream
                    .filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".java"))
                    .forEach(filePath -> {
                        try {
                            this.sharedServer.forceAnalyzeFile(filePath.toRealPath(), "java");
                        } catch (IOException e) {
                            logger.error("Failed to open file: {}", filePath, e);
                        }
                    });
        }
        logger.info("...finished sending didOpen notifications.");
    }

    /**
     * Checks for a build file (pom.xml, build.gradle) or a .classpath file.
     * If none exist, it generates a default .classpath file by guessing the source directory. This is absolutely
     * required for the LSP server to import code.
     *
     * @param projectPath The root of the project workspace.
     * @throws IOException If file I/O fails.
     */
    private void ensureProjectConfiguration(Path projectPath) throws IOException {
        // 1. Check for existing project files (non-recursively). If any exist, do nothing.
        if (Files.exists(projectPath.resolve("pom.xml")) ||
                Files.exists(projectPath.resolve("build.gradle")) ||
                Files.exists(projectPath.resolve("build.gradle.kts")) ||
                Files.exists(projectPath.resolve(".classpath"))) {
            logger.debug("Existing project file found for {}. No action needed.", projectPath);
            return;
        }

        logger.info("No build file found for {}. Generating a default .classpath file.", projectPath);

        // 2. Intelligently guess the common source directory path.
        String sourcePath;
        if (Files.isDirectory(projectPath.resolve("src/main/java"))) {
            sourcePath = "src/main/java";
        } else if (Files.isDirectory(projectPath.resolve("src"))) {
            sourcePath = "src";
        } else {
            // As a last resort, assume sources are in the root.
            sourcePath = ".";
            logger.warn("Could not find a 'src' directory for {}. Defaulting source path to project root.", projectPath);
        }

        // 3. Dynamically determine the JRE version from the current runtime.
        final int javaVersion = Runtime.version().feature();
        String classpathContent = generateClassPathContent(javaVersion, sourcePath);

        // 5. Write the new .classpath file.
        Files.writeString(projectPath.resolve(".classpath"), classpathContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Generated default .classpath for {} with source path '{}'", projectPath, sourcePath);
    }

    private static @NotNull String generateClassPathContent(int javaVersion, String sourcePath) {
        final String jreVersionString = (javaVersion >= 9) ? "JavaSE-" + javaVersion : "JavaSE-1." + javaVersion;
        final String jreContainerPath = "org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/" + jreVersionString;

        // Generate the .classpath content with the dynamic JRE path.
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                    <classpathentry kind="src" path="%s"/>
                    <classpathentry kind="con" path="%s"/>
                </classpath>
                """, sourcePath, jreContainerPath);
    }

    public boolean isClassInProject(String className) {
        return !findTypeByExactName(className).join().isEmpty();
    }

    /**
     * Finds a type (class, interface, enum) by its exact simple or fully qualified name within the workspace.
     *
     * @param className The exact, case-sensitive simple name of the type to find.
     * @return A CompletableFuture that will be completed with a list of matching symbols.
     */
    public CompletableFuture<List<WorkspaceSymbol>> findTypeByExactName(String className) {
        // We still use the general findSymbolsInWorkspace method to get all potential candidates
        return findSymbolsInWorkspace(className).thenApply(symbols -> {
            // After getting the candidates, we apply our strict filters
            return symbols.stream()
                    // Filter 1: Keep only symbols with an exact name match.
                    .filter(symbol -> {
                        final String symbolFullName = symbol.getContainerName() + "." + symbol.getName();
                        return symbol.getName().equals(className) || symbolFullName.equals(className);
                    })
                    // Filter 2: Keep only symbols that are a class, interface, enum, etc.
                    .filter(symbol -> TYPE_KINDS.contains(symbol.getKind()))
                    .collect(Collectors.toList());
        });
    }

    /**
     * Finds symbols within this analyzer's specific workspace using the modern WorkspaceSymbol type.
     *
     * @param symbolName The name of the symbol to search for.
     * @return A CompletableFuture that will be completed with a list of symbols
     * found only within this instance's project path.
     */
    public CompletableFuture<List<? extends WorkspaceSymbol>> findSymbolsInWorkspace(String symbolName) {
        final var allSymbolsFuture =
                sharedServer.query(server ->
                        server.getWorkspaceService().symbol(new WorkspaceSymbolParams(symbolName))
                );

        return allSymbolsFuture.thenApply(futureEither ->
                futureEither.thenApply(either -> {
                    if (either.isLeft()) {
                        // Case 1: Server sent the DEPRECATED type. Convert to the new type and filter.
                        return either.getLeft().stream()
                                .map(this::toWorkspaceSymbol)
                                .filter(symbol -> getUriFromLocation(symbol.getLocation()).startsWith(workspace))
                                .collect(Collectors.toList());
                    } else if (either.isRight()) {
                        // Case 2: Server sent the MODERN type. Just filter.
                        return either.getRight().stream()
                                .filter(symbol -> getUriFromLocation(symbol.getLocation()).startsWith(workspace))
                                .collect(Collectors.toList());
                    } else {
                        return new ArrayList<WorkspaceSymbol>();
                    }
                }).join()
        );
    }

    /**
     * Helper to convert a deprecated SymbolInformation object to the modern WorkspaceSymbol.
     */
    @NotNull
    @SuppressWarnings("deprecation")
    private WorkspaceSymbol toWorkspaceSymbol(@NotNull SymbolInformation info) {
        var ws = new WorkspaceSymbol(info.getName(), info.getKind(), Either.forLeft(info.getLocation()));
        ws.setContainerName(info.getContainerName());
        return ws;
    }

    @Override
    public void close() {
        sharedServer.unregisterClient(this.projectRoot);
    }
}