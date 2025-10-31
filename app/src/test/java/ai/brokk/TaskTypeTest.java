package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

public class TaskTypeTest {

    @Test
    void testSearchDisplayNameIsLutzMode() {
        assertEquals("Lutz Mode", TaskType.SEARCH.displayName(), "SEARCH displayName must remain 'Lutz Mode'");
    }

    @Test
    void testSafeParseByEnumName() {
        assertEquals(Optional.of(TaskType.CODE), TaskType.safeParse("CODE"));
        assertEquals(Optional.of(TaskType.CODE), TaskType.safeParse("code"));
    }

    @Test
    void testSafeParseByDisplayName() {
        // Search maps to "Lutz Mode"
        assertEquals(Optional.of(TaskType.SEARCH), TaskType.safeParse("Lutz Mode"));
        assertEquals(Optional.of(TaskType.SEARCH), TaskType.safeParse("lutz mode"));
    }

    @Test
    void testSafeParseInvalid() {
        assertEquals(Optional.empty(), TaskType.safeParse(null));
        assertEquals(Optional.empty(), TaskType.safeParse(""));
        assertEquals(Optional.empty(), TaskType.safeParse("unknown"));
    }
}
