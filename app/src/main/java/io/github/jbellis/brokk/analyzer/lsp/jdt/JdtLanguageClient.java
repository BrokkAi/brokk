package io.github.jbellis.brokk.analyzer.lsp.jdt;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import io.github.jbellis.brokk.analyzer.lsp.LspStatus;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JdtLanguageClient implements LanguageClient {

    private final Logger logger = LoggerFactory.getLogger(JdtLanguageClient.class);
    private final CountDownLatch serverReadyLatch;
    private final Map<String, CountDownLatch> workspaceReadyLatchMap;
    private final String language;
    private final int ERROR_LOG_LINE_LIMIT = 4;
    private @Nullable IConsoleIO io;
    private final Set<String> accumulatedErrors = new HashSet<>();

    public JdtLanguageClient(
            String language, CountDownLatch serverReadyLatch, Map<String, CountDownLatch> workspaceReadyLatchMap) {
        this.language = language;
        this.serverReadyLatch = serverReadyLatch;
        this.workspaceReadyLatchMap = workspaceReadyLatchMap;
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

    private void alertUser(String message) {
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

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        // These diagnostics are the server reporting linting/compiler issues related to the code itself
        diagnostics.getDiagnostics().forEach(diagnostic -> {
            // Errors might be useful to understand if certain symbols are not resolved properly
            logger.trace("[LSP-SERVER-DIAGNOSTICS] [{}] {}", diagnostic.getSeverity(), diagnostic.getMessage());
        });
    }

    @Override
    @Nullable
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams r) {
        return null;
    }

    @JsonNotification("language/eventNotification")
    public void languageEvent(Object params) {
        logger.info("Language Event {}", params);
    }

    @Override
    public void logMessage(MessageParams message) {
        switch (message.getType()) {
            case Error -> handleSystemError(message);
            case Warning -> logger.warn("[LSP-SERVER-LOG] {}", message.getMessage());
            case Info -> {
                logger.trace("[LSP-SERVER-LOG] INFO: {}", message.getMessage());

                if (message.getMessage().endsWith("build jobs finished")) {
                    // This is a good way we can tell when the server is ready
                    serverReadyLatch.countDown();
                } else if (message.getMessage().lines().findFirst().orElse("").contains("Projects:")) {
                    // The server gives a project dump when a workspace is added, and this is a good way we can tell
                    // when indexing is done. We need to parse these, and countdown any workspaces featured, e.g.
                    // 'brokk: /home/dave/Workspace/BrokkAi/brokk'
                    message.getMessage()
                            .lines()
                            .map(s -> s.split(": "))
                            .filter(arr -> arr.length == 2 && !arr[0].startsWith(" "))
                            .forEach(arr -> {
                                final var workspace =
                                        Path.of(arr[1].trim()).toUri().toString();
                                Optional.ofNullable(workspaceReadyLatchMap.get(workspace))
                                        .ifPresent(latch -> {
                                            logger.info("Marking {} as ready", workspace);
                                            latch.countDown();
                                        });
                            });
                }
            }
            default -> logger.trace("[LSP-SERVER-LOG] DEBUG: {}", message.getMessage());
        }
    }

    private void handleSystemError(MessageParams message) {
        var messageLines = message.getMessage().lines().toList();
        // Avoid chained stack trace spam, these are recorded in the LSP logs elsewhere anyway
        if (messageLines.size() > ERROR_LOG_LINE_LIMIT) {
            final var conciseMessageLines = new ArrayList<>(
                    messageLines.stream().limit(ERROR_LOG_LINE_LIMIT).toList());
            final var cachePath =
                    LspServer.getCacheForLsp(language).resolve(".metadata").resolve(".log");
            conciseMessageLines.add("See logs at '" + cachePath + "' for more details.");
            messageLines = conciseMessageLines;
        }
        final var messageBody = messageLines.stream().collect(Collectors.joining(System.lineSeparator()));

        logger.error("[LSP-SERVER-LOG] {}", messageBody);

        // There is the possibility that the message indicates a complete failure, we should countdown the
        // latches to unblock the clients
        if (failedToImportProject(message)) {
            logger.warn("Failed to import projects, counting down all latches");
            alertUser(
                    "Failed to import Java project, code analysis will be limited.\nPlease ensure the project can build via Gradle, Maven, or Eclipse.");
            workspaceReadyLatchMap.forEach((workspace, latch) -> {
                logger.debug("Marking {} as ready", workspace);
                latch.countDown();
            });
        } else if (isOutOfMemoryError(message)) {
            alertUser(
                    "The Java Language Server ran out of memory. Consider increasing this under 'Settings' -> 'Analyzers' -> 'Java'.");
        } else if (isCachePossiblyCorrupted(message)) {
            alertUser("The Java Language Server cache may be corrupted, automatically clearing now.");
            // Shut down the current server and rebuild a fresh cache
            SharedJdtLspServer.getInstance().clearCache();
        } else if (isUnhandledError(message)) {
            alertUser("Failed to import Java project due to unknown error. Please look at $HOME/.brokk/debug.log.");
        }
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

    private boolean failedToImportProject(MessageParams message) {
        return message.getMessage().contains("Failed to import projects")
                || message.getMessage().contains("Problems occurred when invoking code from plug-in:");
    }

    private boolean isOutOfMemoryError(MessageParams message) {
        return message.getMessage().contains("Java heap space");
    }

    private boolean isCachePossiblyCorrupted(MessageParams message) {
        return message.getMessage().contains("Could not write metadata for");
    }

    private boolean isUnhandledError(MessageParams message) {
        return message.getMessage().contains("Unhandled error");
    }
}
