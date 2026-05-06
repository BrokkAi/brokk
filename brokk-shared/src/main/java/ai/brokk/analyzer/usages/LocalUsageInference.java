package ai.brokk.analyzer.usages;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class LocalUsageInference {

    private LocalUsageInference() {}

    public record Limits(int maxTargetsPerSymbol, double aliasPenaltyPerHop, double ambiguityPenalty) {
        public static Limits defaults() {
            return new Limits(4, 0.1, 0.05);
        }
    }

    private record LocalSymbolState(Set<ReceiverTargetRef> targets, int aliasDepth, boolean blocked) {}

    public static Set<ResolvedReceiverCandidate> infer(List<LocalUsageEvent> events) {
        return infer(events, Limits.defaults());
    }

    public static Set<ResolvedReceiverCandidate> infer(List<LocalUsageEvent> events, Limits limits) {
        var scopes = new ArrayDeque<Map<String, LocalSymbolState>>();
        scopes.addLast(new HashMap<>());
        var resolved = new LinkedHashSet<ResolvedReceiverCandidate>();

        for (LocalUsageEvent event : events) {
            switch (event) {
                case LocalUsageEvent.EnterScope ignored -> scopes.addLast(new HashMap<>());
                case LocalUsageEvent.ExitScope ignored -> {
                    if (scopes.size() > 1) {
                        scopes.removeLast();
                    }
                }
                case LocalUsageEvent.DeclareSymbol declare ->
                    currentScope(scopes).put(declare.name(), blockedState());
                case LocalUsageEvent.SeedSymbol seed ->
                    currentScope(scopes).put(seed.name(), stateFor(seed.targets(), 0, limits));
                case LocalUsageEvent.AliasSymbol alias ->
                    currentScope(scopes).put(alias.name(), aliasState(alias.sourceName(), scopes, limits));
                case LocalUsageEvent.ReceiverAccess access ->
                    lookupVisible(access.receiverName(), scopes)
                            .filter(state ->
                                    !state.blocked() && !state.targets().isEmpty())
                            .ifPresent(state -> resolved.addAll(resolveAccess(access, state, limits)));
            }
        }

        return Set.copyOf(resolved);
    }

    private static Set<ResolvedReceiverCandidate> resolveAccess(
            LocalUsageEvent.ReceiverAccess access, LocalSymbolState state, Limits limits) {
        double ambiguityPenalty = Math.max(0.0, (state.targets().size() - 1) * limits.ambiguityPenalty());
        var resolved = new LinkedHashSet<ResolvedReceiverCandidate>();
        for (ReceiverTargetRef target : state.targets()) {
            double confidence = Math.max(
                    0.0,
                    access.kind() == ReferenceKind.METHOD_CALL || access.kind() == ReferenceKind.FIELD_READ
                            ? target.confidence()
                                    - (state.aliasDepth() * limits.aliasPenaltyPerHop())
                                    - ambiguityPenalty
                            : target.confidence() - ambiguityPenalty);
            resolved.add(new ResolvedReceiverCandidate(
                    access.identifier(), target, access.kind(), access.range(), access.enclosingUnit(), confidence));
        }
        return resolved;
    }

    private static LocalSymbolState aliasState(
            String sourceName, ArrayDeque<Map<String, LocalSymbolState>> scopes, Limits limits) {
        LocalSymbolState source = lookupVisible(sourceName, scopes).orElse(null);
        if (source == null || source.blocked() || source.targets().isEmpty()) {
            return blockedState();
        }
        return stateFor(source.targets(), source.aliasDepth() + 1, limits);
    }

    private static LocalSymbolState stateFor(Set<ReceiverTargetRef> targets, int aliasDepth, Limits limits) {
        if (targets.isEmpty() || targets.size() > limits.maxTargetsPerSymbol()) {
            return blockedState();
        }
        return new LocalSymbolState(Set.copyOf(targets), aliasDepth, false);
    }

    private static LocalSymbolState blockedState() {
        return new LocalSymbolState(Set.of(), 0, true);
    }

    private static Map<String, LocalSymbolState> currentScope(ArrayDeque<Map<String, LocalSymbolState>> scopes) {
        return scopes.getLast();
    }

    private static Optional<LocalSymbolState> lookupVisible(
            String name, ArrayDeque<Map<String, LocalSymbolState>> scopes) {
        var descending = scopes.descendingIterator();
        while (descending.hasNext()) {
            Map<String, LocalSymbolState> scope = descending.next();
            if (scope.containsKey(name)) {
                return Optional.of(scope.get(name));
            }
        }
        return Optional.empty();
    }
}
