package io.github.jbellis.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.util.ComputedValue;
import java.time.Duration;
import java.util.Optional;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

public class DynamicFragmentTest {
    @Test
    public void dynamicSupport_rendersPlaceholderWithoutBlocking() {
        var slow = new ComputedValue<>("slow", () -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            return "done";
        }, false);

        String rendered = DynamicSupport.renderNowOr("(loading...)", slow);
        assertEquals("(loading...)", rendered);

        // bounded await should time out and return empty
        assertTrue(DynamicSupport.await(Duration.ofMillis(10), slow).isEmpty(),
                   "await with short timeout should return empty");
    }
}
