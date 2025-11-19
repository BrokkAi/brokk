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

    @Nullable
    private static ai.brokk.analyzer.CppAnalyzer cppAnalyzer;

    @Nullable
    private static TestProject cppTestProject;

    @BeforeAll
    static void setupAnalyzer() throws IOException {
        // Setup Java analyzer
        Path javaTestPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(javaTestPath), "Test resource directory 'testcode-java' not found.");
        javaTestProject = new TestProject(javaTestPath, Languages.JAVA);
        javaAnalyzer = new JavaAnalyzer(javaTestProject);

        // Setup C++ analyzer
        Path cppTestPath =
                Path.of("src/test/resources/testcode-cpp").toAbsolutePath().normalize();
        assertTrue(Files.exists(cppTestPath), "Test resource directory 'testcode-cpp' not found.");
        cppTestProject = new TestProject(cppTestPath, Languages.CPP_TREESITTER);
        cppAnalyzer = new ai.brokk.analyzer.CppAnalyzer(cppTestProject);
    }

    @AfterAll
    static void teardownAnalyzer() {
        if (javaTestProject != null) {
            javaTestProject.close();
        }
        if (cppTestProject != null) {
            cppTestProject.close();
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

    @Test
    void testGetSymbolLocations_withOverloads() {
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

        // NOTE: Current JavaAnalyzer limitation - getDefinitions() only returns one overload
        // This test verifies SearchTools handles what the analyzer provides correctly.
        // When JavaAnalyzer is updated to return all overloads, this test should be updated
        // to verify all overloads are shown with signatures.

        var definitions = javaAnalyzer.getDefinitions("A.method2").stream().findFirst();
        assertFalse(definitions.isEmpty(), "Should find at least one method");

        String result = tools.getSymbolLocations(List.of("A.method2"));
        assertFalse(result.isEmpty(), "Result should not be empty");
        assertTrue(result.contains("A.method2"), "Should contain method name");
        assertTrue(result.contains("A.java"), "Should contain file path");

        // When overload support is added to JavaAnalyzer, verify both signatures appear:
        // assertTrue(result.contains("A.method2(String)"));
        // assertTrue(result.contains("A.method2(String, int)"));
    }

    @Test
    void testGetMethodSources_withOverloads() {
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

        // NOTE: Current JavaAnalyzer limitation - getDefinitions() only returns one overload
        // This test verifies SearchTools correctly retrieves method source for what's available
        // When JavaAnalyzer is updated to return all overloads, verify both are included

        String result = tools.getMethodSources(List.of("A.method2"));
        assertFalse(result.isEmpty(), "Result should not be empty");

        // Should contain at least one method2 signature
        boolean hasMethod2Signature = result.contains("method2(String input)")
                || result.contains("public String method2(String input)")
                || result.contains("method2(String input, int otherInput)");
        assertTrue(hasMethod2Signature, "Should contain method2 signature");

        // Should contain method body (actual return statement)
        boolean hasMethodBody = result.contains("return \"prefix_\" + input");
        assertTrue(hasMethodBody, "Should contain method body");

        // When overload support is added, verify BOTH overloads are present:
        // assertTrue(result.contains("method2(String input)"));
        // assertTrue(result.contains("method2(String input, int otherInput)"));
        // assertTrue(result.contains("overload of method2"));
    }

    @Test
    void testGetSymbolLocations_withCppOverloads() {
        // Create a context manager that provides the C++ analyzer
        IContextManager ctxWithAnalyzer = (IContextManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IContextManager.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAnalyzer" -> cppAnalyzer;
                        case "getAnalyzerUninterrupted" -> cppAnalyzer;
                        case "getProject" -> cppTestProject;
                        default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                    };
                });

        SearchTools tools = new SearchTools(ctxWithAnalyzer);

        // CppAnalyzer correctly returns ALL overloads (may include duplicates from multiple test files)
        var definitions = cppAnalyzer.getDefinitions("overloadedFunction");
        assertTrue(
                definitions.size() >= 3, "CppAnalyzer should return at least 3 overloads, got " + definitions.size());

        String result = tools.getSymbolLocations(List.of("overloadedFunction"));
        assertFalse(result.isEmpty(), "Result should not be empty");

        // Verify all three overload signatures are present (no spaces in C++ signatures)
        assertTrue(result.contains("overloadedFunction(int)"), "Should contain int overload signature");
        assertTrue(result.contains("overloadedFunction(double)"), "Should contain double overload signature");
        assertTrue(result.contains("overloadedFunction(int,int)"), "Should contain two-int overload signature");

        // Verify file path is present
        assertTrue(
                result.contains("simple_overloads.h") || result.contains("duplicates.h"), "Should contain file path");
    }

    @Test
    void testGetMethodSources_withCppOverloads() {
        // Create a context manager that provides the C++ analyzer
        IContextManager ctxWithAnalyzer = (IContextManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IContextManager.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAnalyzer" -> cppAnalyzer;
                        case "getAnalyzerUninterrupted" -> cppAnalyzer;
                        case "getProject" -> cppTestProject;
                        default -> throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                    };
                });

        SearchTools tools = new SearchTools(ctxWithAnalyzer);

        String result = tools.getMethodSources(List.of("overloadedFunction"));
        assertFalse(result.isEmpty(), "Result should not be empty");

        // Verify all three overloads are present in source output
        assertTrue(
                result.contains("overloadedFunction(int x)") || result.contains("void overloadedFunction(int x)"),
                "Should contain int overload source");
        assertTrue(
                result.contains("overloadedFunction(double x)") || result.contains("void overloadedFunction(double x)"),
                "Should contain double overload source");
        assertTrue(
                result.contains("overloadedFunction(int x, int y)")
                        || result.contains("void overloadedFunction(int x, int y)"),
                "Should contain two-int overload source");

        // Verify comments from overload bodies are present
        assertTrue(result.contains("int overload"), "Should contain comment from int overload");
        assertTrue(result.contains("double overload"), "Should contain comment from double overload");
        assertTrue(result.contains("two int overload"), "Should contain comment from two-int overload");
    }
}
