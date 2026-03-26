package ai.brokk.analyzer;

import ai.brokk.analyzer.IAnalyzer.Range;
import ai.brokk.analyzer.TreeSitterAnalyzer.CodeUnitProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Mutable accumulator for per-file analysis state. */
public class FileAnalysisAccumulator {
    private final Set<CodeUnit> topLevelCUs = new LinkedHashSet<>();
    private final Map<CodeUnit, Set<CodeUnit>> children = new LinkedHashMap<>();
    private final Map<CodeUnit, CodeUnit> childToParent = new HashMap<>();
    private final Map<CodeUnit, Set<String>> signatures = new LinkedHashMap<>();
    private final Map<CodeUnit, Set<Range>> sourceRanges = new LinkedHashMap<>();
    private final Map<CodeUnit, Boolean> hasBody = new HashMap<>();
    private final Map<CodeUnit, Boolean> isTypeAlias = new HashMap<>();
    private final Map<String, Set<CodeUnit>> codeUnitsBySymbol = new HashMap<>();
    private final Map<String, CodeUnit> cuByFqName = new HashMap<>();
    private final Map<CodeUnit, Set<String>> lookupKeys = new HashMap<>();
    private final List<ImportInfo> importInfos = new ArrayList<>();

    public FileAnalysisAccumulator() {}

    /**
     * Adds a CodeUnit to the top-level list.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addTopLevel(CodeUnit cu) {
        topLevelCUs.add(cu);
        addLookupKey(cu.fqName(), cu);
        return this;
    }

    /**
     * Adds a parent-child relationship.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addChild(CodeUnit parent, CodeUnit child) {
        children.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(child);
        childToParent.put(child, parent);
        addLookupKey(child.fqName(), child);
        return this;
    }

    /**
     * Adds a signature to a CodeUnit.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addSignature(CodeUnit cu, String signature) {
        signatures.computeIfAbsent(cu, k -> new LinkedHashSet<>()).add(signature);
        return this;
    }

    /**
     * Adds a source range to a CodeUnit.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addRange(CodeUnit cu, Range range) {
        sourceRanges.computeIfAbsent(cu, k -> new LinkedHashSet<>()).add(range);
        return this;
    }

    /**
     * Sets whether the CodeUnit has a body.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator setHasBody(CodeUnit cu, boolean body) {
        hasBody.put(cu, body);
        return this;
    }

    /**
     * Sets whether the CodeUnit is a type alias.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator setIsTypeAlias(CodeUnit cu, boolean typeAlias) {
        isTypeAlias.put(cu, typeAlias);
        return this;
    }

    /**
     * Indexes a CodeUnit by a symbol.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addSymbolIndex(String symbol, CodeUnit cu) {
        codeUnitsBySymbol.computeIfAbsent(symbol, k -> new HashSet<>()).add(cu);
        return this;
    }

    /**
     * Registers an import found during analysis.
     */
    public void registerImport(ImportInfo info) {
        importInfos.add(info);
    }

    /**
     * Returns an unmodifiable list of imports found in the file.
     */
    public List<ImportInfo> importInfos() {
        return List.copyOf(importInfos);
    }

