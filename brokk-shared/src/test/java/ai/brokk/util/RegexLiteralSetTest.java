package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RegexLiteralSetTest {
    @Test
    void caseInsensitiveLiteralPatternBuildsPrecomputedAsciiMatchers() {
        var search = RegexLiteralSet.fromContainsPattern(
                        Pattern.compile("(?i).*?(?:\\Qmethod1\\E|\\QField2\\E).*?"), null)
                .orElseThrow();

        assertTrue(search.caseInsensitive());
        assertEquals(List.of("method1", "Field2"), search.literals());
        assertEquals(2, search.caseInsensitiveLiterals().size());
        assertTrue(search.caseInsensitiveLiterals().stream().anyMatch(literal -> literal.containedIn("A.METHOD1")));
        assertTrue(search.caseInsensitiveLiterals().stream().anyMatch(literal -> literal.containedIn("D.field2")));
    }

    @Test
    void caseSensitiveLiteralPatternDoesNotBuildCaseInsensitiveMatchers() {
        var search = RegexLiteralSet.fromContainsPattern(Pattern.compile(".*?\\Qmethod1\\E.*?"), null)
                .orElseThrow();

        assertFalse(search.caseInsensitive());
        assertEquals(List.of("method1"), search.literals());
        assertTrue(search.caseInsensitiveLiterals().isEmpty());
    }

    @Test
    void nonAsciiCaseInsensitiveLiteralFallsBackToRegex() {
        var dottedCapitalI = Character.toString(0x0130);

        assertTrue(
                RegexLiteralSet.fromContainsPattern(Pattern.compile("(?i).*?\\Q" + dottedCapitalI + "ndex\\E.*?"), null)
                        .isEmpty());
    }

    @Test
    void substringFilterBuildsPrecomputedAsciiMatcher() {
        var search = RegexLiteralSet.fromContainsPattern(Pattern.compile("(?i).*?\\Qmethod1\\E.*?"), "method1")
                .orElseThrow();

        assertTrue(search.caseInsensitive());
        assertEquals(List.of("method1"), search.literals());
        assertEquals(1, search.caseInsensitiveLiterals().size());
        assertTrue(search.caseInsensitiveLiterals().getFirst().containedIn("A.METHOD1"));
    }
}
