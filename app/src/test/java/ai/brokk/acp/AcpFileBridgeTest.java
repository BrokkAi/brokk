package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import com.agentclientprotocol.sdk.agent.Command;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AcpFileBridge}: capability gating, install/restore lifecycle, and the
 * {@code IOException}-wrapping contract for client-side fs round trips.
 *
 * <p>Bug-protective focus: when the bridge says it can read/write but the client returns an
 * error, code MUST surface an {@link IOException} rather than silently falling back to disk —
 * a fallback would bypass whatever sandbox or permission policy the ACP client was enforcing.
 */
class AcpFileBridgeTest {

    @AfterEach
    void clearBridgeAfterEach() {
        // Defensive: any test that forgets to close its installed bridge must not leak into the
        // next test's thread. A leaked InheritableThreadLocal here would let later tests observe
        // a stale bridge via AcpFileBridge.current().
        var leaked = AcpFileBridge.current();
        assertNull(leaked, "test leaked an installed bridge: " + leaked);
    }

    @Test
    void installWithNullCapabilitiesReturnsNoOpCloseable() throws Exception {
        var ctx = new RecordingContext();
        try (var scope = AcpFileBridge.install(ctx, null)) {
            assertNotNull(scope);
            // No bridge is registered when neither capability is advertised.
            assertNull(AcpFileBridge.current());
        }
        assertNull(AcpFileBridge.current());
    }

    @Test
    void installWithBothCapsFalseReturnsNoOpCloseable() throws Exception {
        var ctx = new RecordingContext();
        var caps = new NegotiatedCapabilities.Builder()
                .readTextFile(false)
                .writeTextFile(false)
                .build();
        try (var scope = AcpFileBridge.install(ctx, caps)) {
            assertNotNull(scope);
            assertNull(AcpFileBridge.current());
        }
    }

    @Test
    void installWithReadOnlyCapsAllowsReadOnly(@TempDir Path tempDir) throws Exception {
        var ctx = new RecordingContext();
        ctx.readContent = "hello\n";
        var caps = new NegotiatedCapabilities.Builder().readTextFile(true).build();
        try (var ignored = AcpFileBridge.install(ctx, caps)) {
            var bridge = AcpFileBridge.current();
            assertNotNull(bridge);
            assertTrue(bridge.canRead());
            assertFalse(bridge.canWrite());

            var pf = new ProjectFile(tempDir.toAbsolutePath().normalize(), Path.of("foo.txt"));
            var read = bridge.tryRead(pf);
            assertEquals(Optional.of("hello\n"), read);
            assertEquals(pf.absPath().toString(), ctx.lastReadPath);
        }
    }

    @Test
    void installRestoresPreviousBridgeOnClose(@TempDir Path tempDir) throws Exception {
        var outerCtx = new RecordingContext();
        var innerCtx = new RecordingContext();
        var caps = new NegotiatedCapabilities.Builder().readTextFile(true).build();

        try (var outer = AcpFileBridge.install(outerCtx, caps)) {
            var outerBridge = AcpFileBridge.current();
            assertNotNull(outerBridge);

            try (var inner = AcpFileBridge.install(innerCtx, caps)) {
                var innerBridge = AcpFileBridge.current();
                assertNotNull(innerBridge);
                // Innermost install wins inside the inner scope.
                assertSame(innerBridge, AcpFileBridge.current());
            }

            // Closing the inner scope must reinstate the outer bridge, not null it.
            assertSame(outerBridge, AcpFileBridge.current());
        }
        assertNull(AcpFileBridge.current());
    }

    @Test
    void tryReadSuccessRoutesToContext(@TempDir Path tempDir) throws Exception {
        var ctx = new RecordingContext();
        ctx.readContent = "abc";
        var caps = new NegotiatedCapabilities.Builder().readTextFile(true).build();

        try (var ignored = AcpFileBridge.install(ctx, caps)) {
            var bridge = AcpFileBridge.current();
            assertNotNull(bridge);
            var pf = new ProjectFile(tempDir.toAbsolutePath().normalize(), Path.of("nested/dir/x.txt"));
            assertEquals(Optional.of("abc"), bridge.tryRead(pf));
            assertEquals(pf.absPath().toString(), ctx.lastReadPath);
        }
    }

    @Test
    void tryReadNullFromContextReturnsEmptyOptional(@TempDir Path tempDir) throws Exception {
        // ctx.readContent stays null — the bridge maps that to Optional.empty without throwing,
        // so callers can distinguish "client returned no content" from "the client errored out".
        var ctx = new RecordingContext();
        var caps = new NegotiatedCapabilities.Builder().readTextFile(true).build();

        try (var ignored = AcpFileBridge.install(ctx, caps)) {
            var bridge = AcpFileBridge.current();
            assertNotNull(bridge);
            var pf = new ProjectFile(tempDir.toAbsolutePath().normalize(), Path.of("missing.txt"));
            assertEquals(Optional.empty(), bridge.tryRead(pf));
        }
    }

