package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlAnalyzerTest {

    @TempDir
    Path tempDir;

    // Helper to create a Project instance for testing.
    // SqlAnalyzer requires a concrete Project, not just IProject.
    // This mock-like Project provides the minimal methods needed by SqlAnalyzer.
    private IProject createTestProject(Set<ProjectFile> filesSet) {
        // Use the single-argument constructor for Project
        return new IProject() {
            @Override
            public Path getRoot() {
                // Ensure the mock returns the tempDir as its root, consistent with Project's behavior
                return tempDir;
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                // This mock implementation directly returns the provided filesSet.
                // SqlAnalyzer will then filter these based on .sql extension and exclusions.
                return filesSet;
            }
        };
    }

    @Test
    void testSingleCreateTable() throws IOException {
        Path sqlFile = tempDir.resolve("test.sql");
        String sqlContent = "CREATE TABLE my_table (id INT, name VARCHAR(100));";
        Files.writeString(sqlFile, sqlContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        assertFalse(analyzer.isEmpty(), "Analyzer should not be empty.");

        List<CodeUnit> allDecls = analyzer.getAllDeclarations();
        assertEquals(1, allDecls.size(), "Should find one declaration.");
        CodeUnit tableCu = allDecls.get(0);
        assertEquals("my_table", tableCu.shortName());
        assertEquals("", tableCu.packageName());
        assertEquals("my_table", tableCu.fqName());
        assertTrue(tableCu.isClass(), "Table should be treated as class-like.");
        assertEquals(projectFile, tableCu.source());

        Set<CodeUnit> fileDecls = analyzer.getDeclarations(projectFile);
        assertEquals(1, fileDecls.size());
        assertTrue(fileDecls.contains(tableCu));

        Optional<ProjectFile> pfOpt = analyzer.getFileFor("my_table");
        assertTrue(pfOpt.isPresent());
        assertEquals(projectFile, pfOpt.get());

        Optional<CodeUnit> defOpt = analyzer.getDefinition("my_table");
        assertTrue(defOpt.isPresent());
        assertEquals(tableCu, defOpt.get());

        Optional<String> skeletonOpt = analyzer.getSkeleton("my_table");
        assertTrue(skeletonOpt.isPresent());
        assertEquals(sqlContent, skeletonOpt.get().trim());

        Optional<String> skeletonHeaderOpt = analyzer.getSkeletonHeader("my_table");
        assertTrue(skeletonHeaderOpt.isPresent());
        assertEquals(sqlContent, skeletonHeaderOpt.get().trim());
    }

    @Test
    void testCreateViewWithQualifiedName() throws IOException {
        Path sqlFile = tempDir.resolve("view_test.sql");
        String sqlContent = "CREATE VIEW my_schema.my_view AS SELECT id FROM my_table;";
        Files.writeString(sqlFile, sqlContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        List<CodeUnit> allDecls = analyzer.getAllDeclarations();
        assertEquals(1, allDecls.size());
        CodeUnit viewCu = allDecls.get(0);
        assertEquals("my_view", viewCu.shortName());
        assertEquals("my_schema", viewCu.packageName());
        assertEquals("my_schema.my_view", viewCu.fqName());
        assertTrue(viewCu.isClass(), "View should be treated as class-like.");

        Optional<String> skeletonOpt = analyzer.getSkeleton("my_schema.my_view");
        assertTrue(skeletonOpt.isPresent());
        assertEquals(sqlContent, skeletonOpt.get().trim());
    }

    @Test
    void testMultipleStatements() throws IOException {
        Path sqlFile = tempDir.resolve("multi.sql");
        String sqlContent =
                """
                CREATE TABLE t1 (c1 INT);
                -- This is a comment
                CREATE OR REPLACE VIEW v_schema.v1 AS
                  SELECT * FROM t1;
                CREATE TABLE IF NOT EXISTS t2 (c2 VARCHAR);
                """;
        Files.writeString(sqlFile, sqlContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        List<CodeUnit> allDecls = analyzer.getAllDeclarations();
        assertEquals(3, allDecls.size());

        Optional<CodeUnit> t1Opt = analyzer.getDefinition("t1");
        assertTrue(t1Opt.isPresent());
        assertEquals("t1", t1Opt.get().shortName());
        assertEquals("", t1Opt.get().packageName());

        Optional<CodeUnit> v1Opt = analyzer.getDefinition("v_schema.v1");
        assertTrue(v1Opt.isPresent());
        assertEquals("v1", v1Opt.get().shortName());
        assertEquals("v_schema", v1Opt.get().packageName());

        Optional<CodeUnit> t2Opt = analyzer.getDefinition("t2");
        assertTrue(t2Opt.isPresent());
        assertEquals("t2", t2Opt.get().shortName());

        assertEquals(3, analyzer.getDeclarations(projectFile).size());
    }

    @Test
    void testRangeAndLineNumbers() throws IOException {
        Path sqlFile = tempDir.resolve("ranges.sql");
        String line1 = "CREATE TABLE tbl_one (col_a TEXT);";
        String line2 = "CREATE VIEW v_two AS SELECT 1;";
        String sqlContent = line1 + "\n" + line2;
        Files.writeString(sqlFile, sqlContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));
        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        Optional<CodeUnit> tblOneOpt = analyzer.getDefinition("tbl_one");
        assertTrue(tblOneOpt.isPresent());
        CodeUnit tblOne = tblOneOpt.get();

        // Get ranges for tbl_one (should be one range)
        List<TreeSitterAnalyzer.Range> tblOneRanges = analyzer.rangesByCodeUnit.get(tblOne);
        assertNotNull(tblOneRanges);
        assertEquals(1, tblOneRanges.size());
        TreeSitterAnalyzer.Range r1 = tblOneRanges.get(0);

        assertEquals(1, r1.startLine(), "tbl_one start line");
        assertEquals(1, r1.endLine(), "tbl_one end line");
        assertEquals(0, r1.startByte(), "tbl_one start byte");
        assertEquals(line1.getBytes(StandardCharsets.UTF_8).length, r1.endByte(), "tbl_one end byte");

        Optional<CodeUnit> vTwoOpt = analyzer.getDefinition("v_two");
        assertTrue(vTwoOpt.isPresent());
        CodeUnit vTwo = vTwoOpt.get();

        List<TreeSitterAnalyzer.Range> vTwoRanges = analyzer.rangesByCodeUnit.get(vTwo);
        assertNotNull(vTwoRanges);
        assertEquals(1, vTwoRanges.size());
        TreeSitterAnalyzer.Range r2 = vTwoRanges.get(0);

        assertEquals(2, r2.startLine(), "v_two start line");
        assertEquals(2, r2.endLine(), "v_two end line");
        // Start byte is after line1 and the newline character
        assertEquals(
                line1.getBytes(StandardCharsets.UTF_8).length + "\n".getBytes(StandardCharsets.UTF_8).length,
                r2.startByte(),
                "v_two start byte");
        assertEquals(sqlContent.getBytes(StandardCharsets.UTF_8).length, r2.endByte(), "v_two end byte");

        // Test skeleton extraction based on these ranges
        Optional<String> skel1 = analyzer.getSkeleton("tbl_one");
        assertTrue(skel1.isPresent());
        assertEquals(line1, skel1.get().trim());

        Optional<String> skel2 = analyzer.getSkeleton("v_two");
        assertTrue(skel2.isPresent());
        assertEquals(line2, skel2.get().trim());
    }

    @Test
    void testExcludedFile() throws IOException {
        Path includedSqlFile = tempDir.resolve("included.sql");
        Files.writeString(includedSqlFile, "CREATE TABLE tbl_included (id INT);", StandardCharsets.UTF_8);
        ProjectFile includedProjectFile =
                new ProjectFile(tempDir, includedSqlFile.getFileName().toString());

        Path excludedDir = tempDir.resolve("excluded_dir");
        Files.createDirectories(excludedDir);
        Path excludedSqlFile = excludedDir.resolve("excluded.sql");
        Files.writeString(excludedSqlFile, "CREATE TABLE tbl_excluded (id INT);", StandardCharsets.UTF_8);
        // Note: ProjectFile for excluded file won't be created if it's correctly filtered by getAllFiles mock setup,
        // but SqlAnalyzer expects ProjectFile instances from project.getAllFiles().
        // So, we must provide it, and SqlAnalyzer's internal exclusion logic will filter it.
        ProjectFile excludedProjectFile = new ProjectFile(
                tempDir,
                excludedDir.getFileName().toString() + "/"
                        + excludedSqlFile.getFileName().toString());

        var testProject = createTestProject(Set.of(includedProjectFile, excludedProjectFile));
        // Exclude the directory "excluded_dir"
        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Set.of(Path.of("excluded_dir")));

        List<CodeUnit> allDecls = analyzer.getAllDeclarations();
        assertEquals(1, allDecls.size(), "Only one declaration from non-excluded file should be found.");
        assertEquals("tbl_included", allDecls.get(0).shortName());

        assertTrue(
                analyzer.getDefinition("tbl_excluded").isEmpty(), "Definition from excluded file should not be found.");
    }

    @Test
    void testEmptySqlFile() throws IOException {
        Path sqlFile = tempDir.resolve("empty.sql");
        Files.writeString(sqlFile, "", StandardCharsets.UTF_8); // Empty content

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        assertTrue(analyzer.isEmpty(), "Analyzer should be empty for an empty SQL file.");
        assertEquals(0, analyzer.getAllDeclarations().size());
        assertTrue(analyzer.getDeclarations(projectFile).isEmpty());
    }

    @Test
    void testNonSqlFile() throws IOException {
        Path txtFile = tempDir.resolve("test.txt"); // Not a .sql file
        Files.writeString(txtFile, "CREATE TABLE my_table (id INT);", StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, txtFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        assertTrue(analyzer.isEmpty(), "Analyzer should be empty if no .sql files are processed.");
        assertEquals(0, analyzer.getAllDeclarations().size());
    }

    @Test
    void testInvalidSqlStatement() throws IOException {
        Path sqlFile = tempDir.resolve("invalid.sql");
        // Contains a valid statement and an invalid one
        String sqlContent =
                "CREATE TABLE valid_table (id INT);\nINVALID SQL STATEMENT;\nCREATE VIEW valid_view AS SELECT 1;";
        Files.writeString(sqlFile, sqlContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        List<CodeUnit> allDecls = analyzer.getAllDeclarations();
        // Should only find the valid table and view
        assertEquals(2, allDecls.size(), "Should only parse valid CREATE statements.");
        assertTrue(allDecls.stream().anyMatch(cu -> cu.fqName().equals("valid_table")));
        assertTrue(allDecls.stream().anyMatch(cu -> cu.fqName().equals("valid_view")));
    }

    @Test
    void testGetTopLevelDeclarationsSingleTable() throws IOException {
        Path sqlFile = tempDir.resolve("test_top_level.sql");
        String sqlContent = "CREATE TABLE my_table (id INT, name VARCHAR(100));";
        Files.writeString(sqlFile, sqlContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        List<CodeUnit> topLevelUnits = analyzer.getTopLevelDeclarations(projectFile);
        assertEquals(1, topLevelUnits.size(), "Should return one top-level code unit.");
        assertEquals("my_table", topLevelUnits.get(0).shortName());
    }

    @Test
    void testGetTopLevelDeclarationsMultipleTables() throws IOException {
        Path sqlFile = tempDir.resolve("multi_top_level.sql");
        String sqlContent =
                """
                CREATE TABLE table1 (id INT);
                CREATE VIEW schema.view1 AS SELECT * FROM table1;
                CREATE TABLE table2 (name VARCHAR);
                """;
        Files.writeString(sqlFile, sqlContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        List<CodeUnit> topLevelUnits = analyzer.getTopLevelDeclarations(projectFile);
        assertEquals(3, topLevelUnits.size(), "Should return three top-level code units.");

        var names = topLevelUnits.stream().map(CodeUnit::fqName).toList();
        assertTrue(names.contains("table1"));
        assertTrue(names.contains("schema.view1"));
        assertTrue(names.contains("table2"));
    }

    @Test
    void testGetTopLevelDeclarationsEmptyFile() throws IOException {
        Path sqlFile = tempDir.resolve("empty_top_level.sql");
        Files.writeString(sqlFile, "", StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        List<CodeUnit> topLevelUnits = analyzer.getTopLevelDeclarations(projectFile);
        assertTrue(topLevelUnits.isEmpty(), "Should return empty list for empty file.");
    }

    @Test
    void testGetTopLevelDeclarationsNonExistentFile() throws IOException {
        Path existingFile = tempDir.resolve("existing.sql");
        Files.writeString(existingFile, "CREATE TABLE tbl (id INT);", StandardCharsets.UTF_8);

        ProjectFile existingProjectFile =
                new ProjectFile(tempDir, existingFile.getFileName().toString());
        ProjectFile nonExistentProjectFile = new ProjectFile(tempDir, "nonexistent.sql");

        var testProject = createTestProject(Set.of(existingProjectFile));
        SqlAnalyzer analyzer = new SqlAnalyzer(testProject, Collections.emptySet());

        List<CodeUnit> topLevelUnits = analyzer.getTopLevelDeclarations(nonExistentProjectFile);
        assertTrue(topLevelUnits.isEmpty(), "Should return empty list for non-existent file.");
    }

    @Test
    void testUpdateWithModifiedFile() throws IOException, InterruptedException {
        Path sqlFile = tempDir.resolve("modified.sql");
        String originalContent = "CREATE TABLE original_table (id INT);";
        Files.writeString(sqlFile, originalContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer1 = new SqlAnalyzer(testProject, Collections.emptySet());
        assertEquals(1, analyzer1.getAllDeclarations().size());
        assertEquals("original_table", analyzer1.getAllDeclarations().get(0).shortName());

        // Modify the file
        Thread.sleep(50); // Ensure mtime difference
        String modifiedContent = "CREATE TABLE modified_table (id INT, name VARCHAR(100));";
        Files.writeString(sqlFile, modifiedContent, StandardCharsets.UTF_8);

        // Update analyzer with the modified file
        IAnalyzer analyzer2 = analyzer1.update(Set.of(projectFile));

        List<CodeUnit> declarations = analyzer2.getAllDeclarations();
        assertEquals(1, declarations.size());
        assertEquals("modified_table", declarations.get(0).shortName());
    }

    @Test
    void testUpdateWithDeletedFile() throws IOException {
        Path sqlFile1 = tempDir.resolve("file1.sql");
        Path sqlFile2 = tempDir.resolve("file2.sql");
        Files.writeString(sqlFile1, "CREATE TABLE table1 (id INT);", StandardCharsets.UTF_8);
        Files.writeString(sqlFile2, "CREATE TABLE table2 (id INT);", StandardCharsets.UTF_8);

        ProjectFile projectFile1 =
                new ProjectFile(tempDir, sqlFile1.getFileName().toString());
        ProjectFile projectFile2 =
                new ProjectFile(tempDir, sqlFile2.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile1, projectFile2));

        SqlAnalyzer analyzer1 = new SqlAnalyzer(testProject, Collections.emptySet());
        assertEquals(2, analyzer1.getAllDeclarations().size());

        // Delete the first file
        Files.delete(sqlFile1);

        // Update analyzer with the deleted file
        IAnalyzer analyzer2 = analyzer1.update(Set.of(projectFile1));

        List<CodeUnit> declarations = analyzer2.getAllDeclarations();
        assertEquals(1, declarations.size());
        assertEquals("table2", declarations.get(0).shortName());
    }

    @Test
    void testUpdateWithNewFile() throws IOException, InterruptedException {
        Path sqlFile1 = tempDir.resolve("file1.sql");
        Files.writeString(sqlFile1, "CREATE TABLE table1 (id INT);", StandardCharsets.UTF_8);

        ProjectFile projectFile1 =
                new ProjectFile(tempDir, sqlFile1.getFileName().toString());

        var testProject = createTestProject(Set.of(projectFile1));
        SqlAnalyzer analyzer1 = new SqlAnalyzer(testProject, Collections.emptySet());
        assertEquals(1, analyzer1.getAllDeclarations().size());

        Thread.sleep(50); // Ensure mtime difference

        // Create a new file
        Path sqlFile2 = tempDir.resolve("file2.sql");
        Files.writeString(sqlFile2, "CREATE TABLE table2 (id INT);", StandardCharsets.UTF_8);
        ProjectFile projectFile2 =
                new ProjectFile(tempDir, sqlFile2.getFileName().toString());

        // Update the project mock to include both files
        var updatedTestProject = createTestProject(Set.of(projectFile1, projectFile2));

        // Create a new analyzer with the updated project that includes both files
        SqlAnalyzer analyzer2 = new SqlAnalyzer(updatedTestProject, Collections.emptySet());

        List<CodeUnit> declarations = analyzer2.getAllDeclarations();
        assertEquals(2, declarations.size());
        var names = declarations.stream().map(CodeUnit::shortName).collect(Collectors.toSet());
        assertTrue(names.contains("table1"));
        assertTrue(names.contains("table2"));
    }

    @Test
    void testUpdateWithEmptyChanges() throws IOException {
        Path sqlFile = tempDir.resolve("test.sql");
        Files.writeString(sqlFile, "CREATE TABLE my_table (id INT);", StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer1 = new SqlAnalyzer(testProject, Collections.emptySet());
        IAnalyzer analyzer2 = analyzer1.update(Collections.emptySet());

        // Should return the same analyzer instance when no changes
        assertSame(analyzer1, analyzer2);
    }

    @Test
    void testUpdateWithNonSqlFiles() throws IOException {
        Path sqlFile = tempDir.resolve("test.sql");
        Path txtFile = tempDir.resolve("test.txt");
        Files.writeString(sqlFile, "CREATE TABLE my_table (id INT);", StandardCharsets.UTF_8);
        Files.writeString(txtFile, "Some text content", StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer1 = new SqlAnalyzer(testProject, Collections.emptySet());
        assertEquals(1, analyzer1.getAllDeclarations().size());

        // Try to update with a non-SQL file (should be ignored)
        ProjectFile txtProjectFile =
                new ProjectFile(tempDir, txtFile.getFileName().toString());
        IAnalyzer analyzer2 = analyzer1.update(Set.of(txtProjectFile));

        // Should still be the same since no SQL files were updated
        assertSame(analyzer1, analyzer2);
    }

    @Test
    void testFullUpdate() throws IOException, InterruptedException {
        Path sqlFile = tempDir.resolve("test.sql");
        String initialContent = "CREATE TABLE initial_table (id INT);";
        Files.writeString(sqlFile, initialContent, StandardCharsets.UTF_8);

        ProjectFile projectFile = new ProjectFile(tempDir, sqlFile.getFileName().toString());
        var testProject = createTestProject(Set.of(projectFile));

        SqlAnalyzer analyzer1 = new SqlAnalyzer(testProject, Collections.emptySet());
        assertEquals(1, analyzer1.getAllDeclarations().size());
        assertEquals("initial_table", analyzer1.getAllDeclarations().get(0).shortName());

        // Modify the file
        Thread.sleep(50); // Ensure mtime difference
        String modifiedContent = "CREATE TABLE modified_table (id INT);";
        Files.writeString(sqlFile, modifiedContent, StandardCharsets.UTF_8);

        // Use full update (no explicit file list)
        IAnalyzer analyzer2 = analyzer1.update();

        List<CodeUnit> declarations = analyzer2.getAllDeclarations();
        assertEquals(1, declarations.size());
        assertEquals("modified_table", declarations.get(0).shortName());
    }
}
