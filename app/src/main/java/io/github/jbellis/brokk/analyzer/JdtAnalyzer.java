package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzer;
import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzerHelper;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import io.github.jbellis.brokk.analyzer.lsp.jdt.JdtLanguageClient;
import io.github.jbellis.brokk.analyzer.lsp.jdt.JdtProjectHelper;
import io.github.jbellis.brokk.analyzer.lsp.jdt.JdtSkeletonHelper;
import io.github.jbellis.brokk.analyzer.lsp.jdt.SharedJdtLspServer;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.SymbolKind;

public class JdtAnalyzer implements LspAnalyzer, CanCommunicate, SkeletonProvider, LintingProvider {

    private final Path projectRoot;
    private final String workspace;
    private final SharedJdtLspServer sharedServer;
    private boolean useEclipseBuildFiles = false;

    /**
     * Creates an analyzer for a specific project workspace.
     *
     * @param project the IProject file containing the necessary project information.
     * @throws IOException if the server cannot be started.
     */
    public JdtAnalyzer(IProject project) throws IOException {
        Path projectRoot = project.getRoot().toAbsolutePath().normalize();
        try {
            // Follow symlinks to "canonical" path if possible
            projectRoot = project.getRoot().toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            logger.warn("Unable to resolve real path for {}", projectRoot);
        }
        this.projectRoot = projectRoot;

        if (!this.projectRoot.toFile().exists()) {
            throw new FileNotFoundException("Project directory does not exist: " + this.projectRoot);
        } else {
            try {
                useEclipseBuildFiles = JdtProjectHelper.ensureProjectConfiguration(this.projectRoot);
            } catch (Exception e) {
                logger.warn(
                        "Error validating and creating project build files for: {}. Attempting to continue.",
                        this.projectRoot,
                        e);
            }
            this.workspace = this.projectRoot.toUri().toString();
            this.sharedServer = SharedJdtLspServer.getInstance();
            this.sharedServer.registerClient(
                    this.projectRoot, project.getExcludedDirectories(), getInitializationOptions(), getLanguage());
            try {
                // Indexing generally completes within a couple of seconds, but larger projects need grace
                final var maybeWorkspaceReadyLatch = this.getWorkspaceReadyLatch(this.workspace);
                if (maybeWorkspaceReadyLatch.isEmpty()) {
                    logger.warn("Could not find workspace latch for {}. Continuing...", this.workspace);
                } else {
                    maybeWorkspaceReadyLatch.get().await();
                    logger.debug("JDT LSP indexing complete. The analyzer ready.");
                }
            } catch (InterruptedException e) {
                logger.debug("Interrupted while waiting for initialization, the server may not be properly indexed", e);
            }
        }
    }

    @Override
    public Path getProjectRoot() {
        return this.projectRoot;
    }

    @Override
    public String getWorkspace() {
        return this.workspace;
    }

    @Override
    public LspServer getServer() {
        return this.sharedServer;
    }

    @Override
    public String getLanguage() {
        return "java";
    }

    @Override
    public Map<String, Object> getInitializationOptions() {
        final var options = new HashMap<String, Object>();
        final var javaOptions = new HashMap<String, Object>();
        final var server = new HashMap<String, Object>();
        final var symbols = new HashMap<String, Object>();
        final var references = new HashMap<String, Object>();
        final var configuration = new HashMap<String, Object>();
        final var imporT = new HashMap<String, Object>();
        final var autobuild = new HashMap<String, Object>();

        server.put("launchMode", "Hybrid");
        // Include method declarations from source files in symbol search.
        symbols.put("includeSourceMethodDeclarations", true);
        // include getter, setter and builder/constructor when finding references.
        references.put("includeAccessors", true);
        configuration.put("updateBuildConfiguration", "automatic");
        if (this.useEclipseBuildFiles) {
            imporT.put("maven", Map.of("enabled", false));
            imporT.put("gradle", Map.of("enabled", false));
        } else {
            imporT.put("maven", Map.of("enabled", true, "wrapper", Map.of("enabled", true)));
            imporT.put("gradle", Map.of("enabled", true, "wrapper", Map.of("enabled", true)));
        }
        autobuild.put("enabled", true);

        javaOptions.put("server", server);
        javaOptions.put("symbols", symbols);
        javaOptions.put("references", references);
        javaOptions.put("configuration", configuration);
        javaOptions.put("import", imporT);
        javaOptions.put("autobuild", autobuild);

        options.put("java", javaOptions);
        return options;
    }

