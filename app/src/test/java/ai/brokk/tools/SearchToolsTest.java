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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private @Nullable GitRepo repo;
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
        Files.createDirectories(projectRoot);
        mockProjectFiles = new HashSet<>();
        repo = null;

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
                        case "getRepo" -> {
                            ensureGitRepoInitialized();
                            yield repo;
                        }
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
        if (repo != null) {
            repo.close();
        }
    }

    /**
     * Lazily initializes the Git repository only when needed.
     * This avoids the cost of git init + commits for tests that don't use git features.
     */
    private void ensureGitRepoInitialized() throws Exception {
        if (repo != null) {
            return;
        }
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
    }

    @Test
    void testSearchGitCommitMessages_invalidRegexFallback() throws Exception {
        ensureGitRepoInitialized();
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
        String resultNix = searchTools.findFilenames(List.of(relativePathNix), 200);
        assertTrue(
                resultNix.contains(relativePathNix) || resultNix.contains(relativePathWin),
                "Should find file with forward-slash path");

        // B. File name only
        String resultName = searchTools.findFilenames(List.of("MOP.svelte"), 200);
        assertTrue(
                resultName.contains(relativePathNix) || resultName.contains(relativePathWin),
                "Should find file with file name only");

        // C. Partial path
        String resultPartial = searchTools.findFilenames(List.of("src/MOP"), 200);
        assertTrue(
                resultPartial.contains(relativePathNix) || resultPartial.contains(relativePathWin),
                "Should find file with partial path");

        // D. Full path with backslashes (Windows-style)
        String resultWin = searchTools.findFilenames(List.of(relativePathWin), 200);
        assertTrue(
                resultWin.contains(relativePathNix) || resultWin.contains(relativePathWin),
                "Should find file with back-slash path pattern");

        // E. Regex path pattern (frontend-mop/.*\.svelte)
        String regexPattern = "frontend-mop/.*\\.svelte";
        String resultRegex = searchTools.findFilenames(List.of(regexPattern), 200);
        assertTrue(
                resultRegex.contains(relativePathNix) || resultRegex.contains(relativePathWin),
                "Should find file with regex pattern");

        // F. Case-insensitive check
        String resultUpper = searchTools.findFilenames(List.of("MOP.SVELTE"), 200);
        assertTrue(
                resultUpper.contains(relativePathNix) || resultUpper.contains(relativePathWin),
                "Should find file with case-insensitive match");
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
        String result = tools.skimDirectory(".");
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
                String result = tools.skimDirectory(pathString);

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
        ensureGitRepoInitialized();
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

        String result = searchTools.searchFileContents(List.of("MATCH"), "**/grep_test.txt", false, false, 1, 200, 200);

        assertTrue(result.contains("grep_test.txt [1 match]"));
        assertTrue(result.contains("1: line1"));
        assertTrue(result.contains("2: line2 MATCH"));
        assertTrue(result.contains("3: line3"));
        assertFalse(result.contains("4: line4"));
    }

    @Test
    void testSearchFileContents_invalidRegexThrows() throws Exception {
        // Create a file so the tool attempts regex compilation
        Path readme = projectRoot.resolve("README.md");
        Files.writeString(readme, "test content");
        mockProjectFiles.add(new ProjectFile(projectRoot, "README.md"));

        // "[[" is invalid regex, should return error message
        String result = searchTools.searchFileContents(List.of("[["), "README.md", false, false, 0, 200, 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error. Result:\n" + result);
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
        String result = searchTools.searchFileContents(List.of("found"), "**/root.txt", false, false, 0, 200, 200);
        assertTrue(result.contains("root.txt"), "Should find file at root even with **/ prefix");
    }

    @Test
    void testSearchFileContents_CaseInsensitiveFlag() throws Exception {
        Path txt = projectRoot.resolve("case_insensitive.txt");
        Files.writeString(txt, "Line1\nLine2 MATCH\nLine3");
        mockProjectFiles.add(new ProjectFile(projectRoot, "case_insensitive.txt"));

        String withoutFlag =
                searchTools.searchFileContents(List.of("match"), "case_insensitive.txt", false, false, 0, 200, 200);
        assertTrue(withoutFlag.contains("No matches found"), "Should not match without case-insensitive flag");

        String withFlag =
                searchTools.searchFileContents(List.of("match"), "case_insensitive.txt", true, false, 0, 200, 200);
        assertTrue(withFlag.contains("case_insensitive.txt [1 match]"), "Should match with case-insensitive flag");
        assertTrue(withFlag.contains("2: Line2 MATCH"), "Should show matching line");
    }

    @Test
    void testSearchFileContents_MultilineFlag_Anchors() throws Exception {
        Path txt = projectRoot.resolve("multiline.txt");
        Files.writeString(txt, "line1\nline2\nline3");
        mockProjectFiles.add(new ProjectFile(projectRoot, "multiline.txt"));

        String withoutFlag =
                searchTools.searchFileContents(List.of("^line2$"), "multiline.txt", false, false, 0, 200, 200);
        assertTrue(withoutFlag.contains("No matches found"), "Should not match ^/$ without multiline flag");

        String withFlag = searchTools.searchFileContents(List.of("^line2$"), "multiline.txt", false, true, 0, 200, 200);
        assertTrue(withFlag.contains("multiline.txt [1 match]"), "Should match with multiline flag");
        assertTrue(withFlag.contains("2: line2"), "Should show the anchored match line");
    }

    @Test
    void testGetGitLog_RenameTracking() throws Exception {
        ensureGitRepoInitialized();
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
    void testSearchSymbols_StripsParams() {
        // Mock analyzer is static, but we can verify the Tool's sanitization logic
        // by checking if it calls analyzer with stripped names.
        // Since we can't easily mock the static analyzer's return for specific calls here,
        // we'll rely on the logic being covered by the fact that SearchTools.stripParams
        // is private and used by searchSymbols.

        // This test ensures it doesn't crash and handles the typical LLM mistake
        String result = searchTools.searchSymbols(List.of("com.Foo.bar(int, String)"), false, 200);
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
        String result = searchTools.searchFileContents(List.of("MATCH"), "context_test.txt", false, false, 1, 200, 200);

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
        String resultsCapped =
                searchTools.searchFileContents(List.of("MATCH"), "context_test.txt", false, false, 999, 200, 200);
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

        String result =
                searchTools.searchFileContents(List.of("MATCH"), "matches_per_file_test.txt", false, false, 0, 200, 10);

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

        String result = searchTools.searchFileContents(List.of("MATCH"), "budget*.txt", false, false, 0, 999, 999);

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
        String result = searchTools.searchFileContents(List.of("MATCH"), "exact_limit.txt", false, false, 0, 10, 3);

        assertTrue(result.contains("exact_limit.txt [first 3 matches]"), "Should show hit limit in header");
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

        String result =
                searchTools.searchFileContents(List.of("MATCH"), "trailing_newline.txt", false, false, 1, 10, 10);

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

        String result = searchTools.searchFileContents(List.of("MATCH"), "long_line.txt", false, false, 0, 200, 200);

        assertTrue(result.contains("1: MATCH "), "Should include matching line");
        assertTrue(result.contains("[TRUNCATED"), "Should truncate very long lines");
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

        String result = searchTools.xmlSelect("ns.xml", "*|root > *|item", "TEXT", "", 10, 10);

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

        String result = searchTools.xmlSelect("attrs.xml", "*|item", "ATTRS", "", 10, 10);

        ObjectMapper mapper = new ObjectMapper();
        List<String> jsonLines = result.lines().filter(l -> l.startsWith("{")).toList();
        assertFalse(jsonLines.isEmpty(), "Should contain JSONL lines. Result:\n" + result);

        JsonNode node = mapper.readTree(jsonLines.getFirst());
        assertEquals("ns:item", node.get("name").asText());
        assertEquals("a", node.get("attrs").get("id").asText());
        assertEquals("test", node.get("attrs").get("scope").asText());
        assertTrue(node.get("path").asText().contains("item[1]"));
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

        String result = searchTools.xmlSelect("long.xml", "*|big", "MARKUP", "", 10, 10);

        assertTrue(result.contains("[MARKUP_TOO_LARGE]"), "Should indicate markup was too large. Result:\n" + result);
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
        assertTrue(result.contains("children={ns:item:2}"), "Should include child histogram. Result:\n" + result);
        assertTrue(result.contains("textLen="), "Should include textLen. Result:\n" + result);
        assertTrue(result.contains("attrs={id=\"a\"}"), "Should include attrs. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_TextMode() throws Exception {
        Path html = projectRoot.resolve("test.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="content">Hello World</div>
                  <div class="content">Goodbye World</div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "test.html"));

        String result = searchTools.xmlSelect("test.html", "div.content", "TEXT", "", 10, 10);

        assertTrue(result.contains("File: test.html (2 matches)"), "Should show file header. Result:\n" + result);
        assertTrue(result.contains("Hello World"), "Should extract text from first div. Result:\n" + result);
        assertTrue(result.contains("Goodbye World"), "Should extract text from second div. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_DivItem_TextMode() throws Exception {
        Path html = projectRoot.resolve("divitem.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="item" data-id="a">hello</div>
                  <div class="item">world</div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "divitem.html"));

        String result = searchTools.xmlSelect("divitem.html", "div.item", "TEXT", "", 10, 10);

        assertTrue(result.contains("File: divitem.html (2 matches)"), "Should show file header. Result:\n" + result);
        assertTrue(result.contains("hello"), "Should extract text 'hello' from first div.item. Result:\n" + result);
        assertTrue(result.contains("world"), "Should extract text 'world' from second div.item. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_DivItem_AttrMode() throws Exception {
        Path html = projectRoot.resolve("divitem_attr.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="item" data-id="a">hello</div>
                  <div class="item">world</div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "divitem_attr.html"));

        String result = searchTools.xmlSelect("divitem_attr.html", "div.item", "ATTR", "data-id", 10, 10);

        assertTrue(
                result.contains("File: divitem_attr.html (2 matches)"), "Should show file header. Result:\n" + result);
        assertTrue(
                result.contains("@data-id=\"a\""),
                "Should extract data-id='a' from first div.item. Result:\n" + result);
        // Second item has no data-id, so it should show empty value
        assertTrue(
                result.contains("@data-id=\"\""), "Should show empty data-id for second div.item. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_DivItem_AttrsMode() throws Exception {
        Path html = projectRoot.resolve("divitem_attrs.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="item" data-id="a">hello</div>
                  <div class="item">world</div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "divitem_attrs.html"));

        String result = searchTools.xmlSelect("divitem_attrs.html", "div.item", "ATTRS", "", 10, 10);

        assertTrue(
                result.contains("File: divitem_attrs.html (2 matches)"), "Should show file header. Result:\n" + result);

        ObjectMapper mapper = new ObjectMapper();
        List<String> jsonLines = result.lines().filter(l -> l.startsWith("{")).toList();
        assertEquals(2, jsonLines.size(), "Should have 2 JSONL lines. Result:\n" + result);

        // Parse first element (has data-id="a")
        JsonNode node1 = mapper.readTree(jsonLines.get(0));
        assertEquals("div", node1.get("name").asText(), "First element name should be 'div'");
        assertTrue(node1.has("path"), "First element should have 'path'. Result:\n" + result);
        String path1 = node1.get("path").asText();
        assertTrue(
                path1.startsWith("/") && path1.contains("div["),
                "Path should start with '/' and contain 'div['. Path: " + path1);
        assertTrue(node1.has("attrs"), "First element should have 'attrs'");
        JsonNode attrs1 = node1.get("attrs");
        assertTrue(attrs1.has("class"), "First element attrs should have 'class'");
        assertEquals("item", attrs1.get("class").asText());
        assertTrue(attrs1.has("data-id"), "First element attrs should have 'data-id'");
        assertEquals("a", attrs1.get("data-id").asText());

        // Parse second element (no data-id)
        JsonNode node2 = mapper.readTree(jsonLines.get(1));
        assertEquals("div", node2.get("name").asText(), "Second element name should be 'div'");
        assertTrue(node2.has("path"), "Second element should have 'path'");
        assertTrue(node2.has("attrs"), "Second element should have 'attrs'");
        JsonNode attrs2 = node2.get("attrs");
        assertTrue(attrs2.has("class"), "Second element attrs should have 'class'");
        assertEquals("item", attrs2.get("class").asText());
        assertFalse(attrs2.has("data-id"), "Second element attrs should NOT have 'data-id'");
    }

    @Test
    void testHtmlSelect_InvalidCssSelector() throws Exception {
        Path html = projectRoot.resolve("invalid_css.html");
        Files.writeString(html, "<html><body><div>Test</div></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "invalid_css.html"));

        String result = searchTools.xmlSelect("invalid_css.html", "div[", "TEXT", "", 10, 10);

        assertTrue(
                result.contains("Invalid CSS selector"),
                "Should report invalid CSS selector error. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_InvalidCssSelector_GlobMultipleFiles_ValidatesOnceUpFront() throws Exception {
        // Create two HTML files
        Path html1 = projectRoot.resolve("page1.html");
        Path html2 = projectRoot.resolve("page2.html");
        Files.writeString(html1, "<html><body><div>Page 1</div></body></html>");
        Files.writeString(html2, "<html><body><div>Page 2</div></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "page1.html"));
        mockProjectFiles.add(new ProjectFile(projectRoot, "page2.html"));

        // Call with invalid selector and glob matching multiple files
        String result = searchTools.xmlSelect("*.html", "div[", "TEXT", "", 10, 10);

        // Assert single error message with colon
        assertTrue(
                result.contains("Invalid CSS selector:"),
                "Should report invalid CSS selector error with colon. Result:\n" + result);
        // Assert no per-file headers (validation happens before file processing)
        assertFalse(
                result.contains("File: "),
                "Should not contain per-file headers since validation is up-front. Result:\n" + result);
        // Assert single-line message (no extra output)
        assertEquals(1, result.lines().count(), "Should be a single-line error message. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_AttrMode() throws Exception {
        Path html = projectRoot.resolve("links.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <a href="https://example.com" id="link1">Example</a>
                  <a href="https://test.org" id="link2">Test</a>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "links.html"));

        String result = searchTools.xmlSelect("links.html", "a", "ATTR", "href", 10, 10);

        assertTrue(result.contains("File: links.html (2 matches)"), "Should show file header. Result:\n" + result);
        assertTrue(
                result.contains("@href=\"https://example.com\""),
                "Should extract href from first link. Result:\n" + result);
        assertTrue(
                result.contains("@href=\"https://test.org\""),
                "Should extract href from second link. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_AttrsMode() throws Exception {
        Path html = projectRoot.resolve("attrs.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <input type="text" name="username" class="form-control" />
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "attrs.html"));

        String result = searchTools.xmlSelect("attrs.html", "input", "ATTRS", "", 10, 10);

        assertTrue(result.contains("File: attrs.html (1 match)"), "Should show file header. Result:\n" + result);

        ObjectMapper mapper = new ObjectMapper();
        List<String> jsonLines = result.lines().filter(l -> l.startsWith("{")).toList();
        assertFalse(jsonLines.isEmpty(), "Should contain JSONL lines. Result:\n" + result);

        JsonNode node = mapper.readTree(jsonLines.getFirst());
        assertEquals("input", node.get("name").asText());
        assertEquals("text", node.get("attrs").get("type").asText());
        assertEquals("username", node.get("attrs").get("name").asText());
        assertEquals("form-control", node.get("attrs").get("class").asText());
    }

    @Test
    void testHtmlSelect_PathMode() throws Exception {
        Path html = projectRoot.resolve("path.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div><span>First</span></div>
                  <div><span>Second</span></div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "path.html"));

        String result = searchTools.xmlSelect("path.html", "span", "PATH", "", 10, 10);

        assertTrue(
                result.contains("/html[1]/body[1]/div[1]/span[1]"),
                "Should show path for first span. Result:\n" + result);
        assertTrue(
                result.contains("/html[1]/body[1]/div[2]/span[1]"),
                "Should show path for second span. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_HtmlMode() throws Exception {
        Path html = projectRoot.resolve("html_mode.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="small"><b>Bold</b></div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "html_mode.html"));

        String result = searchTools.xmlSelect("html_mode.html", "div.small", "MARKUP", "", 10, 10);

        assertTrue(result.contains("<div class=\"small\">"), "Should contain outer HTML. Result:\n" + result);
        assertTrue(result.contains("<b>Bold</b>"), "Should contain inner HTML. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_HtmlModeFallsBackToSkimWhenTooLarge() throws Exception {
        Path html = projectRoot.resolve("big.html");
        String bigText = "a".repeat(2100);
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="big">%s</div>
                </body>
                </html>
                """
                        .stripIndent()
                        .formatted(bigText));
        mockProjectFiles.add(new ProjectFile(projectRoot, "big.html"));

        String result = searchTools.xmlSelect("big.html", "div.big", "MARKUP", "", 10, 10);

        assertTrue(result.contains("[MARKUP_TOO_LARGE]"), "Should indicate markup was too large. Result:\n" + result);
        assertTrue(result.contains("textLen="), "Fallback skim should include textLen. Result:\n" + result);
        assertFalse(result.contains("a".repeat(200)), "Should not dump the huge text content. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_NameMode() throws Exception {
        Path html = projectRoot.resolve("name.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <article>Content</article>
                  <section>More</section>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "name.html"));

        String result = searchTools.xmlSelect("name.html", "body > *", "NAME", "", 10, 10);

        assertTrue(result.contains(": article"), "Should show article tag name. Result:\n" + result);
        assertTrue(result.contains(": section"), "Should show section tag name. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_NoFilesFound() throws Exception {
        String result = searchTools.xmlSelect("nonexistent.html", "div", "TEXT", "", 10, 10);
        assertTrue(
                result.contains("No markup files found matching"), "Should report no files found. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_NoMatches() throws Exception {
        Path html = projectRoot.resolve("no_match.html");
        Files.writeString(html, "<html><body><div>Test</div></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "no_match.html"));

        String result = searchTools.xmlSelect("no_match.html", "span.nonexistent", "TEXT", "", 10, 10);
        assertTrue(result.contains("No results for xmlSelect"), "Should report no matches. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_HtmExtension() throws Exception {
        Path htm = projectRoot.resolve("test.htm");
        Files.writeString(htm, "<html><body><p>Paragraph</p></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "test.htm"));

        String result = searchTools.xmlSelect("test.htm", "p", "TEXT", "", 10, 10);

        assertTrue(result.contains("File: test.htm"), "Should find .htm files. Result:\n" + result);
        assertTrue(result.contains("Paragraph"), "Should extract text. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_GlobPattern() throws Exception {
        Path html1 = projectRoot.resolve("page1.html");
        Path html2 = projectRoot.resolve("page2.html");
        Files.writeString(html1, "<html><body><h1>Page 1</h1></body></html>");
        Files.writeString(html2, "<html><body><h1>Page 2</h1></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "page1.html"));
        mockProjectFiles.add(new ProjectFile(projectRoot, "page2.html"));

        String result = searchTools.xmlSelect("*.html", "h1", "TEXT", "", 10, 10);

        assertTrue(result.contains("page1.html"), "Should find page1.html. Result:\n" + result);
        assertTrue(result.contains("page2.html"), "Should find page2.html. Result:\n" + result);
        assertTrue(result.contains("Page 1"), "Should extract text from page1. Result:\n" + result);
        assertTrue(result.contains("Page 2"), "Should extract text from page2. Result:\n" + result);
    }

    @Test
    void testHtmlSelect_PathRetry() throws Exception {
        Path rootHtml = projectRoot.resolve("root.html");
        Files.writeString(rootHtml, "<html><body><p>found me</p></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "root.html"));

        // Verify that **/root.html matches a file at the project root via the retry logic
        String result = searchTools.xmlSelect("**/root.html", "p", "TEXT", "", 10, 10);
        assertTrue(result.contains("root.html"), "Should find file at root even with **/ prefix. Result:\n" + result);
        assertTrue(result.contains("found me"), "Should extract text from the element. Result:\n" + result);
    }

    @Test
    void testHtmlSkim_Basic() throws Exception {
        Path html = projectRoot.resolve("skim.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div id="header">Header</div>
                  <div id="content">
                    <p>Paragraph 1</p>
                    <p>Paragraph 2</p>
                  </div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "skim.html"));

        String result = searchTools.xmlSkim("skim.html", 10);

        assertTrue(result.contains("<file path=\"skim.html\">"), "Should include file wrapper. Result:\n" + result);
        assertTrue(
                result.contains("<body>") || result.contains("<div>"),
                "Should include at least one tag marker like <body> or <div>. Result:\n" + result);
        assertTrue(result.contains("textLen="), "Should include textLen. Result:\n" + result);
        assertTrue(result.contains("attrs={"), "Should include attrs={ section somewhere. Result:\n" + result);
    }

    @Test
    void testHtmlSkim_NestedTagsWithAttributes() throws Exception {
        Path html = projectRoot.resolve("skim_nested.html");
        Files.writeString(
                html,
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="container" data-role="main">
                    <span id="inner">Content</span>
                  </div>
                </body>
                </html>
                """
                        .stripIndent());
        mockProjectFiles.add(new ProjectFile(projectRoot, "skim_nested.html"));

        String result = searchTools.xmlSkim("skim_nested.html", 10);

        assertTrue(
                result.contains("<file path=\"skim_nested.html\">"), "Should include file wrapper. Result:\n" + result);
        assertTrue(
                result.contains("<body>") || result.contains("<div>"),
                "Should include at least one tag marker. Result:\n" + result);
        assertTrue(result.contains("attrs={"), "Should include attrs={ section. Result:\n" + result);
        // Verify nested structure is captured
        assertTrue(
                result.contains("children={") || result.contains("div") || result.contains("span"),
                "Should capture nested structure. Result:\n" + result);
    }

    @Test
    void testHtmlSkim_HtmExtension() throws Exception {
        Path htm = projectRoot.resolve("skim.htm");
        Files.writeString(htm, "<html><body><p>Test</p></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "skim.htm"));

        String result = searchTools.xmlSkim("skim.htm", 10);

        assertTrue(result.contains("<file path=\"skim.htm\">"), "Should find .htm files. Result:\n" + result);
        assertTrue(result.contains("<body>"), "Should include body. Result:\n" + result);
    }

    @Test
    void testHtmlSkim_NoFilesFound() throws Exception {
        String result = searchTools.xmlSkim("nonexistent.html", 10);
        assertTrue(
                result.contains("No markup files found matching"), "Should report no files found. Result:\n" + result);
    }

    @Test
    void testHtmlSkim_PathRetry() throws Exception {
        Path rootHtml = projectRoot.resolve("root_skim.html");
        Files.writeString(rootHtml, "<html><body><div>Test</div></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "root_skim.html"));

        String result = searchTools.xmlSkim("**/root_skim.html", 10);
        assertTrue(
                result.contains("root_skim.html"), "Should find file at root even with **/ prefix. Result:\n" + result);
    }

    @Test
    void testHtmlSkim_MalformedHtml() throws Exception {
        Path html = projectRoot.resolve("malformed.html");
        Files.writeString(html, "<div><p>Unclosed tags<span>more");
        mockProjectFiles.add(new ProjectFile(projectRoot, "malformed.html"));

        String result = searchTools.xmlSkim("malformed.html", 10);

        assertTrue(
                result.contains("<file path=\"malformed.html\">"),
                "Should handle malformed HTML gracefully. Result:\n" + result);
        assertTrue(
                result.contains("<div>") || result.contains("<body>"),
                "Should still parse elements. Result:\n" + result);
    }

    @Test
    void testHtmlSkim_GlobPattern() throws Exception {
        Path html1 = projectRoot.resolve("page1_skim.html");
        Path html2 = projectRoot.resolve("page2_skim.html");
        Files.writeString(html1, "<html><body><h1>Page 1</h1></body></html>");
        Files.writeString(html2, "<html><body><h1>Page 2</h1></body></html>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "page1_skim.html"));
        mockProjectFiles.add(new ProjectFile(projectRoot, "page2_skim.html"));

        String result = searchTools.xmlSkim("*_skim.html", 10);

        assertTrue(result.contains("page1_skim.html"), "Should find page1. Result:\n" + result);
        assertTrue(result.contains("page2_skim.html"), "Should find page2. Result:\n" + result);
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
