package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.Languages;
import org.junit.jupiter.api.Test;

public class RustTestDetectionTest {
    @Test
    void testLanguageDetection() {
        assertEquals(Languages.RUST, Languages.fromExtension("rs"));
    }
}
