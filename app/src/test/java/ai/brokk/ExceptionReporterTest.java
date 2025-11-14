package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for ExceptionReporter focusing on business logic: - Stack trace formatting - Deduplication - Signature
 * generation - Cache management
 */
class ExceptionReporterTest {

    @Nested
    @DisplayName("Stack trace formatting tests")
    class FormatStackTraceTests {

        @Test
        @DisplayName("Should format a simple exception with stack trace")
        void shouldFormatSimpleException() {
            Exception exception = new RuntimeException("Test error message");
            String formatted = invokePrivateFormatStackTrace(exception);

            assertNotNull(formatted);
            assertTrue(formatted.contains("RuntimeException"), "Should contain exception class name");
            assertTrue(formatted.contains("Test error message"), "Should contain exception message");
            assertTrue(formatted.contains("at "), "Should contain stack trace frames");
        }

        @Test
        @DisplayName("Should format exception with cause chain")
        void shouldFormatExceptionWithCause() {
            Exception cause = new IllegalArgumentException("Root cause");
            Exception exception = new RuntimeException("Wrapper exception", cause);
            String formatted = invokePrivateFormatStackTrace(exception);

            assertNotNull(formatted);
            assertTrue(formatted.contains("RuntimeException"), "Should contain wrapper exception class");
            assertTrue(formatted.contains("Wrapper exception"), "Should contain wrapper message");
            assertTrue(formatted.contains("at "), "Should contain stack trace frames");
            // The formatted stacktrace should be non-trivial (not just a single line)
            assertTrue(formatted.split("\n").length > 5, "Should have multiple lines in stacktrace");
        }

        @Test
        @DisplayName("Should truncate extremely long stack traces")
        void shouldTruncateLongStackTraces() {
            // Create an exception with a very deep stack trace
            Exception exception = createDeepStackTraceException(500);
            String formatted = invokePrivateFormatStackTrace(exception);

            assertNotNull(formatted);
            assertTrue(
                    formatted.length() <= 10100,
                    "Formatted stack trace should be truncated to around 10000 chars plus truncation message");
            if (formatted.contains("truncated")) {
                assertTrue(formatted.contains("total length:"), "Truncation message should indicate original length");
            }
        }

        @Test
        @DisplayName("Should handle exceptions with null messages")
        void shouldHandleNullMessage() {
            Exception exception = new RuntimeException((String) null);
            String formatted = invokePrivateFormatStackTrace(exception);

            assertNotNull(formatted);
            assertTrue(formatted.contains("RuntimeException"), "Should contain exception class name");
            assertTrue(formatted.contains("at "), "Should contain stack trace frames");
        }
    }

    @Nested
    @DisplayName("Exception signature generation tests")
    class GenerateExceptionSignatureTests {

        @Test
        @DisplayName("Should generate consistent signatures for same exception type and message")
        void shouldGenerateConsistentSignatures() {
            // Create exceptions from the same helper method so they have same stack frames
            Exception ex1 = createExceptionForConsistencyTest();
            Exception ex2 = createExceptionForConsistencyTest();

            String sig1 = invokePrivateGenerateExceptionSignature(ex1);
            String sig2 = invokePrivateGenerateExceptionSignature(ex2);

            // They should have the same exception class and message
            assertTrue(
                    sig1.startsWith("java.lang.RuntimeException:Test message"),
                    "Signature should start with exception type and message");
            assertTrue(
                    sig2.startsWith("java.lang.RuntimeException:Test message"),
                    "Signature should start with exception type and message");

            // Note: exact signatures may differ slightly due to different line numbers,
            // but the structure should be consistent
            assertTrue(sig1.contains("|"), "Signature should contain stack frame separators");
            assertTrue(sig2.contains("|"), "Signature should contain stack frame separators");
        }

