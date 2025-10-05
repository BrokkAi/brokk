package io.github.jbellis.brokk.gui.dialogs;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.BlitzForge;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/**
 * Headless progress listener for BlitzForge that writes progress and results to an {@link IConsoleIO}.
 * No Swing dependencies; suitable for CLI and background usage.
 */
public final class BlitzForgeProgressHeadless implements BlitzForge.Listener {

    private final IConsoleIO io;

    private volatile int totalFiles = 0;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger changedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger llmLineCount = new AtomicInteger(0);

    private final Map<ProjectFile, String> failures = new ConcurrentHashMap<>();
    private final Set<ProjectFile> changedFiles = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public BlitzForgeProgressHeadless(IConsoleIO io) {
        this.io = io;
    }

    @Override
    public void onStart(int total) {
        totalFiles = total;
        io.systemOutput("Starting BlitzForge: " + total + " file(s) to process...");
    }

    @Override
    public void onFileStart(ProjectFile file) {
        io.systemOutput("[BlitzForge] Processing: " + file);
    }

    @Override
    public void onLlmOutput(ProjectFile file, String token, boolean isNewMessage, boolean isReasoning) {
        // Forward streaming output to the console I/O as AI content.
        io.llmOutput(token, ChatMessageType.AI, isNewMessage, isReasoning);

        // Track rough output size by counting newline characters.
        int newLines = (int) token.chars().filter(c -> c == '\n').count();
        if (newLines > 0) {
            llmLineCount.addAndGet(newLines);
        }
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
            io.systemOutput("[BlitzForge] Error in " + file + ": " + errorMessage);
        } else {
            io.systemOutput("[BlitzForge] Completed: " + file + (edited ? " (changed)" : ""));
        }

        if (!llmOutput.isBlank()) {
            // Emit the final per-file LLM output as a single new AI message
            io.llmOutput(llmOutput, ChatMessageType.AI, true, false);
            int newLines = (int) llmOutput.chars().filter(c -> c == '\n').count();
            if (newLines > 0) {
                llmLineCount.addAndGet(newLines);
            }
        }
    }

    @Override
    public void onProgress(int processed, int total) {
        processedCount.set(processed); // Keep internal counter aligned with engine's callback
        io.systemOutput("[BlitzForge] Progress: " + processed + " / " + total);
    }

    @Override
    public void onDone(TaskResult result) {
        // Summarize outcome
        var summary = new StringBuilder();
        summary.append("BlitzForge finished.\n")
               .append("Total files: ").append(totalFiles).append("\n")
               .append("Processed: ").append(processedCount.get()).append("\n")
               .append("Changed: ").append(changedCount.get()).append("\n")
               .append("Failed: ").append(failedCount.get()).append("\n")
               .append("LLM lines: ").append(llmLineCount.get()).append("\n")
               .append("Stop reason: ").append(result.stopDetails().reason());

        var explanation = result.stopDetails().explanation();
        if (!explanation.isBlank()) {
            summary.append("\nDetails: ").append(explanation);
        }

        if (!failures.isEmpty()) {
            summary.append("\nFailures:");
            failures.forEach((file, err) -> summary.append("\n - ").append(file).append(": ").append(err));
        }

        if (!changedFiles.isEmpty()) {
            summary.append("\nChanged files:");
            changedFiles.forEach(f -> summary.append("\n - ").append(f));
        }

        io.systemOutput(summary.toString());
    }
}
