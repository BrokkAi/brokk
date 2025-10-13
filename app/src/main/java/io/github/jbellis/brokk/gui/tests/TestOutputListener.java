package io.github.jbellis.brokk.gui.tests;

/** Listener for streaming test output parsing events. Implementations should be thread-safe if used across threads. */
public interface TestOutputListener {
    /**
     * Called when a test is detected as started. The test name is a human-readable identifier, e.g.,
     * "com.example.MyTest > testSomething".
     */
    void onTestStarted(String testName);

    /**
     * Called for streaming output attributed to a specific test while it is running. Text may include line terminators;
     * the listener should append as-is.
     */
    void onTestOutput(String testName, String text);

    /**
     * Called when a test is detected as completed. The output contains all accumulated lines attributed to the test.
     */
    void onTestCompleted(String testName, TestEntry.Status status, String output);
}
