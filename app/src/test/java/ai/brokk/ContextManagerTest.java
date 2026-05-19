package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.ContextOutputFragments;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ai.brokk.analyzer.TestFileHeuristics}. */
class ContextManagerTest {
    @Test
    void shouldMatchTestFilenames(@TempDir Path tempDir) {
        var positives = List.of(
                // match in path
                "src/test/java/MyClass.java",
                "src/tests/io/github/Main.kt",

                // match in file
                "TestX.java",
                "TestsX.java",
                "XTest.java",
                "XTests.java",
                "CamelTestCase.java",
                "CamelTestsCase.java",
                // with a path
                "src/foo/bar/TestX.java",
                "src/foo/bar/TestsX.java",
                "src/foo/bar/XTest.java",
                "src/foo/bar/XTests.java",
                "src/foo/bar/CamelTestCase.java",
                "src/foo/bar/CamelTestsCase.java",

                // underscore style
                "test_x.py",
                "tests_x.py",
                "x_test.py",
                "x_tests.py",
                "under_test_score.py",
                "under_tests_score.py",
                // with a path
                "src/foo/bar/test_x.py",
                "src/foo/bar/tests_x.py",
                "src/foo/bar/x_test.py",
                "src/foo/bar/x_tests.py",
                "src/foo/bar/under_test_score.py",
                "src/foo/bar/under_tests_score.py",

                // js/ts conventions
                "component.spec.ts",
                "component.spec.js",
                "component.spec.tsx",
                "component.spec.jsx",
                "my-component.spec.ts",
                "MyComponent.spec.ts",
                "foo.test.ts",
                "foo.test.js",
                "src/__tests__/utils.ts",
                "__tests__/helper.js",
                "__tests__/Component.test.js",
                "packages/core/__tests__/util.ts");

        var mismatches = new ArrayList<String>();

        positives.forEach(path -> {
            if (!ContextManager.isTestFile(new ProjectFile(tempDir, path), null)) {
                mismatches.add(path);
            }
        });

        assertTrue(mismatches.isEmpty(), "Expected to match but didn't: " + mismatches);
    }

    @Test
    void shouldNotMatchNonTestFilenames(@TempDir Path tempDir) {
        var negatives = List.of(
                "testing/Bar.java",
                "src/production/java/MyClass.java",
                "contest/file.java",
                "testament/Foo.java",
                "src/main/java/Testament.java",
                "src/main/java/Contest.java",
                "inspector/code.ts",
                "spectacle/show.js",
                "src/respect.ts",
                "aspect-ratio.ts");

        var unexpectedMatches = new ArrayList<String>();

        negatives.forEach(path -> {
            if (ContextManager.isTestFile(new ProjectFile(tempDir, path), null)) {
                unexpectedMatches.add(path);
            }
        });

        assertTrue(unexpectedMatches.isEmpty(), "Unexpectedly matched: " + unexpectedMatches);
    }

