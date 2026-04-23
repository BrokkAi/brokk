package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.ProjectFile;
import java.util.Set;

public record ReferenceGraphResult(
        Set<ReferenceHit> hits, Set<ProjectFile> frontier, Set<String> externalFrontierSpecifiers) {}
