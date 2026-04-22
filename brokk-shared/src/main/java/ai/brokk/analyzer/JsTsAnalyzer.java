package ai.brokk.analyzer;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.javascript.JsTsExportUsageExtractor;
import ai.brokk.analyzer.javascript.TsConfigPathsResolver;
import ai.brokk.analyzer.typescript.TypeScriptTreeSitterNodeTypes;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ResolvedReceiverCandidate;
import ai.brokk.project.ICoreProject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;

/**
 * Shared base class for JavaScript and TypeScript analyzers.
 * Centralizes module resolution and import analysis logic.
 */
public abstract class JsTsAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider {

    protected record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    public record MemberLookupKey(String ownerClassName, String memberName, boolean instanceReceiver) {}

    public record ExportResolutionKey(ProjectFile definingFile, String exportName, int maxReexportDepth) {}

    public record ExportSeed(ProjectFile file, String exportName) {}

    public record ReverseExportSeedKey(ProjectFile sourceFile, String sourceExportName) {}

    public record ExportResolutionData(
            Set<CodeUnit> targets, Set<ProjectFile> frontier, Set<String> externalFrontier) {}

    protected static final List<String> KNOWN_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    private static final Pattern ES6_IMPORT_PATTERN = Pattern.compile("from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern ES6_SIDE_EFFECT_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern CJS_REQUIRE_PATTERN = Pattern.compile("require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Set<String> JS_LOG_BARE_NAMES = Set.of("log", "warn", "error", "exception");
    private static final Set<String> JS_LOG_RECEIVER_NAMES = Set.of("log", "logger", "console");
    private static final Set<String> JS_LOG_METHOD_NAMES = Set.of("log", "warn", "error", "exception");
    private static final Set<String> CLONE_AST_IDENTIFIER_TYPES = Set.copyOf(new HashSet<>(List.of(
            IDENTIFIER, TypeScriptTreeSitterNodeTypes.IDENTIFIER, TypeScriptTreeSitterNodeTypes.PROPERTY_IDENTIFIER)));
    private static final Set<String> CLONE_AST_STRING_TYPES = Set.copyOf(new HashSet<>(List.of(
            STRING,
            TEMPLATE_STRING,
            TypeScriptTreeSitterNodeTypes.STRING,
            TypeScriptTreeSitterNodeTypes.TEMPLATE_STRING)));
    private static final Set<String> CLONE_AST_NUMBER_TYPES =
            Set.copyOf(new HashSet<>(List.of(NUMBER, TypeScriptTreeSitterNodeTypes.NUMBER)));
    private static final Set<String> CLONE_AST_IGNORED_TYPES = Set.of(
            TypeScriptTreeSitterNodeTypes.ACCESSIBILITY_MODIFIER,
            TypeScriptTreeSitterNodeTypes.MODIFIERS,
            TypeScriptTreeSitterNodeTypes.TYPE_PARAMETERS);

    private final Cache<ModulePathKey, Optional<ProjectFile>> moduleResolutionCache =
            Caffeine.newBuilder().maximumSize(10_000).build();

    private final Cache<Path, TsConfigPathsResolver> tsConfigResolverCache =
            Caffeine.newBuilder().maximumSize(64).build();
    private final Cache<ProjectFile, Map<MemberLookupKey, CodeUnit>> memberResolutionIndexCache =
            Caffeine.newBuilder().maximumSize(10_000).build();
    private final Cache<ExportResolutionKey, ExportResolutionData> exportResolutionCache =
            Caffeine.newBuilder().maximumSize(20_000).build();

    private volatile @Nullable Set<Path> absoluteProjectPathsCache;
    private volatile @Nullable Map<ProjectFile, Set<ProjectFile>> reverseReexportIndexCache;
    private volatile @Nullable Map<ReverseExportSeedKey, Set<ExportSeed>> reverseExportSeedIndexCache;
    private volatile @Nullable Map<String, Set<String>> heritageIndexCache;
    private volatile boolean importReverseIndexPrimed;

    protected JsTsAnalyzer(ICoreProject project, Language language) {
        super(project, language);
    }

    protected JsTsAnalyzer(ICoreProject project, Language language, ProgressListener listener) {
        super(project, language, listener);
    }

    protected JsTsAnalyzer(ICoreProject project, Language language, AnalyzerState state, ProgressListener listener) {
        this(project, language, state, listener, null);
    }

    protected JsTsAnalyzer(
            ICoreProject project,
            Language language,
            AnalyzerState state,
            ProgressListener listener,
            @Nullable AnalyzerCache cache) {
        super(project, language, state, listener, cache);
    }

    public TSLanguage tsLanguage() {
        return getTSLanguage();
    }

    @Override
    public List<ImportInfo> importInfoOf(ProjectFile file) {
        return super.importInfoOf(file);
    }

    /**
     * Computes and caches an index of exports for the given file, including ESM re-exports.
     */
    public ExportIndex exportIndexOf(ProjectFile file) {
        ExportIndex cached = cache().exportIndex().get(file);
        if (cached != null) {
            return cached;
        }

        ExportIndex computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return ExportIndex.empty();
                    }
                    return withSource(
                            file,
                            sc -> JsTsExportUsageExtractor.computeExportIndex(this, root, sc),
                            ExportIndex.empty());
                },
                ExportIndex.empty());

        cache().exportIndex().put(file, computed);
        return computed;
    }

    /**
     * Computes and caches a mapping of local import bindings for the given file.
     */
    public ImportBinder importBinderOf(ProjectFile file) {
        ImportBinder cached = cache().importBinder().get(file);
        if (cached != null) {
            return cached;
        }

        ImportBinder computed = JsTsExportUsageExtractor.computeImportBinder(importInfoOf(file));
        cache().importBinder().put(file, computed);
        return computed;
    }

    /**
     * Extracts flow-insensitive candidates for exported-symbol usage analysis, based on the given import binder.
     */
    public Set<ReferenceCandidate> exportUsageCandidatesOf(ProjectFile file, ImportBinder binder) {
        Set<ReferenceCandidate> cached = cache().references().get(file);
        if (cached != null) {
            return cached;
        }

        Set<ReferenceCandidate> computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) return Set.<ReferenceCandidate>of();
                    return withSource(
                            file,
                            sc -> JsTsExportUsageExtractor.computeExportUsageCandidates(this, file, root, sc, binder),
                            Set.<ReferenceCandidate>of());
                },
                Set.<ReferenceCandidate>of());

        cache().references().put(file, computed);
        return computed;
    }

    public Set<ResolvedReceiverCandidate> resolvedReceiverCandidatesOf(ProjectFile file, ImportBinder binder) {
        Set<ResolvedReceiverCandidate> cached = cache().receiverCandidates().get(file);
        if (cached != null) {
            return cached;
        }

        Set<ResolvedReceiverCandidate> computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return Set.<ResolvedReceiverCandidate>of();
                    }
                    return withSource(
                            file,
                            sc -> JsTsExportUsageExtractor.computeResolvedReceiverCandidates(
                                    this, file, root, sc, binder),
                            Set.<ResolvedReceiverCandidate>of());
                },
                Set.<ResolvedReceiverCandidate>of());
        cache().receiverCandidates().put(file, computed);
        return computed;
    }

    public Map<MemberLookupKey, CodeUnit> memberResolutionIndex(ProjectFile file) {
        return memberResolutionIndexCache.get(file, this::buildMemberResolutionIndex);
    }

    public ExportResolutionData cachedExportResolution(
            ProjectFile definingFile,
            String exportName,
            int maxReexportDepth,
            java.util.function.Supplier<ExportResolutionData> supplier) {
        return exportResolutionCache.get(
                new ExportResolutionKey(definingFile, exportName, maxReexportDepth), ignored -> supplier.get());
    }

    public Map<ProjectFile, Set<ProjectFile>> reverseReexportIndex() {
        Map<ProjectFile, Set<ProjectFile>> cached = reverseReexportIndexCache;
        if (cached != null) {
            return cached;
        }
        var computed = JsTsExportUsageExtractor.buildReverseReexportIndex(this);
        reverseReexportIndexCache = computed;
        return computed;
    }

    public Map<ReverseExportSeedKey, Set<ExportSeed>> reverseExportSeedIndex() {
        Map<ReverseExportSeedKey, Set<ExportSeed>> cached = reverseExportSeedIndexCache;
        if (cached != null) {
            return cached;
        }
        var computed = JsTsExportUsageExtractor.buildReverseExportSeedIndex(this);
        reverseExportSeedIndexCache = computed;
        return computed;
    }

    public Map<String, Set<String>> heritageIndex() {
        Map<String, Set<String>> cached = heritageIndexCache;
        if (cached != null) {
            return cached;
        }
        var computed = JsTsExportUsageExtractor.buildHeritageIndex(this);
        heritageIndexCache = computed;
        return computed;
    }

    public void ensureImportReverseIndexPopulated() throws InterruptedException {
        if (importReverseIndexPrimed) {
            return;
        }
        var providerOpt = as(ImportAnalysisProvider.class);
        if (providerOpt.isEmpty()) {
            importReverseIndexPrimed = true;
            return;
        }
        JsTsExportUsageExtractor.ensureImportReverseIndexPopulated(this, providerOpt.orElseThrow());
        importReverseIndexPrimed = true;
    }

    public boolean hasCachedReceiverCandidates(ProjectFile file) {
        return cache().receiverCandidates().get(file) != null;
    }

    private Map<MemberLookupKey, CodeUnit> buildMemberResolutionIndex(ProjectFile file) {
        var index = new java.util.HashMap<MemberLookupKey, CodeUnit>();
        for (CodeUnit declaration : getAllDeclarations()) {
            if (!declaration.source().equals(file)) {
                continue;
            }
            if (declaration.kind() != CodeUnitType.FIELD && declaration.kind() != CodeUnitType.FUNCTION) {
                continue;
            }
            String ownerName = ownerNameOf(declaration);
            if (ownerName.isEmpty()) {
                continue;
            }
            index.putIfAbsent(
                    new MemberLookupKey(ownerName, normalizedMemberName(declaration), !isStaticMember(declaration)),
                    declaration);
        }
        return Map.copyOf(index);
    }

    private static String ownerNameOf(CodeUnit codeUnit) {
        String shortName = codeUnit.shortName();
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return shortName.substring(0, lastDot);
    }

    private static String normalizedMemberName(CodeUnit codeUnit) {
        String identifier = codeUnit.identifier();
        int marker = identifier.indexOf('$');
        return marker >= 0 ? identifier.substring(0, marker) : identifier;
    }

    private static boolean isStaticMember(CodeUnit codeUnit) {
        return codeUnit.fqName().contains("$static");
    }

    /**
     * Best-effort module resolution for JS/TS ESM specifiers.
     */
    public Optional<ProjectFile> resolveEsmModule(ProjectFile importingFile, String moduleSpecifier) {
        return resolveEsmModuleOutcome(importingFile, moduleSpecifier).resolved();
    }

    public record ResolutionOutcome(Optional<ProjectFile> resolved, Optional<String> externalFrontier) {
        public static ResolutionOutcome resolved(ProjectFile file) {
            return new ResolutionOutcome(Optional.of(file), Optional.empty());
        }

        public static ResolutionOutcome external(String specifier) {
            return new ResolutionOutcome(Optional.empty(), Optional.of(specifier));
        }

        public static ResolutionOutcome empty() {
            return new ResolutionOutcome(Optional.empty(), Optional.empty());
        }
    }

    public ResolutionOutcome resolveEsmModuleOutcome(ProjectFile importingFile, String moduleSpecifier) {
        Path root = getProject().getRoot().normalize();
        Set<Path> absolutePaths = absoluteProjectPaths();

        if (moduleSpecifier.startsWith("./") || moduleSpecifier.startsWith("../")) {
            Path parentDir = importingFile.absPath().getParent();
            ProjectFile pf = resolveModulePathFromBase(root, absolutePaths, parentDir, moduleSpecifier);
            return pf != null ? ResolutionOutcome.resolved(pf) : ResolutionOutcome.empty();
        }

        TsConfigPathsResolver resolver = tsConfigResolverCache.get(root, TsConfigPathsResolver::new);
        TsConfigPathsResolver.Expansion expansion = resolver.expand(importingFile, moduleSpecifier);
        if (!expansion.hadAnyMapping()) {
            return ResolutionOutcome.external(moduleSpecifier);
        }

        for (String candidate : expansion.candidates()) {
            ProjectFile pf = resolveModulePathFromBase(root, absolutePaths, root, candidate);
            if (pf != null) {
                return ResolutionOutcome.resolved(pf);
            }
        }

        return ResolutionOutcome.external(moduleSpecifier);
    }

    @Override
    public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
        checkStale("commentDensity");
        String ext = cu.source().extension();
        if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) {
            return Optional.empty();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(cu.source(), COMMENT_NODE_TYPES);
        return Optional.of(buildRollUpStats(cu, counts));
    }

    @Override
    public List<CommentDensityStats> commentDensityByTopLevel(ProjectFile file) {
        checkStale("commentDensityByTopLevel");
        String ext = file.extension();
        if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) {
            return List.of();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(file, COMMENT_NODE_TYPES);
        List<CommentDensityStats> rows = new ArrayList<>();
        for (CodeUnit top : getTopLevelDeclarations(file)) {
            rows.add(buildRollUpStats(top, counts));
        }
        return List.copyOf(rows);
    }

    @Override
    protected String buildCloneAstSignature(String source) {
        return withFreshTree(source, "", tree -> {
            TSNode root = tree.getRootNode();
            if (root == null) {
                return "";
            }
            SourceContent sourceContent = SourceContent.of(source);
            var labels = new ArrayList<String>();
            try (var cursor = new TSTreeCursor(root)) {
                while (true) {
                    TSNode node = cursor.currentNode();
                    if (node == null) {
                        break;
                    }
                    labels.add(normalizeJsTsAstLabel(node, sourceContent));
                    if (!gotoNextDepthFirst(cursor, true)) {
                        break;
                    }
                }
            }
            return String.join("|", labels);
        });
    }

    @Override
    protected int refineCloneSimilarityPercent(
            CloneCandidateData left, CloneCandidateData right, int tokenSimilarity, CloneSmellWeights weights) {
        if (left.astSignature().isBlank() || right.astSignature().isBlank()) {
            return tokenSimilarity;
        }
        int astSimilarity = computeAstRefinementSimilarityPercent(left.astSignature(), right.astSignature());
        if (astSimilarity == 0) {
            return tokenSimilarity;
        }
        if (astSimilarity < weights.astSimilarityPercent()) {
            return 0;
        }
        return Math.min(tokenSimilarity, astSimilarity);
    }

    private static String normalizeJsTsAstLabel(TSNode node, SourceContent sourceContent) {
        String type = Objects.toString(node.getType(), "");
        String text = sourceContent.substringFrom(node).strip();
        if (CLONE_AST_IDENTIFIER_TYPES.contains(type)) {
            return "ID";
        }
        if (CLONE_AST_STRING_TYPES.contains(type)) {
            return "STR";
        }
        if (CLONE_AST_NUMBER_TYPES.contains(type)) {
            return "NUM";
        }
        if (TypeScriptTreeSitterNodeTypes.TRUE.equals(text)
                || TypeScriptTreeSitterNodeTypes.FALSE.equals(text)
                || TRUE.equals(text)
                || FALSE.equals(text)) {
            return "BOOL";
        }
        if (CLONE_AST_IGNORED_TYPES.contains(type)) {
            return "IGN";
        }
        return "N:" + type;
    }

    @Override
    public List<ExceptionHandlingSmell> findExceptionHandlingSmells(ProjectFile file, ExceptionSmellWeights weights) {
        checkStale("findExceptionHandlingSmells");
        ExceptionSmellWeights resolvedWeights = weights != null ? weights : ExceptionSmellWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file,
                            source -> detectExceptionHandlingSmells(file, root, source, resolvedWeights),
                            List.of());
                },
                List.of());
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        TSNode root = tree.getRootNode();
        if (root == null) {
            return false;
        }
        var calls = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CALL_EXPRESSION), calls);
        return calls.stream().anyMatch(call -> {
            String name = callExpressionName(call, sourceContent);
            return TEST_FUNCTION_NAMES.contains(name) && testCallback(call).isPresent();
        });
    }

    @Override
    public List<TestAssertionSmell> findTestAssertionSmells(ProjectFile file, TestAssertionWeights weights) {
        checkStale("findTestAssertionSmells");
        if (!containsTests(file)) {
            return List.of();
        }
        TestAssertionWeights resolvedWeights = weights != null ? weights : TestAssertionWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file, source -> detectTestAssertionSmells(file, root, source, resolvedWeights), List.of());
                },
                List.of());
    }

    private List<TestAssertionSmell> detectTestAssertionSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, TestAssertionWeights weights) {
        var calls = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CALL_EXPRESSION), calls);
        var findings = new ArrayList<TestSmellCandidate>();
        calls.stream()
                .filter(call -> Set.of(TEST_FN_TEST, TEST_FN_IT).contains(callExpressionName(call, sourceContent)))
                .forEach(call -> testCallback(call)
                        .ifPresent(callback ->
                                analyzeTestCallback(file, call, callback, sourceContent, weights, findings)));
        return findings.stream()
                .sorted(TEST_SMELL_CANDIDATE_COMPARATOR)
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private void analyzeTestCallback(
            ProjectFile file,
            TSNode testCall,
            TSNode callback,
            SourceContent sourceContent,
            TestAssertionWeights weights,
            List<TestSmellCandidate> out) {
        TSNode body = callback.getChildByFieldName(FIELD_BODY);
        if (body == null) {
            body = callback;
        }
        var calls = new ArrayList<TSNode>();
        collectNodesByType(body, Set.of(CALL_EXPRESSION), calls);
        List<AssertionSignal> assertions = calls.stream()
                .map(call -> assertionSignal(call, sourceContent, weights))
                .flatMap(Optional::stream)
                .toList();
        String enclosing = enclosingCodeUnit(
                        file,
                        testCall.getStartPoint().getRow(),
                        testCall.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        int assertionCount = assertions.size();
        if (assertionCount == 0) {
            addTestSmellCandidate(
                    file,
                    enclosing,
                    TEST_ASSERTION_KIND_NO_ASSERTIONS,
                    weights.noAssertionWeight(),
                    0,
                    List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                    sourceContent.substringFrom(testCall),
                    testCall.getStartByte(),
                    out);
            return;
        }
        assertions.stream()
                .filter(signal -> signal.baseScore() > 0)
                .forEach(signal -> addTestSmellCandidate(
                        file,
                        enclosing,
                        signal.kind(),
                        signal.baseScore(),
                        assertionCount,
                        signal.reasons(),
                        signal.excerpt(),
                        signal.startByte(),
                        out));
        boolean allShallow = assertions.stream().allMatch(AssertionSignal::shallow);
        if (allShallow) {
            int score = weights.shallowAssertionOnlyWeight()
                    - testMeaningfulAssertionCredit(assertions, weights, AssertionSignal::meaningful);
            if (score > 0) {
                addTestSmellCandidate(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_SHALLOW_ONLY,
                        score,
                        assertionCount,
                        List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                        sourceContent.substringFrom(testCall),
                        testCall.getStartByte(),
                        out);
            }
        }
    }

    private Optional<AssertionSignal> assertionSignal(
            TSNode call, SourceContent sourceContent, TestAssertionWeights weights) {
        TSNode function = call.getChildByFieldName(FIELD_FUNCTION);
        if (function == null) {
            return Optional.empty();
        }
        if (MEMBER_EXPRESSION.equals(function.getType())) {
            String property = memberPropertyName(function, sourceContent);
            Optional<TSNode> expectArg = expectArgument(function, sourceContent);
            if (expectArg.isPresent() && EXPECT_TERMINAL_NAMES.contains(property)) {
                return Optional.of(classifyExpectAssertion(call, property, expectArg.get(), sourceContent, weights));
            }
            if (MOCK_VERIFY_TERMINAL_NAMES.contains(property)) {
                return Optional.of(new AssertionSignal(
                        TEST_ASSERTION_KIND_MOCK_VERIFICATION,
                        0,
                        false,
                        true,
                        call.getStartByte(),
                        List.of(),
                        sourceContent.substringFrom(call)));
            }
            if (ASSERT.equals(memberObjectName(function, sourceContent))) {
                return Optional.of(classifyAssertCall(call, property, sourceContent, weights));
            }
        }
        return Optional.empty();
    }

    private AssertionSignal classifyExpectAssertion(
            TSNode call, String property, TSNode expectArg, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> args = argumentNodes(call);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean shallow = SHALLOW_EXPECT_TERMINAL_NAMES.contains(property);
        boolean meaningful = !shallow;
        String kind = TEST_ASSERTION_KIND_EXPECT;
        if ((TO_BE.equals(property) || TO_EQUAL.equals(property) || TO_STRICT_EQUAL.equals(property))
                && args.size() == 1) {
            TSNode actual = args.getFirst();
            if (isConstantExpression(expectArg) && isConstantExpression(actual)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if (sameExpression(expectArg, actual, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }
        if (SNAPSHOT_EXPECT_TERMINAL_NAMES.contains(property)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_SNAPSHOT);
            kind = TEST_ASSERTION_KIND_SNAPSHOT;
            meaningful = false;
        }
        if (shallow) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            meaningful = false;
        }
        if (containsOverspecifiedLiteral(args, sourceContent, weights)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
        }
        return new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                call.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(call));
    }

    private AssertionSignal classifyAssertCall(
            TSNode call, String property, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> args = argumentNodes(call);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean meaningful = true;
        String kind = TEST_ASSERTION_KIND_ASSERT;
        if ((TO_BE.equals(property) || TO_EQUAL.equals(property)) && args.size() >= 2) {
            TSNode expected = args.get(0);
            TSNode actual = args.get(1);
            if (isConstantExpression(expected) && isConstantExpression(actual)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if (sameExpression(expected, actual, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }
        return new AssertionSignal(
                kind,
                score,
                false,
                meaningful,
                call.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(call));
    }

    private static Optional<TSNode> testCallback(TSNode call) {
        return argumentNodes(call).stream()
                .filter(arg -> ARROW_FUNCTION.equals(arg.getType()) || FUNCTION_EXPRESSION.equals(arg.getType()))
                .findFirst();
    }

    private static String callExpressionName(TSNode call, SourceContent sourceContent) {
        TSNode function = call.getChildByFieldName(FIELD_FUNCTION);
        if (function == null) {
            return "";
        }
        if (IDENTIFIER.equals(function.getType())) {
            return sourceContent.substringFrom(function).strip();
        }
        if (MEMBER_EXPRESSION.equals(function.getType())) {
            return memberPropertyName(function, sourceContent);
        }
        return "";
    }

    private static String memberPropertyName(TSNode member, SourceContent sourceContent) {
        TSNode property = member.getChildByFieldName(FIELD_PROPERTY);
        return property == null ? "" : sourceContent.substringFrom(property).strip();
    }

    private static String memberObjectName(TSNode member, SourceContent sourceContent) {
        TSNode object = member.getChildByFieldName(FIELD_OBJECT);
        return object == null ? "" : sourceContent.substringFrom(object).strip();
    }

    private static Optional<TSNode> expectArgument(TSNode member, SourceContent sourceContent) {
        TSNode object = member.getChildByFieldName(FIELD_OBJECT);
        if (object == null || !CALL_EXPRESSION.equals(object.getType())) {
            return Optional.empty();
        }
        TSNode function = object.getChildByFieldName(FIELD_FUNCTION);
        if (function == null
                || !EXPECT.equals(sourceContent.substringFrom(function).strip())) {
            return Optional.empty();
        }
        return argumentNodes(object).stream().findFirst();
    }

    private static List<TSNode> argumentNodes(TSNode call) {
        TSNode arguments = call.getChildByFieldName(FIELD_ARGUMENTS);
        if (arguments == null) {
            arguments = firstNamedChildOfType(call, ARGUMENTS);
        }
        if (arguments == null) {
            return List.of();
        }
        var out = new ArrayList<TSNode>();
        for (int i = 0; i < arguments.getNamedChildCount(); i++) {
            TSNode child = arguments.getNamedChild(i);
            if (child != null) {
                out.add(child);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isConstantExpression(TSNode node) {
        return CONSTANT_LITERAL_TYPES.contains(node.getType());
    }

    private static boolean sameExpression(TSNode left, TSNode right, SourceContent sourceContent) {
        return sourceContent
                .substringFrom(left)
                .strip()
                .equals(sourceContent.substringFrom(right).strip());
    }

    private static boolean containsOverspecifiedLiteral(
            List<TSNode> args, SourceContent sourceContent, TestAssertionWeights weights) {
        return args.stream()
                .anyMatch(arg -> Set.of(STRING, TEMPLATE_STRING).contains(arg.getType())
                        && sourceContent.substringFrom(arg).length() >= weights.largeLiteralLengthThreshold());
    }

    private static @Nullable TSNode firstNamedChildOfType(TSNode node, String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null && type.equals(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private record AssertionSignal(
            String kind,
            int baseScore,
            boolean shallow,
            boolean meaningful,
            int startByte,
            List<String> reasons,
            String excerpt) {}

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var catches = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CATCH_CLAUSE), catches);
        return catches.stream()
                .map(catchClause -> analyzeCatchClause(file, catchClause, sourceContent, weights))
                .flatMap(Optional::stream)
                .sorted(java.util.Comparator.comparingInt(ExceptionHandlingSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(ExceptionHandlingSmell::enclosingFqName))
                .toList();
    }

    private Optional<ExceptionHandlingSmell> analyzeCatchClause(
            ProjectFile file, TSNode catchClause, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode bodyNode = catchClause.getChildByFieldName("body");
        if (bodyNode == null) {
            bodyNode = catchClause.getNamedChildren().stream()
                    .filter(child -> STATEMENT_BLOCK.equals(child.getType()))
                    .findFirst()
                    .orElse(null);
        }
        if (bodyNode == null) {
            return Optional.empty();
        }

        int bodyStatements = countBodyExpressions(bodyNode);
        String bodyText = sourceContent.substringFrom(bodyNode);
        boolean hasAnyComment = bodyText.contains("//") || bodyText.contains("/*");
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean throwPresent = hasDescendantOfType(bodyNode, THROW_STATEMENT);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !throwPresent;

        String catchType = extractCatchType(catchClause, sourceContent);
        int score = 0;
        var reasons = new ArrayList<String>();
        if (catchType.equals("<untyped>") || catchType.equals("any") || catchType.equals("<unknown>")) {
            score += weights.genericExceptionWeight();
            reasons.add("generic-catch:" + catchType);
        } else if (catchType.contains("Error") || catchType.contains("Exception")) {
            score += weights.genericRuntimeExceptionWeight();
            reasons.add("generic-catch:" + catchType);
        }
        if (emptyBody) {
            score += weights.emptyBodyWeight();
            reasons.add("empty-body");
        }
        if (commentOnlyBody) {
            score += weights.commentOnlyBodyWeight();
            reasons.add("comment-only-body");
        }
        if (smallBody) {
            score += weights.smallBodyWeight();
            reasons.add("small-body:" + bodyStatements);
        }
        if (logOnly) {
            score += weights.logOnlyWeight();
            reasons.add("log-only-body");
        }

        int creditStatements = Math.min(bodyStatements, Math.max(0, weights.meaningfulBodyStatementThreshold()));
        int bodyCredit = Math.max(0, weights.meaningfulBodyCreditPerStatement()) * creditStatements;
        if (bodyCredit > 0) {
            score -= bodyCredit;
            reasons.add("meaningful-body-credit:" + bodyCredit);
        }
        if (score <= 0) {
            return Optional.empty();
        }

        String enclosing = enclosingCodeUnit(
                        file,
                        catchClause.getStartPoint().getRow(),
                        catchClause.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        return Optional.of(new ExceptionHandlingSmell(
                file,
                enclosing,
                catchType,
                score,
                bodyStatements,
                List.copyOf(reasons),
                compactCatchExcerpt(sourceContent.substringFrom(catchClause))));
    }

    private static int countBodyExpressions(TSNode bodyNode) {
        int expressions = 0;
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode child = bodyNode.getNamedChild(i);
            if (child != null && CATCH_BODY_MEANINGFUL_STATEMENT_TYPES.contains(child.getType())) {
                expressions++;
            }
        }
        return expressions;
    }

    private static String extractCatchType(TSNode catchClause, SourceContent sourceContent) {
        TSNode parameterNode = catchClause.getChildByFieldName("parameter");
        if (parameterNode == null) {
            return "<untyped>";
        }
        String parameterText = sourceContent.substringFrom(parameterNode).strip();
        int colon = parameterText.indexOf(':');
        if (colon < 0) {
            return "<untyped>";
        }
        String type = parameterText.substring(colon + 1).strip();
        return type.isEmpty() ? "<untyped>" : type;
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode statement = firstNonCommentNamedChild(bodyNode, COMMENT_NODE_TYPES);
        if (statement == null || !EXPRESSION_STATEMENT.equals(statement.getType())) {
            return false;
        }
        TSNode call = findFirstNamedDescendant(statement, CALL_EXPRESSION);
        if (call == null) {
            return false;
        }
        TSNode functionNode = call.getChildByFieldName("function");
        if (functionNode == null) {
            return false;
        }
        if (IDENTIFIER.equals(functionNode.getType())) {
            String bare = sourceContent.substringFrom(functionNode).strip().toLowerCase(Locale.ROOT);
            return JS_LOG_BARE_NAMES.contains(bare);
        }
        if (!MEMBER_EXPRESSION.equals(functionNode.getType())) {
            return false;
        }
        TSNode objectNode = functionNode.getChildByFieldName("object");
        TSNode propertyNode = functionNode.getChildByFieldName("property");
        if (objectNode == null || propertyNode == null) {
            return false;
        }
        String receiver = sourceContent.substringFrom(objectNode).strip().toLowerCase(Locale.ROOT);
        String method = sourceContent.substringFrom(propertyNode).strip().toLowerCase(Locale.ROOT);
        boolean loggerLikeReceiver = JS_LOG_RECEIVER_NAMES.contains(receiver)
                || JS_LOG_RECEIVER_NAMES.stream().anyMatch(name -> receiver.endsWith("." + name));
        boolean loggerLikeMethod = JS_LOG_METHOD_NAMES.contains(method);
        return loggerLikeReceiver && loggerLikeMethod;
    }

    private static String compactCatchExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private CommentDensityStats buildRollUpStats(CodeUnit cu, Map<String, CommentLineBreakdown> counts) {
        CommentLineBreakdown own = counts.getOrDefault(cu.fqName(), new CommentLineBreakdown(0, 0));
        int span = rangesOf(cu).stream()
                .mapToInt(r -> r.endLine() - r.startLine() + 1)
                .sum();
        String path = cu.source().toString();
        if (!cu.isClass()) {
            return new CommentDensityStats(
                    cu.fqName(),
                    path,
                    own.headerLines(),
                    own.inlineLines(),
                    span,
                    own.headerLines(),
                    own.inlineLines(),
                    span);
        }
        int rh = own.headerLines();
        int ri = own.inlineLines();
        int rs = span;
        for (CodeUnit ch : getDirectChildren(cu)) {
            CommentDensityStats child = buildRollUpStats(ch, counts);
            rh += child.rolledUpHeaderCommentLines();
            ri += child.rolledUpInlineCommentLines();
            rs += child.rolledUpSpanLines();
        }
        return new CommentDensityStats(cu.fqName(), path, own.headerLines(), own.inlineLines(), span, rh, ri, rs);
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return performImportedCodeUnitsOf(file);
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return performReferencingFilesOf(file);
    }

    @Override
    public Set<String> relevantImportsFor(CodeUnit cu) {
        return getSource(cu, false)
                .map(source -> {
                    Set<String> codeIdentifiers = extractTypeIdentifiers(source);
                    List<ImportInfo> imports = importInfoOf(cu.source());

                    return imports.stream()
                            .filter(imp -> importMatchesAnyIdentifier(imp.rawSnippet(), codeIdentifiers))
                            .map(ImportInfo::rawSnippet)
                            .collect(Collectors.toSet());
                })
                .orElseGet(Set::of);
    }

    private boolean importMatchesAnyIdentifier(String importStatement, Set<String> codeIdentifiers) {
        Set<String> importIdentifiers = extractIdentifiersFromImport(importStatement);
        for (String id : importIdentifiers) {
            if (codeIdentifiers.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public abstract Set<String> extractIdentifiersFromImport(String importStatement);

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        // Handle ES6 imports
        TSNode importNode = capturedNodesForMatch.get(CaptureNames.IMPORT_DECLARATION);

        if (importNode != null) {
            localImportInfos.addAll(JsTsExportUsageExtractor.extractImportInfos(this, importNode, sourceContent));
        }

        // CommonJS require extraction
        extractCommonJsRequireImport(capturedNodesForMatch, sourceContent, localImportInfos);
    }

    @Override
    protected FileAnalysisAccumulator createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            FileAnalysisAccumulator acc) {
        if (localImportStatements.isEmpty()) {
            return acc;
        }

        String moduleShortName = file.getFileName();
        CodeUnit moduleCU = CodeUnit.module(file, modulePackageName, moduleShortName);

        if (acc.getByFqName(moduleCU.fqName()) != null) {
            return acc;
        }

        String importBlockSignature = String.join("\n", localImportStatements);
        var moduleRange = new Range(
                rootNode.getStartByte(),
                rootNode.getEndByte(),
                rootNode.getStartPoint().getRow(),
                rootNode.getEndPoint().getRow(),
                rootNode.getStartByte());

        acc.addTopLevel(moduleCU)
                .addSignature(moduleCU, importBlockSignature)
                .addRange(moduleCU, moduleRange)
                .setHasBody(moduleCU, true)
                .addSymbolIndex(moduleCU.identifier(), moduleCU)
                .addSymbolIndex(moduleCU.shortName(), moduleCU);
        return acc;
    }

    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        Path root = getProject().getRoot().normalize();
        Set<Path> absolutePaths = absoluteProjectPaths();

        var resolved = importStatements.stream()
                .map(JsTsAnalyzer::extractModulePathFromImport)
                .flatMap(Optional::stream)
                .flatMap(path -> resolveImportModule(file, path, root, absolutePaths).stream())
                .flatMap(resolvedFile -> {
                    Set<CodeUnit> decls = getDeclarations(resolvedFile);
                    if (!decls.isEmpty()) {
                        return decls.stream();
                    }
                    // Preserve module dependency edges for barrel files / re-export-only modules.
                    return Stream.of(CodeUnit.module(resolvedFile, "", resolvedFile.getFileName())
                            .withSynthetic(true));
                })
                .collect(Collectors.toSet());

        return Set.copyOf(resolved);
    }

    private Optional<ProjectFile> resolveImportModule(
            ProjectFile importingFile, String moduleSpecifier, Path root, Set<Path> absolutePaths) {
        if (moduleSpecifier.startsWith("./") || moduleSpecifier.startsWith("../")) {
            return moduleResolutionCache.get(
                    new ModulePathKey(importingFile, moduleSpecifier),
                    key -> Optional.ofNullable(
                            resolveJavaScriptLikeModulePath(root, absolutePaths, importingFile, moduleSpecifier)));
        }

        ResolutionOutcome out = resolveEsmModuleOutcome(importingFile, moduleSpecifier);
        return out.resolved();
    }

    @Override
    public boolean couldImportFile(ProjectFile sourceFile, List<ImportInfo> imports, ProjectFile target) {
        for (ImportInfo imp : imports) {
            Optional<String> modulePathOpt = extractModulePathFromImport(imp.rawSnippet());
            if (modulePathOpt.isEmpty()) {
                continue;
            }

            String modulePath = modulePathOpt.get();

            if (modulePath.startsWith("./") || modulePath.startsWith("../")) {
                if (couldModulePathMatchTarget(modulePath, target)) {
                    return true;
                }
                continue;
            }

            // TSConfig paths/baseUrl expansion: use actual module resolution to avoid false negatives.
            ResolutionOutcome out = resolveEsmModuleOutcome(sourceFile, modulePath);
            if (out.resolved().isPresent() && out.resolved().get().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private Set<Path> absoluteProjectPaths() {
        Set<Path> cached = absoluteProjectPathsCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = absoluteProjectPathsCache;
            if (cached == null) {
                cached = getProject().getAllFiles().stream()
                        .map(ProjectFile::absPath)
                        .collect(Collectors.toSet());
                absoluteProjectPathsCache = cached;
            }
            return cached;
        }
    }

    @Override
    public boolean couldImportFile(List<ImportInfo> imports, ProjectFile target) {
        // Prefer the 3-arg variant so we can use TSConfig context; keep this as a conservative fallback.
        for (ImportInfo imp : imports) {
            Optional<String> modulePathOpt = extractModulePathFromImport(imp.rawSnippet());
            if (modulePathOpt.isEmpty()) {
                continue;
            }
            String modulePath = modulePathOpt.get();
            if (modulePath.startsWith("./") || modulePath.startsWith("../")) {
                if (couldModulePathMatchTarget(modulePath, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a relative module path could resolve to the given target file.
     * This is a conservative text-based check that may have false positives but no false negatives.
     *
     * @param modulePath the module path from the import (e.g., "./utils/helper", "../models/User")
     * @param target the target ProjectFile to check
     * @return true if the module path could potentially resolve to the target
     */
    private boolean couldModulePathMatchTarget(String modulePath, ProjectFile target) {
        // Normalize the module path by removing leading ./ and ../ segments
        // We keep track of ".." but for matching purposes we just need the semantic path
        String normalizedPath = modulePath;
        while (normalizedPath.startsWith("./") || normalizedPath.startsWith("../")) {
            if (normalizedPath.startsWith("./")) {
                normalizedPath = normalizedPath.substring(2);
            } else if (normalizedPath.startsWith("../")) {
                normalizedPath = normalizedPath.substring(3);
            }
        }

        if (normalizedPath.isEmpty()) {
            return false;
        }

        // Get the target's relative path as a string with forward slashes
        String targetPath = target.getRelPath().toString().replace('\\', '/');

        // Check if the target path ends with the normalized import path
        // We need to check several variations:
        // 1. Direct match with extension: utils/helper matches utils/helper.ts
        // 2. Index file match: utils matches utils/index.ts

        // Strip known extensions from the import path if present
        String importBasePath = normalizedPath;
        for (String ext : KNOWN_EXTENSIONS) {
            if (importBasePath.endsWith(ext)) {
                importBasePath = importBasePath.substring(0, importBasePath.length() - ext.length());
                break;
            }
        }

        // Strip extension from target to get base path
        String targetBasePath = targetPath;
        for (String ext : KNOWN_EXTENSIONS) {
            if (targetBasePath.endsWith(ext)) {
                targetBasePath = targetBasePath.substring(0, targetBasePath.length() - ext.length());
                break;
            }
        }

        // Check 1: Direct path match (e.g., "utils/helper" matches "src/utils/helper")
        if (targetBasePath.endsWith(importBasePath)) {
            // Make sure we're matching a complete path segment
            int matchStart = targetBasePath.length() - importBasePath.length();
            if (matchStart == 0 || targetBasePath.charAt(matchStart - 1) == '/') {
                return true;
            }
        }

        // Check 2: Index file match (e.g., "utils" matches "utils/index")
        if (targetBasePath.endsWith("/index")) {
            String dirPath = targetBasePath.substring(0, targetBasePath.length() - "/index".length());
            if (dirPath.endsWith(importBasePath)) {
                int matchStart = dirPath.length() - importBasePath.length();
                if (matchStart == 0 || dirPath.charAt(matchStart - 1) == '/') {
                    return true;
                }
            }
        }

        return false;
    }

    public static Optional<String> extractModulePathFromImport(String importStatement) {
        Matcher es6Matcher = ES6_IMPORT_PATTERN.matcher(importStatement);
        if (es6Matcher.find()) return Optional.of(es6Matcher.group(1));

        Matcher sideEffectMatcher = ES6_SIDE_EFFECT_IMPORT_PATTERN.matcher(importStatement);
        if (sideEffectMatcher.find()) return Optional.of(sideEffectMatcher.group(1));

        Matcher cjsMatcher = CJS_REQUIRE_PATTERN.matcher(importStatement);
        if (cjsMatcher.find()) return Optional.of(cjsMatcher.group(1));

        return Optional.empty();
    }

    protected static @Nullable ProjectFile resolveJavaScriptLikeModulePath(
            Path projectRoot, Set<Path> absolutePaths, ProjectFile importingFile, String modulePath) {
        if (!modulePath.startsWith("./") && !modulePath.startsWith("../")) {
            return null;
        }
        Path parentDir = importingFile.absPath().getParent();
        return resolveModulePathFromBase(projectRoot, absolutePaths, parentDir, modulePath);
    }

    protected static @Nullable ProjectFile resolveModulePathFromBase(
            Path projectRoot, Set<Path> absolutePaths, @Nullable Path baseDir, String modulePath) {
        if (baseDir == null) {
            return null;
        }

        Path resolvedPath = baseDir.resolve(modulePath).normalize();
        String fileName = resolvedPath.getFileName().toString();

        if (KNOWN_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            if (absolutePaths.contains(resolvedPath) && resolvedPath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(resolvedPath));
            }
        }

        String baseName = fileName;
        for (String ext : KNOWN_EXTENSIONS) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        Path basePath = resolvedPath.resolveSibling(baseName);

        List<String> fileExtensions =
                Stream.concat(Stream.of(""), KNOWN_EXTENSIONS.stream()).toList();
        for (String ext : fileExtensions) {
            Path candidatePath = ext.isEmpty() ? basePath : basePath.resolveSibling(baseName + ext);
            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        List<String> indexFiles = List.of("index.js", "index.jsx", "index.ts", "index.tsx");
        for (String indexFile : indexFiles) {
            Path candidatePath = resolvedPath.resolve(indexFile);
            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        return null;
    }

    protected static void extractCommonJsRequireImport(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        // Check for both legacy and new split-query capture names
        TSNode requireCallNode = capturedNodesForMatch.get(REQUIRE_CALL_CAPTURE_NAME);
        if (requireCallNode == null) {
            requireCallNode = capturedNodesForMatch.get("module.require_call");
        }

        if (requireCallNode == null) {
            return;
        }

        // Identify the require function identifier to verify it's a 'require' call
        TSNode requireFuncNode = capturedNodesForMatch.get(REQUIRE_FUNC_CAPTURE_NAME);
        if (requireFuncNode == null) {
            requireFuncNode = capturedNodesForMatch.get("_require_func");
        }
        if (requireFuncNode == null) {
            requireFuncNode = capturedNodesForMatch.get("require_func");
        }

        boolean isRequire = false;
        if (requireFuncNode != null) {
            String funcName = sourceContent.substringFrom(requireFuncNode).strip();
            isRequire = "require".equals(funcName);
        } else {
            String text = sourceContent.substringFrom(requireCallNode).trim();
            isRequire = text.startsWith("require") || text.contains("require(");
        }

        if (isRequire) {
            TSNode nodeToCapture = requireCallNode;

            // Search upwards for the containing statement to capture the full 'require' assignment/usage
            TSNode current = requireCallNode;
            while (current != null) {
                String type = current.getType();
                if ("lexical_declaration".equals(type)
                        || "variable_declaration".equals(type)
                        || "expression_statement".equals(type)
                        || "variable_declarator".equals(type)) {
                    nodeToCapture = current;
                    // If we found a declarator, try one more step for the full declaration
                    TSNode parent = current.getParent();
                    if (parent != null
                            && Optional.ofNullable(parent.getType()).orElse("").contains("declaration")) {
                        nodeToCapture = parent;
                    }
                    break;
                }
                if ("program".equals(type)) {
                    break;
                }
                current = current.getParent();
            }

            String requireText = sourceContent.substringFrom(nodeToCapture).strip();
            if (!requireText.isEmpty()) {
                localImportInfos.add(new ImportInfo(requireText, false, null, null));
            }
        }
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return "constructor".equals(candidate.identifier());
    }

    @Override
    public int computeCyclomaticComplexity(CodeUnit cu) {
        Integer result = withTreeOf(
                cu.source(),
                tree -> {
                    List<Range> ranges = rangesOf(cu);
                    if (ranges.isEmpty()) return 1;

                    Range firstRange = ranges.getFirst();
                    TSNode root = tree.getRootNode();
                    if (root == null) return 1;
                    TSNode cuNode = root.getDescendantForByteRange(firstRange.startByte(), firstRange.endByte());

                    if (cuNode == null) return 1;

                    int complexity = 1;
                    Deque<TSNode> stack = new ArrayDeque<>();
                    stack.push(cuNode);

                    while (!stack.isEmpty()) {
                        TSNode node = stack.pop();
                        String type = node.getType();

                        if (type != null) {
                            switch (type) {
                                case IF_STATEMENT,
                                        FOR_STATEMENT,
                                        FOR_IN_STATEMENT,
                                        WHILE_STATEMENT,
                                        DO_STATEMENT,
                                        CATCH_CLAUSE,
                                        TERNARY_EXPRESSION -> complexity++;
                                case SWITCH_CASE -> {
                                    // Increment for 'case ...:', but not for 'default:'
                                    if (node.getChildByFieldName("value") != null) {
                                        complexity++;
                                    }
                                }
                                case BINARY_EXPRESSION -> {
                                    TSNode operatorNode = node.getChildByFieldName("operator");
                                    if (operatorNode != null) {
                                        String operator = operatorNode.getType(); // In JS/TS grammar,
                                        // operators are often
                                        // their own types
                                        if (operator != null
                                                && (operator.equals("&&")
                                                        || operator.equals("||")
                                                        || operator.equals("??"))) {
                                            complexity++;
                                        }
                                    }
                                }
                                default -> {}
                            }
                        }

                        for (int i = 0; i < node.getChildCount(); i++) {
                            TSNode child = node.getChild(i);
                            if (child != null) {
                                stack.push(child);
                            }
                        }
                    }

                    return complexity;
                },
                1);
        return result != null ? result : 1;
    }
}
