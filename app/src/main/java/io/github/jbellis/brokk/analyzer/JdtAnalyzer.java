package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzer;
import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzerHelper;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import io.github.jbellis.brokk.analyzer.lsp.SharedLspServer;
import io.github.jbellis.brokk.analyzer.lsp.jdt.JdtProjectHelper;
import io.github.jbellis.brokk.analyzer.lsp.jdt.JdtSkeletonHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class JdtAnalyzer implements LspAnalyzer {

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
            try {
                JdtProjectHelper.ensureProjectConfiguration(this.projectRoot);
            } catch (Exception e) {
                logger.warn("Error validating and creating project build files for: {}. Attempting to continue.", projectRoot, e);
            }
            this.workspace = this.projectRoot.toUri().toString();
            this.sharedServer = SharedLspServer.getInstance();
            this.sharedServer.registerClient(this.projectRoot, excludedPaths, getInitializationOptions(), getLanguage());
            this.sharedServer.refreshWorkspace().join();
            try {
                // Indexing generally completes within a couple of seconds, but larger projects need grace
                final var maybeWorkspaceReadyLatch = this.getWorkspaceReadyLatch(this.workspace);
                if (maybeWorkspaceReadyLatch.isEmpty()) {
                    logger.warn("Could not find workspace latch for {}. Continuing...", this.workspace);
                } else {
                    maybeWorkspaceReadyLatch.get().await();
                    logger.debug("JDT LSP indexing complete. The analyzer ready.");
//                    if (!maybeWorkspaceReadyLatch.get().await(5, TimeUnit.MINUTES)) {
//                        logger.warn("Server is taking longer than expected to complete indexing, continuing with partial indexes.");
//                    } else {
//                        logger.debug("JDT LSP indexing complete. The analyzer ready.");
//                    }
                }
            } catch (InterruptedException e) {
                logger.debug("Interrupted while waiting for initialization, the server may not be properly indexed", e);
            }
        }
    }

    @Override
    public @NotNull Path getProjectRoot() {
        return this.projectRoot;
    }

    @Override
    public @NotNull String getWorkspace() {
        return this.workspace;
    }

    @Override
    public @NotNull LspServer getServer() {
        return this.sharedServer;
    }

    @Override
    public @NotNull String getLanguage() {
        return "java";
    }

    @Override
    public @NotNull Map<String, Object> getInitializationOptions() {
        final var options = new HashMap<String, Object>();
        final var javaOptions = new HashMap<String, Object>();
        final var server = new HashMap<String, Object>();
        final var symbols = new HashMap<String, Object>();
        final var references = new HashMap<String, Object>();
        final var configuration = new HashMap<String, Object>();
        final var imporT = new HashMap<String, Object>();

        server.put("launchMode", "Hybrid");
        // Include method declarations from source files in symbol search.
        symbols.put("includeSourceMethodDeclarations", true);
        // include getter, setter and builder/constructor when finding references.
        references.put("includeAccessors", true);
        configuration.put("updateBuildConfiguration", "automatic");
        imporT.put("maven", Map.of("wrapper", Map.of("enabled", true)));
        imporT.put("gradle", Map.of("wrapper", Map.of("enabled", true)));

        javaOptions.put("server", server);
        javaOptions.put("symbols", symbols);
        javaOptions.put("references", references);
        javaOptions.put("configuration", configuration);
        javaOptions.put("import", imporT);

        options.put("java", javaOptions);
        return options;
    }

    /**
     * Strips the method signature (parentheses and parameters) from a method name string.
     *
     * @param methodName The full method name from the symbol object (e.g., "myMethod(int)").
     * @return The clean method name without the signature (e.g., "myMethod").
     */
    @Override
    public @NotNull String resolveMethodName(@NotNull String methodName) {
        final var cleanedName = methodName.replace('$', '.');
        int parenIndex = cleanedName.indexOf('(');

        // If a parenthesis is found, return the part of the string before it.
        if (parenIndex != -1) {
            return cleanedName.substring(0, parenIndex);
        }

        // Otherwise, return the original string.
        return cleanedName;
    }

    @Override
    public @Nullable String getClassSource(@NotNull String classFullName) {
        // JSP containers are dot-delimited and get rid of the '$'
        final String cleanedName = classFullName.replace('$', '.');
        return LspAnalyzer.super.getClassSource(cleanedName);
    }

    @Override
    public @NotNull String sanitizeType(String typeName) {
        // Check if the type has generic parameters
        if (typeName.contains("<")) {
            final String mainType = typeName.substring(0, typeName.indexOf('<'));
            final String genericPart = typeName.substring(typeName.indexOf('<') + 1, typeName.lastIndexOf('>'));

            // Process the main part of the type (e.g., "java.util.List")
            final String processedMain = this.processType(mainType);

            // Process each generic parameter recursively
            final String processedParams = Arrays.stream(genericPart.split(","))
                    .map(param -> {
                        final String trimmed = param.trim();
                        // If a parameter is itself generic, recurse
                        if (trimmed.contains("<")) {
                            return this.sanitizeType(trimmed);
                        } else {
                            return this.processType(trimmed);
                        }
                    })
                    .collect(Collectors.joining(", "));

            return String.format("%s<%s>", processedMain, processedParams);
        } else {
            // If not a generic type, process directly
            return this.processType(typeName);
        }
    }

    /**
     * A helper method to convert a single, non-generic type name
     * to its simple name, preserving array brackets.
     *
     * @param typeString The type string (e.g., "java.lang.String" or "int[]").
     * @return The simple name (e.g., "String" or "int[]").
     */
    private String processType(String typeString) {
        boolean isArray = typeString.endsWith("[]");
        // Remove array brackets to get the base type name
        String base = isArray ? typeString.substring(0, typeString.length() - 2) : typeString;

        // Get the last part of the dot-separated package name
        int lastDotIndex = base.lastIndexOf('.');
        String shortName = (lastDotIndex != -1) ? base.substring(lastDotIndex + 1) : base;

        // Add array brackets back if they were present
        return isArray ? shortName + "[]" : shortName;
    }

    private Optional<String> getSkeleton(String fqName, boolean headerOnly) {
        final Set<String> skeletons = LspAnalyzerHelper.findTypesInWorkspace(fqName, workspace, sharedServer, false)
                .thenApply(typeSymbols ->
                        typeSymbols.stream().map(typeSymbol -> {
                                    // First, read the full source text of the file.
                                    final Optional<String> fullSourceOpt =
                                            LspAnalyzerHelper.getSourceForUriString(typeSymbol.getLocation().getLeft().getUri());
                                    if (fullSourceOpt.isEmpty()) {
                                        return Optional.<String>empty();
                                    } else {
                                        final String fullSource = fullSourceOpt.get();
                                        final var eitherLocation = typeSymbol.getLocation();
                                        if (eitherLocation.isLeft()) {
                                            return JdtSkeletonHelper.getSymbolSkeleton(
                                                    sharedServer,
                                                    eitherLocation.getLeft(),
                                                    fullSource,
                                                    headerOnly
                                            ).join();
                                        } else {
                                            return Optional.<String>empty();
                                        }
                                    }
                                })
                                .flatMap(Optional::stream)
                                .collect(Collectors.toSet())
                ).join();

        if (skeletons.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(String.join(System.lineSeparator(), skeletons));
        }
    }

    @Override
    public Optional<String> getSkeleton(String fqName) {
        return getSkeleton(fqName, false);
    }

    @Override
    public Optional<String> getSkeletonHeader(String className) {
        return getSkeleton(className, true);
    }

    @Override
    public void close() {
        sharedServer.unregisterClient(this.projectRoot, getInitializationOptions(), getLanguage());
    }
}