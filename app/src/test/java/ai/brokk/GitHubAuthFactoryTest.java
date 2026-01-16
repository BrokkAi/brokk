package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitHubAuthFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createForProject_withJobToken_doesNotDependOnGlobalTokenOrGitRemote() throws Exception {
        MainProject.setGitHubToken("");

        IProject project = new IProject() {
            @Override
            public Path getRoot() {
                return tempDir;
            }

            @Override
            public IssueProvider getIssuesProvider() {
                return IssueProvider.github("owner", "repo");
            }

            @Override
            public ai.brokk.git.IGitRepo getRepo() {
                throw new AssertionError("getRepo() must not be called when issues provider supplies owner/repo");
            }
        };

        var auth = GitHubAuth.createForProject(project, "job-token");
        assertNotNull(auth);
    }
}
