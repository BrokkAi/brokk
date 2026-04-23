package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer.Range;
import ai.brokk.analyzer.ProjectFile;

public record ReferenceHit(
        ProjectFile file,
        Range range,
        CodeUnit enclosingUnit,
        ReferenceKind kind,
        CodeUnit resolved,
        double confidence) {}
