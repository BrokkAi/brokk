package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.cli.HeadlessConsole;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Lightweight tests for the IContextManager.getModelOrDefault default method.
 *
 * These tests do NOT try to re-implement or assert ContextManager's full
 * model-selection logic. Instead they verify that the default helper:
 * - Delegates to getService().getModel(config, ...), and
 * - Returns that model when non-null,
 * - Falls back to a non-null model when the primary lookup returns null.
 */
class ContextManagerModelResolutionTest {

    /**
     * Simple stub service that returns a configured StreamingChatModel (or null).
     */
    private static final class StubService extends AbstractService {
        private final AtomicReference<StreamingChatModel> primary = new AtomicReference<>();
        private final AtomicReference<StreamingChatModel> fallback = new AtomicReference<>();

        StubService() {
            super(createTempMainProject());
        }

        private static MainProject createTempMainProject() {
            try {
                Path dir = Files.createTempDirectory("cm-model-res-service");
                // initialize a git repo to satisfy MainProject constructor expectations
                Git.init().setDirectory(dir.toFile()).call().close();
                return new MainProject(dir);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void setPrimary(@Nullable StreamingChatModel model) {
            primary.set(model);
        }

        void setFallback(@Nullable StreamingChatModel model) {
            fallback.set(model);
        }

        @Override
        public float getUserBalance() {
            return 0.0f;
        }

        @Override
        public void sendFeedback(String category, String feedbackText, boolean includeDebugLog, File screenshotFile) {
            // no-op
        }

        @Override
        public JsonNode reportClientException(
                String stacktrace, String clientVersion, Map<String, String> optionalFields) throws IOException {
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public StreamingChatModel getModel(
                ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
            StreamingChatModel m = primary.get();
            if (m != null) {
                return m;
            }
            return fallback.get();
        }

        @Override
        public String nameOf(StreamingChatModel model) {
            return model == null ? UNAVAILABLE : "stub-model";
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
            return true;
        }
    }

    /**
     * Minimal IContextManager stub that wires in our StubService.
     */
    private static final class StubContextManager implements IContextManager {
        private final StubService service;

        StubContextManager(StubService service) {
            this.service = service;
        }

        @Override
        public AbstractService getService() {
            return service;
        }

        @Override
        public IProject getProject() {
            return service.project;
        }

        @Override
        public IConsoleIO getIo() {
            return new HeadlessConsole();
        }
    }

    private static final class DummyModel extends AbstractService.UnavailableStreamingModel {
        // No behavior needed; identity and non-null are sufficient.
    }

    @Test
    void whenServiceAdvertisesConfiguredModel_getModelOrDefaultReturnsIt() throws Exception {
        StubService service = new StubService();
        StubContextManager cm = new StubContextManager(service);

        StreamingChatModel m1 = new DummyModel();
        service.setPrimary(m1); // primary lookup returns m1

        ModelConfig cfg = new ModelConfig("any-model", Service.ReasoningLevel.DEFAULT);

        StreamingChatModel resolved = cm.getModelOrDefault(cfg, "code");
        assertNotNull(resolved);
        assertSame(m1, resolved, "getModelOrDefault should return the service-provided primary model when available");
    }

    @Test
    void whenServiceReturnsNull_getModelOrDefaultFallsBackToNonNullModel() throws Exception {
        StubService service = new StubService();
        StubContextManager cm = new StubContextManager(service);

        // Simulate missing primary model but available fallback
        StreamingChatModel fallbackModel = new DummyModel();
        service.setPrimary(null);
        service.setFallback(fallbackModel);

        ModelConfig cfg = new ModelConfig("missing-model", Service.ReasoningLevel.DEFAULT);

        StreamingChatModel resolved = cm.getModelOrDefault(cfg, "code");
        assertNotNull(resolved, "getModelOrDefault should not return null when a fallback model is available");
    }

    @Test
    void whenBothPrimaryAndFallbackNull_getModelOrDefaultStillReturnsNonNullUnavailableModel() throws Exception {
        StubService service = new StubService();
        StubContextManager cm = new StubContextManager(service);

        service.setPrimary(null);
        service.setFallback(null);

        ModelConfig cfg = new ModelConfig("missing-model", Service.ReasoningLevel.DEFAULT);

        StreamingChatModel resolved = cm.getModelOrDefault(cfg, "code");
        assertNotNull(
                resolved,
                "getModelOrDefault should fall back to the UnavailableStreamingModel when service returns null");
    }
}
