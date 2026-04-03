package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.project.CoreProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandaloneCodeIntelligenceTest {

    @TempDir
    Path tempDir;

    private CoreProject project;

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void wiresProjectAndAnalyzer() {
        project = new CoreProject(tempDir);
        IAnalyzer analyzer = new DisabledAnalyzer(project);

        var intel = new StandaloneCodeIntelligence(project, analyzer);

        assertEquals(project, intel.getProject());
        assertEquals(analyzer, intel.getAnalyzer());
    }

    @Test
    void returnsGitRepoWhenAvailable() throws Exception {
        var repoDir = tempDir.resolve("git-project");
        Files.createDirectories(repoDir);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.writeString(repoDir.resolve("README.md"), "test");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        project = new CoreProject(repoDir);
        var intel = new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project));

        assertNotNull(intel.getRepo(), "Should return non-null repo for git project");
        assertTrue(project.hasGit());
    }

    @Test
    void returnsRepoForNonGitProject() {
        project = new CoreProject(tempDir);
        var intel = new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project));

        // LocalFileRepo is returned for non-git dirs, so getRepo() is non-null
        assertNotNull(intel.getRepo(), "Should return LocalFileRepo for non-git project");
    }

    @Test
    void toFileResolvesRelativePath() throws Exception {
        Files.writeString(tempDir.resolve("hello.py"), "print('hi')");
        project = new CoreProject(tempDir);
        var intel = new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project));

        var pf = intel.toFile("hello.py");
        assertNotNull(pf);
        assertEquals("hello.py", pf.toString());
    }
}
