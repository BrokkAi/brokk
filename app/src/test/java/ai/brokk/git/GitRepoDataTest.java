package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestGitRepo;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoDataTest {

    @TempDir
    Path tempDir;

    @Test
    void getFileDiffs_skips_content_loading_for_binary_files() throws Exception {
        // Arrange
        // Initialize a real git repo so GitRepo constructor succeeds
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // repo initialized
        }

        // Create a fake binary ProjectFile
        var binaryFile = new ProjectFile(tempDir, "large_binary.bin") {
            @Override
            public boolean isBinary() {
                return true;
            }

            @Override
            public Optional<Long> size() {
                /** Simulate a very large size (e.g., 1GB). */
                return Optional.of(1024L * 1024L * 1024L);
            }
        };

        // Local subclass of GitRepo in the same package as GitRepoData
        // to handle path resolution for the test
        var mockRepo = new GitRepo(tempDir) {
            @Override
            public Optional<ProjectFile> toProjectFile(String repoRelativePath) {
                if ("large_binary.bin".equals(repoRelativePath)) {
                    return Optional.of(binaryFile);
                }
                return Optional.empty();
            }
        };

        // Subclass GitRepoData to verify getRefContent is never called
        var gitRepoData = new GitRepoData(mockRepo) {
            @Override
            public String getRefContent(String ref, ProjectFile file) {
                throw new AssertionError("getRefContent should NOT be called for binary files");
            }

            // We need to provide a mock implementation of scanDiffs
            @Override
            protected List<DiffEntry> scanDiffs(String oldRef, String newRef) {
                // Use reflection or a factory to create a DiffEntry since the constructor is protected
                try {
                    var constructor = DiffEntry.class.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    DiffEntry entry = constructor.newInstance();

                    var oldPathField = DiffEntry.class.getDeclaredField("oldPath");
                    oldPathField.setAccessible(true);
                    oldPathField.set(entry, "large_binary.bin");

                    var newPathField = DiffEntry.class.getDeclaredField("newPath");
                    newPathField.setAccessible(true);
                    newPathField.set(entry, "large_binary.bin");

                    var changeTypeField = DiffEntry.class.getDeclaredField("changeType");
                    changeTypeField.setAccessible(true);
                    changeTypeField.set(entry, DiffEntry.ChangeType.MODIFY);

                    return List.of(entry);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        // Act
        var diffs = gitRepoData.getFileDiffs("HEAD^", "HEAD");

        // Assert
        assertEquals(1, diffs.size());
        var diff = diffs.getFirst();
        assertTrue(diff.isBinary());
        assertEquals(GitRepoData.BINARY_FILE_MARKER + " (old)]", diff.oldText());
        assertEquals(GitRepoData.BINARY_FILE_MARKER + " (new)]", diff.newText());
    }
}
