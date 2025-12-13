package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IWatchService.EventBatch;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for FileWatcherHelper utility class.
 */
class FileWatcherHelperTest {

    private Path projectRoot;
    private Path gitRepoRoot;
    private FileWatcherHelper helper;

    @BeforeEach
    void setUp() {
        // Use toAbsolutePath() to ensure paths are absolute on all platforms (Windows needs drive letter)
        projectRoot = Path.of("/test/project").toAbsolutePath();
        gitRepoRoot = Path.of("/test/project").toAbsolutePath();
        helper = new FileWatcherHelper(gitRepoRoot);
    }

    @Test
    void testIsGitMetadataChanged_WithGitFiles() {
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(projectRoot, Path.of(".git/HEAD")));

        assertTrue(helper.isGitMetadataChanged(batch), "Should detect .git directory changes");
    }

    @Test
    void testIsGitMetadataChanged_WithoutGitFiles() {
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(projectRoot, Path.of("src/Main.java")));

        assertFalse(helper.isGitMetadataChanged(batch), "Should not detect non-git changes as git metadata");
    }

    @Test
    void testIsGitMetadataChanged_NoGitRepo() {
        FileWatcherHelper helperNoGit = new FileWatcherHelper(null);
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(projectRoot, Path.of(".git/HEAD")));

        assertFalse(helperNoGit.isGitMetadataChanged(batch), "Should return false when no git repo configured");
    }

    @Test
    void testGetChangedTrackedFiles() {
        EventBatch batch = new EventBatch();
        ProjectFile file1 = new ProjectFile(projectRoot, Path.of("src/Main.java"));
        ProjectFile file2 = new ProjectFile(projectRoot, Path.of("src/Test.java"));
        ProjectFile file3 = new ProjectFile(projectRoot, Path.of("build/output.class"));

        batch.files.add(file1);
        batch.files.add(file2);
        batch.files.add(file3);

        Set<ProjectFile> trackedFiles = Set.of(file1, file2); // file3 not tracked

        Set<ProjectFile> changed = helper.getChangedTrackedFiles(batch, trackedFiles);

        assertEquals(2, changed.size(), "Should return only tracked files");
        assertTrue(changed.contains(file1));
        assertTrue(changed.contains(file2));
        assertFalse(changed.contains(file3), "Should not include untracked files");
    }

    @Test
    void testGetFilesWithExtensions() {
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(projectRoot, Path.of("src/Main.java")));
        batch.files.add(new ProjectFile(projectRoot, Path.of("src/Test.java")));
        batch.files.add(new ProjectFile(projectRoot, Path.of("README.md")));
        batch.files.add(new ProjectFile(projectRoot, Path.of("src/utils.ts")));

        Set<ProjectFile> javaFiles = helper.getFilesWithExtensions(batch, Set.of("java"));
        assertEquals(2, javaFiles.size(), "Should return only .java files");

        Set<ProjectFile> multipleExts = helper.getFilesWithExtensions(batch, Set.of("java", "ts"));
        assertEquals(3, multipleExts.size(), "Should return .java and .ts files");
    }

    @Test
    void testGetFilesInDirectory() {
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(projectRoot, Path.of("src/main/Main.java")));
        batch.files.add(new ProjectFile(projectRoot, Path.of("src/test/Test.java")));
        batch.files.add(new ProjectFile(projectRoot, Path.of("docs/README.md")));

        Set<ProjectFile> srcFiles = helper.getFilesInDirectory(batch, Path.of("src"));
        assertEquals(2, srcFiles.size(), "Should return files in src/ directory");

        Set<ProjectFile> docsFiles = helper.getFilesInDirectory(batch, Path.of("docs"));
        assertEquals(1, docsFiles.size(), "Should return files in docs/ directory");
    }

    @Test
    void testContainsAnyFile() {
        EventBatch batch = new EventBatch();
        ProjectFile file1 = new ProjectFile(projectRoot, Path.of("src/Main.java"));
        ProjectFile file2 = new ProjectFile(projectRoot, Path.of("src/Test.java"));
        ProjectFile file3 = new ProjectFile(projectRoot, Path.of("src/Other.java"));

        batch.files.add(file1);
        batch.files.add(file2);

        assertTrue(helper.containsAnyFile(batch, Set.of(file1)), "Should find file1");
        assertTrue(helper.containsAnyFile(batch, Set.of(file1, file2)), "Should find either file");
        assertFalse(helper.containsAnyFile(batch, Set.of(file3)), "Should not find file3");
    }

    @Test
    void testIsSignificantChange_WithFiles() {
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(projectRoot, Path.of("src/Main.java")));

        assertTrue(helper.isSignificantChange(batch), "Batch with files is significant");
    }

    @Test
    void testIsSignificantChange_WithOverflow() {
        EventBatch batch = new EventBatch();
        batch.isOverflowed = true;

        assertTrue(helper.isSignificantChange(batch), "Overflow is significant");
    }

    @Test
    void testIsSignificantChange_Empty() {
        EventBatch batch = new EventBatch();

        assertFalse(helper.isSignificantChange(batch), "Empty batch is not significant");
    }

    @Test
    void testClassifyChanges_GitAndTracked() {
        EventBatch batch = new EventBatch();
        ProjectFile gitFile = new ProjectFile(projectRoot, Path.of(".git/HEAD"));
        ProjectFile trackedFile = new ProjectFile(projectRoot, Path.of("src/Main.java"));

        batch.files.add(gitFile);
        batch.files.add(trackedFile);

        Set<ProjectFile> trackedFiles = Set.of(trackedFile);

        var classification = helper.classifyChanges(batch, trackedFiles);

        assertTrue(classification.gitMetadataChanged, "Should detect git changes");
        assertTrue(classification.trackedFilesChanged, "Should detect tracked file changes");
        assertEquals(1, classification.changedTrackedFiles.size(), "Should have 1 changed tracked file");
        assertTrue(classification.changedTrackedFiles.contains(trackedFile));
    }

    @Test
    void testClassifyChanges_OnlyGit() {
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(projectRoot, Path.of(".git/refs/heads/main")));

        var classification = helper.classifyChanges(batch, Set.of());

        assertTrue(classification.gitMetadataChanged, "Should detect git changes");
        assertFalse(classification.trackedFilesChanged, "Should not detect tracked changes");
        assertTrue(classification.changedTrackedFiles.isEmpty());
    }

    @Test
    void testClassifyChanges_OnlyTracked() {
        EventBatch batch = new EventBatch();
        ProjectFile file = new ProjectFile(projectRoot, Path.of("src/Main.java"));
        batch.files.add(file);

        Set<ProjectFile> trackedFiles = Set.of(file);
        var classification = helper.classifyChanges(batch, trackedFiles);

        assertFalse(classification.gitMetadataChanged, "Should not detect git changes");
        assertTrue(classification.trackedFilesChanged, "Should detect tracked changes");
        assertEquals(1, classification.changedTrackedFiles.size());
    }

    /**
     * Tests that git metadata changes are detected in worktree scenarios where
     * gitRepoRoot is different from projectRoot. In this case, git files come
     * with gitRepoRoot as their base path but still have .git-prefixed relative paths.
     */
    @Test
    void testIsGitMetadataChanged_Worktree() {
        // In worktree scenario, gitRepoRoot is different from projectRoot
        Path mainRepoRoot = Path.of("/main/repo").toAbsolutePath();
        FileWatcherHelper worktreeHelper = new FileWatcherHelper(mainRepoRoot);

        // Git events from worktrees will have gitRepoRoot as base with .git prefix
        // This simulates what NativeProjectWatchService produces after the fix
        EventBatch batch = new EventBatch();
        batch.files.add(new ProjectFile(mainRepoRoot, Path.of(".git/HEAD")));

        assertTrue(
                worktreeHelper.isGitMetadataChanged(batch),
                "Should detect .git changes even when projectRoot != gitRepoRoot");
    }

    /**
     * Tests that regular files are not falsely detected as git metadata.
     */
    @Test
    void testIsGitMetadataChanged_NotGitignore() {
        EventBatch batch = new EventBatch();
        // .gitignore is NOT a git metadata file (it's a regular tracked file)
        batch.files.add(new ProjectFile(projectRoot, Path.of(".gitignore")));

        assertFalse(helper.isGitMetadataChanged(batch), "Should not detect .gitignore as git metadata");
    }

    /**
     * Tests that isGitMetadataChanged correctly detects .git prefix regardless of the
     * ProjectFile's base path. This is critical for worktree support where git events
     * may have different base paths than regular file events.
     */
    @Test
    void testFileWatcherHelperWithDifferentBases() {
        // Simulate three different scenarios:
        // 1. Standard repo: both projectRoot and gitRepoRoot are the same
        // 2. Worktree with events from main repo's .git
        // 3. Mixed batch with different bases

        Path worktreeRoot = Path.of("/worktree/project").toAbsolutePath();
        Path mainRepoRoot = Path.of("/main/repo").toAbsolutePath();
        Path externalGitDir = Path.of("/external/git/dir").toAbsolutePath();

        // Helper configured with mainRepoRoot as gitRepoRoot (worktree scenario)
        FileWatcherHelper worktreeHelper = new FileWatcherHelper(mainRepoRoot);

        // Test 1: Git event with worktreeRoot as base but .git prefix in relPath
        EventBatch batch1 = new EventBatch();
        batch1.files.add(new ProjectFile(worktreeRoot, Path.of(".git/HEAD")));
        assertTrue(
                worktreeHelper.isGitMetadataChanged(batch1),
                "Should detect .git prefix regardless of base being worktreeRoot");

        // Test 2: Git event with mainRepoRoot as base
        EventBatch batch2 = new EventBatch();
        batch2.files.add(new ProjectFile(mainRepoRoot, Path.of(".git/refs/heads/main")));
        assertTrue(worktreeHelper.isGitMetadataChanged(batch2), "Should detect .git prefix with mainRepoRoot base");

        // Test 3: Git event with arbitrary external base
        EventBatch batch3 = new EventBatch();
        batch3.files.add(new ProjectFile(externalGitDir, Path.of(".git/objects/pack/pack-123.idx")));
        assertTrue(worktreeHelper.isGitMetadataChanged(batch3), "Should detect .git prefix with any base path");

        // Test 4: Mixed batch - one git, one regular file with different bases
        EventBatch batch4 = new EventBatch();
        batch4.files.add(new ProjectFile(mainRepoRoot, Path.of(".git/index")));
        batch4.files.add(new ProjectFile(worktreeRoot, Path.of("src/Main.java")));
        assertTrue(worktreeHelper.isGitMetadataChanged(batch4), "Should detect git metadata in mixed batch");

        // Test 5: Non-git files only, different bases
        EventBatch batch5 = new EventBatch();
        batch5.files.add(new ProjectFile(worktreeRoot, Path.of("src/Main.java")));
        batch5.files.add(new ProjectFile(mainRepoRoot, Path.of("build.gradle")));
        assertFalse(worktreeHelper.isGitMetadataChanged(batch5), "Should not detect git metadata when none present");

        // Test 6: File in a directory named .github (not .git)
        EventBatch batch6 = new EventBatch();
        batch6.files.add(new ProjectFile(worktreeRoot, Path.of(".github/workflows/ci.yml")));
        assertFalse(worktreeHelper.isGitMetadataChanged(batch6), "Should not confuse .github with .git");
    }
}
