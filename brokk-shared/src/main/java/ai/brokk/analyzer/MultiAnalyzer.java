package ai.brokk.analyzer;

import ai.brokk.project.ICoreProject;
import java.util.*;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.pcollections.HashTreePMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiAnalyzer
        implements IAnalyzer, TypeAliasProvider, ImportAnalysisProvider, TypeHierarchyProvider, TestDetectionProvider {
    private static final Logger log = LoggerFactory.getLogger(MultiAnalyzer.class);

    private static final Set<Class<? extends CapabilityProvider>> SUPPORTED_CAPABILITIES = Set.of(
            ImportAnalysisProvider.class,
            TypeHierarchyProvider.class,
            TypeAliasProvider.class,
            TestDetectionProvider.class);

    private final Map<Language, IAnalyzer> delegates;
    private final Collection<ITemplateAnalyzer> templateAnalyzers;

    public MultiAnalyzer(Map<Language, IAnalyzer> delegates) {
        this(delegates, List.of());
    }

    public MultiAnalyzer(Map<Language, IAnalyzer> delegates, Collection<ITemplateAnalyzer> templateAnalyzers) {
        this.delegates = delegates; // Store the live map directly
        this.templateAnalyzers = List.copyOf(templateAnalyzers);
    }

    private <R> Optional<R> findFirst(Function<IAnalyzer, Optional<R>> extractor) {
        for (var delegate : delegates.values()) {
            try {
                var result = extractor.apply(delegate);
                if (result.isPresent()) {
                    return result;
                }
            } catch (UnsupportedOperationException ignored) {
                // This delegate doesn't support the operation
            }
        }
        return Optional.empty();
    }

    /**
     * Get the delegate analyzer for the language of the given CodeUnit.
     *
     * @param cu The CodeUnit whose language to detect
     * @return The delegate analyzer for that language, or empty if no delegate exists
     */
    private Optional<IAnalyzer> delegateFor(CodeUnit cu) {
        var lang = Languages.fromExtension(cu.source().extension());
        var delegate = delegates.get(lang);
        if (delegate == null && !lang.equals(Languages.NONE)) {
            log.debug("No delegate found for language {} (from file {})", lang, cu.source());
        }
        return Optional.ofNullable(delegate);
    }

    /**
     * Get the delegate analyzer for the language of the given ProjectFile.
     *
     * @param file The ProjectFile whose language to detect
     * @return The delegate analyzer for that language, or empty if no delegate exists
     */
    private Optional<IAnalyzer> delegateFor(ProjectFile file) {
        var lang = Languages.fromExtension(file.extension());
        var delegate = delegates.get(lang);
        if (delegate == null && !lang.equals(Languages.NONE)) {
            log.debug("No delegate found for language {} (from file {})", lang, file);
        }
        return Optional.ofNullable(delegate);
    }

    @Override
    public boolean isEmpty() {
        return delegates.values().stream().allMatch(IAnalyzer::isEmpty);
    }

    @Override
    public Set<Language> languages() {
        return delegates.keySet();
    }

    @Override
    public <T extends CapabilityProvider> Optional<T> as(Class<T> capability) {
        if (SUPPORTED_CAPABILITIES.contains(capability)) {
            // We only return 'this' for these specific capabilities if at least one delegate supports them.
            boolean anyDelegateSupports =
                    delegates.values().stream().anyMatch(d -> d.as(capability).isPresent());
            return anyDelegateSupports ? Optional.of(capability.cast(this)) : Optional.empty();
        }

        throw new AssertionError("MultiAnalyzer does not support casting to " + capability);
    }

    @Override
    public List<String> importStatementsOf(ProjectFile file) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.importStatementsOf(file).stream())
                .toList();
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return delegateFor(file)
                .flatMap(delegate -> delegate.as(ImportAnalysisProvider.class))
                .map(provider -> provider.importedCodeUnitsOf(file))
                .orElse(Set.of());
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.as(ImportAnalysisProvider.class).stream())
                .flatMap(provider -> provider.referencingFilesOf(file).stream())
                .collect(Collectors.toSet());
    }

    @Override
    public List<ImportInfo> importInfoOf(ProjectFile file) {
        return delegateFor(file)
                .flatMap(delegate -> delegate.as(ImportAnalysisProvider.class))
                .map(provider -> provider.importInfoOf(file))
                .orElse(List.of());
    }

    @Override
    public Set<String> relevantImportsFor(CodeUnit cu) {
        return delegateFor(cu)
                .flatMap(delegate -> delegate.as(ImportAnalysisProvider.class))
                .map(provider -> provider.relevantImportsFor(cu))
                .orElse(Set.of());
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.enclosingCodeUnit(file, range).stream())
                .findFirst();
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, int startLine, int endLine) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.enclosingCodeUnit(file, startLine, endLine).stream())
                .findFirst();
    }

    @Override
    public boolean isAccessExpression(ProjectFile file, int startByte, int endByte) {
        return delegateFor(file)
                .map(delegate -> delegate.isAccessExpression(file, startByte, endByte))
                .orElse(true); // conservative default when no delegate found
    }

    @Override
    public Optional<DeclarationInfo> findNearestDeclaration(
            ProjectFile file, int startByte, int endByte, String identifierName) {
        return delegateFor(file)
                .flatMap(delegate -> delegate.findNearestDeclaration(file, startByte, endByte, identifierName));
    }

    @Override
    public ICoreProject getProject() {
        return findFirst(analyzer -> Optional.of(analyzer.getProject())).orElseThrow();
    }

    @Override
    public Optional<String> getSkeleton(CodeUnit cu) {
        return delegateFor(cu).flatMap(analyzer -> analyzer.getSkeleton(cu));
    }

    @Override
    public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
        return delegateFor(classUnit).flatMap(analyzer -> analyzer.getSkeletonHeader(classUnit));
    }

    @Override
    public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
        return delegateFor(file)
                .map(delegate -> delegate.getTopLevelDeclarations(file))
                .orElseGet(() -> templateTopLevelDeclarations(file));
    }

    private List<CodeUnit> templateTopLevelDeclarations(ProjectFile file) {
        for (var ta : templateAnalyzers) {
            List<CodeUnit> tlds = ta.getTopLevelDeclarations(file, getProject());
            if (!tlds.isEmpty()) {
                return tlds;
            }
        }
        return List.of();
    }

    @Override
    public Set<ProjectFile> getAnalyzedFiles() {
        return delegates.values().stream()
                .flatMap(a -> a.getAnalyzedFiles().stream())
                .collect(Collectors.toSet());
    }

    @Override
    public String summarizeSymbols(ProjectFile file, String sourceText) {
        return delegateFor(file)
                .map(delegate -> delegate.summarizeSymbols(file, sourceText))
                .orElse("");
    }

    @Override
    public List<CodeUnit> getDirectChildren(CodeUnit cu) {
        return delegateFor(cu).map(delegate -> delegate.getDirectChildren(cu)).orElse(List.of());
    }

    @Override
    public List<Range> rangesOf(CodeUnit codeUnit) {
        return delegateFor(codeUnit)
                .map(delegate -> delegate.rangesOf(codeUnit))
                .orElse(List.of());
    }

    @Override
    public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
        return delegateFor(cu).flatMap(delegate -> delegate.commentDensity(cu));
    }

    @Override
    public Optional<CommentDensityStats> commentDensity(String fqName) {
        return getDefinitions(fqName).stream()
                .map(cu -> delegateFor(cu).flatMap(delegate -> delegate.commentDensity(cu)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public List<CommentDensityStats> commentDensityByTopLevel(ProjectFile file) {
        return delegateFor(file)
                .map(delegate -> delegate.commentDensityByTopLevel(file))
                .orElse(List.of());
    }

    @Override
    public List<ExceptionHandlingSmell> findExceptionHandlingSmells(ProjectFile file, ExceptionSmellWeights weights) {
        return delegateFor(file)
                .map(delegate -> delegate.findExceptionHandlingSmells(file, weights))
                .orElse(List.of());
    }

    @Override
    public List<CloneSmell> findStructuralCloneSmells(ProjectFile file, CloneSmellWeights weights) {
        return delegateFor(file)
                .map(delegate -> delegate.findStructuralCloneSmells(file, weights))
                .orElse(List.of());
    }

    @Override
    public List<CloneSmell> findStructuralCloneSmells(List<ProjectFile> files, CloneSmellWeights weights) {
        return files.stream()
                .collect(Collectors.groupingBy(
                        file -> Languages.fromExtension(file.extension()), LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .flatMap(entry -> {
                    var delegate = delegates.get(entry.getKey());
                    if (delegate == null) {
                        return Stream.<CloneSmell>empty();
                    }
                    return delegate.findStructuralCloneSmells(entry.getValue(), weights).stream();
                })
                .toList();
    }

    @Override
    public Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
        return delegateFor(codeUnit).flatMap(analyzer -> analyzer.getSource(codeUnit, includeComments));
    }

    @Override
    public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
        Set<String> allSources = new LinkedHashSet<>();

        // 1. Get sources from the primary language delegate
        delegateFor(codeUnit).ifPresent(delegate -> {
            try {
                allSources.addAll(delegate.getSources(codeUnit, includeComments));
            } catch (Exception e) {
                log.error("Error getting sources from delegate for {}: {}", codeUnit, e.getMessage());
            }
        });

        // 2. Get sources from applicable template analyzers
        for (var ta : templateAnalyzers) {
            try {
                allSources.addAll(ta.getTemplateSources(codeUnit));
            } catch (Exception e) {
                log.error(
                        "Error getting template sources from {} for {}: {}",
                        ta.internalName(),
                        codeUnit,
                        e.getMessage());
            }
        }

        return allSources;
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        return delegateFor(file).map(analyzer -> analyzer.getSkeletons(file)).orElse(Collections.emptyMap());
    }

    @Override
    public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
        return delegateFor(classUnit)
                .map(delegate -> delegate.getMembersInClass(classUnit))
                .orElse(List.of());
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.getAllDeclarations().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        return delegateFor(file).map(delegate -> delegate.getDeclarations(file)).orElse(Set.of());
    }

    /**
     * Aggregates definitions from all delegate analyzers and returns them in sorted order.
     *
     * <p><b>Cross-language behavior:</b> Results from all languages are combined. If the same fqName
     * exists in multiple languages (e.g., Java and Kotlin), all are returned. The default sort order
     * is by file path, so callers using {@code .findFirst()} get deterministic results. To prefer
     * a specific language, override {@link #priorityComparator()} or filter by file extension.
     */
    @Override
    public SequencedSet<CodeUnit> getDefinitions(String fqName) {
        var results = delegates.values().stream()
                .flatMap(analyzer -> analyzer.getDefinitions(fqName).stream())
                .collect(Collectors.toSet());
        return sortDefinitions(results);
    }

    @Override
    public Set<CodeUnit> searchDefinitions(String pattern) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.searchDefinitions(pattern).stream())
                .collect(Collectors.toSet());
    }

    @Override
    public Set<CodeUnit> autocompleteDefinitions(String query) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.autocompleteDefinitions(query).stream())
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getSymbols(Set<CodeUnit> sources) {
        return delegates.values().stream()
                .flatMap(analyzer -> {
                    try {
                        return analyzer.getSymbols(sources).stream();
                    } catch (UnsupportedOperationException e) {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toSet());
    }

    @Override
    public IAnalyzer update() {
        final var newDelegates = new HashMap<Language, IAnalyzer>(delegates.size());
        for (var entry : delegates.entrySet()) {
            var delegateKey = entry.getKey();
            var analyzer = entry.getValue();
            newDelegates.put(delegateKey, analyzer.update());
        }
        return new MultiAnalyzer(newDelegates, templateAnalyzers);
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        final var newDelegates = new HashMap<Language, IAnalyzer>(delegates.size());
        for (var entry : delegates.entrySet()) {
            var delegateKey = entry.getKey();
            var analyzer = entry.getValue();

            // Filter files by language extensions
            var languageExtensions = delegateKey.getExtensions();
            var relevantFiles = changedFiles.stream()
                    .filter(pf -> languageExtensions.contains(pf.extension()))
                    .collect(Collectors.toSet());

            if (relevantFiles.isEmpty()) {
                newDelegates.put(delegateKey, analyzer);
            } else {
                newDelegates.put(delegateKey, analyzer.update(relevantFiles));
            }
        }

        return new MultiAnalyzer(newDelegates, templateAnalyzers);
    }

    @SuppressWarnings("unchecked")
    public TreeSitterAnalyzer.AnalyzerState snapshotState() {
        Map<String, Set<CodeUnit>> mergedSymbolIndex = new HashMap<>();
        Map<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties> mergedCodeUnitState = new HashMap<>();
        Map<ProjectFile, TreeSitterAnalyzer.FileProperties> mergedFileState = new HashMap<>();
        NavigableSet<String> mergedSymbolKeys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        long maxEpochNanos = 0;

        if (delegates.isEmpty()) {
            return new TreeSitterAnalyzer.AnalyzerState(
                    HashTreePMap.empty(),
                    HashTreePMap.empty(),
                    HashTreePMap.empty(),
                    new TreeSitterAnalyzer.SymbolKeyIndex(
                            Collections.unmodifiableNavigableSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER))),
                    HashTreePMap.empty(),
                    System.nanoTime());
        }

        for (var delegate : delegates.values()) {
            if (delegate instanceof TreeSitterAnalyzer ts) {
                var state = ts.snapshotState();
                maxEpochNanos = Math.max(maxEpochNanos, state.snapshotEpochNanos());

                // Merge indices
                state.symbolIndex().forEach((symbol, units) -> {
                    mergedSymbolIndex
                            .computeIfAbsent(symbol, k -> new HashSet<>())
                            .addAll(units);
                    mergedSymbolKeys.add(symbol);
                });
                mergedCodeUnitState.putAll(state.codeUnitState());
                mergedFileState.putAll(state.fileState());

                // Process Angular component signals from metadata
                state.codeUnitState().forEach((cu, props) -> {
                    Object attr = props.attributes().get("angular.component");
                    if (attr instanceof Map<?, ?> angularInfo) {
                        Map<String, Object> payload = new HashMap<>((Map<String, Object>) angularInfo);
                        payload.put("hostClass", cu);
                        emitHostSignal("COMPONENT_FOUND", payload, state);
                    }
                });
            }
        }

        // Trigger analysis for all discovered templates
        for (var templateAnalyzer : templateAnalyzers) {
            // Use the symbol index to find host classes that might have templates
            for (var hostClass : mergedCodeUnitState.keySet()) {
                Set<ProjectFile> templateFiles = templateAnalyzer.getTemplateFiles(hostClass, getProject());
                for (var templateFile : templateFiles) {
                    analyzeTemplates(templateFile, hostClass);
                }
            }
        }

        // Aggregate results from template analyzers
        Map<ProjectFile, List<TemplateAnalysisResult>> aggregatedTemplateResults = new HashMap<>();
        for (var templateAnalyzer : templateAnalyzers) {
            var snapshot = templateAnalyzer.snapshotState();
            for (var result : snapshot) {
                aggregatedTemplateResults
                        .computeIfAbsent(result.templateFile(), k -> new ArrayList<>())
                        .add(result);
            }
        }

        if (maxEpochNanos == 0) {
            maxEpochNanos = System.nanoTime();
        }

        return new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.from(mergedSymbolIndex),
                HashTreePMap.from(mergedCodeUnitState),
                HashTreePMap.from(mergedFileState),
                new TreeSitterAnalyzer.SymbolKeyIndex(Collections.unmodifiableNavigableSet(mergedSymbolKeys)),
                HashTreePMap.from(aggregatedTemplateResults),
                maxEpochNanos);
    }

    /**
     * Emits a signal to all registered template analyzers.
     * Host analyzers should call this when they encounter structural patterns (like @Component)
     * that require template-side analysis.
     */
    public void emitHostSignal(String signal, Map<String, Object> payload, TreeSitterAnalyzer.AnalyzerState state) {
        for (var templateAnalyzer : templateAnalyzers) {
            try {
                templateAnalyzer.onHostSignal(signal, payload, state);
            } catch (Exception e) {
                log.error(
                        "Error routing signal {} to template analyzer {}", signal, templateAnalyzer.internalName(), e);
            }
        }
    }

    /**
     * Provides a summary of a template file using applicable template analyzers.
     */
    public Optional<String> summarizeTemplate(ProjectFile templateFile, ICoreProject projectRoot) {
        var extension = templateFile.extension();
        return templateAnalyzers.stream()
                .filter(ta -> ta.getSupportedExtensions().contains(extension))
                .map(ta -> ta.summarizeTemplate(templateFile, projectRoot))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Orchestrates the analysis of a template file using the appropriate guest analyzer.
     */
    public List<TemplateAnalysisResult> analyzeTemplates(ProjectFile templateFile, CodeUnit hostClass) {
        var extension = templateFile.extension();
        return templateAnalyzers.stream()
                .filter(ta -> ta.getSupportedExtensions().contains(extension))
                .flatMap(ta -> {
                    var hostAnalyzer = delegateFor(hostClass);
                    return hostAnalyzer.stream()
                            .map(iAnalyzer -> ta.analyzeTemplate(iAnalyzer, templateFile, hostClass));
                })
                .toList();
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return findFirst(analyzer -> analyzer.extractCallReceiver(reference));
    }

    @Override
    public boolean isTypeAlias(CodeUnit cu) {
        for (var delegate : delegates.values()) {
            try {
                var providerOpt = delegate.as(TypeAliasProvider.class);
                if (providerOpt.isPresent() && providerOpt.get().isTypeAlias(cu)) {
                    return true;
                }
            } catch (UnsupportedOperationException ignored) {
                // delegate doesn't implement capability
            }
        }
        return false;
    }

    /**
     * @return a copy of the delegates of this analyzer.
     */
    public Map<Language, IAnalyzer> getDelegates() {
        return Collections.unmodifiableMap(delegates);
    }

    public Collection<ITemplateAnalyzer> getTemplateAnalyzers() {
        return templateAnalyzers;
    }

    @Override
    public Optional<IAnalyzer> subAnalyzer(Language language) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.subAnalyzer(language).stream())
                .findAny();
    }

    @Override
    public List<CodeUnit> getDirectAncestors(CodeUnit cu) {
        return delegateFor(cu)
                .flatMap(analyzer -> analyzer.as(TypeHierarchyProvider.class))
                .map(provider -> provider.getDirectAncestors(cu))
                .orElse(List.of());
    }

    /**
     * Delegates test detection to the language-specific analyzer.
     *
     * <p>Returns {@code false} if no delegate exists for the file's language. This is consistent
     * with {@link IAnalyzer#containsTests} semantics where a negative result from an unsupported
     * analyzer is treated as "unknown" semantic detection.
     */
    @Override
    public boolean containsTests(ProjectFile file) {
        return delegateFor(file).map(delegate -> delegate.containsTests(file)).orElse(false);
    }

    @Override
    public Set<CodeUnit> getDirectDescendants(CodeUnit cu) {
        return delegateFor(cu)
                .flatMap(analyzer -> analyzer.as(TypeHierarchyProvider.class))
                .map(provider -> provider.getDirectDescendants(cu))
                .orElse(Set.of());
    }

    @Override
    public List<String> getTestModules(Collection<ProjectFile> files) {
        Map<Language, List<ProjectFile>> grouped =
                files.stream().collect(Collectors.groupingBy(f -> Languages.fromExtension(f.extension())));

        return grouped.entrySet().stream()
                .flatMap(entry -> {
                    Language lang = entry.getKey();
                    List<ProjectFile> groupFiles = entry.getValue();
                    IAnalyzer delegate = delegates.get(lang);
                    if (delegate != null) {
                        return delegate.getTestModules(groupFiles).stream();
                    }
                    return IAnalyzer.super.getTestModules(groupFiles).stream();
                })
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public Set<CodeUnit> testFilesToCodeUnits(Collection<ProjectFile> files) {
        Map<Language, List<ProjectFile>> grouped =
                files.stream().collect(Collectors.groupingBy(f -> Languages.fromExtension(f.extension())));

        return grouped.entrySet().stream()
                .flatMap(entry -> {
                    Language lang = entry.getKey();
                    List<ProjectFile> groupFiles = entry.getValue();
                    IAnalyzer delegate = delegates.get(lang);
                    if (delegate != null) {
                        return delegate.testFilesToCodeUnits(groupFiles).stream();
                    }
                    return IAnalyzer.super.testFilesToCodeUnits(groupFiles).stream();
                })
                .collect(Collectors.toSet());
    }
}
