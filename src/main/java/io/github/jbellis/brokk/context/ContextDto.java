package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.context.FragmentDtos.*;
import java.util.List;

/**
 * DTO record for Context containing only persistent fields.
 * Excludes transient runtime fields like contextManager, action, etc.
 */
public record ContextDto(List<ReferencedFragmentDto> editableFiles, // Changed from List<Object>
                         List<ReferencedFragmentDto> readonlyFiles, // Changed from List<Object>
                         List<VirtualFragmentDto> virtualFragments,
                         List<TaskEntryDto> taskHistory,
                         TaskFragmentDto parsedOutput,
                         String actionSummary)
{
    public ContextDto {
        // Defensive copying for immutability
        editableFiles = editableFiles != null ? List.copyOf(editableFiles) : List.of();
        readonlyFiles = readonlyFiles != null ? List.copyOf(readonlyFiles) : List.of();
        virtualFragments = virtualFragments != null ? List.copyOf(virtualFragments) : List.of();
        taskHistory = taskHistory != null ? List.copyOf(taskHistory) : List.of();
        // parsedOutput is already immutable (record), no copying needed
        // actionSummary is already immutable (String), no copying needed
    }
}
