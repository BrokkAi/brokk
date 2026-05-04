package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.Set;

public record ExportResolutionData(Set<CodeUnit> targets, Set<ProjectFile> frontier, Set<String> externalFrontier) {}
