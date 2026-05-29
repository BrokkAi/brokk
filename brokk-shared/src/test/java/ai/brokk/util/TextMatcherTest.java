package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextMatcherTest {
    @Test
    void compileRejectsBackreferencesBeforeMatching() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> TextMatcher.compile("for\\s+(\\w+)\\s+in\\s+.*self\\.assertEqual\\(\\1,\\s*\\1\\)", 0));

        assertTrue(error.getMessage().contains("backreferences"));
    }

    @Test
    void compileRejectsNestedUnboundedQuantifiersBeforeMatching() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> TextMatcher.compile("for\\s+\\w+\\s+in\\s+.*:\\s+(?:[^\\n]+\\n\\s+)*self\\.assertEqual", 0));

        assertTrue(error.getMessage().contains("nested unbounded regex quantifiers"));
    }

    @Test
    void compileStillAllowsSimpleRegex() {
        var matcher = TextMatcher.compile("foo.*bar", 0);

        assertInstanceOf(TextMatcher.Regex.class, matcher);
        assertTrue(matcher.find("fooXYZbar", null));
    }

    @Test
    void compileStillFallsBackToLiteralForInvalidRegex() {
        var matcher = TextMatcher.compile("_show_welcome_message(", 0);

        assertInstanceOf(TextMatcher.Literal.class, matcher);
        assertTrue(matcher.find("def _show_welcome_message(self):", null));
    }
}
