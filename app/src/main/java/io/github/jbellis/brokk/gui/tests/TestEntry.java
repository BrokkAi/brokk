package io.github.jbellis.brokk.gui.tests;

import java.time.Instant;
import org.jetbrains.annotations.Nullable;

/**
 * Model for a single test file/class in the test runner. Tracks the test's path, display name, accumulated output, and
 * execution status.
 */
public class TestEntry {
    public enum Status {
        RUNNING,
        PASSED,
        FAILED,
        ERROR
    }

    private final String filePath;
    private String displayName;
    private final StringBuilder output;
    private Status status;

    // Timestamps for when execution started and completed (nullable until set)
    private volatile @Nullable Instant startedAt;
    private volatile @Nullable Instant completedAt;

    public TestEntry(String filePath, String displayName) {
        this.filePath = filePath;
        this.displayName = displayName;
        this.output = new StringBuilder();
        this.status = Status.RUNNING;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public synchronized String getOutput() {
        return output.toString();
    }

    public synchronized void appendOutput(String text) {
        output.append(text);
    }

    public synchronized void clearOutput() {
        output.setLength(0);
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public @Nullable Instant getStartedAt() {
        return startedAt;
    }

    public @Nullable Instant getCompletedAt() {
        return completedAt;
    }

    public void setStartedAtIfAbsent(Instant when) {
        if (startedAt == null) {
            startedAt = when;
        }
    }

    public void setCompletedAtIfAbsent(Instant when) {
        if (completedAt == null) {
            completedAt = when;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}
