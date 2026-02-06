package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IssueWriterServiceTest {

    @Test
    void testParseIssueResponse_ValidJson() {
        String json =
                """
                { "title": "Bug: NPE in Foo", "bodyMarkdown": "Steps...\\n1) Do X\\n2) Do Y" }
                """;

        var response = IssueWriterService.parseIssueResponse(json);

        assertNotNull(response);
        assertEquals("Bug: NPE in Foo", response.title());
        assertEquals("Steps...\n1) Do X\n2) Do Y", response.bodyMarkdown());
    }

    @Test
    void testParseIssueResponse_WrappedJson() {
        String wrapped =
                """
                Here is the issue:

                ```json
                {
                  "title": "Bug: NPE in Foo",
                  "bodyMarkdown": "Steps...\\n- a\\n- b"
                }
                ```

                End.
                """;

        var response = IssueWriterService.parseIssueResponse(wrapped);

        assertNotNull(response);
        assertEquals("Bug: NPE in Foo", response.title());
        assertEquals("Steps...\n- a\n- b", response.bodyMarkdown());
    }

    @Test
    void testParseIssueResponse_MissingFields_ThrowsException() {
        String onlyTitle = """
                { "title": "Bug: NPE in Foo" }
                """;
        String onlyBody = """
                { "bodyMarkdown": "Steps..." }
                """;

        assertThrows(IllegalArgumentException.class, () -> IssueWriterService.parseIssueResponse(onlyTitle));
        assertThrows(IllegalArgumentException.class, () -> IssueWriterService.parseIssueResponse(onlyBody));
    }

    @Test
    void testParseIssueResponse_MalformedJson_ThrowsException() {
        String malformed = "{ this is not valid json }";
        assertThrows(IllegalArgumentException.class, () -> IssueWriterService.parseIssueResponse(malformed));
    }

    @Test
    void testParseIssueResponse_WrongTypes_ThrowsException() {
        String wrongTypes = """
                { "title": 123, "bodyMarkdown": true }
                """;
        assertThrows(IllegalArgumentException.class, () -> IssueWriterService.parseIssueResponse(wrongTypes));
    }

    @Test
    void testParseIssueResponse_EmptyInput_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> IssueWriterService.parseIssueResponse(""));
        assertThrows(IllegalArgumentException.class, () -> IssueWriterService.parseIssueResponse("   "));
        assertThrows(IllegalArgumentException.class, () -> IssueWriterService.parseIssueResponse(null));
    }
}
