package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
                var source = sourceCodeProvider.getMethodSource(cu.fqName(), true);
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
                var skeletonHeader = skeletonProvider.getSkeletonHeader(cu.fqName());
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
                .flatMap(testFile -> analyzer.getDeclarationsInFile(testFile).stream())
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
     * Retrieves method source code for the given method names.
     *
     * @param analyzer The Analyzer instance.
     * @param methodNames Fully qualified method names.
     * @return A map of method name to its source code string. Returns an empty map if no sources are found.
     */
    public static Map<String, String> getMethodSources(IAnalyzer analyzer, List<String> methodNames) {
        assert analyzer instanceof SourceCodeProvider : "Analyzer is not available for getMethodSourcesData.";
        if (methodNames.isEmpty()) {
            return Map.of();
        }

        Map<String, String> sources = new LinkedHashMap<>(); // Preserve order potentially

        // Iterate through each requested method name
        for (String methodName : methodNames) {
            if (!methodName.isBlank()) {
                // Attempt to get the source code for the method
                analyzer.as(SourceCodeProvider.class)
                        .flatMap(scp -> scp.getMethodSource(methodName, true))
                        // If source is found, add it to the map with a header comment
                        .ifPresent(methodSource ->
                                sources.put(methodName, "// Source for " + methodName + "\n" + methodSource));
                // If methodSourceOpt is empty, we simply don't add an entry for this methodName
            }
        }
        // Return the map containing sources for all found methods
        return sources;
    }

    /**
     * Retrieves class source code for the given class names, including filename headers.
     *
     * @param analyzer The Analyzer instance.
     * @param classNames Fully qualified class names.
     * @return A map of class name to its formatted source code string (with header). Returns an empty map if no sources
     *     are found.
     */
    public static Map<String, String> getClassSources(IAnalyzer analyzer, List<String> classNames) {
        assert analyzer instanceof SourceCodeProvider : "Analyzer is not available for getClassSourcesData.";
        if (classNames.isEmpty()) {
            return Map.of();
        }

        Map<String, String> sources = new LinkedHashMap<>(); // Preserve order potentially

        // Iterate through each requested class name
        for (String className : classNames) {
            if (!className.isBlank()) {
                // Attempt to get the source code for the class
                analyzer.as(SourceCodeProvider.class)
                        .flatMap(scp -> scp.getClassSource(className, true))
                        .filter(classSource -> !classSource.isEmpty())
                        .ifPresent(classSource -> {
                            // If source is found, format it with a header and add to the map
                            String filename = analyzer.getFileFor(className)
                                    .map(ProjectFile::toString)
                                    .orElse("unknown file");
                            String formattedSource =
                                    "Source code of %s (from %s):\n\n%s".formatted(className, filename, classSource);
                            sources.put(className, formattedSource);
                            // If classSource is null or empty, we simply don't add an entry for this className
                        });
            }
        }
        // Return the map containing formatted sources for all found classes
        return sources;
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
