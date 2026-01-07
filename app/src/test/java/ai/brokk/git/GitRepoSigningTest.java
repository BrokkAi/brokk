package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.MainProject;
import ai.brokk.util.GpgKeyUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoSigningTest {

    @TempDir
    Path tempDir;

    private Path projectDir;
    private MainProject project;
    private GitRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        // Initialize a real git repo in the temp directory
        org.eclipse.jgit.api.Git.init().setDirectory(projectDir.toFile()).call();

        project = new MainProject(projectDir);
        repo = new GitRepo(projectDir, project);
    }

    @Test
    void testCommitCommand_SigningEnabled() throws Exception {
        // JGit's GpgProcessRunner spawns gpg as a child process which inherits the JVM's
        // environment, not our custom SystemReader. We cannot inject GNUPGHOME from Java.
        // Fall back to using keys from the user's real keyring if available.
        List<GpgKeyUtil.GpgKey> systemKeys = GpgKeyUtil.listSecretKeys();
        Assumptions.assumeFalse(systemKeys.isEmpty(),
                "No GPG secret keys available in system keyring - skipping signing test");

        String keyId = systemKeys.getFirst().id();
        project.setGpgCommitSigningEnabled(true);
        project.setGpgSigningKey(keyId);

        Path file = projectDir.resolve("signed.txt");
        Files.writeString(file, "content");
        repo.add(new ProjectFile(projectDir, projectDir.relativize(file)));

        repo.commitCommand().setMessage("Signed commit").call();

        try (RevWalk walk = new RevWalk(repo.getRepository())) {
            RevCommit commit = walk.parseCommit(repo.getRepository().resolve("HEAD"));
            byte[] signature = commit.getRawGpgSignature();
            assertNotNull(signature, "Commit should have a GPG signature");
            assertTrue(signature.length > 0, "Signature should not be empty");
        }
    }

    @Test
    void testCommitCommand_SigningDisabled() throws Exception {
        project.setGpgCommitSigningEnabled(false);

        Path file = projectDir.resolve("unsigned.txt");
        Files.writeString(file, "content");
        repo.add(new ProjectFile(projectDir, projectDir.relativize(file)));

        repo.commitCommand().setMessage("Unsigned commit").call();

        try (RevWalk walk = new RevWalk(repo.getRepository())) {
            RevCommit commit = walk.parseCommit(repo.getRepository().resolve("HEAD"));
            assertNull(commit.getRawGpgSignature(), "Commit should NOT have a GPG signature");
        }
    }

    @Test
    void testIsGpgSigned_ReflectsInternalState() {
        // GitRepo.java currently initializes gpgPassPhrase to null in constructor
        assertFalse(repo.isGpgSigned(), "By default GPG signed should be false");
    }
}
