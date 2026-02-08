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
    public void testExtractScoresSingleValueFallback() {
        // Test fallback to single value replicated
        var result = RelevanceClassifier.extractScores("The score is 0.75", 3);
        assertEquals(List.of(0.75, 0.75, 0.75), result);
    }

    @Test
    public void testExtractScoresSingleValueFallbackWithJsonStyle() {
        // Test fallback with JSON-style single value
        var result = RelevanceClassifier.extractScores("{\"score\": 0.6}", 2);
        assertEquals(List.of(0.6, 0.6), result);
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
        // Test wrong array count falls back to single value extraction
        var result = RelevanceClassifier.extractScores("[0.8, 0.3]", 3);
        // Array has 2 elements but expected 3, so it should try single-value fallback
        // Since "0.8" is the first number found, it replicates that
        assertEquals(List.of(0.8, 0.8, 0.8), result);
    }

    @Test
    public void testExtractScoresInvalidJsonFallsBackToSingle() {
        // Test invalid JSON array falls back to single value
        var result = RelevanceClassifier.extractScores("[0.8, not_a_number]", 2);
        // Invalid JSON, falls back to first number "0.8"
        assertEquals(List.of(0.8, 0.8), result);
    }
}
