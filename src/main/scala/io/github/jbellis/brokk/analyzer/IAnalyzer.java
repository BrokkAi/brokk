package io.github.jbellis.brokk.analyzer;

import scala.Tuple2;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public interface IAnalyzer {
    // Basics
    default boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    default boolean isCpg() {
        throw new UnsupportedOperationException();
    }

    // CPG methods
    default List<CodeUnit> getUses(String fqName) {
        throw new UnsupportedOperationException();
    }

    default List<Tuple2<CodeUnit, Double>> getPagerank(Map<String, Double> seedClassWeights,
                                                       int k,
                                                       boolean reversed) {
        throw new UnsupportedOperationException();
    }

    default Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        throw new UnsupportedOperationException();
    }

    default Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        throw new UnsupportedOperationException();
    }

    // Summarization

    /**
     * return a summary of the given type or method
     */
    default Optional<String> getSkeleton(String fqName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns just the class signature and field declarations, without method details.
     * Used in symbol usages lookup. (Show the "header" of the class that uses the referenced symbol in a field declaration.)
     */
    default Optional<String> getSkeletonHeader(String className) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the source code for a given method name. If multiple methods match (e.g. overloads),
     * their source code snippets are concatenated (separated by newlines). If none match, returns None.
     */
    default Optional<String> getMethodSource(String fqName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the source code for the entire given class.
     * If the class is partial or has multiple definitions, this typically returns the primary definition.
     */
    default String getClassSource(String fqcn) {
        throw new UnsupportedOperationException();
    }

    default Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        Map<CodeUnit, String> skeletons = new HashMap<>();
        for (CodeUnit symbol : getDeclarationsInFile(file)) {
            Optional<String> skelOpt = getSkeleton(symbol.fqName());
            skelOpt.ifPresent(s -> skeletons.put(symbol, s));
        }
        return skeletons;
    }

    default List<CodeUnit> getMembersInClass(String fqClass) {
        throw new UnsupportedOperationException();
    }

    // Everything else
    default List<CodeUnit> getAllDeclarations() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets all classes in a given file.
     */
    default Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        throw new UnsupportedOperationException();
    }

    default Optional<ProjectFile> getFileFor(String fqName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Finds a single CodeUnit definition matching the exact symbol name.
     *
     * @param fqName The exact, case-sensitive FQ name of the class, method, or field.
     *               Symbols are checked in that order, so if you have a field and a method with the same name,
     *               the method will be returned.
     * @return An Optional containing the CodeUnit if exactly one match is found, otherwise empty.
     */
    default Optional<CodeUnit> getDefinition(String fqName) {
        throw new UnsupportedOperationException();
    }

    default List<CodeUnit> searchDefinitions(String pattern) {
        throw new UnsupportedOperationException();
    }

    /** language-specific: given a CU return its immediate children */
    default List<CodeUnit> directChildren(CodeUnit cu) { return List.of(); }

    private static void addShort(String full, Set<String> out) {
        if (full == null || full.isEmpty()) return;
        var lastDot    = full.lastIndexOf('.');
        var lastDollar = full.lastIndexOf('$');
        var idx        = Math.max(lastDot, lastDollar);
        var shortName  = idx >= 0 ? full.substring(idx + 1) : full;
        if (!shortName.isEmpty()) out.add(shortName);
    }

    /**
     * Gets a set of relevant symbol names (classes, methods, fields) defined within the given source CodeUnits.
     *
     * Almost all String representations in the Analyzer are fully-qualified, but these are not! In CodeUnit
     * terms, this returns identifiers -- just the symbol name itself, no class or package hierarchy.
     *
     * @param sources source files or classes to analyse
     * @return unqualified symbol names found within the sources
     */
    default Set<String> getSymbols(Set<CodeUnit> sources) {
        var visited = new HashSet<CodeUnit>();
        var work    = new ArrayDeque<>(sources);
        var symbols = ConcurrentHashMap.<String>newKeySet();

        while (!work.isEmpty()) {
            var cu = work.poll();
            if (!visited.add(cu)) continue;

            // 1) add the unit’s own short name
            addShort(cu.shortName(), symbols);

            // 2) recurse
            work.addAll(directChildren(cu));
        }
        return symbols;
    }

    /**
     * Locates the source file and line range for the given fully-qualified method name.
     * The {@code paramNames} list contains the *parameter variable names* (not types).
     * If there is only a single match, or exactly one match with matching param names, return it.
     * Otherwise throw {@code SymbolNotFoundException} or {@code SymbolAmbiguousException}.
     *
     * TODO this should return an Optional
     */
    default FunctionLocation getFunctionLocation(String fqMethodName, List<String> paramNames) {
        throw new UnsupportedOperationException();
    }

    /**
     * Container for a function’s location and current source text.
     */
    record FunctionLocation(ProjectFile file, int startLine, int endLine, String code) {}
}
