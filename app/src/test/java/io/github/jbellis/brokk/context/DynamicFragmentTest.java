package io.github.jbellis.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.util.ComputedValue;
import java.time.Duration;

import org.junit.jupiter.api.Test;

public class DynamicFragmentTest {
    @Test
    public void dynamicSupport_rendersPlaceholderWithoutBlocking() {
        var slow = new ComputedValue<>("slow", () -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            return "done";
        }, false);

        String rendered = slow.renderNowOr("(loading...)");
        assertEquals("(loading...)", rendered);

        // bounded await should time out and return empty
        assertTrue(slow.await(Duration.ofMillis(10)).isEmpty(),
                   "await with short timeout should return empty");
    }
}
