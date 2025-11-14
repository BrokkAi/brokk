package ai.brokk;

import ai.brokk.analyzer.*;
import ai.brokk.analyzer.CallSite;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import ai.brokk.analyzer.SourceCodeProvider;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * String-based convenience methods for analyzer capabilities.
 * If you already have a CodeUnit, call the provider directly.
 */
public class AnalyzerUtil {
    private static final Logger logger = LogManager.getLogger(AnalyzerUtil.class);

    public static List<CodeWithSource> processUsages(IAnalyzer analyzer, List<CodeUnit> uses) {
        List<CodeWithSource> results = new ArrayList<>();

        var maybeSourceCodeProvider = analyzer.as(SourceCodeProvider.class);
        if (maybeSourceCodeProvider.isEmpty()) {
            logger.warn("Analyzer ({}) does not provide source code, skipping", analyzer.getClass());
        }
        maybeSourceCodeProvider.ifPresent(sourceCodeProvider -> {
            var methodUses = uses.stream().filter(CodeUnit::isFunction).sorted().toList();
            for (var cu : methodUses) {
                var source = sourceCodeProvider.getMethodSource(cu, true);
                if (source.isPresent()) {
                    results.add(new CodeWithSource(source.get(), cu));
                } else {
                    logger.warn("Unable to obtain source code for method use by {}", cu.fqName());
                }
            }
        });

        var maybeSkeletonProvider = analyzer.as(SkeletonProvider.class);
        if (maybeSkeletonProvider.isEmpty()) {
            logger.warn("Analyzer ({}) does not provide skeletons, skipping", analyzer.getClass());
        }
        maybeSkeletonProvider.ifPresent(skeletonProvider -> {
            var typeUses = uses.stream().filter(CodeUnit::isClass).sorted().toList();
            for (var cu : typeUses) {
                var skeletonHeader = skeletonProvider.getSkeletonHeader(cu);
                skeletonHeader.ifPresent(header -> results.add(new CodeWithSource(header, cu)));
            }
        });

        return results;
    }

    public static Set<CodeUnit> coalesceInnerClasses(Set<CodeUnit> classes) {
        return classes.stream()
                .filter(cu -> {
                    var name = cu.fqName();
                    if (!name.contains("$")) return true;
                    var parent = name.substring(0, name.indexOf('$'));
                    return classes.stream().noneMatch(other -> other.fqName().equals(parent));
                })
                .collect(Collectors.toSet());
    }

    public static Set<CodeUnit> testFilesToCodeUnits(IAnalyzer analyzer, Collection<ProjectFile> files) {
        var classUnitsInTestFiles = files.stream()
                .flatMap(testFile -> analyzer.getTopLevelDeclarations(testFile).stream())
                .filter(CodeUnit::isClass)
                .collect(Collectors.toSet());

        return AnalyzerUtil.coalesceInnerClasses(classUnitsInTestFiles);
    }

    private record StackEntry(String method, int depth) {}

    /** Helper method to recursively format the call graph (both callers and callees) */
    public static String formatCallGraph(
            Map<String, List<CallSite>> callgraph, String rootMethodName, boolean isCallerGraph) {
        var result = new StringBuilder();
        String arrow = isCallerGraph ? "<-" : "->";

        var visited = new HashSet<String>();
        var stack = new ArrayDeque<>(List.of(new StackEntry(rootMethodName, 0)));

        // Process each method
        result.append(rootMethodName).append("\n");
        while (!stack.isEmpty()) {
            var entry = stack.pop();
            var sites = callgraph.get(entry.method);
            if (sites == null) {
                continue;
            }
            sites.stream().sorted().forEach(site -> {
                result.append("""
            %s %s
            ```
            %s
            ```
            """
                        .indent(2 * entry.depth)
                        .formatted(arrow, site.target().fqName(), site.sourceLine()));

                // Process this method's callers/callees (if not already processed)
                if (visited.add(site.target().fqName())) {
                    stack.push(new StackEntry(site.target().fqName(), entry.depth + 1));
                }
            });
        }

        return result.toString();
    }

    /**
     * Get skeleton for a symbol by fully qualified name.
     */
    public static Optional<String> getSkeleton(IAnalyzer analyzer, String fqName) {
        return analyzer.getDefinition(fqName)
                .flatMap(cu -> analyzer.as(SkeletonProvider.class).flatMap(skp -> skp.getSkeleton(cu)));
    }

    /**
     * Get skeleton header (class signature + fields without method bodies) for a class by name.
     */
    public static Optional<String> getSkeletonHeader(IAnalyzer analyzer, String className) {
        return analyzer.getDefinition(className)
                .flatMap(cu -> analyzer.as(SkeletonProvider.class).flatMap(skp -> skp.getSkeletonHeader(cu)));
    }

    /**
     * Get all source code versions for a method (handles overloads) by fully qualified name.
     */
    public static Set<String> getMethodSources(IAnalyzer analyzer, String fqName, boolean includeComments) {
        return analyzer.getDefinition(fqName)
                .filter(CodeUnit::isFunction)
                .flatMap(cu ->
                        analyzer.as(SourceCodeProvider.class).map(scp -> scp.getMethodSources(cu, includeComments)))
                .orElse(Collections.emptySet());
    }

