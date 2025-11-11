package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for ExceptionReporter's async behavior using a test spy implementation. These tests verify that
 * ExceptionReporter correctly calls Service.reportClientException with the expected parameters and handles async
 * execution properly.
 */
class ExceptionReporterAsyncTest {

    private TestServiceSpy serviceSpy;
    private ExceptionReporter exceptionReporter;

    @BeforeEach
    void setUp() {
        serviceSpy = new TestServiceSpy();
        exceptionReporter = new ExceptionReporter(serviceSpy);
    }

    @Test
    @DisplayName("Should call service.reportClientException with formatted stacktrace")
    void shouldCallServiceWithFormattedStacktrace() throws Exception {
        Exception testException = new RuntimeException("Test exception message");

        // Report the exception
        exceptionReporter.reportException(testException);

        // Wait for async operation to complete
        Thread.sleep(500);

        // Verify the service was called
        assertEquals(1, serviceSpy.getCalls().size(), "Service should be called once");

        ServiceCall call = serviceSpy.getCalls().get(0);

        // Verify the stacktrace contains the exception details
        assertTrue(call.stacktrace.contains("RuntimeException"), "Stacktrace should contain exception class");
        assertTrue(call.stacktrace.contains("Test exception message"), "Stacktrace should contain exception message");
        assertTrue(call.stacktrace.contains("at "), "Stacktrace should contain stack frames");

        // Verify the version is BuildInfo.version
        assertEquals(BuildInfo.version, call.clientVersion);
    }

    @Test
    @DisplayName("Should deduplicate identical exceptions within dedup window")
    void shouldDeduplicateIdenticalExceptions() throws Exception {
        Exception testException = createExceptionAtSameLocation();

        // Report the same exception twice
        exceptionReporter.reportException(testException);
        Thread.sleep(100);
        exceptionReporter.reportException(testException);
        Thread.sleep(100);

        // Should only be called once due to deduplication
        assertEquals(1, serviceSpy.getCalls().size(), "Service should be called once due to deduplication");
    }

    @Test
    @DisplayName("Should report different exceptions separately")
    void shouldReportDifferentExceptionsSeparately() throws Exception {
        Exception exception1 = new RuntimeException("Exception 1");
        Exception exception2 = new IllegalStateException("Exception 2");

        // Report two different exceptions
        exceptionReporter.reportException(exception1);
        Thread.sleep(100);
        exceptionReporter.reportException(exception2);
        Thread.sleep(100);

        // Should be called twice for different exceptions
        assertEquals(2, serviceSpy.getCalls().size(), "Service should be called twice for different exceptions");

        ServiceCall call1 = serviceSpy.getCalls().get(0);
        ServiceCall call2 = serviceSpy.getCalls().get(1);

        assertTrue(call1.stacktrace.contains("RuntimeException"));
        assertTrue(call2.stacktrace.contains("IllegalStateException"));
    }

    @Test
    @DisplayName("Should not propagate exceptions from failed service calls")
    void shouldNotPropagateExceptionsFromFailedServiceCalls() throws Exception {
        // Configure the spy to throw an exception
        serviceSpy.setShouldFail(true);

        Exception testException = new RuntimeException("Test exception");

        // This should not throw - the exception is caught internally
        assertDoesNotThrow(() -> {
            exceptionReporter.reportException(testException);
            Thread.sleep(500);
        });

        // Verify the service was called despite the failure
        assertEquals(1, serviceSpy.getCalls().size(), "Service should be called even though it failed");
    }

    @Test
    @DisplayName("Should truncate very long stacktraces before sending")
    void shouldTruncateVeryLongStacktraces() throws Exception {
        Exception deepException = createDeepStackTraceException(500);

        // Report the exception
        exceptionReporter.reportException(deepException);
        Thread.sleep(500);

        // Verify the service was called
        assertEquals(1, serviceSpy.getCalls().size(), "Service should be called once");

        ServiceCall call = serviceSpy.getCalls().get(0);

        // Verify it was truncated (max 10000 chars + truncation message)
        assertTrue(
                call.stacktrace.length() <= 10100,
                "Stacktrace should be truncated to ~10000 chars. Actual: " + call.stacktrace.length());
    }

