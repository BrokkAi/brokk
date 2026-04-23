package ai.brokk.acp;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.util.Messages;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.annotation.SetSessionModel;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Annotation-based ACP agent that exposes Brokk's code intelligence and execution
 * capabilities via the Agent Client Protocol over stdio.
 *
 * <p>Handles the full ACP lifecycle: initialization, session management, prompt
 * execution, mode/model configuration, and cancellation.
 */
@AcpAgent(name = "brokk", version = BuildInfo.version)
public class BrokkAcpAgent {
    private static final Logger logger = LogManager.getLogger(BrokkAcpAgent.class);

    private static final String DEFAULT_CODE_MODEL = "gemini-3-flash-preview";
    private static final String DEFAULT_REASONING_LEVEL = "medium";
    private static final String DEFAULT_REASONING_LEVEL_CODE = "disable";
    private static final Set<String> REASONING_LEVEL_IDS = Set.of("low", "medium", "high", "disable", "default");
    private static final Path ACP_SETTINGS_PATH =
            Path.of(System.getProperty("user.home"), ".brokk", "acp_settings.json");

    private static final List<AcpSchema.SessionMode> AVAILABLE_MODES = List.of(
            new AcpSchema.SessionMode("LUTZ", "LUTZ", "Agentic loop with task list"),
            new AcpSchema.SessionMode("CODE", "CODE", "Code changes only"),
            new AcpSchema.SessionMode("ASK", "ASK", "Question answering"),
            new AcpSchema.SessionMode("PLAN", "PLAN", "Planning only"));

    private final ContextManager cm;
    private final JobRunner jobRunner;
    private final JobStore jobStore;

    // Per-session state
    private final Map<String, String> modeBySession = new ConcurrentHashMap<>();
    private final Map<String, String> modelBySession = new ConcurrentHashMap<>();
    private final Map<String, String> reasoningBySession = new ConcurrentHashMap<>();
    private final Map<String, String> activeJobBySession = new ConcurrentHashMap<>();

    // Persisted defaults (loaded once on construction)
    private volatile String defaultModelId;
    private volatile String defaultReasoningLevel;

    // Set after AcpAgentSupport.build() to enable session updates outside of prompt context
    private volatile @Nullable AcpSyncAgent syncAgent;

    public BrokkAcpAgent(ContextManager cm, JobRunner jobRunner, JobStore jobStore) {
        this.cm = cm;
        this.jobRunner = jobRunner;
        this.jobStore = jobStore;

        var defaults = loadAcpDefaults();
        this.defaultModelId = defaults.defaultModel;
        this.defaultReasoningLevel = defaults.defaultReasoning;
        logger.info("ACP defaults loaded: model={}, reasoning={}", defaultModelId, defaultReasoningLevel);
    }

    public void setSyncAgent(AcpSyncAgent agent) {
        this.syncAgent = agent;
    }

    @Initialize
    public AcpSchema.InitializeResponse initialize() {
        logger.info("ACP initialize");
        var capabilities = new AcpSchema.AgentCapabilities(
                true, // loadSession
                null, // mcpCapabilities
                new AcpSchema.PromptCapabilities(null, true, null), // embeddedContext = true
                Map.of("brokk", Map.of("context", true, "completions", true, "costs", true)));
        return new AcpSchema.InitializeResponse(
                AcpSchema.LATEST_PROTOCOL_VERSION,
                capabilities,
                List.of(),
                new AcpSchema.Implementation("brokk", BuildInfo.version, "Brokk Code Intelligence"),
                null);
    }

    @NewSession
    public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
        logger.info("ACP new session, cwd={}", request.cwd());

        // Create session in ContextManager and use its UUID as the ACP session ID
        cm.createSessionAsync("ACP Session").join();
        var sessionId = cm.getCurrentSessionId().toString();

        modeBySession.put(sessionId, "LUTZ");
        reasoningBySession.put(sessionId, defaultReasoningLevel);

        var modeState = new AcpSchema.SessionModeState("LUTZ", AVAILABLE_MODES);
        var modelState = buildModelState(sessionId);
        var meta = buildVariantMeta(sessionId);

        scheduleAvailableCommandsUpdate(sessionId);

