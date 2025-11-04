package ai.brokk.gui.visualize;

import ai.brokk.analyzer.ProjectFile;
import java.util.Map;

public class Graph {

    public final Map<ProjectFile, Node> nodes;
    public final Map<Pair, Edge> edges;

    public Graph(Map<ProjectFile, Node> nodes, Map<Pair, Edge> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public record Pair(ProjectFile a, ProjectFile b) {
        public Pair {
            // Canonical representation: keep a's path lexicographically smaller than b's
            if (a.absPath().toString().compareTo(b.absPath().toString()) > 0) {
                var temp = a;
                a = b;
                b = temp;
            }
        }
    }
}
