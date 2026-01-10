package ai.brokk.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public class TaskList {

    public record TaskItem(
            @JsonProperty(value = "title", required = false, defaultValue = "") @Nullable String title,
            String text,
            boolean done) {}

    public record TaskListData(List<TaskItem> tasks) {}
}
