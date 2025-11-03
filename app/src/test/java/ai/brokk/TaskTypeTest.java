package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

public class TaskTypeTest {

    @Test
    void testSearchDisplayNameIsLutzMode() {
        assertEquals("Lutz Mode", TaskResult.Type.SEARCH.displayName(), "SEARCH displayName must remain 'Lutz Mode'");
    }

    @Test
    void testSafeParseByEnumName() {
        assertEquals(Optional.of(TaskResult.Type.CODE), TaskResult.Type.safeParse("CODE"));
        assertEquals(Optional.of(TaskResult.Type.CODE), TaskResult.Type.safeParse("code"));
    }

    @Test
    void testSafeParseByDisplayName() {
        // Search maps to "Lutz Mode"
        assertEquals(Optional.of(TaskResult.Type.SEARCH), TaskResult.Type.safeParse("Lutz Mode"));
        assertEquals(Optional.of(TaskResult.Type.SEARCH), TaskResult.Type.safeParse("lutz mode"));
    }

    @Test
    void testSafeParseInvalid() {
        assertEquals(Optional.empty(), TaskResult.Type.safeParse(null));
        assertEquals(Optional.empty(), TaskResult.Type.safeParse(""));
        assertEquals(Optional.empty(), TaskResult.Type.safeParse("unknown"));
    }
}
