package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LlmResponseMetadataTest {

    @Test
    void sum_keepsNullableFieldsNullWhenBothSidesNull() {
        var a = new Llm.ResponseMetadata(1, 2, 3, 4, 5, null, null, null, null, null);
        var b = new Llm.ResponseMetadata(10, 20, 30, 40, 50, null, null, null, null, null);

        var summed = Llm.ResponseMetadata.sum(a, b);

        assertEquals(11, summed.inputTokens());
        assertEquals(22, summed.cachedInputTokens());
        assertEquals(33, summed.thinkingTokens());
        assertEquals(44, summed.outputTokens());
        assertEquals(55, summed.elapsedMs());
        assertNull(summed.modelName());
        assertNull(summed.finishReason());
        assertNull(summed.created());
        assertNull(summed.serviceTier());
        assertNull(summed.error());
    }
}
