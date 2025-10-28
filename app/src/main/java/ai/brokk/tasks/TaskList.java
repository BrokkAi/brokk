package ai.brokk.tasks;

import java.util.List;

public class TaskList {

    public record TaskItem(String text, boolean done) {}

    public record TaskListData(List<TaskItem> tasks) {}
}
