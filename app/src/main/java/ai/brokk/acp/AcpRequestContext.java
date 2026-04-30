package ai.brokk.acp;

import static java.util.Objects.requireNonNull;

import com.agentclientprotocol.sdk.agent.Command;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.TypeRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Mono;

/** Blocking prompt context backed directly by an {@link AcpAgentSession}. */
final class AcpRequestContext implements AcpPromptContext {

    private static final Logger logger = LogManager.getLogger(AcpRequestContext.class);

    /**
     * Tools whose permission verdicts must NEVER be cached with {@code allow_always} /
     * {@code reject_always}. {@code "shell"} is here because the cache key is the literal string
     * "shell" — caching one approval would blanket-allow every future shell command in the session,
     * including destructive ones the user never saw. {@code "unknown"} is the sentinel from
     * {@link AcpPromptContext}'s single-arg default for non-tool prompts (confirm dialogs).
     */
    private static final Set<String> NON_CACHEABLE_TOOL_NAMES = Set.of("shell", "unknown");

    /**
     * Per-permission round-trip timeout. The SDK's global request timeout (configured in {@link
     * BrokkAcpRuntime} at {@code PERMISSION_TIMEOUT + 5 min}) propagates as an uncaught exception
     * on expiry rather than something the user sees — observed in real sessions where a click on
     * "Allow once" never reached the agent (IDE-side or pipe issue) and brokk silently crashed the
     * prompt. With this tighter timeout, the prompt is denied and the user gets a chat message
     * explaining what happened, while leaving generous think-time for an attentive user to click.
     *
     * <p>Package-private so {@link AcpServerMain}'s watchdog can quote it symbolically in
     * diagnostic log messages.
     */
    static final Duration PERMISSION_TIMEOUT = Duration.ofMinutes(30);

    /**
     * Round-trip timeout for non-permission ACP requests (file I/O, terminal ops). Bounds how long
     * a {@link reactor.core.scheduler.Schedulers#boundedElastic()} thread can be pinned by a stuck
     * IDE round-trip; without this cap, the SDK's session timeout in {@link BrokkAcpRuntime} is the
     * only ceiling, which is too generous for ops like {@code fs/read_text_file} that should
     * normally complete in milliseconds.
     */
    private static final Duration IO_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Number of agent→client {@code session/request_permission} calls currently awaiting a
     * response. Exposed (package-private) so {@link AcpServerMain}'s inbound watchdog can decide
     * whether prolonged stdin silence is suspicious or just an idle session.
     */
    static final AtomicInteger OUTSTANDING_PERMISSION_REQUESTS = new AtomicInteger(0);

    private final AcpAgentSession session;
    private final String sessionId;
    private final @Nullable NegotiatedCapabilities clientCapabilities;
    private final @Nullable BrokkAcpAgent agent;

    AcpRequestContext(
            AcpAgentSession session,
            String sessionId,
            @Nullable NegotiatedCapabilities clientCapabilities,
            @Nullable BrokkAcpAgent agent) {
        this.session = session;
        this.sessionId = sessionId;
        this.clientCapabilities = clientCapabilities;
        this.agent = agent;
    }

    @Override
    public void sendUpdate(String sessionId, AcpSchema.SessionUpdate update) {
        sendSessionUpdate(session, sessionId, update);
    }

    static void sendSessionUpdate(AcpAgentSession session, String sessionId, AcpSchema.SessionUpdate update) {
        session.sendNotification(AcpSchema.METHOD_SESSION_UPDATE, new AcpSchema.SessionNotification(sessionId, update))
                .block();
    }

