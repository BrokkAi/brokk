package ai.brokk;

import ai.brokk.analyzer.*;
import ai.brokk.analyzer.CallSite;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

        var methodUses = uses.stream().filter(CodeUnit::isFunction).sorted().toList();
        for (var cu : methodUses) {
            var source = analyzer.getSource(cu, true);
            if (source.isPresent()) {
                results.add(new CodeWithSource(source.get(), cu));
            } else if (!(analyzer instanceof DisabledAnalyzer)) {
                logger.warn("Unable to obtain source code for method use by {}", cu.fqName());
            }
        }

        var typeUses = uses.stream().filter(CodeUnit::isClass).sorted().toList();
        for (var cu : typeUses) {
            var skeletonHeader = analyzer.getSkeletonHeader(cu);
            skeletonHeader.ifPresent(header -> results.add(new CodeWithSource(header, cu)));
        }

        // Handle fields by showing their containing class skeleton
        var fieldUses = uses.stream().filter(CodeUnit::isField).sorted().toList();
        for (var field : fieldUses) {
            var parentOpt = analyzer.parentOf(field);
            if (parentOpt.isEmpty()) {
                if (!(analyzer instanceof DisabledAnalyzer)) {
                    logger.warn("Unable to find parent class for field {}", field.fqName());
                }
                continue;
            }

            var parent = parentOpt.get();
            analyzer.getSkeletonHeader(parent).ifPresent(header -> results.add(new CodeWithSource(header, parent)));
        }

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

    /**
     * Returns a stream of declarations from the given files, logging warnings if files are missing from the analyzer
     * or contain no code units.
     */
    public static Stream<CodeUnit> getTestDeclarationsWithLogging(IAnalyzer analyzer, Collection<ProjectFile> files) {
        Set<ProjectFile> analyzedFiles = analyzer.getAnalyzedFiles();
        return files.stream().flatMap(testFile -> {
            if (!analyzedFiles.contains(testFile)) {
                logger.warn("Test file is missing from analyzer index: {}", testFile);
                return Stream.empty();
            }

            Set<CodeUnit> decls = analyzer.getDeclarations(testFile);
            if (decls.isEmpty()) {
                logger.warn("Test file contains no code units: {}", testFile);
            }
            return decls.stream();
        });
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
        String combined = analyzer.getDefinitions(fqName).stream()
                .map(analyzer::getSkeleton)
                .flatMap(Optional::stream)
                .collect(Collectors.joining("\n\n"));
        return combined.isEmpty() ? Optional.empty() : Optional.of(combined);
    }

    /**
     * Get skeleton header (class signature + fields without method bodies) for a class by name.
     */
    public static Optional<String> getSkeletonHeader(IAnalyzer analyzer, String className) {
        String combined = analyzer.getDefinitions(className).stream()
                .map(analyzer::getSkeletonHeader)
                .flatMap(Optional::stream)
                .collect(Collectors.joining("\n\n"));
        return combined.isEmpty() ? Optional.empty() : Optional.of(combined);
    }

    /**
     * Get source code for a code unit by fully qualified name. Currently, only methods and classes are supported. For
     * overloaded methods, will combine sources for these as far as their fqNames match.
     */
    public static Optional<String> getSource(IAnalyzer analyzer, String fqName, boolean includeComments) {
        return analyzer.getDefinitions(fqName).stream()
                .filter(cu -> cu.isFunction() || cu.isClass())
                .flatMap(cu -> analyzer.getSource(cu, includeComments).stream())
                .reduce((srcA, srcB) -> srcA + "\n\n" + srcB);
    }

    /**
     * Get members (methods, fields, nested classes) of a class by fully qualified name.
     */
    public static List<CodeUnit> getMembersInClass(IAnalyzer analyzer, String fqClass) {
        return analyzer.getDefinitions(fqClass).stream()
                .filter(CodeUnit::isClass)
                .map(analyzer::getMembersInClass)
                .flatMap(List::stream)
                .toList();
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
        ProjectFile chosenFromInput = cm.toFile(input);
        return cm.getProject()
                .getFileByRelPath(chosenFromInput.getRelPath())
                .map(chosen -> summarize
                        ? new ContextFragments.SummaryFragment(
                                cm, chosen.getRelPath().toString(), ContextFragment.SummaryType.FILE_SKELETONS)
                        : new ContextFragments.ProjectPathFragment(chosen, cm));
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
     * @return a Set containing {@link ContextFragments.CodeFragment}s or {@link ContextFragments.SummaryFragment}s;
     *         empty if no matching class is found
     */
    public static Set<ContextFragment> selectClassFragment(
            IAnalyzer analyzer, IContextManager cm, String input, boolean summarize) {

        Set<CodeUnit> matches = analyzer.getDefinitions(input).stream()
                .filter(CodeUnit::isClass)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (matches.isEmpty()) {
            matches = analyzer.searchDefinitions(input).stream()
                    .filter(CodeUnit::isClass)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        if (matches.isEmpty()) {
            String suffix = "." + input;
            matches = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isClass)
                    .filter(cu -> cu.shortName().equals(input)
                            || cu.fqName().equals(input)
                            || cu.fqName().endsWith(suffix))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        if (matches.isEmpty()) {
            return Set.of();
        }

        if (summarize) {
            return matches.stream()
                    .map(CodeUnit::fqName)
                    .distinct()
                    .map(fqn -> (ContextFragment) new ContextFragments.SummaryFragment(
                            cm, fqn, ContextFragment.SummaryType.CODEUNIT_SKELETON))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        return matches.stream()
                .map(cu -> (ContextFragment) new ContextFragments.CodeFragment(cm, cu))
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
     * @return a Set containing {@link ContextFragments.CodeFragment}s or {@link ContextFragments.SummaryFragment}s;
     *         empty if no matching method is found
     */
    public static Set<ContextFragment> selectMethodFragment(
            IAnalyzer analyzer, IContextManager cm, String input, boolean summarize) {

        // 1) Exact definition by fully qualified name
        Set<CodeUnit> matches = analyzer.getDefinitions(input).stream()
                .filter(CodeUnit::isFunction)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 2) Search-based lookup
        if (matches.isEmpty()) {
            matches = analyzer.searchDefinitions(input).stream()
                    .filter(CodeUnit::isFunction)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 3) Autocomplete-based lookup (helps when only short name is provided)
        if (matches.isEmpty()) {
            matches = analyzer.autocompleteDefinitions(input).stream()
                    .filter(CodeUnit::isFunction)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 4) Fallback: scan all declarations for functions matching short name or fqName suffix
        if (matches.isEmpty()) {
            String suffix = "." + input;
            matches = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isFunction)
                    .filter(cu -> cu.shortName().equals(input)
                            || cu.fqName().equals(input)
                            || cu.fqName().endsWith(suffix))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 5) Additional fallback: scan members of all classes for matching methods
        if (matches.isEmpty()) {
            String suffix = "." + input;
            matches = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isClass)
                    .flatMap(cls -> analyzer.getMembersInClass(cls).stream())
                    .filter(CodeUnit::isFunction)
                    .filter(cu -> cu.shortName().equals(input)
                            || cu.fqName().equals(input)
                            || cu.fqName().endsWith(suffix))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        if (matches.isEmpty()) {
            return Set.of();
        }

        if (summarize) {
            return matches.stream()
                    .map(CodeUnit::fqName)
                    .distinct()
                    .map(fqn -> (ContextFragment) new ContextFragments.SummaryFragment(
                            cm, fqn, ContextFragment.SummaryType.CODEUNIT_SKELETON))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        return matches.stream()
                .map(cu -> (ContextFragment) new ContextFragments.CodeFragment(cm, cu))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Builds a fragment for a usage selection.
     *
     * <p>Returns a {@link ContextFragments.UsageFragment}. If no symbol can be resolved, a
     * {@link ContextFragments.UsageFragment} is still created using the raw input.
     *
     * @param analyzer the analyzer used to resolve the target symbol; if null, returns empty
     * @param cm the context manager used to construct fragments
     * @param input a symbol identifier (short or fully qualified); blank yields empty
     * @param includeTestFiles whether to include tests when building the {@link ContextFragments.UsageFragment}
     * @return an Optional containing {@link ContextFragments.UsageFragment}; empty if analyzer is null or input is
     * blank
     */
    public static Optional<ContextFragment> selectUsageFragment(
            IAnalyzer analyzer, IContextManager cm, String input, boolean includeTestFiles) {
        return selectUsageFragment(analyzer, cm, input, includeTestFiles, ContextFragments.UsageMode.FULL);
    }

    /**
     * Builds a fragment for a usage selection with specified mode.
     *
     * <p>Returns a {@link ContextFragments.UsageFragment}. If no symbol can be resolved, a
     * {@link ContextFragments.UsageFragment} is still created using the raw input.
     *
     * @param analyzer the analyzer used to resolve the target symbol; if null, returns empty
     * @param cm the context manager used to construct fragments
     * @param input a symbol identifier (short or fully qualified); blank yields empty
     * @param includeTestFiles whether to include tests when building the {@link ContextFragments.UsageFragment}
     * @param mode the usage mode (FULL or SAMPLE)
     * @return an Optional containing {@link ContextFragments.UsageFragment}; empty if analyzer is null or input is
     * blank
     */
    public static Optional<ContextFragment> selectUsageFragment(
            IAnalyzer analyzer,
            IContextManager cm,
            String input,
            boolean includeTestFiles,
            ContextFragments.UsageMode mode) {
        if (input.trim().isEmpty()) return Optional.empty();

        Optional<CodeUnit> exactMethod = analyzer.getDefinitions(input).stream()
                .filter(CodeUnit::isFunction)
                .findFirst();
        Optional<CodeUnit> any = exactMethod.isPresent()
                ? exactMethod
                : analyzer.getDefinitions(input).stream()
                        .findFirst()
                        .or(() -> analyzer.searchDefinitions(input).stream().findFirst());

        var target = any.map(CodeUnit::fqName).orElse(input);
        return Optional.of(new ContextFragments.UsageFragment(cm, target, includeTestFiles, mode));
    }

    public record CodeWithSource(String code, CodeUnit source) {
        public String text(IAnalyzer analyzer) {
            return text(analyzer, List.of(this));
        }

        /**
         * Same output format as {@link #text(IAnalyzer, List)}, but uses analyzer resolution for better accuracy.
         */
        public static String text(IAnalyzer analyzer, List<CodeWithSource> parts) {
            Map<CodeUnit, List<String>> methodsByOwner = new LinkedHashMap<>();
            List<CodeWithSource> classParts = new ArrayList<>();

            for (var cws : parts) {
                var cu = cws.source();
                if (cu.isFunction()) {
                    var owner = analyzer.parentOf(cu).orElse(cu);
                    methodsByOwner
                            .computeIfAbsent(owner, k -> new ArrayList<>())
                            .add(cws.code());
                } else if (cu.isClass()) {
                    classParts.add(cws);
                }
            }

            StringBuilder sb = new StringBuilder();

            if (!methodsByOwner.isEmpty()) {
                for (var entry : methodsByOwner.entrySet()) {
                    var owner = entry.getKey();
                    sb.append(
                            """
                            <methods class="%s" file="%s">
                            %s
                            </methods>
                            """
                                    .formatted(
                                            owner.fqName(),
                                            owner.source().toString(),
                                            String.join("\n\n", entry.getValue())));
                }
            }

            if (!classParts.isEmpty()) {
                Map<CodeUnit, List<String>> classCodesByCu = new LinkedHashMap<>();
                for (var cws : classParts) {
                    var cu = cws.source();
                    if (!cu.isClass()) continue;
                    classCodesByCu.computeIfAbsent(cu, k -> new ArrayList<>()).add(cws.code());
                }

                for (var entry : classCodesByCu.entrySet()) {
                    var cls = entry.getKey();
                    var codesForClass = entry.getValue();

                    sb.append(
                            """
                            <class file="%s">
                            %s
                            </class>
                            """
                                    .formatted(cls.source().toString(), String.join("\n\n", codesForClass)));
                }
            }

            return sb.toString();
        }
    }
}
