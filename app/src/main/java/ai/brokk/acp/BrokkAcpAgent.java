package ai.brokk.acp;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private final Map<String, String> activeJobBySession = new ConcurrentHashMap<>();

    public BrokkAcpAgent(ContextManager cm, JobRunner jobRunner, JobStore jobStore) {
        this.cm = cm;
        this.jobRunner = jobRunner;
        this.jobStore = jobStore;
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
                null,
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

        var modeState = new AcpSchema.SessionModeState("LUTZ", AVAILABLE_MODES);
        var modelState = buildModelState(sessionId);

        return new AcpSchema.NewSessionResponse(sessionId, modeState, modelState);
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

        var modeState = new AcpSchema.SessionModeState(modeBySession.getOrDefault(sessionId, "LUTZ"), AVAILABLE_MODES);
        var modelState = buildModelState(sessionId);

        return new AcpSchema.LoadSessionResponse(modeState, modelState);
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

        // Build JobSpec
        var mode = modeBySession.getOrDefault(sessionId, "LUTZ");
        var model = modelBySession.getOrDefault(sessionId, "");
        var tags = new HashMap<String, String>();
        tags.put("mode", mode);

        var spec = new JobSpec(
                text, // taskInput
                true, // autoCommit
                true, // autoCompress
                model, // plannerModel
                null, // scanModel
                null, // codeModel
                false, // preScan
                tags, // tags
                null, // sourceBranch
                null, // targetBranch
                null, // reasoningLevel
                null, // reasoningLevelCode
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

        modelBySession.put(sessionId, modelId);

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

    private AcpSchema.SessionModelState buildModelState(String sessionId) {
        var service = cm.getService();
        var availableModels = service.getAvailableModels();
        var models = availableModels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new AcpSchema.ModelInfo(e.getKey(), e.getKey(), null))
                .toList();

        if (models.isEmpty()) {
            models = List.of(new AcpSchema.ModelInfo("default", "Default Model", null));
        }

        var currentModel =
                modelBySession.getOrDefault(sessionId, models.getFirst().modelId());
        modelBySession.putIfAbsent(sessionId, currentModel);

        return new AcpSchema.SessionModelState(currentModel, models);
    }
}
