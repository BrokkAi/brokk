package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConflictInspectorTest {
    /**
     * Parse a single-file BRK_* annotated conflict into structured sections so tests can assert on exact lines in
     * OUR/BASE/THEIR blocks without relying on brittle substring matching.
     */
    private static record AnnotatedLine(String shaShort, String text) {}

    private static record ParsedAnnotated(
            String oursHeaderShort,
            String baseHeaderShort,
            String theirsHeaderShort,
            java.util.List<AnnotatedLine> ours,
            java.util.List<AnnotatedLine> base,
            java.util.List<AnnotatedLine> theirs) {}

    private static ParsedAnnotated parseAnnotated(String annotated) {
        var ours = new java.util.ArrayList<AnnotatedLine>();
        var base = new java.util.ArrayList<AnnotatedLine>();
        var theirs = new java.util.ArrayList<AnnotatedLine>();
        String oursHeader = null, baseHeader = null, theirsHeader = null;

        enum Section {
            NONE,
            OURS,
            BASE,
            THEIRS
        }
        var section = Section.NONE;

        for (var line : annotated.split("\n", -1)) {
            if (line.startsWith("BRK_OUR_VERSION ")) {
                oursHeader = line.substring("BRK_OUR_VERSION ".length()).trim();
                section = Section.OURS;
                continue;
            }
            if (line.startsWith("BRK_BASE_VERSION ")) {
                baseHeader = line.substring("BRK_BASE_VERSION ".length()).trim();
                section = Section.BASE;
                continue;
            }
            if (line.startsWith("BRK_THEIR_VERSION ")) {
                theirsHeader = line.substring("BRK_THEIR_VERSION ".length()).trim();
                section = Section.THEIRS;
                continue;
            }
            if (line.startsWith("BRK_CONFLICT_BEGIN") || line.startsWith("BRK_CONFLICT_END")) {
                continue; // not content
            }

            // Only lines inside a section should have SHA + space + content.
            if (section != Section.NONE) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    var sha = line.substring(0, sp);
                    var text = line.substring(sp + 1);
                    switch (section) {
                        case OURS -> ours.add(new AnnotatedLine(sha, text));
                        case BASE -> base.add(new AnnotatedLine(sha, text));
                        case THEIRS -> theirs.add(new AnnotatedLine(sha, text));
                        default -> {}
                    }
                }
            }
        }

        return new ParsedAnnotated(oursHeader, baseHeader, theirsHeader, ours, base, theirs);
    }

    // Simple IProject implementation to avoid anonymous class literal in source (prevents test filter parsing issues)
    private static final class TestProject implements IProject {
        private final Path root;
        private final IGitRepo repo;

        private TestProject(Path root, IGitRepo repo) {
            this.root = root;
            this.repo = repo;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public IGitRepo getRepo() {
            return repo;
        }
    }

    private static void initRepo(Path root) throws Exception {
        runGit(root, "init");
        runGit(root, "config user.name TestUser");
        runGit(root, "config user.email test@example.com");
    }

    private static void runGit(Path root, String args) throws Exception {
        Environment.instance.runShellCommand("git " + args, root, out -> {}, Environment.UNLIMITED_TIMEOUT);
    }

    private static void runGitAllowFail(Path root, String args) throws Exception {
        try {
            runGit(root, args);
        } catch (Environment.SubprocessException e) {
            // expected for conflict-producing commands
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

    private static IProject makeProject(Path root, GitRepo repo) {
        return new TestProject(root, repo);
    }

    /** Simple content conflict: validate mode, commits, and that it's actually a content conflict. */
    @Test
    void detectsMergeConflictAndCommits() throws Exception {
        Path projectRoot = Files.createTempDirectory("ci-merge");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            Files.writeString(projectRoot.resolve("common.txt"), "A\nB\n");
            runGit(projectRoot, "add common.txt");
            runGit(projectRoot, "commit -m base");
            var baseSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            var mainBranch = getCurrentBranch(projectRoot);

            // feature branch modifies B -> Y
            runGit(projectRoot, "branch feature");
            runGit(projectRoot, "checkout feature");
            Files.writeString(projectRoot.resolve("common.txt"), "A\nY\n");
            runGit(projectRoot, "commit -am c_feature");
            var featureSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // main branch modifies B -> X
            runGit(projectRoot, "checkout " + mainBranch);
            Files.writeString(projectRoot.resolve("common.txt"), "A\nX\n");
            runGit(projectRoot, "commit -am c_main");
            var mainSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // merge -> conflict
            runGitAllowFail(projectRoot, "merge feature");

            var conflict = ConflictInspector.inspectFromProject(makeProject(projectRoot, repo));
            assertEquals(MergeAgent.MergeMode.MERGE, conflict.state());
            assertEquals(mainSha, conflict.ourCommitId());
            assertEquals(featureSha, conflict.otherCommitId());
            assertEquals(repo.shortHash(baseSha), repo.shortHash(conflict.baseCommitId()));
            assertEquals(1, conflict.files().size());

            var cf = conflict.files().iterator().next();
            assertEquals(
                    new ProjectFile(projectRoot, "common.txt").getRelPath(),
                    cf.ourFile().getRelPath());
            assertEquals(
                    new ProjectFile(projectRoot, "common.txt").getRelPath(),
                    cf.theirFile().getRelPath());
            assertNotNull(cf.baseFile());
            assertTrue(cf.isContentConflict()); // ensure we're not passing due to a non-content scenario
        }
    }

    /**
     * Rename-on-ours vs modify-on-theirs conflict: verify that - historical paths are correct (ours renamed) - BRK
     * headers use the expected short ids - blame inside OUR/THEIR sections points to the correct commits - the
     * annotator reports the discovered commit ids
     */
    @Test
    void annotateTracksBlameAcrossRename() throws Exception {
        Path projectRoot = Files.createTempDirectory("ci-rename");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // base
            Files.writeString(projectRoot.resolve("common.txt"), "L1\nL2\n");
            runGit(projectRoot, "add common.txt");
            runGit(projectRoot, "commit -m base");
            var baseSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            var mainBranch = getCurrentBranch(projectRoot);

            // feature from base: change L2 -> Y2
            runGit(projectRoot, "branch feature");
            runGit(projectRoot, "checkout feature");
            Files.writeString(projectRoot.resolve("common.txt"), "L1\nY2\n");
            runGit(projectRoot, "commit -am c_feature");
            var featureSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // back to main: rename and commit X2 then tweak L1 so X2's blame should be from first ours commit
            runGit(projectRoot, "checkout " + mainBranch);
            runGit(projectRoot, "mv common.txt renamed.txt");
            Files.writeString(projectRoot.resolve("renamed.txt"), "L1\nX2\n");
            runGit(projectRoot, "commit -am ours1");
            var ours1Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            Files.writeString(projectRoot.resolve("renamed.txt"), "X1\nX2\n");
            runGit(projectRoot, "commit -am ours2");
            var ours2Sha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // merge -> conflict (rename/modify vs modify)
            runGitAllowFail(projectRoot, "merge feature");

            var conflict = ConflictInspector.inspectFromProject(makeProject(projectRoot, repo));
            assertEquals(MergeAgent.MergeMode.MERGE, conflict.state());

            var cf = conflict.files().iterator().next();
            // Ensure paths reflect rename on our side
            assertEquals(
                    new ProjectFile(projectRoot, "renamed.txt").getRelPath(),
                    cf.ourFile().getRelPath());
            assertEquals(
                    new ProjectFile(projectRoot, "common.txt").getRelPath(),
                    cf.theirFile().getRelPath());
            assertTrue(cf.isContentConflict());

            var annotator = new ConflictAnnotator(repo, conflict);
            var annotated = annotator.annotate(cf);
            var parsed = parseAnnotated(annotated.contents());

            // Headers should show HEAD on ours, base, and feature on theirs
            assertEquals(repo.shortHash(ours2Sha), parsed.oursHeaderShort());
            assertEquals(repo.shortHash(baseSha), parsed.baseHeaderShort());
            assertEquals(repo.shortHash(featureSha), parsed.theirsHeaderShort());

            // our changed line should be attributed to first ours commit (ours1)
            assertTrue(parsed.ours().stream()
                    .anyMatch(l -> l.shaShort().equals(repo.shortHash(ours1Sha))
                            && l.text().equals("X2")));

            // their changed line should be attributed to feature commit
            assertTrue(parsed.theirs().stream()
                    .anyMatch(l -> l.shaShort().equals(repo.shortHash(featureSha))
                            && l.text().equals("Y2")));

            // Annotator should surface these commits in its sets
            assertTrue(annotated.ourCommits().contains(ours1Sha));
            assertTrue(annotated.theirCommits().contains(featureSha));
        }
    }

    /**
     * Modify/delete conflict: ensure 'ours' content is null, but path is present, and that this is identified as a
     * non-content conflict.
     */
    @Test
    void detectsDeleteModifyConflict() throws Exception {
        Path projectRoot = Files.createTempDirectory("ci-delmod");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // base
            Files.writeString(projectRoot.resolve("dm.txt"), "BASE\n");
            runGit(projectRoot, "add dm.txt");
            runGit(projectRoot, "commit -m base");
            var baseSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            var mainBranch = getCurrentBranch(projectRoot);

            // feature from base: modify file
            runGit(projectRoot, "branch feature");
            runGit(projectRoot, "checkout feature");
            Files.writeString(projectRoot.resolve("dm.txt"), "THEIRS\n");
            runGit(projectRoot, "commit -am theirs_mod");
            var featureSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // back to main: delete file
            runGit(projectRoot, "checkout " + mainBranch);
            runGit(projectRoot, "rm dm.txt");
            runGit(projectRoot, "commit -m ours_del");
            var oursDelSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // merge -> modify/delete conflict
            runGitAllowFail(projectRoot, "merge feature");

            var conflict = ConflictInspector.inspectFromProject(makeProject(projectRoot, repo));
            assertEquals(MergeAgent.MergeMode.MERGE, conflict.state());
            assertEquals(oursDelSha, conflict.ourCommitId());
            assertEquals(featureSha, conflict.otherCommitId());
            assertEquals(1, conflict.files().size());

            var cf = conflict.files().iterator().next();
            // path is dm.txt on both sides
            assertEquals(
                    new ProjectFile(projectRoot, "dm.txt").getRelPath(),
                    cf.ourFile().getRelPath());
            assertEquals(
                    new ProjectFile(projectRoot, "dm.txt").getRelPath(),
                    cf.theirFile().getRelPath());

            // ours deleted: expect null contents for our side
            assertNull(cf.ourContent());
            // theirs modified: expect contents present
            assertNotNull(cf.theirContent());
            assertTrue(cf.theirContent().contains("THEIRS"));

            // base exists: expect base contents present
            assertNotNull(cf.baseFile());
            assertNotNull(cf.baseContent());
            assertTrue(cf.baseContent().contains("BASE"));

            // sanity: base sha shortened matches
            assertEquals(repo.shortHash(baseSha), repo.shortHash(conflict.baseCommitId()));

            // And: this is not a content conflict
            assertTrue(!cf.isContentConflict());
        }
    }

    /**
     * Add/add conflict: base is absent and both sides supply content. This *is* a content conflict for our pipeline
     * (both sides have blobs), so ConflictAnnotator is allowed to run a 2-way merge against an empty base.
     */
    @Test
    void detectsAddAddConflict() throws Exception {
        Path projectRoot = Files.createTempDirectory("ci-addadd");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // make an initial base commit
            Files.writeString(projectRoot.resolve("README"), "init\n");
            runGit(projectRoot, "add README");
            runGit(projectRoot, "commit -m base");
            var mainBranch = getCurrentBranch(projectRoot);

            // feature from base: add file
            runGit(projectRoot, "branch feature");
            runGit(projectRoot, "checkout feature");
            Files.writeString(projectRoot.resolve("aa.txt"), "THEIRS\n");
            runGit(projectRoot, "add aa.txt");
            runGit(projectRoot, "commit -m theirs_add");
            var featureSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // back to main: add file with different contents
            runGit(projectRoot, "checkout " + mainBranch);
            Files.writeString(projectRoot.resolve("aa.txt"), "OURS\n");
            runGit(projectRoot, "add aa.txt");
            runGit(projectRoot, "commit -m ours_add");
            var oursSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // merge -> add/add conflict
            runGitAllowFail(projectRoot, "merge feature");

            var conflict = ConflictInspector.inspectFromProject(makeProject(projectRoot, repo));
            assertEquals(MergeAgent.MergeMode.MERGE, conflict.state());
            assertEquals(oursSha, conflict.ourCommitId());
            assertEquals(featureSha, conflict.otherCommitId());
            assertEquals(1, conflict.files().size());

            var cf = conflict.files().iterator().next();
            // both sides are aa.txt; base does not exist
            assertEquals(
                    new ProjectFile(projectRoot, "aa.txt").getRelPath(),
                    cf.ourFile().getRelPath());
            assertEquals(
                    new ProjectFile(projectRoot, "aa.txt").getRelPath(),
                    cf.theirFile().getRelPath());

            // base absent for add/add
            assertNull(cf.baseFile());
            assertNull(cf.baseContent());

            // both sides have contents
            assertNotNull(cf.ourContent());
            assertTrue(cf.ourContent().contains("OURS"));
            assertNotNull(cf.theirContent());
            assertTrue(cf.theirContent().contains("THEIRS"));

            // By design, add/add is treated as a content conflict (both sides have blobs).
            assertTrue(cf.isContentConflict());
        }
    }

    /**
     * During REBASE, ConflictAnnotator should show the 'onto' commit (not the cherry-picked OTHER) in the
     * BRK_OUR_VERSION header. Verify header ids and per-line blame on both sides.
     */
    @Test
    void annotateHeaderUsesOntoInRebase() throws Exception {
        Path projectRoot = Files.createTempDirectory("ci-rebase");
        initRepo(projectRoot);

        try (var repo = new GitRepo(projectRoot)) {
            // base
            Files.writeString(projectRoot.resolve("rb.txt"), "L1\n");
            runGit(projectRoot, "add rb.txt");
            runGit(projectRoot, "commit -m base");
            var baseSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();
            var mainBranch = getCurrentBranch(projectRoot);

            // feature from base: modify line -> Y1
            runGit(projectRoot, "branch feature");
            runGit(projectRoot, "checkout feature");
            Files.writeString(projectRoot.resolve("rb.txt"), "Y1\n");
            runGit(projectRoot, "commit -am feature_mod");
            var featureSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // back to main: modify line -> X1
            runGit(projectRoot, "checkout " + mainBranch);
            Files.writeString(projectRoot.resolve("rb.txt"), "X1\n");
            runGit(projectRoot, "commit -am main_mod");
            var ontoSha = runGitCapture(projectRoot, "rev-parse HEAD").trim();

            // rebase feature onto main -> conflict
            runGit(projectRoot, "checkout feature");
            runGitAllowFail(projectRoot, "rebase " + mainBranch);

            var conflict = ConflictInspector.inspectFromProject(makeProject(projectRoot, repo));
            assertEquals(MergeAgent.MergeMode.REBASE, conflict.state());
            // base of the cherry-picked OTHER is the original base
            assertEquals(repo.shortHash(baseSha), repo.shortHash(conflict.baseCommitId()));

            var cf = conflict.files().iterator().next();
            assertTrue(cf.isContentConflict());

            var annotator = new ConflictAnnotator(repo, conflict);
            var annotated = annotator.annotate(cf);
            var parsed = parseAnnotated(annotated.contents());

            // OUR header should reflect the 'onto' commit
            assertEquals(repo.shortHash(ontoSha), parsed.oursHeaderShort());
            // THEIR header reflects the commit being replayed by rebase
            assertEquals(repo.shortHash(featureSha), parsed.theirsHeaderShort());
            assertEquals(repo.shortHash(baseSha), parsed.baseHeaderShort());

            // OUR block line should be from onto commit (X1)
            assertTrue(parsed.ours().stream()
                    .anyMatch(l -> l.shaShort().equals(repo.shortHash(ontoSha))
                            && l.text().equals("X1")));

            // THEIR block line should be from feature commit (Y1)
            assertTrue(parsed.theirs().stream()
                    .anyMatch(l -> l.shaShort().equals(repo.shortHash(featureSha))
                            && l.text().equals("Y1")));
        }
    }
}
