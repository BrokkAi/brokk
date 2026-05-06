package ai.brokk.analyzer;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

public final class RelaxedSourceLookupResolver {
    private record RequestedLookup(String requestedName, IAnalyzer.SourceLookupAlias alias) {}

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
        Map<String, Collection<IAnalyzer.SourceLookupAlias>> unresolvedLookupKeysByRequestedName =
                new LinkedHashMap<>();

        for (String requestedName : requestedNames) {
            if (results.containsKey(requestedName)) {
                continue;
            }

            Collection<IAnalyzer.SourceLookupAlias> lookupKeys = requestLookupKeysFor(analyzer, requestedName);
            var exactMatch = lookupKeys.stream()
                    .flatMap(alias -> analyzer.getDefinitions(alias.lookupName()).stream()
                            .filter(kindFilter)
                            .filter(candidate -> matchesSource(alias, candidate)))
                    .findFirst();
            if (exactMatch.isPresent()) {
                results.put(requestedName, RelaxedSourceLookup.resolved(exactMatch.get()));
                continue;
            }

            if (lookupKeys.isEmpty()) {
                results.put(requestedName, RelaxedSourceLookup.notFound());
                continue;
            }
            unresolvedLookupKeysByRequestedName.put(requestedName, lookupKeys);
        }

        if (unresolvedLookupKeysByRequestedName.isEmpty()) {
            return results;
        }

        Map<String, List<RequestedLookup>> requestedLookupsByNormalizedKey = new LinkedHashMap<>();
        for (var entry : unresolvedLookupKeysByRequestedName.entrySet()) {
            String requestedName = entry.getKey();
            entry.getValue().forEach(alias -> requestedLookupsByNormalizedKey
                    .computeIfAbsent(alias.lookupName(), ignored -> new ArrayList<>())
                    .add(new RequestedLookup(requestedName, alias)));
        }

        Map<String, Set<CodeUnit>> matchesByRequestedName = new LinkedHashMap<>();
        unresolvedLookupKeysByRequestedName
                .keySet()
                .forEach(name -> matchesByRequestedName.put(name, new LinkedHashSet<>()));

        List<CodeUnit> declarations = analyzer.getAllDeclarations();
        addRelaxedMatches(matchesByRequestedName, requestedLookupsByNormalizedKey, declarations, kindFilter);
        declarations.stream()
                .filter(CodeUnit::isClass)
                .forEach(cls -> addRelaxedMatches(
                        matchesByRequestedName,
                        requestedLookupsByNormalizedKey,
                        analyzer.getMembersInClass(cls),
                        kindFilter));

        for (String requestedName : matchesByRequestedName.keySet()) {
            Set<CodeUnit> matches = requireNonNull(matchesByRequestedName.get(requestedName));

            if (matches.size() == 1) {
                results.put(
                        requestedName,
                        RelaxedSourceLookup.resolved(matches.iterator().next()));
            } else if (!matches.isEmpty()) {
                results.put(requestedName, RelaxedSourceLookup.ambiguous(ambiguityNames(matches)));
            } else {
                results.put(requestedName, RelaxedSourceLookup.notFound());
            }
        }

        return results;
    }

    private static void addRelaxedMatches(
            Map<String, Set<CodeUnit>> matchesByRequestedName,
            Map<String, List<RequestedLookup>> requestedLookupsByNormalizedKey,
            Collection<CodeUnit> candidates,
            Predicate<CodeUnit> kindFilter) {
        candidates.stream().filter(kindFilter).forEach(candidate -> {
            for (String candidateKey : lookupKeysFor(candidate)) {
                List<RequestedLookup> requestedLookups = requestedLookupsByNormalizedKey.get(candidateKey);
                if (requestedLookups == null) {
                    continue;
                }
                for (RequestedLookup requestedLookup : requestedLookups) {
                    if (!matchesSource(requestedLookup.alias(), candidate)) {
                        continue;
                    }
                    Set<CodeUnit> matches = requireNonNull(matchesByRequestedName.get(requestedLookup.requestedName()));
                    matches.add(candidate);
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

    private static Collection<IAnalyzer.SourceLookupAlias> requestLookupKeysFor(
            IAnalyzer analyzer, String requestedName) {
        LinkedHashSet<IAnalyzer.SourceLookupAlias> keys = new LinkedHashSet<>();
        analyzer.sourceLookupAliases(requestedName.strip()).stream()
                .map(alias ->
                        alias.withLookupName(normalizeName(alias.lookupName().strip())))
                .forEach(alias -> addKey(keys, alias));
        return keys.stream()
                .filter(alias -> keys.stream().noneMatch(other -> isLessSpecific(alias, other)))
                .toList();
    }

    private static void addKey(Set<IAnalyzer.SourceLookupAlias> keys, IAnalyzer.SourceLookupAlias key) {
        if (!key.lookupName().isBlank()) {
            keys.add(key);
        }
    }

    private static boolean isLessSpecific(IAnalyzer.SourceLookupAlias alias, IAnalyzer.SourceLookupAlias other) {
        if (!alias.lookupName().equals(other.lookupName()) || alias.sourcePathKind() != other.sourcePathKind()) {
            return false;
        }
        if (alias.sourcePathSuffix().isBlank()) {
            return !other.sourcePathSuffix().isBlank();
        }
        if (other.sourcePathSuffix().isBlank()
                || alias.sourcePathSuffix().length() >= other.sourcePathSuffix().length()) {
            return false;
        }
        return other.sourcePathSuffix().endsWith("/" + alias.sourcePathSuffix());
    }

    private static boolean matchesSource(IAnalyzer.SourceLookupAlias alias, CodeUnit candidate) {
        if (alias.sourcePathSuffix().isBlank()) {
            return true;
        }

        String candidatePath =
                switch (alias.sourcePathKind()) {
                    case FILE -> candidate.source().getRelPath().toString().replace('\\', '/');
                    case DIRECTORY -> candidate.source().getParent().toString().replace('\\', '/');
                };
        return candidatePath.equals(alias.sourcePathSuffix()) || candidatePath.endsWith("/" + alias.sourcePathSuffix());
    }

    private static List<String> ambiguityNames(Collection<CodeUnit> matches) {
        boolean hasDuplicateFqName =
                matches.stream()
                        .collect(Collectors.groupingBy(CodeUnit::fqName, Collectors.counting()))
                        .values()
                        .stream()
                        .anyMatch(count -> count > 1);
        return matches.stream()
                .map(candidate -> hasDuplicateFqName
                        ? "%s (%s)".formatted(candidate.fqName(), candidate.source())
                        : candidate.fqName())
                .sorted()
                .toList();
    }

    private static String normalizeName(String name) {
        return name.replace('$', '.');
    }
}
