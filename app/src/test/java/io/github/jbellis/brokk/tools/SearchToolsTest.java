package io.github.jbellis.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
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
    /** mutable set returned from the project-proxy’s getAllFiles() */
    private java.util.Set<ProjectFile> mockProjectFiles;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testRepo");
        mockProjectFiles = new java.util.HashSet<>();
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            // initial commit
            Path readme = projectRoot.resolve("README.md");
            java.nio.file.Files.writeString(readme, "Initial commit file");
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
        Class<?>[] projectInterfaces = io.github.jbellis.brokk.AbstractProject.class.getInterfaces();
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
        java.nio.file.Path txt = projectRoot.resolve("substring_test.txt");
        java.nio.file.Files.writeString(txt, "some content with [[ pattern");

        // 2. Add to mock project file list so SearchTools sees it
        mockProjectFiles.add(new ProjectFile(projectRoot, "substring_test.txt"));

        // 3. Invoke searchSubstrings with an invalid regex
        String result =
                searchTools.searchSubstrings(java.util.List.of("[["), "testing invalid regex fallback for substrings");

        // 4. Verify fallback occurred and file is reported
        assertTrue(result.contains("substring_test.txt"), "Result should reference the test file");
    }

    @Test
    void testSearchFilenames_invalidRegexFallback() throws Exception {
        // 1. Create a file whose *name* contains the substring "[["
        java.nio.file.Path filePath = projectRoot.resolve("filename_[[-test.txt");
        java.nio.file.Files.writeString(filePath, "dummy");

        // 2. Add to mock project file list
        mockProjectFiles.add(new ProjectFile(projectRoot, "filename_[[-test.txt"));

        // 3. Search with invalid regex
        String result =
                searchTools.searchFilenames(java.util.List.of("[["), "testing invalid regex fallback for filenames");

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
        mockProjectFiles.add(new ProjectFile(projectRoot, relativePathNix));

        // 3. Test cases
        // A. Full path with forward slashes
        String resultNix = searchTools.searchFilenames(java.util.List.of(relativePathNix), "test nix path");
        assertTrue(resultNix.contains(relativePathNix), "Should find file with forward-slash path");

        // B. File name only
        String resultName = searchTools.searchFilenames(java.util.List.of("MOP.svelte"), "test file name");
        assertTrue(resultName.contains(relativePathNix), "Should find file with file name only");

        // C. Partial path
        String resultPartial = searchTools.searchFilenames(java.util.List.of("src/MOP"), "test partial path");
        assertTrue(resultPartial.contains(relativePathNix), "Should find file with partial path");

        // D. Full path with backslashes (Windows-style)
        String relativePathWin = "frontend-mop\\src\\MOP.svelte";
        String resultWin = searchTools.searchFilenames(java.util.List.of(relativePathWin), "test windows path");
        assertTrue(resultWin.contains(relativePathNix), "Should find file with back-slash path pattern");
    }
}
