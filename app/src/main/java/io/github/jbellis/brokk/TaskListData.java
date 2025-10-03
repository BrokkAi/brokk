package io.github.jbellis.brokk;

import java.util.List;

/** DTO wrapper for a task list. */
public record TaskListData(List<TaskListEntryDto> tasks) {}
