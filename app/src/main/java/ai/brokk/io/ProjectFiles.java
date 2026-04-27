package ai.brokk.io;

import ai.brokk.acp.AcpFileBridge;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.util.Optional;
import org.jetbrains.annotations.Blocking;

/**
 * Indirection over {@link ProjectFile#read()} and {@link ProjectFile#write(String)} that routes
 * through the ACP client filesystem when an {@link AcpFileBridge} is installed for the current
 * thread, otherwise falls through to direct disk I/O.
 *
 * <p>Use these helpers from agent/tool code (anywhere a Brokk prompt may be running) so unsaved
 * editor buffers in the connected ACP client are honored.
 */
public final class ProjectFiles {

    private ProjectFiles() {}

    @Blocking
    public static Optional<String> read(ProjectFile file) {
        var bridge = AcpFileBridge.current();
        if (bridge != null && bridge.canRead()) {
            var fromClient = bridge.tryRead(file);
            if (fromClient.isPresent()) {
                return fromClient;
            }
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
        file.write(content);
    }
}
