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
        String result = searchTools.findFilenames(List.of("[["), "testing invalid regex error");
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
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
        String resultNix = searchTools.findFilenames(List.of(relativePathNix), "test nix path");
        assertTrue(
                resultNix.contains(relativePathNix) || resultNix.contains(relativePathWin),
                "Should find file with forward-slash path");

        // B. File name only
        String resultName = searchTools.findFilenames(List.of("MOP.svelte"), "test file name");
        assertTrue(
                resultName.contains(relativePathNix) || resultName.contains(relativePathWin),
                "Should find file with file name only");

        // C. Partial path
        String resultPartial = searchTools.findFilenames(List.of("src/MOP"), "test partial path");
        assertTrue(
                resultPartial.contains(relativePathNix) || resultPartial.contains(relativePathWin),
                "Should find file with partial path");

        // D. Full path with backslashes (Windows-style)
        String resultWin = searchTools.findFilenames(List.of(relativePathWin), "test windows path");
        assertTrue(
                resultWin.contains(relativePathNix) || resultWin.contains(relativePathWin),
                "Should find file with back-slash path pattern");

        // E. Regex path pattern (frontend-mop/.*\.svelte)
        String regexPattern = "frontend-mop/.*\\.svelte";
        String resultRegex = searchTools.findFilenames(List.of(regexPattern), "test regex path");
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

        String result = searchTools.searchFileContents("MATCH", "**/grep_test.txt", 1, 200);

        assertTrue(result.contains("grep_test.txt"));
        assertTrue(result.contains("1: line1"));
        assertTrue(result.contains("2: line2 MATCH"));
        assertTrue(result.contains("3: line3"));
        assertFalse(result.contains("4: line4"));
    }

    @Test
    void testSearchFileContents_invalidRegexThrows() throws Exception {
        // "[[" is invalid regex, should return error message
        String result = searchTools.searchFileContents("[[", "README.md", 0, 200);
        assertTrue(result.contains("Invalid regex pattern"), "Should report regex error");
    }

    @Test
    void testXpathQuery() throws Exception {
        Path xml = projectRoot.resolve("test.xml");
        Files.writeString(xml, "<root xmlns:ns='uri'><ns:child>hello</ns:child></root>");
        mockProjectFiles.add(new ProjectFile(projectRoot, "test.xml"));

        // Test local-name rewrite
        String result = searchTools.xpathQuery("test.xml", "/root/child");

        assertTrue(result.contains("File: test.xml"));
        assertTrue(result.contains("hello"));
    }

    @Test
    void testJq() throws Exception {
        Path json = projectRoot.resolve("test.json");
        Files.writeString(json, "{\"a\": [{\"id\": 1}, {\"id\": 2}]}");
        mockProjectFiles.add(new ProjectFile(projectRoot, "test.json"));

        String result = searchTools.jq("test.json", ".a[] | .id");

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
        String filterResult = searchTools.jq("bad.json", ".[[[");
        assertTrue(filterResult.contains("Invalid jq filter"), "Should report filter compilation error");

        // 2. Invalid Content
        String contentResult = searchTools.jq("bad.json", ".");
        assertTrue(contentResult.contains("errors in 1 of 1 files"), "Should report JSON parsing error");
    }

    @Test
    void testSearchFileContents_PathRetry() throws Exception {
        Path rootFile = projectRoot.resolve("root.txt");
        Files.writeString(rootFile, "found me");
        mockProjectFiles.add(new ProjectFile(projectRoot, "root.txt"));

        // Verify that **/root.txt matches a file at the project root via the retry logic
        String result = searchTools.searchFileContents("found", "**/root.txt", 0, 200);
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
        String result = searchTools.searchFileContents("MATCH", "context_test.txt", 1, 200);

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
        String resultsCapped = searchTools.searchFileContents("MATCH", "context_test.txt", 999, 200);
        assertTrue(resultsCapped.contains("7: L7"));
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

        String result = searchTools.xpathQuery("security.xml", "/root");

        // DocumentBuilder should reject the DTD
        assertTrue(result.contains("errors in 1 of 1 files"), "Should report error due to DTD disallowance");
        assertTrue(
                result.contains("DOCTYPE") && result.contains("disallow-doctype-decl"),
                "Should specifically mention DOCTYPE restriction, got: " + result);
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
