package io.github.jbellis.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.util.ComputedValue;
import java.time.Duration;
import java.util.Optional;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

public class DynamicFragmentDefaultsTest {

    private static final IContextManager CM = new IContextManager() {};

    @Test
    public void virtualFragmentAdapters_returnCompletedValues() {
        var vf = new ContextFragment.VirtualFragment(CM) {
            @Override public ContextFragment.FragmentType getType() { return ContextFragment.FragmentType.STRING; }
            @Override public String description() { return "desc"; }
            @Override public String text() { return "hello"; }
            @Override public boolean isDynamic() { return false; }
            @Override public String syntaxStyle() { return SyntaxConstants.SYNTAX_STYLE_NONE; }
        };

        assertEquals(Optional.of("hello"), vf.computedText().tryGet());
        assertEquals(Optional.of("desc"), vf.computedDescription().tryGet());
        assertEquals(Optional.of(SyntaxConstants.SYNTAX_STYLE_NONE), vf.computedSyntaxStyle().tryGet());
    }

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
