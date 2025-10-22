package io.github.jbellis.brokk.context;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/** DTOs for timeline-v4 persistence. */
public final class TimelineDtos {
  private TimelineDtos() {}

  public record SessionStepDto(List<String> contextIds, @Nullable TaskResultDto event) {}

  public record TaskResultDto(
      @Nullable String taskId,
      @Nullable String beforeContextId,
      @Nullable String afterContextId,
      long createdAtEpochMillis,
      @Nullable String summaryText,
      String actionDescription,
      @Nullable String outputId,
      StopDetailsDto stopDetails) {}

  public record StopDetailsDto(String reason, String explanation) {}
}
