package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.util.Messages;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.lang.Math.min;
import static org.junit.jupiter.api.Assertions.*;

public class LlmTest {

    // Dummy ConsoleIO for testing purposes
    static class NoOpConsoleIO implements IConsoleIO {
        @Override
        public void actionOutput(String msg) {
        }

        @Override
        public void toolError(String msg, String title) {
            System.out.println(title + ": " + msg);
        }
        @Override public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {}
        @Override public void systemOutput(String message) {}
    }

    private static Llm llm;
    private static IContextManager contextManager; // Add field for ContextManager

    @TempDir
    static Path tempDir; // JUnit Jupiter provides a temporary directory

    @BeforeAll
    static void setUp() {
        // Create ContextManager, which initializes Models internally
        var consoleIO = new NoOpConsoleIO();
        var project = new IProject() {
            @Override
            public Path getRoot() {
                return tempDir;
            }
        };
        var models = new Service(project);
        contextManager = new IContextManager() {
            @Override
            public IConsoleIO getIo() {
                return consoleIO;
            }

            @Override
            public IProject getProject() {
                return project;
            }

            @Override
            public Service getService() {
                return models;
            }
        };
    }

    // Simple tool for testing
    static class WeatherTool {
        @Tool("Get the current weather")
        public String getWeather(@P("Location at which to perform the weather lookup") String location) {
            return "The weather in " + location + " is sunny.";
        }
    }

    // uncomment when you need it, this makes live API calls
//    @Test
    void testModels() {
        // Get Models instance from ContextManager
        var models = contextManager.getService();
        var availableModels = models.getAvailableModels();
        Assumptions.assumeFalse(availableModels.isEmpty(), "No models available via LiteLLM, skipping testModels test.");

        var messages = List.<ChatMessage>of(new UserMessage("hello world"));
        Map<String, Throwable> failures = new ConcurrentHashMap<>();

        availableModels.keySet().parallelStream().forEach(modelName -> {
            try {
                System.out.println("Testing model: " + modelName);
                // Get model instance via the Models object
                StreamingChatLanguageModel model = models.getModel(modelName, Service.ReasoningLevel.DEFAULT);
                var coder = contextManager.getLlm(model, "testModels");
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
                assertNotNull(result.originalMessage().text(), "AI message text should not be null for model: " + modelName);
                assertFalse(result.originalMessage().text().isBlank(), "AI message text should not be blank for model: " + modelName);

                var firstLine = result.originalMessage().text().lines().findFirst().orElse("");
                System.out.println("Response from " + modelName + ": " + firstLine.substring(0, min(firstLine.length(), 50)) + "...");
            } catch (Throwable t) {
                // Catch assertion errors or any other exceptions during the test for this model
                failures.put(modelName, t);
                System.err.println("Failure testing model " + modelName + ": ");
                t.printStackTrace();
            }
        });

        if (!failures.isEmpty()) {
            String failureSummary = failures.entrySet().stream()
                    .map(entry -> "Model '" + entry.getKey() + "' failed: " + entry.getValue().getMessage() +
                            (entry.getValue().getCause() != null ? " (Cause: " + entry.getValue().getCause().getMessage() + ")" : ""))
                    .collect(Collectors.joining("\n"));
            fail("One or more models failed the basic connectivity test:\n" + failureSummary);
        }
    }

