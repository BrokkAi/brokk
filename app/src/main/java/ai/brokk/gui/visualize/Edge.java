package ai.brokk.gui.visualize;

import ai.brokk.analyzer.ProjectFile;

public record Edge(ProjectFile a, ProjectFile b, int weight) {
}
