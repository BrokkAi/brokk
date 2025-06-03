package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AnalyzerUtil {
    private static final Logger logger = LogManager.getLogger(AnalyzerUtil.class);

    @NotNull
    public static AnalyzerWrapper.CodeWithSource processUsages(IAnalyzer analyzer, List<CodeUnit> uses) {
        StringBuilder code = new StringBuilder();
        Set<CodeUnit> sources = new HashSet<>();

        // method uses
        var methodUses = uses.stream()
                .filter(CodeUnit::isFunction)
                .sorted()
                .toList();
        // type uses
        var typeUses = uses.stream()
                .filter(CodeUnit::isClass)
                .sorted()
                .toList();

        if (!methodUses.isEmpty()) {
            Map<String, List<String>> groupedMethods = new LinkedHashMap<>();
            for (var cu : methodUses) {
                var source = analyzer.getMethodSource(cu.fqName());
                if (source.isPresent()) {
                    String classname = ContextFragment.toClassname(cu.fqName());
                    groupedMethods.computeIfAbsent(classname, k -> new ArrayList<>()).add(source.get());
                    sources.add(cu);
                }
            }
            if (!groupedMethods.isEmpty()) {
                code.append("Method uses:\n\n");
                for (var entry : groupedMethods.entrySet()) {
                    var methods = entry.getValue();
                    if (!methods.isEmpty()) {
                        var fqcn = entry.getKey();
                        var file = analyzer.getFileFor(fqcn).map(ProjectFile::toString).orElse("?");
                        code.append("""
                                <methods class="%s" file="%s">
                                %s
                                </methods>
                                """.formatted(fqcn, file, String.join("\n\n", methods)));
                    }
                }
            }
        }

        if (!typeUses.isEmpty()) {
            code.append("Type uses:\n\n");
            for (var cu : typeUses) {
                var skeletonHeader = analyzer.getSkeletonHeader(cu.fqName());
                if (skeletonHeader.isEmpty()) {
                    continue;
                }
                code.append(skeletonHeader.get()).append("\n");
                sources.add(cu);
            }
        }

        return new AnalyzerWrapper.CodeWithSource(code.toString(), sources);
    }

    public static List<CodeUnit> combinedPagerankFor(IAnalyzer analyzer, Map<String, Double> weightedSeeds) {
        logger.trace("Computing pagerank for {}", weightedSeeds);

        // do forward and reverse pagerank passes
        var forwardResults = analyzer.getPagerank(weightedSeeds, 3 * Context.MAX_AUTO_CONTEXT_FILES, false);
        var reverseResults = analyzer.getPagerank(weightedSeeds, 3 * Context.MAX_AUTO_CONTEXT_FILES, true);

        // combine results by summing scores
        var combinedScores = new HashMap<CodeUnit, Double>();
        forwardResults.forEach(pair -> combinedScores.put(pair._1(), pair._2()));
        reverseResults.forEach(pair -> combinedScores.merge(pair._1(), pair._2(), Double::sum));

        // sort by combined score
        var result = combinedScores.entrySet().stream()
                .sorted(Map.Entry.<CodeUnit, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                // isClassInProject filtering is implicitly handled by getPagerank returning CodeUnits
                .toList();

        logger.trace("Pagerank results: {}", result);
        return result;
    }

    @NotNull
    public static Set<CodeUnit> coalesceInnerClasses(Set<CodeUnit> classes)
    {
        return classes.stream()
                .filter(cu -> {
                    var name = cu.fqName();
                    if (!name.contains("$")) return true;
                    var parent = name.substring(0, name.indexOf('$'));
                    return classes.stream().noneMatch(other -> other.fqName().equals(parent));
                })
                .collect(Collectors.toSet());
    }

    public static @NotNull Map<CodeUnit, String> getSkeletonStrings(IAnalyzer analyzer, Set<CodeUnit> classes) {
        var coalescedUnits = coalesceInnerClasses(classes);
        return coalescedUnits.stream().parallel()
                .map(cu -> Map.entry(cu, analyzer.getSkeleton(cu.fqName())))
                .filter(entry -> entry.getValue().isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    public static List<String> testFilesToFQCNs(IAnalyzer analyzer, Collection<ProjectFile> files) {
        if (analyzer instanceof JavaAnalyzer) {
            // TODO remove this hack when we can get Joern to process the damn test files
            return files.stream()
                    .map(file -> {
                        // Extract class name from filename (without extension)
                        var fileName = file.getRelPath().getFileName().toString();
                        var className = fileName.contains(".") 
                                ? fileName.substring(0, fileName.lastIndexOf('.'))
                                : fileName;
                        
                        // Read file content and extract package declaration
                        try {
                            var content = file.read();
                            var packageName = extractPackageName(content);
                            
                            // Build FQCN: package.classname or just classname if no package
                            return packageName.isEmpty() 
                                    ? className 
                                    : packageName + "." + className;
                        } catch (IOException e) {
                            // If we can't read the file, just use the simple class name
                            logger.warn("Could not read file {}", file, e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        }

        var classUnitsInTestFiles = files.stream()
                .flatMap(testFile -> analyzer.getDeclarationsInFile(testFile).stream())
                .filter(CodeUnit::isClass)
                .collect(Collectors.toSet());

        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(classUnitsInTestFiles);

        return coalescedClasses.stream()
                .map(CodeUnit::fqName)
                .sorted() // for consistent test command generation
                .toList();
    }

    private record StackEntry(String method, int depth) {
    }

    /**
     * Helper method to recursively format the call graph (both callers and callees)
     */
    public static String formatCallGraph(Map<String, List<CallSite>> callgraph,
                                         String rootMethodName,
                                         boolean isCallerGraph)
    {
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
                                      """.stripIndent().indent(2 * entry.depth)
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
     * Retrieves skeleton data for the given class names.
     * @param analyzer The Analyzer instance.
     * @param classNames Fully qualified class names.
     * @return A map of CodeUnit to its skeleton string. Returns an empty map if no skeletons are found.
     */
    public static Map<CodeUnit, String> getClassSkeletonsData(IAnalyzer analyzer, List<String> classNames) {
        assert analyzer.isCpg() : "CPG Analyzer is not available.";
        if (classNames == null || classNames.isEmpty()) {
            return Map.of();
        }

        return classNames.stream()
                .distinct()
                .map(analyzer::getDefinition) // Get the CodeUnit definition directly
                .flatMap(Optional::stream)    // Convert Optional<CodeUnit> to Stream<CodeUnit>
                .filter(CodeUnit::isClass)    // Ensure it's a class CodeUnit
                .map(cu -> {
                    Optional<String> skeletonOpt = analyzer.getSkeleton(cu.fqName()); // Use fqName from CodeUnit
                    return skeletonOpt.isPresent() ? Map.entry(cu, skeletonOpt.get()) : null; // Create entry if skeleton exists
                })
                .filter(Objects::nonNull) // Filter out null entries (where skeleton wasn't found)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Retrieves method source code for the given method names.
     * @param analyzer The Analyzer instance.
     * @param methodNames Fully qualified method names.
     * @return A map of method name to its source code string. Returns an empty map if no sources are found.
     */
    public static Map<String, String> getMethodSourcesData(IAnalyzer analyzer, List<String> methodNames) {
        assert analyzer.isCpg() : "CPG Analyzer is not available for getMethodSourcesData.";
        if (methodNames == null || methodNames.isEmpty()) {
            return Map.of();
        }

        Map<String, String> sources = new LinkedHashMap<>(); // Preserve order potentially

        // Iterate through each requested method name
        for (String methodName : methodNames) {
            if (methodName != null && !methodName.isBlank()) {
                // Attempt to get the source code for the method
                var methodSourceOpt = analyzer.getMethodSource(methodName);
                if (methodSourceOpt.isPresent()) {
                    // If source is found, add it to the map with a header comment
                    String methodSource = methodSourceOpt.get();
                    sources.put(methodName, "// Source for " + methodName + "\n" + methodSource);
                }
                // If methodSourceOpt is empty, we simply don't add an entry for this methodName
            }
        }
        // Return the map containing sources for all found methods
        return sources;
    }

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;");

    /**
     * Extracts the package name from Java source code content.
     * @param content The source code content
     * @return The package name, or empty string if no package declaration found
     */
    private static String extractPackageName(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("//") && !line.startsWith("/*"))
                .map(PACKAGE_PATTERN::matcher)
                .filter(Matcher::matches)
                .findFirst()
                .map(matcher -> matcher.group(1))
                .orElse("");
    }

    /**
     * Retrieves class source code for the given class names, including filename headers.
     * @param analyzer The Analyzer instance.
     * @param classNames Fully qualified class names.
     * @return A map of class name to its formatted source code string (with header). Returns an empty map if no sources are found.
     */
    public static Map<String, String> getClassSourcesData(IAnalyzer analyzer, List<String> classNames) {
        assert analyzer.isCpg() : "CPG Analyzer is not available for getClassSourcesData.";
        if (classNames == null || classNames.isEmpty()) {
            return Map.of();
        }

        Map<String, String> sources = new LinkedHashMap<>(); // Preserve order potentially

        // Iterate through each requested class name
        for (String className : classNames) {
            if (className != null && !className.isBlank()) {
                // Attempt to get the source code for the class
                var classSource = analyzer.getClassSource(className);
                if (classSource != null && !classSource.isEmpty()) {
                    // If source is found, format it with a header and add to the map
                    String filename = analyzer.getFileFor(className).map(ProjectFile::toString).orElse("unknown file");
                    String formattedSource = "Source code of %s (from %s):\n\n%s".formatted(className, filename, classSource);
                    sources.put(className, formattedSource);
                    // If classSource is null or empty, we simply don't add an entry for this className
                }
            }
        }
        // Return the map containing formatted sources for all found classes
        return sources;
    }
}
