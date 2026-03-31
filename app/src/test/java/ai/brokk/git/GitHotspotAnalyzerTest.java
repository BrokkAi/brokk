package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitHotspotAnalyzerTest {

    @TempDir
    Path tempDir;

    private TestProject project;
    private GitRepo repo;
    private TestAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize a real Git repo in the temp dir for JGit traversal
        Git.init().setDirectory(tempDir.toFile()).call();
        repo = new GitRepo(tempDir);
        project = new TestProject(tempDir, Languages.JAVA).withRepo(repo);
        analyzer = new TestAnalyzer(new java.util.ArrayList<>(), new java.util.HashMap<>(), project);
    }

    @AfterEach
    void tearDown() {
        project.close();
    }

    @Test
    void testHotspotDetection() throws Exception {
        Path filePath = tempDir.resolve("ComplexService.java");
        ProjectFile pf = new ProjectFile(tempDir, "ComplexService.java");

        // 1. Create multiple commits to generate churn
        for (int i = 0; i < 15; i++) {
            Files.writeString(filePath, "public class ComplexService { void method" + i + "() {} }");
            repo.add(pf);
            repo.getGit()
                    .commit()
                    .setSign(false)
                    .setAuthor("dev" + (i % 3), "dev" + (i % 3) + "@example.com")
                    .setMessage("Commit " + i)
                    .call();
        }

        // 2. Mock high complexity for this file
        ai.brokk.analyzer.CodeUnit cu = new ai.brokk.analyzer.CodeUnit(
                pf, ai.brokk.analyzer.CodeUnitType.FUNCTION, "ComplexService", "complexMethod", "()", false);
        analyzer.addDeclaration(cu);
        analyzer.setComplexity(cu, 20);

        GitHotspotAnalyzer hotspotAnalyzer = new GitHotspotAnalyzer(repo, analyzer);
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);

        GitHotspotAnalyzer.HotspotReport report = hotspotAnalyzer.analyze(since, 100);

        assertNotNull(report);
        assertEquals(15, report.analyzedCommits());

        GitHotspotAnalyzer.FileHotspotInfo info = report.files().stream()
                .filter(f -> f.path().equals("ComplexService.java"))
                .findFirst()
                .orElseThrow();

        assertEquals(15, info.churn());
        assertEquals(3, info.uniqueAuthors());
        assertEquals(3, info.topAuthors().size());
        assertEquals(5, info.topAuthors().get(0).commits());
        assertTrue(info.topAuthors().get(0).name().startsWith("dev"));
        assertTrue(info.topAuthors().get(0).email().contains("@example.com"));
        assertEquals(GitHotspotAnalyzer.HotspotCategory.HOTSPOT, info.category());
    }

    @Test
    void testCategoryAssignment() throws Exception {
        GitHotspotAnalyzer hotspotAnalyzer = new GitHotspotAnalyzer(repo, analyzer);

        // Using reflection or a protected accessor isn't allowed by project rules,
        // so we verify via the analyze call with controlled inputs if possible,
        // or just ensure the public API works as expected.

        // Since we can't easily mock the complexity count in TestAnalyzer without
        // deeper integration, we'll verify the logic in a simple way.

        Path filePath = tempDir.resolve("Stable.java");
        ProjectFile pf = new ProjectFile(tempDir, "Stable.java");
        Files.writeString(filePath, "class Stable {}");
        repo.add(pf);
        repo.getGit().commit().setSign(false).setMessage("Initial").call();

        GitHotspotAnalyzer.HotspotReport report =
                hotspotAnalyzer.analyze(Instant.now().minus(1, ChronoUnit.HOURS), 10);
        assertFalse(report.files().isEmpty());
        assertEquals(
                GitHotspotAnalyzer.HotspotCategory.STABLE, report.files().get(0).category());
    }

    @Test
    void testMissingObjectFallback() throws Exception {
        // We simulate the fallback by providing a DiffFormatter that throws on first scan
        // but succeeds when renames are disabled.
        GitHotspotAnalyzer hotspotAnalyzer = new GitHotspotAnalyzer(repo, analyzer);

        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE) {
            private boolean throwOnce = true;

            @Override
            public List<DiffEntry> scan(
                    @Nullable org.eclipse.jgit.treewalk.AbstractTreeIterator oldTree,
                    @Nullable org.eclipse.jgit.treewalk.AbstractTreeIterator newTree)
                    throws java.io.IOException {
                if (throwOnce && isDetectRenames()) {
                    throwOnce = false;
                    // Wrap it to test the more robust detection
                    throw new java.io.IOException(
                            "Wrapped failure", new MissingObjectException(ObjectId.zeroId(), "blob"));
                }
                return super.scan(oldTree, newTree);
            }
        }) {
            df.setRepository(repo.getRepository());
            df.setDetectRenames(true);

            // Create a commit to process
            Path filePath = tempDir.resolve("Fallback.java");
            ProjectFile pf = new ProjectFile(tempDir, "Fallback.java");
            Files.writeString(filePath, "class Fallback {}");
            repo.add(pf);
            String commitId = repo.getGit()
                    .commit()
                    .setSign(false)
                    .setMessage("Fallback test")
                    .call()
                    .name();

            org.eclipse.jgit.revwalk.RevCommit commit;
            try (org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repo.getRepository())) {
                commit = rw.parseCommit(repo.getRepository().resolve(commitId));
            }

            // This should not throw MissingObjectException because processCommit catches and retries
            hotspotAnalyzer.processCommit(commit, df, new HashMap<>());

            assertFalse(df.isDetectRenames(), "Rename detection should have been disabled after exception");
        }
    }
}