    /**
     * Get source code for a method by fully qualified name. If multiple versions exist (overloads), they are
     * concatenated.
     */
    public static Optional<String> getMethodSource(IAnalyzer analyzer, String fqName, boolean includeComments) {
        return analyzer.getDefinition(fqName).filter(CodeUnit::isFunction).flatMap(cu -> analyzer.as(
                        SourceCodeProvider.class)
                .flatMap(scp -> scp.getMethodSource(cu, includeComments)));
    }

    /**
     * Get source code for a class by fully qualified name.
     */
    public static Optional<String> getClassSource(IAnalyzer analyzer, String fqcn, boolean includeComments) {
        return analyzer.getDefinition(fqcn).filter(CodeUnit::isClass).flatMap(cu -> analyzer.as(
                        SourceCodeProvider.class)
                .flatMap(scp -> scp.getClassSource(cu, includeComments)));
    }

    /**
     * Get call graph showing what calls the given method.
     */
    public static Map<String, List<CallSite>> getCallgraphTo(IAnalyzer analyzer, String methodName, int depth) {
        return analyzer.getDefinition(methodName)
                .filter(CodeUnit::isFunction)
                .flatMap(cu -> analyzer.as(CallGraphProvider.class).map(cgp -> cgp.getCallgraphTo(cu, depth)))
                .orElse(Collections.emptyMap());
    }

    /**
     * Get call graph showing what the given method calls.
     */
    public static Map<String, List<CallSite>> getCallgraphFrom(IAnalyzer analyzer, String methodName, int depth) {
        return analyzer.getDefinition(methodName)
                .filter(CodeUnit::isFunction)
                .flatMap(cu -> analyzer.as(CallGraphProvider.class).map(cgp -> cgp.getCallgraphFrom(cu, depth)))
                .orElse(Collections.emptyMap());
    }

    /**
     * Get members (methods, fields, nested classes) of a class by fully qualified name.
     */
    public static List<CodeUnit> getMembersInClass(IAnalyzer analyzer, String fqClass) {
        return analyzer.getDefinition(fqClass)
                .filter(CodeUnit::isClass)
                .map(analyzer::getMembersInClass)
                .orElse(List.of());
    }

    /**
     * Get the file containing the definition of a symbol by fully qualified name.
     */
    public static Optional<ProjectFile> getFileFor(IAnalyzer analyzer, String fqName) {
        return analyzer.getDefinition(fqName).map(analyzer::getFileFor).flatMap(f -> f);
    }

    /**
     * Extract the class/module/type name from a method/member reference.
     * This is a heuristic method that uses language-specific parsing.
     */
    public static Optional<String> extractClassName(IAnalyzer analyzer, String reference) {
        return analyzer.extractClassName(reference);
    }

    public record CodeWithSource(String code, CodeUnit source) {
        /** Format this single CodeWithSource instance into the same textual representation used for lists. */
        public String text() {
            return text(List.of(this));
        }

        /**
         * Formats a list of CodeWithSource parts into a human-readable usage summary. The summary will contain -
         * "Method uses:" section grouped by containing class with <methods> blocks - "Type uses:" section with skeleton
         * headers
         */
        public static String text(List<CodeWithSource> parts) {
            Map<String, List<String>> methodsByClass = new LinkedHashMap<>();
            List<CodeWithSource> classParts = new ArrayList<>();

            for (var cws : parts) {
                var cu = cws.source();
                if (cu.isFunction()) {
                    String fqcn = CodeUnit.toClassname(cu.fqName());
                    methodsByClass.computeIfAbsent(fqcn, k -> new ArrayList<>()).add(cws.code());
                } else if (cu.isClass()) {
                    classParts.add(cws);
                }
            }

            StringBuilder sb = new StringBuilder();

            if (!methodsByClass.isEmpty()) {
                for (var entry : methodsByClass.entrySet()) {
                    var fqcn = entry.getKey();

                    // Try to derive the file path from any representative CodeUnit for this class
                    String file = "?";
                    for (var cws : parts) {
                        var cu = cws.source();
                        if (cu.isFunction() && CodeUnit.toClassname(cu.fqName()).equals(fqcn)) {
                            file = cu.source().toString();
                            break;
                        }
                    }

                    sb.append(
                            """
                            <methods class="%s" file="%s">
                            %s
                            </methods>
                            """
                                    .formatted(fqcn, file, String.join("\n\n", entry.getValue())));
                }
            }

            if (!classParts.isEmpty()) {
                // Group class parts by FQCN
                Map<String, List<String>> classCodesByFqcn = new LinkedHashMap<>();
                for (var cws : classParts) {
                    // Each CodeWithSource in classParts represents a class CodeUnit
                    var cu = cws.source();
                    if (!cu.isClass()) continue;
                    String fqcn = cu.fqName();
                    classCodesByFqcn
                            .computeIfAbsent(fqcn, k -> new ArrayList<>())
                            .add(cws.code());
                }

                for (var entry : classCodesByFqcn.entrySet()) {
                    var fqcn = entry.getKey();
                    var codesForClass = entry.getValue();

                    // Find the file path for this class.
                    String file = "?";
                    for (var cws : classParts) {
                        var potentialCu = cws.source();
                        if (potentialCu.isClass() && potentialCu.fqName().equals(fqcn)) {
                            file = potentialCu.source().toString();
                            break;
                        }
                    }

                    sb.append(
                            """
                            <class file="%s">
                            %s
                            </class>
                            """
                                    .formatted(file, String.join("\n\n", codesForClass)));
                }
            }

            return sb.toString();
        }
    }
}
