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
import dev.langchain4j.model.chat.StreamingChatModel;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        String llmResponse =
                """
                I have created a plan for your request.
                <tool_code>
                print(createOrReplaceTaskList(
                    explanation="Plan for feature X",
                    tasks=["%s", "%s"]
                ))
                </tool_code>
                """
                        .formatted(task1, task2);

        // Use Proxy to mock StreamingChatModel to handle all generate overloads (including ChatRequest/ToolChoice
        // variants)
        StreamingChatModel mockModel = (StreamingChatModel) java.lang.reflect.Proxy.newProxyInstance(
                StreamingChatModel.class.getClassLoader(),
                new Class<?>[] {StreamingChatModel.class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("defaultRequestParameters".equals(method.getName())) {
                            Class<?> returnType = method.getReturnType();
                            // Intercept the parameters call and return a proxy that provides token counts
                            if (returnType.isInterface()) {
                                return java.lang.reflect.Proxy.newProxyInstance(
                                        returnType.getClassLoader(), new Class<?>[] {returnType}, (p, m, a) -> {
                                            if ("maxCompletionTokens".equals(m.getName())
                                                    || "maxOutputTokens".equals(m.getName())) {
                                                return 1024;
                                            }
                                            return null;
                                        });
                            }
                            return null;
                        }
                        if ("generate".equals(method.getName())) {
                            dev.langchain4j.model.StreamingResponseHandler<Object> handler = null;
                            if (args != null) {
                                for (Object arg : args) {
                                    if (arg instanceof dev.langchain4j.model.StreamingResponseHandler<?> h) {
                                        @SuppressWarnings("unchecked")
                                        var casted = (dev.langchain4j.model.StreamingResponseHandler<Object>) h;
                                        handler = casted;
                                        break;
                                    }
                                }
                            }

                            if (handler != null) {
                                handler.onNext(llmResponse);
                                handler.onComplete(dev.langchain4j.model.output.Response.from(
                                        dev.langchain4j.data.message.AiMessage.from(llmResponse)));
                            }
                        }
                        return null;
                    }
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
                null,
                null,
                null,
                null,
                null,
                null,
                20);

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
