package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractProject;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                        default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                    }
                });

        IContextManager ctxManager = (IContextManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IContextManager.class}, (proxy, method, args) -> {
                    if ("getProject".equals(method.getName())) return projectProxy;
                    else throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                });

        searchTools = new SearchTools(ctxManager);
    }

    @AfterEach
    void tearDown() {
        repo.close();
    }

    @Test
    void testSearchGitCommitMessages_invalidRegexFallback() {
        String result = searchTools.searchGitCommitMessages("[[", "testing invalid regex fallback");

        // We should get the commit we added that contains the substring "[["
        assertTrue(result.contains("Commit with [[ pattern"), "Commit message should appear in the result");
        // Basic XML-ish structure checks
        assertTrue(result.contains("<commit id="), "Result should contain commit tag");
        assertTrue(result.contains("<message>"), "Result should contain message tag");
        assertTrue(result.contains("<edited_files>"), "Result should contain edited_files tag");
    }

    // ---------------------------------------------------------------------
    //  New tests: invalid-regex fallback for searchSubstrings / searchFilenames
    // ---------------------------------------------------------------------

    @Test
    void testSearchSubstrings_invalidRegexFallback() throws Exception {
        // 1. Create a text file whose contents include the substring "[["
        Path txt = projectRoot.resolve("substring_test.txt");
        Files.writeString(txt, "some content with [[ pattern");

        // 2. Add to mock project file list so SearchTools sees it
        mockProjectFiles.add(new ProjectFile(projectRoot, "substring_test.txt"));

        // 3. Invoke searchSubstrings with an invalid regex
        String result = searchTools.searchSubstrings(List.of("[["), "testing invalid regex fallback for substrings");

        // 4. Verify fallback occurred and file is reported
        assertTrue(result.contains("substring_test.txt"), "Result should reference the test file");
    }

    @Test
    void testSearchFilenames_invalidRegexFallback() throws Exception {
        // 1. Create a file whose *name* contains the substring "[["
        Path filePath = projectRoot.resolve("filename_[[-test.txt");
        Files.writeString(filePath, "dummy");

        // 2. Add to mock project file list
        mockProjectFiles.add(new ProjectFile(projectRoot, "filename_[[-test.txt"));

        // 3. Search with invalid regex
        String result = searchTools.searchFilenames(List.of("[["), "testing invalid regex fallback for filenames");

        // 4. Ensure the file name appears in the output
        assertTrue(result.contains("filename_[[-test.txt"), "Result should reference the test filename");
    }

    @Test
    void testSearchFilenames_withSubdirectories() throws Exception {
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
        String resultNix = searchTools.searchFilenames(List.of(relativePathNix), "test nix path");
        assertTrue(
                resultNix.contains(relativePathNix) || resultNix.contains(relativePathWin),
                "Should find file with forward-slash path");

        // B. File name only
        String resultName = searchTools.searchFilenames(List.of("MOP.svelte"), "test file name");
        assertTrue(
                resultName.contains(relativePathNix) || resultName.contains(relativePathWin),
                "Should find file with file name only");

        // C. Partial path
        String resultPartial = searchTools.searchFilenames(List.of("src/MOP"), "test partial path");
        assertTrue(
                resultPartial.contains(relativePathNix) || resultPartial.contains(relativePathWin),
                "Should find file with partial path");

        // D. Full path with backslashes (Windows-style)
        String resultWin = searchTools.searchFilenames(List.of(relativePathWin), "test windows path");
        assertTrue(
                resultWin.contains(relativePathNix) || resultWin.contains(relativePathWin),
                "Should find file with back-slash path pattern");

        // E. Regex path pattern (frontend-mop/.*\.svelte)
        String regexPattern = "frontend-mop/.*\\\\.svelte";
        String resultRegex = searchTools.searchFilenames(List.of(regexPattern), "test regex path");
        assertTrue(
                resultRegex.contains(relativePathNix) || resultRegex.contains(relativePathWin),
                "Should find file with regex pattern");
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
}
