package io.github.jbellis.brokk.gui.tests;

/**
 * Model for a single test file/class in the test runner.
 * Tracks the test's path, display name, accumulated output, and execution status.
 */
public class TestEntry {
    public enum Status {
        RUNNING,
        PASSED,
        FAILED,
        ERROR
    }

    private final String filePath;
    private final String displayName;
    private final StringBuilder output;
    private Status status;

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

    @Override
    public String toString() {
        return displayName;
    }
}
