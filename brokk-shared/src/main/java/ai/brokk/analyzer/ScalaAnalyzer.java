package ai.brokk.analyzer;

import static ai.brokk.analyzer.scala.Constants.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.scala.CognitiveComplexityAnalysis;
import ai.brokk.project.ICoreProject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.treesitter.ScalaNodeField;
import org.treesitter.ScalaNodeType;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterScala;

public class ScalaAnalyzer extends TreeSitterAnalyzer implements JvmBasedAnalyzer {
    private static final Set<String> LOG_RECEIVER_BASE_NAMES = Set.of("log", "logger");
    private static final Set<String> LOG_METHOD_NAMES = Set.of("warn", "info", "error", "debug", "trace");

    public ScalaAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public ScalaAnalyzer(ICoreProject project, ProgressListener listener) {
        super(project, Languages.SCALA, listener);
    }

    private ScalaAnalyzer(
            ICoreProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.SCALA, state, listener, cache);
    }

    public static ScalaAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new ScalaAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new ScalaAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterScala();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/scala/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/scala/imports.scm");
            case IDENTIFIERS -> Optional.empty();
        };
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return SCALA_SYNTAX_PROFILE;
    }

    @Override
    public int computeCognitiveComplexity(CodeUnit cu) {
        if (!cu.isFunction()) return 0;
        return computeCognitiveComplexities(cu.source()).getOrDefault(cu, 0);
    }

    @Override
    public Map<CodeUnit, Integer> computeCognitiveComplexities(ProjectFile file) {
        Map<CodeUnit, Integer> result = withTreeOf(
                file,
                tree -> withSource(
                        file,
                        content -> {
                            var complexities = new LinkedHashMap<CodeUnit, Integer>();
                            for (CodeUnit cu : functionCodeUnitsInFile(file)) {
                                TSNode cuNode = primaryNodeForCodeUnit(tree, cu);
                                if (cuNode != null) {
                                    complexities.put(cu, CognitiveComplexityAnalysis.compute(cuNode, content));
                                }
                            }
                            return complexities;
                        },
                        Map.of()),
                Map.of());
        return result != null ? result : Map.of();
    }

    private List<CodeUnit> functionCodeUnitsInFile(ProjectFile file) {
        var functions = new ArrayList<CodeUnit>();
        var work = new ArrayDeque<>(getTopLevelDeclarations(file));
        while (!work.isEmpty()) {
            CodeUnit cu = work.pop();
            if (cu.isFunction()) {
                functions.add(cu);
            }
            work.addAll(getDirectChildren(cu));
        }
        return functions;
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

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var tryNodes = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(ScalaNodeType.TRY_EXPRESSION)), tryNodes);

        var findings = new ArrayList<ExceptionHandlingSmell>();
        for (TSNode tryNode : tryNodes) {
            TSNode catchClause = immediateCatchClause(tryNode);
            if (catchClause == null) {
                continue;
            }
            for (TSNode caseClause : topLevelCaseClauses(catchClause)) {
                analyzeCaseClause(file, caseClause, sourceContent, weights).ifPresent(findings::add);
            }
        }

        return findings.stream()
                .sorted(Comparator.comparingInt(ExceptionHandlingSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(ExceptionHandlingSmell::enclosingFqName))
                .toList();
    }

    private static @Nullable TSNode immediateCatchClause(TSNode tryExpression) {
        // Avoid descendant search: nested try/catch inside the try-body should not be attributed to this
        // try_expression.
        TSNode byField = tryExpression.getChildByFieldName("catch");
        if (byField != null && nodeType(ScalaNodeType.CATCH_CLAUSE).equals(byField.getType())) {
            return byField;
        }
        for (int i = 0; i < tryExpression.getNamedChildCount(); i++) {
            TSNode child = tryExpression.getNamedChild(i);
            if (child != null && nodeType(ScalaNodeType.CATCH_CLAUSE).equals(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private Optional<ExceptionHandlingSmell> analyzeCaseClause(
            ProjectFile file, TSNode caseClause, SourceContent sourceContent, ExceptionSmellWeights weights) {
        List<TSNode> handlerNodes = caseClauseHandlerNodes(caseClause);
        if (handlerNodes.isEmpty()) {
            return Optional.empty();
        }
        String catchType = extractCatchType(caseClause, sourceContent);
        boolean hasAnyComment = hasDescendantOfAnyType(
                caseClause,
                Set.of(nodeType(ScalaNodeType.COMMENT), LINE_COMMENT, nodeType(ScalaNodeType.BLOCK_COMMENT)));
        boolean throwPresent = handlerNodes.stream()
                .anyMatch(n -> nodeType(ScalaNodeType.THROW_EXPRESSION).equals(n.getType())
                        || hasDescendantOfType(n, nodeType(ScalaNodeType.THROW_EXPRESSION)));
        int bodyStatements = countMeaningfulHandlerStatements(handlerNodes, sourceContent);

        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(handlerNodes, sourceContent) && !throwPresent;

        int score = 0;
        var reasons = new ArrayList<String>();
        if (catchType.contains("Throwable")) {
            score += weights.genericThrowableWeight();
            reasons.add("generic-catch:Throwable");
        } else if (catchType.contains("Exception")) {
            if (catchType.contains("RuntimeException")) {
                score += weights.genericRuntimeExceptionWeight();
                reasons.add("generic-catch:RuntimeException");
            } else {
                score += weights.genericExceptionWeight();
                reasons.add("generic-catch:Exception");
            }
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
                        caseClause.getStartPoint().getRow(),
                        caseClause.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        String excerpt = compactExcerpt(sourceContent.substringFrom(caseClause));
        return Optional.of(new ExceptionHandlingSmell(
                file, enclosing, catchType, score, bodyStatements, List.copyOf(reasons), excerpt));
    }

    private static List<TSNode> caseClauseHandlerNodes(TSNode caseClause) {
        var named = new ArrayList<TSNode>();
        for (int i = 0; i < caseClause.getNamedChildCount(); i++) {
            TSNode child = caseClause.getNamedChild(i);
            if (child != null) {
                named.add(child);
            }
        }
        int start = 0;
        while (start < named.size()) {
            String type = named.get(start).getType();
            if (nodeType(ScalaNodeType.TYPED_PATTERN).equals(type)
                    || nodeType(ScalaNodeType.GUARD).equals(type)) {
                start++;
                continue;
            }
            break;
        }
        if (start >= named.size()) {
            return List.of();
        }
        return named.subList(start, named.size());
    }

    private static List<TSNode> topLevelCaseClauses(TSNode catchClause) {
        // We want only the case clauses that are part of the catch, not nested matches in the bodies.
        var cases = new ArrayList<TSNode>();
        var stack = new ArrayList<TSNode>();
        stack.add(catchClause);
        while (!stack.isEmpty()) {
            TSNode node = stack.removeLast();
            if (node == null) {
                continue;
            }
            String type = node.getType();
            if (nodeType(ScalaNodeType.CASE_CLAUSE).equals(type)) {
                cases.add(node);
                continue;
            }
            for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
                TSNode child = node.getNamedChild(i);
                if (child != null) {
                    stack.add(child);
                }
            }
        }
        return cases;
    }

    private static boolean hasDescendantOfAnyType(TSNode root, Set<String> types) {
        for (String type : types) {
            if (findFirstNamedDescendant(root, type) != null) {
                return true;
            }
        }
        return false;
    }

    private static String extractCatchType(TSNode caseClause, SourceContent sourceContent) {
        TSNode patternNode = caseClause.getChildByFieldName(nodeField(ScalaNodeField.PATTERN));
        if (patternNode == null) {
            // Fall back to the clause itself; this should still be useful in the markdown output.
            return sourceContent.substringFrom(caseClause).strip();
        }
        TSNode typedPattern = findFirstNamedDescendant(patternNode, nodeType(ScalaNodeType.TYPED_PATTERN));
        if (typedPattern == null) {
            return sourceContent.substringFrom(patternNode).strip();
        }
        TSNode typeNode = typedPattern.getChildByFieldName(nodeField(ScalaNodeField.TYPE));
        if (typeNode == null) {
            return sourceContent.substringFrom(typedPattern).strip();
        }
        return sourceContent.substringFrom(typeNode).strip();
    }

    private static int countMeaningfulHandlerStatements(List<TSNode> handlerNodes, SourceContent sourceContent) {
        int statements = 0;
        for (TSNode node : handlerNodes) {
            if (node == null) {
                continue;
            }
            String type = node.getType();
            if (type == null) {
                continue;
            }
            if (nodeType(ScalaNodeType.COMMENT).equals(type)
                    || LINE_COMMENT.equals(type)
                    || nodeType(ScalaNodeType.BLOCK_COMMENT).equals(type)) {
                continue;
            }
            if (nodeType(ScalaNodeType.UNIT).equals(type)) {
                continue;
            }
            if (SPAN.equals(type)
                    || nodeType(ScalaNodeType.BLOCK).equals(type)
                    || nodeType(ScalaNodeType.INDENTED_BLOCK).equals(type)) {
                statements += countTopLevelSpanElements(node);
                continue;
            }
            String text = sourceContent.substringFrom(node).strip();
            if (text.equals("()") || text.equals("{}")) {
                continue;
            }
            statements++;
        }
        return statements;
    }

    private static int countTopLevelSpanElements(TSNode node) {
        var elements = new ArrayList<TSNode>();
        collectTopLevelSpanElements(node, elements);
        int statements = 0;
        for (TSNode element : elements) {
            String elementType = element.getType();
            if (elementType == null) {
                continue;
            }
            if (nodeType(ScalaNodeType.UNIT).equals(elementType)) {
                continue;
            }
            if (nodeType(ScalaNodeType.COMMENT).equals(elementType)
                    || LINE_COMMENT.equals(elementType)
                    || nodeType(ScalaNodeType.BLOCK_COMMENT).equals(elementType)) {
                continue;
            }
            statements++;
        }
        return statements;
    }

    private static void collectTopLevelSpanElements(TSNode node, List<TSNode> out) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child == null) {
                continue;
            }
            String type = child.getType();
            if (SPAN.equals(type)
                    || nodeType(ScalaNodeType.BLOCK).equals(type)
                    || nodeType(ScalaNodeType.INDENTED_BLOCK).equals(type)) {
                collectTopLevelSpanElements(child, out);
                continue;
            }
            out.add(child);
        }
    }

    private static boolean isLikelyLogOnlyBody(List<TSNode> handlerNodes, SourceContent sourceContent) {
        TSNode call = null;
        for (TSNode node : handlerNodes) {
            if (node == null) {
                continue;
            }
            if (nodeType(ScalaNodeType.CALL_EXPRESSION).equals(node.getType())) {
                call = node;
                break;
            }
            call = findFirstNamedDescendant(node, nodeType(ScalaNodeType.CALL_EXPRESSION));
            if (call != null) {
                break;
            }
        }
        if (call == null) {
            return false;
        }
        return isLikelyLoggerCall(call, sourceContent);
    }

    private static boolean isLikelyLoggerCall(TSNode callExpression, SourceContent sourceContent) {
        TSNode functionNode = callExpression.getChildByFieldName(nodeField(ScalaNodeField.FUNCTION));
        if (functionNode == null) {
            return false;
        }
        if (!nodeType(ScalaNodeType.FIELD_EXPRESSION).equals(functionNode.getType())) {
            return false;
        }
        TSNode receiverNode = functionNode.getChildByFieldName(nodeField(ScalaNodeField.VALUE));
        TSNode fieldNode = functionNode.getChildByFieldName(nodeField(ScalaNodeField.FIELD));
        if (receiverNode == null || fieldNode == null) {
            return false;
        }
        String receiverText = sourceContent.substringFrom(receiverNode).strip().toLowerCase(Locale.ROOT);
        String methodText = sourceContent.substringFrom(fieldNode).strip().toLowerCase(Locale.ROOT);

        String receiverBase = receiverText;
        int lastDot = receiverText.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < receiverText.length()) {
            receiverBase = receiverText.substring(lastDot + 1);
        }
        return LOG_RECEIVER_BASE_NAMES.contains(receiverBase) && LOG_METHOD_NAMES.contains(methodText);
    }

    private static String compactExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file,
            String captureName,
            String simpleName,
            String packageName,
            String classChain,
            List<ScopeSegment> scopeChain,
            @Nullable TSNode definitionNode,
            SkeletonType skeletonType) {
        var effectiveSimpleName = simpleName;

        if (CaptureNames.CONSTRUCTOR_DEFINITION.equals(captureName)) {
            // This is a primary constructor, which is matched against the class name. This constructor is "implicit"
            // and needs to be created explicitly as follows.
            effectiveSimpleName = simpleName + "." + simpleName;
        } else if ("this".equals(simpleName) && skeletonType.equals(SkeletonType.FUNCTION_LIKE)) {
            // This is a secondary constructor, which is named `this`. The simple name should be the class name.
            // The classChain is the simple name of the enclosing class.
            if (!classChain.isEmpty()) {
                var lastDot = classChain.lastIndexOf('.');
                effectiveSimpleName = lastDot < 0 ? classChain : classChain.substring(lastDot + 1);
            }
        }

        String shortName;
        if (classChain.isEmpty()) {
            // If classChain is empty and it's a field/function, it's a top-level definition in the package.
            // We use the simple name directly.
            shortName = effectiveSimpleName;
        } else {
            shortName = classChain + "." + effectiveSimpleName;
        }

        var type =
                switch (skeletonType) {
                    case CLASS_LIKE -> CodeUnitType.CLASS;
                    case FUNCTION_LIKE -> CodeUnitType.FUNCTION;
                    case FIELD_LIKE -> CodeUnitType.FIELD;
                    case MODULE_STATEMENT -> CodeUnitType.MODULE;
                    default -> {
                        // This shouldn't be reached if captureConfiguration is exhaustive
                        log.warn("Unhandled CodeUnitType for '{}'", skeletonType);
                        yield CodeUnitType.CLASS;
                    }
                };

        return new CodeUnit(file, type, packageName, shortName);
    }

    @Override
    protected String determineClassName(@Nullable String nodeType, String shortName) {
        if (nodeType(ScalaNodeType.OBJECT_DEFINITION).equals(nodeType)) {
            // Companion objects append '$' on a bytecode level to avoid naming conflicts
            return shortName + "$";
        } else {
            return shortName;
        }
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        return JavaAnalyzer.determineJvmPackageName(
                rootNode,
                sourceContent,
                nodeType(ScalaNodeType.PACKAGE_CLAUSE),
                SCALA_SYNTAX_PROFILE.classLikeNodeTypes(),
                (node, sourceContent1) -> sourceContent1.substringFrom(node));
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}"; // We'll stick to Scala 2 closers
    }

    @Override
    protected boolean requiresSemicolons() {
        return false; // Scala does not require semicolons
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        return signatureText + " {"; // For consistency with closers, we need to open with Scala 2-style braces
    }

    @Override
    protected String bodyPlaceholder() {
        return "= {...}";
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            SourceContent sourceContent,
            String exportAndModifierPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        var paramSb = new StringBuilder();
        for (int i = 0; i < funcNode.getChildCount(); i++) {
            var child = funcNode.getChild(i);
            if (child != null && "parameters".equals(funcNode.getFieldNameForChild(i))) {
                paramSb.append(sourceContent.substringFrom(child));
            }
        }
        var allParamsText = paramSb.toString();

        var typeParams = typeParamsText.isEmpty() ? "" : typeParamsText;
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        return indent + exportAndModifierPrefix + "def " + functionName + typeParams + allParamsText + ": " + returnType
                + bodyPlaceholder();
    }

    private static final LanguageSyntaxProfile SCALA_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(
                    nodeType(ScalaNodeType.CLASS_DEFINITION),
                    nodeType(ScalaNodeType.OBJECT_DEFINITION),
                    nodeType(ScalaNodeType.TRAIT_DEFINITION),
                    nodeType(ScalaNodeType.ENUM_DEFINITION)),
            Set.of(nodeType(ScalaNodeType.FUNCTION_DEFINITION)),
            Set.of(
                    nodeType(ScalaNodeType.VAL_DEFINITION),
                    nodeType(ScalaNodeType.VAR_DEFINITION),
                    nodeType(ScalaNodeType.SIMPLE_ENUM_CASE)),
            Set.of(nodeType(ScalaNodeType.ANNOTATION), MARKER_ANNOTATION),
            Set.of(),
            IMPORT_DECLARATION,
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "return_type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.OBJECT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.TRAIT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.ENUM_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.LAMBDA_DEFINITION, SkeletonType.FUNCTION_LIKE),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
            );

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForScala(reference);
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return false;
    }

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String simpleName,
            String baseIndent,
            ProjectFile file) {
        String nodeType = fieldNode.getType();
        if (!nodeType(ScalaNodeType.VAL_DEFINITION).equals(nodeType)
                && !nodeType(ScalaNodeType.VAR_DEFINITION).equals(nodeType)) {
            return super.formatFieldSignature(
                    fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
        }

        String trimmedSignature = signatureText.strip();

        // Single-name fast path: preserve the raw slice (modifiers, annotations) but truncate non-literals.
        TSNode patternNode = fieldNode.getChildByFieldName(nodeField(ScalaNodeField.PATTERN));
        if (patternNode != null && nodeType(ScalaNodeType.IDENTIFIER).equals(patternNode.getType())) {
            String patternText = sourceContent.substringFrom(patternNode).strip();
            if (patternText.equals(simpleName)) {
                TSNode valueNode = fieldNode.getChildByFieldName(nodeField(ScalaNodeField.VALUE));
                String result = (exportPrefix.isEmpty() ? trimmedSignature : (exportPrefix + trimmedSignature));

                if (valueNode != null && !isLiteral(valueNode)) {
                    // Truncate non-literal initializer
                    int eqIndex = result.indexOf('=');
                    if (eqIndex > 0) {
                        result = result.substring(0, eqIndex).strip();
                    }
                }
                return baseIndent + result;
            }
        }

        // Multi-name pattern or fallback: reconstruct from AST fields.
        String keyword = nodeType(ScalaNodeType.VAL_DEFINITION).equals(nodeType) ? "val" : "var";
        StringBuilder sb = new StringBuilder();
        sb.append(baseIndent);
        if (!exportPrefix.isEmpty()) {
            sb.append(exportPrefix);
        }
        sb.append(keyword).append(" ").append(simpleName);

        TSNode typeNode = fieldNode.getChildByFieldName(nodeField(ScalaNodeField.TYPE));
        if (typeNode != null) {
            sb.append(": ").append(sourceContent.substringFrom(typeNode));
        }

        TSNode valueNode = fieldNode.getChildByFieldName(nodeField(ScalaNodeField.VALUE));
        if (valueNode != null && isLiteral(valueNode)) {
            sb.append(" = ").append(sourceContent.substringFrom(valueNode));
        }

        return sb.toString();
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

    private record ScalaTestBody(TSNode anchor, TSNode body) {}

    private record AssertionSignal(
            boolean shallow,
            boolean meaningful,
            boolean nullnessOnly,
            String kind,
            int score,
            List<String> reasons,
            String excerptSource,
            int startByte) {}

    private List<TestAssertionSmell> detectTestAssertionSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, TestAssertionWeights weights) {
        List<ScalaTestBody> testBodies = testBodiesOf(root, sourceContent);
        if (testBodies.isEmpty()) {
            // If we have positive test detection but cannot isolate individual test bodies, fall back to
            // analyzing the full file to avoid silent "unsupported" behavior.
            testBodies = List.of(new ScalaTestBody(root, root));
        }

        var candidates = new ArrayList<TestSmellCandidate>();
        for (ScalaTestBody testBody : testBodies) {
            String enclosing = enclosingCodeUnit(
                            file,
                            testBody.anchor().getStartPoint().getRow(),
                            testBody.anchor().getEndPoint().getRow())
                    .map(CodeUnit::fqName)
                    .orElse(file.toString());

            List<AssertionSignal> signals = assertionSignalsOf(testBody.body(), sourceContent, weights);
            int assertionCount = signals.size();

            if (assertionCount == 0) {
                addTestSmellCandidate(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_NO_ASSERTIONS,
                        weights.noAssertionWeight(),
                        0,
                        List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                        sourceContent.substringFrom(testBody.anchor()),
                        testBody.anchor().getStartByte(),
                        candidates);
                continue;
            }

            int shallowCount = 0;
            int nullnessCount = 0;

            for (AssertionSignal signal : signals) {
                if (signal.shallow()) {
                    shallowCount++;
                }
                if (signal.nullnessOnly()) {
                    nullnessCount++;
                }

                addTestSmellCandidate(
                        file,
                        enclosing,
                        signal.kind(),
                        signal.score(),
                        assertionCount,
                        signal.reasons(),
                        signal.excerptSource(),
                        signal.startByte(),
                        candidates);
            }

            if (nullnessCount == assertionCount) {
                addTestSmellCandidate(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_NULLNESS_ONLY,
                        weights.nullnessOnlyWeight(),
                        assertionCount,
                        List.of(TEST_ASSERTION_KIND_NULLNESS_ONLY),
                        sourceContent.substringFrom(testBody.body()),
                        testBody.body().getStartByte(),
                        candidates);
            }

            if (shallowCount == assertionCount) {
                int score = weights.shallowAssertionOnlyWeight()
                        - testMeaningfulAssertionCredit(signals, weights, AssertionSignal::meaningful);
                addTestSmellCandidate(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_SHALLOW_ONLY,
                        score,
                        assertionCount,
                        List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                        sourceContent.substringFrom(testBody.body()),
                        testBody.body().getStartByte(),
                        candidates);
            }
        }

        return candidates.stream()
                .sorted(TEST_SMELL_CANDIDATE_COMPARATOR)
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private List<ScalaTestBody> testBodiesOf(TSNode root, SourceContent sourceContent) {
        var out = new ArrayList<ScalaTestBody>();
        var seenBodies = new HashSet<Integer>();

        var callExpressions = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(ScalaNodeType.CALL_EXPRESSION)), callExpressions);
        for (TSNode call : callExpressions) {
            if (!callNameOf(call, sourceContent).filter("test"::equals).isPresent()) {
                continue;
            }
            TSNode body = firstBlockLikeDescendant(call);
            if (body != null && seenBodies.add(body.getStartByte())) {
                out.add(new ScalaTestBody(call, body));
            }
        }

        var infixExpressions = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(ScalaNodeType.INFIX_EXPRESSION)), infixExpressions);
        for (TSNode infix : infixExpressions) {
            TSNode body = flatSpecBodyFromInfix(infix, sourceContent);
            if (body != null && seenBodies.add(body.getStartByte())) {
                out.add(new ScalaTestBody(infix, body));
            }
        }

        var functions = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(ScalaNodeType.FUNCTION_DEFINITION)), functions);
        for (TSNode fn : functions) {
            if (!hasJUnitTestAnnotation(fn, sourceContent)) {
                continue;
            }
            TSNode body = firstBlockLikeChild(fn);
            if (body != null && seenBodies.add(body.getStartByte())) {
                out.add(new ScalaTestBody(fn, body));
            }
        }

        return out;
    }

    private static Optional<String> callNameOf(TSNode callExpression, SourceContent sourceContent) {
        TSNode functionNode = callExpression.getChildByFieldName(nodeField(ScalaNodeField.FUNCTION));
        if (functionNode != null && nodeType(ScalaNodeType.IDENTIFIER).equals(functionNode.getType())) {
            return Optional.of(sourceContent.substringFrom(functionNode).strip());
        }

        if (functionNode != null && nodeType(ScalaNodeType.FIELD_EXPRESSION).equals(functionNode.getType())) {
            TSNode field = functionNode.getChildByFieldName(nodeField(ScalaNodeField.FIELD));
            if (field != null && nodeType(ScalaNodeType.IDENTIFIER).equals(field.getType())) {
                return Optional.of(sourceContent.substringFrom(field).strip());
            }
            TSNode descendant = findFirstNamedDescendant(functionNode, nodeType(ScalaNodeType.IDENTIFIER));
            if (descendant != null) {
                return Optional.of(sourceContent.substringFrom(descendant).strip());
            }
        }

        TSNode first = callExpression.getNamedChildCount() > 0 ? callExpression.getNamedChild(0) : null;
        if (first != null && nodeType(ScalaNodeType.IDENTIFIER).equals(first.getType())) {
            return Optional.of(sourceContent.substringFrom(first).strip());
        }

        TSNode descendant = findFirstNamedDescendant(callExpression, nodeType(ScalaNodeType.IDENTIFIER));
        if (descendant == null) {
            return Optional.empty();
        }
        return Optional.of(sourceContent.substringFrom(descendant).strip());
    }

    private static @Nullable TSNode flatSpecBodyFromInfix(TSNode infixExpression, SourceContent sourceContent) {
        TSNode operator = operatorNodeOfInfix(infixExpression);
        if (operator == null) {
            return null;
        }
        String operatorText = sourceContent.substringFrom(operator).strip();
        if (!"in".equals(operatorText)) {
            return null;
        }
        return firstBlockLikeChild(infixExpression);
    }

    private static @Nullable TSNode operatorNodeOfInfix(TSNode infixExpression) {
        for (int i = 0; i < infixExpression.getNamedChildCount(); i++) {
            TSNode child = infixExpression.getNamedChild(i);
            if (child == null) {
                continue;
            }
            String type = child.getType();
            if (nodeType(ScalaNodeType.OPERATOR_IDENTIFIER).equals(type)
                    || nodeType(ScalaNodeType.IDENTIFIER).equals(type)) {
                return child;
            }
        }
        return null;
    }

    private boolean hasJUnitTestAnnotation(TSNode functionDefinition, SourceContent sourceContent) {
        var annotations = new ArrayList<TSNode>();
        collectNodesByType(
                functionDefinition, Set.of(nodeType(ScalaNodeType.ANNOTATION), MARKER_ANNOTATION), annotations);
        for (TSNode annotation : annotations) {
            TSNode nameNode = annotation.getChildByFieldName(nodeField(ScalaNodeField.NAME));
            if (nameNode == null || !TYPE_IDENTIFIER.equals(nameNode.getType())) {
                continue;
            }
            String name = sourceContent.substringFrom(nameNode).strip();
            if (TEST_ANNOTATIONS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private List<AssertionSignal> assertionSignalsOf(
            TSNode bodyNode, SourceContent sourceContent, TestAssertionWeights w) {
        var signals = new ArrayList<AssertionSignal>();

        var callExpressions = new ArrayList<TSNode>();
        collectNodesByType(bodyNode, Set.of(nodeType(ScalaNodeType.CALL_EXPRESSION)), callExpressions);
        for (TSNode call : callExpressions) {
            extractAssertionSignalFromCall(call, sourceContent, w).ifPresent(signals::add);
        }

        var infixExpressions = new ArrayList<TSNode>();
        collectNodesByType(bodyNode, Set.of(nodeType(ScalaNodeType.INFIX_EXPRESSION)), infixExpressions);
        for (TSNode infix : infixExpressions) {
            extractAssertionSignalFromScalatestInfix(infix, sourceContent, w).ifPresent(signals::add);
        }

        return signals;
    }

    private Optional<AssertionSignal> extractAssertionSignalFromCall(
            TSNode callExpression, SourceContent sourceContent, TestAssertionWeights w) {
        Optional<String> nameOpt = callNameOf(callExpression, sourceContent);
        if (nameOpt.isEmpty()) {
            return Optional.empty();
        }

        String name = nameOpt.get();
        return switch (name) {
            case "assert" -> Optional.of(signalFromScalaAssert(callExpression, sourceContent, w));
            case "assertTrue" ->
                Optional.of(signalFromUnaryBooleanCall(callExpression, sourceContent, true, w.constantTruthWeight()));
            case "assertFalse" ->
                Optional.of(signalFromUnaryBooleanCall(callExpression, sourceContent, false, w.constantTruthWeight()));
            case "assertEquals" -> Optional.of(signalFromBinaryEqualityCall(callExpression, sourceContent, w));
            case "assertNull", "assertNotNull" ->
                Optional.of(new AssertionSignal(
                        true,
                        false,
                        true,
                        "",
                        0,
                        List.of(),
                        sourceContent.substringFrom(callExpression),
                        callExpression.getStartByte()));
            default -> Optional.empty();
        };
    }

    private AssertionSignal signalFromUnaryBooleanCall(
            TSNode callExpression, SourceContent sourceContent, boolean expected, int constantTruthScore) {
        TSNode arg = nthNamedArgument(callExpression, 0);
        if (arg != null) {
            String text = sourceContent.substringFrom(arg).strip();
            if (String.valueOf(expected).equals(text)) {
                return new AssertionSignal(
                        true,
                        false,
                        false,
                        TEST_ASSERTION_KIND_CONSTANT_TRUTH,
                        constantTruthScore,
                        List.of(TEST_ASSERTION_KIND_CONSTANT_TRUTH),
                        sourceContent.substringFrom(callExpression),
                        callExpression.getStartByte());
            }
        }
        return new AssertionSignal(
                true,
                false,
                false,
                "",
                0,
                List.of(),
                sourceContent.substringFrom(callExpression),
                callExpression.getStartByte());
    }

    private AssertionSignal signalFromBinaryEqualityCall(
            TSNode callExpression, SourceContent sourceContent, TestAssertionWeights w) {
        TSNode left = nthNamedArgument(callExpression, 0);
        TSNode right = nthNamedArgument(callExpression, 1);
        if (left == null || right == null) {
            return new AssertionSignal(
                    true,
                    false,
                    false,
                    "",
                    0,
                    List.of(),
                    sourceContent.substringFrom(callExpression),
                    callExpression.getStartByte());
        }

        String leftText = sourceContent.substringFrom(left).strip();
        String rightText = sourceContent.substringFrom(right).strip();

        if (leftText.equals(rightText)) {
            return new AssertionSignal(
                    true,
                    false,
                    false,
                    TEST_ASSERTION_KIND_SELF_COMPARISON,
                    w.tautologicalAssertionWeight(),
                    List.of(TEST_ASSERTION_KIND_SELF_COMPARISON),
                    sourceContent.substringFrom(callExpression),
                    callExpression.getStartByte());
        }
        if (isLiteral(left) && isLiteral(right)) {
            return new AssertionSignal(
                    true,
                    false,
                    false,
                    TEST_ASSERTION_KIND_CONSTANT_EQUALITY,
                    w.constantEqualityWeight(),
                    List.of(TEST_ASSERTION_KIND_CONSTANT_EQUALITY),
                    sourceContent.substringFrom(callExpression),
                    callExpression.getStartByte());
        }
        if (nodeType(ScalaNodeType.NULL_LITERAL).equals(left.getType())
                || nodeType(ScalaNodeType.NULL_LITERAL).equals(right.getType())) {
            return new AssertionSignal(
                    true,
                    false,
                    true,
                    "",
                    0,
                    List.of(),
                    sourceContent.substringFrom(callExpression),
                    callExpression.getStartByte());
        }

        AssertionSignal overspecified = overspecifiedLiteralSignal(callExpression, sourceContent, w);
        if (overspecified != null) {
            return overspecified;
        }

        return new AssertionSignal(
                false,
                true,
                false,
                "",
                0,
                List.of(),
                sourceContent.substringFrom(callExpression),
                callExpression.getStartByte());
    }

    private AssertionSignal signalFromScalaAssert(
            TSNode callExpression, SourceContent sourceContent, TestAssertionWeights w) {
        TSNode condition = nthNamedArgument(callExpression, 0);
        if (condition == null) {
            return new AssertionSignal(
                    true,
                    false,
                    false,
                    "",
                    0,
                    List.of(),
                    sourceContent.substringFrom(callExpression),
                    callExpression.getStartByte());
        }

        String conditionText = sourceContent.substringFrom(condition).strip();
        if ("true".equals(conditionText) || "false".equals(conditionText)) {
            return new AssertionSignal(
                    true,
                    false,
                    false,
                    TEST_ASSERTION_KIND_CONSTANT_TRUTH,
                    w.constantTruthWeight(),
                    List.of(TEST_ASSERTION_KIND_CONSTANT_TRUTH),
                    sourceContent.substringFrom(callExpression),
                    callExpression.getStartByte());
        }

        if (nodeType(ScalaNodeType.INFIX_EXPRESSION).equals(condition.getType())) {
            return signalFromEqualityExpression(condition, sourceContent, w, callExpression.getStartByte());
        }

        TSNode descendantInfix = findFirstNamedDescendant(condition, nodeType(ScalaNodeType.INFIX_EXPRESSION));
        if (descendantInfix != null) {
            return signalFromEqualityExpression(descendantInfix, sourceContent, w, callExpression.getStartByte());
        }

        AssertionSignal overspecified = overspecifiedLiteralSignal(condition, sourceContent, w);
        if (overspecified != null) {
            return overspecified;
        }

        return new AssertionSignal(
                false,
                true,
                false,
                "",
                0,
                List.of(),
                sourceContent.substringFrom(callExpression),
                callExpression.getStartByte());
    }

    private AssertionSignal signalFromEqualityExpression(
            TSNode infixExpression, SourceContent sourceContent, TestAssertionWeights w, int startByte) {
        TSNode operatorNode = operatorNodeOfInfix(infixExpression);
        if (operatorNode == null) {
            return new AssertionSignal(
                    false, true, false, "", 0, List.of(), sourceContent.substringFrom(infixExpression), startByte);
        }
        String operator = sourceContent.substringFrom(operatorNode).strip();
        if (!"==".equals(operator) && !"!=".equals(operator)) {
            AssertionSignal overspecified = overspecifiedLiteralSignal(infixExpression, sourceContent, w);
            if (overspecified != null) {
                return overspecified;
            }
            return new AssertionSignal(
                    false, true, false, "", 0, List.of(), sourceContent.substringFrom(infixExpression), startByte);
        }

        TSNode left = infixExpression.getNamedChild(0);
        TSNode right = infixExpression.getNamedChild(infixExpression.getNamedChildCount() - 1);
        if (left == null || right == null) {
            return new AssertionSignal(
                    false, true, false, "", 0, List.of(), sourceContent.substringFrom(infixExpression), startByte);
        }

        String leftText = sourceContent.substringFrom(left).strip();
        String rightText = sourceContent.substringFrom(right).strip();
        if (isLiteral(left) && isLiteral(right)) {
            return new AssertionSignal(
                    true,
                    false,
                    false,
                    TEST_ASSERTION_KIND_CONSTANT_EQUALITY,
                    w.constantEqualityWeight(),
                    List.of(TEST_ASSERTION_KIND_CONSTANT_EQUALITY),
                    sourceContent.substringFrom(infixExpression),
                    startByte);
        }
        if (leftText.equals(rightText)) {
            return new AssertionSignal(
                    true,
                    false,
                    false,
                    TEST_ASSERTION_KIND_SELF_COMPARISON,
                    w.tautologicalAssertionWeight(),
                    List.of(TEST_ASSERTION_KIND_SELF_COMPARISON),
                    sourceContent.substringFrom(infixExpression),
                    startByte);
        }
        if (nodeType(ScalaNodeType.NULL_LITERAL).equals(left.getType())
                || nodeType(ScalaNodeType.NULL_LITERAL).equals(right.getType())) {
            return new AssertionSignal(
                    true, false, true, "", 0, List.of(), sourceContent.substringFrom(infixExpression), startByte);
        }

        AssertionSignal overspecified = overspecifiedLiteralSignal(infixExpression, sourceContent, w);
        if (overspecified != null) {
            return overspecified;
        }

        return new AssertionSignal(
                false, true, false, "", 0, List.of(), sourceContent.substringFrom(infixExpression), startByte);
    }

    private Optional<AssertionSignal> extractAssertionSignalFromScalatestInfix(
            TSNode infixExpression, SourceContent sourceContent, TestAssertionWeights w) {
        TSNode operatorNode = operatorNodeOfInfix(infixExpression);
        if (operatorNode == null) {
            return Optional.empty();
        }
        String operator = sourceContent.substringFrom(operatorNode).strip();
        if (!"shouldBe".equals(operator) && !"mustBe".equals(operator)) {
            return Optional.empty();
        }

        TSNode left = infixExpression.getNamedChild(0);
        TSNode right = infixExpression.getNamedChild(infixExpression.getNamedChildCount() - 1);
        if (left == null || right == null) {
            return Optional.empty();
        }

        String leftText = sourceContent.substringFrom(left).strip();
        String rightText = sourceContent.substringFrom(right).strip();
        if (leftText.equals(rightText)) {
            return Optional.of(new AssertionSignal(
                    true,
                    false,
                    false,
                    TEST_ASSERTION_KIND_SELF_COMPARISON,
                    w.tautologicalAssertionWeight(),
                    List.of(TEST_ASSERTION_KIND_SELF_COMPARISON),
                    sourceContent.substringFrom(infixExpression),
                    infixExpression.getStartByte()));
        }
        if (isLiteral(left) && isLiteral(right)) {
            return Optional.of(new AssertionSignal(
                    true,
                    false,
                    false,
                    TEST_ASSERTION_KIND_CONSTANT_EQUALITY,
                    w.constantEqualityWeight(),
                    List.of(TEST_ASSERTION_KIND_CONSTANT_EQUALITY),
                    sourceContent.substringFrom(infixExpression),
                    infixExpression.getStartByte()));
        }
        if (nodeType(ScalaNodeType.NULL_LITERAL).equals(left.getType())
                || nodeType(ScalaNodeType.NULL_LITERAL).equals(right.getType())) {
            return Optional.of(new AssertionSignal(
                    true,
                    false,
                    true,
                    "",
                    0,
                    List.of(),
                    sourceContent.substringFrom(infixExpression),
                    infixExpression.getStartByte()));
        }

        AssertionSignal overspecified = overspecifiedLiteralSignal(infixExpression, sourceContent, w);
        if (overspecified != null) {
            return Optional.of(overspecified);
        }

        return Optional.of(new AssertionSignal(
                false,
                true,
                false,
                "",
                0,
                List.of(),
                sourceContent.substringFrom(infixExpression),
                infixExpression.getStartByte()));
    }

    private @Nullable AssertionSignal overspecifiedLiteralSignal(
            TSNode node, SourceContent sourceContent, TestAssertionWeights w) {
        var strings = new ArrayList<TSNode>();
        collectNodesByType(
                node,
                Set.of(
                        nodeType(ScalaNodeType.STRING),
                        nodeType(ScalaNodeType.INTERPOLATED_STRING),
                        INTERPOLATED_VERBATIM_STRING),
                strings);
        for (TSNode stringNode : strings) {
            if (sourceContent.substringFrom(stringNode).length() > w.largeLiteralLengthThreshold()) {
                return new AssertionSignal(
                        true,
                        false,
                        false,
                        TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL,
                        w.overspecifiedLiteralWeight(),
                        List.of(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL),
                        sourceContent.substringFrom(node),
                        node.getStartByte());
            }
        }
        return null;
    }

    private static @Nullable TSNode nthNamedArgument(TSNode callExpression, int idx) {
        TSNode args = callExpression.getChildByFieldName(nodeType(ScalaNodeType.ARGUMENTS));
        if (args == null) {
            args = callExpression.getChildByFieldName(nodeField(ScalaNodeField.ARGUMENTS));
        }
        if (args == null) {
            for (int i = 0; i < callExpression.getNamedChildCount(); i++) {
                TSNode child = callExpression.getNamedChild(i);
                if (child == null) {
                    continue;
                }
                String type = child.getType();
                if (nodeType(ScalaNodeType.ARGUMENTS).equals(type)
                        || nodeField(ScalaNodeField.ARGUMENTS).equals(type)
                        || ARGUMENT_LIST.equals(type)
                        || ARGUMENTS_LIST.equals(type)) {
                    args = child;
                    break;
                }
            }
        }
        if (args == null) {
            int argIndex = 1 + idx;
            if (callExpression.getNamedChildCount() <= argIndex) {
                return null;
            }
            TSNode child = callExpression.getNamedChild(argIndex);
            if (child == null) {
                return null;
            }
            String type = child.getType();
            if (nodeType(ScalaNodeType.ARGUMENTS).equals(type)
                    || ARGUMENT_LIST.equals(type)
                    || ARGUMENTS_LIST.equals(type)) {
                return child.getNamedChildCount() > idx ? child.getNamedChild(idx) : null;
            }
            return child;
        }
        return args.getNamedChildCount() > idx ? args.getNamedChild(idx) : null;
    }

    private static @Nullable TSNode firstBlockLikeChild(TSNode node) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child == null) {
                continue;
            }
            String type = child.getType();
            if (nodeType(ScalaNodeType.BLOCK).equals(type)
                    || nodeType(ScalaNodeType.INDENTED_BLOCK).equals(type)
                    || BLOCK_EXPRESSION.equals(type)) {
                return child;
            }
        }
        return null;
    }

    private static @Nullable TSNode firstBlockLikeDescendant(TSNode node) {
        TSNode block = findFirstNamedDescendant(node, nodeType(ScalaNodeType.BLOCK));
        if (block != null) {
            return block;
        }
        TSNode indented = findFirstNamedDescendant(node, nodeType(ScalaNodeType.INDENTED_BLOCK));
        if (indented != null) {
            return indented;
        }
        return findFirstNamedDescendant(node, BLOCK_EXPRESSION);
    }

    private boolean isLiteral(TSNode node) {
        String type = node.getType();
        if (type == null) return false;
        return type.endsWith("_literal")
                || nodeType(ScalaNodeType.STRING).equals(type)
                || nodeType(ScalaNodeType.INTERPOLATED_STRING).equals(type)
                || INTERPOLATED_VERBATIM_STRING.equals(type)
                || BOOLEAN.equals(type)
                || nodeType(ScalaNodeType.CHARACTER_LITERAL).equals(type)
                || SYMBOL.equals(type)
                || nodeType(ScalaNodeType.NULL_LITERAL).equals(type);
    }

    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    private static final Set<String> TEST_INFIX_KEYWORDS = Set.of("in", "should", "must", "can");

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        var rootNode = tree.getRootNode();
        if (rootNode == null) return false;
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, rootNode, sourceContent.text());

                        TSQueryMatch match = new TSQueryMatch();
                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                TSNode node = capture.getNode();
                                if (node == null) continue;

                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                switch (captureName) {
                                    case "test.import", "test.call" -> {
                                        return true;
                                    }
                                    case "test.annotation" -> {
                                        String nodeText = sourceContent.substringFrom(node);
                                        if (TEST_ANNOTATIONS.contains(nodeText)) {
                                            return true;
                                        }
                                    }
                                    case "test.infix" -> {
                                        String nodeText = sourceContent.substringFrom(node);
                                        if (TEST_INFIX_KEYWORDS.contains(nodeText)) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fallback via direct syntax traversal (no string parsing).
                    var callExpressions = new ArrayList<TSNode>();
                    collectNodesByType(rootNode, Set.of(nodeType(ScalaNodeType.CALL_EXPRESSION)), callExpressions);
                    if (callExpressions.stream().anyMatch(call -> callNameOf(call, sourceContent)
                            .filter("test"::equals)
                            .isPresent())) {
                        return true;
                    }

                    var infixExpressions = new ArrayList<TSNode>();
                    collectNodesByType(rootNode, Set.of(nodeType(ScalaNodeType.INFIX_EXPRESSION)), infixExpressions);
                    if (infixExpressions.stream().anyMatch(infix -> {
                        TSNode op = operatorNodeOfInfix(infix);
                        return op != null
                                && TEST_INFIX_KEYWORDS.contains(
                                        sourceContent.substringFrom(op).strip());
                    })) {
                        return true;
                    }

                    var annotations = new ArrayList<TSNode>();
                    collectNodesByType(
                            rootNode, Set.of(nodeType(ScalaNodeType.ANNOTATION), MARKER_ANNOTATION), annotations);
                    return annotations.stream().anyMatch(annotation -> {
                        TSNode nameNode = annotation.getChildByFieldName(nodeField(ScalaNodeField.NAME));
                        return nameNode != null
                                && TYPE_IDENTIFIER.equals(nameNode.getType())
                                && TEST_ANNOTATIONS.contains(
                                        sourceContent.substringFrom(nameNode).strip());
                    });
                },
                false);
    }
}
