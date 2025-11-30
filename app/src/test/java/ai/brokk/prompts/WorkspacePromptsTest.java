package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.TaskResult;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ViewingPolicy;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.Messages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Minimal tests for the new WorkspacePrompts.CodeAgentMessages shape.
 * Focuses on:
 *  (a) workspace() returns combined messages
 *  (b) buildFailure() is populated when build fragment exists
 *  (c) exactly one build status block exists in the combined workspace
 */
class WorkspacePromptsTest {

    private TestContextManager cm;
    private TestProject project;
    private TestConsoleIO consoleIO;

    @TempDir
    Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(projectRoot);
        consoleIO = new TestConsoleIO();
        project = new TestProject(projectRoot, Languages.JAVA);
        cm = new TestContextManager(projectRoot, consoleIO, new JavaAnalyzer(project));
    }

    @Test
    void testWorkspaceReturnsCombinedMessages() throws IOException {
        var file = createTestFile("file.txt", "content");
        cm.addEditableFile(file);

        var ctx = new Context(cm, null);
        var frag = new ContextFragment.ProjectPathFragment(file, cm);
        ctx = ctx.addPathFragments(List.of(frag));

        WorkspacePrompts.CodeAgentMessages records =
                WorkspacePrompts.getMessagesForCodeAgent(ctx, new ViewingPolicy(TaskResult.Type.CODE));

        assertNotNull(records);
        assertFalse(records.workspace().isEmpty(), "workspace() should return combined messages");
    }

    @Test
    void testBuildFailurePopulatedWhenBuildFragmentExists() {
        var ctx = new Context(cm, null).withBuildResult(false, "Build failed: syntax error on line 42");

        WorkspacePrompts.CodeAgentMessages records =
                WorkspacePrompts.getMessagesForCodeAgent(ctx, new ViewingPolicy(TaskResult.Type.CODE));

        assertNotNull(records.buildFailure(), "buildFailure() should be populated when a build fragment exists");
        assertTrue(records.buildFailure().contains("syntax error on line 42"));
    }

    @Test
    void testExactlyOneBuildStatusBlockExistsInCombinedWorkspace() throws IOException {
        var file = createTestFile("file.txt", "content");
        cm.addEditableFile(file);

        var ctx = new Context(cm, null);
        var frag = new ContextFragment.ProjectPathFragment(file, cm);
        ctx = ctx.addPathFragments(List.of(frag));
        ctx = ctx.withBuildResult(false, "Compilation failed");

        WorkspacePrompts.CodeAgentMessages records =
                WorkspacePrompts.getMessagesForCodeAgent(ctx, new ViewingPolicy(TaskResult.Type.CODE));

        String allText =
                records.workspace().stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        int count = (int) allText.split("<workspace_build_status").length - 1;
        assertEquals(1, count, "Should have exactly one build status block in the combined workspace");
    }

    // Helper method to create test files
    private ai.brokk.analyzer.ProjectFile createTestFile(String relativePath, String content) {
        try {
            var path = projectRoot.resolve(relativePath);
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes());
            return cm.toFile(relativePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test file: " + relativePath, e);
        }
    }
}
