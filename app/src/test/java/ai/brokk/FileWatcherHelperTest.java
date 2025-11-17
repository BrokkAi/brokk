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
        helper = new FileWatcherHelper(projectRoot, gitRepoRoot);
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
        FileWatcherHelper helperNoGit = new FileWatcherHelper(projectRoot, null);
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
}
