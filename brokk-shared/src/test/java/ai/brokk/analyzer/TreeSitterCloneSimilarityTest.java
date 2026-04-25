package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TreeSitterCloneSimilarityTest {

    @Test
    void hashedSimilarityMatchesStringShingleSimilarityForIdenticalTokens() {
        var tokens = List.of("ID", "OP:=", "NUM", "OP:+", "ID");
        var weights = new IAnalyzer.CloneSmellWeights(1, 1, 2, 1, 70);

        assertSimilarityMatchesStringShingles(tokens, tokens, weights);
    }

    @Test
    void hashedSimilarityMatchesStringShingleSimilarityForPartialOverlap() {
        var left = List.of("ID", "OP:=", "NUM", "OP:+", "ID", "OP:;", "return", "ID");
        var right = List.of("ID", "OP:=", "NUM", "OP:-", "ID", "OP:;", "return", "NUM");
        var weights = new IAnalyzer.CloneSmellWeights(1, 1, 2, 1, 70);

        assertSimilarityMatchesStringShingles(left, right, weights);
    }

    @Test
    void hashedSimilarityMatchesStringShingleSimilarityBelowMinSharedShingles() {
        var left = List.of("ID", "OP:=", "NUM", "OP:+", "ID");
        var right = List.of("return", "STR", "OP:;", "throw", "ID");
        var weights = new IAnalyzer.CloneSmellWeights(1, 1, 2, 3, 70);

        assertSimilarityMatchesStringShingles(left, right, weights);
    }

    @Test
    void hashedSimilarityMatchesStringShingleSimilarityWhenTokenListIsShorterThanShingleSize() {
        var left = List.of("ID", "OP:=");
        var right = List.of("ID", "OP:=");
        var weights = new IAnalyzer.CloneSmellWeights(1, 1, 3, 1, 70);

        assertSimilarityMatchesStringShingles(left, right, weights);
    }

    private static void assertSimilarityMatchesStringShingles(
            List<String> left, List<String> right, IAnalyzer.CloneSmellWeights weights) {
        int expected = stringShingleSimilarity(left, right, weights);
        int actual = TreeSitterAnalyzer.computeCloneTokenSimilarity(
                TreeSitterAnalyzer.hashedShingles(left, weights.shingleSize()),
                TreeSitterAnalyzer.hashedShingles(right, weights.shingleSize()),
                weights);
        assertEquals(expected, actual);
    }

    private static int stringShingleSimilarity(
            List<String> left, List<String> right, IAnalyzer.CloneSmellWeights weights) {
        Set<String> leftShingles = stringShingles(left, weights.shingleSize());
        Set<String> rightShingles = stringShingles(right, weights.shingleSize());
        if (leftShingles.size() < weights.minSharedShingles() || rightShingles.size() < weights.minSharedShingles()) {
            return 0;
        }
        var intersection = new LinkedHashSet<>(leftShingles);
        intersection.retainAll(rightShingles);
        if (intersection.size() < weights.minSharedShingles()) {
            return 0;
        }
        var union = new LinkedHashSet<>(leftShingles);
        union.addAll(rightShingles);
        if (union.isEmpty()) {
            return 0;
        }
        return (int) Math.round((intersection.size() * 100.0) / union.size());
    }

    private static Set<String> stringShingles(List<String> tokens, int shingleSize) {
        int k = Math.max(1, shingleSize);
        if (tokens.size() < k) {
            return Set.of();
        }
        var shingles = new LinkedHashSet<String>();
        for (int i = 0; i <= tokens.size() - k; i++) {
            shingles.add(String.join("|", tokens.subList(i, i + k)));
        }
        return shingles;
    }
}
