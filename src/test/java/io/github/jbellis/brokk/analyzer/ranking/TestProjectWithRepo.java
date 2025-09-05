package io.github.jbellis.brokk.analyzer.ranking;

import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.testutil.TestProject;
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
