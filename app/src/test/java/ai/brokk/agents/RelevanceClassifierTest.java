package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

public class RelevanceClassifierTest {

    @Test
    public void testExtractScoresParseJsonArray() {
        // Test parsing a valid JSON array
        var result = RelevanceClassifier.extractScores("[0.8, 0.3, 0.1]", 3);
        assertEquals(List.of(0.8, 0.3, 0.1), result);
    }

    @Test
    public void testExtractScoresParseJsonArrayWithExtraText() {
        // Test parsing when JSON array is embedded in other text
        var result = RelevanceClassifier.extractScores("Here are the scores: [0.9, 0.5] done", 2);
        assertEquals(List.of(0.9, 0.5), result);
    }

    @Test
    public void testExtractScoresClampsValues() {
        // Test that values outside [0,1] are clamped
        var result = RelevanceClassifier.extractScores("[1.5, -0.2, 0.7]", 3);
        assertEquals(List.of(1.0, 0.0, 0.7), result);
    }

    @Test
    public void testExtractScoresLenientSingleValue() {
        // Test lenient parsing for arity 1
        var result = RelevanceClassifier.extractScores("The score is 0.75", 1);
        assertEquals(List.of(0.75), result);
    }

    @Test
    public void testExtractScoresLenientJsonStyle() {
        // Test lenient parsing for arity 1 with JSON-style
        var result = RelevanceClassifier.extractScores("{\"score\": 0.6}", 1);
        assertEquals(List.of(0.6), result);
    }

    @Test
    public void testExtractScoresNoFallbackForMulti() {
        // Test that we DO NOT fallback to single value when arity > 1
        var result = RelevanceClassifier.extractScores("The score is 0.75", 3);
        assertEquals(List.of(), result);
    }

    @Test
    public void testExtractScoresMalformedReturnsEmpty() {
        // Test malformed input returns empty list
        var result = RelevanceClassifier.extractScores("no numbers here", 3);
        assertEquals(List.of(), result);
    }

    @Test
    public void testExtractScoresEmptyInputReturnsEmpty() {
        // Test empty input returns empty list
        var result = RelevanceClassifier.extractScores("", 3);
        assertEquals(List.of(), result);
    }

    @Test
    public void testExtractScoresWrongCountReturnsEmpty() {
        // Test wrong array count returns empty (triggering retry)
        var result = RelevanceClassifier.extractScores("[0.8, 0.3]", 3);
        assertEquals(List.of(), result);
    }

    @Test
    public void testExtractScoresInvalidJsonReturnsEmpty() {
        // Test invalid JSON array returns empty (triggering retry)
        var result = RelevanceClassifier.extractScores("[0.8, not_a_number]", 2);
        assertEquals(List.of(), result);
    }
}
