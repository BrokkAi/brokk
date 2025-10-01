package io.github.jbellis.brokk.sessions;

import io.github.jbellis.brokk.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple, path-agnostic store for session task lists.
 *
 * <p>Usage:
 *   var file = Path.of(".../tasklist.json");
 *   var data = new TaskListStore.TaskListData(List.of(new TaskListStore.TaskEntryDto("do it", false)));
 *   TaskListStore.write(file, data);
 *   var loaded = TaskListStore.read(file);
 */
public final class TaskListStore {
    private static final Logger logger = LogManager.getLogger(TaskListStore.class);

    private TaskListStore() {}

    /** DTO for a single task list entry. */
    public static record TaskEntryDto(String text, boolean done) {}

    /** DTO wrapper for a task list. */
    public static record TaskListData(List<TaskEntryDto> tasks) {}

    /**
     * Read a task list from the given file path.
     * If the file does not exist or is blank, returns an empty list.
     */
    public static TaskListData read(Path file) throws IOException {
        if (!Files.exists(file)) {
            logger.debug("Task list file {} does not exist; returning empty list", file);
            return new TaskListData(List.of());
        }

        var json = Files.readString(file, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            logger.debug("Task list file {} is blank; returning empty list", file);
            return new TaskListData(List.of());
        }

        var loaded = Json.fromJson(json, TaskListData.class);
        return new TaskListData(List.copyOf(loaded.tasks()));
    }

    /**
     * Write a task list to the given file path. Parent directories are created if missing.
     */
    public static void write(Path file, TaskListData data) throws IOException {
        Files.createDirectories(file.getParent());
        var normalized = new TaskListData(List.copyOf(data.tasks()));
        var json = Json.toJson(normalized);
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
