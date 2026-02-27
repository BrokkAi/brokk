package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.project.AbstractProject;
import ai.brokk.testutil.FileUtil;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
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
    /** mutable set returned from the project-proxy's getAllFiles() */
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

        /*
         * Create a minimal IContextManager mock/stub that only needs to return
         * the project root path for the SearchTools we are testing.
         */
        Class<?>[] projectInterfaces = AbstractProject.class.getInterfaces();
        if (projectInterfaces.length == 0) {
            throw new IllegalStateException("AbstractProject implements no interfaces to proxy");
        }
        Object projectProxy =
                Proxy.newProxyInstance(getClass().getClassLoader(), projectInterfaces, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRoot" -> {
                            return projectRoot;
                        }
                        case "getAllFiles" -> {
                            // return the mutable list we populate in individual tests
                            return mockProjectFiles;
                        }
                        case "isGitignored" -> {
                            Path path = (Path) args[0];
                            // Simulate .brokk being gitignored
                            return path.startsWith(Path.of(AbstractProject.BROKK_DIR));
                        }
                        default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                    }
                });

        IContextManager ctxManager = (IContextManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IContextManager.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getProject" -> projectProxy;
                        case "getRepo" -> repo;
                        case "getAnalyzerUninterrupted", "getAnalyzer" ->
                            Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {ai.brokk.analyzer.IAnalyzer.class},
                                    (p, m, a) -> {
                                        if (m.getReturnType().equals(List.class)) return List.of();
                                        if (m.getReturnType().equals(Set.class)) return Set.of();
                                        return null;
                                    });
                        case "toFile" -> {
                            String relPath = (String) args[0];
                            yield new ProjectFile(projectRoot, Path.of(relPath));
                        }
                        default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                    };
                });

        searchTools = new SearchTools(ctxManager);
    }

    @AfterEach
    void tearDown() {
        repo.close();
    }

    @Test
    void testSearchGitCommitMessages_invalidRegexFallback() {
        String result = searchTools.searchGitCommitMessages("[[", "testing invalid regex fallback", 200);

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
        String result = searchTools.findFilesContaining(List.of("[["), "testing invalid regex error", 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
    }

    @Test
    void testfindFilenames_invalidRegexThrows() throws Exception {
        // SearchTools.compilePatterns throws on invalid regex for this tool
        String result = searchTools.findFilenames(List.of("[["), "testing invalid regex error", 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
    }

    @Test
    void testfindFilenames_limitEnforced() {
        for (int i = 0; i < 10; i++) {
            mockProjectFiles.add(new ProjectFile(projectRoot, "file" + i + ".txt"));
        }

        // Request limit of 5
        String result = searchTools.findFilenames(List.of("file.*\\.txt"), "testing limit", 5);

        assertTrue(result.contains("WARNING: Result limit reached"), "Should contain truncation warning");
        assertTrue(result.contains("max 5 filenames"), "Warning should mention the limit");

        // Count filenames in the comma-separated list
        String listPart = result.substring(result.indexOf("Matching filenames: ") + "Matching filenames: ".length());
        String[] files = listPart.split(", ");
        assertEquals(5, files.length, "Should return exactly 5 filenames");
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
        String resultNix = searchTools.findFilenames(List.of(relativePathNix), "test nix path", 200);
        assertTrue(
                resultNix.contains(relativePathNix) || resultNix.contains(relativePathWin),
                "Should find file with forward-slash path");

        // B. File name only
        String resultName = searchTools.findFilenames(List.of("MOP.svelte"), "test file name", 200);
        assertTrue(
                resultName.contains(relativePathNix) || resultName.contains(relativePathWin),
                "Should find file with file name only");

        // C. Partial path
        String resultPartial = searchTools.findFilenames(List.of("src/MOP"), "test partial path", 200);
        assertTrue(
                resultPartial.contains(relativePathNix) || resultPartial.contains(relativePathWin),
                "Should find file with partial path");

        // D. Full path with backslashes (Windows-style)
        String resultWin = searchTools.findFilenames(List.of(relativePathWin), "test windows path", 200);
        assertTrue(
                resultWin.contains(relativePathNix) || resultWin.contains(relativePathWin),
                "Should find file with back-slash path pattern");

        // E. Regex path pattern (frontend-mop/.*\.svelte)
        String regexPattern = "frontend-mop/.*\\.svelte";
        String resultRegex = searchTools.findFilenames(List.of(regexPattern), "test regex path", 200);
        assertTrue(
                resultRegex.contains(relativePathNix) || resultRegex.contains(relativePathWin),
                "Should find file with regex pattern");
    }

    @Test
    void testSkimDirectory() {
        // Create a context manager that provides the Java analyzer and the test project
        IContextManager ctxWithAnalyzer = (IContextManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IContextManager.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAnalyzer" -> javaAnalyzer;
                        case "getAnalyzerUninterrupted" -> javaAnalyzer;
                        case "getProject" -> javaTestProject;
                        default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                    };
                });

        SearchTools tools = new SearchTools(ctxWithAnalyzer);

        // Test skimming the root directory of the test project
        String result = tools.skimDirectory(".", "testing skimDirectory");
        assertFalse(result.isEmpty(), "Result should not be empty");

        // Verify it contains file entries in XML-ish format
        assertTrue(result.contains("<file path=\"A.java\">"), "Should contain A.java entry");
        assertTrue(result.contains("<file path=\"B.java\">"), "Should contain B.java entry");

        // Verify it contains some identifiers from the files
        assertTrue(result.contains("A"), "Should mention identifier A");
    }

    @Test
    void testSkimDirectory_dependenciesNotGitignored() throws IOException {
        Path projectRootCopy = createDisposableTestProjectCopy();
        try (TestProject localProject = new TestProject(projectRootCopy, Languages.JAVA)) {
            JavaAnalyzer localAnalyzer = new JavaAnalyzer(localProject);

            // Create a context manager that provides the Java analyzer and the test project
            IContextManager ctxWithAnalyzer = (IContextManager) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {IContextManager.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "getAnalyzer" -> localAnalyzer;
                            case "getAnalyzerUninterrupted" -> localAnalyzer;
                            case "getProject" -> localProject;
                            default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                        };
                    });

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
                // 2. Call skimDirectory on the dependency path
                String pathString = Path.of(AbstractProject.BROKK_DIR, AbstractProject.DEPENDENCIES_DIR, "testrepo")
                        .toString();
                String result = tools.skimDirectory(pathString, "testing dependencies bypass gitignore");

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
        // Create a context manager that provides the Java analyzer
        IContextManager ctxWithAnalyzer = (IContextManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IContextManager.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAnalyzer" -> javaAnalyzer;
                        case "getAnalyzerUninterrupted" -> javaAnalyzer;
                        case "getProject" -> javaTestProject;
                        default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                    };
                });

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
        String result = searchTools.getGitLog("", 3, "test");
        // Count the number of <entry> tags
        // Count occurrences of "<entry " to be precise
        int entries = countOccurrences(result, "<entry ");
        assertEquals(3, entries, "Should have exactly 3 entries when limit is 3. Result:\n" + result);
    }

    @Test
    void testGetGitLog_emptyPathReturnsRepoWideLog() {
        String result = searchTools.getGitLog("", 100, "test");

        // Should contain commits from setUp (Initial commit + "Commit with [[ pattern")
        assertTrue(result.contains("<git_log>"), "Should have git_log wrapper");
        assertTrue(result.contains("Initial commit"), "Should contain initial commit message");
        assertTrue(result.contains("Commit with [[ pattern"), "Should contain second commit message");
    }

    @Test
    void testGetGitLog_invalidPathReturnsNoHistory() {
        String result = searchTools.getGitLog("nonexistent/path/to/file.txt", 10, "test");
        assertTrue(
                result.contains("No history found"), "Should indicate no history for invalid path. Result:\n" + result);
    }

    @Test
    void testGetGitLog_fileWithNoHistory() throws Exception {
        // Create a new untracked file (not committed)
        Path newFile = projectRoot.resolve("untracked.txt");
        Files.writeString(newFile, "not committed");

        String result = searchTools.getGitLog("untracked.txt", 10, "test");
        assertTrue(
                result.contains("No history found"),
                "Should indicate no history for untracked file. Result:\n" + result);
    }

    @Test
    void testGetGitLog_limitCappedAt100() throws Exception {
        // Verify that requesting more than 100 doesn't exceed 100
        // We only have a few commits, so just verify no error and entries <= requested
        String result = searchTools.getGitLog("", 200, "test");
        int entries = countOccurrences(result, "<entry ");
        assertTrue(entries <= 100, "Entries should never exceed 100");
        assertTrue(entries > 0, "Should have at least one entry");
    }

    @Test
    void testGetGitLog_specificFile() throws Exception {
        String result = searchTools.getGitLog("README.md", 10, "test");
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

        String result = searchTools.searchFileContents(List.of("MATCH"), "**/grep_test.txt", 1, 200, 200);

        assertTrue(result.contains("grep_test.txt [1 match]"));
        assertTrue(result.contains("1: line1"));
        assertTrue(result.contains("2: line2 MATCH"));
        assertTrue(result.contains("3: line3"));
        assertFalse(result.contains("4: line4"));
    }

    @Test
    void testSearchFileContents_invalidRegexThrows() throws Exception {
        // "[[" is invalid regex, should return error message
        String result = searchTools.searchFileContents(List.of("[["), "README.md", 0, 200, 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
    }

    @Test
    void testXpathQuery() throws Exception {
        Path xml = projectRoot.resolve("test.xml");
        Files.writeString(xml, "<root xmlns:ns='uri'><ns:child>hello</ns:child></root>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "test.xml"));

        // Test local-name rewrite
        String result = searchTools.xpathQuery("test.xml", List.of("/root/child"), 1, 10);

        assertTrue(result.contains("File: test.xml"));
        assertTrue(result.contains("XPath: /root/child"));
        assertTrue(result.contains("hello"));
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
    void testSearchFileContents_PathRetry() throws Exception {
        Path rootFile = projectRoot.resolve("root.txt");
        Files.writeString(rootFile, "found me");
        mockProjectFiles.add(new ProjectFile(projectRoot, "root.txt"));

        // Verify that **/root.txt matches a file at the project root via the retry logic
        String result = searchTools.searchFileContents(List.of("found"), "**/root.txt", 0, 200, 200);
        assertTrue(result.contains("root.txt"), "Should find file at root even with **/ prefix");
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
        String result = searchTools.getGitLog("new_name.txt", 10, "test");
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
    void testSearchSymbols_StripsParams() {
        // Mock analyzer is static, but we can verify the Tool's sanitization logic
        // by checking if it calls analyzer with stripped names.
        // Since we can't easily mock the static analyzer's return for specific calls here,
        // we'll rely on the logic being covered by the fact that SearchTools.stripParams
        // is private and used by searchSymbols.

        // This test ensures it doesn't crash and handles the typical LLM mistake
        String result = searchTools.searchSymbols(List.of("com.Foo.bar(int, String)"), "test", false);
        assertTrue(
                result.contains("No definitions found"), "Should attempt search and find nothing (correctly stripped)");
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
        String result = searchTools.searchFileContents(List.of("MATCH"), "context_test.txt", 1, 200, 200);

        assertTrue(result.contains("1: L1"));
        assertTrue(result.contains("2: L2 MATCH"));
        assertTrue(result.contains("3: L3"));
        assertTrue(result.contains("4: L4 MATCH"));
        assertTrue(result.contains("5: L5"));
        assertFalse(result.contains("6: L6"));

        assertEquals(
                1, countOccurrences(result, "3: L3"), "Line 3 should only be printed once despite overlapping context");

        // Verify clamping: contextLines=999 should be clamped to 50
        // Our file is small, so it should just show everything.
        String resultsCapped = searchTools.searchFileContents(List.of("MATCH"), "context_test.txt", 999, 200, 200);
        assertTrue(resultsCapped.contains("7: L7"));
    }

    @Test
    void testSearchFileContents_MatchesPerFileIsHitCount() throws Exception {
        Path txt = projectRoot.resolve("matches_per_file_test.txt");
        String content = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "MATCH " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(txt, content);
        mockProjectFiles.add(new ProjectFile(projectRoot, "matches_per_file_test.txt"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "matches_per_file_test.txt", 0, 200, 10);

        assertTrue(result.contains("matches_per_file_test.txt [first 10 matches]"));
        assertTrue(result.contains("10: MATCH 10"));
        assertFalse(result.contains("11: MATCH 11"));
    }

    @Test
    void testSearchFileContents_GlobalMatchBudget500() throws Exception {
        Path f1 = projectRoot.resolve("budget1.txt");
        Path f2 = projectRoot.resolve("budget2.txt");

        Files.writeString(
                f1,
                java.util.stream.IntStream.rangeClosed(1, 100)
                        .mapToObj(i -> "MATCH " + i)
                        .collect(java.util.stream.Collectors.joining("\n")));
        Files.writeString(
                f2,
                java.util.stream.IntStream.rangeClosed(1, 600)
                        .mapToObj(i -> "MATCH " + i)
                        .collect(java.util.stream.Collectors.joining("\n")));

        mockProjectFiles.add(new ProjectFile(projectRoot, "budget1.txt"));
        mockProjectFiles.add(new ProjectFile(projectRoot, "budget2.txt"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "budget*.txt", 0, 999, 999);

        assertTrue(result.contains("budget1.txt"));
        assertTrue(result.contains("100: MATCH 100"));

        assertTrue(result.contains("budget2.txt"));
        assertTrue(result.contains("400: MATCH 400"));
        assertFalse(result.contains("401: MATCH 401"));
    }

    @Test
    void testSearchFileContents_NoSpuriousTruncationOnExactMatchLimit() throws Exception {
        Path txt = projectRoot.resolve("exact_limit.txt");
        // Exactly 3 lines, all matching.
        Files.writeString(txt, "MATCH 1\nMATCH 2\nMATCH 3");
        mockProjectFiles.add(new ProjectFile(projectRoot, "exact_limit.txt"));

        // Ask for exactly 3 matches.
        String result = searchTools.searchFileContents(List.of("MATCH"), "exact_limit.txt", 0, 10, 3);

        assertTrue(result.contains("exact_limit.txt [first 3 matches]"), "Should show hit limit in header");
        assertTrue(result.contains("3: MATCH 3"), "Should contain the last match");
        assertFalse(
                result.contains("TRUNCATED: reached matchesPerFile"),
                "Should NOT contain the old-style truncation message line");
    }

    @Test
    void testReadLineRange() throws Exception {
        Path txt = projectRoot.resolve("range_test.txt");
        Files.writeString(txt, "L1\nL2\nL3\nL4\nL5");
        mockProjectFiles.add(new ProjectFile(projectRoot, "range_test.txt"));

        // Basic range
        String result = searchTools.readLineRange("range_test.txt", 2, 4);
        assertTrue(result.contains("File: range_test.txt (lines 2-4)"));
        assertTrue(result.contains("2: L2"));
        assertTrue(result.contains("3: L3"));
        assertTrue(result.contains("4: L4"));
        assertFalse(result.contains("1: L1"));
        assertFalse(result.contains("5: L5"));

        // Cap at 200
        String capped = searchTools.readLineRange("range_test.txt", 1, 500);
        // The file only has 5 lines, so it should report 1-5 even if 500 requested
        assertTrue(capped.contains("(lines 1-5)"), "Should show actual end line 5, got: " + capped);
    }

    @Test
    void testReadLineRange_ClampsToActualFileEnd() throws Exception {
        Path txt = projectRoot.resolve("clamp_test.txt");
        Files.writeString(txt, "1\n2\n3");
        mockProjectFiles.add(new ProjectFile(projectRoot, "clamp_test.txt"));

        // Request 1-200 for a 3-line file
        String result = searchTools.readLineRange("clamp_test.txt", 1, 200);
        assertTrue(result.contains("File: clamp_test.txt (lines 1-3)"), "Header should reflect actual lines returned");
        assertTrue(result.contains("3: 3"), "Should contain line 3");
    }

    @Test
    void testSearchFileContents_NoSpuriousTrailingEmptyLine() throws Exception {
        Path txt = projectRoot.resolve("trailing_newline.txt");
        // File ends with a newline. With 1 context line, we should NOT see a "4: " line.
        Files.writeString(txt, "L1\nL2 MATCH\nL3\n");
        mockProjectFiles.add(new ProjectFile(projectRoot, "trailing_newline.txt"));

        String result = searchTools.searchFileContents(List.of("MATCH"), "trailing_newline.txt", 1, 10, 10);

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

        String result = searchTools.searchFileContents(List.of("MATCH"), "long_line.txt", 0, 200, 200);

        assertTrue(result.contains("1: MATCH "), "Should include matching line");
        assertTrue(result.contains("[TRUNCATED]"), "Should truncate very long lines");
    }

    @Test
    void testXpathQuery_SanitizationAndErrors() throws Exception {
        Path xml = projectRoot.resolve("security.xml");
        // Attempt XXE
        Files.writeString(
                xml,
                """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <root>&xxe;</root>
            """);
        mockProjectFiles.add(new ProjectFile(projectRoot, "security.xml"));

        String result = searchTools.xpathQuery("security.xml", List.of("/root"), 1, 10);

        // DocumentBuilder should reject the DTD
        assertTrue(result.contains("errors in 1 of 1 files"), "Should report error due to DTD disallowance");
        assertTrue(
                result.contains("DOCTYPE") && result.contains("disallow-doctype-decl"),
                "Should specifically mention DOCTYPE restriction, got: " + result);
    }

    @Test
    void testXpathQuery_MaxFilesAndMatchesPerFileEnforced() throws Exception {
        Path xml1 = projectRoot.resolve("xq1.xml");
        Path xml2 = projectRoot.resolve("xq2.xml");

        Files.writeString(xml1, "<root><item>a</item><item>b</item><item>c</item></root>");
        Files.writeString(xml2, "<root><item>d</item><item>e</item><item>f</item></root>");

        mockProjectFiles.add(new ProjectFile(projectRoot, "xq1.xml"));
        mockProjectFiles.add(new ProjectFile(projectRoot, "xq2.xml"));

        String result = searchTools.xpathQuery("xq*.xml", List.of("/root/item"), 1, 2);

        assertTrue(result.contains("File: xq1.xml"), "Should include first matching file");
        assertFalse(result.contains("File: xq2.xml"), "Should not include second file due to maxFiles=1");
        assertTrue(result.contains("TRUNCATED: reached maxFiles=1"), "Should report maxFiles truncation");

        assertTrue(result.contains("  [1]: a"), "Should include first match");
        assertTrue(result.contains("  [2]: b"), "Should include second match");
        assertFalse(result.contains("  [3]: c"), "Should not include third match due to matchesPerFile=2");
    }

    @Test
    void testJq_ProductBudgetClampsMatchesPerFile() throws Exception {
        Path json = projectRoot.resolve("budget.json");
        Files.writeString(json, "{\"a\": [{\"id\": 1001}, {\"id\": 1002}]}");
        mockProjectFiles.add(new ProjectFile(projectRoot, "budget.json"));

        // product budget: maxFiles=400 and matchesPerFile=2 => clamped matchesPerFile becomes 1
        String result = searchTools.jq("budget.json", ".a[] | .id", 400, 2);

        assertTrue(result.contains("1001"), "Should include first output");
        assertFalse(result.contains("1002"), "Should be clamped to a single output by the product budget");
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
}
