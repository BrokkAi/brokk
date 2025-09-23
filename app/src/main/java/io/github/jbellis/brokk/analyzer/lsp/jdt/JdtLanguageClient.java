package io.github.jbellis.brokk.analyzer.lsp.jdt;

import io.github.jbellis.brokk.analyzer.lsp.LspLanguageClient;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

public final class JdtLanguageClient extends LspLanguageClient {

    private volatile boolean suppressionAlerted = false;

    public JdtLanguageClient(
            String language, CountDownLatch serverReadyLatch, Map<String, CountDownLatch> workspaceReadyLatchMap) {
        super(language, serverReadyLatch, workspaceReadyLatchMap);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        String uri = diagnostics.getUri();
        try {
            final var path = Path.of(uri);
            if (!path.getFileName().toString().endsWith(".java")) {
                return; // early exit
            }
        } catch (Exception e) {
            logger.error("Unable to determine validity of diagnostic path argument {}", diagnostics, e);
        }

        var health = getBuildHealth();
        if (health != BuildHealth.HEALTHY) {
            var diags = diagnostics.getDiagnostics();
            if (diags != null && !diags.isEmpty()) {
                var filtered = new ArrayList<Diagnostic>(diags.size());
                int unresolvedCount = 0;
                for (var d : diags) {
                    var msg = d.getMessage();
                    var lower = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
                    boolean unresolvedLike = lower.contains("cannot be resolved")
                            || lower.contains("classpath is incomplete")
                            || lower.contains("class path is incomplete")
                            || lower.contains("build path is incomplete")
                            || lower.contains("project configuration is not up-to-date");
                    if (unresolvedLike) {
                        unresolvedCount++;
                        continue; // filter these in unhealthy state
                    }
                    filtered.add(d);
                }

                double ratio = diags.isEmpty() ? 0.0 : (double) unresolvedCount / (double) diags.size();
                if (ratio >= 0.80) {
                    // If the vast majority are unresolved-like, drop all to reduce noise
                    logger.debug(
                            "Suppressed {} diagnostics ({} unresolved-like; {:.0f}% of total) for {} due to unhealthy workspace ({})",
                            diags.size(), unresolvedCount, ratio * 100.0, uri);
                    if (!suppressionAlerted) {
                        suppressionAlerted = true;
                        alertUser(
                                "Java Language Server build/import appears unhealthy; suppressing unresolved-symbol diagnostics to reduce noise.\n"
                                        + "Fix the project build (Gradle/Maven/Eclipse) for full linting fidelity.");
                    }
                    return; // do not forward any diagnostics for this file
                } else if (unresolvedCount > 0) {
                    logger.debug(
                            "Filtered {} unresolved-like diagnostics out of {} for {} due to unhealthy workspace ({})",
                            unresolvedCount,
                            diags.size(),
                            uri,
                            health);
                    if (!suppressionAlerted) {
                        suppressionAlerted = true;
                        alertUser(
                                "Java Language Server build/import appears unhealthy; suppressing unresolved-symbol diagnostics to reduce noise.\n"
                                        + "Fix the project build (Gradle/Maven/Eclipse) for full linting fidelity.");
                    }
                    var newParams = new PublishDiagnosticsParams(diagnostics.getUri(), filtered);
                    super.publishDiagnostics(newParams);
                    return;
                }
            }
        }

        super.publishDiagnostics(diagnostics);
    }

    @Override
    public void logMessage(MessageParams message) {
        switch (message.getType()) {
            case Error -> {
                handleSystemError(message);
                // Also mark build issues if they manifest as errors
                if (isBuildPathIssue(message)) {
                    setBuildHealth(BuildHealth.BUILD_FAILED);
                }
            }
            case Warning -> {
                logger.warn("[LSP-SERVER-LOG] {}", message.getMessage());
                if (isBuildPathIssue(message)) {
                    setBuildHealth(BuildHealth.BUILD_FAILED);
                }
            }
            case Info -> {
                logger.trace("[LSP-SERVER-LOG] INFO: {}", message.getMessage());

                // On first useful info message, mark as INDEXING if we don't yet know health
                if (getBuildHealth() == BuildHealth.UNKNOWN) {
                    setBuildHealth(BuildHealth.INDEXING);
                }
                if (isBuildPathIssue(message)) {
                    setBuildHealth(BuildHealth.BUILD_FAILED);
                }

                if (message.getMessage().endsWith("build jobs finished")) {
                    // Healthy after build/import jobs complete
                    setBuildHealth(BuildHealth.HEALTHY);
                    // This is a good way we can tell when the server is ready
                    this.getServerReadyLatch().countDown();
                } else if (message.getMessage().lines().findFirst().orElse("").contains("Projects:")) {
                    // The server gives a project dump when a workspace is added, and this is a good way we can tell
                    // when indexing is done. We need to parse these, and countdown any workspaces featured, e.g.
                    // 'brokk: /home/dave/Workspace/BrokkAi/brokk'
                    message.getMessage()
                            .lines()
                            .map(s -> s.split(": "))
                            .filter(arr -> arr.length == 2 && !arr[0].startsWith(" "))
                            .forEach(arr -> {
                                String workspace =
                                        Path.of(arr[1].trim()).toUri().toString();
                                try {
                                    // Follow symlinks to "canonical" path if possible
                                    workspace = Path.of(arr[1].trim())
                                            .toRealPath()
                                            .toUri()
                                            .toString();
                                } catch (IOException e) {
                                    logger.warn("Unable to resolve real path for {}", workspace);
                                }
                                final var finalWorkspace = workspace;
                                Optional.ofNullable(workspaceReadyLatchMap.get(finalWorkspace))
                                        .ifPresent(latch -> {
                                            logger.info("Marking {} as ready", finalWorkspace);
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
            setBuildHealth(BuildHealth.IMPORT_FAILED);
            alertUser(
                    "Failed to import Java project, code analysis will be limited.\nPlease ensure the project can build via Gradle, Maven, or Eclipse.");
            workspaceReadyLatchMap.forEach((workspace, latch) -> {
                logger.debug("Marking {} as ready", workspace);
                latch.countDown();
            });
        } else if (isOutOfMemoryError(message)) {
            setBuildHealth(BuildHealth.OUT_OF_MEMORY);
            alertUser(
                    "The Java Language Server ran out of memory. Consider increasing this under 'Settings' -> 'Analyzers' -> 'Java'.");
        } else if (isCachePossiblyCorrupted(message)) {
            setBuildHealth(BuildHealth.CACHE_CORRUPT);
            alertUser("The Java Language Server cache may be corrupted, automatically clearing now.");
            // Shut down the current server and rebuild a fresh cache
            SharedJdtLspServer.getInstance().clearCache();
        } else if (isBuildPathIssue(message)) {
            setBuildHealth(BuildHealth.BUILD_FAILED);
        } else if (isUnhandledError(message)) {
            alertUser("Failed to import Java project due to unknown error. Please look at $HOME/.brokk/debug.log.");
        }
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

    private boolean isBuildPathIssue(MessageParams message) {
        var m = message.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("classpath is incomplete")
                || m.contains("build path is incomplete")
                || m.contains("project configuration is not up-to-date");
    }
}
