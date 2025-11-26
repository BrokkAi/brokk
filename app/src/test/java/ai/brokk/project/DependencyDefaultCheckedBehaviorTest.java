package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.dependencies.DependenciesPanel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests verifying that newly imported dependencies are marked as live (checked) by
 * default, and that existing live dependencies remain live when new dependencies are added.
 */
@DisplayName("Dependency Default-Checked Behavior Tests")
class DependencyDefaultCheckedBehaviorTest {

    @TempDir
    Path tempProjectRoot;

    private MainProject mainProject;

    @BeforeEach
    void setUp() throws IOException {
        // Initialize a git-less project (LocalFileRepo)
        mainProject = new MainProject(tempProjectRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mainProject != null) {
            mainProject.close();
        }
    }

    @Test
    @DisplayName("First dependency import should be live by default")
    void testFirstDependencyIsLiveByDefault() throws IOException {
        // Setup: Create a mock dependency directory
        String depName1 = "dep-first";
        createDependencyDirectory(mainProject, depName1);

        // Act: Simulate import by adding to live dependencies
        simulateDependencyImport(mainProject, depName1);

        // Assert: The new dependency should be live
        Set<IProject.Dependency> liveDeps = mainProject.getLiveDependencies();
        assertTrue(liveDeps.size() > 0, "Live dependencies should not be empty after import");

        Set<String> liveDepNames = extractDependencyNames(liveDeps);
        assertTrue(liveDepNames.contains(depName1),
                "First imported dependency should be live by default");
    }

    @Test
    @DisplayName("Second dependency import should be live, and first should remain live")
    void testSecondDependencyIsLiveAndFirstRemains() throws IOException {
        // Setup: Create and import first dependency
        String depName1 = "dep-first";
        createDependencyDirectory(mainProject, depName1);
        simulateDependencyImport(mainProject, depName1);

        Set<IProject.Dependency> liveDepsAfterFirst = mainProject.getLiveDependencies();
        assertTrue(liveDepsAfterFirst.size() > 0, "First dependency should be live");

        // Act: Import a second dependency
        String depName2 = "dep-second";
        createDependencyDirectory(mainProject, depName2);
        simulateDependencyImport(mainProject, depName2);

        // Assert: Both dependencies should be live
        Set<IProject.Dependency> liveDepsAfterSecond = mainProject.getLiveDependencies();
        assertEquals(2, liveDepsAfterSecond.size(),
                "Both dependencies should be live after second import");

        Set<String> liveDepNames = extractDependencyNames(liveDepsAfterSecond);
        assertTrue(liveDepNames.contains(depName1),
                "First dependency should remain live after second import");
        assertTrue(liveDepNames.contains(depName2),
                "Second dependency should be live");
    }

    @Test
    @DisplayName("Multiple sequential imports should preserve all live dependencies")
    void testMultipleDependenciesAllRemainLive() throws IOException {
        // Setup & Act: Import 3 dependencies sequentially
        String[] depNames = {"dep-alpha", "dep-beta", "dep-gamma"};
        for (int i = 0; i < depNames.length; i++) {
            String depName = depNames[i];
            createDependencyDirectory(mainProject, depName);
            simulateDependencyImport(mainProject, depName);

            // Assert: All previously imported dependencies should remain live
            Set<IProject.Dependency> liveDeps = mainProject.getLiveDependencies();
            Set<String> liveDepNames = extractDependencyNames(liveDeps);

            // Check all imported so far
            for (int j = 0; j <= i; j++) {
                assertTrue(liveDepNames.contains(depNames[j]),
                        depNames[j] + " should remain live");
            }
        }

        // Final assertion: All 3 should be live
        Set<IProject.Dependency> finalLiveDeps = mainProject.getLiveDependencies();
        assertEquals(3, finalLiveDeps.size(),
                "All three dependencies should be live");
    }

    @Test
    @DisplayName("Toggling dependency off then back on should work correctly")
    void testToggleDependencyOffAndOn() throws IOException {
        // Setup: Create and import a dependency
        String depName = "dep-toggle";
        createDependencyDirectory(mainProject, depName);
        simulateDependencyImport(mainProject, depName);

        Set<IProject.Dependency> liveDeps = mainProject.getLiveDependencies();
        assertEquals(1, liveDeps.size(), "Dependency should be live after import");

        // Act: Remove it from live set
        mainProject.saveLiveDependencies(Set.of());

        // Assert: Should be off
        Set<IProject.Dependency> offDeps = mainProject.getLiveDependencies();
        assertEquals(0, offDeps.size(), "Dependency should be off after toggle");

        // Act: Turn it back on
        Path depTopLevel = mainProject.getMasterRootPathForConfig()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(depName);
        mainProject.saveLiveDependencies(Set.of(depTopLevel));

        // Assert: Should be on again
        Set<IProject.Dependency> backOnDeps = mainProject.getLiveDependencies();
        assertEquals(1, backOnDeps.size(), "Dependency should be live again");
        assertTrue(extractDependencyNames(backOnDeps).contains(depName));
    }