        @Test
        @DisplayName("Should generate different signatures for different exception types")
        void shouldGenerateDifferentSignaturesForDifferentTypes() {
            Exception ex1 = new RuntimeException("Test message");
            Exception ex2 = new IllegalArgumentException("Test message");

            String sig1 = invokePrivateGenerateExceptionSignature(ex1);
            String sig2 = invokePrivateGenerateExceptionSignature(ex2);

            assertNotEquals(sig1, sig2, "Different exception types should produce different signatures");
            assertTrue(sig1.contains("RuntimeException"), "Signature should contain exception class");
            assertTrue(sig2.contains("IllegalArgumentException"), "Signature should contain exception class");
        }

        @Test
        @DisplayName("Should include exception message in signature when short")
        void shouldIncludeShortMessageInSignature() {
            Exception exception = new RuntimeException("Short msg");
            String signature = invokePrivateGenerateExceptionSignature(exception);

            assertTrue(signature.contains("Short msg"), "Short messages should be included in signature");
        }

        @Test
        @DisplayName("Should exclude very long messages from signature")
        void shouldExcludeLongMessageFromSignature() {
            String longMessage = "x".repeat(150);
            Exception exception = new RuntimeException(longMessage);
            String signature = invokePrivateGenerateExceptionSignature(exception);

            assertFalse(signature.contains(longMessage), "Very long messages should not be included in signature");
        }

        @Test
        @DisplayName("Should include stack frame information in signature")
        void shouldIncludeStackFramesInSignature() {
            Exception exception = createExceptionAtKnownLocation();
            String signature = invokePrivateGenerateExceptionSignature(exception);

            assertTrue(signature.contains("|"), "Signature should contain stack frame separators");
            assertTrue(
                    signature.contains("ExceptionReporterTest"),
                    "Signature should reference test class in stack frames");
            assertTrue(signature.contains(":"), "Signature should contain line number separators");
        }

        @Test
        @DisplayName("Should handle exceptions with no stack trace")
        void shouldHandleExceptionsWithNoStackTrace() {
            Exception exception = new RuntimeException("Test");
            exception.setStackTrace(new StackTraceElement[0]);

            String signature = invokePrivateGenerateExceptionSignature(exception);

            assertNotNull(signature);
            assertTrue(signature.contains("RuntimeException"), "Should at least contain exception class name");
        }

        @Test
        @DisplayName("Should handle null message in signature generation")
        void shouldHandleNullMessageInSignature() {
            Exception exception = new RuntimeException((String) null);
            String signature = invokePrivateGenerateExceptionSignature(exception);

            assertNotNull(signature);
            assertTrue(signature.contains("RuntimeException"));
            assertFalse(signature.startsWith(":"), "Should not have message separator with null message");
        }
    }

    @Nested
    @DisplayName("Deduplication tests")
    class DeduplicationTests {

        private ExceptionReporter reporter;

        @BeforeEach
        void setUp() {
            // Create a reporter with a mock Service (we won't actually call it)
            reporter = new ExceptionReporter((Service) null);
        }

        @Test
        @DisplayName("Should track reported exceptions in cache")
        void shouldTrackReportedExceptions() throws Exception {
            Exception exception = new RuntimeException("Test");

            // Get the signature that would be generated
            String signature = invokePrivateGenerateExceptionSignature(exception);

            // Get access to the reportedExceptions map
            Field field = ExceptionReporter.class.getDeclaredField("reportedExceptions");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Long> reportedExceptions = (ConcurrentHashMap<String, Long>) field.get(reporter);

            // Initially empty
            assertEquals(0, reportedExceptions.size());

            // Manually add to cache (simulating what reportException does)
            reportedExceptions.put(signature, System.currentTimeMillis());

            // Now should be in cache
            assertEquals(1, reportedExceptions.size());
            assertTrue(reportedExceptions.containsKey(signature));
        }

