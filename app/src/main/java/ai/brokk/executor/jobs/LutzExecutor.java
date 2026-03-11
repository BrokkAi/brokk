package ai.brokk.executor.jobs;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public final class LutzExecutor {
    private static final Logger logger = LogManager.getLogger(LutzExecutor.class);
    private static final int LUTZ_RECAP_WORD_BUDGET = 40;
    private static final String LUTZ_RECAP_DESCRIPTION = "LUTZ run recap";

    private final @Nullable ContextManager cm;
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
        runSearchPhase(taskInput, plannerModel, scope);

        LutzContext adapter = new LutzContext() {
            @Override
            public List<TaskList.TaskItem> getTasks() {
                return requireNonNull(cm).getTaskList().tasks();
            }

            @Override
            @Blocking
            public void executeTask(TaskList.TaskItem task, StreamingChatModel planner, StreamingChatModel code)
                    throws InterruptedException {
                requireNonNull(cm).executeTask(task, planner, code);
            }
        };

        var completedTasks = runLutzFromSearchResult(adapter, plannerModel, codeModel);
        var contextWithRecap =
                appendLutzRecapToHistory(requireNonNull(cm).liveContext(), adapter.getTasks(), completedTasks);
        scope.publish(contextWithRecap);
        emitPostRunSummary(adapter.getTasks(), completedTasks);
    }

    @Blocking
    private void runSearchPhase(String taskInput, StreamingChatModel plannerModel, ContextManager.TaskScope scope)
            throws InterruptedException {
        var context = requireNonNull(cm).liveContext();
        var searchAgent =
                new ai.brokk.agents.SearchAgent(context, taskInput, plannerModel, SearchPrompts.Objective.LUTZ, scope);
        var taskListResult = searchAgent.execute();
        scope.append(taskListResult);
    }

    interface LutzContext {
        List<TaskList.TaskItem> getTasks();

        @Blocking
        void executeTask(TaskList.TaskItem task, StreamingChatModel planner, StreamingChatModel code)
                throws InterruptedException;
    }

    @Blocking
    List<TaskList.TaskItem> runLutzFromSearchResult(
            LutzContext lutzContext, StreamingChatModel plannerModel, @Nullable StreamingChatModel codeModel)
            throws InterruptedException {
        var generatedTasks = lutzContext.getTasks();
        if (generatedTasks.isEmpty()) {
            var msg = "SearchAgent phase complete; no tasks to execute.";
            logger.info("LUTZ orchestration: {}", msg);
            if (console != null) {
                console.showNotification(IConsoleIO.NotificationRole.INFO, msg);
            }
            return List.of();
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
            lutzContext.executeTask(
                    generatedTask,
                    plannerModel,
                    requireNonNull(codeModel, "code model unavailable for LUTZ task execution"));

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
        return incompleteTasks;
    }

    Context appendLutzRecapToHistory(
            Context context, List<TaskList.TaskItem> generatedTasks, List<TaskList.TaskItem> completedTasks) {
        if (completedTasks.isEmpty()) {
            return context;
        }

        var completedTaskNames = completedTasks.stream()
                .map(task -> task.title().isBlank() ? task.text() : task.title())
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();

        var recapText =
                """
                LUTZ run recap: completed %d of %d generated tasks.
                Completed: %s
                """
                        .formatted(completedTasks.size(), generatedTasks.size(), String.join("; ", completedTaskNames));

        var summary = context.getContextManager()
                .summarizeForConversation(recapText, LUTZ_RECAP_WORD_BUDGET)
                .join();

        var recapMessages = List.of(new SystemMessage("LUTZ recap"), new AiMessage(recapText));
        var recapLog = new ContextFragments.TaskFragment(recapMessages, LUTZ_RECAP_DESCRIPTION);

        return context.addHistoryEntry(recapLog, summary, null);
    }

    void emitPostRunSummary(List<TaskList.TaskItem> generatedTasks, List<TaskList.TaskItem> completedTasks) {
        if (completedTasks.isEmpty()) {
            return;
        }

        var message =
                "LUTZ completed %d of %d generated task(s).".formatted(completedTasks.size(), generatedTasks.size());
        logger.info(message);
        if (console != null) {
            console.showNotification(IConsoleIO.NotificationRole.INFO, message);
        }
    }
}
