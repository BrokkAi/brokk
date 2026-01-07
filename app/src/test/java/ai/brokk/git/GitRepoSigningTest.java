package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.MainProject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.gpg.signing.GpgBinarySigner;
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
    void testCommitCommand_SigningEnabled() throws Exception {
        project.setGpgCommitSigningEnabled(true);
        project.setGpgSigningKey("ABCDEF12");

        CommitCommand command = repo.commitCommand();

        // JGit CommitCommand doesn't expose getters for sign/signer/signingKey.
        // Use reflection to verify internal state.
        Field signerField = CommitCommand.class.getDeclaredField("signer");
        signerField.setAccessible(true);
        Object signer = signerField.get(command);
        assertInstanceOf(GpgBinarySigner.class, signer, "Signer should be an instance of GpgBinarySigner");

        // signingKey and signCommit are fields in CommitCommand
        Field signingKeyField = getFieldFromHierarchy(CommitCommand.class, "signingKey");
        signingKeyField.setAccessible(true);
        assertEquals("ABCDEF12", signingKeyField.get(command), "Signing key should match project setting");

        Field signField = getFieldFromHierarchy(CommitCommand.class, "signCommit");
        signField.setAccessible(true);
        assertEquals(Boolean.TRUE, signField.get(command), "Signing should be enabled on the command");
    }

    @Test
    void testCommitCommand_SigningDisabled() throws Exception {
        project.setGpgCommitSigningEnabled(false);

        CommitCommand command = repo.commitCommand();

        Field signField = getFieldFromHierarchy(CommitCommand.class, "signCommit");
        signField.setAccessible(true);
        // Note: signCommit field in CommitCommand can be null (default) or Boolean.FALSE
        Object signValue = signField.get(command);
        assertTrue(signValue == null || Boolean.FALSE.equals(signValue), "Signing should be disabled on the command");
    }

    private static Field getFieldFromHierarchy(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    @Test
    void testIsGpgSigned_ReflectsInternalState() {
        // GitRepo.java currently initializes gpgPassPhrase to null in constructor
        assertFalse(repo.isGpgSigned(), "By default GPG signed should be false");
    }
}
