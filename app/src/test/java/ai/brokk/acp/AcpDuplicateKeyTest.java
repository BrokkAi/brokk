package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the workaround in {@link AcpServerMain#patchAcpDuplicateKeyBug} produces
 * JSON without duplicate keys. The upstream acp-core schema declares its discriminated
 * unions with {@code @JsonTypeInfo(visible=true)} on the parent interface AND a record
 * property of the same name on each subtype, so by default Jackson emits both — Zed's
 * serde-tagged-enum decoder rejects every such message.
 */
class AcpDuplicateKeyTest {

    @BeforeAll
    static void patchOnce() {
        AcpServerMain.patchAcpDuplicateKeyBug(McpJsonDefaults.getMapper());
    }

    @Test
    void toolCallSerializationHasNoDuplicateKeys() throws Exception {
        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                "tc1",
                "Read foo.java",
                AcpSchema.ToolKind.READ,
                AcpSchema.ToolCallStatus.PENDING,
                List.of(new AcpSchema.ToolCallContentBlock("content", new AcpSchema.TextContent("hello"))),
                List.of(),
                null,
                null,
                Map.of("brokk", Map.of("toolName", "getFileContents")));

        var json = McpJsonDefaults.getMapper().writeValueAsString(toolCall);
        assertNoDuplicateKeys(json);
        // sanity: discriminator is still emitted exactly once
        assertEquals(1, countOccurrences(json, "\"sessionUpdate\":\"tool_call\""));
        // and the inner ToolCallContentBlock + TextContent type fields are also single-key
        assertEquals(1, countOccurrences(json, "\"type\":\"content\""));
        assertEquals(1, countOccurrences(json, "\"type\":\"text\""));
    }

    @Test
    void agentMessageChunkSerializationHasNoDuplicateKeys() throws Exception {
        var chunk = new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent("hello"));
        var json = McpJsonDefaults.getMapper().writeValueAsString(chunk);
        assertNoDuplicateKeys(json);
        assertEquals(1, countOccurrences(json, "\"sessionUpdate\":\"agent_message_chunk\""));
    }

    @Test
    void toolCallUpdateSerializationHasNoDuplicateKeys() throws Exception {
        var update = new AcpSchema.ToolCallUpdateNotification(
                "tool_call_update",
                "tc1",
                "Read foo.java",
                AcpSchema.ToolKind.READ,
                AcpSchema.ToolCallStatus.COMPLETED,
                List.of(new AcpSchema.ToolCallContentBlock("content", new AcpSchema.TextContent("payload"))),
                List.of(),
                null,
                null,
                null);
        var json = McpJsonDefaults.getMapper().writeValueAsString(update);
        assertNoDuplicateKeys(json);
        assertEquals(1, countOccurrences(json, "\"sessionUpdate\":\"tool_call_update\""));
    }

    /**
     * Walks the JSON and asserts no object has the same key appear twice. Uses a manual scan
     * rather than a parser because Jackson's tree model collapses duplicates, hiding the bug.
     */
    private static void assertNoDuplicateKeys(String json) {
        // Per-object key tracking: push a set on '{', pop on '}', check on each "key":
        var stack = new java.util.ArrayDeque<Set<String>>();
        var i = 0;
        var n = json.length();
        while (i < n) {
            char c = json.charAt(i);
            if (c == '{') {
                stack.push(new HashSet<>());
                i++;
            } else if (c == '}') {
                stack.pop();
                i++;
            } else if (c == '"') {
                int end = i + 1;
                while (end < n) {
                    if (json.charAt(end) == '\\') {
                        end += 2;
                        continue;
                    }
                    if (json.charAt(end) == '"') {
                        break;
                    }
                    end++;
                }
                var token = json.substring(i + 1, end);
                int after = end + 1;
                while (after < n && Character.isWhitespace(json.charAt(after))) {
                    after++;
                }
                boolean isKey = after < n && json.charAt(after) == ':' && !stack.isEmpty();
                if (isKey) {
                    var seen = stack.peek();
                    assertFalse(seen.contains(token), "duplicate key '" + token + "' in JSON: " + json);
                    seen.add(token);
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        assertTrue(stack.isEmpty(), "unbalanced braces in JSON: " + json);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
