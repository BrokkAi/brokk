package ai.brokk.acpserver.spec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * ACP (Agent Client Protocol) schema types.
 * <p>
 * This mirrors the official SDK's {@code com.agentclientprotocol.sdk.spec.AcpSchema} structure,
 * providing Java records for protocol messages, responses, and content types.
 */
public final class AcpSchema {

    private AcpSchema() {}

    // ========== Requests ==========

    public record InitializeRequest(int protocolVersion, @Nullable ClientCapabilities capabilities) {}

    public record NewSessionRequest(@Nullable String workingDirectory, @Nullable List<TextContent> context) {}

    public record PromptRequest(String sessionId, List<Content> messages) {}

    public record ModelsListRequest() {}

    public record ContextGetRequest() {}

    public record ContextAddFilesRequest(List<String> relativePaths) {}

    public record ContextDropRequest(List<String> fragmentIds) {}

    public record SessionsListRequest() {}

    public record CancelRequest(@Nullable String sessionId) {}

    public record SessionSwitchRequest(@Nullable String sessionId) {}

    public record GetConversationRequest() {}

    // ========== Responses ==========

    public record InitializeResponse(
            int protocolVersion, AgentCapabilities capabilities, @Nullable AgentInfo agentInfo) {
        public static InitializeResponse ok() {
            return new InitializeResponse(1, AgentCapabilities.DEFAULT, null);
        }
    }

    public record NewSessionResponse(String sessionId, @Nullable String error, @Nullable Object _meta) {}

    public record PromptResponse(StopReason stopReason, @Nullable Object _meta) {
        public static PromptResponse endTurn() {
            return new PromptResponse(StopReason.END_TURN, null);
        }
    }

    public record ModelInfo(String name, String location) {}

    public record ModelsListResponse(List<ModelInfo> models) {}

    public record ContextFragmentInfo(String id, String type, String description) {}

    public record ContextGetResponse(List<ContextFragmentInfo> fragments) {}

    public record ContextAddFilesResponse(List<String> addedFragmentIds) {}

    public record ContextDropResponse(List<String> droppedFragmentIds) {}

    public record SessionInfoDto(String id, String name, long created, long modified) {}

    public record SessionsListResponse(List<SessionInfoDto> sessions) {}

    public record SessionSwitchResponse(String status, String sessionId) {}

    public record ConversationMessage(String role, String text, @Nullable String reasoning) {}

    public record ConversationEntry(
            int sequence,
            boolean isCompressed,
            @Nullable String taskType,
            @Nullable List<ConversationMessage> messages,
            @Nullable String summary) {}

    public record GetConversationResponse(List<ConversationEntry> entries) {}

    // ========== Content Types ==========

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextContent.class, name = "text"),
        @JsonSubTypes.Type(value = ImageContent.class, name = "image")
    })
    public sealed interface Content permits TextContent, ImageContent {}

    public record TextContent(String text) implements Content {}

    public record ImageContent(String data, String mimeType) implements Content {}

    // ========== Session Updates (for streaming) ==========

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = AgentMessageChunk.class, name = "agent_message_chunk"),
        @JsonSubTypes.Type(value = AgentThought.class, name = "agent_thought")
    })
    public sealed interface SessionUpdate permits AgentMessageChunk, AgentThought {}

    public record AgentMessageChunk(Content content) implements SessionUpdate {}

    public record AgentThought(String thought) implements SessionUpdate {}

    public record SessionUpdateNotification(String sessionId, SessionUpdate update) {}

    // ========== Capabilities ==========

    public record ClientCapabilities(boolean readTextFile, boolean writeTextFile) {
        public static final ClientCapabilities DEFAULT = new ClientCapabilities(false, false);
    }

    public record AgentCapabilities(boolean streaming) {
        public static final AgentCapabilities DEFAULT = new AgentCapabilities(true);
    }

    public record AgentInfo(String name, String version) {}

    // ========== Enums ==========

    public enum StopReason {
        END_TURN,
        MAX_TOKENS,
        ERROR
    }
}
