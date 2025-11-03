package ai.brokk.agents;

public class FatalLlmException extends RuntimeException {
    public FatalLlmException(String message) {
        super(message);
    }
}
