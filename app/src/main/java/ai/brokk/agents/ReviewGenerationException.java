package ai.brokk.agents;

import ai.brokk.TaskResult;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Exception thrown when review generation fails due to LLM or processing errors.
 */
@NullMarked
public class ReviewGenerationException extends Exception {

    private final @Nullable TaskResult.StopDetails stopDetails;

    public ReviewGenerationException(String message) {
        super(message);
        this.stopDetails = null;
    }

    public ReviewGenerationException(String message, TaskResult.StopDetails stopDetails) {
        super(message + ": " + stopDetails.explanation());
        this.stopDetails = stopDetails;
    }

    public ReviewGenerationException(String message, Throwable cause) {
        super(message, cause);
        this.stopDetails = null;
    }

    public @Nullable TaskResult.StopDetails getStopDetails() {
        return stopDetails;
    }
}
