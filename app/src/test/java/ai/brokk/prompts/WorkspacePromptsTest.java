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
import dev.langchain4j.data.message.AiMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for WorkspacePrompts builder and view modes.
 * Focuses on workspace rendering logic independent of CodeAgent integration.
 */
class WorkspacePromptsTest {

    private TestProject project;
    private TestContextManager cm;
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

    // Test 1a: CODE_READONLY_PLUS_UNTOUCHED view includes read-only fragments
    @Test
    void testCodeReadOnlyPlusUntouched_includesReadOnlyFragments() {
        var ctx = new Context(cm, null);
        var roFile = createTestFile("ro.txt", "read-only content");
        var roFrag = new ContextFragment.ProjectPathFragment(roFile, cm);
        ctx = ctx.addPathFragments(List.of(roFrag));
        ctx = ctx.setReadonly(roFrag, true);

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .build();

        assertFalse(messages.isEmpty(), "Should have messages for read-only fragments");
        var textContent = Messages.getText(messages.getFirst());
        assertTrue(textContent.contains("READ ONLY"), "Should contain read-only section header");
        assertTrue(textContent.contains("ro.txt"), "Should contain the read-only file");
    }

    // Test 1b: CODE_READONLY_PLUS_UNTOUCHED with changedFiles includes untouched editable
    @Test
    void testCodeReadOnlyPlusUntouched_includesUntouchedEditableWhenChangedFilesSet() throws IOException {
        var changedFile = createTestFile("changed.txt", "changed content");
        var untouchedFile = createTestFile("untouched.txt", "untouched content");
        cm.addEditableFile(changedFile);
        cm.addEditableFile(untouchedFile);

        var ctx = new Context(cm, null);
        var changedFrag = new ContextFragment.ProjectPathFragment(changedFile, cm);
        var untouchedFrag = new ContextFragment.ProjectPathFragment(untouchedFile, cm);
        ctx = ctx.addPathFragments(List.of(changedFrag, untouchedFrag));

        var changedFilesSet = Set.of(changedFile);

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .changedFiles(changedFilesSet)
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("untouched.txt"), "Should include untouched editable files");
        assertTrue(allText.contains("workspace_editable_unchanged"), "Should have editable_unchanged section for untouched files");
    }

    // Test 1c: CODE_READONLY_PLUS_UNTOUCHED with empty changedFiles includes build fragment
    @Test
    void testCodeReadOnlyPlusUntouched_includesBuildFragmentWhenChangedFilesEmpty() {
        var ctx = new Context(cm, null).withBuildResult(false, "Build failed: syntax error on line 42");

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .changedFiles(Set.of())
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(
                allText.contains("workspace_build_status"), "Should include build status when changedFiles is empty");
        assertTrue(allText.contains("syntax error on line 42"), "Should include build error details");
    }

    // Test 1d: EDITABLE_CHANGED view only includes changed editable fragments
    @Test
    void testEditableChanged_onlyIncludesChangedFragments() throws IOException {
        var changedFile = createTestFile("changed.txt", "changed");
        var untouchedFile = createTestFile("untouched.txt", "untouched");
        cm.addEditableFile(changedFile);
        cm.addEditableFile(untouchedFile);

        var ctx = new Context(cm, null);
        var changedFrag = new ContextFragment.ProjectPathFragment(changedFile, cm);
        var untouchedFrag = new ContextFragment.ProjectPathFragment(untouchedFile, cm);
        ctx = ctx.addPathFragments(List.of(changedFrag, untouchedFrag));

        var changedFilesSet = Set.of(changedFile);

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.EDITABLE_CHANGED)
                .changedFiles(changedFilesSet)
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("changed.txt"), "Should include changed file");
        assertFalse(allText.contains("untouched.txt"), "Should not include untouched file");
        assertTrue(allText.contains("workspace_editable_changed"), "Should indicate files have been changed");
    }

    // Test 1e: EDITABLE_CHANGED includes build status
    @Test
    void testEditableChanged_includesBuildStatus() throws IOException {
        var file = createTestFile("file.txt", "content");
        cm.addEditableFile(file);

        var ctx = new Context(cm, null).withBuildResult(false, "Compilation failed");
        var frag = new ContextFragment.ProjectPathFragment(file, cm);
        ctx = ctx.addPathFragments(List.of(frag));

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.EDITABLE_CHANGED)
                .changedFiles(Set.of(file))
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("workspace_build_status"), "Should include build status");
        assertTrue(allText.contains("Compilation failed"), "Should include build error message");
    }

    // Test 1f: EDITABLE_ALL includes all editable fragments regardless of changedFiles
    @Test
    void testEditableAll_includesAllEditableFragments() throws IOException {
        var file1 = createTestFile("file1.txt", "content1");
        var file2 = createTestFile("file2.txt", "content2");
        cm.addEditableFile(file1);
        cm.addEditableFile(file2);

        var ctx = new Context(cm, null);
        var frag1 = new ContextFragment.ProjectPathFragment(file1, cm);
        var frag2 = new ContextFragment.ProjectPathFragment(file2, cm);
        ctx = ctx.addPathFragments(List.of(frag1, frag2));

        // Only file1 is in changedFiles, but both should appear in EDITABLE_ALL
        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.EDITABLE_ALL)
                .changedFiles(Set.of(file1))
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("file1.txt"), "Should include file1");
        assertTrue(allText.contains("file2.txt"), "Should include file2");
    }

    // Test 1g: IN_ADDED_ORDER wraps in <workspace> tag
    @Test
    void testInAddedOrder_wrapsInWorkspaceTag() throws IOException {
        var file = createTestFile("file.txt", "content");
        cm.addEditableFile(file);

        var ctx = new Context(cm, null);
        var frag = new ContextFragment.ProjectPathFragment(file, cm);
        ctx = ctx.addPathFragments(List.of(frag));

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.IN_ADDED_ORDER)
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("<workspace>"), "Should wrap in <workspace> tag");
        assertTrue(allText.contains("</workspace>"), "Should close </workspace> tag");
    }

    // Test 1h: CONTENTS wraps both read-only and editable in <workspace> tag
    @Test
    void testContents_wrapsBothReadOnlyAndEditableInWorkspaceTag() throws IOException {
        var editableFile = createTestFile("editable.txt", "editable content");
        var readOnlyFile = createTestFile("readonly.txt", "readonly content");
        cm.addEditableFile(editableFile);

        var ctx = new Context(cm, null);
        var editableFrag = new ContextFragment.ProjectPathFragment(editableFile, cm);
        var readOnlyFrag = new ContextFragment.ProjectPathFragment(readOnlyFile, cm);
        ctx = ctx.addPathFragments(List.of(editableFrag, readOnlyFrag));
        ctx = ctx.setReadonly(readOnlyFrag, true);

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.GROUPED_BY_MUTABILITY)
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("<workspace>"), "Should wrap in <workspace> tag");
        assertTrue(allText.contains("readonly.txt"), "Should include read-only file");
        assertTrue(allText.contains("editable.txt"), "Should include editable file");
    }

    // Test 2: Build Fragment Duplication Prevention
    @Test
    void testBuildFragmentNotDuplicatedInCodeReadOnlyPlusUntouchedWhenChangedFilesNonEmpty() throws IOException {
        var changedFile = createTestFile("changed.txt", "content");
        cm.addEditableFile(changedFile);

        var ctx = new Context(cm, null).withBuildResult(false, "Build error");
        var frag = new ContextFragment.ProjectPathFragment(changedFile, cm);
        ctx = ctx.addPathFragments(List.of(frag));

        // With changedFiles non-empty, CODE_READONLY_PLUS_UNTOUCHED should NOT include build fragment
        var roMessages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .changedFiles(Set.of(changedFile))
                .build();

        var roText = roMessages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(
                roText.contains("workspace_build_status"),
                "CODE_READONLY_PLUS_UNTOUCHED should not include build status when changedFiles is non-empty");

        // EDITABLE_CHANGED should include it
        var editableMessages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.EDITABLE_CHANGED)
                .changedFiles(Set.of(changedFile))
                .build();

        var editableText =
                editableMessages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(editableText.contains("workspace_build_status"), "EDITABLE_CHANGED should include build status");
    }

    // Test 3: Summary Fragment Combination
    @Test
    void testSummaryFragmentsCombinedIntoSingleBlock() throws IOException, InterruptedException, ExecutionException {
        // Create files with classes so we can create SummaryFragments
        var file1 =
                createTestFile("src/main/java/com/example/Class1.java", "package com.example;\npublic class Class1 {}");
        var file2 =
                createTestFile("src/main/java/com/example/Class2.java", "package com.example;\npublic class Class2 {}");
        cm.addEditableFile(file1);
        cm.addEditableFile(file2);

        // Update analyzer to discover classes
        var analyzer = cm.getAnalyzerWrapper().updateFiles(Set.of(file1, file2)).get();
        assertFalse(analyzer.getAllDeclarations().isEmpty());

        // Create summary fragments for both classes
        var summary1 = new ContextFragment.SummaryFragment(
                cm, "com.example.Class1", ContextFragment.SummaryType.CODEUNIT_SKELETON);
        var summary2 = new ContextFragment.SummaryFragment(
                cm, "com.example.Class2", ContextFragment.SummaryType.CODEUNIT_SKELETON);

        var ctx = new Context(cm, null);
        ctx = ctx.addVirtualFragments(List.of(summary1, summary2));
        ctx.awaitContextsAreComputed(java.time.Duration.ofSeconds(5));

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("<api_summaries"), "Should have api_summaries block");
        assertTrue(allText.contains("fragmentid=\"api_summaries\""), "Should have correct fragmentid");

        // Count how many times api_summaries appears (should be exactly once)
        int count = (int) allText.split("<api_summaries").length - 1;
        assertEquals(1, count, "Should have exactly one combined api_summaries block");
    }

    // Test 4a: Empty context returns empty message list
    @Test
    void testEmptyContext_returnsEmptyMessageList() {
        var ctx = new Context(cm, null);

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .build();

        assertTrue(messages.isEmpty(), "Empty context should return no messages");
    }

    // Test 4b: Only read-only fragments (no editable)
    @Test
    void testOnlyReadOnlyFragments_rendersReadOnlySection() {
        var roFile = createTestFile("ro.txt", "read-only");
        var roFrag = new ContextFragment.ProjectPathFragment(roFile, cm);

        var ctx = new Context(cm, null);
        ctx = ctx.addPathFragments(List.of(roFrag));
        ctx = ctx.setReadonly(roFrag, true);

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("READ ONLY"), "Should have read-only section");
        assertFalse(allText.contains("workspace_editable"), "Should not have editable section");
    }

    // Test 4c: Only editable fragments (no read-only)
    @Test
    void testOnlyEditableFragments_rendersEditableSection() throws IOException {
        var editFile = createTestFile("editable.txt", "editable");
        cm.addEditableFile(editFile);

        var ctx = new Context(cm, null);
        var editFrag = new ContextFragment.ProjectPathFragment(editFile, cm);
        ctx = ctx.addPathFragments(List.of(editFrag));

        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.EDITABLE_ALL)
                .build();

        var allText = messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allText.contains("workspace_editable"), "Should have editable section");
    }

    // Test 4d: changedFiles with no intersecting fragments
    @Test
    void testChangedFilesWithNoIntersect_stillWorks() throws IOException {
        var editFile = createTestFile("file.txt", "content");
        cm.addEditableFile(editFile);
        var nonEditFile = createTestFile("other.txt", "other");

        var ctx = new Context(cm, null);
        var frag = new ContextFragment.ProjectPathFragment(editFile, cm);
        ctx = ctx.addPathFragments(List.of(frag));

        // Set changedFiles to a file that doesn't intersect with any fragments
        var messages = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.EDITABLE_CHANGED)
                .changedFiles(Set.of(nonEditFile))
                .build();

        // Should return empty since no editable fragments match changedFiles
        assertTrue(messages.isEmpty(), "Should return empty when changedFiles don't intersect fragments");
    }

    // Test 5: ViewingPolicy Propagation
    @Test
    void testViewingPolicy_propagatesToStringFragment() {
        // This test verifies that ViewingPolicy is properly passed through to fragment rendering
        // The actual visibility differences depend on StringFragment implementation,
        // but we can verify that different policies are used without error.

        var ctx = new Context(cm, null);

        var policy1 = new ViewingPolicy(TaskResult.Type.CODE);
        var policy2 = new ViewingPolicy(TaskResult.Type.ASK);

        // Both should build without error (actual visual differences depend on fragment implementations)
        var messages1 = WorkspacePrompts.builder(ctx, policy1)
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .build();

        var messages2 = WorkspacePrompts.builder(ctx, policy2)
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .build();

        // Both should succeed (empty in this case since context is empty)
        assertTrue(messages1.isEmpty() && messages2.isEmpty(), "Both policies should work without error");
    }

    // Test 6: Acknowledgment Messages
    @Test
    void testAcknowledgmentMessages_correctForEachView() throws IOException {
        var file = createTestFile("file.txt", "content");
        cm.addEditableFile(file);

        var ctx = new Context(cm, null);
        var frag = new ContextFragment.ProjectPathFragment(file, cm);
        ctx = ctx.addPathFragments(List.of(frag));

        // Test CODE_READONLY_PLUS_UNTOUCHED acknowledgment
        var msgs1 = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.CODE_READONLY_PLUS_UNTOUCHED)
                .build();
        if (!msgs1.isEmpty()) {
            var ack = msgs1.stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .findFirst();
            assertTrue(
                    ack.isPresent() && ack.get().text().contains("read-only"),
                    "CODE_READONLY_PLUS_UNTOUCHED should have appropriate acknowledgment");
        }

        // Test EDITABLE_CHANGED acknowledgment
        var msgs2 = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.EDITABLE_CHANGED)
                .changedFiles(Set.of(file))
                .build();
        if (!msgs2.isEmpty()) {
            var ack = msgs2.stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .findFirst();
            assertTrue(
                    ack.isPresent() && ack.get().text().contains("changed"),
                    "EDITABLE_CHANGED should mention changed files in acknowledgment");
        }

        // Test CONTENTS acknowledgment
        var msgs3 = WorkspacePrompts.builder(ctx, new ViewingPolicy(TaskResult.Type.CODE))
                .view(WorkspacePrompts.WorkspaceView.GROUPED_BY_MUTABILITY)
                .build();
        if (!msgs3.isEmpty()) {
            var ack = msgs3.stream()
                    .filter(AiMessage.class::isInstance)
                    .map(AiMessage.class::cast)
                    .findFirst();
            assertTrue(
                    ack.isPresent() && ack.get().text().contains("Workspace contents"),
                    "CONTENTS should have workspace-specific acknowledgment");
        }
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
