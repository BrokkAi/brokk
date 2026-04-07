package ai.brokk.github;

public class DeviceFlowException extends Exception {
    public enum ErrorType {
        NETWORK_ERROR,
        SERVER_ERROR,
        RATE_LIMITED,
        INVALID_RESPONSE,
        USER_DENIED,
        DEVICE_EXPIRED,
        UNKNOWN
    }

    private final ErrorType errorType;

    public DeviceFlowException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public DeviceFlowException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
