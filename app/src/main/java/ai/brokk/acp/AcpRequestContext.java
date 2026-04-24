package ai.brokk.acp;

import com.agentclientprotocol.sdk.agent.Command;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.TypeRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Blocking prompt context backed directly by an {@link AcpAgentSession}. */
final class AcpRequestContext implements SyncPromptContext {
    private final AcpAgentSession session;
    private final String sessionId;
    private final @Nullable NegotiatedCapabilities clientCapabilities;

    AcpRequestContext(
            AcpAgentSession session, String sessionId, @Nullable NegotiatedCapabilities clientCapabilities) {
        this.session = session;
        this.sessionId = sessionId;
        this.clientCapabilities = clientCapabilities;
    }

    @Override
    public void sendUpdate(String sessionId, AcpSchema.SessionUpdate update) {
        sendSessionUpdate(session, sessionId, update);
    }

    static void sendSessionUpdate(AcpAgentSession session, String sessionId, AcpSchema.SessionUpdate update) {
        session.sendNotification(
                        AcpSchema.METHOD_SESSION_UPDATE, new AcpSchema.SessionNotification(sessionId, update))
                .block();
    }

    @Override
    public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_FS_READ_TEXT_FILE,
                        request,
                        new TypeRef<AcpSchema.ReadTextFileResponse>() {})
                .block();
    }

    @Override
    public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_FS_WRITE_TEXT_FILE,
                        request,
                        new TypeRef<AcpSchema.WriteTextFileResponse>() {})
                .block();
    }

    @Override
    public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
                        request,
                        new TypeRef<AcpSchema.RequestPermissionResponse>() {})
                .block();
    }

    @Override
    public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_TERMINAL_CREATE,
                        request,
                        new TypeRef<AcpSchema.CreateTerminalResponse>() {})
                .block();
    }

    @Override
    public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_TERMINAL_OUTPUT,
                        request,
                        new TypeRef<AcpSchema.TerminalOutputResponse>() {})
                .block();
    }

    @Override
    public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_TERMINAL_RELEASE,
                        request,
                        new TypeRef<AcpSchema.ReleaseTerminalResponse>() {})
                .block();
    }

    @Override
    public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT,
                        request,
                        new TypeRef<AcpSchema.WaitForTerminalExitResponse>() {})
                .block();
    }

    @Override
    public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
        return session
                .sendRequest(
                        AcpSchema.METHOD_TERMINAL_KILL,
                        request,
                        new TypeRef<AcpSchema.KillTerminalCommandResponse>() {})
                .block();
    }

    @Override
    public @Nullable NegotiatedCapabilities getClientCapabilities() {
        return clientCapabilities;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public void sendMessage(String text) {
        sendUpdate(sessionId, new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent(text)));
    }

    @Override
    public void sendThought(String text) {
        sendUpdate(sessionId, new AcpSchema.AgentThoughtChunk("agent_thought_chunk", new AcpSchema.TextContent(text)));
    }

    @Override
    public String readFile(String path) {
        return readFile(path, null, null);
    }

    @Override
    public String readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount) {
        return readTextFile(new AcpSchema.ReadTextFileRequest(sessionId, path, startLine, lineCount)).content();
    }

    @Override
    public Optional<String> tryReadFile(String path) {
        try {
            return Optional.ofNullable(readFile(path));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void writeFile(String path, String content) {
        writeTextFile(new AcpSchema.WriteTextFileRequest(sessionId, path, content));
    }

    @Override
    public boolean askPermission(String action) {
        var toolCall = new AcpSchema.ToolCallUpdate(
                UUID.randomUUID().toString(),
                action,
                AcpSchema.ToolKind.EDIT,
                AcpSchema.ToolCallStatus.PENDING,
                null,
                null,
                null,
                null);
        var options = List.of(
                new AcpSchema.PermissionOption(
                        "allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE),
                new AcpSchema.PermissionOption(
                        "deny", "Deny", AcpSchema.PermissionOptionKind.REJECT_ONCE));
        var response = requestPermission(new AcpSchema.RequestPermissionRequest(sessionId, toolCall, options));
        return response.outcome() instanceof AcpSchema.PermissionSelected selected
                && selected.optionId().equals("allow");
    }

    @Override
    public @Nullable String askChoice(String question, String... options) {
        if (options == null || options.length < 2) {
            throw new IllegalArgumentException("At least 2 options are required");
        }
        var permissionOptions = new ArrayList<AcpSchema.PermissionOption>();
        for (int i = 0; i < options.length; i++) {
            permissionOptions.add(new AcpSchema.PermissionOption(
                    String.valueOf(i), options[i], AcpSchema.PermissionOptionKind.ALLOW_ONCE));
        }
        var toolCall = new AcpSchema.ToolCallUpdate(
                UUID.randomUUID().toString(),
                question,
                AcpSchema.ToolKind.OTHER,
                AcpSchema.ToolCallStatus.PENDING,
                null,
                null,
                null,
                null);
        var response = requestPermission(new AcpSchema.RequestPermissionRequest(sessionId, toolCall, permissionOptions));
        if (response.outcome() instanceof AcpSchema.PermissionSelected selected) {
            return options[Integer.parseInt(selected.optionId())];
        }
        return null;
    }

    @Override
    public CommandResult execute(String... commandAndArgs) {
        return execute(Command.of(commandAndArgs));
    }

    @Override
    public CommandResult execute(Command command) {
        throw new UnsupportedOperationException("Terminal execution is not implemented by Brokk ACP context");
    }
}
