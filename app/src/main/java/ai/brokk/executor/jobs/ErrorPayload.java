package ai.brokk.executor.jobs;

import org.jetbrains.annotations.Nullable;

/**
 * Structured error response payload.
 *
 * @param code The error code (e.g., "VALIDATION_ERROR", "JOB_NOT_FOUND", "INTERNAL_ERROR").
 * @param message A human-readable error message.
 * @param details Additional details; null if no extra information is available.
 */
public record ErrorPayload(String code, String message, @Nullable String details) {

    /**
     * Validate that code and message are non-blank.
     */
    public ErrorPayload {
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /**
     * Common error codes as constants.
     */
    public static class Code {
        public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
        public static final String JOB_NOT_FOUND = "JOB_NOT_FOUND";
        public static final String JOB_ALREADY_EXISTS = "JOB_ALREADY_EXISTS";
        public static final String UNAUTHORIZED = "UNAUTHORIZED";
        public static final String FORBIDDEN = "FORBIDDEN";
        public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
        public static final String TIMEOUT = "TIMEOUT";
        public static final String CANCELLED = "CANCELLED";
        public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
        public static final String NOT_FOUND = "NOT_FOUND";
        public static final String BAD_REQUEST = "BAD_REQUEST";

        private Code() {}
    }

    /**
     * Create an error without additional details.
     * @param code The error code.
     * @param message The error message.
     * @return A new ErrorPayload with details set to null.
     */
    public static ErrorPayload of(String code, String message) {
        return new ErrorPayload(code, message, null);
    }

    /**
     * Create a validation error.
     * @param message The validation error message.
     * @return A new ErrorPayload with code VALIDATION_ERROR.
     */
    public static ErrorPayload validationError(String message) {
        return of(Code.VALIDATION_ERROR, message);
    }

    /**
     * Create a job-not-found error.
     * @param jobId The job ID that was not found.
     * @return A new ErrorPayload with code JOB_NOT_FOUND.
     */
    public static ErrorPayload jobNotFound(String jobId) {
        return of(Code.JOB_NOT_FOUND, "Job not found: " + jobId);
    }

    /**
     * Create an internal error.
     * @param message The error message.
     * @param throwable The underlying exception (for details).
     * @return A new ErrorPayload with code INTERNAL_ERROR and details from the exception.
     */
    public static ErrorPayload internalError(String message, Throwable throwable) {
        return new ErrorPayload(
                Code.INTERNAL_ERROR, message, throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }
}
