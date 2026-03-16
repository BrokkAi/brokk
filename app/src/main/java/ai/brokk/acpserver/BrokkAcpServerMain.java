package ai.brokk.acpserver;

import static java.util.Objects.requireNonNull;

import ai.brokk.AbstractService;
import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Service;
import ai.brokk.SessionManager;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.cli.HeadlessConsole;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.executor.jobs.LutzExecutor;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.util.Messages;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.annotation.SetSessionModel;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "brokk-acp",
        mixinStandardHelpOptions = true,
        description = "Run Brokk as an ACP agent server over stdio.")
public final class BrokkAcpServerMain implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(BrokkAcpServerMain.class);

    private static final List<AcpSchema.SessionMode> AVAILABLE_MODES = List.of(
            new AcpSchema.SessionMode("LUTZ", "Lutz", "Plan and execute tasks."),
            new AcpSchema.SessionMode("CODE", "Code", "Direct code-editing execution."),
            new AcpSchema.SessionMode("ASK", "Ask", "Answer questions without editing."),
            new AcpSchema.SessionMode("PLAN", "Plan", "Produce a task plan only."));

    private static final List<AcpSchema.AvailableCommand> AVAILABLE_COMMANDS = List.of(new AcpSchema.AvailableCommand(
            "context",
            "Show current context snapshot",
            new AcpSchema.AvailableCommandInput("Optional filter text")));

    @CommandLine.Option(names = "--workspace-dir", description = "Workspace root.", required = true)
    private Path workspaceDir;

    @CommandLine.Option(names = "--vendor", description = "Other-models vendor preference.")
    private @Nullable String vendor;

    @CommandLine.Option(names = "--brokk-api-key", description = "Brokk API key override.")
    private @Nullable String brokkApiKey;

    @CommandLine.Option(names = "--favorite-models", description = "Favorite models override JSON.")
    private @Nullable String favoriteModelsJson;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        int exitCode = new CommandLine(new BrokkAcpServerMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    @Blocking
    public Integer call() throws Exception {
        var projectRoot = workspaceDir.toAbsolutePath().normalize();
        applyFavoriteModelsOverride(favoriteModelsJson);

        try (var project = new MainProject(projectRoot); var cm = new ContextManager(project)) {
            applyVendorOverride(project, vendor);
            if (brokkApiKey != null && !brokkApiKey.isBlank()) {
                MainProject.setHeadlessBrokkApiKeyOverride(brokkApiKey);
            }

            cm.createHeadless(true, new HeadlessConsole());
            logger.info("Starting Brokk ACP server {} for {}", BuildInfo.version, projectRoot);
            AcpAgentSupport.create(new BrokkAgentHandlers(cm))
                    .transport(new StdioAcpAgentTransport())
                    .build()
                    .run();
            return 0;
        } finally {
            MainProject.setHeadlessBrokkApiKeyOverride(null);
            MainProject.setHeadlessFavoriteModelsOverride(null);
        }
    }

    private static void applyFavoriteModelsOverride(@Nullable String favoriteModelsJson) throws IOException {
        if (favoriteModelsJson == null || favoriteModelsJson.isBlank()) {
            return;
        }
        var objectMapper = AbstractProject.objectMapper;
        var type = objectMapper.getTypeFactory().constructCollectionType(List.class, Service.FavoriteModel.class);
        List<Service.FavoriteModel> models = objectMapper.readValue(favoriteModelsJson, type);
        MainProject.setHeadlessFavoriteModelsOverride(models);
    }

    private static void applyVendorOverride(MainProject project, @Nullable String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return;
        }
        String requestedVendor = vendor.trim();
        String canonicalVendor = ModelProperties.getAvailableVendors().stream()
                .filter(v -> v.equalsIgnoreCase(requestedVendor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid vendor: " + requestedVendor));
        if (ModelProperties.DEFAULT_VENDOR.equals(canonicalVendor)) {
            for (ModelType type : ModelType.values()) {
                if (type != ModelType.CODE && type != ModelType.ARCHITECT) {
                    project.removeModelConfig(type);
                }
            }
            MainProject.setOtherModelsVendorPreference("");
            return;
        }
        var vendorModels = requireNonNull(ModelProperties.getVendorModels(canonicalVendor));
        vendorModels.forEach(project::setModelConfig);
        MainProject.setOtherModelsVendorPreference(canonicalVendor);
    }

    @AcpAgent(name = "brokk")
    public static final class BrokkAgentHandlers {
        private final ContextManager cm;
        private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

        BrokkAgentHandlers(ContextManager cm) {
            this.cm = cm;
        }

        @Initialize
        public AcpSchema.InitializeResponse initialize() {
            return AcpSchema.InitializeResponse.ok(new AcpSchema.AgentCapabilities(
                    true,
                    new AcpSchema.McpCapabilities(),
                    new AcpSchema.PromptCapabilities(false, true, false)));
        }

        @NewSession
        public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) throws Exception {
            cm.createSessionAsync("ACP Session").get();
            String sessionId = cm.getCurrentSessionId().toString();
            var state = SessionState.fromContextManager(cm, sessionId);
            sessions.put(sessionId, state);
            return new AcpSchema.NewSessionResponse(sessionId, modeState(state), modelState(state));
        }

        @LoadSession
        public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest request) throws Exception {
            UUID sessionUuid = UUID.fromString(request.sessionId());
            switchToSession(sessionUuid);
            var state = SessionState.fromContextManager(cm, request.sessionId());
            sessions.put(request.sessionId(), state);
            return new AcpSchema.LoadSessionResponse(modeState(state), modelState(state));
        }

        @SetSessionMode
        public AcpSchema.SetSessionModeResponse setSessionMode(AcpSchema.SetSessionModeRequest request) throws Exception {
            var state = requireSession(request.sessionId());
            String normalizedMode = normalizeMode(request.modeId());
            sessions.put(request.sessionId(), state.withMode(normalizedMode));
            return new AcpSchema.SetSessionModeResponse();
        }

        @SetSessionModel
        public AcpSchema.SetSessionModelResponse setSessionModel(AcpSchema.SetSessionModelRequest request)
                throws Exception {
            var state = requireSession(request.sessionId());
            var selection = parseModelSelection(request.modelId());
            String modelName = selection.modelName();
            var availableModels = cm.getService().getAvailableModels();
            if (!availableModels.containsKey(modelName)) {
                throw new IllegalArgumentException("Unknown model: " + request.modelId());
            }
            var reasoning = sanitizeReasoning(modelName, selection.reasoningLevel());
            sessions.put(request.sessionId(), state.withPlannerModel(modelName).withReasoningLevel(reasoning));
            return new AcpSchema.SetSessionModelResponse();
        }

        @Prompt
        public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext promptContext)
                throws Exception {
            var state = requireSession(request.sessionId());
            switchToSession(state.sessionUuid());
            attachPromptResources(request.prompt(), state.sessionUuid());

            var prompt = request.text().strip();
            if (prompt.isBlank()) {
                return AcpSchema.PromptResponse.endTurn();
            }

            promptContext.sendUpdate(
                    request.sessionId(),
                    new AcpSchema.CurrentModeUpdate("current_mode_update", state.mode()));
            promptContext.sendUpdate(
                    request.sessionId(),
                    new AcpSchema.AvailableCommandsUpdate("available_commands_update", AVAILABLE_COMMANDS));

            if ("/context".equals(prompt) || prompt.startsWith("/context ")) {
                promptContext.sendMessage(renderContextSnapshot(cm.liveContext()));
                return AcpSchema.PromptResponse.endTurn();
            }

            IConsoleIO originalIo = cm.getIo();
            var console = new AcpConsoleIO(
                    (text, reasoning) -> {
                        if (reasoning) {
                            promptContext.sendThought(text);
                        } else {
                            promptContext.sendMessage(text);
                        }
                    },
                    promptContext::sendMessage,
                    promptContext::sendMessage);
            cm.setIo(console);
            try {
                cm.submitLlmAction(() -> executePrompt(state, prompt)).join();
                return AcpSchema.PromptResponse.endTurn();
            } catch (CompletionException e) {
                if (isInterrupted(e)) {
                    return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
                }
                throw e;
            } finally {
                cm.setIo(originalIo);
            }
        }

        @Cancel
        public void cancel(AcpSchema.CancelNotification notification) throws Exception {
            requireSession(notification.sessionId());
            cm.interruptLlmAction();
        }

        private SessionState requireSession(String sessionId) throws Exception {
            var existing = sessions.get(sessionId);
            if (existing != null) {
                return existing;
            }
            UUID sessionUuid = UUID.fromString(sessionId);
            var known = cm.getProject().getSessionManager().listSessions().stream()
                    .map(SessionManager.SessionInfo::id)
                    .anyMatch(sessionUuid::equals);
            if (!known) {
                throw new IllegalArgumentException("Unknown session: " + sessionId);
            }
            switchToSession(sessionUuid);
            var state = SessionState.fromContextManager(cm, sessionId);
            sessions.put(sessionId, state);
            return state;
        }

        private void switchToSession(UUID sessionId) throws Exception {
            if (!Objects.equals(cm.getCurrentSessionId(), sessionId)) {
                cm.switchSessionAsync(sessionId).get();
            }
        }

        @Blocking
        private void executePrompt(SessionState state, String prompt) throws Exception {
            StreamingChatModel plannerModel = resolveModel(state.plannerModel(), state.reasoningLevel());
            StreamingChatModel codeModel = resolveModel(state.codeModel(), AbstractService.ReasoningLevel.DISABLE);
            switch (state.mode()) {
                case "CODE" -> {
                    try (var scope = cm.beginTaskUngrouped(prompt)) {
                        var result = new CodeAgent(cm, codeModel).execute(prompt, Set.of());
                        scope.append(result);
                    }
                }
                case "ASK" -> {
                    try (var scope = cm.beginTaskUngrouped(prompt)) {
                        var result = new SearchAgent(
                                        cm.liveContext(),
                                        prompt,
                                        plannerModel,
                                        SearchPrompts.Objective.ANSWER_ONLY,
                                        cm.getIo())
                                .execute();
                        scope.append(result);
                    }
                }
                case "PLAN" -> {
                    try (var scope = cm.beginTaskUngrouped(prompt)) {
                        var result = new LutzAgent(
                                        cm.liveContext(),
                                        prompt,
                                        plannerModel,
                                        SearchPrompts.Objective.TASKS_ONLY,
                                        scope)
                                .execute();
                        scope.append(result);
                    }
                }
                default -> {
                    try (var scope = cm.beginTaskUngrouped(prompt)) {
                        new LutzExecutor(cm, Thread.currentThread()::isInterrupted, cm.getIo())
                                .execute(prompt, plannerModel, codeModel, scope);
                    }
                }
            }
        }

        private StreamingChatModel resolveModel(String modelName, AbstractService.ReasoningLevel reasoningLevel) {
            var model = cm.getService().getModel(new AbstractService.ModelConfig(modelName, reasoningLevel));
            if (model == null) {
                throw new IllegalArgumentException("Model unavailable: " + modelName);
            }
            return model;
        }

        private void attachPromptResources(Collection<AcpSchema.ContentBlock> blocks, UUID sessionUuid) throws Exception {
            if (blocks.isEmpty()) {
                return;
            }
            switchToSession(sessionUuid);

            var root = cm.getProject().getRoot();
            var files = new ArrayList<ai.brokk.analyzer.ProjectFile>();
            var pastedTexts = new ArrayList<String>();

            for (var block : blocks) {
                if (block instanceof AcpSchema.ResourceLink link) {
                    resolveWorkspaceFile(root, link.uri()).ifPresent(path -> files.add(cm.toFile(path)));
                } else if (block instanceof AcpSchema.Resource resource) {
                    var embedded = resource.resource();
                    if (embedded instanceof AcpSchema.TextResourceContents textResource) {
                        resolveWorkspaceFile(root, textResource.uri()).ifPresentOrElse(
                                path -> files.add(cm.toFile(path)),
                                () -> {
                                    if (textResource.text() != null && !textResource.text().isBlank()) {
                                        pastedTexts.add(textResource.text());
                                    }
                                });
                    }
                }
            }

            if (!files.isEmpty()) {
                cm.addFiles(files);
            }
            pastedTexts.forEach(cm::addPastedTextFragment);
        }

        private Optional<String> resolveWorkspaceFile(Path root, @Nullable String uriText) {
            if (uriText == null || uriText.isBlank()) {
                return Optional.empty();
            }
            try {
                Path resolved;
                if (uriText.startsWith("file:")) {
                    resolved = Path.of(URI.create(uriText)).normalize();
                } else {
                    resolved = root.resolve(uriText).normalize();
                }
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                    return Optional.empty();
                }
                return Optional.of(root.relativize(resolved).toString());
            } catch (Exception e) {
                logger.debug("Ignoring unsupported ACP resource {}", uriText, e);
                return Optional.empty();
            }
        }

        private AcpSchema.SessionModeState modeState(SessionState state) {
            return new AcpSchema.SessionModeState(state.mode(), AVAILABLE_MODES);
        }

        private AcpSchema.SessionModelState modelState(SessionState state) {
            return new AcpSchema.SessionModelState(currentModelId(state), availableModels());
        }

        private List<AcpSchema.ModelInfo> availableModels() {
            var service = cm.getService();
            return service.getAvailableModels().entrySet().stream()
                    .flatMap(entry -> modelInfosFor(service, entry.getKey(), entry.getValue()).stream())
                    .toList();
        }

        private List<AcpSchema.ModelInfo> modelInfosFor(AbstractService service, String modelName, String location) {
            var infos = new ArrayList<AcpSchema.ModelInfo>();
            infos.add(new AcpSchema.ModelInfo(modelName, modelName, location));
            if (service.supportsReasoningEffort(modelName)) {
                for (var level : List.of("low", "medium", "high")) {
                    infos.add(new AcpSchema.ModelInfo(
                            modelId(modelName, level), modelName + " (" + level + ")", location));
                }
            }
            if (service.supportsReasoningDisable(modelName)) {
                infos.add(new AcpSchema.ModelInfo(
                        modelId(modelName, "disable"), modelName + " (disable)", location));
            }
            return infos;
        }

        private String currentModelId(SessionState state) {
            return modelId(state.plannerModel(), state.reasoningLevel().name().toLowerCase(Locale.ROOT));
        }

        private String modelId(String modelName, String reasoningLevel) {
            return "default".equals(reasoningLevel) ? modelName : modelName + "/" + reasoningLevel;
        }

        private ModelSelection parseModelSelection(String rawModelId) {
            String modelId = rawModelId == null ? "" : rawModelId.strip();
            if (modelId.isBlank()) {
                return new ModelSelection("", AbstractService.ReasoningLevel.DEFAULT);
            }
            int slash = modelId.lastIndexOf('/');
            if (slash <= 0 || slash == modelId.length() - 1) {
                return new ModelSelection(modelId, AbstractService.ReasoningLevel.DEFAULT);
            }
            String suffix = modelId.substring(slash + 1);
            var reasoning = AbstractService.ReasoningLevel.fromString(suffix, AbstractService.ReasoningLevel.DEFAULT);
            if (reasoning == AbstractService.ReasoningLevel.DEFAULT && !"default".equalsIgnoreCase(suffix)) {
                return new ModelSelection(modelId, AbstractService.ReasoningLevel.DEFAULT);
            }
            return new ModelSelection(modelId.substring(0, slash), reasoning);
        }

        private AbstractService.ReasoningLevel sanitizeReasoning(
                String modelName, AbstractService.ReasoningLevel requested) {
            var service = cm.getService();
            if (requested == AbstractService.ReasoningLevel.DISABLE && !service.supportsReasoningDisable(modelName)) {
                return AbstractService.ReasoningLevel.DEFAULT;
            }
            if (requested != AbstractService.ReasoningLevel.DEFAULT
                    && requested != AbstractService.ReasoningLevel.DISABLE
                    && !service.supportsReasoningEffort(modelName)) {
                return AbstractService.ReasoningLevel.DEFAULT;
            }
            return requested;
        }

        private String normalizeMode(@Nullable String modeId) {
            if (modeId == null) {
                return "LUTZ";
            }
            return AVAILABLE_MODES.stream()
                    .map(AcpSchema.SessionMode::id)
                    .filter(mode -> mode.equalsIgnoreCase(modeId))
                    .findFirst()
                    .orElse("LUTZ");
        }

        private static boolean isInterrupted(Throwable throwable) {
            Throwable current = throwable;
            while (current != null) {
                if (current instanceof InterruptedException) {
                    return true;
                }
                current = current.getCause();
            }
            return Thread.currentThread().isInterrupted();
        }

        private static String renderContextSnapshot(Context context) {
            var fragments = context.getAllFragmentsInDisplayOrder();
            int totalTokens = fragments.stream().mapToInt(BrokkAgentHandlers::estimateTokens).sum();

            var lines = new ArrayList<String>();
            lines.add("| Fragment | Tokens |");
            lines.add("|---|---:|");
            if (fragments.isEmpty()) {
                lines.add("| (none) | 0 |");
            } else {
                for (var fragment : fragments) {
                    lines.add("| %s | %,d |".formatted(
                            fragment.shortDescription().renderNowOr("fragment"),
                            estimateTokens(fragment)));
                }
            }
            lines.add("");
            lines.add("Total Tokens: %,d".formatted(totalTokens));
            return String.join("\n", lines);
        }

        private static int estimateTokens(ContextFragment fragment) {
            try {
                if (fragment.isText() || fragment.getType().isOutput()) {
                    String text = fragment.text().renderNowOr("");
                    if (!text.isBlank()) {
                        return Messages.getApproximateTokens(text);
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to estimate tokens for fragment {}", fragment.id(), e);
            }
            return 0;
        }
    }

    private record ModelSelection(String modelName, AbstractService.ReasoningLevel reasoningLevel) {}

    private record SessionState(
            String sessionId,
            UUID sessionUuid,
            String mode,
            String plannerModel,
            String codeModel,
            AbstractService.ReasoningLevel reasoningLevel) {
        private static SessionState fromContextManager(ContextManager cm, String sessionId) {
            var project = cm.getProject();
            var planner = project.getModelConfig(ModelType.ARCHITECT);
            var code = project.getModelConfig(ModelType.CODE);
            return new SessionState(
                    sessionId,
                    UUID.fromString(sessionId),
                    "LUTZ",
                    planner.name(),
                    code.name(),
                    planner.reasoning());
        }

        private SessionState withMode(String newMode) {
            return new SessionState(sessionId, sessionUuid, newMode, plannerModel, codeModel, reasoningLevel);
        }

        private SessionState withPlannerModel(String newPlannerModel) {
            return new SessionState(sessionId, sessionUuid, mode, newPlannerModel, codeModel, reasoningLevel);
        }

        private SessionState withReasoningLevel(AbstractService.ReasoningLevel newReasoningLevel) {
            return new SessionState(sessionId, sessionUuid, mode, plannerModel, codeModel, newReasoningLevel);
        }
    }
}
