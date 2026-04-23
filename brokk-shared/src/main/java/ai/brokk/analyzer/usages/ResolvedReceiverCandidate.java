package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer.Range;

public record ResolvedReceiverCandidate(
        String identifier,
        ReceiverTargetRef receiverTarget,
        ReferenceKind kind,
        Range range,
        CodeUnit enclosingUnit,
        double confidence) {}
