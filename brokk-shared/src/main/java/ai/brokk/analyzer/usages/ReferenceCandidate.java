package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer.Range;
import org.jetbrains.annotations.Nullable;

public record ReferenceCandidate(
        String identifier, @Nullable String qualifier, ReferenceKind kind, Range range, CodeUnit enclosingUnit) {}
