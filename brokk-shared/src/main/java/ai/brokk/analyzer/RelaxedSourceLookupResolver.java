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
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class RelaxedSourceLookupResolver {
    private static final Pattern GO_RECEIVER_QUALIFIER =
            Pattern.compile("\\(\\*?([A-Za-z_][A-Za-z0-9_]*(?:\\[[^\\]]+])?)\\)");
    private static final Pattern GO_TYPE_ARGUMENTS = Pattern.compile("\\[[^\\]]+\\]");

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
        Map<String, Collection<String>> unresolvedLookupKeysByRequestedName = new LinkedHashMap<>();

        for (String requestedName : requestedNames) {
            if (results.containsKey(requestedName)) {
                continue;
            }

            Collection<String> lookupKeys = requestLookupKeysFor(requestedName);
            var exactMatch = lookupKeys.stream()
                    .flatMap(name -> analyzer.getDefinitions(name).stream())
                    .filter(kindFilter)
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

        Map<String, List<String>> requestedNamesByNormalizedKey = new LinkedHashMap<>();
        for (var entry : unresolvedLookupKeysByRequestedName.entrySet()) {
            String requestedName = entry.getKey();
            entry.getValue().forEach(lookupKey -> requestedNamesByNormalizedKey
                    .computeIfAbsent(lookupKey, ignored -> new ArrayList<>())
                    .add(requestedName));
        }

        Map<String, Map<String, CodeUnit>> matchesByRequestedName = new LinkedHashMap<>();
        unresolvedLookupKeysByRequestedName
                .keySet()
                .forEach(name -> matchesByRequestedName.put(name, new LinkedHashMap<>()));

        List<CodeUnit> declarations = analyzer.getAllDeclarations();
        addRelaxedMatches(matchesByRequestedName, requestedNamesByNormalizedKey, declarations, kindFilter);
        declarations.stream()
                .filter(CodeUnit::isClass)
                .forEach(cls -> addRelaxedMatches(
                        matchesByRequestedName,
                        requestedNamesByNormalizedKey,
                        analyzer.getMembersInClass(cls),
                        kindFilter));

        for (String requestedName : matchesByRequestedName.keySet()) {
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

    private static Collection<String> requestLookupKeysFor(String requestedName) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String normalizedName = normalizeName(requestedName.strip());
        addKey(keys, normalizedName);
        if (normalizedName.isEmpty()) {
            return keys;
        }

        String goReceiverName = normalizeGoReceiverSyntax(normalizedName);
        addKey(keys, goReceiverName);
        addKey(keys, stripGoTypeArguments(goReceiverName));

        addGoPathAliases(keys, normalizedName);
        addGoPathAliases(keys, goReceiverName);
        addGoPathAliases(keys, stripGoTypeArguments(goReceiverName));

        return keys;
    }

    private static void addGoPathAliases(Set<String> keys, String name) {
        String unixName = name.replace('\\', '/');
        int separatorIdx = unixName.indexOf('/');
        while (separatorIdx >= 0) {
            if (separatorIdx + 1 < unixName.length()) {
                addGoPathSuffixAliases(keys, unixName.substring(separatorIdx + 1));
            }
            separatorIdx = unixName.indexOf('/', separatorIdx + 1);
        }
    }

    private static void addGoPathSuffixAliases(Set<String> keys, String pathSuffix) {
        addKey(keys, pathSuffix);

        String dottedSuffix = pathSuffix.replace('/', '.');
        addKey(keys, dottedSuffix);
        addGoPackageFileAlias(keys, dottedSuffix);
    }

    private static void addGoPackageFileAlias(Set<String> keys, String dottedSuffix) {
        var parts = List.of(dottedSuffix.split("\\."));
        if (parts.size() < 4) {
            return;
        }

        var aliasParts = new ArrayList<String>(parts.size() - 1);
        aliasParts.add(parts.getFirst());
        aliasParts.addAll(parts.subList(2, parts.size()));
        addKey(keys, String.join(".", aliasParts));
    }

    private static String normalizeGoReceiverSyntax(String name) {
        return GO_RECEIVER_QUALIFIER.matcher(name).replaceAll("$1");
    }

    private static String stripGoTypeArguments(String name) {
        return GO_TYPE_ARGUMENTS.matcher(name).replaceAll("");
    }

    private static void addKey(Set<String> keys, String key) {
        if (!key.isBlank()) {
            keys.add(key);
        }
    }

    private static String normalizeName(String name) {
        return name.replace('$', '.');
    }
}
