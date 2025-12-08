package ai.brokk.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.Test;

public class LlmTimeoutsTest {

    @Test
    public void isTimeout_http504_returnsTrue() {
        Throwable t = new HttpException(504, "Gateway timeout");
        assertTrue(LlmTimeouts.isTimeout(t));
    }

    @Test
    public void isTimeout_http503_returnsFalse() {
        Throwable t = new HttpException(503, "Service unavailable");
        assertFalse(LlmTimeouts.isTimeout(t));
    }

    @Test
    public void isTimeout_otherThrowable_returnsFalse() {
        Throwable t = new RuntimeException("boom");
        assertFalse(LlmTimeouts.isTimeout(t));
    }
}
