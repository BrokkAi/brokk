package io.github.jbellis.brokk.sessions;

import java.util.List;

/**
 * Container DTOs for session task lists.
 */
public final class TaskListStore {

    private TaskListStore() {}

    /** DTO for a single task list entry. */
    public static record TaskEntryDto(String text, boolean done) {}

    /** DTO wrapper for a task list. */
    public static record TaskListData(List<TaskEntryDto> tasks) {}
}
