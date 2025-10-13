package io.github.jbellis.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

public class TestOutputParserTest {

    private static final class CapturingListener implements TestOutputListener {
        private final List<String> started = new ArrayList<>();
        private final Map<String, StringBuilder> outputs = new HashMap<>();
        private final List<Completed> completed = new ArrayList<>();

        record Completed(String name, TestEntry.Status status, String output) {}

        @Override
        public void onTestStarted(String testName) {
            started.add(testName);
            outputs.computeIfAbsent(testName, k -> new StringBuilder());
        }

        @Override
        public void onTestOutput(String testName, String text) {
            outputs.computeIfAbsent(testName, k -> new StringBuilder()).append(text);
        }

        @Override
        public void onTestCompleted(String testName, TestEntry.Status status, String output) {
            completed.add(new Completed(testName, status, output));
        }

        String outputOf(String testName) {
            return outputs.getOrDefault(testName, new StringBuilder()).toString();
        }

        List<String> started() {
            return started;
        }

        List<Completed> completed() {
            return completed;
        }
    }

    private static void feedLines(TestOutputParser parser, String... lines) {
        for (String l : lines) {
            parser.processChunk(l + "\n");
        }
    }

    private static void feedAsSingleChunk(TestOutputParser parser, String... lines) {
        String joined = String.join("\n", lines) + "\n";
        parser.processChunk(joined);
    }

    @Test
    void gradle_simple_pass() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String tn = "com.example.MyTest > testThing";
        feedLines(
                parser,
                "com.example.MyTest > testThing STARTED",
                "stdout from test\nsecond line",
                "com.example.MyTest > testThing PASSED");

        assertEquals(List.of(tn), listener.started());
        assertEquals(1, listener.completed().size());
        var c = listener.completed().getFirst();
        assertEquals(tn, c.name());
        assertEquals(TestEntry.Status.PASSED, c.status());

        String out = listener.outputOf(tn);
        assertTrue(out.contains("STARTED"));
        assertTrue(out.contains("stdout from test"));
        assertTrue(out.contains("second line"));
        assertTrue(out.contains("PASSED"));
        assertEquals(out, c.output(), "completed output should match accumulated onTestOutput");
    }

    @Test
    void gradle_fail_and_skipped_mapping() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String tFail = "com.example.FooTest > willFail";
        String tSkip = "com.example.FooTest > willSkip";

        feedLines(parser, tFail + " STARTED", tFail + " FAILED", tSkip + " STARTED", tSkip + " SKIPPED");

        assertTrue(listener.started().contains(tFail));
        assertTrue(listener.started().contains(tSkip));
        var byName = toMap(listener.completed());
        assertEquals(TestEntry.Status.FAILED, byName.get(tFail).status());
        assertEquals(TestEntry.Status.PASSED, byName.get(tSkip).status(), "SKIPPED maps to PASSED");
    }

    @Test
    void gradle_result_without_started_is_handled() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String tn = "com.example.ImplicitStart > onlyResult";
        feedLines(parser, tn + " PASSED");

        // Parser should synthesize start before completion
        assertEquals(List.of(tn), listener.started());
        assertEquals(1, listener.completed().size());
        assertEquals(TestEntry.Status.PASSED, listener.completed().getFirst().status());
    }

    @Test
    void maven_running_and_summary_pass() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String cls = "com.example.BarTest";
        feedLines(
                parser, "[INFO] Running " + cls, "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 - in " + cls);

        assertEquals(List.of(cls), listener.started());
        assertEquals(1, listener.completed().size());
        var c = listener.completed().getFirst();
        assertEquals(cls, c.name());
        assertEquals(TestEntry.Status.PASSED, c.status());
        assertTrue(c.output().contains("Running " + cls));
        assertTrue(c.output().contains("Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 - in " + cls));
    }

    @Test
    void maven_summary_with_time_elapsed_and_error_maps_to_error() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String cls = "com.example.BazTest";
        feedLines(
                parser,
                "[INFO] Running " + cls,
                "[INFO] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.012 s - in " + cls);

        assertEquals(1, listener.completed().size());
        var c = listener.completed().getFirst();
        assertEquals(TestEntry.Status.ERROR, c.status());
    }

    @Test
    void maven_summary_without_in_uses_last_running_class() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String cls = "com.example.NoInSuffixTest";
        feedLines(parser, "Running " + cls, "Tests run: 2, Failures: 0, Errors: 0, Skipped: 1");

        assertEquals(1, listener.completed().size());
        var c = listener.completed().getFirst();
        assertEquals(cls, c.name());
        assertEquals(TestEntry.Status.PASSED, c.status(), "Skipped-only maps to PASSED");
    }

    @Test
    void interleaved_output_attributed_to_most_recent_active_test() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String a = "pkg.A > m1";
        String b = "pkg.B > m2";

        feedLines(
                parser,
                a + " STARTED",
                "A: first line",
                b + " STARTED",
                "interleaved generic line", // should go to B (most recent active)
                b + " PASSED",
                a + " FAILED");

        var byName = toMap(listener.completed());
        assertEquals(TestEntry.Status.PASSED, byName.get(b).status());
        assertEquals(TestEntry.Status.FAILED, byName.get(a).status());

        String outA = listener.outputOf(a);
        String outB = listener.outputOf(b);

        assertTrue(outA.contains("A: first line"));
        assertFalse(outA.contains("interleaved generic line"), "generic line should not be attributed to A");
        assertTrue(
                outB.contains("interleaved generic line"),
                "generic line should be attributed to latest started test (B)");
    }

    @Test
    void incomplete_stream_does_not_emit_completion() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        feedLines(parser, "com.example.HangingTest > stillRunning STARTED", "some log line");

        assertEquals(1, listener.started().size());
        assertTrue(listener.completed().isEmpty(), "No completion should be emitted for incomplete stream");
    }

    @Test
    void chunked_input_single_call_works() {
        var listener = new CapturingListener();
        var parser = new TestOutputParser(listener);

        String tn = "pkg.ChunkTest > chunked";
        feedAsSingleChunk(parser, tn + " STARTED", "log a", "log b", tn + " PASSED");

        assertEquals(List.of(tn), listener.started());
        assertEquals(1, listener.completed().size());
        assertEquals(TestEntry.Status.PASSED, listener.completed().getFirst().status());
        String out = listener.completed().getFirst().output();
        assertTrue(out.contains("log a"));
        assertTrue(out.contains("log b"));
    }

    private static Map<String, CapturingListener.Completed> toMap(List<CapturingListener.Completed> list) {
        Map<String, CapturingListener.Completed> m = new HashMap<>();
        for (var c : list) {
            m.put(c.name(), c);
        }
        return m;
    }
}
