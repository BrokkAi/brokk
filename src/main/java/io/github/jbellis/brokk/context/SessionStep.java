package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.TaskResult;
import java.util.List;
import static java.util.Objects.requireNonNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single chronological step in a session timeline.
 *
 * Invariants:
 * - contexts is an ordered list of frozen Context snapshots (no dynamic fragments).
 * - For manual steps (event == null), contexts must contain exactly one snapshot (the produced state).
 * - For AI steps (event != null), contexts may be empty (read-only task) or contain one or more produced snapshots
 *   in chronological order; the pre-context referenced by the event.beforeContextId is NOT duplicated here.
 * - The first context in the list (when present) is the earliest snapshot within this step; the last is the most recent.
 */
public record SessionStep(@Nullable TaskResult event, List<Context> contexts) {

  /**
   * Compact constructor to validate invariants and defensively copy the contexts list.
   */
  public SessionStep {
    // Defensive copy; null check via requireNonNull since @Nullable is not present on contexts.
    contexts = List.copyOf(requireNonNull(contexts, "contexts"));

    // Invariants:
    // - Manual step (event == null): exactly one snapshot.
    // - AI step (event != null): zero or more snapshots allowed (read-only AI turns are represented by an empty list).
    if (event == null) {
      if (contexts.size() != 1) {
        throw new IllegalArgumentException("Manual SessionStep (event == null) requires exactly one Context snapshot");
      }
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
