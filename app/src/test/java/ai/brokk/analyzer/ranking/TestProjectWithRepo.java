package ai.brokk.analyzer.ranking;

import ai.brokk.analyzer.Language;
import ai.brokk.git.GitRepo;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;

public class TestProjectWithRepo extends TestProject {
    private final GitRepo repo;

    TestProjectWithRepo(Path root, Language language, GitRepo repo) {
        super(root, language);
        this.repo = repo;
    }

    @Override
    public GitRepo getRepo() {
        return repo;
    }
}
