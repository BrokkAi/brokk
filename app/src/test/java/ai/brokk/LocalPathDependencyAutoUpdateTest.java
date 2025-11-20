package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.FileUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for local-directory dependency auto-update mechanics.
 *
 * <p>These tests exercise
 * {@link AbstractProject#updateLocalPathDependencyOnDisk(ProjectFile, AbstractProject.DependencyMetadata)}.
 */
class LocalPathDependencyAutoUpdateTest {

    private Path tempRoot;
    private Path dependenciesRoot;
    private Path sourceDir;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("brokk-local-dep-update-");
        dependenciesRoot = tempRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
        Files.createDirectories(dependenciesRoot);

        sourceDir = Files.createDirectory(tempRoot.resolve("external-lib"));
        Files.writeString(sourceDir.resolve("Foo.java"), "class FooV1 {}");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempRoot != null && Files.exists(tempRoot)) {
            FileUtil.deleteRecursively(tempRoot);
        }
    }

    @Test
    void updateLocalPathDependencyOnDisk_shouldReplaceFilesAndReportChanges() throws Exception {
        var project = new MainProject(tempRoot);
        project.setAnalyzerLanguages(Set.of(Languages.JAVA));

        Path depDir = dependenciesRoot.resolve("local-dep");
        Files.createDirectories(depDir);
        // Seed the dependency directory with initial contents mirroring the source directory.
        Files.copy(sourceDir.resolve("Foo.java"), depDir.resolve("Foo.java"));

        // Record dependency metadata pointing at our local source directory.
        AbstractProject.writeLocalPathDependencyMetadata(
                depDir, sourceDir.toAbsolutePath().normalize());

        var metadataOpt = AbstractProject.readDependencyMetadata(depDir);
        assertTrue(metadataOpt.isPresent(), "Expected metadata to be present for local path dependency");

        // Mutate the source directory: add a new file.
        Files.writeString(sourceDir.resolve("Bar.java"), "class Bar {}");

        // Build the ProjectFile for the on-disk dependency root.
        var depProjectFile = new ProjectFile(
                project.getMasterRootPathForConfig(),
                project.getMasterRootPathForConfig().relativize(depDir));

        Set<ProjectFile> changedFiles = project.updateLocalPathDependencyOnDisk(depProjectFile, metadataOpt.get());

        assertFalse(changedFiles.isEmpty(), "Changed files set should not be empty after source update");

        var relPaths = changedFiles.stream()
                .map(ProjectFile::getRelPath)
                .map(Path::toString)
                .collect(Collectors.toSet());

        String expectedNewFileRel = project.getMasterRootPathForConfig()
                .relativize(depDir.resolve("Bar.java"))
                .toString();

        assertTrue(relPaths.contains(expectedNewFileRel), "Changed files should contain newly added Bar.java");

        assertTrue(
                Files.exists(depDir.resolve("Bar.java")),
                "Local dependency should contain updated contents from source directory");
    }

    @Test
    void updateLocalPathDependencyOnDisk_shouldFailWhenSourceInsideDependencies() throws Exception {
        var project = new MainProject(tempRoot);
        project.setAnalyzerLanguages(Set.of(Languages.JAVA));

        Path badSource = dependenciesRoot.resolve("nested-source");
        Files.createDirectories(badSource);
        Files.writeString(badSource.resolve("Foo.java"), "class Foo {}");

        Path depDir = dependenciesRoot.resolve("local-dep");
        Files.createDirectories(depDir);

        AbstractProject.writeLocalPathDependencyMetadata(
                depDir, badSource.toAbsolutePath().normalize());

        var metadataOpt = AbstractProject.readDependencyMetadata(depDir);
        assertTrue(metadataOpt.isPresent(), "Expected metadata for local path dependency");

        var depProjectFile = new ProjectFile(
                project.getMasterRootPathForConfig(),
                project.getMasterRootPathForConfig().relativize(depDir));

        assertThrows(
                java.io.IOException.class,
                () -> project.updateLocalPathDependencyOnDisk(depProjectFile, metadataOpt.get()),
                "Expected update to fail when source directory is inside dependencies root");
    }
}
