package io.github.jbellis.brokk.agents;

/**
 * Tiny utility holder for non-text operations that future steps may emit. These are immutable and can be composed and
 * audited.
 */
public final class NonTextOps {

    private NonTextOps() {
        // utility holder
    }

    public sealed interface NonTextOp permits Move, Delete, PickSide, SetExecutable {}

    /** Move/rename a file path. */
    public record Move(String from, String to) implements NonTextOp {}

    /** Delete a file path. */
    public record Delete(String path) implements NonTextOp {}

    /** Pick one side for the given index path in a conflict. side must be either "ours" or "theirs". */
    public record PickSide(String indexPath, String side) implements NonTextOp {
        public PickSide {
            if (!"ours".equals(side) && !"theirs".equals(side)) {
                throw new IllegalArgumentException("side must be 'ours' or 'theirs'");
            }
        }
    }

    /** Toggle the executable bit on a file path. */
    public record SetExecutable(String path, boolean executable) implements NonTextOp {}
}
