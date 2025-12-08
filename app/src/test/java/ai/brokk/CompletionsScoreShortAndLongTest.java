package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompletionsScoreShortAndLongTest {

    private record Candidate(String id, String shortText, String longText, int tie) {}

    @Test
    @DisplayName("scoreShortAndLong: keeps long form when it scores better than the short form")
    void keepsLongWhenBetterThanBestShort() {
        // Pattern that clearly favors the long exact match over a gappy short match
        String pattern = "foo";

        var longBetter = new Candidate("LB", "f_o_o_bar", "foo", 0);

        List<ShorthandCompletion> results = Completions.scoreShortAndLong(
                pattern,
                List.of(longBetter),
                Candidate::shortText,
                Candidate::longText,
                Candidate::tie,
                c -> new ShorthandCompletion(null, c.id(), c.longText()));

        // The candidate should be present because its long form ("foo") beats its short form.
        assertTrue(
                results.stream().anyMatch(sc -> "LB".equals(sc.getInputText())),
                "Expected long-better candidate to be included");
    }

    @Test
    @DisplayName("scoreShortAndLong: filters out long matches worse than the best short match")
    void filtersLongWorseThanBestShort() {
        // Construct candidates such that:
        // - SG has an exact short match ("Chr") -> best short score (lowest).
        // - SB has a poor short match ("C_h_r_bar") -> high (worse) score among shorts.
        // - LM has only a long mid-word match ("zzzChrzzz") that is worse than the best short ("Chr"),
        //   and must be excluded even with the tolerance band.
        String pattern = "Chr";

        var shortGood = new Candidate("SG", "Chr", "irrelevant", 0);
        var shortBad = new Candidate("SB", "C_h_r_bar", "irrelevant", 0);
        var longMidWord = new Candidate("LM", "zzzz", "zzzChrzzz", 0);

        List<ShorthandCompletion> results = Completions.scoreShortAndLong(
                pattern,
                List.of(shortGood, shortBad, longMidWord),
                Candidate::shortText,
                Candidate::longText,
                Candidate::tie,
                c -> new ShorthandCompletion(null, c.id(), c.longText()));

        // Must include the best short ("SG").
        assertTrue(
                results.stream().anyMatch(sc -> "SG".equals(sc.getInputText())),
                "Expected best short candidate to be included");

        // With the corrected policy and hardened inputs, "LM" should be filtered out because its long match is worse
        // than the best short match.
        assertFalse(
                results.stream().anyMatch(sc -> "LM".equals(sc.getInputText())),
                "Expected long-worse-than-best-short candidate to be excluded");
    }

    @Test
    @DisplayName("scoreShortAndLong: honors minLength parameter by blocking patterns shorter than minLength")
    void minLengthBlocksShortInputs() {
        String pattern = "C"; // length 1, should be blocked for minLength=3
        var fileCandidate = new Candidate("C.java", "C", "C.java", 0);

        List<ShorthandCompletion> results = Completions.scoreShortAndLong(
                pattern,
                List.of(fileCandidate),
                Candidate::shortText,
                Candidate::longText,
                Candidate::tie,
                c -> new ShorthandCompletion(null, c.id(), c.longText()),
                3);

        assertTrue(results.isEmpty(), "Expected no results for pattern shorter than minLength");
    }

    @Test
    @DisplayName("scoreShortAndLong: allows queries of length >= minLength including extension-aware inputs like 'C.j'")
    void minLengthAllowsExtensionAwareThreeChars() {
        String pattern = "C.j"; // length 3, meets minLength=3
        var fileCandidate = new Candidate("C.java", "C", "C.java", 0);

        List<ShorthandCompletion> results = Completions.scoreShortAndLong(
                pattern,
                List.of(fileCandidate),
                Candidate::shortText,
                Candidate::longText,
                Candidate::tie,
                c -> new ShorthandCompletion(null, c.id(), c.longText()),
                3);

        assertTrue(
                results.stream().anyMatch(sc -> "C.java".equals(sc.getInputText())),
                "Expected candidate to be included for 'C.j' with minLength=3");
    }

    @Test
    @DisplayName("scoreShortAndLong: tolerance keeps near-best short matches and excludes long-only mid-word")
    void toleranceKeepsNearBestShorts() {
        String pattern = "Chr";

        var chrome = new Candidate("Chrome.java", "Chrome.java", "Chrome.java", 0);
        var chatResponse = new Candidate("ChatResponse.java", "ChatResponse.java", "ChatResponse.java", 0);
        var longMid = new Candidate("LM", "zzzz", "xChrSomething", 0);

        List<ShorthandCompletion> results = Completions.scoreShortAndLong(
                pattern,
                List.of(chrome, chatResponse, longMid),
                Candidate::shortText,
                Candidate::longText,
                Candidate::tie,
                c -> new ShorthandCompletion(null, c.id(), c.longText()));

        assertTrue(
                results.stream().anyMatch(sc -> "Chrome.java".equals(sc.getInputText())),
                "Expected 'Chrome.java' to be included for 'Chr'");
        assertTrue(
                results.stream().anyMatch(sc -> "ChatResponse.java".equals(sc.getInputText())),
                "Expected 'ChatResponse.java' to be included for 'Chr'");
        assertFalse(
                results.stream().anyMatch(sc -> "LM".equals(sc.getInputText())),
                "Expected long-only mid-word candidate to be excluded for 'Chr'");
    }
}
