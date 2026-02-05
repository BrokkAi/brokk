package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JavaAnalyzerUpdateTest {

    @TempDir
    Path tempDir;

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        // initial Java sources
        new ProjectFile(tempDir, "A.java")
                .write("""
        public class A {
          public int method1() { return 1; }
        }
        """);
        new ProjectFile(tempDir, "B.java")
                .write("""
        public class B {
          public int methodB() { return 1; }
        }
        """);

        project = new TestProject(tempDir, Languages.JAVA);
        analyzer = new JavaAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdateWithProvidedSet() throws IOException {
        // verify initial state
        assertTrue(analyzer.getDefinitions("A.method1").stream().findFirst().isPresent());
        assertTrue(analyzer.getDefinitions("A.method2").stream().findFirst().isEmpty());

        // mutate source – add method2
        new ProjectFile(project.getRoot(), "A.java")
                .write(
                        """
        public class A {
          public int method1() { return 1; }
          public int method2() { return 2; }
        }
        """);

        // before update the analyzer still returns old view
        assertTrue(analyzer.getDefinitions("A.method2").stream().findFirst().isEmpty());

        // update ONLY this file
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "A");
        assertTrue(maybeFile.isPresent());
        analyzer = analyzer.update(Set.of(maybeFile.get()));

        // method2 should now be visible
        assertTrue(analyzer.getDefinitions("A.method2").stream().findFirst().isPresent());

        // change again but don't include file in explicit set
        new ProjectFile(project.getRoot(), "A.java")
                .write(
                        """
        public class A {
          public int method1() { return 1; }
          public int method2() { return 2; }
          public int method3() { return 3; }
        }
        """);
        // call update with empty set – no change expected
        analyzer = analyzer.update(Set.of());
        assertTrue(analyzer.getDefinitions("A.method3").stream().findFirst().isEmpty());
    }

    @Test
    void verifySupertypeUpdate() throws IOException {
        // Create B and C so they are resolvable
        new ProjectFile(project.getRoot(), "B.java").write("public class B {}");
        new ProjectFile(project.getRoot(), "C.java").write("public class C {}");

        // Initial state: A extends B
        new ProjectFile(project.getRoot(), "A.java").write("public class A extends B {}");

        analyzer = analyzer.update();

        CodeUnit unitA = analyzer.getDefinitions("A").stream().findFirst().orElseThrow();

        // Resolve ancestors to populate the lazy cache in TreeSitterAnalyzer
        var hierarchyInitial = analyzer.as(TypeHierarchyProvider.class).orElseThrow();
        List<CodeUnit> ancestorsInitial = hierarchyInitial.getDirectAncestors(unitA);
        assertEquals(1, ancestorsInitial.size());
        assertEquals("B", ancestorsInitial.getFirst().shortName());

        // Mutate A to extend C instead
        new ProjectFile(project.getRoot(), "A.java").write("public class A extends C {}");

        // Update the analyzer. This returns a fresh instance, which should result in a fresh cache.
        analyzer = analyzer.update();

        CodeUnit unitAUpdated =
                analyzer.getDefinitions("A").stream().findFirst().orElseThrow();
        var hierarchyUpdated = analyzer.as(TypeHierarchyProvider.class).orElseThrow();
        List<CodeUnit> ancestorsUpdated = hierarchyUpdated.getDirectAncestors(unitAUpdated);

        assertEquals(1, ancestorsUpdated.size());
        assertEquals("C", ancestorsUpdated.getFirst().shortName(), "Supertype should be updated to C");
    }

    @Test
    void cachePreservedForUnchangedFilesOnExplicitUpdate() throws IOException {
        var fileA = AnalyzerUtil.getFileFor(analyzer, "A").orElseThrow();
        var fileB = AnalyzerUtil.getFileFor(analyzer, "B").orElseThrow();

        // Trigger lazy tree caching
        var treeABefore = ((TreeSitterAnalyzer) analyzer).treeOf(fileA);
        var treeBBefore = ((TreeSitterAnalyzer) analyzer).treeOf(fileB);
        assertNotNull(treeABefore);
        assertNotNull(treeBBefore);

        // Modify ONLY A.java on disk
        new ProjectFile(project.getRoot(), "A.java")
                .write(
                        """
        public class A {
          public int method1() { return 1; }
          public int modified() { return 2; }
        }
        """);

        // Run explicit update for A
        analyzer = analyzer.update(Set.of(fileA));

        // B's tree should be preserved (same instance)
        var treeBAfter = ((TreeSitterAnalyzer) analyzer).treeOf(fileB);
        assertSame(treeBBefore, treeBAfter, "B's cached tree should be carried over");

        // A's tree should be different (or at least re-parsed)
        var treeAAfter = ((TreeSitterAnalyzer) analyzer).treeOf(fileA);
        assertNotSame(treeABefore, treeAAfter, "A's cached tree should be invalidated and replaced");
        assertNotNull(treeAAfter);
    }

    @Test
    void automaticUpdateDetection() throws IOException {
        // add new method then rely on hash detection
        new ProjectFile(project.getRoot(), "A.java")
                .write(
                        """
        public class A {
          public int method1() { return 1; }
          public int method4() { return 4; }
        }
        """);
        analyzer = analyzer.update(); // no-arg detection
        assertTrue(analyzer.getDefinitions("A.method4").stream().findFirst().isPresent());

        // delete file – analyzer should drop symbols
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "A");
        assertTrue(maybeFile.isPresent());
        Files.deleteIfExists(maybeFile.get().absPath());

        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinitions("A").stream().findFirst().isEmpty());
    }

    @Test
    void signaturesCacheRespectsIncrementalUpdates() throws IOException {
        // Write controlled contents for both files
        new ProjectFile(project.getRoot(), "A.java")
                .write(
                        """
                        public class A {
                          public int m() { return 1; }
                        }
                        """);
        new ProjectFile(project.getRoot(), "B.java")
                .write(
                        """
                        public class B {
                          public int n() { return 1; }
                        }
                        """);

        // Rebuild analyzer to pick up the controlled file contents
        analyzer = new JavaAnalyzer(project);

        var maybeA = AnalyzerUtil.getFileFor(analyzer, "A");
        var maybeB = AnalyzerUtil.getFileFor(analyzer, "B");
        assertTrue(maybeA.isPresent());
        assertTrue(maybeB.isPresent());
        var fileA = maybeA.get();
        var fileB = maybeB.get();

        // Capture initial skeletons (class-level skeletons)
        var skeletonsA = analyzer.getSkeletons(fileA);
        var skeletonsB = analyzer.getSkeletons(fileB);

        var oldASkel = skeletonsA.entrySet().stream()
                .filter(e -> e.getKey().isClass() && e.getKey().shortName().equals("A"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find class skeleton for A"));
        var oldBSkel = skeletonsB.entrySet().stream()
                .filter(e -> e.getKey().isClass() && e.getKey().shortName().equals("B"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find class skeleton for B"));

        // Modify only A.java on disk
        new ProjectFile(project.getRoot(), "A.java")
                .write(
                        """
                        public class A {
                          public int m() { return 42; }
                          public int added() { return 2; }
                        }
                        """);

        // Run explicit update for just A
        analyzer = analyzer.update(Set.of(fileA));

        // Re-fetch skeletons after update
        var skeletonsA2 = analyzer.getSkeletons(fileA);
        var skeletonsB2 = analyzer.getSkeletons(fileB);

        var newASkel = skeletonsA2.entrySet().stream()
                .filter(e -> e.getKey().isClass() && e.getKey().shortName().equals("A"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find class skeleton for A after update"));
        var newBSkel = skeletonsB2.entrySet().stream()
                .filter(e -> e.getKey().isClass() && e.getKey().shortName().equals("B"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find class skeleton for B after update"));

        // Semantic assertions:
        // - Skeleton for changed file A.java must change after the incremental update.
        // - Skeleton for unchanged file B.java must remain identical.
        assertNotEquals(
                oldASkel, newASkel, "Skeleton for changed file A.java should be updated after incremental update");
        assertEquals(
                oldBSkel,
                newBSkel,
                "Skeleton for unchanged file B.java should remain identical and be served from cached signatures");
    }
}
