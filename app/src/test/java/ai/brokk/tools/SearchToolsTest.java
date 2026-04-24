package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.IAnalyzer.Range;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.TestRepo;
import ai.brokk.project.AbstractProject;
import ai.brokk.testutil.FileUtil;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SearchTools}.
 *
 * <p>This focuses on the fallback behaviour implemented for invalid regular expressions.
 */
public class SearchToolsTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private GitRepo repo;
    private SearchTools searchTools;
    /** mutable set returned from the test project's getAllFiles() */
    private Set<ProjectFile> mockProjectFiles;

    // For analyzer-dependent tests
    @Nullable
    private static JavaAnalyzer javaAnalyzer;

    @Nullable
    private static TestProject javaTestProject;

    @BeforeAll
    static void setupAnalyzer() throws IOException {
        Path javaTestPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(javaTestPath), "Test resource directory 'testcode-java' not found.");
        javaTestProject = new TestProject(javaTestPath, Languages.JAVA);
        javaAnalyzer = new JavaAnalyzer(javaTestProject);
    }

    @AfterAll
    static void teardownAnalyzer() {
        if (javaTestProject != null) {
            javaTestProject.close();
        }
    }

    private static Path createDisposableTestProjectCopy() throws IOException {
        Path sourceDir =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        Path copyDir = Files.createTempDirectory("brokk-searchtools-testcode-java-");
        FileUtil.copyDirectory(sourceDir, copyDir);
        return copyDir;
    }

    private static void deleteRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new RuntimeException("Failed to delete temp directory: " + path, e);
        }
    }

    private void recreateSearchTools() {
        repo.close();
        repo = new GitRepo(projectRoot);

        TestProject project = new TestProject(projectRoot, Languages.NONE)
                .withRepo(repo)
                .withAllFilesSupplier(() -> mockProjectFiles)
                .withGitignoredPredicate(path -> path.startsWith(Path.of(AbstractProject.BROKK_DIR)));
        TestContextManager ctxManager =
                new TestContextManager(project, new TestConsoleIO(), Set.of(), new TestAnalyzer(), repo);

        searchTools = new SearchTools(ctxManager);
    }

    private void commitTrackedFile(String relativePath, String content, Instant instant) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
        mockProjectFiles.add(new ProjectFile(projectRoot, relativePath));

        try (Git git = Git.open(projectRoot.toFile())) {
            var ident = new PersonIdent("Test User", "test@example.com", instant, ZoneId.of("UTC"));
            git.add().addFilepattern(relativePath.replace('\\', '/')).call();
            git.commit()
                    .setMessage("Update " + relativePath)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }

    private void commitTrackedFiles(Map<String, String> filesByPath, Instant instant, String message) throws Exception {
        try (Git git = Git.open(projectRoot.toFile())) {
            var ident = new PersonIdent("Test User", "test@example.com", instant, ZoneId.of("UTC"));
            for (var entry : filesByPath.entrySet()) {
                Path file = projectRoot.resolve(entry.getKey());
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, entry.getValue());
                mockProjectFiles.add(new ProjectFile(projectRoot, entry.getKey()));
                git.add().addFilepattern(entry.getKey().replace('\\', '/')).call();
            }
            git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testRepo");
        mockProjectFiles = new HashSet<>();
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            // initial commit
            Path readme = projectRoot.resolve("README.md");
            Files.writeString(readme, "Initial commit file");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial commit").setSign(false).call();

            // commit that will be matched by substring fallback
            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Commit with [[ pattern")
                    .setSign(false)
                    .call();
        }

        repo = new GitRepo(projectRoot);
        recreateSearchTools();
    }

    @AfterEach
    void tearDown() {
        repo.close();
    }

    @Test
    void testSearchGitCommitMessages_invalidRegexFallback() {
        String result = searchTools.searchGitCommitMessages("[[", 200);

        // We should get the commit we added that contains the substring "[["
        assertTrue(result.contains("Commit with [[ pattern"), "Commit message should appear in the result");
        // Basic XML-ish structure checks
        assertTrue(result.contains("<commit id="), "Result should contain commit tag");
        assertTrue(result.contains("<message>"), "Result should contain message tag");
        assertTrue(result.contains("<edited_files>"), "Result should contain edited_files tag");
    }

    // ---------------------------------------------------------------------
    //  New tests: invalid-regex fallback for findFilesContaining / findFilenames
    // ---------------------------------------------------------------------

    @Test
    void testfindFilesContaining_invalidRegexThrows() throws Exception {
        // SearchTools.compilePatterns throws on invalid regex for this tool
        String result = searchTools.findFilesContaining(List.of("[["), 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
    }

    @Test
    void testfindFilenames_invalidRegexThrows() throws Exception {
        // SearchTools.compilePatterns throws on invalid regex for this tool
        String result = searchTools.findFilenames(List.of("[["), 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
    }

    @Test
    void testfindFilenames_limitEnforced() {
        for (int i = 0; i < 10; i++) {
            mockProjectFiles.add(new ProjectFile(projectRoot, "file" + i + ".txt"));
        }

        // Request limit of 5
        String result = searchTools.findFilenames(List.of("file.*\\.txt"), 5);

        assertTrue(result.contains("### WARNING: Result limit reached"), "Should contain truncation warning");
        assertTrue(result.contains("max 5 filenames"), "Warning should mention the limit");
        long bulletCount = result.lines().filter(line -> line.startsWith("- ")).count();
        assertEquals(5, bulletCount, "Should return exactly 5 filenames");
    }

    @Test
    void testfindFilenames_limitUsesGitImportanceBeforeAlphabeticalDisplay() throws Exception {
        commitTrackedFile("a-low.txt", "low\n", Instant.parse("2020-01-01T00:00:00Z"));
        commitTrackedFile("z-high.txt", "high\n", Instant.parse("2025-01-01T00:00:00Z"));
        recreateSearchTools();

        String result = searchTools.findFilenames(List.of(".*\\.txt"), 1);
        String mainSection = mainResultSection(result);

        assertTrue(mainSection.contains("z-high.txt"), "More important file should be selected when limit is hit");
        assertFalse(
                mainSection.contains("a-low.txt"), "Alphabetically earlier file should be dropped when less important");
    }

    @Test
    void testfindFilenames_selectedFilesAreRenderedAlphabetically() throws Exception {
        commitTrackedFile("a-low.txt", "low\n", Instant.parse("2020-01-01T00:00:00Z"));
        commitTrackedFile("z-high.txt", "high\n", Instant.parse("2025-01-01T00:00:00Z"));
        recreateSearchTools();

        String result = searchTools.findFilenames(List.of(".*\\.txt"), 2);

        assertTrue(
                result.indexOf("a-low.txt") < result.indexOf("z-high.txt"),
                "Selected files should still render alphabetically");
    }

    @Test
    void getClassSources_resolvesUniqueNonFqName() throws Exception {
        Path customRoot = Files.createTempDirectory("brokk-app-search-class-");
        try {
            Files.createDirectories(customRoot.resolve("src/main/java/com/example"));
            Files.writeString(customRoot.resolve("src/main/java/com/example/Foo.java"), "class Foo {}\n");

            var project = new TestProject(customRoot, Languages.JAVA);
            ProjectFile fooFile = new ProjectFile(customRoot, "src/main/java/com/example/Foo.java");
            CodeUnit fooClass = CodeUnit.cls(fooFile, "com.example", "Foo");
            IAnalyzer analyzer = new DisabledAnalyzer(project) {
                @Override
                public List<CodeUnit> getAllDeclarations() {
                    return List.of(fooClass);
                }

                @Override
                public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                    SequencedSet<CodeUnit> results = new LinkedHashSet<>();
                    if ("com.example.Foo".equals(fqName)) {
                        results.add(fooClass);
                    }
                    return results;
                }

                @Override
                public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
                    return codeUnit.equals(fooClass) ? Set.of("class Foo {}") : Set.of();
                }
            };
            SearchTools tools =
                    new SearchTools(new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer));

            String result = tools.getClassSources(List.of("Foo"));

            assertTrue(result.contains("class Foo {}"), "Should resolve a unique simple class name. Result: " + result);
        } finally {
            deleteRecursively(customRoot);
        }
    }

    @Test
    void getClassSources_listsAmbiguousNonFqMatches() throws Exception {
        Path customRoot = Files.createTempDirectory("brokk-app-search-class-ambiguous-");
        try {
            var project = new TestProject(customRoot, Languages.JAVA);
            ProjectFile firstFile = new ProjectFile(customRoot, "src/main/java/com/example/Foo.java");
            ProjectFile secondFile = new ProjectFile(customRoot, "src/main/java/org/example/Foo.java");
            CodeUnit firstFoo = CodeUnit.cls(firstFile, "com.example", "Foo");
            CodeUnit secondFoo = CodeUnit.cls(secondFile, "org.example", "Foo");
            IAnalyzer analyzer = new DisabledAnalyzer(project) {
                @Override
                public List<CodeUnit> getAllDeclarations() {
                    return List.of(firstFoo, secondFoo);
                }
            };
            SearchTools tools =
                    new SearchTools(new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer));

            String result = tools.getClassSources(List.of("Foo"));

            assertTrue(result.contains("Ambiguous class match for 'Foo'"), result);
            assertTrue(result.contains("com.example.Foo"), result);
            assertTrue(result.contains("org.example.Foo"), result);
        } finally {
            deleteRecursively(customRoot);
        }
    }

    @Test
    void getMethodSources_resolvesUniqueNonFqName() throws Exception {
        Path customRoot = Files.createTempDirectory("brokk-app-search-method-");
        try {
            Files.createDirectories(customRoot.resolve("src/main/java/com/example"));
            Files.writeString(customRoot.resolve("src/main/java/com/example/Foo.java"), "class Foo {}\n");

            var project = new TestProject(customRoot, Languages.JAVA);
            ProjectFile fooFile = new ProjectFile(customRoot, "src/main/java/com/example/Foo.java");
            CodeUnit fooClass = CodeUnit.cls(fooFile, "com.example", "Foo");
            CodeUnit incMethod = CodeUnit.fn(fooFile, "com.example", "Foo.inc");
            IAnalyzer analyzer = new DisabledAnalyzer(project) {
                @Override
                public List<CodeUnit> getAllDeclarations() {
                    return List.of(fooClass);
                }

                @Override
                public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
                    return classUnit.equals(fooClass) ? List.of(incMethod) : List.of();
                }

                @Override
                public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                    SequencedSet<CodeUnit> results = new LinkedHashSet<>();
                    if ("com.example.Foo".equals(fqName)) {
                        results.add(fooClass);
                    } else if ("com.example.Foo.inc".equals(fqName)) {
                        results.add(incMethod);
                    }
                    return results;
                }

                @Override
                public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
                    return codeUnit.equals(incMethod) ? Set.of("int inc(int x) { return x + 1; }") : Set.of();
                }
            };
            SearchTools tools =
                    new SearchTools(new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer));

            String result = tools.getMethodSources(List.of("inc"));

            assertTrue(
                    result.contains("int inc(int x) { return x + 1; }"),
                    "Should resolve a unique simple method name. Result: " + result);
        } finally {
            deleteRecursively(customRoot);
        }
    }

    @Test
    void getMethodSources_listsAmbiguousNonFqMatches() throws Exception {
        Path customRoot = Files.createTempDirectory("brokk-app-search-method-ambiguous-");
        try {
            var project = new TestProject(customRoot, Languages.JAVA);
            ProjectFile firstFile = new ProjectFile(customRoot, "src/main/java/com/example/Foo.java");
            ProjectFile secondFile = new ProjectFile(customRoot, "src/main/java/org/example/Bar.java");
            CodeUnit fooClass = CodeUnit.cls(firstFile, "com.example", "Foo");
            CodeUnit barClass = CodeUnit.cls(secondFile, "org.example", "Bar");
            CodeUnit firstMethod = CodeUnit.fn(firstFile, "com.example", "Foo.inc");
            CodeUnit secondMethod = CodeUnit.fn(secondFile, "org.example", "Bar.inc");
            IAnalyzer analyzer = new DisabledAnalyzer(project) {
                @Override
                public List<CodeUnit> getAllDeclarations() {
                    return List.of(fooClass, barClass);
                }

                @Override
                public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
                    if (classUnit.equals(fooClass)) {
                        return List.of(firstMethod);
                    }
                    if (classUnit.equals(barClass)) {
                        return List.of(secondMethod);
                    }
                    return List.of();
                }
            };
            SearchTools tools =
                    new SearchTools(new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer));

            String result = tools.getMethodSources(List.of("inc"));

            assertTrue(result.contains("Ambiguous method match for 'inc'"), result);
            assertTrue(result.contains("com.example.Foo.inc"), result);
            assertTrue(result.contains("org.example.Bar.inc"), result);
        } finally {
            deleteRecursively(customRoot);
        }
    }

    @Test
    void testfindFilenames_withSubdirectories() throws Exception {
        // 1. Create a file with a subdirectory path
        Path subDir = projectRoot.resolve("frontend-mop").resolve("src");
        Files.createDirectories(subDir);
        Path filePath = subDir.resolve("MOP.svelte");
        Files.writeString(filePath, "dummy content");

        // 2. Add to mock project file list.
        String relativePathNix = "frontend-mop/src/MOP.svelte";
        String relativePathWin = "frontend-mop\\src\\MOP.svelte";
        mockProjectFiles.add(new ProjectFile(projectRoot, relativePathNix));

        // 3. Test cases
        // A. Full path with forward slashes
        String resultNix = searchTools.findFilenames(List.of(relativePathNix), 200);
        assertTrue(resultNix.contains("# frontend-mop/src"), "Should group by common prefix");
        assertTrue(resultNix.contains("- MOP.svelte"), "Should include matching file under prefix");

        // B. File name only
        String resultName = searchTools.findFilenames(List.of("MOP.svelte"), 200);
        assertTrue(resultName.contains("# frontend-mop/src"), "Should group by common prefix");
        assertTrue(resultName.contains("- MOP.svelte"), "Should include matching file under prefix");

        // C. Partial path
        String resultPartial = searchTools.findFilenames(List.of("src/MOP"), 200);
        assertTrue(resultPartial.contains("# frontend-mop/src"), "Should group by common prefix");
        assertTrue(resultPartial.contains("- MOP.svelte"), "Should include matching file under prefix");

        // D. Full path with backslashes (Windows-style)
        String resultWin = searchTools.findFilenames(List.of(relativePathWin), 200);
        assertTrue(resultWin.contains("# frontend-mop/src"), "Should group by common prefix");
        assertTrue(resultWin.contains("- MOP.svelte"), "Should include matching file under prefix");

        // E. Regex path pattern (frontend-mop/.*\.svelte)
        String regexPattern = "frontend-mop/.*\\.svelte";
        String resultRegex = searchTools.findFilenames(List.of(regexPattern), 200);
        assertTrue(resultRegex.contains("# frontend-mop/src"), "Should group by common prefix");
        assertTrue(resultRegex.contains("- MOP.svelte"), "Should include matching file under prefix");

        // F. Case-insensitive check
        String resultUpper = searchTools.findFilenames(List.of("MOP.SVELTE"), 200);
        assertTrue(resultUpper.contains("# frontend-mop/src"), "Should group by common prefix");
        assertTrue(resultUpper.contains("- MOP.svelte"), "Should include matching file under prefix");
    }

    @Test
    void testFindFilenames_DoesNotAppendRelatedContent() throws Exception {
        commitTrackedFiles(
                Map.of("A.java", "class A {}", "B.java", "class B {}"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");
        recreateSearchTools();

        String result = searchTools.findFilenames(List.of("A\\.java"), 10);

        assertTrue(result.contains("A.java"), "Should still include matching filenames");
        assertFalse(result.contains("## Related Content"), "Should not include related content for non-search tools");
    }

    @Test
    void testSkimFiles() {
        TestContextManager ctxWithAnalyzer = new TestContextManager(
                javaTestProject, new TestConsoleIO(), Set.of(), javaAnalyzer, new TestRepo(javaTestProject.getRoot()));

        SearchTools tools = new SearchTools(ctxWithAnalyzer);

        // Test skimming the root directory of the test project using glob
        String result = tools.skimFiles(List.of("*.java"));
        assertFalse(result.isEmpty(), "Result should not be empty");

        // Verify it contains file entries in XML-ish format with loc
        assertTrue(result.contains("<file path=\"A.java\" loc=\""), "Should contain A.java entry with loc");
        assertTrue(result.contains("<file path=\"B.java\" loc=\""), "Should contain B.java entry with loc");

        // Verify it contains some identifiers from the files
        assertTrue(result.contains("A"), "Should mention identifier A");
    }

    @Test
    void testSkimFiles_dependenciesNotGitignored() throws IOException {
        Path projectRootCopy = createDisposableTestProjectCopy();
        try (TestProject localProject = new TestProject(projectRootCopy, Languages.JAVA)) {
            JavaAnalyzer localAnalyzer = new JavaAnalyzer(localProject);

            TestContextManager ctxWithAnalyzer = new TestContextManager(
                    localProject, new TestConsoleIO(), Set.of(), localAnalyzer, new TestRepo(localProject.getRoot()));

            SearchTools tools = new SearchTools(ctxWithAnalyzer);

            // 1. Create a .brokk/dependencies/testrepo directory structure
            Path depsRepoPath = localProject
                    .getRoot()
                    .resolve(AbstractProject.BROKK_DIR)
                    .resolve(AbstractProject.DEPENDENCIES_DIR)
                    .resolve("testrepo");
            Files.createDirectories(depsRepoPath);
            Path testFile = depsRepoPath.resolve("DependencyFile.java");
            Files.writeString(testFile, "public class DependencyFile {}");

            try {
                // 2. Call skimFiles on the dependency path
                // Using String.join instead of Path.of to avoid "Illegal char <*>" on Windows
                String pathString = String.join(
                        "/", AbstractProject.BROKK_DIR, AbstractProject.DEPENDENCIES_DIR, "testrepo", "*.java");
                String result = tools.skimFiles(List.of(pathString));

                // 3. Verify that the file IS returned (not filtered out by the .brokk gitignore simulation)
                assertTrue(
                        result.contains("DependencyFile.java"),
                        "File in dependencies should be found even if .brokk is gitignored");
            } finally {
                // Clean up the created directory in the test project
                Files.deleteIfExists(testFile);
                Files.deleteIfExists(depsRepoPath);
            }
        } finally {
            deleteRecursively(projectRootCopy);
        }
    }

    @Test
    void testGetClassSkeletons() {
        TestContextManager ctxWithAnalyzer = new TestContextManager(
                javaTestProject, new TestConsoleIO(), Set.of(), javaAnalyzer, new TestRepo(javaTestProject.getRoot()));

        SearchTools tools = new SearchTools(ctxWithAnalyzer);

        // Test 1: Valid class names - verify skeleton structure
        String result = tools.getClassSkeletons(List.of("A", "D"));
        assertFalse(result.isEmpty(), "Result should not be empty for valid classes");

        // Split by double newline to get individual skeletons
        String[] skeletons = result.split("\n\n");
        assertTrue(skeletons.length >= 2, "Should have at least 2 skeletons");

        // Verify that skeletons contain class declarations
        boolean foundA = false;
        boolean foundD = false;
        for (String skeleton : skeletons) {
            if (skeleton.contains("class A") && !skeleton.contains("class AB")) {
                foundA = true;
                // Verify it's a skeleton (should not contain method bodies)
                assertFalse(skeleton.contains("System.out"), "Skeleton should not contain method body");
            }
            if (skeleton.contains("class D")) {
                foundD = true;
                assertFalse(skeleton.contains("System.out"), "Skeleton should not contain method body");
            }
        }
        assertTrue(foundA, "Should find skeleton for class A");
        assertTrue(foundD, "Should find skeleton for class D");

        // Test 2: Mix of valid and invalid class names
        String result2 = tools.getClassSkeletons(List.of("A", "NonExistent"));
        assertFalse(result2.isEmpty(), "Should return results even with some invalid names");
        assertTrue(result2.contains("class A"), "Should still contain skeleton for valid class A");

        // Test 3: Non-existent class only
        String result3 = tools.getClassSkeletons(List.of("CompletelyFake"));
        assertTrue(
                result3.contains("No classes found") || result3.isEmpty(),
                "Should handle non-existent class gracefully");

        // Test 4: Verify CodeUnit-native API is used (not String-based)
        // This is implicitly tested by the fact that the method works correctly
        // The refactored code uses getDefinition(String) -> filter(isClass) -> getSkeleton(CodeUnit)
        String result4 = tools.getClassSkeletons(List.of("A"));
        assertTrue(result4.contains("class A"), "CodeUnit-native API should work correctly");
    }

    @Test
    void testGetSummaries_routesClassesAndFilesTogether() {
        TestContextManager ctxWithAnalyzer = new TestContextManager(
                javaTestProject, new TestConsoleIO(), Set.of(), javaAnalyzer, new TestRepo(javaTestProject.getRoot()));

        SearchTools tools = new SearchTools(ctxWithAnalyzer);

        String result = tools.getSummaries(List.of("A", "B.java", "Missing.java"));

        assertTrue(result.contains("class A"), "Should include class summary for direct class targets");
        assertTrue(result.contains("class B"), "Should include class summary for file targets");
        assertTrue(result.contains("No project files found matching: Missing.java"), "Should report unmatched files");
    }

    @Test
    void testGetGitLog_limitEnforced() throws Exception {
        // Create several commits
        try (Git git = Git.open(projectRoot.toFile())) {
            for (int i = 1; i <= 5; i++) {
                git.commit()
                        .setAllowEmpty(true)
                        .setMessage("Extra commit " + i)
                        .setSign(false)
                        .call();
            }
        }

        // Request with limit of 3 (repo already has 2 commits from setUp + 5 = 7 total)
        String result = searchTools.getGitLog("", 3);
        // Count the number of <entry> tags
        // Count occurrences of "<entry " to be precise
        int entries = countOccurrences(result, "<entry ");
        assertEquals(3, entries, "Should have exactly 3 entries when limit is 3. Result:\n" + result);
    }

    @Test
    void testGetGitLog_emptyPathReturnsRepoWideLog() {
        String result = searchTools.getGitLog("", 100);

        // Should contain commits from setUp (Initial commit + "Commit with [[ pattern")
        assertTrue(result.contains("<git_log>"), "Should have git_log wrapper");
        assertTrue(result.contains("Initial commit"), "Should contain initial commit message");
        assertTrue(result.contains("Commit with [[ pattern"), "Should contain second commit message");
    }

    @Test
    void testGetGitLog_invalidPathReturnsNoHistory() {
        String result = searchTools.getGitLog("nonexistent/path/to/file.txt", 10);
        assertTrue(
                result.contains("No history found"), "Should indicate no history for invalid path. Result:\n" + result);
    }

    @Test
    void testGetGitLog_fileWithNoHistory() throws Exception {
        // Create a new untracked file (not committed)
        Path newFile = projectRoot.resolve("untracked.txt");
        Files.writeString(newFile, "not committed");

        String result = searchTools.getGitLog("untracked.txt", 10);
        assertTrue(
                result.contains("No history found"),
                "Should indicate no history for untracked file. Result:\n" + result);
    }

    @Test
    void testGetGitLog_limitCappedAt100() throws Exception {
        // Verify that requesting more than 100 doesn't exceed 100
        // We only have a few commits, so just verify no error and entries <= requested
        String result = searchTools.getGitLog("", 200);
        int entries = countOccurrences(result, "<entry ");
        assertTrue(entries <= 100, "Entries should never exceed 100");
        assertTrue(entries > 0, "Should have at least one entry");
    }

    @Test
    void testGetGitLog_specificFile() throws Exception {
        String result = searchTools.getGitLog("README.md", 10);
        assertTrue(result.contains("<git_log"), "Should have git_log wrapper");
        assertTrue(result.contains("Initial commit"), "Should contain the commit that added README.md");
        assertTrue(result.contains("Files: README.md"), "Should contain simple filename CDL");

        // The "Commit with [[ pattern" is an empty commit, so README.md shouldn't appear in it
        assertFalse(
                result.contains("Commit with [[ pattern"), "Should not contain empty commit unrelated to README.md");
    }

    @Test
    void testSearchFileContents() throws Exception {
        Path txt = projectRoot.resolve("grep_test.txt");
        Files.writeString(txt, "line1\nline2 MATCH\nline3\nline4");
        mockProjectFiles.add(new ProjectFile(projectRoot, "grep_test.txt"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "**/grep_test.txt", false, false, 1, 200);

        assertTrue(result.contains("<file path=\"grep_test.txt\" loc=\"4\">"));
        assertTrue(result.contains("<matches>"));
        assertTrue(result.contains("1: line1"));
        assertTrue(result.contains("2: line2 MATCH"));
        assertTrue(result.contains("3: line3"));
        assertFalse(result.contains("4: line4"));
    }

    @Test
    void testSearchFileContents_invalidRegexThrows() throws Exception {
        // "[[" is invalid regex, should return error message
        String result = searchTools.searchFileContents(List.of("[["), "README.md", false, false, 0, 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
    }

    @Test
    void testJq() throws Exception {
        Path json = projectRoot.resolve("test.json");
        Files.writeString(json, "{\"a\": [{\"id\": 1}, {\"id\": 2}]}");
        mockProjectFiles.add(new ProjectFile(projectRoot, "test.json"));

        String result = searchTools.jq("test.json", ".a[] | .id", 1, 10);

        assertTrue(result.contains("File: test.json"));
        assertTrue(result.contains("1"));
        assertTrue(result.contains("2"));
    }

    @Test
    void testJq_InvalidJsonAndFilter() throws Exception {
        Path json = projectRoot.resolve("bad.json");
        Files.writeString(json, "{ invalid json }");
        mockProjectFiles.add(new ProjectFile(projectRoot, "bad.json"));

        // 1. Invalid Filter
        String filterResult = searchTools.jq("bad.json", ".[[[", 1, 10);
        assertTrue(filterResult.contains("Invalid jq filter"), "Should report filter compilation error");

        // 2. Invalid Content
        String contentResult = searchTools.jq("bad.json", ".", 1, 10);
        assertTrue(contentResult.contains("errors in 1 of 1 files"), "Should report JSON parsing error");
    }

    @Test
    void testJq_BfsSummarizationWhenResultTooLarge() throws Exception {
        Path json = projectRoot.resolve("big.json");
        String huge = "a".repeat(2100);
        Files.writeString(
                json,
                """
                {
                  "obj": {
                    "k1": "%s",
                    "k2": 123,
                    "k3": {"nested": [1, 2, 3]}
                  }
                }
                """
                        .stripIndent()
                        .formatted(huge));
        mockProjectFiles.add(new ProjectFile(projectRoot, "big.json"));

        String result = searchTools.jq("big.json", ".obj", 1, 10);

        assertTrue(result.contains("[JSON_TOO_LARGE]"), "Should indicate JSON was too large. Result:\n" + result);
        assertTrue(result.contains("$ type=object"), "Skim should include root object line. Result:\n" + result);
        assertTrue(result.contains("$.k1 type=string len=2100"), "Skim should include string len. Result:\n" + result);
        assertFalse(result.contains("a".repeat(200)), "Should not dump the huge string content. Result:\n" + result);
    }

    @Test
    void testSearchFileContents_PathRetry() throws Exception {
        Path rootFile = projectRoot.resolve("root.txt");
        Files.writeString(rootFile, "found me");
        mockProjectFiles.add(new ProjectFile(projectRoot, "root.txt"));

        // Verify that **/root.txt matches a file at the project root via the retry logic
        String result = searchTools.searchFileContents(List.of("found"), "**/root.txt", false, false, 0, 200);
        assertTrue(result.contains("root.txt"), "Should find file at root even with **/ prefix");
    }

    @Test
    void testSearchFileContents_limitUsesGitImportanceBeforeAlphabeticalDisplay() throws Exception {
        commitTrackedFile("a-low.txt", "MATCH low\n", Instant.parse("2020-01-01T00:00:00Z"));
        commitTrackedFile("z-high.txt", "MATCH high\n", Instant.parse("2025-01-01T00:00:00Z"));
        recreateSearchTools();

        String result = searchTools.searchFileContents(List.of("MATCH"), "*.txt", false, false, 0, 1);
        String mainSection = mainResultSection(result);

        assertTrue(
                mainSection.contains("z-high.txt"),
                "More important matching file should be selected when maxFiles is hit");
        assertFalse(
                mainSection.contains("a-low.txt"),
                "Alphabetically earlier matching file should be omitted when less important");
    }

    @Test
    void testSearchFileContents_CaseInsensitiveFlag() throws Exception {
        Path txt = projectRoot.resolve("case_insensitive.txt");
        Files.writeString(txt, "Line1\nLine2 MATCH\nLine3");
        mockProjectFiles.add(new ProjectFile(projectRoot, "case_insensitive.txt"));

        String withoutFlag =
                searchTools.searchFileContents(List.of("match"), "case_insensitive.txt", false, false, 0, 200);
        assertTrue(withoutFlag.contains("No matches found"), "Should not match without case-insensitive flag");

        String withFlag = searchTools.searchFileContents(List.of("match"), "case_insensitive.txt", true, false, 0, 200);
        assertTrue(
                withFlag.contains("<file path=\"case_insensitive.txt\" loc=\"3\">"),
                "Should match with case-insensitive flag and show file metadata");
        assertTrue(withFlag.contains("2: Line2 MATCH"), "Should show matching line");
    }

    @Test
    void testSearchFileContents_MultilineFlag_Anchors() throws Exception {
        Path txt = projectRoot.resolve("multiline.txt");
        Files.writeString(txt, "line1\nline2\nline3");
        mockProjectFiles.add(new ProjectFile(projectRoot, "multiline.txt"));

        String withoutFlag = searchTools.searchFileContents(List.of("^line2$"), "multiline.txt", false, false, 0, 200);
        assertTrue(withoutFlag.contains("No matches found"), "Should not match ^/$ without multiline flag");

        String withFlag = searchTools.searchFileContents(List.of("^line2$"), "multiline.txt", false, true, 0, 200);
        assertTrue(
                withFlag.contains("<file path=\"multiline.txt\" loc=\"3\">"),
                "Should match with multiline flag and show file metadata");
        assertTrue(withFlag.contains("2: line2"), "Should show the anchored match line");
    }

    @Test
    void testGetGitLog_RenameTracking() throws Exception {
        Path oldPath = projectRoot.resolve("old_name.txt");
        Files.writeString(oldPath, "original content");

        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern("old_name.txt").call();
            git.commit().setMessage("Add old_name").setSign(false).call();

            // Rename via git
            Path newPath = projectRoot.resolve("new_name.txt");
            Files.move(oldPath, newPath);
            git.add().addFilepattern("new_name.txt").call();
            git.rm().addFilepattern("old_name.txt").call();
            git.commit().setMessage("Rename to new_name").setSign(false).call();
        }

        // When requesting log for new_name.txt, we should see the rename breadcrumb
        String result = searchTools.getGitLog("new_name.txt", 10);
        assertTrue(result.contains("[RENAMED]"), "Should show rename marker in log");
        assertTrue(result.contains("old_name.txt -> new_name.txt"), "Should show path transition");
    }

    @Test
    void testCompilePatterns_ErrorAggregation() {
        List<String> invalidPatterns = List.of("valid", "[", "(", "   ");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SearchTools.compilePatterns(invalidPatterns));

        assertTrue(ex.getMessage().contains("'['"), "Should report first invalid pattern");
        assertTrue(ex.getMessage().contains("'('"), "Should report second invalid pattern");
        assertFalse(ex.getMessage().contains("'valid'"), "Should not report valid pattern");
        assertFalse(ex.getMessage().contains("'   '"), "Should ignore blank patterns");
    }

    @Test
    void testSearchSymbols_StripsParams() throws InterruptedException {
        // This test ensures it doesn't crash and handles the typical LLM mistake
        String result = searchTools.searchSymbols(List.of("com.Foo.bar(int, String)"), false, 200);
        assertTrue(
                result.contains("No definitions found"), "Should attempt search and find nothing (correctly stripped)");
    }

    @Test
    void testSearchSymbols_RendersDisplaySignatures() throws IOException, InterruptedException {
        Path aJava = projectRoot.resolve("src/main/java/com/example/A.java");
        Files.createDirectories(aJava.getParent());
        Files.writeString(
                aJava,
                """
                class A extends Base {
                    public void bar(int x, int y) {}
                }
                """
                        .stripIndent());
        ProjectFile pf = new ProjectFile(projectRoot, "src/main/java/com/example/A.java");
        mockProjectFiles.add(pf);

        CodeUnit cls = ai.brokk.analyzer.CodeUnit.cls(pf, "com.example", "A");
        CodeUnit method = ai.brokk.analyzer.CodeUnit.fn(pf, "com.example", "A.bar");
        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(cls);
        analyzer.addDeclaration(method);
        analyzer.setDisplaySignatures(cls, List.of("class A extends Base"));
        analyzer.setDisplaySignatures(method, List.of("public void bar(int x, int y)"));
        analyzer.setRanges(cls, List.of(new Range(0, 0, 0, 30, 0)));
        analyzer.setRanges(method, List.of(new Range(0, 0, 1, 39, 0)));

        TestContextManager ctx = new TestContextManager(
                new TestProject(projectRoot, Languages.JAVA).withAllFilesSupplier(() -> mockProjectFiles),
                new TestConsoleIO(),
                Set.of(),
                analyzer,
                repo);
        SearchTools tools = new SearchTools(ctx);

        String result = tools.searchSymbols(List.of(".*A.*"), false, 200);

        assertTrue(result.contains("- 1: class A extends Base"), "Should render class signature. Result: " + result);
        assertTrue(
                result.contains("- 2: public void bar(int x, int y)"),
                "Should render method signature. Result: " + result);
        assertFalse(result.contains("com.example.A.bar"), "Should not render raw method FQN. Result: " + result);
    }

    @Test
    void testSearchSymbols_PreservesOverloadsWhenDisplaySignaturesCollide() throws IOException, InterruptedException {
        Path aJava = projectRoot.resolve("src/main/java/com/example/A.java");
        Files.createDirectories(aJava.getParent());
        Files.writeString(
                aJava,
                """
                class A {
                    public void bar(int value) {}
                    public void bar(String value) {}
                }
                """
                        .stripIndent());
        ProjectFile pf = new ProjectFile(projectRoot, "src/main/java/com/example/A.java");
        mockProjectFiles.add(pf);

        CodeUnit intOverload =
                new ai.brokk.analyzer.CodeUnit(pf, ai.brokk.analyzer.CodeUnitType.FUNCTION, "com.example", "A.bar", "(int)");
        CodeUnit stringOverload = new ai.brokk.analyzer.CodeUnit(
                pf, ai.brokk.analyzer.CodeUnitType.FUNCTION, "com.example", "A.bar", "(String)");
        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(intOverload);
        analyzer.addDeclaration(stringOverload);
        analyzer.setDisplaySignatures(intOverload, List.of("public void bar(T value)"));
        analyzer.setDisplaySignatures(stringOverload, List.of("public void bar(T value)"));
        analyzer.setRanges(intOverload, List.of(new Range(0, 0, 1, 33, 0)));
        analyzer.setRanges(stringOverload, List.of(new Range(0, 0, 2, 36, 0)));

        TestContextManager ctx = new TestContextManager(
                new TestProject(projectRoot, Languages.JAVA).withAllFilesSupplier(() -> mockProjectFiles),
                new TestConsoleIO(),
                Set.of(),
                analyzer,
                repo);
        SearchTools tools = new SearchTools(ctx);

        String result = tools.searchSymbols(List.of(".*A.*"), false, 200);

        assertTrue(
                result.contains("- 2: public void bar(T value)"),
                "Should keep the first overload when display signatures collide. Result: " + result);
        assertTrue(
                result.contains("- 3: public void bar(T value)"),
                "Should keep the second overload when display signatures collide. Result: " + result);
    }

    @Test
    void testSearchSymbols_IncludesLoc() throws IOException, InterruptedException {
        Path aJava = projectRoot.resolve("A.java");
        Files.writeString(aJava, "class A {}");
        ProjectFile pf = new ProjectFile(projectRoot, "A.java");
        mockProjectFiles.add(pf);

        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(ai.brokk.analyzer.CodeUnit.cls(pf, "", "A"));

        TestContextManager ctx = new TestContextManager(
                new TestProject(projectRoot, Languages.JAVA).withAllFilesSupplier(() -> mockProjectFiles),
                new TestConsoleIO(),
                Set.of(),
                analyzer,
                repo);
        SearchTools tools = new SearchTools(ctx);

        String result = tools.searchSymbols(List.of("A"), false, 200);
        assertTrue(result.contains("loc=\"1\""), "Should contain loc attribute in file tag. Result: " + result);
    }

    @Test
    void testSearchSymbols_AppendsRelatedContent() throws Exception {
        commitTrackedFiles(
                Map.of("A.java", "class A {}", "B.java", "class B {}"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");

        ProjectFile aFile = new ProjectFile(projectRoot, "A.java");
        ProjectFile bFile = new ProjectFile(projectRoot, "B.java");
        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(ai.brokk.analyzer.CodeUnit.cls(aFile, "", "A"));
        analyzer.addDeclaration(ai.brokk.analyzer.CodeUnit.cls(bFile, "", "B"));

        TestContextManager ctx = new TestContextManager(
                new TestProject(projectRoot, Languages.JAVA).withAllFilesSupplier(() -> mockProjectFiles),
                new TestConsoleIO(),
                Set.of(),
                analyzer,
                repo);
        SearchTools tools = new SearchTools(ctx);

        String result = tools.searchSymbols(List.of("A"), false, 200);
        String relatedSection = relatedContentSection(result);

        assertTrue(result.contains("## Related Content"), "Should include related content header");
        assertTrue(relatedSection.contains("B.java"), "Should include a Git-related file");
        assertFalse(relatedSection.contains("A.java"), "Should not echo the result file in related content");
    }

    @Test
    void testGetSymbolLocations_IncludesLoc() throws IOException {
        Path aJava = projectRoot.resolve("A.java");
        Files.writeString(aJava, "class A {}");
        ProjectFile pf = new ProjectFile(projectRoot, "A.java");
        mockProjectFiles.add(pf);

        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(ai.brokk.analyzer.CodeUnit.cls(pf, "", "A"));

        TestContextManager ctx = new TestContextManager(
                new TestProject(projectRoot, Languages.JAVA).withAllFilesSupplier(() -> mockProjectFiles),
                new TestConsoleIO(),
                Set.of(),
                analyzer,
                repo);
        SearchTools tools = new SearchTools(ctx);

        String result = tools.getSymbolLocations(List.of("A"));
        assertTrue(result.contains("A -> A.java (1 loc)"), "Symbol locations should include LOC. Result: " + result);
    }

    @Test
    void testFindFilenames_IncludesLoc() throws IOException {
        Path locTest = projectRoot.resolve("loc_test.txt");
        Files.writeString(locTest, "content");
        mockProjectFiles.add(new ProjectFile(projectRoot, "loc_test.txt"));
        String result = searchTools.findFilenames(List.of("loc_test"), 10);
        assertTrue(result.contains("loc_test.txt (1 loc)"), "Filename results should include LOC");
    }

    @Test
    void testListFiles_IncludesLoc() throws IOException {
        Path listLoc = projectRoot.resolve("list_loc.txt");
        Files.writeString(listLoc, "content");
        mockProjectFiles.add(new ProjectFile(projectRoot, "list_loc.txt"));
        String result = searchTools.listFiles(".");
        assertTrue(result.contains("list_loc.txt (1 loc)"), "List files results should include LOC");
    }

    @Test
    void testSearchFileContents_ContextAndClamping() throws Exception {
        Path txt = projectRoot.resolve("context_test.txt");
        // Matches on lines 2 and 4 (1-indexed)
        Files.writeString(txt, "L1\nL2 MATCH\nL3\nL4 MATCH\nL5\nL6\nL7");
        mockProjectFiles.add(new ProjectFile(projectRoot, "context_test.txt"));

        // contextLines = 1. Matches are at index 1 and 3.
        // Match 1 (idx 1) -> lines 0, 1, 2
        // Match 2 (idx 3) -> lines 2, 3, 4
        // De-duped output should show L1, L2, L3, L4, L5 exactly once.
        String result = searchTools.searchFileContents(List.of("MATCH"), "context_test.txt", false, false, 1, 200);

        assertTrue(result.contains("1: L1"));
        assertTrue(result.contains("2: L2 MATCH"));
        assertTrue(result.contains("3: L3"));
        assertTrue(result.contains("4: L4 MATCH"));
        assertTrue(result.contains("5: L5"));
        assertFalse(result.contains("6: L6"));

        assertEquals(
                1, countOccurrences(result, "3: L3"), "Line 3 should only be printed once despite overlapping context");

        // Verify clamping: contextLines=999 should be clamped to 5
        // Our file is small, so it should just show everything.
        String resultsCapped =
                searchTools.searchFileContents(List.of("MATCH"), "context_test.txt", false, false, 999, 200);
        assertTrue(resultsCapped.contains("7: L7"));
    }

    @Test
    void testSearchFileContents_FixedPerPatternLimitIsHitCount() throws Exception {
        Path txt = projectRoot.resolve("matches_per_file_test.txt");
        String content = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "MATCH " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(txt, content);
        mockProjectFiles.add(new ProjectFile(projectRoot, "matches_per_file_test.txt"));

        String result =
                searchTools.searchFileContents(List.of("MATCH"), "matches_per_file_test.txt", false, false, 0, 200);

        assertTrue(result.contains("<file path=\"matches_per_file_test.txt\" loc=\"30\">"));
        assertTrue(result.contains("20: MATCH 20"));
        assertFalse(result.contains("21: MATCH 21"));
    }

    @Test
    void testSearchFileContents_GlobalMatchBudget500IsSoftWithinFile() throws Exception {
        String twentyA = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "A " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        for (int i = 1; i <= 24; i++) {
            String filename = "budget%02d.txt".formatted(i);
            Files.writeString(projectRoot.resolve(filename), twentyA);
            mockProjectFiles.add(new ProjectFile(projectRoot, filename));
        }

        String twentyB = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "B " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        String twentyC = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "C " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(projectRoot.resolve("budget25.txt"), twentyB + "\n" + twentyC);
        mockProjectFiles.add(new ProjectFile(projectRoot, "budget25.txt"));

        Files.writeString(projectRoot.resolve("budget26.txt"), twentyA);
        mockProjectFiles.add(new ProjectFile(projectRoot, "budget26.txt"));

        String result = searchTools.searchFileContents(List.of("A", "B", "C"), "budget*.txt", false, false, 0, 999);
        String mainSection = mainResultSection(result);

        assertTrue(mainSection.contains("<file path=\"budget25.txt\" loc=\"40\">"));
        assertTrue(mainSection.contains("21: C 1"));
        assertFalse(
                mainSection.contains("budget26.txt"),
                "Should stop before the next file after crossing the 500-match limit");
        assertTrue(result.contains("TRUNCATED: reached global limit of 500 total matches"));
    }

    @Test
    void testSearchFileContents_NoSpuriousTruncationOnExactMatchLimit() throws Exception {
        Path txt = projectRoot.resolve("exact_limit.txt");
        // Exactly 3 lines, all matching.
        Files.writeString(txt, "MATCH 1\nMATCH 2\nMATCH 3");
        mockProjectFiles.add(new ProjectFile(projectRoot, "exact_limit.txt"));

        // Ask for exactly 3 matches.
        String result = searchTools.searchFileContents(List.of("MATCH"), "exact_limit.txt", false, false, 0, 10);

        assertTrue(result.contains("<file path=\"exact_limit.txt\" loc=\"3\">"), "Should show file metadata");
        assertTrue(result.contains("3: MATCH 3"), "Should contain the last match");
        assertFalse(
                result.contains("TRUNCATED: reached matchesPerFile"),
                "Should NOT contain the old-style truncation message line");
    }

    @Test
    void testSearchFileContents_NoSpuriousTrailingEmptyLine() throws Exception {
        Path txt = projectRoot.resolve("trailing_newline.txt");
        // File ends with a newline. With 1 context line, we should NOT see a "4: " line.
        Files.writeString(txt, "L1\nL2 MATCH\nL3\n");
        mockProjectFiles.add(new ProjectFile(projectRoot, "trailing_newline.txt"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "trailing_newline.txt", false, false, 1, 10);

        assertTrue(result.contains("1: L1"), "Should include context above");
        assertTrue(result.contains("2: L2 MATCH"), "Should include match");
        assertTrue(result.contains("3: L3"), "Should include context below");
        assertFalse(result.contains("4: "), "Should NOT include a spurious 4th line due to trailing newline");
    }

    @Test
    void testSearchFileContents_TruncatesLongLines() throws Exception {
        Path txt = projectRoot.resolve("long_line.txt");
        String longTail = "a".repeat(5000);
        Files.writeString(txt, "MATCH " + longTail);
        mockProjectFiles.add(new ProjectFile(projectRoot, "long_line.txt"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "long_line.txt", false, false, 0, 200);

        assertTrue(result.contains("1: MATCH "), "Should include matching line");
        assertTrue(result.contains("[TRUNCATED"), "Should truncate very long lines");
    }

    @Test
    void testSearchFileContents_GlobRecursiveJava() throws Exception {
        Path nestedDir = projectRoot.resolve("src/main/java");
        Files.createDirectories(nestedDir);
        Path file = nestedDir.resolve("Nested.java");
        Files.writeString(file, "class Nested { String token = \"MATCH\"; }");
        mockProjectFiles.add(new ProjectFile(projectRoot, "src/main/java/Nested.java"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "**/*.java", false, false, 0, 200);
        assertTrue(result.contains("src/main/java/Nested.java"), "Should find nested Java file with recursive glob");
    }

    @Test
    void testSearchFileContents_GlobBackslashInput() throws Exception {
        Path nestedDir = projectRoot.resolve("a/b");
        Files.createDirectories(nestedDir);
        Path file = nestedDir.resolve("Backslash.java");
        Files.writeString(file, "class Backslash { String token = \"MATCH\"; }");
        mockProjectFiles.add(new ProjectFile(projectRoot, "a/b/Backslash.java"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "a\\**\\*.java", false, false, 0, 200);
        assertTrue(
                result.contains("a/b/Backslash.java"), "Should find nested file even when input glob uses backslashes");
    }

    @Test
    void testSearchFileContents_LiteralSubpathGlob() throws Exception {
        Path subDir = projectRoot.resolve("dir");
        Files.createDirectories(subDir);
        Path file = subDir.resolve("file.txt");
        Files.writeString(file, "MATCH");
        mockProjectFiles.add(new ProjectFile(projectRoot, "dir/file.txt"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "dir/file.txt", false, false, 0, 200);
        assertTrue(result.contains("dir/file.txt"), "Should find file in subdirectory using literal subpath");
    }

    @Test
    void testSearchFileContents_MultiplePatternsSameLine_Deduped() throws Exception {
        Path txt = projectRoot.resolve("multi_pattern_dedupe.txt");
        Files.writeString(txt, "alpha beta gamma");
        mockProjectFiles.add(new ProjectFile(projectRoot, "multi_pattern_dedupe.txt"));

        String result = searchTools.searchFileContents(
                List.of("alpha", "beta", "gamma"), "multi_pattern_dedupe.txt", false, false, 0, 200);

        assertEquals(
                1,
                countOccurrences(result, "1: alpha beta gamma"),
                "Line should be emitted once after combining matches from all patterns");
    }

    @Test
    void testSearchFileContents_SearchTypeClassifiesAnalyzedFiles() throws Exception {
        Path nestedDir = projectRoot.resolve("src/main/java/com/example");
        Files.createDirectories(nestedDir);
        Path file = nestedDir.resolve("Foo.java");
        Files.writeString(
                file,
                """
                package com.example;
                import java.util.List;
                class Foo {
                    String before = "before";
                    List<String> values;
                    Foo useFoo(Foo other) {
                        Foo local = other;
                        return local;
                    }
                    String after = "after";
                }
                """
                        .stripIndent());

        ProjectFile projectFile = new ProjectFile(projectRoot, "src/main/java/com/example/Foo.java");
        mockProjectFiles.add(projectFile);

        CodeUnit cls = CodeUnit.cls(projectFile, "com.example", "Foo");
        CodeUnit field = CodeUnit.field(projectFile, "com.example", "Foo.values");
        CodeUnit method = CodeUnit.fn(projectFile, "com.example", "Foo.useFoo");

        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(cls);
        analyzer.addDeclaration(field);
        analyzer.addDeclaration(method);
        analyzer.setRanges(cls, List.of(new Range(0, 0, 2, 10, 0)));
        analyzer.setRanges(field, List.of(new Range(0, 0, 4, 4, 0)));
        analyzer.setRanges(method, List.of(new Range(0, 0, 5, 8, 0)));
        analyzer.setImportStatements(projectFile, List.of("import java.util.List;"));

        SearchTools tools = new SearchTools(new TestContextManager(
                new TestProject(projectRoot, Languages.JAVA).withAllFilesSupplier(() -> mockProjectFiles),
                new TestConsoleIO(),
                Set.of(),
                analyzer,
                repo));

        String declarations =
                tools.searchFileContents(List.of("Foo", "List"), "**/*.java", "declarations", false, false, 0, 20);
        assertTrue(declarations.contains("[DECLARATIONS]"));
        assertTrue(declarations.contains("3: class Foo {"));
        assertTrue(declarations.contains("5:     List<String> values;"));
        assertTrue(declarations.contains("6:     Foo useFoo(Foo other) {"));
        assertFalse(declarations.contains("2: import java.util.List;"));
        assertFalse(declarations.contains("7:         Foo local = other;"));

        String usages = tools.searchFileContents(List.of("Foo"), "**/*.java", "usages", false, false, 2, 20);
        assertTrue(usages.contains("[USAGES]"));
        assertTrue(usages.contains("Foo::useFoo [6..9]"));
        assertTrue(usages.contains("6:     Foo useFoo(Foo other) {"));
        assertTrue(usages.contains("7:         Foo local = other;"));
        assertTrue(usages.contains("8:         return local;"));
        assertTrue(usages.contains("9:     }"));
        assertFalse(usages.contains("3: class Foo {"));
        assertFalse(usages.contains("5:     List<String> values;"));
        assertFalse(usages.contains("10:     String after = \"after\";"));

        String all = tools.searchFileContents(List.of("Foo", "List"), "**/*.java", "all", false, false, 0, 20);
        assertTrue(all.contains("<matches>"));
        assertTrue(all.contains("<related>"));
        assertTrue(all.contains("[DECLARATIONS]"));
        assertTrue(all.contains("[USAGES]"));
        assertTrue(all.contains("Foo::useFoo [6..9]"));
        assertTrue(all.contains("2: import java.util.List;"));
    }

    @Test
    void testJq_ProductBudgetClampsMatchesPerFile() throws Exception {
        Path json = projectRoot.resolve("budget.json");
        Files.writeString(json, "{\"a\": [{\"id\": 1001}, {\"id\": 1002}]}");
        mockProjectFiles.add(new ProjectFile(projectRoot, "budget.json"));

        // product budget: maxFiles=100 and matchesPerFile=10 => clamped matchesPerFile becomes 5
        String result = searchTools.jq("budget.json", ".a[] | .id", 100, 10);

        assertTrue(result.contains("1001"), "Should include first output");
        assertTrue(result.contains("1002"), "Should still include second output");
    }

    @Test
    void testXmlSelect_NamespaceAgnostic_RewritesLocalNames() throws Exception {
        Path xml = projectRoot.resolve("ns.xml");
        Files.writeString(
                xml,
                """
                <ns:root xmlns:ns="urn:test">
                  <ns:item id="a">hello</ns:item>
                  <ns:item id="b"><ns:sub>world</ns:sub></ns:item>
                </ns:root>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "ns.xml"));

        // Without local-name() rewriting, /root/item would not match prefixed elements.
        String result = searchTools.xmlSelect("ns.xml", "/root/item", "TEXT", "", 10, 10);

        assertTrue(result.contains("hello"), "Should extract text for first item. Result:\n" + result);
        assertTrue(result.contains("world"), "Should extract descendant text for second item. Result:\n" + result);
    }

    @Test
    void testXmlSelect_AttrsJsonl() throws Exception {
        Path xml = projectRoot.resolve("attrs.xml");
        Files.writeString(
                xml,
                """
                <ns:root xmlns:ns="urn:test">
                  <ns:item id="a" scope="test">hello</ns:item>
                </ns:root>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "attrs.xml"));

        String result = searchTools.xmlSelect("attrs.xml", "//item", "ATTRS", "", 10, 10);

        ObjectMapper mapper = new ObjectMapper();
        List<String> jsonLines = result.lines().filter(l -> l.startsWith("{")).toList();
        assertFalse(jsonLines.isEmpty(), "Should contain JSONL lines. Result:\n" + result);

        JsonNode node = mapper.readTree(jsonLines.getFirst());
        assertEquals("item", node.get("name").asText());
        assertEquals("a", node.get("attrs").get("id").asText());
        assertEquals("test", node.get("attrs").get("scope").asText());
        assertTrue(node.get("path").asText().contains("/root[1]/item[1]"));
    }

    @Test
    void testXmlSelect_XmlFallsBackToSkimWhenTooLarge() throws Exception {
        Path xml = projectRoot.resolve("long.xml");
        String bigText = "a".repeat(2100);
        Files.writeString(
                xml,
                """
                <ns:root xmlns:ns="urn:test">
                  <ns:big>%s</ns:big>
                </ns:root>
                """
                        .stripIndent()
                        .formatted(bigText));
        mockProjectFiles.add(new ProjectFile(projectRoot, "long.xml"));

        String result = searchTools.xmlSelect("long.xml", "//big", "XML", "", 10, 10);

        assertTrue(result.contains("[XML_TOO_LARGE]"), "Should indicate XML was too large. Result:\n" + result);
        assertTrue(result.contains("textLen="), "Fallback skim should include textLen. Result:\n" + result);
        assertFalse(result.contains("a".repeat(200)), "Should not dump the huge text content. Result:\n" + result);
    }

    @Test
    void testXmlSkim_Basic() throws Exception {
        Path xml = projectRoot.resolve("skim.xml");
        Files.writeString(
                xml,
                """
                <ns:root xmlns:ns="urn:test">
                  <ns:item id="a">hello</ns:item>
                  <ns:item id="b">world</ns:item>
                </ns:root>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "skim.xml"));

        String result = searchTools.xmlSkim("skim.xml", 10);

        assertTrue(result.contains("<file path=\"skim.xml\">"), "Should include file wrapper. Result:\n" + result);
        assertTrue(result.contains("children={item:2}"), "Should include child histogram. Result:\n" + result);
        assertTrue(result.contains("textLen="), "Should include textLen. Result:\n" + result);
        assertTrue(result.contains("attrs={id=\"a\"}"), "Should include attrs. Result:\n" + result);
    }

    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }

    private static String mainResultSection(String text) {
        int relatedContentIdx = text.indexOf("\n\n## Related Content\n");
        return relatedContentIdx >= 0 ? text.substring(0, relatedContentIdx) : text;
    }

    private static String relatedContentSection(String text) {
        int relatedContentIdx = text.indexOf("\n\n## Related Content\n");
        return relatedContentIdx >= 0 ? text.substring(relatedContentIdx) : "";
    }
}
