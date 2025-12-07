package ai.brokk.gui.util;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.prompts.ArchitectPrompts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Guards against loading excessively large content into the context.
 * Estimates token count before loading and prompts the user for confirmation
 * if the estimate exceeds a threshold relative to the model's max input tokens.
 */
public final class ContextSizeGuard {
    private static final Logger logger = LogManager.getLogger(ContextSizeGuard.class);

    // Uses file size heuristic (~4 bytes/token) instead of actual tokenizer for speed.
    // Reading file contents to tokenize would defeat the purpose of a fast pre-flight check.
    // Code is slightly more token-dense (~3 bytes/token), so this may underestimate,
    // which is the safe direction - we warn earlier rather than later.
    private static final int BYTES_PER_TOKEN_ESTIMATE = 4;
    private static final int MAX_FILES_TO_ENUMERATE = 10_000;
    private static final double HARD_LIMIT_MULTIPLIER = 2.0;

    private ContextSizeGuard() {}

    public record SizeEstimate(int fileCount, long estimatedTokens, boolean isTruncated) {}

    /**
     * Estimates tokens for a collection of files/directories using file size heuristic.
     * Does not read file contents - uses bytes/4 approximation.
     */
    public static SizeEstimate estimateTokens(Collection<ProjectFile> files) {
        long totalBytes = 0;
        int fileCount = 0;
        boolean truncated = false;

        for (var pf : files) {
            var path = pf.absPath();
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    var iterator = walk.filter(Files::isRegularFile).iterator();
                    while (iterator.hasNext() && fileCount < MAX_FILES_TO_ENUMERATE) {
                        var file = iterator.next();
                        try {
                            totalBytes += Files.size(file);
                            fileCount++;
                        } catch (IOException ignored) {
                            // Skip files we can't read
                        }
                    }
                    if (iterator.hasNext()) {
                        truncated = true;
                    }
                } catch (IOException e) {
                    logger.debug("Error walking directory {}: {}", path, e.getMessage());
                }
            } else if (Files.isRegularFile(path)) {
                try {
                    totalBytes += Files.size(path);
                    fileCount++;
                } catch (IOException ignored) {
                    // Skip files we can't read
                }
            }
        }

        long estimatedTokens = totalBytes / BYTES_PER_TOKEN_ESTIMATE;
        return new SizeEstimate(fileCount, estimatedTokens, truncated);
    }

    /**
     * Checks if the estimated tokens exceed the threshold and prompts for confirmation if so.
     * Runs estimation in background to avoid blocking EDT.
     *
     * @param files Files to add
     * @param chrome Chrome instance for dialogs and model info
     * @param onConfirmed Called with true if user confirms or no confirmation needed,
     *                    false if user cancels
     */
    public static void checkAndConfirm(Collection<ProjectFile> files, Chrome chrome, Consumer<Boolean> onConfirmed) {
        CompletableFuture.supplyAsync(() -> estimateTokens(files))
                .thenAccept(estimate -> {
                    var contextManager = chrome.getContextManager();
                    var service = contextManager.getService();

                    // Get threshold based on current model
                    var model = chrome.getInstructionsPanel().getSelectedModel();
                    int maxInputTokens = service.getMaxInputTokens(model);

                    long hardLimit = (long) (maxInputTokens * HARD_LIMIT_MULTIPLIER);
                    long warningThreshold = (long) (maxInputTokens * ArchitectPrompts.WORKSPACE_WARNING_THRESHOLD);

                    // Hard limit - reject without asking
                    if (estimate.estimatedTokens() > hardLimit) {
                        chrome.toolError(
                                formatHardLimitMessage(estimate, maxInputTokens, hardLimit), "Context Size Limit");
                        onConfirmed.accept(false);
                        return;
                    }

                    // Under warning threshold - allow without asking
                    if (estimate.estimatedTokens() <= warningThreshold) {
                        onConfirmed.accept(true);
                        return;
                    }

                    // Between warning and hard limit - ask for confirmation
                    SwingUtilities.invokeLater(() -> {
                        var message = formatConfirmationMessage(estimate, maxInputTokens);
                        int result = chrome.showConfirmDialog(
                                message,
                                "Large Context Warning",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        onConfirmed.accept(result == JOptionPane.YES_OPTION);
                    });
                })
                .exceptionally(ex -> {
                    logger.error("Error estimating context size", ex);
                    // On error, allow the operation to proceed
                    onConfirmed.accept(true);
                    return null;
                });
    }

    private static String formatConfirmationMessage(SizeEstimate estimate, int maxInputTokens) {
        var sb = new StringBuilder();
        sb.append("You are about to add ");
        sb.append(String.format("%,d", estimate.fileCount()));
        sb.append(" file(s) with an estimated ~");
        sb.append(String.format("%,d", estimate.estimatedTokens()));
        sb.append(" tokens.\n\n");

        if (estimate.isTruncated()) {
            sb.append("(File enumeration was truncated at ");
            sb.append(String.format("%,d", MAX_FILES_TO_ENUMERATE));
            sb.append(" files)\n\n");
        }

        sb.append("This exceeds ");
        sb.append(String.format("%.0f%%", ArchitectPrompts.WORKSPACE_WARNING_THRESHOLD * 100));
        sb.append(" of the current model's context window (");
        sb.append(String.format("%,d", maxInputTokens));
        sb.append(" tokens).\n");
        sb.append("Large context sizes may cause performance issues.\n\n");
        sb.append("Do you want to continue?");

        return sb.toString();
    }

    private static String formatHardLimitMessage(SizeEstimate estimate, int maxInputTokens, long hardLimit) {
        var sb = new StringBuilder();
        sb.append("Cannot add ");
        sb.append(String.format("%,d", estimate.fileCount()));
        sb.append(" file(s) with an estimated ~");
        sb.append(String.format("%,d", estimate.estimatedTokens()));
        sb.append(" tokens.\n\n");

        if (estimate.isTruncated()) {
            sb.append("(File enumeration was truncated at ");
            sb.append(String.format("%,d", MAX_FILES_TO_ENUMERATE));
            sb.append(" files)\n\n");
        }

        sb.append("This exceeds the maximum allowed context size (");
        sb.append(String.format("%,d", hardLimit));
        sb.append(" tokens).\n");
        sb.append("The current model's context window is ");
        sb.append(String.format("%,d", maxInputTokens));
        sb.append(" tokens.");

        return sb.toString();
    }
}
