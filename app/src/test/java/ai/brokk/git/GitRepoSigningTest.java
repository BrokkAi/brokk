package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import java.nio.file.Path;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
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
    void testCommitFilesUsesNativeGitWhenSigningEnabled() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Initial").call();
        }

        MainProject.setGpgCommitSigningEnabled(true);
        MainProject.setGpgSigningKey("TESTKEYID");

        var oldFactory = Environment.shellCommandRunnerFactory;
        java.util.concurrent.atomic.AtomicReference<String> capturedCommand =
                new java.util.concurrent.atomic.AtomicReference<>();

        try {
            Environment.shellCommandRunnerFactory = (cmd, projectRoot) -> (outputConsumer, timeout) -> {
                if (cmd.contains("git commit")) {
                    capturedCommand.set(cmd);
                }
                return "";
            };

            try (GitRepo repo = new GitRepo(tempDir)) {
                repo.commitFiles(java.util.List.of(), "Test Message");

                String cmd = capturedCommand.get();
                assertNotNull(cmd);
                assertTrue(cmd.contains("-S"), "Should contain -S flag");
                assertTrue(cmd.contains("-u") && cmd.contains("TESTKEYID"), "Should contain -u flag with key");
                assertTrue(cmd.contains("Test Message"), "Should contain message");
            }
        } finally {
            Environment.shellCommandRunnerFactory = oldFactory;
        }
    }

    @Test
    void testCommitFilesNativeSyntaxWithMultipleFiles() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.commit().setSign(false).setAllowEmpty(true).setMessage("Initial").call();
        }

        MainProject.setGpgCommitSigningEnabled(true);
        MainProject.setGpgSigningKey("KEY");

        var oldFactory = Environment.shellCommandRunnerFactory;
        java.util.concurrent.atomic.AtomicReference<String> capturedCommand =
                new java.util.concurrent.atomic.AtomicReference<>();

        try {
            Environment.shellCommandRunnerFactory = (cmd, projectRoot) -> (outputConsumer, timeout) -> {
                if (cmd.contains("git commit")) {
                    capturedCommand.set(cmd);
                }
                return "";
            };

            try (GitRepo repo = new GitRepo(tempDir)) {
                var file1 = new ProjectFile(tempDir, Path.of("file1.txt"));
                var file2 = new ProjectFile(tempDir, Path.of("file2.txt"));
                repo.commitFiles(java.util.List.of(file1, file2), "Multi-file commit");

                String cmd = capturedCommand.get();
                assertNotNull(cmd);
                // Correct syntax: git commit ... --only -- file1 file2
                assertTrue(cmd.contains("--only -- file1.txt file2.txt"),
                        "Command should contain files after a single --only --. Found: " + cmd);

                // Verify --only doesn't appear multiple times
                int onlyCount = (cmd.length() - cmd.replace("--only", "").length()) / "--only".length();
                assertTrue(onlyCount == 1, "Expected '--only' to appear once, but found " + onlyCount);
            }
        } finally {
            Environment.shellCommandRunnerFactory = oldFactory;
        }
    }

    @Test
    void testCommitFilesUsesDefaultKeyWhenEmpty() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Initial").call();
        }

        MainProject.setGpgCommitSigningEnabled(true);
        MainProject.setGpgSigningKey(""); // Default

        var oldFactory = Environment.shellCommandRunnerFactory;
        java.util.concurrent.atomic.AtomicReference<String> capturedCommand =
                new java.util.concurrent.atomic.AtomicReference<>();

        try {
            Environment.shellCommandRunnerFactory = (cmd, projectRoot) -> (outputConsumer, timeout) -> {
                if (cmd.contains("git commit")) {
                    capturedCommand.set(cmd);
                }
                return "";
            };

            try (GitRepo repo = new GitRepo(tempDir)) {
                repo.commitFiles(java.util.List.of(), "Default Key Test");

                String cmd = capturedCommand.get();
                assertNotNull(cmd);
                assertTrue(cmd.contains("-S"), "Should contain -S flag");
                assertTrue(!cmd.contains("-u"), "Should not contain -u flag when key is empty");
            }
        } finally {
            Environment.shellCommandRunnerFactory = oldFactory;
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
                assertTrue(
                        !isSetLocally,
                        "GPG signing config should not be present in local config if it was originally unset");
            }
        }
    }
}