    // uncomment when you need it, this makes live API calls
//    @Test
    void testToolCalling() {
        var models = contextManager.getService();
        var availableModels = models.getAvailableModels();
        Assumptions.assumeFalse(availableModels.isEmpty(), "No models available via LiteLLM, skipping testToolCalling test.");

        var weatherTool = new WeatherTool();
        var toolSpecifications = ToolSpecifications.toolSpecificationsFrom(weatherTool);

        Map<String, Throwable> failures = new ConcurrentHashMap<>();

        List.of("gemini-2.5-pro-exp-03-25").parallelStream()
                .filter(k -> !k.contains("R1")) // R1 doesn't support tool calling OR json output
                .forEach(modelName -> {
                    try {
                        System.out.println("Testing tool calling for model: " + modelName);
                        StreamingChatLanguageModel model = models.getModel(modelName, Service.ReasoningLevel.DEFAULT);
                        var coder = contextManager.getLlm(model, "testToolCalling");
                        assertNotNull(model, "Failed to get model instance for: " + modelName);

                        var messages = new ArrayList<ChatMessage>();
                        messages.add(new UserMessage("What is the weather like in London?"));
                        var result = coder.sendRequest(messages, toolSpecifications, ToolChoice.REQUIRED, false);

                        assertNotNull(result, "Result should not be null for model: " + modelName);
                        assertFalse(false, "Request should not be cancelled for model: " + modelName);
                        if (result.error() != null) {
                            throw new AssertionError("Request resulted in an error for model: " + modelName, result.error());
                        }

                        var chatResponse = result.chatResponse();
                        assertNotNull(chatResponse, "ChatResponse should not be null for model: " + modelName);
                        assertNotNull(result.originalMessage(), "AI message should not be null for model: " + modelName);

                        // ASSERTION 1: Check if a tool execution was requested
                        assertTrue(result.originalMessage().hasToolExecutionRequests(),
                                   "Model " + modelName + " did not request tool execution. Response: " + chatResponse.text());
                        System.out.println("Tool call requested successfully by " + modelName);

                        // check that we can send the result back
                        var tr = result.originalMessage().toolExecutionRequests().getFirst();
                        // NB: this is a quick hack that does not actually pass arguments from the tool call
                        messages.add(result.originalMessage());
                        var term = new ToolExecutionResultMessage(tr.id(), tr.name(), new WeatherTool().getWeather("London"));
                        messages.add(term);
                        messages.add(new UserMessage("Given what you know about London, is this unusual?"));
                        result = coder.sendRequest(messages);
                        assertNotNull(result, "Result should not be null for model: " + modelName);
                        assertFalse(false, "Request should not be cancelled for model: " + modelName);
                        if (result.error() != null) {
                            throw new AssertionError("Followup request resulted in an error for model: " + modelName, result.error());
                        }
                        System.out.println("Tool response processed successfully by " + modelName);
                    } catch (Throwable t) {
                        // Catch assertion errors or any other exceptions during the test for this model
                        failures.put(modelName, t);
                        // Log the error immediately for easier debugging during parallel execution
                        System.err.printf("Failure testing tool calling for model %s: %s%n",
                                          modelName, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                        t.printStackTrace();
                    }
                });

        if (!failures.isEmpty()) {
            String failureSummary = failures.entrySet().stream()
                    .map(entry -> "Model '" + entry.getKey() + "' failed tool calling: " + entry.getValue().getMessage() +
                            (entry.getValue().getCause() != null ? " (Cause: " + entry.getValue().getCause().getMessage() + ")" : ""))
                    .collect(Collectors.joining("\n"));
            fail("One or more models failed the tool calling test:\n" + failureSummary);
        }
    }

    void testEmulateToolExecutionResults() {
        var user1 = new UserMessage("Initial request");
        var term1 = ToolExecutionResultMessage.toolExecutionResultMessage("t1", "toolA", "Result A");
        var term2 = ToolExecutionResultMessage.toolExecutionResultMessage("t2", "toolB", "Result B");
        var user2 = new UserMessage("Follow-up based on results");
        var ai1 = new AiMessage("AI response");
        var term3 = ToolExecutionResultMessage.toolExecutionResultMessage("t3", "toolC", "Result C");
        var user3 = new UserMessage("Another follow-up");
        var term4 = ToolExecutionResultMessage.toolExecutionResultMessage("t4", "toolD", "Result D"); // Trailing

        // Case 1: Single TERM followed by UserMessage
        var messages1 = List.of(user1, term1, user2);
        var result1 = llm.emulateToolExecutionResults(messages1);
        assertEquals(2, result1.size());
        assertEquals(user1, result1.get(0));
        assertTrue(result1.get(1) instanceof UserMessage);
        assertEquals("<toolcall id=\"t1\" name=\"toolA\">\nResult A\n</toolcall>\n\nFollow-up based on results", Messages.getText(result1.get(1)).stripIndent());
        assertEquals(user2.name(), ((UserMessage) result1.get(1)).name()); // Name preserved

        // Case 2: Multiple TERMs followed by UserMessage
        var messages2 = List.of(user1, term1, term2, user2);
        var result2 = llm.emulateToolExecutionResults(messages2);
        assertEquals(2, result2.size());
        assertEquals(user1, result2.get(0));
        assertTrue(result2.get(1) instanceof UserMessage);
        assertEquals("<toolcall id=\"t1\" name=\"toolA\">\nResult A\n</toolcall>\n\n<toolcall id=\"t2\" name=\"toolB\">\nResult B\n</toolcall>\n\nFollow-up based on results", Messages.getText(result2.get(1)).stripIndent());

        // Case 3: TERM followed by non-UserMessage (AiMessage)
        var messages3 = List.of(user1, term1, ai1, user2);
        var result3 = llm.emulateToolExecutionResults(messages3);
        assertEquals(4, result3.size());
        assertEquals(user1, result3.get(0));
        assertTrue(result3.get(1) instanceof UserMessage);
        assertEquals("<toolcall id=\"t1\" name=\"toolA\">\nResult A\n</toolcall>\n", Messages.getText(result3.get(1)).stripIndent());
        assertEquals(ai1, result3.get(2));
        assertEquals(user2, result3.get(3));

        // Case 4: Trailing TERM(s)
        var messages4 = List.of(user1, term1, user2, term4);
        var result4 = llm.emulateToolExecutionResults(messages4);
        assertEquals(3, result4.size());
        assertEquals(user1, result4.get(0));
        assertEquals("<toolcall id=\"t1\" name=\"toolA\">\nResult A\n</toolcall>\n\nFollow-up based on results", Messages.getText(result4.get(1)).stripIndent());
        assertEquals("<toolcall id=\"t4\" name=\"toolD\">\nResult D\n</toolcall>\n", Messages.getText(result4.get(2)).stripIndent());

        // Case 5: Multiple combinations and other messages, including combining multiple terms
        var messages5 = List.of(user1, term1, term2, user2, ai1, term3, term4, user3);
        var result5 = llm.emulateToolExecutionResults(messages5);
        // Expected: user1, combined(message from term1,term2 and user2), ai1, combined(message from term3,term4 and user3)
        assertEquals(4, result5.size());
        assertEquals(user1, result5.get(0));
        var expectedText5_1 = """
                <toolcall id="t1" name="toolA">
                Result A
                </toolcall>
                
                <toolcall id="t2" name="toolB">
                Result B
                </toolcall>
                
                Follow-up based on results""".stripIndent();
        assertEquals(expectedText5_1, Messages.getText(result5.get(1)));
        assertEquals(ai1, result5.get(2));
        var expectedText5_3 = """
                <toolcall id="t3" name="toolC">
                Result C
                </toolcall>
                
                <toolcall id="t4" name="toolD">
                Result D
                </toolcall>
                
                Another follow-up""".stripIndent();
        assertEquals(expectedText5_3, Messages.getText(result5.get(3)));

        // Case 6: No TERMs
        var messages6 = List.of(user1, ai1, user2);
        var result6 = llm.emulateToolExecutionResults(messages6);
        assertEquals(messages6, result6);
        assertEquals(messages6, result6, "List should be identical if unmodified");

        // Case 7: Only TERMs - creates a new UserMessage
        var messages7 = List.<ChatMessage>of(term1, term2);
        var result7 = llm.emulateToolExecutionResults(messages7);
        assertEquals(1, result7.size());
        assertTrue(result7.get(0) instanceof UserMessage);
        assertEquals("<toolcall id=\"t1\" name=\"toolA\">\nResult A\n</toolcall>\n\n<toolcall id=\"t2\" name=\"toolB\">\nResult B\n</toolcall>\n", Messages.getText(result7.get(0)).stripIndent());

        // Case 8: TERM at the beginning followed by UserMessage
        var messages8 = List.of(term1, user1);
        var result8 = llm.emulateToolExecutionResults(messages8);
        assertEquals(1, result8.size());
        assertTrue(result8.get(0) instanceof UserMessage);
        assertEquals("<toolcall id=\"t1\" name=\"toolA\">\nResult A\n</toolcall>\n\nInitial request", Messages.getText(result8.get(0)).stripIndent());
    }
}
