package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class SearchPromptsTest {
    @Test
    void issueDiagnosisTerminals_isIssueOnly() {
        assertEquals(
                EnumSet.of(SearchPrompts.Terminal.DESCRIBE_ISSUE),
                SearchPrompts.Objective.ISSUE_DESCRIPTION.terminals());
    }
}
