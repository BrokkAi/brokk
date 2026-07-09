package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
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

        var ctx = new Context(cm);
        var frag = new ContextFragments.ProjectPathFragment(file, cm);
        ctx = ctx.addFragments(List.of(frag));

        WorkspacePrompts.CodeAgentMessages records =
                WorkspacePrompts.getMessagesForCodeAgent(ctx, EnumSet.of(SpecialTextType.TASK_LIST));

        assertNotNull(records);
        assertFalse(records.workspace().isEmpty(), "workspace() should return combined messages");
        var allText = records.workspace().stream().map(Messages::getText).collect(Collectors.joining("\n"));
        assertTrue(allText.contains("This editable Workspace is the current source of truth for these files."));
        assertTrue(allText.contains("Do not ask to reopen, inspect, or re-check these files before editing"));
    }

    @Test
    void testGetMessagesInAddedOrderReturnsOnlyDynamicWhenNoSystemPinned() throws IOException {
        var file = createTestFile("dynamic.txt", "dynamic content");
        cm.addEditableFile(file);

        var ctx = new Context(cm);
        var frag = new ContextFragments.ProjectPathFragment(file, cm);
        ctx = ctx.addFragments(List.of(frag));

        var messages = WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.noneOf(SpecialTextType.class));

        assertFalse(messages.isEmpty(), "Should return messages for dynamic content");

        String allText = messages.stream().map(Messages::getText).collect(Collectors.joining("\n"));
        assertFalse(allText.contains("<workspace>"), "Should no longer contain grouped workspace block");
        assertTrue(allText.contains("<fragment"), "Should contain individual fragment tags");
        assertTrue(allText.contains("dynamic.txt"), "Should contain the dynamic fragment");
    }

    @Test
    void testGetMessagesInAddedOrderSplitsUserPinnedFragments() {
        var ctx = new Context(cm);

        // Add a regular (non-special) fragment and pin it
        var regularFrag = new ContextFragments.StringFragment(cm, "Pinned content", "Pinned Fragment", "text/plain");
        // Add a special fragment (should go to non-pinned section even if pinned)
        var specialFrag = SpecialTextType.BUILD_RESULTS.create(cm, "Build output here");

        ctx = ctx.addFragments(List.of(regularFrag, specialFrag));
        ctx = ctx.withPinned(regularFrag, true);

        var messages = WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.noneOf(SpecialTextType.class));

        String allText = messages.stream().map(Messages::getText).collect(Collectors.joining("\n"));

        // No more grouped wrapper blocks
        assertFalse(allText.contains("<workspace_static>"), "Should not contain static workspace block");
        assertFalse(allText.contains("<workspace>"), "Should not contain dynamic workspace block");

        // Verify ordering: pinned fragment first (now with cache control), then non-pinned
        int pinnedIndex = -1;
        int specialIndex = -1;

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            String text = Messages.getText(msg);
            if (text.contains("Pinned Fragment")) pinnedIndex = i;
            if (text.contains("Build")) specialIndex = i;
        }

        assertTrue(pinnedIndex != -1 && specialIndex != -1);
        assertTrue(pinnedIndex < specialIndex, "Pinned fragment should come before non-pinned special fragment");

        var pinnedMsg = messages.get(pinnedIndex);
        assertInstanceOf(UserMessage.class, pinnedMsg);

        var pinnedContents = ((UserMessage) pinnedMsg).contents();
        var lastTextContent = pinnedContents.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .reduce((first, second) -> second) // get last
                .orElseThrow();
        assertEquals("ephemeral", lastTextContent.cacheControl());
    }

    @Test
    void testSpecialFragmentsGoToDynamicEvenWhenPinned() {
        var ctx = new Context(cm);

        // Add a special fragment (TASK_LIST) and pin it
        var specialFrag = SpecialTextType.TASK_LIST.create(cm, "{\"tasks\": []}");
        ctx = ctx.addFragments(List.of(specialFrag));
        ctx = ctx.withPinned(specialFrag, true);

        var messages = WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.noneOf(SpecialTextType.class));

        String allText = messages.stream().map(Messages::getText).collect(Collectors.joining("\n"));

        // Special fragments should always go to non-pinned section
        assertFalse(
                allText.contains("static Workspace contents"),
                "Should not have static ack for pinned special fragments");
        assertTrue(allText.contains("<fragment"), "Should contain fragment tag");
        assertTrue(allText.contains("Task List"), "Should contain the special fragment");
    }

    @Test
    void testStaticAckMessageHasCacheControl() {
        var ctx = new Context(cm);
        // Add a regular fragment and pin it to create static content
        var regularFrag = new ContextFragments.StringFragment(cm, "Pinned content", "Pinned Fragment", "text/plain");
        ctx = ctx.addFragments(List.of(regularFrag));
        ctx = ctx.withPinned(regularFrag, true);

        var messages = WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.noneOf(SpecialTextType.class));

        // Last pinned UserMessage should have cache control on its last TextContent
        var cacheControlledMsg = (UserMessage) messages.stream()
                .filter(m -> m instanceof UserMessage um
                        && um.contents().stream().anyMatch(c -> "ephemeral".equals(c.cacheControl())))
                .findFirst()
                .orElseThrow();

        var lastContent = cacheControlledMsg.contents().getLast();
        assertEquals("ephemeral", lastContent.cacheControl());
    }

    @Test
    void testGetMessagesInAddedOrderReturnsExpectedMessageCount() {
        var ctx = new Context(cm);

        // Add a user-pinned non-special fragment and a dynamic fragment
        var pinnedFrag = new ContextFragments.StringFragment(cm, "Pinned content", "Pinned Fragment", "text/plain");
        var dynamicFrag = new ContextFragments.StringFragment(cm, "Some content", "Test Fragment", "text/plain");

        ctx = ctx.addFragments(List.of(pinnedFrag, dynamicFrag));
        ctx = ctx.withPinned(pinnedFrag, true);

        var messages = WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.noneOf(SpecialTextType.class));

        // Expected sequence:
        // 1. UserMessage (Pinned Fragment, now with cache control set to ephemeral)
        // 2. UserMessage (Test Fragment)
        // 3. AiMessage (final ack)
        // Note: if style guide is present, it adds another message at index 0.
        // We assume style guide is empty in this test environment.
        assertTrue(
                messages.size() >= 3, "Should return at least 3 messages for one pinned and one non-pinned fragment");

        String lastText = Messages.getText(messages.getLast());
        assertTrue(
                lastText.contains("providing these Workspace contents"),
                "Last message should be the final acknowledgment");
    }

    @Test
    void testBuildFailurePopulatedWhenBuildFragmentExists() {
        var ctx = new Context(cm).withBuildResult(false, "Build failed: syntax error on line 42");

        WorkspacePrompts.CodeAgentMessages records =
                WorkspacePrompts.getMessagesForCodeAgent(ctx, EnumSet.of(SpecialTextType.TASK_LIST));

        assertNotNull(records.buildFailure(), "buildFailure() should be populated when a build fragment exists");
        assertTrue(records.buildFailure().contains("syntax error on line 42"));
    }

    @Test
    void testExactlyOneBuildStatusBlockExistsInCombinedWorkspace() throws IOException {
        var file = createTestFile("file.txt", "content");
        cm.addEditableFile(file);

        var ctx = new Context(cm);
        var frag = new ContextFragments.ProjectPathFragment(file, cm);
        ctx = ctx.addFragments(List.of(frag));
        ctx = ctx.withBuildResult(false, "Compilation failed");

        WorkspacePrompts.CodeAgentMessages records =
                WorkspacePrompts.getMessagesForCodeAgent(ctx, EnumSet.of(SpecialTextType.TASK_LIST));

        String allText = records.workspace().stream().map(Messages::getText).collect(Collectors.joining("\n"));
        int count = (int) allText.split("<workspace_build_status").length - 1;
        assertEquals(1, count, "Should have exactly one build status block in the combined workspace");
    }

    @Test
    void testSortEditableFragmentsByMtimeOrdersProjectFilesByModificationTime() throws IOException {
        // Create three test files
        var fileA = createTestFile("src/A.java", "class A {}");
        var fileB = createTestFile("src/B.java", "class B {}");
        var fileC = createTestFile("src/C.java", "class C {}");

        // Explicitly set mtimes to ensure ordering: A oldest, then B, then C newest
        long baseMillis = System.currentTimeMillis() - 10_000L;
        Files.setLastModifiedTime(projectRoot.resolve("src/A.java"), FileTime.fromMillis(baseMillis));
        Files.setLastModifiedTime(projectRoot.resolve("src/B.java"), FileTime.fromMillis(baseMillis + 1_000L));
        Files.setLastModifiedTime(projectRoot.resolve("src/C.java"), FileTime.fromMillis(baseMillis + 2_000L));

        // Create fragments in reverse order (C, B, A) to verify sorting reorders them
        var fragC = new ContextFragments.ProjectPathFragment(fileC, cm);
        var fragB = new ContextFragments.ProjectPathFragment(fileB, cm);
        var fragA = new ContextFragments.ProjectPathFragment(fileA, cm);

        var ctx = new Context(cm).addFragments(List.of(fragC, fragB, fragA));

        // Sort and verify order is by mtime (oldest A, then B, then newest C)
        var sorted = ContextFragment.sortByMtime(ctx.getEditableFragments()).toList();

        assertEquals(3, sorted.size(), "All three fragments should be present");
        assertEquals(fragA, sorted.get(0), "Oldest file A should be first");
        assertEquals(fragB, sorted.get(1), "Middle file B should be second");
        assertEquals(fragC, sorted.get(2), "Newest file C should be last");
    }

    @Test
    void testFormatTocConsolidatesFormats() {
        var file = createTestFile("test.java", "class Test {}");
        cm.addEditableFile(file);

        var ctx = new Context(cm);
        var frag = new ContextFragments.ProjectPathFragment(file, cm);
        ctx = ctx.addFragments(List.of(frag));

        // TOC should always show a single editable section and never split by changed/unchanged
        String toc = WorkspacePrompts.formatToc(ctx);
        assertTrue(toc.contains("<workspace_editable>"), "Should have a single editable section");
        assertTrue(toc.contains("loc=\"1\""), "Should include loc attribute in TOC entry");
        assertTrue(
                toc.contains("Base edits on that content; do not ask to inspect those files again."),
                "TOC should remind the model to use the current Workspace directly");
        assertFalse(
                toc.contains("<workspace_editable_unchanged>"),
                "Toc should no longer include an 'unchanged' editable section");
        assertFalse(
                toc.contains("<workspace_editable_changed>"),
                "Toc should no longer include a 'changed' editable section");
    }

    @Test
    void testFormatTocEmptyWorkspaceMessage() {
        var ctx = new Context(cm);
        String toc = WorkspacePrompts.formatToc(ctx);

        assertTrue(toc.contains("The Workspace is currently empty."));
        assertFalse(toc.contains("Here is a list of the full contents of the Workspace that you can refer to above."));
    }

    @Test
    void testSortEditableFragmentsByMtimePreservesNonProjectFragmentOrder() throws IOException {
        var file = createTestFile("file.txt", "content");
        cm.addEditableFile(file);

        // Create an editable virtual fragment (UsageFragment) and a project path fragment
        var virtualFrag = new ContextFragments.UsageFragment(cm, "com.example.SomeTarget");
        var projectFrag = new ContextFragments.ProjectPathFragment(file, cm);

        var ctx = new Context(cm).addFragments(List.of(projectFrag)).addFragments(virtualFrag);

        // Sort and verify virtual fragment stays first
        var sorted = ContextFragment.sortByMtime(ctx.getEditableFragments()).toList();

        assertEquals(2, sorted.size());
        assertInstanceOf(
                ContextFragments.UsageFragment.class, sorted.get(0), "Editable virtual fragment should remain first");
        assertEquals(projectFrag, sorted.get(1), "Project fragment should be second");
    }

    // Helper method to create test files
    private ProjectFile createTestFile(String relativePath, String content) {
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
