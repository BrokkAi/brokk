package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.DependencyUpdater;
import ai.brokk.util.FileUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for local-directory dependency auto-update mechanics.
 *
 * <p>These tests exercise
 * {@link DependencyUpdater#updateLocalPathDependencyOnDisk(ai.brokk.IProject, ProjectFile, DependencyUpdater.DependencyMetadata)}.
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
    void updateLocalPathDependencyOnDisk_shouldFailWhenSourceInsideDependencies() throws Exception {
        var project = new MainProject(tempRoot);
        project.setAnalyzerLanguages(Set.of(Languages.JAVA));

        Path badSource = dependenciesRoot.resolve("nested-source");
        Files.createDirectories(badSource);
        Files.writeString(badSource.resolve("Foo.java"), "class Foo {}");

        Path depDir = dependenciesRoot.resolve("local-dep");
        Files.createDirectories(depDir);

        DependencyUpdater.writeLocalPathDependencyMetadata(
                depDir, badSource.toAbsolutePath().normalize());

        var metadataOpt = DependencyUpdater.readDependencyMetadata(depDir);
        assertTrue(metadataOpt.isPresent(), "Expected metadata for local path dependency");

        var depProjectFile = new ProjectFile(
                project.getMasterRootPathForConfig(),
                project.getMasterRootPathForConfig().relativize(depDir));

        assertThrows(
                java.io.IOException.class,
                () -> DependencyUpdater.updateLocalPathDependencyOnDisk(project, depProjectFile, metadataOpt.get()),
                "Expected update to fail when source directory is inside dependencies root");
    }
}
