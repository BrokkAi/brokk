package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.MainProject;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CommitCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoSigningTest {

    @TempDir
    Path tempDir;

    @Test
    void testCommitCommandConfigurationWhenSigningDisabled() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call();
        
        MainProject.setGpgCommitSigningEnabled(false);
        
        try (GitRepo repo = new GitRepo(tempDir)) {
            CommitCommand cmd = repo.commitCommand();
            // JGit doesn't provide a public getter for isSign, but we verify it doesn't throw
            assertNotNull(cmd);
            assertTrue(!repo.isGpgSigned());
        }
    }

    @Test
    void testCommitCommandConfigurationWhenSigningEnabled() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call();

        MainProject.setGpgCommitSigningEnabled(true);
        MainProject.setGpgSigningKey("TESTKEYID");

        try (GitRepo repo = new GitRepo(tempDir)) {
            CommitCommand cmd = repo.commitCommand();
            assertNotNull(cmd);
            assertTrue(repo.isGpgSigned());

            // Verify the config was updated in memory
            String configKey = repo.getGit().getRepository().getConfig().getString("user", null, "signingkey");
            assertEquals("TESTKEYID", configKey);
        }
    }

    @Test
    void testCommitCommandConfigurationWithDefaultKey() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call();
        
        MainProject.setGpgCommitSigningEnabled(true);
        MainProject.setGpgSigningKey(""); // Empty means default
        
        try (GitRepo repo = new GitRepo(tempDir)) {
            CommitCommand cmd = repo.commitCommand();
            assertNotNull(cmd);
            assertTrue(repo.isGpgSigned());
        }
    }
}
