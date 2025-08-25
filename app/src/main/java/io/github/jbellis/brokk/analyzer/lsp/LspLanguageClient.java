package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.LintResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LspLanguageClient implements LanguageClient {

    protected final Logger logger = LoggerFactory.getLogger(LspLanguageClient.class);

    protected final String language;
    private final CountDownLatch serverReadyLatch;
    protected final Map<String, CountDownLatch> workspaceReadyLatchMap;

    private final int MAX_DIAGNOSTICS_PER_FILE = 500;
    protected final int ERROR_LOG_LINE_LIMIT = 4;
    private final int DIAGNOSTIC_SETTLE_INTERVAL = 1000;

    private @Nullable IConsoleIO io;
    private final Set<String> accumulatedErrors = new HashSet<>();
    protected final Map<String, List<LintResult.LintDiagnostic>> fileDiagnostics = new ConcurrentHashMap<>();
    private final AtomicLong lastDiagnosticTime = new AtomicLong(0);
    private final ScheduledExecutorService diagnosticsSettleExecutor;
    private volatile @Nullable ScheduledFuture<?> settleTask;
    private volatile @Nullable CompletableFuture<Void> settleFuture;

    protected LspLanguageClient(
            String language, CountDownLatch serverReadyLatch, Map<String, CountDownLatch> workspaceReadyLatchMap) {
        this.language = language;
        this.serverReadyLatch = serverReadyLatch;
        this.workspaceReadyLatchMap = workspaceReadyLatchMap;
        this.diagnosticsSettleExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "diagnostics-settle-" + language);
            thread.setDaemon(true);
            return thread;
        });
    }

    protected CountDownLatch getServerReadyLatch() {
        return serverReadyLatch;
    }

    /**
     * Sets the IO to report client-facing diagnostics with.
     *
     * @param io the IO object to use.
     */
    public void setIo(@Nullable IConsoleIO io) {
        this.io = io;
        if (!accumulatedErrors.isEmpty()) {
            final var accumulatedMessage = String.join(System.lineSeparator(), accumulatedErrors);
            alertUser(accumulatedMessage);
            accumulatedErrors.clear();
        }
    }

    protected void alertUser(String message) {
        if (io != null) {
            io.systemOutput(message);
        } else {
            accumulatedErrors.add(message);
        }
    }

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void showMessage(MessageParams messageParams) {
        logger.info("[LSP-SERVER-SHOW-MESSAGE] {}", messageParams);
    }

    @JsonNotification("language/eventNotification")
    public void languageEvent(Object params) {
        logger.info("Language Event {}", params);
    }

    @Override
    @Nullable
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams r) {
        return null;
    }

    @JsonNotification("language/status")
    public void languageStatus(LspStatus message) {
        final var kind = message.type();
        final var msg = message.message();
        logger.debug("[LSP-SERVER-STATUS] {}: {}", kind, msg);
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        // Acknowledge the server's request and return a completed future.
        // This satisfies the protocol and prevents an exception.
        logger.trace("Server requested to register capabilities: {}", params.getRegistrations());
        return CompletableFuture.completedFuture(null);
    }

    /** Clears diagnostics for the specified files. */
    public void clearDiagnosticsForFiles(List<ProjectFile> files) {
        files.stream()
                .map(ProjectFile::absPath)
                .map(this::safeResolvePath)
                .map(Path::toString)
                .forEach(fileDiagnostics::remove);
    }

    protected Path safeResolvePath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            logger.error("Cannot resolve path {}", path, e);
        }
        return path;
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        if (diagnostics.getDiagnostics().isEmpty()) return;

        // Update last diagnostic time
        lastDiagnosticTime.set(System.currentTimeMillis());
        scheduleSettleCheck();

        String uri = diagnostics.getUri();
        String filePath = uriToFilePath(uri);

        List<LintResult.LintDiagnostic> lintDiagnostics = diagnostics.getDiagnostics().stream()
                .map(diagnostic -> new LintResult.LintDiagnostic(
                        filePath,
                        diagnostic.getRange().getStart().getLine() + 1, // LSP is 0-based, we want 1-based
                        diagnostic.getRange().getStart().getCharacter() + 1,
                        mapSeverity(diagnostic.getSeverity()),
                        diagnostic.getMessage(),
                        diagnostic.getCode() != null ? diagnostic.getCode().getLeft() : ""))
                .toList();

        // Store diagnostics, limiting size to prevent memory issues
        fileDiagnostics.compute(filePath, (key, existing) -> {
            var combined = new ArrayList<LintResult.LintDiagnostic>();
            if (existing != null) {
                combined.addAll(existing);
            }
            combined.addAll(lintDiagnostics);

            // Keep only the most recent diagnostics if we exceed the limit
            if (combined.size() > MAX_DIAGNOSTICS_PER_FILE) {
                return combined.subList(combined.size() - MAX_DIAGNOSTICS_PER_FILE, combined.size());
            }
            return combined;
        });

        diagnostics.getDiagnostics().forEach(diagnostic -> {
            // Errors might be useful to understand if certain symbols are not resolved properly
            logger.trace("[LSP-SERVER-DIAGNOSTICS] [{}] {}", diagnostic.getSeverity(), diagnostic.getMessage());
        });
    }

    protected String uriToFilePath(String uri) {
        try {
            return Paths.get(URI.create(uri)).toRealPath().toString();
        } catch (Exception e) {
            logger.warn("Failed to convert URI to file path: {}", uri, e);
            return uri;
        }
    }

    /** Gets diagnostics for the specified files. */
    public List<LintResult.LintDiagnostic> getDiagnosticsForFiles(List<ProjectFile> files) {
        return files.stream()
                .map(ProjectFile::absPath)
                .map(this::safeResolvePath)
                .map(Path::toString)
                .flatMap(filePath -> fileDiagnostics.getOrDefault(filePath, List.of()).stream())
                .toList();
    }

    /**
     * Returns a CompletableFuture that completes when diagnostics have stopped coming in for at least 1 second. If
     * diagnostics are already settled, returns a completed future.
     */
    public CompletableFuture<Void> waitForDiagnosticsToSettle() {
        try {
            // We need to give diagnostics a moment to start
            Thread.sleep(250);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for diagnostics to settle");
        }

        var now = System.currentTimeMillis();
        var lastDiagnostic = lastDiagnosticTime.get();

        // If no diagnostics have been received yet, or they're already settled
        if (lastDiagnostic == 0 || (now - lastDiagnostic) >= DIAGNOSTIC_SETTLE_INTERVAL) {
            return CompletableFuture.completedFuture(null);
        }

        // Create or return existing settle future
        if (settleFuture == null || settleFuture.isDone()) {
            settleFuture = new CompletableFuture<>();
        }

        return settleFuture;
    }

    private void scheduleSettleCheck() {
        // Cancel existing task if any
        var currentTask = settleTask;
        if (currentTask != null) {
            currentTask.cancel(false);
        }

        // Schedule new task to check if diagnostics have settled
        settleTask = diagnosticsSettleExecutor.schedule(
                () -> {
                    var now = System.currentTimeMillis();
                    var lastDiagnostic = lastDiagnosticTime.get();

                    if ((now - lastDiagnostic) >= DIAGNOSTIC_SETTLE_INTERVAL) {
                        // Diagnostics have settled
                        var future = settleFuture;
                        if (future != null && !future.isDone()) {
                            future.complete(null);
                        }
                    }
                },
                DIAGNOSTIC_SETTLE_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    protected LintResult.LintDiagnostic.Severity mapSeverity(DiagnosticSeverity severity) {
        return switch (severity) {
            case Error -> LintResult.LintDiagnostic.Severity.ERROR;
            case Warning -> LintResult.LintDiagnostic.Severity.WARNING;
            case Information -> LintResult.LintDiagnostic.Severity.INFO;
            case Hint -> LintResult.LintDiagnostic.Severity.HINT;
        };
    }
}
