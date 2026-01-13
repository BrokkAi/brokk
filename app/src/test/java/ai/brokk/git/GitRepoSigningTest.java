package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.MainProject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoSigningTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path gpgHome;

    private Path projectDir;
    private MainProject project;
    private GitRepo repo;
    private String testKeyId;

    @BeforeEach
    void setUp() throws Exception {
        projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);

        // Initialize a real git repo in the temp directory
        Git.init().setDirectory(projectDir.toFile()).call();

        project = new MainProject(projectDir);
        repo = new GitRepo(projectDir, project);
    }

    @AfterEach
    void tearDown() {
        if (repo != null) {
            repo.close();
        }
        // Kill gpg-agent for the temp keyring to ensure cleanup
        if (gpgHome != null && Files.exists(gpgHome)) {
            try {
                ProcessBuilder killAgent = new ProcessBuilder("gpgconf", "--kill", "gpg-agent");
                killAgent
                        .environment()
                        .put("GNUPGHOME", gpgHome.toAbsolutePath().toString());
                Process p = killAgent.start();
                p.waitFor();
            } catch (Exception ignored) {
                // Best effort cleanup
            }
        }
    }

    /**
     * Generates a non-interactive GPG key in the temporary GNUPGHOME.
     * Returns the long key ID.
     */
    private String generateTestKey() throws Exception {
        // Verify gpg is available
        ProcessBuilder checkGpg = new ProcessBuilder("gpg", "--version");
        Process versionCheck = checkGpg.start();
        int versionExit = versionCheck.waitFor();
        Assumptions.assumeTrue(versionExit == 0, "gpg command is not available");

        // Create batch key spec for non-interactive key generation
        String keySpec =
                """
                %no-protection
                Key-Type: RSA
                Key-Length: 2048
                Name-Real: Test User
                Name-Email: test@example.com
                Expire-Date: 0
                %commit
                """;

        ProcessBuilder genKey = new ProcessBuilder("gpg", "--batch", "--gen-key");
        genKey.environment().put("GNUPGHOME", gpgHome.toAbsolutePath().toString());
        genKey.redirectErrorStream(true);

        Process process = genKey.start();
        try (PrintWriter writer =
                new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.print(keySpec);
            writer.flush();
        }

        int exitCode = process.waitFor();
        assertTrue(exitCode == 0, "gpg --gen-key failed with exit code " + exitCode);

        // List the generated key to get its ID
        ProcessBuilder listKeys = new ProcessBuilder(
                "gpg", "--list-secret-keys", "--with-colons", "--fixed-list-mode", "--keyid-format=long");
        listKeys.environment().put("GNUPGHOME", gpgHome.toAbsolutePath().toString());

        Process listProcess = listKeys.start();
        String keyId = null;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(listProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":", -1);
                if (parts.length > 4 && "sec".equals(parts[0])) {
                    keyId = parts[4]; // Field 5 is the long key ID
                    break;
                }
            }
        }

        int listExit = listProcess.waitFor();
        assertTrue(listExit == 0, "gpg --list-secret-keys failed");
        assertNotNull(keyId, "Failed to extract generated key ID");

        return keyId;
    }

    @Test
    void testCommitCommand_SigningEnabled() throws Exception {
        // Generate a temporary test key
        testKeyId = generateTestKey();

        project.setGpgCommitSigningEnabled(true);
        project.setGpgSigningKey(testKeyId);

        // Configure git to use the temp GPG home
        // Note: We set gpg.program to a wrapper script that includes GNUPGHOME
        Path gpgWrapper = projectDir.resolve("gpg-wrapper.sh");
        Files.writeString(
                gpgWrapper, "#!/bin/sh\nexport GNUPGHOME=" + gpgHome.toAbsolutePath() + "\nexec gpg \"$@\"\n");
        Files.setPosixFilePermissions(gpgWrapper, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        var config = repo.getRepository().getConfig();
        config.setString("gpg", null, "program", gpgWrapper.toAbsolutePath().toString());
        config.save();

        // Create and sign a commit
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
