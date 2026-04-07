package ai.brokk.testutil;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Shared test utilities for executor integration tests.
 */
public final class ExecutorTestUtil {

    private ExecutorTestUtil() {}

    /**
     * Polls the job status endpoint until the job reaches a terminal state (COMPLETED, FAILED, or CANCELLED).
     *
     * @param baseUrl   the base URL of the executor (e.g., "http://127.0.0.1:8080")
     * @param jobId     the job ID to poll
     * @param authToken the auth token for the Authorization header
     * @param timeout   maximum time to wait for completion
     * @throws AssertionError if the job does not complete within the timeout
     */
    public static void awaitJobCompletion(String baseUrl, String jobId, String authToken, Duration timeout)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var statusUrl = URI.create(baseUrl + "/v1/jobs/" + jobId).toURL();
            var conn = (HttpURLConnection) statusUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);

            try (InputStream is = conn.getInputStream()) {
                var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                if (response.contains("\"state\":\"COMPLETED\"")
                        || response.contains("\"state\":\"FAILED\"")
                        || response.contains("\"state\":\"CANCELLED\"")) {
                    return;
                }
            } finally {
                conn.disconnect();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not complete within timeout: " + timeout);
    }
}