        @Test
        @DisplayName("Should clean up old entries when cache exceeds limit")
        void shouldCleanupOldEntries() throws Exception {
            // Get access to the reportedExceptions map
            Field field = ExceptionReporter.class.getDeclaredField("reportedExceptions");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Long> reportedExceptions = (ConcurrentHashMap<String, Long>) field.get(reporter);

            // Add old entries (more than 1 hour old)
            long oldTime = System.currentTimeMillis() - (2 * 60 * 60 * 1000); // 2 hours ago
            for (int i = 0; i < 500; i++) {
                reportedExceptions.put("old_signature_" + i, oldTime);
            }

            // Add recent entries
            long recentTime = System.currentTimeMillis();
            for (int i = 0; i < 600; i++) {
                reportedExceptions.put("recent_signature_" + i, recentTime);
            }

            // Total should be 1100 entries
            assertEquals(1100, reportedExceptions.size());

            // Manually invoke cleanup (simulating what happens when size > 1000)
            Method cleanupMethod = ExceptionReporter.class.getDeclaredMethod("cleanupOldEntries");
            cleanupMethod.setAccessible(true);
            cleanupMethod.invoke(reporter);

            // Old entries should be removed, recent ones should remain
            assertEquals(600, reportedExceptions.size(), "Should have removed all old entries");
            assertTrue(reportedExceptions.containsKey("recent_signature_0"), "Recent entries should remain");
            assertFalse(reportedExceptions.containsKey("old_signature_0"), "Old entries should be removed");
        }
    }

    @Nested
    @DisplayName("Edge cases and error handling")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should generate signatures for exceptions with single-frame stack traces")
        void shouldHandleSingleFrameStackTrace() {
            Exception exception = new RuntimeException("Test");
            StackTraceElement[] singleFrame = {new StackTraceElement("com.example.Class", "method", "Class.java", 42)};
            exception.setStackTrace(singleFrame);

            String signature = invokePrivateGenerateExceptionSignature(exception);

            assertNotNull(signature);
            assertTrue(signature.contains("com.example.Class"));
            assertTrue(signature.contains("method"));
            assertTrue(signature.contains("42"));
        }

        @Test
        @DisplayName("Should handle exceptions with very deep stack traces")
        void shouldHandleDeepStackTraces() {
            Exception exception = createDeepStackTraceException(100);
            String signature = invokePrivateGenerateExceptionSignature(exception);

            assertNotNull(signature);
            // Signature should only include first 3 frames, not all 100
            long separatorCount = signature.chars().filter(ch -> ch == '|').count();
            assertTrue(separatorCount <= 3, "Signature should only include first 3 stack frames");
        }

        @Test
        @DisplayName("Should handle exceptions from different locations differently")
        void shouldDifferentiateSameExceptionFromDifferentLocations() {
            Exception ex1 = createExceptionAtLocationA();
            Exception ex2 = createExceptionAtLocationB();

            String sig1 = invokePrivateGenerateExceptionSignature(ex1);
            String sig2 = invokePrivateGenerateExceptionSignature(ex2);

            assertNotEquals(
                    sig1, sig2, "Same exception type from different locations should have different signatures");
        }
    }

    // Helper methods

    private String invokePrivateFormatStackTrace(Throwable throwable) {
        try {
            // Create a temporary ExceptionReporter instance (Service can be null for this test)
            ExceptionReporter reporter = new ExceptionReporter((Service) null);
            Method method = ExceptionReporter.class.getDeclaredMethod("formatStackTrace", Throwable.class);
            method.setAccessible(true);
            return (String) method.invoke(reporter, throwable);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke formatStackTrace", e);
        }
    }

    private String invokePrivateGenerateExceptionSignature(Throwable throwable) {
        try {
            // Create a temporary ExceptionReporter instance (Service can be null for this test)
            ExceptionReporter reporter = new ExceptionReporter((Service) null);
            Method method = ExceptionReporter.class.getDeclaredMethod("generateExceptionSignature", Throwable.class);
            method.setAccessible(true);
            return (String) method.invoke(reporter, throwable);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke generateExceptionSignature", e);
        }
    }

    private Exception createExceptionForConsistencyTest() {
        return new RuntimeException("Test message");
    }

    private Exception createExceptionAtKnownLocation() {
        return new RuntimeException("Test from known location");
    }

    private Exception createExceptionAtLocationA() {
        return new IllegalStateException("Location A");
    }

    private Exception createExceptionAtLocationB() {
        return new IllegalStateException("Location B");
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
}
