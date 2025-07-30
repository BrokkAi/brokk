package io.github.jbellis.brokk.analyzer.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public final class SimpleLanguageClient implements LanguageClient {

    private final Logger logger = LoggerFactory.getLogger(SimpleLanguageClient.class);
    private final CountDownLatch serverReadyLatch;

    public SimpleLanguageClient(CountDownLatch serverReadyLatch) {
        this.serverReadyLatch = serverReadyLatch;
    }

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        logger.info("[LSP-SERVER-SHOW-MESSAGE] {}", messageParams);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        // These diagnostics are the server reporting linting/compiler issues related to the code itself
        diagnostics.getDiagnostics().forEach(diagnostic -> {
            if (Objects.equals(diagnostic.getSeverity(), DiagnosticSeverity.Error)) {
                // Errors might be useful to understand if certain symbols are not resolved properly
                logger.debug("[LSP-SERVER-DIAGNOSTICS] [{}] {}", diagnostic.getSeverity(), diagnostic.getMessage());
            } else {
                logger.trace("[LSP-SERVER-DIAGNOSTICS] [{}] {}", diagnostic.getSeverity(), diagnostic.getMessage());
            }
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
            case Error -> {
                // Avoid chained stack trace spam
                final var conciseMessage = message.getMessage().lines()
                        .takeWhile(line -> !line.startsWith("Caused by"))
                        .collect(Collectors.joining(System.lineSeparator()));
                logger.error("[LSP-SERVER-LOG] {}", conciseMessage);
            }
            case Warning -> logger.warn("[LSP-SERVER-LOG] {}", message.getMessage());
            case Info -> {
                logger.info("[LSP-SERVER-LOG] {}", message.getMessage());
                if (message.getMessage().endsWith("build jobs finished")) {
                    // This is a good way we can tell when indexing is done
                    serverReadyLatch.countDown();
                }
            }
            default -> logger.debug("[LSP-SERVER-LOG] {}", message.getMessage());
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
        logger.info("Server requested to register capabilities: {}", params.getRegistrations());
        return CompletableFuture.completedFuture(null);
    }
}
