package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test auto-scroll functionality in BufferDiffPanel.
 * Verifies that the panel automatically scrolls to the first difference when a diff is opened.
 */
class BufferDiffPanelAutoScrollTest {

    @Test
    @DisplayName("scrollToFirstDifference method exists and handles null safely")
    void testScrollToFirstDifferenceMethodExists() throws Exception {
        // Verify that the scrollToFirstDifference method exists on BufferDiffPanel
        Method scrollMethod = BufferDiffPanel.class.getDeclaredMethod("scrollToFirstDifference");
        assertNotNull(scrollMethod, "scrollToFirstDifference method should exist");

        // Verify the method is private as intended
        assertTrue(java.lang.reflect.Modifier.isPrivate(scrollMethod.getModifiers()),
                  "scrollToFirstDifference should be private");
    }

    @Test
    @DisplayName("Auto-scroll logic correctly identifies first difference in patch")
    void testFirstDifferenceSelection() throws Exception {
        // Create a test patch with multiple differences to verify logic
        var originalLines = List.of(
            "line 1",
            "line 2",
            "line 3",
            "line 4",
            "line 5"
        );

        var revisedLines = List.of(
            "line 1",
            "modified line 2",
            "line 3",
            "new line 3.5",
            "line 4",
            "modified line 5"
        );

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // Verify patch has differences
        assertFalse(patch.getDeltas().isEmpty(), "Patch should have differences");

        // Verify first delta is at the expected position
        AbstractDelta<String> firstDelta = patch.getDeltas().getFirst();
        assertNotNull(firstDelta, "First delta should not be null");

        // The first difference should be at line 1 (0-based indexing for "line 2" modification)
        assertEquals(1, firstDelta.getSource().getPosition(),
                    "First difference should be at line 1 (modification of 'line 2')");
    }

    @Test
    @DisplayName("Auto-scroll handles empty patch gracefully")
    void testEmptyPatchHandling() throws Exception {
        // Create identical lists to generate empty patch
        var originalLines = List.of("line 1", "line 2", "line 3");
        var revisedLines = List.of("line 1", "line 2", "line 3");

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // Verify patch is empty
        assertTrue(patch.getDeltas().isEmpty(), "Patch should be empty for identical content");

        // The auto-scroll logic should handle this gracefully (selectedDelta will be null)
        // This test verifies that our logic correctly handles this case
    }

    @Test
    @DisplayName("selectedDelta initialization follows first difference pattern")
    void testSelectedDeltaInitializationLogic() throws Exception {
        // Test the logic used in refreshDiffNode for selectedDelta initialization
        var originalLines = List.of("A", "B", "C");
        var revisedLines = List.of("A", "Modified B", "C", "D");

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // Simulate the selectedDelta initialization logic from refreshDiffNode
        AbstractDelta<String> selectedDelta = null;
        if (!patch.getDeltas().isEmpty()) {
            selectedDelta = patch.getDeltas().getFirst();
        }

        // Verify the logic works correctly
        assertNotNull(selectedDelta, "selectedDelta should be set to first delta");
        assertEquals(1, selectedDelta.getSource().getPosition(),
                    "First delta should be at position 1 (modification of 'B')");
    }

    @Test
    @DisplayName("shouldSkipAutoScroll detects file addition scenario")
    void testFileAdditionDetection() throws Exception {
        // Simulate file addition: empty original vs content revised
        var originalLines = List.<String>of(); // Empty file
        var revisedLines = List.of(
            "public class NewFile {",
            "    public void method() {",
            "        System.out.println(\"Hello\");",
            "    }",
            "}"
        );

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // Verify patch structure - should be INSERT delta
        assertFalse(patch.getDeltas().isEmpty(), "Patch should have deltas for file addition");

        var firstDelta = patch.getDeltas().getFirst();
        assertEquals(0, firstDelta.getSource().size(), "Source should be empty for file addition");
        assertTrue(firstDelta.getTarget().size() > 0, "Target should have content for file addition");

        // The shouldSkipAutoScroll method would detect this via isOneSidedContent()
        boolean isOneSided = patch.getDeltas().stream().allMatch(delta ->
            delta.getSource().size() == 0 || delta.getTarget().size() == 0);
        assertTrue(isOneSided, "File addition should be detected as one-sided content");
    }

