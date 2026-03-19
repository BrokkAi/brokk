package ai.brokk.acpserver.agent;

import ai.brokk.acpserver.transport.JsonRpcMessage;

/**
 * Exception thrown when an ACP protocol error occurs.
 */
public class AcpProtocolException extends RuntimeException {

    private final int code;

    public static final int METHOD_NOT_FOUND = JsonRpcMessage.RpcError.METHOD_NOT_FOUND;
    public static final int INVALID_PARAMS = JsonRpcMessage.RpcError.INVALID_PARAMS;
    public static final int INTERNAL_ERROR = JsonRpcMessage.RpcError.INTERNAL_ERROR;

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
