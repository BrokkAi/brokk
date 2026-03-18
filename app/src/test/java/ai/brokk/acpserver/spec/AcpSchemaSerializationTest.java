package ai.brokk.acpserver.spec;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.acpserver.spec.AcpSchema.AgentCapabilities;
import ai.brokk.acpserver.spec.AcpSchema.AgentInfo;
import ai.brokk.acpserver.spec.AcpSchema.AgentMessageChunk;
import ai.brokk.acpserver.spec.AcpSchema.AgentThought;
import ai.brokk.acpserver.spec.AcpSchema.ClientCapabilities;
import ai.brokk.acpserver.spec.AcpSchema.Content;
import ai.brokk.acpserver.spec.AcpSchema.ImageContent;
import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.spec.AcpSchema.SessionUpdate;
import ai.brokk.acpserver.spec.AcpSchema.SessionUpdateNotification;
import ai.brokk.acpserver.spec.AcpSchema.StopReason;
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
    void initializeRequestRoundTrip() throws Exception {
        var caps = new ClientCapabilities(true, false);
        var request = new InitializeRequest(1, caps);

        String json = mapper.writeValueAsString(request);
        var deserialized = mapper.readValue(json, InitializeRequest.class);

        assertEquals(request.protocolVersion(), deserialized.protocolVersion());
        assertEquals(request.capabilities(), deserialized.capabilities());
    }

    @Test
    void initializeResponseOk() throws Exception {
        var response = InitializeResponse.ok();

        String json = mapper.writeValueAsString(response);
        var deserialized = mapper.readValue(json, InitializeResponse.class);

        assertEquals(1, deserialized.protocolVersion());
        assertEquals(AgentCapabilities.DEFAULT, deserialized.capabilities());
        assertNull(deserialized.agentInfo());
    }

    @Test
    void initializeResponseWithAgentInfo() throws Exception {
        var info = new AgentInfo("Brokk", "1.0.0");
        var response = new InitializeResponse(1, new AgentCapabilities(true), info);

        String json = mapper.writeValueAsString(response);
        var deserialized = mapper.readValue(json, InitializeResponse.class);

        assertEquals("Brokk", deserialized.agentInfo().name());
        assertEquals("1.0.0", deserialized.agentInfo().version());
    }

    @Test
    void newSessionRequestRoundTrip() throws Exception {
        var context = List.of(new TextContent("Initial context"));
        var request = new NewSessionRequest("/workspace/project", context);

        String json = mapper.writeValueAsString(request);
        var deserialized = mapper.readValue(json, NewSessionRequest.class);

        assertEquals("/workspace/project", deserialized.workingDirectory());
        assertEquals(1, deserialized.context().size());
        assertEquals("Initial context", deserialized.context().getFirst().text());
    }

    @Test
    void newSessionResponseRoundTrip() throws Exception {
        var response = new NewSessionResponse("session-123", null, null);

        String json = mapper.writeValueAsString(response);
        var deserialized = mapper.readValue(json, NewSessionResponse.class);

        assertEquals("session-123", deserialized.sessionId());
        assertNull(deserialized.error());
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
    void promptResponseEndTurn() throws Exception {
        var response = PromptResponse.endTurn();

        String json = mapper.writeValueAsString(response);
        var deserialized = mapper.readValue(json, PromptResponse.class);

        assertEquals(StopReason.END_TURN, deserialized.stopReason());
        assertNull(deserialized._meta());
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

        // Verify type field is present
        assert textJson.contains("\"type\":\"text\"");
        assert imageJson.contains("\"type\":\"image\"");
    }

    @Test
    void sessionUpdateTypeDiscriminator() throws Exception {
        String chunkJson = mapper.writeValueAsString(new AgentMessageChunk(new TextContent("test")));
        String thoughtJson = mapper.writeValueAsString(new AgentThought("thinking"));

        // Verify type field is present
        assert chunkJson.contains("\"type\":\"agent_message_chunk\"");
        assert thoughtJson.contains("\"type\":\"agent_thought\"");
    }
}
