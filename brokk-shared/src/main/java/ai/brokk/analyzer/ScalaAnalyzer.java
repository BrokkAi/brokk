package ai.brokk.analyzer;

import static ai.brokk.analyzer.scala.ScalaTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.ICoreProject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
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
        collectNodesByType(root, Set.of(TRY_EXPRESSION), tryNodes);

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
        if (byField != null && CATCH_CLAUSE.equals(byField.getType())) {
            return byField;
        }
        for (int i = 0; i < tryExpression.getNamedChildCount(); i++) {
            TSNode child = tryExpression.getNamedChild(i);
            if (child != null && CATCH_CLAUSE.equals(child.getType())) {
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
        boolean hasAnyComment = hasDescendantOfAnyType(caseClause, Set.of(COMMENT, LINE_COMMENT, BLOCK_COMMENT));
        boolean throwPresent = handlerNodes.stream()
                .anyMatch(n -> THROW_EXPRESSION.equals(n.getType()) || hasDescendantOfType(n, THROW_EXPRESSION));
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
            if (TYPED_PATTERN.equals(type) || GUARD.equals(type)) {
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
            if (CASE_CLAUSE.equals(type)) {
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
        TSNode patternNode = caseClause.getChildByFieldName("pattern");
        if (patternNode == null) {
            // Fall back to the clause itself; this should still be useful in the markdown output.
            return sourceContent.substringFrom(caseClause).strip();
        }
        TSNode typedPattern = findFirstNamedDescendant(patternNode, TYPED_PATTERN);
        if (typedPattern == null) {
            return sourceContent.substringFrom(patternNode).strip();
        }
        TSNode typeNode = typedPattern.getChildByFieldName("type");
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
            if (COMMENT.equals(type) || LINE_COMMENT.equals(type) || BLOCK_COMMENT.equals(type)) {
                continue;
            }
            if (UNIT.equals(type)) {
                continue;
            }
            if (SPAN.equals(type) || BLOCK.equals(type) || INDENTED_BLOCK.equals(type)) {
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
            if (UNIT.equals(elementType)) {
                continue;
            }
            if (COMMENT.equals(elementType) || LINE_COMMENT.equals(elementType) || BLOCK_COMMENT.equals(elementType)) {
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
            if (SPAN.equals(type) || BLOCK.equals(type) || INDENTED_BLOCK.equals(type)) {
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
            if (CALL_EXPRESSION.equals(node.getType())) {
                call = node;
                break;
            }
            call = findFirstNamedDescendant(node, CALL_EXPRESSION);
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
        TSNode functionNode = callExpression.getChildByFieldName("function");
        if (functionNode == null) {
            return false;
        }
        if (!FIELD_EXPRESSION.equals(functionNode.getType())) {
            return false;
        }
        TSNode receiverNode = functionNode.getChildByFieldName("value");
        TSNode fieldNode = functionNode.getChildByFieldName("field");
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
        if (OBJECT_DEFINITION.equals(nodeType)) {
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
                PACKAGE_CLAUSE,
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
            Set.of(CLASS_DEFINITION, OBJECT_DEFINITION, INTERFACE_DEFINITION, ENUM_DEFINITION),
            Set.of(FUNCTION_DEFINITION),
            Set.of(VAL_DEFINITION, VAR_DEFINITION, SIMPLE_ENUM_CASE),
            Set.of("annotation", "marker_annotation"),
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
        if (!VAL_DEFINITION.equals(nodeType) && !VAR_DEFINITION.equals(nodeType)) {
            return super.formatFieldSignature(
                    fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
        }

        String trimmedSignature = signatureText.strip();

        // Single-name fast path: preserve the raw slice (modifiers, annotations) but truncate non-literals.
        TSNode patternNode = fieldNode.getChildByFieldName("pattern");
        if (patternNode != null && "identifier".equals(patternNode.getType())) {
            String patternText = sourceContent.substringFrom(patternNode).strip();
            if (patternText.equals(simpleName)) {
                TSNode valueNode = fieldNode.getChildByFieldName("value");
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
        String keyword = VAL_DEFINITION.equals(nodeType) ? "val" : "var";
        StringBuilder sb = new StringBuilder();
        sb.append(baseIndent);
        if (!exportPrefix.isEmpty()) {
            sb.append(exportPrefix);
        }
        sb.append(keyword).append(" ").append(simpleName);

        TSNode typeNode = fieldNode.getChildByFieldName("type");
        if (typeNode != null) {
            sb.append(": ").append(sourceContent.substringFrom(typeNode));
        }

        TSNode valueNode = fieldNode.getChildByFieldName("value");
        if (valueNode != null && isLiteral(valueNode)) {
            sb.append(" = ").append(sourceContent.substringFrom(valueNode));
        }

        return sb.toString();
    }

    private boolean isLiteral(TSNode node) {
        String type = node.getType();
        if (type == null) return false;
        return type.endsWith("_literal")
                || STRING.equals(type)
                || INTERPOLATED_STRING.equals(type)
                || INTERPOLATED_VERBATIM_STRING.equals(type)
                || BOOLEAN.equals(type)
                || CHARACTER.equals(type)
                || SYMBOL.equals(type)
                || NULL.equals(type);
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
                    return false;
                },
                false);
    }
}
