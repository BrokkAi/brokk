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
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Mutable accumulator for per-file analysis state.
 */
public class FileAnalysisAccumulator {
    private final Set<CodeUnit> topLevelCUs = new LinkedHashSet<>();
    private final Map<CodeUnit, Set<CodeUnit>> children = new LinkedHashMap<>();
    private final Map<CodeUnit, CodeUnit> childToParent = new HashMap<>();
    private final Map<CodeUnit, Set<String>> signatures = new LinkedHashMap<>();
    private final Map<CodeUnit, Set<Range>> sourceRanges = new LinkedHashMap<>();
    private final Map<CodeUnit, Boolean> hasBody = new HashMap<>();
    private final Map<String, Set<CodeUnit>> codeUnitsBySymbol = new HashMap<>();
    private final Map<String, CodeUnit> cuByFqName = new HashMap<>();
    private final Map<CodeUnit, Set<String>> lookupKeys = new HashMap<>();

    public FileAnalysisAccumulator() {}

    /**
     * Adds a CodeUnit to the top-level list. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addTopLevel(CodeUnit cu) {
        topLevelCUs.add(cu);
        addLookupKey(cu.fqName(), cu);
        return this;
    }

    /**
     * Adds a parent-child relationship. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addChild(CodeUnit parent, CodeUnit child) {
        children.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(child);
        childToParent.put(child, parent);
        addLookupKey(child.fqName(), child);
        return this;
    }

    /**
     * Adds a signature to a CodeUnit. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addSignature(CodeUnit cu, String signature) {
        signatures.computeIfAbsent(cu, k -> new LinkedHashSet<>()).add(signature);
        return this;
    }

    /**
     * Adds a source range to a CodeUnit. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addRange(CodeUnit cu, Range range) {
        sourceRanges.computeIfAbsent(cu, k -> new LinkedHashSet<>()).add(range);
        return this;
    }

    /**
     * Sets whether the CodeUnit has a body. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator setHasBody(CodeUnit cu, boolean body) {
        hasBody.put(cu, body);
        return this;
    }

    /**
     * Indexes a CodeUnit by a symbol. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addSymbolIndex(String symbol, CodeUnit cu) {
        codeUnitsBySymbol.computeIfAbsent(symbol, k -> new HashSet<>()).add(cu);
        return this;
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
     * Registers a lookup key for a CodeUnit. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addLookupKey(String lookupKey, CodeUnit cu) {
        cuByFqName.put(lookupKey, cu);
        lookupKeys.computeIfAbsent(cu, k -> new LinkedHashSet<>()).add(lookupKey);
        return this;
    }

    /**
     * Replaces an existing top-level CodeUnit with a new one, ensuring descendants and symbol indices are updated.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator replaceTopLevelCodeUnit(CodeUnit existing, CodeUnit replacement) {
        remove(existing);
        addTopLevel(replacement);
        registerCodeUnit(replacement);
        return this;
    }

    /**
     * Replaces an existing child CodeUnit with a new one under the same parent.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator replaceChildCodeUnit(CodeUnit parent, CodeUnit existing, CodeUnit replacement) {
        remove(existing);
        addChild(parent, replacement);
        registerCodeUnit(replacement);
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

    /**
     * Returns an immutable mapping of FQNs (or signature-augmented lookup keys) to CodeUnits.
     * Mutations should be performed via accumulator APIs.
     */
    public @Nullable CodeUnit getByFqName(String key) {
        return cuByFqName.get(key);
    }

    public boolean getHasBody(CodeUnit cu, boolean defaultValue) {
        return hasBody.getOrDefault(cu, defaultValue);
    }

    /**
     * Returns a view of the children for the given CodeUnit.
     */
    public List<CodeUnit> getChildren(CodeUnit cu) {
        Set<CodeUnit> kids = children.get(cu);
        return kids == null ? List.of() : List.copyOf(kids);
    }

    /**
     * Returns a view of the signatures for the given CodeUnit.
     */
    public List<String> getSignatures(CodeUnit cu) {
        Set<String> sigs = signatures.get(cu);
        return sigs == null ? List.of() : List.copyOf(sigs);
    }

    /**
     * Returns a view of the ranges for the given CodeUnit.
     */
    public List<Range> getRanges(CodeUnit cu) {
        Set<Range> ranges = sourceRanges.get(cu);
        return ranges == null ? List.of() : List.copyOf(ranges);
    }

