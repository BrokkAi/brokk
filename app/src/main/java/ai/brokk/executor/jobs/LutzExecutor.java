package ai.brokk.executor.jobs;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.agents.LutzAgent;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Orchestrates LUTZ (Locate, Understand, Transform, Zap) jobs.
 * This includes a search phase to build context and a task list, followed by sequential task execution.
 */
public final class LutzExecutor {
    private static final Logger logger = LogManager.getLogger(LutzExecutor.class);

    private final ContextManager cm;
    private final BooleanSupplier isCancelled;
    private final @Nullable IConsoleIO console;

    public LutzExecutor(ContextManager cm, BooleanSupplier isCancelled, @Nullable IConsoleIO console) {
        this.cm = cm;
        this.isCancelled = isCancelled;
        this.console = console;
    }

    @Blocking
    public void execute(
            String taskInput,
            StreamingChatModel plannerModel,
            @Nullable StreamingChatModel codeModel,
            ContextManager.TaskScope scope)
            throws InterruptedException {
        // Phase 1: Search
        runSearchPhase(taskInput, plannerModel, scope);

        // Phase 2: Execution Loop
        LutzContext adapter = new LutzContext() {
            @Override
            public List<TaskList.TaskItem> getTasks() {
                return cm.getTaskList().tasks();
            }

            @Override
            @Blocking
            public void executeTask(TaskList.TaskItem task, StreamingChatModel planner, StreamingChatModel code)
                    throws InterruptedException {
                cm.executeTask(task, planner, code);
            }
        };

        runLutzFromSearchResult(adapter, plannerModel, codeModel);
    }

    @Blocking
    private void runSearchPhase(String taskInput, StreamingChatModel plannerModel, ContextManager.TaskScope scope)
            throws InterruptedException {
        var context = cm.liveContext();
        var searchAgent = new LutzAgent(context, taskInput, plannerModel, SearchPrompts.Objective.LUTZ, scope);
        var taskListResult = searchAgent.execute();
        scope.append(taskListResult);
    }

    /**
     * Abstract context for LUTZ task orchestration, used to decouple from SearchAgent/LLM in tests.
     */
    interface LutzContext {
        List<TaskList.TaskItem> getTasks();

        @Blocking
        void executeTask(TaskList.TaskItem task, StreamingChatModel planner, StreamingChatModel code)
                throws InterruptedException;
    }

    @Blocking
    void runLutzFromSearchResult(
            LutzContext lutzContext, StreamingChatModel plannerModel, @Nullable StreamingChatModel codeModel)
            throws InterruptedException {
        var generatedTasks = lutzContext.getTasks();
        if (generatedTasks.isEmpty()) {
            var msg = "Search complete. No tasks were identified for execution.";
            logger.info("LUTZ orchestration: {}", msg);
            reportFinalSummary(msg);
            return;
        }

        logger.debug("LUTZ orchestration: {} task(s) to execute", generatedTasks.size());
        var incompleteTasks = generatedTasks.stream().filter(t -> !t.done()).toList();
        logger.debug("LUTZ orchestration: will execute {} incomplete task(s)", incompleteTasks.size());

        if (isCancelled.getAsBoolean()) {
            throw new JobRunner.IssueCancelledException(
                    "LUTZ orchestration: execution cancelled before task execution");
        }

        for (TaskList.TaskItem generatedTask : incompleteTasks) {
            if (isCancelled.getAsBoolean()) {
                throw new JobRunner.IssueCancelledException(
                        "LUTZ orchestration: execution cancelled during task iteration");
            }

            logger.info("LUTZ orchestration: executing generated task: {}", generatedTask.text());
            try {
                lutzContext.executeTask(
                        generatedTask,
                        plannerModel,
                        requireNonNull(codeModel, "code model unavailable for LUTZ task execution"));
            } catch (Exception e) {
                logger.warn("LUTZ orchestration: generated task execution failed: {}", e.getMessage());
                throw e;
            }

            if (isCancelled.getAsBoolean()) {
                throw new JobRunner.IssueCancelledException(
                        "LUTZ orchestration: execution cancelled during task iteration");
            }
        }

        if (isCancelled.getAsBoolean()) {
            throw new JobRunner.IssueCancelledException(
                    "LUTZ orchestration: execution cancelled after final task execution");
        }

        logger.debug("LUTZ orchestration: all generated tasks executed");
        reportFinalSummary("All identified tasks have been successfully executed.");
    }

    private void reportFinalSummary(String message) {
        if (console == null) return;

        String summary =
                """

                ## LUTZ Execution Summary
                %s

                **Status:** Complete
                """
                        .formatted(message);

        console.llmOutput(
                summary, dev.langchain4j.data.message.ChatMessageType.AI, ai.brokk.LlmOutputMeta.newMessage());
    }
}