    /**
     * Removes a CodeUnit and its descendants from all internal maps.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator remove(CodeUnit cu) {
        Set<CodeUnit> kids = children.remove(cu);
        if (kids != null) {
            for (CodeUnit child : new ArrayList<>(kids)) {
                remove(child);
            }
        }

        CodeUnit parent = childToParent.remove(cu);
        if (parent != null) {
            Set<CodeUnit> siblings = children.get(parent);
            if (siblings != null) {
                siblings.remove(cu);
            }
        }

        topLevelCUs.remove(cu);
        signatures.remove(cu);
        sourceRanges.remove(cu);
        hasBody.remove(cu);
        isTypeAlias.remove(cu);
        removeFromSymbolIndex(cu);

        Set<String> keys = lookupKeys.remove(cu);
        if (keys != null) {
            for (String key : keys) {
                cuByFqName.remove(key);
            }
        }

        return this;
    }

    /**
     * Registers a lookup key for a CodeUnit.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addLookupKey(String lookupKey, CodeUnit cu) {
        cuByFqName.put(lookupKey, cu);
        lookupKeys.computeIfAbsent(cu, k -> new LinkedHashSet<>()).add(lookupKey);
        return this;
    }

    /**
     * Registers a CodeUnit in the symbol index.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator registerCodeUnit(CodeUnit cu) {
        addSymbolIndex(cu.identifier(), cu);
        if (!cu.shortName().equals(cu.identifier())) {
            addSymbolIndex(cu.shortName(), cu);
        }
        return this;
    }

    private void removeFromSymbolIndex(CodeUnit cu) {
        String[] keys = {cu.identifier(), cu.shortName()};
        for (String key : keys) {
            Set<CodeUnit> set = codeUnitsBySymbol.get(key);
            if (set != null) {
                set.remove(cu);
                if (set.isEmpty()) {
                    codeUnitsBySymbol.remove(key);
                }
            }
        }
    }

    public @Nullable CodeUnit getByFqName(String key) {
        return cuByFqName.get(key);
    }

    public @Nullable CodeUnit findTopLevelDuplicate(CodeUnit cu) {
        return topLevelCUs.stream().filter(t -> t.equals(cu)).findFirst().orElse(null);
    }

    public @Nullable CodeUnit findTopLevelCrossKindDuplicate(CodeUnit cu) {
        return topLevelCUs.stream()
                .filter(t -> t.fqName().equals(cu.fqName()))
                .findFirst()
                .orElse(null);
    }

    public void replaceTopLevelCodeUnit(CodeUnit oldCu, CodeUnit newCu) {
        remove(oldCu);
        registerCodeUnit(newCu);
        addTopLevel(newCu);
    }

    public @Nullable CodeUnit findChildDuplicate(CodeUnit parent, CodeUnit cu) {
        return getChildren(parent).stream()
                .filter(t -> t.equals(cu))
                .findFirst()
                .orElse(null);
    }

    public @Nullable CodeUnit findChildCrossKindDuplicate(CodeUnit parent, CodeUnit cu) {
        return getChildren(parent).stream()
                .filter(t -> t.fqName().equals(cu.fqName()))
                .findFirst()
                .orElse(null);
    }

    public void replaceChildCodeUnit(CodeUnit parent, CodeUnit oldCu, CodeUnit newCu) {
        remove(oldCu);
        registerCodeUnit(newCu);
        addChild(parent, newCu);
    }

    public void detachChildren(CodeUnit cu) {
        children.remove(cu);
    }

    public List<String> getLookupKeys(CodeUnit cu) {
        Set<String> keys = lookupKeys.get(cu);
        return keys == null ? List.of() : List.copyOf(keys);
    }

    public boolean getHasBody(CodeUnit cu, boolean defaultValue) {
        return hasBody.getOrDefault(cu, defaultValue);
    }

    public List<CodeUnit> getChildren(CodeUnit cu) {
        Set<CodeUnit> kids = children.get(cu);
        return kids == null ? List.of() : List.copyOf(kids);
    }

    public List<String> getSignatures(CodeUnit cu) {
        Set<String> sigs = signatures.get(cu);
        return sigs == null ? List.of() : List.copyOf(sigs);
    }

    public List<Range> getRanges(CodeUnit cu) {
        Set<Range> ranges = sourceRanges.get(cu);
        return ranges == null ? List.of() : List.copyOf(ranges);
    }

    public Map<String, Set<CodeUnit>> codeUnitsBySymbol() {
        return Map.copyOf(codeUnitsBySymbol);
    }

    public Map<CodeUnit, CodeUnitProperties> toCodeUnitProperties() {
        Map<CodeUnit, CodeUnitProperties> localStates = new HashMap<>();
        var unionKeys = new HashSet<CodeUnit>();
        unionKeys.addAll(children.keySet());
        children.values().forEach(unionKeys::addAll);
        unionKeys.addAll(signatures.keySet());
        unionKeys.addAll(sourceRanges.keySet());
        unionKeys.addAll(isTypeAlias.keySet());
        for (var cu : unionKeys) {
            var kids = children.getOrDefault(cu, Collections.emptySet());
            var sigs = signatures.getOrDefault(cu, Collections.emptySet());
            var rngs = sourceRanges.getOrDefault(cu, Collections.emptySet());
            localStates.put(
                    cu,
                    new CodeUnitProperties(
                            Collections.unmodifiableSequencedSet(new LinkedHashSet<>(kids)),
                            Collections.unmodifiableSequencedSet(new LinkedHashSet<>(sigs)),
                            Collections.unmodifiableSequencedSet(new LinkedHashSet<>(rngs)),
                            hasBody.getOrDefault(cu, false),
                            isTypeAlias.getOrDefault(cu, false)));
        }
        return localStates;
    }

    public Map<String, CodeUnit> cuByFqName() {
        return Map.copyOf(cuByFqName);
    }

    public List<CodeUnit> topLevelCUs() {
        return List.copyOf(topLevelCUs);
    }

    public Map<CodeUnit, Set<CodeUnit>> children() {
        Map<CodeUnit, Set<CodeUnit>> copy = new HashMap<>();
        children.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(copy);
    }
}