    public List<String> getLookupKeys(CodeUnit cu) {
        Set<String> keys = lookupKeys.get(cu);
        return keys == null ? List.of() : List.copyOf(keys);
    }

    public void detachChildren(CodeUnit cu) {
        Set<CodeUnit> kids = children.remove(cu);
        if (kids != null) {
            for (CodeUnit child : kids) {
                CodeUnit parent = childToParent.get(child);
                if (Objects.equals(parent, cu)) {
                    childToParent.remove(child);
                }
            }
        }
    }

    public Map<CodeUnit, CodeUnitProperties> toCodeUnitProperties() {
        Map<CodeUnit, CodeUnitProperties> localStates = new HashMap<>();
        var unionKeys = new HashSet<CodeUnit>();
        unionKeys.addAll(children.keySet());
        children.values().forEach(unionKeys::addAll);
        unionKeys.addAll(signatures.keySet());
        unionKeys.addAll(sourceRanges.keySet());
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
                            hasBody.getOrDefault(cu, false)));
        }
        return localStates;
    }

    public Map<String, CodeUnit> cuByFqName() {
        return Map.copyOf(cuByFqName);
    }

    /**
     * Returns an immutable list of top-level CodeUnits for the file.
     * Mutations should be performed via accumulator APIs.
     */
    public List<CodeUnit> topLevelCUs() {
        return List.copyOf(topLevelCUs);
    }

    /**
     * Returns an immutable snapshot of parent-child relationships.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<CodeUnit, Set<CodeUnit>> children() {
        Map<CodeUnit, Set<CodeUnit>> copy = new HashMap<>();
        children.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an immutable snapshot of signatures associated with each CodeUnit.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<CodeUnit, Set<String>> signatures() {
        Map<CodeUnit, Set<String>> copy = new HashMap<>();
        signatures.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an immutable snapshot of source ranges for each CodeUnit.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<CodeUnit, Set<Range>> sourceRanges() {
        Map<CodeUnit, Set<Range>> copy = new HashMap<>();
        sourceRanges.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an immutable mapping of CodeUnits to their body-presence flag.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<CodeUnit, Boolean> hasBody() {
        return Map.copyOf(hasBody);
    }

    /**
     * Returns an immutable snapshot of the symbol index.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<String, Set<CodeUnit>> codeUnitsBySymbol() {
        Map<String, Set<CodeUnit>> copy = new HashMap<>();
        codeUnitsBySymbol.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an immutable snapshot of all lookup keys (FQNs/signatures) registered for each CodeUnit.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<CodeUnit, List<String>> lookupKeys() {
        Map<CodeUnit, List<String>> copy = new HashMap<>();
        lookupKeys.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    public @Nullable CodeUnit findTopLevelDuplicate(CodeUnit cu) {
        return topLevelCUs.stream()
                .filter(existing -> sameLogicalIdentity(existing, cu))
                .findFirst()
                .orElse(null);
    }

    public @Nullable CodeUnit findTopLevelCrossKindDuplicate(CodeUnit cu) {
        return topLevelCUs.stream()
                .filter(existing -> existing.fqName().equals(cu.fqName()) && existing.kind() != cu.kind())
                .findFirst()
                .orElse(null);
    }

    public @Nullable CodeUnit findChildDuplicate(CodeUnit parent, CodeUnit child) {
        Set<CodeUnit> kids = children.get(parent);
        if (kids == null) return null;
        return kids.stream()
                .filter(existing -> sameLogicalIdentity(existing, child))
                .findFirst()
                .orElse(null);
    }

    public @Nullable CodeUnit findChildCrossKindDuplicate(CodeUnit parent, CodeUnit child) {
        Set<CodeUnit> kids = children.get(parent);
        if (kids == null) return null;
        return kids.stream()
                .filter(existing -> existing.fqName().equals(child.fqName()) && existing.kind() != child.kind())
                .findFirst()
                .orElse(null);
    }

    private static boolean sameLogicalIdentity(CodeUnit left, CodeUnit right) {
        return left.kind() == right.kind()
                && Objects.equals(left.fqName(), right.fqName())
                && Objects.equals(left.signature(), right.signature());
    }
}