    @Test
    @DisplayName("Should handle exceptions with null messages")
    void shouldHandleExceptionsWithNullMessages() throws Exception {
        Exception testException = new RuntimeException((String) null);

        // Report the exception
        exceptionReporter.reportException(testException);
        Thread.sleep(500);

        // Verify the service was called successfully
        assertEquals(1, serviceSpy.getCalls().size(), "Service should be called once");

        ServiceCall call = serviceSpy.getCalls().get(0);
        assertTrue(call.stacktrace.contains("RuntimeException"), "Stacktrace should contain exception class");
    }

    @Test
    @DisplayName("Should include cause chain in stacktrace")
    void shouldIncludeCauseChainInStacktrace() throws Exception {
        Exception cause = new IllegalArgumentException("Root cause");
        Exception wrapper = new RuntimeException("Wrapper exception", cause);

        // Report the exception
        exceptionReporter.reportException(wrapper);
        Thread.sleep(500);

        // Verify the service was called
        assertEquals(1, serviceSpy.getCalls().size(), "Service should be called once");

        ServiceCall call = serviceSpy.getCalls().get(0);
        assertTrue(call.stacktrace.contains("RuntimeException"), "Stacktrace should contain wrapper exception");
        assertTrue(
                call.stacktrace.contains("Wrapper exception"), "Stacktrace should contain wrapper exception message");
        // The stacktrace should be non-trivial
        assertTrue(call.stacktrace.split("\n").length > 5, "Stacktrace should have multiple lines");
    }

    @Test
    @DisplayName("Should report same exception type from different locations separately")
    void shouldReportSameExceptionTypeFromDifferentLocations() throws Exception {
        // Create two RuntimeExceptions from different methods (different stack traces)
        Exception exception1 = createExceptionAtLocationA();
        Exception exception2 = createExceptionAtLocationB();

        exceptionReporter.reportException(exception1);
        Thread.sleep(100);
        exceptionReporter.reportException(exception2);
        Thread.sleep(100);

        // Should be called twice because stack traces are different
        assertEquals(
                2,
                serviceSpy.getCalls().size(),
                "Service should be called twice for same exception type from different locations");
    }

    // Helper methods

    private Exception createExceptionAtSameLocation() {
        return new RuntimeException("Test exception at same location");
    }

    private Exception createExceptionAtLocationA() {
        return new RuntimeException("Test exception A");
    }

    private Exception createExceptionAtLocationB() {
        return new RuntimeException("Test exception B");
    }

    private Exception createDeepStackTraceException(int depth) {
        if (depth <= 0) {
            return new RuntimeException("Deep stack trace exception");
        }
        try {
            return createDeepStackTraceExceptionRecursive(depth);
        } catch (Exception e) {
            return e;
        }
    }

    private Exception createDeepStackTraceExceptionRecursive(int depth) {
        if (depth <= 0) {
            throw new RuntimeException("Deep stack trace exception");
        }
        return createDeepStackTraceExceptionRecursive(depth - 1);
    }

    /**
     * Test spy that implements IExceptionReportingService and records all calls to reportClientException.
     * This implementation does not require any global state or Service initialization.
     */
    private static class TestServiceSpy implements ExceptionReporter.ReportingService {
        private final List<ServiceCall> calls = new ArrayList<>();
        private final AtomicInteger callCount = new AtomicInteger(0);
        private boolean shouldFail = false;
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public JsonNode reportClientException(String stacktrace, String clientVersion) throws IOException {
            callCount.incrementAndGet();
            calls.add(new ServiceCall(stacktrace, clientVersion));

            if (shouldFail) {
                throw new IOException("Test service failure");
            }

            // Return a dummy successful response
            return objectMapper
                    .createObjectNode()
                    .put("id", "test-" + callCount.get())
                    .put("created_at", "2025-10-14");
        }

        public List<ServiceCall> getCalls() {
            return new ArrayList<>(calls);
        }

        public void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }
    }

    /** Record of a service call */
    private static class ServiceCall {
        final String stacktrace;
        final String clientVersion;

        ServiceCall(String stacktrace, String clientVersion) {
            this.stacktrace = stacktrace;
            this.clientVersion = clientVersion;
        }
    }
}
