package ai.brokk.gui.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestGitRepo;
import ai.brokk.testutil.TestLanguage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitWorktreeTabTest {

    @TempDir
    Path tempDir;

    @Test
    void setupNewGitWorktree_doesNotReplicateAnalyzerCache() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);
        Git.init().setDirectory(repoRoot.toFile()).call();

        MainProject parentProject = new MainProject(repoRoot);

        TestLanguage fakeLang = new TestLanguage();
        parentProject.setAnalyzerLanguages(Set.of(fakeLang));

        Path sourceCache = fakeLang.getStoragePath(parentProject);
        Files.createDirectories(sourceCache.getParent());
        Files.writeString(sourceCache, "cache-data");

        Path worktreeStorageDir = parentProject.getWorktreeStoragePath();
        Path nextWorktreePath = worktreeStorageDir.resolve("wt-1");

        TestGitRepo fakeGitRepo = new TestGitRepo(repoRoot, nextWorktreePath);

        var result = GitWorktreeTab.setupNewGitWorktree(parentProject, fakeGitRepo, "feature/test", false, "");

        assertTrue(Files.isDirectory(result.worktreePath()), "Worktree directory should be created");

        Path relative = parentProject.getRoot().relativize(sourceCache);
        Path destCache = result.worktreePath().resolve(relative);

        assertFalse(Files.exists(destCache), "Analyzer cache must not be replicated to the new worktree");
        assertTrue(Files.exists(sourceCache), "Original analyzer cache should remain intact");
    }
}
