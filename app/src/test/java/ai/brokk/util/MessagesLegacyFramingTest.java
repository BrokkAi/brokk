package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagesLegacyFramingTest {

    @Test
    void emptyInputReturnsEmptyList() {
        assertEquals(List.of(), Messages.parseLegacyFraming(""));
    }

    @Test
    void cleanMarkdownPassesThroughAsCustomSegment() {
        var clean = "Some clean **markdown** with no framing.";
        var segments = Messages.parseLegacyFraming(clean);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.CUSTOM, segments.get(0).type());
        assertEquals(clean, segments.get(0).content());
    }

    @Test
    void parsesUserAndAiBlocksProducedByMessagesFormat() {
        List<ChatMessage> messages = List.of(new UserMessage("hello"), new AiMessage("hi there"));
        var framed = Messages.format(messages);

        var segments = Messages.parseLegacyFraming(framed);

        assertEquals(2, segments.size());
        assertEquals(ChatMessageType.USER, segments.get(0).type());
        assertEquals("hello", segments.get(0).content());
        assertEquals(ChatMessageType.AI, segments.get(1).type());
        assertEquals("hi there", segments.get(1).content());
    }

    @Test
    void stripsAiSectionLabelsFromReasoningAndToolCalls() {
        var ai = new AiMessage("", "thinking out loud"); // text empty, reasoning set
        var framed = Messages.format(List.of(ai));
        var segments = Messages.parseLegacyFraming(framed);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.AI, segments.get(0).type());
        // After stripping the "Reasoning:" label and de-indenting, only the reasoning body remains.
        assertEquals("thinking out loud", segments.get(0).content());
    }

    @Test
    void unknownTypeFallsBackToCustom() {
        var framed =
                """
                <message type=mystery>
                  payload
                </message>
                """;
        var segments = Messages.parseLegacyFraming(framed);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.CUSTOM, segments.get(0).type());
        assertEquals("payload", segments.get(0).content());
    }

    @Test
    void stripLegacyFramingJoinsSegments() {
        List<ChatMessage> messages = List.of(new UserMessage("hello"), new AiMessage("hi there"));
        var framed = Messages.format(messages);
        assertEquals("hello\n\nhi there", Messages.stripLegacyFraming(framed));
    }

    @Test
    void stripLegacyFramingPreservesCleanMarkdown() {
        var clean = "Already clean";
        assertEquals(clean, Messages.stripLegacyFraming(clean));
    }

    @Test
    void flattensNestedFramingFromCustomWrappingUser() {
        // Old persisted entries sometimes double-wrap: a CustomMessage carrying a markdown blob that
        // itself contains <message type=user>...</message>. Verify we flatten to the inner segment.
        var nested =
                """
                <message type=custom>
                  <message type=user>
                    can you list all the acp related issues?
                  </message>
                </message>
                """;
        var segments = Messages.parseLegacyFraming(nested);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.USER, segments.get(0).type());
        assertEquals("can you list all the acp related issues?", segments.get(0).content());
    }

    @Test
    void emptyCustomBlockDoesNotSwallowFollowingBlock() {
        // Real persisted data started with an empty custom wrapper followed by an AI block. A naive
        // regex matched the outer custom open against the AI block's close, collapsing both into
        // one segment whose content kept the inner framing. Verify each block is recognized separately.
        var input =
                """
                <message type=custom>

                </message>

                <message type=ai>
                  Reasoning:
                  thought
                </message>
                """;
        var segments = Messages.parseLegacyFraming(input);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.AI, segments.get(0).type());
        assertEquals("thought", segments.get(0).content());
    }

    @Test
    void deindentDetectsCommonIndentForArbitraryDepth() {
        // Inner content at 4-space indent (depth-2 nesting) must lose all 4 leading spaces.
        var input =
                """
                <message type=custom>
                  <message type=user>
                    deeply indented user text
                  </message>
                </message>
                """;
        var segments = Messages.parseLegacyFraming(input);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.USER, segments.get(0).type());
        assertEquals("deeply indented user text", segments.get(0).content());
    }

    @Test
    void parseReturnsCustomFallbackWhenFramingTokenPresentButMalformed() {
        var malformed = "<message type=user> not a real block";
        var segments = Messages.parseLegacyFraming(malformed);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.CUSTOM, segments.get(0).type());
        assertEquals(malformed, segments.get(0).content());
    }

    @Test
    void emitsBothOuterAndInnerWhenOuterHasOwnContent() {
        // Locks in the actual emission contract: a nested wrap whose outer frame carries text
        // outside the inner block emits two segments (inner first, then outer with the surrounding
        // text). The Javadoc previously claimed only the inner was emitted, which was misleading.
        var input =
                """
                <message type=custom>
                  prefix
                  <message type=user>
                    question
                  </message>
                  suffix
                </message>
                """;
        var segments = Messages.parseLegacyFraming(input);
        assertEquals(2, segments.size());
        assertEquals(ChatMessageType.USER, segments.get(0).type());
        assertEquals("question", segments.get(0).content());
        assertEquals(ChatMessageType.CUSTOM, segments.get(1).type());
        assertEquals("prefix\nsuffix", segments.get(1).content());
    }

    @Test
    void exceedsMaxDepthFallsBackToSingleCustomSegment() {
        // 33 unterminated opens trip the MAX_FRAMING_DEPTH=32 cap on the 33rd push attempt and
        // bail out to a single CUSTOM segment containing the full markdown, preventing unbounded
        // StringBuilder allocation on adversarial input.
        var input = "<message type=user>\n".repeat(33) + "content\n";
        var segments = Messages.parseLegacyFraming(input);
        assertEquals(1, segments.size());
        assertEquals(ChatMessageType.CUSTOM, segments.get(0).type());
        assertEquals(input, segments.get(0).content());
    }

    @Test
    void parseFromFixture_yieldsExpectedSegmentSequence() throws IOException {
        // Locks parser behavior against a realistic pre-#3443 session blob: leading empty custom
        // wrapper that must NOT swallow the next block, AI body with Reasoning/Text/Tool calls
        // section labels, and a trailing custom tool-result block. Catches collapse-into-one
        // regressions on real-shape input rather than only on hand-written minimal cases.
        var fixture = Files.readString(Path.of("src/test/resources/legacy-framing/sample-session.md"));
        var segments = Messages.parseLegacyFraming(fixture);

        assertEquals(4, segments.size());
        assertEquals(ChatMessageType.USER, segments.get(0).type());
        assertEquals("list the open ACP issues in this repo", segments.get(0).content());
        assertEquals(ChatMessageType.AI, segments.get(1).type());
        assertEquals(ChatMessageType.AI, segments.get(2).type());
        assertEquals("Found 17 open issues with the ACP label.", segments.get(2).content());
        assertEquals(ChatMessageType.CUSTOM, segments.get(3).type());
    }

    @Test
    void stripLegacyFramingFromFixture_dropsAllFramingTags() throws IOException {
        // Mirrors the BrokkAcpAgent.scheduleConversationReplay path: it feeds the persisted
        // mopMarkdown through stripLegacyFraming before emitting an AgentMessageChunk.
        // Tags must never reach the ACP wire.
        var fixture = Files.readString(Path.of("src/test/resources/legacy-framing/sample-session.md"));
        var stripped = Messages.stripLegacyFraming(fixture);

        assertFalse(stripped.contains("<message type="), "stripped output still contains an opening framing tag");
        assertFalse(stripped.contains("</message>"), "stripped output still contains a closing framing tag");
        assertFalse(stripped.isBlank(), "stripped output should retain the human-readable body");
    }
}
