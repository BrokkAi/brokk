package io.github.jbellis.brokk.analyzer.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public final class SimpleLanguageClient implements LanguageClient {

    private final Logger logger = LoggerFactory.getLogger(SimpleLanguageClient.class);
    private final CountDownLatch serverReadyLatch;
    private final Map<String, CountDownLatch> workspaceReadyLatchMap;
    private final String language;
    private final int ERROR_LOG_LINE_LIMIT = 4;

    public SimpleLanguageClient(
            String language,
            CountDownLatch serverReadyLatch,
            Map<String, CountDownLatch> workspaceReadyLatchMap) {
        this.language = language;
        this.serverReadyLatch = serverReadyLatch;
        this.workspaceReadyLatchMap = workspaceReadyLatchMap;
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
            case Error -> {
                var messageLines = message.getMessage().lines().toList();
                // Avoid chained stack trace spam, these are recorded in the LSP logs elsewhere anyway
                if (messageLines.size() > ERROR_LOG_LINE_LIMIT) {
                    final var conciseMessageLines = new ArrayList<>(messageLines.stream().limit(ERROR_LOG_LINE_LIMIT).toList());
                    final var cachePath = LspServer.getCacheForLsp(language)
                            .resolve(".metadata")
                            .resolve(".log");
                    conciseMessageLines.add("See logs at '" + cachePath + "' for more details.");
                    messageLines = conciseMessageLines;
                }
                final var messageBody = messageLines.stream().collect(Collectors.joining(System.lineSeparator()));
                logger.error("[LSP-SERVER-LOG] {}", messageBody);
            }
            case Warning -> logger.warn("[LSP-SERVER-LOG] {}", message.getMessage());
            case Info -> {
                logger.info("[LSP-SERVER-LOG] {}", message.getMessage());

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
                                final var workspace = Path.of(arr[1].trim()).toUri().toString();
                                Optional.ofNullable(workspaceReadyLatchMap.get(workspace))
                                        .ifPresent(CountDownLatch::countDown);
                            });
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
