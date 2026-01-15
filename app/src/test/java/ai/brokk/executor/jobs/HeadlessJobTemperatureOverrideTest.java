package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
                "test task", false, false, "modelA", null, null, false, Map.of("mode", "ASK"), null, null, null, 0.7);

        runner.runAsync("job-unsupported", spec).get(5, TimeUnit.SECONDS);

        assertNull(spyService.lastTemperatureSeen, "Temperature should NOT be passed to getModel if unsupported");
    }

    @Test
    void temperatureOverride_isPassedForSupportedModel() throws Exception {
        // Configure: modelA supports temperature
        spyService.setExposedModelLocations(Map.of("modelA", "locA"));
        spyService.setExposedModelInfoMap(Map.of("locA", Map.of("supported_openai_params", List.of("temperature"))));

        JobSpec spec = new JobSpec(
                "test task", false, false, "modelA", null, null, false, Map.of("mode", "ASK"), null, null, null, 0.7);

        runner.runAsync("job-supported", spec).get(5, TimeUnit.SECONDS);

        assertEquals(
                0.7, spyService.lastTemperatureSeen, 0.001, "Temperature SHOULD be passed to getModel if supported");
    }

    private static class SpyService extends TestService {
        @Nullable
        Double lastTemperatureSeen;

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
            if (parametersOverride != null) {
                this.lastTemperatureSeen = parametersOverride.build().temperature();
            } else {
                this.lastTemperatureSeen = null;
            }
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

    private static class DummyStreamingChatModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            // Trivial implementation
        }
    }
}
