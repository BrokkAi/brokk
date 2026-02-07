package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.brokk.agents.IssueRewriterAgent;
import org.junit.jupiter.api.Test;

class IssueRewriterAgentTest {

    @Test
    void testParseIssueResponse_ValidJson() {
        String json =
                """
                { "title": "Bug: NPE in Foo", "bodyMarkdown": "Steps...\\n1) Do X\\n2) Do Y" }
                """;

        var response = IssueRewriterAgent.parseIssueResponse(json);

        assertNotNull(response);
        assertEquals("Bug: NPE in Foo", response.title());
        assertEquals("Steps...\n1) Do X\n2) Do Y", response.bodyMarkdown());
    }

    @Test
    void testParseIssueResponse_MissingFields_ThrowsException() {
        String onlyTitle = """
                { "title": "Bug: NPE in Foo" }
                """;
        String onlyBody = """
                { "bodyMarkdown": "Steps..." }
                """;

        assertThrows(IllegalArgumentException.class, () -> IssueRewriterAgent.parseIssueResponse(onlyTitle));
        assertThrows(IllegalArgumentException.class, () -> IssueRewriterAgent.parseIssueResponse(onlyBody));
    }

    @Test
    void testParseIssueResponse_MalformedJson_ThrowsException() {
        String malformed = "{ this is not valid json }";
        assertThrows(AssertionError.class, () -> IssueRewriterAgent.parseIssueResponse(malformed));
    }

    @Test
    void testParseIssueResponse_EmptyInput_ThrowsException() {
        assertThrows(NullPointerException.class, () -> IssueRewriterAgent.parseIssueResponse(null));
        assertThrows(AssertionError.class, () -> IssueRewriterAgent.parseIssueResponse(""));
        assertThrows(AssertionError.class, () -> IssueRewriterAgent.parseIssueResponse("   "));
    }
}
