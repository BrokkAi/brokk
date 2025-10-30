package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.AbstractProject;
import ai.brokk.IContextManager;
import ai.brokk.Service;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragment.FragmentType;
import ai.brokk.gui.components.ModelBenchmarkData;
import ai.brokk.gui.components.TokenUsageBar;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for InstructionsPanel.updateTokenCostIndicator().
 * Verifies that token usage bar warning levels are correctly computed based on
 * success rates and whether token counts fall within tested ranges.
 */
class InstructionsPanelIntegrationTest {

    @Test
    void extrapolated_tokenCount_exceeding131k_setsRedWarning() throws Exception {
        // Setup: 131,072 tokens (beyond tested max of 131,071)
        var harness = new TestHarness("gpt-5", Service.ReasoningLevel.DEFAULT, 131_072);

        harness.instructionsPanel.updateTokenCostIndicator();
        SwingUtilities.invokeAndWait(() -> {});

        var warningLevel = harness.getContextAreaContainerWarningLevel();
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "Token count 131,072 (beyond tested range) should produce RED warning");
    }

    @Test
    void tested_range_highSuccess93Percent_setsNoneWarning() throws Exception {
        // Setup: gpt-5 DEFAULT @20k tokens has 93% success rate
        var harness = new TestHarness("gpt-5", Service.ReasoningLevel.DEFAULT, 20_000);

        harness.instructionsPanel.updateTokenCostIndicator();
        SwingUtilities.invokeAndWait(() -> {});

        var warningLevel = harness.getContextAreaContainerWarningLevel();
        assertEquals(
                TokenUsageBar.WarningLevel.NONE,
                warningLevel,
                "93% success rate should produce NONE warning (threshold >= 50%)");
    }

    @Test
    void tested_range_mediumSuccess34Percent_setsYellowWarning() throws Exception {
        // Setup: gpt-5-mini DEFAULT @70k tokens has 34% success rate
        var harness = new TestHarness("gpt-5-mini", Service.ReasoningLevel.DEFAULT, 70_000);

        harness.instructionsPanel.updateTokenCostIndicator();
        SwingUtilities.invokeAndWait(() -> {});

        var warningLevel = harness.getContextAreaContainerWarningLevel();
        assertEquals(
                TokenUsageBar.WarningLevel.YELLOW,
                warningLevel,
                "34% success rate should produce YELLOW warning (30% <= rate < 50%)");
    }

    @Test
    void tested_range_lowSuccess17Percent_setsRedWarning() throws Exception {
        // Setup: gemini-2.5-flash DEFAULT @70k tokens has 17% success rate
        var harness =
                new TestHarness("gemini-2.5-flash", Service.ReasoningLevel.DEFAULT, 70_000);

        harness.instructionsPanel.updateTokenCostIndicator();
        SwingUtilities.invokeAndWait(() -> {});

        var warningLevel = harness.getContextAreaContainerWarningLevel();
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "17% success rate should produce RED warning (rate < 30%)");
    }

    @Test
    void tested_boundary_131071_setsWarningBasedOnSuccessRate() throws Exception {
        // Setup: gpt-5 DEFAULT @131071 tokens (exact boundary, still tested)
        // gpt-5 DEFAULT @131071 falls in 65K-131K range: 50% success rate -> YELLOW
        var harness = new TestHarness("gpt-5", Service.ReasoningLevel.DEFAULT, 131_071);

        harness.instructionsPanel.updateTokenCostIndicator();
        SwingUtilities.invokeAndWait(() -> {});

        var warningLevel = harness.getContextAreaContainerWarningLevel();
        assertEquals(
                TokenUsageBar.WarningLevel.YELLOW,
                warningLevel,
                "At boundary 131,071 tokens (tested), 50% success should produce YELLOW warning");
    }

    @Test
    void extrapolated_200kTokens_setsRedWarning() throws Exception {
        // Setup: 200,000 tokens (well beyond tested range)
        var harness = new TestHarness("gpt-5", Service.ReasoningLevel.DEFAULT, 200_000);

        harness.instructionsPanel.updateTokenCostIndicator();
        SwingUtilities.invokeAndWait(() -> {});

        var warningLevel = harness.getContextAreaContainerWarningLevel();
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "Token count 200,000 (beyond tested range) should produce RED warning");
    }

    /**
     * Test harness providing synchronized background task execution and token computation.
     * Eliminates timing issues by running background tasks synchronously on the calling thread.
     */
    private static class TestHarness {
        final InstructionsPanel instructionsPanel;
        private final InstructionsPanel.ContextAreaContainer contextAreaContainer;

        TestHarness(String modelName, Service.ReasoningLevel reasoning, int approxTokens)
                throws Exception {
            var testChrome = new TestChrome(modelName, reasoning, approxTokens);
            this.instructionsPanel = new InstructionsPanel(testChrome);
            this.contextAreaContainer = instructionsPanel.getContextAreaContainer();
        }

        TokenUsageBar.WarningLevel getContextAreaContainerWarningLevel() throws Exception {
            Field field =
                    contextAreaContainer.getClass().getDeclaredField("warningLevel");
            field.setAccessible(true);
            return (TokenUsageBar.WarningLevel) field.get(contextAreaContainer);
        }
    }

    /**
     * Test double for Chrome, providing test-controlled ContextManager and Service.
     */
    private static class TestChrome extends Chrome {
        private final TestContextManager contextManager;
        private final TestService service;

        TestChrome(String modelName, Service.ReasoningLevel reasoning, int approxTokens)
                throws Exception {
            // Call parent constructor with a dummy ContextManager
            super(new TestContextManager(modelName, reasoning, approxTokens, null));
            this.service = new TestService(modelName, reasoning);
            this.contextManager = new TestContextManager(modelName, reasoning, approxTokens, service);
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }
    }

    /**
     * Test double for ContextManager that executes background tasks synchronously.
     */
    private static class TestContextManager implements IContextManager {
        private final String modelName;
        private final Service.ReasoningLevel reasoning;
        private final int approxTokens;
        private final TestService service;
        private final TestContext selectedContext;

        TestContextManager(
                String modelName,
                Service.ReasoningLevel reasoning,
                int approxTokens,
                TestService service) {
            this.modelName = modelName;
            this.reasoning = reasoning;
            this.approxTokens = approxTokens;
            this.service = service;
            this.selectedContext = new TestContext(approxTokens);
        }

        @Override
        public Context selectedContext() {
            return selectedContext;
        }

        @Override
        public Service getService() {
            // Cast the TestService stub to Service interface for use by InstructionsPanel
            // This works because InstructionsPanel only calls the methods we've stubbed
            return new ServiceAdapter(service);
        }

        @Override
        public <T> CompletableFuture<T> submitBackgroundTask(String taskName, Callable<T> task) {
            // Execute synchronously to avoid timing flakiness in tests
            try {
                T result = task.call();
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        // Stub implementations of other IContextManager methods
        @Override
        public Context liveContext() {
            return selectedContext;
        }

        @Override
        public Context topContext() {
            return selectedContext;
        }

        @Override
        public void addContextListener(ContextListener listener) {}

        @Override
        public void removeContextListener(ContextListener listener) {}

        @Override
        public void addAnalyzerCallback(AnalyzerCallback callback) {}

        @Override
        public void removeAnalyzerCallback(AnalyzerCallback callback) {}

        @Override
        public void addFiles(Collection<?> path) {}

        @Override
        public Set<?> getFilesInContext() {
            return Collections.emptySet();
        }

        @Override
        public void appendTasksToTaskList(List<String> tasks) {}

        @Override
        public Context pushContext(java.util.function.Function<Context, Context> contextGenerator) {
            return selectedContext;
        }

        @Override
        public void requestRebuild() {}

        @Override
        public Object getRepo() {
            return null;
        }

        @Override
        public Object getProject() {
            return null;
        }

        @Override
        public Object getAnalyzer() {
            return null;
        }

        @Override
        public Object getIo() {
            return null;
        }
    }

    /**
     * Test double for Context providing test-controlled fragments.
     * Fragments have sufficient text to produce the desired token count.
     * Wraps the Context interface using composition.
     */
    private static class TestContext implements Context {
        private final int approxTokens;
        private final List<ContextFragment> fragments;
        private final IContextManager contextManager;

        TestContext(int approxTokens) {
            this(approxTokens, null);
        }

        TestContext(int approxTokens, IContextManager contextManager) {
            this.approxTokens = approxTokens;
            this.contextManager = contextManager;
            // Create fragments with text length calibrated to produce approxTokens
            // Rough estimate: ~4 characters per token
            int textLength = Math.max(approxTokens * 4, 100);
            String text = "x".repeat(textLength);
            var fragment = new TestFragment(text);
            this.fragments = List.of(fragment);
        }

        @Override
        public List<ContextFragment> getAllFragmentsInDisplayOrder() {
            return fragments;
        }

        @Override
        public String getName() {
            return "test-context";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        // Stub implementations of other Context methods
        @Override
        public void addFragment(ContextFragment fragment) {}

        @Override
        public void removeFragment(ContextFragment fragment) {}

        @Override
        public void setFragment(ContextFragment fragment) {}

        @Override
        public Stream<ContextFragment> fileFragments() {
            return fragments.stream();
        }

        @Override
        public Stream<ContextFragment.VirtualFragment> virtualFragments() {
            return Stream.empty();
        }

        @Override
        public Stream<ContextFragment> getReadOnlyFragments() {
            return fragments.stream();
        }

        @Override
        public Stream<ContextFragment> getEditableFragments() {
            return Stream.empty();
        }

        @Override
        public Stream<ContextFragment> allFragments() {
            return fragments.stream();
        }

        @Override
        public java.util.UUID id() {
            return java.util.UUID.randomUUID();
        }

        @Override
        public boolean containsFrozenFragments() {
            return false;
        }

        @Override
        public boolean containsDynamicFragments() {
            return false;
        }
    }

    /**
     * Adapter to make TestService work with Service interface.
     * Delegates to the concrete TestService stub.
     */
    private static class ServiceAdapter extends Service {
        private final TestService delegate;

        ServiceAdapter(TestService delegate) {
            // Call dummy parent constructor; we override all needed methods anyway
            super((AbstractProject) null);
            this.delegate = delegate;
        }

        @Override
        public StreamingChatModel getModel(ModelConfig config) {
            return delegate.getModel(config);
        }

        @Override
        public int getMaxInputTokens(StreamingChatModel model) {
            return delegate.getMaxInputTokens(model);
        }

        @Override
        public ModelPricing getModelPricing(String modelName) {
            return delegate.getModelPricing(modelName);
        }

        @Override
        public boolean isReasoning(ModelConfig config) {
            return delegate.isReasoning(config);
        }

        @Override
        public boolean isFreeTier(String modelName) {
            return delegate.isFreeTier(modelName);
        }

        @Override
        public String nameOf(StreamingChatModel model) {
            return delegate.nameOf(model);
        }
    }

    /**
     * Test double for ContextFragment providing text content.
     */
    private static class TestFragment implements ContextFragment {
        private final String text;

        TestFragment(String text) {
            this.text = text;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public boolean isText() {
            return true;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STRING;
        }

        @Override
        public String id() {
            return "test-fragment";
        }

        @Override
        public String shortDescription() {
            return "Test Fragment";
        }

        @Override
        public String description() {
            return "Test Fragment Description";
        }

        @Override
        public String format() {
            return text;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public Set<?> sources() {
            return Collections.emptySet();
        }

        @Override
        public Set<?> files() {
            return Collections.emptySet();
        }

        @Override
        public String syntaxStyle() {
            return null;
        }

        @Override
        public @Nullable IContextManager getContextManager() {
            return null;
        }
    }

    /**
     * Test double stub for Service.
     * We use a minimal stub object rather than implementing the concrete Service class.
     */
    private static class TestService {
        private final String modelName;
        private final Service.ReasoningLevel reasoning;

        TestService(String modelName, Service.ReasoningLevel reasoning) {
            this.modelName = modelName;
            this.reasoning = reasoning;
        }

        public StreamingChatModel getModel(Service.ModelConfig config) {
            // Return a no-op mock streaming model
            return new TestStreamingModel();
        }

        public int getMaxInputTokens(StreamingChatModel model) {
            return 200_000; // Sufficient for all test cases
        }

        public Service.ModelPricing getModelPricing(String modelName) {
            return new Service.ModelPricing(Collections.emptyList());
        }

        public boolean isReasoning(Service.ModelConfig config) {
            return false;
        }

        public boolean isFreeTier(String modelName) {
            return false;
        }

        public String nameOf(StreamingChatModel model) {
            return modelName;
        }
    }

    /**
     * Test double for StreamingChatModel.
     */
    private static class TestStreamingModel implements StreamingChatModel {
        @Override
        public void generate(String userMessage, dev.langchain4j.model.StreamingResponseHandler<dev.langchain4j.model.output.AiMessage> handler) {
            handler.onComplete(new Response<>(new dev.langchain4j.model.output.AiMessage("test response")));
        }

        @Override
        public void generate(List<?> messages, dev.langchain4j.model.StreamingResponseHandler<?> handler) {
        }

        @Override
        public void generate(List<?> messages, List<?> toolSpecifications, dev.langchain4j.model.StreamingResponseHandler<?> handler) {
        }

        @Override
        public void generate(List<?> messages, Object toolSpecification, dev.langchain4j.model.StreamingResponseHandler<?> handler) {
        }
    }
}