    @Test
    void tryReadFailureSurfacesAsIOException(@TempDir Path tempDir) throws Exception {
        // Without this wrap, callers like ProjectFiles silently fall back to direct disk I/O,
        // bypassing whatever sandbox/permission policy the ACP client was enforcing — exactly
        // the failure mode the bridge exists to prevent.
        var ctx = new RecordingContext();
        ctx.readError = new RuntimeException("transport down");
        var caps = new NegotiatedCapabilities.Builder().readTextFile(true).build();

        try (var ignored = AcpFileBridge.install(ctx, caps)) {
            var bridge = AcpFileBridge.current();
            assertNotNull(bridge);
            var pf = new ProjectFile(tempDir.toAbsolutePath().normalize(), Path.of("foo.txt"));
            var ex = assertThrows(IOException.class, () -> bridge.tryRead(pf));
            assertTrue(
                    ex.getMessage().contains("ACP fs/read_text_file failed"), "unexpected message: " + ex.getMessage());
            assertNotNull(ex.getCause());
        }
    }

    @Test
    void tryReadInterruptedCauseRestoresThreadInterruptFlag(@TempDir Path tempDir) {
        var ctx = new RecordingContext();
        ctx.readError = new RuntimeException("wrapper", new InterruptedException("wrapped"));
        var caps = new NegotiatedCapabilities.Builder().readTextFile(true).build();

        boolean wasInterrupted;
        try (var ignored = AcpFileBridge.install(ctx, caps)) {
            var bridge = AcpFileBridge.current();
            assertNotNull(bridge);
            // Clear any stale flag so we measure the bridge's effect, not the test runner's.
            assertFalse(Thread.interrupted(), "test pre-condition: interrupt flag was set");

            var pf = new ProjectFile(tempDir.toAbsolutePath().normalize(), Path.of("foo.txt"));
            assertThrows(IOException.class, () -> bridge.tryRead(pf));
            wasInterrupted = Thread.interrupted();
        } catch (Exception e) {
            // install's AutoCloseable signature requires Exception
            throw new RuntimeException(e);
        }
        assertTrue(
                wasInterrupted, "interrupt flag must be restored when the cause chain contains InterruptedException");
    }

    @Test
    void writeSuccessRoutesToContext(@TempDir Path tempDir) throws Exception {
        var ctx = new RecordingContext();
        var caps = new NegotiatedCapabilities.Builder().writeTextFile(true).build();

        try (var ignored = AcpFileBridge.install(ctx, caps)) {
            var bridge = AcpFileBridge.current();
            assertNotNull(bridge);
            assertFalse(bridge.canRead());
            assertTrue(bridge.canWrite());

            var pf = new ProjectFile(tempDir.toAbsolutePath().normalize(), Path.of("out.txt"));
            bridge.write(pf, "payload");
            assertEquals(pf.absPath().toString(), ctx.lastWritePath);
            assertEquals("payload", ctx.lastWriteContent);
        }
    }

    @Test
    void writeFailureSurfacesAsIOException(@TempDir Path tempDir) throws Exception {
        var ctx = new RecordingContext();
        ctx.writeError = new RuntimeException("disk full");
        var caps = new NegotiatedCapabilities.Builder().writeTextFile(true).build();

        try (var ignored = AcpFileBridge.install(ctx, caps)) {
            var bridge = AcpFileBridge.current();
            assertNotNull(bridge);
            var pf = new ProjectFile(tempDir.toAbsolutePath().normalize(), Path.of("out.txt"));
            var ex = assertThrows(IOException.class, () -> bridge.write(pf, "payload"));
            assertTrue(
                    ex.getMessage().contains("ACP fs/write_text_file failed"),
                    "unexpected message: " + ex.getMessage());
            assertNotNull(ex.getCause());
        }
    }

    /** Minimal {@link SyncPromptContext} that only implements the methods the bridge calls. */
    private static final class RecordingContext implements SyncPromptContext {
        @Nullable
        String readContent;

        @Nullable
        String lastReadPath;

        @Nullable
        RuntimeException readError;

        @Nullable
        String lastWritePath;

        @Nullable
        String lastWriteContent;

        @Nullable
        RuntimeException writeError;

        @Override
        public String readFile(String path) {
            lastReadPath = path;
            if (readError != null) {
                throw readError;
            }
            return readContent;
        }

        @Override
        public String readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount) {
            return readFile(path);
        }

        @Override
        public Optional<String> tryReadFile(String path) {
            return Optional.ofNullable(readContent);
        }

        @Override
        public void writeFile(String path, String content) {
            if (writeError != null) {
                throw writeError;
            }
            lastWritePath = path;
            lastWriteContent = content;
        }

        @Override
        public void sendUpdate(String sessionId, AcpSchema.SessionUpdate update) {}

        @Override
        public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NegotiatedCapabilities getClientCapabilities() {
            return new NegotiatedCapabilities.Builder().build();
        }

        @Override
        public String getSessionId() {
            return "test-session";
        }

        @Override
        public void sendMessage(String text) {}

        @Override
        public void sendThought(String text) {}

        @Override
        public boolean askPermission(String action) {
            return true;
        }

        @Override
        public @Nullable String askChoice(String question, String... options) {
            return options.length > 0 ? options[0] : null;
        }

        @Override
        public CommandResult execute(String... commandAndArgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandResult execute(Command command) {
            throw new UnsupportedOperationException();
        }
    }
}