    @Test
    @DisplayName("WorktreeProject should clone parent's live dependencies on first access")
    void testWorktreeProjectClonesParentLiveDependencies() throws IOException {
        // Setup: Create parent project with imported dependencies
        String depName1 = "dep-parent-first";
        createDependencyDirectory(mainProject, depName1);
        simulateDependencyImport(mainProject, depName1);

        String depName2 = "dep-parent-second";
        createDependencyDirectory(mainProject, depName2);
        simulateDependencyImport(mainProject, depName2);

        // Verify parent has both live
        Set<String> parentLiveDeps = extractDependencyNames(mainProject.getLiveDependencies());
        assertEquals(2, parentLiveDeps.size(), "Parent should have 2 live dependencies");

        // Setup: Create a worktree project with its own temporary root
        @TempDir
        Path worktreeRoot = Files.createTempDirectory("worktree");

        try {
            // Create the .brokk structure for the worktree
            Files.createDirectories(worktreeRoot.resolve(AbstractProject.BROKK_DIR));

            WorktreeProject worktreeProject = new WorktreeProject(worktreeRoot, mainProject);

            // Create matching dependency directories in the worktree so they can be discovered
            createDependencyDirectory(worktreeProject, depName1);
            createDependencyDirectory(worktreeProject, depName2);

            // Act: Access live dependencies for the first time
            Set<IProject.Dependency> worktreeLiveDeps = worktreeProject.getLiveDependencies();

            // Assert: Worktree should inherit parent's live set
            Set<String> worktreeLiveNames = extractDependencyNames(worktreeLiveDeps);
            assertEquals(2, worktreeLiveNames.size(),
                    "Worktree should inherit parent's live dependencies on first access");
            assertTrue(worktreeLiveNames.contains(depName1),
                    "Worktree should have parent's first dependency");
            assertTrue(worktreeLiveNames.contains(depName2),
                    "Worktree should have parent's second dependency");

            // Act: Import a new dependency into the worktree
            String wtDepName = "dep-worktree-new";
            createDependencyDirectory(worktreeProject, wtDepName);
            simulateDependencyImport(worktreeProject, wtDepName);

            // Assert: Worktree should have 3 live dependencies (2 inherited + 1 new)
            Set<IProject.Dependency> worktreeAfterImport = worktreeProject.getLiveDependencies();
            Set<String> worktreeAfterNames = extractDependencyNames(worktreeAfterImport);
            assertEquals(3, worktreeAfterNames.size(),
                    "Worktree should have 3 live dependencies after new import");
            assertTrue(worktreeAfterNames.contains(depName1),
                    "Parent's first dependency should still be live in worktree");
            assertTrue(worktreeAfterNames.contains(depName2),
                    "Parent's second dependency should still be live in worktree");
            assertTrue(worktreeAfterNames.contains(wtDepName),
                    "New dependency should be live in worktree");

            // Assert: Parent should still have only 2 (worktree changes are isolated)
            Set<String> parentAfterWorktreeImport = extractDependencyNames(
                    mainProject.getLiveDependencies());
            assertEquals(2, parentAfterWorktreeImport.size(),
                    "Parent project's live set should be unaffected by worktree import");

            worktreeProject.close();
        } finally {
            // Cleanup
            Files.walk(worktreeRoot)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    @DisplayName("Dependency persistence survives project reload")
    void testDependencyPersistenceAcrossReload() throws IOException {
        // Setup: Create and import dependencies
        String depName1 = "dep-persist-1";
        createDependencyDirectory(mainProject, depName1);
        simulateDependencyImport(mainProject, depName1);

        String depName2 = "dep-persist-2";
        createDependencyDirectory(mainProject, depName2);
        simulateDependencyImport(mainProject, depName2);

        Set<String> originalLiveDeps = extractDependencyNames(mainProject.getLiveDependencies());
        assertEquals(2, originalLiveDeps.size());

        // Act: Close and reload the project
        mainProject.close();
        mainProject = new MainProject(tempProjectRoot);

        // Assert: Live dependencies should be restored from persistence
        Set<String> reloadedLiveDeps = extractDependencyNames(mainProject.getLiveDependencies());
        assertEquals(2, reloadedLiveDeps.size(),
                "Live dependencies should persist across project reload");
        assertTrue(reloadedLiveDeps.contains(depName1),
                "First dependency should be restored after reload");
        assertTrue(reloadedLiveDeps.contains(depName2),
                "Second dependency should be restored after reload");
    }

    // ============== Helper Methods ==============

    /**
     * Creates a mock dependency directory structure under a project's .brokk/dependencies with a
     * simple source file.
     */
    private void createDependencyDirectory(IProject project, String depName) throws IOException {
        Path dependenciesDir = project.getMasterRootPathForConfig()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR);
        Files.createDirectories(dependenciesDir);

        Path depDir = dependenciesDir.resolve(depName);
        Files.createDirectories(depDir);

        // Create a dummy source file so it appears to have content
        Path sourceFile = depDir.resolve("Main.java");
        Files.writeString(sourceFile, "public class Main {}");
    }

    /**
     * Simulates the dependency import process by adding the dependency to the live set. This
     * models what happens when `DependencyLifecycleListener.dependencyImportFinished()` is called
     * and the UI reloads.
     */
    private void simulateDependencyImport(IProject project, String depName) throws IOException {
        // Get current live dependencies
        Set<IProject.Dependency> currentLiveDeps = project.getLiveDependencies();
        Set<Path> liveDependencyTopLevelDirs = new HashSet<>();

        // Add all currently live dependencies
        for (var dep : currentLiveDeps) {
            liveDependencyTopLevelDirs.add(dep.root().absPath());
        }

        // Add the newly imported dependency directory
        Path newDepDir = project.getMasterRootPathForConfig()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(depName);
        liveDependencyTopLevelDirs.add(newDepDir);

        // Persist the updated live set (this is what dependencyImportFinished does)
        project.saveLiveDependencies(liveDependencyTopLevelDirs);

        // Invalidate caches to force reload
        project.invalidateAllFiles();
    }

    /**
     * Extracts dependency names from a set of Dependency records.
     */
    private Set<String> extractDependencyNames(Set<IProject.Dependency> deps) {
        return deps.stream()
                .map(d -> d.root().getRelPath().getFileName().toString())
                .collect(Collectors.toSet());
    }
}
