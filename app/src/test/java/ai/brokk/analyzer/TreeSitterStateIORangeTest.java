package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Range serialization test. Fory handles Record components automatically.
 */
class TreeSitterStateIORangeTest {

    @Test
    void testRangeProperties() {
        IAnalyzer.Range range = new IAnalyzer.Range(10, 20, 1, 2, 5);
        assertEquals(10, range.startByte());
        assertEquals(20, range.endByte());
        assertEquals(1, range.startLine());
        assertEquals(2, range.endLine());
        assertEquals(5, range.commentStartByte());
    }
}
