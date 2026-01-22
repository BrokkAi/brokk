package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchPromptsEnrichmentTest {

    @Test
    void testPromptEnrichmentObjective() {
        SearchPrompts.Objective objective = SearchPrompts.Objective.PROMPT_ENRICHMENT;
        Set<SearchPrompts.Terminal> terminals = objective.terminals();

        assertEquals(1, terminals.size());
        assertTrue(terminals.contains(SearchPrompts.Terminal.ANSWER));
    }
}
