package io.github.jbellis.brokk.gui.visualize;

import io.github.jbellis.brokk.analyzer.ProjectFile;

public record Edge(ProjectFile a, ProjectFile b, int weight) {
}
