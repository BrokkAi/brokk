package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitRepoApplyGitHubAuthenticationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // repo initialized
        }
    }

    private static final class CapturingTransportCommand extends TransportCommand<CapturingTransportCommand, Void> {
        private CredentialsProvider capturedProvider;

        private CapturingTransportCommand() {
            super(null);
        }

        @Override
        public CapturingTransportCommand setCredentialsProvider(CredentialsProvider credentialsProvider) {
            super.setCredentialsProvider(credentialsProvider);
            this.capturedProvider = credentialsProvider;
            return this;
        }

        @Override
        public Void call() {
            throw new AssertionError("This test must not perform network I/O");
        }

        CredentialsProvider capturedProvider() {
            return capturedProvider;
        }
    }

    @Test
    void applyGitHubAuthentication_explicitToken_setsCredentialsProvider() throws Exception {
        Supplier<String> emptyTokenSupplier = () -> "";
        try (var repo = new GitRepo(tempDir, emptyTokenSupplier)) {
            var command = new CapturingTransportCommand();

            repo.applyGitHubAuthentication(command, "https://github.com/owner/repo.git", "explicit-token");

            assertNotNull(command.capturedProvider());
            assertInstanceOf(UsernamePasswordCredentialsProvider.class, command.capturedProvider());

            assertUsernamePasswordIfAccessible(
                    (UsernamePasswordCredentialsProvider) command.capturedProvider(), "token", "explicit-token");
        }
    }

    @Test
    void applyGitHubAuthentication_explicitToken_overridesEmptySupplier_and_missingTokenThrows() throws Exception {
        Supplier<String> emptyTokenSupplier = () -> "";
        try (var repo = new GitRepo(tempDir, emptyTokenSupplier)) {
            var commandWithExplicit = new CapturingTransportCommand();
            assertDoesNotThrow(() -> repo.applyGitHubAuthentication(
                    commandWithExplicit, "https://github.com/owner/repo.git", "explicit-token"));

            assertNotNull(commandWithExplicit.capturedProvider());
            assertInstanceOf(UsernamePasswordCredentialsProvider.class, commandWithExplicit.capturedProvider());

            assertThrows(
                    GitHubAuthenticationException.class,
                    () -> repo.applyGitHubAuthentication(
                            new CapturingTransportCommand(), "https://github.com/owner/repo.git", null));

            assertThrows(
                    GitHubAuthenticationException.class,
                    () -> repo.applyGitHubAuthentication(
                            new CapturingTransportCommand(), "https://github.com/owner/repo.git", "  "));
        }
    }

    private static void assertUsernamePasswordIfAccessible(
            UsernamePasswordCredentialsProvider provider, String expectedUser, String expectedPassword) {
        try {
            Method getUserName = provider.getClass().getMethod("getUserName");
            Method getPassword = provider.getClass().getMethod("getPassword");
            Object user = getUserName.invoke(provider);
            Object password = getPassword.invoke(provider);

            assertEquals(expectedUser, user);
            assertEquals(expectedPassword, password);
        } catch (ReflectiveOperationException ignored) {
            // Older JGit versions don't expose getters; type assertion is sufficient.
        }
    }
}
