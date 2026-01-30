package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Service;
import ai.brokk.concurrent.UserActionManager;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessJobTemperatureOverrideTest {

    @TempDir
    Path tempDir;

    private NoOpContextManager cm;
    private JobStore store;
    private JobRunner runner;
    private SpyService spyService;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
        Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test", StandardCharsets.UTF_8);

        MainProject project = new MainProject(workspaceDir);
        spyService = new SpyService(project);

        Service.Provider provider = new Service.Provider() {
            @Override
            public AbstractService get() {
                return spyService;
            }

            @Override
            public void reinit(IProject p) {
                /* no-op */
            }
        };

        cm = new NoOpContextManager(project, provider);
        store = new JobStore(tempDir.resolve("jobstore"));
        runner = new JobRunner(cm, store);
    }

    @AfterEach
    void tearDown() {
        if (cm != null) {
            cm.close();
        }
    }

    @Test
    void temperatureOverride_isNotPassedForUnsupportedModel() throws Exception {
        // Configure: modelA does NOT support temperature
        spyService.setExposedModelLocations(Map.of("modelA", "locA"));
        spyService.setExposedModelInfoMap(Map.of("locA", Map.of("supported_openai_params", List.of("top_p"))));

        JobSpec spec = new JobSpec(
                "test task",
                false,
                false,
                "modelA",
                null,
                null,
                false,
                Map.of("mode", "ASK"),
                null,
                null,
                null,
                null,
                0.7,
                null,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        runner.runAsync("job-unsupported", spec).get(5, TimeUnit.SECONDS);

        assertNull(spyService.lastTemperatureSeen, "Temperature should NOT be passed to getModel if unsupported");
    }

    @Test
    void temperatureOverride_isPassedForSupportedModel() throws Exception {
        // Configure: modelA supports temperature
        spyService.setExposedModelLocations(Map.of("modelA", "locA"));
        spyService.setExposedModelInfoMap(Map.of("locA", Map.of("supported_openai_params", List.of("temperature"))));

        JobSpec spec = new JobSpec(
                "test task",
                false,
                false,
                "modelA",
                null,
                null,
                false,
                Map.of("mode", "ASK"),
                null,
                null,
                null,
                null,
                0.7,
                null,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        runner.runAsync("job-supported", spec).get(5, TimeUnit.SECONDS);

        assertEquals(
                0.7, spyService.lastTemperatureSeen, 0.001, "Temperature SHOULD be passed to getModel if supported");
    }

    @Test
    void reasoningLevelOverrides_applySeparately_forPlannerVsCodeModels() throws Exception {
        spyService.setExposedModelLocations(Map.of("plannerA", "locPlannerA", "codeA", "locCodeA"));
        spyService.setExposedModelInfoMap(Map.of(
                "locPlannerA", Map.of("supported_openai_params", List.of()),
                "locCodeA", Map.of("supported_openai_params", List.of())));

        JobSpec spec = new JobSpec(
                "test task",
                false,
                false,
                "plannerA",
                null,
                "codeA",
                false,
                Map.of("mode", "ARCHITECT"),
                null,
                null,
                "LOW",
                "HIGH",
                null,
                null,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        runner.runAsync("job-reasoning-split", spec).get(5, TimeUnit.SECONDS);

        assertNotNull(spyService.lastReasoningByModelName.get("plannerA"));
        assertNotNull(spyService.lastReasoningByModelName.get("codeA"));

        assertEquals(
                Service.ReasoningLevel.LOW,
                spyService.lastReasoningByModelName.get("plannerA"),
                "plannerModel should use spec.reasoningLevel()");
        assertEquals(
                Service.ReasoningLevel.HIGH,
                spyService.lastReasoningByModelName.get("codeA"),
                "codeModel should use spec.reasoningLevelCode()");
    }

    @Test
    void temperatureOverrides_applySeparately_forPlannerVsCodeModels() throws Exception {
        spyService.setExposedModelLocations(Map.of("plannerA", "locPlannerA", "codeA", "locCodeA"));
        spyService.setExposedModelInfoMap(Map.of(
                "locPlannerA", Map.of("supported_openai_params", List.of("temperature")),
                "locCodeA", Map.of("supported_openai_params", List.of("temperature"))));

        JobSpec spec = new JobSpec(
                "test task",
                false,
                false,
                "plannerA",
                null,
                "codeA",
                false,
                Map.of("mode", "ARCHITECT"),
                null,
                null,
                null,
                null,
                0.11,
                0.88,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        runner.runAsync("job-temp-split", spec).get(5, TimeUnit.SECONDS);

        assertEquals(0.11, spyService.lastTemperatureByModelName.get("plannerA"), 0.001);
        assertEquals(0.88, spyService.lastTemperatureByModelName.get("codeA"), 0.001);
    }

    private static class SpyService extends TestService {
        @Nullable
        Double lastTemperatureSeen;

        final Map<String, @Nullable Double> lastTemperatureByModelName = new HashMap<>();
        final Map<String, Service.ReasoningLevel> lastReasoningByModelName = new HashMap<>();

        SpyService(IProject project) {
            super(project);
        }

        void setExposedModelLocations(Map<String, String> locations) {
            this.modelLocations = locations;
        }

        void setExposedModelInfoMap(Map<String, Map<String, Object>> info) {
            this.modelInfoMap = info;
        }

        @Override
        public @Nullable StreamingChatModel getModel(
                ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
            lastReasoningByModelName.put(config.name(), config.reasoning());

            @Nullable Double temperature = null;
            if (parametersOverride != null) {
                temperature = parametersOverride.build().temperature();
            }
            this.lastTemperatureSeen = temperature;
            lastTemperatureByModelName.put(config.name(), temperature);

            return new DummyStreamingChatModel();
        }
    }

    private static class NoOpContextManager extends ContextManager {
        NoOpContextManager(IProject project, Service.Provider serviceProvider) {
            super(project, serviceProvider);
        }

        @Override
        public CompletableFuture<Void> submitLlmAction(UserActionManager.ThrowingRunnable task) {
            // Return completed future without executing agent logic to keep test fast and network-free
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * BlockingContextManager variant used by tests to control when the LLM action completes.
     * It never executes the provided runnable; instead it waits on a provided CountDownLatch and
     * then returns either a successful or exceptionally completed future depending on the
     * configured behavior.
     */
    private static class BlockingContextManager extends ContextManager {
        private final java.util.concurrent.CountDownLatch latch;
        private final boolean completeExceptionally;

        BlockingContextManager(IProject project, Service.Provider serviceProvider, java.util.concurrent.CountDownLatch latch, boolean completeExceptionally) {
            super(project, serviceProvider);
            this.latch = latch;
            this.completeExceptionally = completeExceptionally;
        }

        @Override
        public CompletableFuture<Void> submitLlmAction(UserActionManager.ThrowingRunnable task) {
            try {
                // Block until test releases latch
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CompletableFuture<Void> f = new CompletableFuture<>();
                f.completeExceptionally(e);
                return f;
            }
            if (completeExceptionally) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                f.completeExceptionally(new RuntimeException("simulated failure"));
                return f;
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    @Test
    void jobCompletion_persistsAccountingTotalsInStatusMetadata_forReviewMode() throws Exception {
        // Prepare a fresh workspace for this test
        Path workspaceDir = tempDir.resolve("workspace-complete");
        Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
        Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test", StandardCharsets.UTF_8);

        MainProject project = new MainProject(workspaceDir);
        spyService = new SpyService(project);

        Service.Provider provider = new Service.Provider() {
            @Override
            public AbstractService get() {
                return spyService;
            }

            @Override
            public void reinit(IProject p) {
                /* no-op */
            }
        };

        // Latch to pause job until we inject usage
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        // Use blocking CM that will wait for latch and then complete normally
        var blockingCm = new BlockingContextManager(project, provider, latch, false);
        store = new JobStore(tempDir.resolve("jobstore-complete"));
        runner = new JobRunner(blockingCm, store);

        // Create REVIEW JobSpec (we don't provide github tags because submitLlmAction will not execute real logic)
        JobSpec spec = new JobSpec(
                "test task",
                false,
                false,
                "plannerA",
                "scanA",
                "codeA",
                false,
                Map.of("mode", "REVIEW"),
                null,
                null,
                null,
                null,
                null,
                null,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        String jobId = "job-review-complete-1";
        CompletableFuture<Void> fut = runner.runAsync(jobId, spec);

        // Wait until headless console is installed
        long deadline = System.currentTimeMillis() + 5_000;
        while (!(blockingCm.getIo() instanceof ai.brokk.executor.io.HeadlessHttpConsole) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(blockingCm.getIo() instanceof ai.brokk.executor.io.HeadlessHttpConsole, "Headless console should be installed");

        var console = (ai.brokk.executor.io.HeadlessHttpConsole) blockingCm.getIo();

        // Inject two usage reports
        console.reportLlmUsage(new IConsoleIO.LlmUsagePayload(10, 2, 5, 3, 0.0123, "m", null));
        console.reportLlmUsage(new IConsoleIO.LlmUsagePayload(20, 0, 7, 0, 0.1, "m", null));

        // Release the job to finish
        latch.countDown();

        // Await completion
        fut.get(5, TimeUnit.SECONDS);

        JobStatus status = store.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("COMPLETED", status.state());

        var meta = status.metadata();
        assertNotNull(meta);
        assertEquals("30", meta.get("totalInputTokens"));
        assertEquals("2", meta.get("totalCachedInputTokens"));
        assertEquals("12", meta.get("totalOutputTokens"));
        assertEquals("3", meta.get("totalThinkingTokens"));
        assertEquals("42", meta.get("totalTokens"));

        assertNotNull(meta.get("totalCostUsd"));
        assertEquals(0.1123, Double.parseDouble(meta.get("totalCostUsd")), 1e-12);
    }

    @Test
    void jobFailure_persistsAccountingTotalsInStatusMetadata_forReviewMode() throws Exception {
        // Prepare a fresh workspace for this test
        Path workspaceDir = tempDir.resolve("workspace-fail");
        Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
        Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test", StandardCharsets.UTF_8);

        MainProject project = new MainProject(workspaceDir);
        spyService = new SpyService(project);

        Service.Provider provider = new Service.Provider() {
            @Override
            public AbstractService get() {
                return spyService;
            }

            @Override
            public void reinit(IProject p) {
                /* no-op */
            }
        };

        // Latch to pause job until we inject usage
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        // Use blocking CM that will wait for latch and then complete exceptionally
        var blockingCm = new BlockingContextManager(project, provider, latch, true);
        store = new JobStore(tempDir.resolve("jobstore-fail"));
        runner = new JobRunner(blockingCm, store);

        // Create REVIEW JobSpec (we don't provide github tags because submitLlmAction will not execute real logic)
        JobSpec spec = new JobSpec(
                "test task",
                false,
                false,
                "plannerA",
                "scanA",
                "codeA",
                false,
                Map.of("mode", "REVIEW"),
                null,
                null,
                null,
                null,
                null,
                null,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        String jobId = "job-review-fail-1";
        CompletableFuture<Void> fut = runner.runAsync(jobId, spec);

        // Wait until headless console is installed
        long deadline = System.currentTimeMillis() + 5_000;
        while (!(blockingCm.getIo() instanceof ai.brokk.executor.io.HeadlessHttpConsole) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(blockingCm.getIo() instanceof ai.brokk.executor.io.HeadlessHttpConsole, "Headless console should be installed");

        var console = (ai.brokk.executor.io.HeadlessHttpConsole) blockingCm.getIo();

        // Inject two usage reports
        console.reportLlmUsage(new IConsoleIO.LlmUsagePayload(10, 2, 5, 3, 0.0123, "m", null));
        console.reportLlmUsage(new IConsoleIO.LlmUsagePayload(20, 0, 7, 0, 0.1, "m", null));

        // Release the job to finish (it will fail because submitLlmAction completes exceptionally)
        latch.countDown();

        try {
            fut.join();
        } catch (CompletionException ignore) {
            // expected failure path - we will inspect status
        }

        JobStatus status = store.loadStatus(jobId);
        assertNotNull(status);
        assertEquals("FAILED", status.state());

        var meta = status.metadata();
        assertNotNull(meta);
        assertEquals("30", meta.get("totalInputTokens"));
        assertEquals("2", meta.get("totalCachedInputTokens"));
        assertEquals("12", meta.get("totalOutputTokens"));
        assertEquals("3", meta.get("totalThinkingTokens"));
        assertEquals("42", meta.get("totalTokens"));

        assertNotNull(meta.get("totalCostUsd"));
        assertEquals(0.1123, Double.parseDouble(meta.get("totalCostUsd")), 1e-12);
    }

    private static class DummyStreamingChatModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            // Trivial implementation
        }
    }
}
