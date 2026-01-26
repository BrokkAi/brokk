package ai.brokk.executor.jobs.modes;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.context.Context;
import ai.brokk.context.SpecialTextType;
import ai.brokk.executor.jobs.JobExecutionContext;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import ai.brokk.tasks.TaskList;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestService;
import ai.brokk.util.Json;
import java.nio.file.Files;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.Response;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for {@link PlanModeHandler}.
 * Verifies that PLAN mode correctly populates the TASK_LIST fragment in the session context.
 */
public class PlanModeIntegrationTest {

    private ContextManager cm;
    private JobStore jobStore;
    private TestConsoleIO io;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        // 1. Ensure a .brokk directory and .brokk/llm-history directory exist in tempDir.
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir.resolve("llm-history"));

        // 2. Write a minimal .brokk/project.properties file.
        Files.writeString(brokkDir.resolve("project.properties"), "# Minimal properties for test\n");

        var project = new MainProject(tempDir);
        // Use TestService.provider to supply dependencies, but we will inject mocks into JobExecutionContext directly.
        // We override summarizeTaskForConversation to avoid LLM-based title generation which fails in tests.
        cm = new ContextManager(project, TestService.provider(project)) {
            @Override
            public java.util.concurrent.CompletableFuture<String> summarizeTaskForConversation(String input) {
                return java.util.concurrent.CompletableFuture.completedFuture(input);
            }
        };
        io = new TestConsoleIO();
        cm.setIo(io);

        // Initialize jobs directory
        jobStore = new JobStore(tempDir.resolve("jobs"));

        // 3. Call cm.createHeadless() to initialize the session and history before running the test logic.
        cm.createHeadless();
    }

    @Test
    void planMode_populatesTaskListFragment() throws Exception {
        String task1 = "Implement feature X";
        String task2 = "Add tests for X";

        // Mock the LLM response to invoke the createOrReplaceTaskList tool
        String llmResponse = """
                I have created a plan for your request.
                <tool_code>
                print(createOrReplaceTaskList(
                    explanation="Plan for feature X",
                    tasks=["%s", "%s"]
                ))
                </tool_code>
                """.formatted(task1, task2);

        // Use Proxy to mock StreamingChatModel to handle all generate overloads (including ChatRequest/ToolChoice variants)
        StreamingChatModel mockModel = (StreamingChatModel) java.lang.reflect.Proxy.newProxyInstance(
                StreamingChatModel.class.getClassLoader(),
                new Class<?>[]{StreamingChatModel.class},
                (proxy, method, args) -> {
                    if ("generate".equals(method.getName())) {
                        for (Object arg : args) {
                            if (arg instanceof StreamingResponseHandler) {
                                @SuppressWarnings("unchecked")
                                StreamingResponseHandler<Object> handler = (StreamingResponseHandler<Object>) arg;
                                handler.onNext(llmResponse);

                                // Determine return type based on first parameter (ChatRequest vs List<ChatMessage>)
                                if (args.length > 0 && args[0].getClass().getSimpleName().equals("ChatRequest")) {
                                    // ChatRequest -> ChatResponse
                                    try {
                                        Class<?> aiMsgClass = Class.forName("dev.langchain4j.data.message.AiMessage");
                                        Object aiMsg = aiMsgClass.getMethod("from", String.class).invoke(null, llmResponse);

                                        Class<?> chatRespClass = Class.forName("dev.langchain4j.model.chat.response.ChatResponse");
                                        Object builder = chatRespClass.getMethod("builder").invoke(null);
                                        builder.getClass().getMethod("aiMessage", aiMsgClass).invoke(builder, aiMsg);
                                        Object chatResp = builder.getClass().getMethod("build").invoke(builder);

                                        handler.onComplete(Response.from(chatResp));
                                    } catch (Exception e) {
                                        // If reflection fails (e.g. classes moved), try falling back to AiMessage or just log
                                        System.err.println("Failed to construct ChatResponse via reflection: " + e);
                                        // Try standard AiMessage as fallback, though it might fail generics check
                                        handler.onComplete(Response.from(AiMessage.from(llmResponse)));
                                    }
                                } else {
                                    // List<ChatMessage> -> AiMessage
                                    handler.onComplete(Response.from(AiMessage.from(llmResponse)));
                                }
                                return null;
                            }
                        }
                    }
                    return null;
                });

        var spec = new JobSpec(
                "I want to implement feature X",
                true,
                true,
                "test-model",
                "test-model",
                null,
                false,
                Map.of("mode", "PLAN"),
                null, null, null, null, null, null, 20);

        var jobCtx = new JobExecutionContext(
                "test-job-id",
                spec,
                cm,
                jobStore,
                io,
                () -> false,
                mockModel, // plannerModel
                null,
                mockModel); // scanModel

        // When: Running the PLAN mode handler
        PlanModeHandler.run(jobCtx);

        // Then: Context should report the task list data
        var liveCtx = cm.liveContext();
        var taskListData = liveCtx.getTaskListDataOrEmpty();
        assertEquals(2, taskListData.tasks().size(), "Should have 2 tasks");
        assertTrue(taskListData.tasks().stream().anyMatch(t -> t.text().equals(task1)));
        assertTrue(taskListData.tasks().stream().anyMatch(t -> t.text().equals(task2)));

        // And: The fragment should be stored as SpecialTextType.TASK_LIST (JSON)
        var fragOpt = liveCtx.getTaskListFragment();
        assertTrue(fragOpt.isPresent(), "TASK_LIST fragment should be present in context");

        var frag = fragOpt.get();
        assertEquals(SpecialTextType.TASK_LIST.syntaxStyle(), frag.syntaxStyle().join());

        var jsonContent = frag.text().join();
        var deserialized = Json.fromJson(jsonContent, TaskList.TaskListData.class);
        assertEquals(taskListData.tasks().size(), deserialized.tasks().size());
    }

    @Test
    void planMode_emptyTaskList_removesFragment() throws Exception {
        // Given: A context that already has a task list
        var initialData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Existing", "Existing", false)));
        // We must push the context change because ContextManager works on live context
        cm.setTaskListAsync(initialData);
        // Wait for async update (simplified sync wait for test stability)
        Thread.sleep(100);

        var liveCtx = cm.liveContext();
        assertTrue(liveCtx.getTaskListFragment().isPresent());

        // When: Updating with an empty list via Context API (simulating what the handler/tools do)
        Context updated = liveCtx.withTaskList(new TaskList.TaskListData(List.of()));

        // Then: Fragment should be removed
        assertTrue(updated.getTaskListFragment().isEmpty(), "Task list fragment should be removed when empty");
    }
}
