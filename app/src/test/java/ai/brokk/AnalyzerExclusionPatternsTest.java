package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SqlAnalyzer;
import ai.brokk.project.MainProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that exclusion patterns configured in BuildDetails are properly honored
 * by analyzers through the getAnalyzableFiles() chain.
 */
public class AnalyzerExclusionPatternsTest {

    @TempDir
    Path tempDir;

    /**
     * Verifies that files under excluded directories are not returned by getAnalyzableFiles()
     * and therefore not processed by analyzers.
     */
    @Test
    void testExclusionPatterns_excludeDirectoryFromAnalyzer() throws Exception {
        // Initialize Git repo (needed for filtering logic)
        Git.init().setDirectory(tempDir.toFile()).call();

        // Create SQL files in included and excluded directories
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("schema.sql"), "CREATE TABLE users (id INT);", StandardCharsets.UTF_8);

        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(
                buildDir.resolve("generated.sql"), "CREATE TABLE generated_tbl (id INT);", StandardCharsets.UTF_8);

        // Create project and configure exclusion patterns
        var project = new MainProject(tempDir);
        var details = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(List.of("build")));
        project.saveBuildDetails(details);

        // Verify getAnalyzableFiles() excludes the build directory
        Set<ProjectFile> analyzableFiles = project.getAnalyzableFiles(Languages.SQL);
        Set<String> paths = analyzableFiles.stream().map(pf -> pf.toString()).collect(Collectors.toSet());

        assertTrue(paths.contains("src/schema.sql"), "src/schema.sql should be analyzable");
        assertFalse(paths.stream().anyMatch(p -> p.contains("build")), "build/ files should be excluded");

        // Verify SqlAnalyzer only sees the non-excluded file
        var analyzer = new SqlAnalyzer(project);
        var declarations = analyzer.getAllDeclarations();

        assertEquals(1, declarations.size(), "Only one declaration should be found");
        assertEquals("users", declarations.get(0).shortName(), "Only 'users' table should be found");
    }

    /**
     * Verifies that glob patterns (e.g., *.generated.sql) exclude matching files.
     */
    @Test
    void testExclusionPatterns_globPatternsExcludeFiles() throws Exception {
        // Initialize Git repo
        Git.init().setDirectory(tempDir.toFile()).call();

        // Create SQL files with different naming patterns
        Files.writeString(tempDir.resolve("schema.sql"), "CREATE TABLE users (id INT);", StandardCharsets.UTF_8);
        Files.writeString(
                tempDir.resolve("data.generated.sql"), "CREATE TABLE gen_data (id INT);", StandardCharsets.UTF_8);
        Files.writeString(
                tempDir.resolve("migrations.generated.sql"),
                "CREATE TABLE gen_migrations (id INT);",
                StandardCharsets.UTF_8);

        // Create project and configure glob exclusion pattern
        var project = new MainProject(tempDir);
        var details = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(List.of("*.generated.sql")));
        project.saveBuildDetails(details);

        // Verify getAnalyzableFiles() excludes files matching the glob
        Set<ProjectFile> analyzableFiles = project.getAnalyzableFiles(Languages.SQL);
        Set<String> paths = analyzableFiles.stream().map(pf -> pf.toString()).collect(Collectors.toSet());

        assertTrue(paths.contains("schema.sql"), "schema.sql should be analyzable");
        assertFalse(paths.stream().anyMatch(p -> p.contains("generated")), "*.generated.sql files should be excluded");

        // Verify SqlAnalyzer only sees non-excluded files
        var analyzer = new SqlAnalyzer(project);
        var declarations = analyzer.getAllDeclarations();

        assertEquals(1, declarations.size(), "Only one declaration should be found");
        assertEquals("users", declarations.get(0).shortName());
    }

    /**
     * Verifies that multiple exclusion patterns work together.
     */
    @Test
    void testExclusionPatterns_multiplePatterns() throws Exception {
        // Initialize Git repo
        Git.init().setDirectory(tempDir.toFile()).call();

        // Create files in various locations
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("main.sql"), "CREATE TABLE main_tbl (id INT);", StandardCharsets.UTF_8);

        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("output.sql"), "CREATE TABLE build_tbl (id INT);", StandardCharsets.UTF_8);

        Path vendorDir = tempDir.resolve("vendor");
        Files.createDirectories(vendorDir);
        Files.writeString(
                vendorDir.resolve("external.sql"), "CREATE TABLE vendor_tbl (id INT);", StandardCharsets.UTF_8);

        Files.writeString(
                tempDir.resolve("temp.generated.sql"), "CREATE TABLE temp_tbl (id INT);", StandardCharsets.UTF_8);

        // Configure multiple exclusion patterns
        var project = new MainProject(tempDir);
        var details = new BuildAgent.BuildDetails(
                "", "", "", new LinkedHashSet<>(List.of("build", "vendor", "*.generated.sql")));
        project.saveBuildDetails(details);

        // Verify only src/main.sql is analyzable
        Set<ProjectFile> analyzableFiles = project.getAnalyzableFiles(Languages.SQL);
        Set<String> paths = analyzableFiles.stream().map(pf -> pf.toString()).collect(Collectors.toSet());

        assertEquals(1, paths.size(), "Only one file should be analyzable");
        assertTrue(paths.contains("src/main.sql"), "src/main.sql should be the only analyzable file");

        // Verify analyzer
        var analyzer = new SqlAnalyzer(project);
        var declarations = analyzer.getAllDeclarations();

        assertEquals(1, declarations.size());
        assertEquals("main_tbl", declarations.get(0).shortName());
    }
}
