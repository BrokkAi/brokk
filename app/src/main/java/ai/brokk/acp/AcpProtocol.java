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
    static final String METHOD_SESSION_SET_CONFIG_OPTION = "session/set_config_option";

    private AcpProtocol() {}

    // ---- Session config options (dropdowns) ----
    //
    // Mirrors {@code agent-client-protocol-schema-0.12.0::SessionConfigOption} so the JSON we put
    // on the wire matches what Zed and other ACP clients expect from the Rust reference server.
    // The SDK at 0.10.0 has none of these types; once it gains them we can drop these records.

    /**
     * A single dropdown selector advertised by the agent. Serializes as a flat JSON object with
     * {@code type: "select"} and the select-specific fields ({@code currentValue}, {@code options})
     * promoted to the top level (matching the {@code #[serde(flatten)]} on the Rust enum).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionConfigOption(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") @Nullable String description,
            @JsonProperty("category") @Nullable String category,
            @JsonProperty("type") String type,
            @JsonProperty("currentValue") String currentValue,
            @JsonProperty("options") List<SessionConfigSelectOption> options) {

        /** Builds a "select" (single-value dropdown) option with the standard {@code mode} category. */
        static SessionConfigOption select(
                String id,
                String name,
                @Nullable String description,
                String currentValue,
                List<SessionConfigSelectOption> options) {
            return select(id, name, description, "mode", currentValue, options);
        }

        /** Builds a "select" option with an explicit category (e.g. {@code "model"}). */
        static SessionConfigOption select(
                String id,
                String name,
                @Nullable String description,
                String category,
                String currentValue,
                List<SessionConfigSelectOption> options) {
            return new SessionConfigOption(id, name, description, category, "select", currentValue, options);
        }
    }

    /** A single value within a {@link SessionConfigOption} dropdown. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionConfigSelectOption(
            @JsonProperty("value") String value,
            @JsonProperty("name") String name,
            @JsonProperty("description") @Nullable String description) {}

    /** Request body for {@code session/set_config_option}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SetSessionConfigOptionRequest(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("configId") String configId,
            @JsonProperty("value") String value,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    /** Response to {@code session/set_config_option}: the full set after the change. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SetSessionConfigOptionResponse(
            @JsonProperty("configOptions") List<SessionConfigOption> configOptions,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    // ---- Extended session-creation responses ----
    //
    // The SDK's NewSessionResponse and LoadSessionResponse don't carry {@code configOptions}; we
    // wrap them here so the agent can advertise the permission/behavior dropdowns. Field order
    // mirrors the SDK records so the wire JSON is a strict superset.

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NewSessionResponseExt(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("modes") AcpSchema.SessionModeState modes,
            @JsonProperty("models") AcpSchema.SessionModelState models,
            @JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LoadSessionResponseExt(
            @JsonProperty("modes") AcpSchema.SessionModeState modes,
            @JsonProperty("models") AcpSchema.SessionModelState models,
            @JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}

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
            @JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
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
            @JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
            @JsonProperty("_meta") @Nullable Map<String, Object> meta) {}
}
