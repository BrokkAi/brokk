package io.github.jbellis.brokk.gui.tests;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Parses streaming test output from common tools (Gradle, Maven/Surefire, JUnit) and emits events when tests start and
 * complete, along with the accumulated output for each test.
 *
 * <p>Additionally, emits streaming output via {@link TestOutputListener#onTestOutput(String, String)} for incremental
 * UI updates.
 *
 * <p>Recognized patterns: - Gradle (JUnit Platform): "ClassName > methodName STARTED" and "... PASSED|FAILED|SKIPPED" -
 * Maven Surefire: "[INFO] Running com.example.MyTest" "[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 - in
 * com.example.MyTest"
 *
 * <p>Note on SKIPPED: TestEntry.Status has no SKIPPED; SKIPPED is mapped to PASSED.
 */
public final class TestOutputParser {
    private static final Logger logger = LogManager.getLogger(TestOutputParser.class);

    // Gradle/JUnit patterns
    private static final Pattern P_GRADLE_METHOD_STARTED = Pattern.compile("^\\s*(.+) > (.+) STARTED\\s*$");
    private static final Pattern P_GRADLE_METHOD_RESULT =
            Pattern.compile("^\\s*(.+) > (.+) (PASSED|FAILED|SKIPPED)\\s*$");

    // Maven/Surefire patterns
    private static final Pattern P_MAVEN_RUNNING = Pattern.compile("^\\s*(?:\\[INFO\\]\\s*)?Running\\s+(.+)\\s*$");
    private static final Pattern P_MAVEN_CLASS_SUMMARY = Pattern.compile(
            "^\\s*(?:\\[INFO\\]\\s*)?Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)(?:, Time elapsed: [^\\s]+(?: s)?)?(?: - in (.+))?\\s*$");
    private static final Pattern P_LINE_SEP = Pattern.compile("\\R");

    private final TestOutputListener listener;

    /**
     * Active test buffers keyed by test name; insertion order preserved. Tests may be interleaved, so we keep separate
     * buffers.
     */
    private final Map<String, StringBuilder> activeBuffers = new LinkedHashMap<>();

    /**
     * Stack of started tests for attributing incidental output to the "current" test (the most recently started that
     * has not yet completed).
     */
    private final Deque<String> startOrder = new ArrayDeque<>();

    /**
     * Tracks the most recent Maven "Running ClassName" to associate with subsequent class summaries that do not
     * explicitly include " - in ClassName".
     */
    @Nullable
    private String lastMavenRunningClass;

    /** A place to hold output when no test is active. We currently do not attach this to any test. */
    private final StringBuilder globalBuffer = new StringBuilder();

    public TestOutputParser(TestOutputListener listener) {
        this.listener = listener;
    }

    /** Processes an output chunk, splitting into lines (preserving line terminators) and parsing each. */
    public void processChunk(String chunk) {
        // Iterate lines preserving line separators using a matcher, avoiding String.split pitfalls
        var matcher = P_LINE_SEP.matcher(chunk);
        int start = 0;
        while (matcher.find()) {
            var line = chunk.substring(start, matcher.end());
            processLine(line);
            start = matcher.end();
        }
        if (start < chunk.length()) {
            processLine(chunk.substring(start));
        }
    }

    /**
     * Processes a single output line, updating parser state and emitting callbacks as needed. The line may include its
     * trailing newline characters.
     */
    public void processLine(String line) {

        // 1) Gradle: method started
        var m = P_GRADLE_METHOD_STARTED.matcher(line);
        if (m.matches()) {
            var className = m.group(1).trim();
            var methodName = m.group(2).trim();
            var testName = className + " > " + methodName;
            startTestIfNew(testName);
            appendTo(testName, line);
            return;
        }

        // 2) Gradle: method result
        m = P_GRADLE_METHOD_RESULT.matcher(line);
        if (m.matches()) {
            var className = m.group(1).trim();
            var methodName = m.group(2).trim();
            var statusToken = m.group(3).trim();
            var testName = className + " > " + methodName;

            startTestIfNew(testName); // if we missed STARTED
            appendTo(testName, line);
            completeTest(testName, mapStatus(statusToken));
            return;
        }

        // 3) Maven/Surefire: class running
        m = P_MAVEN_RUNNING.matcher(line);
        if (m.matches()) {
            var className = m.group(1).trim();
            lastMavenRunningClass = className;
            startTestIfNew(className);
            appendTo(className, line);
            return;
        }

        // 4) Maven/Surefire: class summary
        m = P_MAVEN_CLASS_SUMMARY.matcher(line);
        if (m.matches()) {
            var failures = parseInt(m.group(2));
            var errors = parseInt(m.group(3));
            var className = m.group(5) != null
                    ? m.group(5).trim()
                    : (lastMavenRunningClass != null ? lastMavenRunningClass : "maven-suite");

            // If we never saw Running, still emit start so listeners see the test.
            startTestIfNew(className);
            appendTo(className, line);

            var status = (errors > 0)
                    ? TestEntry.Status.ERROR
                    : (failures > 0 ? TestEntry.Status.FAILED : mapSkippedToStatus());
            completeTest(className, status);
            return;
        }

        // 5) Otherwise, attribute to the current active test if any; else store globally.
        if (!startOrder.isEmpty()) {
            var current = startOrder.getLast();
            appendTo(current, line);
        } else {
            globalBuffer.append(line);
        }
    }

    /** Clears parser state. Does not emit completion callbacks for active tests. */
    public void reset() {
        activeBuffers.clear();
        startOrder.clear();
        lastMavenRunningClass = null;
        globalBuffer.setLength(0);
    }

    private void startTestIfNew(String testName) {
        if (!activeBuffers.containsKey(testName)) {
            activeBuffers.put(testName, new StringBuilder());
            startOrder.addLast(testName);
            try {
                listener.onTestStarted(testName);
            } catch (RuntimeException e) {
                logger.warn("Listener threw in onTestStarted for {}: {}", testName, e.toString());
            }
            logger.debug("Test started: {}", testName);
        } else {
            // Refresh recency for incidental output routing
            startOrder.remove(testName);
            startOrder.addLast(testName);
        }
    }

    private void appendTo(String testName, String line) {
        var buf = activeBuffers.get(testName);
        if (buf == null) {
            // Unlikely: create and synthesize a start
            startTestIfNew(testName);
            buf = activeBuffers.get(testName);
        }
        requireNonNull(buf).append(line);
        try {
            listener.onTestOutput(testName, line);
        } catch (RuntimeException e) {
            logger.warn("Listener threw in onTestOutput for {}: {}", testName, e.toString());
        }
    }

    private void completeTest(String testName, TestEntry.Status status) {
        var buf = activeBuffers.remove(testName);
        startOrder.remove(testName);
        if (buf == null) {
            buf = new StringBuilder();
            logger.debug("Completing test {} with no buffer (synthesized).", testName);
        }
        var output = buf.toString();
        try {
            listener.onTestCompleted(testName, status, output);
        } catch (RuntimeException e) {
            logger.warn("Listener threw in onTestCompleted for {}: {}", testName, e.toString());
        }
        logger.debug("Test completed: {} -> {}", testName, status);
    }

    private static TestEntry.Status mapStatus(String token) {
        // Map SKIPPED to PASSED due to TestEntry.Status lacking SKIPPED.
        return switch (token) {
            case "PASSED" -> TestEntry.Status.PASSED;
            case "FAILED" -> TestEntry.Status.FAILED;
            case "SKIPPED" -> TestEntry.Status.PASSED;
            default -> {
                // Unknown token - treat conservatively as ERROR
                yield TestEntry.Status.ERROR;
            }
        };
    }

    private static TestEntry.Status mapSkippedToStatus() {
        // Note: TestEntry.Status has no SKIPPED state; we display skipped-only classes as PASSED.
        return TestEntry.Status.PASSED;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
