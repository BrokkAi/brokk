package io.github.jbellis.brokk.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Canonical, strictly ordered timeline of a session.
 *
 * Invariants/behavior:
 * - Timeline is ordered oldest -> newest.
 * - Each SessionStep contains an ordered list of frozen Context snapshots (no dynamic fragments).
 *   For manual steps (event == null), the list contains exactly one snapshot (the produced state).
 *   For AI steps (event != null), the list may be empty (read-only) or contain one or more produced snapshots.
 *   The pre-context for an AI step is not duplicated in the list and can be resolved via event.beforeContextId.
 * - flattenContexts() preserves the same ordering as concatenating each step.contexts in timeline order.
 * - stepForContext(UUID) searches across all contexts and returns the first matching step, if any.
 *
 * Persistence:
 * - This is an in-memory domain model for now; migration from ContextHistory is supported via
 *   fromContextHistory(ContextHistory). v4 persistence will be added in HistoryIo later.
 */
public final class SessionHistory {
  private static final Logger logger = LogManager.getLogger(SessionHistory.class);

  private final List<SessionStep> timeline;

  /**
   * Create a SessionHistory with the given chronological timeline.
   * The provided list is defensively copied and exposed as an unmodifiable view.
   */
  public SessionHistory(List<SessionStep> timeline) {
    Objects.requireNonNull(timeline, "timeline");
    // SessionStep constructor enforces its own invariants.
    this.timeline = Collections.unmodifiableList(new ArrayList<>(timeline));
  }

  /**
   * The ordered timeline of steps (oldest -> newest).
   */
  public List<SessionStep> timeline() {
    return timeline;
  }

  /**
   * Flatten all contexts from all steps in strict chronological order.
   * This is suitable for existing undo/redo code paths that operate on List<Context>.
   */
  public List<Context> flattenContexts() {
    return timeline.stream()
        .flatMap(step -> step.contexts().stream())
        .collect(Collectors.toUnmodifiableList());
  }

  /**
   * Find the first SessionStep that contains a Context whose id matches contextId.
   */
  public Optional<SessionStep> stepForContext(UUID contextId) {
    Objects.requireNonNull(contextId, "contextId");
    return timeline.stream()
        .filter(step -> step.contexts().stream().anyMatch(c -> contextId.equals(c.id())))
        .findFirst();
  }

  /**
   * Migration helper to derive a canonical timeline from a legacy v3 ContextHistory.
   * Each legacy Context becomes a single-step entry with a null TaskResult.
   * Contexts are frozen if needed to satisfy the SessionStep invariants.
   */
  public static SessionHistory fromContextHistory(ContextHistory legacy) {
    Objects.requireNonNull(legacy, "legacy");
    var steps = new ArrayList<SessionStep>();
    var contexts = legacy.getHistory();

    for (var ctx : contexts) {
      var frozen = ctx;
      if (ctx.containsDynamicFragments()) {
        logger.debug("Freezing legacy Context {} during SessionHistory migration", ctx.id());
        frozen = ctx.freeze();
      }
      // Double-check invariant in case freeze did not eliminate dynamics in an edge case.
      if (frozen.containsDynamicFragments()) {
        logger.warn("Context {} still contains dynamic fragments after freeze(); applying freezeAndCleanup()", frozen.id());
        var fr = frozen.freezeAndCleanup();
        frozen = fr.frozenContext();
      }
      steps.add(new SessionStep(null, List.of(frozen)));
    }

    return new SessionHistory(steps);
  }
}