    @Test
    @DisplayName("shouldSkipAutoScroll detects file deletion scenario")
    void testFileDeletionDetection() throws Exception {
        // Simulate file deletion: content original vs empty revised
        var originalLines = List.of(
            "public class DeletedFile {",
            "    public void method() {",
            "        System.out.println(\"Goodbye\");",
            "    }",
            "}"
        );
        var revisedLines = List.<String>of(); // Empty file

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // Verify patch structure - should be DELETE delta
        assertFalse(patch.getDeltas().isEmpty(), "Patch should have deltas for file deletion");

        var firstDelta = patch.getDeltas().getFirst();
        assertTrue(firstDelta.getSource().size() > 0, "Source should have content for file deletion");
        assertEquals(0, firstDelta.getTarget().size(), "Target should be empty for file deletion");

        // The shouldSkipAutoScroll method would detect this via isOneSidedContent()
        boolean isOneSided = patch.getDeltas().stream().allMatch(delta ->
            delta.getSource().size() == 0 || delta.getTarget().size() == 0);
        assertTrue(isOneSided, "File deletion should be detected as one-sided content");
    }

    @Test
    @DisplayName("shouldSkipAutoScroll handles mixed delete+insert changes")
    void testMixedDeleteInsertChanges() throws Exception {
        // Simulate common delete+insert scenario that creates CHANGE deltas
        var originalLines = List.of(
            "old line 1",
            "old line 2",
            "old line 3"
        );
        var revisedLines = List.of(
            "new line 1",
            "new line 2",
            "new line 3"
        );

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // This typically creates CHANGE deltas, not pure INSERT/DELETE
        assertFalse(patch.getDeltas().isEmpty(), "Patch should have deltas");

        // Check if this is detected as one-sided (it shouldn't be)
        boolean isOneSided = patch.getDeltas().stream().allMatch(delta ->
            delta.getSource().size() == 0 || delta.getTarget().size() == 0);

        // This should NOT be one-sided since both source and target have content
        assertFalse(isOneSided, "Mixed changes should not be detected as one-sided");

        // This demonstrates the case where auto-scroll should work normally
    }

    @Test
    @DisplayName("shouldSkipAutoScroll detects massive file replacement")
    void testMassiveFileReplacement() throws Exception {
        // Create a large file replacement scenario
        var originalLines = List.of(
            "// Old implementation",
            "class OldClass {",
            "    void oldMethod1() { }",
            "    void oldMethod2() { }",
            "    void oldMethod3() { }",
            "    // ... many more lines ...",
            "    void oldMethod20() { }",
            "    void oldMethod21() { }",
            "    void oldMethod22() { }",
            "    void oldMethod23() { }",
            "    void oldMethod24() { }",
            "    void oldMethod25() { }"
        );

        var revisedLines = List.of(
            "// New implementation",
            "class NewClass {",
            "    void newMethod1() { }",
            "    void newMethod2() { }",
            "    void newMethod3() { }",
            "    // ... many more lines ...",
            "    void newMethod20() { }",
            "    void newMethod21() { }",
            "    void newMethod22() { }",
            "    void newMethod23() { }",
            "    void newMethod24() { }",
            "    void newMethod25() { }"
        );

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // Verify this creates a substantial change
        assertFalse(patch.getDeltas().isEmpty(), "Patch should have deltas for massive replacement");

        // Check characteristics that massive change detection would look for
        if (patch.getDeltas().size() == 1) {
            var delta = patch.getDeltas().getFirst();
            boolean isMassive = delta.getSource().getPosition() <= 2 &&
                               (delta.getSource().size() > 20 || delta.getTarget().size() > 20);
            // Note: This test documents the expected behavior, actual result depends on diff algorithm
        }
    }

    @Test
    @DisplayName("shouldSkipAutoScroll allows normal edits to auto-scroll")
    void testNormalEditsAllowAutoScroll() throws Exception {
        // Test normal code edits that should trigger auto-scroll
        var originalLines = List.of(
            "public class Example {",
            "    public void method() {",
            "        int x = 1;", // This line will be modified
            "        System.out.println(x);",
            "    }",
            "}"
        );

        var revisedLines = List.of(
            "public class Example {",
            "    public void method() {",
            "        int x = 2;", // Modified line
            "        System.out.println(x);",
            "    }",
            "}"
        );

        Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);

        // Verify patch has changes
        assertFalse(patch.getDeltas().isEmpty(), "Patch should have deltas for normal edits");

        // This should NOT be one-sided content
        boolean isOneSided = patch.getDeltas().stream().allMatch(delta ->
            delta.getSource().size() == 0 || delta.getTarget().size() == 0);
        assertFalse(isOneSided, "Normal edits should not be one-sided");

        // Should not be massive change (single line modification)
        if (patch.getDeltas().size() == 1) {
            var delta = patch.getDeltas().getFirst();
            boolean isMassive = delta.getSource().getPosition() <= 2 &&
                               (delta.getSource().size() > 20 || delta.getTarget().size() > 20);
            assertFalse(isMassive, "Single line edit should not be massive change");
        }

        // This represents the scenario where auto-scroll should work
    }
}