package ai.brokk;

import ai.brokk.analyzer.*;
import ai.brokk.analyzer.CallSite;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import ai.brokk.analyzer.SourceCodeProvider;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import java.nio.file.Path;
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
        return analyzer.getDefinitions(fqName).stream().findFirst().flatMap(cu -> analyzer.as(SkeletonProvider.class)
                .flatMap(skp -> skp.getSkeleton(cu)));
    }

    /**
     * Get skeleton header (class signature + fields without method bodies) for a class by name.
     */
    public static Optional<String> getSkeletonHeader(IAnalyzer analyzer, String className) {
        return analyzer.getDefinitions(className).stream().findFirst().flatMap(cu -> analyzer.as(SkeletonProvider.class)
                .flatMap(skp -> skp.getSkeletonHeader(cu)));
    }

    /**
     * Get all source code versions for a method (handles overloads) by fully qualified name.
     */
    public static Set<String> getMethodSources(IAnalyzer analyzer, String fqName, boolean includeComments) {
        return analyzer.getDefinitions(fqName).stream()
                .filter(CodeUnit::isFunction)
                .findFirst()
                .flatMap(cu ->
                        analyzer.as(SourceCodeProvider.class).map(scp -> scp.getMethodSources(cu, includeComments)))
                .orElse(Collections.emptySet());
    }

    /**
     * Get source code for a method by fully qualified name. If multiple versions exist (overloads), they are
     * concatenated.
     */
    public static Optional<String> getMethodSource(IAnalyzer analyzer, String fqName, boolean includeComments) {
        return analyzer.getDefinitions(fqName).stream()
                .filter(CodeUnit::isFunction)
                .findFirst()
                .flatMap(cu ->
                        analyzer.as(SourceCodeProvider.class).flatMap(scp -> scp.getMethodSource(cu, includeComments)));
    }

    /**
     * Get source code for a class by fully qualified name.
     */
    public static Optional<String> getClassSource(IAnalyzer analyzer, String fqcn, boolean includeComments) {
        return analyzer.getDefinitions(fqcn).stream()
                .filter(CodeUnit::isClass)
                .findFirst()
                .flatMap(cu ->
                        analyzer.as(SourceCodeProvider.class).flatMap(scp -> scp.getClassSource(cu, includeComments)));
    }

    /**
     * Get call graph showing what calls the given method.
     */
    public static Map<String, List<CallSite>> getCallgraphTo(IAnalyzer analyzer, String methodName, int depth) {
        return analyzer.getDefinitions(methodName).stream()
                .filter(CodeUnit::isFunction)
                .findFirst()
                .flatMap(cu -> analyzer.as(CallGraphProvider.class).map(cgp -> cgp.getCallgraphTo(cu, depth)))
                .orElse(Collections.emptyMap());
    }

    /**
     * Get call graph showing what the given method calls.
     */
    public static Map<String, List<CallSite>> getCallgraphFrom(IAnalyzer analyzer, String methodName, int depth) {
        return analyzer.getDefinitions(methodName).stream()
                .filter(CodeUnit::isFunction)
                .findFirst()
                .flatMap(cu -> analyzer.as(CallGraphProvider.class).map(cgp -> cgp.getCallgraphFrom(cu, depth)))
                .orElse(Collections.emptyMap());
    }

    /**
     * Get members (methods, fields, nested classes) of a class by fully qualified name.
     */
    public static List<CodeUnit> getMembersInClass(IAnalyzer analyzer, String fqClass) {
        return analyzer.getDefinitions(fqClass).stream()
                .filter(CodeUnit::isClass)
                .findFirst()
                .map(analyzer::getMembersInClass)
                .orElse(List.of());
    }

    /**
     * Get the file containing the definition of a symbol by fully qualified name.
     */
    public static Optional<ProjectFile> getFileFor(IAnalyzer analyzer, String fqName) {
        return analyzer.getDefinitions(fqName).stream().findFirst().map(CodeUnit::source);
    }

    /**
     * Extract the class/module/type name from a method/member reference.
     * This is a heuristic method that uses language-specific parsing.
     */
    public static Optional<String> extractCallReceiver(IAnalyzer analyzer, String reference) {
        return analyzer.extractCallReceiver(reference);
    }

    /**
     * Builds a fragment for a single file selection.
     *
     * <p>When {@code summarize} is true, a {@link ContextFragments.SummaryFragment} is returned with
     * {@link ContextFragment.SummaryType#FILE_SKELETONS}. Otherwise returns a
     * {@link ContextFragments.ProjectPathFragment}.
     *
     * @param cm the context manager used to resolve the {@link ProjectFile} and construct fragments
     * @param input a project-relative path (normalized internally); blank or non-project paths yield empty
     * @param summarize whether to create a summary ({@link ContextFragments.SummaryFragment}) instead of a file fragment
     * @return an Optional containing either {@link ContextFragments.ProjectPathFragment} or
     *         {@link ContextFragments.SummaryFragment}; empty if the file is not part of the project
     */
    public static Optional<ContextFragment> selectFileFragment(IContextManager cm, String input, boolean summarize) {
        ProjectFile chosen = cm.toFile(input);
        if (!cm.getProject().getAllFiles().contains(chosen)) {
            return Optional.empty();
        }

        ContextFragment frag = summarize
                ? new ContextFragments.SummaryFragment(
                        cm, chosen.getRelPath().toString(), ContextFragment.SummaryType.FILE_SKELETONS)
                : new ContextFragments.ProjectPathFragment(chosen, cm);
        return Optional.of(frag);
    }

    /**
     * Builds fragments for a folder selection.
     *
     * <p>When {@code summarize} is true, a {@link ContextFragments.SummaryFragment} is created per file with
     * {@link ContextFragment.SummaryType#FILE_SKELETONS}. Otherwise returns a
     * {@link ContextFragments.ProjectPathFragment} per file.
     *
     * @param cm the context manager used to resolve files and construct fragments
     * @param input a project-relative folder path (various separators and leading/trailing slashes are normalized)
     * @param includeSubfolders if true, include files in subdirectories; otherwise only direct children are included
     * @param summarize whether to return summary fragments ({@link ContextFragments.SummaryFragment}) instead of file fragments
     * @return an ordered Set of fragments (one per file) or an empty set if no files matched
     */
    public static Set<ContextFragment> selectFolderFragments(
            IContextManager cm, String input, boolean includeSubfolders, boolean summarize) {
        if (input.trim().isEmpty()) {
            return Set.of();
        }

        var rel = input.replace("\\", "/");
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        rel = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;

        Path relPath = Path.of(rel);

        Set<ProjectFile> all = cm.getProject().getAllFiles();
        Set<ProjectFile> selected = new LinkedHashSet<>();
        for (var pf : all) {
            Path fileRel = pf.getRelPath();
            if (includeSubfolders) {
                if (fileRel.startsWith(relPath)) {
                    selected.add(pf);
                }
            } else {
                Path parent = fileRel.getParent();
                if (Objects.equals(parent, relPath)) {
                    selected.add(pf);
                }
            }
        }

        if (selected.isEmpty()) {
            return Set.of();
        }

        if (summarize) {
            return selected.stream()
                    .map(pf -> (ContextFragment) new ContextFragments.SummaryFragment(
                            cm, pf.getRelPath().toString(), ContextFragment.SummaryType.FILE_SKELETONS))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        return selected.stream()
                .map(pf -> (ContextFragment) new ContextFragments.ProjectPathFragment(pf, cm))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Builds a fragment for a class selection.
     *
     * <p>Attempts resolution in this order:
     * <ol>
     *   <li>Exact definition by fully qualified name</li>
     *   <li>Search-based lookup</li>
     *   <li>Fallback scan of all declarations for matching short or fully qualified name</li>
     * </ol>
     *
     * <p>When {@code summarize} is true, returns a {@link ContextFragments.SummaryFragment} with
     * {@link ContextFragment.SummaryType#CODEUNIT_SKELETON}; otherwise returns a {@link ContextFragments.CodeFragment}.
     *
     * @param analyzer the analyzer used to resolve definitions; if null, returns empty
     * @param cm the context manager used to construct fragments
     * @param input a class name (short or fully qualified)
     * @param summarize whether to return a summary ({@link ContextFragments.SummaryFragment}) instead of a code fragment
     * @return an Optional containing {@link ContextFragments.CodeFragment} or {@link ContextFragments.SummaryFragment};
     *         empty if no matching class is found
     */
    public static Optional<ContextFragment> selectClassFragment(
            IAnalyzer analyzer, IContextManager cm, String input, boolean summarize) {

        Optional<CodeUnit> opt = analyzer.getDefinitions(input).stream()
                .filter(CodeUnit::isClass)
                .findFirst();

        if (opt.isEmpty()) {
            opt = analyzer.searchDefinitions(input).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
        }
        if (opt.isEmpty()) {
            String suffix = "." + input;
            opt = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isClass)
                    .filter(cu -> cu.shortName().equals(input)
                            || cu.fqName().equals(input)
                            || cu.fqName().endsWith(suffix))
                    .findFirst();
        }
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        CodeUnit cu = opt.get();
        ContextFragment frag = summarize
                ? new ContextFragments.SummaryFragment(cm, cu.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON)
                : new ContextFragments.CodeFragment(cm, cu);
        return Optional.of(frag);
    }

    /**
     * Builds a fragment for a method selection.
     *
     * <p>Attempts resolution in this order:
     * <ol>
     *   <li>Exact definition by fully qualified name</li>
     *   <li>Search-based lookup</li>
     *   <li>Autocomplete-based lookup</li>
     *   <li>Fallback scan of all declarations</li>
     *   <li>Fallback scan of members of all classes</li>
     * </ol>
     *
     * <p>When {@code summarize} is true, returns a {@link ContextFragments.SummaryFragment} with
     * {@link ContextFragment.SummaryType#CODEUNIT_SKELETON}; otherwise returns a {@link ContextFragments.CodeFragment}.
     *
     * @param analyzer the analyzer used to resolve definitions; if null, returns empty
     * @param cm the context manager used to construct fragments
     * @param input a method name (short or fully qualified)
     * @param summarize whether to return a summary ({@link ContextFragments.SummaryFragment}) instead of a code fragment
     * @return an Optional containing {@link ContextFragments.CodeFragment} or {@link ContextFragments.SummaryFragment};
     *         empty if no matching method is found
     */
    public static Optional<ContextFragment> selectMethodFragment(
            IAnalyzer analyzer, IContextManager cm, String input, boolean summarize) {

        // 1) Exact definition by fully qualified name
        Optional<CodeUnit> opt = analyzer.getDefinitions(input).stream()
                .filter(CodeUnit::isFunction)
                .findFirst();

        // 2) Search-based lookup
        if (opt.isEmpty()) {
            opt = analyzer.searchDefinitions(input).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();
        }

        // 3) Autocomplete-based lookup (helps when only short name is provided)
        if (opt.isEmpty()) {
            opt = analyzer.autocompleteDefinitions(input).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();
        }

        // 4) Fallback: scan all declarations for functions matching short name or fqName suffix
        if (opt.isEmpty()) {
            String suffix = "." + input;
            opt = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isFunction)
                    .filter(cu -> cu.shortName().equals(input)
                            || cu.fqName().equals(input)
                            || cu.fqName().endsWith(suffix))
                    .findFirst();
        }

        // 5) Additional fallback: scan members of all classes for matching methods
        if (opt.isEmpty()) {
            String suffix = "." + input;
            opt = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isClass)
                    .flatMap(cls -> analyzer.getMembersInClass(cls).stream())
                    .filter(CodeUnit::isFunction)
                    .filter(cu -> cu.shortName().equals(input)
                            || cu.fqName().equals(input)
                            || cu.fqName().endsWith(suffix))
                    .findFirst();
        }

        if (opt.isEmpty()) {
            return Optional.empty();
        }

        CodeUnit cu = opt.get();
        ContextFragment frag = summarize
                ? new ContextFragments.SummaryFragment(cm, cu.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON)
                : new ContextFragments.CodeFragment(cm, cu);
        return Optional.of(frag);
    }

    /**
     * Builds a fragment for a usage selection.
     *
     * <p>If the input resolves to a method and {@code summarize} is true, returns a
     * {@link ContextFragments.CallGraphFragment} showing callees at depth 1. Otherwise returns a
     * {@link ContextFragments.UsageFragment}. If no symbol can be resolved, a {@link ContextFragments.UsageFragment}
     * is still created using the raw input.
     *
     * @param analyzer the analyzer used to resolve the target symbol; if null, returns empty
     * @param cm the context manager used to construct fragments
     * @param input a symbol identifier (short or fully qualified); blank yields empty
     * @param includeTestFiles whether to include tests when building the {@link ContextFragments.UsageFragment}
     * @param summarize whether to return a {@link ContextFragments.CallGraphFragment} for a method target
     * @return an Optional containing {@link ContextFragments.CallGraphFragment} (for summarize+method) or
     *         {@link ContextFragments.UsageFragment}; empty if analyzer is null or input is blank
     */
    public static Optional<ContextFragment> selectUsageFragment(
            IAnalyzer analyzer, IContextManager cm, String input, boolean includeTestFiles, boolean summarize) {
        if (input.trim().isEmpty()) return Optional.empty();

        Optional<CodeUnit> exactMethod = analyzer.getDefinitions(input).stream()
                .filter(CodeUnit::isFunction)
                .findFirst();
        Optional<CodeUnit> any = exactMethod.isPresent()
                ? exactMethod
                : analyzer.getDefinitions(input).stream()
                        .findFirst()
                        .or(() -> analyzer.searchDefinitions(input).stream().findFirst());

        if (summarize && any.isPresent() && any.get().isFunction()) {
            var methodFqn = any.get().fqName();
            return Optional.of(new ContextFragments.CallGraphFragment(cm, methodFqn, 1, false));
        }

        var target = any.map(CodeUnit::fqName).orElse(input);
        return Optional.of(new ContextFragments.UsageFragment(cm, target, includeTestFiles));
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
