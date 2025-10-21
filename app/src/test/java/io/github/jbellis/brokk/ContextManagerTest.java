package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContextManager#TEST_FILE_PATTERN}. */
class ContextManagerTest {
    @Test
    void shouldMatchTestFilenames() {
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
                "src/foo/bar/under_tests_score.py");

        var pattern = ContextManager.TEST_FILE_PATTERN;
        var mismatches = new ArrayList<String>();

        positives.forEach(path -> {
            if (!pattern.matcher(path).matches()) {
                mismatches.add(path);
            }
        });

        assertTrue(mismatches.isEmpty(), "Expected to match but didn't: " + mismatches);
    }

    @Test
    void shouldNotMatchNonTestFilenames() {
        var negatives = List.of(
                "testing/Bar.java",
                "src/production/java/MyClass.java",
                "contest/file.java",
                "testament/Foo.java",
                "src/main/java/Testament.java",
                "src/main/java/Contest.java");

        var pattern = ContextManager.TEST_FILE_PATTERN;
        var unexpectedMatches = new ArrayList<String>();

        negatives.forEach(path -> {
            if (pattern.matcher(path).matches()) {
                unexpectedMatches.add(path);
            }
        });

        assertTrue(unexpectedMatches.isEmpty(), "Unexpectedly matched: " + unexpectedMatches);
    }

    @Test
    public void testDropHistoryEntryBySequence() throws Exception {
        var tempDir = Files.createTempDirectory("ctxmgr-test");
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);

        // Build two TaskEntries with distinct sequences
        List<ChatMessage> msgs1 = List.of(UserMessage.from("first"));
        List<ChatMessage> msgs2 = List.of(UserMessage.from("second"));

        var tf1 = new ContextFragment.TaskFragment(cm, msgs1, "First Task");
        var tf2 = new ContextFragment.TaskFragment(cm, msgs2, "Second Task");

        var entry1 = new TaskEntry(101, tf1, null);
        var entry2 = new TaskEntry(202, tf2, null);

        // Seed initial history with both entries
        cm.pushContext(ctx -> ctx.withCompressedHistory(List.of(entry1, entry2)));

        // Sanity check preconditions
        Context before = cm.topContext();
        assertEquals(2, before.getTaskHistory().size(), "Precondition: two history entries expected");

        // Drop the first entry by its sequence
        cm.dropHistoryEntryBySequence(101);

        // Validate the new top context
        Context after = cm.topContext();
        assertEquals(1, after.getTaskHistory().size(), "Exactly one history entry should remain");
        assertTrue(
                after.getTaskHistory().stream().noneMatch(te -> te.sequence() == 101), "Dropped entry must be absent");
        assertEquals("Delete task from history", after.getAction());
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
}
