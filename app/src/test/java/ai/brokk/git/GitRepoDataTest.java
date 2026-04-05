package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoDataTest {

    @TempDir
    Path tempDir;

    @Test
    void getFileDiffs_skips_content_loading_for_binary_files() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("large_binary.bin"), new byte[] {0, 1, 2, 3});
            git.add().addFilepattern("large_binary.bin").call();
            git.commit().setMessage("Initial binary").setSign(false).call();

            Files.write(tempDir.resolve("large_binary.bin"), new byte[] {0, 5, 6, 7});
            git.add().addFilepattern("large_binary.bin").call();
            git.commit().setMessage("Updated binary").setSign(false).call();
        }

        var binaryFile = new ProjectFile(tempDir, "large_binary.bin") {
            @Override
            public boolean isBinary() {
                return true;
            }

            @Override
            public Optional<Long> size() {
                return Optional.of(1024L * 1024L * 1024L);
            }
        };

        var repo = new GitRepo(tempDir) {
            @Override
            public Optional<ProjectFile> toProjectFile(String repoRelativePath) {
                if ("large_binary.bin".equals(repoRelativePath)) {
                    return Optional.of(binaryFile);
                }
                return super.toProjectFile(repoRelativePath);
            }
        };

        var gitRepoData = new GitRepoData(repo) {
            @Override
            public String getRefContent(String ref, ProjectFile file) {
                throw new AssertionError("getRefContent should NOT be called for binary files");
            }
        };

        var diffs = gitRepoData.getFileDiffs("HEAD^", "HEAD");

        assertEquals(1, diffs.size());
        var diff = diffs.getFirst();
        assertTrue(diff.isBinary());
        assertEquals(GitRepoData.BINARY_FILE_MARKER, diff.oldText());
        assertEquals(GitRepoData.BINARY_FILE_MARKER, diff.newText());
    }

    @Test
    void getFileDiffs_modifyUsesOneTreeLookupPerRefEvenWithDuplicateDiffEntries() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.writeString(tempDir.resolve("tracked.txt"), "before\n");
            git.add().addFilepattern("tracked.txt").call();
            git.commit().setMessage("Initial").setSign(false).call();

            Files.writeString(tempDir.resolve("tracked.txt"), "after\n");
            git.add().addFilepattern("tracked.txt").call();
            git.commit().setMessage("Update").setSign(false).call();
        }

        try (var repo = new GitRepo(tempDir)) {
            var gitRepoData = new CountingGitRepoData(repo, true);

            var diffs = gitRepoData.getFileDiffs("HEAD^", "HEAD");

            assertEquals(2, diffs.size());
            assertEquals(2, gitRepoData.treeLookupCount);
        }
    }

    @Test
    void getFileDiffs_addChecksOnlyNewPathAtEachRef() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.writeString(tempDir.resolve("base.txt"), "base\n");
            git.add().addFilepattern("base.txt").call();
            git.commit().setMessage("Initial").setSign(false).call();

            Files.writeString(tempDir.resolve("added.txt"), "added\n");
            git.add().addFilepattern("added.txt").call();
            git.commit().setMessage("Add file").setSign(false).call();
        }

        try (var repo = new GitRepo(tempDir)) {
            var gitRepoData = new CountingGitRepoData(repo, false);

            var diffs = gitRepoData.getFileDiffs("HEAD^", "HEAD");

            assertEquals(1, diffs.size());
            assertEquals(2, gitRepoData.treeLookupCount);
        }
    }

    private static final class CountingGitRepoData extends GitRepoData {
        private final boolean duplicateDiffEntries;
        private int treeLookupCount = 0;

        private CountingGitRepoData(GitRepo repo, boolean duplicateDiffEntries) {
            super(repo);
            this.duplicateDiffEntries = duplicateDiffEntries;
        }

        @Override
        boolean pathExistsInTree(ObjectId treeId, String path) throws GitAPIException {
            treeLookupCount++;
            return super.pathExistsInTree(treeId, path);
        }

        @Override
        protected List<DiffEntry> scanDiffs(String oldRef, String newRef) throws GitAPIException {
            var diffs = super.scanDiffs(oldRef, newRef);
            if (!duplicateDiffEntries || diffs.isEmpty()) {
                return diffs;
            }
            return List.of(diffs.getFirst(), diffs.getFirst());
        }
    }
}
