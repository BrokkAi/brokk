package ai.brokk;

import static java.lang.Math.min;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.TestScriptedLanguageModel;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.BuildOutputPreprocessor;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
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
        String result = BuildOutputPreprocessor.processForLlm(buildOutput, cm);

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

    /**
     * Helper to create a StreamingResult with text content for testing parseJsonToToolRequests
     */
    private Llm.StreamingResult createStreamingResult(String text) {
        var nsr = new Llm.NullSafeResponse(text, null, List.of(), null);
        return new Llm.StreamingResult(nsr, null);
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
}
