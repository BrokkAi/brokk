package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the "checked" (Live) behavior of dependencies as described in the DependenciesPanel
 * behavior doc: "Checked" means the dependency is included in Brokk's analysis context, persisted
 * in workspace properties, and affects exclusion of non-live dependencies.
 *
 * Precise invariant under test:
 *  - As soon as a dependency is added, it must be marked as "checked" (active) immediately when the
 *    liveDependencies property is unset.
 *  - Subsequent additions of other dependencies must not change the checked status of the already-added
 *    dependency that was previously checked.
 *
 * Sequence reproduced:
 *  1) Add dependency A -> assert A is checked.
 *  2) Add dependency B -> assert A remains checked (B's state is determined by the explicit selection rules).
 *
 * Target methods:
 *  - MainProject.getLiveDependencies()
 *  - MainProject.saveLiveDependencies(Set<Path>)
 *
 * The tests use a temporary filesystem layout under a temp project root and do not require a git repo.
 */
public class MainProjectCheckedBehaviorTest {

    private Path tempRoot;
    private MainProject project;

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
        if (tempRoot != null) {
            try {
                // Best-effort cleanup
                deleteRecursively(tempRoot);
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    void shouldIncludeAllDependenciesByDefaultWhenUnset() throws Exception {
        // Arrange: create a temp project with two dependencies; no workspace property set
        setupTempProject();
        Path depA = createDependency("depA", "A.java");
        Path depB = createDependency("depB", "B.js");

        project = new MainProject(tempRoot);

        // Act
        Set<String> liveDepNames = project.getLiveDependencies().stream()
                .map(dep -> extractDepName(dep.root()))
                .collect(Collectors.toSet());

        // Assert: when property is unset, all on-disk dependencies are treated as "checked" (live)
        assertEquals(Set.of("depA", "depB"), liveDepNames, "All dependencies should be live by default when unset");
    }

    @Test
    void shouldPersistAndReturnSelectedLiveDependenciesAfterSave() throws Exception {
        // Arrange
        setupTempProject();
        Path depA = createDependency("depA", "A.java");
        Path depB = createDependency("depB", "B.js");
        project = new MainProject(tempRoot);

        // Act: save only depA as live
        project.saveLiveDependencies(Set.of(depA));

        // Assert 1: getLiveDependencies now returns only depA
        Set<String> liveDepNames = project.getLiveDependencies().stream()
                .map(dep -> extractDepName(dep.root()))
                .collect(Collectors.toSet());
        assertEquals(Set.of("depA"), liveDepNames, "Only selected dependency should be live after save");

        // Assert 2: excluded directories should include the non-live depB under .brokk/dependencies
        String expectedExcludedDepB = Path.of(".brokk", "dependencies", "depB").toString();
        assertTrue(
                project.getExcludedDirectories().contains(expectedExcludedDepB),
                "Non-live dependency should be in excluded directories: " + expectedExcludedDepB);
    }

    @Test
    void shouldReturnEmptySetWhenLiveDependenciesIsBlank() throws Exception {
        // Arrange: pre-create workspace.properties with liveDependencies=""
        setupTempProject();
        createDependency("depA", "A.java");
        createDependency("depB", "B.js");
        writeWorkspacePropsWithBlankLiveDeps(tempRoot);

        project = new MainProject(tempRoot);

        // Act
        Set<String> liveDepNames = project.getLiveDependencies().stream()
                .map(dep -> extractDepName(dep.root()))
                .collect(Collectors.toSet());

        // Assert: explicitly blank property means no live dependencies
        assertTrue(liveDepNames.isEmpty(), "Blank liveDependencies property should result in no live dependencies");
    }

    @Test
    void shouldMarkDependencyCheckedImmediatelyWhenAdded() throws Exception {
        // Arrange
        // UI contract: "As soon as a dependency is added it must be marked as checked (active) immediately."
        // Backend behavior that drives UI: when liveDependencies is UNSET, all on-disk deps are considered live.
        setupTempProject();
        project = new MainProject(tempRoot);

        // Act: add dependency A AFTER the project is constructed
        Path depA = createDependency("depA", "A.java");

        // Assert: A is immediately treated as checked (live) when liveDependencies property is unset
        var liveDepNamesAfterA = project.getLiveDependencies().stream()
                .map(dep -> extractDepName(dep.root()))
                .collect(Collectors.toSet());
        assertTrue(
                liveDepNamesAfterA.contains("depA"),
                "Newly added dependency 'depA' should be live (checked) immediately when property is unset");
    }

    @Test
    void shouldNotUncheckPreviouslyCheckedWhenAddingNewDependencies() throws Exception {
        // Arrange
        // UI contract: "Subsequent additions must not change the checked status of previously-added dependencies."
        // Backend behavior that drives UI: once an explicit selection is saved, only the saved set is live.
        setupTempProject();
        project = new MainProject(tempRoot);

        Path depA = createDependency("depA", "A.java");

        // Persist that only depA is live
        project.saveLiveDependencies(Set.of(depA));

        // Sanity assertion: only depA is live now
        var liveAfterPersist = project.getLiveDependencies().stream()
                .map(dep -> extractDepName(dep.root()))
                .collect(Collectors.toSet());
        assertEquals(Set.of("depA"), liveAfterPersist, "Only 'depA' should be live after persisting selection");

        // Act: add a new dependency B after the selection has been persisted
        Path depB = createDependency("depB", "B.js");

        // Assert: depA remains live (checked), adding depB does not unset depA
        var liveAfterAddingB = project.getLiveDependencies().stream()
                .map(dep -> extractDepName(dep.root()))
                .collect(Collectors.toSet());

        assertTrue(liveAfterAddingB.contains("depA"), "Previously checked 'depA' must remain live after adding 'depB'");
        assertFalse(
                liveAfterAddingB.contains("depB"),
                "Newly added 'depB' should not become live automatically when a persisted selection already exists");
    }

    // --------------------- helpers ---------------------

    private void setupTempProject() throws IOException {
        tempRoot = Files.createTempDirectory("brokk-checked-test-");
        Files.createDirectories(tempRoot.resolve(".brokk"));
        Files.createDirectories(tempRoot.resolve(".brokk").resolve("dependencies"));
    }

    private Path createDependency(String name, String fileName) throws IOException {
        Path depDir = tempRoot.resolve(".brokk").resolve("dependencies").resolve(name);
        Files.createDirectories(depDir);
        Files.writeString(depDir.resolve(fileName), "// dummy content\n");
        return depDir;
    }

    private static void writeWorkspacePropsWithBlankLiveDeps(Path projectRoot) throws IOException {
        Properties p = new Properties();
        // Matches AbstractProject.LIVE_DEPENDENCIES_KEY
        p.setProperty("liveDependencies", "");
        Path wsProps = projectRoot.resolve(".brokk").resolve("workspace.properties");
        Files.createDirectories(wsProps.getParent());
        try (var writer = Files.newBufferedWriter(wsProps)) {
            p.store(writer, "test setup: blank liveDependencies");
        }
    }

    private static String extractDepName(ProjectFile pf) {
        // ProjectFile.relPath is masterRoot-relative, so components are:
        // [".brokk","dependencies","<depName>", ...]
        var rel = pf.getRelPath();
        if (rel.getNameCount() >= 3) {
            return rel.getName(2).toString();
        }
        return rel.toString();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
