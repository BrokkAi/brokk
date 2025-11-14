package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for ExceptionReporter that makes real HTTP calls to the staging server. This test is disabled by
 * default to avoid making network calls during normal test runs.
 *
 * <p>To run this test:
 *
 * <ol>
 *   <li>Set your staging API key via environment variable or system property:
 *       <ul>
 *         <li>Environment: export BROKK_STAGING_API_KEY="brk+your-key-here"
 *         <li>System property: ./gradlew test --tests ExceptionReporterIntegrationTest
 *             -Dbrokk.staging.api.key="brk+your-key-here"
 *       </ul>
 *   <li>Remove or comment out the @Disabled annotation
 *   <li>Run: ./gradlew test --tests ExceptionReporterIntegrationTest
 * </ol>
 */
@Disabled("Integration test that requires staging server - run manually when needed")
class ExceptionReporterIntegrationTest {

    private Service service;
    private String originalBrokkKey;
    private MainProject.LlmProxySetting originalProxySetting;
    private String stagingApiKey;

    @BeforeEach
    void setUp() {
        // Get staging API key from environment or system property
        stagingApiKey = System.getenv("BROKK_STAGING_API_KEY");
        if (stagingApiKey == null) {
            stagingApiKey = System.getProperty("brokk.staging.api.key");
        }

        if (stagingApiKey == null || stagingApiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Staging API key not set. Set BROKK_STAGING_API_KEY environment variable or brokk.staging.api.key system property");
        }

        // Save original settings so we can restore them after the test
        originalBrokkKey = MainProject.getBrokkKey();
        originalProxySetting = MainProject.getProxySetting();

        // Configure for staging server
        MainProject.setLlmProxySetting(MainProject.LlmProxySetting.STAGING);
        MainProject.setBrokkKey(stagingApiKey);

        // Create a minimal mock project for testing
        IProject mockProject = new MinimalMockProject();
        service = new Service(mockProject);

        System.out.println("✓ Test setup complete");
        System.out.println("  Service URL: " + MainProject.getServiceUrl());
        System.out.println("  API Key: " + stagingApiKey.substring(0, 20) + "...");
    }

    @AfterEach
    void tearDown() {
        // Restore original settings
        if (originalBrokkKey != null) {
            MainProject.setBrokkKey(originalBrokkKey);
        }
        if (originalProxySetting != null) {
            MainProject.setLlmProxySetting(originalProxySetting);
        }
    }

    /**
     * Minimal mock implementation of IProject for testing purposes. This provides only the minimal functionality needed
     * to create a Service instance.
     */
    private static class MinimalMockProject implements IProject {
        @Override
        public Path getRoot() {
            return Path.of(System.getProperty("java.io.tmpdir"));
        }

        @Override
        public MainProject.DataRetentionPolicy getDataRetentionPolicy() {
            return MainProject.DataRetentionPolicy.MINIMAL;
        }

        // All other methods use default implementations from IProject interface
    }

    @Test
    @DisplayName("Should successfully report exception to staging server")
    void shouldReportExceptionToStagingServer() throws IOException {
        // Create a test exception with a realistic stack trace
        Exception testException = createTestException();

        // Format the stack trace
        String stacktrace = formatStackTrace(testException);

        // Report to staging server and verify response
        var response = service.reportClientException(stacktrace, BuildInfo.version);

        // Verify the response structure
        assertNotNull(response, "Response should not be null");
        assertTrue(response.has("id"), "Response should have an 'id' field");
        assertTrue(response.has("created_at"), "Response should have a 'created_at' field");

        System.out.println("✓ Successfully reported exception to staging server");
        System.out.println("  Exception: " + testException.getClass().getName());
        System.out.println("  Message: " + testException.getMessage());
        System.out.println("  Client version: " + BuildInfo.version);
        System.out.println("  Server response: " + response);
    }

    @Test
    @DisplayName("Should handle exception with null message")
    void shouldHandleExceptionWithNullMessage() throws IOException {
        Exception testException = new RuntimeException((String) null);
        String stacktrace = formatStackTrace(testException);

        var response = service.reportClientException(stacktrace, BuildInfo.version);

        assertNotNull(response, "Response should not be null");
        System.out.println("✓ Successfully reported exception with null message");
    }

    @Test
    @DisplayName("Should handle exception with very long stack trace")
    void shouldHandleExceptionWithLongStackTrace() throws IOException {
        Exception testException = createDeepStackTraceException(100);
        String stacktrace = formatStackTrace(testException);

        var response = service.reportClientException(stacktrace, BuildInfo.version);

        assertNotNull(response, "Response should not be null");
        System.out.println("✓ Successfully reported exception with deep stack trace");
        System.out.println("  Stack trace length: " + stacktrace.length() + " chars");
    }

    @Test
    @DisplayName("Should handle exception with cause chain")
    void shouldHandleExceptionWithCauseChain() throws IOException {
        Exception cause = new IllegalStateException("Root cause of the problem");
        Exception wrapper = new RuntimeException("Wrapper exception", cause);
        String stacktrace = formatStackTrace(wrapper);

        var response = service.reportClientException(stacktrace, BuildInfo.version);

        assertNotNull(response, "Response should not be null");
        System.out.println("✓ Successfully reported exception with cause chain");
    }

    @Test
    @DisplayName("Should fail gracefully with invalid API key")
    void shouldFailGracefullyWithInvalidApiKey() {
        // Temporarily set invalid API key
        MainProject.setBrokkKey("invalid-key");

        Exception testException = createTestException();
        String stacktrace = formatStackTrace(testException);

        // Should throw exception due to authentication failure
        assertThrows(Exception.class, () -> service.reportClientException(stacktrace, BuildInfo.version));

        // Restore valid key for subsequent tests
        MainProject.setBrokkKey(stagingApiKey);

        System.out.println("✓ Correctly rejected invalid API key");
    }

    // Helper methods

    private Exception createTestException() {
        try {
            // Create a realistic exception by actually throwing one
            throw new RuntimeException(
                    "Integration test exception - this is a test from ExceptionReporterIntegrationTest");
        } catch (Exception e) {
            return e;
        }
    }

    private Exception createDeepStackTraceException(int depth) {
        if (depth <= 0) {
            return new RuntimeException("Deep stack trace test exception");
        }
        try {
            return createDeepStackTraceExceptionRecursive(depth);
        } catch (Exception e) {
            return e;
        }
    }

    private Exception createDeepStackTraceExceptionRecursive(int depth) {
        if (depth <= 0) {
            throw new RuntimeException("Deep stack trace test exception");
        }
        return createDeepStackTraceExceptionRecursive(depth - 1);
    }

    private String formatStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }
}
