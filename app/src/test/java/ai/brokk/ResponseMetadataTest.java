package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class ResponseMetadataTest {

    @Test
    void testSumCostUsd() {
        Llm.ResponseMetadata a = new Llm.ResponseMetadata(10, 5, 0, 20, 100, 0.5, "m1", "STOP", "now", "tier1", null);
        Llm.ResponseMetadata b =
                new Llm.ResponseMetadata(30, 10, 0, 40, 200, 1.2, "m2", "LENGTH", "later", "tier2", null);

        Llm.ResponseMetadata sum = Llm.ResponseMetadata.sum(a, b);

        assertNotNull(sum);
        assertEquals(40, sum.inputTokens());
        assertEquals(15, sum.cachedInputTokens());
        assertEquals(60, sum.outputTokens());
        assertEquals(300, sum.elapsedMs());
        assertEquals(1.7, sum.costUsd(), 0.000001);
        assertEquals("m2", sum.modelName());
        assertEquals("LENGTH", sum.finishReason());
    }

    @Test
    void testSumWithNullCosts() {
        Llm.ResponseMetadata a = new Llm.ResponseMetadata(10, 0, 0, 10, 100, null, null, null, null, null, null);
        Llm.ResponseMetadata b = new Llm.ResponseMetadata(10, 0, 0, 10, 100, 0.5, null, null, null, null, null);

        // a null, b non-null
        assertEquals(0.5, Llm.ResponseMetadata.sum(a, b).costUsd());
        // a non-null, b null
        assertEquals(0.5, Llm.ResponseMetadata.sum(b, a).costUsd());
        // both null
        assertNull(Llm.ResponseMetadata.sum(a, a).costUsd());
    }

    @Test
    void testCostUsdOverflow() {
        Llm.ResponseMetadata a =
                new Llm.ResponseMetadata(0, 0, 0, 0, 0, Double.MAX_VALUE, null, null, null, null, null);
        Llm.ResponseMetadata b =
                new Llm.ResponseMetadata(0, 0, 0, 0, 0, Double.MAX_VALUE, null, null, null, null, null);

        Llm.ResponseMetadata sum = Llm.ResponseMetadata.sum(a, b);
        assertEquals(Double.MAX_VALUE, sum.costUsd());
    }
}
