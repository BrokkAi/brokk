package ai.brokk.tasks;

import java.util.List;
import java.util.UUID;

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

    public record TaskListData(List<TaskItem> tasks) {}
}