    @Test
    void globalToolRegistryIncludesThinkAndBuilderInheritsIt() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-think-tools");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);
        try {
            assertTrue(
                    cm.getToolRegistry().isRegistered("think"),
                    "ContextManager base registry must expose builtin think for custom agents");
            var extended = cm.getToolRegistry().builder().build();
            assertTrue(
                    extended.isRegistered("think"),
                    "Registry builder copies must retain think (CustomAgentExecutor builds this way)");
            assertEquals(1, extended.getTools(List.of("think")).size());
        } finally {
            project.close();
        }
    }

    @Test
    void setReadOnly_rebuildsToolRegistryWithoutDestructiveTools() throws Exception {
        // The headless --read-only contract requires the baseline ToolRegistry to drop
        // @Destructive tools so the LLM never sees them (issue #3617).
        var tempDir = Files.createTempDirectory("ctxmgr-read-only");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);
        try {
            // Default mode: destructive tools (e.g. runShellCommand) are registered.
            assertTrue(
                    cm.getToolRegistry().isRegistered("runShellCommand"),
                    "ContextManager should expose runShellCommand in normal mode");
            assertTrue(cm.getToolRegistry().isRegistered("think"), "think is always available");
            assertTrue(
                    cm.getToolRegistry().isRegistered("explainCommit"),
                    "GitTools.explainCommit is read-only and must remain registered");

            cm.setReadOnly(true);

            assertTrue(cm.isReadOnly());
            assertTrue(cm.getToolRegistry().isReadOnly(), "Rebuilt registry must report read-only");
            assertTrue(cm.getToolRegistry().isRegistered("think"));
            assertTrue(
                    cm.getToolRegistry().isRegistered("explainCommit"),
                    "Read-only mode must keep read-only git tools available");
            assertTrue(
                    !cm.getToolRegistry().isRegistered("runShellCommand"),
                    "Read-only mode must filter @Destructive runShellCommand from the registry");

            // Toggling back restores destructive tools.
            cm.setReadOnly(false);
            assertTrue(cm.getToolRegistry().isRegistered("runShellCommand"));
        } finally {
            project.close();
        }
    }

    @Test
    public void testDropHistoryEntryBySequence() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-test");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);

        // Build two TaskEntries with distinct sequences
        List<ChatMessage> msgs1 = List.of(UserMessage.from("first"));
        List<ChatMessage> msgs2 = List.of(UserMessage.from("second"));

        var md1 = Messages.format(msgs1);
        var md2 = Messages.format(msgs2);
        var entry1 = new TaskEntry(101, "First Task", md1, md1, null, null);
        var entry2 = new TaskEntry(202, "Second Task", md2, md2, null, null);

        // Seed initial history with both entries
        cm.pushContext(ctx -> ctx.withHistory(List.of(entry1, entry2)));

        // Sanity check preconditions
        Context before = cm.liveContext();
        assertEquals(2, before.getTaskHistory().size(), "Precondition: two history entries expected");

        // Drop the first entry by its sequence
        cm.dropHistoryEntryBySequence(101);

        // Validate the new top context
        Context after = cm.liveContext();
        assertEquals(1, after.getTaskHistory().size(), "Exactly one history entry should remain");
        assertTrue(
                after.getTaskHistory().stream().noneMatch(te -> te.sequence() == 101), "Dropped entry must be absent");
    }

    @Test
    public void testAddPathFragmentsEmptyIsNoOp() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-empty-pathfrags");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);
        cm.createHeadless();

        var beforeSize = cm.getContextHistoryList().size();

        // Call with empty fragments
        cm.addPathFragments(List.of());

        var afterSize = cm.getContextHistoryList().size();
        assertEquals(beforeSize, afterSize, "Empty addPathFragments should be a no-op");
    }

    @Test
    public void testAddFilesEmptyIsNoOp() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-empty-files");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);
        cm.createHeadless();

        var beforeSize = cm.getContextHistoryList().size();

        // Empty set of files
        cm.addFiles(Set.of());

        var afterSize = cm.getContextHistoryList().size();
        assertEquals(beforeSize, afterSize, "Empty addFiles should be a no-op");
    }

    @Test
    public void testAddFilesNonEmptyPushesContext() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-nonempty-files");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);
        cm.createHeadless();

        var beforeSize = cm.getContextHistoryList().size();

        // Create one text file and add it
        var pf = new ProjectFile(tempDir, "Sample.java");
        pf.create();
        pf.write("class Sample {}");

        cm.addFiles(Set.of(pf));

        var afterSize = cm.getContextHistoryList().size();
        assertEquals(beforeSize + 1, afterSize, "Adding a file should push a new context");
    }

    @Test
    void pushThenUndoRestoresPreCodeagentWorkspaceAndDiskContents() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-codeagent-undo");
        try (var project = new MainProject(tempDir)) {
            var cm = new ContextManager(project);
            cm.createHeadless();

            var file = new ProjectFile(tempDir, "Sample.java");
            file.write("class Sample { int value = 1; }\n");
            cm.addFiles(Set.of(file));

            var preAttemptContext = cm.liveContext();
            preAttemptContext.awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);

            file.write("class Sample { int value = 2; }\n");
            var attemptContext = preAttemptContext.copyAndRefresh(Set.of(file));
            attemptContext.awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);

            cm.pushContext(ctx -> attemptContext);

            assertTrue(cm.undoContext());
            assertEquals(preAttemptContext, cm.liveContext());
            assertEquals("class Sample { int value = 1; }\n", file.read().orElseThrow());
        }
    }

    @Test
    void copyFragmentsToCurrentContextAsync_historyPreservesEntryOrder_notSequenceSort() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-history-order");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);
        cm.createHeadless();

        try {
            var historyFragment = new ContextOutputFragments.HistoryOutputFragment(List.of("b", "a"));

            cm.copyFragmentsToCurrentContextAsync(List.of(historyFragment)).get();

            assertEquals(
                    List.of("b", "a"),
                    cm.liveContext().getTaskHistory().stream()
                            .map(TaskEntry::summary)
                            .toList(),
                    "History copy should preserve fragment-provided order");
        } finally {
            project.close();
        }
    }

    @Test
    void inferBuildDetails_returnsEmptyForEmptyInference() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-infer-empty");
        try (var project = new MainProject(tempDir);
                var cm = new ContextManager(project) {
                    @Override
                    protected BuildAgent.BuildDetails runBuildDetailsInference() {
                        return BuildAgent.BuildDetails.EMPTY;
                    }
                }) {
            var result = cm.inferBuildDetails(false).get(5, TimeUnit.SECONDS);

            assertEquals("empty", result.status());
            assertEquals(BuildAgent.BuildDetails.EMPTY, result.buildDetails());
            assertTrue(project.hasBuildDetails(), "Empty inference should still persist build details");
        }
    }

    @Test
    void inferBuildDetails_returnsFailedWithDiagnostics() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-infer-failed");
        try (var project = new MainProject(tempDir);
                var cm = new ContextManager(project) {
                    @Override
                    protected BuildAgent.BuildDetails runBuildDetailsInference() {
                        throw new RuntimeException("boom");
                    }
                }) {
            var result = cm.inferBuildDetails(false).get(5, TimeUnit.SECONDS);

            assertEquals("failed", result.status());
            assertTrue(result.diagnostics().contains("boom"));
            assertFalse(project.hasBuildDetails(), "Failed inference should not persist build details");
            assertFalse(project.getBuildDetailsFuture().isCompletedExceptionally());
        }
    }

    @Test
    void inferBuildDetails_usesSingleFlightForConcurrentCallers() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-infer-single-flight");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();

        try (var project = new MainProject(tempDir);
                var cm = new ContextManager(project) {
                    @Override
                    protected BuildAgent.BuildDetails runBuildDetailsInference() throws Exception {
                        calls.incrementAndGet();
                        started.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return BuildAgent.BuildDetails.EMPTY;
                    }
                }) {
            CompletableFuture<ContextManager.BuildDetailsInferenceResult> first = cm.inferBuildDetails(false);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            CompletableFuture<ContextManager.BuildDetailsInferenceResult> second = cm.inferBuildDetails(false);

            assertSame(first, second);
            release.countDown();
            assertEquals("empty", first.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void createHeadlessAndEndpointShareSameInFlightInference() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-headless-share-inflight");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();

        try (var project = new MainProject(tempDir);
                var cm = new ContextManager(project) {
                    @Override
                    protected BuildAgent.BuildDetails runBuildDetailsInference() throws Exception {
                        calls.incrementAndGet();
                        started.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return BuildAgent.BuildDetails.EMPTY;
                    }
                }) {
            cm.createHeadless(true, new NoOpConsoleIO());
            assertTrue(started.await(5, TimeUnit.SECONDS));

            var explicit = cm.inferBuildDetails(false);
            release.countDown();

            assertEquals("empty", explicit.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, calls.get());
        }
    }
}
