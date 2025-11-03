package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConflictAnnotatorTest {

    @BeforeEach
    void setUp() {
        // Ensure the factory is reset to default before each test
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;
    }

    @AfterEach
    void tearDown() {
        // Restore to default after each test to avoid affecting other tests
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;
    }

    @Test
    void testMerge() throws Exception {
        Path projectRoot = Files.createTempDirectory("merge-agent");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // initial base commit (base chain: two commits)
            var baseInitial =
                    """
                    O1
                    O2
                    O3
                    O4
                    O5
                    O6
                    """;
            createCommit(projectRoot, "common.txt", baseInitial, "base0");
            var base0Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // second commit on the base chain — this will become the merge base
            var baseContent =
                    """
                    O1
                    O2
                    O3'
                    O4
                    O5
                    O6
                    """;
            createCommit(projectRoot, "common.txt", baseContent, "base1");
            var base1Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            String mainBranch = getCurrentBranch(projectRoot);

            // create feature branch at current HEAD (the base1 commit)
            runGit(projectRoot, "branch feature");
            runGit(projectRoot, "checkout feature");

            // feature: first commit changes line 2 and line 5
            var theirs1 =
                    """
                    O1
                    Y2
                    O3'
                    O4
                    Y5
                    O6
                    """;
            createCommit(projectRoot, "common.txt", theirs1, "c_feature1");
            var feature1Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // feature: second commit changes line 3 -> Y3'
            var theirsContent =
                    """
                    O1
                    Y2
                    Y3'
                    O4
                    Y5
                    O6
                    """;
            createCommit(projectRoot, "common.txt", theirsContent, "c_feature2");
            var feature2Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // back to main and create our two commits
            runGit(projectRoot, "checkout " + mainBranch);

            // main: first commit changes line 2, 3 and 5
            var ours1 =
                    """
                    O1
                    X2
                    X3
                    O4
                    X5
                    O6
                    """;
            createCommit(projectRoot, "common.txt", ours1, "c_main1");
            var main1Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // main: second commit further tweaks line 3 -> X3'
            var oursContent =
                    """
                    O1
                    X2
                    X3'
                    O4
                    X5
                    O6
                    """;
            createCommit(projectRoot, "common.txt", oursContent, "c_main2");
            var main2Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // merge feature into main (expected to produce conflicts)
            runGitAllowFail(projectRoot, "merge feature");

            // Prepare inputs for annotate()
            var ourFile = new ProjectFile(projectRoot, "common.txt");
            var theirFile = new ProjectFile(projectRoot, "common.txt");
            var baseFile = new ProjectFile(projectRoot, "common.txt");
            var state = MergeAgent.MergeMode.MERGE;

            var cf = new MergeAgent.FileConflict(ourFile, oursContent, theirFile, theirsContent, baseFile, baseContent);

            // Use a stateful annotator instance for this test invocation
            var mc = new MergeAgent.MergeConflict(state, main2Sha, feature2Sha, base1Sha, Set.of(cf));
            var annotator = new ConflictAnnotator(repo, mc);

            // Verify diff3-style annotate output and annotations
            var mergedPreview = annotator.annotate(cf);

            // Headers should include short commit ids for ours/base/theirs
            var baseShort1 = repo.shortHash(base0Sha);
            var baseShort2 = repo.shortHash(base1Sha);
            var oursShort1 = repo.shortHash(main1Sha);
            var oursShort2 = repo.shortHash(main2Sha);
            var theirsShort1 = repo.shortHash(feature1Sha);
            var theirsShort2 = repo.shortHash(feature2Sha);

            var expected =
                    ("""
                    O1
                    BRK_CONFLICT_BEGIN_1
                    BRK_OUR_VERSION %s
                    %s X2
                    %s X3'
                    BRK_BASE_VERSION %s
                    %s O2
                    %s O3'
                    BRK_THEIR_VERSION %s
                    %s Y2
                    %s Y3'
                    BRK_CONFLICT_END_1
                    O4
                    BRK_CONFLICT_BEGIN_2
                    BRK_OUR_VERSION %s
                    %s X5
                    BRK_BASE_VERSION %s
                    %s O5
                    BRK_THEIR_VERSION %s
                    %s Y5
                    BRK_CONFLICT_END_2
                    O6
                    """)
                            .formatted(
                                    // first conflict region (lines 2 & 3)
                                    // header ours (HEAD resolved)
                                    oursShort2, // BRK_OUR_VERSION %s  (HEAD commit short)
                                    oursShort1, // %s X2
                                    oursShort2, // %s X3'
                                    baseShort2, // BRK_BASE_VERSION %s
                                    baseShort1, // %s O2
                                    baseShort2, // %s O3'
                                    theirsShort2, // BRK_THEIR_VERSION %s
                                    theirsShort1, // %s Y2
                                    theirsShort2, // %s Y3'
                                    // second conflict region (line 5)
                                    // header ours (HEAD resolved)
                                    oursShort2, // BRK_OUR_VERSION %s
                                    oursShort1, // %s X5
                                    baseShort2, // BRK_BASE_VERSION %s
                                    baseShort1, // %s O5
                                    theirsShort2, // BRK_THEIR_VERSION %s
                                    theirsShort1 // %s Y5
                                    )
                            .stripTrailing();
            assertEquals(
                    expected, mergedPreview.contents(), "Merged preview should match expected diff3-style content");
        }
    }

    @Test
    void testRebase() throws Exception {
        Path projectRoot = Files.createTempDirectory("merge-agent-rebase");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // base commit
            createCommit(projectRoot, "common.txt", "Original content", "base");
            var baseSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            String mainBranch = getCurrentBranch(projectRoot);

            // main modification
            createCommit(projectRoot, "common.txt", "Main modification", "c_main");
            var mainSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // feature from base
            runGit(projectRoot, "branch feature HEAD~1");
            runGit(projectRoot, "checkout feature");
            createCommit(projectRoot, "common.txt", "Feature modification", "c_feature");
            var featureSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // rebase feature onto main
            runGitAllowFail(projectRoot, "rebase " + mainBranch);

            var rebaseHeadPath =
                    repo.getGit().getRepository().getDirectory().toPath().resolve("REBASE_HEAD");
            assumeTrue(
                    Files.exists(rebaseHeadPath), "Rebase conflict did not produce REBASE_HEAD; skipping smoke test");

            // Prepare inputs for annotate()
            var ourFile = new ProjectFile(projectRoot, "common.txt");
            var theirFile = new ProjectFile(projectRoot, "common.txt");
            var baseFile = new ProjectFile(projectRoot, "common.txt");
            var state = MergeAgent.MergeMode.REBASE;

            var cf = new MergeAgent.FileConflict(
                    ourFile, "Main modification", theirFile, "Feature modification", baseFile, "Original content");

            // Use a stateful annotator instance for this test invocation
            var mc = new MergeAgent.MergeConflict(state, mainSha, featureSha, baseSha, Set.of(cf));
            var annotator = new ConflictAnnotator(repo, mc);

            // Verify diff3-style annotate output and annotations
            var mergedPreview = annotator.annotate(cf);
            var expected = String.join(
                            "\n",
                            "BRK_CONFLICT_BEGIN_1",
                            "BRK_OUR_VERSION %s".formatted(repo.shortHash(mainSha)),
                            "%s Main modification".formatted(repo.shortHash(mainSha)),
                            "BRK_BASE_VERSION %s".formatted(repo.shortHash(baseSha)),
                            "%s Original content".formatted(repo.shortHash(baseSha)),
                            "BRK_THEIR_VERSION %s".formatted(repo.shortHash(featureSha)),
                            "%s Feature modification".formatted(repo.shortHash(featureSha)),
                            "BRK_CONFLICT_END_1")
                    .stripTrailing();
            assertEquals(expected, mergedPreview.contents());
        }
    }

    @Test
    void testCherryPick() throws Exception {
        Path projectRoot = Files.createTempDirectory("merge-agent-cherry");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // base commit
            createCommit(projectRoot, "common.txt", "Original content", "base");
            var baseSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            String mainBranch = getCurrentBranch(projectRoot);

            // main modification
            createCommit(projectRoot, "common.txt", "Main modification", "c_main");
            var mainSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // feature from base
            runGit(projectRoot, "branch feature HEAD~1");
            runGit(projectRoot, "checkout feature");
            createCommit(projectRoot, "common.txt", "Feature modification", "c_feature");
            var featureSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // cherry-pick feature onto main
            runGit(projectRoot, "checkout " + mainBranch);
            runGitAllowFail(projectRoot, "cherry-pick feature");

            // Prepare inputs for annotate()
            var ourFile = new ProjectFile(projectRoot, "common.txt");
            var theirFile = new ProjectFile(projectRoot, "common.txt");
            var baseFile = new ProjectFile(projectRoot, "common.txt");
            var state = MergeAgent.MergeMode.CHERRY_PICK;

            var cf = new MergeAgent.FileConflict(
                    ourFile, "Main modification", theirFile, "Feature modification", baseFile, "Original content");

            // Use a stateful annotator instance for this test invocation
            var mc = new MergeAgent.MergeConflict(state, mainSha, featureSha, baseSha, Set.of(cf));
            var annotator = new ConflictAnnotator(repo, mc);

            // Verify diff3-style annotate output and annotations
            var mergedPreview = annotator.annotate(cf);
            var expected = String.join(
                            "\n",
                            "BRK_CONFLICT_BEGIN_1",
                            "BRK_OUR_VERSION %s".formatted(repo.shortHash(mainSha)),
                            "%s Main modification".formatted(repo.shortHash(mainSha)),
                            "BRK_BASE_VERSION %s".formatted(repo.shortHash(baseSha)),
                            "%s Original content".formatted(repo.shortHash(baseSha)),
                            "BRK_THEIR_VERSION %s".formatted(repo.shortHash(featureSha)),
                            "%s Feature modification".formatted(repo.shortHash(featureSha)),
                            "BRK_CONFLICT_END_1")
                    .stripTrailing();
            assertEquals(expected, mergedPreview.contents());
        }
    }

    @Test
    void testRevert() throws Exception {
        Path projectRoot = Files.createTempDirectory("merge-agent-revert");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // A -> B -> C
            createCommit(projectRoot, "common.txt", "A", "A");
            var commitASha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            createCommit(projectRoot, "common.txt", "B", "B");
            String commitBSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            createCommit(projectRoot, "common.txt", "C", "C");
            var commitCSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // revert B, expect conflict due to C
            runGitAllowFail(projectRoot, "revert " + commitBSha);

            // Prepare inputs for annotate()
            var ourFile = new ProjectFile(projectRoot, "common.txt");
            var theirFile = new ProjectFile(projectRoot, "common.txt");
            var baseFile = new ProjectFile(projectRoot, "common.txt");
            var state = MergeAgent.MergeMode.REVERT;

            var cf = new MergeAgent.FileConflict(ourFile, "C", theirFile, "A", baseFile, "B");

            // Use a stateful annotator instance for this test invocation
            var mc = new MergeAgent.MergeConflict(state, commitCSha, commitASha, commitBSha, Set.of(cf));
            var annotator = new ConflictAnnotator(repo, mc);

            // Verify diff3-style annotate output and annotations
            var mergedPreview = annotator.annotate(cf);

            // For revert, expect a three-way merge between:
            //   ours  = C (current HEAD)
            //   base  = B (the commit being reverted)
            //   theirs= A (B's parent)
            var expected = String.join(
                            "\n",
                            "BRK_CONFLICT_BEGIN_1",
                            "BRK_OUR_VERSION %s".formatted(repo.shortHash(commitCSha)),
                            "%s C".formatted(repo.shortHash(commitCSha)),
                            "BRK_BASE_VERSION %s".formatted(repo.shortHash(commitBSha)),
                            "%s B".formatted(repo.shortHash(commitBSha)),
                            "BRK_THEIR_VERSION %s".formatted(repo.shortHash(commitASha)),
                            "%s A".formatted(repo.shortHash(commitASha)),
                            "BRK_CONFLICT_END_1")
                    .stripTrailing();
            assertEquals(expected, mergedPreview.contents());
        }
    }

    @Test
    void testAutoResolveImportConflict() throws Exception {
        Path projectRoot = Files.createTempDirectory("merge-agent-imports");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            var baseContent =
                    """
                    package p;
                    import a.A;
                    import b.B;

                    class Foo {}
                    """;
            createCommit(projectRoot, "Foo.java", baseContent, "base");
            var baseSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            String mainBranch = getCurrentBranch(projectRoot);

            // feature branch from base
            runGit(projectRoot, "branch feature");
            runGit(projectRoot, "checkout feature");
            var theirsContent =
                    """
                    package p;
                    import a.A;
                    import d.D;

                    class Foo {}
                    """;
            createCommit(projectRoot, "Foo.java", theirsContent, "c_feature");
            var theirsSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // back to main
            runGit(projectRoot, "checkout " + mainBranch);
            var oursContent =
                    """
                    package p;
                    import a.A;
                    import c.C;

                    class Foo {}
                    """;
            createCommit(projectRoot, "Foo.java", oursContent, "c_main");
            var oursSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // merge to create conflict
            runGitAllowFail(projectRoot, "merge feature");

            var ourFile = new ProjectFile(projectRoot, "Foo.java");
            var theirFile = new ProjectFile(projectRoot, "Foo.java");
            var baseFile = new ProjectFile(projectRoot, "Foo.java");
            var state = MergeAgent.MergeMode.MERGE;

            var cf = new MergeAgent.FileConflict(ourFile, oursContent, theirFile, theirsContent, baseFile, baseContent);

            var mc = new MergeAgent.MergeConflict(state, oursSha, theirsSha, baseSha, Set.of(cf));
            var annotator = new ConflictAnnotator(repo, mc);

            var mergedPreview = annotator.annotate(cf);

            var expected =
                    """
                    package p;
                    import a.A;
                    import c.C;
                    import d.D;

                    class Foo {}
                    """
                            .stripTrailing();
            assertEquals(expected, mergedPreview.contents());
        }
    }

    // ---- helpers (C Git only) ----

    private static void initRepo(Path root) throws Exception {
        runGit(root, "init");
        // configure identity for commits
        runGit(root, "config user.name TestUser");
        runGit(root, "config user.email test@example.com");
    }

    private static void createCommit(Path root, String fileName, String content, String message) throws Exception {
        Files.writeString(root.resolve(fileName), content);
        runGit(root, "add " + fileName);
        // keep message simple to avoid quoting issues in shells
        runGit(root, "commit -m " + message);
    }

    private static void runGit(Path root, String args) throws Exception {
        Environment.instance.runShellCommand("git " + args, root, out -> {}, Environment.UNLIMITED_TIMEOUT);
    }

    private static void runGitAllowFail(Path root, String args) throws Exception {
        try {
            runGit(root, args);
        } catch (Environment.SubprocessException e) {
            // merge/rebase/cherry-pick/revert often return non-zero when conflicts are produced – this is expected
        }
    }

    private static String runGitCapture(Path root, String args) throws Exception {
        try {
            return Environment.instance.runShellCommand("git " + args, root, out -> {}, Environment.UNLIMITED_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static String getCurrentBranch(Path root) throws Exception {
        return runGitCapture(root, "rev-parse --abbrev-ref HEAD").trim();
    }
}
