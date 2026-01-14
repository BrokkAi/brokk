package ai.brokk.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class TaskList {

    public record TaskItem(
            String id,
            @JsonProperty(value = "title", required = false, defaultValue = "") @Nullable String title,
            String text,
            boolean done) {

        /**
         * Legacy-compatible constructor for cases where ID and title are not explicitly managed.
         * Generates a random UUID and uses text as the title if title is null.
         */
        public TaskItem(@Nullable String title, String text, boolean done) {
            this(UUID.randomUUID().toString(), (title == null || title.isBlank()) ? text : title, text, done);
        }

        /** Create a new task item for a fix-build request with a generated ID. */
        public static TaskItem createFixTask(String prompt) {
            return new TaskItem(UUID.randomUUID().toString(), "Fix Build Failure", prompt, false);
        }
    }

    public record TaskListData(List<TaskItem> tasks) {}
}
