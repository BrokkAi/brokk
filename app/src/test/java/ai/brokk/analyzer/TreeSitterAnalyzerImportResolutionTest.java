package ai.brokk.analyzer;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for import resolution error handling in TreeSitterAnalyzer.
 * Verifies that errors in one file's import resolution do not prevent processing of other files.
 */
public class TreeSitterAnalyzerImportResolutionTest {
    private static final Logger log = LoggerFactory.getLogger(TreeSitterAnalyzerImportResolutionTest.class);

    @TempDir
    private Path projectRoot;

    private IProject testProject;

    @BeforeEach
    public void setUp() throws Exception {
        // Create a minimal test project structure
        testProject = TestProjectFactory.createTestProject(projectRoot.toFile());
    }

    /**
     * Test that import resolution continues processing other files when one file has
     * import resolution errors.
     *
     * <p>Scenario:
     * - File1: Valid Java file with resolvable imports
     * - File2: Java file with malformed/unresolvable imports that would cause resolution errors
     * - File3: Valid Java file with resolvable imports
     *
     * <p>Expected behavior:
     * - Import resolution should process all files without stopping at File2's error
     * - File1 and File3 should have their imports properly resolved
     * - File2 should have empty resolved imports (due to error handling)
     * - All files should be present in the final analyzer state
     */
    @Test
    public void testImportResolutionContinuesOnError() throws Exception {
        // Create three test files
        Path file1 = projectRoot.resolve("ValidFile1.java");
        Files.write(
                file1,
                "package test;\nimport java.util.List;\npublic class ValidFile1 { }".getBytes(StandardCharsets.UTF_8));

        Path file2 = projectRoot.resolve("ProblematicFile.java");
        // Use invalid import syntax that resolveImports would choke on
        Files.write(
                file2,
                "package test;\nimport %%%invalid%%%;\npublic class ProblematicFile { }".getBytes(StandardCharsets.UTF_8));

        Path file3 = projectRoot.resolve("ValidFile2.java");
        Files.write(
                file3,
                "package test;\nimport java.util.Set;\npublic class ValidFile2 { }".getBytes(StandardCharsets.UTF_8));

        // Create a test analyzer (Java is used as example; actual language depends on implementation)
        IAnalyzer analyzer = testProject.getAnalyzerLanguages().stream()
                .filter(lang -> lang.name().equals("Java"))
                .findFirst()
                .map(lang -> lang.createAnalyzer(testProject))
                .orElseThrow(() -> new IllegalStateException("Java analyzer not available in test project"));

        // Verify all three files are present in the analyzer state
        assertTrue(analyzer instanceof TreeSitterAnalyzer, "Analyzer should be a TreeSitterAnalyzer");
        TreeSitterAnalyzer treeAnalyzer = (TreeSitterAnalyzer) analyzer;

        var fileState = treeAnalyzer.snapshotState().fileState();

        // All three files should be present in the final state
        assertEquals(3, fileState.size(), "All three files should be in the analyzer state");

        // Verify each file is present
        ProjectFile pf1 = new ProjectFile(projectRoot, "ValidFile1.java");
        ProjectFile pf2 = new ProjectFile(projectRoot, "ProblematicFile.java");
        ProjectFile pf3 = new ProjectFile(projectRoot, "ValidFile2.java");

        assertTrue(
                fileState.keySet().stream().anyMatch(f -> f.getFileName().equals("ValidFile1.java")),
                "ValidFile1.java should be in the state");
        assertTrue(
                fileState.keySet().stream().anyMatch(f -> f.getFileName().equals("ProblematicFile.java")),
                "ProblematicFile.java should be in the state despite import resolution error");
        assertTrue(
                fileState.keySet().stream().anyMatch(f -> f.getFileName().equals("ValidFile2.java")),
                "ValidFile2.java should be in the state");

        // Verify that the problematic file's resolved imports are empty (not null) due to error handling
        var problematicFileProps = fileState.values().stream()
                .filter(fp -> fp.importStatements().stream()
                        .anyMatch(stmt -> stmt.contains("%%%invalid%%%")))
                .findFirst();

        assertTrue(
                problematicFileProps.isPresent(),
                "Should find the file with malformed import statement");
        assertTrue(
                problematicFileProps.get().resolvedImports().isEmpty(),
                "Problematic file should have empty resolved imports due to error handling");

        log.info("Test passed: Import resolution correctly handled error and continued processing other files");
    }

    /**
     * Test that import resolution handles exceptions gracefully in a parallel context.
     *
     * <p>Creates multiple files and verifies that even if one throws an exception during
     * resolveImports(), the overall import resolution completes and returns a valid AnalyzerState.
     */
    @Test
    public void testImportResolutionExceptionHandlingInParallel() throws Exception {
        // Create multiple files to ensure parallel processing
        for (int i = 0; i < 5; i++) {
            Path file = projectRoot.resolve("File" + i + ".java");
            String content = i == 2
                    ? "package test;\nimport %%%bad%%%;\npublic class File2 { }"
                    : String.format("package test;\nimport java.util.*;\npublic class File%d { }", i);
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        }

        // Create analyzer
        IAnalyzer analyzer = testProject.getAnalyzerLanguages().stream()
                .filter(lang -> lang.name().equals("Java"))
                .findFirst()
                .map(lang -> lang.createAnalyzer(testProject))
                .orElseThrow(() -> new IllegalStateException("Java analyzer not available"));

        // Verify analyzer state is valid
        assertTrue(analyzer instanceof TreeSitterAnalyzer);
        TreeSitterAnalyzer treeAnalyzer = (TreeSitterAnalyzer) analyzer;
        var state = treeAnalyzer.snapshotState();

        assertNotNull(state, "Analyzer state should not be null");
        assertNotNull(state.fileState(), "File state should not be null");
        assertTrue(state.fileState().size() >= 4, "At least 4 files should be successfully processed");

        log.info("Test passed: Parallel import resolution handled exceptions correctly");
    }
}
