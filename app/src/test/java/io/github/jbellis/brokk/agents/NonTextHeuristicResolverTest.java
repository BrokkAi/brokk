package io.github.jbellis.brokk.agents;

import static io.github.jbellis.brokk.agents.MergeAgent.FileConflict;
import static io.github.jbellis.brokk.agents.MergeAgent.NonTextMetadata;
import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class NonTextHeuristicResolverTest {

    @TempDir
    Path tempRepoDir;

    GitRepo repo;

    @BeforeEach
    void setUp() throws GitAPIException, IOException {
        GitRepo.initRepo(tempRepoDir);
        repo = new GitRepo(tempRepoDir);
    }

    private IProject projectProxy(GitRepo r, Path root) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(), new Class<?>[] {IProject.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getRepo")) {
                        return r;
                    } else if (name.equals("getRoot")) {
                        return root;
                    }
                    throw new UnsupportedOperationException("Not implemented: " + name);
                });
    }

    @Test
    void testDeleteModify() throws Exception {
        // 1) Base commit with a file
        var file = new ProjectFile(tempRepoDir, "a.txt");
        Files.writeString(file.absPath(), "base\n");
        repo.commitFiles(List.of(file), "base");

        // Determine base branch name
        String baseBranch = repo.getCurrentBranch();

        // 2) Create feature branch that modifies the file
        String featureBranch = "feature";
        repo.createAndCheckoutBranch(featureBranch, baseBranch);
        Files.writeString(file.absPath(), "feature change\n");
        repo.commitFiles(List.of(file), "modify a.txt");

        // 3) Create delete branch from base and delete the file
        repo.checkout(baseBranch);
        String deleteBranch = "delete";
        repo.createAndCheckoutBranch(deleteBranch, baseBranch);
        repo.getGit().rm().addFilepattern("a.txt").call();
        repo.commitCommand().setMessage("delete a.txt").call();

        // 4) Merge feature into delete to cause DELETE/MODIFY conflict
        MergeResult mergeResult = repo.mergeIntoHead(featureBranch);
        assertTrue(GitRepo.hasConflicts(mergeResult), "Expected conflicts during merge");

        // 5) Inspect conflicts using ConflictInspector
        var project = projectProxy(repo, tempRepoDir);
        var mergeConflict = ConflictInspector.inspectFromProject(project);

        // Find DELETE_MODIFY entry
        var dmEntries = mergeConflict.orElseThrow().nonText().entrySet().stream()
                .filter(e -> e.getValue().type() == NonTextType.DELETE_MODIFY)
                .toList();
        assertFalse(dmEntries.isEmpty(), "Expected at least one DELETE_MODIFY conflict");

        // 6) Plan using heuristic (group of one is fine here)
        List<Map.Entry<FileConflict, NonTextMetadata>> group =
                dmEntries.stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList();

        var plan = NonTextHeuristicResolver.plan(group);
        assertFalse(plan.ops().isEmpty(), "Expected a non-empty plan for DELETE_MODIFY");

        // 7) Apply
        NonTextHeuristicResolver.apply(repo, tempRepoDir, plan.ops());

        // 8) Assert conflict is resolved
        Status status = repo.getGit().status().call();
        assertTrue(status.getConflicting().isEmpty(), "There should be no conflicting paths");

        // 9) Assert working tree contains the modified file (heuristic chooses the side with content)
        assertTrue(Files.exists(file.absPath()), "a.txt should exist after resolving DELETE_MODIFY");
        String resolved = Files.readString(file.absPath());
        // Normalize line endings for cross-platform compatibility
        assertEquals("feature change\n", resolved.replaceAll("\\r\\n", "\n"));
    }

    @Test
    void testRenameModify() throws Exception {
        // TODO: implement later
    }

    @Test
    void testRenameRename() throws Exception {
        // TODO: implement later
    }

    @Test
    void testFileDirectory() throws Exception {
        // TODO: implement later
    }

    @Test
    void testAddAddBinary() throws Exception {
        // TODO: implement later
    }

    @Test
    void testModeBit() throws Exception {
        // TODO: implement later
    }

    @Test
    void testSubmoduleConflict() {
        // TODO: implement later
    }
}
