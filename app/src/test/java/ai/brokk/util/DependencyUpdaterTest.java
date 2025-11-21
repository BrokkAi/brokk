package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IProject;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for DependencyUpdater utility methods.
 */
public class DependencyUpdaterTest {

    @TempDir
    Path tempDir;

    // ========== gitDependencyNeedsUpdate edge cases ==========

    @Test
    public void gitDependencyNeedsUpdate_nonGitType_returnsFalse() {
        var metadata = DependencyUpdater.DependencyMetadata.forLocalPath(Path.of("/some/path"));
        assertFalse(DependencyUpdater.gitDependencyNeedsUpdate(metadata));
    }

    @Test
    public void gitDependencyNeedsUpdate_missingRepoUrl_returnsTrue() {
        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.GITHUB,
                null,
                null, // missing repoUrl
                "main",
                "abc123",
                System.currentTimeMillis());
        assertTrue(DependencyUpdater.gitDependencyNeedsUpdate(metadata));
    }

    @Test
    public void gitDependencyNeedsUpdate_missingRef_returnsTrue() {
        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.GITHUB,
                null,
                "https://github.com/owner/repo",
                null, // missing ref
                "abc123",
                System.currentTimeMillis());
        assertTrue(DependencyUpdater.gitDependencyNeedsUpdate(metadata));
    }

    @Test
    public void gitDependencyNeedsUpdate_missingCommitHash_returnsTrue() {
        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.GITHUB,
                null,
                "https://github.com/owner/repo",
                "main",
                null, // missing commitHash
                System.currentTimeMillis());
        assertTrue(DependencyUpdater.gitDependencyNeedsUpdate(metadata));
    }

    // ========== localDependencyNeedsUpdate edge cases ==========

    @Test
    public void localDependencyNeedsUpdate_nonLocalType_returnsFalse() {
        var metadata = DependencyUpdater.DependencyMetadata.forGit("https://github.com/owner/repo", "main", "abc123");
        assertFalse(DependencyUpdater.localDependencyNeedsUpdate(metadata));
    }

    @Test
    public void localDependencyNeedsUpdate_nullSourcePath_returnsFalse() {
        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                null, // null sourcePath
                null,
                null,
                null,
                System.currentTimeMillis());
        assertFalse(DependencyUpdater.localDependencyNeedsUpdate(metadata));
    }

    @Test
    public void localDependencyNeedsUpdate_nonExistentDirectory_returnsFalse() {
        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                "/nonexistent/path/that/does/not/exist",
                null,
                null,
                null,
                System.currentTimeMillis());
        assertFalse(DependencyUpdater.localDependencyNeedsUpdate(metadata));
    }

    @Test
    public void localDependencyNeedsUpdate_emptyDirectory_returnsFalse() throws IOException {
        // Create empty directory
        Path emptyDir = Files.createDirectory(tempDir.resolve("empty"));

        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                emptyDir.toString(),
                null,
                null,
                null,
                0L); // Old timestamp
        // Empty directory has no files, so newest timestamp is 0, which is not > 0
        assertFalse(DependencyUpdater.localDependencyNeedsUpdate(metadata));
    }

    @Test
    public void localDependencyNeedsUpdate_newerFiles_returnsTrue() throws IOException {
        // Create directory with a file
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(sourceDir.resolve("test.java"), "content");

        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                sourceDir.toString(),
                null,
                null,
                null,
                0L); // Very old timestamp
        assertTrue(DependencyUpdater.localDependencyNeedsUpdate(metadata));
    }

    @Test
    public void localDependencyNeedsUpdate_olderFiles_returnsFalse() throws IOException {
        // Create directory with a file
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(sourceDir.resolve("test.java"), "content");

        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                sourceDir.toString(),
                null,
                null,
                null,
                System.currentTimeMillis() + 100000L); // Future timestamp
        assertFalse(DependencyUpdater.localDependencyNeedsUpdate(metadata));
    }

    // ========== updateLocalPathDependencyOnDisk path validation ==========

    @Test
    public void updateLocalPathDependencyOnDisk_sourceEqualsTarget_throws() throws IOException {
        Path depDir = Files.createDirectory(tempDir.resolve("dep"));
        var masterRoot = tempDir;
        var depRoot = new ProjectFile(masterRoot, masterRoot.relativize(depDir));

        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                depDir.toString(), // Source equals target
                null,
                null,
                null,
                System.currentTimeMillis());

        var project = createMinimalProject(masterRoot);

        assertThrows(IOException.class, () -> {
            DependencyUpdater.updateLocalPathDependencyOnDisk(project, depRoot, metadata);
        });
    }

    @Test
    public void updateLocalPathDependencyOnDisk_sourceInsideDependenciesRoot_throws() throws IOException {
        Path depsRoot = Files.createDirectories(tempDir.resolve(".brokk").resolve("dependencies"));
        Path anotherDep = Files.createDirectory(depsRoot.resolve("another-dep"));
        Files.writeString(anotherDep.resolve("test.java"), "content");

        Path depDir = Files.createDirectory(depsRoot.resolve("my-dep"));
        var masterRoot = tempDir;
        var depRoot = new ProjectFile(masterRoot, masterRoot.relativize(depDir));

        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                anotherDep.toString(), // Source inside dependencies root
                null,
                null,
                null,
                System.currentTimeMillis());

        var project = createMinimalProject(masterRoot);

        assertThrows(IOException.class, () -> {
            DependencyUpdater.updateLocalPathDependencyOnDisk(project, depRoot, metadata);
        });
    }

    @Test
    public void updateLocalPathDependencyOnDisk_nonExistentSource_throws() throws IOException {
        Path depsRoot = Files.createDirectories(tempDir.resolve(".brokk").resolve("dependencies"));
        Path depDir = Files.createDirectory(depsRoot.resolve("my-dep"));
        var masterRoot = tempDir;
        var depRoot = new ProjectFile(masterRoot, masterRoot.relativize(depDir));

        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                "/nonexistent/source/path",
                null,
                null,
                null,
                System.currentTimeMillis());

        var project = createMinimalProject(masterRoot);

        assertThrows(IOException.class, () -> {
            DependencyUpdater.updateLocalPathDependencyOnDisk(project, depRoot, metadata);
        });
    }

    @Test
    public void updateLocalPathDependencyOnDisk_wrongMetadataType_throws() throws IOException {
        Path depsRoot = Files.createDirectories(tempDir.resolve(".brokk").resolve("dependencies"));
        Path depDir = Files.createDirectory(depsRoot.resolve("my-dep"));
        var masterRoot = tempDir;
        var depRoot = new ProjectFile(masterRoot, masterRoot.relativize(depDir));

        var metadata = DependencyUpdater.DependencyMetadata.forGit("https://github.com/owner/repo", "main", "abc123");

        var project = createMinimalProject(masterRoot);

        assertThrows(IllegalArgumentException.class, () -> {
            DependencyUpdater.updateLocalPathDependencyOnDisk(project, depRoot, metadata);
        });
    }

    // ========== autoUpdateDependenciesOnce live vs non-live ==========

    @Test
    public void autoUpdateDependenciesOnce_skipsDisabledDependencies() throws IOException {
        // Setup: create a dependency directory with metadata
        Path depsRoot = Files.createDirectories(tempDir.resolve(".brokk").resolve("dependencies"));
        Path depDir = Files.createDirectory(depsRoot.resolve("disabled-dep"));

        // Create source directory with newer file
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(sourceDir.resolve("test.java"), "content");

        // Write metadata
        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH,
                sourceDir.toString(),
                null,
                null,
                null,
                0L); // Old timestamp, would normally trigger update
        writeMetadataToFile(depDir, metadata);

        var masterRoot = tempDir;
        var depRoot = new ProjectFile(masterRoot, masterRoot.relativize(depDir));

        // Create project with empty live dependencies (dep is disabled)
        var project = new TestProject(masterRoot, Set.of(depRoot), Set.of());

        var result = DependencyUpdater.autoUpdateDependenciesOnce(project, true, false);

        // Should not update because dependency is not in live set
        assertEquals(0, result.updatedDependencies());
        assertTrue(result.changedFiles().isEmpty());
    }

    @Test
    public void autoUpdateDependenciesOnce_updatesEnabledDependencies() throws IOException {
        // Setup: create a dependency directory with metadata
        Path depsRoot = Files.createDirectories(tempDir.resolve(".brokk").resolve("dependencies"));
        Path depDir = Files.createDirectory(depsRoot.resolve("enabled-dep"));

        // Create source directory with newer file
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(sourceDir.resolve("Test.java"), "content");

        // Write metadata with old timestamp
        var metadata = new DependencyUpdater.DependencyMetadata(
                DependencyUpdater.DependencySourceType.LOCAL_PATH, sourceDir.toString(), null, null, null, 0L);
        writeMetadataToFile(depDir, metadata);

        var masterRoot = tempDir;
        var depRoot = new ProjectFile(masterRoot, masterRoot.relativize(depDir));

        // Create project with this dependency in live set
        var dep = new IProject.Dependency(depRoot, Languages.JAVA);
        var project = new TestProject(masterRoot, Set.of(depRoot), Set.of(dep));

        var result = DependencyUpdater.autoUpdateDependenciesOnce(project, true, false);

        // Should update because dependency is enabled
        assertEquals(1, result.updatedDependencies());
        assertFalse(result.changedFiles().isEmpty());
    }

    // ========== Helper methods ==========

    private IProject createMinimalProject(Path root) {
        return new TestProject(root, Set.of(), Set.of());
    }

    private static void writeMetadataToFile(Path depRoot, DependencyUpdater.DependencyMetadata metadata) {
        var metadataPath = depRoot.resolve(DependencyUpdater.DEPENDENCY_METADATA_FILE);
        try {
            var objectMapper = new ObjectMapper();
            Files.writeString(metadataPath, objectMapper.writeValueAsString(metadata));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Minimal IProject implementation for testing.
     */
    private static class TestProject implements IProject {
        private final Path root;
        private final Set<ProjectFile> allDeps;
        private final Set<Dependency> liveDeps;

        TestProject(Path root, Set<ProjectFile> allDeps, Set<Dependency> liveDeps) {
            this.root = root;
            this.allDeps = allDeps;
            this.liveDeps = liveDeps;
        }

        @Override
        public Path getMasterRootPathForConfig() {
            return root;
        }

        @Override
        public Set<ProjectFile> getAllOnDiskDependencies() {
            return allDeps;
        }

        @Override
        public Set<Dependency> getLiveDependencies() {
            return liveDeps;
        }

        @Override
        public Set<Language> getAnalyzerLanguages() {
            return Set.of(Languages.JAVA);
        }

        // Remaining IProject methods throw UnsupportedOperationException
        @Override
        public Path getRoot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getAutoUpdateLocalDependencies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getAutoUpdateGitDependencies() {
            throw new UnsupportedOperationException();
        }
    }
}
