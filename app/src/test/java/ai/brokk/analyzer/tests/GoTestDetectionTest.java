package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.Languages;
import org.junit.jupiter.api.Test;

public class GoTestDetectionTest {
    @Test
    void testLanguageDetection() {
        assertEquals(Languages.GO, Languages.fromExtension("go"));
    }
}
