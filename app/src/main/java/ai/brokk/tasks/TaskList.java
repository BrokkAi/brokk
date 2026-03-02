package ai.brokk.tasks;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TaskList {

    public record TaskItem(String id, String title, String text, boolean done) {

        @SuppressWarnings("RedundantNullCheck") // To deserialize old tasks that have null titles
        public TaskItem {
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            if (title == null || title.isBlank()) {
                title = text;
            }
        }

        /**
         * Legacy-compatible constructor for cases where ID and title are not explicitly managed.
         * Generates a random UUID and uses text as the title if title is null.
         */
        public TaskItem(String title, String text, boolean done) {
            this(UUID.randomUUID().toString(), title, text, done);
        }

        /** Create a new task item for a fix-build request with a generated ID. */
        public static TaskItem createFixTask(String prompt) {
            return new TaskItem(UUID.randomUUID().toString(), "Fix Build Failure", prompt, false);
        }
    }

    public record TaskListData(@org.jetbrains.annotations.Nullable String bigPicture, List<TaskItem> tasks) {
        /** Legacy constructor for backward compatibility with single-parameter usage. */
        public TaskListData(List<TaskItem> tasks) {
            this(null, tasks);
        }
    }

    public static String formatChecklist(TaskListData data) {
        var tasks = data.tasks();
        if (tasks.isEmpty()) {
            return "(No tasks)";
        }
        return IntStream.range(0, tasks.size())
                .mapToObj(i -> {
                    var t = tasks.get(i);
                    String status = t.done() ? "x" : " ";
                    return "%d. [%s] %s".formatted(i + 1, status, t.title());
                })
                .collect(Collectors.joining("\n"));
    }
}