    @Override
    public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
        return requireNonNull(session.sendRequest(
                        AcpSchema.METHOD_FS_READ_TEXT_FILE, request, new TypeRef<AcpSchema.ReadTextFileResponse>() {})
                .timeout(IO_TIMEOUT)
                .block());
    }

    @Override
    public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
        return requireNonNull(session.sendRequest(
                        AcpSchema.METHOD_FS_WRITE_TEXT_FILE, request, new TypeRef<AcpSchema.WriteTextFileResponse>() {})
                .timeout(IO_TIMEOUT)
                .block());
    }

    @Override
    public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
        OUTSTANDING_PERMISSION_REQUESTS.incrementAndGet();
        try {
            return requireNonNull(session.sendRequest(
                            AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
                            request,
                            new TypeRef<AcpSchema.RequestPermissionResponse>() {})
                    .timeout(PERMISSION_TIMEOUT)
                    .onErrorResume(TimeoutException.class, e -> {
                        logger.warn(
                                "Permission request timed out after {} (no response from client). "
                                        + "Treating as denied. Request: {}",
                                PERMISSION_TIMEOUT,
                                request);
                        // Compose the notification into the Mono chain — calling sendMessage()
                        // here would invoke .block() on Reactor's parallel scheduler thread,
                        // which Reactor forbids and would replace the timeout error with an
                        // IllegalStateException.
                        var timeoutText = "\n**Permission request timed out** after "
                                + PERMISSION_TIMEOUT.toSeconds()
                                + "s without a response from the client. Treating this tool call as denied. "
                                + "If you did click Allow, the IDE may have failed to deliver the response — "
                                + "check `~/.brokk/debug.log` for the `acp-agent-inbound` thread.\n";
                        var update = new AcpSchema.AgentMessageChunk(
                                "agent_message_chunk", new AcpSchema.TextContent(timeoutText));
                        return session.sendNotification(
                                        AcpSchema.METHOD_SESSION_UPDATE,
                                        new AcpSchema.SessionNotification(sessionId, update))
                                .onErrorResume(t -> {
                                    logger.warn("Failed to deliver timeout notification: {}", t.getMessage());
                                    return Mono.empty();
                                })
                                .thenReturn(
                                        new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled()));
                    })
                    .block());
        } finally {
            OUTSTANDING_PERMISSION_REQUESTS.decrementAndGet();
        }
    }

    @Override
    public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
        return requireNonNull(session.sendRequest(
                        AcpSchema.METHOD_TERMINAL_CREATE, request, new TypeRef<AcpSchema.CreateTerminalResponse>() {})
                .timeout(IO_TIMEOUT)
                .block());
    }

    @Override
    public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
        return requireNonNull(session.sendRequest(
                        AcpSchema.METHOD_TERMINAL_OUTPUT, request, new TypeRef<AcpSchema.TerminalOutputResponse>() {})
                .timeout(IO_TIMEOUT)
                .block());
    }

    @Override
    public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
        return requireNonNull(session.sendRequest(
                        AcpSchema.METHOD_TERMINAL_RELEASE, request, new TypeRef<AcpSchema.ReleaseTerminalResponse>() {})
                .timeout(IO_TIMEOUT)
                .block());
    }

    @Override
    public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
        return requireNonNull(session.sendRequest(
                        AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT,
                        request,
                        new TypeRef<AcpSchema.WaitForTerminalExitResponse>() {})
                .timeout(IO_TIMEOUT)
                .block());
    }

    @Override
    public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
        return requireNonNull(session.sendRequest(
                        AcpSchema.METHOD_TERMINAL_KILL,
                        request,
                        new TypeRef<AcpSchema.KillTerminalCommandResponse>() {})
                .timeout(IO_TIMEOUT)
                .block());
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
        return readTextFile(new AcpSchema.ReadTextFileRequest(sessionId, path, startLine, lineCount))
                .content();
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
    public boolean askPermission(String action, String toolName) {
        return askPermissionDetailed(action, toolName, toolName, false, null).isApproved();
    }

    @Override
    public PermissionDecision askPermissionDetailed(
            String action, String toolName, String cacheKey, boolean offerSandboxBypass, @Nullable String rawCommand) {
        boolean cacheable = !NON_CACHEABLE_TOOL_NAMES.contains(toolName);
        var cache = cacheable ? agent : null;

        Optional<BrokkAcpAgent.PermissionVerdict> sticky =
                cache != null ? cache.stickyPermissionFor(sessionId, cacheKey) : Optional.empty();

        // Session-level PermissionMode is consulted BEFORE the sticky cache so READ_ONLY can deny
        // even tools the user previously approved, and BYPASS_PERMISSIONS can short-circuit before
        // any user-facing prompt fires. Mirrors brokk-acp-rust/src/tool_loop.rs:pure_gate_decision.
        if (agent != null) {
            var mode = agent.permissionModeFor(sessionId);
            var kind = PermissionGate.classify(toolName);
            boolean alwaysAllowed = sticky.filter(v -> v != BrokkAcpAgent.PermissionVerdict.DENY)
                    .isPresent();
            switch (PermissionGate.decide(mode, kind, toolName, alwaysAllowed, rawCommand)) {
                case ALLOW -> {
                    return sticky.filter(v -> v == BrokkAcpAgent.PermissionVerdict.ALLOW_NO_SANDBOX)
                                    .isPresent()
                            ? PermissionDecision.ALLOW_NO_SANDBOX
                            : PermissionDecision.ALLOW;
                }
                case REJECT -> {
                    sendMessage("\n**" + toolName + " denied:** " + PermissionGate.READ_ONLY_REJECTION + "\n");
                    return PermissionDecision.DENY;
                }
                case PROMPT -> {
                    // Fall through to the legacy sticky-cache + prompt path. We still need the
                    // sticky cache on the deny side (a previous reject_always must continue to deny
                    // without a round-trip), which gateDecision didn't consult.
                }
            }
        }

        if (sticky.isPresent()) {
            return switch (sticky.get()) {
                case ALLOW -> PermissionDecision.ALLOW;
                case ALLOW_NO_SANDBOX -> PermissionDecision.ALLOW_NO_SANDBOX;
                case DENY -> PermissionDecision.DENY;
            };
        }

        var toolCall = new AcpSchema.ToolCallUpdate(
                UUID.randomUUID().toString(),
                action,
                AcpSchema.ToolKind.EDIT,
                AcpSchema.ToolCallStatus.PENDING,
                null,
                null,
                null,
                null);
        var options = buildPermissionOptions(cacheable, offerSandboxBypass);
        var response = requestPermission(new AcpSchema.RequestPermissionRequest(sessionId, toolCall, options));
        if (!(response.outcome() instanceof AcpSchema.PermissionSelected selected)) {
            return PermissionDecision.DENY;
        }
        var optionId = selected.optionId();
        if (cache != null) {
            switch (optionId) {
                case "allow_always" ->
                    cache.rememberPermission(sessionId, cacheKey, BrokkAcpAgent.PermissionVerdict.ALLOW);
                case "allow_no_sandbox_always" ->
                    cache.rememberPermission(sessionId, cacheKey, BrokkAcpAgent.PermissionVerdict.ALLOW_NO_SANDBOX);
                case "reject_always" ->
                    cache.rememberPermission(sessionId, cacheKey, BrokkAcpAgent.PermissionVerdict.DENY);
                default -> {}
            }
        }
        return switch (optionId) {
            case "allow_once", "allow_always" -> PermissionDecision.ALLOW;
            case "allow_no_sandbox_once", "allow_no_sandbox_always" -> PermissionDecision.ALLOW_NO_SANDBOX;
            default -> PermissionDecision.DENY;
        };
    }

    /**
     * Build the permission-option set for an ACP {@code session/request_permission}. Order is
     * load-bearing: ACP clients render options in array order, and the first option is shown as
     * the default — so safer choices come first.
     */
    private static List<AcpSchema.PermissionOption> buildPermissionOptions(
            boolean cacheable, boolean offerSandboxBypass) {
        var options = new ArrayList<AcpSchema.PermissionOption>();
        options.add(new AcpSchema.PermissionOption(
                "allow_once", cacheable ? "Allow once" : "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE));
        if (cacheable) {
            options.add(new AcpSchema.PermissionOption(
                    "allow_always", "Always allow", AcpSchema.PermissionOptionKind.ALLOW_ALWAYS));
        }
        if (offerSandboxBypass) {
            options.add(new AcpSchema.PermissionOption(
                    "allow_no_sandbox_once", "Allow without sandbox", AcpSchema.PermissionOptionKind.ALLOW_ONCE));
            if (cacheable) {
                options.add(new AcpSchema.PermissionOption(
                        "allow_no_sandbox_always",
                        "Always allow without sandbox",
                        AcpSchema.PermissionOptionKind.ALLOW_ALWAYS));
            }
        }
        options.add(new AcpSchema.PermissionOption(
                "reject_once", cacheable ? "Reject once" : "Reject", AcpSchema.PermissionOptionKind.REJECT_ONCE));
        if (cacheable) {
            options.add(new AcpSchema.PermissionOption(
                    "reject_always", "Always reject", AcpSchema.PermissionOptionKind.REJECT_ALWAYS));
        }
        return options;
    }

    @Override
    public @Nullable String askChoice(String question, String... options) {
        if (options.length < 2) {
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
        var response =
                requestPermission(new AcpSchema.RequestPermissionRequest(sessionId, toolCall, permissionOptions));
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
