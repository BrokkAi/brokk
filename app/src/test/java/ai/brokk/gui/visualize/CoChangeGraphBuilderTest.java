package ai.brokk.gui.visualize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.ICommitInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CoChangeGraphBuilderTest {

    @Test
    void accumulatesEdgeWeightsAndIgnoresNonTracked() {
        Path root =
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

        var A = new ProjectFile(root, "src/A.java");
        var B = new ProjectFile(root, "src/B.java");
        var C = new ProjectFile(root, "src/C.java");
        var D = new ProjectFile(root, "src/D.java"); // not tracked

        Set<ProjectFile> tracked = Set.of(A, B, C);

        // Synthetic commits:
        // 1) A,B
        // 2) A,B,C
        // 3) B,C
        // 4) D (ignored - not tracked)
        // 5) A,A (duplicate within commit - should not create self-edge)
        List<ICommitInfo> commits = List.of(
                new ICommitInfo.CommitInfoStub("c1") {
                    @Override
                    public List<ProjectFile> changedFiles() {
                        return List.of(A, B);
                    }
                },
                new ICommitInfo.CommitInfoStub("c2") {
                    @Override
                    public List<ProjectFile> changedFiles() {
                        return List.of(A, B, C);
                    }
                },
                new ICommitInfo.CommitInfoStub("c3") {
                    @Override
                    public List<ProjectFile> changedFiles() {
                        return List.of(B, C);
                    }
                },
                new ICommitInfo.CommitInfoStub("c4") {
                    @Override
                    public List<ProjectFile> changedFiles() {
                        return List.of(D);
                    }
                },
                new ICommitInfo.CommitInfoStub("c5") {
                    @Override
                    public List<ProjectFile> changedFiles() {
                        return List.of(A, A);
                    }
                });

        var noopProgress = new java.util.concurrent.atomic.AtomicReference<CoChangeGraphBuilder.Progress>();
        Supplier<Boolean> notCancelled = () -> false;

        Graph graph =
                CoChangeGraphBuilder.buildGraphFromCommits(commits, tracked, noopProgress::set, notCancelled, Set.of());

        // Nodes: only A, B, C
        assertEquals(3, graph.nodes.size(), "Expected nodes for tracked changed files only");
        assertNotNull(graph.nodes.get(A));
        assertNotNull(graph.nodes.get(B));
        assertNotNull(graph.nodes.get(C));

        // Edges: AB:2 (c1, c2), AC:1 (c2), BC:2 (c2, c3)
        var ab = graph.edges.get(new Graph.Pair(A, B));
        var ac = graph.edges.get(new Graph.Pair(A, C));
        var bc = graph.edges.get(new Graph.Pair(B, C));

        assertNotNull(ab, "Edge A-B missing");
        assertNotNull(ac, "Edge A-C missing");
        assertNotNull(bc, "Edge B-C missing");

        assertEquals(2, ab.weight(), "AB weight should accumulate across c1 and c2");
        assertEquals(1, ac.weight(), "AC weight should be from c2 only");
        assertEquals(2, bc.weight(), "BC weight should accumulate across c2 and c3");

        // Ensure D is not present as a node nor part of any edge
        var hasDNode = graph.nodes.containsKey(D);
        var hasDEdge = graph.edges.keySet().stream().anyMatch(p -> p.a().equals(D) || p.b().equals(D));
        assertEquals(false, hasDNode, "Untracked file D should be ignored (no node)");
        assertEquals(false, hasDEdge, "Untracked file D should be ignored (no edge)");
    }
}