        return new AcpSchema.NewSessionResponse(sessionId, modeState, modelState, meta);
    }

    @LoadSession
    public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest request) {
        logger.info("ACP load session {}", request.sessionId());
        var sessionId = request.sessionId();

        // Switch to the requested session, or fail if it doesn't exist
        var sessions = cm.getProject().getSessionManager().listSessions();
        var target = sessions.stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        cm.switchSessionAsync(target.id()).join();

        modeBySession.putIfAbsent(sessionId, "LUTZ");
        reasoningBySession.putIfAbsent(sessionId, defaultReasoningLevel);

        var modeState = new AcpSchema.SessionModeState(modeBySession.getOrDefault(sessionId, "LUTZ"), AVAILABLE_MODES);
        var modelState = buildModelState(sessionId);
        var meta = buildVariantMeta(sessionId);

        // Schedule conversation replay and commands advertisement after response is sent
        scheduleConversationReplay(sessionId);
        scheduleAvailableCommandsUpdate(sessionId);

        return new AcpSchema.LoadSessionResponse(modeState, modelState, meta);
    }

    @Prompt
    public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext promptContext) {
        var sessionId = request.sessionId();
        var text = request.text();
        logger.info(
                "ACP prompt session={} text={}",
                sessionId,
                text != null ? text.substring(0, Math.min(80, text.length())) : "(empty)");

        if (text == null || text.isBlank()) {
            promptContext.sendMessage("Error: empty prompt");
            return AcpSchema.PromptResponse.endTurn();
        }

        // Handle slash commands
        if (text.strip().toLowerCase(Locale.ROOT).startsWith("/context")) {
            handleContextCommand(sessionId, promptContext);
            return AcpSchema.PromptResponse.endTurn();
        }

        // Switch to the requested session so the job executes against the correct state
        var sessions = cm.getProject().getSessionManager().listSessions();
        var target = sessions.stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst();
        if (target.isEmpty()) {
            promptContext.sendMessage("Error: unknown session " + sessionId);
            return AcpSchema.PromptResponse.endTurn();
        }
        cm.switchSessionAsync(target.get().id()).join();

        // Build JobSpec with reasoning levels from session state
        var mode = modeBySession.getOrDefault(sessionId, "LUTZ");
        var model = modelBySession.getOrDefault(sessionId, "");
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var tags = new HashMap<String, String>();
        tags.put("mode", mode);

        // Fall back to planner model if default code model is not available
        var availableModels = cm.getService().getAvailableModels();
        var codeModel = availableModels.containsKey(DEFAULT_CODE_MODEL) ? DEFAULT_CODE_MODEL : model;

        var spec = new JobSpec(
                text, // taskInput
                true, // autoCommit
                true, // autoCompress
                model, // plannerModel
                null, // scanModel
                codeModel, // codeModel
                false, // preScan
                tags, // tags
                null, // sourceBranch
                null, // targetBranch
                reasoning, // reasoningLevel
                DEFAULT_REASONING_LEVEL_CODE, // reasoningLevelCode
                null, // temperature
                null, // temperatureCode
                false, // skipVerification
                null); // maxIssueFixAttempts

        // Create console adapter and run the job
        var acpConsole = new AcpConsoleIO(promptContext);
        var jobId = UUID.randomUUID().toString();
        activeJobBySession.put(sessionId, jobId);

        try {
            // Create job in store
            jobStore.createOrGetJob(jobId, spec);

            // Run using the ACP console -- JobRunner handles cm.setIo swap internally
            jobRunner.runAsync(jobId, spec, acpConsole).join();
        } catch (Exception e) {
            logger.error("Job execution failed", e);
            promptContext.sendMessage("\n**Error:** " + e.getMessage() + "\n");
        } finally {
            activeJobBySession.remove(sessionId, jobId);
        }

        return AcpSchema.PromptResponse.endTurn();
    }

    @SetSessionMode
    public AcpSchema.SetSessionModeResponse setMode(AcpSchema.SetSessionModeRequest request) {
        var sessionId = request.sessionId();
        var modeId = request.modeId();
        logger.info("ACP set mode session={} mode={}", sessionId, modeId);

        var validMode = AVAILABLE_MODES.stream().anyMatch(m -> m.id().equals(modeId));
        if (validMode) {
            modeBySession.put(sessionId, modeId);
        }

        return new AcpSchema.SetSessionModeResponse();
    }

    @SetSessionModel
    public AcpSchema.SetSessionModelResponse setModel(AcpSchema.SetSessionModelRequest request) {
        var sessionId = request.sessionId();
        var modelId = request.modelId();
        logger.info("ACP set model session={} model={}", sessionId, modelId);

        // Parse "model/variant" format: split on last "/" and check if last segment is a reasoning level
        var parsed = parseModelSelection(modelId);
        modelBySession.put(sessionId, parsed.baseModel);
        if (parsed.reasoning != null) {
            reasoningBySession.put(sessionId, parsed.reasoning);
        }

        logger.info("ACP set model parsed: base={} reasoning={}", parsed.baseModel, parsed.reasoning);

        // Persist updated defaults
        saveAcpDefaults(parsed.baseModel, reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL));

        return new AcpSchema.SetSessionModelResponse();
    }

    @Cancel
    public void cancel(AcpSchema.CancelNotification notification) {
        var sessionId = notification.sessionId();
        logger.info("ACP cancel session={}", sessionId);
        var jobId = activeJobBySession.get(sessionId);
        if (jobId != null) {
            jobRunner.cancel(jobId);
        } else {
            logger.info("ACP cancel: no active job for session {}", sessionId);
        }
    }

    // ---- Model state with reasoning variants ----

    private AcpSchema.SessionModelState buildModelState(String sessionId) {
        var service = cm.getService();
        var availableModels = service.getAvailableModels();

        // Build model list with reasoning variants (model/low, model/medium, model/high, etc.)
        var models = new ArrayList<AcpSchema.ModelInfo>();
        availableModels.keySet().stream().sorted().forEach(name -> {
            models.add(new AcpSchema.ModelInfo(name, name, null));
            for (var level : List.of("low", "medium", "high", "disable")) {
                models.add(new AcpSchema.ModelInfo(name + "/" + level, name + " (" + level + ")", null));
            }
        });

        if (models.isEmpty()) {
            models.add(new AcpSchema.ModelInfo("default", "Default Model", null));
        }

        // Resolve current model, preferring persisted default for new sessions
        var baseModel = modelBySession.getOrDefault(sessionId, defaultModelId);
        // Validate against available models; fall back to first if not found
        var modelNames = availableModels.keySet();
        if (!baseModel.isEmpty() && !modelNames.contains(baseModel) && !modelNames.isEmpty()) {
            baseModel = modelNames.stream().sorted().findFirst().orElse(baseModel);
        }
        modelBySession.putIfAbsent(sessionId, baseModel);

        // Format current model ID with variant
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var currentModelId = formatModelIdWithVariant(baseModel, reasoning);

        return new AcpSchema.SessionModelState(currentModelId, List.copyOf(models));
    }

    private Map<String, Object> buildVariantMeta(String sessionId) {
        var baseModel = modelBySession.getOrDefault(sessionId, defaultModelId);
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var variants = modelVariantsFor(baseModel);
        return Map.of("brokk", Map.of("modelId", baseModel, "variant", reasoning, "availableVariants", variants));
    }

    private List<String> modelVariantsFor(String modelName) {
        var service = cm.getService();
        if (!service.supportsReasoningEffort(modelName)) {
            return List.of();
        }
        var variants = new ArrayList<>(List.of("low", "medium", "high"));
        if (service.supportsReasoningDisable(modelName)) {
            variants.add("disable");
        }
        return List.copyOf(variants);
    }

    private static String formatModelIdWithVariant(String baseModel, String reasoning) {
        if (reasoning.equals("default") || reasoning.isEmpty()) {
            return baseModel;
        }
        return baseModel + "/" + reasoning;
    }

    // ---- Model selection parsing ----

    private record ParsedModelSelection(String baseModel, @Nullable String reasoning) {}

    private ParsedModelSelection parseModelSelection(String modelId) {
        if (modelId.isBlank()) {
            return new ParsedModelSelection("", null);
        }
        var raw = modelId.strip();

        // Check for "model/variant" format: split on last "/"
        int lastSlash = raw.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < raw.length() - 1) {
            var candidateVariant = raw.substring(lastSlash + 1).strip().toLowerCase(Locale.ROOT);
            if (REASONING_LEVEL_IDS.contains(candidateVariant)) {
                var baseModel = raw.substring(0, lastSlash).strip();
                return new ParsedModelSelection(baseModel, candidateVariant);
            }
        }

        return new ParsedModelSelection(raw, null);
    }

    // ---- Conversation replay ----

    private void scheduleConversationReplay(String sessionId) {
        var agent = this.syncAgent;
        if (agent == null) {
            logger.debug("No syncAgent available, skipping conversation replay for session {}", sessionId);
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                var taskHistory = cm.liveContext().getTaskHistory();
                for (var task : taskHistory) {
                    // Send user prompt as UserMessageChunk
                    var description = task.description();
                    if (!description.isBlank()) {
                        agent.sendSessionUpdate(
                                sessionId,
                                new AcpSchema.UserMessageChunk(
                                        "user_message_chunk", new AcpSchema.TextContent(description)));
                    }

                    // Send agent response as AgentMessageChunk
                    var mopMarkdown = task.mopMarkdown();
                    var responseText = mopMarkdown != null ? mopMarkdown : task.summary();
                    if (responseText != null && !responseText.isBlank()) {
                        agent.sendSessionUpdate(
                                sessionId,
                                new AcpSchema.AgentMessageChunk(
                                        "agent_message_chunk", new AcpSchema.TextContent(responseText)));
                    }
                }
                logger.info("Conversation replay complete for session {} ({} entries)", sessionId, taskHistory.size());
            } catch (Exception e) {
                logger.warn("Conversation replay failed for session {}", sessionId, e);
            }
        });
    }

    // ---- Slash commands ----

    private void handleContextCommand(String sessionId, SyncPromptContext promptContext) {
        logger.info("ACP /context command for session {}", sessionId);
        var live = cm.liveContext();
        var fragments = live.getAllFragmentsInDisplayOrder();

        record FragmentRow(String name, int tokens, double pct) {}

        int totalTokens = 0;
        var rows = new ArrayList<FragmentRow>();
        for (var f : fragments) {
            var name = f.shortDescription().renderNowOr("Unknown");
            int tokens = f.isText() || f.getType().isOutput()
                    ? Messages.getApproximateTokens(f.text().renderNowOr(""))
                    : 0;
            totalTokens += tokens;
            rows.add(new FragmentRow(name, tokens, 0));
        }

        int base = totalTokens > 0 ? totalTokens : 1;
        var withPct = rows.stream()
                .map(r -> new FragmentRow(r.name, r.tokens, (r.tokens / (double) base) * 100))
                .sorted((a, b) -> Double.compare(b.pct, a.pct))
                .toList();

        var top = new ArrayList<>(withPct.stream().limit(4).toList());
        if (withPct.size() > 4) {
            int otherTokens =
                    withPct.stream().skip(4).mapToInt(FragmentRow::tokens).sum();
            double otherPct =
                    withPct.stream().skip(4).mapToDouble(FragmentRow::pct).sum();
            top.add(new FragmentRow("(other)", otherTokens, otherPct));
        }

        var sb = new StringBuilder();
        sb.append("| Fragment | Tokens | % Context |\n");
        sb.append("|---|---:|---:|\n");
        if (top.isEmpty()) {
            sb.append("| (none) | 0 | 0.00% |\n");
        } else {
            for (var row : top) {
                sb.append("| %s | %,d | %.2f%% |\n".formatted(row.name, row.tokens, row.pct));
            }
        }
        sb.append("\n**Total Tokens:** %,d\n".formatted(totalTokens));

        promptContext.sendMessage(sb.toString());
    }

    private void scheduleAvailableCommandsUpdate(String sessionId) {
        var agent = this.syncAgent;
        if (agent == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                var commands =
                        List.of(new AcpSchema.AvailableCommand("context", "Show current context snapshot", null));
                agent.sendSessionUpdate(
                        sessionId, new AcpSchema.AvailableCommandsUpdate("available_commands_update", commands));
            } catch (Exception e) {
                logger.warn("Failed to send available commands for session {}", sessionId, e);
            }
        });
    }

    // ---- ACP defaults persistence ----

    private record AcpDefaults(String defaultModel, String defaultReasoning) {}

    private static AcpDefaults loadAcpDefaults() {
        try {
            if (!Files.exists(ACP_SETTINGS_PATH)) {
                return new AcpDefaults("", DEFAULT_REASONING_LEVEL);
            }
            var mapper = new ObjectMapper();
            var tree = mapper.readTree(ACP_SETTINGS_PATH.toFile());
            var model = tree.has("default_model") ? tree.get("default_model").asText("") : "";
            var reasoning = tree.has("default_reasoning")
                    ? tree.get("default_reasoning").asText(DEFAULT_REASONING_LEVEL)
                    : DEFAULT_REASONING_LEVEL;
            if (!REASONING_LEVEL_IDS.contains(reasoning)) {
                logger.warn(
                        "Persisted ACP reasoning {} invalid; falling back to {}", reasoning, DEFAULT_REASONING_LEVEL);
                reasoning = DEFAULT_REASONING_LEVEL;
            }
            return new AcpDefaults(model.strip(), reasoning.strip());
        } catch (Exception e) {
            logger.warn("Failed to load ACP defaults, using built-in defaults", e);
            return new AcpDefaults("", DEFAULT_REASONING_LEVEL);
        }
    }

    private static void saveAcpDefaults(String model, String reasoning) {
        try {
            var dir = ACP_SETTINGS_PATH.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsString(Map.of("default_model", model, "default_reasoning", reasoning));
            AtomicWrites.save(ACP_SETTINGS_PATH, json);
            logger.debug("Saved ACP defaults: model={}, reasoning={}", model, reasoning);
        } catch (IOException e) {
            logger.warn("Failed to save ACP defaults", e);
        }
    }
}
