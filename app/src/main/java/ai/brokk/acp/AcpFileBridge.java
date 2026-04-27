package ai.brokk.acp;

import ai.brokk.analyzer.ProjectFile;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import java.io.IOException;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Per-prompt seam that routes Brokk's file reads and writes through the ACP client when the client
 * advertises {@code fs.readTextFile} / {@code fs.writeTextFile}, otherwise falls back to direct
 * disk I/O. Installed for the duration of an ACP-driven prompt via {@link #install} and consulted
 * by {@link ai.brokk.io.ProjectFiles}.
 *
 * <p>The bridge propagates to virtual threads through {@link InheritableThreadLocal}, so any work
 * spawned inside {@code BrokkAcpAgent.prompt} sees the same client capability scope.
 */
public final class AcpFileBridge {
    private static final Logger logger = LogManager.getLogger(AcpFileBridge.class);
    private static final InheritableThreadLocal<AcpFileBridge> CURRENT = new InheritableThreadLocal<>();

    private final SyncPromptContext ctx;
    private final boolean canRead;
    private final boolean canWrite;

    private AcpFileBridge(SyncPromptContext ctx, boolean canRead, boolean canWrite) {
        this.ctx = ctx;
        this.canRead = canRead;
        this.canWrite = canWrite;
    }

    public static @Nullable AcpFileBridge current() {
        return CURRENT.get();
    }

    /** Installs a bridge for the current thread; returns an AutoCloseable that restores the prior bridge. */
    public static AutoCloseable install(SyncPromptContext ctx, @Nullable NegotiatedCapabilities caps) {
        boolean canRead = caps != null && caps.supportsReadTextFile();
        boolean canWrite = caps != null && caps.supportsWriteTextFile();
        if (!canRead && !canWrite) {
            return () -> {};
        }
        var previous = CURRENT.get();
        CURRENT.set(new AcpFileBridge(ctx, canRead, canWrite));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public boolean canRead() {
        return canRead;
    }

    public boolean canWrite() {
        return canWrite;
    }

    @Blocking
    public Optional<String> tryRead(ProjectFile file) throws IOException {
        try {
            var content = ctx.readFile(file.absPath().toString());
            return Optional.ofNullable(content);
        } catch (Exception e) {
            // Restore the interrupt flag if reactor wrapped an interruption inside a RuntimeException.
            if (Thread.currentThread().isInterrupted() || isInterruptedCause(e)) {
                Thread.currentThread().interrupt();
            }
            // The bridge says it can read but the call failed (transport, auth, deserialization, …).
            // Surface as IOException so callers don't silently fall back to disk and bypass whatever
            // policy the client was enforcing.
            throw new IOException("ACP fs/read_text_file failed for " + file.absPath(), e);
        }
    }

    @Blocking
    public void write(ProjectFile file, String content) throws IOException {
        try {
            ctx.writeFile(file.absPath().toString(), content);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || isInterruptedCause(e)) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("ACP fs/write_text_file failed for " + file.absPath(), e);
        }
    }

    private static boolean isInterruptedCause(Throwable t) {
        for (var cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
