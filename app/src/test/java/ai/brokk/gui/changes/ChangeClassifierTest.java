package ai.brokk.gui.changes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ChangeClassifierTest {

    @Test
    public void classify_smallTextContents_isText() {
        String left = "line1\nline2\nline3";
        String right = "line1\nline2 modified\nline3";
        ChangeFileStatus status = ChangeClassifier.classify(left, right, 10_000, 5_000);
        assertEquals(ChangeFileStatus.TEXT, status);
    }

    @Test
    public void classify_containsNulCharacter_isBinary() {
        String left = "some\u0000binary";
        String right = "other";
        ChangeFileStatus statusLeftBinary = ChangeClassifier.classify(left, right, 10_000, 5_000);
        assertEquals(ChangeFileStatus.BINARY, statusLeftBinary);

        // also when right side contains NUL
        ChangeFileStatus statusRightBinary = ChangeClassifier.classify("normal", "bad\u0000data", 10_000, 5_000);
        assertEquals(ChangeFileStatus.BINARY, statusRightBinary);
    }

    @Test
    public void classify_oversizedByCombinedLength_isOversized() {
        String left = "a".repeat(800);
        String right = "b".repeat(800);
        ChangeFileStatus status = ChangeClassifier.classify(left, right, 1000, 2000);
        assertEquals(ChangeFileStatus.OVERSIZED, status);
    }

    @Test
    public void classify_oversizedBySingleSide_isOversized() {
        String left = "a".repeat(3000);
        String right = "b".repeat(10);
        ChangeFileStatus status = ChangeClassifier.classify(left, right, 100_000, 2000);
        assertEquals(ChangeFileStatus.OVERSIZED, status);
    }
}
