package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class SearchPromptsIssueDiagnosisTest {

    @Test
    void issueDiagnosisTerminals_isIssueJsonOnly() {
        assertEquals(EnumSet.of(SearchPrompts.Terminal.ISSUE), SearchPrompts.Objective.ISSUE_DIAGNOSIS.terminals());
    }
}