    @Override
    public void setIo(IConsoleIO io) {
        SharedJdtLspServer.setIo(io); // give singleton the IO
    }

    /**
     * Strips the method signature (parentheses and parameters) from a method name string.
     *
     * @param methodName The full method name from the symbol object (e.g., "myMethod(int)").
     * @return The clean method name without the signature (e.g., "myMethod").
     */
    @Override
    public String resolveMethodName(String methodName) {
        final var cleanedName = methodName.replace('$', '.');
        int parenIndex = cleanedName.indexOf('(');

        // Remove any parameter signature (e.g., "myMethod(int)" -> "myMethod")
        return (parenIndex != -1) ? cleanedName.substring(0, parenIndex) : cleanedName;
    }

    @Override
    public Optional<String> getClassSource(String classFullName, boolean includeComments) {
        // JSP containers are dot-delimited and get rid of the '$'
        final String cleanedName = classFullName.replace('$', '.');
        return LspAnalyzer.super.getClassSource(cleanedName, includeComments);
    }

    @Override
    public String sanitizeType(String typeName) {
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
     * A helper method to convert a single, non-generic type name to its simple name, preserving array brackets.
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
                .thenApply(typeSymbols -> typeSymbols.stream()
                        .map(typeSymbol -> {
                            // First, read the full source text of the file.
                            final Optional<String> fullSourceOpt = LspAnalyzerHelper.getSourceForUriString(
                                    typeSymbol.getLocation().getLeft().getUri());
                            if (fullSourceOpt.isEmpty()) {
                                return Optional.<String>empty();
                            } else {
                                final String fullSource = fullSourceOpt.get();
                                final var eitherLocationForType = typeSymbol.getLocation();
                                if (eitherLocationForType.isLeft()) {
                                    return JdtSkeletonHelper.getSymbolSkeleton(
                                                    sharedServer,
                                                    eitherLocationForType.getLeft(),
                                                    fullSource,
                                                    headerOnly)
                                            .join();
                                } else {
                                    return Optional.<String>empty();
                                }
                            }
                        })
                        .flatMap(Optional::stream)
                        .collect(Collectors.toSet()))
                .join();

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

    // A regex to match anonymous class instantiation patterns from symbol names.
    private static final Pattern ANONYMOUS_CLASS_PATTERN =
            Pattern.compile("(?s)^new\\s+[\\w.]+(?:<.*?>)?\\s*\\(.*\\)\\s*\\{.*\\}.*", Pattern.DOTALL);

    @Override
    public boolean isAnonymousClass(SymbolKind kind, String name) {
        if (kind == SymbolKind.Class || kind == SymbolKind.Method) {
            if (name.isEmpty()) {
                return true;
            }
            // Check for the "new Type() {...}" pattern
            if (!name.startsWith("new ")) return false; // a cheap short-circuit
            else return ANONYMOUS_CLASS_PATTERN.matcher(name).matches();
        } else {
            return false;
        }
    }

    @Override
    public LintResult lintFiles(List<ProjectFile> files) {
        if (files.isEmpty()) {
            return new LintResult(List.of());
        }

        // Get the language client to access diagnostics
        var languageClient = (JdtLanguageClient) sharedServer.getLanguageClient();
        if (languageClient == null) {
            logger.warn("JDT language client not available for linting");
            return new LintResult(List.of());
        }

        // Clear existing diagnostics for these files
        languageClient.clearDiagnosticsForFiles(files);
        // Update files
        this.update(new HashSet<>(files));

        // Trigger analysis by refreshing the workspace
        // This will cause the LSP server to re-analyze the files and generate diagnostics
        try {
            sharedServer.refreshWorkspace(getWorkspace()).join();
        } catch (Exception e) {
            logger.warn("Error refreshing workspace for linting", e);
            return new LintResult(List.of());
        }

        try {
            languageClient.waitForDiagnosticsToSettle().join();
        } catch (Exception e) {
            logger.warn("Error waiting for diagnostics to settle, continuing", e);
        }

        // Collect diagnostics for the specified files
        var diagnostics = languageClient.getDiagnosticsForFiles(files);
        return new LintResult(diagnostics);
    }

    @Override
    public void close() {
        sharedServer.unregisterClient(this.projectRoot, getInitializationOptions(), getLanguage());
    }
}
