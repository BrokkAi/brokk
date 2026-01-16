package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LlmOutputMetaTest {

    @Test
    void default_hasAllFlagsFalse() {
        assertFalse(LlmOutputMeta.DEFAULT.isNewMessage());
        assertFalse(LlmOutputMeta.DEFAULT.isReasoning());
        assertFalse(LlmOutputMeta.DEFAULT.isTerminal());
    }

    @Test
    void newMessage_factorySetsOnlyNewMessage() {
        var meta = LlmOutputMeta.newMessage();
        assertTrue(meta.isNewMessage());
        assertFalse(meta.isReasoning());
        assertFalse(meta.isTerminal());
    }

    @Test
    void reasoning_factorySetsOnlyReasoning() {
        var meta = LlmOutputMeta.reasoning();
        assertFalse(meta.isNewMessage());
        assertTrue(meta.isReasoning());
        assertFalse(meta.isTerminal());
    }

    @Test
    void terminal_factorySetsOnlyTerminal() {
        var meta = LlmOutputMeta.terminal();
        assertFalse(meta.isNewMessage());
        assertFalse(meta.isReasoning());
        assertTrue(meta.isTerminal());
    }

    @Test
    void withX_methodsSetFlagsAndPreserveOtherValues() {
        var base = LlmOutputMeta.DEFAULT.withNewMessage(true).withTerminal(true);

        assertEquals(new LlmOutputMeta(false, false, true), base.withNewMessage(false));
        assertEquals(new LlmOutputMeta(true, true, true), base.withReasoning(true));
        assertEquals(new LlmOutputMeta(true, false, false), base.withTerminal(false));
    }

    @Test
    void fluentChaining_works() {
        var meta = LlmOutputMeta.DEFAULT.withNewMessage(true).withReasoning(true);
        assertTrue(meta.isNewMessage());
        assertTrue(meta.isReasoning());
        assertFalse(meta.isTerminal());
    }
}
