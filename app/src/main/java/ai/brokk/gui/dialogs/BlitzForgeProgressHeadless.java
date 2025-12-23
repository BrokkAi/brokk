package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.BlitzForge;
import ai.brokk.analyzer.ProjectFile;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/**
 * Headless progress listener for BlitzForge that writes progress and results to an {@link IConsoleIO}. No Swing
 * dependencies; suitable for CLI and background usage.
 */
public final class BlitzForgeProgressHeadless implements BlitzForge.Listener {

    private final IConsoleIO io;

    private volatile int totalFiles = 0;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger changedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger llmLineCount = new AtomicInteger(0);

    private final Map<ProjectFile, String> failures = new ConcurrentHashMap<>();
    private final Set<ProjectFile> changedFiles = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public BlitzForgeProgressHeadless(IConsoleIO io) {
        this.io = io;
    }

    @Override
    public void onStart(int total) {
        totalFiles = total;
        io.showNotification(
                IConsoleIO.NotificationRole.INFO, "Starting BlitzForge: " + total + " file(s) to process...");
    }

    @Override
    public void onFileStart(ProjectFile file) {
        io.showNotification(IConsoleIO.NotificationRole.INFO, "[BlitzForge] Processing: " + file);
    }

    @Override
    public IConsoleIO getConsoleIO(ProjectFile file) {
        // Headless mode uses the same console for all files.
        return io;
    }

    @Override
    public void onFileResult(ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {
        if (edited) {
            changedFiles.add(file);
            changedCount.incrementAndGet();
        }
        if (errorMessage != null) {
            failures.put(file, errorMessage);
            failedCount.incrementAndGet();
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "[BlitzForge] Error in " + file + ": " + errorMessage);
        } else {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "[BlitzForge] Completed: " + file + (edited ? " (changed)" : ""));
        }

        if (!llmOutput.isBlank()) {
            // Emit the final per-file LLM output as a single new AI message
            io.llmOutput(llmOutput, ChatMessageType.AI, true, false);
            int newLines = (int) llmOutput.chars().filter(c -> c == '\n').count();
            if (newLines > 0) {
                llmLineCount.addAndGet(newLines);
            }
        }

        // Bump processed count and emit progress here (since onProgress is removed)
        int done = processedCount.incrementAndGet();
        io.showNotification(IConsoleIO.NotificationRole.INFO, "[BlitzForge] Progress: " + done + " / " + totalFiles);
    }

    @Override
    public void onComplete(TaskResult result) {
        // Summarize outcome
        var summary = new StringBuilder();
        summary.append("BlitzForge finished.\n")
                .append("Total files: ")
                .append(totalFiles)
                .append("\n")
                .append("Processed: ")
                .append(processedCount.get())
                .append("\n")
                .append("Changed: ")
                .append(changedCount.get())
                .append("\n")
                .append("Failed: ")
                .append(failedCount.get())
                .append("\n")
                .append("LLM lines: ")
                .append(llmLineCount.get())
                .append("\n")
                .append("Stop reason: ")
                .append(result.stopDetails().reason());

        var explanation = result.stopDetails().explanation();
        if (!explanation.isBlank()) {
            summary.append("\nDetails: ").append(explanation);
        }

        if (!failures.isEmpty()) {
            summary.append("\nFailures:");
            failures.forEach((file, err) ->
                    summary.append("\n - ").append(file).append(": ").append(err));
        }

        if (!changedFiles.isEmpty()) {
            summary.append("\nChanged files:");
            changedFiles.forEach(f -> summary.append("\n - ").append(f));
        }

        io.showNotification(IConsoleIO.NotificationRole.INFO, summary.toString());
    }
}
