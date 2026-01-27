package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-style test ensuring PLAN mode is planning-only in headless execution.
 *
 * <p>This test verifies:
 * <ul>
 *   <li>Submitting a PLAN JobSpec and executing via JobRunner succeeds (status persists).</li>
 *   <li>PLAN mode does NOT resolve a code model override (planning-only).</li>
 * </ul>
 *
 * <p>We intentionally make submitLlmAction a no-op to avoid running the actual planning agent (LutzAgent)
 * and LLM calls. This still exercises the headless job runner's early model-resolution path, which must
 * not require a code model in PLAN mode.
 */
class PlanModeHeadlessExecutionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void planModeDoesNotResolveCodeModel(@TempDir Path tmp) throws Exception {
        MainProject project = new MainProject(tmp);

        RecordingService svc = new RecordingService(project);

        Service.Provider provider = new Service.Provider() {
            @Override
            public AbstractService get() {
                return svc;
            }

            @Override
            public void reinit(IProject p) {
                // no-op for test
            }
        };

        ContextManager cm = new NoOpContextManager(project, provider);

        JobStore store = new JobStore(tmp.resolve(".brokk").resolve("jobs"));
        JobRunner runner = new JobRunner(cm, store);

        var tags = Map.of("mode", "PLAN");
        JobSpec spec = JobSpec.of(
                /* taskInput */ "Plan the refactor of authentication subsystem",
                /* autoCommit */ false,
                /* autoCompress */ false,
                /* plannerModel */ "planner-x",
                /* scanModel */ null,
                /* codeModel */ "explicit-code-model",
                /* preScan */ false,
                /* tags */ tags,
                /* reasoningLevelCode */ (String) null);

        String jobId = UUID.randomUUID().toString();

        // The NoOpContextManager prevents the planning agent (and scan-model resolution inside PLAN execution)
        // from running. This test focuses on the planning-only guarantee: PLAN must not resolve code models.
        runner.runAsync(jobId, spec).get(10, TimeUnit.SECONDS);

        assertTrue(
                svc.requestedModelNames.contains("planner-x"),
                "Planner model should have been requested for PLAN mode");
        assertFalse(
                svc.requestedModelNames.contains("explicit-code-model"),
                "Code model override must NOT be resolved for PLAN mode");

        var status = store.loadStatus(jobId);
        assertEquals("COMPLETED", status.state(), "Job should be marked COMPLETED");
    }

    private static final class NoOpContextManager extends ContextManager {
        NoOpContextManager(IProject project, Service.Provider serviceProvider) {
            super(project, serviceProvider);
        }

        @Override
        public CompletableFuture<Void> submitLlmAction(ai.brokk.concurrent.UserActionManager.ThrowingRunnable task) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class RecordingService extends AbstractService {
        final List<String> requestedModelNames = new ArrayList<>();

        RecordingService(IProject project) {
            super(project);
        }

        @Override
        public float getUserBalance() {
            return 0.0f;
        }

        @Override
        public void sendFeedback(
                String category, String feedbackText, boolean includeDebugLog, java.io.File screenshotFile) {
            // no-op for test
        }

        @Override
        public @Nullable StreamingChatModel getModel(
                Service.ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
            requestedModelNames.add(config.name());
            return new AbstractService.UnavailableStreamingModel();
        }

        @Override
        public StreamingChatModel getScanModel() {
            return new AbstractService.UnavailableStreamingModel();
        }

        @Override
        public String nameOf(StreamingChatModel model) {
            return model.getClass().getSimpleName();
        }

        @Override
        public boolean isLazy(StreamingChatModel model) {
            return false;
        }

        @Override
        public boolean isReasoning(StreamingChatModel model) {
            return false;
        }

        @Override
        public boolean requiresEmulatedTools(StreamingChatModel model) {
            return false;
        }

        @Override
        public boolean supportsJsonSchema(StreamingChatModel model) {
            return false;
        }

        @Override
        public JsonNode reportClientException(
                String stacktrace, String clientVersion, Map<String, String> optionalFields) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }
}
