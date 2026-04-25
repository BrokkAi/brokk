package ai.brokk.analyzer;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

public final class RelaxedSourceLookupResolver {

    public record RelaxedSourceLookup(@Nullable CodeUnit codeUnit, List<String> ambiguousFqNames) {
        public static RelaxedSourceLookup resolved(CodeUnit codeUnit) {
            return new RelaxedSourceLookup(codeUnit, List.of());
        }

        public static RelaxedSourceLookup ambiguous(List<String> ambiguousFqNames) {
            return new RelaxedSourceLookup(null, List.copyOf(ambiguousFqNames));
        }

        public static RelaxedSourceLookup notFound() {
            return new RelaxedSourceLookup(null, List.of());
        }

        public boolean isResolved() {
            return codeUnit != null;
        }

        public boolean isAmbiguous() {
            return !ambiguousFqNames.isEmpty();
        }

        public String ambiguityMessage(String kind, String requestedName) {
            return "Ambiguous %s match for '%s':\n- %s"
                    .formatted(kind, requestedName, String.join("\n- ", ambiguousFqNames));
        }
    }

    private RelaxedSourceLookupResolver() {}

    public static Map<String, RelaxedSourceLookup> resolveLookups(
            IAnalyzer analyzer, Collection<String> requestedNames, Predicate<CodeUnit> kindFilter) {
        Map<String, RelaxedSourceLookup> results = new LinkedHashMap<>();
        Map<String, String> unresolvedNames = new LinkedHashMap<>();

        for (String requestedName : requestedNames) {
            if (results.containsKey(requestedName)) {
                continue;
            }

            var exactMatch = analyzer.getDefinitions(requestedName).stream()
                    .filter(kindFilter)
                    .findFirst();
            if (exactMatch.isPresent()) {
                results.put(requestedName, RelaxedSourceLookup.resolved(exactMatch.get()));
                continue;
            }

            String normalizedRequestedName = normalizeName(requestedName.strip());
            if (normalizedRequestedName.isEmpty()) {
                results.put(requestedName, RelaxedSourceLookup.notFound());
                continue;
            }
            unresolvedNames.put(requestedName, normalizedRequestedName);
        }

        if (unresolvedNames.isEmpty()) {
            return results;
        }

        Map<String, List<String>> requestedNamesByNormalizedKey = new LinkedHashMap<>();
        for (var entry : unresolvedNames.entrySet()) {
            requestedNamesByNormalizedKey
                    .computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }

        Map<String, Map<String, CodeUnit>> matchesByRequestedName = new LinkedHashMap<>();
        unresolvedNames.keySet().forEach(name -> matchesByRequestedName.put(name, new LinkedHashMap<>()));

        List<CodeUnit> declarations = analyzer.getAllDeclarations();
        addRelaxedMatches(matchesByRequestedName, requestedNamesByNormalizedKey, declarations, kindFilter);
        declarations.stream()
                .filter(CodeUnit::isClass)
                .forEach(cls -> addRelaxedMatches(
                        matchesByRequestedName,
                        requestedNamesByNormalizedKey,
                        analyzer.getMembersInClass(cls),
                        kindFilter));

        for (String requestedName : unresolvedNames.keySet()) {
            Map<String, CodeUnit> matches = requireNonNull(matchesByRequestedName.get(requestedName));

            if (matches.size() == 1) {
                results.put(
                        requestedName,
                        RelaxedSourceLookup.resolved(matches.values().iterator().next()));
            } else if (!matches.isEmpty()) {
                results.put(
                        requestedName,
                        RelaxedSourceLookup.ambiguous(matches.values().stream()
                                .map(CodeUnit::fqName)
                                .sorted()
                                .toList()));
            } else {
                results.put(requestedName, RelaxedSourceLookup.notFound());
            }
        }

        return results;
    }

    private static void addRelaxedMatches(
            Map<String, Map<String, CodeUnit>> matchesByRequestedName,
            Map<String, List<String>> requestedNamesByNormalizedKey,
            Collection<CodeUnit> candidates,
            Predicate<CodeUnit> kindFilter) {
        candidates.stream().filter(kindFilter).forEach(candidate -> {
            for (String candidateKey : lookupKeysFor(candidate)) {
                List<String> requestedNames = requestedNamesByNormalizedKey.get(candidateKey);
                if (requestedNames == null) {
                    continue;
                }
                for (String requestedName : requestedNames) {
                    Map<String, CodeUnit> matches = requireNonNull(matchesByRequestedName.get(requestedName));
                    matches.putIfAbsent(candidate.fqName(), candidate);
                }
            }
        });
    }

    private static Collection<String> lookupKeysFor(CodeUnit codeUnit) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();

        String normalizedFqName = normalizeName(codeUnit.fqName());
        if (!normalizedFqName.isEmpty()) {
            keys.add(normalizedFqName);
            int separatorIdx = normalizedFqName.indexOf('.');
            while (separatorIdx >= 0) {
                if (separatorIdx + 1 < normalizedFqName.length()) {
                    keys.add(normalizedFqName.substring(separatorIdx + 1));
                }
                separatorIdx = normalizedFqName.indexOf('.', separatorIdx + 1);
            }
        }

        String normalizedShortName = normalizeName(codeUnit.shortName());
        if (!normalizedShortName.isEmpty()) {
            keys.add(normalizedShortName);
        }

        String normalizedIdentifier = normalizeName(codeUnit.identifier());
        if (!normalizedIdentifier.isEmpty()) {
            keys.add(normalizedIdentifier);
        }

        return keys;
    }

    private static String normalizeName(String name) {
        return name.replace('$', '.');
    }
}
