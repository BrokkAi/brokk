package ai.brokk.testutil;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.errors.GitAPIException;

import ai.brokk.analyzer.ProjectFile;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class TestGitRepo extends GitRepo {
    private final Path nextWorktreePath;
    private final Map<String, ProjectFile> fileMap = new HashMap<>();

    public TestGitRepo(Path projectRoot) throws IOException {
        this(projectRoot, projectRoot.resolve("next-worktree"));
    }

    public TestGitRepo(Path projectRoot, Path nextWorktreePath) throws IOException {
        super(projectRoot);
        this.nextWorktreePath = nextWorktreePath;
    }

    @Override
    public Path getNextWorktreePath(Path worktreeStorageDir) {
        return nextWorktreePath;
    }

    @Override
    public void addWorktree(String branch, Path path) throws GitAPIException {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create worktree directory", e);
        }
    }

    @Override
    public void createBranch(String newBranchName, String sourceBranchName) throws GitAPIException {
        // no-op for tests
    }

}
