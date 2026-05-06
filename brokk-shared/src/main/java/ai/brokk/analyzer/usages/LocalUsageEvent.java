package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer.Range;
import java.util.Set;

public sealed interface LocalUsageEvent
        permits LocalUsageEvent.EnterScope,
                LocalUsageEvent.ExitScope,
                LocalUsageEvent.DeclareSymbol,
                LocalUsageEvent.SeedSymbol,
                LocalUsageEvent.AliasSymbol,
                LocalUsageEvent.ReceiverAccess {

    record EnterScope() implements LocalUsageEvent {}

    record ExitScope() implements LocalUsageEvent {}

    record DeclareSymbol(String name) implements LocalUsageEvent {}

    record SeedSymbol(String name, Set<ReceiverTargetRef> targets) implements LocalUsageEvent {}

    record AliasSymbol(String name, String sourceName) implements LocalUsageEvent {}

    record ReceiverAccess(
            String receiverName, String identifier, ReferenceKind kind, Range range, CodeUnit enclosingUnit)
            implements LocalUsageEvent {}
}
