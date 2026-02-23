package ai.brokk.analyzer;

import ai.brokk.analyzer.IAnalyzer.Range;
import ai.brokk.analyzer.TreeSitterAnalyzer.CodeUnitProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Mutable accumulator for per-file analysis state.
 */
public class FileAnalysisAccumulator {
    private final List<CodeUnit> topLevelCUs = new ArrayList<>();
    private final Map<CodeUnit, List<CodeUnit>> children = new HashMap<>();
    private final Map<CodeUnit, List<String>> signatures = new HashMap<>();
    private final Map<CodeUnit, List<Range>> sourceRanges = new HashMap<>();
    private final Map<CodeUnit, Boolean> hasBody = new HashMap<>();
    private final Map<String, Set<CodeUnit>> codeUnitsBySymbol = new HashMap<>();
    private final Map<String, CodeUnit> cuByFqName = new HashMap<>();
    private final Map<CodeUnit, List<String>> lookupKeys = new HashMap<>();

    public FileAnalysisAccumulator() {}

    /**
     * Adds a CodeUnit to the top-level list. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addTopLevel(CodeUnit cu) {
        if (!topLevelCUs.contains(cu)) {
            topLevelCUs.add(cu);
        }
        addLookupKey(cu.fqName(), cu);
        return this;
    }

    /**
     * Adds a parent-child relationship. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addChild(CodeUnit parent, CodeUnit child) {
        List<CodeUnit> kids = children.computeIfAbsent(parent, k -> new ArrayList<>());
        if (!kids.contains(child)) {
            kids.add(child);
        }
        addLookupKey(child.fqName(), child);
        return this;
    }

    /**
     * Adds a signature to a CodeUnit. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addSignature(CodeUnit cu, String signature) {
        List<String> sigs = signatures.computeIfAbsent(cu, k -> new ArrayList<>());
        if (!sigs.contains(signature)) {
            sigs.add(signature);
        }
        return this;
    }

    /**
     * Adds a source range to a CodeUnit. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addRange(CodeUnit cu, Range range) {
        sourceRanges.computeIfAbsent(cu, k -> new ArrayList<>()).add(range);
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
        List<CodeUnit> kids = children.remove(cu);
        if (kids != null) {
            for (CodeUnit child : new ArrayList<>(kids)) {
                remove(child);
            }
        }
        topLevelCUs.remove(cu);
        signatures.remove(cu);
        sourceRanges.remove(cu);
        hasBody.remove(cu);
        removeFromSymbolIndex(cu);

        List<String> keys = lookupKeys.remove(cu);
        if (keys != null) {
            for (String key : keys) {
                cuByFqName.remove(key);
            }
        }

        for (List<CodeUnit> siblingList : children.values()) {
            siblingList.remove(cu);
        }
        return this;
    }

    /**
     * Registers a lookup key for a CodeUnit. Mutations should only be performed via these APIs.
     * @return this accumulator for chaining.
     */
    public FileAnalysisAccumulator addLookupKey(String lookupKey, CodeUnit cu) {
        cuByFqName.put(lookupKey, cu);
        lookupKeys.computeIfAbsent(cu, k -> new ArrayList<>()).add(lookupKey);
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

    public List<CodeUnit> getChildren(CodeUnit cu) {
        return Collections.unmodifiableList(children.getOrDefault(cu, List.of()));
    }

    public List<String> getSignatures(CodeUnit cu) {
        return Collections.unmodifiableList(signatures.getOrDefault(cu, List.of()));
    }

    public List<Range> getRanges(CodeUnit cu) {
        return Collections.unmodifiableList(sourceRanges.getOrDefault(cu, List.of()));
    }

    public List<String> getLookupKeys(CodeUnit cu) {
        return Collections.unmodifiableList(lookupKeys.getOrDefault(cu, List.of()));
    }

    public void detachChildren(CodeUnit cu) {
        children.remove(cu);
    }

    public Map<CodeUnit, CodeUnitProperties> toCodeUnitProperties() {
        Map<CodeUnit, CodeUnitProperties> localStates = new HashMap<>();
        var unionKeys = new HashSet<CodeUnit>();
        unionKeys.addAll(children.keySet());
        children.values().forEach(unionKeys::addAll);
        unionKeys.addAll(signatures.keySet());
        unionKeys.addAll(sourceRanges.keySet());
        for (var cu : unionKeys) {
            var kids = children.getOrDefault(cu, List.of());
            var sigs = signatures.getOrDefault(cu, List.of());
            var rngs = sourceRanges.getOrDefault(cu, List.of());
            localStates.put(
                    cu,
                    new CodeUnitProperties(
                            List.copyOf(kids), List.copyOf(sigs), List.copyOf(rngs), hasBody.getOrDefault(cu, false)));
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
    public Map<CodeUnit, List<CodeUnit>> children() {
        Map<CodeUnit, List<CodeUnit>> copy = new HashMap<>();
        children.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an immutable snapshot of signatures associated with each CodeUnit.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<CodeUnit, List<String>> signatures() {
        Map<CodeUnit, List<String>> copy = new HashMap<>();
        signatures.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an immutable snapshot of source ranges for each CodeUnit.
     * Mutations should be performed via accumulator APIs.
     */
    public Map<CodeUnit, List<Range>> sourceRanges() {
        Map<CodeUnit, List<Range>> copy = new HashMap<>();
        sourceRanges.forEach((k, v) -> copy.put(k, List.copyOf(v)));
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
        List<CodeUnit> kids = children.get(parent);
        if (kids == null) return null;
        return kids.stream()
                .filter(existing -> sameLogicalIdentity(existing, child))
                .findFirst()
                .orElse(null);
    }

    public @Nullable CodeUnit findChildCrossKindDuplicate(CodeUnit parent, CodeUnit child) {
        List<CodeUnit> kids = children.get(parent);
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
