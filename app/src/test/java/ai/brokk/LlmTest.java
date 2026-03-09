package ai.brokk;

import static java.lang.Math.min;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.Llm.ResponseMetadata;
import ai.brokk.Llm.StreamingResult;
import ai.brokk.agents.TestScriptedLanguageModel;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.BuildOutputProcessor;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.exception.OverthinkingException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LlmTest {
    private static Llm llm;
    private static IContextManager contextManager; // Add field for ContextManager

    @TempDir
    static Path tempDir; // JUnit Jupiter provides a temporary directory

    @BeforeAll
    static void setUp() {
        // Create ContextManager, which initializes Models internally
        var consoleIO = new NoOpConsoleIO();
        contextManager = new TestContextManager(tempDir, consoleIO);
    }

    private StreamingChatModel getModel(String modelName) {
        return contextManager.getService().getModel(new AbstractService.ModelConfig(modelName));
    }

    // Simple tool for testing
    static class WeatherTool {
        @Tool(value = "Get the current weather")
        public String getWeather(@P("Location at which to perform the weather lookup") String location) {
            return "The weather in " + location + " is sunny.";
        }
    }

    // Tool with List<Record> parameter to test getInstructions handles JsonObjectSchema array items
    public record ItemEntry(String id, String description) {}

    static class RecordListTool {
        @Tool(value = "Process a list of items")
        public String processItems(@P("List of items to process") List<ItemEntry> items) {
            return "Processed " + items.size() + " items.";
        }
    }

    // uncomment when you need it, this makes live API calls
    //    @Test
    void testModels() {
        // Get Models instance from ContextManager
        var models = contextManager.getService();
        var availableModels = models.getAvailableModels();
        Assumptions.assumeFalse(
                availableModels.isEmpty(), "No models available via LiteLLM, skipping testModels test.");

        var messages = List.<ChatMessage>of(new UserMessage("hello world"));
        Map<String, Throwable> failures = new ConcurrentHashMap<>();

        availableModels.keySet().parallelStream().forEach(modelName -> {
            try {
                System.out.println("Testing model: " + modelName);
                // Get model instance via the Models object
                StreamingChatModel model = getModel(modelName);
                var coder = contextManager.getLlm(model, "testModels", TaskResult.Type.NONE);
                assertNotNull(model, "Failed to get model instance for: " + modelName);

                // Use the non-streaming sendMessage variant for simplicity in testing basic connectivity
                // Note: This uses the internal retry logic of Coder.sendMessage
                var result = coder.sendRequest(messages);

                assertNotNull(result, "Result should not be null for model: " + modelName);
                assertFalse(false, "Request should not be cancelled for model: " + modelName);
                if (result.error() != null) {
                    // Capture the error directly instead of asserting null
                    throw new AssertionError("Request resulted in an error for model: " + modelName, result.error());
                }

                var chatResponse = result.chatResponse();
                assertNotNull(chatResponse, "ChatResponse should not be null for model: " + modelName);
                assertNotNull(result.originalMessage(), "AI message should not be null for model: " + modelName);
                assertNotNull(
                        result.originalMessage().text(), "AI message text should not be null for model: " + modelName);
                assertFalse(
                        result.originalMessage().text().isBlank(),
                        "AI message text should not be blank for model: " + modelName);

                var firstLine =
                        result.originalMessage().text().lines().findFirst().orElse("");
                System.out.println("Response from " + modelName + ": "
                        + firstLine.substring(0, min(firstLine.length(), 50)) + "...");
            } catch (Throwable t) {
                // Catch assertion errors or any other exceptions during the test for this model
                failures.put(modelName, t);
                System.err.println("Failure testing model " + modelName + ": ");
                t.printStackTrace();
            }
        });

        if (!failures.isEmpty()) {
            String failureSummary = failures.entrySet().stream()
                    .map(entry -> "Model '" + entry.getKey() + "' failed: "
                            + entry.getValue().getMessage()
                            + (entry.getValue().getCause() != null
                                    ? " (Cause: " + entry.getValue().getCause().getMessage() + ")"
                                    : ""))
                    .collect(Collectors.joining("\n"));
            fail("One or more models failed the basic connectivity test:\n" + failureSummary);
        }
    }

    // uncomment when you need it, this makes live API calls
    // you will also need to set the ContextManager to a live one:
    // contextManager = new ContextManager(new TestProject(tempDir, Languages.JAVA));
    //        @Test
    void testToolCalling() {
        var models = contextManager.getService();
        var availableModels = models.getAvailableModels();
        Assumptions.assumeFalse(
                availableModels.isEmpty(), "No models available via LiteLLM, skipping testToolCalling test.");

        var weatherTool = new WeatherTool();
        var toolSpecifications = ToolSpecifications.toolSpecificationsFrom(weatherTool);
        var tr =
                contextManager.getToolRegistry().builder().register(weatherTool).build();

        Map<String, Throwable> failures = new ConcurrentHashMap<>();

        availableModels.keySet().parallelStream().forEach(modelName -> {
            try {
                System.out.println("Testing tool calling for model: " + modelName);
                StreamingChatModel model = getModel(modelName);
                var coder = contextManager.getLlm(model, "testToolCalling", TaskResult.Type.NONE);
                assertNotNull(model, "Failed to get model instance for: " + modelName);

                var messages = new ArrayList<ChatMessage>();
                messages.add(new UserMessage("What is the weather like in London?"));
                var tc = new ToolContext(toolSpecifications, ToolChoice.REQUIRED, tr);
                var result = coder.sendRequest(messages, tc);

                assertNotNull(result, "Result should not be null for model: " + modelName);
                assertFalse(false, "Request should not be cancelled for model: " + modelName);
                if (result.error() != null) {
                    throw new AssertionError("Request resulted in an error for model: " + modelName, result.error());
                }

                var chatResponse = result.chatResponse();
                assertNotNull(chatResponse, "ChatResponse should not be null for model: " + modelName);
                assertNotNull(result.originalMessage(), "AI message should not be null for model: " + modelName);

                // ASSERTION 1: Check if a tool execution was requested
                assertTrue(
                        !result.chatResponse().toolRequests().isEmpty(),
                        "Model " + modelName + " did not request tool execution. Response: " + chatResponse.text());
                System.out.println("Tool call requested successfully by " + modelName);

                // check that we can send the result back
                var req = result.chatResponse().toolRequests().getFirst();
                // NB: this is a quick hack that does not actually pass arguments from the tool call
                messages.add(result.originalMessage());
                var term = new ToolExecutionResultMessage(req.id(), req.name(), new WeatherTool().getWeather("London"));
                messages.add(term);
                messages.add(new UserMessage("Given what you know about London, is this unusual?"));
                result = coder.sendRequest(messages, tc);
                assertNotNull(result, "Result should not be null for model: " + modelName);
                assertFalse(false, "Request should not be cancelled for model: " + modelName);
                if (result.error() != null) {
                    throw new AssertionError(
                            "Followup request resulted in an error for model: " + modelName, result.error());
                }
                System.out.println("Tool response processed successfully by " + modelName);
            } catch (Throwable t) {
                // Catch assertion errors or any other exceptions during the test for this model
                failures.put(modelName, t);
                // Log the error immediately for easier debugging during parallel execution
                System.err.printf(
                        "Failure testing tool calling for model %s: %s%n",
                        modelName,
                        t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                t.printStackTrace();
            }
        });

        if (!failures.isEmpty()) {
            String failureSummary = failures.entrySet().stream()
                    .map(entry -> "Model '" + entry.getKey() + "' failed tool calling: "
                            + entry.getValue().getMessage()
                            + (entry.getValue().getCause() != null
                                    ? " (Cause: " + entry.getValue().getCause().getMessage() + ")"
                                    : ""))
                    .collect(Collectors.joining("\n"));
            fail("One or more models failed the tool calling test:\n" + failureSummary);
        }
    }

    // uncomment when you need it, this makes live API calls
    // @Test
    void testBuildErrorExtractionIncludesAllErrors() throws InterruptedException {
        var project = new MainProject(tempDir);
        var cm = new ContextManager(project);

        var models = cm.getService();
        var availableModels = models.getAvailableModels();
        Assumptions.assumeFalse(availableModels.isEmpty(), "No models available, skipping test.");

        // Simulated build output with causally related errors:
        // - NullAway error at line 707 (ROOT CAUSE: method returns null without @Nullable)
        // - RedundantNullCheck warning at line 291 (SYMPTOM: null check appears redundant)
        // The LLM should extract BOTH, not just the first one
        String buildOutput =
                """
                /home/user/project/src/main/java/com/example/Service.java:291: warning: [RedundantNullCheck] Null check on an expression that is statically determined to be non-null according to language semantics or nullness annotations.
                        if (menu != null) {
                                 ^
                    (see https://errorprone.info/bugpattern/RedundantNullCheck)
                /home/user/project/src/main/java/com/example/Service.java:707: error: [NullAway] returning @Nullable expression from method with @NonNull return type
                            return null;
                            ^
                    (see http://t.uber.com/nullaway )
                /home/user/project/src/main/java/com/example/Util.java:129: warning: [MissingOverride] ancestorAdded implements method in AncestorListener; expected @Override
                        public void ancestorAdded(AncestorEvent event) {
                                    ^
                    (see https://errorprone.info/bugpattern/MissingOverride)
                  Did you mean '@Override public void ancestorAdded(AncestorEvent event) {'?
                1 error
                2 warnings

                > Task :app:compileJavaErrorProne FAILED
                """;

        // Call the preprocessor which uses the LLM to extract errors
        String result = BuildOutputProcessor.processForLlm(buildOutput, cm);

        System.out.println("=== Extracted errors ===");
        System.out.println(result);
        System.out.println("========================");

        // The key assertion: the NullAway error (root cause) must be included
        // Previously, the prompt said "fix the error" (singular) which caused LLMs to skip it
        assertTrue(
                result.contains("NullAway") || result.contains("returning @Nullable"),
                "Should include the NullAway error - this is the ROOT CAUSE that needs @Nullable annotation");

        // The warning should also be present since it's related
        assertTrue(
                result.contains("RedundantNullCheck") || result.contains("Null check"),
                "Should include the RedundantNullCheck warning - related to the NullAway error");
    }

    @Test
    void testNativeToolContractRetryOnInvalidToolCallThenSuccess() throws InterruptedException {
        var weatherTool = new WeatherTool();
        var toolSpecifications = ToolSpecifications.toolSpecificationsFrom(weatherTool);
        var tr =
                contextManager.getToolRegistry().builder().register(weatherTool).build();
        var tc = new ToolContext(toolSpecifications, ToolChoice.REQUIRED, tr);

        var invalidReq = ToolExecutionRequest.builder()
                .id("bad1")
                .name("not_a_tool")
                .arguments("{\"x\":1}")
                .build();
        var validReq = ToolExecutionRequest.builder()
                .id("ok1")
                .name("getWeather")
                .arguments("{\"location\":\"London\"}")
                .build();

        var model = new TestScriptedLanguageModel(List.of(
                new AiMessage("first", null, List.of(invalidReq)), new AiMessage("second", null, List.of(validReq))));

        var llm = contextManager.getLlm(
                model, "testNativeToolContractRetryOnInvalidToolCallThenSuccess", TaskResult.Type.NONE);

        var result = llm.sendRequest(List.of(new UserMessage("What's the weather?")), tc);

        assertNull(result.error(), "Expected eventual success after tool contract retry");
        assertEquals(1, result.toolRequests().size());
        assertEquals("getWeather", result.toolRequests().getFirst().name());

        var seen = model.seenRequests();
        assertEquals(2, seen.size(), "Expected exactly one contract-retry round trip");

        var secondReqMessages = seen.get(1).messages();
        assertEquals(1, secondReqMessages.size(), "Expected modified user message only (no message bloat)");

        assertInstanceOf(UserMessage.class, secondReqMessages.getFirst());
        var retryText = Messages.getText(secondReqMessages.getLast());
        assertTrue(retryText.contains("retrying this turn"), "Retry instructions should mention retrying");
        assertTrue(retryText.contains("not_a_tool"), "Retry instructions should include the invalid tool name");
    }

    @Test
    void testToolChoiceRequiredFailsAfterContractRetriesWhenNoToolCalls() throws InterruptedException {
        var weatherTool = new WeatherTool();
        var toolSpecifications = ToolSpecifications.toolSpecificationsFrom(weatherTool);
        var tr =
                contextManager.getToolRegistry().builder().register(weatherTool).build();
        var tc = new ToolContext(toolSpecifications, ToolChoice.REQUIRED, tr);

        var model = new TestScriptedLanguageModel("no tools", "still no tools", "again no tools", "no tools final");
        var llm = contextManager.getLlm(
                model, "testToolChoiceRequiredFailsAfterContractRetriesWhenNoToolCalls", TaskResult.Type.NONE);

        var result = llm.sendRequest(List.of(new UserMessage("Use a tool")), tc);

        assertNotNull(result.error(), "Expected failure after exceeding tool contract retries");
        assertInstanceOf(Llm.MissingToolCallsException.class, result.error());
        assertEquals(4, model.seenRequests().size(), "Expected 4 attempts (initial + 3 contract retries)");
    }

    @Test
    void sumBothNullReturnsNull() {
        assertNull(ResponseMetadata.sum(null, null), "Sum of two null metadata should be null");
    }

    @Test
    void sumReturnsSecondWhenFirstNull() {
        var metaB = new ResponseMetadata(5, 2, 3, 7, 150L, "mB", "DONE", "cB", "tierB", "noerror");
        var res = ResponseMetadata.sum(null, metaB);
        assertNotNull(res);
        assertEquals(metaB.inputTokens(), res.inputTokens());
        assertEquals(metaB.cachedInputTokens(), res.cachedInputTokens());
        assertEquals(metaB.thinkingTokens(), res.thinkingTokens());
        assertEquals(metaB.outputTokens(), res.outputTokens());
        assertEquals(metaB.elapsedMs(), res.elapsedMs());
        assertEquals(metaB.modelName(), res.modelName());
        assertEquals(metaB.finishReason(), res.finishReason());
        assertEquals(metaB.created(), res.created());
        assertEquals(metaB.serviceTier(), res.serviceTier());
        assertEquals(metaB.error(), res.error());
    }

    @Test
    void sumReturnsFirstWhenSecondNull() {
        var metaA = new ResponseMetadata(8, 1, 0, 2, 50L, "mA", "STOP", "cA", "tierA", "errA");
        var res = ResponseMetadata.sum(metaA, null);
        assertNotNull(res);
        assertEquals(metaA.inputTokens(), res.inputTokens());
        assertEquals(metaA.cachedInputTokens(), res.cachedInputTokens());
        assertEquals(metaA.thinkingTokens(), res.thinkingTokens());
        assertEquals(metaA.outputTokens(), res.outputTokens());
        assertEquals(metaA.elapsedMs(), res.elapsedMs());
        assertEquals(metaA.modelName(), res.modelName());
        assertEquals(metaA.finishReason(), res.finishReason());
        assertEquals(metaA.created(), res.created());
        assertEquals(metaA.serviceTier(), res.serviceTier());
        assertEquals(metaA.error(), res.error());
    }

    @Test
    void sumCombinesNumericAndPrefersSecondCategoricals() {
        var metaA = new ResponseMetadata(10, 1, 2, 3, 100L, "modelA", "STOP", "t1", "tierA", "errA");
        var metaB = new ResponseMetadata(5, 2, 4, 6, 200L, "modelB", null, "t2", null, null);

        var combined = ResponseMetadata.sum(metaA, metaB);
        assertNotNull(combined);
        assertEquals(15, combined.inputTokens());
        assertEquals(3, combined.cachedInputTokens());
        assertEquals(6, combined.thinkingTokens());
        assertEquals(9, combined.outputTokens());
        assertEquals(300L, combined.elapsedMs());
        assertEquals("modelB", combined.modelName(), "modelName should come from B");
        assertEquals("STOP", combined.finishReason(), "finishReason should fall back to A when B is null");
        assertEquals("t2", combined.created(), "created should come from B");
        assertEquals("tierA", combined.serviceTier(), "serviceTier should fall back to A when B is null");
        assertEquals("errA", combined.error(), "error should fall back to A when B is null");
    }

    @Test
    void sumAllowsAllCategoricalsNull() {
        var metaA = new ResponseMetadata(3, 0, 1, 1, 10L, null, null, null, null, null);
        var metaB = new ResponseMetadata(4, 0, 2, 2, 20L, null, null, null, null, null);

        var combined = ResponseMetadata.sum(metaA, metaB);
        assertNotNull(combined);
        assertEquals(7, combined.inputTokens());
        assertEquals(0, combined.cachedInputTokens());
        assertEquals(3, combined.thinkingTokens());
        assertEquals(3, combined.outputTokens());
        assertEquals(30L, combined.elapsedMs());
        assertNull(combined.modelName());
        assertNull(combined.finishReason());
        assertNull(combined.created());
        assertNull(combined.serviceTier());
        assertNull(combined.error());
    }

    @Test
    void sumClampsIntTokenOverflowToMaxValue() {
        // Choose values that would overflow when added normally
        int largeNearMax = Integer.MAX_VALUE - 10;
        int smallPositive = 20;

        // Make A have non-null categoricals, B have some overriding and some nulls to test precedence
        var metaA = new ResponseMetadata(
                largeNearMax, // inputTokens will overflow when added with smallPositive
                largeNearMax, // cachedInputTokens
                largeNearMax, // thinkingTokens
                largeNearMax, // outputTokens
                100L,
                "modelA",
                "A_REASON",
                "createdA",
                "tierA",
                "errorA");

        var metaB = new ResponseMetadata(
                smallPositive,
                smallPositive,
                smallPositive,
                smallPositive,
                200L,
                "modelB", // should override modelA
                null, // finishReason null -> should fall back to A
                "createdB", // should override createdA
                null, // serviceTier null -> should fall back to A
                null // error null -> should fall back to A
                );

        var combined = ResponseMetadata.sum(metaA, metaB);
        assertNotNull(combined);
        // inputTokens overflow should be clamped to Integer.MAX_VALUE
        assertEquals(Integer.MAX_VALUE, combined.inputTokens(), "inputTokens should be clamped to Integer.MAX_VALUE");
        assertTrue(combined.inputTokens() >= 0, "Clamped inputTokens must be non-negative");

        // Other int fields we also set to overflow; they should be clamped too
        assertEquals(Integer.MAX_VALUE, combined.cachedInputTokens(), "cachedInputTokens should be clamped");
        assertEquals(Integer.MAX_VALUE, combined.thinkingTokens(), "thinkingTokens should be clamped");
        assertEquals(Integer.MAX_VALUE, combined.outputTokens(), "outputTokens should be clamped");

        // elapsedMs did not overflow here
        assertEquals(300L, combined.elapsedMs());

        // Categorical precedence: values present in B win, otherwise A wins
        assertEquals("modelB", combined.modelName(), "modelName should come from B");
        assertEquals("A_REASON", combined.finishReason(), "finishReason should fall back to A when B is null");
        assertEquals("createdB", combined.created(), "created should come from B");
        assertEquals("tierA", combined.serviceTier(), "serviceTier should fall back to A when B is null");
        assertEquals("errorA", combined.error(), "error should fall back to A when B is null");
    }

    @Test
    void testOverthinkingPreservesMetadata() {
        // Mock token usage with reasoning and cached tokens
        var usage = OpenAiTokenUsage.builder()
                .inputTokenCount(100)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(50)
                        .build())
                .outputTokenCount(200)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder()
                        .reasoningTokens(150)
                        .build())
                .totalTokenCount(300)
                .build();

        // Construct a ChatResponse that looks like overthinking (LENGTH reason, no text/tools)
        var chatResponse = ChatResponse.builder()
                .aiMessage(new AiMessage(""))
                .finishReason(FinishReason.LENGTH)
                .tokenUsage(usage)
                .modelName("test-reasoning-model")
                .build();

        // Exercise the conversion logic
        var result = StreamingResult.fromResponse(chatResponse, null, 1234L);

        // Assert overthinking classification
        assertNotNull(result.error());
        assertInstanceOf(OverthinkingException.class, result.error());
        assertTrue(result.isPartial());

        // Assert metadata preservation for telemetry/costing
        var meta = result.metadata();
        assertNotNull(meta);
        assertEquals(100, meta.inputTokens());
        assertEquals(50, meta.cachedInputTokens());
        assertEquals(150, meta.thinkingTokens());
        assertEquals(200, meta.outputTokens());
        assertEquals(1234L, meta.elapsedMs());
        assertEquals("test-reasoning-model", meta.modelName());
        assertEquals("LENGTH", meta.finishReason());
    }

    @Test
    void testStandardResponseMetadata() {
        var usage = OpenAiTokenUsage.builder()
                .inputTokenCount(10)
                .outputTokenCount(20)
                .totalTokenCount(30)
                .build();

        var chatResponse = ChatResponse.builder()
                .aiMessage(new AiMessage("Hello"))
                .finishReason(FinishReason.STOP)
                .tokenUsage(usage)
                .modelName("standard-model")
                .build();

        var result = StreamingResult.fromResponse(chatResponse, null, 500L);

        assertNull(result.error());
        var meta = result.metadata();
        assertNotNull(meta);
        assertEquals(10, meta.inputTokens());
        assertEquals(20, meta.outputTokens());
        assertEquals("STOP", meta.finishReason());
        assertEquals("standard-model", meta.modelName());
    }

    @Test
    void sumClampsElapsedMsOverflowToLongMaxValue() {
        long almostMax = Long.MAX_VALUE - 5;
        long small = 10L;

        var metaA = new ResponseMetadata(1, 0, 0, 0, almostMax, "mA", null, null, null, null);
        var metaB = new ResponseMetadata(2, 0, 0, 0, small, "mB", "DONE", null, null, null);

        var combined = ResponseMetadata.sum(metaA, metaB);
        assertNotNull(combined);
        // elapsedMs overflow should be clamped to Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, combined.elapsedMs(), "elapsedMs should be clamped to Long.MAX_VALUE");

        // Numeric fields that didn't overflow should be summed normally
        assertEquals(3, combined.inputTokens());
        // Categorical precedence: modelName from B overrides A
        assertEquals("mB", combined.modelName());
        assertEquals("DONE", combined.finishReason());
    }
}
