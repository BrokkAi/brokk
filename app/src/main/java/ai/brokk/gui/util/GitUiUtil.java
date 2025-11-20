package ai.brokk.gui.util;

/**
 * Static utilities for showing diffs, capturing diffs, or editing files in the Git UI, removing duplicated code across
 * multiple panels.
 *
 * <p>This class consolidates methods from three interfaces:
 * <ul>
 *   <li>{@link GitRepoIdUtil} - Repository identification and validation (owner/repo, URLs, hosts)</li>
 *   <li>{@link GitHostUtil} - Remote/host operations (SHA availability, branch info)</li>
 *   <li>{@link GitDiffUiUtil} - Diff display and context capture operations</li>
 * </ul>
 *
 * <p>All methods are static and can be accessed directly via the interface names or through this
 * unified class.
 */
public final class GitUiUtil implements GitRepoIdUtil, GitHostUtil, GitDiffUiUtil {
    private GitUiUtil() {}
}
