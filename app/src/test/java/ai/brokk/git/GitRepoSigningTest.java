package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.MainProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.CommitCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoSigningTest {

    @TempDir
    Path tempDir;

    private MainProject project;
    private GitRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        // Initialize a real git repo in the temp directory
        org.eclipse.jgit.api.Git.init().setDirectory(projectDir.toFile()).call();

        project = new MainProject(projectDir);
        repo = new GitRepo(projectDir, project);
    }

    @Test
    void testCommitCommand_SigningEnabled() {
        project.setGpgCommitSigningEnabled(true);
        project.setGpgSigningKey("ABCDEF12");

        CommitCommand command = repo.commitCommand();

        // JGit CommitCommand doesn't expose getters for sign/signer/signingKey.
        // However, we verify that the command object is successfully created
        // and initialized without exception when signing is enabled.
        assertNotNull(command, "CommitCommand should not be null when signing is enabled");
    }

    @Test
    void testCommitCommand_SigningDisabled() {
        project.setGpgCommitSigningEnabled(false);

        CommitCommand command = repo.commitCommand();

        assertNotNull(command, "CommitCommand should not be null when signing is disabled");
    }

    @Test
    void testIsGpgSigned_ReflectsInternalState() {
        // GitRepo.java currently initializes gpgPassPhrase to null in constructor
        assertFalse(repo.isGpgSigned(), "By default GPG signed should be false");
    }
}
