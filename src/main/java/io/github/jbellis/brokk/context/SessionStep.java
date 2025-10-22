package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.TaskResult;
import java.util.List;
import static java.util.Objects.requireNonNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single chronological step in a session timeline.
 *
 * Invariants:
 * - contexts is a non-empty, ordered list of frozen Context snapshots (no dynamic fragments).
 * - The first context in the list is the earliest snapshot within this step; the last is the most recent.
 * - event may be null for legacy or manual steps without a corresponding TaskResult.
 */
public record SessionStep(@Nullable TaskResult event, List<Context> contexts) {

  /**
   * Compact constructor to validate invariants and defensively copy the contexts list.
   */
  public SessionStep {
    // Defensive copy; null check via requireNonNull since @Nullable is not present on contexts.
    contexts = List.copyOf(requireNonNull(contexts, "contexts"));
    if (contexts.isEmpty()) {
      throw new IllegalArgumentException("contexts must be non-empty for a SessionStep");
    }
    // Validate each Context is present and contains no dynamic fragments (i.e., it is frozen).
    for (var ctx : contexts) {
      requireNonNull(ctx, "Context in SessionStep.contexts must not be null");
      // Use both assert (for dev) and runtime guard (for production) to enforce invariants.
      assert !ctx.containsDynamicFragments() : "SessionStep requires frozen Context snapshots";
      if (ctx.containsDynamicFragments()) {
        throw new IllegalArgumentException(
            "All Contexts in SessionStep must be frozen (no dynamic fragments). Offender contextId=" + ctx.id());
      }
    }
  }
}
