package ai.brokk.executor.manager.provision;

import java.nio.file.Path;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Specification for provisioning a session environment.
 *
 * @param sessionId the unique session identifier
 * @param repoPath the path to the git repository to use
 * @param ref the git ref (branch, tag, or commit) to check out; null for current HEAD
 */
public record SessionSpec(UUID sessionId, Path repoPath, @Nullable String ref) {
    public SessionSpec {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        if (repoPath == null) {
            throw new IllegalArgumentException("repoPath must not be null");
        }
    }
}
