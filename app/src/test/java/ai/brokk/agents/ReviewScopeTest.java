package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.SpecialTextType;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;

class ReviewScopeTest {
    @Test
    void testFromContext_roundTrip() throws IOException, GitAPIException, ReviewScope.ReviewLoadException {
        // Create a project with git and make changes
        var project = InlineTestProjectCreator.code("line1\n", "file.txt")
                .withGit()
                .addCommit("file.txt", "file.txt")
                .build();

        var repo = (ai.brokk.git.GitRepo) project.getRepo();
        String initialCommit = repo.getCurrentCommitId();

        // Make a change and commit
        ProjectFile pf = new ProjectFile(project.getRoot(), "file.txt");
        Files.writeString(pf.absPath(), "line1\nline2\n");
        repo.add(pf);
        repo.commitCommand().setMessage("Add line2").call();

        IContextManager cm = new TestContextManager(project);

        // Create a ReviewScope using fromBaseline
        var originalScope = ReviewScope.fromBaseline(cm, initialCommit, "HEAD");
        assertNotNull(originalScope);
        assertFalse(originalScope.changes().perFileChanges().isEmpty());

        // Simulate what ReviewAgent does: create context with special fragments
        var diffText = originalScope.changes().perFileChanges().stream()
                .map(fd -> {
                    String oldName = fd.oldFile() == null
                            ? "/dev/null"
                            : "a/" + fd.oldFile().toString();
                    String newName = fd.newFile() == null
                            ? "/dev/null"
                            : "b/" + fd.newFile().toString();
                    return ai.brokk.util.ContentDiffUtils.computeDiffResult(
                                    fd.oldText(), fd.newText(), oldName, newName)
                            .diff();
                })
                .collect(java.util.stream.Collectors.joining("\n\n"));

        var context = cm.liveContext()
                .withSpecial(SpecialTextType.REVIEW_DIFF, diffText)
                .withSpecial(
                        SpecialTextType.REVIEW_METADATA,
                        originalScope.metadata().toJson());

        // Now reconstruct from context
        var reconstructed = ReviewScope.fromContext(cm, context);
        assertEquals(
                originalScope.metadata().fromRef(), reconstructed.metadata().fromRef());
        assertEquals(originalScope.metadata().toRef(), reconstructed.metadata().toRef());
        assertEquals(
                originalScope.changes().perFileChanges().size(),
                reconstructed.changes().perFileChanges().size());

        project.close();
    }

    @Test
    void testFromBaseline_SingleCommit() throws IOException, GitAPIException {
        var project = InlineTestProjectCreator.code("line1\n", "file.txt")
                .withGit()
                .addCommit("file.txt", "file.txt")
                .build();

        var repo = (ai.brokk.git.GitRepo) project.getRepo();
        String parentCommit = repo.getCurrentCommitId();

        // Add exactly one more commit
        ProjectFile pf = new ProjectFile(project.getRoot(), "file.txt");
        Files.writeString(pf.absPath(), "line1\nline2\n");
        repo.add(pf);
        var commit = repo.commitCommand().setMessage("Single change").call();
        String commitId = commit.getId().getName();

        IContextManager cm = new TestContextManager(project);

        // This is the scenario that was failing: selecting a single commit (not WORKING)
        // fromRef should be commit^, toRef should be commit
        var scope = ReviewScope.fromBaseline(cm, commitId + "^", commitId);

        assertEquals(repo.resolveToCommit(parentCommit).name(), scope.metadata().fromRef());
        assertEquals(repo.resolveToCommit(commitId).name(), scope.metadata().toRef());
        assertEquals(1, scope.changes().perFileChanges().size());

        project.close();
    }

    @Test
    void testFromContext_missingMetadata() throws IOException {
        var project = InlineTestProjectCreator.code("test\n", "test.txt")
                .withGit()
                .addCommit("test.txt", "test.txt")
                .build();

        IContextManager cm = new TestContextManager(project);
        var context = cm.liveContext(); // No special fragments

        assertThrows(ReviewScope.ReviewLoadContextException.class, () -> {
            ReviewScope.fromContext(cm, context);
        });

        project.close();
    }

    @Test
    void testExtractSessionContext_emptySessionIds_returnsEmptyList() throws IOException {
        var project = InlineTestProjectCreator.code("test\n", "test.txt").build();
        IContextManager cm = new TestContextManager(project);

        var results = ReviewScope.extractSessionContext(cm, List.of(), Set.of());

        assertTrue(results.isEmpty());
        project.close();
    }
}
