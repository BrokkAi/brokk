package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.agents.ArchitectAgent;

/**
 * Encapsulates the choices made by the user in the ArchitectOptionsDialog,
 * including both the agent's operational options and whether to run in a new Git worktree.
 * @param options The configured options for the ArchitectAgent.
 * @param runInWorktree True if the Architect agent should be run in a new Git worktree, false otherwise.
 */
public record ArchitectChoices(ArchitectAgent.ArchitectOptions options, boolean runInWorktree) {
}
