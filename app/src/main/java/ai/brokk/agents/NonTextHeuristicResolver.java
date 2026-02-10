package ai.brokk.agents;

import static ai.brokk.agents.MergeAgent.FileConflict;
import static ai.brokk.agents.MergeAgent.NonTextMetadata;
import static ai.brokk.agents.NonTextOps.*;
import static ai.brokk.agents.NonTextType.DELETE_MODIFY;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.errors.GitAPIException;

public final class NonTextHeuristicResolver {

    public record Result(List<NonTextOp> ops, NonTextConfidence confidence, String summary) {}

    /**
     * Plan heuristics for a group of non-text conflicts.
     *
     * <p>Currently implemented: - DELETE_MODIFY: pick the side that still has content.
     *
     * <p>All other types: returns an empty plan with LOW confidence.
     */
    public static Result plan(List<Map.Entry<FileConflict, NonTextMetadata>> group) {
        var ops = new ArrayList<NonTextOp>();
        var summaries = new ArrayList<String>();
        var confidence = NonTextConfidence.LOW;

        for (var e : group) {
            var fc = e.getKey();
            var meta = e.getValue();

            if (meta.type() == DELETE_MODIFY) {
                // If our side has no content, theirs has content -> pick "theirs".
                // If their side has no content, ours has content -> pick "ours".
                String side = "ours";
                if (fc.ourContent() == null && fc.theirContent() != null) {
                    side = "theirs";
                }
                String indexPath = meta.indexPath() != null
                        ? meta.indexPath()
                        : (meta.ourPath() != null ? meta.ourPath() : meta.theirPath());
                if (indexPath != null && !indexPath.isBlank()) {
                    ops.add(new PickSide(indexPath, side));
                    summaries.add("DELETE_MODIFY: pick " + side + " for " + indexPath);
                    confidence = NonTextConfidence.HIGH;
                } else {
                    summaries.add("DELETE_MODIFY: missing index path; no-op");
                }
            }
        }

        if (ops.isEmpty()) {
            return new Result(List.of(), NonTextConfidence.LOW, "No heuristic plan generated.");
        } else {
            return new Result(List.copyOf(ops), confidence, String.join("; ", summaries));
        }
    }

    /**
     * Apply a sequence of non-text operations to the repository.
     *
     * <p>Supported ops: - PickSide: resolves conflict for path by choosing "ours" or "theirs" - Move: git mv from -> to
     * - Delete: git rm path - SetExecutable: toggle executable bit and stage
     */
    public static void apply(GitRepo repo, Path root, List<NonTextOp> ops) throws GitAPIException, IOException {
        for (var op : ops) {
            if (op instanceof PickSide ps) {
                repo.checkoutPathWithStage(ps.indexPath(), ps.side());
            } else if (op instanceof Move mv) {
                repo.move(mv.from(), mv.to());
            } else if (op instanceof Delete del) {
                // Stage deletion; this also removes from working tree
                repo.getGit().rm().addFilepattern(del.path()).call();
                repo.invalidateCaches();
            } else if (op instanceof SetExecutable se) {
                repo.setExecutable(se.path(), se.executable());
            } else {
                // Future ops: ignore for now
            }
        }
    }
}
