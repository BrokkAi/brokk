package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void testCheckMergeConflictsRestoresSigningConfig() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Ensure signing is OFF for the actual commit so the test doesn't fail on missing keys
            var config = git.getRepository().getConfig();
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();

            git.commit().setMessage("Initial commit").call();
            git.branchCreate().setName("other").call();

            // Now simulate a user preference of signing being ON
            config.setBoolean("commit", null, "gpgsign", true);
            config.save();

            try (GitRepo repo = new GitRepo(tempDir)) {
                // This will trigger the simulation which toggles signing to false and then restores it
                repo.checkMergeConflicts("other", "master", GitRepo.MergeMode.MERGE_COMMIT);

                // Verify it was restored to true
                assertTrue(
                        repo.getGit().getRepository().getConfig().getBoolean("commit", null, "gpgsign", false),
                        "GPG signing config should be restored to true after merge simulation");

                // Verify it also handles the case where it was initially unset
                config.unset("commit", null, "gpgsign");
                config.save();

                repo.checkMergeConflicts("other", "master", GitRepo.MergeMode.MERGE_COMMIT);

                // Verify it is no longer present in the LOCAL config
                boolean isSetLocally = repo.getGit()
                        .getRepository()
                        .getConfig()
                        .getNames("commit", false)
                        .contains("gpgsign");
                assertTrue(!isSetLocally, "GPG signing config should not be present in local config if it was originally unset");
            }
        }
    }
}
