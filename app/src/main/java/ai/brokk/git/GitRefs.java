package ai.brokk.git;

import org.eclipse.jgit.lib.Constants;

/**
 * Centralized constants for special Git references used throughout Brokk.
 */
public final class GitRefs {
    private GitRefs() {}

    /** Sentinel value representing the current working tree state (uncommitted/unstaged changes). */
    public static final String WORKING = "WORKING";

    /** The SHA-1 of the empty tree object, used when diffing root commits. */
    public static final String EMPTY_TREE = Constants.EMPTY_TREE_ID.getName();
}
