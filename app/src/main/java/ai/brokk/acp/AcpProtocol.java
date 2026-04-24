package ai.brokk.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Brokk-local ACP schema additions not yet exposed by the Java ACP SDK. */
final class AcpProtocol {
    static final String METHOD_SESSION_LIST = "session/list";
    static final String METHOD_SESSION_RESUME = "session/resume";
    static final String METHOD_SESSION_CLOSE = "session/close";
    static final String METHOD_SESSION_FORK = "session/fork";

    private AcpProtocol() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InitializeResponse(
            @JsonProperty("protocolVersion") Integer protocolVersion,
            @JsonProperty("agentCapabilities") AgentCapabilities agentCapabilities,
            @JsonProperty("authMethods") List<AcpSchema.AuthMethod> authMethods,
            @JsonProperty("agentInfo") AcpSchema.Implementation agentInfo,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AgentCapabilities(
            @JsonProperty("loadSession") Boolean loadSession,
            @JsonProperty("mcpCapabilities") @Nullable AcpSchema.McpCapabilities mcpCapabilities,
            @JsonProperty("promptCapabilities") @Nullable AcpSchema.PromptCapabilities promptCapabilities,
            @JsonProperty("sessionCapabilities") SessionCapabilities sessionCapabilities,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionCapabilities(
            @JsonProperty("list") @Nullable SessionListCapabilities list,
            @JsonProperty("resume") @Nullable SessionResumeCapabilities resume,
            @JsonProperty("close") @Nullable SessionCloseCapabilities close,
            @JsonProperty("fork") @Nullable SessionForkCapabilities fork,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionListCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionResumeCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionCloseCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionForkCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ListSessionsRequest(
            @JsonProperty("cursor") @Nullable String cursor,
            @JsonProperty("cwd") @Nullable String cwd,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ListSessionsResponse(
            @JsonProperty("sessions") List<SessionInfo> sessions,
            @JsonProperty("nextCursor") @Nullable String nextCursor,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionInfo(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("cwd") String cwd,
            @JsonProperty("title") @Nullable String title,
            @JsonProperty("updatedAt") @Nullable String updatedAt,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResumeSessionRequest(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("cwd") String cwd,
            @JsonProperty("mcpServers") @Nullable List<AcpSchema.McpServer> mcpServers,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResumeSessionResponse(
            @JsonProperty("modes") AcpSchema.SessionModeState modes,
            @JsonProperty("models") AcpSchema.SessionModelState models,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CloseSessionRequest(
            @JsonProperty("sessionId") String sessionId, @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CloseSessionResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ForkSessionRequest(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("cwd") String cwd,
            @JsonProperty("mcpServers") @Nullable List<AcpSchema.McpServer> mcpServers,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ForkSessionResponse(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("modes") AcpSchema.SessionModeState modes,
            @JsonProperty("models") AcpSchema.SessionModelState models,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}
}
