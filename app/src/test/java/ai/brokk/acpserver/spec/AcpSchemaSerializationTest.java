package ai.brokk.acpserver.spec;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.acpserver.spec.AcpSchema.AgentMessageChunk;
import ai.brokk.acpserver.spec.AcpSchema.AgentThought;
import ai.brokk.acpserver.spec.AcpSchema.Content;
import ai.brokk.acpserver.spec.AcpSchema.ImageContent;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.SessionUpdate;
import ai.brokk.acpserver.spec.AcpSchema.SessionUpdateNotification;
import ai.brokk.acpserver.spec.AcpSchema.TextContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AcpSchemaSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void promptRequestWithTextContent() throws Exception {
        List<Content> messages = List.of(new TextContent("Hello, agent!"));
        var request = new PromptRequest("session-123", messages);

        String json = mapper.writeValueAsString(request);
        var deserialized = mapper.readValue(json, PromptRequest.class);

        assertEquals("session-123", deserialized.sessionId());
        assertEquals(1, deserialized.messages().size());
        assertInstanceOf(TextContent.class, deserialized.messages().getFirst());
        assertEquals("Hello, agent!", ((TextContent) deserialized.messages().getFirst()).text());
    }

    @Test
    void promptRequestWithImageContent() throws Exception {
        List<Content> messages = List.of(new ImageContent("base64data", "image/png"));
        var request = new PromptRequest("session-123", messages);

        String json = mapper.writeValueAsString(request);
        var deserialized = mapper.readValue(json, PromptRequest.class);

        assertInstanceOf(ImageContent.class, deserialized.messages().getFirst());
        var image = (ImageContent) deserialized.messages().getFirst();
        assertEquals("base64data", image.data());
        assertEquals("image/png", image.mimeType());
    }

    @Test
    void sessionUpdateAgentMessageChunk() throws Exception {
        SessionUpdate update = new AgentMessageChunk(new TextContent("Hello!"));

        String json = mapper.writeValueAsString(update);
        var deserialized = mapper.readValue(json, SessionUpdate.class);

        assertInstanceOf(AgentMessageChunk.class, deserialized);
        var chunk = (AgentMessageChunk) deserialized;
        assertInstanceOf(TextContent.class, chunk.content());
        assertEquals("Hello!", ((TextContent) chunk.content()).text());
    }

    @Test
    void sessionUpdateAgentThought() throws Exception {
        SessionUpdate update = new AgentThought("Thinking about the problem...");

        String json = mapper.writeValueAsString(update);
        var deserialized = mapper.readValue(json, SessionUpdate.class);

        assertInstanceOf(AgentThought.class, deserialized);
        assertEquals("Thinking about the problem...", ((AgentThought) deserialized).thought());
    }

    @Test
    void sessionUpdateNotificationRoundTrip() throws Exception {
        var update = new AgentMessageChunk(new TextContent("Progress update"));
        var notification = new SessionUpdateNotification("session-123", update);

        String json = mapper.writeValueAsString(notification);
        var deserialized = mapper.readValue(json, SessionUpdateNotification.class);

        assertEquals("session-123", deserialized.sessionId());
        assertInstanceOf(AgentMessageChunk.class, deserialized.update());
    }

    @Test
    void contentTypeDiscriminator() throws Exception {
        String textJson = mapper.writeValueAsString(new TextContent("test"));
        String imageJson = mapper.writeValueAsString(new ImageContent("data", "image/png"));

        assert textJson.contains("\"type\":\"text\"");
        assert imageJson.contains("\"type\":\"image\"");
    }

    @Test
    void sessionUpdateTypeDiscriminator() throws Exception {
        String chunkJson = mapper.writeValueAsString(new AgentMessageChunk(new TextContent("test")));
        String thoughtJson = mapper.writeValueAsString(new AgentThought("thinking"));

        assert chunkJson.contains("\"type\":\"agent_message_chunk\"");
        assert thoughtJson.contains("\"type\":\"agent_thought\"");
    }
}
