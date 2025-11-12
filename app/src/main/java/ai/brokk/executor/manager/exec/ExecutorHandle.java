package ai.brokk.executor.manager.exec;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for a running HeadlessExecutorMain process.
 *
 * @param sessionId the session this executor is serving
 * @param execId the unique executor instance ID
 * @param host the host where the executor is listening
 * @param port the port where the executor is listening
 * @param authToken the authentication token for this executor
 * @param process the running process handle
 * @param lastActiveAt timestamp of last known activity
 */
public record ExecutorHandle(
        UUID sessionId,
        UUID execId,
        String host,
        int port,
        String authToken,
        Process process,
        Instant lastActiveAt) {}
