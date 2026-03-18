package ai.brokk.acpserver.agent;

import org.jspecify.annotations.NullMarked;

/**
 * Exception thrown when an ACP protocol error occurs.
 */
@NullMarked
public class AcpProtocolException extends RuntimeException {

    private final int code;

    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public AcpProtocolException(String message) {
        this(METHOD_NOT_FOUND, message);
    }

    public AcpProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
