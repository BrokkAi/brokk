package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class NonTextGrouperTest {

    // Helper method to create a FileConflict for testing
    private MergeAgent.FileConflict createFileConflict(String ourFile, String theirFile, String baseFile) {
        var root = Path.of("").toAbsolutePath().getRoot();
        return new MergeAgent.FileConflict(
                ourFile != null ? new ProjectFile(root, ourFile) : null,
                null,
                theirFile != null ? new ProjectFile(root, theirFile) : null,
                null,
                baseFile != null ? new ProjectFile(root, baseFile) : null,
                null);
    }

    // Helper method to create NonTextMetadata for testing
    private MergeAgent.NonTextMetadata createNonTextMetadata(
            NonTextType type, String indexPath, String ourPath, String theirPath) {
        return new MergeAgent.NonTextMetadata(
                type, indexPath, ourPath, theirPath, false, false, false, false, false, false);
    }

    @Test
    void testRenameChainGrouping() {
        // Simulate a rename chain A -> B and B -> C across sides:
        // conflict1 links A -> B; conflict2 links B -> C. Shared token "B" should connect them.
        var conflict1 = Map.entry(
                createFileConflict("A", "B", "A_base"),
                createNonTextMetadata(NonTextType.RENAME_MODIFY, "B", "A", "B"));
        var conflict2 = Map.entry(
                createFileConflict("B", "C", "B_base"),
                createNonTextMetadata(NonTextType.RENAME_RENAME, "C", "B", "C"));

        List<List<Map.Entry<MergeAgent.FileConflict, MergeAgent.NonTextMetadata>>> groups =
                NonTextGrouper.group(List.of(conflict1, conflict2));
        assertEquals(1, groups.size(), "Rename chain should be grouped together");
        assertEquals(2, groups.getFirst().size(), "Group should contain both conflicts");
    }

    @Test
    void testUnrelatedConflictsAreSeparate() {
        var conflict1 = Map.entry(
                createFileConflict("path/to/file1", "path/to/file1", "path/to/file1"),
                createNonTextMetadata(NonTextType.DELETE_MODIFY, "path/to/file1", null, "path/to/file1"));
        var conflict2 = Map.entry(
                createFileConflict("another/file2", "another/file2", "another/file2"),
                createNonTextMetadata(NonTextType.DELETE_MODIFY, "another/file2", null, "another/file2"));

        List<List<Map.Entry<MergeAgent.FileConflict, MergeAgent.NonTextMetadata>>> groups =
                NonTextGrouper.group(List.of(conflict1, conflict2));
        assertEquals(2, groups.size(), "Unrelated conflicts should not be grouped together");
    }
}
