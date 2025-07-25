package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzer;
import io.github.jbellis.brokk.analyzer.lsp.SharedLspServer;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
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
//            loadProjectFiles(); // fixme: This is not scalable for larger projects
            this.sharedServer.refreshWorkspace().join();
            this.workspace = this.projectRoot.toUri().toString();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.debug("JDT server thread wait interrupted");
            }
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
                Files.exists(projectPath.resolve(".classpath")) ||
                Files.exists(projectPath.resolve(".project"))) {
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
        String projectFileContent = generateProjectFileContent(projectPath.getFileName().toString());

        // 5. Write the new .classpath and .project file.
        Files.writeString(projectPath.resolve(".project"), projectFileContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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

    private static @NotNull String generateProjectFileContent(String projectName) {
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <projectDescription>
                    <name>%s</name>
                    <comment></comment>
                    <projects></projects>
                    <buildSpec>
                        <buildCommand>
                            <name>org.eclipse.jdt.core.javabuilder</name>
                            <arguments></arguments>
                        </buildCommand>
                    </buildSpec>
                    <natures>
                        <nature>org.eclipse.jdt.core.javanature</nature>
                    </natures>
                </projectDescription>
                """, projectName);
    }

    public boolean isClassInProject(String className) {
        return !findTypesInWorkspace(className).join().isEmpty();
    }

    @Override
    @Nullable
    public String getClassSource(String classFullName) {
        return findTypesInWorkspace(classFullName).thenApply(this::getSourceForSymbol).join().orElse(null);
    }

    /**
     * Finds a type (class, interface, enum) by its exact simple or fully qualified name within the workspace.
     *
     * @param className The exact, case-sensitive simple name of the type to find.
     * @return A CompletableFuture that will be completed with a list of matching symbols.
     */
    public CompletableFuture<List<WorkspaceSymbol>> findTypesInWorkspace(String className) {
        return types(className).thenApply(symbols -> symbols.filter(symbol -> simpleOrFullMatch(symbol, className)).collect(Collectors.toList()));
    }

    public CompletableFuture<List<WorkspaceSymbol>> findMethodSymbol(String className, String methodName) {
        return findTypesInWorkspace(className).thenCompose(classLocations -> {
            if (classLocations.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            return CompletableFuture.completedFuture(findMethodSymbol(classLocations, methodName));
        });
    }
    
    private List<WorkspaceSymbol> findMethodSymbol(List<WorkspaceSymbol> classLocations, String methodName) {
        return classLocations.stream().flatMap(classLocation -> {
            final String uriString = getUriStringFromLocation(classLocation.getLocation());
            final Path filePath = Paths.get(URI.create(uriString));
            return getSymbolsInFile(filePath).thenApply(fileSymbols ->
                    fileSymbols.stream().map(fileSymbolsEither -> {
                        if (fileSymbolsEither.isRight()) {
                            return findSymbolInTree(Collections.singletonList(fileSymbolsEither.getRight()), methodName, SymbolKind.Method)
                                    .stream()
                                    .map(documentSymbol -> documentToWorkspaceSymbol(documentSymbol, uriString))
                                    .toList();
                        } else {
                            // Find the symbol and map it to a new Location object with a precise range
                            return new ArrayList<WorkspaceSymbol>();
                        }
                    }).flatMap(Collection::stream).toList()
            ).join().stream();
        }).toList();
    }
    
    private WorkspaceSymbol documentToWorkspaceSymbol(DocumentSymbol documentSymbol, String uriString) {
        return new WorkspaceSymbol(
                documentSymbol.getName(),
                documentSymbol.getKind(),
                Either.forLeft(new Location(uriString, documentSymbol.getRange()))
        );
    }

    /**
     * Recursively searches a tree of DocumentSymbol objects for a symbol with a specific name and kind.
     */
    private List<DocumentSymbol> findSymbolInTree(List<DocumentSymbol> symbols, String name, SymbolKind kind) {
        for (DocumentSymbol symbol : symbols) {
            // TODO: stripMethodSignature should be some callback ideally
            if (stripMethodSignature(symbol.getName()).equals(name) && symbol.getKind() == kind) {
                return Collections.singletonList(symbol);
            }
            if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                List<DocumentSymbol> found = findSymbolInTree(symbol.getChildren(), name, kind);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return Collections.emptyList();
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        final String cleanedName = stripMethodSignature(fqName);
        final int lastIndex = cleanedName.lastIndexOf('.');
        if (lastIndex != -1) {
            String className = cleanedName.substring(0, lastIndex);
            String methodName = cleanedName.substring(lastIndex + 1);
            final String result = findMethodSymbol(className, methodName).thenApply(maybeSymbol ->
                    maybeSymbol.stream().map(this::getSourceForSymbolDefinition).flatMap(Optional::stream).collect(Collectors.joining("\n"))
            ).join();
            if (result.isBlank()) return Optional.empty();
            else return Optional.of(result);
        } else {
            return Optional.empty();
        }
    }

    /**
     * Gets a list of all symbols defined within a specific file.
     *
     * @param filePath The path to the file to analyze.
     * @return A CompletableFuture that will resolve with the server's response, which is an
     * 'Either' containing a list of SymbolInformation (older format) or DocumentSymbol (newer, hierarchical format).
     */
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> getSymbolsInFile(Path filePath) {
        logger.info("Querying for document symbols in {}", filePath);
        return sharedServer.query(server -> {
            DocumentSymbolParams params = new DocumentSymbolParams(
                    new TextDocumentIdentifier(filePath.toUri().toString())
            );
            return server.getTextDocumentService().documentSymbol(params).join();
        });
    }

//    /**
//     * Intelligently searches for a symbol. If the query contains a '.', it's treated as a
//     * fully-qualified method name; otherwise, it's treated as a simple type name.
//     *
//     * @param query The simple type name (e.g., "MyClass") or a full method name (e.g., "com.example.MyClass.myMethod").
//     * @return A CompletableFuture that will resolve to the Location of the found symbol, if any.
//     */
//    public CompletableFuture<Optional<Location>> findSymbolSmart(String query) {
//        String cleanQuery = query;
//        // 1. Filter out the method signature, if one exists.
//        if (cleanQuery.contains("(")) {
//            cleanQuery = cleanQuery.substring(0, cleanQuery.indexOf('('));
//        }
//
//        int lastDot = cleanQuery.lastIndexOf('.');
//
//        // 2. If no dot, assume it's a simple type name.
//        if (lastDot == -1) {
//            logger.info("Performing TYPE search for '{}'", cleanQuery);
//            return findTypesInWorkspace(cleanQuery);
//        }
//
//        // 3. If a dot exists, split into class and method, and search for the method.
//        String className = cleanQuery.substring(0, lastDot);
//        String methodName = cleanQuery.substring(lastDot + 1);
//        logger.info("Performing METHOD search for class '{}' and method '{}'", className, methodName);
//        return findMethodSymbol(className, methodName);
//    }

    // A placeholder for resolveMethodName
    private boolean simpleOrFullMatch(WorkspaceSymbol symbol, String simpleOrFullName) {
        final String symbolFullName = symbol.getContainerName() + "." + symbol.getName();
        return symbol.getName().equals(simpleOrFullName) || symbolFullName.equals(simpleOrFullName);
    }

    private CompletableFuture<Stream<? extends WorkspaceSymbol>> types(String name) {
        return findSymbolsInWorkspace(name).thenApply(symbols ->
                symbols.stream()
                        .filter(symbol -> TYPE_KINDS.contains(symbol.getKind()))
        );
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
                                .filter(symbol -> getUriStringFromLocation(symbol.getLocation()).startsWith(workspace))
                                .collect(Collectors.toList());
                    } else if (either.isRight()) {
                        // Case 2: Server sent the MODERN type. Just filter.
                        return either.getRight().stream()
                                .filter(symbol -> getUriStringFromLocation(symbol.getLocation()).startsWith(workspace))
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

    /**
     * Strips the method signature (parentheses and parameters) from a method name string.
     *
     * @param methodName The full method name from the symbol object (e.g., "myMethod(int)").
     * @return The clean method name without the signature (e.g., "myMethod").
     */
    @NotNull
    public static String stripMethodSignature(@NotNull String methodName) {
        int parenIndex = methodName.indexOf('(');

        // If a parenthesis is found, return the part of the string before it.
        if (parenIndex != -1) {
            return methodName.substring(0, parenIndex);
        }

        // Otherwise, return the original string.
        return methodName;
    }

    @Override
    public void close() {
        sharedServer.unregisterClient(this.projectRoot);
    }
}