package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.BuildInfo;
import io.github.jbellis.brokk.util.FileUtils;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class LspServer implements LspFileUtilities {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Nullable
    private Process serverProcess;
    @Nullable
    private LanguageServer languageServer;
    @Nullable
    private CompletableFuture<Void> serverInitialized;

    private final AtomicInteger clientCounter = new AtomicInteger(0);
    @NotNull
    private CountDownLatch serverReadyLatch = new CountDownLatch(1);
    protected final Set<Path> activeWorkspaces = ConcurrentHashMap.newKeySet();
    private final Map<Path, Set<String>> workspaceExclusions = new ConcurrentHashMap<>();

    /**
     * Executes a callback function asynchronously with the LanguageServer instance
     * once the server is initialized. This method is non-blocking. This is the intended way of accessing the language
     * server for server-related tasks.
     *
     * @param callback The function to execute, which accepts the @NotNull LanguageServer instance.
     */
    protected void whenInitialized(@NotNull Consumer<LanguageServer> callback) {
        if (serverInitialized == null) {
            logger.warn("Server is not running or initializing; cannot execute callback.");
        } else {
            serverInitialized.thenRunAsync(() -> {
                // this.languageServer should be non-null here
                assert this.languageServer != null;
                try {
                    // TODO: Ensure this is is stable for incremental update
                    if (!serverReadyLatch.await(5000, TimeUnit.MILLISECONDS)) {
                        logger.warn("Server is taking longer than expected to complete indexing, continuing with partial indexes.");
                    }
                } catch (InterruptedException e) {
                    logger.debug("Interrupted while waiting for initialization, the server may not be properly indexed", e);
                }
                callback.accept(this.languageServer);
            }).exceptionally(ex -> {
                logger.error("Failed to execute callback after server initialization", ex);
                return null; // Complete the exceptionally stage
            }).join();
        }
    }

    /**
     * Asynchronously executes a query against the language server once it's initialized.
     * This is the intended way of accessing the language server for operations that return a value.
     *
     * @param callback The function to execute, which accepts the @NotNull LanguageServer instance and returns a value.
     * @param <T>      The type of the value returned by the callback.
     * @return A CompletableFuture that will be completed with the result of the callback.
     */
    public <T> CompletableFuture<T> query(@NotNull Function<LanguageServer, T> callback) {
        if (serverInitialized == null) {
            logger.warn("Server is not running or initializing; cannot execute query.");
            return CompletableFuture.failedFuture(new IllegalStateException("Server is not running or has been shut down."));
        }

        // Chain the callback to run after the serverInitialized future completes.
        // If serverInitialized fails, the exception will automatically propagate to the returned future.
        return serverInitialized.thenApplyAsync(ignoredVoid -> {
            assert this.languageServer != null;
            return callback.apply(this.languageServer);
        });
    }

    protected void startServer(Path initialWorkspace) throws IOException {
        logger.info("First client connected. Starting JDT Language Server...");
        final Path serverHome = unpackLspServer("jdt");
        final Path launcherJar = findFile(serverHome, "org.eclipse.equinox.launcher_");
        final Path configDir = findConfigDir(serverHome);
        final Path cache = Path.of(System.getProperty("user.home"), ".brokk", ".jdt-ls-data").toAbsolutePath();
        FileUtils.deleteDirectoryRecursively(cache); // start on a fresh cache

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-Declipse.application=org.eclipse.jdt.ls.core.id1", "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product", "-noverify", "-Xmx1G",
                "-jar", launcherJar.toString(),
                "-configuration", configDir.toString(),
                "-data", cache.toString()
        );
        this.serverProcess = pb.start();

        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                new SimpleLanguageClient(this.serverReadyLatch),
                serverProcess.getInputStream(),
                serverProcess.getOutputStream()
        );
        this.languageServer = launcher.getRemoteProxy();
        launcher.startListening();

        InitializeParams params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(initialWorkspace.toUri().toString(), initialWorkspace.getFileName().toString())));
        params.setClientInfo(new ClientInfo("Brokk", BuildInfo.version));
        // Java specific
        Map<String, Object> javaSettings = Map.of(
                "symbols", Map.of("includeSourceMethodDeclarations", true)
        );
        params.setInitializationOptions(javaSettings);

        final var capabilities = getCapabilities();
        logger.debug("Setting JDT capabilities {}", capabilities);
        params.setCapabilities(capabilities);

        this.serverInitialized = languageServer.initialize(params).thenRun(() -> {
            if (this.languageServer != null) {
                // will be reduced by one when server signals readiness
                serverReadyLatch = new CountDownLatch(1);
                languageServer.initialized(new InitializedParams());
                logger.info("JDT LS Initialized");
            } else {
                throw new IllegalStateException("JDT LS could not be initialized.");
            }
        });

        try {
            serverInitialized.join();
            addWorkspaceFolder(initialWorkspace);
            logger.debug("Server initialization confirmed with initial workspace: {}", initialWorkspace);
        } catch (CompletionException e) {
            logger.error("Server initialization failed", e.getCause());
            throw e; // Re-throw the exception
        }
    }

    protected ClientCapabilities getCapabilities() {
        // 1. Create a fully-featured ClientCapabilities object.
        ClientCapabilities capabilities = new ClientCapabilities();
        WorkspaceClientCapabilities workspaceCapabilities = new WorkspaceClientCapabilities();

        // 2. Explicitly declare support for workspace symbols.
        SymbolCapabilities symbolCapabilities = new SymbolCapabilities();

        // 3. Declare that we support all kinds of symbols (Class, Method, Field, etc.).
        SymbolKindCapabilities symbolKindCapabilities = new SymbolKindCapabilities();
        symbolKindCapabilities.setValueSet(
                Arrays.stream(SymbolKind.values()).collect(Collectors.toList())
        );
        symbolCapabilities.setSymbolKind(symbolKindCapabilities);
        workspaceCapabilities.setSymbol(symbolCapabilities);
        workspaceCapabilities.setWorkspaceFolders(true);

        capabilities.setWorkspace(workspaceCapabilities);

        // Explicitly declare support for text document synchronization
        TextDocumentClientCapabilities textDocumentCapabilities = new TextDocumentClientCapabilities();
        final var syncCapabilities = new SynchronizationCapabilities();
        syncCapabilities.setDidSave(true);
        syncCapabilities.setWillSave(true);
        syncCapabilities.setWillSaveWaitUntil(true);
        textDocumentCapabilities.setSynchronization(syncCapabilities);

        textDocumentCapabilities.setDefinition(new DefinitionCapabilities());
        textDocumentCapabilities.setReferences(new ReferencesCapabilities());

        // Explicitly add support for call hierarchy and type hierarchy
        textDocumentCapabilities.setCallHierarchy(new CallHierarchyCapabilities(true));
        textDocumentCapabilities.setTypeHierarchy(new TypeHierarchyCapabilities(true));

        // Add support for textDocument/documentSymbol
        DocumentSymbolCapabilities documentSymbolCapabilities = new DocumentSymbolCapabilities();
        documentSymbolCapabilities.setHierarchicalDocumentSymbolSupport(true); // Request a tree structure
        textDocumentCapabilities.setDocumentSymbol(documentSymbolCapabilities);

        // Set the text document capabilities on the main capabilities object
        capabilities.setTextDocument(textDocumentCapabilities);

        return capabilities;
    }

    protected void shutdownServer() {
        logger.info("Last client disconnected. Shutting down JDT Language Server...");
        try {
            if (languageServer != null) {
                languageServer.shutdown().get(5, TimeUnit.SECONDS);
                languageServer.exit();
            }
            if (serverProcess != null) {
                serverProcess.destroyForcibly();
                serverProcess.waitFor(5, TimeUnit.SECONDS);
            }
            logger.info("JDT LS shut down successfully.");
        } catch (Exception e) {
            logger.error("Error shutting down JDT LS", e);
        } finally {
            this.languageServer = null;
            this.serverProcess = null;
            this.serverInitialized = null;
        }
    }

    /**
     * Registers a new client (JdtAnalyzer instance). Starts the server if this is the first client.
     *
     * @param projectPath     The workspace path for the new client.
     * @param excludePatterns A set of glob patterns to exclude for this workspace.
     */
    public synchronized void registerClient(Path projectPath, Set<String> excludePatterns) throws IOException {
        final var projectPathAbsolute = projectPath.toAbsolutePath().normalize();
        workspaceExclusions.put(projectPathAbsolute, excludePatterns);
        if (clientCounter.getAndIncrement() == 0) {
            startServer(projectPathAbsolute);
        } else {
            addWorkspaceFolder(projectPathAbsolute);
        }
        activeWorkspaces.add(projectPathAbsolute);
        updateServerConfiguration(); // Send combined configuration
        logger.debug("Registered workspace: {}. Active clients: {}", projectPathAbsolute, clientCounter.get());
    }

    /**
     * Unregisters a client. Shuts down the server if this is the last client.
     *
     * @param projectPath The workspace path of the client being closed.
     */
    public synchronized void unregisterClient(Path projectPath) {
        final var projectPathAbsolute = projectPath.toAbsolutePath().normalize();
        removeWorkspaceFolder(projectPathAbsolute);
        activeWorkspaces.remove(projectPathAbsolute);
        workspaceExclusions.remove(projectPathAbsolute);
        logger.debug("Unregistered workspace: {}. Active clients: {}", projectPathAbsolute, clientCounter.get());
        if (clientCounter.decrementAndGet() == 0) {
            shutdownServer();
        } else {
            updateServerConfiguration(); // Update config after removal
        }
    }


    private void addWorkspaceFolder(Path folderPath) {
        if (activeWorkspaces.contains(folderPath)) return;

        whenInitialized((server) -> {
            WorkspaceFolder newFolder = new WorkspaceFolder(folderPath.toUri().toString(), folderPath.getFileName().toString());
            WorkspaceFoldersChangeEvent event = new WorkspaceFoldersChangeEvent(List.of(newFolder), List.of());
            server.getWorkspaceService().didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(event));
            logger.debug("Added workspace folder: {}", folderPath);
        });
    }

    private void removeWorkspaceFolder(Path folderPath) {
        whenInitialized((server) -> {
            WorkspaceFolder folderToRemove = new WorkspaceFolder(folderPath.toUri().toString(), folderPath.getFileName().toString());
            WorkspaceFoldersChangeEvent event = new WorkspaceFoldersChangeEvent(List.of(), List.of(folderToRemove));
            server.getWorkspaceService().didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(event));
            logger.debug("Removed workspace folder: {}", folderPath);
        });
    }

    /**
     * Builds a combined configuration from all active workspaces and sends it to the server.
     */
    private void updateServerConfiguration() {
        whenInitialized((server) -> {
            // Combine all exclusion patterns from all workspaces into one map
            Map<String, Boolean> combinedExclusions = new HashMap<>();
            for (Set<String> patterns : workspaceExclusions.values()) {
                for (String pattern : patterns) {
                    combinedExclusions.put(pattern, true);
                }
            }

            // Build the settings structure that JDT LS expects
            Map<String, Object> filesSettings = Map.of("exclude", combinedExclusions);
            Map<String, Object> javaSettings = Map.of("files", filesSettings);
            Map<String, Object> settings = Map.of("java", javaSettings);

            // Send the didChangeConfiguration notification
            DidChangeConfigurationParams params = new DidChangeConfigurationParams(settings);
            server.getWorkspaceService().didChangeConfiguration(params);
            logger.debug("Updated server configuration with combined exclusions: {}", combinedExclusions.keySet());
        });
    }

    public CompletableFuture<Object> refreshWorkspace() {
        return query((server) -> {
            ExecuteCommandParams params = new ExecuteCommandParams(
                    "java.project.buildWorkspace",
                    List.of()
            );
            return server.getWorkspaceService().executeCommand(params);
        });
    }

    public static class SimpleLanguageClient implements LanguageClient {

        private final CountDownLatch serverReadyLatch;

        public SimpleLanguageClient(CountDownLatch serverReadyLatch) {
            this.serverReadyLatch = serverReadyLatch;
        }

        @NotNull
        private final Logger logger = LoggerFactory.getLogger(SimpleLanguageClient.class);

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public final void showMessage(MessageParams messageParams) {
            logger.info("[showMessage] {}", messageParams);
        }

        @Override
        public final void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            try {
                var diagnosticsAbsPath = Path.of(new URI(diagnostics.getUri())).toAbsolutePath().toString();
                if (diagnostics.getDiagnostics().isEmpty()) logger.info("Diagnostics empty for {}", diagnosticsAbsPath);
                diagnostics.getDiagnostics().forEach(diagnostic -> {
                    logger.debug("[Diagnostic] [{}] {}", diagnostic.getSeverity(), diagnostic.getMessage());
                });
            } catch (URISyntaxException e) {
                logger.error("Error parsing a URI from the LSP: " + diagnostics.getUri(), e);
            }
        }

        @Override
        @Nullable
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams r) {
            return null;
        }

        @Override
        public void logMessage(MessageParams message) {
            logger.debug("[LSP-SERVER] {}: {}", message.getType(), message.getMessage());
        }

        @JsonNotification("language/status")
        public void languageStatus(LspStatus message) {
            logger.debug("[LSP-SERVER] {}: {}", message.type(), message.message());
            if (Objects.equals(message.type(), "Started") && Objects.equals(message.message(), "Ready")) {
                serverReadyLatch.countDown();
            }
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            // Acknowledge the server's request and return a completed future.
            // This satisfies the protocol and prevents an exception.
            logger.info("Server requested to register capabilities: {}", params.getRegistrations());
            return CompletableFuture.completedFuture(null);
        }
    }

}
