package ai.brokk.io;

import ai.brokk.acp.AcpFileBridge;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * Indirection over {@link ProjectFile#read()} and {@link ProjectFile#write(String)} that routes
 * through the ACP client filesystem when an {@link AcpFileBridge} is installed for the current
 * thread, otherwise falls through to direct disk I/O.
 *
 * <p>Use these helpers from agent/tool code (anywhere a Brokk prompt may be running) so unsaved
 * editor buffers in the connected ACP client are honored.
 *
 * <p>Semantics when a bridge is installed:
 * <ul>
 *   <li>Read: if {@link AcpFileBridge#canRead()} is true, the bridge is the source of truth.
 *       Transport/permission failures from the client surface as an empty {@code Optional} (with a
 *       logged warning) — we do NOT silently fall back to disk, since that would bypass any policy
 *       the client was enforcing on the read side.
 *   <li>Write: if {@link AcpFileBridge#canWrite()} is true, the bridge is the source of truth.
 *       Failures propagate as {@link IOException}. If the client advertised read but not write,
 *       writes go straight to disk (the asymmetry is the client's choice; we log a warning the
 *       first time it happens to flag the inconsistency).
 * </ul>
 */
public final class ProjectFiles {
    private static final Logger logger = LogManager.getLogger(ProjectFiles.class);

    private ProjectFiles() {}

    @Blocking
    public static Optional<String> read(ProjectFile file) {
        var bridge = AcpFileBridge.current();
        if (bridge != null && bridge.canRead()) {
            try {
                return bridge.tryRead(file);
            } catch (IOException e) {
                logger.warn("ACP read failed for {}; not falling back to disk: {}", file.absPath(), e.getMessage());
                return Optional.empty();
            }
        }
        if (bridge != null && !bridge.canWrite()) {
            // Client opted into read-only mode. Reads are bridged above; this branch is unreachable
            // for the read path but documents the asymmetry. (Writes hit disk below — see #write.)
        }
        return file.read();
    }

    @Blocking
    public static void write(ProjectFile file, String content) throws IOException {
        var bridge = AcpFileBridge.current();
        if (bridge != null && bridge.canWrite()) {
            bridge.write(file, content);
            return;
        }
        if (bridge != null && bridge.canRead() && !bridge.canWrite()) {
            // Client supports the read side but not the write side. Brokk will write to disk; the
            // client's editor buffer for this file (if any) may now be stale relative to disk.
            logger.warn(
                    "ACP client capability asymmetry: fs.readTextFile is supported but fs.writeTextFile is not. "
                            + "Writing {} to disk; the client's editor buffer may diverge.",
                    file.absPath());
        }
        file.write(content);
    }
}
