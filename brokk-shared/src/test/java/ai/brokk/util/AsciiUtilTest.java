package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AsciiUtilTest {
    @Test
    void containsIgnoreCaseMatchesEmptyNeedle() {
        assertTrue(AsciiUtil.containsIgnoreCase("Haystack", ""));
        assertTrue(new AsciiUtil.CaseInsensitiveLiteral("").containedIn("Haystack"));
    }

    @Test
    void containsIgnoreCaseRejectsNeedleLongerThanHaystack() {
        assertFalse(AsciiUtil.containsIgnoreCase("short", "longer needle"));
        assertFalse(new AsciiUtil.CaseInsensitiveLiteral("longer needle").containedIn("short"));
    }

    @Test
    void containsIgnoreCaseMatchesOneCharacterNeedle() {
        assertTrue(AsciiUtil.containsIgnoreCase("abc", "B"));
        assertTrue(new AsciiUtil.CaseInsensitiveLiteral("B").containedIn("abc"));
        assertFalse(AsciiUtil.containsIgnoreCase("abc", "D"));
    }

    @Test
    void containsIgnoreCaseMatchesMixedCaseAscii() {
        assertTrue(AsciiUtil.containsIgnoreCase("com.Example.Service", "example.service"));
        assertTrue(new AsciiUtil.CaseInsensitiveLiteral("EXAMPLE.SERVICE").containedIn("com.Example.Service"));
    }

    @Test
    void containsIgnoreCaseSkipsStartsWithMissingFirstCharacter() {
        assertFalse(AsciiUtil.containsIgnoreCase("bbbbbbbb", "Ab"));
        assertFalse(new AsciiUtil.CaseInsensitiveLiteral("Ab").containedIn("bbbbbbbb"));
    }

    @Test
    void containsIgnoreCaseContinuesAfterPartialCandidateMatch() {
        assertTrue(AsciiUtil.containsIgnoreCase("abxAbC", "abc"));
        assertTrue(new AsciiUtil.CaseInsensitiveLiteral("abc").containedIn("abxAbC"));
    }
}
